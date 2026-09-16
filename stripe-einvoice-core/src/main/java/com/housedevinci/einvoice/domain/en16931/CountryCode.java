package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.util.Locale;
import java.util.Set;

/**
 * BT-40 and BT-55, the country code, ISO 3166-1 alpha-2.
 *
 * <p>Validated against the JDK's own ISO country list rather than against a two-letter pattern:
 * {@code XX} matches a pattern and fails the recipient's code-list rule (PEPPOL-EN16931-CL001 and
 * its XRechnung equivalent), which is a rejection days later instead of a refusal here.
 *
 * <p>Also answers the one question the VAT rules need: whether the country is in the EU VAT area,
 * which decides whether a cross-border supply is an intra-Community one (category K) or an export
 * (category G). The list is explicit and dated rather than derived, because membership is a legal
 * fact that changes and a derived answer would change silently with a JDK upgrade.
 */
public final class CountryCode {

  /**
   * EU member states as at 2026-09-16, by ISO 3166-1 alpha-2. Greece is {@code GR} here (ISO),
   * while its VAT identifier prefix is {@code EL}; both spellings are handled where each belongs.
   */
  public static final Set<String> EU_VAT_AREA =
      Set.of(
          "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU", "IE",
          "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK");

  private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

  private CountryCode() {}

  /**
   * @param field the field's name, for the message - never its value
   * @param value a two-letter country code in any case
   * @return the code upper-cased
   * @throws EInvoiceException {@link ErrorCodes#IDENTIFIER_MALFORMED} when it is not an ISO country
   */
  public static String validate(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE, field + " is required by EN 16931 and is absent");
    }
    String code = value.strip().toUpperCase(Locale.ROOT);
    if (code.length() != 2 || !ISO_COUNTRIES.contains(code)) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          field
              + " is not an ISO 3166-1 alpha-2 country code, and a recipient's code-list rule would"
              + " reject the document days after it was filed");
    }
    return code;
  }

  /** True when the country is in the EU VAT area as this module records it. */
  public static boolean isEuVatArea(String isoCountry) {
    return EU_VAT_AREA.contains(isoCountry);
  }
}
