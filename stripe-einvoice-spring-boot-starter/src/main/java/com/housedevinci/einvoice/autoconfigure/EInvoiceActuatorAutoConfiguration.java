package com.housedevinci.einvoice.autoconfigure;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * D1-09. Checklist line 44 asks for a secret to be <em>explicitly</em> excluded from {@code /env}
 * and {@code /configprops}, proven by an assertion, rather than protected by the framework's
 * default name sanitisation - which happens to catch a property whose name contains "secret" or
 * "key" today, a fact about English words rather than a control. {@code einvoice.chain.hmac-secret}
 * and every retired id under {@code einvoice.chain.hmac-keys} are named here, so a future rename of
 * either property cannot silently turn the chain's key material into a public endpoint.
 */
@AutoConfiguration(after = EInvoiceAutoConfiguration.class)
@ConditionalOnClass(SanitizingFunction.class)
public class EInvoiceActuatorAutoConfiguration {

  static final Set<String> SECRET_PROPERTIES =
      Set.of("einvoice.chain.hmac-secret", "einvoice.stripe.api-key");

  private static final Set<String> SECRET_NAMES =
      SECRET_PROPERTIES.stream()
          .map(EInvoiceActuatorAutoConfiguration::squash)
          .collect(Collectors.toUnmodifiableSet());

  private static final String HMAC_KEYS_PREFIX = squash("einvoice.chain.hmac-keys");

  /**
   * The webhook signing keyring is a map, so its property names carry an operator-chosen id and
   * cannot be listed one by one. The prefix is named here for the same reason the chain's is: the
   * framework's name sanitisation happens to catch "secret" today, which is a fact about English
   * words rather than a control.
   */
  private static final String WEBHOOK_SECRETS_PREFIX = squash("einvoice.stripe.webhook-secrets");

  private static String squash(String name) {
    return name.toLowerCase(Locale.ROOT).replace(".", "").replace("-", "").replace("_", "");
  }

  @Bean
  public SanitizingFunction einvoiceSanitizingFunction() {
    return (SanitizableData data) -> {
      String key = data.getKey();
      if (key == null) {
        return data;
      }
      // Separators stripped, so the canonical name, the relaxed name and the environment variable
      // form (EINVOICE_CHAIN_HMAC_SECRET) are all the same string here, whichever form the
      // reporting property source used.
      String normalised = squash(key);
      if (SECRET_NAMES.contains(normalised)
          || normalised.startsWith(HMAC_KEYS_PREFIX)
          || normalised.startsWith(WEBHOOK_SECRETS_PREFIX)) {
        return data.withValue("******");
      }
      return data;
    };
  }
}
