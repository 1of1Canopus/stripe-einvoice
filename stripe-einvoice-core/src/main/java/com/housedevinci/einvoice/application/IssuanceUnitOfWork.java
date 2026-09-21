package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Hashes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
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
 *       <td>the re-render is byte-identical; there is nothing to undo</td></tr>
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

  private final InboundEventStore inbound;
  private final StripeInvoiceSource source;
  private final NumberAllocator allocator;
  private final IssuanceReader reader;
  private final IssuanceWriter writer;
  private final DocumentRenderer renderer;
  private final DocumentValidator validator;
  private final ArchiveStore archive;
  private final PreflightSupport preflightSupport;
  private final Clock clock;
  private final Configuration configuration;

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

  public IssuanceUnitOfWork(
      InboundEventStore inbound,
      StripeInvoiceSource source,
      NumberAllocator allocator,
      IssuanceReader reader,
      IssuanceWriter writer,
      DocumentRenderer renderer,
      DocumentValidator validator,
      ArchiveStore archive,
      PreflightSupport preflightSupport,
      Clock clock,
      Configuration configuration) {
    this.inbound = Objects.requireNonNull(inbound, "inbound");
    this.source = Objects.requireNonNull(source, "source");
    this.allocator = Objects.requireNonNull(allocator, "allocator");
    this.reader = Objects.requireNonNull(reader, "reader");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.archive = Objects.requireNonNull(archive, "archive");
    this.preflightSupport = Objects.requireNonNull(preflightSupport, "preflightSupport");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.configuration = Objects.requireNonNull(configuration, "configuration");
  }

  /** Drives one recorded event as far as it can go, and records where it stopped. */
  public Outcome process(String eventId) {
    InboundEvent event =
        inbound
            .find(eventId)
            .orElseThrow(
                () ->
                    new EInvoiceException(
                        ErrorCodes.INBOUND_UNREADABLE,
                        "no inbound event is recorded under that id"));
    if (event.state().terminal()
        && !event.state().retryableTerminal()
        && !event.state().refused()) {
      return outcome(event, Optional.empty());
    }
    Optional<Outcome> refusal = route(event);
    if (refusal.isPresent()) {
      return refusal.get();
    }

    SourceInvoice invoice;
    try {
      invoice = source.fetchInvoice(event.objectId());
    } catch (EInvoiceException e) {
      // An outage, a rate limit, a 5xx, or a collection that would not read to exhaustion. None of
      // them is a verdict about the sale, so all of them are retryable up to the ceiling (I-08).
      return fail(event, InboundState.FAILED_FETCH, e.code(), backoffFor(event));
    }
    if (invoice.livemode() != (configuration.mode() == Mode.LIVE)) {
      // The event said one thing and the object says another. The authoritative answer wins, and
      // in this direction it is the one that keeps a test invoice out of the live series (D-01).
      return fail(event, InboundState.REFUSED_MODE, ErrorCodes.MODE_MISMATCH, null);
    }
    inbound.transition(eventId, InboundState.FETCHED, "", clock.instant(), null);

    if (!invoice.finalised()) {
      // Out of order: invoice.paid can arrive before invoice.finalized. Parked, retried, never an
      // invented invoice and never a dropped event.
      return park(event);
    }
    Optional<Issuance> existing =
        reader.findBySource(configuration.sellerId(), configuration.mode(), invoice.id());
    if (existing.isPresent() && existing.get().state() == IssuanceState.ISSUED) {
      return redelivery(event, existing.get(), invoice);
    }
    if (invoice.voided()) {
      // Voided upstream before we ever issued: nothing is owed and nothing is withdrawn.
      return fail(event, InboundState.DROPPED, ErrorCodes.UPSTREAM_VOID_NOT_ISSUED, null);
    }
    if (existing.isPresent() && existing.get().state() == IssuanceState.FAILED_VALIDATION) {
      // Final by declaration: retrying a schematron refusal is noise, and the remedy is an
      // operator voiding the number with the rule id this run recorded.
      return fail(event, InboundState.FAILED_ISSUANCE, ErrorCodes.VALIDATION_REFUSED, null);
    }

    LocalDate issueDate = issueDate(invoice);
    try {
      Totals.reconcile(invoice.totals());
      refuseIfFiscalYearIsClosed(issueDate);
    } catch (TotalsMismatchException mismatch) {
      // The values are on the exception and go no further than a debug line that is off by
      // default; the state, the code and the field name are what the operator sees.
      log.debug(
          "einvoice: totals mismatch on field {} (recomputed {}, upstream {})",
          mismatch.field(),
          mismatch.recomputed(),
          mismatch.upstream());
      return fail(event, InboundState.FAILED_TOTALS, mismatch.code(), null);
    } catch (EInvoiceException refused) {
      return fail(event, InboundState.FAILED_MAPPING, refused.code(), null);
    }
    if (!validator.canValidate()) {
      // D3-02: this is a fact about the application, known before it serves a single request, not
      // a fact about this invoice. Discovering it in P2, after the allocator has already run,
      // spends a legal number on a document that was never going to be validated by anything -
      // every invoice, for as long as the condition holds. No backoff: an operator has to add a
      // processor or change the configuration, and a sweep does not do that.
      return fail(event, InboundState.FAILED_ISSUANCE, ErrorCodes.XSLT_PROCESSOR_MISSING, null);
    }

    // The preflight (D3-01). Every screen the render will apply, applied now, with no number in
    // existence: a buyer-controlled field this module cannot put on a document is refused here and
    // costs nothing. One MappingInput, constructed once and handed to both passes, so the two
    // cannot disagree about the invoice, the series or the issue date.
    MappingInput mapping =
        new MappingInput(invoice, seriesKey(issueDate), issueDate, configuration.rulePackVersion());
    PreflightReport report = preflight(mapping);
    if (report.refused()) {
      // FAILED_MAPPING, with the mapper's own code, and no NUMBERED row, chain entry or archive
      // object. Terminal with an empty successor set: a finalised invoice's fields are frozen, so
      // re-running changes nothing and the sweeper does not re-pick it.
      return fail(event, InboundState.FAILED_MAPPING, report.code(), null)
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
    inbound.transition(eventId, InboundState.MAPPED, "", clock.instant(), null);
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
    Issuance issuance;
    try {
      issuance =
          allocator.allocate(
              new AllocationRequest(
                  mapping.seriesKey(),
                  invoice.id(),
                  invoice.accountId(),
                  invoice.number(),
                  invoice.finalizedAt(),
                  configuration.rulePackVersion()));
    } catch (EInvoiceException e) {
      if (ErrorCodes.ISSUANCE_ALREADY_CLAIMED.equals(e.code())) {
        // The loser of a claim race is not a failure at all - it is a duplicate. The invoice is
        // finalized and paid together in the normal case, so this is the common shape, not an
        // edge case: two events, one winner, and the loser's job is done as soon as it can see
        // that. Re-read the winner's row rather than guess at its number.
        Optional<Issuance> winner =
            reader.findBySource(configuration.sellerId(), configuration.mode(), invoice.id());
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
      Duration retryIn = retryableAllocationFailure(e.code()) ? backoffFor(event) : null;
      return fail(event, InboundState.FAILED_ISSUANCE, e.code(), retryIn);
    }

    // The same MappingInput the preflight approved, plus the number. Not a re-derivation: a second
    // derivation of the issue date is a second date rule, and two date rules drift.
    DocumentInput input = new DocumentInput(mapping, issuance.legalNumber());

    // P2: the exact bytes, and the validation of those exact bytes - never of a re-serialisation.
    // Both ports are always a host's own code in 0.1.0 - the writers are the next change - and a
    // bug in either (a null pointer, an XML library's own runtime exception) must not leave this
    // event MAPPED forever: that is exactly the D2-01 state re-picked immediately, for ever, and
    // never reaching a FAILED_* state the retry ceiling can govern. RuntimeException is caught
    // alongside this module's own exception type; the cause is logged server-side only (checklist
    // line 47), and the row gets a stable, generic code, never the cause.
    byte[] bytes;
    DocumentRenderer.RenderedDocument document;
    try {
      document = renderer.render(input);
      bytes = document.bytes();
    } catch (EInvoiceException e) {
      return renderRefused(event, invoice, e.code(), backoffFor(event));
    } catch (RuntimeException e) {
      log.error("einvoice: the document renderer threw an unexpected exception", e);
      return renderRefused(event, invoice, ErrorCodes.RENDER_FAILED, null);
    }
    DocumentValidator.Report report;
    try {
      report = validator.validate(bytes, input);
    } catch (EInvoiceException e) {
      return fail(event, InboundState.FAILED_ISSUANCE, e.code(), backoffFor(event));
    } catch (RuntimeException e) {
      log.error("einvoice: the document validator threw an unexpected exception", e);
      return fail(event, InboundState.FAILED_ISSUANCE, ErrorCodes.VALIDATOR_FAILED, null);
    }
    if (!report.archivable()) {
      String code =
          report.verdict() == DocumentValidator.Verdict.NOT_EVALUATED
              ? ErrorCodes.VALIDATION_NOT_EVALUATED
              : ErrorCodes.VALIDATION_REFUSED;
      // A refusal leaves no file and no ISSUED row. The number stays allocated with its state
      // recorded, and an operator voids it with this rule id - which is why the rule id is stored
      // rather than left to be inferred.
      writer.markFailed(
          configuration.sellerId(),
          configuration.mode(),
          invoice.id(),
          IssuanceState.FAILED_VALIDATION,
          report.ruleId());
      inbound.transition(
          event.eventId(),
          InboundState.FAILED_ISSUANCE,
          code,
          report.ruleId(),
          clock.instant(),
          null);
      return new Outcome(
          event.eventId(), InboundState.FAILED_ISSUANCE, code, issuance.legalNumber().value());
    }

    String documentSha256 = Hashes.sha256Hex(bytes);
    ArchiveKey key =
        ArchiveKey.of(
            issuance.seriesKey(), issuance.legalNumber(), documentSha256, document.extension());

    // P3: say what we are about to write, then write it.
    try {
      writer.markArchiving(
          configuration.sellerId(), configuration.mode(), invoice.id(), documentSha256, key);
      archive.putIfAbsent(key, bytes);
      if (configuration.readBackAfterWrite()) {
        readBack(key, bytes);
      }
    } catch (EInvoiceException e) {
      writer.markFailed(
          configuration.sellerId(),
          configuration.mode(),
          invoice.id(),
          IssuanceState.FAILED_ARCHIVE,
          "");
      Duration retryIn = ErrorCodes.ARCHIVE_UNAVAILABLE.equals(e.code()) ? backoffFor(event) : null;
      return fail(event, InboundState.FAILED_ISSUANCE, e.code(), retryIn);
    } catch (RuntimeException e) {
      // D2-04: a host-supplied ArchiveStore is the same class of risk as the renderer and the
      // validator - always somebody else's code in 0.1.0.
      log.error("einvoice: the archive store threw an unexpected exception", e);
      writer.markFailed(
          configuration.sellerId(),
          configuration.mode(),
          invoice.id(),
          IssuanceState.FAILED_ARCHIVE,
          "");
      return fail(event, InboundState.FAILED_ISSUANCE, ErrorCodes.ARCHIVE_FAILED, null);
    }

    // P4: the document exists. Wrapped like P3 (D2-01): a crash or a store failure here falls
    // back to P3 on retry - the row is still ARCHIVING, byte-identical - so this is retryable
    // exactly like an archive failure is.
    Issuance issued;
    try {
      issued = writer.markIssued(configuration.sellerId(), configuration.mode(), invoice.id());
    } catch (EInvoiceException e) {
      return fail(event, InboundState.FAILED_ISSUANCE, e.code(), backoffFor(event));
    }
    inbound.transition(event.eventId(), InboundState.COMPLETED, "", clock.instant(), null);
    return new Outcome(event.eventId(), InboundState.COMPLETED, "", issued.legalNumber().value());
  }

  /**
   * The routing decision, taken <b>after</b> the durable record and never before it (I-01).
   *
   * <p>Each refusal here is a terminal state on the row with its own code, answered 200 by the
   * endpoint that recorded it, and replayable through this same method once the pin is updated.
   */
  private Optional<Outcome> route(InboundEvent event) {
    if (!SUBSCRIBED_TYPES.contains(event.type())) {
      return Optional.of(
          fail(event, InboundState.DROPPED, ErrorCodes.UNSUBSCRIBED_EVENT_TYPE, null));
    }
    if (!configuration.pinnedApiVersion().equals(event.apiVersion())) {
      // Never a lenient fallback deserialiser: the mapping table and the golden files are written
      // against the pinned version, so drift is loud and recorded rather than silently absorbed.
      return Optional.of(
          fail(event, InboundState.REFUSED_VERSION_SKEW, ErrorCodes.API_VERSION_SKEW, null));
    }
    if (event.livemode() != (configuration.mode() == Mode.LIVE)) {
      return Optional.of(fail(event, InboundState.REFUSED_MODE, ErrorCodes.MODE_MISMATCH, null));
    }
    if (!configuration.stripeAccountId().equals(event.stripeAccountId())) {
      return Optional.of(
          fail(event, InboundState.REFUSED_ACCOUNT, ErrorCodes.UNKNOWN_ACCOUNT, null));
    }
    inbound.bindSeller(event.eventId(), configuration.sellerId(), clock.instant());
    return Optional.empty();
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
  private Outcome redelivery(InboundEvent event, Issuance issuance, SourceInvoice invoice) {
    Optional<String> conflict = integrityFailure(issuance);
    if (conflict.isPresent()) {
      return fail(event, InboundState.FAILED_ISSUANCE, ErrorCodes.ARCHIVED_DOCUMENT_TAMPERED, null);
    }
    if (invoice.voided()) {
      // The document exists and is never withdrawn; a credit note is the remedy and is not in this
      // edition, so this is a compliance finding with a documented manual remedy - not a DOWN
      // indicator that no free-core user could ever clear (I-04).
      return fail(
          event,
          InboundState.COMPLETED,
          ErrorCodes.VOID_AFTER_ISSUE_NEEDS_CREDIT_NOTE,
          null,
          issuance.legalNumber());
    }
    return fail(event, InboundState.COMPLETED, "", null, issuance.legalNumber());
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
  private Outcome renderRefused(
      InboundEvent event, SourceInvoice invoice, String code, Duration retryIn) {
    writer.markFailed(
        configuration.sellerId(),
        configuration.mode(),
        invoice.id(),
        IssuanceState.FAILED_VALIDATION,
        code);
    // The cause goes where a schematron rule id goes on the same disposition, so an operator
    // looking at a failed number reads one row and learns which refusal it was.
    InboundEvent updated =
        inbound.transition(
            event.eventId(), InboundState.FAILED_ISSUANCE, code, code, clock.instant(), retryIn);
    return new Outcome(updated.eventId(), updated.state(), code, null);
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

  private LocalDate issueDate(SourceInvoice invoice) {
    Instant finalizedAt = invoice.finalizedAt();
    if (finalizedAt == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "the invoice carries no finalisation timestamp, so BT-2 cannot be derived (stripe field:"
              + " status_transitions.finalized_at)");
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

  private SeriesKey seriesKey(LocalDate issueDate) {
    int fiscalYear = configuration.fiscalYearReset() ? issueDate.getYear() : SeriesKey.CONTINUOUS;
    return new SeriesKey(
        configuration.sellerId(), configuration.series(), fiscalYear, configuration.mode());
  }

  /** Exponential, bounded, and derived from the row's own attempt count rather than from memory. */
  private Duration backoffFor(InboundEvent event) {
    long factor = 1L << Math.min(10, Math.max(0, event.attempts()));
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

  private Outcome park(InboundEvent event) {
    return fail(event, InboundState.PARKED, ErrorCodes.INVOICE_NOT_FINALISED, backoffFor(event));
  }

  private Outcome fail(InboundEvent event, InboundState state, String code, Duration retryIn) {
    return fail(event, state, code, retryIn, null);
  }

  private Outcome fail(
      InboundEvent event, InboundState state, String code, Duration retryIn, LegalNumber number) {
    InboundEvent updated =
        inbound.transition(event.eventId(), state, code, clock.instant(), retryIn);
    return new Outcome(
        updated.eventId(), updated.state(), code, number == null ? null : number.value());
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
