package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Probes, in the sense the review process means: each one is first shown to go <b>RED</b> when its
 * control is removed, and only then shown green with the control in place.
 *
 * <p>A test that passes whether or not the control exists is not evidence of anything, and this
 * suite has two controls whose absence is invisible in normal operation - the transactional counter
 * and the append-only triggers.
 */
class CipherProbeNumberingTest {

  @Test
  void cipher_probe_a_rollback_burns_a_sequence_value_and_does_not_burn_our_counter()
      throws Exception {
    // The RED half, run against the mechanism the specification originally named: a PostgreSQL
    // SEQUENCE. nextval is deliberately non-transactional, so a rolled-back transaction leaves a
    // permanent hole - which is what a legal series may never have.
    PostgresSupport.execute("CREATE SEQUENCE IF NOT EXISTS probe_sequence_that_burns");
    long burned;
    try (Connection c = PostgresSupport.dataSource().getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement();
          var rs = st.executeQuery("SELECT nextval('probe_sequence_that_burns')")) {
        rs.next();
      }
      c.rollback();
      try (Statement st = c.createStatement();
          var rs = st.executeQuery("SELECT last_value FROM probe_sequence_that_burns")) {
        rs.next();
        burned = rs.getLong(1);
      }
    }
    assertThat(burned)
        .describedAs("a sequence value survives the rollback of the transaction that took it")
        .isEqualTo(1L);

    // The GREEN half, same failure, this module's mechanism: the counter is ordinary MVCC row
    // state, so the rolled-back transaction leaves it exactly where it was.
    String seller = PostgresSupport.freshSeller("probe-rollback");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_probe_1"));
    try (Connection c = PostgresSupport.dataSource().getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.executeUpdate(
            "UPDATE einvoice_series SET next_number = next_number + 1, updated_at = now()"
                + " WHERE seller_id = '"
                + seller
                + "'");
      }
      c.rollback();
    }
    assertThat(
            PostgresSupport.scalar(
                "SELECT next_number FROM einvoice_series WHERE seller_id = '" + seller + "'"))
        .describedAs("the counter is where the committed allocation left it")
        .isEqualTo(2L);
    assertThat(store.allocate(request(key(seller), "in_probe_2")).legalNumber().counter())
        .isEqualTo(2L);
  }

  @Test
  void cipher_probe_the_append_only_trigger_is_what_refuses_a_delete_not_the_grant()
      throws Exception {
    // RED half: with the trigger disabled, the very statement the suite relies on being refused
    // succeeds. If this half ever stops succeeding, the other half has stopped proving anything.
    String seller = PostgresSupport.freshSeller("probe-trigger");
    PostgresSupport.store(seller, SIX, CLOCK, CHAIN).allocate(request(key(seller), "in_probe_t"));
    PostgresSupport.execute(
        "ALTER TABLE einvoice_issuance DISABLE TRIGGER einvoice_issuance_guard");
    boolean deletedWithoutTheTrigger = false;
    try (Connection c = PostgresSupport.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate("DELETE FROM einvoice_issuance WHERE seller_id = '" + seller + "'");
      deletedWithoutTheTrigger = true;
    } catch (SQLException refused) {
      deletedWithoutTheTrigger = false;
    } finally {
      PostgresSupport.execute(
          "ALTER TABLE einvoice_issuance ENABLE TRIGGER einvoice_issuance_guard");
    }
    assertThat(deletedWithoutTheTrigger)
        .describedAs("with the trigger off the row can be deleted, so the probe can go red")
        .isTrue();

    // GREEN half: with the trigger back, the same statement is refused.
    String second = PostgresSupport.freshSeller("probe-trigger-on");
    PostgresSupport.store(second, SIX, CLOCK, CHAIN).allocate(request(key(second), "in_probe_t2"));
    boolean refused = false;
    try (Connection c = PostgresSupport.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate("DELETE FROM einvoice_issuance WHERE seller_id = '" + second + "'");
    } catch (SQLException expected) {
      refused = expected.getMessage().contains("append-only");
    }
    assertThat(refused).isTrue();
  }
}
