package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;

/**
 * D1-09: the chain secret is excluded from {@code /env} and {@code /configprops} by name, not by
 * luck of the framework's default sanitiser matching an English word in the property name.
 */
class CipherProbeActuatorTest {

  private final SanitizingFunction function =
      new EInvoiceActuatorAutoConfiguration().einvoiceSanitizingFunction();

  @Test
  void probe_the_chain_secret_is_excluded_by_name() {
    assertThat(sanitize("einvoice.chain.hmac-secret", "AAAA")).isEqualTo("******");
    assertThat(sanitize("EINVOICE_CHAIN_HMAC_SECRET", "AAAA")).isEqualTo("******");
    assertThat(sanitize("einvoice.chain.hmac-keys.k1", "AAAA")).isEqualTo("******");
  }

  @Test
  void probe_the_stripe_secrets_are_excluded_by_name() {
    // The webhook keyring is the only thing between the internet and the document generator, and
    // the API key reads every invoice on the account. Both are named here rather than left to the
    // framework's habit of sanitising properties whose names contain an English word.
    assertThat(sanitize("einvoice.stripe.api-key", "rk_live_x")).isEqualTo("******");
    assertThat(sanitize("EINVOICE_STRIPE_API_KEY", "rk_live_x")).isEqualTo("******");
    assertThat(sanitize("einvoice.stripe.webhook-secrets.primary", "whsec_x")).isEqualTo("******");
    assertThat(sanitize("EINVOICE_STRIPE_WEBHOOK_SECRETS_PRIMARY", "whsec_x")).isEqualTo("******");
    assertThat(sanitize("einvoice.stripe.webhook-secrets.retiring", "whsec_y")).isEqualTo("******");
  }

  @Test
  void a_harmless_property_is_left_alone() {
    assertThat(sanitize("einvoice.numbering.prefix", "INV-{fiscalYear}-"))
        .isEqualTo("INV-{fiscalYear}-");
    assertThat(sanitize("einvoice.mode", "live")).isEqualTo("live");
    assertThat(sanitize("einvoice.stripe.api-version", "2026-08-26.dahlia"))
        .isEqualTo("2026-08-26.dahlia");
  }

  private Object sanitize(String key, String value) {
    return function.apply(new SanitizableData(null, key, value)).getValue();
  }
}
