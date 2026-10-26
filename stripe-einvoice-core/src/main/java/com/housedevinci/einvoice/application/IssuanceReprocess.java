package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.LegalNumber;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one refused event through the pipeline again. <b>Privileged</b>, explicit, and never
 * scheduled (QUESTIONS 26, security design ruling P-03).
 *
 * <p><b>Why it exists.</b> A mapping refusal is final for a good reason: a finalised invoice's
 * fields are frozen, so re-running cannot change the verdict, and the sweeper must never re-pick
 * one. But an incomplete seller profile ({@code DEI-304}) or a wrong rule pack is a defect of the
 * <em>application</em>, not of the invoice, and every invoice refused while it was wrong would
 * otherwise need a new upstream event that the seller cannot cause. So the remedy is an operator
 * action with a name on it, not a retry.
 *
 * <p><b>What it is not.</b> Not a bypass: after the row is re-opened it calls {@link
 * IssuanceUnitOfWork#process(String)} and has no code of its own, so intake, routing, the
 * authoritative re-fetch, the preflight and the allocator all run in their ordinary order. Not
 * reachable from a schedule: the sweeper submits event ids to the worker, which calls {@code
 * process}, and neither knows this class exists. Not an HTTP endpoint, and never will be - the same
 * reasoning as the void (N-04): a library cannot authenticate anyone, so an auto-configured
 * reprocess endpoint would hand an unauthenticated caller the issuance pipeline. A host that wants
 * to expose it writes that endpoint itself, behind its own authorization.
 */
public final class IssuanceReprocess {

  private static final Logger log = LoggerFactory.getLogger(IssuanceReprocess.class);

  /** The state a reprocess starts from: the terminal a mapping refusal lands on. */
  public static final InboundState ELIGIBLE = InboundState.FAILED_MAPPING;

  /**
   * The one other start (design 5.2): an allocation stopped by {@code DEI-132} because the clock
   * was behind by more than a day. The operator fixes the clock, then re-runs the event; the re-run
   * goes through the same refusal, so a clock that is still wrong is refused again. Never an
   * "accept" release, and no other {@code FAILED_ISSUANCE} code qualifies.
   */
  public static final String ELIGIBLE_ISSUANCE_CODE = ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES;

  /**
   * True for the two terminals a reprocess may start from when the invoice holds no number. These
   * rows are re-opened to {@code RECEIVED}.
   */
  public static boolean eligible(InboundEvent event) {
    return event.state() == ELIGIBLE
        || (event.state() == InboundState.FAILED_ISSUANCE
            && ELIGIBLE_ISSUANCE_CODE.equals(event.lastCode()));
  }

  /**
   * True for the terminals a reprocess may start from when the invoice holds a number (D-SRE-2):
   * {@code FAILED_ISSUANCE} with any code, and {@code FAILED_MAPPING}. These rows are re-opened in
   * place, never to {@code RECEIVED}, and the run resumes the same number. Example: the archive was
   * down for four days, the row holding {@code INV-2026-000012} passed the retry ceiling; once the
   * archive is back, the reprocess resumes {@code INV-2026-000012} and writes {@code COMPLETED}. A
   * row still due is accepted too: its run is the sweeper's next run, taken now, under the same run
   * lock, with a name on it.
   */
  public static boolean eligibleNumbered(InboundEvent event) {
    return event.state() == InboundState.FAILED_ISSUANCE || event.state() == ELIGIBLE;
  }

  private final IssuanceUnitOfWork unitOfWork;
  private final InboundEventStore inbound;
  private final IssuanceReader reader;
  private final ReprocessLedger ledger;
  private final Clock clock;

  public IssuanceReprocess(
      IssuanceUnitOfWork unitOfWork,
      InboundEventStore inbound,
      IssuanceReader reader,
      ReprocessLedger ledger,
      Clock clock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.inbound = Objects.requireNonNull(inbound, "inbound");
    this.reader = Objects.requireNonNull(reader, "reader");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** What one privileged call concluded. Ids and codes only, like every other outcome. */
  public record Result(
      Disposition disposition,
      String eventId,
      InboundState state,
      String code,
      String legalNumber) {}

  public enum Disposition {
    /** The row was re-opened and the pipeline ran. Where it got to is on {@link Result#state()}. */
    REPROCESSED,

    /**
     * The event is not in {@link #ELIGIBLE}: a passed event, an already-reprocessed one, a totals
     * refusal, a routing refusal. Nothing was written and nothing ran.
     */
    REFUSED_NOT_ELIGIBLE,

    /**
     * A legal number exists for this event's invoice and there is nothing to resume: it is issued,
     * burned or voided. Or a number appeared between the read and the re-open to {@code RECEIVED},
     * and the re-open's own statement refused it (D-SRE-6). Nothing was re-opened or recorded.
     * Since the sweeper retry edges, a row whose invoice holds an <em>open</em> number is re-opened
     * in place and resumes that number instead of being refused here.
     */
    REFUSED_NUMBERED,

    /**
     * Another run of the same invoice holds its run lock (D-PR45-1, {@code DEI-217}). Nothing was
     * re-opened, recorded or run. Call again once that run has ended.
     */
    REFUSED_RUN_IN_PROGRESS
  }

  /**
   * Runs on the caller's own thread, so it never waits for capacity (D-ORPE-4): it takes one of the
   * permits every caller of {@link IssuanceUnitOfWork#process} shares, or refuses at once. Then it
   * takes the invoice's run lock <b>before</b> the re-open, so a re-open never lands under a run
   * that is still live, and runs the pipeline under that same lock.
   *
   * @throws EInvoiceException {@code DEI-200} when no event is recorded under that id; {@code
   *     DEI-102} when every run permit is in use, before anything is read or recorded
   */
  public Result reprocess(ReprocessRequest request) {
    Objects.requireNonNull(request, "request");
    try (RunPermits.Permit permit = unitOfWork.permits().tryAcquire()) {
      return reprocessPermitted(request);
    }
  }

  private Result reprocessPermitted(ReprocessRequest request) {
    String eventId = request.eventId();
    InboundEvent event =
        inbound
            .find(eventId)
            .orElseThrow(
                () ->
                    new EInvoiceException(
                        ErrorCodes.INBOUND_UNREADABLE,
                        "no inbound event is recorded under that id"));
    IssuanceUnitOfWork.Configuration configuration = unitOfWork.configuration();
    Optional<Issuance> existing =
        reader.findBySource(configuration.sellerId(), configuration.mode(), event.objectId());
    // D-SRE-6: "does this row hold a number?" is asked by invoice id only, across every seller and
    // mode. An instance of the other mode then sees a live number and re-opens the row in place;
    // asked with its own mode it would see none and re-open the row to RECEIVED.
    // In place only while the number is open: a voided or burned number has nothing to resume, and
    // an issued one nothing to finish. Those rows are refused, as before.
    Set<IssuanceState> states = reader.issuanceStates(event.objectId());
    boolean numbered = !states.isEmpty();
    boolean inPlace = eligibleNumbered(event) && states.stream().anyMatch(s -> classifyOpen(s));

    if (!inPlace && numbered && eligible(event)) {
      // The number is issued, burned or voided: nothing to resume, and a re-open to RECEIVED
      // would run the pipeline over a numbered invoice. Refused before anything is written.
      log.warn(
          "einvoice: reprocess of event {} refused: a legal number already exists for its invoice",
          eventId);
      return refused(
          Disposition.REFUSED_NUMBERED,
          event.eventId(),
          event.state(),
          ErrorCodes.ISSUANCE_ALREADY_CLAIMED,
          existing);
    }
    if (!inPlace && !(eligible(event) && !numbered)) {
      // Includes the second call after a success: the row is COMPLETED, so this is a stated no-op
      // carrying the number the first call produced, never a second run.
      return refused(
          Disposition.REFUSED_NOT_ELIGIBLE,
          event.eventId(),
          event.state(),
          event.lastCode(),
          existing);
    }
    Optional<Result> ran;
    try {
      ran =
          unitOfWork.runLocked(
              event, run -> reopenAndRun(run, request, event, configuration, inPlace));
    } catch (EInvoiceException e) {
      if (!ErrorCodes.SESSION_NOT_PINNED.equals(e.code())) {
        throw e;
      }
      // The run lock was lost, or could not be kept, before the re-open: nothing was recorded.
      log.warn(
          "einvoice: reprocess of event {} stopped: its run lock could not be kept ({})",
          eventId,
          e.code());
      throw e;
    }
    if (ran.isEmpty()) {
      // D-PR45-1: a run of this invoice is live. Nothing re-opened, nothing recorded.
      return refused(
          Disposition.REFUSED_RUN_IN_PROGRESS,
          eventId,
          event.state(),
          ErrorCodes.INBOUND_RUN_IN_PROGRESS,
          existing);
    }
    return ran.get();
  }

  /** Under the run lock: the re-open with its record, then the run (design row 4). */
  private Result reopenAndRun(
      IssuanceUnitOfWork run,
      ReprocessRequest request,
      InboundEvent event,
      IssuanceUnitOfWork.Configuration configuration,
      boolean inPlace) {
    String eventId = request.eventId();
    // A check, then the re-open on another connection: the re-open is still a conditional update
    // on the state, so a lock lost in between can only cost a run, never a second winner.
    run.assertRunHeld();
    OptionalLong requestSeq =
        inPlace
            // D-SRE-2, D-SRE-8: a numbered row stays FAILED_ISSUANCE (or goes there from
            // FAILED_MAPPING) with its code kept, conditional on the row as read.
            ? ledger.reopenInPlaceAndRecord(
                request, configuration.sellerId(), configuration.mode(), event, clock.instant())
            : ledger.reopenAndRecord(
                request,
                configuration.sellerId(),
                configuration.mode(),
                ErrorCodes.REPROCESS_REQUESTED,
                clock.instant());
    if (requestSeq.isEmpty() && !inPlace && !reader.issuanceStates(event.objectId()).isEmpty()) {
      // The re-open to RECEIVED refuses a numbered row in its own statement (D-SRE-6): a number
      // appeared after the read. Nothing was re-opened or recorded.
      log.warn(
          "einvoice: reprocess of event {} refused: a legal number exists for its invoice",
          eventId);
      return refused(
          Disposition.REFUSED_NUMBERED,
          eventId,
          event.state(),
          ErrorCodes.ISSUANCE_ALREADY_CLAIMED,
          reader.findBySource(configuration.sellerId(), configuration.mode(), event.objectId()));
    }
    if (requestSeq.isEmpty()) {
      // Another call moved the row between the read and the update. One winner, by the database's
      // own row lock, and the loser runs nothing and records nothing.
      var current = inbound.find(eventId);
      return refused(
          Disposition.REFUSED_NOT_ELIGIBLE,
          eventId,
          current.map(InboundEvent::state).orElse(event.state()),
          current.map(InboundEvent::lastCode).orElse(ErrorCodes.REPROCESS_REQUESTED),
          reader.findBySource(configuration.sellerId(), configuration.mode(), event.objectId()));
    }
    // The re-open and the record of who asked committed together (D9-03): from here on there is no
    // ordering of failures that leaves an event running again with nobody recorded as having asked
    // for it. The log line carries ids only; the actor and the reason are in the record.
    log.info("einvoice: event {} re-opened for reprocessing by {}", eventId, request.actor());

    long seq = requestSeq.getAsLong();
    IssuanceUnitOfWork.Outcome outcome;
    try {
      outcome = run.processHeld(eventId);
    } catch (EInvoiceException e) {
      // R-04: a run that threw still concludes, with the code that escaped, so an unconcluded
      // request means one thing only - a process that died - which is what makes DEI-276 readable.
      concludeQuietly(seq, event, configuration, e.code());
      throw e;
    } catch (RuntimeException e) {
      // An untyped failure is not the same failure as an unreadable inbound event (P2-02): the
      // record must not state a cause that did not happen.
      concludeQuietly(seq, event, configuration, ErrorCodes.REPROCESS_UNCLASSIFIED_FAILURE);
      throw e;
    }
    conclude(
        seq,
        event,
        configuration,
        Optional.of(outcome.state()),
        outcome.code(),
        outcome.legalNumber());
    return new Result(
        Disposition.REPROCESSED,
        outcome.eventId(),
        outcome.state(),
        outcome.code(),
        outcome.legalNumber());
  }

  private void conclude(
      long requestSeq,
      InboundEvent event,
      IssuanceUnitOfWork.Configuration configuration,
      Optional<InboundState> state,
      String code,
      String legalNumber) {
    ledger.conclude(
        requestSeq,
        new ReprocessLedger.ReprocessRecord.Outcome(
            event.eventId(),
            configuration.sellerId(),
            configuration.mode(),
            state,
            code,
            legalNumber),
        clock.instant());
  }

  /**
   * Concludes an escaped exception's disposition without letting a ledger failure replace it: the
   * original exception is what the caller must see, and a ledger that throws while recording it is
   * logged, never propagated in its place.
   */
  private void concludeQuietly(
      long requestSeq,
      InboundEvent event,
      IssuanceUnitOfWork.Configuration configuration,
      String code) {
    try {
      conclude(requestSeq, event, configuration, Optional.empty(), code, null);
    } catch (RuntimeException ledgerFailure) {
      log.error(
          "einvoice: failed to record the conclusion of reprocess request {} for event {}",
          requestSeq,
          event.eventId(),
          ledgerFailure);
    }
  }

  /** An open number: allocated, not yet issued, and not disposed. */
  private static boolean classifyOpen(IssuanceState state) {
    return switch (state) {
      case NUMBERED, ARCHIVING, FAILED_ARCHIVE -> true;
      case ISSUED, FAILED_VALIDATION, VOID_UNUSED -> false;
    };
  }

  private static Result refused(
      Disposition disposition,
      String eventId,
      InboundState state,
      String code,
      Optional<Issuance> existing) {
    return new Result(
        disposition,
        eventId,
        state,
        code,
        existing.map(Issuance::legalNumber).map(LegalNumber::value).orElse(null));
  }
}
