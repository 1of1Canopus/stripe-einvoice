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
 * <p>So the question is asked of the database rather than of the mapping: {@code
 * information_schema.view_table_usage} names every view that reads a given table, whoever created
 * it and whether or not anything maps it.
 *
 * <p>Any failure to run the query is itself a refusal. A guard that cannot see is not a guard that
 * found nothing.
 */
public final class DatabaseViewGuard {

  private static final String VIEWS_OVER_OUR_TABLES =
      "SELECT view_schema, view_name, table_name FROM information_schema.view_table_usage"
          + " WHERE table_name = ANY (?) ORDER BY view_schema, view_name";

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
