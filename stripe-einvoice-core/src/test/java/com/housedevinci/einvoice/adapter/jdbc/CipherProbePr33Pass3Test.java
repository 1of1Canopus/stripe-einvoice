package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.DocumentSeriesFixture.MARCH_10;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Security review of PR 33, pass 3: the D-A1-C08 search-path pin and its check, and the D-A1-C09
 * zone refusal. Each test states the property; a red test is a finding.
 */
class CipherProbePr33Pass3Test {

  private static final String PIN = " SET search_path = pg_catalog, pg_temp;";
  private final List<DataSource> opened = new ArrayList<>();

  @AfterEach
  void closePools() {
    opened.forEach(PostgresSupport::close);
    opened.clear();
  }

  private DataSource opened(DataSource ds) {
    opened.add(ds);
    return ds;
  }

  private static String script() {
    try (var in =
        JdbcSupport.class.getResourceAsStream("/com/housedevinci/einvoice/schema-postgresql.sql")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Runs the script on one connection; returns every SQLSTATE raised, in order. */
  private static List<String> run(Connection c, String script) throws SQLException {
    List<String> states = new ArrayList<>();
    c.setAutoCommit(true);
    for (String sql : CipherProbePr33Test.split(script)) {
      try (Statement st = c.createStatement()) {
        st.execute(sql);
      } catch (SQLException refused) {
        states.add(refused.getSQLState());
      }
    }
    return states;
  }

  private static String seriesInsert(String schema, String seller, String type, int year) {
    return "INSERT INTO "
        + schema
        + ".einvoice_series (seller_id, document_type, series, fiscal_year, mode, prefix, width,"
        + " next_number, updated_at) VALUES ('"
        + seller
        + "', '"
        + type
        + "', 'INV', "
        + year
        + ", 'live', 'X"
        + year
        + "-', 6, 1, now())";
  }

  // ------------------------------------------------------------ C08: the check reads the exact pin

  /** A pin to another path, with public first, does not satisfy the final block. */
  @Test
  void probe_a_guard_pinned_to_public_first_still_fails_the_pin_check() throws SQLException {
    String other =
        script()
            .replaceFirst(
                "LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp;",
                "LANGUAGE plpgsql SET search_path = public, pg_catalog;");
    assertThat(other).isNotEqualTo(script());
    DataSource ds = opened(PostgresSupport.emptyDatabase("pr33p3other"));
    List<String> states;
    try (Connection c = ds.getConnection()) {
      states = run(c, other);
    }
    assertThat(states).contains("EIU03");
  }

  // ------------------------------------------------------------ C08: the check follows the trigger

  /**
   * The pin check reads the seven functions by name in the module schema. The property is about the
   * functions the triggers call. A guard trigger rebound to an unpinned function elsewhere must not
   * let the script finish as format 2.
   */
  @Test
  void probe_a_guard_trigger_bound_to_an_unpinned_function_fails_the_final_block()
      throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("pr33p3bind"));
    List<String> states;
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("CREATE SCHEMA elsewhere");
      st.execute(
          "CREATE FUNCTION elsewhere.einvoice_series_insert_guard() RETURNS trigger"
              + " LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$");
      st.execute("DROP TRIGGER einvoice_series_insert_guard ON einvoice_series");
      st.execute(
          "CREATE TRIGGER einvoice_series_insert_guard BEFORE INSERT ON einvoice_series"
              + " FOR EACH ROW EXECUTE FUNCTION elsewhere.einvoice_series_insert_guard()");
      states = run(c, script());
    }
    assertThat(states).contains("EIU03");
  }

  /** A guard trigger that exists but is disabled must not pass the final block either. */
  @Test
  void probe_a_disabled_guard_trigger_fails_the_final_block() throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("pr33p3off"));
    List<String> states;
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("ALTER TABLE einvoice_series DISABLE TRIGGER einvoice_series_insert_guard");
      states = run(c, script());
    }
    assertThat(states).contains("EIU03");
  }

  // ------------------------------------------------------------ C08: pg_temp never wins

  /** An exact-match operator in the writer's own pg_temp does not disarm the pinned guard. */
  @Test
  void probe_an_operator_planted_in_pg_temp_cannot_disarm_the_series_name_binding()
      throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("pr33p3tmp"));
    DocumentSeriesFixture f =
        new DocumentSeriesFixture(ds, PostgresSupport.freshSeller("pr33p3tmp"));
    f.invoice("in_first", MARCH_10);
    DataSource runtime =
        opened(SchemaGrants.roleWithDocumentedGrants(ds, "einvoice_pr33p3tmp", "probe"));

    String state = null;
    try (Connection c = runtime.getConnection();
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE FUNCTION pg_temp.never_differs(varchar, varchar) RETURNS boolean"
              + " LANGUAGE sql IMMUTABLE AS 'SELECT false'");
      st.execute(
          "CREATE OPERATOR pg_temp.<> (LEFTARG = varchar, RIGHTARG = varchar,"
              + " FUNCTION = pg_temp.never_differs)");
      st.execute("SET search_path = pg_temp, public");
      try {
        st.execute(seriesInsert("public", f.seller, "381", 2027));
      } catch (SQLException refused) {
        state = refused.getSQLState();
      }
    }
    assertThat(state).isEqualTo("EIS01");
  }

  // ------------------------------------------------------------ C08: a schema other than public

  /**
   * Installed in schema acct and written from a session whose path does not hold acct, the pinned
   * guards still find their tables and still refuse.
   */
  @Test
  void probe_the_pinned_guards_hold_when_the_module_schema_is_not_public() throws SQLException {
    DataSource ds = opened(PostgresSupport.emptyDatabase("pr33p3acct"));
    List<String> states;
    String refusal = null;
    long rows;
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("CREATE SCHEMA acct");
      st.execute("SET search_path = acct");
      states = run(c, script());
      st.execute("SET search_path = public");
      st.execute(seriesInsert("acct", "seller-acct", "380", 2026));
      try {
        st.execute(seriesInsert("acct", "seller-acct", "381", 2027));
      } catch (SQLException refused) {
        refusal = refused.getSQLState();
      }
      try (var rs =
          st.executeQuery(
              "SELECT (SELECT count(*) FROM acct.einvoice_series)"
                  + " + (SELECT count(*) FROM pg_tables WHERE schemaname = 'public'"
                  + " AND tablename LIKE 'einvoice%')")) {
        rs.next();
        rows = rs.getLong(1);
      }
    }
    assertThat(states).isEmpty();
    assertThat(refusal).isEqualTo("EIS01");
    assertThat(rows).isEqualTo(1);
  }

  // ------------------------------------------------------------ C09: the zone refusal

  private List<String> upgradeWithZone(String hint, String zone) throws SQLException {
    DataSource ds = opened(PostgresSupport.emptyDatabase(hint));
    Format1.installSchema(ds);
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      Format1.number(
          c,
          "seller-" + hint,
          "DEFAULT",
          2026,
          "in_" + hint,
          Instant.parse("2026-03-10T10:00:00Z"));
      c.commit();
      try (var ps = c.prepareStatement("SELECT set_config('einvoice.tax_zone', ?, false)")) {
        ps.setString(1, zone);
        ps.execute();
      }
      return run(c, script());
    }
  }

  @Test
  void probe_region_ids_and_utc_are_accepted_by_the_backfill() throws SQLException {
    assertThat(upgradeWithZone("pr33p3z1", "Etc/UTC")).isEmpty();
    assertThat(upgradeWithZone("pr33p3z2", "Europe/Paris")).isEmpty();
    assertThat(upgradeWithZone("pr33p3z3", "UTC")).isEmpty();
    assertThat(upgradeWithZone("pr33p3z4", "Etc/GMT-2")).isEmpty();
  }

  @Test
  void probe_legacy_and_posix_zone_names_are_refused_by_the_backfill() throws SQLException {
    for (String zone :
        List.of("CET", "EST5EDT", "GMT+1", "GMT0", "Europe/../CET", "Nowhere/Else", "+02:00")) {
      assertThat(upgradeWithZone("pr33p3zr" + Math.abs(zone.hashCode() % 1000), zone))
          .as(zone)
          .contains("EIU02");
    }
  }
}
