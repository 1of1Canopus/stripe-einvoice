package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Refuses to start when a database <b>view</b> reads one of this module's tables.
 *
 * <p>The metamodel scan cannot see this and never will: the host maps an entity to {@code
 * v_invoices}, Hibernate is told about {@code v_invoices}, the scan says clean, and the host reads
 * our rows through it. Writes are less interesting - an auto-updatable view is rewritten to the
 * base table, so the BEFORE ROW triggers still fire - but a read path into a legal ledger, inside
 * an application that also has its own users and its own query paths, is not a boundary we own.
 *
 * <p>So the question is asked of the database rather than of the mapping - but not of {@code
 * information_schema.view_table_usage} (D1-07): that view filters its rows with {@code
 * pg_has_role(owner, 'USAGE')} on the <b>table's</b> owner, and this module's own grant
 * documentation tells the operator to run the application as a role that does <b>not</b> own these
 * tables (the right advice, since an owner can disable the triggers). In that deployment - the one
 * the docs recommend - the information_schema query returns nothing and the guard reports clean
 * while a host view keeps reading the ledger. This queries {@code pg_depend}/{@code pg_rewrite}/
 * {@code pg_class} instead: every role can read the catalog, regardless of who owns what it
 * describes, so the guard sees the same views whichever role runs it.
 *
 * <p>Any failure to run the query is itself a refusal. A guard that cannot see is not a guard that
 * found nothing.
 */
public final class DatabaseViewGuard {

  // A view (or materialized view) "reads" a table when its defining rule depends on that table.
  // pg_depend records that dependency directly: the rule that implements the view (classid
  // pg_rewrite) depends on the table (refclassid pg_class). Every role can read pg_catalog, so this
  // sees the same views regardless of who owns the view or the table (D1-07) - the ownership-
  // filtered information_schema view this replaces could not make that promise.
  private static final String VIEWS_OVER_OUR_TABLES =
      "SELECT DISTINCT n.nspname AS view_schema, v.relname AS view_name, t.relname AS table_name"
          + " FROM pg_depend d"
          + " JOIN pg_rewrite r ON r.oid = d.objid AND d.classid = 'pg_rewrite'::regclass"
          + " JOIN pg_class v ON v.oid = r.ev_class AND v.relkind IN ('v', 'm')"
          + " JOIN pg_namespace n ON n.oid = v.relnamespace"
          + " JOIN pg_class t ON t.oid = d.refobjid AND d.refclassid = 'pg_class'::regclass"
          + " WHERE t.relname = ANY (?)"
          + " ORDER BY 1, 2";

  private DatabaseViewGuard() {}

  public static void refuseViewsOverOurTables(DataSource dataSource) {
    List<String> views = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(VIEWS_OVER_OUR_TABLES)) {
      ps.setArray(1, c.createArrayOf("varchar", EInvoiceTables.ALL.toArray(new String[0])));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          views.add(rs.getString(1) + "." + rs.getString(2) + " reads " + rs.getString(3));
        }
      }
    } catch (SQLException e) {
      throw new EInvoiceException(
          ErrorCodes.PERSISTENCE_MAPPING_REFUSED,
          "this module could not check whether a database view reads its tables (SQLState "
              + e.getSQLState()
              + "). Unverifiable is not clean, so it refuses to start.",
          e);
    }
    if (!views.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.PERSISTENCE_MAPPING_REFUSED,
          "a database view reads a table this module owns: "
              + String.join(", ", views)
              + ". A view is the one way an entity mapping can reach these rows while the"
              + " persistence-mapping scan reports nothing, so it is refused at the same door.");
    }
  }
}
