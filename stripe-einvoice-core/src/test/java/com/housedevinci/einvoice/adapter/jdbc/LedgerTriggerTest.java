package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.Mode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What the database refuses, checked at the database and as the runtime role - not as the owner,
 * and not by reading the DDL.
 *
 * <p>Every one of these is a control the design leans on: the ledger is append-only, the number's
 * identity is immutable, the document hash and archive key are write-once, the state machine is
 * re-asserted outside the JVM, and the counter only ever advances by one.
 */
class LedgerTriggerTest {

  private static final String ROLE = "einvoice_runtime_test";
  private static final String PASSWORD = "einvoice-runtime-test";

  private static DataSource runtime;

  @BeforeAll
  static void createRuntimeRole() {
    PostgresSupport.dataSource();
    PostgresSupport.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
            + ROLE
            + "') THEN CREATE ROLE "
            + ROLE
            + " LOGIN PASSWORD '"
            + PASSWORD
            + "'; END IF; END $$");
    PostgresSupport.execute(
        "GRANT SELECT, INSERT ON einvoice_series, einvoice_issuance, einvoice_issuance_event,"
            + " einvoice_issuance_anchor TO "
            + ROLE);
    PostgresSupport.execute(
        "GRANT UPDATE ON einvoice_series, einvoice_issuance, einvoice_issuance_anchor TO " + ROLE);
    PostgresSupport.execute(
        "GRANT USAGE ON SEQUENCE einvoice_issuance_id_seq, einvoice_issuance_event_seq_seq TO "
            + ROLE);
    PostgresSupport.execute("GRANT CONNECT ON DATABASE test TO " + ROLE);
    runtime = PostgresSupport.dataSourceAs(ROLE, PASSWORD);
  }

  private static void asRuntime(String sql) throws SQLException {
    try (Connection c = runtime.getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  /**
   * Two layers, asserted separately.
   *
   * <p>As the <b>runtime role</b>, a statement may be refused by the grant before it ever reaches a
   * trigger - which is the outcome we want in production, and which is also exactly how a test can
   * report a trigger as working when the trigger is not there at all. So the same statement is run
   * again as the <b>owner</b>, where no grant stands in the way, and the trigger's own message is
   * what has to come back. A control that is only ever exercised behind another control is not a
   * control that has been tested.
   */
  private static void refusedByGrantAndByTrigger(String sql, String triggerMessage) {
    assertThatThrownBy(() -> asRuntime(sql)).isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> PostgresSupport.execute(sql)).hasMessageContaining(triggerMessage);
  }

  @Test
  void the_runtime_role_cannot_delete_or_truncate_an_issuance_row() {
    String seller = PostgresSupport.freshSeller("no-delete");
    PostgresSupport.store(seller, SIX, CLOCK, CHAIN).allocate(request(key(seller), "in_nd"));

    refusedByGrantAndByTrigger(
        "DELETE FROM einvoice_issuance WHERE seller_id = '" + seller + "'", "append-only");
    refusedByGrantAndByTrigger("TRUNCATE einvoice_issuance", "append-only");
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_issuance WHERE seller_id = '" + seller + "'"))
        .isEqualTo(1L);
  }

  @Test
  void the_runtime_role_cannot_rewrite_an_immutable_column() {
    String seller = PostgresSupport.freshSeller("immutable");
    PostgresSupport.store(seller, SIX, CLOCK, CHAIN).allocate(request(key(seller), "in_im"));

    assertThatThrownBy(
            () ->
                asRuntime(
                    "UPDATE einvoice_issuance SET legal_number = 'INV-2026-000999' WHERE"
                        + " seller_id = '"
                        + seller
                        + "'"))
        .hasMessageContaining("only state, document_sha256");
    assertThatThrownBy(
            () ->
                asRuntime(
                    "UPDATE einvoice_issuance SET issued_at = now() WHERE seller_id = '"
                        + seller
                        + "'"))
        .hasMessageContaining("only state, document_sha256");
  }

  @Test
  void the_document_hash_and_archive_key_are_write_once() {
    // N-06: the chain would detect a rewrite as BROKEN, but detection is not prevention when
    // prevention costs one clause.
    String seller = PostgresSupport.freshSeller("write-once");
    PostgresSupport.store(seller, SIX, CLOCK, CHAIN).allocate(request(key(seller), "in_wo"));
    PostgresSupport.execute(
        "UPDATE einvoice_issuance SET state = 'ARCHIVING', document_sha256 = repeat('a', 64),"
            + " archive_key = 'live/2026/INV-2026-000001.xml' WHERE seller_id = '"
            + seller
            + "'");

    assertThatThrownBy(
            () ->
                asRuntime(
                    "UPDATE einvoice_issuance SET document_sha256 = repeat('b', 64) WHERE"
                        + " seller_id = '"
                        + seller
                        + "'"))
        .hasMessageContaining("document_sha256 is write-once");
    assertThatThrownBy(
            () ->
                asRuntime(
                    "UPDATE einvoice_issuance SET archive_key = 'elsewhere.xml' WHERE seller_id ="
                        + " '"
                        + seller
                        + "'"))
        .hasMessageContaining("archive_key is write-once");
  }

  @Test
  void a_void_of_an_issued_number_is_refused_by_the_trigger() {
    // The same refusal as the enum's, in a second and independent place. A caller that reaches the
    // database directly does not get a different answer from one that goes through the service.
    String seller = PostgresSupport.freshSeller("trigger-void");
    PostgresSupport.store(seller, SIX, CLOCK, CHAIN).allocate(request(key(seller), "in_tv"));
    PostgresSupport.execute(
        "UPDATE einvoice_issuance SET state = 'ARCHIVING' WHERE seller_id = '" + seller + "'");
    PostgresSupport.execute(
        "UPDATE einvoice_issuance SET state = 'ISSUED' WHERE seller_id = '" + seller + "'");

    assertThatThrownBy(
            () ->
                asRuntime(
                    "UPDATE einvoice_issuance SET state = 'VOID_UNUSED' WHERE seller_id = '"
                        + seller
                        + "'"))
        .hasMessageContaining("is not a declared transition");
  }

  @Test
  void an_undeclared_state_transition_is_refused_by_the_trigger() {
    String seller = PostgresSupport.freshSeller("transition");
    PostgresSupport.store(seller, SIX, CLOCK, CHAIN).allocate(request(key(seller), "in_tr"));

    assertThatThrownBy(
            () ->
                asRuntime(
                    "UPDATE einvoice_issuance SET state = 'ISSUED' WHERE seller_id = '"
                        + seller
                        + "'"))
        .hasMessageContaining("NUMBERED -> ISSUED is not a declared transition");
  }

  /** An issuance row moved by the owner into {@code state}, with a hash and key past NUMBERED. */
  private static String rowIn(String hint, String state) {
    String seller = PostgresSupport.freshSeller(hint);
    PostgresSupport.store(seller, SIX, CLOCK, CHAIN).allocate(request(key(seller), "in_" + hint));
    if (!state.equals("NUMBERED")) {
      PostgresSupport.execute(
          "UPDATE einvoice_issuance SET state = 'ARCHIVING', document_sha256 = repeat('a', 64),"
              + " archive_key = 'live/2026/INV-2026-000001.xml' WHERE seller_id = '"
              + seller
              + "'");
    }
    if (state.equals("FAILED_ARCHIVE")) {
      PostgresSupport.execute(
          "UPDATE einvoice_issuance SET state = 'FAILED_ARCHIVE' WHERE seller_id = '"
              + seller
              + "'");
    }
    return seller;
  }

  @Test
  void a_resumed_row_can_be_burned_by_the_runtime_role() throws SQLException {
    // QUESTIONS 38: the two edges the enum gained, re-asserted at the database.
    for (String from : new String[] {"ARCHIVING", "FAILED_ARCHIVE"}) {
      String seller = rowIn("burn-" + from.toLowerCase(java.util.Locale.ROOT), from);
      asRuntime(
          "UPDATE einvoice_issuance SET state = 'FAILED_VALIDATION',"
              + " void_rule_id = 'EINVOICE-DATES' WHERE seller_id = '"
              + seller
              + "'");
      assertThat(
              PostgresSupport.scalar(
                  "SELECT count(*) FROM einvoice_issuance WHERE seller_id = '"
                      + seller
                      + "' AND state = 'FAILED_VALIDATION' AND void_rule_id = 'EINVOICE-DATES'"))
          .isEqualTo(1L);
      // Out of a burn only a void is legal, never back to an archive state.
      for (String back : new String[] {"FAILED_ARCHIVE", "ARCHIVING", "ISSUED"}) {
        assertThatThrownBy(
                () ->
                    asRuntime(
                        "UPDATE einvoice_issuance SET state = '"
                            + back
                            + "' WHERE seller_id = '"
                            + seller
                            + "'"))
            .hasMessageContaining("is not a declared transition");
      }
    }
  }

  @Test
  void an_issued_number_cannot_be_burned() {
    String seller = rowIn("burn-issued", "ARCHIVING");
    PostgresSupport.execute(
        "UPDATE einvoice_issuance SET state = 'ISSUED' WHERE seller_id = '" + seller + "'");

    assertThatThrownBy(
            () ->
                asRuntime(
                    "UPDATE einvoice_issuance SET state = 'FAILED_VALIDATION', void_rule_id = 'R'"
                        + " WHERE seller_id = '"
                        + seller
                        + "'"))
        .hasMessageContaining("ISSUED -> FAILED_VALIDATION is not a declared transition");
  }

  @Test
  void a_burn_writes_only_the_rule_id() {
    // D-RTR-2: a burn that also plants a reason, a hash or a key is refused, from each of the three
    // states a burn may leave. A planted key would also hide an orphan object from the sweep.
    for (String from : new String[] {"NUMBERED", "ARCHIVING", "FAILED_ARCHIVE"}) {
      for (String extra :
          new String[] {
            "void_reason = 'planted'",
            "document_sha256 = repeat('b', 64)",
            "archive_key = 'live/elsewhere.xml'"
          }) {
        String seller = rowIn("only-rule-" + from.toLowerCase(java.util.Locale.ROOT), from);
        String sql =
            "UPDATE einvoice_issuance SET state = 'FAILED_VALIDATION', void_rule_id = 'R', "
                + extra
                + " WHERE seller_id = '"
                + seller
                + "'";
        assertThatThrownBy(() -> asRuntime(sql))
            .as(from + " " + extra)
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> PostgresSupport.execute(sql))
            .as(from + " " + extra)
            .hasMessageContaining("a burn records its rule id and nothing else");
      }
    }
  }

  @Test
  void a_void_reason_changes_only_with_a_void() {
    String seller = rowIn("reason-void", "FAILED_ARCHIVE");

    PostgresSupport.execute(
        "UPDATE einvoice_issuance SET state = 'VOID_UNUSED', void_reason = 'archive lost',"
            + " void_rule_id = 'OPS-1' WHERE seller_id = '"
            + seller
            + "'");

    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_issuance WHERE seller_id = '"
                    + seller
                    + "' AND void_reason = 'archive lost'"))
        .isEqualTo(1L);
  }

  @Test
  void the_series_counter_refuses_a_non_monotonic_update() {
    String seller = PostgresSupport.freshSeller("monotonic");
    PostgresSupport.store(seller, SIX, CLOCK, CHAIN).allocate(request(key(seller), "in_mono"));

    assertThatThrownBy(
            () ->
                asRuntime(
                    "UPDATE einvoice_series SET next_number = 1 WHERE seller_id = '"
                        + seller
                        + "'"))
        .hasMessageContaining("only advances by one");
    assertThatThrownBy(
            () ->
                asRuntime(
                    "UPDATE einvoice_series SET next_number = next_number + 5 WHERE seller_id = '"
                        + seller
                        + "'"))
        .hasMessageContaining("only advances by one");
    assertThatThrownBy(
            () ->
                asRuntime(
                    "UPDATE einvoice_series SET prefix = 'OTHER-' WHERE seller_id = '"
                        + seller
                        + "'"))
        .hasMessageContaining("prefix and width are immutable");
  }

  @Test
  void the_issuance_log_refuses_an_update_or_a_delete() {
    String seller = PostgresSupport.freshSeller("log");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_log"));
    store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_log", "wrong customer", ""));

    refusedByGrantAndByTrigger(
        "UPDATE einvoice_issuance_event SET void_reason = 'something else' WHERE seller_id = '"
            + seller
            + "'",
        "append-only");
    refusedByGrantAndByTrigger(
        "DELETE FROM einvoice_issuance_event WHERE seller_id = '" + seller + "'", "append-only");
  }

  @Test
  void the_anchor_refuses_a_rewind_a_delete_and_a_mode_change() {
    String seller = PostgresSupport.freshSeller("anchor");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_anchor"));
    store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_anchor", "test setup", ""));

    refusedByGrantAndByTrigger(
        "UPDATE einvoice_issuance_anchor SET row_count = 0 WHERE id = 1",
        "only advances by one row");
    refusedByGrantAndByTrigger(
        "UPDATE einvoice_issuance_anchor SET keyed = NOT keyed WHERE id = 1", "keyed is immutable");
    refusedByGrantAndByTrigger("DELETE FROM einvoice_issuance_anchor WHERE id = 1", "append-only");
  }
}
