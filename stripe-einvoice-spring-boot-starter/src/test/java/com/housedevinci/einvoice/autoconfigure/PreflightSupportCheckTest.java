package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.FindingStore;
import com.housedevinci.einvoice.application.MappingInput;
import com.housedevinci.einvoice.application.PreflightReport;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P-01, at the other end: a renderer with no preflight is found at startup, before a single
 * invoice, and leaves a durable record rather than one line in a boot log.
 *
 * <p>Two paths reach that record and both are tested - this one, the method lookup at startup, and
 * the unit of work's own, for a renderer that overrides the method and answers {@code
 * NOT_SUPPORTED} anyway ({@code CipherProbePreflightTest}). A control on one event is not a
 * control.
 */
class PreflightSupportCheckTest {

  private static final AtomicInteger SELLER = new AtomicInteger();

  private ApplicationContextRunner runner(String seller, Class<?> ports) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(ports)
        .withPropertyValues(
            "einvoice.seller.id=" + seller,
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
            "einvoice.chain.hmac-key-id=k1",
            "einvoice.archive.type=filesystem",
            "einvoice.archive.root="
                + System.getProperty("java.io.tmpdir")
                + "/einvoice-preflight-check-test",
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "einvoice.issuance.enabled=false",
            "einvoice.reconcile.enabled=false");
  }

  @Test
  void a_renderer_without_preflight_is_recorded_at_startup() {
    String seller = "preflightcheck-" + SELLER.incrementAndGet();
    runner(seller, PortsWithoutPreflight.class)
        .run(
            context -> {
              FindingStore findings = context.getBean(FindingStore.class);
              assertThat(findings.open(seller, Mode.LIVE, 10))
                  .extracting(ComplianceFinding::code)
                  .describedAs("the operator sees it in the findings list, not only in a boot log")
                  .contains(ErrorCodes.PREFLIGHT_NOT_SUPPORTED);
            });
  }

  @Test
  void a_renderer_with_a_preflight_raises_nothing() {
    String seller = "preflightcheck-" + SELLER.incrementAndGet();
    runner(seller, PortsWithPreflight.class)
        .run(
            context -> {
              FindingStore findings = context.getBean(FindingStore.class);
              assertThat(findings.open(seller, Mode.LIVE, 10))
                  .extracting(ComplianceFinding::code)
                  .doesNotContain(ErrorCodes.PREFLIGHT_NOT_SUPPORTED);
            });
  }

  @Test
  void the_check_reads_the_method_and_not_the_class_name() {
    assertThat(PreflightSupportCheck.overridesPreflight(new WithPreflight())).isTrue();
    assertThat(
            PreflightSupportCheck.overridesPreflight(
                input ->
                    new DocumentRenderer.RenderedDocument(
                        "<Invoice/>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "xml",
                        "test")))
        .isFalse();
  }

  static final class WithPreflight implements DocumentRenderer {

    @Override
    public PreflightReport preflight(MappingInput input) {
      return PreflightReport.passed();
    }

    @Override
    public RenderedDocument render(DocumentInput input) {
      return new RenderedDocument(
          "<Invoice/>".getBytes(java.nio.charset.StandardCharsets.UTF_8), "xml", "test");
    }
  }

  @Configuration
  static class PortsWithoutPreflight {

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
      return new com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp
          .RecordingStripeSource();
    }
  }

  @Configuration
  static class PortsWithPreflight extends PortsWithoutPreflight {

    @Override
    @Bean
    DocumentRenderer renderer() {
      return new WithPreflight();
    }
  }
}
