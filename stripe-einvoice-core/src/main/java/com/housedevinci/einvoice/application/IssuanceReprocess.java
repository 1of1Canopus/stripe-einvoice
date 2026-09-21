package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.LegalNumber;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
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

  /** The only state a reprocess may start from: the terminal a mapping refusal lands on. */
  public static final InboundState ELIGIBLE = InboundState.FAILED_MAPPING;

  private final IssuanceUnitOfWork unitOfWork;
  private final InboundEventStore inbound;
  private final IssuanceReader reader;
  private final FindingStore findings;
  private final Clock clock;

  public IssuanceReprocess(
      IssuanceUnitOfWork unitOfWork,
      InboundEventStore inbound,
      IssuanceReader reader,
      FindingStore findings,
      Clock clock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.inbound = Objects.requireNonNull(inbound, "inbound");
    this.reader = Objects.requireNonNull(reader, "reader");
    this.findings = Objects.requireNonNull(findings, "findings");
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
     * A legal number is already allocated for this event's invoice. A mapping refusal never
     * allocates one, so this means the number came from somewhere else, and re-running the pipeline
     * over a numbered invoice is exactly what this method must never do.
     */
    REFUSED_NUMBERED
  }

  /**
   * @throws EInvoiceException {@code DEI-200} when no event is recorded under that id
   */
  public Result reprocess(ReprocessRequest request) {
    Objects.requireNonNull(request, "request");
    String eventId = request.eventId();
    var event =
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

    if (event.state() != ELIGIBLE) {
      // Includes the second call after a success: the row is COMPLETED, so this is a stated no-op
      // carrying the number the first call produced, never a second run.
      return refused(
          Disposition.REFUSED_NOT_ELIGIBLE,
          event.eventId(),
          event.state(),
          event.lastCode(),
          existing);
    }
    if (existing.isPresent()) {
      // Defence in depth. A FAILED_MAPPING row has no number by construction - the preflight
      // refuses before the allocator - and "by construction" is not a control.
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
    if (!inbound.reopenForReprocess(eventId, ErrorCodes.REPROCESS_REQUESTED, clock.instant())) {
      // Another call moved the row between the read and the update. One winner, by the database's
      // own row lock, and the loser runs nothing at all.
      var current = inbound.find(eventId);
      return refused(
          Disposition.REFUSED_NOT_ELIGIBLE,
          eventId,
          current.map(e -> e.state()).orElse(event.state()),
          current.map(e -> e.lastCode()).orElse(ErrorCodes.REPROCESS_REQUESTED),
          reader.findBySource(configuration.sellerId(), configuration.mode(), event.objectId()));
    }
    // Recorded before the run, so a crash in the middle still leaves who asked and why. The
    // findings table is this module's operator-facing, never-deleted record; an acknowledgement is
    // already "who decided, when, why", so the request's justification goes there rather than into
    // a second table. Ids and codes only: the actor and reason are screened and bounded.
    findings.record(
        ComplianceFinding.of(
            configuration.sellerId(),
            configuration.mode(),
            ErrorCodes.REPROCESSED,
            eventId,
            clock.instant()));
    findings.acknowledge(
        configuration.sellerId(),
        configuration.mode(),
        ErrorCodes.REPROCESSED,
        eventId,
        request.justification(),
        clock.instant());
    log.info("einvoice: event {} re-opened for reprocessing by {}", eventId, request.actor());

    IssuanceUnitOfWork.Outcome outcome = unitOfWork.process(eventId);
    return new Result(
        Disposition.REPROCESSED,
        outcome.eventId(),
        outcome.state(),
        outcome.code(),
        outcome.legalNumber());
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
