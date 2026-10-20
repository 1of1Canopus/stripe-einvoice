package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.FindingStore;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** QUESTIONS 29 in the starter: the two properties, their refusals and WARNs, and the wiring. */
@ExtendWith(OutputCaptureExtension.class)
class RunningLimitStarterTest {

  private ApplicationContextRunner runner(String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(IssuanceWiringTest.Ports.class)
        .withPropertyValues(
            "einvoice.seller.id=q29",
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
            "einvoice.chain.hmac-key-id=k1",
            "einvoice.archive.type=filesystem",
            "einvoice.archive.root=" + System.getProperty("java.io.tmpdir") + "/einvoice-q29-test",
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .withPropertyValues(properties);
  }

  @Test
  void the_defaults_are_seventy_two_hours_and_ninety_days_and_say_nothing(CapturedOutput out) {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              EInvoiceProperties.Issuance issuance =
                  context.getBean(EInvoiceProperties.class).getIssuance();
              assertThat(issuance.getRunningCeiling()).isEqualTo(Duration.ofHours(72));
              assertThat(issuance.getParkedCeiling()).isEqualTo(Duration.ofDays(90));
            });
    assertThat(out.getOut())
        .doesNotContain("running-ceiling is")
        .doesNotContain("parked-ceiling is");
  }

  // ---- probe 9 and probe 14 ----------------------------------------------------------------

  @Test
  void probe_a_weakened_ceiling_is_silent(CapturedOutput out) {
    runner("einvoice.issuance.running-ceiling=P8D", "einvoice.issuance.parked-ceiling=P400D")
        .run(context -> assertThat(context).hasNotFailed());
    assertThat(out.getOut())
        .contains("einvoice.issuance.running-ceiling is PT192H")
        .contains("einvoice.issuance.parked-ceiling is PT9600H");
  }

  @Test
  void probe_a_too_short_ceiling_binds() {
    String[][] refused = {
      {"einvoice.issuance.running-ceiling=PT0S"},
      {"einvoice.issuance.running-ceiling=-PT1H"},
      {"einvoice.issuance.parked-ceiling=PT0S"},
      {"einvoice.issuance.parked-ceiling=-P1D"},
      // F-5: shorter than or equal to the longest retry backoff
      {"einvoice.issuance.running-ceiling=PT1H", "einvoice.issuance.max-retry-backoff=PT1H"},
      {"einvoice.issuance.running-ceiling=PT30M"},
      // F-5: a parked limit below the running one
      {"einvoice.issuance.parked-ceiling=PT71H"},
    };
    for (String[] properties : refused) {
      runner(properties)
          .run(
              context ->
                  assertThat(context)
                      .as("%s", String.join(", ", properties))
                      .hasFailed()
                      .getFailure()
                      .rootCause()
                      .hasMessageContaining("running-ceiling"));
    }
  }

  // ---- the wiring: the issuance sweep expires before it reads what is due -----------------

  @Test
  void the_issuance_sweep_expires_a_stranded_row_and_reports_it_on_the_event_id() {
    runner()
        .run(
            context -> {
              IssuanceSweeper sweeper = context.getBean(IssuanceSweeper.class);
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              FindingStore findings = context.getBean(FindingStore.class);
              Instant now = context.getBean(Clock.class).instant();
              String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
              byte[] body = ("{\"id\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
              inbound.record(
                  InboundEvent.received(
                      new EventIdentity(
                          eventId, "invoice.finalized", "2026-08-26.dahlia", true, "", "in_q29"),
                      Mode.LIVE,
                      "primary",
                      body,
                      now.minus(Duration.ofDays(4))),
                  body);

              sweeper.sweepOnce();

              assertThat(inbound.find(eventId).orElseThrow().state())
                  .isEqualTo(InboundState.EXPIRED);
              assertThat(sweeper.lastExpiredCount())
                  .hasValueSatisfying(n -> assertThat(n).isPositive());
              assertThat(findings.open("q29", Mode.LIVE, 10_000))
                  .anyMatch(
                      f ->
                          f.code().equals(ErrorCodes.RECON_STUCK_INBOUND)
                              && f.subjectId().equals(eventId));
            });
  }
}
