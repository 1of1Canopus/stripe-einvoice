package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The security review's round-2 findings on the sweeper retry edges design, D-SRE-6 to D-SRE-9, and
 * the D-SRE-2 probe, each red before the build and green after it.
 */
class SweeperRetryEdgesProbeTest {

  private static final long WAIT = 30;

  /**
   * D-SRE-6 (a). A test-mode instance on the live database runs a live {@code FETCHED} row that
   * holds a number. Asked with its own mode, the issuance table says "no number"; the row must
   * still be read as numbered, so the refusal is mapped and the number stays findable.
   */
  @Test
  void probe_a_test_instance_moves_a_live_numbered_fetched_row_to_a_refusal() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre6_fetched");
    String number = RetryEdgeFixture.number(harness, "in_sre6_fetched");
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);

    assertThatCode(() -> harness.unitOfWorkInMode(Mode.TEST).process(eventId))
        .doesNotThrowAnyException();

    InboundEvent row = RetryEdgeFixture.row(harness, eventId);
    assertThat(row.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(row.lastCode()).isEqualTo(ErrorCodes.MODE_MISMATCH);
    assertThat(RetryEdgeFixture.number(harness, "in_sre6_fetched")).isEqualTo(number);
  }

  /**
   * D-SRE-6 (b). A reprocess called on a test-mode instance for a numbered live {@code
   * FAILED_MAPPING} row must not re-open it to {@code RECEIVED}, the "never holds a number" state.
   */
  @Test
  void probe_a_reprocess_from_another_mode_reopens_a_numbered_row_to_received() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre6_mapping");
    String number = RetryEdgeFixture.number(harness, "in_sre6_mapping");
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);
    harness
        .inbound()
        .transition(
            eventId,
            InboundState.FAILED_MAPPING,
            ErrorCodes.CLOSED_FISCAL_YEAR,
            harness.now(),
            null);

    IssuanceReprocess.Result result =
        harness
            .reprocessFor(harness.unitOfWorkInMode(Mode.TEST))
            .reprocess(new ReprocessRequest(eventId, "operator-1", "year reopened"));

    InboundEvent row = RetryEdgeFixture.row(harness, eventId);
    assertThat(row.state())
        .as("a numbered row is never re-opened to a state that cannot hold a number")
        .isNotIn(RetryEdgeFixture.PRE_FETCHED);
    assertThat(row.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(result.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(RetryEdgeFixture.number(harness, "in_sre6_mapping")).isEqualTo(number);
  }

  /**
   * D-SRE-7. The purge must keep an inbound row whose invoice holds an open number past retention:
   * the reprocess needs that row to resume the number. It leaves once the number resolves.
   */
  @Test
  void probe_the_purge_deletes_a_row_whose_number_is_still_open() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createOn(PostgresSupport.freshDatabase("sre_purge"));
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre7_purge");
    var cutoff = harness.now().plus(Duration.ofDays(31));

    harness.inbound().purgeOlderThan(cutoff, 10);

    assertThat(harness.inbound().find(eventId))
        .as("the row of an open number outlives retention")
        .isPresent();

    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
    harness.inbound().purgeOlderThan(cutoff, 10);
    assertThat(harness.inbound().find(eventId))
        .as("once the number is issued the row leaves at the next purge")
        .isEmpty();
  }

  /**
   * D-SRE-8. Two operator calls on one numbered row that the in-place re-open accepts: one ledger
   * entry, one run. The first run is held inside the render while the second call is made.
   */
  @Test
  void two_reprocess_calls_on_one_numbered_row_record_one_entry_and_run_once() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre8_twice");
    String number = RetryEdgeFixture.number(harness, "in_sre8_twice");
    // Stopped with no retry: the shape a revoked key leaves on a numbered row.
    harness
        .inbound()
        .transition(
            eventId,
            InboundState.FAILED_ISSUANCE,
            RetryEdgeFixture.ACCESS_REFUSED,
            harness.now(),
            null);
    HeldRenderer held = new HeldRenderer(harness.renderer());
    IssuanceReprocess reprocess = harness.reprocessWith(held);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceReprocess.Result> first =
          pool.submit(
              () ->
                  reprocess.reprocess(new ReprocessRequest(eventId, "operator-1", "key rotated")));
      boolean reached = held.reached.await(WAIT, TimeUnit.SECONDS);
      IssuanceReprocess.Result second =
          reprocess.reprocess(new ReprocessRequest(eventId, "operator-1", "key rotated"));
      held.release.countDown();

      assertThat(reached).as("the first call ran into the render").isTrue();
      IssuanceReprocess.Result firstResult = first.get(WAIT, TimeUnit.SECONDS);
      assertThat(second.disposition()).isNotEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
      assertThat(firstResult.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
      assertThat(firstResult.state()).isEqualTo(InboundState.COMPLETED);
      assertThat(firstResult.legalNumber()).isEqualTo(number);
      assertThat(held.renders.get()).as("one run").isEqualTo(1);
      assertThat(
              harness.reprocessRecords(eventId).stream()
                  .filter(r -> "REQUESTED".equals(r.kind()))
                  .count())
          .as("one ledger entry")
          .isEqualTo(1);
    } finally {
      held.release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * D-SRE-9. A success that lands while the refusal is being written must not consume the flag
   * before the refused row is visible. The hook runs a successful Stripe read of another invoice
   * inside the refusal's write, before it commits.
   */
  @Test
  void a_success_racing_a_refusal_still_re_arms_the_refused_row() throws Exception {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createOn(PostgresSupport.freshDatabase("sre_race"));
    harness.source().with(TestInvoices.finalised("in_sre9_refused"));
    harness.source().with(TestInvoices.draft("in_sre9_other"));
    harness.source().with(TestInvoices.draft("in_sre9_later"));
    String refused = harness.receive("invoice.finalized", "in_sre9_refused");
    String other = harness.receive("invoice.finalized", "in_sre9_other");
    String later = harness.receive("invoice.finalized", "in_sre9_later");
    harness
        .source()
        .breakFor(
            "in_sre9_refused",
            new EInvoiceException(RetryEdgeFixture.ACCESS_REFUSED, "status 401"));

    ExecutorService pool = Executors.newSingleThreadExecutor();
    AtomicBoolean hooked = new AtomicBoolean();
    AtomicBoolean hookRan = new AtomicBoolean();
    InboundRunLock racing =
        new InboundRunLock() {
          @Override
          public <T> Optional<T> whileHeld(
              InboundEvent storedRow, java.util.function.Function<HeldRun, T> work) {
            return harness
                .runLock()
                .whileHeld(
                    storedRow,
                    held ->
                        work.apply(
                            new HeldRun(
                                beforeAccessRefusedWrite(
                                    held.inbound(),
                                    () -> {
                                      if (hooked.compareAndSet(false, true)) {
                                        Future<?> success =
                                            pool.submit(() -> unitOfWorkOf(harness).process(other));
                                        try {
                                          success.get(WAIT, TimeUnit.SECONDS);
                                          hookRan.set(true);
                                        } catch (Exception e) {
                                          throw new IllegalStateException(e);
                                        }
                                      }
                                    }),
                                held.proof())));
          }
        };
    try {
      IssuanceUnitOfWork unitOfWork =
          harness.unitOfWorkWithRun(
              harness.inbound(), racing, harness.permits(), harness.renderer());
      shared.set(unitOfWork);
      unitOfWork.process(refused);
      assertThat(hookRan).as("the racing success ran inside the refusal's write").isTrue();
      assertThat(RetryEdgeFixture.row(harness, refused).nextAttempt())
          .as("a revoked key stops the row")
          .isEmpty();

      unitOfWork.process(later);

      InboundEvent row = RetryEdgeFixture.row(harness, refused);
      assertThat(row.nextAttempt()).as("the next success re-arms the refused row").isPresent();
      assertThat(harness.due()).extracting(InboundEvent::eventId).contains(refused);
    } finally {
      pool.shutdownNow();
    }
  }

  /** The unit of work under test, so the hook's success shares its flag. */
  private final java.util.concurrent.atomic.AtomicReference<IssuanceUnitOfWork> shared =
      new java.util.concurrent.atomic.AtomicReference<>();

  private IssuanceUnitOfWork unitOfWorkOf(IssuanceTestHarness harness) {
    return shared.get();
  }

  /**
   * D-SRE-2. A numbered row past the retry ceiling is no longer due; once the cause is fixed, the
   * operator's reprocess resumes the same number.
   */
  @Test
  void probe_a_numbered_row_past_the_ceiling_cannot_be_resumed() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre2_ceiling");
    String number = RetryEdgeFixture.number(harness, "in_sre2_ceiling");
    harness.moveClockForward(Duration.ofDays(4));
    assertThat(harness.due()).extracting(InboundEvent::eventId).doesNotContain(eventId);

    IssuanceReprocess.Result result =
        harness.reprocess().reprocess(new ReprocessRequest(eventId, "operator-1", "archive back"));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(result.legalNumber()).isEqualTo(number);
  }

  /** Runs {@code hook} inside a held store's write of the revoked-key code, before the write. */
  static InboundEventStore beforeAccessRefusedWrite(InboundEventStore delegate, Runnable hook) {
    return (InboundEventStore)
        Proxy.newProxyInstance(
            InboundEventStore.class.getClassLoader(),
            new Class<?>[] {InboundEventStore.class},
            (proxy, method, args) -> {
              if (method.getName().startsWith("transition")
                  && args != null
                  && List.of(args).contains(RetryEdgeFixture.ACCESS_REFUSED)) {
                hook.run();
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /** Counts the calls of one store method by name, delegating every call. */
  static InboundEventStore counting(
      InboundEventStore delegate, String methodName, AtomicInteger calls) {
    return (InboundEventStore)
        Proxy.newProxyInstance(
            InboundEventStore.class.getClassLoader(),
            new Class<?>[] {InboundEventStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals(methodName)) {
                calls.incrementAndGet();
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /** Holds a run inside the render until released, counting the renders. */
  static final class HeldRenderer implements DocumentRenderer {
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger renders = new AtomicInteger();
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
      renders.incrementAndGet();
      reached.countDown();
      try {
        if (!release.await(WAIT, TimeUnit.SECONDS)) {
          throw new IllegalStateException("never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return delegate.render(input);
    }
  }
}
