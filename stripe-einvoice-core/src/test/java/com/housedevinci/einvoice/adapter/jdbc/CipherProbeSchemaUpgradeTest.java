package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.IssuanceChainVerifier.Status;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.Mode;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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

/**
 * The upgrade from a database that the released 0.1.0 wrote (credit-note series design, sections 4
 * and 5; S-01, S-03, S-05, S-06, S-07). Each probe starts from an empty database, installs the
 * verbatim 0.1.0 script and writes rows with the 0.1.0 statements and the 0.1.0 canonical form
 * ({@link Format1}), then upgrades.
 */
class CipherProbeSchemaUpgradeTest {

  private static final java.util.List<DataSource> OPENED =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  private static <T extends DataSource> T opened(T ds) {
    OPENED.add(ds);
    return ds;
  }

  @AfterEach
  void closePools() {
    synchronized (OPENED) {
      OPENED.forEach(PostgresSupport::close);
      OPENED.clear();
    }
  }

  private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
  private static final String SELLER = "seller-upgrade";
  private static final Instant JAN_15 = Instant.parse("2026-01-15T10:00:00Z");
  // 23:30 UTC on 20 January is 00:30 on 21 January in Paris: the backfill must use the zone.
  private static final Instant JAN_20_LATE = Instant.parse("2026-01-20T23:30:00Z");

  /** A 0.1.0 database: one issued invoice, one voided number, chained in ei2h. */
  private static HikariDataSource released(String hint) {
    HikariDataSource ds = opened(PostgresSupport.emptyDatabase(hint));
    Format1.installSchema(ds);
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      String n1 = Format1.number(c, SELLER, "DEFAULT", 2026, "in_one", JAN_15);
      String n2 = Format1.number(c, SELLER, "DEFAULT", 2026, "in_two", JAN_20_LATE);
      String sha = DocumentSeriesFixture.sha("one");
      String h1 =
          Format1.dispose(
              c,
              SELLER,
              "DEFAULT",
              2026,
              "in_one",
              n1,
              1,
              "ISSUED",
              sha,
              "live/" + n1 + "-" + sha + ".xml",
              "",
              JAN_15,
              JAN_15,
              IssuanceChain.GENESIS,
              1);
      Format1.dispose(
          c,
          SELLER,
          "DEFAULT",
          2026,
          "in_two",
          n2,
          2,
          "VOID_UNUSED",
          "",
          "",
          "never sent",
          JAN_20_LATE,
          JAN_20_LATE,
          h1,
          2);
      c.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return ds;
  }

  private static JdbcIssuanceStore store(DataSource ds) {
    return new DocumentSeriesFixtureOnDefault(ds).store;
  }

  private static IssuanceChainVerifier verifier(JdbcIssuanceStore store) {
    return new IssuanceChainVerifier(
        store, store, Map.of(Format1.KEY_ID, Format1.SECRET.getBytes(StandardCharsets.UTF_8)));
  }

  /** The upgraded seller's series: the 0.1.0 invoice series name, plus a credit-note series. */
  private static final class DocumentSeriesFixtureOnDefault {
    final JdbcIssuanceStore store;

    DocumentSeriesFixtureOnDefault(DataSource ds) {
      this.store =
          new JdbcIssuanceStore(
              ds,
              DocumentSeriesFixture.CHAIN,
              Map.of(
                  new JdbcIssuanceStore.SeriesId(SELLER, "DEFAULT", Mode.LIVE),
                  DocumentSeriesFixture.INVOICES,
                  new JdbcIssuanceStore.SeriesId(
                      SELLER,
                      "CN",
                      Mode.LIVE,
                      com.housedevinci.einvoice.domain.en16931.DocumentTypeCode.CREDIT_NOTE),
                  DocumentSeriesFixture.CREDITS),
              DocumentSeriesFixture.CLOCK);
    }
  }

  private static String script() {
    try (var in =
        JdbcSupport.class.getResourceAsStream("/com/housedevinci/einvoice/schema-postgresql.sql")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Runs the bundled script directly, as an owner would by hand, with the zone set. */
  private static void runScriptByHand(DataSource ds, String suffix) throws SQLException {
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("SET LOCAL einvoice.tax_zone = 'Europe/Paris'");
        st.execute(script() + suffix);
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private static List<String> rows(DataSource ds, String sql) {
    List<String> out = new ArrayList<>();
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      int n = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        StringBuilder row = new StringBuilder();
        for (int i = 1; i <= n; i++) {
          row.append(rs.getString(i)).append('|');
        }
        out.add(row.toString());
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private static List<String> evidence(DataSource ds) {
    List<String> all = new ArrayList<>();
    all.addAll(
        rows(
            ds,
            "SELECT seq, legal_number, counter, state, hash, prev_hash FROM einvoice_issuance_event ORDER BY seq"));
    all.addAll(
        rows(
            ds,
            "SELECT id, legal_number, counter, state, document_sha256, archive_key FROM einvoice_issuance ORDER BY id"));
    all.addAll(rows(ds, "SELECT head_hash, row_count FROM einvoice_issuance_anchor"));
    return all;
  }

  private static List<String> constraints(DataSource ds) {
    return rows(
        ds,
        "SELECT conrelid::regclass::text, conname, pg_get_constraintdef(oid) FROM pg_constraint"
            + " WHERE connamespace = 'public'::regnamespace ORDER BY 1, 2");
  }

  private static boolean hasColumn(DataSource ds, String table, String column) {
    return PostgresSupport.scalarOn(
            ds,
            "SELECT count(*) FROM information_schema.columns WHERE table_name = '"
                + table
                + "' AND column_name = '"
                + column
                + "'")
        == 1;
  }

  // ---------------------------------------------------------------- probe 7-3 and 7-6

  @Test
  void probe_an_upgraded_0_1_0_database_is_intact_across_the_seam() throws SQLException {
    HikariDataSource ds = released("seam");
    List<String> before = evidence(ds);

    JdbcSupport.initializeSchema(ds, Optional.of(PARIS));
    assertThat(evidence(ds)).as("no number, hash or anchor rewritten").isEqualTo(before);
    List<String> afterFirst = constraints(ds);
    runScriptByHand(ds, "");
    runScriptByHand(ds, "");
    assertThat(constraints(ds)).as("a second and third run change nothing").isEqualTo(afterFirst);
    assertThat(evidence(ds)).isEqualTo(before);

    JdbcIssuanceStore store = store(ds);
    IssuanceChainVerifier.Report upgraded = verifier(store).verify();
    assertThat(upgraded.status()).isEqualTo(Status.INTACT);
    assertThat(upgraded.forms()).containsExactly(Map.entry(IssuanceChain.KEYED_VERSION, 2L));
    assertThat(rows(ds, "SELECT document_type, last_issue_date::text FROM einvoice_series"))
        .as("type 380, and the latest BT-2 as a Paris date (00:30 on the 21st)")
        .containsExactly("380|2026-01-21|");

    // The next invoice continues the same series at the same next number, and a credit note
    // lands in its own series.
    DocumentSeriesFixture f = new DocumentSeriesFixture(ds, SELLER);
    Issuance third =
        store.allocate(
            DocumentSeriesFixture.request(
                new com.housedevinci.einvoice.domain.SeriesKey(SELLER, "DEFAULT", 2026, Mode.LIVE),
                "in_three",
                Instant.parse("2026-02-01T10:00:00Z")));
    assertThat(third.legalNumber().value()).isEqualTo("INV-2026-000003");
    long one =
        PostgresSupport.scalarOn(
            ds, "SELECT id FROM einvoice_issuance WHERE stripe_invoice_id = 'in_one'");
    Issuance credit =
        store.allocateTyped(
            DocumentSeriesFixture.request(
                new com.housedevinci.einvoice.domain.SeriesKey(
                    SELLER,
                    "CN",
                    2026,
                    Mode.LIVE,
                    com.housedevinci.einvoice.domain.en16931.DocumentTypeCode.CREDIT_NOTE),
                "cn_one",
                Instant.parse("2026-02-01T10:00:00Z")),
            Optional.of(one));
    assertThat(credit.legalNumber().value()).isEqualTo("CN-2026-000001");
    f.issue(credit, Mode.LIVE);

    IssuanceChainVerifier.Report mixed = verifier(store).verify();
    assertThat(mixed.status()).isEqualTo(Status.INTACT);
    assertThat(mixed.forms())
        .containsOnly(
            Map.entry(IssuanceChain.KEYED_VERSION, 2L),
            Map.entry(IssuanceChain.TYPED_KEYED_VERSION, 1L));
  }

  @Test
  void probe_relabelling_a_typed_row_with_the_older_form_is_broken() {
    HikariDataSource ds = released("relabel");
    JdbcSupport.initializeSchema(ds, Optional.of(PARIS));
    JdbcIssuanceStore store = store(ds);
    DocumentSeriesFixture f = new DocumentSeriesFixture(ds, SELLER);
    Issuance third =
        store.allocate(
            DocumentSeriesFixture.request(
                new com.housedevinci.einvoice.domain.SeriesKey(SELLER, "DEFAULT", 2026, Mode.LIVE),
                "in_three",
                Instant.parse("2026-02-01T10:00:00Z")));
    f.issue(third, Mode.LIVE);
    assertThat(verifier(store).verify().status()).isEqualTo(Status.INTACT);

    PostgresSupport.executeOn(
        ds,
        "ALTER TABLE einvoice_issuance_event DISABLE TRIGGER einvoice_issuance_event_append_only");
    PostgresSupport.executeOn(
        ds,
        "UPDATE einvoice_issuance_event SET chain_version = 'ei2h' WHERE chain_version = 'ei4h'");
    assertThat(verifier(store).verify().status()).isEqualTo(Status.BROKEN);
  }

  // ---------------------------------------------------------------- S-01

  @Test
  void probe_flipping_the_type_of_a_pre_seam_series_is_broken() {
    HikariDataSource ds = released("preseamflip");
    JdbcSupport.initializeSchema(ds, Optional.of(PARIS));
    JdbcIssuanceStore store = store(ds);
    assertThat(verifier(store).verify().status()).isEqualTo(Status.INTACT);

    // Every row of this trail is ei2h, which hashes no type. Flipping the series row turns a year
    // of invoices into credit notes with every hash still verifying - unless the verifier holds
    // that an ei2h row is an invoice by definition.
    PostgresSupport.executeOn(
        ds, "ALTER TABLE einvoice_series DISABLE TRIGGER einvoice_series_guard");
    PostgresSupport.executeOn(ds, "UPDATE einvoice_series SET document_type = '381'");
    assertThat(verifier(store).verify().status()).isEqualTo(Status.BROKEN);
  }

  @Test
  void probe_a_pre_seam_event_that_claims_a_credit_note_type_is_broken() {
    HikariDataSource ds = released("preseamevent");
    JdbcSupport.initializeSchema(ds, Optional.of(PARIS));
    long one =
        PostgresSupport.scalarOn(
            ds, "SELECT id FROM einvoice_issuance WHERE stripe_invoice_id = 'in_one'");
    PostgresSupport.executeOn(
        ds,
        "ALTER TABLE einvoice_issuance_event DISABLE TRIGGER einvoice_issuance_event_append_only");
    PostgresSupport.executeOn(
        ds,
        "UPDATE einvoice_issuance_event e SET document_type = '381', corrects_issuance_id = t.id,"
            + " corrects_seller_id = t.seller_id, corrects_mode = t.mode,"
            + " corrects_series = t.series, corrects_fiscal_year = t.fiscal_year,"
            + " corrects_legal_number = t.legal_number FROM einvoice_issuance t WHERE t.id = "
            + one
            + " AND e.stripe_invoice_id = 'in_two'");
    assertThat(verifier(store(ds)).verify().status()).isEqualTo(Status.BROKEN);
  }

  // ---------------------------------------------------------------- S-03, interrupted run

  @Test
  void probe_the_primary_key_swap_finds_a_host_named_key_and_is_idempotent() throws SQLException {
    HikariDataSource ds = released("pkname");
    PostgresSupport.executeOn(
        ds, "ALTER TABLE einvoice_series RENAME CONSTRAINT einvoice_series_pkey TO host_named_pk");

    JdbcSupport.initializeSchema(ds, Optional.of(PARIS));
    List<String> once = constraints(ds);
    runScriptByHand(ds, "");
    assertThat(constraints(ds)).isEqualTo(once);
    assertThat(
            rows(
                ds,
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE contype = 'p' AND"
                    + " conrelid = 'einvoice_series'::regclass"))
        .containsExactly("PRIMARY KEY (seller_id, document_type, series, fiscal_year, mode)|");
  }

  @Test
  void probe_an_interrupted_upgrade_leaves_the_released_schema() {
    HikariDataSource ds = released("interrupted");
    List<String> before = constraints(ds);
    assertThatThrownBy(() -> runScriptByHand(ds, "\nSELECT 1 / 0;"))
        .isInstanceOf(SQLException.class);
    assertThat(hasColumn(ds, "einvoice_series", "document_type")).isFalse();
    assertThat(hasColumn(ds, "einvoice_issuance", "corrects_issuance_id")).isFalse();
    assertThat(constraints(ds)).isEqualTo(before);
  }

  // ---------------------------------------------------------------- S-06 and the backfill zone

  @Test
  void probe_a_duplicate_legal_number_refuses_the_upgrade_by_name() throws SQLException {
    HikariDataSource ds = released("duplicate");
    // 0.1.0 allowed this: one string in two series of one seller and mode.
    try (Connection c = ds.getConnection()) {
      Format1.number(c, SELLER, "OTHER", 2026, "in_other", JAN_15);
      try (Statement st = c.createStatement()) {
        st.execute(
            "ALTER TABLE einvoice_issuance DISABLE TRIGGER einvoice_issuance_guard;"
                + " UPDATE einvoice_issuance SET legal_number = 'INV-2026-000001'"
                + " WHERE stripe_invoice_id = 'in_other';"
                + " ALTER TABLE einvoice_issuance ENABLE TRIGGER einvoice_issuance_guard");
      }
    }
    assertThatThrownBy(() -> JdbcSupport.initializeSchema(ds, Optional.of(PARIS)))
        .isInstanceOf(EInvoiceException.class)
        .satisfies(
            e -> {
              assertThat(((EInvoiceException) e).code())
                  .isEqualTo(ErrorCodes.SCHEMA_UPGRADE_REFUSED);
              assertThat(e.getMessage()).contains("1 legal number(s)").doesNotContain("in_other");
            });
    assertThat(hasColumn(ds, "einvoice_series", "document_type")).as("unchanged").isFalse();
  }

  @Test
  void probe_an_upgrade_that_needs_the_tax_zone_refuses_without_one() {
    HikariDataSource ds = released("nozone");
    assertThatThrownBy(() -> JdbcSupport.initializeSchema(ds))
        .extracting("code")
        .isEqualTo(ErrorCodes.SCHEMA_UPGRADE_REFUSED);
    assertThat(hasColumn(ds, "einvoice_series", "document_type")).isFalse();
  }

  // ---------------------------------------------------------------- probe 7-4, downgrade

  @Test
  void probe_released_0_1_0_writes_fail_against_the_upgraded_schema() throws SQLException {
    HikariDataSource ds = released("downgrade");
    JdbcSupport.initializeSchema(ds, Optional.of(PARIS));
    long next = PostgresSupport.scalarOn(ds, "SELECT next_number FROM einvoice_series");

    // One assertion per 0.1.0 write path, so each dropped default is under test on its own.
    // (a) The 0.1.0 allocation on the existing series row: the counter statement succeeds, the
    // issuance insert fails, and the transaction takes the counter back with it.
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      assertThatThrownBy(
              () -> Format1.allocateAndInsert(c, SELLER, "DEFAULT", 2026, "in_old_binary", JAN_15))
          .hasMessageContaining("document_type")
          .hasMessageContaining("einvoice_issuance");
      c.rollback();
    }
    assertThat(PostgresSupport.scalarOn(ds, "SELECT next_number FROM einvoice_series"))
        .as("the counter rolled back with the refused insert")
        .isEqualTo(next);
    try (Connection c = ds.getConnection()) {
      // (b) The 0.1.0 series seed for a new series.
      assertThatThrownBy(() -> Format1.seed(c, SELLER, "NEWSERIES", 2027, JAN_15))
          .hasMessageContaining("document_type")
          .hasMessageContaining("einvoice_series");
      // (c) The 0.1.0 chain append.
      assertThatThrownBy(
              () -> {
                try (var ps = c.prepareStatement(Format1.INSERT_EVENT)) {
                  java.sql.Timestamp at = java.sql.Timestamp.from(JAN_15);
                  Object[] values = {
                    at,
                    SELLER,
                    "live",
                    "",
                    "DEFAULT",
                    2026,
                    "INV-2026-000001",
                    1L,
                    "in_one",
                    "S",
                    "ISSUED",
                    at,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "ei2h",
                    Format1.KEY_ID,
                    IssuanceChain.GENESIS,
                    "f".repeat(64)
                  };
                  for (int i = 0; i < values.length; i++) {
                    ps.setObject(i + 1, values[i]);
                  }
                  ps.executeUpdate();
                }
              })
          .hasMessageContaining("document_type")
          .hasMessageContaining("einvoice_issuance_event");
    }
  }

  // ---------------------------------------------------------------- S-07, the residual

  @Test
  void probe_the_old_canonical_form_reports_broken_on_new_rows() {
    // Measured, not asserted in prose: a 0.1.0 verifier recomputes a 0.2.0 row with the field
    // list it knows, and gets a different hash. That BROKEN is meaningless; the upgrade note says
    // never to act on it, and to stop every 0.1.0 instance before upgrading.
    DataSource ds = opened(PostgresSupport.freshDatabase("oldform"));
    DocumentSeriesFixture f = new DocumentSeriesFixture(ds, PostgresSupport.freshSeller("oldform"));
    f.issuedInvoice("in_new", DocumentSeriesFixture.MARCH_10);
    var event = f.store.readAfter(0, 10).get(0);
    assertThat(event.chainVersion()).isEqualTo(IssuanceChain.TYPED_KEYED_VERSION);
    assertThat(DocumentSeriesFixture.CHAIN.verify(event, IssuanceChain.GENESIS)).isTrue();
    assertThat(Format1.hashOf(event, IssuanceChain.GENESIS)).isNotEqualTo(event.hash());
  }

  // ---------------------------------------------------------------- probe 7-5 and S-05

  @Test
  void probe_a_newer_schema_format_refuses_startup_by_name() {
    DataSource ds = opened(PostgresSupport.freshDatabase("forward"));
    PostgresSupport.executeOn(ds, "UPDATE einvoice_schema SET format_version = 3");
    assertThatThrownBy(() -> JdbcSupport.initializeSchema(ds))
        .extracting("code")
        .isEqualTo(ErrorCodes.SCHEMA_VERSION_UNSUPPORTED);
    assertThatThrownBy(() -> JdbcSupport.verifySchema(ds))
        .extracting("code")
        .isEqualTo(ErrorCodes.SCHEMA_VERSION_UNSUPPORTED);
  }

  @Test
  void probe_a_permission_error_on_the_version_row_refuses_startup() {
    DataSource ds = opened(PostgresSupport.freshDatabase("noselect"));
    DataSource runtime =
        opened(SchemaGrants.roleWithDocumentedGrants(ds, "einvoice_noselect", "probe"));
    PostgresSupport.executeOn(ds, "REVOKE SELECT ON einvoice_schema FROM einvoice_noselect");
    assertThatThrownBy(() -> JdbcSupport.initializeSchema(runtime))
        .isInstanceOf(EInvoiceException.class)
        .satisfies(
            e -> {
              assertThat(((EInvoiceException) e).code())
                  .isEqualTo(ErrorCodes.SCHEMA_VERSION_UNREADABLE);
              assertThat(e.getMessage()).contains("42501");
            });
  }

  @Test
  void probe_the_runtime_role_cannot_lower_the_format_version() {
    DataSource ds = opened(PostgresSupport.freshDatabase("lower"));
    DataSource runtime =
        opened(SchemaGrants.roleWithDocumentedGrants(ds, "einvoice_lower", "probe"));
    // As the documented role: no UPDATE and no DELETE at all, by grant (SQLState 42501).
    for (String sql :
        List.of("UPDATE einvoice_schema SET format_version = 1", "DELETE FROM einvoice_schema")) {
      assertThatThrownBy(() -> PostgresSupport.executeOn(runtime, sql))
          .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
    }
    // As the owner: the trigger refuses a lower version and a delete.
    assertThatThrownBy(
            () -> PostgresSupport.executeOn(ds, "UPDATE einvoice_schema SET format_version = 1"))
        .hasMessageContaining("never go down");
    assertThatThrownBy(() -> PostgresSupport.executeOn(ds, "DELETE FROM einvoice_schema"))
        .hasMessageContaining("never deleted");
  }

  @Test
  void probe_an_unprepared_schema_is_refused_when_initialisation_is_off() {
    HikariDataSource ds = released("unprepared");
    assertThatThrownBy(() -> JdbcSupport.verifySchema(ds))
        .extracting("code")
        .isEqualTo(ErrorCodes.SCHEMA_NOT_PREPARED);
  }

  @Test
  void probe_the_runtime_role_on_a_0_1_0_schema_is_told_to_run_the_upgrade_as_owner() {
    HikariDataSource ds = released("runtimeold");
    PostgresSupport.executeOn(
        ds,
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'einvoice_runtimeold')"
            + " THEN CREATE ROLE einvoice_runtimeold LOGIN PASSWORD 'probe'; END IF; END $$");
    PostgresSupport.executeOn(
        ds,
        "GRANT SELECT, INSERT, UPDATE ON einvoice_series, einvoice_issuance,"
            + " einvoice_issuance_event, einvoice_issuance_anchor TO einvoice_runtimeold");
    DataSource runtime = opened(PostgresSupport.asRole(ds, "einvoice_runtimeold", "probe"));
    assertThatThrownBy(() -> JdbcSupport.initializeSchema(runtime, Optional.of(PARIS)))
        .extracting("code")
        .isEqualTo(ErrorCodes.CONFIG);
    assertThat(hasColumn(ds, "einvoice_series", "document_type")).isFalse();
  }

  private static String sqlState(Throwable e) {
    Throwable t = e;
    while (t != null) {
      if (t instanceof SQLException se) {
        return se.getSQLState();
      }
      t = t.getCause();
    }
    return "";
  }
}
