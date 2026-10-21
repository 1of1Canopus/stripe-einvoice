package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * D-PR43-1 (candidate B) and D-PR43-6: the issuance row lock inside a caller's transaction.
 * Savepoint per held unit, the poison after a failure once the transaction wrote, autosave ALWAYS
 * refused, DEI-248 on an aborted entry, the caller's connection never aborted or closed.
 */
class JoinedModeLockTest {

  private static final String SHA = "c".repeat(64);

  private static JdbcIssuanceStore store(DataSource ds, String seller, JdbcUnitOfWork unitOfWork) {
    return TestStores.store(
        unitOfWork,
        ds,
        CHAIN,
        Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
        CLOCK,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT);
  }

  /** A test trigger: the chained ISSUED event of {@code invoiceId} fails, after the state write. */
  private static void refuseIssuedEventOf(DataSource ds, String invoiceId) {
    PostgresSupport.executeOn(
        ds,
        "CREATE OR REPLACE FUNCTION test_refuse_issued_event() RETURNS trigger AS $$ BEGIN IF"
            + " NEW.stripe_invoice_id = '"
            + invoiceId
            + "' AND NEW.state = 'ISSUED' THEN RAISE EXCEPTION 'test refusal'; END IF; RETURN"
            + " NEW; END $$ LANGUAGE plpgsql");
    PostgresSupport.executeOn(
        ds,
        "CREATE TRIGGER test_refuse BEFORE INSERT ON einvoice_issuance_event FOR EACH ROW"
            + " EXECUTE FUNCTION test_refuse_issued_event()");
  }

  private static long rowLocks(DataSource ds) {
    return PostgresSupport.scalarOn(
        ds,
        "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = "
            + (AdvisoryLocks.ROW_CLASS_ID & 0xFFFFFFFFL));
  }

  private static long rows(DataSource ds, String invoiceId) {
    return PostgresSupport.scalarOn(
        ds, "SELECT count(*) FROM einvoice_issuance WHERE stripe_invoice_id = '" + invoiceId + "'");
  }

  /** Unit 1 marks ARCHIVING (writes), unit 2 marks ISSUED and fails at the chained event. */
  private static Optional<String> halfWritten(
      JdbcIssuanceStore joined, String seller, Issuance row, String invoiceId) {
    return joined.whileHeld(
        row.id(),
        held -> {
          held.writer()
              .markArchiving(
                  seller,
                  Mode.LIVE,
                  invoiceId,
                  SHA,
                  ArchiveKey.of(row.seriesKey(), row.legalNumber(), SHA, "xml"));
          try {
            held.writer().markIssued(seller, Mode.LIVE, invoiceId);
            return "issued";
          } catch (EInvoiceException e) {
            return e.code(); // the run handles it, as the unit of work does
          }
        });
  }

  /** The caller's connection, counting abort() and close(), optionally refusing the UNLOCK. */
  private static Connection spy(Connection real, AtomicInteger abortsOrCloses, boolean failUnlock) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.equals("abort") || name.equals("close")) {
                abortsOrCloses.incrementAndGet();
              }
              if (failUnlock
                  && name.equals("prepareStatement")
                  && IssuanceRowLocks.UNLOCK.equals(args[0])) {
                throw new SQLException("unlock refused (test)", "XX000");
              }
              try {
                return method.invoke(real, args);
              } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  @ParameterizedTest(name = "autosave {0}")
  @ValueSource(strings = {"never", "conservative"})
  void probe_a_caught_joined_failure_never_commits_a_half_written_unit(String autosave)
      throws Exception {
    DataSource ds =
        PostgresSupport.freshDatabase("half" + autosave, 3, true, "autosave=" + autosave);
    try {
      String seller = PostgresSupport.freshSeller("half");
      refuseIssuedEventOf(ds, "in_half");
      long counterBefore = PostgresSupport.scalarOn(ds, "SELECT count(*) FROM einvoice_series");
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        Issuance row = joined.allocate(request(key(seller), "in_half"));
        assertThatThrownBy(() -> halfWritten(joined, seller, row, "in_half"))
            .isInstanceOf(EInvoiceException.class);
        caller.commit(); // returns normally; PostgreSQL answers ROLLBACK

        assertThat(rows(ds, "in_half")).as("no half-written invoice committed").isZero();
        assertThat(PostgresSupport.scalarOn(ds, "SELECT count(*) FROM einvoice_series"))
            .as("the number was not consumed")
            .isEqualTo(counterBefore);
        assertThat(rowLocks(ds)).isZero();
        assertThat(caller.isClosed()).isFalse();
        assertThat(caller.getAutoCommit()).isFalse();
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  @Test
  void probe_an_autosave_always_connection_is_refused_before_any_key() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("always", 3, true, "autosave=always");
    try {
      String seller = PostgresSupport.freshSeller("always");
      Issuance row =
          store(ds, seller, JdbcUnitOfWork.ownConnection(ds))
              .allocate(request(key(seller), "in_al"));
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));

        assertThatThrownBy(() -> halfWritten(joined, seller, row, "in_al"))
            .isInstanceOf(EInvoiceException.class)
            .extracting("code")
            .isEqualTo(ErrorCodes.HOST_AUTOSAVE_UNSUPPORTED);
        assertThat(rowLocks(ds)).isZero();
        caller.commit();
        assertThat(caller.isClosed()).isFalse();
      }
      assertThat(
              store(ds, seller, JdbcUnitOfWork.ownConnection(ds))
                  .findBySource(seller, Mode.LIVE, "in_al")
                  .orElseThrow()
                  .state())
          .as("nothing written")
          .isEqualTo(IssuanceState.NUMBERED);
    } finally {
      PostgresSupport.close(ds);
    }
  }

  @Test
  void probe_the_poison_aborts_the_transaction_and_is_confirmed() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("poison", 3);
    try {
      String seller = PostgresSupport.freshSeller("poison");
      refuseIssuedEventOf(ds, "in_poison");
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        Issuance row = joined.allocate(request(key(seller), "in_poison"));
        EInvoiceException refused =
            (EInvoiceException)
                org.junit.jupiter.api.Assertions.assertThrows(
                    EInvoiceException.class, () -> halfWritten(joined, seller, row, "in_poison"));

        assertThat(Arrays.stream(refused.getSuppressed()))
            .as("the module's poison was its own DE248")
            .anySatisfy(t -> assertThat(((SQLException) t).getSQLState()).isEqualTo("DE248"));
        assertThatThrownBy(
                () -> {
                  try (Statement st = caller.createStatement()) {
                    st.execute("SELECT 1");
                  }
                })
            .isInstanceOf(SQLException.class)
            .extracting("SQLState")
            .isEqualTo("25P02");
        caller.rollback();
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  @Test
  void a_poison_that_returns_normally_is_not_confirmed() throws Exception {
    // The confirmation, not the poison's own answer, decides (test double: a statement that
    // succeeds in place of the poison).
    try (Connection c = PostgresSupport.dataSource().getConnection()) {
      c.setAutoCommit(false);
      IssuanceRowLocks.Poisoned result = IssuanceRowLocks.poison(c, "SELECT 1");
      assertThat(result.confirmed()).isFalse();
      assertThat(IssuanceRowLocks.poison(c, IssuanceRowLocks.POISON).confirmed()).isTrue();
      c.rollback();
    }
  }

  @Test
  void probe_the_callers_connection_is_open_and_usable_after_our_failure() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("usable", 3);
    try {
      String seller = PostgresSupport.freshSeller("usable");
      refuseIssuedEventOf(ds, "in_usable");
      Issuance later =
          store(ds, seller, JdbcUnitOfWork.ownConnection(ds))
              .allocate(request(key(seller), "in_usable_later"));
      AtomicInteger abortsOrCloses = new AtomicInteger();
      try (Connection real = ds.getConnection()) {
        Connection caller = spy(real, abortsOrCloses, false);
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        Issuance row = joined.allocate(request(key(seller), "in_usable"));
        assertThatThrownBy(() -> halfWritten(joined, seller, row, "in_usable"))
            .isInstanceOf(EInvoiceException.class);
        caller.commit();

        try (Statement st = caller.createStatement()) {
          st.execute("SELECT 1");
        }
        JdbcIssuanceStore next = store(ds, seller, JdbcUnitOfWork.using(caller));
        assertThat(next.whileHeld(later.id(), held -> "next call")).contains("next call");
        caller.commit();
        assertThat(abortsOrCloses).as("never aborted or closed by the module").hasValue(0);
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  @Test
  void probe_a_throwing_unlock_still_poisons() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("unlockthrows", 3);
    try {
      String seller = PostgresSupport.freshSeller("unlockthrows");
      refuseIssuedEventOf(ds, "in_unlock");
      try (Connection real = ds.getConnection()) {
        Connection caller = spy(real, new AtomicInteger(), true);
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        Issuance row = joined.allocate(request(key(seller), "in_unlock"));
        assertThatThrownBy(() -> halfWritten(joined, seller, row, "in_unlock"))
            .isInstanceOf(EInvoiceException.class)
            .extracting("code")
            .as("the run's own failure code, not a release code")
            .isEqualTo(ErrorCodes.STORE_UNAVAILABLE);
        real.commit();
        assertThat(rows(ds, "in_unlock")).isZero();
        try (Statement st = real.createStatement()) {
          st.execute("SELECT pg_advisory_unlock_all()");
        }
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  @Test
  void a_release_failure_alone_keeps_the_outcome_and_the_writes() throws Exception {
    // R2: the run succeeded, only its release failed: DEI-247 logged, no poison, the caller
    // commits the run's writes.
    DataSource ds = PostgresSupport.freshDatabase("releaseonly", 3);
    try {
      String seller = PostgresSupport.freshSeller("releaseonly");
      Issuance row =
          store(ds, seller, JdbcUnitOfWork.ownConnection(ds))
              .allocate(request(key(seller), "in_r2"));
      try (Connection real = ds.getConnection()) {
        Connection caller = spy(real, new AtomicInteger(), true);
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        Optional<String> outcome =
            joined.whileHeld(
                row.id(),
                held -> {
                  held.writer()
                      .markArchiving(
                          seller,
                          Mode.LIVE,
                          "in_r2",
                          SHA,
                          ArchiveKey.of(row.seriesKey(), row.legalNumber(), SHA, "xml"));
                  return "archiving";
                });
        assertThat(outcome).contains("archiving");
        real.commit();
        try (Statement st = real.createStatement()) {
          st.execute("SELECT pg_advisory_unlock_all()");
        }
      }
      assertThat(
              store(ds, seller, JdbcUnitOfWork.ownConnection(ds))
                  .findBySource(seller, Mode.LIVE, "in_r2")
                  .orElseThrow()
                  .state())
          .isEqualTo(IssuanceState.ARCHIVING);
    } finally {
      PostgresSupport.close(ds);
    }
  }

  @Test
  void probe_aborted_entry_on_a_later_unit_is_dei_248() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("entry248", 3);
    try {
      String seller = PostgresSupport.freshSeller("entry248");
      JdbcIssuanceStore own = store(ds, seller, JdbcUnitOfWork.ownConnection(ds));
      Issuance first = own.allocate(request(key(seller), "in_e1"));
      Issuance second = own.allocate(request(key(seller), "in_e2"));
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        assertThat(joined.whileHeld(first.id(), held -> "one")).contains("one");
        assertThatThrownBy(
                () -> {
                  try (Statement st = caller.createStatement()) {
                    st.execute("SELECT 1/0"); // the caller's own failure aborts its transaction
                  }
                })
            .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> joined.whileHeld(second.id(), held -> "two"))
            .isInstanceOf(EInvoiceException.class)
            .extracting("code")
            .isEqualTo(ErrorCodes.HOST_TRANSACTION_ABORTED);
        caller.rollback();
        assertThat(rowLocks(ds)).as("no key taken").isZero();
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  @Test
  void a_callers_own_savepoint_stays_usable_around_our_call() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("callersp", 3);
    try {
      String seller = PostgresSupport.freshSeller("callersp");
      Issuance row =
          store(ds, seller, JdbcUnitOfWork.ownConnection(ds))
              .allocate(request(key(seller), "in_sp"));
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        Savepoint mine = caller.setSavepoint("einvoice_held");
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        assertThat(joined.whileHeld(row.id(), held -> "ok")).contains("ok");
        caller.rollback(mine);
        caller.commit();
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  @Test
  void a_typed_refusal_after_a_write_outside_the_lock_poisons_the_callers_commit()
      throws Exception {
    // Point 3: rollback-only alone does not stop a plain JDBC caller's commit.
    DataSource ds = PostgresSupport.freshDatabase("typedrefusal", 3);
    try {
      PostgresSupport.executeOn(ds, "CREATE TABLE test_side (x int)");
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        JdbcUnitOfWork joined = JdbcUnitOfWork.using(caller);
        assertThatThrownBy(
                () ->
                    joined.inTransaction(
                        unit -> {
                          try (Statement st = unit.connection().createStatement()) {
                            st.executeUpdate("INSERT INTO test_side VALUES (1)");
                          }
                          throw new EInvoiceException(ErrorCodes.INVALID, "refused after a write");
                        }))
            .isInstanceOf(EInvoiceException.class);
        caller.commit();
      }
      assertThat(PostgresSupport.scalarOn(ds, "SELECT count(*) FROM test_side")).isZero();
    } finally {
      PostgresSupport.close(ds);
    }
  }
}
