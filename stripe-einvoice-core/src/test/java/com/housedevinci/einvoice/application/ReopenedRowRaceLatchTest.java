package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * D-PR9-F2: the window behind the intermittent failure of {@code
 * CipherProbePr9Test.probe_a_reopened_row_run_twice_at_once_still_numbers_once}, held open with a
 * latch. Two runs of one re-opened event: run A holds inside its archive write (so it holds the
 * issuance row lock); run B reaches the lock while A is in flight.
 */
class ReopenedRowRaceLatchTest {

  private static DocumentRenderer repairable(IssuanceTestHarness harness, AtomicBoolean repaired) {
    DocumentRenderer real = harness.renderer();
    return new DocumentRenderer() {
      @Override
      public boolean carriesSupplyDate() {
        return true;
      }

      @Override
      public PreflightReport preflight(MappingInput input) {
        return repaired.get()
            ? PreflightReport.passed()
            : PreflightReport.refused(ErrorCodes.SELLER_PROFILE_INCOMPLETE);
      }

      @Override
      public RenderedDocument render(DocumentInput input) {
        return real.render(input);
      }
    };
  }

  /** A re-opened, repaired event, ready for two runs at once. */
  private static String reopened(
      IssuanceTestHarness harness, DocumentRenderer renderer, AtomicBoolean repaired, String id) {
    harness.source().with(TestInvoices.finalised(id));
    String eventId = harness.receive("invoice.finalized", id);
    assertThat(harness.unitOfWorkWithRenderer(renderer).process(eventId).state())
        .isEqualTo(InboundState.FAILED_MAPPING);
    repaired.set(true);
    assertThat(
            harness
                .reprocessLedger()
                .reopenAndRecord(
                    new ReprocessRequest(eventId, "ops", "in-flight re-open for the race"),
                    harness.sellerId(),
                    com.housedevinci.einvoice.domain.Mode.LIVE,
                    ErrorCodes.REPROCESS_REQUESTED,
                    Instant.parse("2026-01-16T09:05:00Z"))
                .isPresent())
        .isTrue();
    return eventId;
  }

  @Test
  void a_second_run_of_the_same_event_during_the_put_is_in_flight_and_never_wins()
      throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    AtomicBoolean repaired = new AtomicBoolean(false);
    DocumentRenderer renderer = repairable(harness, repaired);
    String eventId = reopened(harness, renderer, repaired, "in_pr9_latch");

    CountDownLatch inPut = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService workerA = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> a =
          workerA.submit(
              () ->
                  harness
                      .unitOfWorkWith(
                          renderer,
                          TestValidators.passing(),
                          ArchiveRetryFixture.READER,
                          ArchiveRetryFixture.heldPut(harness.archive(), inPut, release))
                      .process(eventId));
      assertThat(inPut.await(30, TimeUnit.SECONDS)).as("run A is inside its PUT").isTrue();

      IssuanceUnitOfWork.Outcome b = harness.unitOfWorkWithRenderer(renderer).process(eventId);
      release.countDown();
      IssuanceUnitOfWork.Outcome outcomeA = a.get(30, TimeUnit.SECONDS);

      assertThat(outcomeA.state()).isEqualTo(InboundState.COMPLETED);
      assertThat(harness.inbound().find(eventId).orElseThrow().state())
          .as("the event ends on the first run's outcome")
          .isEqualTo(InboundState.COMPLETED);
      // Run B met the row lock while A was inside its PUT: it read nothing, rendered nothing,
      // and answers "in flight" (DEI-245). This is the window behind the intermittent failure.
      assertThat(b.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
      assertThat(b.code()).isEqualTo(ErrorCodes.ISSUANCE_IN_FLIGHT);
    } finally {
      release.countDown();
      workerA.shutdownNow();
    }
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
  }

  @Test
  void a_second_run_refused_by_the_lock_after_the_first_concluded_answers_from_it()
      throws Exception {
    // The late-write window: B's lock attempt was refused while A held it, and A then concluded
    // COMPLETED before B wrote its answer. B must not write over A's outcome.
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    AtomicBoolean repaired = new AtomicBoolean(false);
    DocumentRenderer renderer = repairable(harness, repaired);
    String eventId = reopened(harness, renderer, repaired, "in_pr9_late");
    IssuanceRowLock refusedAfterTheFirstConcluded =
        new IssuanceRowLock() {
          @Override
          public <T> java.util.Optional<T> whileHeld(
              long issuanceId, java.util.function.Function<Held, T> work) {
            // "held by another worker" at the TRY; that worker then concludes before B writes
            harness.unitOfWorkWithRenderer(renderer).process(eventId);
            return java.util.Optional.empty();
          }

          @Override
          public void verifySessionPinning() {
            harness.store().verifySessionPinning();
          }
        };

    IssuanceUnitOfWork.Outcome b =
        harness.unitOfWorkWithLock(refusedAfterTheFirstConcluded, renderer).process(eventId);

    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .as("the first run's outcome stands")
        .isEqualTo(InboundState.COMPLETED);
    assertThat(b.state()).as("B answers from what A recorded").isEqualTo(InboundState.COMPLETED);
    assertThat(harness.numberedRows()).isEqualTo(1);
  }
}
