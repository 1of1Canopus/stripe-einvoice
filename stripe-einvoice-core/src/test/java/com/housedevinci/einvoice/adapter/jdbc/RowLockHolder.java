package com.housedevinci.einvoice.adapter.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Another worker's hold on an issuance row lock, taken on its own connection, for the tests. */
public final class RowLockHolder implements AutoCloseable {

  private final Connection connection;
  private final long issuanceId;

  private RowLockHolder(Connection connection, long issuanceId) {
    this.connection = connection;
    this.issuanceId = issuanceId;
  }

  public static RowLockHolder hold(DataSource dataSource, long issuanceId) {
    try {
      Connection c = dataSource.getConnection();
      if (IssuanceRowLocks.tryLock(c, issuanceId).isEmpty()) {
        c.close();
        throw new IllegalStateException("the test could not take the row lock");
      }
      return new RowLockHolder(c, issuanceId);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The backend pid that holds the lock of {@code issuanceId}, or -1 when nobody holds it. */
  public static int holderPid(DataSource dataSource, long issuanceId) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND granted"
                    + " AND objsubid = 2 AND classid = CAST(? AS bigint)::oid"
                    + " AND objid = CAST(? AS bigint)::oid")) {
      ps.setLong(1, AdvisoryLocks.ROW_CLASS_ID & 0xFFFFFFFFL);
      ps.setLong(2, issuanceId & 0xFFFFFFFFL);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : -1;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    try (PreparedStatement ps = connection.prepareStatement(IssuanceRowLocks.UNLOCK)) {
      ps.setInt(1, AdvisoryLocks.ROW_CLASS_ID);
      ps.setInt(2, IssuanceRowLocks.rowKey(issuanceId));
      ps.execute();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    } finally {
      try {
        connection.close();
      } catch (SQLException ignored) {
        // closing a test connection
      }
    }
  }
}
