package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.FindingStore;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.ReconciliationSweep;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The three background jobs and the two signals, exercised rather than merely wired: what the
 * sweeper does when it runs, what the findings endpoint returns, what health says when the watcher
 * has been silent, and what an acknowledgement records.
 */
class IssuanceOperationsTest {

  private static final Instant NOW = Instant.parse("2026-01-16T09:00:00Z");

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
                + "/einvoice-operations-test",
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .withPropertyValues(properties);
  }

  @Test
  void the_sweeper_runs_its_three_jobs_and_records_when_each_last_completed() {
    runner()
        .run(
            context -> {
              IssuanceSweeper sweeper = context.getBean(IssuanceSweeper.class);
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
              byte[] body = ("{\"id\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
              inbound.record(
                  InboundEvent.received(
                      new EventIdentity(
                          eventId, "invoice.finalized", "2026-08-26.dahlia", true, "", "in_1"),
                      Mode.LIVE,
                      "primary",
                      body,
                      NOW),
                  body);

              assertThat(sweeper.sweepOnce()).isPositive();
              assertThat(sweeper.lastSweepAt()).isPresent();

              assertThat(sweeper.reconcileOnce()).isPresent();
              assertThat(sweeper.lastReconciliationAt()).isPresent();
              assertThat(sweeper.lastReconciliationResult())
                  .get()
                  .extracting(ReconciliationSweep.Result::stripeInvoicesChecked)
                  .isNotNull();

              // Bounded by the batch, and counted. What it removes is buyer data by definition,
              // so the job logs a count and nothing else.
              assertThat(sweeper.purgeOnce())
                  .isBetween(
                      0, context.getBean(EInvoiceProperties.class).getInbound().getPurgeBatch());

              assertThat(sweeper.verifyChainOnce()).isPresent();
              assertThat(sweeper.lastChainCheck())
                  .get()
                  .extracting(check -> check.report().status().name())
                  .isNotNull();
            });
  }

  @Test
  void the_purge_removes_what_is_past_the_retention_ceiling_and_logs_a_count() {
    runner("einvoice.inbound.retention=1s")
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
              byte[] body = ("{\"id\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
              inbound.record(
                  InboundEvent.received(
                      new EventIdentity(
                          eventId, "invoice.finalized", "2026-08-26.dahlia", true, "", "in_1"),
                      Mode.LIVE,
                      "primary",
                      body,
                      Instant.now().minus(Duration.ofDays(2))),
                  body);
              TestPostgres.execute(
                  "UPDATE einvoice_inbound_event SET received_at = now() - interval '2 days'"
                      + " WHERE event_id = '"
                      + eventId
                      + "'");

              assertThat(context.getBean(IssuanceSweeper.class).purgeOnce()).isPositive();
              assertThat(inbound.find(eventId)).isEmpty();
            });
  }

  @Test
  void the_findings_endpoint_returns_codes_counts_and_subjects_and_no_buyer_field() {
    runner()
        .run(
            context -> {
              FindingStore findings = context.getBean(FindingStore.class);
              String subject = "in_" + UUID.randomUUID().toString().replace("-", "");
              findings.record(
                  ComplianceFinding.of(
                      "operations", Mode.LIVE, ErrorCodes.RECON_MISSING_ISSUANCE, subject, NOW));

              Map<String, Object> response =
                  context.getBean(IssuanceFindingsEndpoint.class).findings();

              assertThat(response).containsEntry("mode", "live");
              assertThat(response.get("counts").toString())
                  .contains(ErrorCodes.RECON_MISSING_ISSUANCE);
              assertThat(response.get("open").toString()).contains(subject).doesNotContain("Buyer");
            });
  }

  @Test
  void acknowledging_a_finding_records_a_reason_and_clears_it_from_the_open_list() {
    runner()
        .run(
            context -> {
              FindingStore findings = context.getBean(FindingStore.class);
              IssuanceFindingService service = context.getBean(IssuanceFindingService.class);
              String subject = "in_" + UUID.randomUUID().toString().replace("-", "");
              findings.record(
                  ComplianceFinding.of(
                      "operations", Mode.LIVE, ErrorCodes.RECON_STUCK_ISSUANCE, subject, NOW));
              assertThat(service.open(50))
                  .extracting(ComplianceFinding::subjectId)
                  .contains(subject);

              service.acknowledge(
                  ErrorCodes.RECON_STUCK_ISSUANCE, subject, "voided by hand after the outage");

              assertThat(service.open(50))
                  .extracting(ComplianceFinding::subjectId)
                  .doesNotContain(subject);
              // And an acknowledgement with no reason is a delete with extra steps.
              assertThatThrownBy(
                      () -> service.acknowledge(ErrorCodes.RECON_STUCK_ISSUANCE, subject, "  "))
                  .isInstanceOf(EInvoiceException.class);
            });
  }

  @Test
  void health_reports_up_once_the_watcher_has_run_and_down_when_it_goes_quiet() {
    runner()
        .run(
            context -> {
              IssuanceSweeper sweeper = context.getBean(IssuanceSweeper.class);
              sweeper.sweepOnce();
              sweeper.reconcileOnce();
              sweeper.verifyChainOnce();

              EInvoiceHealthIndicator fresh =
                  new EInvoiceHealthIndicator(
                      java.util.Optional.of(sweeper),
                      context.getBean(EInvoiceProperties.class),
                      Clock.systemUTC());
              assertThat(fresh.health().getStatus().getCode()).isEqualTo("UP");
              assertThat(fresh.health().getDetails()).containsKeys("lastSweep", "chain");

              // A day later, with nothing having run since: never "healthy", because the whole
              // point of the watcher is to notice silence.
              EInvoiceHealthIndicator stale =
                  new EInvoiceHealthIndicator(
                      java.util.Optional.of(sweeper),
                      context.getBean(EInvoiceProperties.class),
                      Clock.fixed(Instant.now().plus(Duration.ofDays(1)), ZoneOffset.UTC));
              assertThat(stale.health().getStatus().getCode()).isEqualTo("DOWN");
            });
  }

  @Test
  void health_says_nothing_about_reconciliation_when_it_is_switched_off() {
    runner("einvoice.reconcile.enabled=false")
        .run(
            context -> {
              IssuanceSweeper sweeper = context.getBean(IssuanceSweeper.class);
              sweeper.sweepOnce();
              sweeper.verifyChainOnce();
              EInvoiceHealthIndicator health =
                  new EInvoiceHealthIndicator(
                      java.util.Optional.of(sweeper),
                      context.getBean(EInvoiceProperties.class),
                      Clock.systemUTC());
              assertThat(health.health().getDetails()).doesNotContainKey("lastReconciliation");
            });
  }

  @Test
  void the_worker_reports_what_it_processed_what_it_refused_and_what_is_waiting() {
    runner()
        .run(
            context -> {
              IssuanceWorker worker = context.getBean(IssuanceWorker.class);
              assertThat(worker.queueDepth()).isZero();
              assertThat(worker.processedCount()).isNotNegative();
              assertThat(worker.rejectedCount()).isNotNegative();
              // An event that does not exist is a refusal the worker logs and swallows: a worker
              // thread that died on one bad id would stop draining the queue for every good one.
              assertThat(worker.submit("evt_does_not_exist")).isTrue();
            });
  }

  @Test
  void every_property_binds_and_reads_back_as_written() {
    // Properties are the module's public API as much as its types are: a getter that no test ever
    // reads is a rename nobody notices until a customer's configuration stops taking effect.
    runner(
            "einvoice.rule-pack-version=fr-2026.2",
            "einvoice.stripe.api-version=",
            "einvoice.stripe.require-restricted-key=false",
            "einvoice.stripe.telemetry=false",
            "einvoice.stripe.list-lookback=45d",
            "einvoice.webhook.path=/hooks/stripe",
            "einvoice.webhook.max-body-bytes=65536",
            "einvoice.webhook.tolerance=120s",
            "einvoice.issuance.concurrency=3",
            "einvoice.issuance.queue-capacity=64",
            "einvoice.issuance.retry-backoff=30s",
            "einvoice.issuance.max-retry-backoff=20m",
            "einvoice.issuance.retry-ceiling=48h",
            "einvoice.issuance.alert-after=3h",
            "einvoice.issuance.sweep-interval=2m",
            "einvoice.inbound.retention=14d",
            "einvoice.inbound.purge-batch=250",
            "einvoice.reconcile.interval=5m",
            "einvoice.reconcile.window=3d",
            "einvoice.reconcile.grace=10m",
            "einvoice.reconcile.drift-sample=25",
            "einvoice.reconcile.page-size=100",
            "einvoice.archive.s3.bucket=archive",
            "einvoice.archive.s3.prefix=tenant-a",
            "einvoice.archive.s3.region=eu-west-3",
            "einvoice.archive.s3.endpoint=https://s3.example.invalid",
            "einvoice.archive.s3.path-style-access=true")
        .run(
            context -> {
              EInvoiceProperties properties = context.getBean(EInvoiceProperties.class);
              assertThat(properties.getRulePackVersion()).isEqualTo("fr-2026.2");
              assertThat(properties.getStripe().getApiVersion()).isEmpty();
              assertThat(properties.getStripe().isRequireRestrictedKey()).isFalse();
              assertThat(properties.getStripe().isTelemetry()).isFalse();
              assertThat(properties.getStripe().getListLookback()).isEqualTo(Duration.ofDays(45));
              assertThat(properties.getStripe().getWebhookSecrets()).containsKey("primary");
              assertThat(properties.getWebhook().getPath()).isEqualTo("/hooks/stripe");
              assertThat(properties.getWebhook().getMaxBodyBytes()).isEqualTo(65536);
              assertThat(properties.getWebhook().getTolerance()).isEqualTo(Duration.ofMinutes(2));
              assertThat(properties.getWebhook().isEnabled()).isTrue();
              assertThat(properties.getIssuance().getConcurrency()).isEqualTo(3);
              assertThat(properties.getIssuance().getQueueCapacity()).isEqualTo(64);
              assertThat(properties.getIssuance().getRetryBackoff())
                  .isEqualTo(Duration.ofSeconds(30));
              assertThat(properties.getIssuance().getMaxRetryBackoff())
                  .isEqualTo(Duration.ofMinutes(20));
              assertThat(properties.getIssuance().getRetryCeiling())
                  .isEqualTo(Duration.ofHours(48));
              assertThat(properties.getIssuance().getAlertAfter()).isEqualTo(Duration.ofHours(3));
              assertThat(properties.getIssuance().getSweepInterval())
                  .isEqualTo(Duration.ofMinutes(2));
              assertThat(properties.getIssuance().isEnabled()).isTrue();
              assertThat(properties.getInbound().getRetention()).isEqualTo(Duration.ofDays(14));
              assertThat(properties.getInbound().getPurgeBatch()).isEqualTo(250);
              assertThat(properties.getReconcile().getInterval()).isEqualTo(Duration.ofMinutes(5));
              assertThat(properties.getReconcile().getWindow()).isEqualTo(Duration.ofDays(3));
              assertThat(properties.getReconcile().getGrace()).isEqualTo(Duration.ofMinutes(10));
              assertThat(properties.getReconcile().getDriftSample()).isEqualTo(25);
              assertThat(properties.getReconcile().getPageSize()).isEqualTo(100);
              assertThat(properties.getReconcile().isEnabled()).isTrue();
              assertThat(properties.getArchive().getType()).isEqualTo("filesystem");
              assertThat(properties.getArchive().getRoot()).contains("einvoice-operations-test");
              assertThat(properties.getArchive().isAllowNonAtomicStore()).isFalse();
              assertThat(properties.getArchive().getS3().getBucket()).isEqualTo("archive");
              assertThat(properties.getArchive().getS3().getPrefix()).isEqualTo("tenant-a");
              assertThat(properties.getArchive().getS3().getRegion()).isEqualTo("eu-west-3");
              assertThat(properties.getArchive().getS3().getEndpoint())
                  .isEqualTo("https://s3.example.invalid");
              assertThat(properties.getArchive().getS3().isPathStyleAccess()).isTrue();
            });
  }

  @Test
  void the_bundled_stripe_source_is_built_from_the_configured_key_and_pins_the_sdk_version() {
    // No network: building the client and reading its pin is what is asserted. A key that is not
    // restricted is refused by default, and the sample value below is obviously not a real one.
    runner("einvoice.stripe.api-key=rk_test_" + "0".repeat(24), "einvoice.stripe.telemetry=false")
        .run(
            context ->
                assertThat(
                        context
                            .getBean(
                                com.housedevinci.einvoice.application.StripeInvoiceSource.class)
                            .pinnedApiVersion())
                    .isNotBlank());
  }

  @Test
  void an_event_the_sweeper_re_picks_reaches_the_worker() {
    runner("einvoice.issuance.sweep-interval=10m")
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
              byte[] body = ("{\"id\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
              inbound.record(
                  InboundEvent.received(
                      new EventIdentity(
                          eventId, "invoice.finalized", "2026-08-26.dahlia", true, "", "in_1"),
                      Mode.LIVE,
                      "primary",
                      body,
                      Instant.now()),
                  body);

              assertThat(context.getBean(IssuanceSweeper.class).sweepOnce()).isPositive();
              org.awaitility.Awaitility.await()
                  .atMost(Duration.ofSeconds(20))
                  .untilAsserted(
                      () ->
                          assertThat(inbound.find(eventId).orElseThrow().state())
                              .isNotEqualTo(InboundState.RECEIVED));
            });
  }
}
