package com.housedevinci.einvoice.adapter.jdbc;

import java.sql.Connection;
import java.util.Objects;

/**
 * A unit of work on one connection that someone else holds open (D-RTR-7): it commits each of its
 * own transactions on that connection and never closes it. It exists so that the holder of a
 * session lock writes on the connection that holds the lock, and never asks the pool for a second
 * one while it holds the first.
 */
final class HeldConnectionUnitOfWork extends AbstractJdbcUnitOfWork {

  private final Connection connection;

  HeldConnectionUnitOfWork(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
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
  public Participation participation() {
    return Participation.OWN_CONNECTION;
  }
}
