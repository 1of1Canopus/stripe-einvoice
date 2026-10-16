package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.DocumentSeriesFixture.MARCH_10;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.IssuanceChainVerifier.Status;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Security review of the typed number series (format 2), pass 1. Each probe failed on the branch
 * as reviewed; each states the property, not the fix, so any correct fix turns it green.
 */
class CipherProbePr33Test {

  private static final List<DataSource> OPENED =
      java.util.Collections.synchronizedList(new ArrayList<>());

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

  private static final String SELLER = "seller-pr33";

  private static String script() {
    try (var in =
        JdbcSupport.class.getResourceAsStream("/com/housedevinci/einvoice/schema-postgresql.sql")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Runs a script the way {@code psql -f} does with its defaults, which is what the upgrade note
   * tells an owner to do by hand: one statement at a time, each in autocommit, and on an error it
   * prints the error and goes on to the next statement (no ON_ERROR_STOP, no single transaction).
   */
  private static void runLikePsql(DataSource ds, String prelude, String script) {
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(true);
      List<String> statements = new ArrayList<>();
      if (!prelude.isEmpty()) {
        statements.add(prelude);
      }
      statements.addAll(split(script));
      for (String sql : statements) {
        try (Statement st = c.createStatement()) {
          st.execute(sql);
        } catch (SQLException ignoredLikePsql) {
          // psql prints "ERROR: ..." and continues
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Splits on top-level semicolons, honouring -- comments, '...' literals and $$ bodies. */
  static List<String> split(String script) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean dollar = false;
    boolean quote = false;
    for (int i = 0; i < script.length(); i++) {
      char ch = script.charAt(i);
      if (!dollar && !quote && ch == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
        int nl = script.indexOf('\n', i);
        i = nl < 0 ? script.length() : nl;
        cur.append('\n');
        continue;
      }
      if (!quote && ch == '$' && i + 1 < script.length() && script.charAt(i + 1) == '$') {
        dollar = !dollar;
        cur.append("$$");
        i++;
        continue;
      }
      if (!dollar && ch == '\'') {
        quote = !quote;
      }
      if (!dollar && !quote && ch == ';') {
        if (!cur.toString().isBlank()) {
          out.add(cur.toString());
        }
        cur.setLength(0);
        continue;
      }
      cur.append(ch);
    }
    if (!cur.toString().isBlank()) {
      out.add(cur.toString());
    }
    return out;
  }

  /** A released 0.1.0 database with numbered rows written by the 0.1.0 statements. */
  private static DataSource released(String hint, String... seriesAndIds) {
    DataSource ds = opened(PostgresSupport.emptyDatabase(hint));
    Format1.installSchema(ds);
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      for (int i = 0; i < seriesAndIds.length; i += 2) {
        Format1.number(
            c, SELLER, seriesAndIds[i], 2026, seriesAndIds[i + 1], Instant.parse("2026-01-20T23:00:00Z"));
      }
      c.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return ds;
  }

  private static Optional<LocalDate> lastIssueDate(DataSource ds, String series) {
    try (Connection c = ds.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT last_issue_date FROM einvoice_series WHERE seller_id = ? AND series = ?")) {
      ps.setString(1, SELLER);
      ps.setString(2, series);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? Optional.ofNullable(rs.getObject(1, LocalDate.class)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // ------------------------------------------------------------------ F-1 (MEDIUM)

  /**
   * The upgrade note says to run the script "by hand with psql" and to set the zone first. An
   * owner who forgets the zone gets the script's own refusal ("nothing was changed"), and psql
   * runs on: the version row is stamped 2 while the series with rows has no latest date. The
   * version gate then vouches for a schema the upgrade never finished.
   */
  @Test
  void probe_a_by_hand_upgrade_refused_for_the_zone_is_never_accepted_as_format_2() {
    DataSource ds = released("pr33nozone", "DEFAULT", "in_1");

    runLikePsql(ds, "", script());

    assertThat(lastIssueDate(ds, "DEFAULT")).isEmpty();
    Throwable startup = catchThrowable(() -> JdbcSupport.verifySchema(ds));
    assertThat(startup)
        .as("a schema whose upgrade refused must not pass the format-2 gate")
        .isInstanceOf(EInvoiceException.class);
  }

  /**
   * Same path, the other refusal: two rows of one seller and mode share a legal number. The
   * pre-check refuses, the unique index then fails, psql runs on and stamps format 2. The schema
   * runs without the legal-number uniqueness that format 2 promises, and startup accepts it.
   */
  @Test
  void probe_a_by_hand_upgrade_refused_for_duplicate_numbers_is_never_accepted_as_format_2() {
    DataSource ds = released("pr33dup", "A", "in_1", "B", "in_2");

    runLikePsql(ds, "SET einvoice.tax_zone = 'Europe/Paris'", script());

    assertThat(
            PostgresSupport.scalarOn(
                ds,
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'einvoice_issuance_number_unique'"))
        .isZero();
    Throwable startup = catchThrowable(() -> JdbcSupport.verifySchema(ds));
    assertThat(startup)
        .as("a schema without the legal-number uniqueness must not pass the format-2 gate")
        .isInstanceOf(EInvoiceException.class);
    Throwable init = catchThrowable(() -> JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of("Europe/Paris"))));
    assertThat(init)
        .as("the starter's own path must not return early on it either")
        .isInstanceOf(EInvoiceException.class);
  }

  // ------------------------------------------------------------------ F-2 (LOW)

  /**
   * S-09: one series name serves one document type for a seller and mode, in every fiscal year.
   * The insert guard checks with a plain EXISTS, which cannot see another transaction's uncommitted
   * row, and no index covers "same name, other year, other type". Two writers seeding the name
   * for two years at once both commit.
   */
  @Test
  void probe_one_series_name_never_binds_two_types_under_concurrent_inserts() throws Exception {
    String seller = PostgresSupport.freshSeller("pr33race");
    String insert =
        "INSERT INTO einvoice_series (seller_id, document_type, series, fiscal_year, mode, prefix,"
            + " width, next_number, updated_at) VALUES ('"
            + seller
            + "', '%s', 'RACE', %d, 'live', '%s', 6, 1, now())";
    DataSource ds = PostgresSupport.dataSource();
    try (Connection first = ds.getConnection()) {
      first.setAutoCommit(false);
      try (Statement st = first.createStatement()) {
        st.execute(String.format(insert, "380", 2026, "R26-"));
      }
      CompletableFuture<Void> second =
          CompletableFuture.runAsync(
              () -> {
                try (Connection c = ds.getConnection();
                    Statement st = c.createStatement()) {
                  st.execute(String.format(insert, "381", 2027, "C27-"));
                } catch (SQLException refusedIsFine) {
                  // a refusal is the property holding
                }
              });
      Thread.sleep(500);
      first.commit();
      second.get(20, TimeUnit.SECONDS);
    }
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(DISTINCT document_type) FROM einvoice_series WHERE seller_id = '"
                    + seller
                    + "' AND series = 'RACE'"))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------ F-3 (LOW)

  /**
   * The series' latest date only moves forward and nothing lowers it. The allocator takes BT-2 as
   * a date from the caller and never checks it against the instant it was derived from, so one
   * direct call with a far date moves {@code last_issue_date} to it for good: every later invoice
   * of the series is then an open DEI-127, and every later credit note of a credit series is
   * refused. No time zone puts 2026-03-10T10:00Z on 1 January 2099.
   */
  @Test
  void probe_an_issue_date_no_zone_can_derive_from_issued_at_never_moves_the_series_date() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("pr33far");
    f.invoice("in_first", MARCH_10);

    Throwable far =
        catchThrowable(
            () ->
                f.store.allocate(
                    new AllocationRequest(
                        f.invoices(Mode.LIVE),
                        "in_far",
                        "",
                        "S-far",
                        MARCH_10,
                        LocalDate.of(2099, 1, 1),
                        "r1")));

    assertThat(far).isInstanceOf(EInvoiceException.class);
    assertThat(((EInvoiceException) far).code()).isEqualTo(ErrorCodes.INVALID);
    assertThat(
            f.scalar(
                "SELECT count(*) FROM einvoice_series WHERE seller_id = '"
                    + f.seller
                    + "' AND last_issue_date > DATE '2026-12-31'"))
        .isZero();
  }

  // ------------------------------------------------------------------ F-4 (LOW)

  /**
   * The starter accepts any {@link ZoneId} as the tax zone and passes its id to the backfill.
   * PostgreSQL reads an offset id such as {@code +02:00} or {@code UTC+02:00} as a POSIX zone, with
   * the sign reversed, so an invoice at 23:00 UTC on 20 January is the 21st in Java (and on the
   * document) and the 20th in the backfilled series date. The script's own comment says an unknown
   * zone fails; PostgreSQL also accepts invented names such as {@code Nowhere+5}.
   */
  @ParameterizedTest
  @ValueSource(strings = {"+02:00", "UTC+02:00"})
  void probe_the_backfilled_date_is_the_date_the_starter_derives_in_the_same_zone(String zone) {
    DataSource ds = released("pr33zone", "DEFAULT", "in_1");
    LocalDate javaDate = LocalDate.ofInstant(Instant.parse("2026-01-20T23:00:00Z"), ZoneId.of(zone));

    Throwable upgrade = catchThrowable(() -> JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of(zone))));

    if (upgrade == null) {
      assertThat(lastIssueDate(ds, "DEFAULT")).contains(javaDate);
    } else {
      assertThat(upgrade).isInstanceOf(EInvoiceException.class);
      assertThat(((EInvoiceException) upgrade).code()).isEqualTo(ErrorCodes.SCHEMA_UPGRADE_REFUSED);
    }
  }

  // ------------------------------------------------------------------ F-5 (LOW)

  /**
   * The event table's CHECK covers {@code corrects_issuance_id} only, and the reader builds a
   * target only when that id is set. A full corrected-invoice identity planted on an invoice's
   * chained event (id left NULL) is neither hashed nor refused nor read: an auditor querying the
   * table sees an invoice event that names a corrected invoice, and the verifier says INTACT.
   */
  @Test
  void probe_a_correction_identity_planted_on_an_invoice_event_is_refused_or_broken() {
    DataSource ds = opened(PostgresSupport.freshDatabase("pr33plant"));
    DocumentSeriesFixture f = new DocumentSeriesFixture(ds, PostgresSupport.freshSeller("pr33plant"));
    f.issuedInvoice("in_a", MARCH_10);
    IssuanceChainVerifier verifier =
        new IssuanceChainVerifier(
            f.store, f.store, Map.of(Format1.KEY_ID, Format1.SECRET.getBytes(StandardCharsets.UTF_8)));
    assertThat(verifier.verify().status()).isEqualTo(Status.INTACT);

    boolean refused = false;
    PostgresSupport.executeOn(
        ds, "ALTER TABLE einvoice_issuance_event DISABLE TRIGGER einvoice_issuance_event_append_only");
    try {
      PostgresSupport.executeOn(
          ds,
          "UPDATE einvoice_issuance_event SET corrects_seller_id = seller_id, corrects_mode = mode,"
              + " corrects_series = 'INV', corrects_fiscal_year = 2026,"
              + " corrects_legal_number = 'INV-2026-000099' WHERE stripe_invoice_id = 'in_a'");
    } catch (IllegalStateException e) {
      refused = true;
    } finally {
      PostgresSupport.executeOn(
          ds, "ALTER TABLE einvoice_issuance_event ENABLE TRIGGER einvoice_issuance_event_append_only");
    }

    if (!refused) {
      assertThat(verifier.verify().status()).isEqualTo(Status.BROKEN);
    }
  }

  // ------------------------------------------------------------------ F-6 (MEDIUM)

  /**
   * NR-1. The two new insert guards read einvoice_issuance and einvoice_issuance_event by short
   * name, so PostgreSQL resolves them through the caller's search_path, where pg_temp comes first.
   * The runtime role holds TEMPORARY on the database by default. Two temporary tables that say
   * "ISSUED" make the database accept a credit note against a number that was never issued: the
   * bound of D-CNS-S12, "for every writer", does not hold for the role the triggers exist to bind.
   * By hand: CREATE TEMP TABLE einvoice_issuance AS SELECT * FROM public.einvoice_issuance WHERE
   * id = :target; UPDATE it to ISSUED with a hash; CREATE TEMP TABLE einvoice_issuance_event with
   * one ISSUED row of that number and hash; INSERT INTO public.einvoice_issuance ... '381', :target.
   */
  @Test
  void probe_the_runtime_role_cannot_shadow_the_target_bound_with_temporary_tables()
      throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("pr33shadow"));
    DocumentSeriesFixture f = new DocumentSeriesFixture(ds, PostgresSupport.freshSeller("pr33shadow"));
    Issuance numbered = f.invoice("in_never_issued", MARCH_10);
    DataSource runtime =
        opened(SchemaGrants.roleWithDocumentedGrants(ds, "einvoice_pr33shadow", "probe"));

    try (Connection c = runtime.getConnection();
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE TEMP TABLE einvoice_issuance AS SELECT * FROM public.einvoice_issuance WHERE id = "
              + numbered.id());
      st.execute("UPDATE einvoice_issuance SET state = 'ISSUED', document_sha256 = repeat('f', 64)");
      st.execute(
          "CREATE TEMP TABLE einvoice_issuance_event (seq bigint, seller_id text, mode text,"
              + " legal_number text, state text, document_sha256 text)");
      st.execute(
          "INSERT INTO einvoice_issuance_event VALUES (1, '"
              + f.seller
              + "', 'live', '"
              + numbered.legalNumber().value()
              + "', 'ISSUED', repeat('f', 64))");
      try {
        st.execute(
            "INSERT INTO public.einvoice_issuance (seller_id, mode, stripe_invoice_id, series,"
                + " fiscal_year, legal_number, counter, issued_at, allocated_at, state,"
                + " document_type, corrects_issuance_id) VALUES ('"
                + f.seller
                + "', 'live', 'cn_forged', 'CN', 2026, 'CN-2026-000001', 1, now(), now(),"
                + " 'NUMBERED', '381', "
                + numbered.id()
                + ")");
      } catch (SQLException refusedIsTheProperty) {
        // the guard saw the real tables
      }
    }

    assertThat(
            f.scalar(
                "SELECT count(*) FROM einvoice_issuance WHERE stripe_invoice_id = 'cn_forged'"
                    + " AND seller_id = '"
                    + f.seller
                    + "'"))
        .isZero();
  }

  /** NR-1, the other guard: an empty temporary einvoice_series hides the name's existing type. */
  @Test
  void probe_the_runtime_role_cannot_shadow_the_series_name_binding_with_a_temporary_table()
      throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("pr33shadowseries"));
    DocumentSeriesFixture f =
        new DocumentSeriesFixture(ds, PostgresSupport.freshSeller("pr33shadowseries"));
    f.invoice("in_first", MARCH_10);
    DataSource runtime =
        opened(SchemaGrants.roleWithDocumentedGrants(ds, "einvoice_pr33shadows", "probe"));

    try (Connection c = runtime.getConnection();
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE TEMP TABLE einvoice_series (seller_id text, mode text, series text,"
              + " document_type text)");
      try {
        st.execute(
            "INSERT INTO public.einvoice_series (seller_id, document_type, series, fiscal_year,"
                + " mode, prefix, width, next_number, updated_at) VALUES ('"
                + f.seller
                + "', '381', 'INV', 2027, 'live', 'X27-', 6, 1, now())");
      } catch (SQLException refusedIsTheProperty) {
        // the guard saw the real table
      }
    }

    assertThat(
            f.scalar(
                "SELECT count(DISTINCT document_type) FROM einvoice_series WHERE seller_id = '"
                    + f.seller
                    + "' AND series = 'INV'"))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------ F-7 (LOW)

  /**
   * S-01's pre-seam rule lives in IssuanceChain.verify: an ei1/ei2h row must read 380 and carry no
   * target. Removing that clause leaves the builder's probe green, because that probe rewrites the
   * event only and the row cross-check catches it. This probe rewrites the event AND the row the
   * same way and leaves the series row at 380, so the clause is the only line: without it the
   * verifier reports a pre-seam invoice turned into a credit note as INTACT.
   */
  @Test
  void probe_a_pre_seam_invoice_rewritten_into_a_credit_note_on_row_and_event_is_broken()
      throws SQLException {
    DataSource ds = opened(PostgresSupport.emptyDatabase("pr33preseam"));
    Format1.installSchema(ds);
    Instant jan15 = Instant.parse("2026-01-15T10:00:00Z");
    String n1;
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      n1 = Format1.number(c, SELLER, "DEFAULT", 2026, "in_one", jan15);
      String n2 = Format1.number(c, SELLER, "DEFAULT", 2026, "in_two", jan15);
      String sha = DocumentSeriesFixture.sha("one");
      String h1 =
          Format1.dispose(c, SELLER, "DEFAULT", 2026, "in_one", n1, 1, "ISSUED", sha,
              "live/" + n1 + "-" + sha + ".xml", "", jan15, jan15, IssuanceChain.GENESIS, 1);
      Format1.dispose(c, SELLER, "DEFAULT", 2026, "in_two", n2, 2, "VOID_UNUSED", "", "",
          "never sent", jan15, jan15, h1, 2);
      c.commit();
    }
    JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of("Europe/Paris")));
    JdbcIssuanceStore store =
        new JdbcIssuanceStore(
            ds,
            DocumentSeriesFixture.CHAIN,
            Map.of(
                new JdbcIssuanceStore.SeriesId(SELLER, "DEFAULT", Mode.LIVE),
                DocumentSeriesFixture.INVOICES),
            DocumentSeriesFixture.CLOCK);
    IssuanceChainVerifier verifier =
        new IssuanceChainVerifier(
            store, store, Map.of(Format1.KEY_ID, Format1.SECRET.getBytes(StandardCharsets.UTF_8)));
    assertThat(verifier.verify().status()).isEqualTo(Status.INTACT);

    long target =
        PostgresSupport.scalarOn(
            ds, "SELECT id FROM einvoice_issuance WHERE stripe_invoice_id = 'in_one'");
    PostgresSupport.executeOn(ds, "ALTER TABLE einvoice_issuance DISABLE TRIGGER einvoice_issuance_guard");
    PostgresSupport.executeOn(
        ds,
        "UPDATE einvoice_issuance SET document_type = '381', corrects_issuance_id = "
            + target
            + " WHERE stripe_invoice_id = 'in_two'");
    PostgresSupport.executeOn(ds, "ALTER TABLE einvoice_issuance ENABLE TRIGGER einvoice_issuance_guard");
    PostgresSupport.executeOn(
        ds, "ALTER TABLE einvoice_issuance_event DISABLE TRIGGER einvoice_issuance_event_append_only");
    PostgresSupport.executeOn(
        ds,
        "UPDATE einvoice_issuance_event SET document_type = '381', corrects_issuance_id = "
            + target
            + ", corrects_seller_id = '"
            + SELLER
            + "', corrects_mode = 'live', corrects_series = 'DEFAULT', corrects_fiscal_year = 2026,"
            + " corrects_legal_number = '"
            + n1
            + "' WHERE stripe_invoice_id = 'in_two'");
    PostgresSupport.executeOn(
        ds, "ALTER TABLE einvoice_issuance_event ENABLE TRIGGER einvoice_issuance_event_append_only");

    assertThat(verifier.verify().status()).isEqualTo(Status.BROKEN);
  }
}
