package com.housedevinci.einvoice;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ScreenedText;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Pass 2 on D5-04: what the tightened rules still do not name. */
class CipherProbePr7bDeterminismTest {

  @Test
  void probe_a_refusal_message_does_not_depend_on_the_default_locale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("hi-IN-u-nu-deva"));
      String deva = message();
      Locale.setDefault(Locale.ROOT);
      String root = message();
      System.out.println("D7-04 deva=" + deva);
      System.out.println("D7-04 root=" + root);
      assertThat(deva)
          .as("the domain screen formats a code point with the default locale's digits")
          .isEqualTo(root);
    } finally {
      Locale.setDefault(original);
    }
  }

  private static String message() {
    try {
      ScreenedText.screen("buyer name", "Elbe" + ((char) 0x07) + "AG", 200);
      return "<no refusal>";
    } catch (EInvoiceException e) {
      return e.getMessage();
    }
  }

  @Test
  void probe_the_determinism_rules_name_the_locale_dependent_formatters() {
    JavaClasses fixture = new ClassFileImporter().importClasses(LocaleDependentFixture.class);
    String moduleWide = evaluate(DeterminismRules.moduleWide(), fixture);
    System.out.println("D7-04 moduleWide refused = " + moduleWide);
    assertThat(moduleWide)
        .as(
            "String.format without a Locale, toUpperCase() and Calendar.getInstance() read the"
                + " environment exactly as LocalDate.now() does")
        .isEqualTo("refused");
  }

  @Test
  void probe_the_module_wide_rule_names_what_the_preflight_rule_names() {
    JavaClasses fixture = new ClassFileImporter().importClasses(ClockOnlyFixture.class);
    System.out.println(
        "D7-04 Clock.systemDefaultZone(): moduleWide="
            + evaluate(DeterminismRules.moduleWide(), fixture)
            + " preflightPath(as written, other packages)="
            + evaluate(DeterminismRules.preflightPath(), fixture));
    assertThat(evaluate(DeterminismRules.moduleWide(), fixture))
        .as(
            "the narrow rule calls Clock.systemDefaultZone() fatal; the module-wide one does not"
                + " name it at all, so it is fatal in two packages and allowed in the rest")
        .isEqualTo("refused");
  }

  static final class ClockOnlyFixture {
    java.time.Clock clock() {
      return java.time.Clock.systemDefaultZone();
    }
  }

  private static String evaluate(com.tngtech.archunit.lang.ArchRule rule, JavaClasses classes) {
    try {
      rule.check(classes);
      return "allowed";
    } catch (AssertionError refused) {
      return "refused";
    }
  }

  /** Environment readers the two rules do not name. */
  static final class LocaleDependentFixture {
    String amount(java.math.BigDecimal value) {
      return String.format("%,.2f", value);
    }

    String upper(String value) {
      return value.toUpperCase();
    }

    long calendar() {
      return java.util.Calendar.getInstance().getTimeInMillis();
    }

    java.time.Clock clock() {
      return java.time.Clock.systemDefaultZone();
    }
  }
}
