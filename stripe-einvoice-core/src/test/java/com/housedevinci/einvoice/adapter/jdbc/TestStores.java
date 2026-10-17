package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Stores for tests: the issue clock follows the test's clock (see {@link TestIssueClock}). The zone
 * is UTC unless a test names one, because the fixtures' dates are written in UTC.
 */
public final class TestStores {

  private TestStores() {}

  static JdbcIssuanceStore store(
      DataSource ds,
      IssuanceChain chain,
      Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> series,
      Clock clock) {
    return store(ds, chain, series, ZoneOffset.UTC, clock);
  }

  static JdbcIssuanceStore store(
      DataSource ds,
      IssuanceChain chain,
      Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> series,
      ZoneId zone,
      Clock clock) {
    return store(
        JdbcUnitOfWork.ownConnection(ds),
        ds,
        chain,
        series,
        zone,
        clock,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT,
        TestIssueClock.following(clock));
  }

  static JdbcIssuanceStore store(
      DataSource ds,
      IssuanceChain chain,
      Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> series,
      Clock clock,
      Duration allocationTimeout) {
    return store(
        JdbcUnitOfWork.ownConnection(ds),
        ds,
        chain,
        series,
        ZoneOffset.UTC,
        clock,
        allocationTimeout,
        TestIssueClock.following(clock));
  }

  static JdbcIssuanceStore store(
      JdbcUnitOfWork unitOfWork,
      DataSource ds,
      IssuanceChain chain,
      Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> series,
      Clock clock,
      Duration allocationTimeout) {
    return store(
        unitOfWork,
        ds,
        chain,
        series,
        ZoneOffset.UTC,
        clock,
        allocationTimeout,
        TestIssueClock.following(clock));
  }

  static JdbcIssuanceStore store(
      JdbcUnitOfWork unitOfWork,
      DataSource ds,
      IssuanceChain chain,
      Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> series,
      ZoneId zone,
      Clock clock,
      Duration allocationTimeout,
      IssueClock issueClock) {
    return new JdbcIssuanceStore(
        unitOfWork,
        ds,
        chain,
        series,
        zone,
        clock,
        allocationTimeout,
        JdbcIssuanceStore.DEFAULT_MAX_CLOCK_REGRESSION,
        issueClock);
  }
}
