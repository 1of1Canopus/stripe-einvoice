package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.InboundState;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * D-PR9-F1: an inbound write before the number claim that loses to another worker.
 *
 * <p>Two workers run {@code process} on one event. Worker B is held inside the Stripe fetch, after
 * it read the row and before its {@code FETCHED} write. Worker A moves the row on meanwhile. When B
 * resumes, its write expects the state B read, finds another one, and B returns the stored outcome:
 * no {@code DEI-210}, and no row dragged back to {@code FETCHED}.
 */
class InboundWriteBeforeClaimTest {

  private static final long WAIT = 30;

  /**
   * A pair of latches: the held worker counts {@code reached} down and waits for {@code release}.
   */
  private static final class Gate {
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    void hold() {
      reached.countDown();
      try {
        if (!release.await(WAIT, TimeUnit.SECONDS)) {
          throw new IllegalStateException("the gate was never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted at the gate", e);
      }
    }

    boolean awaitReached() throws InterruptedException {
      return reached.await(WAIT, TimeUnit.SECONDS);
    }
  }

  /** The harness's own Stripe source, with its caller held after the read. */
  private static final class HeldSource implements StripeInvoiceSource {
    final Gate gate = new Gate();
    private final StripeInvoiceSource delegate;

    HeldSource(StripeInvoiceSource delegate) {
      this.delegate = delegate;
    }

    @Override
    public SourceInvoice fetchInvoice(String invoiceId) {
      SourceInvoice invoice = delegate.fetchInvoice(invoiceId);
      gate.hold();
      return invoice;
    }

    @Override
    public List<String> finalisedInvoiceIds(Instant from, Instant to) {
      return delegate.finalisedInvoiceIds(from, to);
    }

    @Override
    public String pinnedApiVersion() {
      return delegate.pinnedApiVersion();
    }
  }

  /** The harness's own renderer, with its caller held inside the render: past MAPPED and P1. */
  private static final class HeldRenderer implements DocumentRenderer {
    final Gate gate = new Gate();
    private final DocumentRenderer delegate;

    HeldRenderer(DocumentRenderer delegate) {
      this.delegate = delegate;
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
      gate.hold();
      return delegate.render(input);
    }
  }

  @Test
  void a_worker_held_in_the_fetch_while_another_completes_returns_the_completed_outcome()
      throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pr9f1_fetch_done"));
    String eventId = harness.receive("invoice.finalized", "in_pr9f1_fetch_done");

    HeldSource held = new HeldSource(harness.source());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> workerB =
          pool.submit(
              () ->
                  harness
                      .unitOfWorkWithSourceAndRenderer(held, harness.renderer())
                      .process(eventId));
      assertThat(held.gate.awaitReached()).as("worker B is inside the fetch").isTrue();

      IssuanceUnitOfWork.Outcome a = harness.unitOfWork().process(eventId);
      assertThat(a.state()).isEqualTo(InboundState.COMPLETED);

      held.gate.release.countDown();
      // Before the fix: DEI-210 "an inbound event in state COMPLETED cannot move to FETCHED".
      IssuanceUnitOfWork.Outcome b = workerB.get(WAIT, TimeUnit.SECONDS);
      assertThat(b.state()).isEqualTo(InboundState.COMPLETED);
      assertThat(b.legalNumber()).isEqualTo(a.legalNumber());
    } finally {
      pool.shutdownNow();
    }
    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .isEqualTo(InboundState.COMPLETED);
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  @Test
  void a_worker_held_in_the_fetch_does_not_drag_a_row_another_worker_mapped_back_to_fetched()
      throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pr9f1_fetch_mapped"));
    String eventId = harness.receive("invoice.finalized", "in_pr9f1_fetch_mapped");

    HeldSource heldFetchB = new HeldSource(harness.source());
    HeldPreflight heldPreflightB = new HeldPreflight(harness.renderer());
    HeldRenderer heldRenderA = new HeldRenderer(harness.renderer());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<IssuanceUnitOfWork.Outcome> workerB =
          pool.submit(
              () ->
                  harness
                      .unitOfWorkWithSourceAndRenderer(heldFetchB, heldPreflightB)
                      .process(eventId));
      assertThat(heldFetchB.gate.awaitReached()).as("worker B is inside the fetch").isTrue();

      Future<IssuanceUnitOfWork.Outcome> workerA =
          pool.submit(
              () ->
                  harness
                      .unitOfWorkWithSourceAndRenderer(harness.source(), heldRenderA)
                      .process(eventId));
      assertThat(heldRenderA.gate.awaitReached()).as("worker A is inside the render").isTrue();
      assertThat(harness.inbound().find(eventId).orElseThrow().state())
          .isEqualTo(InboundState.MAPPED);

      // B leaves the fetch, makes its FETCHED write, and stops again in the preflight.
      heldFetchB.gate.release.countDown();
      assertThat(heldPreflightB.gate.awaitReached()).as("worker B is in the preflight").isTrue();
      // Before the fix: FETCHED, because MAPPED -> FETCHED is an edge and B's write landed.
      assertThat(harness.inbound().find(eventId).orElseThrow().state())
          .as("the row A moved forward is not moved back")
          .isEqualTo(InboundState.MAPPED);

      heldRenderA.gate.release.countDown();
      IssuanceUnitOfWork.Outcome a = workerA.get(WAIT, TimeUnit.SECONDS);
      assertThat(a.state()).isEqualTo(InboundState.COMPLETED);

      heldPreflightB.gate.release.countDown();
      IssuanceUnitOfWork.Outcome b = workerB.get(WAIT, TimeUnit.SECONDS);
      assertThat(b.state()).isEqualTo(InboundState.COMPLETED);
      assertThat(b.legalNumber()).isEqualTo(a.legalNumber());
    } finally {
      heldRenderA.gate.release.countDown();
      heldFetchB.gate.release.countDown();
      heldPreflightB.gate.release.countDown();
      pool.shutdownNow();
    }
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  @Test
  void a_refusal_that_loses_to_a_run_in_flight_steps_aside_and_leaves_the_row_alone()
      throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pr9f1_refusal"));
    String eventId = harness.receive("invoice.finalized", "in_pr9f1_refusal");

    HeldSource heldFetchB = new HeldSource(harness.source());
    HeldRenderer heldRenderA = new HeldRenderer(harness.renderer());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<IssuanceUnitOfWork.Outcome> workerB =
          pool.submit(
              () ->
                  harness
                      .unitOfWorkWithSourceAndRenderer(heldFetchB, new RefusingPreflight())
                      .process(eventId));
      assertThat(heldFetchB.gate.awaitReached()).as("worker B is inside the fetch").isTrue();

      Future<IssuanceUnitOfWork.Outcome> workerA =
          pool.submit(
              () ->
                  harness
                      .unitOfWorkWithSourceAndRenderer(harness.source(), heldRenderA)
                      .process(eventId));
      assertThat(heldRenderA.gate.awaitReached()).as("worker A is inside the render").isTrue();

      heldFetchB.gate.release.countDown();
      IssuanceUnitOfWork.Outcome b = workerB.get(WAIT, TimeUnit.SECONDS);
      // Before the fix: B's FETCHED write drags the row back and its FAILED_MAPPING lands, a
      // terminal written under a run that holds a number.
      assertThat(b.state()).as("B answers with the stored state").isEqualTo(InboundState.MAPPED);
      assertThat(harness.inbound().find(eventId).orElseThrow().state())
          .isEqualTo(InboundState.MAPPED);

      heldRenderA.gate.release.countDown();
      assertThat(workerA.get(WAIT, TimeUnit.SECONDS).state()).isEqualTo(InboundState.COMPLETED);
    } finally {
      heldRenderA.gate.release.countDown();
      heldFetchB.gate.release.countDown();
      pool.shutdownNow();
    }
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  /** The harness's own renderer, with its caller held inside the preflight. */
  private static final class HeldPreflight implements DocumentRenderer {
    final Gate gate = new Gate();
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
      gate.hold();
      return delegate.preflight(input);
    }

    @Override
    public RenderedDocument render(DocumentInput input) {
      return delegate.render(input);
    }
  }

  /** A renderer whose preflight refuses, so its run concludes FAILED_MAPPING before P1. */
  private static final class RefusingPreflight implements DocumentRenderer {
    private final DocumentRenderer delegate = new DeterministicRenderer();

    @Override
    public boolean carriesSupplyDate() {
      return true;
    }

    @Override
    public PreflightReport preflight(MappingInput input) {
      return PreflightReport.refused(
          com.housedevinci.einvoice.domain.ErrorCodes.SELLER_PROFILE_INCOMPLETE);
    }

    @Override
    public RenderedDocument render(DocumentInput input) {
      return delegate.render(input);
    }
  }
}
