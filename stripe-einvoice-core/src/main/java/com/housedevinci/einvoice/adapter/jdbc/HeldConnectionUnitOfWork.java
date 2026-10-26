package com.housedevinci.einvoice.adapter.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * A unit of work on one connection that the issuance row lock holds open (D-RTR-7): it commits each
 * of its own transactions on that connection and never closes it, so the holder never asks the pool
 * for a second connection.
 *
 * <p>Revision 3 (D-PR42-2), P1: every transaction it runs, reads included, starts by proving that
 * this session still holds the lock of this acquisition (its token in the session, and the key
 * granted to this backend). Inside one transaction a pooler keeps one server connection, so the
 * proof covers the whole transaction. A failed proof rolls back and refuses with {@code DEI-104}
 * before anything is written.
 */
final class HeldConnectionUnitOfWork extends AbstractJdbcUnitOfWork {

  private final Connection connection;
  private final IssuanceRowLocks.SessionLock lock;
  private final String token;
  private final java.util.function.Supplier<RuntimeException> lost;

  HeldConnectionUnitOfWork(Connection connection, long issuanceId, String token) {
    this(
        connection,
        IssuanceRowLocks.SessionLock.row(issuanceId),
        token,
        () -> IssuanceRowLocks.lostLock(issuanceId));
  }

  /**
   * @param lost the refusal of a transaction whose proof failed; it names the lock's subject, never
   *     the token
   */
  HeldConnectionUnitOfWork(
      Connection connection,
      IssuanceRowLocks.SessionLock lock,
      String token,
      java.util.function.Supplier<RuntimeException> lost) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.lock = Objects.requireNonNull(lock, "lock");
    this.token = Objects.requireNonNull(token, "token");
    this.lost = Objects.requireNonNull(lost, "lost");
  }

  @Override
  protected Connection acquire() {
    return connection;
  }

  @Override
  protected void release(Connection released) {
    // The holder closes it, after it has released its lock.
  }

  @Override
  protected boolean joined(Connection released) {
    return false;
  }

  @Override
  protected LockLedger ledger(Connection released) {
    return LockLedger.forOneTransaction();
  }

  @Override
  protected void beforeTransaction(Connection c) throws SQLException {
    if (!IssuanceRowLocks.prove(c, lock, token)) {
      throw lost.get();
    }
  }

  /** Reads run in a transaction too, so the proof and the read share one server connection. */
  @Override
  public <T> T inReadUnit(SqlWork<T> work) {
    return inTransaction(work);
  }

  @Override
  public Participation participation() {
    return Participation.OWN_CONNECTION;
  }
}
