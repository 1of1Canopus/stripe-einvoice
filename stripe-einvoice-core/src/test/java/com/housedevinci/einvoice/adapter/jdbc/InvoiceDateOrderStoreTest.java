package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.domain.DateGapCause;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceEvent;
import com.housedevinci.einvoice.domain.IssueClockException;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesReport;
import com.housedevinci.einvoice.domain.SeriesScope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * D-PS1-27 at the store, over a real PostgreSQL: the issue date is the numbering day, read from the
 * database clock after the series lock, and the clock is refused when it cannot be trusted. Each
 * test names the reorder path or the design item it holds (design sections 2 and 5).
 */
class InvoiceDateOrderStoreTest {

  private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
  private static final SeriesDefinition RESETTING =
      new SeriesDefinition("INV-{fiscalYear}-", 6, true);

  /** Paris 23:59:58.5 on 10 March 2026 (UTC+1): midnight is 1.5 seconds away. */
  private static final Instant BEFORE_PARIS_MIDNIGHT = Instant.parse("2026-03-10T22:59:58.500Z");

  private record Fixture(
      DataSource ds, String seller, JdbcIssuanceStore store, TestIssueClock issueClock) {

    SeriesScope scope() {
      return new SeriesScope(seller, "INV", Mode.LIVE);
    }

    Issuance allocate(String invoiceId, Instant finalizedAt) {
      return store.allocate(request(invoiceId, finalizedAt));
    }

    AllocationRequest request(String invoiceId, Instant finalizedAt) {
      return new AllocationRequest(scope(), invoiceId, "", "S-" + invoiceId, finalizedAt, "r1");
    }

    long rows() {
      return PostgresSupport.scalarOn(
          ds, "SELECT count(*) FROM einvoice_issuance WHERE seller_id = '" + seller + "'");
    }

    long findings(String code) {
      return PostgresSupport.scalarOn(
          ds,
          "SELECT count(*) FROM einvoice_finding WHERE seller_id = '"
              + seller
              + "' AND code = '"
              + code
              + "'");
    }

    void plantLastIssueDate(String isoDate, int fiscalYear) {
      // A hand UPDATE that moves the counter and the date in one statement: the series guard
      // allows it, which is exactly the D-PS1-42 shape.
      PostgresSupport.executeOn(
          ds,
          "UPDATE einvoice_series SET next_number = next_number + 1, last_issue_date = DATE '"
              + isoDate
              + "' WHERE seller_id = '"
              + seller
              + "' AND series = 'INV' AND mode = 'live' AND fiscal_year = "
              + fiscalYear);
    }
  }

  private static Fixture fixture(String hint, ZoneId zone, Instant now) {
    return fixture(PostgresSupport.dataSource(), hint, zone, Clock.fixed(now, ZoneOffset.UTC));
  }

  private static Fixture fixture(DataSource ds, String hint, ZoneId zone, Clock clock) {
    String seller = PostgresSupport.freshSeller(hint);
    TestIssueClock issueClock = TestIssueClock.following(clock);
    JdbcIssuanceStore store =
        TestStores.store(
            JdbcUnitOfWork.ownConnection(ds),
            ds,
            DocumentSeriesFixture.CHAIN,
            Map.of(new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE), RESETTING),
            zone,
            clock,
            JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT,
            issueClock);
    return new Fixture(ds, seller, store, issueClock);
  }

  /** Holds the series row lock of {@code fixture} for {@code millis} on another connection. */
  private static Future<?> holdSeriesLock(
      ExecutorService pool, Fixture f, int fiscalYear, long millis, CountDownLatch held) {
    return pool.submit(
        () -> {
          try (Connection c = f.ds().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT next_number FROM einvoice_series WHERE seller_id = ? AND series = 'INV'"
                        + " AND mode = 'live' AND fiscal_year = ? FOR UPDATE")) {
              ps.setString(1, f.seller());
              ps.setInt(2, fiscalYear);
              ps.executeQuery().close();
            }
            held.countDown();
            Thread.sleep(millis);
            c.commit();
          }
          return null;
        });
  }

  // ------------------------------------------------------------------ T-R1, M3, M4

  /**
   * R1. A transaction that starts on 10 March and waits for the series lock past Paris midnight
   * dates its invoice 11 March: the date comes from {@code clock_timestamp()} read after the lock,
   * not from {@code now()} (the transaction start, M3) and not from a reading taken before the lock
   * (M4).
   */
  @Test
  void a_transaction_that_waits_for_the_lock_across_midnight_is_dated_the_new_day()
      throws Exception {
    Fixture f = fixture("tr1", PARIS, BEFORE_PARIS_MIDNIGHT);
    Issuance first = f.allocate("in_a", BEFORE_PARIS_MIDNIGHT.minusSeconds(3600));
    assertThat(first.dating().orElseThrow().issueDate()).isEqualTo(LocalDate.parse("2026-03-10"));

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch held = new CountDownLatch(1);
      Future<?> holder = holdSeriesLock(pool, f, 2026, 3_000, held);
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      Issuance waiter = f.allocate("in_b", BEFORE_PARIS_MIDNIGHT.minusSeconds(3600));
      holder.get(10, TimeUnit.SECONDS);

      assertThat(waiter.legalNumber().value()).isEqualTo("INV-2026-000002");
      assertThat(waiter.dating().orElseThrow().issueDate())
          .isEqualTo(LocalDate.parse("2026-03-11"));
      assertThat(waiter.dating().orElseThrow().supplyDate())
          .contains(LocalDate.parse("2026-03-10"));
      assertThat(waiter.dating().orElseThrow().previousNumber()).contains("INV-2026-000001");
      assertThat(waiter.dating().orElseThrow().previousIssueDate())
          .contains(LocalDate.parse("2026-03-10"));
      assertThat(waiter.dating().orElseThrow().cause()).contains(DateGapCause.UNRECORDED);
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------------ M5

  /**
   * M5 as the realistic bug: a conversion that uses the session's {@code TimeZone} (UTC here) gives
   * 10 March at 00:00:01 Paris, which is 23:00:01 UTC. The tax zone decides, in Java, once.
   */
  @Test
  void the_session_time_zone_never_decides_the_issue_day() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(PostgresSupport.jdbcUrl());
    config.setUsername(PostgresSupport.username());
    config.setPassword(PostgresSupport.password());
    config.setMaximumPoolSize(4);
    config.setConnectionInitSql("SET TIME ZONE 'UTC'");
    try (HikariDataSource utcSessions = new HikariDataSource(config)) {
      Fixture f =
          fixture(
              utcSessions,
              "tr1utc",
              PARIS,
              Clock.fixed(Instant.parse("2026-03-10T23:00:01Z"), ZoneOffset.UTC));
      Issuance issued = f.allocate("in_utc", Instant.parse("2026-03-10T23:00:00Z"));
      assertThat(issued.dating().orElseThrow().issueDate())
          .isEqualTo(LocalDate.parse("2026-03-11"));
    }
  }

  // ------------------------------------------------------------------ T-year-change, M11, M12

  /**
   * D-PS1-41. 31 December 23:59:58.5 Paris: the series is picked from the first reading (2026), its
   * lock is held past midnight, and the reading under the lock is 2027. Refused with DEI-131,
   * nothing numbered; the retry in a new transaction numbers in 2027's series, dated 1 January.
   */
  @Test
  void a_year_that_changes_under_the_lock_is_refused_and_the_retry_takes_the_new_year()
      throws Exception {
    Instant beforeNewYear = Instant.parse("2026-12-31T22:59:58.500Z");
    Fixture f = fixture("tyear", PARIS, beforeNewYear);
    f.allocate("in_dec", beforeNewYear.minusSeconds(60));
    long rowsBefore = f.rows();

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch held = new CountDownLatch(1);
      Future<?> holder = holdSeriesLock(pool, f, 2026, 3_000, held);
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      Throwable refused =
          catchThrowable(() -> f.allocate("in_late", beforeNewYear.minusSeconds(60)));
      holder.get(10, TimeUnit.SECONDS);

      assertThat(refused).isInstanceOf(EInvoiceException.class);
      assertThat(((EInvoiceException) refused).code()).isEqualTo(ErrorCodes.ISSUE_YEAR_CHANGED);
      assertThat(f.rows()).isEqualTo(rowsBefore);
      assertThat(
              PostgresSupport.scalarOn(
                  f.ds(),
                  "SELECT next_number FROM einvoice_series WHERE seller_id = '"
                      + f.seller()
                      + "' AND fiscal_year = 2026"))
          .isEqualTo(2L);

      Issuance retried = f.allocate("in_late", beforeNewYear.minusSeconds(60));
      assertThat(retried.legalNumber().value()).isEqualTo("INV-2027-000001");
      assertThat(retried.seriesKey().fiscalYear()).isEqualTo(2027);
      assertThat(retried.dating().orElseThrow().issueDate())
          .isEqualTo(LocalDate.parse("2027-01-01"));
      assertThat(retried.dating().orElseThrow().supplyDate())
          .contains(LocalDate.parse("2026-12-31"));
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * The joined path of T-year-change (round 2, item 1): inside the host's own transaction DEI-131
   * reaches the host as a typed, retryable exception with its code; the host rolls back and retries
   * in a new transaction.
   */
  @Test
  void a_year_change_reaches_a_joined_host_with_its_code_and_a_new_transaction_succeeds()
      throws Exception {
    Instant beforeNewYear = Instant.parse("2026-12-31T22:59:58.500Z");
    Clock clock = Clock.fixed(beforeNewYear, ZoneOffset.UTC);
    Fixture f = fixture(PostgresSupport.dataSource(), "tyearj", PARIS, clock);
    f.allocate("in_dec", beforeNewYear.minusSeconds(60));

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection host = f.ds().getConnection()) {
      host.setAutoCommit(false);
      JdbcIssuanceStore joined =
          TestStores.store(
              JdbcUnitOfWork.using(host),
              f.ds(),
              DocumentSeriesFixture.CHAIN,
              Map.of(new JdbcIssuanceStore.SeriesId(f.seller(), "INV", Mode.LIVE), RESETTING),
              PARIS,
              clock,
              JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT,
              f.issueClock());
      CountDownLatch held = new CountDownLatch(1);
      Future<?> holder = holdSeriesLock(pool, f, 2026, 3_000, held);
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      Throwable refused =
          catchThrowable(
              () -> joined.allocate(f.request("in_join", beforeNewYear.minusSeconds(60))));
      holder.get(10, TimeUnit.SECONDS);
      host.rollback();

      assertThat(refused).isInstanceOf(EInvoiceException.class);
      assertThat(((EInvoiceException) refused).code()).isEqualTo(ErrorCodes.ISSUE_YEAR_CHANGED);
      // A new host transaction is a new unit of work: the refused one is poisoned by design.
      JdbcIssuanceStore nextTransaction =
          TestStores.store(
              JdbcUnitOfWork.using(host),
              f.ds(),
              DocumentSeriesFixture.CHAIN,
              Map.of(new JdbcIssuanceStore.SeriesId(f.seller(), "INV", Mode.LIVE), RESETTING),
              PARIS,
              clock,
              JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT,
              f.issueClock());
      Issuance retried =
          nextTransaction.allocate(f.request("in_join", beforeNewYear.minusSeconds(60)));
      host.commit();
      assertThat(retried.legalNumber().value()).isEqualTo("INV-2027-000001");
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------------ T-clock-2s, M6

  /**
   * Design 5.2. The series already holds 11 March, the database clock reads 23:59:58.5 on 10 March:
   * DEI-132, retryable, no number and no finding (1.5 seconds is below the bound). Once the clock
   * has reached the new day the same allocation numbers, dated 11 March.
   */
  @Test
  void a_clock_two_seconds_behind_the_series_waits_and_then_numbers() throws Exception {
    Fixture f = fixture("tclock2s", PARIS, BEFORE_PARIS_MIDNIGHT);
    f.allocate("in_a", BEFORE_PARIS_MIDNIGHT.minusSeconds(3600));
    f.plantLastIssueDate("2026-03-11", 2026);
    long rowsBefore = f.rows();

    Throwable behind =
        catchThrowable(() -> f.allocate("in_b", BEFORE_PARIS_MIDNIGHT.minusSeconds(3600)));

    assertThat(behind).isInstanceOf(IssueClockException.class);
    assertThat(((IssueClockException) behind).code())
        .isEqualTo(ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES);
    assertThat(((IssueClockException) behind).retryable()).isTrue();
    assertThat(f.rows()).isEqualTo(rowsBefore);
    assertThat(f.findings(ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES)).isZero();

    Thread.sleep(2_000);
    Issuance numbered = f.allocate("in_b", BEFORE_PARIS_MIDNIGHT.minusSeconds(3600));
    assertThat(numbered.dating().orElseThrow().issueDate())
        .isEqualTo(LocalDate.parse("2026-03-11"));
  }

  // ------------------------------------------------------------------ T-clock-2d (store half), M7

  /**
   * The database clock two days behind the application clock: DEI-132, not retryable, a finding
   * that names the series, no number.
   */
  @Test
  void a_database_clock_two_days_behind_is_refused_for_good_with_a_finding() {
    Fixture f = fixture("tclock2d", ZoneOffset.UTC, Instant.parse("2026-03-10T10:00:00Z"));
    f.allocate("in_a", Instant.parse("2026-03-08T10:00:00Z"));
    f.issueClock().shift(Duration.ofDays(-2));

    Throwable refused =
        catchThrowable(() -> f.allocate("in_b", Instant.parse("2026-03-07T10:00:00Z")));

    assertThat(refused).isInstanceOf(IssueClockException.class);
    assertThat(((IssueClockException) refused).retryable()).isFalse();
    assertThat(f.rows()).isEqualTo(1L);
    assertThat(f.findings(ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES)).isEqualTo(1L);
  }

  // ------------------------------------------------------------------ D-PS1-46

  /** Two independent clocks must agree: the database ten minutes ahead of the JVM is refused. */
  @Test
  void a_database_clock_ten_minutes_ahead_of_the_application_is_refused() {
    Fixture f = fixture("tahead", ZoneOffset.UTC, Instant.parse("2026-03-10T10:00:00Z"));
    f.issueClock().shift(Duration.ofMinutes(10));

    Throwable refused =
        catchThrowable(() -> f.allocate("in_a", Instant.parse("2026-03-10T09:00:00Z")));

    assertThat(refused).isInstanceOf(IssueClockException.class);
    assertThat(((IssueClockException) refused).retryable()).isTrue();
    assertThat(f.rows()).isZero();
    assertThat(f.findings(ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES)).isEqualTo(1L);
  }

  @Test
  void a_database_clock_ten_seconds_ahead_of_the_application_numbers() {
    Fixture f = fixture("tahead10s", ZoneOffset.UTC, Instant.parse("2026-03-10T10:00:00Z"));
    f.issueClock().shift(Duration.ofSeconds(10));

    Issuance numbered = f.allocate("in_a", Instant.parse("2026-03-10T09:00:00Z"));

    assertThat(numbered.allocatedAt()).isAfter(Instant.parse("2026-03-10T10:00:09Z"));
  }

  /** An invoice Stripe finalised after the database's "now": our clock is behind, so it waits. */
  @Test
  void an_invoice_finalised_after_the_database_clock_waits_rather_than_predate_its_supply() {
    Fixture f = fixture("tsupply", ZoneOffset.UTC, Instant.parse("2026-03-10T23:59:59Z"));

    Throwable refused =
        catchThrowable(() -> f.allocate("in_a", Instant.parse("2026-03-11T00:00:30Z")));

    assertThat(refused).isInstanceOf(IssueClockException.class);
    assertThat(((IssueClockException) refused).retryable()).isTrue();
    assertThat(f.rows()).isZero();
  }

  // ------------------------------------------------------------------ T-R10, M11

  /**
   * R10. A continuous series: an invoice supplied on 31 December and numbered on 2 January, after a
   * January number, is dated 2 January, so the dates of the series still go up.
   */
  @Test
  void r10_a_continuous_series_dates_a_december_sale_numbered_in_january_on_its_numbering_day() {
    String seller = PostgresSupport.freshSeller("tr10");
    Clock clock = Clock.fixed(Instant.parse("2027-01-02T10:00:00Z"), ZoneOffset.UTC);
    JdbcIssuanceStore store =
        TestStores.store(
            PostgresSupport.dataSource(),
            DocumentSeriesFixture.CHAIN,
            Map.of(
                new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE),
                new SeriesDefinition("C-", 6, false)),
            clock);
    SeriesScope scope = new SeriesScope(seller, "INV", Mode.LIVE);
    Issuance january =
        store.allocate(
            new AllocationRequest(
                scope, "in_jan", "", "S", Instant.parse("2027-01-02T09:00:00Z"), "r1"));
    Issuance december =
        store.allocate(
            new AllocationRequest(
                scope, "in_dec", "", "S", Instant.parse("2026-12-31T15:00:00Z"), "r1"));

    assertThat(december.legalNumber().counter()).isEqualTo(january.legalNumber().counter() + 1);
    assertThat(december.seriesKey().continuous()).isTrue();
    assertThat(december.dating().orElseThrow().issueDate())
        .isEqualTo(LocalDate.parse("2027-01-02"));
    assertThat(december.dating().orElseThrow().supplyDate())
        .contains(LocalDate.parse("2026-12-31"));
  }

  /**
   * D-PS1-41 on a resetting series: the December sale numbered on 2 January goes to 2027's series,
   * never to the closing year's after the fact.
   */
  @Test
  void a_resetting_series_takes_the_year_of_the_numbering_day_not_of_the_sale() {
    Fixture f = fixture("tr10r", ZoneOffset.UTC, Instant.parse("2027-01-02T10:00:00Z"));
    Issuance december = f.allocate("in_dec", Instant.parse("2026-12-31T15:00:00Z"));

    assertThat(december.seriesKey().fiscalYear()).isEqualTo(2027);
    assertThat(december.legalNumber().value()).isEqualTo("INV-2027-000001");
  }

  // ------------------------------------------------------------------ T-future-date, M10

  @Test
  void a_future_latest_date_stops_startup_with_its_own_code_and_a_finding() {
    Fixture f = fixture("tfuture", ZoneOffset.UTC, Instant.parse("2026-03-10T10:00:00Z"));
    f.allocate("in_a", Instant.parse("2026-03-10T09:00:00Z"));
    f.plantLastIssueDate("2026-03-12", 2026);

    Throwable refused = catchThrowable(() -> f.store().checkSeriesDates());

    assertThat(refused).isInstanceOf(EInvoiceException.class);
    assertThat(((EInvoiceException) refused).code()).isEqualTo(ErrorCodes.SERIES_DATE_IN_FUTURE);
    assertThat(f.findings(ErrorCodes.SERIES_DATE_IN_FUTURE)).isEqualTo(1L);
  }

  @Test
  void a_latest_date_one_day_ahead_is_a_clock_to_wait_for_not_a_future_date() {
    Fixture f = fixture("tfuture1", ZoneOffset.UTC, Instant.parse("2026-03-10T10:00:00Z"));
    f.allocate("in_a", Instant.parse("2026-03-10T09:00:00Z"));
    f.plantLastIssueDate("2026-03-11", 2026);

    f.store().checkSeriesDates();
    Throwable refused =
        catchThrowable(() -> f.allocate("in_b", Instant.parse("2026-03-10T09:00:00Z")));

    assertThat(refused).isInstanceOf(IssueClockException.class);
    assertThat(((IssueClockException) refused).retryable()).isTrue();
    // Fourteen hours behind is above the five-minute bound, so the wait is visible.
    assertThat(f.findings(ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES)).isEqualTo(1L);
  }

  // ------------------------------------------------------------------ T-null-upgrade, M9

  @Test
  void a_series_with_numbers_and_no_latest_date_is_refused_at_startup_and_at_allocation() {
    DataSource ds = PostgresSupport.freshDatabase("tnull");
    try {
      Fixture f =
          fixture(
              ds,
              "tnull",
              ZoneOffset.UTC,
              Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC));
      f.allocate("in_a", Instant.parse("2026-03-10T09:00:00Z"));
      PostgresSupport.executeOn(
          ds, "ALTER TABLE einvoice_series DISABLE TRIGGER einvoice_series_guard");
      PostgresSupport.executeOn(ds, "UPDATE einvoice_series SET last_issue_date = NULL");
      PostgresSupport.executeOn(
          ds, "ALTER TABLE einvoice_series ENABLE TRIGGER einvoice_series_guard");

      Throwable atStartup = catchThrowable(() -> f.store().checkSeriesDates());
      Throwable atAllocation =
          catchThrowable(() -> f.allocate("in_b", Instant.parse("2026-03-10T09:00:00Z")));

      assertThat(atStartup).isInstanceOf(EInvoiceException.class);
      assertThat(((EInvoiceException) atStartup).code())
          .isEqualTo(ErrorCodes.SCHEMA_UPGRADE_REFUSED);
      assertThat(atAllocation).isInstanceOf(EInvoiceException.class);
      assertThat(((EInvoiceException) atAllocation).code())
          .isEqualTo(ErrorCodes.SCHEMA_UPGRADE_REFUSED);
      assertThat(f.rows()).isEqualTo(1L);
    } finally {
      PostgresSupport.close(ds);
    }
  }

  // ------------------------------------------------------------------ T-same-day, M8

  /** Two numbers on one day, in either order of finalisation, are not a gap and not a refusal. */
  @Test
  void two_invoices_of_one_day_in_either_order_carry_no_cause_and_no_refusal() {
    Fixture f = fixture("tsame", ZoneOffset.UTC, Instant.parse("2026-03-10T18:00:00Z"));
    Issuance later = f.allocate("in_later", Instant.parse("2026-03-10T17:00:00Z"));
    Issuance earlier = f.allocate("in_earlier", Instant.parse("2026-03-10T08:00:00Z"));

    assertThat(later.dating().orElseThrow().cause()).contains(DateGapCause.NONE);
    assertThat(earlier.dating().orElseThrow().cause()).contains(DateGapCause.NONE);
    assertThat(earlier.dating().orElseThrow().issueDate()).isEqualTo(LocalDate.parse("2026-03-10"));
    assertThat(f.findings(ErrorCodes.ISSUED_LATE)).isZero();
  }

  // ------------------------------------------------------------------ T-gap-finding

  @Test
  void an_invoice_numbered_more_than_a_day_after_its_supply_is_numbered_with_a_finding() {
    Fixture f = fixture("tgap", ZoneOffset.UTC, Instant.parse("2026-03-12T10:00:00Z"));
    Issuance late = f.allocate("in_late", Instant.parse("2026-03-10T09:00:00Z"));

    assertThat(late.dating().orElseThrow().issueDate()).isEqualTo(LocalDate.parse("2026-03-12"));
    assertThat(late.dating().orElseThrow().supplyDate()).contains(LocalDate.parse("2026-03-10"));
    assertThat(late.dating().orElseThrow().cause()).contains(DateGapCause.UNRECORDED);
    assertThat(f.findings(ErrorCodes.ISSUED_LATE)).isEqualTo(1L);
  }

  @Test
  void a_one_day_gap_has_a_cause_and_no_finding() {
    Fixture f = fixture("tgap1", ZoneOffset.UTC, Instant.parse("2026-03-11T10:00:00Z"));
    Issuance late = f.allocate("in_late", Instant.parse("2026-03-10T09:00:00Z"));

    assertThat(late.dating().orElseThrow().cause()).contains(DateGapCause.UNRECORDED);
    assertThat(f.findings(ErrorCodes.ISSUED_LATE)).isZero();
  }

  // ------------------------------------------------------------------ BG-14

  @Test
  void a_common_service_period_becomes_the_invoicing_period_with_its_last_day_included() {
    Fixture f = fixture("tperiod", ZoneOffset.UTC, Instant.parse("2026-03-01T10:00:00Z"));
    Issuance issued =
        f.store()
            .allocate(
                new AllocationRequest(
                    f.scope(),
                    "in_sub",
                    "",
                    "S-sub",
                    Instant.parse("2026-03-01T09:00:00Z"),
                    Optional.of(
                        new AllocationRequest.ServicePeriod(
                            Instant.parse("2026-02-01T00:00:00Z"),
                            Instant.parse("2026-03-01T00:00:00Z"))),
                    "r1"));

    assertThat(issued.dating().orElseThrow().supplyDate()).isEmpty();
    assertThat(issued.dating().orElseThrow().supplyPeriod().orElseThrow().start())
        .isEqualTo(LocalDate.parse("2026-02-01"));
    assertThat(issued.dating().orElseThrow().supplyPeriod().orElseThrow().end())
        .isEqualTo(LocalDate.parse("2026-02-28"));
    // Finalised and numbered on 1 March: no gap. Lateness is counted from the finalisation day,
    // never from the period's end (D-PR36-1).
    assertThat(issued.dating().orElseThrow().cause()).contains(DateGapCause.NONE);
    assertThat(f.findings(ErrorCodes.ISSUED_LATE)).isZero();
  }

  // ------------------------------------------------------------------ T-report, chain

  /**
   * Q32 (iii): the record shows the dates. The row and the chained event carry the same dating, a
   * resume returns it unchanged, and the series report shows issue, supply, allocation and cause.
   */
  @Test
  void the_row_the_event_the_resume_and_the_report_carry_the_same_dates() {
    Fixture f = fixture("treport", ZoneOffset.UTC, Instant.parse("2026-03-12T10:00:00Z"));
    Issuance first = f.allocate("in_1", Instant.parse("2026-03-12T09:00:00Z"));
    Issuance late = f.allocate("in_2", Instant.parse("2026-03-10T09:00:00Z"));
    Issuance resumed = f.allocate("in_2", Instant.parse("2026-03-10T09:00:00Z"));
    assertThat(resumed).isEqualTo(late);

    String sha = DocumentSeriesFixture.sha("in_2");
    f.store()
        .markArchiving(
            f.seller(),
            Mode.LIVE,
            "in_2",
            sha,
            new com.housedevinci.einvoice.domain.ArchiveKey(
                "live/" + late.legalNumber().value() + "-" + sha + ".xml"));
    f.store().markIssued(f.seller(), Mode.LIVE, "in_2");
    List<IssuanceEvent> events = f.store().readAfter(0, 100_000);
    IssuanceEvent issuedEvent =
        events.stream()
            .filter(
                e ->
                    e.legalNumber().equals(late.legalNumber())
                        && e.seriesKey().sellerId().equals(f.seller()))
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(issuedEvent.dating()).isEqualTo(late.dating());
    assertThat(issuedEvent.dating().orElseThrow().previousNumber())
        .contains(first.legalNumber().value());

    SeriesReport report = f.store().seriesReport(late.seriesKey());
    SeriesReport.Line line = report.lines().get(1);
    assertThat(line.issueDate()).isEqualTo(LocalDate.parse("2026-03-12"));
    assertThat(line.supplyDate()).contains(LocalDate.parse("2026-03-10"));
    assertThat(line.cause()).contains(DateGapCause.UNRECORDED);
    assertThat(line.allocatedAt()).isEqualTo(late.allocatedAt());
    assertThat(line.dateOutOfOrder()).isFalse();
  }

  /** The dates are immutable on the row: the issuance guard refuses a rewrite. */
  @Test
  void the_issue_date_of_a_numbered_row_cannot_be_rewritten() {
    Fixture f = fixture("timmut", ZoneOffset.UTC, Instant.parse("2026-03-12T10:00:00Z"));
    f.allocate("in_1", Instant.parse("2026-03-12T09:00:00Z"));

    Throwable rewrite =
        catchThrowable(
            () ->
                PostgresSupport.executeOn(
                    f.ds(),
                    "UPDATE einvoice_issuance SET issue_date = DATE '2026-03-01' WHERE seller_id = '"
                        + f.seller()
                        + "'"));

    assertThat(rewrite).hasMessageContaining("only state");
  }

  // ------------------------------------------------------------------ D-PS1-45

  /**
   * The issue clock is {@code pg_catalog.clock_timestamp()}, schema-qualified, with no wrapper. A
   * role that may create a look-alike function on a schema earlier in the search path cannot move
   * the legal date of an invoice: the production store, on the real database clock, still dates the
   * invoice today.
   */
  @Test
  void probe_runtime_role_cannot_redefine_the_issue_clock() {
    DataSource owner = PostgresSupport.freshDatabase("tclockfn");
    try {
      PostgresSupport.executeOn(owner, "CREATE SCHEMA evil");
      PostgresSupport.executeOn(
          owner,
          "CREATE FUNCTION evil.clock_timestamp() RETURNS timestamptz LANGUAGE sql"
              + " AS $$ SELECT TIMESTAMPTZ '2099-01-01 00:00:00+00' $$");
      DataSource runtime =
          SchemaGrants.roleWithDocumentedGrants(owner, "einvoice_clockprobe", "clockprobe");
      PostgresSupport.executeOn(owner, "GRANT USAGE, CREATE ON SCHEMA evil TO einvoice_clockprobe");
      PostgresSupport.executeOn(
          owner, "ALTER ROLE einvoice_clockprobe SET search_path = evil, pg_catalog, public");
      String seller = PostgresSupport.freshSeller("tclockfn");
      JdbcIssuanceStore production =
          new JdbcIssuanceStore(
              runtime,
              DocumentSeriesFixture.CHAIN,
              Map.of(new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE), RESETTING),
              ZoneOffset.UTC,
              Clock.systemUTC());
      Instant now = Instant.now();
      Issuance issued =
          production.allocate(
              new AllocationRequest(
                  new SeriesScope(seller, "INV", Mode.LIVE),
                  "in_now",
                  "",
                  "S",
                  now.minusSeconds(60),
                  "r1"));

      assertThat(IssueClock.STATEMENT).isEqualTo("SELECT pg_catalog.clock_timestamp()");
      assertThat(issued.dating().orElseThrow().issueDate().getYear()).isNotEqualTo(2099);
      assertThat(Duration.between(now, issued.allocatedAt()).abs())
          .isLessThan(Duration.ofMinutes(1));
      PostgresSupport.close(runtime);
    } finally {
      PostgresSupport.close(owner);
    }
  }
}
