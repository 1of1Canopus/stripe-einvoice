package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 60, pass two. D-PR60-7 and D-PR60-8 are text that contradicts the code.
 * D-PR60-7, the code side: core CipherProbeIssuanceTest
 * probe_a_version_skewed_event_is_re_picked_by_the_sweeper_after_the_pin_moves (green). D-PR60-8,
 * the code side: StripeApiInvoiceSource.requireClasspathSdk compares Stripe API versions, and
 * stripe-java 33.4.0, 33.4.1 and 33.4.2 all speak 2026-08-26.dahlia.
 */
class CipherProbePr60PassTwoTest {

  /** FAILS: the README says refused rows stay refused when the module catches up with Stripe. */
  @Test
  void probe_the_readme_says_skew_rows_stay_refused_after_the_module_catches_up()
      throws IOException {
    String readme = read("README.md");
    int start = readme.indexOf("| Stripe moved its API version |");
    assertThat(start).isNotNegative();
    String row = readme.substring(start, readme.indexOf('\n', start));
    assertThat(row).doesNotContain("stay refused");
  }

  /** FAILS: the install text promises a refusal for any other stripe-java release. */
  @Test
  void probe_the_install_text_promises_a_refusal_for_any_other_stripe_java_release()
      throws IOException {
    assertThat(read("README.md")).doesNotContain("any other version");
    assertThat(read("CHANGELOG.md")).doesNotContain("any other version is refused");
  }

  /**
   * PASSES now. The guard the fix keeps: a stripe-java bump that keeps the API version (a patch)
   * passes every other test and would auto-merge with a README naming the old release.
   */
  @Test
  void check_the_readme_names_the_stripe_java_release_the_build_resolves() throws IOException {
    assertThat(read("README.md"))
        .contains(
            "<artifactId>stripe-java</artifactId>\n  <version>"
                + com.stripe.Stripe.VERSION
                + "</version>");
  }

  private static String read(String relative) throws IOException {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path file = dir.resolve(relative);
      if (Files.exists(file) && Files.exists(dir.resolve("mvnw"))) {
        return Files.readString(file, StandardCharsets.UTF_8);
      }
    }
    throw new IOException(relative + " not found above the module");
  }
}
