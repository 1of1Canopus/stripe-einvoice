package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.instant;
import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.ts;

import com.housedevinci.einvoice.application.FindingStore;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.ScreenedText;
import com.housedevinci.einvoice.domain.Timestamps;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The findings list in PostgreSQL. Every statement carries the seller and the mode. */
public final class JdbcFindingStore implements FindingStore {

  static final String UPSERT =
      "INSERT INTO einvoice_finding (seller_id, mode, code, subject_id, first_seen, last_seen)"
          + " VALUES (?,?,?,?,?,?)"
          + " ON CONFLICT (seller_id, mode, code, subject_id) DO UPDATE"
          + " SET last_seen = EXCLUDED.last_seen";

  static final String ACKNOWLEDGE =
      "UPDATE einvoice_finding SET acknowledged_at = ?, ack_reason = ?"
          + " WHERE seller_id = ? AND mode = ? AND code = ? AND subject_id = ?";

  static final String OPEN =
      "SELECT seller_id, mode, code, subject_id, first_seen, last_seen, acknowledged_at,"
          + " ack_reason FROM einvoice_finding"
          + " WHERE seller_id = ? AND mode = ? AND acknowledged_at IS NULL"
          + " ORDER BY first_seen LIMIT ?";

  static final String OPEN_COUNTS =
      "SELECT code, count(*) FROM einvoice_finding"
          + " WHERE seller_id = ? AND mode = ? AND acknowledged_at IS NULL"
          + " GROUP BY code ORDER BY code";

  private final JdbcUnitOfWork unitOfWork;

  public JdbcFindingStore(JdbcUnitOfWork unitOfWork) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public void record(ComplianceFinding finding) {
    unitOfWork.inTransaction(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(UPSERT)) {
            int i = 1;
            ps.setString(i++, finding.sellerId());
            ps.setString(i++, finding.mode().wire());
            ps.setString(i++, finding.code());
            ps.setString(i++, finding.subjectId());
            ps.setObject(i++, ts(finding.firstSeen()));
            ps.setObject(i, ts(finding.lastSeen()));
            ps.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public void acknowledge(
      String sellerId, Mode mode, String code, String subjectId, String reason, Instant when) {
    // Screened like every other free text that reaches an auditor-facing report, and mandatory: an
    // acknowledgement with no reason is a delete with extra steps.
    String screened =
        ScreenedText.screen("acknowledgement reason", reason, ComplianceFinding.MAX_REASON_CHARS);
    unitOfWork.inTransaction(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(ACKNOWLEDGE)) {
            int i = 1;
            ps.setObject(i++, ts(Timestamps.toStorage(when)));
            ps.setString(i++, screened);
            ps.setString(i++, sellerId);
            ps.setString(i++, mode.wire());
            ps.setString(i++, code);
            ps.setString(i, subjectId);
            if (ps.executeUpdate() == 0) {
              throw new EInvoiceException(
                  ErrorCodes.INVALID, "no open finding matches that code and subject");
            }
          }
          return null;
        });
  }

  @Override
  public List<ComplianceFinding> open(String sellerId, Mode mode, int limit) {
    return unitOfWork.inReadUnit(
        unit -> {
          List<ComplianceFinding> findings = new ArrayList<>();
          try (PreparedStatement ps = unit.connection().prepareStatement(OPEN)) {
            ps.setString(1, sellerId);
            ps.setString(2, mode.wire());
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                findings.add(
                    new ComplianceFinding(
                        rs.getString("seller_id"),
                        Mode.of(rs.getString("mode")),
                        rs.getString("code"),
                        rs.getString("subject_id"),
                        instant(rs.getObject("first_seen", OffsetDateTime.class)),
                        instant(rs.getObject("last_seen", OffsetDateTime.class)),
                        instant(rs.getObject("acknowledged_at", OffsetDateTime.class)),
                        rs.getString("ack_reason")));
              }
            }
          }
          return List.copyOf(findings);
        });
  }

  @Override
  public List<CodeCount> openCounts(String sellerId, Mode mode) {
    return unitOfWork.inReadUnit(
        unit -> {
          List<CodeCount> counts = new ArrayList<>();
          try (PreparedStatement ps = unit.connection().prepareStatement(OPEN_COUNTS)) {
            ps.setString(1, sellerId);
            ps.setString(2, mode.wire());
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                counts.add(new CodeCount(rs.getString(1), rs.getLong(2)));
              }
            }
          }
          return List.copyOf(counts);
        });
  }
}
