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
  void a_harmless_property_is_left_alone() {
    assertThat(sanitize("einvoice.numbering.prefix", "INV-{fiscalYear}-"))
        .isEqualTo("INV-{fiscalYear}-");
    assertThat(sanitize("einvoice.mode", "live")).isEqualTo("live");
  }

  private Object sanitize(String key, String value) {
    return function.apply(new SanitizableData(null, key, value)).getValue();
  }
}
