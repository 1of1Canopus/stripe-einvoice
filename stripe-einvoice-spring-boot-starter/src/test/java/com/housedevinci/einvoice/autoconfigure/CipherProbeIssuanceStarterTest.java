package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The edge and the watcher, probed at the level of the mechanism rather than of the wiring. Each
 * probe was run with its control removed and shown to go RED; the runs are in the pull request
 * body.
 */
class CipherProbeIssuanceStarterTest {

  private static final String SECRET = "whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final Instant NOW = Instant.parse("2026-01-16T09:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static String body(String eventId) {
    return "{\"id\":\""
        + eventId
        + "\",\"type\":\"invoice.finalized\",\"api_version\":\""
        + IssuanceTestApp.PINNED_VERSION
        + "\",\"livemode\":true,\"data\":{\"object\":{\"id\":\"in_1\"}}}";
  }

  private static String signature(String secret, byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((NOW.getEpochSecond() + ".").getBytes(StandardCharsets.UTF_8));
      mac.update(payload);
      return "t=" + NOW.getEpochSecond() + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static MockHttpServletRequest request(byte[] payload, String signature, String type) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/stripe");
    request.setContentType(type);
    request.setContent(payload);
    if (signature != null) {
      request.addHeader("Stripe-Signature", signature);
    }
    return request;
  }

  private static String freshId() {
    return "evt_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static com.housedevinci.einvoice.application.InboundIntake intake(
      org.springframework.context.ApplicationContext context, InboundEventStore inbound) {
    return new com.housedevinci.einvoice.application.InboundIntake(
        inbound,
        context.getBean(com.housedevinci.einvoice.application.FindingStore.class),
        context
            .getBean(com.housedevinci.einvoice.application.IssuanceUnitOfWork.class)
            .configuration(),
        CLOCK);
  }

  private StripeWebhookController controller(
      org.springframework.context.ApplicationContext context,
      InboundEventStore inbound,
      IssuanceWorker worker) {
    return new StripeWebhookController(
        intake(context, inbound),
        worker,
        Map.of("primary", SECRET),
        Duration.ofMinutes(5),
        1024 * 1024,
        Mode.LIVE,
        CLOCK);
  }

  @Test
  void probe_an_unsigned_body_is_refused_and_nothing_is_written() {
    runner()
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              StripeWebhookController controller =
                  controller(context, inbound, context.getBean(IssuanceWorker.class));
              String eventId = freshId();
              byte[] payload = body(eventId).getBytes(StandardCharsets.UTF_8);

              ResponseEntity<Void> response =
                  controller.receive(request(payload, null, "application/json"));

              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(inbound.find(eventId)).isEmpty();
            });
  }

  @Test
  void probe_a_body_signed_with_the_wrong_secret_is_refused_and_nothing_is_written() {
    runner()
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              StripeWebhookController controller =
                  controller(context, inbound, context.getBean(IssuanceWorker.class));
              String eventId = freshId();
              byte[] payload = body(eventId).getBytes(StandardCharsets.UTF_8);

              ResponseEntity<Void> response =
                  controller.receive(
                      request(
                          payload,
                          signature("whsec_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", payload),
                          "application/json"));

              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(inbound.find(eventId)).isEmpty();
            });
  }

  @Test
  void probe_a_body_whose_declared_length_lies_is_still_capped_by_the_counting_read() {
    // I-09's second case, and the one a Content-Length check cannot cover: the header says the
    // body is small and it is not. The cap has to come from counting the bytes as they are read.
    //
    // The body below is a complete, valid, correctly signed event, padded past the cap - so a 400
    // can only come from the cap. A first version of this probe left out livemode and the object
    // id, and passed for a different reason entirely: the identity reader refused it. A probe that
    // passes with the control removed is not a probe (checklist line 60).
    runner()
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              StripeWebhookController controller =
                  new StripeWebhookController(
                      intake(context, inbound),
                      context.getBean(IssuanceWorker.class),
                      Map.of("primary", SECRET),
                      Duration.ofMinutes(5),
                      4096,
                      Mode.LIVE,
                      CLOCK);
              String eventId = freshId();
              String json =
                  "{\"id\":\""
                      + eventId
                      + "\",\"type\":\"invoice.finalized\","
                      + "\"api_version\":\""
                      + IssuanceTestApp.PINNED_VERSION
                      + "\","
                      + "\"livemode\":true,\"data\":{\"object\":{\"id\":\"in_1\"}},"
                      + "\"padding\":\""
                      + "z".repeat(64 * 1024)
                      + "\"}";
              byte[] payload = json.getBytes(StandardCharsets.UTF_8);

              MockHttpServletRequest lying =
                  new MockHttpServletRequest("POST", "/webhooks/stripe") {
                    @Override
                    public long getContentLengthLong() {
                      return 12; // the header lies, as a hostile client's would
                    }
                  };
              lying.setContentType("application/json");
              lying.setContent(payload);
              lying.addHeader("Stripe-Signature", signature(SECRET, payload));

              assertThat(controller.receive(lying).getStatusCode())
                  .isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(inbound.find(eventId)).isEmpty();
            });
  }

  @Test
  void probe_a_signature_valid_refusal_is_recorded_and_answered_200() {
    // I-01. Stripe disables an endpoint that keeps failing and an API version skew hits every event
    // on the account at once, so a refusal that answered 400 could stop the whole intake.
    runner()
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              StripeWebhookController controller =
                  controller(context, inbound, context.getBean(IssuanceWorker.class));
              String eventId = freshId();
              byte[] payload =
                  ("{\"id\":\""
                          + eventId
                          + "\",\"type\":\"invoice.finalized\",\"api_version\":\"2019-02-19\","
                          + "\"livemode\":true,\"data\":{\"object\":{\"id\":\"in_1\"}}}")
                      .getBytes(StandardCharsets.UTF_8);

              ResponseEntity<Void> response =
                  controller.receive(
                      request(payload, signature(SECRET, payload), "application/json"));

              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(inbound.find(eventId)).isPresent();
            });
  }

  @Test
  void probe_a_burst_past_the_queue_capacity_loses_no_event() {
    // I-10. The queue is one deep and the pool is one thread, so most of this burst is rejected.
    // A rejected event stays RECEIVED and the sweeper picks it up: back-pressure costs latency and
    // never an event.
    runnerWith(
            SlowSource.class,
            "einvoice.issuance.queue-capacity=1",
            "einvoice.issuance.concurrency=1",
            "einvoice.issuance.sweep-interval=24h")
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              IssuanceWorker worker = context.getBean(IssuanceWorker.class);
              List<String> events =
                  java.util.stream.IntStream.range(0, 50)
                      .mapToObj(
                          i -> {
                            String eventId = freshId();
                            EventIdentity identity =
                                new EventIdentity(
                                    eventId,
                                    "invoice.finalized",
                                    IssuanceTestApp.PINNED_VERSION,
                                    true,
                                    "",
                                    "in_burst_" + i);
                            inbound.record(
                                InboundEvent.received(
                                    identity,
                                    Mode.LIVE,
                                    "primary",
                                    body(eventId).getBytes(StandardCharsets.UTF_8),
                                    NOW),
                                body(eventId).getBytes(StandardCharsets.UTF_8),
                                IssuanceTestApp.PINNED_VERSION);
                            return eventId;
                          })
                      .toList();

              events.forEach(worker::submit);
              assertThat(worker.rejectedCount()).isPositive();

              // D-PR57-CI-1. A run stamps its row (next attempt = now + backoff) before it moves
              // it, so while the worker runs, its row is RECEIVED and not due. Reading the rows
              // then compares two moments of a moving table. The slow source holds a run in that
              // window; the test waits until every submitted event is either refused or has
              // finished its run, so both reads below see the same, settled rows.
              SlowStripeSource source = context.getBean(SlowStripeSource.class);
              assertThat(source.awaitFirstRead(Duration.ofSeconds(30)))
                  .as("the worker started a run")
                  .isTrue();
              await()
                  .atMost(Duration.ofSeconds(30))
                  .pollInterval(Duration.ofMillis(50))
                  .untilAsserted(
                      () ->
                          assertThat(worker.rejectedCount() + worker.processedCount())
                              .as("refused plus finished runs, out of %d submitted", events.size())
                              .isEqualTo(events.size()));

              // Whatever the worker refused is still there to be found, in a state a sweeper picks
              // up. Nothing was dropped on the floor.
              List<String> due =
                  inbound
                      .due(
                          NOW.plus(Duration.ofMinutes(5)),
                          Duration.ofHours(72),
                          com.housedevinci.einvoice.domain.RunningLimit.DEFAULT,
                          1000,
                          IssuanceTestApp.PINNED_VERSION)
                      .stream()
                      .map(InboundEvent::eventId)
                      .toList();
              assertThat(rejectedOf(events, inbound)).hasSize((int) worker.rejectedCount());
              assertThat(due).containsAll(rejectedOf(events, inbound));
            });
  }

  private static List<String> rejectedOf(List<String> events, InboundEventStore inbound) {
    return events.stream()
        .filter(
            eventId ->
                inbound.find(eventId).map(InboundEvent::state).orElse(InboundState.COMPLETED)
                    == InboundState.RECEIVED)
        .toList();
  }

  @Test
  void probe_the_watcher_being_silent_is_not_healthy() {
    // "No result" is never "healthy": the whole point of the watcher is to notice silence, so an
    // indicator that reported UP before its first sweep would be reporting on nothing.
    runner()
        .run(
            context -> {
              EInvoiceHealthIndicator health =
                  new EInvoiceHealthIndicator(
                      java.util.Optional.of(context.getBean(IssuanceSweeper.class)),
                      context.getBean(EInvoiceProperties.class),
                      Clock.fixed(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC));
              assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
            });
  }

  @Test
  void probe_the_health_contributor_is_outside_readiness_and_liveness() {
    runner()
        .run(
            context -> {
              assertThat(
                      context
                          .getEnvironment()
                          .getProperty("management.endpoint.health.group.readiness.include", ""))
                  .doesNotContain("einvoice");
              assertThat(
                      context
                          .getEnvironment()
                          .getProperty("management.endpoint.health.group.liveness.include", ""))
                  .doesNotContain("einvoice");
            });
  }

  @Test
  void probe_the_pipeline_refuses_to_start_without_a_validator() {
    // base() carries a webhook secret: intake is configured, so a missing DocumentValidator now
    // fails startup by name rather than starting silently with no endpoint (D2-02).
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(NoValidator.class)
        .withPropertyValues(base())
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(EInvoiceException.class)
                    .hasMessageContaining("DocumentValidator"));
  }

  private ApplicationContextRunner runner(String... properties) {
    return runnerWith(IssuanceWiringTest.Ports.class, properties);
  }

  private ApplicationContextRunner runnerWith(Class<?> ports, String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(ports)
        .withPropertyValues(base())
        .withPropertyValues(properties);
  }

  private static String[] base() {
    return new String[] {
      "einvoice.seller.id=probe",
      "einvoice.seller.tax-zone=Europe/Paris",
      "einvoice.numbering.prefix=INV-{fiscalYear}-",
      // One keyed chain for every starter test that boots a context: the trail is one per
      // database, its anchor records whether it is keyed, and a context configured the other
      // way is refused on append.
      "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
      "einvoice.chain.hmac-key-id=k1",
      "einvoice.archive.type=filesystem",
      "einvoice.archive.root=" + System.getProperty("java.io.tmpdir") + "/einvoice-probe-test",
      "einvoice.stripe.webhook-secrets.primary=" + SECRET,
      "einvoice.reconcile.enabled=false"
    };
  }

  /**
   * The test ports with a Stripe read that takes a while. A run stamps its row and only then reads
   * Stripe, so during the read the row is RECEIVED with a next attempt in the future: not due, and
   * not finished. With the fast test source that window is too short to hit on a quiet machine,
   * which is why the burst probe failed only on a loaded runner (D-PR57-CI-1). Here it is open for
   * a known time, and the test can wait until a run is inside it.
   */
  @Configuration
  static class SlowSource extends IssuanceWiringTest.Ports {

    @Bean
    @Override
    SlowStripeSource source() {
      return new SlowStripeSource(Duration.ofMillis(300));
    }
  }

  static final class SlowStripeSource implements StripeInvoiceSource {

    private final StripeInvoiceSource fast = new IssuanceTestApp.RecordingStripeSource();
    private final Duration readTime;
    private final CountDownLatch firstRead = new CountDownLatch(1);

    SlowStripeSource(Duration readTime) {
      this.readTime = readTime;
    }

    /** True once a run has stamped its row and is reading Stripe. */
    boolean awaitFirstRead(Duration timeout) throws InterruptedException {
      return firstRead.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public SourceInvoice fetchInvoice(String invoiceId) {
      firstRead.countDown();
      try {
        Thread.sleep(readTime);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return fast.fetchInvoice(invoiceId);
    }

    @Override
    public List<String> finalisedInvoiceIds(Instant from, Instant to) {
      return fast.finalisedInvoiceIds(from, to);
    }

    @Override
    public String pinnedApiVersion() {
      return fast.pinnedApiVersion();
    }
  }

  @Configuration
  static class NoValidator {

    @Bean(destroyMethod = "")
    DataSource dataSource() {
      return TestPostgres.dataSource();
    }

    @Bean
    DocumentRenderer renderer() {
      return TestRenderers.supplying(
          input ->
              new DocumentRenderer.RenderedDocument(
                  "<Invoice/>".getBytes(StandardCharsets.UTF_8), "xml", "test"));
    }

    @Bean
    StripeInvoiceSource source() {
      return new IssuanceTestApp.RecordingStripeSource();
    }
  }
}
