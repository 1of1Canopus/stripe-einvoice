package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * The edge, over real HTTP, against a real PostgreSQL.
 *
 * <p>The chain configuration matches every other starter test that appends a disposition, and that
 * is not incidental: a hash chain is one trail for the whole database, its anchor records whether
 * that trail is keyed, and a second application configured the other way is refused on append. The
 * shared container is one database.
 *
 * <p>The contract under test is I-01's: 400 only for "not provably from Stripe", 200 for everything
 * with a valid signature - including the refusals - and 503 only when the record could not be made
 * durable.
 */
@SpringBootTest(
    classes = IssuanceTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "einvoice.seller.id=" + IssuanceTestApp.SELLER,
      "einvoice.seller.tax-zone=Europe/Paris",
      "einvoice.numbering.prefix=INV-{fiscalYear}-",
      "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
      "einvoice.chain.hmac-key-id=k1",
      "einvoice.archive.type=filesystem",
      "einvoice.archive.root=${java.io.tmpdir}/einvoice-endpoint-test",
      "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "einvoice.stripe.webhook-secrets.retiring=whsec_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      "einvoice.reconcile.enabled=false",
      "management.endpoints.web.exposure.include=health,einvoicefindings"
    })
class StripeWebhookEndpointTest {

  private static final String PRIMARY = "whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String RETIRING = "whsec_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  @LocalServerPort int port;

  @Autowired InboundEventStore inbound;

  private RestClient client() {
    return RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
  }

  private static String body(String eventId, String type, String apiVersion, boolean livemode) {
    return "{\"id\":\""
        + eventId
        + "\",\"type\":\""
        + type
        + "\",\"api_version\":\""
        + apiVersion
        + "\",\"livemode\":"
        + livemode
        + ",\"data\":{\"object\":{\"id\":\"in_1\"}}}";
  }

  private static String signature(String secret, byte[] body) {
    long t = System.currentTimeMillis() / 1000;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
      mac.update(body);
      return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private ResponseEntity<Void> post(byte[] body, String signature, MediaType contentType) {
    HttpHeaders headers = new HttpHeaders();
    if (contentType != null) {
      headers.setContentType(contentType);
    }
    if (signature != null) {
      headers.add(WebhookSignature.HEADER, signature);
    }
    return client()
        .method(HttpMethod.POST)
        .uri("/webhooks/stripe")
        .headers(h -> h.addAll(headers))
        .body(body)
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toBodilessEntity();
  }

  private static String freshId() {
    return "evt_" + UUID.randomUUID().toString().replace("-", "");
  }

  @Test
  void a_signed_event_is_recorded_answered_200_and_issued() {
    String eventId = freshId();
    byte[] body =
        body(eventId, "invoice.finalized", IssuanceTestApp.PINNED_VERSION, true)
            .getBytes(StandardCharsets.UTF_8);

    assertThat(post(body, signature(PRIMARY, body), MediaType.APPLICATION_JSON).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    InboundEvent recorded = inbound.find(eventId).orElseThrow();
    assertThat(recorded.signatureKeyId()).isEqualTo("primary");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(inbound.find(eventId).orElseThrow().state())
                    .isIn(InboundState.COMPLETED, InboundState.FAILED_ISSUANCE));
    assertThat(inbound.find(eventId).orElseThrow().state()).isEqualTo(InboundState.COMPLETED);
  }

  @Test
  void a_second_webhook_secret_id_verifies_so_a_rotation_is_not_an_outage() {
    String eventId = freshId();
    byte[] body =
        body(eventId, "invoice.finalized", IssuanceTestApp.PINNED_VERSION, true)
            .getBytes(StandardCharsets.UTF_8);

    assertThat(post(body, signature(RETIRING, body), MediaType.APPLICATION_JSON).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(inbound.find(eventId).orElseThrow().signatureKeyId()).isEqualTo("retiring");
  }

  @Test
  void a_bad_signature_writes_nothing_and_answers_400() {
    String eventId = freshId();
    byte[] body =
        body(eventId, "invoice.finalized", IssuanceTestApp.PINNED_VERSION, true)
            .getBytes(StandardCharsets.UTF_8);

    assertThat(
            post(
                    body,
                    signature("whsec_cccccccccccccccccccccccccccccccc", body),
                    MediaType.APPLICATION_JSON)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(inbound.find(eventId)).isEmpty();
  }

  @Test
  void a_missing_signature_writes_nothing_and_answers_400() {
    String eventId = freshId();
    byte[] body =
        body(eventId, "invoice.finalized", IssuanceTestApp.PINNED_VERSION, true)
            .getBytes(StandardCharsets.UTF_8);

    assertThat(post(body, null, MediaType.APPLICATION_JSON).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(inbound.find(eventId)).isEmpty();
  }

  @Test
  void a_content_type_that_is_not_json_is_refused_before_anything_is_read() {
    String eventId = freshId();
    byte[] body =
        body(eventId, "invoice.finalized", IssuanceTestApp.PINNED_VERSION, true)
            .getBytes(StandardCharsets.UTF_8);

    assertThat(post(body, signature(PRIMARY, body), MediaType.TEXT_PLAIN).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(inbound.find(eventId)).isEmpty();
  }

  @Test
  void a_body_over_the_cap_is_rejected_and_never_recorded() {
    String eventId = freshId();
    String padding = "x".repeat(2 * 1024 * 1024);
    byte[] body =
        ("{\"id\":\""
                + eventId
                + "\",\"type\":\"invoice.finalized\",\"api_version\":\""
                + IssuanceTestApp.PINNED_VERSION
                + "\",\"livemode\":true,\"padding\":\""
                + padding
                + "\",\"data\":{\"object\":{\"id\":\"in_1\"}}}")
            .getBytes(StandardCharsets.UTF_8);

    assertThat(post(body, signature(PRIMARY, body), MediaType.APPLICATION_JSON).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(inbound.find(eventId)).isEmpty();
  }

  @Test
  void a_chunked_body_over_the_cap_is_aborted_mid_read() {
    // No Content-Length at all: the cap has to come from counting the bytes as they arrive, which
    // is the whole of I-09. A cap that believed the header would have accepted this one.
    String eventId = freshId();
    byte[] body =
        ("{\"id\":\""
                + eventId
                + "\",\"type\":\"invoice.finalized\",\"padding\":\""
                + "y".repeat(3 * 1024 * 1024)
                + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(WebhookSignature.HEADER, signature(PRIMARY, body));
    headers.add("Transfer-Encoding", "chunked");

    ResponseEntity<Void> response =
        client()
            .method(HttpMethod.POST)
            .uri("/webhooks/stripe")
            .headers(h -> h.addAll(headers))
            .body(new HttpEntity<>(body).getBody())
            .retrieve()
            .onStatus(status -> true, (request, resp) -> {})
            .toBodilessEntity();

    assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    assertThat(inbound.find(eventId)).isEmpty();
  }

  @Test
  void a_version_skew_event_is_recorded_and_answered_200() {
    // I-01: Stripe disables an endpoint that keeps failing, and a skew applies to every event on
    // the account at once. A refusal that answered 400 would take the whole intake down with it.
    String eventId = freshId();
    byte[] body =
        body(eventId, "invoice.finalized", "2019-02-19", true).getBytes(StandardCharsets.UTF_8);

    assertThat(post(body, signature(PRIMARY, body), MediaType.APPLICATION_JSON).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(inbound.find(eventId).orElseThrow().state())
                    .isEqualTo(InboundState.REFUSED_VERSION_SKEW));
    InboundEvent refused = inbound.find(eventId).orElseThrow();
    assertThat(refused.lastCode()).isEqualTo(ErrorCodes.API_VERSION_SKEW);
    // Replayable: the body is still there for when the operator re-pins.
    assertThat(refused.bodyPresent()).isTrue();
  }

  @Test
  void a_test_mode_event_in_a_live_application_is_recorded_and_refused() {
    String eventId = freshId();
    byte[] body =
        body(eventId, "invoice.finalized", IssuanceTestApp.PINNED_VERSION, false)
            .getBytes(StandardCharsets.UTF_8);

    assertThat(post(body, signature(PRIMARY, body), MediaType.APPLICATION_JSON).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(inbound.find(eventId).orElseThrow().state())
                    .isEqualTo(InboundState.REFUSED_MODE));
  }

  @Test
  void a_duplicate_delivery_is_a_no_op_and_still_answers_200() {
    String eventId = freshId();
    byte[] body =
        body(eventId, "invoice.finalized", IssuanceTestApp.PINNED_VERSION, true)
            .getBytes(StandardCharsets.UTF_8);
    String signature = signature(PRIMARY, body);

    assertThat(post(body, signature, MediaType.APPLICATION_JSON).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(post(body, signature, MediaType.APPLICATION_JSON).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    assertThat(
            TestPostgres.count(
                "SELECT count(*) FROM einvoice_inbound_event WHERE event_id = '" + eventId + "'"))
        .isEqualTo(1);
  }

  @Test
  void a_body_whose_identity_fields_do_not_parse_is_a_400_with_nothing_written() {
    byte[] body = "{\"id\":\"evt_x\",\"id\":\"evt_y\"}".getBytes(StandardCharsets.UTF_8);

    assertThat(post(body, signature(PRIMARY, body), MediaType.APPLICATION_JSON).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(inbound.find("evt_x")).isEmpty();
    assertThat(inbound.find("evt_y")).isEmpty();
  }
}
