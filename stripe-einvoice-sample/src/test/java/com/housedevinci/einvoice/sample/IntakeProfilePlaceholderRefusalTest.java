package com.housedevinci.einvoice.sample;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D14-03, end to end: a reader who pastes the documented command exactly as it is printed gets a
 * refusal, not a running application.
 *
 * <p>The README and {@code application-intake.yml} print {@code whsec_from_your_stripe_dashboard}
 * and {@code rk_test_your_restricted_read_scoped_key} as the values to replace. Until this fix they
 * were well formed by every check the module applied, so the documented command started an
 * application in the sample's own mode whose unauthenticated webhook endpoint was "authenticated"
 * by a string published on GitHub. The values are typed out here on purpose: this test is the
 * reader's copy-paste, and {@code DocumentedPlaceholderSecretsTest} in the starter is the one that
 * reads them back out of the shipped files so a new placeholder cannot escape the same refusal.
 */
@Testcontainers
class IntakeProfilePlaceholderRefusalTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse(
                      "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777")
                  .asCompatibleSubstituteFor("postgres"))
          .withStartupTimeout(java.time.Duration.ofMinutes(2));

  @Test
  void the_documented_command_pasted_verbatim_refuses_to_start_and_says_why() {
    assertThatThrownBy(
            () ->
                new SpringApplicationBuilder(SampleApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .profiles("intake")
                    .properties(
                        "server.port=0",
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "EINVOICE_CHAIN_SECRET="
                            + Base64.getEncoder()
                                .encodeToString(
                                    "einvoice-sample-chain-secret-01!"
                                        .getBytes(StandardCharsets.UTF_8)),
                        "EINVOICE_ARCHIVE_ROOT=target/einvoice-archive-placeholder-refusal",
                        "EINVOICE_WEBHOOK_SECRET=whsec_from_your_stripe_dashboard",
                        "EINVOICE_STRIPE_KEY=rk_test_your_restricted_read_scoped_key")
                    .run()
                    .close())
        .rootCause()
        .hasMessageContaining("placeholder");
  }
}
