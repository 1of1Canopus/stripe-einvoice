package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Hashes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.IssueClockException;
import com.housedevinci.einvoice.domain.LegalNumber;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.Totals;
import com.housedevinci.einvoice.domain.TotalsMismatchException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One sale, one legal document, or none - and a record either way.
 *
 * <p>This is the unit of work the rest of the module hangs on. It drives one durably recorded event
 * from arrival to a disposition, across two stores that cannot commit together, and its whole
 * design is about what a crash at each step leaves behind:
 *
 * <table>
 *   <caption>The phases and what a crash immediately after each one leaves</caption>
 *   <tr><th>Phase</th><th>Database</th><th>Archive</th><th>Recovery</th></tr>
 *   <tr><td>P1 claim</td><td>issuance {@code NUMBERED}</td><td>-</td>
 *       <td>resumed by {@code (seller, mode, stripe invoice id)}: the same number, never a new
 *       one</td></tr>
 *   <tr><td>P2 render and validate</td><td>-</td><td>-</td>
 *       <td>the re-render must be byte-identical; when it is not, the number burns with {@code
 *       DEI-244}; there is nothing to undo</td></tr>
 *   <tr><td>P3 write</td><td>{@code ARCHIVING} with the hash and key, before the PUT</td>
 *       <td>the object</td>
 *       <td>the retry's write-once PUT sees identical bytes and succeeds; an orphan object is one
 *       indexed query away because the row predicted it</td></tr>
 *   <tr><td>P4 issue</td><td>{@code ISSUED} + the chained disposition, one transaction</td>
 *       <td>-</td><td>a crash before the commit falls back to P3, byte-identical</td></tr>
 * </table>
 *
 * <p>"One sale, two invoices" is impossible because P1 is guarded by a unique constraint on {@code
 * (seller, mode, stripe invoice id)} and every retry resumes onto the existing row. "One number,
 * two documents" is impossible because the archive is write-once and the key carries the content
 * hash.
 *
 * <p><b>Arrival order decides nothing.</b> The object's own status from the authoritative re-fetch
 * does. A backwards transition is a no-op, a redelivery of an issued invoice returns the stored
 * document after checking it against its recorded hash, and an invoice that is not finalised yet is
 * parked and retried rather than invented.
 *
 * <p>No Spring here, and no JDBC: this is the application layer, and every dependency is a port.
 */
public final class IssuanceUnitOfWork {

  private static final Logger log = LoggerFactory.getLogger(IssuanceUnitOfWork.class);

  /**
   * The two event types this module acts on.
   *
   * <p>{@code credit_note.created} is deliberately absent: credit notes are not in this edition,
   * and a listener that receives a legal event and discards it is worse than no listener (D-13).
   */
  public static final Set<String> SUBSCRIBED_TYPES = Set.of("invoice.finalized", "invoice.paid");

  /**
   * The plain store in the instance the host builds: it is read ({@code find}, {@code due}) and
   * never written. In the per-run instance it is {@link InboundRunLock.HeldRun#inbound()}, so every
   * inbound write of a run proves the run lock first (D-PR45-1).
   */
  private final InboundEventStore inbound;

  private final InboundRunLock runLock;
  private final RunPermits permits;

  /** The run lock's proof in the per-run instance; refuses in the instance the host builds. */
  private final InboundRunLock.Proof runProof;

  private final StripeInvoiceSource source;
  private final NumberAllocator allocator;
  private final IssuanceReader reader;
  private final IssuanceWriter writer;
  private final IssuanceRowLock rowLock;
  private final DocumentRenderer renderer;
  private final DocumentValidator validator;
  private final DocumentDates dates;
  private final ArchiveStore archive;
  private final PreflightSupport preflightSupport;
  private final Clock clock;
  private final Configuration configuration;

  /**
   * True from a committed {@code DEI-219} conclusion until the next successful Stripe call of this
   * process (design "sweeper retry edges" section 5, D-SRE-3). One object, shared by the instance
   * the host builds and every per-run instance, so a refusal in one run and a success in another
   * meet here.
   */
  private final AtomicBoolean accessRefused;

  /**
   * Everything the unit of work needs that is not a port.
   *
   * @param stripeAccountId the account this seller is reached by; blank is the platform account.
   *     The seller is resolved from this and from Stripe's own {@code account} field, never from
   *     metadata or a customer field (D-01, D-10)
   * @param taxZone the seller's tax jurisdiction zone, required and with no default: BT-2 is
   *     derived in it, so an invoice finalised at 23:30 UTC lands in the day, month and quarter the
   *     seller's tax authority would put it in rather than the container's (D-15)
   * @param closedYearCutoff how long after a fiscal year ends a late invoice from that year may
   *     still be numbered; empty means no cut-off, which is the default because it is an accounting
   *     policy and not a security control
   */
  public record Configuration(
      String sellerId,
      String stripeAccountId,
      Mode mode,
      String series,
      boolean fiscalYearReset,
      ZoneId taxZone,
      String pinnedApiVersion,
      String rulePackVersion,
      Duration retryBackoff,
      Duration maxRetryBackoff,
      Optional<Duration> closedYearCutoff,
      boolean readBackAfterWrite) {

    public Configuration {
      Objects.requireNonNull(sellerId, "sellerId");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(taxZone, "taxZone");
      Objects.requireNonNull(closedYearCutoff, "closedYearCutoff");
      stripeAccountId = stripeAccountId == null ? "" : stripeAccountId;
      rulePackVersion = rulePackVersion == null ? "" : rulePackVersion;
      retryBackoff = retryBackoff == null ? Duration.ofMinutes(1) : retryBackoff;
      maxRetryBackoff = maxRetryBackoff == null ? Duration.ofHours(1) : maxRetryBackoff;
    }
  }

  /**
   * What one run concluded. Ids and codes only: no buyer field, no amount (checklist line 45).
   *
   * @param preflight what the renderer answered before the number was allocated, empty when the run
   *     stopped before the preflight - never silently absent when it answered {@code
   *     NOT_SUPPORTED}, which is the whole point of carrying it
   */
  public record Outcome(
      String eventId,
      InboundState state,
      String code,
      String legalNumber,
      Optional<PreflightReport.Verdict> preflight) {

    public Outcome {
      preflight = preflight == null ? Optional.empty() : preflight;
    }

    public Outcome(String eventId, InboundState state, String code, String legalNumber) {
      this(eventId, state, code, legalNumber, Optional.empty());
    }

    public Outcome withPreflight(PreflightReport.Verdict verdict) {
      return new Outcome(eventId, state, code, legalNumber, Optional.ofNullable(verdict));
    }
  }

  /**
   * The readers this module ships, by class name. {@code application} cannot import the adapter, so
   * a reader is recognised by its exact class name and by being loaded from the same code source as
   * this class (design review R1). A lambda, an anonymous class, a subclass, or a class of the same
   * name in another jar is foreign.
   */
  private static final Set<String> MODULE_DATE_READERS =
      Set.of("com.housedevinci.einvoice.adapter.validation.UblDocumentDates");

  /**
   * @param rowLock the lock one issuance row is held under from its ARCHIVING marker to its outcome
   *     (D-RTR-7). The module's {@code JdbcIssuanceStore} is one; pass the same store as {@code
   *     reader} and {@code writer}
   * @param dates the reader of the document's own dates, which the unit of work compares with the
   *     allocation before anything is archived, whatever {@code validator} answers (D-PR36-6). Pass
   *     {@code new UblDocumentDates()} from {@code adapter.validation}. Any other reader is logged
   *     as a WARN at construction, so at every startup: the guarantee EINVOICE-DATES then rests on
   *     that reader. A host that wires this class by hand writes the composition root, with the
   *     same trust as choosing the allocator.
   * @param runLock at most one run per Stripe invoice (D-PR45-1). Pass the module's {@code
   *     JdbcInboundRunLock} over the same unit of work as {@code inbound}
   * @param permits the bound on live runs, shared with every other caller of {@link #process}
   *     (D-ORPE-4): one instance per application, of {@code einvoice.issuance.concurrency}
   */
  public IssuanceUnitOfWork(
      InboundEventStore inbound,
      InboundRunLock runLock,
      RunPermits permits,
      StripeInvoiceSource source,
      NumberAllocator allocator,
      IssuanceReader reader,
      IssuanceWriter writer,
      IssuanceRowLock rowLock,
      DocumentRenderer renderer,
      DocumentValidator validator,
      DocumentDates dates,
      ArchiveStore archive,
      PreflightSupport preflightSupport,
      Clock clock,
      Configuration configuration) {
    this.inbound = Objects.requireNonNull(inbound, "inbound");
    this.runLock = Objects.requireNonNull(runLock, "runLock");
    this.permits = Objects.requireNonNull(permits, "permits");
    this.runProof =
        () -> {
          throw new EInvoiceException(
              ErrorCodes.SESSION_NOT_PINNED, "this step runs only under an inbound run lock");
        };
    this.accessRefused = new AtomicBoolean();
    this.source =
        new AccessWatchingSource(Objects.requireNonNull(source, "source"), this::accepted);
    this.allocator = Objects.requireNonNull(allocator, "allocator");
    this.reader = Objects.requireNonNull(reader, "reader");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.rowLock = Objects.requireNonNull(rowLock, "rowLock");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.dates = Objects.requireNonNull(dates, "dates");
    this.archive = Objects.requireNonNull(archive, "archive");
    this.preflightSupport = Objects.requireNonNull(preflightSupport, "preflightSupport");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    if (!isModuleReader(dates)) {
      log.warn(
          "einvoice: the document date reader {} is not the module's own. The guarantee"
              + " EINVOICE-DATES (the archived BT-2, BT-7, BT-8, BT-72 and BG-14 equal the"
              + " allocation) now rests on that class. Pass new UblDocumentDates() to keep the"
              + " module's check.",
          dates.getClass().getName());
    }
  }

  /**
   * The instance one run uses (D-PR45-1): every port of {@code base}, with the inbound store and
   * the proof of the run lock this run holds. Built per run; the construction checks do not run
   * again.
   */
  private IssuanceUnitOfWork(IssuanceUnitOfWork base, InboundRunLock.HeldRun held) {
    this.inbound = Objects.requireNonNull(held.inbound(), "held inbound");
    this.runLock = base.runLock;
    this.permits = base.permits;
    this.runProof = Objects.requireNonNull(held.proof(), "held proof");
    this.accessRefused = base.accessRefused;
    this.source = base.source;
    this.allocator = base.allocator;
    this.reader = base.reader;
    this.writer = base.writer;
    this.rowLock = base.rowLock;
    this.renderer = base.renderer;
    this.validator = base.validator;
    this.dates = base.dates;
    this.archive = base.archive;
    this.preflightSupport = base.preflightSupport;
    this.clock = base.clock;
    this.configuration = base.configuration;
  }

  /** Design review R1: the exact class name, and the same code source as this class. */
  static boolean isModuleReader(DocumentDates dates) {
    Class<?> type = dates.getClass();
    if (!MODULE_DATE_READERS.contains(type.getName())) {
      return false;
    }
    java.security.CodeSource ours = IssuanceUnitOfWork.class.getProtectionDomain().getCodeSource();
    java.security.CodeSource theirs = type.getProtectionDomain().getCodeSource();
    return ours != null
        && theirs != null
        && ours.getLocation() != null
        && ours.getLocation().equals(theirs.getLocation());
  }

  /**
   * Drives one recorded event as far as it can go, and records where it stopped.
   *
   * <p><b>One run per invoice</b> (D-PR45-1). The run takes a permit (waiting for one, holding no
   * connection), then the invoice's run lock, never waiting for it. Another run of the same invoice
   * holds it: this run writes nothing and answers the stored state with {@code DEI-217}; the
   * sweeper picks the row again later. A lock lost mid-run (the server or the network ended the
   * session) answers the stored state with {@code DEI-104}, logged at WARN; nothing more was
   * written. A failure to take the lock (pool timeout, outage) is thrown with its own code.
   */
  public Outcome process(String eventId) {
    try (RunPermits.Permit permit = permits.acquire()) {
      return processPermitted(eventId);
    }
  }

  /** {@link #process} once a permit is held: the worker's path and the reprocess's. */
  Outcome processPermitted(String eventId) {
    InboundEvent row = find(eventId);
    if (concluded(row)) {
      return outcome(row, Optional.empty());
    }
    try {
      return runLocked(row, run -> run.processHeld(eventId)).orElseGet(() -> busy(row));
    } catch (EInvoiceException e) {
      if (ErrorCodes.SESSION_NOT_PINNED.equals(e.code())) {
        return lockLost(row);
      }
      throw e;
    }
  }

  /** The permits every caller of {@link #process} shares, for the reprocess's host-thread path. */
  RunPermits permits() {
    return permits;
  }

  /**
   * Runs {@code work} on a per-run instance while this invoice's run lock is held; empty when
   * another run holds it. For {@link IssuanceReprocess}, which re-opens the row under the same lock
   * before it runs.
   */
  <T> Optional<T> runLocked(
      InboundEvent storedRow, java.util.function.Function<IssuanceUnitOfWork, T> work) {
    return runLock.whileHeld(storedRow, held -> work.apply(new IssuanceUnitOfWork(this, held)));
  }

  /** On a per-run instance: refuses with {@code DEI-104} when the run lock is lost. */
  void assertRunHeld() {
    runProof.assertHeld();
  }

  private InboundEvent find(String eventId) {
    return inbound
        .find(eventId)
        .orElseThrow(
            () ->
                new EInvoiceException(
                    ErrorCodes.INBOUND_UNREADABLE, "no inbound event is recorded under that id"));
  }

  private static boolean concluded(InboundEvent event) {
    return event.state().terminal()
        && !event.state().retryableTerminal()
        && !event.state().refused();
  }

  /** {@code DEI-217}: another run of this invoice holds the lock. Nothing was written. */
  private Outcome busy(InboundEvent row) {
    log.debug(
        "einvoice: event {} is not run now: another run of its invoice is in progress ({})",
        row.eventId(),
        ErrorCodes.INBOUND_RUN_IN_PROGRESS);
    return new Outcome(row.eventId(), row.state(), ErrorCodes.INBOUND_RUN_IN_PROGRESS, null);
  }

  /**
   * {@code DEI-104} (D-ORPE-2): the run lock could not be kept, or the connection path cannot keep
   * one. Never the busy code. The stored state is the answer; this run wrote nothing more.
   */
  private Outcome lockLost(InboundEvent row) {
    log.warn(
        "einvoice: the run of event {} stopped: its run lock was lost or could not be kept on one"
            + " database session ({}); nothing more was written and the row is retried after its"
            + " backoff",
        row.eventId(),
        ErrorCodes.SESSION_NOT_PINNED);
    InboundEvent stored;
    try {
      stored = inbound.find(row.eventId()).orElse(row);
    } catch (RuntimeException e) {
      stored = row;
    }
    return new Outcome(stored.eventId(), stored.state(), ErrorCodes.SESSION_NOT_PINNED, null);
  }

  /**
   * One run under the run lock, on the per-run instance: decide on the row read under the lock,
   * stamp the attempt, then the pipeline.
   */
  Outcome processHeld(String eventId) {
    InboundEvent read = find(eventId);
    if (concluded(read)) {
      return outcome(read, Optional.empty());
    }
    // D-ORPE-3: the stamp is this run's only + 1, and its time is when a successor may start if
    // this run dies. Every conclusion overwrites it.
    Optional<InboundEvent> stamped =
        inbound.stampAttempt(
            eventId, read.state(), clock.instant(), backoffAfter(read.attempts() + 1));
    if (stamped.isEmpty()) {
      return concludedElsewhere(read);
    }
    return pipeline(stamped.get());
  }

  private Outcome pipeline(InboundEvent event) {
    // D-PR9-F1, kept under the run lock: every inbound write before the number claim is a
    // compare-and-set on the state this run last read or wrote. Under the lock no other run moves
    // the row, so a compare that fails means a writer outside the lock: a bug, logged at WARN, and
    // this run writes nothing more.
    InboundState read = event.state();
    Optional<Outcome> refusal = route(event, read);
    if (refusal.isPresent()) {
      return refusal.get();
    }

    SourceInvoice invoice;
    try {
      invoice = source.fetchInvoice(event.objectId());
    } catch (EInvoiceException e) {
      // An outage, a rate limit, a 5xx, or a collection that would not read to exhaustion. None of
      // them is a verdict about the sale, so all of them are retryable up to the ceiling (I-08).
      // A refused key (DEI-219) is the exception: it does not improve with time, so it stops
      // with no retry until a success or a restart re-arms it (design section 5).
      return concludeBeforeFetch(
          read, event, InboundState.FAILED_FETCH, e.code(), backoffFor(event));
    }
    if (invoice.livemode() != (configuration.mode() == Mode.LIVE)) {
      // The event said one thing and the object says another. The authoritative answer wins, and
      // in this direction it is the one that keeps a test invoice out of the live series (D-01).
      return concludeBeforeFetch(
          read, event, InboundState.REFUSED_MODE, ErrorCodes.MODE_MISMATCH, null);
    }
    Optional<Outcome> fetchedElsewhere = step(event, read, InboundState.FETCHED);
    if (fetchedElsewhere.isPresent()) {
      return fetchedElsewhere.get();
    }
    // From here every write expects FETCHED.
    final InboundState fetched = InboundState.FETCHED;

    if (!invoice.finalised()) {
      // Out of order: invoice.paid can arrive before invoice.finalized. Parked, retried, never an
      // invented invoice and never a dropped event.
      return park(event, fetched);
    }
    Optional<Issuance> existing =
        reader.findBySource(configuration.sellerId(), configuration.mode(), invoice.id());
    Optional<Outcome> decided = existingDisposition(event, fetched, existing, invoice);
    if (decided.isPresent()) {
      return decided.get();
    }

    // The supply day: finalized_at in the tax zone. Since the issue date is the numbering day, this
    // is the date of the sale, never BT-2 (D-PS1-27). The closed-year cut-off is a rule about when
    // the sale happened, so it runs on this day (design 5.3).
    LocalDate supplyDay = supplyDay(invoice);
    try {
      Totals.reconcile(invoice.totals());
      refuseIfFiscalYearIsClosed(supplyDay);
    } catch (TotalsMismatchException mismatch) {
      // The values are on the exception and go no further than a debug line that is off by
      // default; the state, the code and the field name are what the operator sees.
      log.debug(
          "einvoice: totals mismatch on field {} (recomputed {}, upstream {})",
          mismatch.field(),
          mismatch.recomputed(),
          mismatch.upstream());
      return failFrom(fetched, event, InboundState.FAILED_TOTALS, mismatch.code(), null);
    } catch (EInvoiceException refused) {
      return failFrom(fetched, event, InboundState.FAILED_MAPPING, refused.code(), null);
    }
    if (!validator.canValidate()) {
      // D3-02: this is a fact about the application, known before it serves a single request, not
      // a fact about this invoice. Discovering it in P2, after the allocator has already run,
      // spends a legal number on a document that was never going to be validated by anything -
      // every invoice, for as long as the condition holds. No backoff: an operator has to add a
      // processor or change the configuration, and a sweep does not do that.
      return failFrom(
          fetched, event, InboundState.FAILED_ISSUANCE, ErrorCodes.XSLT_PROCESSOR_MISSING, null);
    }
    try {
      // D-RTR-7, R5: a connection path that cannot hold the row lock is refused before a number
      // is consumed. Retried, so the events go through once the pooler is fixed.
      rowLock.verifySessionPinning();
    } catch (EInvoiceException e) {
      return fail(event, InboundState.FAILED_ISSUANCE, e.code(), backoffFor(event));
    }
    if (!renderer.carriesSupplyDate()) {
      // D-PS1-44: the issue date is the numbering day, so a document without its supply date would
      // hide when the sale happened. Refused before a number exists, like the preflight's own
      // refusals; terminal, because the renderer will not learn the field by running again.
      return failFrom(
          fetched, event, InboundState.FAILED_MAPPING, ErrorCodes.SUPPLY_DATE_NOT_CARRIED, null);
    }

    // The preflight (D3-01). Every screen the render will apply, applied now, with no number in
    // existence: a buyer-controlled field this module cannot put on a document is refused here and
    // costs nothing. One MappingInput, constructed once and handed to both passes, so the two
    // cannot disagree about the invoice, the series or the issue date.
    MappingInput mapping =
        new MappingInput(invoice, seriesKey(supplyDay), supplyDay, configuration.rulePackVersion());
    PreflightReport report = preflight(mapping);
    if (report.refused()) {
      // FAILED_MAPPING, with the mapper's own code, and no NUMBERED row, chain entry or archive
      // object. Terminal with an empty successor set: a finalised invoice's fields are frozen, so
      // re-running changes nothing and the sweeper does not re-pick it.
      return failFrom(fetched, event, InboundState.FAILED_MAPPING, report.code(), null)
          .withPreflight(report.verdict());
    }
    if (report.verdict() == PreflightReport.Verdict.NOT_SUPPORTED) {
      // The compatibility path: allocation proceeds exactly as it did before this method existed,
      // and the fact is recorded rather than swallowed.
      preflightSupport.notSupported(
          configuration.sellerId(), configuration.mode(), renderer.getClass().getName());
    }
    // MAPPED now means what it says: the mapping ran, over every screen, and produced a document
    // model. Before the preflight existed this row went MAPPED as soon as the totals agreed, and
    // the mapping itself was first attempted after a number had been allocated.
    Optional<Outcome> mappedElsewhere = step(event, fetched, InboundState.MAPPED);
    if (mappedElsewhere.isPresent()) {
      return mappedElsewhere.get().withPreflight(report.verdict());
    }
    return issue(event, invoice, mapping).withPreflight(report.verdict());
  }

  /**
   * The renderer's answer, with a host implementation's own bug turned into a refusal.
   *
   * <p>Fail closed: a {@code RuntimeException} out of a preflight is the same bug that would come
   * out of the render a moment later, where it costs a legal number. No retry - a host port's own
   * bug does not improve by running again - and the cause is logged server-side only.
   */
  private PreflightReport preflight(MappingInput mapping) {
    try {
      return renderer.preflight(mapping);
    } catch (EInvoiceException refusal) {
      return PreflightReport.refused(refusal.code());
    } catch (RuntimeException e) {
      log.error("einvoice: the document renderer's preflight threw an unexpected exception", e);
      return PreflightReport.refused(ErrorCodes.PREFLIGHT_FAILED);
    }
  }

  /** P1 to P4: everything from the number onwards. */
  private Outcome issue(InboundEvent event, SourceInvoice invoice, MappingInput mapping) {
    // P1: the number. Every refusal that depends on data has already happened, so a number is
    // consumed only for an invoice this module has decided it can document. Wrapped like every
    // other phase (D2-01): unwrapped, a refusal here left the row MAPPED with attempts 0 and no
    // code, so the sweeper re-picked it immediately, for ever, and the retry ceiling was never
    // reached.
    // D-PR45-1: the first step that acts on the number. A run that lost its lock stops here. The
    // allocator's one number per invoice keeps the number safe in the window after this check.
    runProof.assertHeld();
    Issuance issuance;
    try {
      issuance =
          allocator.allocate(
              new AllocationRequest(
                  mapping.seriesKey().scope(),
                  invoice.id(),
                  invoice.accountId(),
                  invoice.number(),
                  invoice.finalizedAt(),
                  invoice.commonServicePeriod(),
                  configuration.rulePackVersion()));
    } catch (EInvoiceException e) {
      if (ErrorCodes.ISSUANCE_ALREADY_CLAIMED.equals(e.code())) {
        // The loser of a claim race is not a failure at all - it is a duplicate. The invoice is
        // finalized and paid together in the normal case, so this is the common shape, not an
        // edge case: two events, one winner, and the loser's job is done as soon as it can see
        // that. Re-read the winner's row rather than guess at its number.
        Optional<Issuance> winner =
            reader.findBySource(configuration.sellerId(), configuration.mode(), invoice.id());
        // V-03: the loser's disposition follows the winner's state through the same classification
        // the switch uses. COMPLETED is documented as "the issuance reached ISSUED", so a loser
        // that concluded COMPLETED behind a burned or voided winner recorded a sale with no
        // document on the success terminal.
        if (winner.isPresent() && classify(winner.get().state()).disposed()) {
          return terminalDisposition(event, Optional.empty(), winner.get());
        }
        return fail(
            event,
            InboundState.COMPLETED,
            ErrorCodes.ISSUANCE_ALREADY_CLAIMED,
            null,
            winner.map(Issuance::legalNumber).orElse(null));
      }
      // A backoff for the codes that can improve with time; none for the codes that cannot,
      // because a series that is not configured or has run out of numbers stays that way until an
      // operator acts, and reattempting it every sweep is the unbounded loop this fix removes.
      Duration retryIn = retryableAllocationFailure(e) ? backoffFor(event) : null;
      return fail(event, InboundState.FAILED_ISSUANCE, e.code(), retryIn);
    }

    // The same MappingInput the preflight approved, plus what the allocator fixed under the series
    // lock: the number, its series, and its dates. BT-2 comes from the allocation and nowhere else,
    // so a resumed run renders the same date the first run was given (D-PS1-27).
    DocumentInput input = DocumentInput.numbered(mapping, issuance, configuration.taxZone());

    // D-RTR-7: everything from here to the outcome runs under the row's archive lock, on the lock
    // holder's own connection. Held by another worker: retried later, nothing read or written. The
    // inbound row is written after the lock is released, so the holder never needs a second
    // connection.
    Optional<Pending> concluded;
    try {
      concluded =
          rowLock.whileHeld(issuance.id(), held -> underLock(invoice, issuance, input, held));
    } catch (EInvoiceException e) {
      Duration retryIn = transientStoreFailure(e.code()) ? backoffFor(event) : null;
      return fail(event, InboundState.FAILED_ISSUANCE, e.code(), retryIn);
    }
    if (concluded.isEmpty()) {
      // D-PR9-F2: the lock holder is usually another run of this same event, and it may conclude
      // before this write. So the in-flight answer is a compare-and-set from MAPPED, the state
      // this run left: when the holder already wrote its outcome, this run answers from it and
      // writes nothing over it. Example: A issues INV-2026-000001 and writes COMPLETED; B's
      // in-flight write finds COMPLETED and B returns COMPLETED with that number.
      return failFrom(
          InboundState.MAPPED,
          event,
          InboundState.FAILED_ISSUANCE,
          ErrorCodes.ISSUANCE_IN_FLIGHT,
          backoffFor(event));
    }
    return apply(event, concluded.get());
  }

  /**
   * P2 to P4 under the row lock. Every decision uses the row as read again here, under the lock
   * (D-RTR-7, R3.2): another worker may have issued, burned or let a void through between the
   * allocator's read and the lock.
   */
  private Pending underLock(
      SourceInvoice invoice, Issuance allocated, DocumentInput input, IssuanceRowLock.Held held) {
    Issuance issuance =
        held.reader()
            .findBySource(configuration.sellerId(), configuration.mode(), invoice.id())
            .filter(row -> row.id() == allocated.id())
            .orElseThrow(
                () ->
                    new EInvoiceException(
                        ErrorCodes.ISSUANCE_NOT_FOUND,
                        "the issuance row read under the lock is not the row that was allocated"));
    switch (classify(issuance.state())) {
      case REDELIVERY -> {
        return redeliveryPending(issuance, invoice);
      }
      case VOIDED, BURNED -> {
        return terminalPending(issuance);
      }
      case RESUME -> {
        // carries on below
      }
    }

    // QUESTIONS 38, D-RTR-1: a resumed row that recorded a hash may already have its document in
    // the archive. It looks before it renders, so a number whose valid document exists is never
    // burned, and a read that fails is never mistaken for an absent object.
    Optional<Pending> resumed = resumeFromArchive(invoice, issuance, held);
    if (resumed.isPresent()) {
      return resumed.get();
    }

    // P2: the exact bytes, and the validation of those exact bytes - never of a re-serialisation.
    // Both ports are always a host's own code in 0.1.0 - the writers are the next change - and a
    // bug in either (a null pointer, an XML library's own runtime exception) must not leave this
    // event MAPPED forever: that is exactly the D2-01 state re-picked immediately, for ever, and
    // never reaching a FAILED_* state the retry ceiling can govern. RuntimeException is caught
    // alongside this module's own exception type; the cause is logged server-side only (checklist
    // line 47), and the row gets a stable, generic code, never the cause.
    // Design review R2: one private array. It is hashed once, here, and no port ever holds it:
    // the validator, the date reader and the archive each get their own copy, so a port that keeps
    // its array and rewrites it later cannot change what is read, hashed, keyed or compared.
    byte[] archived;
    DocumentRenderer.RenderedDocument document;
    try {
      document = renderer.render(input);
      archived = document.bytes();
    } catch (EInvoiceException e) {
      return renderRefused(invoice, e.code(), backoffFor(held), held);
    } catch (RuntimeException e) {
      log.error("einvoice: the document renderer threw an unexpected exception", e);
      return renderRefused(invoice, ErrorCodes.RENDER_FAILED, null, held);
    }
    DocumentValidator.Report report;
    try {
      report = validator.validate(archived.clone(), input);
    } catch (EInvoiceException e) {
      return Pending.of(InboundState.FAILED_ISSUANCE, e.code(), backoffFor(held), null);
    } catch (RuntimeException e) {
      log.error("einvoice: the document validator threw an unexpected exception", e);
      return Pending.of(InboundState.FAILED_ISSUANCE, ErrorCodes.VALIDATOR_FAILED, null, null);
    }
    if (report.archivable()) {
      // D-PR36-6: the last gate before the archive, and not the validator's job. Whatever
      // validator is wired, the dates in these bytes are compared with the allocation here.
      report = checkDates(archived, input);
    }
    if (!report.archivable()) {
      // D9-02: the rule id is a host port's own string. It is normalised once, here at the
      // boundary, and the normalised value is what the issuance row, the inbound event and the
      // chained disposition all carry - so no later statement can refuse it and strand a number.
      String ruleId = com.housedevinci.einvoice.domain.RuleIds.normalise(report.ruleId());
      String code =
          report.verdict() == DocumentValidator.Verdict.NOT_EVALUATED
              ? ErrorCodes.VALIDATION_NOT_EVALUATED
              : ErrorCodes.VALIDATION_REFUSED;
      // A refusal leaves no file and no ISSUED row. The number stays allocated with its state
      // recorded, and an operator voids it with this rule id - which is why the rule id is stored
      // rather than left to be inferred. A void that landed while this run was in flight makes the
      // write illegal, and then the event concludes on that code instead of this one.
      Optional<WriteRefusal> refused =
          concludeIssuance(held, invoice.id(), IssuanceState.FAILED_VALIDATION, ruleId);
      if (refused.isPresent()) {
        return pendingOn(refused.get(), issuance.legalNumber(), held);
      }
      if (issuance.documentHash().isPresent()) {
        // D-RTR-6: two hashes now describe this number, and only the operator can tell them apart.
        // Hashes and ids only, never content.
        log.warn(
            "einvoice: number {} was refused on a resumed run ({}) and is burned. Its recorded"
                + " hash {} names the first bytes, which passed and were not in the archive at the"
                + " resumed read; the refused bytes hash to {}",
            issuance.legalNumber().value(),
            ruleId,
            issuance.documentSha256(),
            Hashes.sha256Hex(archived));
      }
      return new Pending(
          InboundState.FAILED_ISSUANCE, code, ruleId, null, issuance.legalNumber().value());
    }

    String documentSha256 = Hashes.sha256Hex(archived);
    ArchiveKey key =
        ArchiveKey.of(
            issuance.seriesKey(), issuance.legalNumber(), documentSha256, document.extension());
    if (issuance.documentHash().isPresent()
        && (!issuance.documentSha256().equals(documentSha256)
            || !issuance.archiveKey().equals(key.value()))) {
      return renderNotReproducible(invoice, issuance, documentSha256, key, held);
    }
    return archiveAndIssue(invoice, documentSha256, key, Optional.of(archived), held);
  }

  /**
   * A resumed run rendered bytes that passed every gate, but they are not the document the row
   * recorded before its first archive write (D-RTR-5): another hash, or another extension and so
   * another key. The hash and key are write-once, so the new bytes can never be archived under this
   * number, and the recorded ones were never stored. The number burns with {@code DEI-244}, the
   * recorded hash and key stay on the row and in the chained event, and the new hash goes to the
   * WARN line only. Before any store call, so {@code markArchiving} keeps {@code DEI-240} for every
   * other caller. A void that landed while the run rendered wins, as on every burn.
   */
  private Pending renderNotReproducible(
      SourceInvoice invoice,
      Issuance issuance,
      String documentSha256,
      ArchiveKey key,
      IssuanceRowLock.Held held) {
    String code = ErrorCodes.RENDER_NOT_REPRODUCIBLE;
    Optional<WriteRefusal> refused =
        concludeIssuance(held, invoice.id(), IssuanceState.FAILED_VALIDATION, code);
    if (refused.isPresent()) {
      return pendingOn(refused.get(), issuance.legalNumber(), held);
    }
    // Hashes, keys and the number only, never content.
    log.warn(
        "einvoice: number {} is burned with {}: a resumed run rendered bytes that passed but are"
            + " not the recorded document, and nothing was archived. Recorded hash {} at key {};"
            + " the new bytes hash to {} at key {}. Make the renderer byte-stable, or drain open"
            + " numbers before a renderer, profile or mapping change",
        issuance.legalNumber().value(),
        code,
        issuance.documentSha256(),
        issuance.archiveKey(),
        documentSha256,
        key.value());
    return new Pending(
        InboundState.FAILED_ISSUANCE, code, code, null, issuance.legalNumber().value());
  }

  /**
   * What a resumed row with a recorded hash concludes before anything is rendered (QUESTIONS 38,
   * D-RTR-1), or empty when the run renders as usual.
   *
   * <p>The marker is written only after validation passed, so a recorded hash always names bytes
   * that passed the gate. Four outcomes of reading the row's own key, and only one may burn:
   *
   * <ul>
   *   <li>the read fails: the row goes {@code FAILED_ARCHIVE} and the event is retried. "Could not
   *       look" is not "nothing there";
   *   <li>the object holds the recorded bytes: the document exists, so the number is issued by the
   *       existing edges, with no render. Those bytes passed the gate when they were written, under
   *       the rule pack already on the row, and they sit in the legal archive;
   *   <li>the object holds other bytes: {@code DEI-240}, terminal, never a burn. The key carries
   *       the content hash, so this is a second writer or a tampered store;
   *   <li>no object: empty, and the run renders, validates and checks dates as on a first run. A
   *       refusal then burns through the edge from this state.
   * </ul>
   */
  private Optional<Pending> resumeFromArchive(
      SourceInvoice invoice, Issuance issuance, IssuanceRowLock.Held held) {
    boolean marked =
        issuance.state() == IssuanceState.ARCHIVING
            || issuance.state() == IssuanceState.FAILED_ARCHIVE;
    if (!marked || issuance.documentHash().isEmpty() || issuance.archiveObjectKey().isEmpty()) {
      return Optional.empty();
    }
    ArchiveKey key = new ArchiveKey(issuance.archiveKey());
    Optional<byte[]> stored;
    try {
      stored = archive.get(key);
    } catch (EInvoiceException e) {
      return Optional.of(archiveFailed(invoice, e.code(), backoffFor(held), held));
    } catch (RuntimeException e) {
      // D2-04's class of risk: a host store's own bug. Logged server-side only. Still retried:
      // waiting is the only way to learn what the archive holds, and the ceiling bounds it.
      log.error("einvoice: the archive store threw an unexpected exception on a resumed read", e);
      return Optional.of(archiveFailed(invoice, ErrorCodes.ARCHIVE_FAILED, backoffFor(held), held));
    }
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    byte[] recorded =
        issuance.documentSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] found =
        Hashes.sha256Hex(stored.get()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(recorded, found)) {
      log.warn(
          "einvoice: the archive holds other bytes than number {} recorded, at its own key;"
              + " refused as a content conflict, never overwritten and never burned",
          issuance.legalNumber().value());
      return Optional.of(archiveFailed(invoice, ErrorCodes.ARCHIVE_CONTENT_CONFLICT, null, held));
    }
    return Optional.of(
        archiveAndIssue(invoice, issuance.documentSha256(), key, Optional.empty(), held));
  }

  /**
   * P3 and P4: the marker, the write when there are bytes to write, and the issue.
   *
   * @param toWrite the bytes to put at the key, or empty when a resumed run found them already
   *     there with the recorded hash: the marker is re-asserted and the number issued, no PUT
   */
  private Pending archiveAndIssue(
      SourceInvoice invoice,
      String documentSha256,
      ArchiveKey key,
      Optional<byte[]> toWrite,
      IssuanceRowLock.Held held) {
    // D-PR45-1: the second step that acts on the number. Outside the try below, so a lost lock is
    // never recorded as an archive failure; the PR 43 row lock guards the window after this check.
    runProof.assertHeld();
    // P3: say what we are about to write, then write it.
    try {
      held.writer()
          .markArchiving(
              configuration.sellerId(), configuration.mode(), invoice.id(), documentSha256, key);
      if (toWrite.isPresent()) {
        archive.putIfAbsent(key, toWrite.get().clone());
        if (configuration.readBackAfterWrite()) {
          readBack(key, toWrite.get());
        }
      }
    } catch (EInvoiceException e) {
      // RC-01: this write is inside a catch, so it must never raise a second, different failure in
      // place of the first - which is what it did when the row had been voided in the meantime.
      Duration retryIn = ErrorCodes.ARCHIVE_UNAVAILABLE.equals(e.code()) ? backoffFor(held) : null;
      return archiveFailed(invoice, e.code(), retryIn, held);
    } catch (RuntimeException e) {
      // D2-04: a host-supplied ArchiveStore is the same class of risk as the renderer and the
      // validator - always somebody else's code in 0.1.0.
      log.error("einvoice: the archive store threw an unexpected exception", e);
      return archiveFailed(invoice, ErrorCodes.ARCHIVE_FAILED, null, held);
    }

    // P4: the document exists. Wrapped like P3 (D2-01): a crash or a store failure here falls
    // back to P3 on retry - the row is still ARCHIVING, byte-identical - so this is retryable
    // exactly like an archive failure is.
    Issuance issued;
    try {
      issued =
          held.writer().markIssued(configuration.sellerId(), configuration.mode(), invoice.id());
    } catch (EInvoiceException e) {
      // A void taken while this run was archiving makes ISSUED illegal. That is terminal, not
      // retryable: there is no state left from which this document can be published.
      if (voidedSince(held.reader(), invoice.id())) {
        return Pending.of(InboundState.FAILED_ISSUANCE, ErrorCodes.NUMBER_VOIDED, null, null);
      }
      return Pending.of(InboundState.FAILED_ISSUANCE, e.code(), backoffFor(held), null);
    }
    return Pending.of(InboundState.COMPLETED, "", null, issued.legalNumber());
  }

  /**
   * The archive could not be written or read: the row goes {@code FAILED_ARCHIVE} (it stays there
   * when it already was), and the event concludes on {@code code}, retried when {@code retryIn} is
   * set. Never a burn.
   */
  private Pending archiveFailed(
      SourceInvoice invoice, String code, Duration retryIn, IssuanceRowLock.Held held) {
    Optional<WriteRefusal> refused =
        concludeIssuance(held, invoice.id(), IssuanceState.FAILED_ARCHIVE, "");
    if (refused.isPresent()) {
      return pendingOn(refused.get(), null, held);
    }
    return Pending.of(InboundState.FAILED_ISSUANCE, code, retryIn, null);
  }

  /**
   * The rule EINVOICE-DATES over the private array (D-PR36-6, design review R3). The reader is a
   * port: if it throws or answers nothing, the answer is NOT_EVALUATED, never a strand in {@code
   * MAPPED} and never a pass. The cause is logged server-side only.
   */
  private DocumentValidator.Report checkDates(byte[] archived, DocumentInput input) {
    DocumentDates.FoundDates found;
    try {
      found = dates.read(archived.clone());
    } catch (EInvoiceException e) {
      log.error("einvoice: the document date reader refused with code {}", e.code());
      found = null;
    } catch (RuntimeException e) {
      log.error("einvoice: the document date reader threw an unexpected exception", e);
      found = null;
    }
    return DocumentDatesRule.check(found, input.dating());
  }

  /**
   * The routing decision, taken <b>after</b> the durable record and never before it (I-01).
   *
   * <p>Each refusal here is a terminal state on the row with its own code, answered 200 by the
   * endpoint that recorded it, and replayable through this same method once the pin is updated.
   */
  private Optional<Outcome> route(InboundEvent event, InboundState read) {
    if (!SUBSCRIBED_TYPES.contains(event.type())) {
      return Optional.of(
          concludeBeforeFetch(
              read, event, InboundState.DROPPED, ErrorCodes.UNSUBSCRIBED_EVENT_TYPE, null));
    }
    if (!configuration.pinnedApiVersion().equals(event.apiVersion())) {
      // Never a lenient fallback deserialiser: the mapping table and the golden files are written
      // against the pinned version, so drift is loud and recorded rather than silently absorbed.
      return Optional.of(
          concludeBeforeFetch(
              read, event, InboundState.REFUSED_VERSION_SKEW, ErrorCodes.API_VERSION_SKEW, null));
    }
    if (event.livemode() != (configuration.mode() == Mode.LIVE)) {
      return Optional.of(
          concludeBeforeFetch(
              read, event, InboundState.REFUSED_MODE, ErrorCodes.MODE_MISMATCH, null));
    }
    if (!configuration.stripeAccountId().equals(event.stripeAccountId())) {
      return Optional.of(
          concludeBeforeFetch(
              read, event, InboundState.REFUSED_ACCOUNT, ErrorCodes.UNKNOWN_ACCOUNT, null));
    }
    inbound.bindSeller(event.eventId(), configuration.sellerId(), clock.instant());
    return Optional.empty();
  }

  /**
   * Every conclusion a run reaches before {@code FETCHED} goes through here (D-PR45-B1, design
   * "sweeper retry edges" section 4): the route's four refusals, the fetch failure and the fetched
   * object's mode.
   *
   * <p>A row that may hold a number never writes a pre-{@code FETCHED} state: that state would say
   * "this event never got going" while a legal number waits behind it. Such a row is written {@code
   * FAILED_ISSUANCE} with the conclusion's own code, an edge every number-capable start already
   * has, so no new edge leaves a numbered state. Its retry: a backoff, bounded by the {@code DUE}
   * ceiling, except for an unsubscribed type ({@code DEI-205}, an operator decides) and a refused
   * key ({@code DEI-219}, re-armed by the next success or startup). A row that cannot hold a number
   * writes {@code target}; a refused key there also stops with no retry.
   *
   * @param retryIn the retry of the unmapped conclusion
   */
  private Outcome concludeBeforeFetch(
      InboundState read, InboundEvent event, InboundState target, String code, Duration retryIn) {
    boolean keyRefused = ErrorCodes.STRIPE_ACCESS_REFUSED.equals(code);
    Outcome outcome;
    if (mayHoldNumber(read, event)) {
      Duration mapped = target == InboundState.DROPPED || keyRefused ? null : backoffFor(event);
      outcome = failFrom(read, event, InboundState.FAILED_ISSUANCE, code, mapped);
    } else {
      outcome = failFrom(read, event, target, code, keyRefused ? null : retryIn);
    }
    if (keyRefused && code.equals(outcome.code())) {
      // D-SRE-9: only after the write committed. A success that wins the flag from here on runs
      // its re-arm after this row is visible, so the row cannot miss it.
      accessRefused.set(true);
    }
    return outcome;
  }

  /**
   * Whether the row a run started from may hold a number (design section 3). An exhaustive switch
   * with no {@code default}: a new state fails to compile until it is given an answer here.
   *
   * <p>{@code FETCHED} is either: a retry of a numbered row that crashed after its {@code FETCHED}
   * write, or a first run that crashed before {@code MAPPED}. The issuance table tells them apart
   * (D-SRE-4), asked by invoice id only, across every seller and mode (D-SRE-6), so a wrong mode
   * can only make a row look numbered. The read is safe without the claim's lock: a claimer must
   * first move this row to {@code MAPPED}, which breaks this run's compare on {@code FETCHED}. It
   * is never used for {@code MAPPED}, where a claimer can be inside the claim while the row still
   * reads {@code MAPPED}.
   */
  private boolean mayHoldNumber(InboundState read, InboundEvent event) {
    return switch (read) {
      case MAPPED, FAILED_ISSUANCE -> true;
      case FETCHED -> !reader.issuanceStates(event.objectId()).isEmpty();
      case RECEIVED,
          PARKED,
          FAILED_FETCH,
          REFUSED_VERSION_SKEW,
          REFUSED_MODE,
          REFUSED_ACCOUNT,
          COMPLETED,
          DROPPED,
          FAILED_MAPPING,
          FAILED_TOTALS ->
          false;
    };
  }

  /**
   * A Stripe call succeeded. The first success after a committed refusal re-arms the stopped rows;
   * every other success costs one compare (D-SRE-3).
   */
  private void accepted() {
    if (accessRefused.compareAndSet(true, false)) {
      rearm("a Stripe call succeeded after a refused key");
    }
  }

  /**
   * Makes every row this instance stopped on a refused key due now: once at startup, from the
   * sweeper's thread, and once per change from "refused" to "accepted" (D-SRE-3). Only rows of this
   * instance's mode and Stripe account, and only rows with no next attempt, so a stamped live run
   * is never touched. The rows stay inside the {@code DUE} ceiling measured from arrival; a row
   * past it is resumed by {@link IssuanceReprocess}.
   *
   * @return how many rows were made due
   */
  public int rearmAccessRefused() {
    return rearm("startup");
  }

  private int rearm(String why) {
    try {
      int rows =
          inbound.rearmAccessRefused(
              configuration.mode() == Mode.LIVE, configuration.stripeAccountId(), clock.instant());
      if (rows > 0) {
        log.info(
            "einvoice: {} event(s) stopped on a refused Stripe key ({}) are due again: {}",
            rows,
            ErrorCodes.STRIPE_ACCESS_REFUSED,
            why);
      }
      return rows;
    } catch (EInvoiceException e) {
      // The next success tries again: the flag goes back up rather than losing the re-arm.
      accessRefused.set(true);
      log.warn(
          "einvoice: the re-arm of events stopped on a refused Stripe key failed ({}); the next"
              + " successful Stripe call tries again",
          e.code());
      return 0;
    }
  }

  /**
   * The Stripe source every run of this unit of work reads through. A success reports to {@link
   * #accepted()}; a failure passes through unchanged. Hand it to the reconciliation sweep, so its
   * list call counts as a success too.
   */
  public StripeInvoiceSource stripeSource() {
    return source;
  }

  /** Reports each successful call; adds nothing else. */
  private record AccessWatchingSource(StripeInvoiceSource delegate, Runnable onSuccess)
      implements StripeInvoiceSource {

    @Override
    public SourceInvoice fetchInvoice(String invoiceId) {
      SourceInvoice invoice = delegate.fetchInvoice(invoiceId);
      onSuccess.run();
      return invoice;
    }

    @Override
    public List<String> finalisedInvoiceIds(Instant from, Instant to) {
      List<String> ids = delegate.finalisedInvoiceIds(from, to);
      onSuccess.run();
      return ids;
    }

    @Override
    public String pinnedApiVersion() {
      return delegate.pinnedApiVersion();
    }
  }

  /**
   * A redelivery of an event whose invoice is already issued.
   *
   * <p>It runs the <b>integrity check</b> - the archived bytes against the recorded hash - and
   * returns the stored document. It does not re-render and does not compare against a fresh
   * re-render: upstream data legitimately moves, a buyer who corrects their address in March must
   * not turn a January document into an alert, and an alarm that fires on normal behaviour is one
   * operators learn to ignore within a week (I-02).
   */
  private Outcome redelivery(
      InboundEvent event, InboundState read, Issuance issuance, SourceInvoice invoice) {
    return applyFrom(event, Optional.of(read), redeliveryPending(issuance, invoice));
  }

  private Pending redeliveryPending(Issuance issuance, SourceInvoice invoice) {
    Optional<String> conflict;
    try {
      conflict = integrityFailure(issuance);
    } catch (EInvoiceException e) {
      // The store contract (D-RTR-8): a read that fails is "not known yet", never "tampered".
      // Retried; nothing is rendered and no number is taken.
      return Pending.of(InboundState.FAILED_ISSUANCE, e.code(), BACKOFF, null);
    } catch (RuntimeException e) {
      log.error("einvoice: the archive store threw an unexpected exception on a redelivery", e);
      return Pending.of(InboundState.FAILED_ISSUANCE, ErrorCodes.ARCHIVE_FAILED, BACKOFF, null);
    }
    if (conflict.isPresent()) {
      return Pending.of(
          InboundState.FAILED_ISSUANCE, ErrorCodes.ARCHIVED_DOCUMENT_TAMPERED, null, null);
    }
    if (invoice.voided()) {
      // The document exists and is never withdrawn; a credit note is the remedy and is not in this
      // edition, so this is a compliance finding with a documented manual remedy - not a DOWN
      // indicator that no free-core user could ever clear (I-04).
      return Pending.of(
          InboundState.COMPLETED,
          ErrorCodes.VOID_AFTER_ISSUE_NEEDS_CREDIT_NOTE,
          null,
          issuance.legalNumber());
    }
    return Pending.of(InboundState.COMPLETED, "", null, issuance.legalNumber());
  }

  /**
   * A render that refused after the number was allocated (D5-03).
   *
   * <p>The number's fate goes on its own row, exactly as a validation refusal's does: without this
   * the issuance stayed {@code NUMBERED} - a legal number allocated, no document, no reason
   * recorded and nothing to void against - until the reconciliation sweep's stuck check noticed a
   * count, hours later, without a cause. The disposition is {@code FAILED_VALIDATION} because that
   * is what it means to an operator: this number will never carry a document and needs a void. The
   * render's own code goes where a schematron rule id goes, so the row says which. A distinct
   * {@code FAILED_RENDER} state would read better and is an enum value, a successor edge, an {@code
   * open()} case, the trigger guard and a migration - its own change, not a line here.
   *
   * <p>One statement, one store call, outside any transaction of ours: the same shape the
   * validation refusal already uses, so the allocator design's "never two locks in one transaction"
   * rule is untouched.
   */
  private Pending renderRefused(
      SourceInvoice invoice, String code, Duration retryIn, IssuanceRowLock.Held held) {
    Optional<WriteRefusal> refused =
        concludeIssuance(held, invoice.id(), IssuanceState.FAILED_VALIDATION, code);
    if (refused.isPresent()) {
      return pendingOn(refused.get(), null, held);
    }
    // The cause goes where a schematron rule id goes on the same disposition, so an operator
    // looking at a failed number reads one row and learns which refusal it was.
    return new Pending(InboundState.FAILED_ISSUANCE, code, code, retryIn, null);
  }

  /** Empty when the archived bytes still hash to what the row recorded; a code when they do not. */
  private Optional<String> integrityFailure(Issuance issuance) {
    Optional<String> expected = issuance.documentHash();
    Optional<String> key = issuance.archiveObjectKey();
    if (expected.isEmpty() || key.isEmpty()) {
      return Optional.of(ErrorCodes.ARCHIVED_DOCUMENT_TAMPERED);
    }
    Optional<byte[]> stored = archive.get(new ArchiveKey(key.get()));
    if (stored.isEmpty() || !Hashes.sha256Hex(stored.get()).equals(expected.get())) {
      return Optional.of(ErrorCodes.ARCHIVED_DOCUMENT_TAMPERED);
    }
    return Optional.empty();
  }

  /**
   * The weaker mode's read-back (I-06). It narrows the window between two writers and does not
   * close it, and this module says that rather than implying otherwise.
   */
  private void readBack(ArchiveKey key, byte[] bytes) {
    byte[] stored =
        archive
            .get(key)
            .orElseThrow(
                () ->
                    new EInvoiceException(
                        ErrorCodes.ARCHIVE_UNAVAILABLE,
                        "the archive accepted the write and then could not produce the object"));
    if (!MessageDigest.isEqual(stored, bytes)) {
      throw new EInvoiceException(
          ErrorCodes.ARCHIVE_CONTENT_CONFLICT,
          "the archive holds different bytes at this key immediately after the write. This store"
              + " does not support a conditional create and another writer reached the key first.");
    }
  }

  private LocalDate supplyDay(SourceInvoice invoice) {
    Instant finalizedAt = invoice.finalizedAt();
    if (finalizedAt == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "the invoice carries no finalisation timestamp, so its supply date cannot be derived"
              + " (stripe field: status_transitions.finalized_at)");
    }
    return LocalDate.ofInstant(finalizedAt, configuration.taxZone());
  }

  private void refuseIfFiscalYearIsClosed(LocalDate issueDate) {
    Optional<Duration> cutoff = configuration.closedYearCutoff();
    if (cutoff.isEmpty()) {
      return;
    }
    LocalDate today = LocalDate.ofInstant(clock.instant(), configuration.taxZone());
    if (issueDate.getYear() >= today.getYear()) {
      return;
    }
    Instant yearEnd =
        LocalDate.of(issueDate.getYear() + 1, 1, 1)
            .atStartOfDay(configuration.taxZone())
            .toInstant();
    if (clock.instant().isAfter(yearEnd.plus(cutoff.get()))) {
      throw new EInvoiceException(
          ErrorCodes.CLOSED_FISCAL_YEAR,
          "this invoice belongs to a fiscal year that closed longer ago than"
              + " einvoice.numbering.closed-year-cutoff allows. Numbering it now would insert a"
              + " document into a period the seller has already declared, so it is refused and"
              + " reported instead.");
    }
  }

  /**
   * The series the preflight sees: provisional, from the supply day. The allocator picks the real
   * fiscal year from the numbering day (D-PS1-41) and the render uses the allocated one.
   */
  private SeriesKey seriesKey(LocalDate supplyDay) {
    int fiscalYear = configuration.fiscalYearReset() ? supplyDay.getYear() : SeriesKey.CONTINUOUS;
    return new SeriesKey(
        configuration.sellerId(), configuration.series(), fiscalYear, configuration.mode());
  }

  /**
   * Exponential, bounded, and derived from the row's own attempt count rather than from memory.
   * {@code event} is the row as this run stamped it (D-ORPE-3), so the first run waits the base,
   * the second twice the base, and so on: the schedule a run had before the stamp existed.
   */
  private Duration backoffFor(InboundEvent event) {
    return backoffAfter(event.attempts());
  }

  /** The backoff after {@code runs} runs: base x 2^(runs - 1), capped. */
  private Duration backoffAfter(int runs) {
    long factor = 1L << Math.min(10, Math.max(0, runs - 1));
    Duration backoff = configuration.retryBackoff().multipliedBy(factor);
    return backoff.compareTo(configuration.maxRetryBackoff()) > 0
        ? configuration.maxRetryBackoff()
        : backoff;
  }

  /**
   * D2-01. Only the allocator failures that can plausibly improve with time get a backoff: a store
   * outage or a lock-timeout are transient. A series that is not configured or has run out of
   * numbers is an operator decision, not a delay - reattempting it every sweep is exactly the
   * unbounded loop this finding removes.
   */
  private static boolean retryableAllocationFailure(String code) {
    return ErrorCodes.STORE_UNAVAILABLE.equals(code) || ErrorCodes.ALLOCATION_TIMEOUT.equals(code);
  }

  /**
   * The allocator's refusals that waiting can cure, beyond the store outages: a fiscal year that
   * changed under the lock (DEI-131, retried in a new transaction, which picks the new year), and a
   * clock regression small enough to be waited out (DEI-132). A regression beyond one day and a
   * series dated in the future (DEI-130) stop here: an operator fixes the clock or the row first.
   */
  private static boolean retryableAllocationFailure(EInvoiceException e) {
    if (e instanceof IssueClockException clock) {
      return clock.retryable();
    }
    return retryableAllocationFailure(e.code()) || ErrorCodes.ISSUE_YEAR_CHANGED.equals(e.code());
  }

  /**
   * What an issuance row that already exists for this Stripe invoice means for this run.
   *
   * <p>RC-01 existed because six issuance states were tested by an {@code if}-chain that named two:
   * a row in {@code VOID_UNUSED} fell through to the allocator, resumed onto the voided number, and
   * every state write from there raised an illegal transition that escaped {@code process} and left
   * the inbound row running for ever. This is a {@code switch} with <b>no {@code default}</b>, so a
   * seventh state fails to compile rather than falling through to the allocator again.
   */
  private enum ExistingIssuance {
    /** {@code ISSUED}: the document exists; a further event is a redelivery. */
    REDELIVERY,
    /** {@code NUMBERED}, {@code ARCHIVING}, {@code FAILED_ARCHIVE}: the run may carry on. */
    RESUME,
    /** {@code FAILED_VALIDATION}: the number is burned and awaits an operator void. */
    BURNED,
    /** {@code VOID_UNUSED}: the number is disposed; the Stripe invoice is terminal here. */
    VOIDED;

    /** True when the number will never carry a document, so no path may re-enter the pipeline. */
    boolean disposed() {
      return this == BURNED || this == VOIDED;
    }
  }

  private static ExistingIssuance classify(IssuanceState state) {
    return switch (state) {
      case ISSUED -> ExistingIssuance.REDELIVERY;
      case NUMBERED, ARCHIVING, FAILED_ARCHIVE -> ExistingIssuance.RESUME;
      case FAILED_VALIDATION -> ExistingIssuance.BURNED;
      case VOID_UNUSED -> ExistingIssuance.VOIDED;
    };
  }

  /**
   * The decision the classification produces, or empty when this run carries on to the allocator.
   *
   * <p>The upstream-void check keeps the place it had: a Stripe invoice voided before we ever
   * issued is dropped, whether there is an open row or none. The one order this changes is a
   * <em>voided</em> number, where the local disposition is the stronger fact - our number is
   * terminal whatever Stripe later does with the invoice.
   */
  private Optional<Outcome> existingDisposition(
      InboundEvent event, InboundState read, Optional<Issuance> existing, SourceInvoice invoice) {
    if (existing.isEmpty()) {
      return invoice.voided()
          // Voided upstream before we ever issued: nothing is owed and nothing is withdrawn.
          ? Optional.of(upstreamVoid(event, read))
          : Optional.empty();
    }
    Issuance row = existing.get();
    return switch (classify(row.state())) {
      case REDELIVERY -> Optional.of(redelivery(event, read, row, invoice));
      case VOIDED -> Optional.of(terminalDisposition(event, Optional.of(read), row));
      case BURNED ->
          Optional.of(
              invoice.voided()
                  ? upstreamVoid(event, read)
                  : terminalDisposition(event, Optional.of(read), row));
      case RESUME -> invoice.voided() ? Optional.of(upstreamVoid(event, read)) : Optional.empty();
    };
  }

  private Outcome upstreamVoid(InboundEvent event, InboundState read) {
    return failFrom(read, event, InboundState.DROPPED, ErrorCodes.UPSTREAM_VOID_NOT_ISSUED, null);
  }

  /**
   * A number that will never carry a document: the event concludes here, with a stable code, a
   * recorded state the due query does not re-pick, and no backoff.
   *
   * <p>{@code FAILED_VALIDATION} is final by declaration - retrying a schematron refusal is noise,
   * and the remedy is an operator voiding the number with the rule id the run recorded. {@code
   * VOID_UNUSED} is final for the Stripe invoice itself: one invoice maps to one number for all
   * time, so there is no second number to allocate and nothing a later run could do.
   */
  private Outcome terminalDisposition(
      InboundEvent event, Optional<InboundState> read, Issuance row) {
    return applyFrom(event, read, terminalPending(row));
  }

  private Pending terminalPending(Issuance row) {
    String code =
        classify(row.state()) == ExistingIssuance.VOIDED
            ? ErrorCodes.NUMBER_VOIDED
            : ErrorCodes.VALIDATION_REFUSED;
    return Pending.of(InboundState.FAILED_ISSUANCE, code, null, row.legalNumber());
  }

  /**
   * Writes a terminal issuance state, and never raises a second, different failure in place of the
   * first (the review's hard constraint on RC-01).
   *
   * <p>Every {@code markFailed} in this class runs through here. A void taken concurrently with a
   * run that is already past the preflight leaves the row in {@code VOID_UNUSED}, out of which no
   * transition is legal; before this, the {@code markFailed} inside the archive {@code catch} threw
   * from within the catch and replaced the original failure with {@code DEI-113}.
   *
   * @return empty when the state was written, or the stable code the caller must conclude the
   *     inbound row with instead of its own
   */
  /**
   * True when the row for that Stripe invoice is now {@code VOID_UNUSED} - a void landed mid-run.
   *
   * <p><b>Total</b> (D11-01). This is called from inside a failure handler, and the ordinary reason
   * the state write failed is that the store has gone away - the same store this read goes to. An
   * unguarded read there replaces the failure the handler already has with a new one and lets it
   * escape {@code process}, which is the shape the constraint forbids. A handler that cannot read
   * falls back to what it already knows: not voided, so the failure already in hand stands.
   */
  private boolean voidedSince(IssuanceReader under, String stripeInvoiceId) {
    try {
      return under
          .findBySource(configuration.sellerId(), configuration.mode(), stripeInvoiceId)
          .map(row -> classify(row.state()) == ExistingIssuance.VOIDED)
          .orElse(false);
    } catch (EInvoiceException e) {
      log.warn(
          "einvoice: could not re-read an issuance while handling a failure ({}); treating it as"
              + " not voided and keeping the failure already recorded",
          e.code());
      return false;
    } catch (RuntimeException e) {
      log.warn("einvoice: an issuance read threw while handling a failure", e);
      return false;
    }
  }

  /**
   * Why a state write was refused, and whether running again can help (D11-02).
   *
   * @param code the stable code the caller concludes the inbound row with instead of its own
   * @param retryable true for a store fault, which is a fact about the infrastructure and not a
   *     verdict on the sale; false when the row itself moved under the run, which no retry undoes
   */
  private record WriteRefusal(String code, boolean retryable) {}

  /**
   * Writes a terminal issuance state, and never raises a second, different failure in place of the
   * first (the review's hard constraint on RC-01).
   *
   * <p>Two reasons a write fails, and they must not conclude alike (D11-02). The row moved under
   * the run - a void landed mid-flight - and no retry undoes that, so the event concludes
   * terminally. Or the store itself failed, which says nothing about the sale: concluding that
   * permanently would leave the event never due again with the issuance row still open, a silent
   * terminal reached through a blip, which is the defect class RC-01 belongs to.
   *
   * @return empty when the state was written
   */
  private Optional<WriteRefusal> concludeIssuance(
      IssuanceRowLock.Held held, String stripeInvoiceId, IssuanceState target, String detail) {
    try {
      held.writer()
          .markFailed(
              configuration.sellerId(), configuration.mode(), stripeInvoiceId, target, detail);
      return Optional.empty();
    } catch (EInvoiceException e) {
      if (voidedSince(held.reader(), stripeInvoiceId)) {
        log.warn(
            "einvoice: an issuance could not be recorded as {} ({}); it was voided while the run"
                + " was in flight",
            target,
            e.code());
        return Optional.of(new WriteRefusal(ErrorCodes.NUMBER_VOIDED, false));
      }
      boolean transientFailure = transientStoreFailure(e.code());
      log.warn(
          "einvoice: an issuance could not be recorded as {} ({}); {}",
          target,
          e.code(),
          transientFailure ? "another attempt is scheduled" : "the event concludes with that code");
      return Optional.of(new WriteRefusal(e.code(), transientFailure));
    }
  }

  /**
   * A failure of the store rather than a verdict about this sale. The same list the allocator's
   * refusals use, named once (D11-02): a second list would drift from it.
   */
  private static boolean transientStoreFailure(String code) {
    // Revision 3: a row lock lost mid-run (DEI-104) is retried; the next run decides from what
    // the archive and the row then hold, under a fresh lock.
    return retryableAllocationFailure(code) || ErrorCodes.SESSION_NOT_PINNED.equals(code);
  }

  /** Concludes the inbound row on a refused state write, with a next attempt only if one helps. */
  private Pending pendingOn(WriteRefusal refusal, LegalNumber number, IssuanceRowLock.Held held) {
    return Pending.of(
        InboundState.FAILED_ISSUANCE,
        refusal.code(),
        refusal.retryable() ? backoffFor(held) : null,
        number);
  }

  /**
   * What a run under the row lock concluded, written on the inbound row only after the lock is
   * released (D-RTR-7, R3.6), so the holder never asks the pool for a second connection. A {@code
   * retryIn} of {@link #BACKOFF} means "the event's own backoff", resolved in {@link #apply}.
   */
  private record Pending(
      InboundState state, String code, String ruleId, Duration retryIn, String legalNumber) {

    static Pending of(InboundState state, String code, Duration retryIn, LegalNumber number) {
      return new Pending(state, code, "", retryIn, number == null ? null : number.value());
    }
  }

  /** "The event's own backoff", inside the held section, which has no event in hand. */
  private static final Duration BACKOFF = Duration.ofNanos(-1);

  @SuppressWarnings("unused")
  private static Duration backoffFor(IssuanceRowLock.Held held) {
    return BACKOFF;
  }

  private Outcome apply(InboundEvent event, Pending pending) {
    Duration retryIn = BACKOFF.equals(pending.retryIn()) ? backoffFor(event) : pending.retryIn();
    InboundEvent updated =
        pending.ruleId().isEmpty()
            ? inbound.transition(
                event.eventId(), pending.state(), pending.code(), clock.instant(), retryIn)
            : inbound.transition(
                event.eventId(),
                pending.state(),
                pending.code(),
                pending.ruleId(),
                clock.instant(),
                retryIn);
    return new Outcome(updated.eventId(), updated.state(), pending.code(), pending.legalNumber());
  }

  /**
   * {@link #apply} for a conclusion reached before the claim: with {@code read} present the write
   * is the compare-and-set of D-PR9-F1, and a row another run moved meanwhile is answered from what
   * is stored.
   */
  private Outcome applyFrom(InboundEvent event, Optional<InboundState> read, Pending pending) {
    if (read.isEmpty()) {
      return apply(event, pending);
    }
    Duration retryIn = BACKOFF.equals(pending.retryIn()) ? backoffFor(event) : pending.retryIn();
    return inbound
        .transitionFrom(
            event.eventId(),
            read.get(),
            pending.state(),
            pending.code(),
            pending.ruleId(),
            clock.instant(),
            retryIn)
        .map(
            updated ->
                new Outcome(
                    updated.eventId(), updated.state(), pending.code(), pending.legalNumber()))
        .orElseGet(() -> concludedElsewhere(event));
  }

  private Outcome park(InboundEvent event, InboundState read) {
    return failFrom(
        read, event, InboundState.PARKED, ErrorCodes.INVOICE_NOT_FINALISED, backoffFor(event));
  }

  private Outcome fail(InboundEvent event, InboundState state, String code, Duration retryIn) {
    return fail(event, state, code, retryIn, null);
  }

  private Outcome fail(
      InboundEvent event, InboundState state, String code, Duration retryIn, LegalNumber number) {
    return write(event, Optional.empty(), state, code, retryIn, number);
  }

  /** A write before the number claim: lands only on the state this run last read (D-PR9-F1). */
  private Outcome failFrom(
      InboundState read, InboundEvent event, InboundState state, String code, Duration retryIn) {
    return write(event, Optional.of(read), state, code, retryIn, null);
  }

  /**
   * The one inbound write of a conclusion. With {@code read} present it is a compare-and-set, and a
   * row another run moved meanwhile is answered from what is stored. Empty only after P1, where
   * this run holds the number and its write is the truth about that number.
   */
  private Outcome write(
      InboundEvent event,
      Optional<InboundState> read,
      InboundState state,
      String code,
      Duration retryIn,
      LegalNumber number) {
    String legalNumber = number == null ? null : number.value();
    if (read.isEmpty()) {
      InboundEvent updated =
          inbound.transition(event.eventId(), state, code, clock.instant(), retryIn);
      return new Outcome(updated.eventId(), updated.state(), code, legalNumber);
    }
    return inbound
        .transitionFrom(event.eventId(), read.get(), state, code, "", clock.instant(), retryIn)
        .map(updated -> new Outcome(updated.eventId(), updated.state(), code, legalNumber))
        .orElseGet(() -> concludedElsewhere(event));
  }

  /**
   * D-PR9-F1: this run's write found the row moved by another run. The answer is what that run
   * stored, read back now: the inbound row's state and code, and the number when one exists. This
   * run writes nothing more, exactly like the claim loser in P1. Example: worker B waited in the
   * preflight while worker A issued {@code INV-2026-000001}; B's {@code MAPPED} write finds {@code
   * COMPLETED}, and B returns {@code COMPLETED} with that number.
   */
  private Outcome concludedElsewhere(InboundEvent event) {
    return storedOutcome(reread(event));
  }

  /**
   * The {@code FETCHED} and {@code MAPPED} steps, as compare-and-sets (D-PR9-F1).
   *
   * <p>Empty when the write landed and this run carries on. Under the run lock no other run moves
   * the row (D-PR45-1), so the PR 45 "carry on behind a run in flight" branch is gone: a compare
   * that fails means a writer outside the lock, and the stored outcome is the answer.
   */
  private Optional<Outcome> step(InboundEvent event, InboundState read, InboundState next) {
    // The step keeps a next attempt one backoff away, like the stamp: a run that dies after this
    // step is not due again at once (D-PR45-1). Every conclusion overwrites it.
    if (inbound
        .transitionFrom(event.eventId(), read, next, "", "", clock.instant(), backoffFor(event))
        .isPresent()) {
      return Optional.empty();
    }
    return Optional.of(storedOutcome(reread(event)));
  }

  private InboundEvent reread(InboundEvent event) {
    return inbound
        .find(event.eventId())
        .orElseThrow(
            () ->
                new EInvoiceException(
                    ErrorCodes.INBOUND_UNREADABLE, "no inbound event is recorded under that id"));
  }

  private Outcome storedOutcome(InboundEvent stored) {
    Optional<LegalNumber> number =
        reader
            .findBySource(configuration.sellerId(), configuration.mode(), stored.objectId())
            .map(Issuance::legalNumber);
    log.warn(
        "einvoice: event {} was moved to {} by a writer outside its run lock; this run stops and"
            + " writes nothing",
        stored.eventId(),
        stored.state());
    return outcome(stored, number);
  }

  private Outcome outcome(InboundEvent event, Optional<LegalNumber> number) {
    return new Outcome(
        event.eventId(),
        event.state(),
        event.lastCode(),
        number.map(LegalNumber::value).orElse(null));
  }

  /** The configuration this unit of work runs under, for the sweep and the startup log. */
  public Configuration configuration() {
    return configuration;
  }

  /** Every event the sweeper should re-pick, oldest first (I-08, I-10, D2-03). */
  public List<InboundEvent> due(Duration retryCeiling, int limit) {
    return inbound.due(clock.instant(), retryCeiling, limit, configuration.pinnedApiVersion());
  }
}
