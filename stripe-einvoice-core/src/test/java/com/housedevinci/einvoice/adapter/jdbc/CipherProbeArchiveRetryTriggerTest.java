package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The security spec review's database probes for the archive retry refusal design (2026-10-19):
 * probe 6 (a burn writes only the rule id) and probe 9 (the upgraded guard keeps its search path),
 * plus the upgrade path S12 that probe 9 rides on.
 */
class CipherProbeArchiveRetryTriggerTest {

  private static final Instant JAN_15 = Instant.parse("2026-01-15T10:00:00Z");

  /**
   * Probe 6 (D-RTR-2): from NUMBERED nothing is write-once yet, so only the new clause stops it.
   */
  @Test
  void probe_a_burn_writes_only_the_rule_id() {
    for (String extra :
        new String[] {
          "void_reason = 'planted'", "document_sha256 = repeat('a', 64)", "archive_key = 'k'"
        }) {
      String seller = PostgresSupport.freshSeller("probe6");
      PostgresSupport.store(seller, SIX, CLOCK, CHAIN).allocate(request(key(seller), "in_p6"));

      assertThatThrownBy(
              () ->
                  PostgresSupport.execute(
                      "UPDATE einvoice_issuance SET state = 'FAILED_VALIDATION',"
                          + " void_rule_id = 'R', "
                          + extra
                          + " WHERE seller_id = '"
                          + seller
                          + "'"))
          .as(extra)
          .hasMessageContaining("a burn records its rule id and nothing else");
    }
  }

  /** A 0.1.0 database with one number left in FAILED_ARCHIVE after a timed-out PUT. */
  private static HikariDataSource releasedWithFailedArchive(String hint) throws SQLException {
    HikariDataSource ds = PostgresSupport.emptyDatabase(hint);
    Format1.installSchema(ds);
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      Format1.number(c, "seller-retry", "DEFAULT", 2026, "in_up", JAN_15);
      c.commit();
    }
    PostgresSupport.executeOn(
        ds,
        "UPDATE einvoice_issuance SET state = 'ARCHIVING', document_sha256 = repeat('c', 64),"
            + " archive_key = 'live/2026/INV-2026-000001.xml'");
    PostgresSupport.executeOn(ds, "UPDATE einvoice_issuance SET state = 'FAILED_ARCHIVE'");
    return ds;
  }

  /** S12: the new edge exists after the upgrade, not only on a fresh install. */
  @Test
  void an_upgraded_0_1_0_database_accepts_the_new_edge() throws SQLException {
    HikariDataSource ds = releasedWithFailedArchive("retryedge");
    try {
      assertThatThrownBy(
              () ->
                  PostgresSupport.executeOn(
                      ds,
                      "UPDATE einvoice_issuance SET state = 'FAILED_VALIDATION',"
                          + " void_rule_id = 'EINVOICE-DATES'"))
          .as("0.1.0 refuses the edge")
          .hasMessageContaining("not a declared transition");

      JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of("Europe/Paris")));

      PostgresSupport.executeOn(
          ds,
          "UPDATE einvoice_issuance SET state = 'FAILED_VALIDATION',"
              + " void_rule_id = 'EINVOICE-DATES'");
      assertThat(
              PostgresSupport.scalarOn(
                  ds,
                  "SELECT count(*) FROM einvoice_issuance WHERE state = 'FAILED_VALIDATION'"
                      + " AND document_sha256 = repeat('c', 64)"))
          .isEqualTo(1L);
    } finally {
      PostgresSupport.close(ds);
    }
  }

  /** Probe 9: CREATE OR REPLACE keeps the pin only if the new body still declares it. */
  @Test
  void probe_the_upgraded_guard_keeps_its_search_path() throws SQLException {
    HikariDataSource ds = releasedWithFailedArchive("retrypin");
    try {
      JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of("Europe/Paris")));

      assertThat(
              PostgresSupport.scalarOn(
                  ds,
                  "SELECT count(*) FROM pg_proc WHERE proname = 'einvoice_issuance_guard'"
                      + " AND proconfig @> ARRAY['search_path=pg_catalog, pg_temp']::text[]"))
          .isEqualTo(1L);
    } finally {
      PostgresSupport.close(ds);
    }
  }
}
