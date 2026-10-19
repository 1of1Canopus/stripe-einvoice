package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.domain.DateGapCause;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesScope;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Security review of the date-order change (D-PS1-27), pass 1. Each probe fails on the reviewed
 * head and passes once its finding is fixed.
 */
class CipherProbePr36Test {

  private static final Map<String, byte[]> KEYRING =
      Map.of("k1", "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  private static final SeriesDefinition RESETTING =
      new SeriesDefinition("INV-{fiscalYear}-", 6, true);

  private static JdbcIssuanceStore store(DataSource ds, String seller, Clock clock) {
    return TestStores.store(
        JdbcUnitOfWork.ownConnection(ds),
        ds,
        CHAIN,
        Map.of(new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE), RESETTING),
        ZoneOffset.UTC,
        clock,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT,
        TestIssueClock.following(clock));
  }

  private static AllocationRequest request(
      String seller,
      String invoiceId,
      Instant finalizedAt,
      Optional<AllocationRequest.ServicePeriod> period) {
    return new AllocationRequest(
        new SeriesScope(seller, "INV", Mode.LIVE),
        invoiceId,
        "",
        "S-" + invoiceId,
        finalizedAt,
        period,
        "r1");
  }

  private static long findings(DataSource ds, String seller, String code) {
    return PostgresSupport.scalarOn(
        ds,
        "SELECT count(*) FROM einvoice_finding WHERE seller_id = '"
            + seller
            + "' AND code = '"
            + code
            + "'");
  }

  /**
   * D-PR36-1. Stripe bills a licensed subscription in advance: the invoice is finalised on 1
   * January for the period 1 January to 1 February. An outage holds it, and it is numbered on 20
   * January. The gap is measured from the END of the period, which is still in the future, so the
   * record says NONE and no late-issue finding is raised, although the invoice was issued 19 days
   * after Stripe finalised it.
   */
  @Test
  void probe_an_advance_billed_period_numbered_weeks_late_is_recorded_as_on_time() {
    DataSource ds = PostgresSupport.dataSource();
    String seller = PostgresSupport.freshSeller("p36adv");
    JdbcIssuanceStore store =
        store(ds, seller, Clock.fixed(Instant.parse("2026-01-20T10:00:00Z"), ZoneOffset.UTC));

    Issuance late =
        store.allocate(
            request(
                seller,
                "in_adv",
                Instant.parse("2026-01-01T10:00:00Z"),
                Optional.of(
                    new AllocationRequest.ServicePeriod(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-02-01T00:00:00Z")))));

    assertThat(late.dating().orElseThrow().issueDate()).isEqualTo(LocalDate.parse("2026-01-20"));
    assertThat(late.dating().orElseThrow().cause()).isNotEqualTo(Optional.of(DateGapCause.NONE));
    assertThat(findings(ds, seller, ErrorCodes.ISSUED_LATE)).isEqualTo(1L);
  }

  /**
   * D-PR36-2. The dates on the issuance row are what the series report shows and what a resumed
   * render prints. A role that outranks the triggers rewrites the row's issue date; the chained
   * event still holds the true one, but the verifier does not compare them and says INTACT.
   */
  @Test
  void probe_a_rewritten_issue_date_on_the_row_is_reported_by_the_verifier() {
    DataSource ds = PostgresSupport.freshDatabase("p36rew");
    String seller = PostgresSupport.freshSeller("p36rew");
    JdbcIssuanceStore store =
        store(ds, seller, Clock.fixed(Instant.parse("2026-03-12T10:00:00Z"), ZoneOffset.UTC));
    store.allocate(
        request(seller, "in_rew", Instant.parse("2026-03-12T09:00:00Z"), Optional.empty()));
    store.markFailed(seller, Mode.LIVE, "in_rew", IssuanceState.FAILED_VALIDATION, "BR-DE-15");

    PostgresSupport.executeOn(ds, "ALTER TABLE einvoice_issuance DISABLE TRIGGER USER");
    PostgresSupport.executeOn(
        ds,
        "UPDATE einvoice_issuance SET issue_date = DATE '2026-03-01'"
            + " WHERE stripe_invoice_id = 'in_rew'");
    PostgresSupport.executeOn(ds, "ALTER TABLE einvoice_issuance ENABLE TRIGGER USER");

    // The rewritten date is what a resumed run would render as BT-2.
    Issuance resumed =
        store.allocate(
            request(seller, "in_rew", Instant.parse("2026-03-12T09:00:00Z"), Optional.empty()));
    assertThat(resumed.dating().orElseThrow().issueDate()).isEqualTo(LocalDate.parse("2026-03-01"));

    IssuanceChainVerifier.Report report = new IssuanceChainVerifier(store, store, KEYRING).verify();
    assertThat(report.status()).isEqualTo(IssuanceChainVerifier.Status.BROKEN);
  }

  /**
   * D-PR36-3. Both clocks step back three days together (one wrong NTP source for the database host
   * and the application host). The cross-check cannot see it, and the series' latest date, written
   * by a real allocation, now reads as "more than one day in the future". The refusal tells the
   * operator to find a hand edit and lower last_issue_date as the schema owner; doing so would let
   * the next invoices be dated before ones already issued. The message must send the operator to
   * the clocks first.
   */
  @Test
  void probe_both_clocks_back_three_days_is_not_sent_to_lowering_the_series_date() {
    DataSource ds = PostgresSupport.dataSource();
    String seller = PostgresSupport.freshSeller("p36back");
    store(ds, seller, Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC))
        .allocate(request(seller, "in_a", Instant.parse("2026-03-10T09:00:00Z"), Optional.empty()));

    JdbcIssuanceStore wrongClocks =
        store(ds, seller, Clock.fixed(Instant.parse("2026-03-07T10:00:00Z"), ZoneOffset.UTC));
    Throwable refused =
        catchThrowable(
            () ->
                wrongClocks.allocate(
                    request(
                        seller, "in_b", Instant.parse("2026-03-07T09:00:00Z"), Optional.empty())));

    assertThat(refused).isInstanceOf(EInvoiceException.class);
    assertThat(refused.getMessage()).contains("NTP");
  }

  /**
   * D-PR36-5. Every store test reads the issue clock through the test seam, which keeps only the
   * DIFFERENCE between two database readings and adds it to a fixed test clock. A production
   * statement that returns a wrong absolute instant (here: the database instant truncated to the
   * day in the session time zone, the "SQL converts in the session zone" bug of design mutation M5)
   * keeps every difference at zero, so the M5 claiming test stays green. This probe reads the
   * production clock itself, on sessions in two other zones, and compares it with the real time. It
   * is green on the reviewed head and red on that mutant.
   */
  @Test
  void probe_the_production_issue_clock_is_the_real_instant_in_any_session_zone() throws Exception {
    DataSource ds = PostgresSupport.dataSource();
    for (String zone : new String[] {"UTC", "Pacific/Kiritimati", "America/Adak"}) {
      try (Connection c = ds.getConnection()) {
        try (Statement st = c.createStatement()) {
          st.execute("SET TIME ZONE '" + zone + "'");
        }
        Instant before = Instant.now();
        Instant read = IssueClock.DATABASE.read(c);
        Instant after = Instant.now();
        assertThat(read).isBetween(before.minus(Duration.ofSeconds(2)), after.plusSeconds(2));
      }
    }
  }

  /**
   * Check, not a finding: eight allocators on one series, one real database clock (the production
   * statement, no seam), the system clock for the cross-check. In counter order, the allocation
   * instants and the issue dates never go down, because the clock is read under the row lock that
   * is held to commit. Green on the reviewed head.
   */
  @Test
  void concurrent_allocators_on_one_series_give_instants_and_dates_in_counter_order()
      throws Exception {
    DataSource ds = PostgresSupport.dataSource();
    String seller = PostgresSupport.freshSeller("p36conc");
    Clock system = Clock.systemUTC();
    JdbcIssuanceStore store =
        TestStores.store(
            JdbcUnitOfWork.ownConnection(ds),
            ds,
            CHAIN,
            Map.of(new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE), RESETTING),
            ZoneOffset.UTC,
            system,
            JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT,
            IssueClock.DATABASE);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      for (int n = 0; n < 64; n++) {
        String id = "in_conc_" + n;
        pool.submit(
            () ->
                store.allocate(
                    request(seller, id, system.instant().minusSeconds(60), Optional.empty())));
      }
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            PostgresSupport.scalarOn(
                ds, "SELECT count(*) FROM einvoice_issuance WHERE seller_id = '" + seller + "'"))
        .isEqualTo(64L);
    assertThat(
            PostgresSupport.scalarOn(
                ds,
                "SELECT count(*) FROM (SELECT allocated_at, issue_date, lag(allocated_at) OVER w AS pa,"
                    + " lag(issue_date) OVER w AS pd FROM einvoice_issuance WHERE seller_id = '"
                    + seller
                    + "' WINDOW w AS (ORDER BY counter)) x WHERE allocated_at < pa OR issue_date < pd"))
        .isZero();
  }
}
