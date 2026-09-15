package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.sql.Connection;
import java.util.Objects;

/**
 * The caller owns the connection and its transaction: this never commits, never rolls back, never
 * touches auto-commit and never closes.
 *
 * <p>The connection is the transaction here, so the lock ledger hangs off this instance - one
 * instance per caller-owned transaction, which is how the factory is meant to be used.
 */
final class CallerConnectionUnitOfWork extends AbstractJdbcUnitOfWork {

  private final Connection connection;
  private final LockLedger ledger = LockLedger.forOneTransaction();
  private boolean poisoned;

  CallerConnectionUnitOfWork(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  @Override
  protected Connection acquire() {
    if (poisoned) {
      throw new EInvoiceException(
          ErrorCodes.UNIT_OF_WORK_POISONED,
          "this caller-owned transaction already failed inside the e-invoice store after it had"
              + " written, so nothing more will be run on it. Roll it back; a commit would record"
              + " a half-written unit of work.");
    }
    return connection;
  }

  @Override
  protected void release(Connection released) {
    // the caller's connection: not ours to close
  }

  @Override
  protected boolean joined(Connection released) {
    return true;
  }

  @Override
  protected LockLedger ledger(Connection released) {
    return ledger;
  }

  @Override
  protected void markRollbackOnly(Connection released) {
    poisoned = true;
  }

  @Override
  public Participation participation() {
    return Participation.JOIN;
  }
}
