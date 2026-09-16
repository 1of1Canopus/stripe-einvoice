package com.housedevinci.einvoice.sample;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.housedevinci.einvoice.autoconfigure.IssuanceVoidService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The whole sample, end to end, against a real PostgreSQL: two numbers allocated, one voided, and a
 * report that shows both with their dispositions - which is the page an auditor would read.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = {SampleApplication.class, SampleEndToEndTest.FakeStripe.class})
@AutoConfigureMockMvc
@Testcontainers
class SampleEndToEndTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse(
                      "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777")
                  .asCompatibleSubstituteFor("postgres"))
          .withStartupTimeout(java.time.Duration.ofMinutes(2));

  /** Basic sample:sample, the credentials application.yml defaults to. */
  private static final String BASIC =
      "Basic "
          + Base64.getEncoder().encodeToString("sample:sample".getBytes(StandardCharsets.UTF_8));

  private static final String FINALIZED_AT = "2026-02-01T09:15:00Z";

  private static final String WEBHOOK_SECRET = "whsec_" + "s".repeat(32);

  @DynamicPropertySource
  static void secrets(DynamicPropertyRegistry registry) {
    registry.add("einvoice.stripe.webhook-secrets.primary", () -> WEBHOOK_SECRET);
    registry.add(
        "einvoice.archive.root",
        () -> System.getProperty("java.io.tmpdir") + "/einvoice-sample-archive");
    // Obviously synthetic and computed, never pasted: this module's secrets come from the
    // environment, and a base64 blob in a test file reads like a real key to the next reader.
    registry.add(
        "einvoice.chain.hmac-secret",
        () ->
            Base64.getEncoder()
                .encodeToString(
                    "einvoice-sample-chain-secret-01!".getBytes(StandardCharsets.UTF_8)));
  }

  @Autowired MockMvc mvc;

  @Autowired IssuanceVoidService voids;

  @Autowired com.housedevinci.einvoice.application.IssuanceReader issuances;

  @Autowired com.housedevinci.einvoice.application.ArchiveStore archive;

  @Test
  void a_stripe_invoice_gets_a_number_a_void_is_recorded_and_the_report_explains_the_series()
      throws Exception {
    allocate("in_sample_1", "ABCD-0001")
        .andExpect(jsonPath("$.legalNumber").value("INV-2026-000001"));

    // The same Stripe invoice, twice: one sale, one number, for all time.
    allocate("in_sample_1", "ABCD-0001")
        .andExpect(jsonPath("$.legalNumber").value("INV-2026-000001"));

    allocate("in_sample_2", "ABCD-0002")
        .andExpect(jsonPath("$.legalNumber").value("INV-2026-000002"));

    // Voiding has no endpoint in the module and none in this sample: it is a service call, and a
    // host that wants to expose it writes that endpoint behind its own authorization.
    voids.voidUnused("in_sample_2", "duplicate of in_sample_1", "OPS-1");

    mvc.perform(get("/series/{on}", FINALIZED_AT).header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.open").value(1))
        .andExpect(jsonPath("$.contiguous").value(true))
        .andExpect(jsonPath("$.lines.length()").value(2))
        .andExpect(jsonPath("$.lines[1].state").value("VOID_UNUSED"))
        .andExpect(jsonPath("$.lines[1].reason").value("duplicate of in_sample_1"));

    mvc.perform(get("/invoices/{id}/number", "in_sample_1").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("NUMBERED"));
    mvc.perform(get("/invoices/{id}/number", "in_never_seen").header("Authorization", BASIC))
        .andExpect(status().isNotFound());
  }

  @Test
  void a_signed_webhook_produces_one_archived_document_under_one_legal_number() throws Exception {
    byte[] body =
        ("{\"id\":\"evt_sample_1\",\"type\":\"invoice.finalized\",\"api_version\":\""
                + FakeStripe.PINNED
                + "\",\"livemode\":true,\"data\":{\"object\":{\"id\":\"in_webhook\"}}}")
            .getBytes(StandardCharsets.UTF_8);

    // The webhook path is the one endpoint the sample leaves unauthenticated: the signature over
    // the exact bytes is its authentication, and HTTP Basic in front of it would only make
    // Stripe's deliveries fail.
    mvc.perform(
            post("/webhooks/stripe")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature(body))
                .content(body))
        .andExpect(status().isOk());

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                org.assertj.core.api.Assertions.assertThat(
                        issuances.findBySource(
                            "acme-fr", com.housedevinci.einvoice.domain.Mode.LIVE, "in_webhook"))
                    .get()
                    .extracting(com.housedevinci.einvoice.domain.Issuance::state)
                    .isEqualTo(com.housedevinci.einvoice.domain.IssuanceState.ISSUED));

    com.housedevinci.einvoice.domain.Issuance issuance =
        issuances
            .findBySource("acme-fr", com.housedevinci.einvoice.domain.Mode.LIVE, "in_webhook")
            .orElseThrow();
    byte[] archived =
        archive
            .get(new com.housedevinci.einvoice.domain.ArchiveKey(issuance.archiveKey()))
            .orElseThrow();
    org.assertj.core.api.Assertions.assertThat(new String(archived, StandardCharsets.UTF_8))
        .describedAs("the sample's own placeholder, which no tool may mistake for EN 16931")
        .contains("<PlaceholderDocument>")
        .contains(issuance.legalNumber().value())
        .doesNotContain("<Invoice>");
    org.assertj.core.api.Assertions.assertThat(
            com.housedevinci.einvoice.domain.Hashes.sha256Hex(archived))
        .isEqualTo(issuance.documentSha256());
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

  /** The authoritative source, faked: the sample has no Stripe key and asks for none. */
  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class FakeStripe {

    static final String PINNED = "2026-08-26.dahlia";

    @org.springframework.context.annotation.Bean
    com.housedevinci.einvoice.application.StripeInvoiceSource source() {
      return new com.housedevinci.einvoice.application.StripeInvoiceSource() {

        @Override
        public com.housedevinci.einvoice.application.SourceInvoice fetchInvoice(String invoiceId) {
          return new com.housedevinci.einvoice.application.SourceInvoice(
              invoiceId,
              "ABCD-0009",
              "",
              true,
              "eur",
              "paid",
              java.time.Instant.parse(FINALIZED_AT),
              new com.housedevinci.einvoice.application.SourceInvoice.SourceParty(
                  "Buyer Cooperative",
                  "buyer@example.invalid",
                  "1 Example Street",
                  "",
                  "75001",
                  "Example City",
                  "FR",
                  "FR00000000000"),
              java.util.List.of(
                  new com.housedevinci.einvoice.application.SourceInvoice.SourceLine(
                      "il_1", "One month of service", "txr_20", 10_000, 12_000)),
              java.util.List.of(
                  new com.housedevinci.einvoice.domain.Totals.Bucket(
                      "txr_20",
                      com.housedevinci.einvoice.domain.Percentage.of("20"),
                      false,
                      2_000)),
              10_000,
              2_000,
              12_000,
              true);
        }

        @Override
        public java.util.List<String> finalisedInvoiceIds(
            java.time.Instant from, java.time.Instant to) {
          return java.util.List.of();
        }

        @Override
        public String pinnedApiVersion() {
          return PINNED;
        }
      };
    }
  }

  @Test
  void every_endpoint_of_the_sample_requires_authentication() throws Exception {
    mvc.perform(get("/series/{on}", FINALIZED_AT)).andExpect(status().isUnauthorized());
    mvc.perform(post("/invoices/{id}/number", "in_anonymous")).andExpect(status().isUnauthorized());
  }

  private org.springframework.test.web.servlet.ResultActions allocate(String id, String number)
      throws Exception {
    return mvc.perform(
            post("/invoices/{id}/number", id)
                .param("stripeNumber", number)
                .param("finalizedAt", FINALIZED_AT)
                .header("Authorization", BASIC))
        .andExpect(status().isOk());
  }
}
