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

  /** The one session setting that carries the current acquisition token (C2: one per session). */
  static final String TOKEN_SETTING = "einvoice.row_lock";

  private static final String HELD_BY_ANYONE =
      "EXISTS (SELECT 1 FROM pg_catalog.pg_locks l"
          + " WHERE l.locktype = 'advisory' AND l.granted AND l.objsubid = 2"
          + " AND l.classid = CAST(? AS bigint)::oid AND l.objid = CAST(? AS bigint)::oid"
          // D-PR42-3: advisory locks belong to one database; another database's row is not ours.
          + " AND l.database = (SELECT d.oid FROM pg_catalog.pg_database d"
          + " WHERE d.datname = pg_catalog.current_database())";

  /**
   * One statement, so one backend: is the key granted to anyone (this backend included), and if
   * not, try it; only when it got the lock, write this acquisition's token into the session
   * (Revision 3, D-PR42-2). The subquery is not flattened (a volatile target), so the lock is tried
   * once.
   */
  static final String TRY =
      "SELECT t.got, CASE WHEN t.got THEN pg_catalog.set_config('"
          + TOKEN_SETTING
          + "', ?, false) END FROM (SELECT CASE WHEN "
          + HELD_BY_ANYONE
          + ") THEN false ELSE pg_catalog.pg_try_advisory_lock(?, ?) END AS got) t";

  /**
   * P1: the session still carries this acquisition's token AND this backend holds the key. Never
   * NULL (C3): a session that never set the token answers false. The pid is never compared: a
   * DISCARD ALL wipes the token, a client that re-takes the key writes its own, and a new backend
   * with a reused pid has none.
   */
  static final String PROOF =
      "(pg_catalog.current_setting('"
          + TOKEN_SETTING
          + "', true) = ? AND "
          + HELD_BY_ANYONE.replace(
              "WHERE d.datname = pg_catalog.current_database())",
              "WHERE d.datname = pg_catalog.current_database())"
                  + " AND l.pid = pg_catalog.pg_backend_pid()")
          + ")) IS TRUE";

  /** P2: one statement that frees the key only when the proof holds, and clears the token. */
  static final String UNLOCK =
      "SELECT CASE WHEN "
          + PROOF
          + " THEN pg_catalog.pg_advisory_unlock(?, ?) AND pg_catalog.set_config('"
          + TOKEN_SETTING
          + "', '', false) = '' ELSE false END";

  static final String EVICT = "SELECT pg_catalog.pg_terminate_backend(pg_catalog.pg_backend_pid())";

  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

  private IssuanceRowLocks() {}

  /** The second key: the low 32 bits of the row id. Ids 2^32 apart collide, which only retries. */
  static int rowKey(long issuanceId) {
    return (int) issuanceId;
  }

  private static int bindKey(PreparedStatement ps, int from, long issuanceId) throws SQLException {
    ps.setLong(from, AdvisoryLocks.ROW_CLASS_ID & 0xFFFFFFFFL);
    ps.setLong(from + 1, issuanceId & 0xFFFFFFFFL);
    return from + 2;
  }

  private static String value(long issuanceId, String token) {
    return issuanceId + ":" + token;
  }

  /** Takes the lock as an own-connection holder: the TRY commits on its own (C1). */
  static Optional<String> tryLock(Connection c, long issuanceId) throws SQLException {
    return tryLock(c, issuanceId, false);
  }

  /**
   * The acquisition token when this session now holds the lock, or empty when someone holds it.
   *
   * @param joined true inside a caller's transaction: the TRY and the UNLOCK both run in it, so it
   *     is not committed here. Otherwise the TRY commits at once (C1): a later rollback must not
   *     undo the token, because it does not undo the lock.
   */
  static Optional<String> tryLock(Connection c, long issuanceId, boolean joined)
      throws SQLException {
    byte[] raw = new byte[16];
    RANDOM.nextBytes(raw);
    String token = java.util.HexFormat.of().formatHex(raw);
    boolean got;
    try (PreparedStatement ps = c.prepareStatement(TRY)) {
      int i = 1;
      ps.setString(i++, value(issuanceId, token));
      i = bindKey(ps, i, issuanceId);
      ps.setInt(i++, AdvisoryLocks.ROW_CLASS_ID);
      ps.setInt(i, rowKey(issuanceId));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        got = rs.getBoolean(1);
      }
    } catch (SQLException e) {
      throw refusedByPooler(e);
    }
    if (!joined && !c.getAutoCommit()) {
      c.commit();
    }
    return got ? Optional.of(token) : Optional.empty();
  }

  /** P1, as the first statement of a held transaction. */
  static boolean prove(Connection c, long issuanceId, String token) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT " + PROOF)) {
      int i = 1;
      ps.setString(i++, value(issuanceId, token));
      bindKey(ps, i, issuanceId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    } catch (SQLException e) {
      throw refusedByPooler(e);
    }
  }

  /**
   * P2. True when this acquisition's lock was freed. False, or a throw, means the proof failed or
   * the session is gone: {@code pg_advisory_unlock} was not called on anyone else's lock, and the
   * caller evicts the connection.
   */
  static boolean unlock(Connection c, long issuanceId, String token) {
    return unlock(c, issuanceId, token, false);
  }

  /**
   * @param joined true inside a caller's transaction, which this never commits
   */
  static boolean unlock(Connection c, long issuanceId, String token, boolean joined) {
    try (PreparedStatement ps = c.prepareStatement(UNLOCK)) {
      int i = 1;
      ps.setString(i++, value(issuanceId, token));
      i = bindKey(ps, i, issuanceId);
      ps.setInt(i++, AdvisoryLocks.ROW_CLASS_ID);
      ps.setInt(i, rowKey(issuanceId));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        boolean released = rs.getBoolean(1);
        if (!joined && !c.getAutoCommit()) {
          c.commit();
        }
        return released;
      }
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * C5: a statement-mode pooler refuses a transaction block with SQLState 08P01. That is a
   * connection path that cannot keep the lock, so it is DEI-104, not an outage.
   */
  static SQLException refusedByPooler(SQLException e) {
    if ("08P01".equals(e.getSQLState())) {
      throw notPinned("the database connection refused a transaction block (SQLState 08P01)");
    }
    return e;
  }

  static EInvoiceException notPinned(String why) {
    return new EInvoiceException(
        ErrorCodes.SESSION_NOT_PINNED,
        "the database connections do not keep one server session ("
            + why
            + "): a pooler in transaction or statement mode (PgBouncer, for example) is between"
            + " this application and PostgreSQL. The issuance row lock is a session lock and would"
            + " silently be no lock at all, so nothing is issued. Use session pooling or a direct"
            + " connection.");
  }

  /** The refusal of a held transaction whose proof failed. Names the row, never the token (C6). */
  static EInvoiceException lostLock(long issuanceId) {
    return new EInvoiceException(
        ErrorCodes.SESSION_NOT_PINNED,
        "the row lock of issuance row "
            + issuanceId
            + " is no longer held by this run on its database session; nothing was written in"
            + " this transaction");
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
        ErrorCodes.ROW_LOCK_RELEASE_FAILED);
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
      try (Connection two = dataSource.getConnection()) {
        two.setAutoCommit(false);
        int other;
        try (Statement st = two.createStatement();
            ResultSet rs = st.executeQuery("SELECT pg_catalog.pg_backend_pid()")) {
          rs.next();
          other = rs.getInt(1);
        }
        if (other == first || !sameSession(one, first, nonce)) {
          throw notPinned("two connections reached one server session");
        }
        two.rollback();
      }
      // Revision 3, P4: a round-robin pooler hands the next transaction to the least recently
      // used idle server, so a few more statements move connection one off its server. An early
      // warning only: under low load a transaction pooler can still pass, and the per-lock proof
      // is the control.
      for (int i = 0; i < 4; i++) {
        if (!sameSession(one, first, nonce)) {
          throw notPinned("one connection moved to another server session");
        }
      }
    } catch (SQLException e) {
      throw JdbcSupport.unavailable(refusedByPooler(e));
    }
  }

  private static boolean sameSession(Connection c, int pid, String nonce) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT pg_catalog.pg_backend_pid(),"
                    + " pg_catalog.current_setting('einvoice.session_probe', true)")) {
      rs.next();
      return rs.getInt(1) == pid && nonce.equals(rs.getString(2));
    }
  }
}
