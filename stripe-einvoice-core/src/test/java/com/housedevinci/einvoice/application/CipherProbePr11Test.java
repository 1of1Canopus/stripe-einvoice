package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Adversarial probes for the void-terminal-disposition pull request: the stated deviation on the
 * two void columns, and the new failure handling the fix introduced.
 */
class CipherProbePr11Test {

  // ---------------------------------------------------------------------------------------
  // The stated deviation: the void justification is not write-once, it changes in the statement
  // that records a disposition. That is only safe if a row can enter a disposition at most once.
  // ---------------------------------------------------------------------------------------

  /**
   * D11 deviation, half one. If a row could be moved into {@code VOID_UNUSED} twice, or into {@code
   * FAILED_VALIDATION} after a void, the "only in the statement that records the disposition"
   * clause would be a licence to rewrite the justification as often as an attacker can move the
   * state. Both directions must be refused, in the domain and again in the trigger.
   */
  @Test
  void probe_a_disposed_row_cannot_enter_a_disposition_a_second_time() {
    String invoiceId = "in_pr11_twice";
    IssuanceTestHarness harness = IssuanceTestHarness.createWith(TestValidators.failing("BR-DE-1"));
    harness.source().with(TestInvoices.finalised(invoiceId));
    harness.unitOfWork().process(harness.receive("invoice.finalized", invoiceId));
    harness
        .store()
        .voidUnused(
            new VoidRequest(harness.sellerId(), Mode.LIVE, invoiceId, "first reason", "BR-DE-1"));
    assertThat(harness.issuance(invoiceId).orElseThrow().state())
        .isEqualTo(IssuanceState.VOID_UNUSED);

    Throwable secondVoid =
        catchThrowable(
            () ->
                harness
                    .store()
                    .voidUnused(
                        new VoidRequest(
                            harness.sellerId(), Mode.LIVE, invoiceId, "second reason", "BR-XX-9")));
    assertThat(secondVoid).as("a voided number must not be voidable again").isNotNull();

    // And the raw edge, with exactly the grants the runtime role holds.
    Throwable backToBurned =
        catchThrowable(
            () ->
                PostgresSupport.execute(
                    "UPDATE einvoice_issuance SET state = 'FAILED_VALIDATION',"
                        + " void_reason = 'planted', void_rule_id = 'PLANTED'"
                        + " WHERE stripe_invoice_id = '"
                        + invoiceId
                        + "'"));
    assertThat(backToBurned)
        .as("VOID_UNUSED must be a sink: no edge back into a state that may rewrite the reason")
        .isNotNull();
    assertThat(harness.issuance(invoiceId).orElseThrow().voidReason()).isEqualTo("first reason");
  }

  /**
   * D11 deviation, half two. The clause lets the runtime role write a justification in the same
   * statement that moves a live number into a disposition - a forged burn. Prevention is not
   * claimed there; detection is. The cross-check must report it.
   */
  @Test
  void probe_a_forged_disposition_that_plants_a_justification_is_reported_broken() {
    String invoiceId = "in_pr11_forged";
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised(invoiceId));
    harness.unitOfWork().process(harness.receive("invoice.finalized", invoiceId));
    // Put the row back in a live state the forged edge can start from.
    PostgresSupport.execute(
        "ALTER TABLE einvoice_issuance DISABLE TRIGGER USER;"
            + " UPDATE einvoice_issuance SET state = 'NUMBERED' WHERE stripe_invoice_id = '"
            + invoiceId
            + "'; ALTER TABLE einvoice_issuance ENABLE TRIGGER USER");

    // One statement, the runtime role's own grants, no chained event.
    PostgresSupport.execute(
        "UPDATE einvoice_issuance SET state = 'FAILED_VALIDATION',"
            + " void_reason = 'buyer asked us to cancel', void_rule_id = 'BR-CL-01'"
            + " WHERE stripe_invoice_id = '"
            + invoiceId
            + "'");

    IssuanceChainVerifier.Report report = verifier(harness).verify();
    assertThat(report.status())
        .as("a justification written without a chained event must not verify as intact")
        .isEqualTo(IssuanceChainVerifier.Status.BROKEN);
    assertThat(report.unchainedDispositions()).isGreaterThan(0);
  }

  /**
   * The two new sweep codes must not grow the findings page: a voided sale is seen by every sweep
   * for as long as the window covers it.
   */
  @Test
  void probe_a_voided_sale_does_not_grow_the_findings_page_every_sweep() {
    String invoiceId = "in_pr11_page";
    IssuanceTestHarness harness = IssuanceTestHarness.createWith(TestValidators.failing("BR-DE-1"));
    harness.source().with(TestInvoices.finalised(invoiceId));
    harness.unitOfWork().process(harness.receive("invoice.finalized", invoiceId));
    harness
        .store()
        .voidUnused(new VoidRequest(harness.sellerId(), Mode.LIVE, invoiceId, "burned", "BR-DE-1"));

    ReconciliationSweep sweep = harness.sweep(sweepSettings());
    sweep.sweep();
    sweep.sweep();
    sweep.sweep();

    assertThat(harness.findingRows(invoiceId))
        .as("the finding is upserted, not appended once per sweep")
        .isEqualTo(1);
    assertThat(harness.openFindings())
        .filteredOn(f -> f.code().equals(ErrorCodes.RECON_VOIDED_NO_DOCUMENT))
        .hasSize(1);
  }

  // ---------------------------------------------------------------------------------------
  // What the fix introduced.
  // ---------------------------------------------------------------------------------------

  /**
   * D11: {@code concludeIssuance} turns <em>every</em> failure of the state write into a terminal
   * inbound disposition with no next attempt - including a transient one. A store blip while
   * recording a validation refusal used to leave the event retryable; now the event is concluded
   * with the transient code and {@code next_attempt_at} null, so the due query never picks it up
   * again. A silent terminal, which is the defect class RC-01 belongs to.
   */
  @Test
  void probe_a_transient_store_failure_while_burning_is_not_made_permanent() {
    String invoiceId = "in_pr11_transient";
    IssuanceTestHarness harness = IssuanceTestHarness.createWith(TestValidators.failing("BR-DE-1"));
    harness.source().with(TestInvoices.finalised(invoiceId));
    String eventId = harness.receive("invoice.finalized", invoiceId);

    IssuanceUnitOfWork uow =
        unitOfWorkWith(
            harness,
            harness.reader(),
            new ThrowingWriter(
                harness.writer(),
                IssuanceState.FAILED_VALIDATION,
                new EInvoiceException(ErrorCodes.STORE_UNAVAILABLE, "connection reset")),
            harness.archive(),
            TestValidators.failing("BR-DE-1"));

    IssuanceUnitOfWork.Outcome outcome = uow.process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    // Builder's note (D11-02 fix): the probe asserted due-ness at t0, which the module's own retry
    // discipline never gives - a scheduled attempt is a backoff, not an immediate re-run. The
    // intent is kept and made stricter: the row must carry a next attempt at all (a null there is
    // the silent terminal this probe is about), and it must be due once that attempt has come.
    assertThat(harness.inbound().find(eventId).orElseThrow().nextAttempt())
        .as("a transient failure must schedule another attempt, not conclude with none")
        .isPresent();
    harness.moveClockForward(java.time.Duration.ofHours(1));
    assertThat(harness.due().stream().anyMatch(e -> e.eventId().equals(eventId)))
        .as(
            "a transient failure must leave the event recoverable, not silently terminal"
                + " (recorded code was "
                + outcome.code()
                + ")")
        .isTrue();
  }

  /**
   * D11: the archive {@code catch} must never raise a second, different failure in place of the
   * first - the review's hard constraint on RC-01. {@code concludeIssuance} answers it for the
   * write, then calls {@code voidedSince}, an unguarded <em>read</em>, inside the same catch. When
   * the store is the thing that is failing - the ordinary reason the write failed - that read
   * throws and the exception escapes {@code process}, which is the shape the constraint forbids.
   */
  @Test
  void probe_a_failing_read_inside_the_archive_catch_does_not_escape_process() {
    String invoiceId = "in_pr11_escape";
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised(invoiceId));
    String eventId = harness.receive("invoice.finalized", invoiceId);

    EInvoiceException down =
        new EInvoiceException(ErrorCodes.STORE_UNAVAILABLE, "connection reset");
    ThrowingReader reader = new ThrowingReader(harness.reader(), down);
    IssuanceUnitOfWork uow =
        unitOfWorkWith(
            harness,
            reader,
            new ThrowingWriter(harness.writer(), IssuanceState.FAILED_ARCHIVE, down),
            new FailingArchive());

    // The first lookup, before the allocator, must succeed; the store goes away afterwards.
    reader.failFromCallNumber(2);

    Throwable escaped = catchThrowable(() -> uow.process(eventId));
    assertThat(escaped)
        .as("no read inside the failure handler may replace the recorded failure with a throw")
        .isNull();
  }

  // ---------------------------------------------------------------------------------------

  private static ReconciliationSweep.Settings sweepSettings() {
    return new ReconciliationSweep.Settings(
        java.time.Duration.ofDays(30),
        java.time.Duration.ZERO,
        java.time.Duration.ofHours(6),
        10,
        500);
  }

  private IssuanceChainVerifier verifier(IssuanceTestHarness harness) {
    return new IssuanceChainVerifier(
        harness.store(),
        harness.store(),
        Map.of("k1", "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
  }

  private IssuanceUnitOfWork unitOfWorkWith(
      IssuanceTestHarness harness,
      IssuanceReader reader,
      IssuanceWriter writer,
      ArchiveStore archive) {
    return unitOfWorkWith(harness, reader, writer, archive, TestValidators.passing());
  }

  private IssuanceUnitOfWork unitOfWorkWith(
      IssuanceTestHarness harness,
      IssuanceReader reader,
      IssuanceWriter writer,
      ArchiveStore archive,
      DocumentValidator validator) {
    return new IssuanceUnitOfWork(
        harness.inbound(),
        harness.source(),
        harness.store(),
        reader,
        writer,
        harness.renderer(),
        validator,
        archive,
        harness.preflightSupport(),
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-01-16T09:00:00Z"), java.time.ZoneOffset.UTC),
        harness.unitOfWork().configuration());
  }

  /** A writer that fails on one target state, the way a store that has gone away fails. */
  private static final class ThrowingWriter implements IssuanceWriter {
    private final IssuanceWriter delegate;
    private final IssuanceState failOn;
    private final EInvoiceException failure;

    ThrowingWriter(IssuanceWriter delegate, IssuanceState failOn, EInvoiceException failure) {
      this.delegate = delegate;
      this.failOn = failOn;
      this.failure = failure;
    }

    @Override
    public Issuance markArchiving(
        String sellerId, Mode mode, String stripeInvoiceId, String documentSha256, ArchiveKey key) {
      return delegate.markArchiving(sellerId, mode, stripeInvoiceId, documentSha256, key);
    }

    @Override
    public Issuance markIssued(String sellerId, Mode mode, String stripeInvoiceId) {
      return delegate.markIssued(sellerId, mode, stripeInvoiceId);
    }

    @Override
    public Issuance markFailed(
        String sellerId, Mode mode, String stripeInvoiceId, IssuanceState state, String ruleId) {
      if (state == failOn) {
        throw failure;
      }
      return delegate.markFailed(sellerId, mode, stripeInvoiceId, state, ruleId);
    }
  }

  /** A reader that goes away from the nth call, as a store does mid-run. */
  private static final class ThrowingReader implements IssuanceReader {
    private final IssuanceReader delegate;
    private final EInvoiceException failure;
    private int calls;
    private int failFrom = Integer.MAX_VALUE;

    ThrowingReader(IssuanceReader delegate, EInvoiceException failure) {
      this.delegate = delegate;
      this.failure = failure;
    }

    void failFromCallNumber(int n) {
      this.failFrom = n;
    }

    @Override
    public Optional<Issuance> findBySource(String sellerId, Mode mode, String stripeInvoiceId) {
      if (++calls >= failFrom) {
        throw failure;
      }
      return delegate.findBySource(sellerId, mode, stripeInvoiceId);
    }

    @Override
    public com.housedevinci.einvoice.domain.SeriesReport seriesReport(
        com.housedevinci.einvoice.domain.SeriesKey seriesKey) {
      return delegate.seriesReport(seriesKey);
    }

    @Override
    public java.util.List<Issuance> inSeries(
        com.housedevinci.einvoice.domain.SeriesKey seriesKey, int limit) {
      return delegate.inSeries(seriesKey, limit);
    }
  }

  /** An archive that is unavailable, so P3 lands in the catch the constraint is about. */
  private static final class FailingArchive implements ArchiveStore {
    @Override
    public boolean supportsAtomicCreate() {
      return true;
    }

    @Override
    public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
      throw new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "bucket unreachable");
    }

    @Override
    public Optional<byte[]> get(ArchiveKey key) {
      return Optional.empty();
    }

    @Override
    public java.util.List<String> list(String prefix, int limit) {
      return java.util.List.of();
    }

    @Override
    public String describe() {
      return "unavailable";
    }
  }
}
