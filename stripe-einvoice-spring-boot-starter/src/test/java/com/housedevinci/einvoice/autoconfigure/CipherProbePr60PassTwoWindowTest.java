package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Security review, PR 60, pass two, D-PR60-10. Each reconciliation pass lists the invoices
 * finalised in [now - grace - window, now - grace]. A window shorter than the interval leaves gaps
 * between passes; zero or negative covers nothing. Either is reconciliation switched off without
 * the WARN that einvoice.reconcile.enabled=false now gives.
 */
class CipherProbePr60PassTwoWindowTest {

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
                + "/einvoice-pr60-window-probe",
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "einvoice.issuance.sweep-interval=24h")
        .withPropertyValues(properties);
  }

  /** FAILS: D-PR60-10. A window that cannot cover the time between two passes starts silently. */
  @Test
  void probe_a_reconciliation_window_shorter_than_its_interval_starts() {
    for (String window : List.of("0s", "-1d", "1m")) {
      runner("einvoice.reconcile.window=" + window, "einvoice.reconcile.interval=15m")
          .run(context -> assertThat(context).as("window " + window).hasFailed());
    }
  }
}
