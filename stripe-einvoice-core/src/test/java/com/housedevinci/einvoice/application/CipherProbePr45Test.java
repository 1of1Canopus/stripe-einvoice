package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.InboundState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 45 pass 1 (D-PR45-1). Two runs on ONE event both reach the number claim: the
 * sweeper re-picks a MAPPED row (due at once) while the worker that wrote it is rendering. The
 * second run's FETCHED and MAPPED writes land (QUESTIONS 44), it loses the claim, and the second
 * run resumes the winner's NUMBERED row and documents it. When the winner's own render then fails,
 * its post-claim FAILED_ISSUANCE write is an unconditional transition from COMPLETED: DEI-210
 * escapes process, the D-PR9-F1 symptom on the post-claim side.
 *
 * <p>Install under stripe-einvoice-core/src/test/java/com/housedevinci/einvoice/application/.
 *
 * <p>Build of the one-run-per-event design (Revision 2): the probe went in unchanged and was red on
 * main, 3 of 3. Its escape assertions stand as written. The outcome assertions that described the
 * race (the second run documenting the number) now describe the design's T2 and T3: the second run
 * of one invoice is busy ({@code DEI-217}), writes nothing, and the first run's own conclusion is
 * the row's conclusion.
 */
class CipherProbePr45Test {

  private static final long WAIT = 30;

  /** Holds the winner inside the render (past MAPPED and P1); then renders or throws. */
  private static final class HeldRenderer implements DocumentRenderer {
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private final DocumentRenderer delegate;
    private final boolean fail;

    HeldRenderer(DocumentRenderer delegate, boolean fail) {
      this.delegate = delegate;
      this.fail = fail;
    }

    @Override
    public boolean carriesSupplyDate() {
      return delegate.carriesSupplyDate();
    }

    @Override
    public PreflightReport preflight(MappingInput input) {
      return delegate.preflight(input);
    }

    @Override
    public RenderedDocument render(DocumentInput input) {
      reached.countDown();
      try {
        if (!release.await(WAIT, TimeUnit.SECONDS)) {
          throw new IllegalStateException("never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      if (fail) {
        throw new IllegalStateException("renderer bug in the winner");
      }
      return delegate.render(input);
    }
  }

  @Test
  void probe_a_winner_that_succeeds_after_a_same_event_resume_concludes_without_an_exception()
      throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pr45_resume_same_event"));
    String eventId = harness.receive("invoice.finalized", "in_pr45_resume_same_event");
    HeldRenderer heldA = new HeldRenderer(harness.renderer(), false);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Throwable escaped = null;
    IssuanceUnitOfWork.Outcome a = null;
    IssuanceUnitOfWork.Outcome b;
    try {
      Future<IssuanceUnitOfWork.Outcome> workerA =
          pool.submit(
              () ->
                  harness
                      .unitOfWorkWithSourceAndRenderer(harness.source(), heldA)
                      .process(eventId));
      assertThat(heldA.reached.await(WAIT, TimeUnit.SECONDS)).isTrue();
      // The sweeper's run on the same MAPPED row while A renders.
      b = harness.unitOfWork().process(eventId);
      heldA.release.countDown();
      try {
        a = workerA.get(WAIT, TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException e) {
        escaped = e.getCause();
      }
    } finally {
      heldA.release.countDown();
      pool.shutdownNow();
    }
    assertThat(escaped).as("the winner must not throw").isNull();
    assertThat(a.state()).isEqualTo(InboundState.COMPLETED);
    // T2/T3: the sweeper's run is busy and writes nothing.
    assertThat(b.code())
        .isEqualTo(com.housedevinci.einvoice.domain.ErrorCodes.INBOUND_RUN_IN_PROGRESS);
    assertThat(b.state()).isEqualTo(InboundState.MAPPED);
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  @Test
  void probe_a_winner_whose_render_fails_after_a_same_event_resume_escapes_dei_210()
      throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pr45_loser_then_fail"));
    String eventId = harness.receive("invoice.finalized", "in_pr45_loser_then_fail");
    HeldRenderer heldA = new HeldRenderer(harness.renderer(), true);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Throwable escaped = null;
    try {
      Future<IssuanceUnitOfWork.Outcome> workerA =
          pool.submit(
              () ->
                  harness
                      .unitOfWorkWithSourceAndRenderer(harness.source(), heldA)
                      .process(eventId));
      assertThat(heldA.reached.await(WAIT, TimeUnit.SECONDS)).isTrue();
      IssuanceUnitOfWork.Outcome sweeper = harness.unitOfWork().process(eventId);
      assertThat(sweeper.code())
          .isEqualTo(com.housedevinci.einvoice.domain.ErrorCodes.INBOUND_RUN_IN_PROGRESS);
      heldA.release.countDown();
      try {
        workerA.get(WAIT, TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException e) {
        escaped = e.getCause();
      }
    } finally {
      heldA.release.countDown();
      pool.shutdownNow();
    }
    assertThat(escaped).as("the winner's failure write must not escape (was DEI-210?)").isNull();
    // T2: one run, one render. The winner's render failed, so the winner's failure is the row's
    // conclusion: FAILED_ISSUANCE from MAPPED, a legal edge, and no document.
    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .as("the only run that rendered concluded it")
        .isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(harness.archive().size()).isZero();
  }

  /** Holds a run inside the preflight: after findBySource, before its MAPPED write. */
  private static final class HeldPreflight implements DocumentRenderer {
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private final DocumentRenderer delegate;

    HeldPreflight(DocumentRenderer delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean carriesSupplyDate() {
      return delegate.carriesSupplyDate();
    }

    @Override
    public PreflightReport preflight(MappingInput input) {
      reached.countDown();
      try {
        if (!release.await(WAIT, TimeUnit.SECONDS)) {
          throw new IllegalStateException("never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return delegate.preflight(input);
    }

    @Override
    public RenderedDocument render(DocumentInput input) {
      return delegate.render(input);
    }
  }

  /**
   * The carry-on-behind branch. B saw no issuance, waits in the preflight; A passes MAPPED and P1
   * and renders. B's MAPPED step finds MAPPED, carries on, resumes A's number at P1 and completes
   * the row. A's render then fails: its FAILED_ISSUANCE write (no compare, after P1) is DEI-210.
   */
  @Test
  void probe_a_carry_on_run_completes_first_and_the_winner_failure_escapes_dei_210()
      throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pr45_carry_on_loser"));
    String eventId = harness.receive("invoice.finalized", "in_pr45_carry_on_loser");
    HeldPreflight heldB = new HeldPreflight(harness.renderer());
    HeldRenderer heldA = new HeldRenderer(harness.renderer(), true);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Throwable escaped = null;
    IssuanceUnitOfWork.Outcome b;
    try {
      Future<IssuanceUnitOfWork.Outcome> workerB =
          pool.submit(
              () ->
                  harness
                      .unitOfWorkWithSourceAndRenderer(harness.source(), heldB)
                      .process(eventId));
      assertThat(heldB.reached.await(WAIT, TimeUnit.SECONDS)).isTrue();
      Future<IssuanceUnitOfWork.Outcome> workerA =
          pool.submit(
              () ->
                  harness
                      .unitOfWorkWithSourceAndRenderer(harness.source(), heldA)
                      .process(eventId));
      // T3: B holds the run lock in its preflight, so A is busy at once: it never reaches P1, never
      // renders, and writes nothing. The carry-on-behind branch no longer exists.
      try {
        IssuanceUnitOfWork.Outcome a = workerA.get(WAIT, TimeUnit.SECONDS);
        assertThat(a.code())
            .isEqualTo(com.housedevinci.einvoice.domain.ErrorCodes.INBOUND_RUN_IN_PROGRESS);
      } catch (java.util.concurrent.ExecutionException e) {
        escaped = e.getCause();
      }
      assertThat(heldA.reached.getCount()).as("A never rendered").isEqualTo(1);
      heldB.release.countDown();
      b = workerB.get(WAIT, TimeUnit.SECONDS);
      assertThat(b.state()).isEqualTo(InboundState.COMPLETED);
    } finally {
      heldA.release.countDown();
      heldB.release.countDown();
      pool.shutdownNow();
    }
    assertThat(escaped).as("A must not throw").isNull();
    assertThat(harness.archive().size()).isEqualTo(1);
    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .isEqualTo(InboundState.COMPLETED);
  }
}
