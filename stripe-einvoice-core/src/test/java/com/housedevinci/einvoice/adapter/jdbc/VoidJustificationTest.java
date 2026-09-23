package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Why a legal number was burned or voided is evidence, not a note (RC-02).
 *
 * <p>Two lines hold it. Line two: the trigger lets the void justification change only in the
 * statement that records the disposition, so the runtime role - which needs {@code UPDATE} on this
 * table for the state machine to work at all - cannot rewrite it afterwards. Line one: the
 * verifier's cross-check compares the row's justification with the chained event's, so a rewrite by
 * a role that outranks the triggers is still reported.
 *
 * <p>Each test runs on its own database: a forged row is not something to leave behind.
 */
class VoidJustificationTest {

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

  @Test
  void a_burn_records_its_rule_id_on_the_row_as_well_as_in_the_chain() {
    Fixture fixture = onItsOwnDatabase("burnrow");
    fixture.store().allocate(request(key(fixture.seller()), "in_burn_row"));

    fixture
        .store()
        .markFailed(
            fixture.seller(), Mode.LIVE, "in_burn_row", IssuanceState.FAILED_VALIDATION, RULE);

    // The auditor-facing series report reads the row. A column the row never carried could not be
    // compared with the chain either, which is how a rewrite went unreported.
    assertThat(
            PostgresSupport.scalarOn(
                fixture.dataSource(),
                "SELECT count(*) FROM einvoice_issuance WHERE void_rule_id = '" + RULE + "'"))
        .isEqualTo(1);
    assertThat(
            new IssuanceChainVerifier(fixture.store(), fixture.store(), KEYRING).verify().status())
        .isEqualTo(IssuanceChainVerifier.Status.INTACT);
  }

  @Test
  void the_void_still_replaces_the_burns_rule_id_in_the_statement_that_voids() {
    Fixture fixture = onItsOwnDatabase("voidrow");
    fixture.store().allocate(request(key(fixture.seller()), "in_void_row"));
    fixture
        .store()
        .markFailed(
            fixture.seller(), Mode.LIVE, "in_void_row", IssuanceState.FAILED_VALIDATION, RULE);

    fixture
        .store()
        .voidUnused(
            new VoidRequest(
                fixture.seller(),
                Mode.LIVE,
                "in_void_row",
                "the buyer data cannot be corrected",
                "BR-DE-16"));

    assertThat(
            PostgresSupport.scalarOn(
                fixture.dataSource(),
                "SELECT count(*) FROM einvoice_issuance WHERE void_rule_id = 'BR-DE-16'"
                    + " AND void_reason = 'the buyer data cannot be corrected'"))
        .isEqualTo(1);
    assertThat(
            new IssuanceChainVerifier(fixture.store(), fixture.store(), KEYRING).verify().status())
        .isEqualTo(IssuanceChainVerifier.Status.INTACT);
  }

  @Test
  void a_bare_update_of_the_justification_is_refused_on_a_burned_row() {
    Fixture fixture = onItsOwnDatabase("burnfreeze");
    fixture.store().allocate(request(key(fixture.seller()), "in_burn_freeze"));
    fixture
        .store()
        .markFailed(
            fixture.seller(), Mode.LIVE, "in_burn_freeze", IssuanceState.FAILED_VALIDATION, RULE);

    Throwable refused =
        catchThrowable(
            () ->
                PostgresSupport.executeOn(
                    fixture.dataSource(),
                    "UPDATE einvoice_issuance SET void_reason = 'buyer asked us to cancel',"
                        + " void_rule_id = 'BR-CL-01' WHERE stripe_invoice_id = 'in_burn_freeze'"));

    assertThat(refused).isNotNull();
    assertThat(
            PostgresSupport.scalarOn(
                fixture.dataSource(),
                "SELECT count(*) FROM einvoice_issuance WHERE void_rule_id = '" + RULE + "'"))
        .isEqualTo(1);
  }

  @Test
  void a_bare_update_of_the_justification_is_refused_on_a_voided_row() {
    Fixture fixture = onItsOwnDatabase("voidfreeze");
    fixture.store().allocate(request(key(fixture.seller()), "in_void_freeze"));
    fixture
        .store()
        .voidUnused(
            new VoidRequest(fixture.seller(), Mode.LIVE, "in_void_freeze", "never rendered", RULE));

    Throwable refused =
        catchThrowable(
            () ->
                PostgresSupport.executeOn(
                    fixture.dataSource(),
                    "UPDATE einvoice_issuance SET void_reason = 'a different story'"
                        + " WHERE stripe_invoice_id = 'in_void_freeze'"));

    assertThat(refused).isNotNull();
    assertThat(
            new IssuanceChainVerifier(fixture.store(), fixture.store(), KEYRING).verify().status())
        .isEqualTo(IssuanceChainVerifier.Status.INTACT);
  }

  @Test
  void a_rewrite_by_a_role_that_outranks_the_triggers_is_reported_by_the_cross_check() {
    Fixture fixture = onItsOwnDatabase("outrank");
    fixture.store().allocate(request(key(fixture.seller()), "in_outrank"));
    fixture
        .store()
        .markFailed(
            fixture.seller(), Mode.LIVE, "in_outrank", IssuanceState.FAILED_VALIDATION, RULE);

    PostgresSupport.executeOn(
        fixture.dataSource(), "ALTER TABLE einvoice_issuance DISABLE TRIGGER USER");
    PostgresSupport.executeOn(
        fixture.dataSource(),
        "UPDATE einvoice_issuance SET void_rule_id = 'BR-CL-01' WHERE stripe_invoice_id ="
            + " 'in_outrank'");
    PostgresSupport.executeOn(
        fixture.dataSource(), "ALTER TABLE einvoice_issuance ENABLE TRIGGER USER");

    IssuanceChainVerifier.Report report =
        new IssuanceChainVerifier(fixture.store(), fixture.store(), KEYRING).verify();

    assertThat(report.status()).isEqualTo(IssuanceChainVerifier.Status.BROKEN);
    // The hashes still recompute: it is the comparison with the row, not the walk, that caught it.
    assertThat(report.brokenAtSequence()).isEqualTo(-1);
    assertThat(report.unchainedDispositions()).isEqualTo(1);
  }
}
