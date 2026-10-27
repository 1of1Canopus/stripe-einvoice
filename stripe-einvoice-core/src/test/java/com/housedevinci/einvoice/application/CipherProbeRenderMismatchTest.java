package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_NO_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.burnLine;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.IssuanceState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The security spec review's probes for the resumed render hash mismatch design (2026-10-20). Each
 * failed on the main branch before this change: the row stayed {@code FAILED_ARCHIVE} with {@code
 * DEI-240}, nothing was chained, and the sweep re-enqueued it.
 */
class CipherProbeRenderMismatchTest {

  private static final ReconciliationSweep.Settings SETTINGS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);

  @Test
  void probe_a_resumed_render_with_another_hash_is_not_recorded() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_probe_rm1");

    ResumedRenderMismatchPathsTest.retryUpgraded(row);

    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(row.now().voidRuleId()).isEqualTo(ErrorCodes.RENDER_NOT_REPRODUCIBLE);
    assertThat(row.now().documentSha256()).isEqualTo(row.before().documentSha256());
    assertThat(row.chained())
        .containsExactly(burnLine(row.now(), ErrorCodes.RENDER_NOT_REPRODUCIBLE));
  }

  @Test
  void probe_a_render_mismatch_is_re_enqueued_by_the_sweep() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_probe_rm2");
    ResumedRenderMismatchPathsTest.retryUpgraded(row);
    row.harness().moveClockForward(Duration.ofHours(7));

    row.harness().sweep(SETTINGS).sweep();

    assertThat(row.harness().openFindings())
        .extracting(ComplianceFinding::code)
        .contains(ErrorCodes.RECON_BURNED_NO_DOCUMENT)
        .doesNotContain(ErrorCodes.RECON_STUCK_ISSUANCE, ErrorCodes.RECON_MISSING_ISSUANCE);
    assertThat(row.harness().inbound().find("recon-in_probe_rm2")).isEmpty();
  }

  @Test
  void probe_a_resumed_render_with_another_extension_is_not_recorded() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_probe_rm3");

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(
            ResumedRenderMismatchPathsTest.otherExtension(row.harness()),
            TestValidators.passing(),
            ArchiveRetryFixture.READER);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.RENDER_NOT_REPRODUCIBLE);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(row.harness().archive().size()).isZero();
  }
}
