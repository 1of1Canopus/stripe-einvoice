package com.housedevinci.einvoice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * The two determinism rules, defined once.
 *
 * <p>They are the control the "same input, same verdict, always" claim rests on, so they are
 * themselves under test - and a test of a rule must apply the <em>rule</em>, not a copy of its
 * method list. A copy goes on passing after the original is tightened, which is how a rule with a
 * gap survives the test written to find the gap.
 */
public final class DeterminismRules {

  private DeterminismRules() {}

  public static ArchRule moduleWide() {
    return noClasses()
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
        .orShould()
        .callMethod(java.util.TimeZone.class, "getDefault")
        .orShould()
        .callMethod(java.time.LocalDate.class, "now", java.time.ZoneId.class)
        .orShould()
        .callMethod(java.time.LocalDateTime.class, "now", java.time.ZoneId.class)
        .orShould()
        .callMethod(java.time.ZonedDateTime.class, "now")
        .orShould()
        .callMethod(java.time.ZonedDateTime.class, "now", java.time.ZoneId.class)
        .orShould()
        .callMethod(java.time.OffsetDateTime.class, "now")
        .orShould()
        .callMethod(java.time.OffsetDateTime.class, "now", java.time.ZoneId.class)
        .orShould()
        .callMethod(java.time.LocalTime.class, "now")
        .orShould()
        .callMethod(java.time.Year.class, "now")
        .orShould()
        .callMethod(java.time.Clock.class, "systemUTC")
        .orShould()
        .callMethod(java.lang.System.class, "currentTimeMillis")
        .orShould()
        .callMethod(java.lang.System.class, "nanoTime")
        .orShould()
        .callMethod(java.util.Locale.class, "getDefault", java.util.Locale.Category.class)
        .orShould()
        .callConstructor(java.util.Date.class)
        .because(
            "a legal date and a rendered number must not depend on where the container runs."
                + " The overloads matter as much as the no-argument forms: ArchUnit matches a"
                + " signature, so LocalDate.now(zone) and Locale.getDefault(category) pass a"
                + " rule that names only LocalDate.now() and Locale.getDefault()");
  }

  public static ArchRule preflightPath() {
    return noClasses()
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
        .orShould()
        .callMethod(java.time.LocalDate.class, "now", java.time.ZoneId.class)
        .orShould()
        .callMethod(java.time.LocalDateTime.class, "now", java.time.ZoneId.class)
        .orShould()
        .callMethod(java.time.ZonedDateTime.class, "now")
        .orShould()
        .callMethod(java.time.ZonedDateTime.class, "now", java.time.ZoneId.class)
        .orShould()
        .callMethod(java.time.OffsetDateTime.class, "now")
        .orShould()
        .callMethod(java.time.OffsetDateTime.class, "now", java.time.ZoneId.class)
        .orShould()
        .callMethod(java.time.LocalTime.class, "now")
        .orShould()
        .callMethod(java.time.Year.class, "now")
        .orShould()
        .callMethod(java.time.Clock.class, "systemUTC")
        .orShould()
        .callMethod(java.lang.System.class, "currentTimeMillis")
        .orShould()
        .callMethod(java.lang.System.class, "nanoTime")
        .orShould()
        .callMethod(java.util.Locale.class, "getDefault", java.util.Locale.Category.class)
        .orShould()
        .callConstructor(java.util.Date.class)
        .because(
            "the preflight and the render must reach the same verdict on any machine, in any"
                + " zone and under any locale");
  }
}
