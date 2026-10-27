package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.ARCHIVING_NO_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.ARCHIVING_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.adapter.jdbc.RowLockHolder;
import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * D-RTR-7, the issuance row lock, at the unit of work: the security ruling's test list (R2 to R5)
 * and one test per path that can reach a burn or a read of a recorded key (R3.4). The store-level
 * properties are in {@code IssuanceRowLockStoreTest}; the pooler refusal in {@code
 * PgBouncerSessionPinningTest}.
 */
class IssuanceRowLockPathsTest {

  private static final ReconciliationSweep.Settings SETTINGS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);

  /** The store's lock, recording which rows it was asked for, or answering "held" on demand. */
  private static final class RecordingLock implements IssuanceRowLock {
    final List<Long> asked = new CopyOnWriteArrayList<>();
    final AtomicBoolean heldElsewhere = new AtomicBoolean();
    private final IssuanceRowLock real;

    RecordingLock(IssuanceRowLock real) {
      this.real = real;
    }

    @Override
    public <T> Optional<T> whileHeld(long issuanceId, Function<Held, T> work) {
      asked.add(issuanceId);
      return heldElsewhere.get() ? Optional.empty() : real.whileHeld(issuanceId, work);
    }

    @Override
    public void verifySessionPinning() {
      real.verifySessionPinning();
    }
  }

  /** A reader that counts what the archive was asked. */
  private static final class CountingReads {
    final AtomicInteger reads = new AtomicInteger();

    ArchiveStore over(InMemoryArchiveStore inner) {
      return new ArchiveStore() {
        @Override
        public boolean supportsAtomicCreate() {
          return true;
        }

        @Override
        public WriteResult putIfAbsent(com.housedevinci.einvoice.domain.ArchiveKey k, byte[] b) {
          return inner.putIfAbsent(k, b);
        }

        @Override
        public Optional<byte[]> get(com.housedevinci.einvoice.domain.ArchiveKey key) {
          reads.incrementAndGet();
          return inner.get(key);
        }

        @Override
        public List<String> list(String prefix, int limit) {
          return inner.list(prefix, limit);
        }

        @Override
        public String describe() {
          return "counting archive (test)";
        }
      };
    }
  }

  // ------------------------------------------------------------------ R3.1: try only

  @Test
  void a_lock_held_by_another_worker_retries_without_reading_or_rendering() {
    Row row = prepare(ARCHIVING_WITH_OBJECT, "in_lk_held");
    CountingReads counting = new CountingReads();
    int renders = row.harness().renderer().renders();
    IssuanceUnitOfWork.Outcome outcome;
    long started = System.nanoTime();
    try (RowLockHolder other =
        RowLockHolder.hold(PostgresSupport.dataSource(), row.before().id())) {
      outcome =
          row.retry(
              row.harness().renderer(),
              TestValidators.failing("WOULD-BURN"),
              ArchiveRetryFixture.READER,
              counting.over(row.harness().archive()));
    }

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    assertThat(outcome.code()).isEqualTo(ErrorCodes.ISSUANCE_IN_FLIGHT);
    assertThat(row.event().nextAttemptAt()).as("retried later").isNotNull();
    assertThat(counting.reads).hasValue(0);
    assertThat(row.harness().renderer().renders()).isEqualTo(renders);
    assertThat(row.now().state()).isEqualTo(IssuanceState.ARCHIVING);
    assertThat(row.chained()).isEmpty();
  }

  @Test
  void a_free_lock_runs_the_resume_as_before_and_leaves_no_lock() {
    Row row = prepare(ARCHIVING_WITH_OBJECT, "in_lk_free");

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(row.harness().renderer(), TestValidators.passing(), ArchiveRetryFixture.READER);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(RowLockHolder.holderPid(PostgresSupport.dataSource(), row.before().id()))
        .isEqualTo(-1);
  }

  // ------------------------------------------------------------------ R3.2: decide under the lock

  @Test
  void the_row_is_read_again_under_the_lock() {
    // The allocator's read saw ARCHIVING; a void lands before this run gets the lock. The run
    // must decide from the read under the lock: terminal, no archive read, no render.
    Row row = prepare(ARCHIVING_NO_OBJECT, "in_lk_reread");
    IssuanceTestHarness harness = row.harness();
    CountingReads counting = new CountingReads();
    int renders = harness.renderer().renders();
    IssuanceRowLock voidingFirst =
        new IssuanceRowLock() {
          @Override
          public <T> Optional<T> whileHeld(long id, Function<Held, T> work) {
            harness
                .store()
                .voidUnused(
                    new VoidRequest(
                        harness.sellerId(), Mode.LIVE, "in_lk_reread", "archive lost", "OPS-9"));
            return harness.store().whileHeld(id, work);
          }

          @Override
          public void verifySessionPinning() {
            harness.store().verifySessionPinning();
          }
        };
    IssuanceUnitOfWork unitOfWork =
        new IssuanceUnitOfWork(
            harness.inbound(),
            harness.runLock(),
            harness.permits(),
            harness.source(),
            harness.store(),
            harness.store(),
            harness.store(),
            voidingFirst,
            ArchiveRetryFixture.backdating(harness, 7),
            TestValidators.passing(),
            ArchiveRetryFixture.READER,
            counting.over(harness.archive()),
            harness.preflightSupport(),
            java.time.Clock.fixed(harness.now(), java.time.ZoneOffset.UTC),
            harness.unitOfWork().configuration());

    IssuanceUnitOfWork.Outcome outcome = unitOfWork.process(row.eventId());

    assertThat(outcome.code()).isEqualTo(ErrorCodes.NUMBER_VOIDED);
    assertThat(counting.reads).hasValue(0);
    assertThat(harness.renderer().renders()).isEqualTo(renders);
    assertThat(row.now().state()).isEqualTo(IssuanceState.VOID_UNUSED);
  }

  // ------------------------------------------------------------------ R4: holder terminated

  @Test
  void a_terminated_holder_releases_the_lock_and_leaves_the_d_rtr_4_window() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create().withoutRunLock();
    harness.source().with(TestInvoices.finalised("in_lk_kill"));
    String finalized = harness.receive("invoice.finalized", "in_lk_kill");
    String paid = harness.receive("invoice.paid", "in_lk_kill");
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
                          ArchiveRetryFixture.heldPut(harness.archive(), inPut, release))
                      .process(finalized));
      assertThat(inPut.await(30, TimeUnit.SECONDS)).isTrue();
      long id = harness.issuance("in_lk_kill").orElseThrow().id();
      int pid = RowLockHolder.holderPid(PostgresSupport.dataSource(), id);
      assertThat(pid).as("worker A holds the row lock during its PUT").isPositive();
      PostgresSupport.execute("SELECT pg_terminate_backend(" + pid + ")");

      // Worker B: the lock died with A's session, so B proceeds, reads nothing, and is refused.
      IssuanceUnitOfWork.Outcome b =
          harness
              .unitOfWorkWith(
                  ArchiveRetryFixture.backdating(harness, 7),
                  TestValidators.passing(),
                  ArchiveRetryFixture.READER,
                  harness.archive())
              .process(paid);
      release.countDown();
      IssuanceUnitOfWork.Outcome outcomeA = a.get(30, TimeUnit.SECONDS);

      assertThat(b.code()).isEqualTo(ErrorCodes.VALIDATION_REFUSED);
      // The D-RTR-4 window: A's object lands after the burn, and A writes nothing wrong.
      assertThat(harness.issuance("in_lk_kill").orElseThrow().state())
          .isEqualTo(IssuanceState.FAILED_VALIDATION);
      assertThat(harness.archive().size()).isEqualTo(1);
      assertThat(outcomeA.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
      // The sweep's disposed-number check names the object; it stays in the archive.
      harness.moveClockForward(Duration.ofHours(1));
      assertThat(harness.sweep(SETTINGS).sweep().disposedWithObject()).isEqualTo(1);
      assertThat(harness.openFindings())
          .anySatisfy(
              f -> {
                assertThat(f.code()).isEqualTo(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
                assertThat(f.subjectId()).isEqualTo("in_lk_kill");
              });
      assertThat(harness.archive().size()).isEqualTo(1);
      assertThat(RowLockHolder.holderPid(PostgresSupport.dataSource(), id)).isEqualTo(-1);
    } finally {
      release.countDown();
      workerA.shutdownNow();
    }
  }

  // ------------------------------------------------------------------ R3.6: no pool starvation

  @Test
  void two_slow_writes_on_a_pool_of_two_both_issue() throws Exception {
    DataSource two = PostgresSupport.freshDatabase("pooltwo", 2);
    try {
      IssuanceTestHarness harness = IssuanceTestHarness.createOn(two).withoutRunLock();
      harness.source().with(TestInvoices.finalised("in_pool_1"));
      harness.source().with(TestInvoices.finalised("in_pool_2"));
      String e1 = harness.receive("invoice.finalized", "in_pool_1");
      String e2 = harness.receive("invoice.finalized", "in_pool_2");
      harness.store().verifySessionPinning();
      CountDownLatch bothInPut = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      IssuanceUnitOfWork slow =
          harness.unitOfWorkWith(
              harness.renderer(),
              TestValidators.passing(),
              ArchiveRetryFixture.READER,
              ArchiveRetryFixture.heldPut(harness.archive(), bothInPut, release));
      ExecutorService workers = Executors.newFixedThreadPool(2);
      try {
        Future<IssuanceUnitOfWork.Outcome> f1 = workers.submit(() -> slow.process(e1));
        Future<IssuanceUnitOfWork.Outcome> f2 = workers.submit(() -> slow.process(e2));
        assertThat(bothInPut.await(30, TimeUnit.SECONDS))
            .as("both workers hold a lock connection inside their PUT at once")
            .isTrue();
        release.countDown();

        assertThat(f1.get(30, TimeUnit.SECONDS).state()).isEqualTo(InboundState.COMPLETED);
        assertThat(f2.get(30, TimeUnit.SECONDS).state()).isEqualTo(InboundState.COMPLETED);
      } finally {
        release.countDown();
        workers.shutdownNow();
      }
    } finally {
      PostgresSupport.close(two);
    }
  }

  // ------------------------------------------------------------------ R3.4: one test per path

  private static RecordingLock recording(IssuanceTestHarness harness) {
    return new RecordingLock(harness.store());
  }

  /** The path ran under the lock: "held" answers DEI-245 with nothing written, "free" proceeds. */
  private static void tookTheLock(
      IssuanceTestHarness harness,
      RecordingLock lock,
      String invoiceId,
      java.util.function.Supplier<IssuanceUnitOfWork.Outcome> run) {
    lock.heldElsewhere.set(true);
    IssuanceUnitOfWork.Outcome held = run.get();
    assertThat(held.code()).isEqualTo(ErrorCodes.ISSUANCE_IN_FLIGHT);
    long id = harness.issuance(invoiceId).orElseThrow().id();
    assertThat(lock.asked).contains(id);
    assertThat(harness.issuance(invoiceId).orElseThrow().state())
        .isNotEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(ArchiveRetryFixture.chained(harness, invoiceId)).isEmpty();
  }

  @Test
  void the_webhook_path_takes_the_row_lock() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_path_hook"));
    String eventId = harness.receive("invoice.finalized", "in_path_hook");
    RecordingLock lock = recording(harness);
    IssuanceUnitOfWork unitOfWork = harness.unitOfWorkWithLock(lock, harness.renderer());

    tookTheLock(harness, lock, "in_path_hook", () -> unitOfWork.process(eventId));
    lock.heldElsewhere.set(false);
    assertThat(unitOfWork.process(eventId).state()).isEqualTo(InboundState.COMPLETED);
  }

  @Test
  void the_sweep_re_pick_takes_the_row_lock() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_path_due"));
    String eventId = harness.receive("invoice.finalized", "in_path_due");
    harness.archive().failWith(new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "timeout"));
    harness.unitOfWork().process(eventId);
    harness.archive().heal();
    harness.moveClockForward(Duration.ofHours(2));
    assertThat(harness.due()).extracting("eventId").contains(eventId);
    RecordingLock lock = recording(harness);
    IssuanceUnitOfWork unitOfWork = harness.unitOfWorkWithLock(lock, harness.renderer());

    tookTheLock(harness, lock, "in_path_due", () -> unitOfWork.process(eventId));
  }

  @Test
  void the_reconciliation_path_takes_the_row_lock() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_path_recon"));
    harness.moveClockForward(Duration.ofHours(1));
    harness.sweep(SETTINGS).sweep();
    String enqueued = "recon-in_path_recon";
    assertThat(harness.inbound().find(enqueued)).isPresent();
    RecordingLock lock = recording(harness);
    IssuanceUnitOfWork unitOfWork = harness.unitOfWorkWithLock(lock, harness.renderer());

    tookTheLock(harness, lock, "in_path_recon", () -> unitOfWork.process(enqueued));
  }

  @Test
  void the_reprocess_path_takes_the_row_lock() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_path_reprocess"));
    String eventId = harness.receive("invoice.finalized", "in_path_reprocess");
    AtomicBoolean profileIncomplete = new AtomicBoolean(true);
    DocumentRenderer repairable =
        new DocumentRenderer() {
          @Override
          public boolean carriesSupplyDate() {
            return true;
          }

          @Override
          public PreflightReport preflight(MappingInput input) {
            return profileIncomplete.get()
                ? PreflightReport.refused(ErrorCodes.SELLER_PROFILE_INCOMPLETE)
                : PreflightReport.passed();
          }

          @Override
          public RenderedDocument render(DocumentInput input) {
            return harness.renderer().render(input);
          }
        };
    RecordingLock lock = recording(harness);
    IssuanceUnitOfWork unitOfWork = harness.unitOfWorkWithLock(lock, repairable);
    assertThat(unitOfWork.process(eventId).state()).isEqualTo(InboundState.FAILED_MAPPING);
    profileIncomplete.set(false);

    tookTheLock(
        harness,
        lock,
        "in_path_reprocess",
        () -> {
          IssuanceReprocess.Result result =
              harness
                  .reprocessFor(unitOfWork)
                  .reprocess(new ReprocessRequest(eventId, "ops", "fixed"));
          return new IssuanceUnitOfWork.Outcome(
              eventId, result.state(), result.code(), result.legalNumber());
        });
  }

  // ------------------------------------------------------------------ the store contract (PR 41)

  @Test
  void probe_an_unavailable_archive_is_retried_on_resume_and_on_redelivery() {
    // Resume: a row with a recorded hash whose key cannot be read is retried later, never
    // re-rendered, never burned, and no new number is taken.
    Row resumed = prepare(ARCHIVING_WITH_OBJECT, "in_store_resume");
    long counter = resumed.harness().seriesCounter();
    int renders = resumed.harness().renderer().renders();
    IssuanceUnitOfWork.Outcome onResume =
        resumed.retry(
            resumed.harness().renderer(),
            TestValidators.passing(),
            ArchiveRetryFixture.READER,
            ArchiveRetryFixture.unreadable(
                resumed.harness().archive(),
                new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "unreadable")));
    assertThat(onResume.code()).isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE);
    assertThat(resumed.event().nextAttemptAt()).isNotNull();
    assertThat(resumed.harness().renderer().renders()).isEqualTo(renders);
    assertThat(resumed.harness().seriesCounter()).isEqualTo(counter);
    assertThat(resumed.now().state()).isEqualTo(IssuanceState.FAILED_ARCHIVE);

    // Redelivery: an issued number whose object cannot be read is retried, never "tampered".
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_store_redeliver"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_store_redeliver"));
    String paid = harness.receive("invoice.paid", "in_store_redeliver");
    long issuedCounter = harness.seriesCounter();
    int issuedRenders = harness.renderer().renders();
    IssuanceUnitOfWork.Outcome onRedelivery =
        harness
            .unitOfWorkWith(
                harness.renderer(),
                TestValidators.passing(),
                ArchiveRetryFixture.READER,
                ArchiveRetryFixture.unreadable(
                    harness.archive(),
                    new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "unreadable")))
            .process(paid);
    assertThat(onRedelivery.code())
        .isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE)
        .isNotEqualTo(ErrorCodes.ARCHIVED_DOCUMENT_TAMPERED);
    assertThat(harness.inbound().find(paid).orElseThrow().nextAttemptAt()).isNotNull();
    assertThat(harness.renderer().renders()).isEqualTo(issuedRenders);
    assertThat(harness.seriesCounter()).isEqualTo(issuedCounter);
    assertThat(harness.issuance("in_store_redeliver").orElseThrow().state())
        .isEqualTo(IssuanceState.ISSUED);
  }
}
