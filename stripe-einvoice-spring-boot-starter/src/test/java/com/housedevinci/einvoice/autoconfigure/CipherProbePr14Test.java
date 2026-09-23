package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR 14, security review. The release fix promotes {@code einvoice.issuance.enabled=false} to the
 * shipped sample's default and to the documented remedy in the wiring check's refusal message ("run
 * the numbering API only"). These probes ask what that property actually turns off.
 */
class CipherProbePr14Test {

  private static final String ROOT = System.getProperty("java.io.tmpdir") + "/einvoice-cipher-pr14";

  /**
   * D14-02. With every intake bean present, a webhook signing secret configured, and the operator
   * having said {@code einvoice.issuance.enabled=false}, the module still maps its unauthenticated
   * webhook endpoint and still builds the worker and the inbound event store; only the sweeper is
   * conditional on the property. The result is a host that accepts and durably records Stripe
   * events, answers Stripe 200 so nothing retries and nothing alerts, and never issues a document -
   * the exact failure the intake wiring check exists to prevent (D2-02), reached through the
   * property the check's own message and the README recommend as the way to turn intake off. The
   * wiring check is also silent in this state: it returns at the "all three beans present" branch
   * before it reaches the "numbering API only, as configured" line.
   */
  @Test
  void probe_disabled_issuance_still_maps_the_unauthenticated_webhook_endpoint() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(Ports.class)
        .withPropertyValues(base())
        .withPropertyValues("einvoice.issuance.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .describedAs(
                      "einvoice.issuance.enabled=false is documented as 'run the numbering API"
                          + " only', so no Stripe intake may be reachable: the unauthenticated"
                          + " webhook endpoint must not be mapped")
                  .doesNotHaveBean(StripeWebhookController.class);
              assertThat(context)
                  .describedAs(
                      "nor may the pipeline that records what that endpoint accepts be built")
                  .doesNotHaveBean(IssuanceWorker.class);
            });
  }

  private static String[] base() {
    return new String[] {
      "einvoice.seller.id=cipher-pr14",
      "einvoice.seller.tax-zone=Europe/Paris",
      "einvoice.numbering.prefix=INV-{fiscalYear}-",
      "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
      "einvoice.chain.hmac-key-id=k1",
      "einvoice.archive.type=filesystem",
      "einvoice.archive.root=" + ROOT,
      "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    };
  }

  /**
   * D14-03. The two Stripe secrets the README and {@code application-intake.yml} print as the
   * values to paste are not in the module's own placeholder denylist, so pasting the documented
   * command verbatim starts an application whose webhook endpoint is authenticated by a signing
   * secret printed in a public README. The module already refuses {@code whsec_changeme} for this
   * reason; it must refuse the placeholders it publishes itself.
   */
  @Test
  void probe_the_readme_placeholder_secrets_are_accepted_as_real_secrets() {
    assertThat(
            catchStartup(
                "einvoice.stripe.webhook-secrets.primary=whsec_from_your_stripe_dashboard"))
        .describedAs(
            "the webhook signing secret printed in the public README must be refused as a"
                + " placeholder, not accepted as the secret guarding the endpoint")
        .isNotNull();
  }

  private static Throwable catchStartup(String override) {
    final Throwable[] failure = new Throwable[1];
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(Ports.class)
        .withPropertyValues(base())
        .withPropertyValues(override)
        .run(context -> failure[0] = context.getStartupFailure());
    return failure[0];
  }

  @Configuration
  static class Ports {

    // destroyMethod = "": the pool is shared by every context these tests start.
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
}
