package com.housedevinci.einvoice.autoconfigure.issuance;

import com.housedevinci.einvoice.adapter.jdbc.JdbcSupport;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.autoconfigure.TestPostgres;
import com.housedevinci.einvoice.autoconfigure.TestRenderers;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The host of the skew cure tests (D-PR60-2): a database of its own, so its sweeps read only its
 * rows, and a pin of its own that is not {@code Stripe.API_VERSION} (D-SKEW-10). In this package,
 * like {@link IssuanceTestApp}, so the JPA scan does not reach the test entities.
 */
@Configuration
@EnableAutoConfiguration
public class SkewCureTestApp {

  /** The host's pin: neither the SDK's constant nor the shared test pin. */
  public static final String PIN = "2099-01-01.hostpin";

  @Bean
  HikariDataSource dataSource() {
    HikariDataSource own = TestPostgres.emptyDatabase("skew_cure_" + System.nanoTime());
    own.setMaximumPoolSize(8);
    JdbcSupport.initializeSchema(own);
    return own;
  }

  @Bean
  DocumentRenderer renderer() {
    return TestRenderers.supplying(
        input ->
            new DocumentRenderer.RenderedDocument(TestRenderers.datedUbl(input), "xml", "test"));
  }

  @Bean
  DocumentValidator validator() {
    return (bytes, input) -> DocumentValidator.Report.passed();
  }

  @Bean
  IssuanceTestApp.RecordingStripeSource stripeSource() {
    return new IssuanceTestApp.RecordingStripeSource(PIN);
  }
}
