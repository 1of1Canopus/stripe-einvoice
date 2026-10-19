package com.housedevinci.einvoice.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.IssuanceReader;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-PR36-6, end to end (path P11): the sample with a host validator bean that answers PASSED
 * without reading and a host renderer that dates the invoice a week early. Before 0.2.0's date
 * check moved into the unit of work, this webhook ended with an archived invoice dated a week
 * before its number. Now it ends FAILED_ISSUANCE and nothing is archived.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = {
      SampleApplication.class,
      SampleEndToEndTest.FakeStripe.class,
      SampleHostValidatorTest.HostPorts.class
    })
@AutoConfigureMockMvc
@Testcontainers
class SampleHostValidatorTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse(
                      "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777")
                  .asCompatibleSubstituteFor("postgres"))
          .withStartupTimeout(Duration.ofMinutes(2));

  private static final String WEBHOOK_SECRET = "whsec_" + "h".repeat(32);

  private static final Path ARCHIVE =
      Path.of(System.getProperty("java.io.tmpdir"), "einvoice-sample-host-validator");

  @DynamicPropertySource
  static void secrets(DynamicPropertyRegistry registry) {
    registry.add("einvoice.issuance.enabled", () -> "true");
    registry.add("einvoice.stripe.webhook-secrets.primary", () -> WEBHOOK_SECRET);
    registry.add("einvoice.archive.root", ARCHIVE::toString);
    registry.add(
        "einvoice.chain.hmac-secret",
        () ->
            Base64.getEncoder()
                .encodeToString(
                    "einvoice-sample-chain-secret-02!".getBytes(StandardCharsets.UTF_8)));
  }

  @Autowired MockMvc mvc;

  @Autowired InboundEventStore inbound;

  @Autowired IssuanceReader issuances;

  @Test
  void a_host_validator_in_the_sample_cannot_archive_a_backdated_invoice() throws Exception {
    byte[] body =
        ("{\"id\":\"evt_sample_hv\",\"type\":\"invoice.finalized\",\"api_version\":\""
                + SampleEndToEndTest.FakeStripe.PINNED
                + "\",\"livemode\":true,\"data\":{\"object\":{\"id\":\"in_host_validator\"}}}")
            .getBytes(StandardCharsets.UTF_8);

    mvc.perform(
            post("/webhooks/stripe")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature(body))
                .content(body))
        .andExpect(status().isOk());

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(inbound.find("evt_sample_hv").orElseThrow().state())
                    .isEqualTo(InboundState.FAILED_ISSUANCE));
    assertThat(inbound.lastRuleId("evt_sample_hv")).contains("EINVOICE-DATES");
    assertThat(issuances.findBySource("acme-fr", Mode.LIVE, "in_host_validator").orElseThrow())
        .satisfies(
            i -> {
              assertThat(i.state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
              assertThat(i.documentHash()).isEmpty();
            });
    try (var files = Files.walk(ARCHIVE)) {
      assertThat(files.filter(Files::isRegularFile).map(Path::toString))
          .noneMatch(p -> p.endsWith(".xml"));
    }
  }

  private static String signature(byte[] body) throws Exception {
    long t = java.time.Instant.now().getEpochSecond();
    javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(
        new javax.crypto.spec.SecretKeySpec(
            WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
    mac.update(body);
    return "t=" + t + ",v1=" + java.util.HexFormat.of().formatHex(mac.doFinal());
  }

  /** A host's own renderer and validator: the module's back off (README, "ours backs off"). */
  @TestConfiguration(proxyBeanMethods = false)
  static class HostPorts {

    @Bean
    DocumentRenderer hostRenderer() {
      return new DocumentRenderer() {
        @Override
        public boolean carriesSupplyDate() {
          return true;
        }

        @Override
        public RenderedDocument render(com.housedevinci.einvoice.application.DocumentInput input) {
          String xml =
              "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\""
                  + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
                  + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\">"
                  + "<cbc:ID>"
                  + input.legalNumber().value()
                  + "</cbc:ID><cbc:IssueDate>"
                  + input.issueDate().minusDays(7)
                  + "</cbc:IssueDate><cac:Delivery><cbc:ActualDeliveryDate>"
                  + input.supplyDate().map(Object::toString).orElse("")
                  + "</cbc:ActualDeliveryDate></cac:Delivery></Invoice>";
          return new RenderedDocument(xml.getBytes(StandardCharsets.UTF_8), "xml", "host");
        }
      };
    }

    @Bean
    DocumentValidator hostValidator() {
      return (bytes, input) -> DocumentValidator.Report.passed();
    }
  }
}
