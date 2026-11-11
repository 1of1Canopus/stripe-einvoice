package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.FindingStore;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.InboundIntake;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Security review of the skew cure (PR 62), pass 1, promoted into the suite: the identity mismatch
 * at the endpoint (D-PR62-4) and a blank host pin at startup (D-PR62-3).
 */
class CipherProbePr62StarterTest {

  private static final String SECRET = "whsec_dddddddddddddddddddddddddddddddd";
  private static final Instant NOW = Instant.parse("2026-01-16T09:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static byte[] body(String eventId) {
    return ("{\"id\":\""
            + eventId
            + "\",\"type\":\"invoice.finalized\",\"api_version\":\"2026-03-31.clover\","
            + "\"livemode\":true,\"data\":{\"object\":{\"id\":\"in_1\"}}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String signature(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((NOW.getEpochSecond() + ".").getBytes(StandardCharsets.UTF_8));
      mac.update(payload);
      return "t=" + NOW.getEpochSecond() + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * D-PR62-4, the webhook path of an identity mismatch: 200, no hand-over to the worker (a null
   * worker throws if asked), one finding DEI-223 on the event id.
   */
  @Test
  void an_identity_mismatch_at_the_endpoint_is_200_with_a_finding_and_no_run() {
    InboundEventStore store =
        (InboundEventStore)
            Proxy.newProxyInstance(
                InboundEventStore.class.getClassLoader(),
                new Class<?>[] {InboundEventStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("record")) {
                    return InboundEventStore.Recorded.IDENTITY_MISMATCH;
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    java.util.List<com.housedevinci.einvoice.domain.ComplianceFinding> raised =
        new java.util.ArrayList<>();
    FindingStore findings =
        (FindingStore)
            Proxy.newProxyInstance(
                FindingStore.class.getClassLoader(),
                new Class<?>[] {FindingStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("record") && args.length == 1) {
                    raised.add((com.housedevinci.einvoice.domain.ComplianceFinding) args[0]);
                    return null;
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    IssuanceUnitOfWork.Configuration configuration =
        new IssuanceUnitOfWork.Configuration(
            "seller-1",
            "",
            Mode.LIVE,
            "INV",
            false,
            ZoneId.of("Europe/Paris"),
            "2026-03-31.clover",
            "fr-2026.1",
            Duration.ofMinutes(1),
            Duration.ofHours(1),
            Optional.empty(),
            false);
    StripeWebhookController controller =
        new StripeWebhookController(
            new InboundIntake(store, findings, configuration, CLOCK),
            null,
            Map.of("primary", SECRET),
            Duration.ofMinutes(5),
            1024 * 1024,
            Mode.LIVE,
            CLOCK);
    byte[] payload = body("evt_p62mismatch");
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/stripe");
    request.setContentType("application/json");
    request.setContent(payload);
    request.addHeader("Stripe-Signature", signature(payload));

    var response = controller.receive(request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(raised).hasSize(1);
    assertThat(raised.get(0).code()).isEqualTo(ErrorCodes.INBOUND_IDENTITY_MISMATCH);
    assertThat(raised.get(0).subjectId()).isEqualTo("evt_p62mismatch");
  }

  /**
   * D-PR62-3. A host source whose pin is blank fails the context at startup with DEI-100. Before
   * the fix the application started and answered every signed event 400, recording nothing.
   */
  @Test
  void a_host_source_with_a_blank_pin_fails_the_context_with_a_configuration_error() {
    for (String pin : new String[] {"", " ", "\t"}) {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
          .withUserConfiguration(IssuanceWiringTest.PortsWithoutSource.class)
          .withBean(StripeInvoiceSource.class, () -> new IssuanceTestApp.RecordingStripeSource(pin))
          .withPropertyValues(
              "einvoice.seller.id=wiring",
              "einvoice.seller.tax-zone=Europe/Paris",
              "einvoice.numbering.prefix=INV-{fiscalYear}-",
              "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
              "einvoice.chain.hmac-key-id=k1",
              "einvoice.archive.type=filesystem",
              "einvoice.archive.root="
                  + System.getProperty("java.io.tmpdir")
                  + "/einvoice-wiring-test",
              "einvoice.stripe.webhook-secrets.primary=" + SECRET)
          .run(
              context ->
                  assertThat(context)
                      .as("pin [%s]", pin)
                      .hasFailed()
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(EInvoiceException.class)
                      .extracting(e -> ((EInvoiceException) e).code())
                      .isEqualTo(ErrorCodes.CONFIG));
    }
  }
}
