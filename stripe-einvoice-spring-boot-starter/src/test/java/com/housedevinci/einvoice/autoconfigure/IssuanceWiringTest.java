package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What the issuance half wires, what it refuses to wire, and what it refuses to start with. */
class IssuanceWiringTest {

  private static final String ROOT = System.getProperty("java.io.tmpdir") + "/einvoice-wiring-test";

  private ApplicationContextRunner runner(String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(Ports.class)
        .withPropertyValues(base())
        .withPropertyValues(properties);
  }

  private static String[] base() {
    return new String[] {
      "einvoice.seller.id=wiring",
      "einvoice.seller.tax-zone=Europe/Paris",
      "einvoice.numbering.prefix=INV-{fiscalYear}-",
      // One keyed chain for every starter test that boots a context: the trail is one per
      // database, its anchor records whether it is keyed, and a context configured the other
      // way is refused on append.
      "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
      "einvoice.chain.hmac-key-id=k1",
      "einvoice.archive.type=filesystem",
      "einvoice.archive.root=" + ROOT,
      "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    };
  }

  @Test
  void a_configured_application_gets_the_unit_of_work_the_worker_and_the_sweeper() {
    runner()
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(com.housedevinci.einvoice.application.IssuanceUnitOfWork.class)
                    .hasSingleBean(IssuanceWorker.class)
                    .hasSingleBean(IssuanceSweeper.class)
                    .hasSingleBean(InboundEventStore.class)
                    .hasSingleBean(IssuanceFindingService.class));
  }

  @Test
  void without_a_renderer_and_with_no_intake_configured_the_numbering_api_still_starts() {
    // A host with no webhook secret and no explicit einvoice.issuance.enabled has given every
    // visible sign that it wants the numbering API only. An application that can allocate a
    // number and cannot produce a validated document would otherwise consume a legal series and
    // archive nothing, so the issuance half still does not start - but quietly, with a WARN, not a
    // failure (D2-02).
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(PortsWithoutRenderer.class)
        .withPropertyValues(noIntakeConfigured())
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(com.housedevinci.einvoice.application.IssuanceUnitOfWork.class)
                    .doesNotHaveBean(IssuanceWorker.class)
                    .doesNotHaveBean(StripeWebhookController.class)
                    .hasSingleBean(IssuanceNumberingService.class));
  }

  // D2-02
  @Test
  void probe_a_configured_intake_without_a_renderer_is_refused_loudly_not_silently() {
    // base() carries a webhook secret and an archive root: an application that plainly expects to
    // receive events. Silently starting with no endpoint, no sweeper and no signal is precisely
    // the failure the durable-record-first design exists to prevent, reached by a configuration
    // mistake rather than a crash.
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(PortsWithoutRenderer.class)
        .withPropertyValues(base())
        .run(
            context ->
                assertThat(
                        context.getStartupFailure() == null
                            && !context.containsBean("einvoiceWebhookController"))
                    .describedAs(
                        "a configured intake with no renderer must either fail startup or still"
                            + " wire the controller - never both start clean and say nothing")
                    .isFalse());
  }

  @Test
  void a_configured_intake_without_a_renderer_names_the_missing_bean_and_the_opt_out() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(PortsWithoutRenderer.class)
        .withPropertyValues(base())
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(EInvoiceException.class)
                    .hasMessageContaining("DocumentRenderer")
                    .hasMessageContaining("einvoice.issuance.enabled=false"));
  }

  @Test
  void an_explicitly_enabled_issuance_without_a_renderer_fails_fast_even_with_no_secret() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(PortsWithoutRenderer.class)
        .withPropertyValues(noIntakeConfigured())
        .withPropertyValues("einvoice.issuance.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  // D2-05
  @Test
  void probe_an_explicitly_disabled_intake_starts_even_with_secrets_configured() {
    // base() carries a webhook secret, which on its own means "configured". An explicit
    // einvoice.issuance.enabled=false must win over it - a shared configuration server or a
    // rollback is the ordinary shape of this, and without the fix the refusal's own remedy ("set
    // einvoice.issuance.enabled=false") does not work, because the operator has already done it.
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(PortsWithoutRenderer.class)
        .withPropertyValues(base())
        .withPropertyValues("einvoice.issuance.enabled=false")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .describedAs(
                        "an explicit einvoice.issuance.enabled=false must be honoured even with a"
                            + " webhook secret configured")
                    .isNull());
  }

  @Test
  void an_explicitly_disabled_issuance_with_no_secret_and_no_renderer_only_warns() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(PortsWithoutRenderer.class)
        .withPropertyValues(noIntakeConfigured())
        .withPropertyValues("einvoice.issuance.enabled=false")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(
                        com.housedevinci.einvoice.application.IssuanceUnitOfWork.class));
  }

  /** No webhook secret, and archive properties valid but unrelated to intake configuration. */
  private static String[] noIntakeConfigured() {
    return new String[] {
      "einvoice.seller.id=wiring",
      "einvoice.seller.tax-zone=Europe/Paris",
      "einvoice.numbering.prefix=INV-{fiscalYear}-",
      "einvoice.chain.unkeyed=true",
      "einvoice.archive.type=filesystem",
      "einvoice.archive.root=" + ROOT
    };
  }

  @Test
  void a_store_without_atomic_create_is_refused_at_startup() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(PortsWithOverwritingArchive.class)
        .withPropertyValues(base())
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(EInvoiceException.class)
                    .hasMessageContaining("overwrote"));
  }

  @Test
  void the_weaker_mode_lets_a_non_atomic_store_start_and_turns_on_the_read_back() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(PortsWithOverwritingArchive.class)
        .withPropertyValues(base())
        .withPropertyValues("einvoice.archive.allow-non-atomic-store=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBean(com.housedevinci.einvoice.application.IssuanceUnitOfWork.class)
                          .configuration()
                          .readBackAfterWrite())
                  .isTrue();
            });
  }

  @Test
  void a_filesystem_archive_with_no_root_is_refused_by_name() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(Ports.class)
        .withPropertyValues(
            "einvoice.seller.id=wiring",
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.unkeyed=true",
            "einvoice.archive.type=filesystem")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("einvoice.archive.root"));
  }

  @Test
  void the_closed_year_cutoff_reaches_the_unit_of_work_when_it_is_set() {
    runner("einvoice.numbering.closed-year-cutoff=90d")
        .run(
            context ->
                assertThat(
                        context
                            .getBean(com.housedevinci.einvoice.application.IssuanceUnitOfWork.class)
                            .configuration()
                            .closedYearCutoff())
                    .contains(java.time.Duration.ofDays(90)));
  }

  @Test
  void with_no_cutoff_configured_none_is_applied() {
    runner()
        .run(
            context ->
                assertThat(
                        context
                            .getBean(com.housedevinci.einvoice.application.IssuanceUnitOfWork.class)
                            .configuration()
                            .closedYearCutoff())
                    .isEmpty());
  }

  @Test
  void a_webhook_endpoint_with_no_signing_secret_is_refused_rather_than_left_open() {
    // The keyring is the only thing between the internet and the document generator, so an
    // application with an endpoint and no secret must not start at all.
    assertThat(
            org.assertj.core.api.Assertions.catchThrowableOfType(
                EInvoiceException.class,
                () -> StripeSecrets.requireWebhookSecrets(java.util.Map.of())))
        .extracting("code")
        .isEqualTo(ErrorCodes.CONFIG);
  }

  @Test
  void a_signing_secret_that_is_not_one_is_refused_without_printing_it() {
    EInvoiceException refused =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            EInvoiceException.class,
            () ->
                StripeSecrets.requireWebhookSecrets(
                    java.util.Map.of("primary", "not-a-stripe-secret")));
    assertThat(refused.getMessage())
        .contains("einvoice.stripe.webhook-secrets.primary")
        .doesNotContain("not-a-stripe-secret");
  }

  @Test
  void a_tolerance_wider_than_the_bound_is_refused_at_startup() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowableOfType(
                EInvoiceException.class,
                () -> StripeSecrets.requireBoundedTolerance(java.time.Duration.ofHours(2))))
        .extracting("code")
        .isEqualTo(ErrorCodes.CONFIG);
  }

  @Test
  void an_api_key_that_is_not_restricted_is_refused_by_default_and_never_printed() {
    EInvoiceException refused =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            EInvoiceException.class,
            () -> StripeSecrets.requireApiKey("sk_live_notarealkey", true));
    assertThat(refused.getMessage()).doesNotContain("sk_live_notarealkey");
    // The weaker mode exists, loudly.
    StripeSecrets.requireApiKey("sk_live_notarealkey", false);
  }

  @Test
  void the_health_contributor_is_not_in_the_readiness_or_liveness_group() {
    // I-04: a business condition three weeks old must never be able to take the host application
    // out of the load balancer, so this module's contributor lives in a group of its own.
    assertThat(EInvoiceHealthGroupPostProcessor.GROUP_PROPERTY)
        .isEqualTo("management.endpoint.health.group.einvoice.include");
    assertThat(EInvoiceHealthGroupPostProcessor.CONTRIBUTOR).isEqualTo("einvoiceIssuance");

    runner()
        .run(
            context -> {
              org.springframework.core.env.Environment environment = context.getEnvironment();
              assertThat(
                      environment.getProperty(
                          "management.endpoint.health.group.readiness.include", ""))
                  .doesNotContain(EInvoiceHealthGroupPostProcessor.CONTRIBUTOR);
              assertThat(
                      environment.getProperty(
                          "management.endpoint.health.group.liveness.include", ""))
                  .doesNotContain(EInvoiceHealthGroupPostProcessor.CONTRIBUTOR);
            });
  }

  @Configuration
  public static class Ports {

    // destroyMethod = "": the pool is shared by every context these tests start, and a context
    // that closed it would take the next test's database with it.
    @Bean(destroyMethod = "")
    DataSource dataSource() {
      return TestPostgres.dataSource();
    }

    @Bean
    DocumentRenderer renderer() {
      return input ->
          new DocumentRenderer.RenderedDocument(
              "<Invoice/>".getBytes(java.nio.charset.StandardCharsets.UTF_8), "xml", "test");
    }

    @Bean
    DocumentValidator validator() {
      return (bytes, input) -> DocumentValidator.Report.passed();
    }

    @Bean
    StripeInvoiceSource source() {
      return new IssuanceTestApp.RecordingStripeSource();
    }
  }

  @Configuration
  static class PortsWithoutRenderer {

    // destroyMethod = "": the pool is shared by every context these tests start, and a context
    // that closed it would take the next test's database with it.
    @Bean(destroyMethod = "")
    DataSource dataSource() {
      return TestPostgres.dataSource();
    }

    @Bean
    StripeInvoiceSource source() {
      return new IssuanceTestApp.RecordingStripeSource();
    }
  }

  @Configuration
  static class PortsWithOverwritingArchive extends Ports {

    /** A store that ignores the conditional header and answers success: I-06's real-world shape. */
    @Bean
    ArchiveStore archive() {
      return new ArchiveStore() {

        @Override
        public boolean supportsAtomicCreate() {
          return true;
        }

        @Override
        public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
          return WriteResult.CREATED;
        }

        @Override
        public Optional<byte[]> get(ArchiveKey key) {
          return Optional.empty();
        }

        @Override
        public List<String> list(String prefix, int limit) {
          return List.of();
        }

        @Override
        public String describe() {
          return "a store that says yes to everything (test)";
        }
      };
    }
  }
}
