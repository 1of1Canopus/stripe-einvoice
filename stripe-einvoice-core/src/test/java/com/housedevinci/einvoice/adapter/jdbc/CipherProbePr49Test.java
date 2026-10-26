package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 49 pass 1 (one run per invoice). Install under
 * stripe-einvoice-core/src/test/java/com/housedevinci/einvoice/adapter/jdbc/.
 *
 * <p>D-PR49-1. In joined mode (a caller's transaction on the thread), a held unit that fails after
 * the run has written poisons the caller's transaction at once (AbstractJdbcUnitOfWork.runJoined).
 * The run lock's release then runs in an aborted transaction, fails with 25P02, and the session
 * lock stays on the caller's pooled connection. Every later run of that invoice answers DEI-217
 * "busy" while no run is live, until the pool retires that connection. The PR 43 row lock releases
 * before it poisons (JdbcIssuanceStore.heldJoined); the run lock does not.
 */
class CipherProbePr49Test {

  private static long runLocks(DataSource ds) {
    return PostgresSupport.scalarOn(
        ds,
        "SELECT count(*) FROM pg_catalog.pg_locks WHERE locktype = 'advisory' AND objsubid = 2"
            + " AND classid = "
            + (AdvisoryLocks.RUN_CLASS_ID & 0xFFFFFFFFL)
            + " AND database = (SELECT oid FROM pg_catalog.pg_database"
            + " WHERE datname = pg_catalog.current_database())");
  }

  /** Any statement failure after the stamp (a timeout, a constraint, a full disk) will do. */
  private static void refuseFetchedWrite(DataSource ds) {
    PostgresSupport.executeOn(
        ds,
        "CREATE OR REPLACE FUNCTION test_refuse_fetched() RETURNS trigger AS $$ BEGIN IF"
            + " NEW.state = 'FETCHED' THEN RAISE EXCEPTION 'test refusal'; END IF; RETURN NEW;"
            + " END $$ LANGUAGE plpgsql");
    PostgresSupport.executeOn(
        ds,
        "CREATE TRIGGER test_refuse_fetched BEFORE UPDATE ON einvoice_inbound_event"
            + " FOR EACH ROW EXECUTE FUNCTION test_refuse_fetched()");
  }

  @Test
  void probe_a_failed_joined_run_leaves_its_run_lock_on_the_callers_connection() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("pr49joined", 6);
    IssuanceTestHarness harness = IssuanceTestHarness.createOn(ds);
    String eventId = harness.receive("invoice.finalized", "in_pr49_joined");
    InboundEvent stored = harness.inbound().find(eventId).orElseThrow();
    refuseFetchedWrite(ds);

    try (Connection caller = ds.getConnection()) {
      caller.setAutoCommit(false);
      JdbcInboundRunLock joined = new JdbcInboundRunLock(JdbcUnitOfWork.using(caller), ds);

      assertThatThrownBy(
              () ->
                  joined.whileHeld(
                      stored,
                      held -> {
                        held.inbound()
                            .stampAttempt(
                                eventId,
                                InboundState.RECEIVED,
                                harness.now(),
                                Duration.ofMinutes(1));
                        return held.inbound()
                            .transitionFrom(
                                eventId,
                                InboundState.RECEIVED,
                                InboundState.FETCHED,
                                "",
                                "",
                                harness.now(),
                                Duration.ofMinutes(1));
                      }))
          .isInstanceOf(RuntimeException.class);
      caller.rollback();
    }
    PostgresSupport.executeOn(ds, "DROP TRIGGER test_refuse_fetched ON einvoice_inbound_event");

    // The caller's connection is back in its pool. No run is live, so the next run must get in:
    // DEI-217 means "another run is live", and none is.
    Optional<String> next = harness.runLock().whileHeld(stored, held -> "next");
    assertThat(next).as("a run with no other run live is never busy").contains("next");
    assertThat(runLocks(ds))
        .as("run locks left on a pooled session after the caller rolled back")
        .isZero();
  }

  /**
   * Pass 2, D-PR49-1 on its own (mutation F1: the joined check removed). The host writes a row in
   * its transaction, calls a run that would stamp the event, and rolls back. Nothing may stay
   * committed: not the host's row, not the run's stamp. Without the guard the call falls through to
   * the own-connection path on the caller's connection, whose commits publish both.
   */
  @Test
  void probe_a_host_rollback_after_a_run_leaves_nothing_committed() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("pr49rollback", 6);
    IssuanceTestHarness harness = IssuanceTestHarness.createOn(ds);
    String eventId = harness.receive("invoice.finalized", "in_pr49_rollback");
    InboundEvent stored = harness.inbound().find(eventId).orElseThrow();
    PostgresSupport.executeOn(ds, "CREATE TABLE test_host_row (x int)");
    Throwable thrown = null;

    try (Connection caller = ds.getConnection()) {
      caller.setAutoCommit(false);
      try (Statement st = caller.createStatement()) {
        st.executeUpdate("INSERT INTO test_host_row VALUES (1)");
      }
      JdbcInboundRunLock joined = new JdbcInboundRunLock(JdbcUnitOfWork.using(caller), ds);
      try {
        joined.whileHeld(
            stored,
            held ->
                held.inbound()
                    .stampAttempt(
                        eventId, InboundState.RECEIVED, harness.now(), Duration.ofMinutes(1)));
      } catch (RuntimeException e) {
        thrown = e;
      }
      caller.rollback();
    }

    assertThat(PostgresSupport.scalarOn(ds, "SELECT count(*) FROM test_host_row"))
        .as("the host rolled back: its row must not stay committed")
        .isZero();
    assertThat(harness.inbound().find(eventId).orElseThrow())
        .as("the host rolled back: the run's stamp must not stay committed")
        .isEqualTo(stored);
    assertThat(runLocks(ds)).as("run locks left after the host rolled back").isZero();
    assertThat(thrown)
        .as("a run inside a caller's transaction is refused")
        .isInstanceOf(EInvoiceException.class);
    assertThat(((EInvoiceException) thrown).code()).isEqualTo(ErrorCodes.RUN_IN_HOST_TRANSACTION);
  }
}
