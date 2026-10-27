package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.IssuanceState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Security review pass 1 of the resumed render hash mismatch PR, run on a merge with the stray
 * archive object PR (D-RTR-4). Added by whichever of the two PRs merges second: on either branch
 * alone {@code DEI-281} does not exist yet.
 */
class CipherProbePr53eAfterPr52Test {

  private static final ReconciliationSweep.Settings SETTINGS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);

  /** H1 lands at the recorded key after the DEI-244 burn: reported by the disposed check only. */
  @Test
  void probe_a_late_first_object_after_a_render_mismatch_burn_is_reported_as_disposed() {
    Row row = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_p53e_m1");
    String k1 = row.before().archiveKey();
    byte[] h1 = row.harness().archive().get(new ArchiveKey(k1)).orElseThrow();
    row.harness().archive().forget(k1);
    ResumedRenderMismatchPathsTest.retryUpgraded(row);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(row.now().voidRuleId()).isEqualTo(ErrorCodes.RENDER_NOT_REPRODUCIBLE);

    row.harness().archive().putDirectly(k1, h1);
    row.harness().moveClockForward(Duration.ofHours(7));
    row.harness().sweep(SETTINGS).sweep();

    assertThat(row.harness().openFindings())
        .extracting(ComplianceFinding::code)
        .contains("DEI-281", ErrorCodes.RECON_BURNED_NO_DOCUMENT)
        .doesNotContain(
            ErrorCodes.RECON_ARCHIVE_DRIFT,
            ErrorCodes.RECON_ORPHAN_OBJECT,
            ErrorCodes.RECON_STUCK_ISSUANCE);
    assertThat(row.harness().inbound().find("recon-in_p53e_m1")).isEmpty();
  }

  /** Other bytes at the recorded key after the burn: DEI-281 and drift, never an unknown object. */
  @Test
  void probe_other_bytes_after_a_render_mismatch_burn_are_disposed_and_drift() {
    Row row = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_p53e_m2");
    String k1 = row.before().archiveKey();
    row.harness().archive().forget(k1);
    ResumedRenderMismatchPathsTest.retryUpgraded(row);

    row.harness()
        .archive()
        .putDirectly(k1, "planted".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    row.harness().moveClockForward(Duration.ofHours(7));
    row.harness().sweep(SETTINGS).sweep();

    assertThat(row.harness().openFindings())
        .extracting(ComplianceFinding::code)
        .contains("DEI-281", ErrorCodes.RECON_ARCHIVE_DRIFT)
        .doesNotContain(ErrorCodes.RECON_ORPHAN_OBJECT);
  }
}
