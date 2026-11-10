package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.FakeStripeSource;
import com.housedevinci.einvoice.application.InboundEventStore.Recorded;
import com.housedevinci.einvoice.application.ReconciliationSweep;
import com.housedevinci.einvoice.application.TestInvoices;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * D-PR60-2, the paths of the unit of work and the reconciliation pass (skew duplicate design,
 * probes 15 and 16): a cured row runs once and gets one number.
 */
class SkewCurePathsTest {

  private static final String OLD = "2025-12-15.acacia";
  private static final String NEW = FakeStripeSource.PINNED;

  private static long numbered(IssuanceTestHarness h, String invoiceId) {
    return PostgresSupport.scalarOn(
        h.dataSource(),
        "SELECT count(*) FROM einvoice_issuance WHERE stripe_invoice_id = '" + invoiceId + "'");
  }

  private static String column(IssuanceTestHarness h, String eventId, String column) {
    return PostgresSupport.scalarOn(
                h.dataSource(),
                "SELECT count(*) FROM einvoice_inbound_event WHERE event_id = '"
                    + eventId
                    + "' AND "
                    + column
                    + " IS NOT NULL")
            == 1
        ? "set"
        : "null";
  }

  // --------------------------------------------------------------------------------- probe 15

  @Test
  void a_row_refused_for_skew_after_it_was_parked_is_cured_and_issues_once() {
    IssuanceTestHarness h = IssuanceTestHarness.create().pinnedTo(OLD);
    String invoice = "in_cure_parked_" + System.nanoTime();
    h.source().with(TestInvoices.draft(invoice));
    String eventId = h.receive("invoice.finalized", invoice, OLD, true, "");

    // At the old pin the invoice is still a draft: the row parks, and keeps its park mark.
    assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.PARKED);
    assertThat(column(h, eventId, "parked_at")).isEqualTo("set");

    // The deploy moves the pin; the re-run of the parked row meets the route and is refused.
    h.pinnedTo(NEW);
    assertThat(h.unitOfWork().process(eventId).state())
        .isEqualTo(InboundState.REFUSED_VERSION_SKEW);

    // The same event at the new pin cures it, the park mark stays, and it issues exactly once.
    h.source().with(TestInvoices.finalised(invoice));
    byte[] body = ("{\"id\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
    InboundEvent copy =
        InboundEvent.received(
            new EventIdentity(eventId, "invoice.finalized", NEW, true, "", invoice),
            Mode.LIVE,
            "primary",
            body,
            h.now());
    assertThat(h.inbound().record(copy, body, NEW)).isEqualTo(Recorded.CURED);
    assertThat(column(h, eventId, "parked_at"))
        .as("the allocator's history is kept")
        .isEqualTo("set");

    assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
    assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
    assertThat(numbered(h, invoice)).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------- probe 16

  @Test
  void a_reconciliation_row_refused_at_the_previous_pin_is_cured_by_the_next_pass() {
    IssuanceTestHarness h = IssuanceTestHarness.create().pinnedTo(OLD);
    String invoice = "in_cure_recon_" + System.nanoTime();
    h.source().with(TestInvoices.finalised(invoice));
    String reconId = "recon-" + invoice;

    // A pass at the old pin enqueues the missing invoice; the deploy lands before it runs.
    h.sweep(settings()).sweep();
    assertThat(h.inbound().find(reconId).orElseThrow().apiVersion()).isEqualTo(OLD);
    h.pinnedTo(NEW);
    assertThat(h.unitOfWork().process(reconId).state())
        .isEqualTo(InboundState.REFUSED_VERSION_SKEW);

    // The next pass, at the new pin, cures its own row. The body stays null.
    h.sweep(settings()).sweep();
    InboundEvent cured = h.inbound().find(reconId).orElseThrow();
    assertThat(cured.state()).isEqualTo(InboundState.RECEIVED);
    assertThat(cured.apiVersion()).isEqualTo(NEW);
    assertThat(cured.bodyPresent()).isFalse();
    assertThat(h.inbound().body(reconId)).isEmpty();

    assertThat(h.unitOfWork().process(reconId).state()).isEqualTo(InboundState.COMPLETED);
    h.sweep(settings()).sweep();
    assertThat(numbered(h, invoice)).isEqualTo(1);
  }

  private static ReconciliationSweep.Settings settings() {
    return new ReconciliationSweep.Settings(
        Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);
  }
}
