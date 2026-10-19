package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Security review, PR 40 pass 2: the name insert with no conflict target (D-C03-B6).
 *
 * <p>With no target, every unique and exclusion index of {@code einvoice_series_name} is an
 * arbiter. The insert then does nothing on any of them, and the read after it looks only at the
 * primary key. These probes check what happens at each isolation level and when an index the
 * primary key does not imply is present.
 */
class CipherProbePr40Pass2Test {

  private static final SeriesDefinition RACE = new SeriesDefinition("R-{fiscalYear}-", 6, true);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-20T10:00:00Z"), ZoneOffset.UTC);
  private static final Duration WAIT = Duration.ofSeconds(10);

  private static JdbcIssuanceStore store(DataSource ds, String seller, Mode mode) {
    return TestStores.store(
        ds,
        DocumentSeriesFixture.CHAIN,
        Map.of(
            new JdbcIssuanceStore.SeriesId(
                seller, "RACE", mode, DocumentTypeCode.COMMERCIAL_INVOICE),
            RACE),
        CLOCK);
  }

  /**
   * D-PR40-4. Example: an operator adds a unique index on (seller_id, series) to make a name unique
   * across modes. The seller already used RACE in test mode. Opening RACE in live mode then does
   * nothing on that index, the read by primary key finds no live row, and the allocator says "being
   * created by a concurrent transaction, retry". That is a false cause and a retry that can never
   * succeed. The branch is unreachable with the shipped schema (rows are append-only and RR/SER
   * raise 40001), so reaching it means a schema defect, not a race.
   */
  @Test
  void probe_a_foreign_unique_index_turns_the_name_insert_into_a_false_retry() {
    String seller = PostgresSupport.freshSeller("p40b4");
    HikariDataSource ds = (HikariDataSource) PostgresSupport.freshDatabase("p40b4");
    try {
      PostgresSupport.executeOn(
          ds, "CREATE UNIQUE INDEX probe_mode_blind ON einvoice_series_name (seller_id, series)");
      store(ds, seller, Mode.TEST).openSeries(new SeriesKey(seller, "RACE", 2026, Mode.TEST));
      Throwable live =
          catchThrowable(
              () ->
                  store(ds, seller, Mode.LIVE)
                      .openSeries(new SeriesKey(seller, "RACE", 2026, Mode.LIVE)));
      assertThat(live).isInstanceOf(EInvoiceException.class);
      assertThat(((EInvoiceException) live).code())
          .as("a permanent schema condition must not be reported as a retryable concurrent race")
          .isNotEqualTo(ErrorCodes.ALLOCATION_TIMEOUT);
      assertThat(live.getMessage()).doesNotContain("concurrent");
    } finally {
      ds.close();
    }
  }

  /**
   * Confirmation, not a finding: the read-after-insert window at REPEATABLE READ and SERIALIZABLE.
   * Writer A takes its snapshot (the first read finds no name), is held inside its insert, writer B
   * commits the same name, then A goes on. A's snapshot cannot see B's row, so PostgreSQL does not
   * do nothing: it raises 40001, mapped to DEI-117. A never reaches the empty second read.
   */
  @ParameterizedTest
  @ValueSource(strings = {"repeatable read", "serializable"})
  void confirm_a_snapshot_older_than_the_winner_gets_40001_not_an_empty_read(String level)
      throws Exception {
    String seller = PostgresSupport.freshSeller("p40brr");
    try (HikariDataSource ds = pausable("p40brr");
        HikariDataSource held = paused(ds, level);
        Connection gate = ds.getConnection()) {
      exec(gate, "SELECT pg_advisory_lock(424303)");
      CompletableFuture<Throwable> a =
          CompletableFuture.supplyAsync(
              () ->
                  catchThrowable(
                      () ->
                          store(held, seller, Mode.LIVE)
                              .openSeries(new SeriesKey(seller, "RACE", 2026, Mode.LIVE))));
      assertThat(waitsOn(ds, pid(gate))).as("writer A is held inside its insert").isTrue();
      store(ds, seller, Mode.LIVE).openSeries(new SeriesKey(seller, "RACE", 2027, Mode.LIVE));
      exec(gate, "SELECT pg_advisory_unlock(424303)");
      Throwable outcome = a.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      assertThat(outcome).isInstanceOf(EInvoiceException.class);
      assertThat(((EInvoiceException) outcome).code()).isEqualTo(ErrorCodes.ALLOCATION_TIMEOUT);
      assertThat(sqlState(outcome)).isEqualTo("40001");
      assertThat(
              PostgresSupport.scalarOn(
                  ds, "SELECT count(*) FROM einvoice_series WHERE seller_id = '" + seller + "'"))
          .isEqualTo(1);
    }
  }

  // ------------------------------------------------------------------ helpers (as the race test)

  private static HikariDataSource pausable(String hint) {
    HikariDataSource ds = (HikariDataSource) PostgresSupport.freshDatabase(hint);
    for (String sql :
        new String[] {
          "CREATE FUNCTION c03b_pause(text) RETURNS text LANGUAGE plpgsql IMMUTABLE AS $$ BEGIN"
              + " IF current_setting('c03b.pause', true) = 'on' THEN"
              + " PERFORM pg_advisory_xact_lock(424303); END IF; RETURN $1; END $$",
          "ALTER TABLE einvoice_series DROP CONSTRAINT einvoice_series_name_fk",
          "ALTER TABLE einvoice_series_name DROP CONSTRAINT einvoice_series_name_type_key",
          "ALTER TABLE einvoice_series_name DROP CONSTRAINT einvoice_series_name_pkey",
          "CREATE INDEX c03b_pause_first ON einvoice_series_name ((c03b_pause(series)))",
          "ALTER TABLE einvoice_series_name ADD CONSTRAINT einvoice_series_name_pkey"
              + " PRIMARY KEY (seller_id, mode, series)",
          "ALTER TABLE einvoice_series_name ADD CONSTRAINT einvoice_series_name_type_key"
              + " UNIQUE (seller_id, mode, series, document_type)",
          "ALTER TABLE einvoice_series ADD CONSTRAINT einvoice_series_name_fk FOREIGN KEY"
              + " (seller_id, mode, series, document_type) REFERENCES einvoice_series_name"
              + " (seller_id, mode, series, document_type)"
        }) {
      PostgresSupport.executeOn(ds, sql);
    }
    return ds;
  }

  private static HikariDataSource paused(HikariDataSource ds, String level) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(ds.getJdbcUrl());
    config.setUsername(ds.getUsername());
    config.setPassword(ds.getPassword());
    config.setMaximumPoolSize(2);
    config.setConnectionInitSql(
        "SET c03b.pause = 'on'; SET default_transaction_isolation = '" + level + "'");
    return new HikariDataSource(config);
  }

  private static int pid(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static boolean waitsOn(DataSource on, int blocker) throws Exception {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      try (Connection c = on.getConnection();
          PreparedStatement ps =
              c.prepareStatement(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                      + " AND pg_blocking_pids(pid) @> ARRAY[?]::int[]")) {
        ps.setInt(1, blocker);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (rs.getLong(1) > 0) {
            return true;
          }
        }
      }
      Thread.sleep(20);
    }
    return false;
  }

  private static void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static String sqlState(Throwable t) {
    Throwable cur = t;
    while (cur != null) {
      if (cur instanceof SQLException se) {
        return se.getSQLState();
      }
      cur = cur.getCause();
    }
    return "none";
  }
}
