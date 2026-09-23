package com.housedevinci.einvoice.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.StripeInvoiceSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D14-01. PR 14 ships a second configuration file, {@code application-intake.yml}, and the README
 * tells a reader to run the sample with it. Nothing loads it: {@code SampleEndToEndTest} sets the
 * same three properties by hand and never activates the profile, and {@code
 * SampleShippedConfigurationTest} covers the profile being off. That is the same shape as the
 * defect this branch exists to fix - a shipped configuration file no test ever starts - moved one
 * file across.
 *
 * <p>This probe activates the profile and supplies only what the README says comes from the
 * environment, with syntactically valid fake values. The context must start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("intake")
@Testcontainers
class CipherProbePr14IntakeProfileTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse(
                      "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777")
                  .asCompatibleSubstituteFor("postgres"))
          .withStartupTimeout(java.time.Duration.ofMinutes(2));

  @DynamicPropertySource
  static void theThreeEnvironmentValuesTheReadmeNames(DynamicPropertyRegistry registry) {
    registry.add(
        "EINVOICE_CHAIN_SECRET",
        () ->
            Base64.getEncoder()
                .encodeToString(
                    "einvoice-sample-chain-secret-01!".getBytes(StandardCharsets.UTF_8)));
    registry.add("EINVOICE_WEBHOOK_SECRET", () -> "whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    // Assembled rather than written out, like IssuanceOperationsTest does: a well-formed
    // rk_test_ literal in a pushed file is refused by GitHub push protection, fake or not.
    registry.add("EINVOICE_STRIPE_KEY", () -> "rk_test_" + "a".repeat(24));
    registry.add("EINVOICE_ARCHIVE_ROOT", () -> "target/einvoice-archive-cipher-pr14");
  }

  @Autowired ApplicationContext context;

  @Test
  void probe_the_intake_profile_the_readme_documents_is_never_started_by_any_test() {
    assertThat(context.getBeanNamesForType(StripeInvoiceSource.class))
        .describedAs(
            "the intake profile must produce the Stripe reader it exists to configure; if this"
                + " context did not start, the file the README tells a reader to use is broken")
        .hasSize(1);
    assertThat(context.getEnvironment().getProperty("einvoice.issuance.enabled")).isEqualTo("true");
  }
}
