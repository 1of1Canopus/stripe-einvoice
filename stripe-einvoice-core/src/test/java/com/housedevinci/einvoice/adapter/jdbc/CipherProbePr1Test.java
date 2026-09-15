package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
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

  /**
   * D1-04, rewritten against the explicit port that the design review settled on (probe ruling 1).
   *
   * <p>The pass-1 version drove a hand-held pooled connection and expected the store's own {@code
   * getConnection()} to return that same physical connection - which no pool does, and which only
   * an ambient thread-local registry could have arranged. The defect it detects is unchanged: a
   * caller that owns a transaction, allocates inside it and rolls back must not keep the number.
   * The caller now hands its connection over explicitly.
   *
   * <p>It does not close D1-04 on its own: the host-transaction regression tests are the starter's
   * TransactionTemplate and {@code @Transactional} probes.
   */
  @Test
  void probe_a_host_transaction_rollback_does_not_leave_an_allocated_number() throws Exception {
    String seller = PostgresSupport.freshSeller("probe-hosttx");
    try (Connection host = PostgresSupport.dataSource().getConnection()) {
      host.setAutoCommit(false);
      storeOn(JdbcUnitOfWork.using(host), seller)
          .allocate(requestIn(key(seller, 2026), "in_hosttx", NumberAllocatorTest.JANUARY));
      host.rollback();
    }
    assertThat(storeFor(seller, SIX).findBySource(seller, Mode.LIVE, "in_hosttx"))
        .describedAs(
            "the allocation must be part of the caller's unit of work, not a second connection"
                + " that commits on its own")
        .isEmpty();
    assertThat(nextNumberOf(seller))
        .describedAs("and the counter must be back where it was")
        .isEqualTo(1L);
  }

  /** Probe 2: the direct call outside any transaction keeps its old guarantee. */
  @Test
  void probe_an_allocation_outside_any_transaction_still_commits_on_its_own() {
    String seller = PostgresSupport.freshSeller("probe-notx");
    JdbcIssuanceStore store = storeFor(seller, SIX);
    store.allocate(requestIn(key(seller, 2026), "in_notx", NumberAllocatorTest.JANUARY));
    assertThat(store.findBySource(seller, Mode.LIVE, "in_notx"))
        .describedAs("a caller with no transaction of its own must still get a committed number")
        .isPresent();
  }

  /** Probe 3: a caller-owned connection in auto-commit is refused before anything is written. */
  @Test
  void probe_a_caller_connection_in_autocommit_is_refused_rather_than_silently_committed()
      throws Exception {
    String seller = PostgresSupport.freshSeller("probe-autocommit");
    storeFor(seller, SIX).openSeries(key(seller, 2026));
    try (Connection host = PostgresSupport.dataSource().getConnection()) {
      assertThat(host.getAutoCommit()).isTrue();
      JdbcIssuanceStore store = storeOn(JdbcUnitOfWork.using(host), seller);
      assertThatThrownBy(
              () ->
                  store.allocate(
                      requestIn(key(seller, 2026), "in_autocommit", NumberAllocatorTest.JANUARY)))
          .describedAs(
              "statement-by-statement commits under a caller that believes it owns a"
                  + " transaction is the defect, not a convenience")
          .isInstanceOf(EInvoiceException.class)
          .extracting(e -> ((EInvoiceException) e).code())
          .isEqualTo(ErrorCodes.HOST_AUTOCOMMIT);
    }
    assertThat(nextNumberOf(seller)).describedAs("and nothing was written").isEqualTo(1L);
  }

  /** Probe 8: the caller's lock_timeout is restored, on the succeeding and the failing path. */
  @Test
  void probe_the_callers_lock_timeout_is_restored_after_an_allocation() throws Exception {
    String seller = PostgresSupport.freshSeller("probe-locktimeout");
    try (Connection host = PostgresSupport.dataSource().getConnection()) {
      host.setAutoCommit(false);
      try (Statement st = host.createStatement()) {
        st.execute("SET LOCAL lock_timeout = '7331ms'");
      }
      JdbcIssuanceStore store = storeOn(JdbcUnitOfWork.using(host), seller);
      store.allocate(requestIn(key(seller, 2026), "in_lt1", NumberAllocatorTest.JANUARY));
      assertThat(lockTimeoutOf(host))
          .describedAs("the caller's own later statements must keep the caller's timeout")
          .isEqualTo("7331ms");

      // And on the failing path: the same caller-owned transaction disposes (taking the chain
      // lock) and then asks for another number, which is refused by the lock-order rule after our
      // SET LOCAL has already run.
      store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_lt1", "lock timeout probe", ""));
      assertThatThrownBy(
              () ->
                  store.allocate(
                      requestIn(key(seller, 2026), "in_lt2", NumberAllocatorTest.JANUARY)))
          .isInstanceOf(EInvoiceException.class)
          .extracting(e -> ((EInvoiceException) e).code())
          .isEqualTo(ErrorCodes.LOCK_ORDER_VIOLATION);
      assertThat(lockTimeoutOf(host))
          .describedAs("a refusal must not leave our timeout on the caller's transaction")
          .isEqualTo("7331ms");
      host.rollback();
    }
  }

  private static String lockTimeoutOf(Connection c) throws Exception {
    try (Statement st = c.createStatement();
        var rs = st.executeQuery("SHOW lock_timeout")) {
      return rs.next() ? rs.getString(1) : "";
    }
  }

  private static long nextNumberOf(String seller) {
    return PostgresSupport.scalar(
        "SELECT coalesce((SELECT next_number FROM einvoice_series WHERE seller_id = '"
            + seller
            + "' AND series = 'DEFAULT' AND fiscal_year = 2026 AND mode = 'live'), 1)");
  }

  private static JdbcIssuanceStore storeOn(JdbcUnitOfWork unitOfWork, String seller) {
    return storeOn(unitOfWork, seller, SIX);
  }

  private static JdbcIssuanceStore storeOn(
      JdbcUnitOfWork unitOfWork, String seller, SeriesDefinition definition) {
    return new JdbcIssuanceStore(
        unitOfWork,
        PostgresSupport.dataSource(),
        CHAIN,
        Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), definition),
        CLOCK,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT);
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
