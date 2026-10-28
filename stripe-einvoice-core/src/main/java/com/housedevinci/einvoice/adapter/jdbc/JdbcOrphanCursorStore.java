package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.instant;
import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.ts;

import com.housedevinci.einvoice.application.ArchiveListing;
import com.housedevinci.einvoice.application.OrphanCursor;
import com.housedevinci.einvoice.application.OrphanCursorStore;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.Timestamps;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * The orphan walk's saved position in PostgreSQL ({@code einvoice_orphan_cursor}, D-PR52-3). Every
 * statement carries the seller and the mode.
 *
 * <p>The position is stored as the raw UTF-8 bytes of the key ({@code bytea}): {@code text} refuses
 * a NUL, and an S3 key may carry any code point. The compare-and-set is on the revision, which
 * every change moves by one. The first insert is {@code ON CONFLICT DO NOTHING}: a second instance
 * that inserts at the same moment learns it lost from the row count, never from an error that would
 * abort a host transaction it runs in.
 */
public final class JdbcOrphanCursorStore implements OrphanCursorStore {

  static final String READ =
      "SELECT cursor_key, store_prefix, cycle_started_at, last_complete_cycle_at,"
          + " last_cycle_keys, keys_this_cycle, revision FROM einvoice_orphan_cursor"
          + " WHERE seller_id = ? AND mode = ?";

  static final String INSERT =
      "INSERT INTO einvoice_orphan_cursor (seller_id, mode, cursor_key, store_prefix,"
          + " cycle_started_at, last_complete_cycle_at, last_cycle_keys, keys_this_cycle, revision)"
          + " VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (seller_id, mode) DO NOTHING";

  static final String REPLACE =
      "UPDATE einvoice_orphan_cursor SET cursor_key = ?, store_prefix = ?, cycle_started_at = ?,"
          + " last_complete_cycle_at = ?, last_cycle_keys = ?, keys_this_cycle = ?, revision = ?"
          + " WHERE seller_id = ? AND mode = ? AND revision = ?";

  /**
   * An issued row with a key: served by the partial index on {@code (seller_id, mode,
   * archive_key)}. Issued only: a row marked for archiving whose write never happened has a key and
   * no object, and must not make an empty archive look wrong.
   */
  static final String HAS_ARCHIVED =
      "SELECT EXISTS (SELECT 1 FROM einvoice_issuance WHERE seller_id = ? AND mode = ?"
          + " AND archive_key <> '' AND state = 'ISSUED')";

  private final JdbcUnitOfWork unitOfWork;

  public JdbcOrphanCursorStore(JdbcUnitOfWork unitOfWork) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Optional<OrphanCursor> read(String sellerId, Mode mode) {
    return unitOfWork.inReadUnit(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(READ)) {
            ps.setString(1, sellerId);
            ps.setString(2, mode.wire());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                return Optional.empty();
              }
              return Optional.of(
                  new OrphanCursor(
                      sellerId,
                      mode,
                      new String(rs.getBytes("cursor_key"), StandardCharsets.UTF_8),
                      rs.getString("store_prefix"),
                      instant(rs.getObject("cycle_started_at", OffsetDateTime.class)),
                      Optional.ofNullable(
                          instant(rs.getObject("last_complete_cycle_at", OffsetDateTime.class))),
                      rs.getLong("last_cycle_keys"),
                      rs.getLong("keys_this_cycle"),
                      rs.getLong("revision")));
            }
          }
        });
  }

  @Override
  public boolean insert(OrphanCursor cursor) {
    return unitOfWork.inTransaction(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(INSERT)) {
            int i = 1;
            ps.setString(i++, cursor.sellerId());
            ps.setString(i++, cursor.mode().wire());
            i = bindState(ps, i, cursor);
            ps.setLong(i, cursor.revision());
            return ps.executeUpdate() == 1;
          }
        });
  }

  @Override
  public boolean replace(OrphanCursor expected, OrphanCursor next) {
    if (!expected.sellerId().equals(next.sellerId()) || expected.mode() != next.mode()) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "an orphan cursor replaces its own seller and mode only");
    }
    return unitOfWork.inTransaction(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(REPLACE)) {
            int i = bindState(ps, 1, next);
            ps.setLong(i++, next.revision());
            ps.setString(i++, expected.sellerId());
            ps.setString(i++, expected.mode().wire());
            ps.setLong(i, expected.revision());
            return ps.executeUpdate() == 1;
          }
        });
  }

  @Override
  public boolean hasArchivedDocuments(String sellerId, Mode mode) {
    return unitOfWork.inReadUnit(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(HAS_ARCHIVED)) {
            ps.setString(1, sellerId);
            ps.setString(2, mode.wire());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return rs.getBoolean(1);
            }
          }
        });
  }

  private static int bindState(PreparedStatement ps, int start, OrphanCursor cursor)
      throws java.sql.SQLException {
    int i = start;
    ps.setBytes(i++, ArchiveListing.utf8(cursor.position()));
    ps.setString(i++, cursor.storeIdentity());
    ps.setObject(i++, ts(Timestamps.toStorage(cursor.cycleStartedAt())));
    if (cursor.lastCompleteCycleAt().isPresent()) {
      ps.setObject(i++, ts(Timestamps.toStorage(cursor.lastCompleteCycleAt().get())));
    } else {
      ps.setNull(i++, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
    }
    ps.setLong(i++, cursor.lastCycleKeys());
    ps.setLong(i++, cursor.keysThisCycle());
    return i;
  }
}
