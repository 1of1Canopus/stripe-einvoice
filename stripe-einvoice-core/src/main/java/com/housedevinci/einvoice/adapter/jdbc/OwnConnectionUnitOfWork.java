package com.housedevinci.einvoice.adapter.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Opens its own connection, commits it, closes it: the behaviour this module had before the port
 * existed, and the behaviour a direct call outside any transaction still gets.
 */
final class OwnConnectionUnitOfWork extends AbstractJdbcUnitOfWork {

  private final DataSource dataSource;

  OwnConnectionUnitOfWork(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  protected Connection acquire() throws SQLException {
    return dataSource.getConnection();
  }

  @Override
  protected void release(Connection connection) throws SQLException {
    connection.close();
  }

  @Override
  protected boolean joined(Connection connection) {
    return false;
  }

  @Override
  protected LockLedger ledger(Connection connection) {
    return LockLedger.forOneTransaction();
  }

  @Override
  public Participation participation() {
    return Participation.OWN_CONNECTION;
  }
}
