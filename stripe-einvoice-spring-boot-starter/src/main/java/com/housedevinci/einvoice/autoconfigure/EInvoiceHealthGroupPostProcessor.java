package com.housedevinci.einvoice.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Puts this module's health contributor in a group of its own, by default (I-04).
 *
 * <p>The contributor reports operational conditions - a stale sweep, an unverifiable chain - and
 * those must be visible to an operator without ever being able to reach the {@code readiness} or
 * {@code liveness} groups, where they would take the pod out of the load balancer or restart it.
 * Boot has no way for a library to say "group me separately" other than a property, so the property
 * is contributed here as a <b>default</b>: it sits below every other property source, so a host
 * that wants a different arrangement simply sets its own and this one loses.
 *
 * <p>The group is deliberately not added to readiness or liveness anywhere in this module, and a
 * test asserts the contributor is absent from both.
 */
public class EInvoiceHealthGroupPostProcessor implements EnvironmentPostProcessor {

  static final String GROUP_PROPERTY = "management.endpoint.health.group.einvoice.include";

  /**
   * The contributor's own name, which must differ from the group's: Boot refuses to register a
   * contributor whose name collides with a health group, and the collision is not obvious from
   * either side until it fails at startup.
   */
  static final String CONTRIBUTOR = "einvoiceIssuance";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    Map<String, Object> defaults = new LinkedHashMap<>();
    defaults.put(GROUP_PROPERTY, CONTRIBUTOR);
    environment
        .getPropertySources()
        .addLast(new MapPropertySource("einvoice-health-group-defaults", defaults));
  }
}
