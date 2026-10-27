package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.domain.EInvoiceException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Objects;

/**
 * The held unit of work in joined mode (D-PR43-1, candidate B): every unit runs on the caller's
 * connection, inside the caller's transaction, wrapped in a JDBC savepoint and started by the lock
 * proof. A unit that fails is rolled back to its savepoint, so the caller's transaction stays
 * usable and the release can still run. When the transaction had already written through the store,
 * the failure is recorded on the scope: at the held section's end the transaction is aborted on
 * purpose (the poison), so the caller's commit commits nothing.
 */
final class JoinedHeldUnitOfWork implements JdbcUnitOfWork {

  /** What the held section learned about its units, read once at the end of the section. */
  static final class Scope {
    private String failureCode;

    boolean poisonPending() {
      return failureCode != null;
    }

    String failureCode() {
      return failureCode;
    }

    void failedAfterWrite(String code) {
      if (failureCode == null) {
        failureCode = code;
      }
    }
  }

  private final Connection connection;
  private final LockLedger ledger;
  private final long issuanceId;
  private final String token;
  private final Scope scope;

  JoinedHeldUnitOfWork(
      Connection connection, LockLedger ledger, long issuanceId, String token, Scope scope) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.issuanceId = issuanceId;
    this.token = Objects.requireNonNull(token, "token");
    this.scope = Objects.requireNonNull(scope, "scope");
  }

  @Override
  public <T> T inTransaction(SqlWork<T> work) {
    Savepoint savepoint;
    try {
      savepoint = connection.setSavepoint();
    } catch (SQLException e) {
      throw IssuanceRowLocks.abortedOrUnavailable(e);
    }
    try {
      if (!IssuanceRowLocks.prove(connection, issuanceId, token)) {
        throw IssuanceRowLocks.lostLock(issuanceId);
      }
      Unit unit =
          new Unit(
              StatementTracking.tracking(connection, ledger::markWritten),
              connection,
              ledger,
              true);
      T result = work.run(unit);
      connection.releaseSavepoint(savepoint);
      return result;
    } catch (SQLException | RuntimeException e) {
      undo(savepoint);
      RuntimeException thrown =
          e instanceof RuntimeException runtime
              ? runtime
              : JdbcSupport.unavailable((SQLException) e);
      if (ledger.written()) {
        scope.failedAfterWrite(
            thrown instanceof EInvoiceException typed
                ? typed.code()
                : com.housedevinci.einvoice.domain.ErrorCodes.STORE_UNAVAILABLE);
      }
      throw thrown;
    }
  }

  private void undo(Savepoint savepoint) {
    try {
      connection.rollback(savepoint);
    } catch (SQLException ignored) {
      // the transaction or the connection is gone: the poison or the server ends it
    }
    try {
      connection.releaseSavepoint(savepoint);
    } catch (SQLException ignored) {
      // as above
    }
  }

  @Override
  public <T> T inReadUnit(SqlWork<T> work) {
    return inTransaction(work);
  }

  @Override
  public <T> T onOneConnection(SessionWork<T> work) {
    try {
      return work.run(connection, this, true);
    } catch (SQLException e) {
      throw JdbcSupport.unavailable(e);
    }
  }

  @Override
  public Participation participation() {
    return Participation.JOIN;
  }
}
