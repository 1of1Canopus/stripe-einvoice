package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static com.housedevinci.einvoice.application.DisposedNumberArchivePathsTest.SETTINGS;
import static com.housedevinci.einvoice.application.DisposedNumberArchivePathsTest.burned;
import static com.housedevinci.einvoice.application.DisposedNumberArchivePathsTest.codesFor;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.application.DisposedNumberArchivePathsTest.Burned;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The security review's probes for D-RTR-4 (burned-number-archive-orphan design, spec review of
 * 2026-10-20). Each was red before the fifth sweep check. The two filesystem probes of that review
 * (D-RTR-8) were closed by PR 41 and live in {@code FilesystemArchiveStoreTest}.
 */
class CipherProbeDisposedArchiveTest {

  @Test
  void probe_a_late_object_at_a_burned_key_is_not_reported() {
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_probe_burn");
    b.land();
    b.harness().moveClockForward(Duration.ofHours(1));

    b.harness().sweep(SETTINGS).sweep();

    assertThat(codesFor(b.harness(), "in_probe_burn"))
        .contains(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
  }

  @Test
  void probe_a_late_object_at_a_voided_key_is_not_reported() {
    Row row = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_probe_void");
    IssuanceTestHarness harness = row.harness();
    String key = row.before().archiveKey();
    byte[] first =
        harness.archive().get(new com.housedevinci.einvoice.domain.ArchiveKey(key)).orElseThrow();
    harness.archive().forget(key);
    harness
        .store()
        .voidUnused(new VoidRequest(harness.sellerId(), Mode.LIVE, "in_probe_void", "ops", ""));
    harness.archive().putDirectly(key, first);
    harness.moveClockForward(Duration.ofHours(1));

    harness.sweep(SETTINGS).sweep();

    assertThat(codesFor(harness, "in_probe_void"))
        .contains(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
  }

  @Test
  void probe_other_bytes_at_a_disposed_key_are_not_drift() {
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_probe_drift");
    b.harness().archive().putDirectly(b.key(), "other".getBytes(StandardCharsets.UTF_8));
    b.harness().moveClockForward(Duration.ofHours(1));

    b.harness().sweep(SETTINGS).sweep();

    assertThat(codesFor(b.harness(), "in_probe_drift"))
        .contains(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER, ErrorCodes.RECON_ARCHIVE_DRIFT);
  }

  @Test
  void probe_a_read_failure_on_a_disposed_key_is_counted_clean() {
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_probe_fail");
    b.land();
    DisposedArchiveFixture.Counting failing =
        new DisposedArchiveFixture.Counting(b.harness().archive())
            .failingOn(b.key(), new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "503"));
    b.harness().moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = b.harness().sweepWith(SETTINGS, failing).sweep();

    assertThat(result.disposedUnverified()).isEqualTo(1);
    assertThat(result.total()).isPositive();
  }
}
