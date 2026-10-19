package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.application.ReconciliationSweep;
import com.housedevinci.einvoice.domain.DateGapCause;
import com.housedevinci.einvoice.domain.SeriesScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * The cause deriver (design 5.6): why an invoice was numbered on a later day than its supply.
 *
 * <p>The only class that turns stored history into a {@link DateGapCause}, and it reads stored
 * history only, inside the allocation transaction: every inbound row recorded for the invoice, its
 * park and retry marks, its source, and the reprocess ledger. Nothing a caller passes takes part,
 * which an ArchUnit rule holds. Precedence when several apply: {@code REPROCESS}, {@code SWEEP},
 * {@code PARK}, {@code RETRY}, {@code LATE_WEBHOOK}, then {@code UNRECORDED}.
 */
final class JdbcDateGapCauses {

  static final String HISTORY =
      "SELECT EXISTS (SELECT 1 FROM einvoice_reprocess_request r"
          + "   WHERE r.kind = 'REQUESTED' AND r.seller_id = ? AND r.mode = ?"
          + "   AND r.event_id IN (SELECT e.event_id FROM einvoice_inbound_event e"
          + "     WHERE e.seller_id = ? AND e.mode = ? AND e.object_id = ?)) AS reprocessed,"
          + " coalesce(bool_or(signature_key_id = ?), false) AS swept,"
          + " coalesce(bool_or(parked_at IS NOT NULL), false) AS parked,"
          + " coalesce(bool_or(retried_at IS NOT NULL), false) AS retried,"
          + " min(received_at) AS first_received"
          + " FROM einvoice_inbound_event"
          + " WHERE seller_id = ? AND mode = ? AND object_id = ?";

  private JdbcDateGapCauses() {}

  /**
   * @param daysLate whole days from the supply to the issue; zero means {@link DateGapCause#NONE}
   * @param supplyDay the supply day in the tax zone, to tell a late webhook from a late run
   */
  static DateGapCause derive(
      Connection c,
      SeriesScope scope,
      String stripeInvoiceId,
      long daysLate,
      LocalDate supplyDay,
      ZoneId taxZone)
      throws SQLException {
    if (daysLate <= 0) {
      return DateGapCause.NONE;
    }
    try (PreparedStatement ps = c.prepareStatement(HISTORY)) {
      int i = 1;
      ps.setString(i++, scope.sellerId());
      ps.setString(i++, scope.mode().wire());
      ps.setString(i++, scope.sellerId());
      ps.setString(i++, scope.mode().wire());
      ps.setString(i++, stripeInvoiceId);
      ps.setString(i++, ReconciliationSweep.RECONCILIATION_SOURCE);
      ps.setString(i++, scope.sellerId());
      ps.setString(i++, scope.mode().wire());
      ps.setString(i, stripeInvoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        if (rs.getBoolean("reprocessed")) {
          return DateGapCause.REPROCESS;
        }
        if (rs.getBoolean("swept")) {
          return DateGapCause.SWEEP;
        }
        if (rs.getBoolean("parked")) {
          return DateGapCause.PARK;
        }
        if (rs.getBoolean("retried")) {
          return DateGapCause.RETRY;
        }
        OffsetDateTime first = rs.getObject("first_received", OffsetDateTime.class);
        if (first != null && LocalDate.ofInstant(first.toInstant(), taxZone).isAfter(supplyDay)) {
          return DateGapCause.LATE_WEBHOOK;
        }
        return DateGapCause.UNRECORDED;
      }
    }
  }
}
