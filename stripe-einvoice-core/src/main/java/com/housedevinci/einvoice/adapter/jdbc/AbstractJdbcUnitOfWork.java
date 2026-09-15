package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The shared body of every {@link JdbcUnitOfWork}: acquire, decide who commits, run, fail closed.
 *
 * <p>Subclasses answer four questions - where the connection comes from, whether a caller owns the
 * transaction it is in, which lock ledger that transaction has, and how to refuse the caller's
 * commit - and inherit the rules that must not differ between them.
 */
public abstract class AbstractJdbcUnitOfWork implements JdbcUnitOfWork {

  protected abstract Connection acquire() throws SQLException;

  protected abstract void release(Connection connection) throws SQLException;

  /**
   * True when a caller owns the commit and the rollback of the transaction this connection is in.
   */
  protected abstract boolean joined(Connection connection) throws SQLException;

  /** The ledger of locks already held by the transaction this connection is in. */
  protected abstract LockLedger ledger(Connection connection);

  /**
   * Refuses the caller's commit after this unit of work has already written. The default does
   * nothing, which is correct only where there is no caller-owned transaction to poison.
   */
  protected void markRollbackOnly(Connection connection) {
    // nothing to poison: the implementation that opened the connection rolls it back itself
  }

  @Override
  public <T> T inTransaction(SqlWork<T> work) {
    return run(work, true);
  }

  @Override
  public <T> T inReadUnit(SqlWork<T> work) {
    return run(work, false);
  }

  private <T> T run(SqlWork<T> work, boolean transactional) {
    Connection connection = null;
    try {
      connection = acquire();
      return joined(connection)
          ? runJoined(work, transactional, connection)
          : runOwned(work, transactional, connection);
    } catch (SQLException e) {
      throw JdbcSupport.unavailable(e);
    } finally {
      if (connection != null) {
        try {
          release(connection);
        } catch (SQLException e) {
          throw JdbcSupport.unavailable(e);
        }
      }
    }
  }

  private <T> T runJoined(SqlWork<T> work, boolean transactional, Connection connection)
      throws SQLException {
    if (connection.getAutoCommit()) {
      throw new EInvoiceException(
          ErrorCodes.HOST_AUTOCOMMIT,
          "this connection was handed to the e-invoice store as a caller-owned transaction and it"
              + " is in auto-commit mode, so each statement would commit on its own. A caller that"
              + " rolled back would keep the allocated number. Call setAutoCommit(false) before"
              + " handing the connection over, or let the store open its own connection.");
    }
    if (transactional) {
      refuseReadOnlyTransaction(connection);
    }
    AtomicBoolean executed = new AtomicBoolean();
    Unit unit =
        new Unit(
            StatementTracking.tracking(connection, executed),
            connection,
            ledger(connection),
            true,
            executed);
    try {
      return work.run(unit);
    } catch (SQLException | RuntimeException e) {
      if (executed.get()) {
        // Our own typed refusal, raised in Java after a statement succeeded, is the case that
        // matters: the caller could otherwise catch it, carry on and commit a half-written unit of
        // work - a counter with no issuance row, which appears in no series report line (T-03).
        markRollbackOnly(connection);
      }
      throw e;
    }
  }

  private <T> T runOwned(SqlWork<T> work, boolean transactional, Connection connection)
      throws SQLException {
    AtomicBoolean executed = new AtomicBoolean();
    Unit unit =
        new Unit(
            StatementTracking.tracking(connection, executed),
            connection,
            LockLedger.forOneTransaction(),
            false,
            executed);
    if (!transactional) {
      return work.run(unit);
    }
    boolean previous = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      T result = work.run(unit);
      connection.commit();
      return result;
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(previous);
    }
  }

  /**
   * A read-only host transaction is refused before the series row lock is taken, by name.
   *
   * <p>Without this the first {@code INSERT} fails with SQLState 25006 and is reported as {@code
   * DEI-102 STORE_UNAVAILABLE} - an outage code for what is a caller's annotation.
   */
  private static void refuseReadOnlyTransaction(Connection connection) throws SQLException {
    try (Statement st = connection.createStatement();
        var rs = st.executeQuery("SHOW transaction_read_only")) {
      if (rs.next() && "on".equalsIgnoreCase(rs.getString(1))) {
        throw new EInvoiceException(
            ErrorCodes.HOST_TRANSACTION_READ_ONLY,
            "this allocation was called inside a read-only transaction (Spring's"
                + " @Transactional(readOnly = true), or SET TRANSACTION READ ONLY). Allocating a"
                + " legal number writes: it advances a counter and inserts an issuance row. Call"
                + " it from a read-write transaction.");
      }
    }
  }
}
