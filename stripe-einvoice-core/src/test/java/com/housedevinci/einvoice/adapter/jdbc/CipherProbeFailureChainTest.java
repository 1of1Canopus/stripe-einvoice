package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * D7-03: the justification for a burned number lives in the chain, not only on a mutable row.
 *
 * <p>The auditor's question is "why is number 3 missing from the issued sequence". Until this
 * change the only answer was the issuance row's state column - a column that legitimately changes -
 * while the chain, which is what this module offers as its tamper-evident record, said nothing at
 * all about the number at all.
 *
 * <p>Each probe runs on its own database: the chain is global to a database, and a forged row is
 * not something to leave behind for another test.
 */
class CipherProbeFailureChainTest {

  private static final Map<String, byte[]> KEYRING =
      Map.of("k1", "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  private static final String RULE = "BR-DE-15";

  private record Fixture(DataSource dataSource, JdbcIssuanceStore store, String seller) {}

  private static Fixture onItsOwnDatabase(String hint) {
    DataSource ds = PostgresSupport.freshDatabase(hint);
    String seller = PostgresSupport.freshSeller(hint);
    SeriesDefinition definition = SIX;
    JdbcIssuanceStore store =
        new JdbcIssuanceStore(
            ds,
            CHAIN,
            Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), definition),
            CLOCK);
    return new Fixture(ds, store, seller);
  }

  private static long scalarOn(DataSource ds, String sql) {
    return PostgresSupport.scalarOn(ds, sql);
  }

  @Test
  void probe_a_number_burned_by_a_validation_refusal_is_explained_in_the_chain() {
    Fixture fixture = onItsOwnDatabase("burned");
    JdbcIssuanceStore store = fixture.store();
    store.allocate(request(key(fixture.seller()), "in_burned_1"));

    store.markFailed(
        fixture.seller(), Mode.LIVE, "in_burned_1", IssuanceState.FAILED_VALIDATION, RULE);

    assertThat(
            scalarOn(
                fixture.dataSource(),
                "SELECT count(*) FROM einvoice_issuance_event WHERE state = 'FAILED_VALIDATION'"
                    + " AND void_rule_id = '"
                    + RULE
                    + "'"))
        .isEqualTo(1);
    IssuanceChainVerifier.Report report = new IssuanceChainVerifier(store, store, KEYRING).verify();
    assertThat(report.status()).isEqualTo(IssuanceChainVerifier.Status.INTACT);
    assertThat(report.unchainedDispositions()).isZero();
    assertThat(report.verified()).isEqualTo(1);
  }

  @Test
  void probe_a_render_refusal_carries_its_code_where_a_rule_id_goes() {
    Fixture fixture = onItsOwnDatabase("render");
    JdbcIssuanceStore store = fixture.store();
    store.allocate(request(key(fixture.seller()), "in_render_1"));

    store.markFailed(
        fixture.seller(),
        Mode.LIVE,
        "in_render_1",
        IssuanceState.FAILED_VALIDATION,
        ErrorCodes.RENDER_FAILED);

    assertThat(
            scalarOn(
                fixture.dataSource(),
                "SELECT count(*) FROM einvoice_issuance_event WHERE void_rule_id = '"
                    + ErrorCodes.RENDER_FAILED
                    + "'"))
        .isEqualTo(1);
  }

  @Test
  void probe_a_burned_row_written_out_of_band_is_reported_broken() {
    Fixture fixture = onItsOwnDatabase("burnedoob");
    JdbcIssuanceStore store = fixture.store();
    store.allocate(request(key(fixture.seller()), "in_burned_oob"));
    store.markFailed(
        fixture.seller(), Mode.LIVE, "in_burned_oob", IssuanceState.FAILED_VALIDATION, RULE);
    assertThat(new IssuanceChainVerifier(store, store, KEYRING).verify().status())
        .isEqualTo(IssuanceChainVerifier.Status.INTACT);

    // The host's own JDBC: a burned number invented out of band, with no chained event that agrees
    // with it. Before D7-03 this row was invisible to the verifier - no burned row was chained, so
    // none could be cross-checked.
    PostgresSupport.executeOn(
        fixture.dataSource(),
        "INSERT INTO einvoice_issuance (seller_id, mode, stripe_invoice_id, series, fiscal_year,"
            + " legal_number, counter, issued_at, allocated_at, state) VALUES ('"
            + fixture.seller()
            + "', 'live', 'in_forged_burn', 'DEFAULT', 2026, 'INV-2026-009999', 9999, now(),"
            + " now(), 'FAILED_VALIDATION')");

    IssuanceChainVerifier.Report report = new IssuanceChainVerifier(store, store, KEYRING).verify();
    assertThat(report.status()).isEqualTo(IssuanceChainVerifier.Status.BROKEN);
    assertThat(report.unchainedDispositions()).isEqualTo(1);
  }

  @Test
  void probe_a_retryable_archive_failure_is_not_chained() {
    Fixture fixture = onItsOwnDatabase("archfail");
    JdbcIssuanceStore store = fixture.store();
    store.allocate(request(key(fixture.seller()), "in_arch_1"));

    store.markFailed(fixture.seller(), Mode.LIVE, "in_arch_1", IssuanceState.FAILED_ARCHIVE, "");

    // Retryable, so not a disposition: the number may still be issued, and one chain row per retry
    // cycle would be noise in the record an auditor reads. Its eventual fate - ISSUED or
    // VOID_UNUSED - is chained.
    assertThat(scalarOn(fixture.dataSource(), "SELECT count(*) FROM einvoice_issuance_event"))
        .isZero();
    assertThat(new IssuanceChainVerifier(store, store, KEYRING).verify().status())
        .isEqualTo(IssuanceChainVerifier.Status.EMPTY);
  }
}
