package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

/**
 * Separates "this application does not use the issuance path" from "this application uses it and is
 * missing a part" (D2-02).
 *
 * <p>The unit of work, the worker, the sweeper, the webhook controller and the health indicator are
 * all conditional, transitively, on a {@link DocumentRenderer} and a {@link DocumentValidator}
 * bean. Refusing to wire the pipeline without them is the right call for a host with no writers yet
 * - allocating a number with no way to produce a validated document would consume a legal series
 * and archive nothing - but a refusal nobody is told about takes more with it than the pipeline: an
 * application with webhook secrets and an archive root configured plainly expects to receive
 * events, and starting with no endpoint, no sweeper and no log line at any level means Stripe posts
 * to a path that answers 404, retries for three days, and disables the endpoint with nothing having
 * ever noticed. That is precisely the failure the durable-record-first design exists to prevent,
 * reached by a configuration mistake rather than a crash.
 *
 * <p>"Configured" is a signature-verifiable question rather than a guess: a non-empty {@code
 * einvoice.stripe.webhook-secrets}, or {@code einvoice.issuance.enabled} set explicitly (checked
 * against the {@link Environment}, never the field default, since the property itself defaults to
 * {@code true}). Neither present, and this is a numbering-only host by every visible sign - one
 * WARN at every startup, never a failure.
 */
final class IssuanceIntakeWiringCheck implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(IssuanceIntakeWiringCheck.class);

  private final ConfigurableListableBeanFactory beanFactory;
  private final EInvoiceProperties properties;
  private final Environment environment;

  IssuanceIntakeWiringCheck(
      ConfigurableListableBeanFactory beanFactory,
      EInvoiceProperties properties,
      Environment environment) {
    this.beanFactory = beanFactory;
    this.properties = properties;
    this.environment = environment;
  }

  @Override
  public void afterPropertiesSet() {
    boolean hasRenderer = beanFactory.getBeanNamesForType(DocumentRenderer.class).length > 0;
    boolean hasValidator = beanFactory.getBeanNamesForType(DocumentValidator.class).length > 0;
    if (hasRenderer && hasValidator) {
      return;
    }
    String missing = missingBeans(hasRenderer, hasValidator);
    if (intakeIsConfigured()) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.stripe.webhook-secrets is configured, or einvoice.issuance.enabled is"
              + " explicitly true, so this application is set up to receive Stripe events - but no "
              + missing
              + " bean is in the context. Allocating a legal number with no way to produce a"
              + " validated document would consume the series and archive nothing, so the issuance"
              + " pipeline refuses to start rather than start silently with no endpoint, no sweeper"
              + " and no signal. Supply the missing bean(s), or set"
              + " einvoice.issuance.enabled=false to run the numbering API only.");
    }
    log.warn(
        "einvoice: the issuance path is not wired ({} missing), and intake is not configured - no"
            + " einvoice.stripe.webhook-secrets, and einvoice.issuance.enabled is not explicitly"
            + " set. Only the numbering API is available. This is expected for a numbering-only"
            + " host; set einvoice.issuance.enabled=false to say so explicitly and keep this line"
            + " from repeating at every startup.",
        missing);
  }

  private static String missingBeans(boolean hasRenderer, boolean hasValidator) {
    StringBuilder missing = new StringBuilder();
    if (!hasRenderer) {
      missing.append("DocumentRenderer");
    }
    if (!hasValidator) {
      if (!missing.isEmpty()) {
        missing.append(" and ");
      }
      missing.append("DocumentValidator");
    }
    return missing.toString();
  }

  private boolean intakeIsConfigured() {
    // D2-05: an explicit false wins over everything else, including a webhook secret sitting in
    // a shared configuration server from an earlier rollout. Without this, the secrets arm below
    // returns true first, the pipeline fails to start, and the refusal's own remedy - "set
    // einvoice.issuance.enabled=false to run the numbering API only" - is refused too, because the
    // operator has already done exactly that.
    if (environment.containsProperty("einvoice.issuance.enabled")
        && !properties.getIssuance().isEnabled()) {
      return false;
    }
    if (!properties.getStripe().getWebhookSecrets().isEmpty()) {
      return true;
    }
    // The property itself defaults to true, so only an explicit setting - present in the
    // environment, regardless of value - counts as the operator saying something.
    return environment.containsProperty("einvoice.issuance.enabled")
        && properties.getIssuance().isEnabled();
  }
}
