package com.housedevinci.einvoice.adapter.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * The explicit port for transaction participation (D1-04).
 *
 * <p>Every statement this module's store issues runs inside a unit of work obtained here, and the
 * unit of work - never the caller, never a store method - decides who commits, who closes and who
 * marks a host transaction rollback-only. There is deliberately <b>no ambient "current connection"
 * registry</b> in this module: a caller that already owns a connection or a transaction says so, by
 * choosing the implementation, and a caller that says nothing gets its own transaction exactly as
 * before.
 *
 * <p>Three implementations, two of them here:
 *
 * <ul>
 *   <li>{@link #ownConnection(DataSource)} - opens a connection, commits it, closes it. The
 *       behaviour this module had before the port existed, and still the behaviour of a direct call
 *       made outside any transaction.
 *   <li>{@link #using(Connection)} - the caller owns the connection and the transaction: this never
 *       commits, never rolls back, never touches auto-commit and never closes. A connection handed
 *       in with auto-commit on is refused, because committing statement by statement under a caller
 *       that believes it owns a transaction is the defect this port exists to remove.
 *   <li>the starter's Spring implementation - joins the host's transaction through Spring's
 *       data-source utilities when one is active, and behaves as {@link #ownConnection} when none
 *       is.
 * </ul>
 */
public interface JdbcUnitOfWork {

  /** What an implementation does, for the startup log line; never a per-call assertion (T-06). */
  enum Participation {
    /** Joins the caller's transaction when there is one. */
    JOIN,
    /** Always opens and commits its own connection. */
    OWN_CONNECTION
  }

  /** Work to run against one connection. */
  @FunctionalInterface
  interface SqlWork<T> {
    T run(Unit unit) throws SQLException;
  }

  /**
   * A transactional unit of work: the writes. In joined mode the host commits it; otherwise this
   * unit commits it.
   */
  <T> T inTransaction(SqlWork<T> work);

  /**
   * A read unit of work. It is still a unit of work: in joined mode it runs on the host's
   * connection, so a host that allocated a number and then reads it back inside the same
   * transaction sees its own uncommitted row (T-01).
   */
  <T> T inReadUnit(SqlWork<T> work);

  /** The implementation's kind, for one log line at startup. */
  Participation participation();

  static JdbcUnitOfWork ownConnection(DataSource dataSource) {
    return new OwnConnectionUnitOfWork(dataSource);
  }

  static JdbcUnitOfWork using(Connection callerOwned) {
    return new CallerConnectionUnitOfWork(callerOwned);
  }

  /**
   * One execution of a unit of work: the connection the work must use, the locks this transaction
   * already holds, and whether a caller owns the commit.
   */
  final class Unit {

    private final Connection work;
    private final Connection plumbing;
    private final LockLedger locks;
    private final boolean joined;
    private final AtomicBoolean executedAnyStatement;

    Unit(
        Connection work,
        Connection plumbing,
        LockLedger locks,
        boolean joined,
        AtomicBoolean executedAnyStatement) {
      this.work = Objects.requireNonNull(work);
      this.plumbing = Objects.requireNonNull(plumbing);
      this.locks = Objects.requireNonNull(locks);
      this.joined = joined;
      this.executedAnyStatement = Objects.requireNonNull(executedAnyStatement);
    }

    /**
     * The connection the work must use. It records that a statement was executed, so the unit of
     * work can tell a refusal raised before it touched the database from one raised after (T-03).
     */
    public Connection connection() {
      return work;
    }

    /** The locks this transaction already holds, in the only scope the invariant is checkable. */
    public LockLedger locks() {
      return locks;
    }

    /** True when a caller owns the commit and the rollback of this transaction. */
    public boolean joined() {
      return joined;
    }

    /**
     * The untracked connection, for the unit's own plumbing only: the lock-timeout save and restore
     * and the read-only probe are this module's bookkeeping, not the caller's work, and must not
     * count as "we have written something".
     */
    Connection plumbing() {
      return plumbing;
    }

    boolean executedAnyStatement() {
      return executedAnyStatement.get();
    }
  }
}
