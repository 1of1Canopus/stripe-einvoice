package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The issuance log's chain, and the cross-check that makes it cover the one path no scan and no
 * grant can close: the host's own raw JDBC against our tables.
 */
class IssuanceChainVerifierTest {

  private static final Map<String, byte[]> KEYRING =
      Map.of("k1", "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

  private static IssuanceChainVerifier verifier(JdbcIssuanceStore store) {
    return new IssuanceChainVerifier(store, store, KEYRING);
  }

  private record Fixture(DataSource dataSource, JdbcIssuanceStore store, String seller) {}

  private static Fixture onItsOwnDatabase(String hint, IssuanceChain chain) {
    DataSource ds = PostgresSupport.freshDatabase(hint);
    String seller = PostgresSupport.freshSeller(hint);
    SeriesDefinition definition = SIX;
    JdbcIssuanceStore store =
        new JdbcIssuanceStore(
            ds,
            chain,
            Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), definition),
            CLOCK);
    return new Fixture(ds, store, seller);
  }

  @Test
  void a_chained_disposition_verifies_intact() {
    Fixture fixture = onItsOwnDatabase("chainok", CHAIN);
    String seller = fixture.seller();
    JdbcIssuanceStore store = fixture.store();
    store.allocate(request(key(seller), "in_chain_1"));
    store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_chain_1", "wrong customer", "OPS-1"));

    IssuanceChainVerifier.Report report = verifier(store).verify();

    assertThat(report.status()).isEqualTo(IssuanceChainVerifier.Status.INTACT);
    assertThat(report.intact()).isTrue();
    assertThat(report.keyed()).isTrue();
    assertThat(report.keyIds()).contains("k1");
    assertThat(report.unchainedDispositions()).isZero();
  }

  @Test
  void an_unkeyed_log_is_never_reported_as_intact() {
    // A separate word for a separate property: an unkeyed log can be recomputed by anyone who can
    // write a row, so a clean unkeyed result must not read like a clean keyed one.
    Fixture fixture = onItsOwnDatabase("unkeyed", IssuanceChain.unkeyed());
    String seller = fixture.seller();
    JdbcIssuanceStore store = fixture.store();
    store.allocate(request(key(seller), "in_unkeyed"));
    store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_unkeyed", "mode check", ""));

    IssuanceChainVerifier.Report report =
        new IssuanceChainVerifier(store, store, Map.of()).verify();

    assertThat(report.status()).isEqualTo(IssuanceChainVerifier.Status.INTACT_UNKEYED);
    assertThat(report.status()).isNotEqualTo(IssuanceChainVerifier.Status.INTACT);
    assertThat(report.keyed()).isFalse();
  }

  @Test
  void a_rewritten_chain_row_is_reported_broken() {
    Fixture fixture = onItsOwnDatabase("tamper", CHAIN);
    String seller = fixture.seller();
    JdbcIssuanceStore store = fixture.store();
    store.allocate(request(key(seller), "in_tamper"));
    store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_tamper", "original reason", ""));
    // The trigger refuses an UPDATE for every role, so a tamper has to be simulated the way a
    // table owner would actually do it: disable the trigger, rewrite, re-enable.
    PostgresSupport.executeOn(
        fixture.dataSource(),
        "ALTER TABLE einvoice_issuance_event DISABLE TRIGGER einvoice_issuance_event_append_only");
    PostgresSupport.executeOn(
        fixture.dataSource(),
        "UPDATE einvoice_issuance_event SET void_reason = 'a reason nobody gave' WHERE seller_id"
            + " = '"
            + seller
            + "'");
    PostgresSupport.executeOn(
        fixture.dataSource(),
        "ALTER TABLE einvoice_issuance_event ENABLE TRIGGER einvoice_issuance_event_append_only");

    assertThat(verifier(store).verify().status()).isEqualTo(IssuanceChainVerifier.Status.BROKEN);
  }

  @Test
  void a_disposed_row_written_out_of_band_is_reported_broken() {
    // The residual path the numbering design names and does not pretend to close with a scan: the
    // host's own JdbcTemplate. It cannot be seen by the persistence-mapping guard and the runtime
    // role must keep INSERT for this module to work at all. What catches it is the chain.
    Fixture fixture = onItsOwnDatabase("oob", CHAIN);
    String seller = fixture.seller();
    JdbcIssuanceStore store = fixture.store();
    store.allocate(request(key(seller), "in_oob_1"));
    store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_oob_1", "a real void", ""));
    assertThat(verifier(store).verify().status()).isEqualTo(IssuanceChainVerifier.Status.INTACT);

    PostgresSupport.executeOn(
        fixture.dataSource(),
        "INSERT INTO einvoice_issuance (seller_id, mode, stripe_invoice_id, series, fiscal_year,"
            + " legal_number, counter, issued_at, allocated_at, state) VALUES ('"
            + seller
            + "', 'live', 'in_forged', 'DEFAULT', 2026, 'INV-2026-009999', 9999, now(), now(),"
            + " 'ISSUED')");

    IssuanceChainVerifier.Report report = verifier(store).verify();
    assertThat(report.status()).isEqualTo(IssuanceChainVerifier.Status.BROKEN);
    assertThat(report.unchainedDispositions()).isPositive();
  }
}
