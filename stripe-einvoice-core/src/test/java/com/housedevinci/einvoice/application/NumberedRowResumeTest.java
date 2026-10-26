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
}
