package com.housedevinci.einvoice.adapter.en16931;

/**
 * A class in the mapper's own package that reads the environment through the overloads the branch's
 * determinism rule does not name. Nothing calls it; it exists to be imported by the security
 * review's probe and refused.
 */
public final class CipherNonDeterministicFixture {

  private CipherNonDeterministicFixture() {}

  public static String environmentReaders() {
    java.time.LocalDate a = java.time.LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati"));
    java.time.ZonedDateTime b = java.time.ZonedDateTime.now();
    java.time.OffsetDateTime c = java.time.OffsetDateTime.now();
    long d = System.currentTimeMillis();
    java.util.Locale e = java.util.Locale.getDefault(java.util.Locale.Category.FORMAT);
    return a + "|" + b + "|" + c + "|" + d + "|" + e;
  }
}
