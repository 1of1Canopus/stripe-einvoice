package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * D14-03. Every Stripe secret this repository prints in a file a reader is told to copy from must
 * be refused as a placeholder.
 *
 * <p>The module already refuses {@code whsec_changeme} for exactly this reason. The two values the
 * public README and the sample's {@code application-intake.yml} name - {@code
 * whsec_from_your_stripe_dashboard} and {@code rk_test_your_restricted_read_scoped_key} - were in
 * neither list and are well formed by every other check here, so pasting the documented command
 * verbatim started an application whose unauthenticated webhook endpoint was authenticated by a
 * string anyone can read on GitHub.
 *
 * <p>This test reads the literals back out of the shipped files rather than repeating them, so a
 * future documented placeholder cannot be added without being refused.
 */
class DocumentedPlaceholderSecretsTest {

  private static final Pattern SECRET_LITERAL =
      Pattern.compile("\\b((?:whsec|rk_test|rk_live|sk_test|sk_live)_[A-Za-z0-9_]+)");

  @Test
  void every_stripe_secret_printed_in_the_shipped_files_is_refused_as_a_placeholder()
      throws IOException {
    Set<String> literals = new LinkedHashSet<>();
    for (Path file : List.of(repositoryFile("README.md"), repositoryFile(INTAKE_PROFILE))) {
      Matcher matcher = SECRET_LITERAL.matcher(Files.readString(file, StandardCharsets.UTF_8));
      while (matcher.find()) {
        literals.add(matcher.group(1));
      }
    }

    assertThat(literals)
        .describedAs(
            "the README and the intake profile are the two files this repository tells a reader to"
                + " copy Stripe secrets out of; if neither prints one any more, this test has"
                + " stopped measuring anything")
        .isNotEmpty();

    for (String literal : literals) {
      if (literal.startsWith("whsec_")) {
        assertThatThrownBy(() -> StripeSecrets.requireWebhookSecrets(Map.of("primary", literal)))
            .describedAs("a webhook signing secret printed in a shipped file: " + literal)
            .isInstanceOf(EInvoiceException.class)
            .hasMessageContaining("placeholder");
      } else {
        assertThatThrownBy(() -> StripeSecrets.requireApiKey(literal, true))
            .describedAs("a Stripe API key printed in a shipped file: " + literal)
            .isInstanceOf(EInvoiceException.class)
            .hasMessageContaining("placeholder");
      }
    }
  }

  private static final String INTAKE_PROFILE =
      "stripe-einvoice-sample/src/main/resources/application-intake.yml";

  /** The tests run with the module directory as the working directory; the files are one up. */
  private static Path repositoryFile(String relative) {
    Path here = Path.of("").toAbsolutePath();
    for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
      Path file = candidate.resolve(relative);
      if (Files.exists(file)) {
        return file;
      }
    }
    throw new IllegalStateException(
        relative + " was not found above " + here + ": this test cannot verify what is shipped");
  }
}
