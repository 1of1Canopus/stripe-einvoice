package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.ScreenedText;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Cipher probes for PR 1. Each one FAILS on 71ce8d6 and must go green with the fix. */
class CipherProbePr1Test {

  private static SeriesKey key(String seller, int year) {
    return new SeriesKey(seller, "DEFAULT", year, Mode.LIVE);
  }

  private static AllocationRequest requestIn(SeriesKey k, String invoiceId, Instant issuedAt) {
    return new AllocationRequest(k, invoiceId, "", "STRIPE-X", issuedAt, "fr-2026.1");
  }

  private static JdbcIssuanceStore storeFor(String seller, SeriesDefinition def) {
    return new JdbcIssuanceStore(
        PostgresSupport.dataSource(),
        CHAIN,
        Map.of(
            new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), def,
            new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.TEST), def),
        CLOCK);
  }

  // D1-01
  @Test
  void probe_a_new_fiscal_year_does_not_reissue_the_previous_years_legal_number() {
    String seller = PostgresSupport.freshSeller("probe-year");
    JdbcIssuanceStore store = storeFor(seller, SIX);
    Issuance a =
        store.allocate(
            requestIn(key(seller, 2026), "in_y2026", Instant.parse("2026-06-01T10:00:00Z")));
    Issuance b =
        store.allocate(
            requestIn(key(seller, 2027), "in_y2027", Instant.parse("2027-06-01T10:00:00Z")));
    assertThat(b.legalNumber().value())
        .describedAs("two legal documents of one seller must never carry the same number")
        .isNotEqualTo(a.legalNumber().value());
  }

  // D1-02
  @Test
  void probe_the_disposition_cross_check_is_keyed_per_row_not_per_number() {
    DataSource ds = PostgresSupport.freshDatabase("probexcheck");
    String seller = "seller-xcheck";
    JdbcIssuanceStore store =
        new JdbcIssuanceStore(
            ds,
            CHAIN,
            Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
            CLOCK);
    store.allocate(requestIn(key(seller, 2026), "in_a", Instant.parse("2026-06-01T10:00:00Z")));
    store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_a", "probe disposition", ""));
    // An out-of-band row, inserted by a host's own JdbcTemplate, already disposed, carrying the
    // same legal number in the next fiscal year. No chained event exists for it.
    PostgresSupport.executeOn(
        ds,
        "INSERT INTO einvoice_issuance (seller_id, mode, stripe_invoice_id, series, fiscal_year,"
            + " legal_number, counter, issued_at, allocated_at, rule_pack_version, state,"
            + " void_reason) VALUES ('"
            + seller
            + "','live','in_forged','DEFAULT',2027,'INV-2026-000001',1, now(), now(), 'x',"
            + " 'VOID_UNUSED','forged')");
    IssuanceChainVerifier verifier =
        new IssuanceChainVerifier(
            store, store, Map.of("k1", "0123456789abcdef0123456789abcdef".getBytes()));
    assertThat(verifier.verify().status())
        .describedAs("an out-of-band disposed row must not be vouched for by another row's event")
        .isEqualTo(IssuanceChainVerifier.Status.BROKEN);
  }

  // D1-03
  @Test
  void probe_the_rendered_number_follows_the_series_row_not_todays_properties() {
    String seller = PostgresSupport.freshSeller("probe-prefix");
    storeFor(seller, SIX)
        .allocate(requestIn(key(seller, 2026), "in_p1", NumberAllocatorTest.JANUARY));
    // The operator edits einvoice.numbering.prefix/width and restarts. The series row is unchanged
    // and its prefix and width are trigger-immutable; the renderer must follow the row.
    JdbcIssuanceStore afterEdit = storeFor(seller, new SeriesDefinition("ZZZ-", 8, true));
    Issuance second =
        afterEdit.allocate(requestIn(key(seller, 2026), "in_p2", NumberAllocatorTest.JANUARY));
    assertThat(second.legalNumber().value())
        .describedAs("the format of a legal series must not change because a property changed")
        .isEqualTo("INV-2026-000002");
  }

  // D1-04
  @Test
  void probe_a_host_transaction_rollback_does_not_leave_an_allocated_number() throws Exception {
    String seller = PostgresSupport.freshSeller("probe-hosttx");
    JdbcIssuanceStore store = storeFor(seller, SIX);
    try (Connection host = PostgresSupport.dataSource().getConnection()) {
      host.setAutoCommit(false);
      store.allocate(requestIn(key(seller, 2026), "in_hosttx", NumberAllocatorTest.JANUARY));
      host.rollback();
    }
    assertThat(store.findBySource(seller, Mode.LIVE, "in_hosttx"))
        .describedAs(
            "the allocation must be part of the caller's unit of work, not a second connection"
                + " that commits on its own")
        .isEmpty();
  }

  // D1-05
  @Test
  void probe_a_blocked_allocation_gives_up_after_the_configured_timeout() throws Exception {
    String seller = PostgresSupport.freshSeller("probe-timeout");
    JdbcIssuanceStore store = storeFor(seller, SIX);
    store.allocate(requestIn(key(seller, 2026), "in_t0", NumberAllocatorTest.JANUARY));
    try (Connection blocker = PostgresSupport.dataSource().getConnection()) {
      blocker.setAutoCommit(false);
      try (Statement st = blocker.createStatement()) {
        st.execute(
            "SELECT 1 FROM einvoice_series WHERE seller_id = '"
                + seller
                + "' AND series = 'DEFAULT' AND fiscal_year = 2026 AND mode = 'live' FOR UPDATE");
      }
      CompletableFuture<Object> blocked =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return store.allocate(
                      requestIn(key(seller, 2026), "in_t1", NumberAllocatorTest.JANUARY));
                } catch (RuntimeException e) {
                  return e;
                }
              });
      Object outcome = blocked.get(20, TimeUnit.SECONDS);
      assertThat(outcome)
          .describedAs("einvoice.numbering.allocation-timeout must bound the wait on the row lock")
          .isInstanceOf(RuntimeException.class);
      blocker.rollback();
    }
  }

  // D1-06
  @Test
  void probe_a_value_of_unicode_whitespace_only_is_refused_by_the_screening_function() {
    String nbspOnly = "  ​";
    try {
      String kept = ScreenedText.screen("void reason", nbspOnly, 100);
      org.assertj.core.api.Assertions.fail(
          "a value that renders blank must be refused; screen() returned "
              + kept.length()
              + " characters");
    } catch (com.housedevinci.einvoice.domain.EInvoiceException expected) {
      assertThat(expected.getMessage()).contains("blank");
    }
  }

  // D1-08
  @Test
  void probe_an_empty_document_hash_reads_back_as_an_empty_string() {
    String seller = PostgresSupport.freshSeller("probe-char");
    JdbcIssuanceStore store = storeFor(seller, SIX);
    store.allocate(requestIn(key(seller, 2026), "in_char", NumberAllocatorTest.JANUARY));
    Issuance read = store.findBySource(seller, Mode.LIVE, "in_char").orElseThrow();
    assertThat(read.documentSha256())
        .describedAs(
            "char(64) DEFAULT '' blank-pads: the same logical value has two spellings, one of"
                + " which reaches hashed material")
        .isEmpty();
  }
}
