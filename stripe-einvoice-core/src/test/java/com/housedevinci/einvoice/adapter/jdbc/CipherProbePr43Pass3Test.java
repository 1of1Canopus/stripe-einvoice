package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Security review, pass 3 of PR 43 (D-PR43-1 candidate B as built, D-PR43-6). */
class CipherProbePr43Pass3Test {

  private static final String SHA = "d".repeat(64);

  private static JdbcIssuanceStore store(DataSource ds, String seller, JdbcUnitOfWork uow) {
    return TestStores.store(
        uow,
        ds,
        CHAIN,
        Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
        CLOCK,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT);
  }

  private static long rowLocks(DataSource ds) {
    return PostgresSupport.scalarOn(
        ds,
        "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = "
            + (AdvisoryLocks.ROW_CLASS_ID & 0xFFFFFFFFL));
  }

  private static long nextNumber(DataSource ds, String seller) {
    return PostgresSupport.scalarOn(
        ds,
        "SELECT coalesce(max(next_number), 1) FROM einvoice_series WHERE seller_id = '"
            + seller
            + "'");
  }

  private static long rows(DataSource ds, String invoiceId) {
    return PostgresSupport.scalarOn(
        ds, "SELECT count(*) FROM einvoice_issuance WHERE stripe_invoice_id = '" + invoiceId + "'");
  }

  /** The issuance insert of {@code invoiceId} fails, after the allocation moved the counter. */
  private static void refuseIssuanceInsertOf(DataSource ds, String invoiceId) {
    PostgresSupport.executeOn(
        ds,
        "CREATE OR REPLACE FUNCTION test_refuse_insert() RETURNS trigger AS $$ BEGIN IF"
            + " NEW.stripe_invoice_id = '"
            + invoiceId
            + "' THEN RAISE EXCEPTION 'test refusal'; END IF; RETURN NEW; END $$ LANGUAGE"
            + " plpgsql");
    PostgresSupport.executeOn(
        ds,
        "CREATE TRIGGER test_refuse_insert BEFORE INSERT ON einvoice_issuance FOR EACH ROW"
            + " EXECUTE FUNCTION test_refuse_insert()");
  }

  /** The chained ISSUED event of {@code invoiceId} fails, after the state write. */
  private static void refuseIssuedEventOf(DataSource ds, String invoiceId) {
    PostgresSupport.executeOn(
        ds,
        "CREATE OR REPLACE FUNCTION test_refuse_issued() RETURNS trigger AS $$ BEGIN IF"
            + " NEW.stripe_invoice_id = '"
            + invoiceId
            + "' AND NEW.state = 'ISSUED' THEN RAISE EXCEPTION 'test refusal'; END IF; RETURN"
            + " NEW; END $$ LANGUAGE plpgsql");
    PostgresSupport.executeOn(
        ds,
        "CREATE TRIGGER test_refuse_issued BEFORE INSERT ON einvoice_issuance_event FOR EACH ROW"
            + " EXECUTE FUNCTION test_refuse_issued()");
  }

  private static String autosaveOf(Connection c) throws Exception {
    Class<?> pg = Class.forName("org.postgresql.PGConnection");
    return String.valueOf(pg.getMethod("getAutosave").invoke(c.unwrap(pg)));
  }

  /**
   * Item 2 (D-PR43-6 scope). A joined allocation runs outside the held section, so it never reaches
   * the DEI-249 check. Under autosave=always the driver undoes the failed insert and the point-3
   * poison, and a plain JDBC caller that catches and commits keeps a moved counter with no issuance
   * row (T-03).
   */
  @Test
  void probe_a_joined_allocation_on_autosave_always_is_refused_before_any_number()
      throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("p3alloc", 3, true, "autosave=always");
    try {
      String seller = PostgresSupport.freshSeller("p3alloc");
      store(ds, seller, JdbcUnitOfWork.ownConnection(ds)).allocate(request(key(seller), "in_p3a0"));
      long before = nextNumber(ds, seller);
      refuseIssuanceInsertOf(ds, "in_p3a1");
      String code;
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        EInvoiceException e =
            Assertions.assertThrows(
                EInvoiceException.class, () -> joined.allocate(request(key(seller), "in_p3a1")));
        code = e.code();
        caller.commit();
      }
      assertThat(nextNumber(ds, seller))
          .as("no number consumed by the caller's commit")
          .isEqualTo(before);
      assertThat(code).isEqualTo(ErrorCodes.HOST_AUTOSAVE_UNSUPPORTED);
    } finally {
      PostgresSupport.close(ds);
    }
  }

  /**
   * Item 1 (DEI-248 scope). The public code says "25P02 at the first statement of a joined call". A
   * joined allocation on a transaction the caller already aborted must answer DEI-248, never the
   * outage code DEI-102.
   */
  @Test
  void probe_a_joined_allocation_on_an_aborted_transaction_is_dei_248() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("p3abort", 3);
    try {
      String seller = PostgresSupport.freshSeller("p3abort");
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        try (Statement st = caller.createStatement()) {
          st.execute("SELECT 1/0");
        } catch (SQLException expected) {
          // the caller's own failure aborts its transaction
        }
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        EInvoiceException e =
            Assertions.assertThrows(
                EInvoiceException.class, () -> joined.allocate(request(key(seller), "in_p3b")));
        caller.rollback();
        assertThat(e.code()).isEqualTo(ErrorCodes.HOST_TRANSACTION_ABORTED);
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  /** Item 1: whileHeld entered on an aborted caller transaction: DEI-248, no key taken. */
  @Test
  void probe_a_held_entry_on_an_aborted_transaction_is_dei_248_with_no_key() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("p3heldabort", 3);
    try {
      String seller = PostgresSupport.freshSeller("p3heldabort");
      Issuance row =
          store(ds, seller, JdbcUnitOfWork.ownConnection(ds))
              .allocate(request(key(seller), "in_p3c"));
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        try (Statement st = caller.createStatement()) {
          st.execute("SELECT 1/0");
        } catch (SQLException expected) {
          // aborted
        }
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        EInvoiceException e =
            Assertions.assertThrows(
                EInvoiceException.class, () -> joined.whileHeld(row.id(), held -> "ran"));
        assertThat(e.code()).isEqualTo(ErrorCodes.HOST_TRANSACTION_ABORTED);
        assertThat(rowLocks(ds)).isZero();
        caller.rollback();
        assertThat(caller.isClosed()).isFalse();
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  /**
   * Item 1: the work catches a failed unit, writes again and returns normally. The run must still
   * refuse with the failed unit's code, the poison must be the module's DE248, the lock must be
   * released, the connection open, and the caller's commit must commit nothing.
   */
  @Test
  void probe_a_swallowed_unit_failure_then_a_good_write_still_commits_nothing() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("p3swallow", 3);
    try {
      String seller = PostgresSupport.freshSeller("p3swallow");
      refuseIssuedEventOf(ds, "in_p3d");
      Issuance other =
          store(ds, seller, JdbcUnitOfWork.ownConnection(ds))
              .allocate(request(key(seller), "in_p3e"));
      long before;
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        before = nextNumber(ds, seller);
        Issuance row = joined.allocate(request(key(seller), "in_p3d"));
        EInvoiceException e =
            Assertions.assertThrows(
                EInvoiceException.class,
                () ->
                    joined.whileHeld(
                        row.id(),
                        held -> {
                          held.writer()
                              .markArchiving(
                                  seller,
                                  Mode.LIVE,
                                  "in_p3d",
                                  SHA,
                                  ArchiveKey.of(row.seriesKey(), row.legalNumber(), SHA, "xml"));
                          try {
                            held.writer().markIssued(seller, Mode.LIVE, "in_p3d");
                          } catch (EInvoiceException swallowed) {
                            // the run carries on
                          }
                          return held.reader().findBySource(seller, Mode.LIVE, "in_p3d");
                        }));
        assertThat(e.code()).isEqualTo(ErrorCodes.STORE_UNAVAILABLE);
        assertThat(e.getSuppressed())
            .anySatisfy(t -> assertThat(((SQLException) t).getSQLState()).isEqualTo("DE248"));
        assertThat(rowLocks(ds)).as("released before the poison").isZero();
        caller.commit();
        assertThat(caller.isClosed()).isFalse();
        assertThat(caller.getAutoCommit()).isFalse();
      }
      assertThat(rows(ds, "in_p3d")).isZero();
      assertThat(nextNumber(ds, seller)).isEqualTo(before);
      assertThat(
              store(ds, seller, JdbcUnitOfWork.ownConnection(ds))
                  .findBySource(seller, Mode.LIVE, "in_p3e")
                  .orElseThrow()
                  .state())
          .isEqualTo(IssuanceState.NUMBERED);
      assertThat(other.id()).isNotEqualTo(0L);
    } finally {
      PostgresSupport.close(ds);
    }
  }

  /**
   * Deviation 1 (J5). R2: a release failure alone must not mark the caller rollback-only. For a
   * plain JDBC caller rollback-only means the module refuses its next call in that transaction, so
   * a next call that succeeds kills the briefed mutation.
   */
  @Test
  void probe_a_release_failure_alone_leaves_the_next_call_usable() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("p3r2", 3);
    try {
      String seller = PostgresSupport.freshSeller("p3r2");
      Issuance row =
          store(ds, seller, JdbcUnitOfWork.ownConnection(ds))
              .allocate(request(key(seller), "in_p3f"));
      try (Connection real = ds.getConnection()) {
        Connection caller =
            (Connection)
                java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      if (method.getName().equals("prepareStatement")
                          && IssuanceRowLocks.UNLOCK.equals(args[0])) {
                        throw new SQLException("unlock refused (probe)", "XX000");
                      }
                      try {
                        return method.invoke(real, args);
                      } catch (java.lang.reflect.InvocationTargetException ex) {
                        throw ex.getCause();
                      }
                    });
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        Optional<String> outcome = joined.whileHeld(row.id(), held -> "ok");
        assertThat(outcome).contains("ok");
        assertThat(joined.findBySource(seller, Mode.LIVE, "in_p3f"))
            .as("the module's next call in the same transaction is not refused")
            .isPresent();
        real.commit();
        try (Statement st = real.createStatement()) {
          st.execute("SELECT pg_advisory_unlock_all()");
        }
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  /** Item 2: own connections run under autosave NEVER and give the pool back its own mode. */
  @Test
  void probe_own_connections_set_never_and_restore_the_pools_mode() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("p3own", 2, true, "autosave=always");
    try {
      try (Connection c = ds.getConnection()) {
        Object previous = IssuanceRowLocks.autosaveNever(c);
        assertThat(String.valueOf(previous)).isEqualTo("ALWAYS");
        assertThat(autosaveOf(c)).isEqualTo("NEVER");
        IssuanceRowLocks.restoreAutosave(c, previous);
        assertThat(autosaveOf(c)).isEqualTo("ALWAYS");
      }
      String seller = PostgresSupport.freshSeller("p3own");
      JdbcIssuanceStore own = store(ds, seller, JdbcUnitOfWork.ownConnection(ds));
      JdbcIssuanceStore.trustSessionPinningForTest(ds);
      Issuance row = own.allocate(request(key(seller), "in_p3g"));
      assertThat(own.whileHeld(row.id(), held -> "held")).contains("held");
      List<Connection> all = new ArrayList<>();
      try {
        for (int i = 0; i < 2; i++) {
          all.add(ds.getConnection());
        }
        for (Connection c : all) {
          assertThat(autosaveOf(c)).as("the pool's own mode is back").isEqualTo("ALWAYS");
        }
      } finally {
        for (Connection c : all) {
          c.close();
        }
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }
}
