package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.DateGapCause;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssueDating;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * D-PS1-27 through the unit of work: every path of design section 2 that used to number an invoice
 * out of date order now numbers it at once, dates it on the numbering day, keeps the sale's day as
 * the supply date, and records why the two differ. The harness's tax zone is Europe/Paris.
 *
 * <p>Example of the defect this closes: an invoice finalised on 3 January and numbered on 10
 * January carried 3 January while a number given on 5 January carried 5 January.
 */
class InvoiceDateOrderPathsTest {

  private static final Instant JAN_3 = Instant.parse("2026-01-03T10:00:00Z");
  private static final Instant JAN_3_LATER = Instant.parse("2026-01-03T11:00:00Z");
  private static final Instant JAN_10 = Instant.parse("2026-01-10T10:00:00Z");
  private static final LocalDate DAY_3 = LocalDate.parse("2026-01-03");
  private static final LocalDate DAY_10 = LocalDate.parse("2026-01-10");

  private static SourceInvoice finalisedAt(String id, Instant finalizedAt) {
    SourceInvoice base = TestInvoices.finalised(id);
    return new SourceInvoice(
        base.id(),
        base.number(),
        base.accountId(),
        base.livemode(),
        base.currency(),
        base.status(),
        finalizedAt,
        base.buyer(),
        base.lines(),
        base.taxBuckets(),
        base.taxTreatments(),
        base.subtotalMinor(),
        base.taxMinor(),
        base.totalMinor(),
        true);
  }

  private static IssueDating datingOf(IssuanceTestHarness harness, String invoiceId) {
    return harness.issuance(invoiceId).orElseThrow().dating().orElseThrow();
  }

  /** The common assertions of every reorder path (design section 6). */
  private static void numberedLateWith(
      IssuanceTestHarness harness, String invoiceId, DateGapCause cause) {
    IssueDating dating = datingOf(harness, invoiceId);
    assertThat(dating.issueDate()).isEqualTo(DAY_10);
    assertThat(dating.supplyDate()).contains(DAY_3);
    assertThat(dating.cause()).contains(cause);
    String document = new String(harness.archivedBytes(invoiceId), StandardCharsets.UTF_8);
    assertThat(document).contains("<IssueDate>2026-01-10</IssueDate>");
    assertThat(document).contains("<Delivery>2026-01-03</Delivery>");
    assertThat(harness.findingRows(harness.issuance(invoiceId).orElseThrow().legalNumber().value()))
        .isEqualTo(1L);
  }

  // ------------------------------------------------------------------ R2, M1

  @Test
  void r2_a_late_webhook_is_numbered_at_once_and_dated_the_day_it_is_numbered() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(JAN_10);
    harness.source().with(finalisedAt("in_r2", JAN_3));
    String eventId = harness.receive("invoice.finalized", "in_r2");

    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);

    numberedLateWith(harness, "in_r2", DateGapCause.LATE_WEBHOOK);
  }

  // ------------------------------------------------------------------ R3

  @Test
  void r3_fetch_retries_then_success_is_dated_the_numbering_day_with_cause_retry() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(JAN_3_LATER);
    harness.source().with(finalisedAt("in_r3", JAN_3));
    String eventId = harness.receive("invoice.finalized", "in_r3");
    harness.source().breakWith(new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "down"));
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.FAILED_FETCH);

    harness.source().heal();
    harness.moveClockTo(JAN_10);
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);

    numberedLateWith(harness, "in_r3", DateGapCause.RETRY);
  }

  // ------------------------------------------------------------------ R4

  @Test
  void r4_an_allocation_timeout_then_success_has_cause_retry() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(JAN_3_LATER);
    harness.source().with(finalisedAt("in_r4", JAN_3));
    String eventId = harness.receive("invoice.finalized", "in_r4");
    NumberAllocator timingOut =
        request -> {
          throw new EInvoiceException(ErrorCodes.ALLOCATION_TIMEOUT, "lock wait");
        };
    IssuanceUnitOfWork.Outcome failed = harness.unitOfWorkWithAllocator(timingOut).process(eventId);
    assertThat(failed.state()).isEqualTo(InboundState.FAILED_ISSUANCE);

    harness.moveClockTo(JAN_10);
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);

    numberedLateWith(harness, "in_r4", DateGapCause.RETRY);
  }

  // ------------------------------------------------------------------ R5, T-park

  @Test
  void r5_a_parked_event_gets_its_issue_day_when_it_reaches_the_allocator() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(JAN_3);
    harness.source().with(TestInvoices.draft("in_r5"));
    String eventId = harness.receive("invoice.paid", "in_r5");
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.PARKED);

    harness.source().with(finalisedAt("in_r5", JAN_3_LATER));
    harness.moveClockTo(JAN_10);
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);

    numberedLateWith(harness, "in_r5", DateGapCause.PARK);
  }

  // ------------------------------------------------------------------ R6

  @Test
  void r6_an_event_past_its_retry_ceiling_re_driven_later_has_cause_retry() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(JAN_3_LATER);
    harness.source().with(finalisedAt("in_r6", JAN_3));
    String eventId = harness.receive("invoice.finalized", "in_r6");
    harness.source().breakWith(new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "down"));
    for (int attempt = 0; attempt < 4; attempt++) {
      harness.unitOfWork().process(eventId);
      harness.moveClockForward(Duration.ofHours(6));
    }

    harness.source().heal();
    harness.moveClockTo(JAN_10);
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);

    numberedLateWith(harness, "in_r6", DateGapCause.RETRY);
  }

  // ------------------------------------------------------------------ R7

  @Test
  void r7_a_reprocessed_mapping_refusal_has_cause_reprocess() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(JAN_3_LATER);
    harness.source().with(finalisedAt("in_r7", JAN_3));
    String eventId = harness.receive("invoice.finalized", "in_r7");
    AtomicBoolean profileIncomplete = new AtomicBoolean(true);
    DocumentRenderer repairable =
        new DocumentRenderer() {
          @Override
          public boolean carriesSupplyDate() {
            return true;
          }

          @Override
          public PreflightReport preflight(MappingInput input) {
            return profileIncomplete.get()
                ? PreflightReport.refused(ErrorCodes.SELLER_PROFILE_INCOMPLETE)
                : PreflightReport.passed();
          }

          @Override
          public RenderedDocument render(DocumentInput input) {
            return harness.renderer().render(input);
          }
        };
    assertThat(harness.unitOfWorkWithRenderer(repairable).process(eventId).state())
        .isEqualTo(InboundState.FAILED_MAPPING);

    profileIncomplete.set(false);
    harness.moveClockTo(JAN_10);
    IssuanceReprocess.Result result =
        harness.reprocessWith(repairable).reprocess(new ReprocessRequest(eventId, "ops", "fixed"));
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);

    numberedLateWith(harness, "in_r7", DateGapCause.REPROCESS);
  }

  // ------------------------------------------------------------------ R8, R9

  @Test
  void r8_r9_a_sweep_re_enqueued_invoice_has_cause_sweep() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(JAN_3_LATER);
    harness.source().with(finalisedAt("in_r8", JAN_3));
    harness
        .sweep(
            new ReconciliationSweep.Settings(
                Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500))
        .sweep();

    harness.moveClockTo(JAN_10);
    assertThat(harness.unitOfWork().process("recon-in_r8").state())
        .isEqualTo(InboundState.COMPLETED);

    numberedLateWith(harness, "in_r8", DateGapCause.SWEEP);
  }

  // ------------------------------------------------------------------ T-closed-year

  @Test
  void an_old_supply_date_past_the_cut_off_is_still_refused() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWithCutoff(Optional.of(Duration.ofDays(30)));
    harness.moveClockTo(Instant.parse("2026-03-01T10:00:00Z"));
    harness.source().with(finalisedAt("in_closed", Instant.parse("2025-12-15T10:00:00Z")));
    String eventId = harness.receive("invoice.finalized", "in_closed");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.CLOSED_FISCAL_YEAR);
    assertThat(harness.numberedRows()).isZero();
  }

  // ------------------------------------------------------------------ T-supply-not-carried, M14

  @Test
  void a_renderer_that_cannot_carry_the_supply_date_is_refused_before_a_number_exists() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_nosupply"));
    String eventId = harness.receive("invoice.finalized", "in_nosupply");
    DocumentRenderer withoutSupply =
        new DocumentRenderer() {
          @Override
          public PreflightReport preflight(MappingInput input) {
            return PreflightReport.passed();
          }

          @Override
          public RenderedDocument render(DocumentInput input) {
            return harness.renderer().render(input);
          }
        };

    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWorkWithRenderer(withoutSupply).process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.SUPPLY_DATE_NOT_CARRIED);
    assertThat(harness.numberedRows()).isZero();
  }

  // ------------------------------------------------------------------ T-clock-2d, M7

  /**
   * The database clock two days behind: the event stops on DEI-132 with no next attempt and no
   * number. After the clock is fixed, the privileged reprocess re-runs it through the same refusal,
   * and it is numbered. There is no accept release.
   */
  @Test
  void a_clock_two_days_behind_stops_the_event_and_a_reprocess_after_the_fix_numbers_it() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_2d"));
    String eventId = harness.receive("invoice.finalized", "in_2d");
    harness.shiftDatabaseClock(Duration.ofDays(-2));

    IssuanceUnitOfWork.Outcome stopped = harness.unitOfWork().process(eventId);

    assertThat(stopped.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(stopped.code()).isEqualTo(ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES);
    assertThat(harness.inbound().find(eventId).orElseThrow().nextAttemptAt()).isNull();
    assertThat(harness.numberedRows()).isZero();

    IssuanceReprocess.Result stillWrong =
        harness.reprocess().reprocess(new ReprocessRequest(eventId, "ops", "clock fixed?"));
    assertThat(stillWrong.code()).isEqualTo(ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES);

    harness.shiftDatabaseClock(Duration.ZERO);
    IssuanceReprocess.Result fixed =
        harness.reprocess().reprocess(new ReprocessRequest(eventId, "ops", "clock fixed"));
    assertThat(fixed.state()).isEqualTo(InboundState.COMPLETED);
    Issuance issued = harness.issuance("in_2d").orElseThrow();
    assertThat(issued.dating().orElseThrow().issueDate())
        .isEqualTo(LocalDate.ofInstant(harness.now(), harness.taxZone()));
  }

  // ------------------------------------------------------------------ T-clock-2s, M6

  /** A small disagreement is waited out: DEI-132 with a next attempt scheduled. */
  @Test
  void a_small_clock_disagreement_is_retried_with_backoff() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_2s"));
    String eventId = harness.receive("invoice.finalized", "in_2s");
    harness.shiftDatabaseClock(Duration.ofMinutes(10));

    IssuanceUnitOfWork.Outcome waiting = harness.unitOfWork().process(eventId);

    assertThat(waiting.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(waiting.code()).isEqualTo(ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES);
    assertThat(harness.inbound().find(eventId).orElseThrow().nextAttemptAt()).isNotNull();

    harness.shiftDatabaseClock(Duration.ZERO);
    harness.moveClockForward(Duration.ofMinutes(5));
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
    assertThat(datingOf(harness, "in_2s").cause()).contains(DateGapCause.NONE);
  }

  // ------------------------------------------------------------------ T-same-day

  @Test
  void an_invoice_numbered_on_its_supply_day_has_cause_none_and_no_finding() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_same"));
    String eventId = harness.receive("invoice.finalized", "in_same");

    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);

    IssueDating dating = datingOf(harness, "in_same");
    assertThat(dating.issueDate()).isEqualTo(dating.supplyDate().orElseThrow());
    assertThat(dating.cause()).contains(DateGapCause.NONE);
    assertThat(harness.findingRows(harness.issuance("in_same").orElseThrow().legalNumber().value()))
        .isZero();
  }
}
