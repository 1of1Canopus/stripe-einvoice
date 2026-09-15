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
}
