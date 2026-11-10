package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.housedevinci.einvoice.adapter.jdbc.JdbcInboundEventStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcUnitOfWork;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.InboundEventStore.Recorded;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp;
import com.housedevinci.einvoice.autoconfigure.issuance.SkewCureTestApp;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * D-PR60-2 end to end, signed through the webhook endpoint (skew duplicate design, probes 12, 13,
 * 14, 23 and 24).
 *
 * <p>Its own database, so the sweeps it runs read only its rows, and its own pin, which is not
 * {@code Stripe.API_VERSION}: the cure must follow the pin the route reads (D-SKEW-10). The sweeper
 * interval is a day, so every run here is one the test started.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    classes = SkewCureTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "einvoice.seller.id=" + IssuanceTestApp.SELLER,
      "einvoice.seller.tax-zone=Europe/Paris",
      "einvoice.numbering.prefix=INV-{fiscalYear}-",
      "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
      "einvoice.chain.hmac-key-id=k1",
      "einvoice.archive.type=filesystem",
      "einvoice.archive.root=${java.io.tmpdir}/einvoice-skew-cure-test",
      "einvoice.stripe.webhook-secrets.primary=" + SkewCureWebhookTest.SECRET,
      "einvoice.reconcile.enabled=false",
      "einvoice.issuance.sweep-interval=24h"
    })
class SkewCureWebhookTest {

  static final String SECRET = "whsec_cccccccccccccccccccccccccccccccc";

  static final String PIN = SkewCureTestApp.PIN;

  /** The previous pin, the one a webhook endpoint not yet re-pinned still sends. */
  static final String PREVIOUS = "2025-12-15.acacia";

  @LocalServerPort int port;

  @Autowired InboundEventStore inbound;

  @Autowired IssuanceTestApp.RecordingStripeSource stripeSource;

  @Autowired IssuanceSweeper sweeper;

  @Autowired IssuanceUnitOfWork unitOfWork;

  @Autowired DataSource dataSource;

  private static String freshId() {
    return "evt_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static String invoiceOf(String eventId) {
    return "in_" + eventId.substring("evt_".length());
  }

  private byte[] body(String eventId, String apiVersion) {
    return ("{\"id\":\""
            + eventId
            + "\",\"object\":\"event\",\"api_version\":\""
            + apiVersion
            + "\",\"livemode\":true,\"type\":\"invoice.finalized\",\"data\":{\"object\":{\"id\":\""
            + invoiceOf(eventId)
            + "\",\"object\":\"invoice\"}}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String signature(byte[] body) {
    long t = System.currentTimeMillis() / 1000;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
      mac.update(body);
      return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpStatus post(String eventId, String apiVersion) {
    byte[] body = body(eventId, apiVersion);
    ResponseEntity<Void> response =
        RestClient.builder()
            .baseUrl("http://127.0.0.1:" + port)
            .build()
            .post()
            .uri("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header(WebhookSignature.HEADER, signature(body))
            .body(body)
            .retrieve()
            .onStatus(status -> true, (request, r) -> {})
            .toBodilessEntity();
    return HttpStatus.valueOf(response.getStatusCode().value());
  }

  private InboundState awaitState(String eventId, InboundState state) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> assertThat(inbound.find(eventId).orElseThrow().state()).isEqualTo(state));
    return state;
  }

  private long issued(String invoiceId) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM einvoice_issuance WHERE stripe_invoice_id = ?")) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** The wrong copy, signed and posted, routed to skew by the worker. */
  private String refusedAtThePreviousPin() {
    String eventId = freshId();
    stripeSource.add(invoiceOf(eventId));
    assertThat(post(eventId, PREVIOUS)).isEqualTo(HttpStatus.OK);
    awaitState(eventId, InboundState.REFUSED_VERSION_SKEW);
    return eventId;
  }

  // --------------------------------------------------------------------------------- probe 12

  @Test
  void the_sdk_version_copy_of_an_event_refused_at_the_previous_pin_is_processed()
      throws Exception {
    // The review's probe: in production the pin is the SDK's version, here the host's own (probe
    // 23 shows why that difference matters). The copy at the pin is processed, once.
    String eventId = refusedAtThePreviousPin();

    assertThat(post(eventId, PIN)).isEqualTo(HttpStatus.OK);

    awaitState(eventId, InboundState.COMPLETED);
    assertThat(inbound.find(eventId).orElseThrow().apiVersion()).isEqualTo(PIN);
    assertThat(issued(invoiceOf(eventId))).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------- probe 13

  @Test
  void a_dashboard_resend_at_the_pin_issues_a_stuck_event_once() throws Exception {
    String eventId = refusedAtThePreviousPin();
    // Stuck: a sweep does not re-pick it, its version is not the pin.
    sweeper.sweepOnce();
    assertThat(inbound.find(eventId).orElseThrow().state())
        .isEqualTo(InboundState.REFUSED_VERSION_SKEW);

    // The operator resends it from the Dashboard to the re-pinned endpoint, and presses twice.
    assertThat(post(eventId, PIN)).isEqualTo(HttpStatus.OK);
    awaitState(eventId, InboundState.COMPLETED);
    InboundEvent done = inbound.find(eventId).orElseThrow();
    assertThat(post(eventId, PIN)).isEqualTo(HttpStatus.OK);

    sweeper.sweepOnce();
    assertThat(inbound.find(eventId).orElseThrow()).isEqualTo(done);
    assertThat(issued(invoiceOf(eventId))).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------- probe 14

  @Test
  void reconciliation_and_a_cured_event_issue_one_document() throws Exception {
    String eventId = refusedAtThePreviousPin();
    String invoice = invoiceOf(eventId);
    String reconId = "recon-" + invoice;

    // Reconciliation finds the invoice unissued and enqueues its own row, at the pin.
    sweeper.reconcileOnce();
    assertThat(inbound.find(reconId)).isPresent();

    // The right copy arrives and cures the webhook row; both rows run, one after the other.
    assertThat(post(eventId, PIN)).isEqualTo(HttpStatus.OK);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              sweeper.sweepOnce();
              assertThat(inbound.find(eventId).orElseThrow().state())
                  .isEqualTo(InboundState.COMPLETED);
              assertThat(inbound.find(reconId).orElseThrow().state())
                  .isEqualTo(InboundState.COMPLETED);
            });
    assertThat(issued(invoice)).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------- probe 23

  @Test
  void the_webhook_cures_at_the_configured_pin() throws Exception {
    assertThat(PIN).isNotEqualTo(com.stripe.Stripe.API_VERSION);
    String eventId = refusedAtThePreviousPin();

    // A copy at the SDK's version is not the host's pin: it cures nothing.
    assertThat(post(eventId, com.stripe.Stripe.API_VERSION)).isEqualTo(HttpStatus.OK);
    InboundEvent still = inbound.find(eventId).orElseThrow();
    assertThat(still.apiVersion()).isEqualTo(PREVIOUS);
    assertThat(still.cureGeneration()).isZero();

    assertThat(post(eventId, PIN)).isEqualTo(HttpStatus.OK);
    awaitState(eventId, InboundState.COMPLETED);
    assertThat(issued(invoiceOf(eventId))).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------- probe 24

  @Test
  void a_run_overtaken_by_a_cure_logs_the_cure_not_an_outside_writer(CapturedOutput output)
      throws Exception {
    String eventId = freshId();
    stripeSource.add(invoiceOf(eventId));
    byte[] wrongBody = body(eventId, PREVIOUS);
    assertThat(
            inbound.record(
                InboundEvent.received(
                    new EventIdentity(
                        eventId, "invoice.finalized", PREVIOUS, true, "", invoiceOf(eventId)),
                    Mode.LIVE,
                    "primary",
                    wrongBody,
                    Instant.now()),
                wrongBody,
                PIN))
        .isEqualTo(Recorded.FIRST);

    ExecutorService runner = Executors.newSingleThreadExecutor();
    try (Connection holder = dataSource.getConnection()) {
      // Hold the row, so the run reads it at the previous version and waits on its stamp.
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement(
              "SELECT 1 FROM einvoice_inbound_event WHERE event_id = ? FOR UPDATE")) {
        lock.setString(1, eventId);
        lock.executeQuery().close();
      }
      Future<IssuanceUnitOfWork.Outcome> run = runner.submit(() -> unitOfWork.process(eventId));
      awaitStampWaiter();
      // The cure lands first, in the holder's transaction: the real statement, on the held row.
      byte[] rightBody = body(eventId, PIN);
      assertThat(
              new JdbcInboundEventStore(JdbcUnitOfWork.using(holder))
                  .record(
                      InboundEvent.received(
                          new EventIdentity(
                              eventId, "invoice.finalized", PIN, true, "", invoiceOf(eventId)),
                          Mode.LIVE,
                          "primary",
                          rightBody,
                          Instant.now()),
                      rightBody,
                      PIN))
          .isEqualTo(Recorded.CURED);
      holder.commit();

      IssuanceUnitOfWork.Outcome outcome = run.get(30, TimeUnit.SECONDS);
      assertThat(outcome.state()).isEqualTo(InboundState.RECEIVED);
    } finally {
      runner.shutdownNow();
    }
    assertThat(output.getAll())
        .contains("event " + eventId + " was cured by its copy at API version " + PIN)
        .doesNotContain("event " + eventId + " was moved to");

    // The cured row runs next.
    sweeper.sweepOnce();
    awaitState(eventId, InboundState.COMPLETED);
    assertThat(issued(invoiceOf(eventId))).isEqualTo(1);
  }

  private void awaitStampWaiter() throws Exception {
    try (Connection probe = dataSource.getConnection()) {
      for (int i = 0; i < 500; i++) {
        try (PreparedStatement ps =
                probe.prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                        + " AND query LIKE 'UPDATE einvoice_inbound_event SET attempts%'");
            ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (rs.getLong(1) > 0) {
            return;
          }
        }
        Thread.sleep(20);
      }
      throw new IllegalStateException("the run never waited on its stamp");
    }
  }
}
