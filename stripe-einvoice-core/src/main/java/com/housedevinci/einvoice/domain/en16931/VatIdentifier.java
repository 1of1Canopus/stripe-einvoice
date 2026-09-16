package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.util.Locale;
import java.util.Map;

/**
 * BT-31 and BT-48, a party's VAT identifier, validated for <b>both</b> parties (D-06).
 *
 * <p>What is checked is the shape, per issuing country, and the check digit where the scheme has
 * one this module can compute: France's two-digit key over the SIREN, and the SIREN's own Luhn.
 * What is <b>not</b> checked is whether the number is registered, which needs VIES and a network
 * call this module deliberately does not make. An identifier whose format this module cannot check
 * is reported as not verified rather than passed - D-06 asks for exactly that distinction, and a
 * silent "looks fine" on a number nobody validated is the failure it names.
 *
 * <p>Greece is the one country whose VAT prefix ({@code EL}) is not its ISO country code ({@code
 * GR}). Both spellings resolve to the same rules.
 */
public record VatIdentifier(String value, boolean checkDigitVerified) {

  /** The per-country length range of the part after the two-letter prefix. */
  private static final Map<String, int[]> NATIONAL_LENGTHS = nationalLengths();

  public VatIdentifier {
    value = normalise(value);
  }

  /**
   * Parses and checks a VAT identifier.
   *
   * @param field the field's name, for the message - never its value
   * @throws EInvoiceException {@link ErrorCodes#IDENTIFIER_MALFORMED} when the prefix is not a
   *     known country, the national part is the wrong length for that country, or a check digit
   *     this module can compute does not agree
   */
  public static VatIdentifier parse(String field, String value) {
    String normalised = normalise(value);
    if (normalised.length() < 4) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          field + " is too short to be a VAT identifier with a country prefix");
    }
    String prefix = normalised.substring(0, 2);
    String national = normalised.substring(2);
    String country = "EL".equals(prefix) ? "GR" : prefix;
    if (!CountryCode.EU_VAT_AREA.contains(country)
        && !"GB".equals(country)
        && !"CH".equals(country)
        && !"NO".equals(country)) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          field
              + " does not start with a country prefix this module recognises. A VAT identifier"
              + " without a resolvable issuing country is an identifier the recipient cannot check");
    }
    int[] range = NATIONAL_LENGTHS.get(country);
    if (range != null && (national.length() < range[0] || national.length() > range[1])) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          field
              + " has "
              + national.length()
              + " characters after its "
              + prefix
              + " prefix; that country issues between "
              + range[0]
              + " and "
              + range[1]);
    }
    boolean verified = false;
    if ("FR".equals(country)) {
      requireFrenchKey(field, national);
      verified = true;
    }
    return new VatIdentifier(normalised, verified);
  }

  /**
   * The French VAT identifier is a two-character key followed by the nine-digit SIREN. The key is
   * {@code (12 + 3 * (SIREN mod 97)) mod 97} when it is numeric; an alphabetic key is issued to
   * some taxpayers and is not computable here, so only the SIREN's Luhn is checked in that case.
   */
  private static void requireFrenchKey(String field, String national) {
    String key = national.substring(0, 2);
    String siren = national.substring(2);
    for (int i = 0; i < siren.length(); i++) {
      if (siren.charAt(i) < '0' || siren.charAt(i) > '9') {
        throw new EInvoiceException(
            ErrorCodes.IDENTIFIER_MALFORMED, field + " carries a SIREN that is not nine digits");
      }
    }
    requireLuhn(field, siren);
    boolean numericKey = Character.isDigit(key.charAt(0)) && Character.isDigit(key.charAt(1));
    if (numericKey) {
      long expected = (12 + 3 * (Long.parseLong(siren) % 97)) % 97;
      if (expected != Long.parseLong(key)) {
        throw new EInvoiceException(
            ErrorCodes.IDENTIFIER_MALFORMED,
            field
                + " carries a French VAT key that does not agree with its SIREN. A mistyped"
                + " identifier on a legal document is a rejection at the recipient, not a typo");
      }
    }
  }

  /** The SIREN's own Luhn check digit. Also used by SIRET, which is the SIREN plus five digits. */
  public static void requireLuhn(String field, String digits) {
    int sum = 0;
    boolean doubling = digits.length() % 2 == 0;
    for (int i = 0; i < digits.length(); i++) {
      int digit = digits.charAt(i) - '0';
      if (digit < 0 || digit > 9) {
        throw new EInvoiceException(
            ErrorCodes.IDENTIFIER_MALFORMED, field + " is expected to be digits only");
      }
      if (doubling) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      doubling = !doubling;
      sum += digit;
    }
    if (sum % 10 != 0) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          field + " fails its own check digit, so it is not the registration it claims to be");
    }
  }

  /** The issuing country, with Greece's {@code EL} prefix resolved to its ISO code. */
  public String issuingCountry() {
    String prefix = value.substring(0, 2);
    return "EL".equals(prefix) ? "GR" : prefix;
  }

  private static String normalise(String value) {
    if (value == null || value.isBlank()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "a VAT identifier is required here and is absent (stripe field: customer_tax_ids)");
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == ' ' || c == '.' || c == '-') {
        continue;
      }
      char upper = Character.toUpperCase(c);
      if (!((upper >= '0' && upper <= '9') || (upper >= 'A' && upper <= 'Z'))) {
        throw new EInvoiceException(
            ErrorCodes.IDENTIFIER_MALFORMED,
            "a VAT identifier is letters and digits only (offending character at index " + i + ")");
      }
      out.append(upper);
    }
    String normalised = out.toString();
    if (normalised.length() > BusinessTerms.TAX_IDENTIFIER) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          "a VAT identifier is at most " + BusinessTerms.TAX_IDENTIFIER + " characters");
    }
    return normalised;
  }

  private static Map<String, int[]> nationalLengths() {
    return Map.ofEntries(
        Map.entry("AT", new int[] {9, 9}),
        Map.entry("BE", new int[] {10, 10}),
        Map.entry("BG", new int[] {9, 10}),
        Map.entry("CY", new int[] {9, 9}),
        Map.entry("CZ", new int[] {8, 10}),
        Map.entry("DE", new int[] {9, 9}),
        Map.entry("DK", new int[] {8, 8}),
        Map.entry("EE", new int[] {9, 9}),
        Map.entry("ES", new int[] {9, 9}),
        Map.entry("FI", new int[] {8, 8}),
        Map.entry("FR", new int[] {11, 11}),
        Map.entry("GR", new int[] {9, 9}),
        Map.entry("HR", new int[] {11, 11}),
        Map.entry("HU", new int[] {8, 8}),
        Map.entry("IE", new int[] {8, 9}),
        Map.entry("IT", new int[] {11, 11}),
        Map.entry("LT", new int[] {9, 12}),
        Map.entry("LU", new int[] {8, 8}),
        Map.entry("LV", new int[] {11, 11}),
        Map.entry("MT", new int[] {8, 8}),
        Map.entry("NL", new int[] {12, 12}),
        Map.entry("PL", new int[] {10, 10}),
        Map.entry("PT", new int[] {9, 9}),
        Map.entry("RO", new int[] {2, 10}),
        Map.entry("SE", new int[] {12, 12}),
        Map.entry("SI", new int[] {8, 8}),
        Map.entry("SK", new int[] {10, 10}));
  }

  @Override
  public String toString() {
    // Never the value: a VAT identifier is a party's identity and belongs in no log line.
    return "VatIdentifier["
        + value.substring(0, 2)
        + ", checkDigitVerified="
        + checkDigitVerified
        + "]";
  }

  /** Lower-cased, for nothing but a deterministic comparison in tests and reports. */
  public String prefix() {
    return value.substring(0, 2).toLowerCase(Locale.ROOT);
  }
}
