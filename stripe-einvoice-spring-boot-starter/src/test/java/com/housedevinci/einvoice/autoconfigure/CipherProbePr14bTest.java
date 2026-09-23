package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.JdbcInboundEventStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcIssuanceStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcUnitOfWork;
import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.PreflightSupport;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.domain.EInvoiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pass 2, D14-07: the D14-02 gate sits on two beans that are both {@code @ConditionalOnMissingBean}
 * - this module's documented "bring your own" contract. A host that supplies its own inbound store
 * and its own unit of work AND sets einvoice.issuance.enabled=false gets the whole intake back:
 * worker, unauthenticated webhook endpoint, durable rows. The wiring check answers that state with
 * the numbering-only line, which states "no webhook endpoint, no inbound event store, no issuance
 * worker" without ever asking the bean factory whether that is true.
 */
@ExtendWith(OutputCaptureExtension.class)
class CipherProbePr14bTest {

  private static final String ROOT = System.getProperty("java.io.tmpdir") + "/einvoice-pr14b";

  private WebApplicationContextRunner runner() {
    return new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(HostBroughtItsOwn.class)
        .withPropertyValues(
            "einvoice.seller.id=pr14b",
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
            "einvoice.chain.hmac-key-id=k1",
            "einvoice.archive.type=filesystem",
            "einvoice.archive.root=" + ROOT,
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "einvoice.issuance.enabled=false");
  }

  /**
   * Correction pass note (see the internal open-questions log, item 33): the fix chosen for this
   * finding refuses the contradiction the same way every other refusal in {@code
   * IssuanceIntakeWiringCheck} does - by throwing from {@code afterPropertiesSet}, which fails the
   * whole Spring context. A {@code WebApplicationContextRunner} that failed to start throws {@link
   * IllegalStateException} from any plain accessor other than the assertion methods, so the
   * original form of this probe ({@code assertThat(context.getBeanNamesForType(...)).isEmpty()},
   * with no {@code hasFailed()} guard) cannot pass against that fix, or against any fix that
   * follows this class's own established pattern. The assertion below keeps exactly the same
   * guarantee under test - the host-supplied unit of work must not leave the endpoint reachable -
   * checked through {@code hasFailed()} the way every other refusal in {@link IssuanceWiringTest}
   * is checked.
   */
  @Test
  void probe_a_host_supplied_unit_of_work_reopens_the_webhook_endpoint_with_intake_off() {
    runner()
        .run(
            context ->
                assertThat(context)
                    .describedAs(
                        "einvoice.issuance.enabled=false has to mean no Stripe endpoint whatever"
                            + " beans the host supplied: the contradiction must be refused, not"
                            + " honoured in silence")
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(EInvoiceException.class)
                    .hasMessageContaining("einvoice.issuance.enabled=false")
                    .hasMessageContaining("IssuanceUnitOfWork"));
  }

  /**
   * Correction pass note (see the internal open-questions log, item 33): same accessor problem as
   * the probe above. The invariant under test - the numbering-only line never names an absence that
   * is not one - is still true, and stronger, under the throwing fix: the line is not printed at
   * all when the contradiction is present, only the refusal is. Checked here as "the success line
   * never appears next to a refusal", through {@code hasFailed()} rather than a direct accessor on
   * a context that did not start.
   */
  @Test
  void probe_the_numbering_only_line_claims_absences_it_never_checked(CapturedOutput output) {
    runner()
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(output.getAll())
                  .describedAs(
                      "the startup line is the only evidence an operator is given for \"numbering"
                          + " API only\"; it may not print that claim next to a refused"
                          + " contradiction")
                  .doesNotContain("numbering API only, as configured");
            });
  }

  /**
   * D14-09: the 0.1.0 changelog's Notes say "This module auto-configures no HTTP endpoint at all".
   * The module auto-configures {@code einvoiceWebhookController}, an unauthenticated (signature
   * verified) POST endpoint, whenever the intake is wired. A public artifact may not understate the
   * attack surface it ships.
   */
  @Test
  void probe_the_changelog_denies_the_endpoint_this_module_auto_configures() throws Exception {
    String changelog = Files.readString(repositoryFile("CHANGELOG.md"));
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(IssuanceWiringTest.Ports.class)
        .withPropertyValues(
            "einvoice.seller.id=pr14b",
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
            "einvoice.chain.hmac-key-id=k1",
            "einvoice.archive.type=filesystem",
            "einvoice.archive.root=" + ROOT,
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .run(
            context -> {
              boolean endpoint =
                  context.getBeanNamesForType(StripeWebhookController.class).length > 0;
              assertThat(endpoint && changelog.contains("auto-configures no HTTP endpoint at all"))
                  .describedAs(
                      "the changelog states the module opens no HTTP endpoint while a wired"
                          + " context maps the Stripe webhook controller")
                  .isFalse();
            });
  }

  private static Path repositoryFile(String relative) {
    for (Path candidate = Path.of("").toAbsolutePath();
        candidate != null;
        candidate = candidate.getParent()) {
      Path file = candidate.resolve(relative);
      if (Files.isRegularFile(file) && Files.exists(candidate.resolve(".git"))) {
        return file;
      }
    }
    throw new IllegalStateException("not found: " + relative);
  }

  @Configuration
  static class HostBroughtItsOwn extends IssuanceWiringTest.Ports {

    @Bean
    InboundEventStore hostInboundStore(JdbcUnitOfWork unitOfWork) {
      return new JdbcInboundEventStore(unitOfWork);
    }

    @Bean
    IssuanceUnitOfWork hostUnitOfWork(
        InboundEventStore inbound,
        StripeInvoiceSource source,
        JdbcIssuanceStore store,
        DocumentRenderer renderer,
        DocumentValidator validator,
        ArchiveStore archive,
        PreflightSupport preflightSupport,
        Clock clock,
        EInvoiceProperties properties) {
      return new IssuanceUnitOfWork(
          inbound,
          source,
          store,
          store,
          store,
          renderer,
          validator,
          archive,
          preflightSupport,
          clock,
          EInvoiceIssuanceAutoConfiguration.configuration(properties, source));
    }
  }
}
