package com.housedevinci.einvoice.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.autoconfigure.IssuanceNumberingService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The sample with the configuration it actually ships, and nothing else: no Stripe key, no webhook
 * secret, no profile. It must start.
 *
 * <p>This test exists because release run 35899901341 was the first time anything ever started the
 * shipped application, and it could not: {@code application.yml} configured a Stripe webhook
 * secret, so the starter's intake wiring check refused a context that had no {@link
 * StripeInvoiceSource} in it. {@link SampleEndToEndTest} never saw it - it supplies its own fake
 * Stripe and its own secrets, which is the right shape for testing issuance and the wrong shape for
 * answering "does the thing we ship start".
 *
 * <p>The only properties set here are the ones a reader supplies from the environment by the
 * README's own instruction (the datasource, through Testcontainers, and the issuance-chain secret).
 * Anything else set here would make this test stop being about the shipped file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class SampleShippedConfigurationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse(
                      "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777")
                  .asCompatibleSubstituteFor("postgres"))
          .withStartupTimeout(java.time.Duration.ofMinutes(2));

  @DynamicPropertySource
  static void theOneSecretTheReadmeAsksFor(DynamicPropertyRegistry registry) {
    registry.add(
        "einvoice.chain.hmac-secret",
        () ->
            Base64.getEncoder()
                .encodeToString(
                    "einvoice-sample-chain-secret-01!".getBytes(StandardCharsets.UTF_8)));
  }

  @Autowired ApplicationContext context;

  @Test
  void the_sample_starts_with_the_configuration_it_ships_and_no_stripe_credentials() {
    // Numbering is what the shipped sample is for, and it is wired.
    assertThat(context.getBeanNamesForType(IssuanceNumberingService.class)).hasSize(1);
    assertThat(context.getBeanNamesForType(InvoiceNumberController.class)).hasSize(1);
  }

  @Test
  void the_shipped_sample_has_no_stripe_intake() {
    // No reader, because there is no API key; and no webhook secret, so the intake wiring check
    // treats this as the documented numbering-only state instead of a host missing a part. If a
    // future change adds either one back to application.yml, this fails here rather than on a tag.
    assertThat(context.getBeanNamesForType(StripeInvoiceSource.class)).isEmpty();
    assertThat(context.getEnvironment().getProperty("einvoice.stripe.webhook-secrets.primary"))
        .isNull();
    assertThat(context.getEnvironment().getProperty("einvoice.issuance.enabled"))
        .isEqualTo("false");
  }
}
