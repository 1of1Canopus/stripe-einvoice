package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SQL of the issuance row lock (D-RTR-7): session-scoped, two-argument, tried and never waited
 * for, and released by hand. Every function is called through {@code pg_catalog}, so a shadowing
 * function earlier on the search path cannot answer in its place.
 */
final class IssuanceRowLocks {

  private static final Logger log = LoggerFactory.getLogger(IssuanceRowLocks.class);

  /**
   * One statement, so one backend: is the key granted to anyone (this backend included), and if
   * not, try it. The "anyone" half matters under a pooler that hands this client the backend that
   * already holds the lock: session advisory locks are re-entrant, so a bare try would succeed
   * there and the lock would be no lock.
   */
  static final String TRY =
      "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_catalog.pg_locks l"
          + " WHERE l.locktype = 'advisory' AND l.granted AND l.objsubid = 2"
          + " AND l.classid = CAST(? AS bigint)::oid AND l.objid = CAST(? AS bigint)::oid)"
          + " THEN false ELSE pg_catalog.pg_try_advisory_lock(?, ?) END,"
          + " pg_catalog.pg_backend_pid()";

  static final String UNLOCK =
      "SELECT pg_catalog.pg_advisory_unlock(?, ?), pg_catalog.pg_backend_pid()";

  static final String EVICT = "SELECT pg_catalog.pg_terminate_backend(pg_catalog.pg_backend_pid())";

  private IssuanceRowLocks() {}

  /** The second key: the low 32 bits of the row id. Ids 2^32 apart collide, which only retries. */
  static int rowKey(long issuanceId) {
    return (int) issuanceId;
  }

  /** The backend pid that now holds the lock, or empty when someone holds it already. */
  static Optional<Integer> tryLock(Connection c, long issuanceId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(TRY)) {
      ps.setLong(1, AdvisoryLocks.ROW_CLASS_ID & 0xFFFFFFFFL);
      ps.setLong(2, issuanceId & 0xFFFFFFFFL);
      ps.setInt(3, AdvisoryLocks.ROW_CLASS_ID);
      ps.setInt(4, rowKey(issuanceId));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1) ? Optional.of(rs.getInt(2)) : Optional.empty();
      }
    }
  }

  /**
   * Releases the lock. True when it was released by the backend that took it. False, or a throw,
   * means the lock may still be held by a session this module no longer controls, and the caller
   * evicts the connection.
   */
  static boolean unlock(Connection c, long issuanceId, int holderPid) {
    try (PreparedStatement ps = c.prepareStatement(UNLOCK)) {
      ps.setInt(1, AdvisoryLocks.ROW_CLASS_ID);
      ps.setInt(2, rowKey(issuanceId));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1) && rs.getInt(2) == holderPid;
      }
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * Ends the session, which releases every session lock it holds, and leaves the connection broken
   * so that no pool hands it out again: the server terminates the backend, and the driver and the
   * pool see a connection that is gone. Logged at ERROR with its code; never thrown, because it
   * runs in a finally block and must not replace the outcome already in hand.
   */
  static void evict(Connection c, long issuanceId) {
    log.error(
        "einvoice: the archive lock of issuance row {} could not be released on its own session;"
            + " the connection is closed instead of pooled ({})",
        issuanceId,
        ErrorCodes.SESSION_NOT_PINNED);
    try (Statement st = c.createStatement()) {
      st.execute(EVICT);
    } catch (SQLException expected) {
      // The backend terminated itself: this is the success case.
    }
    try {
      c.abort(Runnable::run);
    } catch (SQLException | RuntimeException ignored) {
      // Already gone.
    }
  }

  /**
   * R5: the lock needs every statement of its holder on one server session. A pooler in transaction
   * or statement mode breaks that silently, so this refuses it with {@code DEI-104}.
   *
   * <p>How it tells. Connection one reads its backend pid and ends that transaction. Connection two
   * then opens a transaction and reads its pid while connection one is still open. Connection one
   * reads its pid again. On a direct connection or a session pooler the two connections are two
   * sessions, so the second pid differs from the first, and the first connection keeps its pid. A
   * transaction pooler hands the server connection that connection one just released to connection
   * two (the same pid) or hands connection one another one (a new pid). A session setting written
   * on connection one must also read back on it.
   */
  static void verifySessionPinning(DataSource dataSource) {
    String nonce = UUID.randomUUID().toString();
    try (Connection one = dataSource.getConnection()) {
      one.setAutoCommit(true);
      int first;
      try (PreparedStatement ps =
          one.prepareStatement(
              "SELECT pg_catalog.pg_backend_pid(),"
                  + " pg_catalog.set_config('einvoice.session_probe', ?, false)")) {
        ps.setString(1, nonce);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          first = rs.getInt(1);
        }
      }
      int other;
      try (Connection two = dataSource.getConnection()) {
        two.setAutoCommit(false);
        try (Statement st = two.createStatement();
            ResultSet rs = st.executeQuery("SELECT pg_catalog.pg_backend_pid()")) {
          rs.next();
          other = rs.getInt(1);
        }
        int again;
        String setting;
        try (Statement st = one.createStatement();
            ResultSet rs =
                st.executeQuery(
                    "SELECT pg_catalog.pg_backend_pid(),"
                        + " pg_catalog.current_setting('einvoice.session_probe', true)")) {
          rs.next();
          again = rs.getInt(1);
          setting = rs.getString(2);
        }
        two.rollback();
        if (other == first || again != first || !nonce.equals(setting)) {
          throw new EInvoiceException(
              ErrorCodes.SESSION_NOT_PINNED,
              "the database connections do not keep one server session: a pooler in transaction"
                  + " or statement mode (PgBouncer, for example) is between this application and"
                  + " PostgreSQL. The issuance row lock is a session lock and would silently be no"
                  + " lock at all, so nothing is issued. Use session pooling or a direct"
                  + " connection.");
        }
      }
    } catch (SQLException e) {
      throw JdbcSupport.unavailable(e);
    }
  }
}
