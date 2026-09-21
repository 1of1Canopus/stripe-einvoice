package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.instant;
import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.ts;

import com.housedevinci.einvoice.application.ReprocessLedger;
import com.housedevinci.einvoice.application.ReprocessRequest;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * The reprocess record in PostgreSQL: two inserts, no update, ever.
 *
 * <p>The re-open and its {@code REQUESTED} row are one {@code inTransaction} call - the whole point
 * of the mechanism - and the conditional {@code UPDATE} is the same statement the inbound store
 * declares, so there is one definition of what "still eligible" means.
 */
public final class JdbcReprocessLedger implements ReprocessLedger {

  private static final String INSERT_REQUESTED =
      "INSERT INTO einvoice_reprocess_request (kind, event_id, seller_id, mode, actor, reason, at)"
          + " VALUES ('REQUESTED', ?, ?, ?, ?, ?, ?)";

  private static final String INSERT_CONCLUDED =
      "INSERT INTO einvoice_reprocess_request (kind, request_seq, event_id, seller_id, mode, at,"
          + " outcome_state, outcome_code, legal_number) VALUES ('CONCLUDED', ?,?,?,?,?,?,?,?)";

  private static final String COLUMNS =
      "seq, kind, request_seq, event_id, seller_id, mode, actor, reason, at, outcome_state,"
          + " outcome_code, legal_number";

  private static final String BY_EVENT =
      "SELECT " + COLUMNS + " FROM einvoice_reprocess_request WHERE event_id = ? ORDER BY seq";

  private static final String UNFINISHED =
      "SELECT "
          + COLUMNS
          + " FROM einvoice_reprocess_request r WHERE r.kind = 'REQUESTED' AND r.at < ?"
          + " AND NOT EXISTS (SELECT 1 FROM einvoice_reprocess_request c"
          + "   WHERE c.kind = 'CONCLUDED' AND c.request_seq = r.seq)"
          + " ORDER BY r.seq LIMIT ?";

  private final JdbcUnitOfWork unitOfWork;

  public JdbcReprocessLedger(JdbcUnitOfWork unitOfWork) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public OptionalLong reopenAndRecord(
      ReprocessRequest request, String sellerId, Mode mode, String code, Instant now) {
    return unitOfWork.inTransaction(
        unit -> {
          Connection c = unit.connection();
          Instant at = Timestamps.toStorage(now);
          try (PreparedStatement reopen =
              c.prepareStatement(JdbcInboundEventStore.REOPEN_FOR_REPROCESS)) {
            reopen.setString(1, code == null ? "" : code);
            reopen.setObject(2, ts(at));
            reopen.setString(3, request.eventId());
            if (reopen.executeUpdate() != 1) {
              return OptionalLong.empty();
            }
          }
          try (PreparedStatement record =
              c.prepareStatement(INSERT_REQUESTED, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            record.setString(i++, request.eventId());
            record.setString(i++, sellerId);
            record.setString(i++, mode.wire());
            record.setString(i++, request.actor());
            record.setString(i++, request.reason());
            record.setObject(i, ts(at));
            record.executeUpdate();
            try (ResultSet keys = record.getGeneratedKeys()) {
              if (!keys.next()) {
                // Unreachable with a bigserial primary key; never silently returning "no record"
                // for a row that was just re-opened is the whole property.
                throw new EInvoiceException(
                    ErrorCodes.STORE_UNAVAILABLE,
                    "the reprocess record was written without returning its sequence");
              }
              return OptionalLong.of(keys.getLong(1));
            }
          }
        });
  }

  @Override
  public void conclude(long requestSeq, ReprocessRecord.Outcome outcome, Instant now) {
    unitOfWork.inTransaction(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(INSERT_CONCLUDED)) {
            int i = 1;
            ps.setLong(i++, requestSeq);
            ps.setString(i++, outcome.eventId());
            ps.setString(i++, outcome.sellerId());
            ps.setString(i++, outcome.mode().wire());
            ps.setObject(i++, ts(Timestamps.toStorage(now)));
            ps.setString(i++, outcome.state().map(InboundState::name).orElse(""));
            ps.setString(i++, outcome.code());
            ps.setString(i, outcome.legalNumber());
            ps.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public List<ReprocessRecord> forEvent(String eventId) {
    return unitOfWork.inReadUnit(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(BY_EVENT)) {
            ps.setString(1, eventId);
            return readAll(ps);
          }
        });
  }

  @Override
  public List<ReprocessRecord> unfinished(Instant olderThan, int limit) {
    return unitOfWork.inReadUnit(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(UNFINISHED)) {
            ps.setObject(1, ts(Timestamps.toStorage(olderThan)));
            ps.setInt(2, Math.max(1, limit));
            return readAll(ps);
          }
        });
  }

  private static List<ReprocessRecord> readAll(PreparedStatement ps) throws SQLException {
    List<ReprocessRecord> rows = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        long requestSeq = rs.getLong(3);
        // wasNull() reports on the column just read, so it is asked before anything else is.
        OptionalLong request = rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(requestSeq);
        rows.add(
            new ReprocessRecord(
                rs.getLong(1),
                rs.getString(2),
                request,
                rs.getString(4),
                rs.getString(5),
                Mode.of(rs.getString(6)),
                rs.getString(7),
                rs.getString(8),
                instant(rs.getObject(9, java.time.OffsetDateTime.class)),
                rs.getString(10),
                rs.getString(11),
                rs.getString(12)));
      }
    }
    return List.copyOf(rows);
  }
}
