package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.DocumentSeriesFixture.MARCH_10;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Security review of the typed number series (format 2), pass 2. Each probe states the property,
 * not the fix. The operator probes grant CREATE on the install schema to the documented runtime
 * role: that is the default of PostgreSQL 14 and older for the public schema, and the docs state no
 * minimum version and no REVOKE.
 */
class CipherProbePr33Pass2Test {

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

  /** The documented runtime role, plus CREATE on public as PostgreSQL 14 grants it by default. */
  private static DataSource runtimeWithPublicCreate(DataSource owner, String role) {
    DataSource runtime = opened(SchemaGrants.roleWithDocumentedGrants(owner, role, "probe"));
    PostgresSupport.executeOn(owner, "GRANT CREATE ON SCHEMA public TO " + role);
    return runtime;
  }

  private static void quietly(Statement st, String sql) {
    try {
      st.execute(sql);
    } catch (SQLException refusedIsTheProperty) {
      // a refusal is the property holding
    }
  }

  // ------------------------------------------------------------ P2-1 (MEDIUM), NR-1 operators

  /**
   * The series insert guard compares {@code s.document_type <> $4} on varchar. pg_catalog has no
   * {@code <>(varchar, varchar)}, so an exact-match operator in public wins over the built-in one
   * even with pg_catalog first in the path (the CVE-2018-1058 shape). The guard then never sees
   * another type, and one series name numbers two types.
   */
  @Test
  void probe_a_runtime_role_operator_cannot_disarm_the_series_name_binding() throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("pr33p2op"));
    DocumentSeriesFixture f =
        new DocumentSeriesFixture(ds, PostgresSupport.freshSeller("pr33p2op"));
    f.invoice("in_first", MARCH_10);
    DataSource runtime = runtimeWithPublicCreate(ds, "einvoice_pr33p2op");

    try (Connection c = runtime.getConnection();
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE FUNCTION public.never_differs(varchar, varchar) RETURNS boolean"
              + " LANGUAGE sql IMMUTABLE AS 'SELECT false'");
      st.execute(
          "CREATE OPERATOR public.<> (LEFTARG = varchar, RIGHTARG = varchar,"
              + " FUNCTION = public.never_differs)");
      quietly(
          st,
          "INSERT INTO public.einvoice_series (seller_id, document_type, series, fiscal_year,"
              + " mode, prefix, width, next_number, updated_at) VALUES ('"
              + f.seller
              + "', '381', 'INV', 2027, 'live', 'X27-', 6, 1, now())");
      st.execute("DROP OPERATOR public.<> (varchar, varchar)");
    }

    assertThat(
            f.scalar(
                "SELECT count(DISTINCT document_type) FROM einvoice_series WHERE seller_id = '"
                    + f.seller
                    + "' AND series = 'INV'"))
        .isEqualTo(1);
  }

  /**
   * The same look-alike disarms a guard that 0.1.x already shipped: the issuance guard's {@code
   * NEW.legal_number <> OLD.legal_number}. The documented role rewrites a legal number.
   */
  @Test
  void probe_a_runtime_role_operator_cannot_rewrite_a_legal_number() throws SQLException {
    DataSource ds = opened(PostgresSupport.freshDatabase("pr33p2legal"));
    DocumentSeriesFixture f =
        new DocumentSeriesFixture(ds, PostgresSupport.freshSeller("pr33p2legal"));
    Issuance numbered = f.invoice("in_legal", MARCH_10);
    DataSource runtime = runtimeWithPublicCreate(ds, "einvoice_pr33p2legal");

    try (Connection c = runtime.getConnection();
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE FUNCTION public.never_differs(varchar, varchar) RETURNS boolean"
              + " LANGUAGE sql IMMUTABLE AS 'SELECT false'");
      st.execute(
          "CREATE OPERATOR public.<> (LEFTARG = varchar, RIGHTARG = varchar,"
              + " FUNCTION = public.never_differs)");
      quietly(
          st,
          "UPDATE public.einvoice_issuance SET legal_number = 'INV-2026-999999' WHERE id = "
              + numbered.id());
      st.execute("DROP OPERATOR public.<> (varchar, varchar)");
    }

    assertThat(
            f.scalar(
                "SELECT count(*) FROM einvoice_issuance WHERE id = "
                    + numbered.id()
                    + " AND legal_number = '"
                    + numbered.legalNumber().value()
                    + "'"))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------ P2-2 (LOW), backfill zone set

  /**
   * The backfill accepts any name in pg_timezone_names. That set holds legacy names that are not
   * region ids (CET, EET, MET, WET, EST5EDT, ...). On the pinned PostgreSQL image (tzdata 2026b)
   * CET has no summer time; the JVM (tzdb 2025b) gives it summer time. An invoice at 22:30 UTC on
   * 14 July is the 15th in Java and on the document, and the 14th in the backfilled series date.
   */
  @Test
  void probe_a_legacy_zone_name_is_refused_or_backfills_the_date_the_starter_derives() {
    String seller = "seller-pr33p2zone";
    Instant summerNight = Instant.parse("2026-07-14T22:30:00Z");
    DataSource ds = opened(PostgresSupport.emptyDatabase("pr33p2zone"));
    Format1.installSchema(ds);
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      Format1.number(c, seller, "DEFAULT", 2026, "in_summer", summerNight);
      c.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    ZoneId zone = ZoneId.of("CET");
    LocalDate javaDate = LocalDate.ofInstant(summerNight, zone);

    Throwable upgrade = catchThrowable(() -> JdbcSupport.initializeSchema(ds, Optional.of(zone)));

    if (upgrade == null) {
      LocalDate backfilled;
      try (Connection c = ds.getConnection();
          var ps =
              c.prepareStatement(
                  "SELECT last_issue_date FROM einvoice_series WHERE seller_id = ?")) {
        ps.setString(1, seller);
        try (var rs = ps.executeQuery()) {
          rs.next();
          backfilled = rs.getObject(1, LocalDate.class);
        }
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
      assertThat(backfilled).isEqualTo(javaDate);
    } else {
      assertThat(upgrade).isInstanceOf(EInvoiceException.class);
      assertThat(((EInvoiceException) upgrade).code()).isEqualTo(ErrorCodes.SCHEMA_UPGRADE_REFUSED);
    }
  }
}
