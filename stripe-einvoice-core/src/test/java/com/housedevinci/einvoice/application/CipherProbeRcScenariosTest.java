package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import org.junit.jupiter.api.Test;

/**
 * Release-candidate probes: mechanisms built in different pull requests, interacting on the merged
 * tree.
 */
class CipherProbeRcScenariosTest {

  private static final String INVOICE_ID = "in_rc_void_1";

  /**
   * RC-01. The prescribed operator remedy for a burned number is a void. A later event for the same
   * invoice then resumes onto the VOID_UNUSED row, and every state write from there is an illegal
   * transition that escapes {@code process}, leaving the inbound row MAPPED - which the due query
   * re-picks on every sweep, for ever, with a Stripe re-fetch and a full render each time.
   */
  @Test
  void probe_an_event_after_a_void_neither_loops_nor_escapes() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWith(TestValidators.failing("BR-DE-15"));
    harness.source().with(TestInvoices.finalised(INVOICE_ID));

    String first = harness.receive("invoice.finalized", INVOICE_ID);
    assertThat(harness.unitOfWork().process(first).state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(harness.issuance(INVOICE_ID).orElseThrow().state())
        .isEqualTo(IssuanceState.FAILED_VALIDATION);

    // The documented remedy: an operator voids the burned number with a reason.
    harness
        .store()
        .voidUnused(
            new VoidRequest(
                harness.sellerId(),
                Mode.LIVE,
                INVOICE_ID,
                "validation refused, number will never carry a document",
                "BR-DE-15"));
    assertThat(harness.issuance(INVOICE_ID).orElseThrow().state())
        .isEqualTo(IssuanceState.VOID_UNUSED);

    // A later event for the same invoice - invoice.paid days after the void, or the sweep's own
    // re-enqueue of an invoice it can see has no ISSUED document.
    String second = harness.receive("invoice.paid", INVOICE_ID);
    Throwable escaped =
        org.assertj.core.api.Assertions.catchThrowable(() -> harness.unitOfWork().process(second));

    InboundState state = harness.inbound().find(second).orElseThrow().state();
    boolean due = harness.due().stream().anyMatch(e -> e.eventId().equals(second));
    String code = harness.inbound().find(second).orElseThrow().lastCode();

    assertThat(escaped)
        .as("a second event on a voided invoice must not throw out of the unit of work")
        .isNull();
    assertThat(state)
        .as("the row must reach a state the due query does not re-pick on every sweep")
        .isNotEqualTo(InboundState.MAPPED);
    assertThat(due).as("the row must not be due for ever with no backoff").isFalse();
    assertThat(code).as("an operator must be told why this event stopped").isNotBlank();
  }

  /**
   * RC-02. The burn justification the D7-03 closure put into the chain is read back off the mutable
   * row, and the verifier's cross-check compares seven identity and state columns only. So the
   * runtime role - which needs UPDATE on this table for the state machine to work at all - can
   * rewrite why a legal number was burned or voided, and the chain still reports INTACT.
   */
  @Test
  void probe_a_rewritten_burn_reason_is_not_reported_by_the_verifier() {
    String invoiceId = "in_rc_rule_1";
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWith(TestValidators.failing("BR-DE-15"));
    harness.source().with(TestInvoices.finalised(invoiceId));
    String eventId = harness.receive("invoice.finalized", invoiceId);
    assertThat(harness.unitOfWork().process(eventId).state())
        .isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(harness.issuance(invoiceId).orElseThrow().state())
        .isEqualTo(IssuanceState.FAILED_VALIDATION);

    // Exactly the grants docs/schema-grants.sql gives the runtime role: UPDATE on
    // einvoice_issuance. Line two of the fix refuses it outright, so this is now an assertion in
    // its own right rather than the setup it used to be.
    String rewrite =
        "UPDATE einvoice_issuance SET void_rule_id = 'BR-CL-01',"
            + " void_reason = 'buyer asked us to cancel'"
            + " WHERE stripe_invoice_id = '"
            + invoiceId
            + "'";
    Throwable refused =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> com.housedevinci.einvoice.adapter.jdbc.PostgresSupport.execute(rewrite));
    assertThat(refused)
        .as("the runtime role must not be able to rewrite a burn justification at all")
        .isNotNull();

    // Line one, and the half that matters: a role that outranks the triggers - the residual this
    // module names in SECURITY-NOTES - makes the same rewrite. The chain itself still verifies, so
    // only the cross-check between the chained event and the row can report this.
    com.housedevinci.einvoice.adapter.jdbc.PostgresSupport.execute(
        "ALTER TABLE einvoice_issuance DISABLE TRIGGER USER");
    com.housedevinci.einvoice.adapter.jdbc.PostgresSupport.execute(rewrite);
    com.housedevinci.einvoice.adapter.jdbc.PostgresSupport.execute(
        "ALTER TABLE einvoice_issuance ENABLE TRIGGER USER");

    com.housedevinci.einvoice.application.IssuanceChainVerifier.Report report =
        new com.housedevinci.einvoice.application.IssuanceChainVerifier(
                harness.store(),
                harness.store(),
                java.util.Map.of(
                    "k1",
                    "0123456789abcdef0123456789abcdef"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .verify();

    assertThat(report.status())
        .as("a rewritten burn justification must not verify as an intact chain")
        .isEqualTo(com.housedevinci.einvoice.application.IssuanceChainVerifier.Status.BROKEN);
    assertThat(report.brokenAtSequence())
        .as("the hashes still recompute: it is the cross-check that must catch this")
        .isEqualTo(-1);
    assertThat(report.unchainedDispositions())
        .as("the rewritten row must be the disposition that agrees with no chained event")
        .isGreaterThan(0);
  }
}
