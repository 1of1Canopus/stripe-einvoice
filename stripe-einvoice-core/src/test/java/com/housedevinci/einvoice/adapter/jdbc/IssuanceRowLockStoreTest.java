package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.Mode;
import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** D-RTR-7, the issuance row lock at the store: R2, R3.1, R3.5 and the joined unit of work. */
class IssuanceRowLockStoreTest {

  private static JdbcIssuanceStore store(DataSource ds, String seller, JdbcUnitOfWork unitOfWork) {
    return TestStores.store(
        unitOfWork,
        ds,
        CHAIN,
        Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
        CLOCK,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT);
  }

  @Test
  void try_only_a_held_lock_answers_at_once_and_runs_nothing() {
    String seller = PostgresSupport.freshSeller("lock-try");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    Issuance row = store.allocate(request(key(seller), "in_try"));
    long started = System.nanoTime();
    Optional<String> ran;
    try (RowLockHolder other = RowLockHolder.hold(PostgresSupport.dataSource(), row.id())) {
      ran = store.whileHeld(row.id(), held -> "ran");
    }

    assertThat(ran).isEmpty();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(store.whileHeld(row.id(), held -> "ran")).contains("ran");
  }

  @Test
  void a_released_lock_leaves_no_lock_behind() {
    String seller = PostgresSupport.freshSeller("lock-release");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    Issuance row = store.allocate(request(key(seller), "in_release"));

    store.whileHeld(
        row.id(),
        held -> {
          assertThat(RowLockHolder.holderPid(PostgresSupport.dataSource(), row.id())).isPositive();
          return "";
        });
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            store.whileHeld(
                row.id(),
                held -> {
                  throw new IllegalStateException("work failed");
                }));

    assertThat(RowLockHolder.holderPid(PostgresSupport.dataSource(), row.id())).isEqualTo(-1);
  }

  @Test
  void two_rows_two_to_the_32_apart_share_a_key_and_only_retry() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("collision");
    try {
      String seller = PostgresSupport.freshSeller("collision");
      JdbcIssuanceStore store = store(ds, seller, JdbcUnitOfWork.ownConnection(ds));
      Issuance first = store.allocate(request(key(seller), "in_c1"));
      PostgresSupport.executeOn(
          ds, "SELECT setval('einvoice_issuance_id_seq', " + (first.id() + (1L << 32) - 1) + ")");
      Issuance second = store.allocate(request(key(seller), "in_c2"));
      assertThat(second.id()).isEqualTo(first.id() + (1L << 32));
      assertThat(IssuanceRowLocks.rowKey(second.id()))
          .isEqualTo(IssuanceRowLocks.rowKey(first.id()));

      CountDownLatch holding = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      ExecutorService a = Executors.newSingleThreadExecutor();
      try {
        Future<Optional<String>> firstHeld =
            a.submit(
                () ->
                    store.whileHeld(
                        first.id(),
                        held -> {
                          holding.countDown();
                          try {
                            release.await(30, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                          return "first";
                        }));
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(store.whileHeld(second.id(), held -> "second"))
            .as("a collision is a spurious retry: nothing ran, nothing could burn")
            .isEmpty();
        release.countDown();
        assertThat(firstHeld.get(30, TimeUnit.SECONDS)).contains("first");
        assertThat(store.whileHeld(second.id(), held -> "second")).contains("second");
      } finally {
        release.countDown();
        a.shutdownNow();
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  @Test
  void a_failed_release_evicts_the_connection_instead_of_pooling_it() {
    DataSource one = PostgresSupport.freshDatabase("evict", 2);
    try {
      String seller = PostgresSupport.freshSeller("evict");
      JdbcIssuanceStore store = store(one, seller, JdbcUnitOfWork.ownConnection(one));
      store.verifySessionPinning();
      Issuance row = store.allocate(request(key(seller), "in_evict"));

      // The holder's session is killed from outside while it holds the lock: its release fails.
      Optional<String> result =
          store.whileHeld(
              row.id(),
              held -> {
                int pid = RowLockHolder.holderPid(one, row.id());
                PostgresSupport.executeOn(one, "SELECT pg_terminate_backend(" + pid + ")");
                return "done";
              });

      assertThat(result).contains("done");
      assertThat(RowLockHolder.holderPid(one, row.id())).isEqualTo(-1);
      // Had the dead connection gone back to the pool, the next borrows would fail.
      for (int i = 0; i < 3; i++) {
        assertThat(store.whileHeld(row.id(), held -> "again")).contains("again");
      }
    } finally {
      PostgresSupport.close(one);
    }
  }

  @Test
  void an_evicted_connection_never_comes_back_from_the_pool_holding_the_lock() throws Exception {
    // R3.5 on a live session: the release answered false (the lock is not where it should be), so
    // the connection is evicted. A pool of one proves it: the next connection is a new session and
    // holds no lock.
    DataSource one = PostgresSupport.freshDatabase("evictlive", 1);
    try {
      int before;
      try (Connection c = one.getConnection()) {
        assertThat(IssuanceRowLocks.tryLock(c, 77L)).isPresent();
        try (var st = c.createStatement();
            var rs = st.executeQuery("SELECT pg_backend_pid()")) {
          rs.next();
          before = rs.getInt(1);
        }
        IssuanceRowLocks.evict(c, 77L);
      }
      try (Connection next = one.getConnection();
          var st = next.createStatement();
          var rs = st.executeQuery("SELECT pg_backend_pid()")) {
        rs.next();
        assertThat(rs.getInt(1)).as("a new session, not the evicted one").isNotEqualTo(before);
      }
      assertThat(RowLockHolder.holderPid(one, 77L)).isEqualTo(-1);
    } finally {
      PostgresSupport.close(one);
    }
  }

  @Test
  void a_joined_unit_of_work_holds_the_lock_on_the_callers_connection() throws Exception {
    DataSource single = PostgresSupport.freshDatabase("joined", 2);
    try {
      String seller = PostgresSupport.freshSeller("joined");
      JdbcIssuanceStore own = store(single, seller, JdbcUnitOfWork.ownConnection(single));
      own.verifySessionPinning();
      Issuance row = own.allocate(request(key(seller), "in_joined"));
      try (Connection caller = single.getConnection();
          Connection blocker = single.getConnection()) {
        // The pool (size 2) is now empty: any second connection the lock needed would time out.
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(single, seller, JdbcUnitOfWork.using(caller));
        String sha = "b".repeat(64);
        Optional<Issuance> marked =
            joined.whileHeld(
                row.id(),
                held ->
                    held.writer()
                        .markArchiving(
                            seller,
                            Mode.LIVE,
                            "in_joined",
                            sha,
                            com.housedevinci.einvoice.domain.ArchiveKey.of(
                                row.seriesKey(), row.legalNumber(), sha, "xml")));
        caller.commit();
        assertThat(marked).isPresent();
        assertThat(blocker.isValid(1)).isTrue();
      }
      assertThat(own.findBySource(seller, Mode.LIVE, "in_joined").orElseThrow().documentSha256())
          .isEqualTo("b".repeat(64));
    } finally {
      PostgresSupport.close(single);
    }
  }
}
