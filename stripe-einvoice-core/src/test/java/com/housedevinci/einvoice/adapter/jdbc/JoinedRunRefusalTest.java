package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * D-PR49-1, decided on QUESTIONS 50: a run never joins a caller's transaction. The guard itself,
 * not only its effect: without it a joined call would fall through to the own-connection holder on
 * the caller's connection, whose proof commits, and so would commit the caller's own writes.
 */
class JoinedRunRefusalTest {

  private static long runLocks(DataSource ds) {
    return PostgresSupport.scalarOn(
        ds,
        "SELECT count(*) FROM pg_catalog.pg_locks WHERE locktype = 'advisory' AND objsubid = 2"
            + " AND classid = "
            + (AdvisoryLocks.RUN_CLASS_ID & 0xFFFFFFFFL)
            + " AND database = (SELECT oid FROM pg_catalog.pg_database"
            + " WHERE datname = pg_catalog.current_database())");
  }

  @Test
  void a_run_in_a_callers_transaction_is_refused_before_the_lock_and_touches_nothing()
      throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("pr49refusal", 6);
    IssuanceTestHarness harness = IssuanceTestHarness.createOn(ds);
    String eventId = harness.receive("invoice.finalized", "in_pr49_refusal");
    InboundEvent stored = harness.inbound().find(eventId).orElseThrow();
    PostgresSupport.executeOn(ds, "CREATE TABLE test_caller_marker (x int)");
    AtomicBoolean ran = new AtomicBoolean();

    try (Connection caller = ds.getConnection()) {
      caller.setAutoCommit(false);
      try (Statement st = caller.createStatement()) {
        st.executeUpdate("INSERT INTO test_caller_marker VALUES (1)");
      }
      JdbcInboundRunLock joined = new JdbcInboundRunLock(JdbcUnitOfWork.using(caller), ds);

      assertThatThrownBy(
              () ->
                  joined.whileHeld(
                      stored,
                      held -> {
                        ran.set(true);
                        return "ran";
                      }))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.RUN_IN_HOST_TRANSACTION);

      assertThat(ran).as("the work never runs").isFalse();
      assertThat(runLocks(ds)).as("no run lock is taken").isZero();
      assertThat(PostgresSupport.scalarOn(ds, "SELECT count(*) FROM test_caller_marker"))
          .as("the caller's own write is not committed by us")
          .isZero();
      try (Statement st = caller.createStatement();
          ResultSet rs = st.executeQuery("SELECT count(*) FROM test_caller_marker")) {
        assertThat(rs.next()).as("the caller's transaction is not aborted").isTrue();
        assertThat(rs.getLong(1)).isEqualTo(1);
      }
      caller.rollback();
    }
    assertThat(harness.inbound().find(eventId).orElseThrow())
        .as("the stored row is as it was")
        .isEqualTo(stored);
  }
}
