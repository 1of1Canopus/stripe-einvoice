package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Security review, PR 60. D-PR60-5 and the reconciliation cushion. The probes for D-PR60-1 (a code
 * path that does not exist; the fix is text) and D-PR60-2 (design stop) are not in this file.
 * Context: D-PR60-1: the upgrade note says an event refused at the previous pin "replays once the
 * versions match". The row records the version the event was sent at, the pin can only be the
 * SDK's, so a row recorded at 2026-08-26.dahlia never matches on 34.0.0 and is never re-picked by
 * the due query.
 */
@ExtendWith(OutputCaptureExtension.class)
class CipherProbePr60Test {

  private static final String PREVIOUS_PIN = "2026-08-26.dahlia";

  private ApplicationContextRunner runner(String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(IssuanceWiringTest.Ports.class)
        .withPropertyValues(
            "einvoice.seller.id=operations",
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
            "einvoice.chain.hmac-key-id=k1",
            "einvoice.archive.type=filesystem",
            "einvoice.archive.root="
                + System.getProperty("java.io.tmpdir")
                + "/einvoice-pr60-probe",
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "einvoice.issuance.sweep-interval=24h")
        .withPropertyValues(properties);
  }

  private static String freshId() {
    return "evt_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static String recordAtPreviousPin(InboundEventStore inbound, String eventId) {
    String invoiceId = "in_" + eventId.substring("evt_".length());
    byte[] body = ("{\"id\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
    inbound.record(
        InboundEvent.received(
            new EventIdentity(eventId, "invoice.finalized", PREVIOUS_PIN, true, "", invoiceId),
            Mode.LIVE,
            "primary",
            body,
            Instant.now()),
        body,
        com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp.PINNED_VERSION);
    return invoiceId;
  }

  /**
   * Red before the fix (D-PR60-5). After a pin move, reconciliation is the only path that issues an
   * invoice whose event was refused at the previous pin. Switching it off is a weaker mode and says
   * nothing at start-up.
   */
  @Test
  void probe_switching_reconciliation_off_is_silent_at_start_up(CapturedOutput out) {
    runner("einvoice.reconcile.enabled=false").run(context -> assertThat(context).hasNotFailed());
    assertThat(out.getOut() + out.getErr())
        .as("a WARN that names einvoice.reconcile.enabled=false")
        .contains("einvoice.reconcile.enabled=false");
  }

  /**
   * PASSES: the cushion. With reconciliation on (the default) the invoice is re-enqueued at the SDK
   * pin.
   */
  @Test
  void check_reconciliation_re_enqueues_the_invoice_a_previous_pin_row_left_unissued() {
    runner()
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              IssuanceSweeper sweeper = context.getBean(IssuanceSweeper.class);
              com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp.RecordingStripeSource
                  source =
                      (com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp
                              .RecordingStripeSource)
                          context.getBean(
                              com.housedevinci.einvoice.application.StripeInvoiceSource.class);
              String eventId = freshId();
              String invoiceId = recordAtPreviousPin(inbound, eventId);
              source.add(invoiceId);
              sweeper.sweepOnce();
              Awaitility.await()
                  .atMost(Duration.ofSeconds(20))
                  .until(
                      () ->
                          inbound.find(eventId).orElseThrow().state()
                              == InboundState.REFUSED_VERSION_SKEW);
              assertThat(sweeper.reconcileOnce()).isPresent();
              Awaitility.await()
                  .atMost(Duration.ofSeconds(30))
                  .untilAsserted(
                      () -> {
                        sweeper.sweepOnce();
                        // Past routing at the SDK pin: neither waiting nor refused at the route.
                        // Whether this test context's renderer then issues it is not the point.
                        InboundEvent recon = inbound.find("recon-" + invoiceId).orElseThrow();
                        assertThat(recon.apiVersion()).isEqualTo(com.stripe.Stripe.API_VERSION);
                        assertThat(recon.state())
                            .isNotIn(
                                InboundState.RECEIVED,
                                InboundState.REFUSED_VERSION_SKEW,
                                InboundState.REFUSED_MODE,
                                InboundState.REFUSED_ACCOUNT,
                                InboundState.DROPPED);
                        System.out.println(
                            "PR60-CHECK recon row state="
                                + recon.state()
                                + " code="
                                + recon.lastCode());
                      });
              assertThat(inbound.find(eventId).orElseThrow().state())
                  .isEqualTo(InboundState.REFUSED_VERSION_SKEW);
            });
  }
}
