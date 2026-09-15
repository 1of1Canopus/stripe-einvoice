package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.SeriesReport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The allocator's own behaviour, against a real PostgreSQL. */
class NumberAllocatorTest {

  static final SeriesDefinition SIX = new SeriesDefinition("INV-2026-", 6, true);
  static final Instant JANUARY = Instant.parse("2026-01-15T10:00:00Z");
  static final Clock CLOCK = Clock.fixed(JANUARY, ZoneOffset.UTC);
  static final IssuanceChain CHAIN =
      IssuanceChain.keyed(
          "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "k1");

  static AllocationRequest request(SeriesKey key, String invoiceId) {
    return new AllocationRequest(key, invoiceId, "", "STRIPE-0001", JANUARY, "fr-2026.1");
  }

  static SeriesKey key(String seller) {
    return new SeriesKey(seller, "DEFAULT", 2026, Mode.LIVE);
  }

  @Test
  void the_first_allocation_of_a_fresh_series_returns_one() {
    // N-09: RETURNING next_number - 1 yields the pre-increment value, and next_number is seeded at
    // 1. Both conventions are one refactor away from being inverted, and the error would be
    // invisible except as a wrong first invoice.
    String seller = PostgresSupport.freshSeller("first");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    Issuance issuance = store.allocate(request(key(seller), "in_first"));

    assertThat(issuance.legalNumber().counter()).isEqualTo(1L);
    assertThat(issuance.legalNumber().value()).isEqualTo("INV-2026-000001");
    assertThat(issuance.state()).isEqualTo(IssuanceState.NUMBERED);
  }

  @Test
  void a_redelivered_stripe_invoice_resumes_the_same_number() {
    String seller = PostgresSupport.freshSeller("resume");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    Issuance first = store.allocate(request(key(seller), "in_resume"));
    Issuance again = store.allocate(request(key(seller), "in_resume"));

    assertThat(again.legalNumber().value()).isEqualTo(first.legalNumber().value());
    assertThat(again.id()).isEqualTo(first.id());
    assertThat(
            PostgresSupport.scalar(
                "SELECT next_number FROM einvoice_series WHERE seller_id = '" + seller + "'"))
        .isEqualTo(2L);
  }

  @Test
  void a_missing_series_is_refused_and_never_auto_created() {
    String seller = PostgresSupport.freshSeller("unconfigured");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    SeriesKey unknown = new SeriesKey(seller, "OTHER", 2026, Mode.LIVE);

    assertThatThrownBy(() -> store.allocate(request(unknown, "in_unknown")))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.SERIES_NOT_CONFIGURED);
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_series WHERE series = 'OTHER' AND seller_id = '"
                    + seller
                    + "'"))
        .isZero();
  }

  @Test
  void a_test_mode_event_never_touches_the_live_series() {
    // D-01: the mode is in the series primary key, so a test event cannot consume a live number by
    // construction rather than by a filter someone can forget.
    String seller = PostgresSupport.freshSeller("mode");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_live_1"));
    store.allocate(request(key(seller), "in_live_2"));

    Issuance test =
        store.allocate(request(new SeriesKey(seller, "DEFAULT", 2026, Mode.TEST), "in_test_1"));

    assertThat(test.legalNumber().counter()).isEqualTo(1L);
    assertThat(
            PostgresSupport.scalar(
                "SELECT next_number FROM einvoice_series WHERE seller_id = '"
                    + seller
                    + "' AND mode = 'live'"))
        .isEqualTo(3L);
  }

  @Test
  void the_first_invoice_of_a_new_fiscal_year_allocates_without_a_restart() {
    // N-01: the row for the new year is created inside the allocation transaction. An application
    // last restarted in November has no 2027 row, and every invoice after midnight on 1 January
    // would otherwise fail until someone restarted it.
    String seller = PostgresSupport.freshSeller("rollover");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_2026_last"));

    Instant newYear = Instant.parse("2027-01-01T00:00:03Z");
    SeriesKey next = new SeriesKey(seller, "DEFAULT", 2027, Mode.LIVE);
    Issuance first =
        store.allocate(
            new AllocationRequest(next, "in_2027_first", "", "STRIPE-0002", newYear, "fr-2027.1"));

    assertThat(first.legalNumber().counter()).isEqualTo(1L);
    assertThat(
            PostgresSupport.scalar(
                "SELECT next_number FROM einvoice_series WHERE seller_id = '"
                    + seller
                    + "' AND fiscal_year = 2027"))
        .isEqualTo(2L);
  }

  @Test
  void a_series_at_its_last_number_refuses_the_next_allocation() {
    // N-02: at 10^width the padding stops padding and the number silently gets wider. A legal
    // series does not change format mid-way, so the counter is refused before it is consumed.
    String seller = PostgresSupport.freshSeller("exhausted");
    SeriesDefinition narrow = new SeriesDefinition("SMALL-", 4, true);
    JdbcIssuanceStore store =
        new JdbcIssuanceStore(
            PostgresSupport.dataSource(),
            CHAIN,
            Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), narrow),
            CLOCK);
    // Seeded near the boundary by inserting the series row directly: the monotonic trigger refuses
    // to let anything jump a counter forward, which is the point of it.
    PostgresSupport.execute(
        "INSERT INTO einvoice_series"
            + " (seller_id, series, fiscal_year, mode, prefix, width, next_number, updated_at)"
            + " VALUES ('"
            + seller
            + "', 'DEFAULT', 2026, 'live', 'SMALL-', 4, 9999, now())");

    Issuance last = store.allocate(request(key(seller), "in_narrow_last"));
    assertThat(last.legalNumber().value()).isEqualTo("SMALL-9999");

    assertThatThrownBy(() -> store.allocate(request(key(seller), "in_narrow_over")))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.SERIES_EXHAUSTED);
    assertThat(
            PostgresSupport.scalar(
                "SELECT next_number FROM einvoice_series WHERE seller_id = '" + seller + "'"))
        .isEqualTo(10000L);
  }

  @Test
  void a_void_of_an_issued_number_is_refused_by_the_enum() {
    String seller = PostgresSupport.freshSeller("void-issued");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_issued"));
    PostgresSupport.execute(
        "UPDATE einvoice_issuance SET state = 'ARCHIVING' WHERE seller_id = '" + seller + "'");
    PostgresSupport.execute(
        "UPDATE einvoice_issuance SET state = 'ISSUED' WHERE seller_id = '" + seller + "'");

    assertThatThrownBy(
            () ->
                store.voidUnused(
                    new VoidRequest(seller, Mode.LIVE, "in_issued", "customer cancelled", "")))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
  }

  @Test
  void a_voided_number_keeps_its_reason_and_is_never_reallocated() {
    String seller = PostgresSupport.freshSeller("void");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_void"));
    Issuance voided =
        store.voidUnused(
            new VoidRequest(
                seller, Mode.LIVE, "in_void", "schematron BR-CO-10 on BT-1", "BR-CO-10"));

    assertThat(voided.state()).isEqualTo(IssuanceState.VOID_UNUSED);
    assertThat(voided.voidedReason()).contains("schematron BR-CO-10 on BT-1");
    assertThat(voided.voidedRuleId()).contains("BR-CO-10");

    Issuance next = store.allocate(request(key(seller), "in_after_void"));
    assertThat(next.legalNumber().counter()).isEqualTo(2L);
  }

  @Test
  void the_series_report_enumerates_every_allocated_number_with_its_disposition() {
    // The deviation-3 ruling: an unexplained hole and a hole with a chained reason must never look
    // alike to an auditor, so the report lists every number, its disposition and the open count.
    String seller = PostgresSupport.freshSeller("report");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_r1"));
    store.allocate(request(key(seller), "in_r2"));
    store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_r2", "duplicate sale", "OPS-2"));
    store.allocate(request(key(seller), "in_r3"));

    SeriesReport report = store.seriesReport(key(seller));

    assertThat(report.lines()).hasSize(3);
    assertThat(report.contiguous()).isTrue();
    assertThat(report.openCount()).isEqualTo(2L);
    assertThat(report.nextNumber()).isEqualTo(4L);
    assertThat(report.lines().get(1).state()).isEqualTo(IssuanceState.VOID_UNUSED);
    assertThat(report.lines().get(1).reason()).contains("duplicate sale");
    // N-08: both timestamps are on the line, so a late invoice from a closing year can be explained
    // on the page where the question is asked.
    assertThat(report.lines().get(0).issuedAt()).isEqualTo(JANUARY);
    assertThat(report.lines().get(0).allocatedAt()).isEqualTo(JANUARY);
  }

  @Test
  void a_void_reason_with_control_characters_is_refused() {
    String seller = PostgresSupport.freshSeller("void-screen");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_screen"));
    String withControl = "cancelled" + (char) 7 + " by ops";

    assertThatThrownBy(
            () ->
                store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_screen", withControl, "")))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INVALID);
  }
}
