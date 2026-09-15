package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The format checks on the two Stripe secrets, run at startup rather than at the first event
 * (checklist line 44).
 *
 * <p>A blank secret fails closed, which is fine. A <em>wrong</em> secret also fails closed, and
 * indistinguishably from an attack - so the shape is checked here, by name, where the message can
 * say what to fix without ever printing the value.
 */
final class StripeSecrets {

  /** The upper bound on the signature tolerance. A wide window plus a ledger gap is a replay. */
  static final Duration MAX_TOLERANCE = Duration.ofMinutes(10);

  private static final Set<String> PLACEHOLDERS =
      Set.of(
          "whsec_test",
          "whsec_changeme",
          "whsec_xxx",
          "sk_test_xxx",
          "rk_test_xxx",
          "changeme",
          "replace-me");

  private StripeSecrets() {}

  static void requireWebhookSecrets(Map<String, String> secrets) {
    if (secrets.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.stripe.webhook-secrets.<id> is required and has no default: it is the only"
              + " thing between the internet and this module's document generator. Supply at least"
              + " one from the environment, for example"
              + " EINVOICE_STRIPE_WEBHOOK_SECRETS_PRIMARY=whsec_...");
    }
    secrets.forEach(
        (id, secret) -> {
          if (secret == null || !secret.startsWith("whsec_") || secret.length() < 24) {
            throw new EInvoiceException(
                ErrorCodes.CONFIG,
                "einvoice.stripe.webhook-secrets."
                    + id
                    + " is not a Stripe signing secret: they begin with 'whsec_' and are longer"
                    + " than this one. The value is not printed here on purpose.");
          }
          if (PLACEHOLDERS.contains(secret.toLowerCase(Locale.ROOT))) {
            throw new EInvoiceException(
                ErrorCodes.CONFIG,
                "einvoice.stripe.webhook-secrets."
                    + id
                    + " is a placeholder from a sample file, not a secret.");
          }
        });
  }

  static void requireBoundedTolerance(Duration tolerance) {
    if (tolerance.isNegative() || tolerance.isZero() || tolerance.compareTo(MAX_TOLERANCE) > 0) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.webhook.tolerance must be positive and at most "
              + MAX_TOLERANCE
              + ": a wide replay window plus any gap in the event ledger is a replay window, and"
              + " Stripe's own default is five minutes.");
    }
  }

  static void requireApiKey(String apiKey, boolean requireRestricted) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.stripe.api-key is required and has no default. Use a restricted key with read"
              + " scopes on invoices, customers, credit notes and tax rates; this module never"
              + " writes to Stripe.");
    }
    if (PLACEHOLDERS.contains(apiKey.toLowerCase(Locale.ROOT))) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG, "einvoice.stripe.api-key is a placeholder from a sample file.");
    }
    if (requireRestricted && !apiKey.startsWith("rk_")) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.stripe.api-key is not a restricted key (rk_...). This module reads invoices,"
              + " customers, credit notes and tax rates and writes nothing, so a secret key here is"
              + " authority nobody asked for. Set einvoice.stripe.require-restricted-key=false to"
              + " accept it anyway, and it will WARN at every startup.");
    }
  }
}
