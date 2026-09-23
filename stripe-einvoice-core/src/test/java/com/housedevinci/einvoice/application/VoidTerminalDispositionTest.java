package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * A voided number is terminal for its Stripe invoice (RC-01).
 *
 * <p>One test per path that can restart work: a later event of another type, the same event again,
 * the sweeper's due query, the reconciliation sweep, the privileged reprocess, a row that was
 * already queued when the void was taken, the claim-race loser, a void taken while a run is in
 * flight, and the next sale after all of it. A control on one event is not a control.
 */
class VoidTerminalDispositionTest {

  private static final ReconciliationSweep.Settings SETTINGS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);
  private static final String RULE = "BR-DE-15";

  private record Burned(IssuanceTestHarness harness, String firstEventId) {}

  /** Burns a number the way a validation refusal does, then voids it the way the docs prescribe. */
  private static Burned burnedAndVoided(String invoiceId) {
    IssuanceTestHarness harness = IssuanceTestHarness.createWith(TestValidators.failing(RULE));
    harness.source().with(TestInvoices.finalised(invoiceId));
    String first = harness.receive("invoice.finalized", invoiceId);
    harness.unitOfWork().process(first);
    voidIt(harness, invoiceId, "will never carry a document", RULE);
    assertThat(harness.issuance(invoiceId).orElseThrow().state())
        .isEqualTo(IssuanceState.VOID_UNUSED);
    return new Burned(harness, first);
  }

  private static void voidIt(
      IssuanceTestHarness harness, String invoiceId, String reason, String ruleId) {
    harness
        .store()
        .voidUnused(new VoidRequest(harness.sellerId(), Mode.LIVE, invoiceId, reason, ruleId));
  }

  @Test
  void a_later_event_for_a_voided_invoice_concludes_and_allocates_nothing() {
    Burned burned = burnedAndVoided("in_void_later");
    IssuanceTestHarness harness = burned.harness();
    long counterBefore = harness.seriesCounter();
    int rendersBefore = harness.renderer().renders();

    String paid = harness.receive("invoice.paid", "in_void_later");
    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(paid);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.NUMBER_VOIDED);
    assertThat(harness.seriesCounter()).isEqualTo(counterBefore);
    assertThat(harness.numberedRows()).isEqualTo(1);
    // The loop's cost, not only its outcome: a voided invoice is decided before the allocator, so
    // nothing is rendered again on every sweep for ever.
    assertThat(harness.renderer().renders())
        .as("a voided invoice must not re-enter the render")
        .isEqualTo(rendersBefore);
    assertThat(harness.issuance("in_void_later").orElseThrow().state())
        .isEqualTo(IssuanceState.VOID_UNUSED);
  }

  @Test
  void the_voided_invoices_own_event_run_again_concludes_the_same_way() {
    Burned burned = burnedAndVoided("in_void_same");

    // The same event row, re-run: a redelivery, an operator replay, or a worker that picked it up
    // again. Idempotent, and nothing escapes.
    IssuanceUnitOfWork.Outcome again = burned.harness().unitOfWork().process(burned.firstEventId());

    assertThat(again.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(again.code()).isEqualTo(ErrorCodes.NUMBER_VOIDED);
    assertThat(burned.harness().numberedRows()).isEqualTo(1);
  }

  @Test
  void a_concluded_event_on_a_voided_invoice_is_never_due_again() {
    IssuanceTestHarness harness = burnedAndVoided("in_void_due").harness();
    String paid = harness.receive("invoice.paid", "in_void_due");
    harness.unitOfWork().process(paid);

    assertThat(harness.due()).extracting("eventId").doesNotContain(paid);
    harness.moveClockForward(Duration.ofHours(2));
    assertThat(harness.due()).extracting("eventId").doesNotContain(paid);
  }

  @Test
  void reconciliation_reports_a_voided_invoice_and_never_re_enqueues_it() {
    IssuanceTestHarness harness = burnedAndVoided("in_void_recon").harness();
    harness.moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.voidedNoDocument()).isEqualTo(1);
    assertThat(result.missingIssuance()).isZero();
    assertThat(harness.openFindings())
        .extracting(ComplianceFinding::code)
        .contains(ErrorCodes.RECON_VOIDED_NO_DOCUMENT)
        .doesNotContain(ErrorCodes.RECON_MISSING_ISSUANCE);
    assertThat(harness.inbound().find("recon-in_void_recon")).isEmpty();
  }

  @Test
  void reconciliation_reports_a_burned_number_and_never_re_enqueues_it() {
    IssuanceTestHarness harness = IssuanceTestHarness.createWith(TestValidators.failing(RULE));
    harness.source().with(TestInvoices.finalised("in_burn_recon"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_burn_recon"));
    harness.moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.burnedNoDocument()).isEqualTo(1);
    assertThat(result.missingIssuance()).isZero();
    assertThat(harness.openFindings())
        .extracting(ComplianceFinding::code)
        .contains(ErrorCodes.RECON_BURNED_NO_DOCUMENT);
    assertThat(harness.inbound().find("recon-in_burn_recon")).isEmpty();
  }

  @Test
  void a_sale_with_no_recorded_reason_is_still_re_enqueued() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_still_missing"));
    harness.moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.missingIssuance()).isEqualTo(1);
    assertThat(result.voidedNoDocument()).isZero();
    assertThat(harness.inbound().find("recon-in_still_missing")).isPresent();
  }

  @Test
  void a_row_already_queued_when_the_void_was_taken_concludes_on_the_switch() {
    // The reader no classification split can prevent: the row was enqueued before the void.
    IssuanceTestHarness harness = IssuanceTestHarness.createWith(TestValidators.failing(RULE));
    harness.source().with(TestInvoices.finalised("in_void_queued"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_void_queued"));
    String queued = harness.receive("invoice.paid", "in_void_queued");
    assertThat(harness.due()).extracting("eventId").contains(queued);

    voidIt(harness, "in_void_queued", "burned, then voided", RULE);
    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(queued);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.NUMBER_VOIDED);
    assertThat(harness.due()).extracting("eventId").doesNotContain(queued);
  }

  @Test
  void the_claim_race_loser_follows_a_voided_winner_rather_than_completing() {
    // The winner's row is voided between this run's lookup and its allocation - what a concurrent
    // void does to the loser of a claim race. Before this the loser concluded COMPLETED, the
    // success terminal, for a sale with no document.
    IssuanceTestHarness harness = inStateThroughTheStore("in_void_race", IssuanceState.NUMBERED);
    String paid = harness.receive("invoice.paid", "in_void_race");
    NumberAllocator lostTheRace =
        request -> {
          voidIt(harness, request.stripeInvoiceId(), "voided by the winner", RULE);
          throw new EInvoiceException(
              ErrorCodes.ISSUANCE_ALREADY_CLAIMED, "another transaction claimed this invoice");
        };

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWorkWithAllocator(lostTheRace).process(paid);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.NUMBER_VOIDED);
  }

  @Test
  void a_void_taken_while_a_run_is_archiving_ends_terminally_and_throws_nothing() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_void_inflight"));
    String eventId = harness.receive("invoice.finalized", "in_void_inflight");
    Throwable escaped =
        catchThrowable(
            () ->
                harness
                    .unitOfWorkWithArchive(new VoidingArchiveStore(harness, "in_void_inflight"))
                    .process(eventId));

    assertThat(escaped).isNull();
    assertThat(harness.issuance("in_void_inflight").orElseThrow().state())
        .isEqualTo(IssuanceState.VOID_UNUSED);
    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(harness.inbound().find(eventId).orElseThrow().lastCode())
        .isEqualTo(ErrorCodes.NUMBER_VOIDED);
  }

  @Test
  void a_fresh_invoice_after_a_void_takes_the_next_number_exactly_once() {
    // The burn is driven through the store so that the next sale meets the ordinary validator.
    IssuanceTestHarness harness =
        inStateThroughTheStore("in_void_then_new", IssuanceState.NUMBERED);
    harness
        .store()
        .markFailed(
            harness.sellerId(),
            Mode.LIVE,
            "in_void_then_new",
            IssuanceState.FAILED_VALIDATION,
            RULE);
    voidIt(harness, "in_void_then_new", "will never carry a document", RULE);
    harness.source().with(TestInvoices.finalised("in_after_void"));

    // The upstream remedy: a new Stripe invoice, through the ordinary path, with no special case.
    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWork().process(harness.receive("invoice.finalized", "in_after_void"));

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    Issuance issued = harness.issuance("in_after_void").orElseThrow();
    assertThat(issued.state()).isEqualTo(IssuanceState.ISSUED);
    assertThat(issued.legalNumber().counter()).isEqualTo(2);
    assertThat(harness.numberedRows()).isEqualTo(2);
  }

  @Test
  void a_privileged_reprocess_of_a_voided_invoice_is_still_refused() {
    Burned burned = burnedAndVoided("in_void_reprocess");

    IssuanceReprocess.Result result =
        burned
            .harness()
            .reprocess()
            .reprocess(
                new ReprocessRequest(
                    burned.firstEventId(), "operator-1", "data was corrected upstream"));

    // Two gates refuse this, and the outer one answers first: the event is not in the eligible
    // set. The numbered gate behind it is asserted by its own test in the reprocess suite. What
    // matters here is that no reprocess of a voided invoice runs the pipeline or takes a number.
    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REFUSED_NOT_ELIGIBLE);
    assertThat(result.disposition()).isNotEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(burned.harness().numberedRows()).isEqualTo(1);
    assertThat(burned.harness().issuance("in_void_reprocess").orElseThrow().state())
        .isEqualTo(IssuanceState.VOID_UNUSED);
  }

  @Test
  void acknowledging_the_finding_restarts_nothing() {
    // The acknowledgement writes a reason and never re-runs anything.
    IssuanceTestHarness harness = burnedAndVoided("in_void_ack").harness();
    harness.moveClockForward(Duration.ofHours(1));
    harness.sweep(SETTINGS).sweep();

    harness
        .findings()
        .acknowledge(
            harness.sellerId(),
            Mode.LIVE,
            ErrorCodes.RECON_VOIDED_NO_DOCUMENT,
            "in_void_ack",
            "the sale is being re-invoiced upstream",
            harness.now());

    assertThat(harness.inbound().find("recon-in_void_ack")).isEmpty();
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.issuance("in_void_ack").orElseThrow().state())
        .isEqualTo(IssuanceState.VOID_UNUSED);
  }

  @Test
  void every_issuance_state_reaches_a_declared_outcome_with_nothing_thrown() {
    // The switch has no default, so the compiler protects the next state. This protects the six
    // that exist: each is driven to, then a further event is run through the unit of work.
    for (IssuanceState state : IssuanceState.values()) {
      String invoiceId = "in_walk_" + state.name().toLowerCase(Locale.ROOT);
      IssuanceTestHarness harness = harnessInState(state, invoiceId);
      String later = harness.receive("invoice.paid", invoiceId);

      Throwable escaped = catchThrowable(() -> harness.unitOfWork().process(later));

      assertThat(escaped).as("state %s must not throw out of the unit of work", state).isNull();
      assertThat(harness.inbound().find(later).orElseThrow().state())
          .as("state %s must leave a declared inbound state", state)
          .isIn(
              InboundState.COMPLETED,
              InboundState.DROPPED,
              InboundState.FAILED_ISSUANCE,
              InboundState.PARKED);
      assertThat(harness.numberedRows())
          .as("state %s must allocate no second number", state)
          .isEqualTo(1);
    }
  }

  private static IssuanceTestHarness harnessInState(IssuanceState state, String invoiceId) {
    return switch (state) {
      case ISSUED -> {
        IssuanceTestHarness harness = IssuanceTestHarness.create();
        harness.source().with(TestInvoices.finalised(invoiceId));
        harness.unitOfWork().process(harness.receive("invoice.finalized", invoiceId));
        yield harness;
      }
      case NUMBERED, ARCHIVING, FAILED_ARCHIVE -> inStateThroughTheStore(invoiceId, state);
      case FAILED_VALIDATION -> {
        IssuanceTestHarness harness = IssuanceTestHarness.createWith(TestValidators.failing(RULE));
        harness.source().with(TestInvoices.finalised(invoiceId));
        harness.unitOfWork().process(harness.receive("invoice.finalized", invoiceId));
        yield harness;
      }
      case VOID_UNUSED -> burnedAndVoided(invoiceId).harness();
    };
  }

  /** Drives a fresh issuance to one of the open states through the store the pipeline uses. */
  private static IssuanceTestHarness inStateThroughTheStore(
      String invoiceId, IssuanceState target) {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised(invoiceId));
    harness
        .store()
        .allocate(
            new AllocationRequest(
                harness.seriesKey(), invoiceId, "", "STRIPE-1", harness.now(), "fr-2026.1"));
    if (target != IssuanceState.NUMBERED) {
      Issuance numbered = harness.issuance(invoiceId).orElseThrow();
      harness
          .store()
          .markArchiving(
              harness.sellerId(),
              Mode.LIVE,
              invoiceId,
              "0".repeat(64),
              ArchiveKey.of(harness.seriesKey(), numbered.legalNumber(), "0".repeat(64), "xml"));
    }
    if (target == IssuanceState.FAILED_ARCHIVE) {
      harness.store().markFailed(harness.sellerId(), Mode.LIVE, invoiceId, target, "");
    }
    return harness;
  }

  /** An archive that takes the operator's void while the run is inside P3, then fails the write. */
  private record VoidingArchiveStore(IssuanceTestHarness harness, String invoiceId)
      implements ArchiveStore {

    @Override
    public boolean supportsAtomicCreate() {
      return true;
    }

    @Override
    public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
      voidIt(harness, invoiceId, "voided while the run was archiving", "");
      throw new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "the archive is unavailable");
    }

    @Override
    public java.util.Optional<byte[]> get(ArchiveKey key) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.List<String> list(String prefix, int limit) {
      return java.util.List.of();
    }

    @Override
    public String describe() {
      return "voiding archive";
    }
  }
}
