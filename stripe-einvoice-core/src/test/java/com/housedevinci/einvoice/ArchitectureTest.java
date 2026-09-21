package com.housedevinci.einvoice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The boundary, enforced rather than described.
 *
 * <p>{@code domain} is JDK-only: no Spring, no JPA, no JDBC, no Jackson, no Stripe SDK, no XML API
 * and no crypto library. The EN 16931 model that arrives in a later pull request will be under
 * heavy pressure to take a JAXB annotation "just for the writer"; this is what says no.
 */
class ArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.housedevinci.einvoice");

  @Test
  void the_domain_depends_on_nothing_but_the_jdk() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.housedevinci.einvoice.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "javax.sql..",
                "java.sql..",
                "org.hibernate..",
                "com.fasterxml..",
                "com.stripe..",
                "org.slf4j..",
                "javax.xml..",
                "org.w3c..",
                "org.bouncycastle..")
            .because(
                "the EN 16931 model, the numbering value objects and the chain are the part of this"
                    + " module that outlives every framework it is wired into");
    rule.check(CLASSES);
  }

  @Test
  void the_application_layer_holds_ports_and_no_infrastructure() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.housedevinci.einvoice.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "org.hibernate..",
                "com.stripe..",
                "javax.sql..",
                "java.sql..")
            .because("ports are interfaces; the adapters that implement them live elsewhere");
    rule.check(CLASSES);
  }

  @Test
  void nothing_reads_the_time_the_zone_or_the_locale_from_the_environment() {
    // D-15, checklist line 16. An invoice date decided by the container's default zone puts a
    // 23:30 UTC invoice in the wrong VAT period, and a number formatted in the default locale is a
    // different string on a different machine. Time comes from an injected Clock, the zone from the
    // seller profile, and formatting from Locale.ROOT.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.housedevinci.einvoice..")
            .should()
            .callMethod(java.time.Instant.class, "now")
            .orShould()
            .callMethod(java.time.LocalDate.class, "now")
            .orShould()
            .callMethod(java.time.LocalDateTime.class, "now")
            .orShould()
            .callMethod(java.time.ZoneId.class, "systemDefault")
            .orShould()
            .callMethod(java.util.Locale.class, "getDefault")
            .because(
                "a legal date and a rendered number must not depend on where the container runs");
    rule.check(CLASSES);
  }

  @Test
  void the_preflight_path_reads_no_clock_no_zone_and_no_locale() {
    // The preflight decides whether a legal number is spent, so it has to decide the same thing
    // on every machine and on every replay. Named explicitly rather than left to the module-wide
    // rule above, because this is the path whose determinism the "costs no number" claim rests
    // on: the issue date arrives already derived, and nothing here may derive a second one.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAnyPackage(
                "com.housedevinci.einvoice.adapter.en16931..",
                "com.housedevinci.einvoice.domain.en16931..")
            .should()
            .callMethod(java.time.Instant.class, "now")
            .orShould()
            .callMethod(java.time.LocalDate.class, "now")
            .orShould()
            .callMethod(java.time.Clock.class, "systemDefaultZone")
            .orShould()
            .callMethod(java.time.ZoneId.class, "systemDefault")
            .orShould()
            .callMethod(java.util.TimeZone.class, "getDefault")
            .orShould()
            .callMethod(java.util.Locale.class, "getDefault")
            .because(
                "the preflight and the render must reach the same verdict on any machine, in any"
                    + " zone and under any locale");
    rule.check(CLASSES);
  }

  @Test
  void document_generation_never_formats_through_the_default_locale() {
    // Checklist line 17. String.format's one-argument overload takes Locale.getDefault(), so a
    // single one of them in a writer makes the bytes depend on the machine - and the Thai-digit
    // locale in GoldenDocumentTest is what would notice, long after the value was archived.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAnyPackage(
                "com.housedevinci.einvoice.adapter.xml..",
                "com.housedevinci.einvoice.adapter.en16931..",
                "com.housedevinci.einvoice.domain.en16931..")
            .should()
            .callMethod(String.class, "format", String.class, Object[].class)
            .orShould()
            .callMethod(java.util.TimeZone.class, "getDefault")
            .orShould()
            .callMethod(java.text.NumberFormat.class, "getInstance")
            .orShould()
            // The single-argument overload takes the default FORMAT locale. On an all-numeric
            // pattern that happens to be harmless today, which is exactly why a byte comparison
            // does not catch it and this rule has to: the day somebody adds MMM to a pattern, the
            // month name becomes the machine's language and no golden file notices until then.
            .callMethod(java.time.format.DateTimeFormatter.class, "ofPattern", String.class)
            .because("the bytes of a legal document are the same bytes wherever they are produced");
    rule.check(CLASSES);
  }

  @Test
  void the_en16931_model_takes_no_marshaller_annotation() {
    // D-25. The pull to annotate the model "just for the writer" is real and this is what says no:
    // the canonical writer exists precisely so that prefixes, attribute order and empty-element
    // form are ours rather than whichever Jakarta XML Bind implementation is on the classpath.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.housedevinci.einvoice.domain.en16931..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "jakarta.xml..", "javax.xml..", "com.fasterxml..", "org.w3c..", "org.xml..")
            .because("the EN 16931 model is a domain model, not a serialisation form");
    rule.check(CLASSES);
  }
}
