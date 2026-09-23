package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * D14-02, over real HTTP: what {@code einvoice.issuance.enabled=false} means at the edge.
 *
 * <p>The property is documented - in the README, and in this module's own refusal message - as "run
 * the numbering API only". It used to turn off the sweeper alone, so an application with the three
 * intake beans and a webhook signing secret still mapped the unauthenticated endpoint, still
 * answered Stripe 200 (so nothing retried and nothing alerted), and still wrote a durable row for
 * every event it would never issue. This test posts a correctly signed event - not a malformed one,
 * so nothing but the absent endpoint can explain the outcome - and asserts 404 rather than 401 or
 * 200, and an empty inbound table read straight from the database rather than through a port that
 * is no longer wired.
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
      "einvoice.archive.root=${java.io.tmpdir}/einvoice-disabled-intake-test",
      // A signing secret IS configured: this is the state the finding is about, an operator who
      // turned intake off on a host that still carries the credentials of the one before.
      "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "einvoice.issuance.enabled=false"
    })
class DisabledIntakeEndpointTest {

  private static final String PRIMARY = "whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  @Test
  void a_signed_event_posted_to_a_numbering_only_host_gets_404_and_is_recorded_nowhere()
      throws Exception {
    String eventId = "evt_disabled_" + UUID.randomUUID().toString().replace("-", "");
    String body =
        "{\"id\":\""
            + eventId
            + "\",\"type\":\"invoice.finalized\",\"api_version\":\""
            + IssuanceTestApp.PINNED_VERSION
            + "\",\"livemode\":false,\"data\":{\"object\":{\"id\":\"in_1\"}}}";
    long timestamp = System.currentTimeMillis() / 1000;

    ResponseEntity<String> response =
        RestClient.builder()
            .baseUrl("http://127.0.0.1:" + port)
            .build()
            .post()
            .uri("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", "t=" + timestamp + ",v1=" + sign(timestamp, body))
            .body(body)
            .retrieve()
            .onStatus(status -> true, (request, ignored) -> {})
            .toEntity(String.class);

    assertThat(response.getStatusCode())
        .describedAs(
            "with intake off the endpoint must not exist at all: a 401 or a 200 would mean the"
                + " module still accepts Stripe traffic on a host documented as numbering only")
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(inboundRows(eventId))
        .describedAs("nothing may be recorded for an event this application never accepted")
        .isZero();
  }

  private int inboundRows(String eventId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM einvoice_inbound_event WHERE event_id = ?")) {
      statement.setString(1, eventId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static String sign(long timestamp, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(PRIMARY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of()
        .formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
  }
}
