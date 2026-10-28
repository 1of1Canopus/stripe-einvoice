package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;

/** Plain-JDBC helpers shared by the adapters. No Spring. */
public final class JdbcSupport {

  private JdbcSupport() {}

  @FunctionalInterface
  interface SqlWork<T> {
    T run(Connection c) throws SQLException;
  }

  static <T> T inTransaction(DataSource ds, SqlWork<T> work) {
    try (Connection c = ds.getConnection()) {
      boolean previous = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        T result = work.run(c);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(previous);
      }
    } catch (SQLException e) {
      throw unavailable(e);
    }
  }

  static <T> T withConnection(DataSource ds, SqlWork<T> work) {
    try (Connection c = ds.getConnection()) {
      return work.run(c);
    } catch (SQLException e) {
      throw unavailable(e);
    }
  }

  static OffsetDateTime ts(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  /**
   * The issue clock (D-PS1-40): one {@code timestamptz} read on {@code c}, returned as an instant
   * truncated to what the database stores. See {@link IssueClock#STATEMENT}.
   */
  static Instant readIssueClock(java.sql.Connection c) throws java.sql.SQLException {
    try (java.sql.Statement st = c.createStatement();
        java.sql.ResultSet rs = st.executeQuery(IssueClock.STATEMENT)) {
      rs.next();
      return com.housedevinci.einvoice.domain.Timestamps.toStorage(
          instant(rs.getObject(1, OffsetDateTime.class)));
    }
  }

  static Instant instant(OffsetDateTime t) {
    return t == null ? null : t.toInstant();
  }

  private static final String SCHEMA_FULLY_PRESENT =
      "SELECT to_regclass('einvoice_series') IS NOT NULL"
          + " AND to_regclass('einvoice_issuance') IS NOT NULL"
          + " AND to_regclass('einvoice_issuance_event') IS NOT NULL"
          + " AND to_regclass('einvoice_inbound_event') IS NOT NULL"
          + " AND to_regclass('einvoice_finding') IS NOT NULL"
          + " AND to_regclass('einvoice_issuance_anchor') IS NOT NULL"
          + " AND EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'einvoice_series_guard')"
          + " AND EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'einvoice_issuance_guard')"
          + " AND EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'einvoice_append_only')"
          + " AND EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'einvoice_anchor_monotonic')"
          + " AND EXISTS (SELECT 1 FROM pg_trigger"
          + "   WHERE tgname = 'einvoice_series_guard'"
          + "     AND tgrelid = to_regclass(quote_ident(current_schema()) || '.einvoice_series'))"
          + " AND EXISTS (SELECT 1 FROM pg_trigger"
          + "   WHERE tgname = 'einvoice_issuance_guard'"
          + "     AND tgrelid = to_regclass(quote_ident(current_schema()) || '.einvoice_issuance'))"
          + " AND EXISTS (SELECT 1 FROM pg_trigger"
          + "   WHERE tgname = 'einvoice_issuance_anchor_monotonic'"
          + "     AND tgrelid ="
          + "       to_regclass(quote_ident(current_schema()) || '.einvoice_issuance_anchor'))"
          + " AND EXISTS (SELECT 1 FROM pg_trigger"
          + "   WHERE tgname = 'einvoice_issuance_insert_guard'"
          + "     AND tgrelid = to_regclass(quote_ident(current_schema()) || '.einvoice_issuance'))"
          + " AND EXISTS (SELECT 1 FROM pg_trigger"
          + "   WHERE tgname = 'einvoice_series_insert_guard'"
          + "     AND tgrelid = to_regclass(quote_ident(current_schema()) || '.einvoice_series'))"
          + " AND EXISTS (SELECT 1 FROM pg_trigger"
          + "   WHERE tgname = 'einvoice_schema_guard'"
          + "     AND tgrelid = to_regclass(quote_ident(current_schema()) || '.einvoice_schema'))"
          // D-A1-C03: a database an unreleased build stamped at format 2 before the name table
          // existed must run the script again, and meet its EIU04 pre-check.
          + " AND to_regclass('einvoice_series_name') IS NOT NULL"
          + " AND EXISTS (SELECT 1 FROM pg_trigger"
          + "   WHERE tgname = 'einvoice_series_name_append_only'"
          + "     AND tgrelid ="
          + "       to_regclass(quote_ident(current_schema()) || '.einvoice_series_name'))"
          + " AND EXISTS (SELECT 1 FROM pg_trigger"
          + "   WHERE tgname = 'einvoice_series_name_no_truncate'"
          + "     AND tgrelid ="
          + "       to_regclass(quote_ident(current_schema()) || '.einvoice_series_name'))"
          + " AND EXISTS (SELECT 1 FROM pg_constraint"
          + "   WHERE conname = 'einvoice_series_name_fk' AND contype = 'f'"
          + "     AND conrelid = to_regclass(quote_ident(current_schema()) || '.einvoice_series'))"
          // D-PR52-3: a database stamped at format 2 before the orphan walk existed runs the script
          // again for its table and its lookup index.
          + " AND to_regclass('einvoice_orphan_cursor') IS NOT NULL"
          + " AND to_regclass('einvoice_issuance_archive_key') IS NOT NULL";

  /**
   * The schema format this version of the module writes and reads: 2 since 0.2.0 (typed series, the
   * corrected-invoice link and the series' latest issue date). 0.1.0 had no version row.
   */
  public static final int FORMAT_VERSION = 2;

  static final String READ_SCHEMA_VERSION =
      "SELECT format_version, min_compatible_version FROM einvoice_schema WHERE id = 1";

  /** This module's own SQLSTATEs raised by the schema script, mapped to {@code DEI-134}. */
  private static final java.util.Set<String> UPGRADE_REFUSALS =
      java.util.Set.of("EIU01", "EIU02", "EIU03", "EIU04");

  /** What the version row says. {@code 0} when the table exists without its row. */
  record SchemaVersion(boolean present, int formatVersion, int minCompatibleVersion) {

    static final SchemaVersion ABSENT = new SchemaVersion(false, 0, 0);

    boolean current() {
      return present && formatVersion == FORMAT_VERSION;
    }
  }

  /**
   * Runs the bundled PostgreSQL schema; idempotent, and serialised by its own advisory lock.
   *
   * <p><b>D1-10.</b> {@code einvoice.initialize-schema} defaults to {@code true}, and the
   * documented runtime role has no {@code CREATE} on the schema - by design, since that role must
   * not be able to alter its own guards. {@code CREATE TABLE IF NOT EXISTS} and {@code CREATE OR
   * REPLACE FUNCTION} both still require {@code CREATE} on the schema in PostgreSQL even when the
   * object already exists: the privilege is checked before the "if not exists"/"or replace" test
   * runs. So this reads before it writes: when every table, function and trigger this module owns
   * is already present, the DDL script never runs at all, and a fully migrated database needs no
   * privilege past {@code SELECT}/{@code INSERT}/{@code UPDATE} to start. A first bootstrap - where
   * something really is missing - still needs an owner-capable role, and a permission failure there
   * is reported by name rather than as a generic outage.
   */
  public static void initializeSchema(DataSource ds) {
    initializeSchema(ds, Optional.empty());
  }

  /**
   * As {@link #initializeSchema(DataSource)}, with the seller's tax zone, which the upgrade from
   * format 1 needs to record each series' latest issue date as a calendar date. The starter always
   * passes it; a fresh database needs none.
   *
   * <p><b>Format 2.</b> "Fully present" is now first a read of {@code einvoice_schema}: on a 0.1.0
   * database every table, function and trigger of 0.1.0 exists, so the old presence test passed and
   * the upgrade would silently never run (credit-note series design, section 5). Only {@code 42P01}
   * means "absent, run the script"; any other failure refuses startup with {@code DEI-133} (S-05),
   * and a newer format refuses with {@code DEI-135} before anything is written.
   */
  public static void initializeSchema(DataSource ds, Optional<ZoneId> taxZone) {
    SchemaVersion before = readSchemaVersion(ds);
    refuseIfNewer(before);
    if (before.current() && schemaFullyPresent(ds)) {
      return;
    }
    String sql;
    try (InputStream in =
        JdbcSupport.class.getResourceAsStream("/com/housedevinci/einvoice/schema-postgresql.sql")) {
      if (in == null) {
        throw new IllegalStateException("schema-postgresql.sql missing from classpath");
      }
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read schema", e);
    }
    try {
      inTransaction(
          ds,
          c -> {
            if (taxZone.isPresent()) {
              // A transaction-local setting the script reads for the backfill. Bound, never
              // concatenated: the value is configuration, and it still goes through a parameter.
              try (var ps = c.prepareStatement("SELECT set_config('einvoice.tax_zone', ?, true)")) {
                ps.setString(1, taxZone.get().getId());
                ps.execute();
              }
            }
            try (Statement st = c.createStatement()) {
              st.execute(sql);
            }
            return null;
          });
    } catch (EInvoiceException e) {
      if (e.getCause() instanceof SQLException se && UPGRADE_REFUSALS.contains(se.getSQLState())) {
        // S-06: a data condition, not an outage. The unavailability path would tell the operator
        // to retry, for ever, and nothing about the data would change. The script's own message
        // carries a count and no identifiers, so it is safe to repeat here.
        throw new EInvoiceException(
            ErrorCodes.SCHEMA_UPGRADE_REFUSED,
            "the schema upgrade to format "
                + FORMAT_VERSION
                + " refused to run and changed nothing (SQLState "
                + se.getSQLState()
                + "): "
                + firstLine(se.getMessage()),
            se);
      }
      if (e.getCause() instanceof SQLException se && "42501".equals(se.getSQLState())) {
        throw new EInvoiceException(
            ErrorCodes.CONFIG,
            "einvoice.initialize-schema=true but this database role has no CREATE privilege on the"
                + " schema (SQLState 42501). Either run the bundled schema once as an owner-capable"
                + " role and then run this application with only the grants in"
                + " docs/schema-grants.sql, or set einvoice.initialize-schema=false once the schema"
                + " is installed.",
            se);
      }
      throw e;
    }
    SchemaVersion after = readSchemaVersion(ds);
    if (!after.current()) {
      throw new EInvoiceException(
          ErrorCodes.SCHEMA_NOT_PREPARED,
          "the bundled schema script ran but the schema does not report format "
              + FORMAT_VERSION
              + "; refusing to start on a schema this module cannot vouch for");
    }
  }

  /**
   * For {@code einvoice.initialize-schema=false}: the schema must already be at this module's
   * format. An older one is refused by name ({@code DEI-136}) rather than used: format 1 has no
   * document type, and every numbering statement of this version would fail on it mid-run.
   */
  public static void verifySchema(DataSource ds) {
    SchemaVersion version = readSchemaVersion(ds);
    refuseIfNewer(version);
    if (!version.current()) {
      throw new EInvoiceException(
          ErrorCodes.SCHEMA_NOT_PREPARED,
          "einvoice.initialize-schema=false and the database schema is "
              + (version.present() ? "at format " + version.formatVersion() : "at format 1 (0.1.x)")
              + ", older than format "
              + FORMAT_VERSION
              + " which this version needs. Stop every instance of the older version, run the"
              + " bundled schema-postgresql.sql once as the schema owner with einvoice.tax_zone set"
              + " to the seller's tax zone, then start this version.");
    }
  }

  static SchemaVersion readSchemaVersion(DataSource ds) {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      try (var rs = st.executeQuery(READ_SCHEMA_VERSION)) {
        return rs.next()
            ? new SchemaVersion(true, rs.getInt(1), rs.getInt(2))
            : new SchemaVersion(true, 0, 0);
      }
    } catch (SQLException e) {
      if ("42P01".equals(e.getSQLState())) {
        return SchemaVersion.ABSENT;
      }
      // S-05: unreadable is not absent. Treating a permission error as "absent, run the script"
      // would attempt DDL as a role with no CREATE and then tell the operator to install a schema
      // that is already installed.
      throw new EInvoiceException(
          ErrorCodes.SCHEMA_VERSION_UNREADABLE,
          "the schema version row einvoice_schema could not be read (SQLState "
              + e.getSQLState()
              + "). Only a missing table means the schema is absent; anything else stops startup."
              + " If the SQLState is 42501, grant SELECT on einvoice_schema to this role (see"
              + " docs/schema-grants.sql).",
          e);
    }
  }

  private static void refuseIfNewer(SchemaVersion version) {
    if (version.formatVersion() > FORMAT_VERSION
        || version.minCompatibleVersion() > FORMAT_VERSION) {
      throw new EInvoiceException(
          ErrorCodes.SCHEMA_VERSION_UNSUPPORTED,
          "the database schema is at format "
              + version.formatVersion()
              + " (oldest compatible "
              + version.minCompatibleVersion()
              + "), newer than format "
              + FORMAT_VERSION
              + " which this version of the module understands. Run the newer version, or restore"
              + " the database it was upgraded from.");
    }
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "";
    }
    String line = message.lines().findFirst().orElse("");
    return line.startsWith("ERROR: ") ? line.substring("ERROR: ".length()) : line;
  }

  private static boolean schemaFullyPresent(DataSource ds) {
    return withConnection(
        ds,
        c -> {
          try (Statement st = c.createStatement();
              var rs = st.executeQuery(SCHEMA_FULLY_PRESENT)) {
            return rs.next() && rs.getBoolean(1);
          }
        });
  }

  /**
   * Refuses a server that is not PostgreSQL.
   *
   * <p>It probes the <em>server</em> - the JDBC metadata product name, then {@code SELECT
   * version()} - and never a configured dialect string (N-09). In the one scenario where this check
   * matters, the configured string is the thing that is wrong: an application pointed at MySQL with
   * a PostgreSQL dialect still configured would pass a property check and then run {@code UPDATE
   * ... RETURNING} against a server that does not have it.
   */
  public static void requirePostgreSql(DataSource ds) {
    String product =
        withConnection(
            ds,
            c -> {
              DatabaseMetaData metaData = c.getMetaData();
              String name = metaData == null ? "" : metaData.getDatabaseProductName();
              if (name != null && name.toLowerCase(Locale.ROOT).contains("postgres")) {
                try (Statement st = c.createStatement();
                    var rs = st.executeQuery("SELECT version()")) {
                  if (rs.next()) {
                    String version = rs.getString(1);
                    if (version != null
                        && version.toLowerCase(Locale.ROOT).contains("postgresql")) {
                      return "postgresql";
                    }
                  }
                }
              }
              return name == null ? "" : name;
            });
    if (!"postgresql".equals(product)) {
      throw new EInvoiceException(
          ErrorCodes.UNSUPPORTED_DATABASE,
          "this module requires PostgreSQL: the allocator's UPDATE ... RETURNING, the append-only"
              + " triggers and the advisory locks have no portable equivalent, and degrading them"
              + " silently would degrade a legal numbering series. The server reported a product"
              + " name that is not PostgreSQL.");
    }
  }

  /** True when the current database role owns a table and could therefore disable its triggers. */
  public static boolean runtimeRoleOwnsTable(DataSource ds, String table) {
    return withConnection(
        ds,
        c -> {
          try (var ps =
              c.prepareStatement(
                  "SELECT tableowner = current_user FROM pg_tables WHERE tablename = ?")) {
            ps.setString(1, table);
            try (var rs = ps.executeQuery()) {
              return rs.next() && rs.getBoolean(1);
            }
          }
        });
  }

  /**
   * A database failure is an <em>outage</em>, never a business outcome. The caller answers 503 and
   * the health indicator goes DOWN; nothing about a number is concluded from it.
   *
   * <p>Only the SQLState is kept: a driver message can carry a bind value, and a bind value on this
   * path can be a buyer's own identifier.
   */
  static EInvoiceException unavailable(SQLException cause) {
    return new EInvoiceException(
        ErrorCodes.STORE_UNAVAILABLE,
        "the e-invoice store is unavailable (SQLState " + cause.getSQLState() + ")",
        cause);
  }
}
