package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * D-PR9-F1: the deterministic form of the flake in
 * CipherProbePr9Test.probe_a_reopened_row_run_twice_at_once_still_numbers_once.
 *
 * <p>Two workers run {@code process} on one re-opened event. Worker B reads the row, fetches, and
 * finds no issuance yet. It is held inside the preflight (after findBySource, before the MAPPED
 * write) while worker A runs the whole event to COMPLETED. When B resumes, it writes MAPPED over a
 * COMPLETED row. The state machine refuses with DEI-210 and the exception escapes {@code process}.
 * The second worker on a finished event must conclude idempotently instead: COMPLETED, one number,
 * one chain entry, one archive object.
 *
 * <p>Install under stripe-einvoice-core/src/test/java/com/housedevinci/einvoice/application/.
 */
class CipherProbePr9FlakeTest {

  /** Passes the preflight; the profile is corrected. */
  private static class PassingRenderer implements DocumentRenderer {
    private final DeterministicRenderer delegate = new DeterministicRenderer();

    @Override
    public boolean carriesSupplyDate() {
      return true;
    }

    @Override
    public PreflightReport preflight(MappingInput input) {
      return PreflightReport.passed();
    }

    @Override
    public RenderedDocument render(DocumentInput input) {
      return delegate.render(input);
    }
  }

  /** Refuses the preflight, so the event reaches FAILED_MAPPING and can be re-opened. */
  private static final class RefusingRenderer extends PassingRenderer {
    @Override
    public PreflightReport preflight(MappingInput input) {
      return PreflightReport.refused(ErrorCodes.SELLER_PROFILE_INCOMPLETE);
    }
  }

  /** Holds its caller inside the preflight until the other worker has finished the event. */
  private static final class GatedRenderer extends PassingRenderer {
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public PreflightReport preflight(MappingInput input) {
      reached.countDown();
      boolean released = false;
      while (!released) {
        try {
          released = release.await(30, TimeUnit.SECONDS);
          if (!released) {
            return PreflightReport.refused(ErrorCodes.PREFLIGHT_FAILED);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return PreflightReport.refused(ErrorCodes.PREFLIGHT_FAILED);
        }
      }
      return PreflightReport.passed();
    }
  }

  @Test
  void probe_second_worker_on_a_reopened_row_completed_meanwhile_is_not_refused_with_dei_210()
      throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_cipher_pr9f1"));
    String eventId = harness.receive("invoice.finalized", "in_cipher_pr9f1");
    assertThat(harness.unitOfWorkWithRenderer(new RefusingRenderer()).process(eventId).state())
        .isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(
            harness
                .reprocessLedger()
                .reopenAndRecord(
                    new ReprocessRequest(eventId, "ops-jane", "in-flight re-open for the race"),
                    harness.sellerId(),
                    Mode.LIVE,
                    ErrorCodes.REPROCESS_REQUESTED,
                    Instant.parse("2026-01-16T09:05:00Z"))
                .isPresent())
        .isTrue();

    GatedRenderer gated = new GatedRenderer();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // Worker B: passes findBySource with no issuance, then waits inside the preflight.
      Future<IssuanceUnitOfWork.Outcome> workerB =
          pool.submit(() -> harness.unitOfWorkWithRenderer(gated).process(eventId));
      assertThat(gated.reached.await(30, TimeUnit.SECONDS))
          .as("worker B reached the preflight")
          .isTrue();

      // Worker A: the whole event, start to finish, while B is held.
      assertThat(harness.unitOfWorkWithRenderer(new PassingRenderer()).process(eventId).state())
          .isEqualTo(InboundState.COMPLETED);

      gated.release.countDown();
      // Current code: ExecutionException wrapping EInvoiceException DEI-210
      // "an inbound event in state COMPLETED cannot move to MAPPED".
      assertThat(workerB.get(30, TimeUnit.SECONDS).state())
          .as("the second worker on a finished event concludes idempotently")
          .isEqualTo(InboundState.COMPLETED);
    } finally {
      pool.shutdownNow();
    }
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
    assertThat(harness.archive().size()).isEqualTo(1);
  }
}
