package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * D-RTR-7 (suspected, LOW): two workers run the same invoice at once, for example during a rolling
 * deploy. Worker A (old renderer) writes the ARCHIVING marker and is inside its PUT. Worker B (new
 * renderer) resumes the row, reads the key before A's object lands, finds nothing, renders other
 * bytes, is refused, and burns the row through ARCHIVING -> FAILED_VALIDATION. A's PUT then lands.
 *
 * <p>The probe asserts the safe outcome: a number whose document is in the archive is never
 * recorded as burned. It reproduced before the row lock (B DEI-250, A DEI-113, row
 * FAILED_VALIDATION, one object) and is green with it.
 */
class CipherProbeArchiveRetryRaceTest {

  /** The harness archive, except that a PUT waits for the test before it stores anything. */
  private static ArchiveStore heldPut(
      InMemoryArchiveStore inner, CountDownLatch inPut, CountDownLatch release) {
    return new ArchiveStore() {
      @Override
      public boolean supportsAtomicCreate() {
        return true;
      }

      @Override
      public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
        inPut.countDown();
        try {
          if (!release.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the probe never released the PUT");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
        return inner.putIfAbsent(key, bytes);
      }

      @Override
      public Optional<byte[]> get(ArchiveKey key) {
        return inner.get(key);
      }

      @Override
      public List<String> list(String prefix, int limit) {
        return inner.list(prefix, limit);
      }

      @Override
      public String describe() {
        return "held archive (test)";
      }
    };
  }

  @Test
  void probe_a_concurrent_resume_does_not_burn_a_number_whose_put_is_in_flight() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create().withoutRunLock();
    harness.source().with(TestInvoices.finalised("in_rtr7"));
    String finalized = harness.receive("invoice.finalized", "in_rtr7");
    String paid = harness.receive("invoice.paid", "in_rtr7");
    InMemoryArchiveStore inner = harness.archive();
    CountDownLatch inPut = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService workerA = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> a =
          workerA.submit(
              () ->
                  harness
                      .unitOfWorkWith(
                          harness.renderer(),
                          TestValidators.passing(),
                          ArchiveRetryFixture.READER,
                          heldPut(inner, inPut, release))
                      .process(finalized));
      assertThat(inPut.await(30, TimeUnit.SECONDS)).as("worker A reached its PUT").isTrue();
      assertThat(harness.issuance("in_rtr7").orElseThrow().state())
          .isEqualTo(IssuanceState.ARCHIVING);

      // Worker B, new renderer: resumes the ARCHIVING row while A's PUT is in flight.
      IssuanceUnitOfWork.Outcome b =
          harness
              .unitOfWorkWith(
                  ArchiveRetryFixture.backdating(harness, 7),
                  TestValidators.passing(),
                  ArchiveRetryFixture.READER,
                  inner)
              .process(paid);
      release.countDown();
      IssuanceUnitOfWork.Outcome outcomeA = a.get(30, TimeUnit.SECONDS);

      Issuance row = harness.issuance("in_rtr7").orElseThrow();
      System.err.println(
          "D-RTR-7 probe: worker B "
              + b.code()
              + ", worker A "
              + outcomeA.code()
              + ", row "
              + row.state()
              + ", objects "
              + inner.size());
      assertThat(row.state() == IssuanceState.FAILED_VALIDATION && inner.size() == 1)
          .as("a number whose document is in the archive must not be recorded as burned")
          .isFalse();
      // With the row lock: B found A's lock, read and rendered nothing, and retries; A issued.
      assertThat(b.code())
          .isEqualTo(com.housedevinci.einvoice.domain.ErrorCodes.ISSUANCE_IN_FLIGHT);
      assertThat(harness.inbound().find(paid).orElseThrow().nextAttemptAt()).isNotNull();
      assertThat(row.state()).isEqualTo(IssuanceState.ISSUED);
    } finally {
      release.countDown();
      workerA.shutdownNow();
    }
  }
}
