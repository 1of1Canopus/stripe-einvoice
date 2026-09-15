package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.adapter.stripe.StripeApiInvoiceSource;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.stripe.Stripe;
import com.stripe.StripeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the Stripe client, in its own class so the auto-configuration never loads an SDK type
 * unless the SDK is there.
 *
 * <p>Telemetry is off unless the operator turns it on, and turning it on WARNs at every startup
 * (D-11): the SDK's usage telemetry reports request paths and timings to a third party from inside
 * an application that processes invoices.
 */
final class StripeClientFactory {

  private static final Logger log = LoggerFactory.getLogger(StripeClientFactory.class);

  private StripeClientFactory() {}

  static StripeInvoiceSource create(EInvoiceProperties properties) {
    EInvoiceProperties.Stripe stripe = properties.getStripe();
    StripeSecrets.requireApiKey(stripe.getApiKey(), stripe.isRequireRestrictedKey());
    if (!stripe.isRequireRestrictedKey()) {
      log.warn(
          "einvoice: einvoice.stripe.require-restricted-key=false. This module reads invoices and"
              + " writes nothing, so a full secret key here is authority that only widens the blast"
              + " radius of a leak.");
    }
    Stripe.enableTelemetry = stripe.isTelemetry();
    if (stripe.isTelemetry()) {
      log.warn(
          "einvoice: einvoice.stripe.telemetry=true. The SDK will report request paths and timings"
              + " to a third party from inside an application that processes invoices.");
    }
    String pinned =
        stripe.getApiVersion().isBlank()
            ? StripeApiInvoiceSource.SDK_API_VERSION
            : stripe.getApiVersion();
    log.info("einvoice: reading the Stripe API at pinned version {}", pinned);
    return new StripeApiInvoiceSource(
        StripeClient.builder().setApiKey(stripe.getApiKey()).build(),
        pinned,
        stripe.getListLookback());
  }
}
