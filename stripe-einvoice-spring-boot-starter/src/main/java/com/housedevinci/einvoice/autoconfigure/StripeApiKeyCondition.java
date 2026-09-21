package com.housedevinci.einvoice.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * "Is there an API key", where <b>blank is no key</b>.
 *
 * <p>{@code @ConditionalOnProperty} answers "is the property present", and an application that
 * writes {@code api-key: ${EINVOICE_STRIPE_KEY:}} - the only way a YAML file can offer an
 * environment variable with a fallback, and what this project's own sample does - always makes it
 * present. The bean factory then ran and threw "einvoice.stripe.api-key is required" out of a
 * context that had never asked to talk to Stripe at all, so a host running the numbering API only
 * could not start. Found by running the documented quick start from a clean clone.
 *
 * <p>Treating blank as absent is not a silent pass: an application that IS configured to receive
 * Stripe events and has no source bean is refused by name at startup by {@link
 * IssuanceIntakeWiringCheck}. This condition decides whether the bean is attempted; that check
 * decides whether its absence is acceptable.
 */
final class StripeApiKeyCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return StringUtils.hasText(context.getEnvironment().getProperty("einvoice.stripe.api-key"));
  }
}
