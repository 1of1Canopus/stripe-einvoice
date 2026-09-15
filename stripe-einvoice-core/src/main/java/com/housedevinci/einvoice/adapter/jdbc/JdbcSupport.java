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
import java.time.ZoneOffset;
import java.util.Locale;
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
          + "       to_regclass(quote_ident(current_schema()) || '.einvoice_issuance_anchor'))";

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
    if (schemaFullyPresent(ds)) {
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
            try (Statement st = c.createStatement()) {
              st.execute(sql);
            }
            return null;
          });
    } catch (EInvoiceException e) {
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
