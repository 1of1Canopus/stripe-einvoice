package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.DocumentSeriesFixture.MARCH_10;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * D-A1-C03, the paths other than the race itself (series-name design, section 6, tests 3, 4, 5 and
 * 7): the start-up seed, a raw insert by the documented runtime role, the upgrade of a populated
 * 0.1.x database, the refusal of a database that already binds one name to two types, and the final
 * schema block.
 *
 * <p>Example of what is now refused: the runtime role inserts a 2027 credit-note row for series
 * {@code INV}, whose name row says invoice. The foreign key refuses it ({@code 23503}).
 */
class SeriesNameBindingTest {

  private static final String ZONE = "Europe/Paris";
  private static final String SELLER = "seller-c03";
  private final List<DataSource> opened = new ArrayList<>();

  @AfterEach
  void closePools() {
    opened.forEach(PostgresSupport::close);
    opened.clear();
  }

  private <T extends DataSource> T opened(T ds) {
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

  /** A refusal as psql shows it: the SQLSTATE and the message. */
  private record Refusal(String state, String message) {}

  /** Runs a script the way psql -f does by default: statement by statement, going on on error. */
  private static List<Refusal> runLikePsql(Connection c, String prelude, String script)
      throws SQLException {
    List<Refusal> refusals = new ArrayList<>();
    c.setAutoCommit(true);
    List<String> statements = new ArrayList<>();
    if (!prelude.isEmpty()) {
      statements.add(prelude);
    }
    statements.addAll(CipherProbePr33Test.split(script));
    for (String sql : statements) {
      try (Statement st = c.createStatement()) {
        st.execute(sql);
      } catch (SQLException refused) {
        refusals.add(new Refusal(refused.getSQLState(), String.valueOf(refused.getMessage())));
      }
    }
    return refusals;
  }

  private static void exec(DataSource ds, String sql) {
    PostgresSupport.executeOn(ds, sql);
  }

  private static String stateOf(DataSource ds, String sql) {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
      return "accepted";
    } catch (SQLException refused) {
      return refused.getSQLState();
    }
  }

  private static String yearRow(String seller, String series, String type, int year) {
    return "INSERT INTO einvoice_series (seller_id, document_type, series, fiscal_year, mode,"
        + " prefix, width, next_number, updated_at) VALUES ('"
        + seller
        + "', '"
        + type
        + "', '"
        + series
        + "', "
        + year
        + ", 'live', 'X"
        + year
        + "-', 6, 1, now())";
  }

  private static String nameRow(String seller, String series, String type) {
    return "INSERT INTO einvoice_series_name (seller_id, mode, series, document_type, created_at)"
        + " VALUES ('"
        + seller
        + "', 'live', '"
        + series
        + "', '"
        + type
        + "', now())";
  }

  private static List<String> rows(DataSource ds, String sql) {
    List<String> out = new ArrayList<>();
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      int columns = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        StringBuilder row = new StringBuilder();
        for (int i = 1; i <= columns; i++) {
          row.append(i > 1 ? "|" : "").append(rs.getString(i));
        }
        out.add(row.toString());
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  /** A released 0.1.0 database with numbered rows written by the 0.1.0 statements. */
  private DataSource released(String hint, String... seriesYearId) {
    DataSource ds = opened(PostgresSupport.emptyDatabase(hint));
    Format1.installSchema(ds);
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      for (int i = 0; i < seriesYearId.length; i += 3) {
        Format1.number(
            c,
            SELLER,
            seriesYearId[i],
            Integer.parseInt(seriesYearId[i + 1]),
            seriesYearId[i + 2],
            Instant.parse("2026-01-20T10:00:00Z"));
      }
      c.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return ds;
  }

  /**
   * A database in the shape an unreleased build wrote: typed series rows with no name table, and
   * series {@code RACE} numbering invoices in 2025 and credit notes in 2027. (Format 1 renders the
   * year into the prefix, so the years keep the legal numbers distinct.)
   */
  private DataSource splitByAnUnreleasedBuild(String hint) {
    DataSource ds = released(hint, "INV", "2026", "in_1", "RACE", "2025", "in_2");
    exec(
        ds,
        "ALTER TABLE einvoice_series ADD COLUMN document_type varchar(4) NOT NULL DEFAULT '380'");
    exec(ds, yearRow(SELLER, "RACE", "381", 2027));
    return ds;
  }

  // ------------------------------------------------------------------ test 3: the start-up seed
  // (P2)

  /**
   * Test 3: the start-up seed of a name whose name row has the other type refuses with {@code
   * CONFIG}, naming the series, and opens no year row. Example: the configuration says {@code INV}
   * numbers invoices, the database already binds {@code INV} to credit notes.
   */
  @Test
  void the_start_up_seed_refuses_a_name_bound_to_the_other_type() {
    String seller = PostgresSupport.freshSeller("c03seed");
    PostgresSupport.execute(nameRow(seller, "INV", "381"));
    JdbcIssuanceStore store =
        TestStores.store(
            PostgresSupport.dataSource(),
            DocumentSeriesFixture.CHAIN,
            Map.of(
                new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE),
                DocumentSeriesFixture.INVOICES),
            DocumentSeriesFixture.CLOCK);

    Throwable seed =
        catchThrowable(() -> store.openSeries(new SeriesKey(seller, "INV", 2026, Mode.LIVE)));

    assertThat(seed).isInstanceOf(EInvoiceException.class);
    assertThat(((EInvoiceException) seed).code()).isEqualTo(ErrorCodes.CONFIG);
    assertThat(seed.getMessage()).contains("INV").contains("another document type");
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_series WHERE seller_id = '" + seller + "'"))
        .isZero();
  }

  /** The seed of a name with no row yet writes the name row with the configured type. */
  @Test
  void the_start_up_seed_binds_a_new_name_to_its_configured_type() {
    String seller = PostgresSupport.freshSeller("c03seednew");
    JdbcIssuanceStore store =
        TestStores.store(
            PostgresSupport.dataSource(),
            DocumentSeriesFixture.CHAIN,
            Map.of(
                new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE),
                DocumentSeriesFixture.INVOICES),
            DocumentSeriesFixture.CLOCK);

    store.openSeries(new SeriesKey(seller, "INV", 2026, Mode.LIVE));
    store.openSeries(new SeriesKey(seller, "INV", 2027, Mode.LIVE));

    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_series_name WHERE seller_id = '"
                    + seller
                    + "' AND series = 'INV' AND document_type = '380'"))
        .isEqualTo(1);
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_series WHERE seller_id = '" + seller + "'"))
        .isEqualTo(2);
  }

  // ------------------------------------------------------------------ test 4: raw insert (P3)

  /**
   * Test 4: the documented runtime role, writing by hand. A year row with no name row, or with the
   * other type, fails on the foreign key; a second name row fails on the primary key; UPDATE,
   * DELETE and TRUNCATE of a name row are not granted, and the guard triggers refuse them for a
   * role that holds the privilege.
   */
  @Test
  void the_runtime_role_cannot_bind_a_name_to_a_second_type_by_hand() {
    DataSource ds = opened(PostgresSupport.freshDatabase("c03role"));
    DocumentSeriesFixture f = new DocumentSeriesFixture(ds, PostgresSupport.freshSeller("c03role"));
    f.invoice("in_first", MARCH_10);
    DataSource runtime =
        opened(SchemaGrants.roleWithDocumentedGrants(ds, "einvoice_c03role", "probe"));

    assertThat(stateOf(runtime, yearRow(f.seller, "NONAME", "380", 2026)))
        .as("a year row with no name row")
        .isEqualTo("23503");
    assertThat(stateOf(runtime, yearRow(f.seller, "INV", "381", 2027)))
        .as("a year row of the other type")
        .isEqualTo("23503");
    assertThat(stateOf(runtime, nameRow(f.seller, "INV", "381")))
        .as("a second name row")
        .isEqualTo("23505");
    String key = " WHERE seller_id = '" + f.seller + "' AND series = 'INV'";
    assertThat(stateOf(runtime, "UPDATE einvoice_series_name SET document_type = '381'" + key))
        .isEqualTo("42501");
    assertThat(stateOf(runtime, "DELETE FROM einvoice_series_name" + key)).isEqualTo("42501");
    assertThat(stateOf(runtime, "TRUNCATE einvoice_series_name")).isEqualTo("42501");

    // the guards, for a role that holds every privilege (the owner)
    assertThat(stateOf(ds, "UPDATE einvoice_series_name SET document_type = '381'" + key))
        .isEqualTo("P0001");
    assertThat(stateOf(ds, "DELETE FROM einvoice_series_name" + key)).isEqualTo("P0001");
    assertThat(stateOf(ds, "TRUNCATE einvoice_series_name")).isEqualTo("P0001");

    // a new name is the runtime role's to create, with a matching year row
    assertThat(stateOf(runtime, nameRow(f.seller, "NEW", "381"))).isEqualTo("accepted");
    assertThat(stateOf(runtime, yearRow(f.seller, "NEW", "381", 2026))).isEqualTo("accepted");

    assertThat(
            f.scalar(
                "SELECT count(DISTINCT document_type) FROM einvoice_series WHERE seller_id = '"
                    + f.seller
                    + "' AND series = 'INV'"))
        .isEqualTo(1);
  }

  /**
   * R5: the foreign key survives the module's own trigger switches. An owner who runs {@code
   * DISABLE TRIGGER USER} still cannot add a year row of the other type.
   */
  @Test
  void the_foreign_key_holds_with_every_user_trigger_disabled() throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("c03user"));
    DocumentSeriesFixture f = new DocumentSeriesFixture(ds, PostgresSupport.freshSeller("c03user"));
    f.invoice("in_first", MARCH_10);
    String state;
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      st.execute("ALTER TABLE einvoice_series DISABLE TRIGGER USER");
      st.execute("ALTER TABLE einvoice_series_name DISABLE TRIGGER USER");
      try {
        st.execute(yearRow(f.seller, "INV", "381", 2027));
        state = "accepted";
      } catch (SQLException refused) {
        state = refused.getSQLState();
      }
      c.rollback();
    }
    assertThat(state).isEqualTo("23503");
  }

  // ------------------------------------------------------------------ test 5: the upgrade

  /**
   * Test 5: a populated 0.1.x database gets one name row per name, every one {@code 380}, and the
   * foreign key; a second run of the script changes nothing.
   */
  @Test
  void a_0_1_x_database_gets_one_name_row_per_name_and_a_second_run_changes_nothing()
      throws SQLException {
    DataSource ds =
        released("c03up", "INV", "2026", "in_1", "INV", "2027", "in_2", "OTHER", "2025", "in_3");

    JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of(ZONE)));

    assertThat(
            rows(
                ds,
                "SELECT seller_id, mode, series, document_type FROM einvoice_series_name"
                    + " ORDER BY series"))
        .containsExactly(SELLER + "|live|INV|380", SELLER + "|live|OTHER|380");
    assertThat(
            PostgresSupport.scalarOn(
                ds,
                "SELECT count(*) FROM pg_constraint WHERE conname = 'einvoice_series_name_fk'"
                    + " AND contype = 'f' AND convalidated"))
        .isEqualTo(1);

    String snapshot =
        "SELECT conrelid::regclass::text, conname, pg_get_constraintdef(oid) FROM pg_constraint"
            + " WHERE connamespace = 'public'::regnamespace ORDER BY 1, 2";
    String triggers =
        "SELECT tgrelid::regclass::text, tgname, tgenabled FROM pg_trigger"
            + " WHERE tgrelid::regclass::text LIKE 'einvoice%' ORDER BY 1, 2";
    List<String> constraintsBefore = rows(ds, snapshot);
    List<String> triggersBefore = rows(ds, triggers);
    List<String> namesBefore =
        rows(ds, "SELECT series, created_at FROM einvoice_series_name ORDER BY 1");
    List<Refusal> rerun;
    try (Connection c = ds.getConnection()) {
      rerun = runLikePsql(c, "SET einvoice.tax_zone = '" + ZONE + "'", script());
    }
    assertThat(rerun).isEmpty();
    assertThat(rows(ds, snapshot)).isEqualTo(constraintsBefore);
    assertThat(rows(ds, triggers)).isEqualTo(triggersBefore);
    assertThat(rows(ds, "SELECT series, created_at FROM einvoice_series_name ORDER BY 1"))
        .isEqualTo(namesBefore);
  }

  /**
   * Test 5: a database that already binds one name to two types refuses the upgrade with {@code
   * DEI-134} / {@code EIU04}, a count and the remedy, and changes nothing.
   */
  @Test
  void a_database_binding_one_name_to_two_types_refuses_the_upgrade_and_changes_nothing() {
    DataSource ds = splitByAnUnreleasedBuild("c03split");

    Throwable upgrade =
        catchThrowable(() -> JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of(ZONE))));

    assertThat(upgrade).isInstanceOf(EInvoiceException.class);
    assertThat(((EInvoiceException) upgrade).code()).isEqualTo(ErrorCodes.SCHEMA_UPGRADE_REFUSED);
    assertThat(upgrade.getMessage())
        .contains("EIU04")
        .contains("1 series name")
        .contains("unreleased build")
        .contains("rebuild it, or restore its 0.1.x copy")
        .doesNotContain("RACE")
        .doesNotContain(SELLER);
    assertThat(
            PostgresSupport.scalarOn(
                ds, "SELECT count(*) FROM pg_class WHERE relname = 'einvoice_series_name'"))
        .isZero();
    assertThat(
            PostgresSupport.scalarOn(
                ds, "SELECT count(*) FROM pg_class WHERE relname = 'einvoice_schema'"))
        .isZero();
  }

  /**
   * Test 5, R3: the same database run by hand past the error, statement by statement, gets no name
   * row for the two-type name, no foreign key, and no format-2 stamp; the starter still refuses it.
   */
  @Test
  void a_by_hand_run_past_the_refusal_binds_no_type_and_is_not_stamped() throws SQLException {
    DataSource ds = splitByAnUnreleasedBuild("c03hand");

    List<Refusal> refusals;
    try (Connection c = ds.getConnection()) {
      refusals = runLikePsql(c, "SET einvoice.tax_zone = '" + ZONE + "'", script());
    }

    assertThat(refusals).extracting(Refusal::state).contains("EIU04", "EIU03");
    assertThat(rows(ds, "SELECT series, document_type FROM einvoice_series_name ORDER BY 1"))
        .containsExactly("INV|380");
    assertThat(
            PostgresSupport.scalarOn(
                ds, "SELECT count(*) FROM pg_constraint WHERE conname = 'einvoice_series_name_fk'"))
        .isZero();
    assertThat(PostgresSupport.scalarOn(ds, "SELECT count(*) FROM einvoice_schema")).isZero();
    Throwable startup =
        catchThrowable(() -> JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of(ZONE))));
    assertThat(startup).isInstanceOf(EInvoiceException.class);
    assertThat(((EInvoiceException) startup).code()).isEqualTo(ErrorCodes.SCHEMA_UPGRADE_REFUSED);
  }

  /**
   * Test 5: a database an unreleased build already stamped at format 2, with no name table, is not
   * taken as fully present: the next owner start runs the script, which refuses it with {@code
   * EIU04}.
   */
  @Test
  void a_format_2_database_without_the_name_table_runs_the_script_again_and_is_refused() {
    DataSource ds = opened(PostgresSupport.freshDatabase("c03stamped"));
    exec(ds, "ALTER TABLE einvoice_series DROP CONSTRAINT einvoice_series_name_fk");
    exec(ds, "DROP TABLE einvoice_series_name");
    exec(ds, yearRow(SELLER, "RACE", "380", 2026));
    exec(ds, yearRow(SELLER, "RACE", "381", 2027));
    assertThat(JdbcSupport.readSchemaVersion(ds).current()).isTrue();

    Throwable startup =
        catchThrowable(() -> JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of(ZONE))));

    assertThat(startup).isInstanceOf(EInvoiceException.class);
    assertThat(((EInvoiceException) startup).code()).isEqualTo(ErrorCodes.SCHEMA_UPGRADE_REFUSED);
    assertThat(startup.getMessage()).contains("EIU04");
  }

  // ------------------------------------------------------------------ test 7: the final block

  private List<Refusal> rerunOn(DataSource ds, String... before) throws SQLException {
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(true);
      try (Statement st = c.createStatement()) {
        for (String sql : before) {
          st.execute(sql);
        }
      }
      return runLikePsql(c, "", script());
    }
  }

  private static void assertRefusedNaming(List<Refusal> refusals, String object) {
    assertThat(refusals)
        .filteredOn(r -> "EIU03".equals(r.state()))
        .as("EIU03 naming %s", object)
        .anySatisfy(r -> assertThat(r.message()).contains(object));
  }

  /** Test 7: each name-table guard, disabled, breaks the stamp by name. */
  @ParameterizedTest
  @ValueSource(strings = {"einvoice_series_name_append_only", "einvoice_series_name_no_truncate"})
  void a_disabled_name_table_guard_fails_the_final_block(String trigger) throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("c03off"));
    assertRefusedNaming(
        rerunOn(ds, "ALTER TABLE einvoice_series_name DISABLE TRIGGER " + trigger), trigger);
  }

  /** Test 7: a name-table guard rebound to another function breaks the stamp by name. */
  @Test
  void a_name_table_guard_bound_to_another_function_fails_the_final_block() throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("c03bind"));
    assertRefusedNaming(
        rerunOn(
            ds,
            "CREATE SCHEMA elsewhere",
            "CREATE FUNCTION elsewhere.einvoice_append_only() RETURNS trigger LANGUAGE plpgsql"
                + " SET search_path = pg_catalog, pg_temp AS $$ BEGIN RETURN NEW; END $$",
            "DROP TRIGGER einvoice_series_name_append_only ON einvoice_series_name",
            "CREATE TRIGGER einvoice_series_name_append_only BEFORE UPDATE OR DELETE ON"
                + " einvoice_series_name FOR EACH ROW EXECUTE FUNCTION elsewhere.einvoice_append_only()"),
        "einvoice_series_name_append_only");
  }

  /** Test 7: the function the name-table guards call, without its pinned search path. */
  @Test
  void the_name_table_guard_function_without_its_pin_fails_the_final_block() throws SQLException {
    String unpinned =
        script()
            .replace(
                "RAISE EXCEPTION '% is append-only (attempted %)', TG_TABLE_NAME, TG_OP;\nEND;\n$$"
                    + " LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp;",
                "RAISE EXCEPTION '% is append-only (attempted %)', TG_TABLE_NAME, TG_OP;\nEND;\n$$"
                    + " LANGUAGE plpgsql;");
    assertThat(unpinned).isNotEqualTo(script());
    DataSource ds = opened(PostgresSupport.emptyDatabase("c03pin"));
    List<Refusal> refusals;
    try (Connection c = ds.getConnection()) {
      refusals = runLikePsql(c, "", unpinned);
    }
    assertRefusedNaming(refusals, "einvoice_append_only");
    assertThat(PostgresSupport.scalarOn(ds, "SELECT count(*) FROM einvoice_schema")).isZero();
  }

  /**
   * Test 7, the builder's addition for the build review: the foreign key's internal triggers
   * disabled (superuser only: {@code convalidated} stays true) break the stamp by name.
   */
  @Test
  void a_disabled_foreign_key_trigger_fails_the_final_block() throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("c03fkoff"));
    List<String> internal =
        rows(
            ds,
            "SELECT tgrelid::regclass::text || ':' || tgname FROM pg_trigger"
                + " WHERE tgisinternal AND tgconstraint = (SELECT oid FROM pg_constraint"
                + " WHERE conname = 'einvoice_series_name_fk') ORDER BY 1 LIMIT 1");
    assertThat(internal).hasSize(1);
    String[] parts = internal.get(0).split(":");
    assertRefusedNaming(
        rerunOn(ds, "ALTER TABLE " + parts[0] + " DISABLE TRIGGER \"" + parts[1] + "\""),
        "einvoice_series_name_fk");
  }

  /** Test 7: a foreign key added NOT VALID is not the control. */
  @Test
  void a_foreign_key_not_validated_fails_the_final_block() throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("c03fknv"));
    assertRefusedNaming(
        rerunOn(
            ds,
            "ALTER TABLE einvoice_series DROP CONSTRAINT einvoice_series_name_fk",
            "ALTER TABLE einvoice_series ADD CONSTRAINT einvoice_series_name_fk FOREIGN KEY"
                + " (seller_id, mode, series, document_type) REFERENCES einvoice_series_name"
                + " (seller_id, mode, series, document_type) NOT VALID"),
        "einvoice_series_name_fk");
  }

  /** Test 7: a foreign key of that name that leaves out the type does not bind the type. */
  @Test
  void a_foreign_key_without_the_type_fails_the_final_block() throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("c03fkcols"));
    assertRefusedNaming(
        rerunOn(
            ds,
            "ALTER TABLE einvoice_series DROP CONSTRAINT einvoice_series_name_fk",
            "ALTER TABLE einvoice_series ADD CONSTRAINT einvoice_series_name_fk FOREIGN KEY"
                + " (seller_id, mode, series) REFERENCES einvoice_series_name"
                + " (seller_id, mode, series)"),
        "einvoice_series_name_fk");
  }

  /** Test 7: a name table whose primary key includes the type does not make the name unique. */
  @Test
  void a_name_table_keyed_on_the_type_too_fails_the_final_block() throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("c03pk"));
    assertRefusedNaming(
        rerunOn(
            ds,
            "ALTER TABLE einvoice_series DROP CONSTRAINT einvoice_series_name_fk",
            "ALTER TABLE einvoice_series_name DROP CONSTRAINT einvoice_series_name_pkey",
            "ALTER TABLE einvoice_series_name ADD CONSTRAINT einvoice_series_name_pkey"
                + " PRIMARY KEY (seller_id, mode, series, document_type)"),
        "einvoice_series_name");
  }
}
