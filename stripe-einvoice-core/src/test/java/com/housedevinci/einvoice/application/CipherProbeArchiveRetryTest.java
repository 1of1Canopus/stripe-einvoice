package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.ARCHIVING_NO_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.ARCHIVING_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_NO_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The security spec review's probes for the archive retry refusal design (2026-10-19), probes 1 to
 * 5, 7 and 8. Probes 6 and 9 are database probes, in {@code CipherProbeArchiveRetryTriggerTest}.
 * Each failed on the main branch before this change, or pins a refusal by design.
 */
class CipherProbeArchiveRetryTest {

  /** Probe 1 (D-DD-B1): before the fix the event ended on DEI-113 and the row stayed open. */
  @Test
  void probe_a_failed_archive_refusal_ends_on_illegal_transition() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_probe1");

    IssuanceUnitOfWork.Outcome outcome = row.retryBackdated();

    assertThat(outcome.code()).isEqualTo(ErrorCodes.VALIDATION_REFUSED);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(row.now().voidRuleId()).isEqualTo(DocumentDatesRule.RULE_ID);
  }

  /** Probe 2 (Q1): the same from a crash-resumed ARCHIVING row with no object. */
  @Test
  void probe_a_crashed_archiving_refusal_ends_on_illegal_transition() {
    Row row = prepare(ARCHIVING_NO_OBJECT, "in_probe2");

    IssuanceUnitOfWork.Outcome outcome = row.retryBackdated();

    assertThat(outcome.code()).isEqualTo(ErrorCodes.VALIDATION_REFUSED);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
  }

  /** Probe 3 (D-RTR-1): the object exists, so the number is issued, never burned. */
  @Test
  void probe_a_resumed_row_whose_object_exists_is_not_burned() {
    Row row = prepare(ARCHIVING_WITH_OBJECT, "in_probe3");
    ArchiveRetryFixture.Counting refusing =
        new ArchiveRetryFixture.Counting(TestValidators.failing("WOULD-BURN"));

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(row.harness().renderer(), refusing, ArchiveRetryFixture.READER);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(row.now().state()).isEqualTo(IssuanceState.ISSUED);
    assertThat(refusing.calls).hasValue(0);
  }

  /** Probe 4 (D-RTR-1): the PUT answered with a timeout but landed. */
  @Test
  void probe_a_timed_out_put_that_landed_is_not_burned() {
    Row row = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_probe4");
    ArchiveRetryFixture.Counting refusing =
        new ArchiveRetryFixture.Counting(TestValidators.failing("WOULD-BURN"));

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(
            ArchiveRetryFixture.backdating(row.harness(), 7), refusing, ArchiveRetryFixture.READER);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(row.now().state()).isEqualTo(IssuanceState.ISSUED);
    assertThat(refusing.calls).hasValue(0);
  }

  /** Probe 5 (D-RTR-1): "I could not look" is not "there is nothing". */
  @Test
  void probe_an_archive_read_failure_on_resume_does_not_burn() {
    Row row = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_probe5");

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(
            ArchiveRetryFixture.backdating(row.harness(), 7),
            TestValidators.passing(),
            ArchiveRetryFixture.READER,
            ArchiveRetryFixture.unreadable(
                row.harness().archive(),
                new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "503")));

    assertThat(outcome.code()).isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_ARCHIVE);
    assertThat(row.now().voidRuleId()).isEmpty();
    assertThat(row.event().nextAttemptAt()).isNotNull();
  }

  /** Probe 7a (D-RTR-3): a render refusal on a resumed row is recorded on the row. */
  @Test
  void probe_a_render_refusal_on_retry_is_recorded() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_probe7a");

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(
            ArchiveRetryFixture.throwing(
                row.harness(), new EInvoiceException(ErrorCodes.SELLER_PROFILE_INCOMPLETE, "x")),
            TestValidators.passing(),
            ArchiveRetryFixture.READER);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.SELLER_PROFILE_INCOMPLETE);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(row.now().voidRuleId()).isEqualTo(ErrorCodes.SELLER_PROFILE_INCOMPLETE);
  }

  /** Probe 7b (D-RTR-3): a NOT_EVALUATED verdict on a resumed row is recorded on the row. */
  @Test
  void probe_not_evaluated_on_retry_is_recorded() {
    Row row = prepare(ARCHIVING_NO_OBJECT, "in_probe7b");

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(
            row.harness().renderer(),
            TestValidators.notEvaluated("SCHEMATRON"),
            ArchiveRetryFixture.READER);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.VALIDATION_NOT_EVALUATED);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
  }

  /** Probe 8: a burned retry is reported once and never enqueued again. */
  @Test
  void probe_a_burned_retry_is_never_re_enqueued() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_probe8");
    row.retryBackdated();
    row.harness().moveClockForward(Duration.ofHours(7));

    ReconciliationSweep.Result result =
        row.harness()
            .sweep(
                new ReconciliationSweep.Settings(
                    Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500))
            .sweep();

    assertThat(result.missingIssuance()).isZero();
    assertThat(result.stuckIssuances()).isZero();
    assertThat(row.harness().inbound().find("recon-in_probe8")).isEmpty();
  }
}
