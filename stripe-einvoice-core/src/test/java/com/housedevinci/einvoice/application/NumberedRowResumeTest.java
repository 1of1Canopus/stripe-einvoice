package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Every numbered row the edge table leaves undue has an operator path that resumes the same number
 * (D-SRE-2): the reprocess re-opens it in place, never to {@code RECEIVED}, and runs it through
 * {@code route} again. And the post-{@code FETCHED} conclusions that strand a number (D-SRE-5b).
 */
class NumberedRowResumeTest {

  private static final ReprocessRequest REQUEST =
      new ReprocessRequest("placeholder", "operator-1", "cause fixed");

  private static ReprocessRequest request(String eventId) {
    return new ReprocessRequest(eventId, REQUEST.actor(), REQUEST.reason());
  }

  /** A numbered row stopped with {@code code} and no retry, the shape the mapping leaves. */
  private static String stoppedWith(IssuanceTestHarness harness, String objectId, String code) {
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, objectId);
    harness.inbound().transition(eventId, InboundState.FAILED_ISSUANCE, code, harness.now(), null);
    return eventId;
  }

  @Test
  void a_numbered_row_stopped_as_an_unsubscribed_type_resumes_its_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = stoppedWith(harness, "in_sre_resume_205", ErrorCodes.UNSUBSCRIBED_EVENT_TYPE);
    String number = RetryEdgeFixture.number(harness, "in_sre_resume_205");

    IssuanceReprocess.Result result = harness.reprocess().reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(result.legalNumber()).isEqualTo(number);
  }

  @Test
  void a_numbered_row_stopped_by_a_revoked_key_resumes_once_the_key_is_fixed() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = stoppedWith(harness, "in_sre_resume_219", RetryEdgeFixture.ACCESS_REFUSED);
    String number = RetryEdgeFixture.number(harness, "in_sre_resume_219");

    IssuanceReprocess.Result result = harness.reprocess().reprocess(request(eventId));

    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(result.legalNumber()).isEqualTo(number);
  }

  @Test
  void a_key_still_revoked_ends_failed_issuance_again_with_the_number_kept() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = stoppedWith(harness, "in_sre_resume_still", RetryEdgeFixture.ACCESS_REFUSED);
    String number = RetryEdgeFixture.number(harness, "in_sre_resume_still");
    harness
        .source()
        .breakFor(
            "in_sre_resume_still",
            new EInvoiceException(RetryEdgeFixture.ACCESS_REFUSED, "status 401"));

    IssuanceReprocess.Result result = harness.reprocess().reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    InboundEvent row = RetryEdgeFixture.row(harness, eventId);
    assertThat(row.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(row.lastCode()).isEqualTo(RetryEdgeFixture.ACCESS_REFUSED);
    assertThat(row.nextAttempt()).isEmpty();
    assertThat(RetryEdgeFixture.number(harness, "in_sre_resume_still")).isEqualTo(number);
  }

  @Test
  void a_numbered_failed_mapping_row_is_reopened_in_place_and_resumes_its_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre_resume_mapping");
    String number = RetryEdgeFixture.number(harness, "in_sre_resume_mapping");
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);
    harness
        .inbound()
        .transition(
            eventId,
            InboundState.FAILED_MAPPING,
            ErrorCodes.CLOSED_FISCAL_YEAR,
            harness.now(),
            null);

    IssuanceReprocess.Result result = harness.reprocess().reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(result.legalNumber()).isEqualTo(number);
  }

  /**
   * QUESTIONS 48. A {@code MAPPED} row past its time limit is {@code EXPIRED} with its number still
   * open. The reprocess re-opens it in place, never to {@code RECEIVED}, and resumes that number.
   */
  @Test
  void a_numbered_expired_row_is_reopened_in_place_and_resumes_its_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre_resume_expired");
    String number = RetryEdgeFixture.number(harness, "in_sre_resume_expired");
    harness.inbound().transition(eventId, InboundState.MAPPED, "", harness.now(), null);
    harness
        .inbound()
        .transition(
            eventId, InboundState.EXPIRED, ErrorCodes.INBOUND_CEILING_REACHED, harness.now(), null);

    IssuanceReprocess.Result result = harness.reprocess().reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(result.legalNumber()).isEqualTo(number);
  }

  /**
   * D-SRE-5b. A numbered row resumed after its fiscal year closed ends {@code FAILED_MAPPING} with
   * an open number: the stuck check names it, and the reprocess is still its way back.
   */
  @Test
  void a_numbered_resume_after_the_year_closed_ends_failed_mapping_and_the_stuck_check_names_it() {
    IssuanceTestHarness harness = IssuanceTestHarness.createWithCutoff(Optional.of(Duration.ZERO));
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre_closed_year");
    harness.moveClockForward(Duration.ofHours(7));
    harness.sweep(new ReconciliationSweep.Settings(null, null, null, 0, 0)).sweep();
    harness.moveClockTo(Instant.parse("2027-01-02T09:00:00Z"));

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(harness.issuance("in_sre_closed_year").orElseThrow().state().open()).isTrue();
    assertThat(harness.openFindings())
        .anySatisfy(
            f -> {
              assertThat(f.code()).isEqualTo(ErrorCodes.RECON_STUCK_ISSUANCE);
              assertThat(f.subjectId()).isEqualTo("in_sre_closed_year");
            });
    assertThat(IssuanceReprocess.eligible(RetryEdgeFixture.row(harness, eventId))).isTrue();
  }

  /** D-SRE-5b. A totals refusal on a resume is final, with the number open and named. */
  @Test
  void a_numbered_resume_that_meets_a_totals_mismatch_ends_failed_totals_and_is_named() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre_totals");
    harness.source().with(TestInvoices.offByOneCentInOneBucket("in_sre_totals"));
    harness.moveClockForward(Duration.ofHours(7));

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);
    harness.sweep(new ReconciliationSweep.Settings(null, null, null, 0, 0)).sweep();

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_TOTALS);
    assertThat(harness.openFindings())
        .anySatisfy(
            f -> {
              assertThat(f.code()).isEqualTo(ErrorCodes.RECON_STUCK_ISSUANCE);
              assertThat(f.subjectId()).isEqualTo("in_sre_totals");
            });
  }

  /** D-SRE-8, at the statement: a re-open decided on a stale read records nothing. */
  @Test
  void an_in_place_re_open_on_a_stale_read_records_nothing() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = stoppedWith(harness, "in_sre_stale", RetryEdgeFixture.ACCESS_REFUSED);
    InboundEvent asRead = RetryEdgeFixture.row(harness, eventId);
    // Another run moved the row after this call read it: the stamp adds an attempt.
    harness.inbound().stampAttempt(eventId, asRead.state(), harness.now(), Duration.ofMinutes(1));

    var seq =
        harness
            .reprocessLedger()
            .reopenInPlaceAndRecord(
                request(eventId),
                harness.sellerId(),
                com.housedevinci.einvoice.domain.Mode.LIVE,
                asRead,
                harness.now());

    assertThat(seq).isEmpty();
    assertThat(harness.reprocessRecords(eventId)).isEmpty();
  }

  /** D-SRE-6, at the statement: the re-open to RECEIVED refuses a numbered row by itself. */
  @Test
  void the_re_open_to_received_refuses_a_row_whose_invoice_holds_a_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre_received_guard");
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);
    harness
        .inbound()
        .transition(
            eventId,
            InboundState.FAILED_MAPPING,
            ErrorCodes.CLOSED_FISCAL_YEAR,
            harness.now(),
            null);

    var seq =
        harness
            .reprocessLedger()
            .reopenAndRecord(
                request(eventId),
                harness.sellerId(),
                com.housedevinci.einvoice.domain.Mode.LIVE,
                ErrorCodes.REPROCESS_REQUESTED,
                harness.now());

    assertThat(seq).isEmpty();
    assertThat(RetryEdgeFixture.row(harness, eventId).state())
        .isEqualTo(InboundState.FAILED_MAPPING);
  }

  /** The unnumbered path is unchanged: a FAILED_MAPPING row with no number goes to RECEIVED. */
  @Test
  void an_unnumbered_failed_mapping_row_is_still_re_opened_to_received() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_sre_unnumbered"));
    String eventId = harness.receive("invoice.finalized", "in_sre_unnumbered");
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);
    harness
        .inbound()
        .transition(
            eventId,
            InboundState.FAILED_MAPPING,
            ErrorCodes.MAPPING_INCOMPLETE,
            harness.now(),
            null);

    IssuanceReprocess.Result result = harness.reprocess().reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
  }

  /** A numbered row in a state the in-place re-open does not take is refused, not re-opened. */
  @Test
  void a_numbered_running_row_is_not_eligible() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre_running");
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);

    IssuanceReprocess.Result result = harness.reprocess().reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REFUSED_NOT_ELIGIBLE);
    assertThat(RetryEdgeFixture.row(harness, eventId).state()).isEqualTo(InboundState.FETCHED);
  }

  /**
   * D-PR50-2. A MAPPED row that crashed before the claim holds no number. An outage maps it to
   * FAILED_ISSUANCE; past the retry ceiling only the reprocess can run it, and it does.
   */
  @Test
  void an_unnumbered_mapped_row_mapped_by_an_outage_is_reprocessed_to_completed() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pr50_unnumbered_mapped"));
    String eventId = harness.receive("invoice.finalized", "in_pr50_unnumbered_mapped");
    RetryEdgeFixture.moveTo(harness, eventId, InboundState.MAPPED);
    harness
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "stripe is down"));
    assertThat(harness.unitOfWork().process(eventId).state())
        .isEqualTo(InboundState.FAILED_ISSUANCE);
    harness.source().heal();
    harness.moveClockForward(Duration.ofDays(4));
    assertThat(harness.issuance("in_pr50_unnumbered_mapped")).isEmpty();

    IssuanceReprocess.Result result = harness.reprocess().reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(result.legalNumber()).isNotNull();
  }
}
