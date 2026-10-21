package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  // ------------------------------------------------------------------ Revision 3 (D-PR42-2)

  @Test
  void probe_a_rolled_back_held_unit_keeps_the_token_on_a_pool_without_autocommit() {
    // C1: a Spring host often sets hikari auto-commit=false. The TRY must commit on its own, or the
    // first held unit's rollback undoes the token (not the lock) and every later proof fails.
    DataSource manual = PostgresSupport.freshDatabase("noautocommit", 2, false);
    try {
      String seller = PostgresSupport.freshSeller("noautocommit");
      JdbcIssuanceStore store = store(manual, seller, JdbcUnitOfWork.ownConnection(manual));
      Issuance row = store.allocate(request(key(seller), "in_noauto"));

      Optional<String> firstError =
          store.whileHeld(
              row.id(),
              held -> {
                String code = "";
                try {
                  held.writer().markIssued(seller, Mode.LIVE, "in_noauto"); // NUMBERED: refused
                } catch (com.housedevinci.einvoice.domain.EInvoiceException e) {
                  code = e.code();
                }
                // The next held unit still proves the lock.
                assertThat(held.reader().findBySource(seller, Mode.LIVE, "in_noauto")).isPresent();
                return code;
              });

      assertThat(firstError)
          .contains(com.housedevinci.einvoice.domain.ErrorCodes.ILLEGAL_TRANSITION);
      assertThat(RowLockHolder.holderPid(manual, row.id()))
          .as("released, not evicted")
          .isEqualTo(-1);
    } finally {
      PostgresSupport.close(manual);
    }
  }

  @Test
  void the_proof_is_false_not_null_on_a_session_without_a_token() throws Exception {
    // C3: the proof is written "(...) IS TRUE", so SQL itself answers false.
    try (Connection c = PostgresSupport.dataSource().getConnection();
        var ps = c.prepareStatement("SELECT " + IssuanceRowLocks.PROOF)) {
      ps.setString(1, "5:" + "a".repeat(32));
      ps.setLong(2, AdvisoryLocks.ROW_CLASS_ID & 0xFFFFFFFFL);
      ps.setLong(3, 5L);
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getObject(1)).isEqualTo(Boolean.FALSE);
      }
    }
  }

  @Test
  void a_new_session_holding_the_key_without_the_token_fails_the_proof() throws Exception {
    // Pid reuse, faked: the OS cannot be made to hand out the same pid, so a new session takes the
    // key itself. "This backend holds the key" is then true; the old acquisition's token is not on
    // it. The proof never compares pids, so it fails.
    long id = 909_090L;
    String oldToken;
    try (Connection old = PostgresSupport.dataSource().getConnection()) {
      oldToken = IssuanceRowLocks.tryLock(old, id).orElseThrow();
      assertThat(IssuanceRowLocks.unlock(old, id, oldToken)).isTrue();
    }
    try (Connection fresh = PostgresSupport.dataSource().getConnection();
        var st = fresh.createStatement()) {
      st.execute(
          "SELECT pg_try_advisory_lock(" + AdvisoryLocks.ROW_CLASS_ID + ", " + (int) id + ")");
      try {
        assertThat(IssuanceRowLocks.prove(fresh, id, oldToken)).isFalse();
      } finally {
        st.execute(
            "SELECT pg_advisory_unlock(" + AdvisoryLocks.ROW_CLASS_ID + ", " + (int) id + ")");
      }
    }
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

      // The holder's session is killed from outside while it holds the lock: its release fails,
      // and since Revision 3 (P2) a release that cannot prove the lock answers DEI-104.
      assertThatThrownBy(
              () ->
                  store.whileHeld(
                      row.id(),
                      held -> {
                        int pid = RowLockHolder.holderPid(one, row.id());
                        PostgresSupport.executeOn(one, "SELECT pg_terminate_backend(" + pid + ")");
                        return "done";
                      }))
          .isInstanceOf(com.housedevinci.einvoice.domain.EInvoiceException.class)
          .extracting("code")
          .isEqualTo(com.housedevinci.einvoice.domain.ErrorCodes.SESSION_NOT_PINNED);

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
