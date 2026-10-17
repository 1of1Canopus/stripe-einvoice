package com.housedevinci.einvoice.adapter.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Where the issue instant comes from: the database clock, read on the allocation connection.
 *
 * <p>Package-private on purpose. The only production implementation is {@link #DATABASE}, which
 * runs {@link #STATEMENT}; the public constructors of {@link JdbcIssuanceStore} always use it. A
 * test in this package may substitute a clock that runs the same statement on the same connection
 * and shifts the answer by a fixed offset, so the read still happens where production reads it,
 * after a real lock wait (D-PS1-40, D-PS1-45).
 */
interface IssueClock {

  /**
   * {@code clock_timestamp()}, never {@code now()} or {@code CURRENT_TIMESTAMP}: those are the
   * start of the transaction, so a transaction that began at 23:59:59 and waited for the series
   * lock past midnight would date its invoice the day before. Schema-qualified with no wrapper
   * function: a role that may create a function earlier on the search path must not be able to
   * redefine every invoice's legal date (D-PS1-45). The value is a {@code timestamptz}; SQL never
   * turns it into a date, Java does, once, in the tax zone.
   */
  String STATEMENT = "SELECT pg_catalog.clock_timestamp()";

  Instant read(Connection connection) throws SQLException;

  IssueClock DATABASE = JdbcSupport::readIssueClock;
}
