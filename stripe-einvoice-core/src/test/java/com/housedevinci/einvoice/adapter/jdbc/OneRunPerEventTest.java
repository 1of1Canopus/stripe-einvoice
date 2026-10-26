package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.IssuanceReprocess;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.MappingInput;
import com.housedevinci.einvoice.application.PreflightReport;
import com.housedevinci.einvoice.application.ReprocessRequest;
import com.housedevinci.einvoice.application.RunPermits;
import com.housedevinci.einvoice.application.TestInvoices;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import com.zaxxer.hikari.HikariConfigMXBean;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * One run per event (D-PR45-1, design one-run-per-event Revision 2): every path to a run or to a
 * write of the inbound row, one test each (section 5), T1 to T21 of the design's list. T2 and T3
 * are the review probe {@code CipherProbePr45Test}; T10 is {@code PgBouncerSessionPinningTest};
 * T12, T21 and T23 are in the starter; T15 is {@code ErrorCodesUniquenessTest}. T22 lands with the
 * QUESTIONS 29 expiry, which is not on this branch.
 */
class OneRunPerEventTest {

  private static final long WAIT = 30;

  /** Holds a run inside the render: past MAPPED and P1, before P3. Counts every render. */
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

  /** A run of {@code unitOfWork} on {@code eventId}, started on its own thread. */
  private static Future<IssuanceUnitOfWork.Outcome> start(
      ExecutorService pool, IssuanceUnitOfWork unitOfWork, String eventId) {
    return pool.submit(() -> unitOfWork.process(eventId));
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertThat(latch.await(WAIT, TimeUnit.SECONDS)).as("the run reached the held step").isTrue();
  }

  private static InboundEvent row(IssuanceTestHarness harness, String eventId) {
    return harness.inbound().find(eventId).orElseThrow();
  }

  /** Ends every backend that holds an advisory lock of {@code classId} on {@code key}. */
  private static int terminate(DataSource dataSource, int classId, int key) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT pg_catalog.pg_terminate_backend(pid) FROM pg_catalog.pg_locks"
                    + " WHERE locktype = 'advisory' AND objsubid = 2"
                    + " AND classid = CAST(? AS bigint)::oid AND objid = CAST(? AS bigint)::oid")) {
      ps.setLong(1, classId & 0xFFFFFFFFL);
      ps.setLong(2, key & 0xFFFFFFFFL);
      int ended = 0;
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ended++;
        }
      }
      return ended;
    }
  }

  private static int terminateRunLock(IssuanceTestHarness harness, String objectId)
      throws SQLException {
    return terminate(
        harness.dataSource(), AdvisoryLocks.RUN_CLASS_ID, JdbcInboundRunLock.runKey(objectId));
  }

  // ------------------------------------------------------------------------------- section 5

  /** T1, paths 1 and 3: the worker's run and a second run of the same event. */
  @Test
  void a_second_run_of_one_event_is_busy_and_the_renderer_runs_once() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_orpe_t1"));
    String eventId = harness.receive("invoice.finalized", "in_orpe_t1");
    HeldRenderer held = new HeldRenderer(harness.renderer());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> a =
          start(pool, harness.unitOfWorkWithSourceAndRenderer(harness.source(), held), eventId);
      await(held.reached);

      IssuanceUnitOfWork.Outcome b =
          harness.unitOfWorkWithSourceAndRenderer(harness.source(), held).process(eventId);

      assertThat(b.code()).isEqualTo(ErrorCodes.INBOUND_RUN_IN_PROGRESS);
      assertThat(b.state()).isEqualTo(InboundState.MAPPED);
      held.release.countDown();
      assertThat(a.get(WAIT, TimeUnit.SECONDS).state()).isEqualTo(InboundState.COMPLETED);
      assertThat(held.renders.get()).isEqualTo(1);
      assertThat(harness.numberedRows()).isEqualTo(1);
    } finally {
      held.release.countDown();
      pool.shutdownNow();
    }
  }

  /** T4, path 4: a re-open while a run holds the lock refuses, records nothing, moves nothing. */
  @Test
  void a_reprocess_while_a_run_holds_the_lock_records_nothing() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_orpe_t4"));
    String eventId = harness.receive("invoice.finalized", "in_orpe_t4");
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);
    harness
        .inbound()
        .transition(eventId, InboundState.FAILED_MAPPING, "DEI-304", harness.now(), null);
    InboundEvent before = row(harness, eventId);

    IssuanceReprocess.Result result =
        harness
            .runLock()
            .whileHeld(
                before,
                held ->
                    harness
                        .reprocess()
                        .reprocess(new ReprocessRequest(eventId, "ops-alice", "seller fixed")))
            .orElseThrow();

    assertThat(result.disposition())
        .isEqualTo(IssuanceReprocess.Disposition.REFUSED_RUN_IN_PROGRESS);
    assertThat(result.code()).isEqualTo(ErrorCodes.INBOUND_RUN_IN_PROGRESS);
    assertThat(harness.reprocessRecords(eventId)).isEmpty();
    assertThat(row(harness, eventId).state()).isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(row(harness, eventId).attempts()).isEqualTo(before.attempts());
  }

  /** T5, path 5: a second pool on the same database contends on the same key. */
  @Test
  void a_second_data_source_on_the_same_database_is_busy() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = harness.receive("invoice.finalized", "in_orpe_t5");
    InboundEvent stored = row(harness, eventId);
    try (com.zaxxer.hikari.HikariDataSource second = secondPool()) {
      JdbcInboundRunLock other =
          new JdbcInboundRunLock(JdbcUnitOfWork.ownConnection(second), second);

      Optional<Optional<String>> nested =
          harness.runLock().whileHeld(stored, held -> other.whileHeld(stored, h -> "ran"));

      assertThat(nested).contains(Optional.empty());
      assertThat(other.whileHeld(stored, h -> "ran")).contains("ran");
    }
  }

  private static com.zaxxer.hikari.HikariDataSource secondPool() {
    com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
    config.setJdbcUrl(PostgresSupport.jdbcUrl());
    config.setUsername(PostgresSupport.username());
    config.setPassword(PostgresSupport.password());
    config.setMaximumPoolSize(3);
    return new com.zaxxer.hikari.HikariDataSource(config);
  }

  /** T6, paths 6 and 7: invoice.finalized and invoice.paid of one invoice, at once. */
  @Test
  void finalized_and_paid_of_one_invoice_run_one_at_a_time_and_both_end_right() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_orpe_t6"));
    String finalized = harness.receive("invoice.finalized", "in_orpe_t6");
    String paid = harness.receive("invoice.paid", "in_orpe_t6");
    HeldRenderer held = new HeldRenderer(harness.renderer());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> a =
          start(pool, harness.unitOfWorkWithSourceAndRenderer(harness.source(), held), finalized);
      await(held.reached);

      IssuanceUnitOfWork.Outcome busy = harness.unitOfWork().process(paid);

      assertThat(busy.code()).isEqualTo(ErrorCodes.INBOUND_RUN_IN_PROGRESS);
      assertThat(busy.state()).isEqualTo(InboundState.RECEIVED);
      assertThat(row(harness, paid).attempts()).as("busy writes nothing").isZero();
      held.release.countDown();
      IssuanceUnitOfWork.Outcome first = a.get(WAIT, TimeUnit.SECONDS);
      IssuanceUnitOfWork.Outcome second = harness.unitOfWork().process(paid);

      assertThat(first.state()).isEqualTo(InboundState.COMPLETED);
      assertThat(second.state()).isEqualTo(InboundState.COMPLETED);
      assertThat(second.legalNumber()).isEqualTo(first.legalNumber());
      assertThat(harness.numberedRows()).isEqualTo(1);
      assertThat(harness.archive().size()).isEqualTo(1);
    } finally {
      held.release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * T7, path 8: a crashed holder. Its session ends, the lock goes with it, and the stamp decides
   * when the successor starts: not before one backoff, then onto the same number.
   */
  @Test
  void a_crashed_holder_frees_the_lock_and_the_stamp_sets_the_successor_start() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_orpe_t7"));
    String eventId = harness.receive("invoice.finalized", "in_orpe_t7");
    HeldRenderer held = new HeldRenderer(harness.renderer());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> a =
          start(pool, harness.unitOfWorkWithSourceAndRenderer(harness.source(), held), eventId);
      await(held.reached);
      String number = harness.issuance("in_orpe_t7").orElseThrow().legalNumber().value();
      long issuanceId = harness.issuance("in_orpe_t7").orElseThrow().id();

      // The pod is gone: both of its sessions end, which frees both of its locks.
      assertThat(terminateRunLock(harness, "in_orpe_t7")).isEqualTo(1);
      terminate(harness.dataSource(), AdvisoryLocks.ROW_CLASS_ID, (int) issuanceId);

      assertThat(harness.due()).extracting(InboundEvent::eventId).doesNotContain(eventId);
      harness.moveClockForward(Duration.ofMinutes(1).plusSeconds(1));
      assertThat(harness.due()).extracting(InboundEvent::eventId).contains(eventId);

      IssuanceUnitOfWork.Outcome successor = harness.unitOfWork().process(eventId);

      assertThat(successor.state()).isEqualTo(InboundState.COMPLETED);
      assertThat(successor.legalNumber()).isEqualTo(number);
      assertThat(harness.numberedRows()).isEqualTo(1);
      held.release.countDown();
      a.get(WAIT, TimeUnit.SECONDS);
      assertThat(row(harness, eventId).state()).isEqualTo(InboundState.COMPLETED);
      assertThat(harness.archive().size()).isEqualTo(1);
    } finally {
      held.release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * T7, the stamp on its own: a holder that dies inside its first Stripe fetch has written no step
   * yet, so the stamp is the only thing that keeps the row from being due at once.
   */
  @Test
  void a_holder_that_dies_in_its_first_fetch_is_not_due_before_its_stamp() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_orpe_t7b"));
    String eventId = harness.receive("invoice.finalized", "in_orpe_t7b");
    CountDownLatch inFetch = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    com.housedevinci.einvoice.application.StripeInvoiceSource held =
        new com.housedevinci.einvoice.application.StripeInvoiceSource() {
          @Override
          public com.housedevinci.einvoice.application.SourceInvoice fetchInvoice(String id) {
            inFetch.countDown();
            try {
              release.await(WAIT, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            throw new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "the pod is gone");
          }

          @Override
          public List<String> finalisedInvoiceIds(Instant from, Instant to) {
            return List.of();
          }

          @Override
          public String pinnedApiVersion() {
            return harness.source().pinnedApiVersion();
          }
        };
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> a =
          start(pool, harness.unitOfWorkWithSourceAndRenderer(held, harness.renderer()), eventId);
      await(inFetch);
      assertThat(terminateRunLock(harness, "in_orpe_t7b")).isEqualTo(1);

      assertThat(row(harness, eventId).state()).isEqualTo(InboundState.RECEIVED);
      assertThat(harness.due()).extracting(InboundEvent::eventId).doesNotContain(eventId);
      harness.moveClockForward(Duration.ofMinutes(1).plusSeconds(1));
      assertThat(harness.due()).extracting(InboundEvent::eventId).contains(eventId);

      release.countDown();
      assertThat(a.get(WAIT, TimeUnit.SECONDS).code()).isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
      assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * T8, path 9: a holder paused before P3 loses its sessions; a successor completes; the old run
   * wakes up, stops at the proof and writes nothing.
   */
  @Test
  void a_holder_that_lost_its_session_stops_at_the_proof_and_writes_nothing() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_orpe_t8"));
    String eventId = harness.receive("invoice.finalized", "in_orpe_t8");
    HeldRenderer held = new HeldRenderer(harness.renderer());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> a =
          start(pool, harness.unitOfWorkWithSourceAndRenderer(harness.source(), held), eventId);
      await(held.reached);
      long issuanceId = harness.issuance("in_orpe_t8").orElseThrow().id();
      terminateRunLock(harness, "in_orpe_t8");
      terminate(harness.dataSource(), AdvisoryLocks.ROW_CLASS_ID, (int) issuanceId);
      IssuanceUnitOfWork.Outcome successor = harness.unitOfWork().process(eventId);
      assertThat(successor.state()).isEqualTo(InboundState.COMPLETED);
      long chained = harness.chainedEvents();

      held.release.countDown();
      IssuanceUnitOfWork.Outcome old = a.get(WAIT, TimeUnit.SECONDS);

      assertThat(old.code()).isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
      assertThat(row(harness, eventId).state()).isEqualTo(InboundState.COMPLETED);
      assertThat(harness.chainedEvents()).isEqualTo(chained);
      assertThat(harness.archive().size()).isEqualTo(1);
      assertThat(harness.issuance("in_orpe_t8").orElseThrow().state().name()).isEqualTo("ISSUED");
    } finally {
      held.release.countDown();
      pool.shutdownNow();
    }
  }

  /** T9, path 10: the purge skips a row whose run is live, and deletes it once released. */
  @Test
  void the_purge_skips_a_held_row_and_deletes_it_after_release() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createOn(PostgresSupport.freshDatabase("orpe_purge", 6));
    String eventId = harness.receive("invoice.finalized", "in_orpe_t9");
    InboundEvent stored = row(harness, eventId);
    Instant cutoff = harness.now().plus(Duration.ofDays(1));

    Optional<Integer> whileHeld =
        harness.runLock().whileHeld(stored, held -> harness.inbound().purgeOlderThan(cutoff, 10));

    assertThat(whileHeld).contains(0);
    assertThat(harness.inbound().find(eventId)).isPresent();
    assertThat(harness.inbound().purgeOlderThan(cutoff, 10)).isEqualTo(1);
    assertThat(harness.inbound().find(eventId)).isEmpty();
  }

  /** T11, path 14: two invoices whose keys collide only wait for each other. */
  @Test
  void a_key_collision_costs_one_sweep_and_both_invoices_complete() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_orpe_t11_a"));
    harness.source().with(TestInvoices.finalised("in_orpe_t11_b"));
    String a = harness.receive("invoice.finalized", "in_orpe_t11_a");
    String b = harness.receive("invoice.finalized", "in_orpe_t11_b");
    JdbcInboundRunLock colliding =
        new JdbcInboundRunLock(
            JdbcUnitOfWork.ownConnection(harness.dataSource()),
            harness.dataSource(),
            objectId -> 0x0BAD_CAFE);
    HeldRenderer held = new HeldRenderer(harness.renderer());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> first =
          start(
              pool,
              harness.unitOfWorkWithRun(harness.inbound(), colliding, harness.permits(), held),
              a);
      await(held.reached);
      IssuanceUnitOfWork other =
          harness.unitOfWorkWithRun(
              harness.inbound(), colliding, harness.permits(), harness.renderer());

      assertThat(other.process(b).code()).isEqualTo(ErrorCodes.INBOUND_RUN_IN_PROGRESS);
      held.release.countDown();
      assertThat(first.get(WAIT, TimeUnit.SECONDS).state()).isEqualTo(InboundState.COMPLETED);
      assertThat(other.process(b).state()).isEqualTo(InboundState.COMPLETED);
    } finally {
      held.release.countDown();
      pool.shutdownNow();
    }
  }

  /** T14: an exception out of the work releases the lock; the next run acquires it. */
  @Test
  void an_exception_out_of_the_work_releases_the_lock() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = harness.receive("invoice.finalized", "in_orpe_t14");
    InboundEvent stored = row(harness, eventId);

    assertThatThrownBy(
            () ->
                harness
                    .runLock()
                    .whileHeld(
                        stored,
                        held -> {
                          throw new EInvoiceException(ErrorCodes.RENDER_FAILED, "host bug");
                        }))
        .isInstanceOf(EInvoiceException.class);

    assertThat(harness.runLock().whileHeld(stored, held -> "next")).contains("next");
  }

  /**
   * T16 (D-ORPE-2): a run whose session is killed mid-run ends DEI-104 at WARN with the event id,
   * not busy; it writes nothing more and its connection is not pooled again.
   */
  @Test
  void probe_a_run_that_loses_its_lock_reports_dei_104_at_warn_not_busy() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_orpe_t16"));
    String eventId = harness.receive("invoice.finalized", "in_orpe_t16");
    HeldRenderer held = new HeldRenderer(harness.renderer());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      Future<IssuanceUnitOfWork.Outcome> a =
          start(pool, harness.unitOfWorkWithSourceAndRenderer(harness.source(), held), eventId);
      await(held.reached);
      assertThat(terminateRunLock(harness, "in_orpe_t16")).isEqualTo(1);
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

      held.release.countDown();
      IssuanceUnitOfWork.Outcome outcome = a.get(WAIT, TimeUnit.SECONDS);

      System.setErr(original);
      assertThat(outcome.code()).isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
      assertThat(outcome.state()).isEqualTo(InboundState.MAPPED);
      assertThat(row(harness, eventId).state()).isEqualTo(InboundState.MAPPED);
      assertThat(harness.archive().size()).as("stopped before P3").isZero();
      List<String> warns = new ArrayList<>();
      for (String line : captured.toString(StandardCharsets.UTF_8).split("\n")) {
        if (line.contains("WARN") && line.contains(eventId) && line.contains("DEI-104")) {
          warns.add(line);
        }
      }
      assertThat(warns).hasSize(1);
      assertThat(captured.toString(StandardCharsets.UTF_8)).doesNotContain("DEI-217");
      // The lock is free: the next run takes it.
      assertThat(harness.runLock().whileHeld(row(harness, eventId), h -> "next")).contains("next");
    } finally {
      System.setErr(original);
      held.release.countDown();
      pool.shutdownNow();
    }
  }

  /** T17 (D-ORPE-3): one run is one attempt; a Stripe outage retries at +1, +2, +4 minutes. */
  @Test
  void a_fetch_outage_retries_at_one_two_four_backoffs_with_the_stamp_in_place() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_orpe_t17"));
    String eventId = harness.receive("invoice.finalized", "in_orpe_t17");
    harness
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "stripe is down"));

    List<Duration> waits = new ArrayList<>();
    for (int run = 0; run < 3; run++) {
      IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);
      assertThat(outcome.state()).isEqualTo(InboundState.FAILED_FETCH);
      InboundEvent stored = row(harness, eventId);
      assertThat(stored.attempts()).isEqualTo(run + 1);
      waits.add(Duration.between(harness.now(), stored.nextAttemptAt()));
      harness.moveClockTo(stored.nextAttemptAt());
    }

    assertThat(waits)
        .containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4));
  }

  /** T18 (D-ORPE-4): host calls beyond the bound refuse DEI-102 and take no connection. */
  @Test
  void reprocess_calls_beyond_the_bound_do_not_take_more_run_lock_connections() throws Exception {
    CountingDataSource counting = new CountingDataSource(PostgresSupport.dataSource());
    IssuanceTestHarness harness = IssuanceTestHarness.createOn(counting);
    RunPermits two = new RunPermits(2);
    harness.source().with(TestInvoices.finalised("in_orpe_t18_a"));
    harness.source().with(TestInvoices.finalised("in_orpe_t18_b"));
    String a = harness.receive("invoice.finalized", "in_orpe_t18_a");
    String b = harness.receive("invoice.finalized", "in_orpe_t18_b");
    HeldRenderer heldA = new HeldRenderer(harness.renderer());
    HeldRenderer heldB = new HeldRenderer(harness.renderer());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<IssuanceUnitOfWork.Outcome> runA =
          start(
              pool, harness.unitOfWorkWithRun(harness.inbound(), harness.runLock(), two, heldA), a);
      Future<IssuanceUnitOfWork.Outcome> runB =
          start(
              pool, harness.unitOfWorkWithRun(harness.inbound(), harness.runLock(), two, heldB), b);
      await(heldA.reached);
      await(heldB.reached);
      IssuanceReprocess reprocess =
          harness.reprocessFor(
              harness.unitOfWorkWithRun(
                  harness.inbound(), harness.runLock(), two, harness.renderer()));
      int before = counting.taken.get();

      for (int click = 0; click < 10; click++) {
        assertThatThrownBy(
                () -> reprocess.reprocess(new ReprocessRequest(a, "ops-alice", "click " + "x")))
            .isInstanceOf(EInvoiceException.class)
            .extracting("code")
            .isEqualTo(ErrorCodes.STORE_UNAVAILABLE);
      }

      assertThat(counting.taken.get()).as("no connection taken").isEqualTo(before);
      assertThat(two.available()).isZero();
      heldA.release.countDown();
      heldB.release.countDown();
      runA.get(WAIT, TimeUnit.SECONDS);
      runB.get(WAIT, TimeUnit.SECONDS);
      assertThat(two.available()).isEqualTo(2);
    } finally {
      heldA.release.countDown();
      heldB.release.countDown();
      pool.shutdownNow();
    }
  }

  /** T19, path 15 (D-ORPE-5): rows of two modes for one invoice share one key. */
  @Test
  void two_instances_of_different_modes_contend_on_one_row_key() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String live = harness.receive("invoice.finalized", "in_orpe_t19");
    InboundEvent liveRow = row(harness, live);
    InboundEvent testRow =
        new InboundEvent(
            "evt_test_mode_" + live,
            liveRow.type(),
            liveRow.objectId(),
            liveRow.apiVersion(),
            false,
            liveRow.stripeAccountId(),
            "another-seller",
            Mode.TEST,
            liveRow.signatureKeyId(),
            liveRow.receivedAt(),
            liveRow.updatedAt(),
            liveRow.state(),
            liveRow.attempts(),
            liveRow.nextAttemptAt(),
            liveRow.lastCode(),
            liveRow.bodySha256(),
            liveRow.bodyPresent());

    Optional<Optional<String>> nested =
        harness
            .runLock()
            .whileHeld(liveRow, held -> harness.runLock().whileHeld(testRow, h -> "x"));

    assertThat(nested).contains(Optional.empty());
  }

  /** T20 (D-ORPE-7a): a pool that cannot give a connection is DEI-102, never busy. */
  @Test
  void a_pool_timeout_on_acquire_is_dei_102_not_busy() throws Exception {
    DataSource small = PostgresSupport.freshDatabase("orpe_timeout", 2);
    ((HikariConfigMXBean) small).setConnectionTimeout(500);
    IssuanceTestHarness harness = IssuanceTestHarness.createOn(small);
    String eventId = harness.receive("invoice.finalized", "in_orpe_t20");
    InboundEvent stored = row(harness, eventId);
    harness.store().verifySessionPinning();

    try (Connection one = small.getConnection();
        Connection two = small.getConnection()) {
      assertThatThrownBy(() -> harness.runLock().whileHeld(stored, held -> "ran"))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.STORE_UNAVAILABLE);
    }
    assertThat(harness.runLock().whileHeld(stored, held -> "ran")).contains("ran");
  }

  /**
   * T13: the instance a host builds never writes the plain inbound store; every write of a run goes
   * through the held one. ArchUnit cannot tell two instances of one interface apart, so the plain
   * store is a spy that refuses any write, over every outcome class of the pipeline.
   */
  @Test
  void the_unit_of_work_never_writes_through_the_plain_inbound_store() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    ReadOnlyInbound spy = new ReadOnlyInbound(harness.inbound());
    IssuanceUnitOfWork unitOfWork =
        harness.unitOfWorkWithRun(spy, harness.runLock(), harness.permits(), harness.renderer());
    harness.source().with(TestInvoices.finalised("in_orpe_t13"));
    String completed = harness.receive("invoice.finalized", "in_orpe_t13");
    String dropped = harness.receive("customer.created", "in_orpe_t13_drop");
    String skewed =
        harness.receive("invoice.finalized", "in_orpe_t13_skew", "2019-01-01", true, "");
    String outage = harness.receive("invoice.finalized", "in_orpe_t13_out");

    assertThat(unitOfWork.process(completed).state()).isEqualTo(InboundState.COMPLETED);
    assertThat(unitOfWork.process(completed).state()).isEqualTo(InboundState.COMPLETED);
    assertThat(unitOfWork.process(dropped).state()).isEqualTo(InboundState.DROPPED);
    assertThat(unitOfWork.process(skewed).state()).isEqualTo(InboundState.REFUSED_VERSION_SKEW);
    harness
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "stripe is down"));
    assertThat(unitOfWork.process(outage).state()).isEqualTo(InboundState.FAILED_FETCH);

    assertThat(spy.writes).isEmpty();
  }

  /** The plain store as the unit of work sees it: reads pass, every write is recorded. */
  private static final class ReadOnlyInbound implements InboundEventStore {
    private final InboundEventStore delegate;
    final List<String> writes = new ArrayList<>();

    ReadOnlyInbound(InboundEventStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean record(InboundEvent event, byte[] body) {
      writes.add("record");
      return delegate.record(event, body);
    }

    @Override
    public Optional<InboundEvent> find(String eventId) {
      return delegate.find(eventId);
    }

    @Override
    public Optional<byte[]> body(String eventId) {
      return delegate.body(eventId);
    }

    @Override
    public InboundEvent transition(
        String eventId,
        InboundState next,
        String code,
        String ruleId,
        Instant now,
        Duration retryIn) {
      writes.add("transition");
      return delegate.transition(eventId, next, code, ruleId, now, retryIn);
    }

    @Override
    public Optional<InboundEvent> transitionFrom(
        String eventId,
        InboundState expected,
        InboundState next,
        String code,
        String ruleId,
        Instant now,
        Duration retryIn) {
      writes.add("transitionFrom");
      return delegate.transitionFrom(eventId, expected, next, code, ruleId, now, retryIn);
    }

    @Override
    public Optional<InboundEvent> stampAttempt(
        String eventId, InboundState expected, Instant now, Duration retryIn) {
      writes.add("stampAttempt");
      return delegate.stampAttempt(eventId, expected, now, retryIn);
    }

    @Override
    public Optional<String> lastRuleId(String eventId) {
      return delegate.lastRuleId(eventId);
    }

    @Override
    public InboundEvent bindSeller(String eventId, String sellerId, Instant now) {
      writes.add("bindSeller");
      return delegate.bindSeller(eventId, sellerId, now);
    }

    @Override
    public List<InboundEvent> due(
        Instant now, Duration retryCeiling, int limit, String pinnedApiVersion) {
      return delegate.due(now, retryCeiling, limit, pinnedApiVersion);
    }

    @Override
    public int purgeOlderThan(Instant cutoff, int limit) {
      writes.add("purge");
      return delegate.purgeOlderThan(cutoff, limit);
    }

    @Override
    public List<StateCount> countsByState() {
      return delegate.countsByState();
    }
  }

  /** Counts every connection handed out, for T18. */
  static final class CountingDataSource implements DataSource {
    final AtomicInteger taken = new AtomicInteger();
    private final DataSource delegate;

    CountingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
      taken.incrementAndGet();
      return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      taken.incrementAndGet();
      return delegate.getConnection(username, password);
    }

    @Override
    public java.io.PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
      return java.util.logging.Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }
  }
}
