package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
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
 *
 * <p>Both beans present is not the end of the check (D3-02): a validator that reports it can never
 * validate anything - {@link DocumentValidator#canValidate()} - is the same class of problem as a
 * missing bean, refused the same way.
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
    // The authoritative reader is the third bean the pipeline cannot run without, and it was not
    // asked about here until a fresh clone of the sample proved why it has to be: with a webhook
    // secret configured and no einvoice.stripe.api-key, the renderer and the validator both exist,
    // this check passed, and the application started with an endpoint that recorded events nothing
    // would ever fetch, number or archive. That is the same defect D2-02 closed for the other two
    // beans, closed here the same way and with the same remedy.
    boolean hasSource = beanFactory.getBeanNamesForType(StripeInvoiceSource.class).length > 0;
    if (hasRenderer && hasValidator && hasSource) {
      // D3-02: wired is not the same question as able to validate anything, ever. A validator
      // whose canValidate() says no (an XSLT 2.0 processor missing from the classpath, in this
      // module's own implementation) is the same class of problem as a missing bean: known before
      // this application serves a single request, and every invoice pays for it with a legal
      // number otherwise. Same treatment as the missing-bean case below.
      DocumentValidator validator = beanFactory.getBean(DocumentValidator.class);
      if (!validator.canValidate() && intakeIsConfigured()) {
        throw new EInvoiceException(
            ErrorCodes.CONFIG,
            "einvoice.stripe.webhook-secrets is configured, or einvoice.issuance.enabled is"
                + " explicitly true, so this application is set up to receive Stripe events - but"
                + " the configured DocumentValidator reports that it can never validate anything"
                + " (no XSLT 2.0 processor on the classpath, in this module's own implementation)."
                + " Allocating a legal number for a document nothing will ever validate would"
                + " consume the series and archive nothing, so the issuance pipeline refuses to"
                + " start rather than fail once per invoice after the number is already spent. Add"
                + " a processor to the application, or set einvoice.issuance.enabled=false to run"
                + " the numbering API only.");
      }
      return;
    }
    String missing = missingBeans(hasRenderer, hasValidator, hasSource);
    if (intakeIsConfigured()) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.stripe.webhook-secrets is configured, or einvoice.issuance.enabled is"
              + " explicitly true, so this application is set up to receive Stripe events - but no "
              + missing
              + " bean is in the context. The starter builds a DocumentRenderer and a"
              + " DocumentValidator from einvoice.seller.* and einvoice.documents.*, so the usual"
              + " cause there is that einvoice.seller.name is not set; it builds a"
              + " StripeInvoiceSource when com.stripe:stripe-java is on the classpath (it is an"
              + " optional dependency of this starter, so the application declares it) AND"
              + " einvoice.stripe.api-key is set. Allocating a legal number with no way to produce a"
              + " validated document would consume the series and archive nothing, so the issuance"
              + " pipeline refuses to start rather than start silently with no endpoint, no sweeper"
              + " and no signal. Supply the missing bean(s), or set"
              + " einvoice.issuance.enabled=false to run the numbering API only.");
    }
    if (environment.containsProperty("einvoice.issuance.enabled")
        && !properties.getIssuance().isEnabled()) {
      // The operator already said it: einvoice.issuance.enabled=false. Telling them to do what
      // they have done is how a startup log becomes noise nobody reads.
      log.info(
          "einvoice: numbering API only, as configured (einvoice.issuance.enabled=false). No Stripe"
              + " intake, sweeper or archive is wired ({} absent).",
          missing);
      return;
    }
    log.warn(
        "einvoice: the issuance path is not wired ({} missing), and intake is not configured - no"
            + " einvoice.stripe.webhook-secrets, and einvoice.issuance.enabled is not explicitly"
            + " set. Only the numbering API is available. This is expected for a numbering-only"
            + " host; set einvoice.issuance.enabled=false to say so explicitly and keep this line"
            + " from repeating at every startup.",
        missing);
  }

  private static String missingBeans(boolean hasRenderer, boolean hasValidator, boolean hasSource) {
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
    if (!hasSource) {
      if (!missing.isEmpty()) {
        missing.append(" and ");
      }
      missing.append("StripeInvoiceSource");
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
