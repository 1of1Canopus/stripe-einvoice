package com.housedevinci.einvoice.adapter.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The test half of the {@link IssueClock} seam (D-PS1-45). It runs the production statement, on the
 * allocation connection, at the moment production runs it, and then moves the answer onto the
 * test's own timeline: {@code testClock.instant() + (database now - database at the first read) +
 * shift}. Real time spent waiting for a lock is therefore real time on the issue clock, which is
 * what T-R1 and the M3/M4 mutations need; and a fixed or movable test clock still decides the day.
 */
final class TestIssueClock implements IssueClock {

  private final Clock testClock;
  private volatile Duration shift;
  private volatile Instant firstDatabaseRead;

  TestIssueClock(Clock testClock, Duration shift) {
    this.testClock = testClock;
    this.shift = shift;
  }

  /** The issue clock follows {@code testClock}, plus the real time that passed in the database. */
  static TestIssueClock following(Clock testClock) {
    return new TestIssueClock(testClock, Duration.ZERO);
  }

  /** Moves the database clock relative to the test clock, as a wrong NTP step would. */
  void shift(Duration by) {
    this.shift = by;
  }

  @Override
  public Instant read(Connection connection) throws SQLException {
    Instant database = DATABASE.read(connection);
    if (firstDatabaseRead == null) {
      synchronized (this) {
        if (firstDatabaseRead == null) {
          firstDatabaseRead = database;
        }
      }
    }
    return testClock.instant().plus(Duration.between(firstDatabaseRead, database)).plus(shift);
  }
}
