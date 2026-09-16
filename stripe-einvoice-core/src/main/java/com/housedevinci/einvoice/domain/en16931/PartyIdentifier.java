package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.ScreenedText;
import java.util.Locale;
import java.util.Map;

/**
 * An identifier with the scheme it is issued under: BT-29/BT-46 (party identifier), BT-30/BT-47
 * (legal registration identifier) and BT-34/BT-49 (electronic address).
 *
 * <p>An identifier without its scheme is a number nobody can resolve. The scheme is an ISO 6523 ICD
 * code, or - for an electronic address - a code from the Peppol Electronic Address Scheme list,
 * which is what {@code PEPPOL-EN16931-CL008} checks: {@code EM} was removed from that list, so an
 * email address as an electronic address is refused here rather than at the access point.
 */
public record PartyIdentifier(String scheme, String value) {

  /**
   * The Peppol EAS codes for a VAT-identifier-based electronic address, by country. These are the
   * schemes this module can derive on its own from a party's VAT identifier; anything else is
   * configured explicitly.
   *
   * <p>Source: Peppol Electronic Address Scheme code list, read 2026-09-16 at {@code
   * https://docs.peppol.eu/poacc/billing/3.0/codelist/eas/}.
   */
  public static final Map<String, String> VAT_ELECTRONIC_ADDRESS_SCHEMES =
      Map.ofEntries(
          Map.entry("AT", "9915"),
          Map.entry("BE", "9925"),
          Map.entry("DK", "0184"),
          Map.entry("DE", "9930"),
          Map.entry("ES", "9920"),
          Map.entry("FI", "0216"),
          Map.entry("FR", "9957"),
          Map.entry("IT", "9906"),
          Map.entry("NL", "9944"),
          Map.entry("NO", "0192"),
          Map.entry("PL", "9945"),
          Map.entry("PT", "9946"),
          Map.entry("SE", "0007"));

  /** ISO 6523 ICD 0088: a GS1 Global Location Number, thirteen digits with a mod-10 check. */
  public static final String GLN_SCHEME = "0088";

  /** ISO 6523 ICD 0002: a French SIRENE registration, nine or fourteen digits with a Luhn check. */
  public static final String SIRENE_SCHEME = "0002";

  public PartyIdentifier {
    scheme = screenScheme(scheme);
    value = ScreenedText.screen("party identifier", value, BusinessTerms.ELECTRONIC_ADDRESS);
    if (!value.equals(value.strip())) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          "a party identifier carries no leading or trailing space");
    }
    // Both parties' identifiers, checked where a check digit exists (D-06). A mistyped GLN or
    // SIREN is refused here, by name, rather than by the recipient's own rule days later.
    if (GLN_SCHEME.equals(scheme)) {
      requireGln(value);
    } else if (SIRENE_SCHEME.equals(scheme)) {
      VatIdentifier.requireLuhn("a SIRENE identifier (scheme 0002)", value);
    }
  }

  /** GS1's mod-10 check over thirteen digits. */
  private static void requireGln(String value) {
    if (value.length() != 13) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED, "a GLN (scheme 0088) is thirteen digits");
    }
    int sum = 0;
    for (int i = 0; i < 12; i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        throw new EInvoiceException(
            ErrorCodes.IDENTIFIER_MALFORMED, "a GLN (scheme 0088) is digits only");
      }
      sum += (c - '0') * (i % 2 == 1 ? 3 : 1);
    }
    int expected = (10 - sum % 10) % 10;
    if (value.charAt(12) - '0' != expected) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          "a GLN (scheme 0088) fails its own GS1 check digit, so it is not the location it claims"
              + " to be");
    }
  }

  /**
   * The electronic address (BT-34/BT-49) derived from a party's VAT identifier, when this module
   * knows the scheme for that country.
   *
   * @throws EInvoiceException {@link ErrorCodes#IDENTIFIER_MALFORMED} when it does not, so that the
   *     caller configures one rather than getting a scheme that fails the recipient's code list
   */
  public static PartyIdentifier fromVatIdentifier(String isoCountry, String vatIdentifier) {
    String scheme = VAT_ELECTRONIC_ADDRESS_SCHEMES.get(isoCountry);
    if (scheme == null) {
      throw new EInvoiceException(
          ErrorCodes.IDENTIFIER_MALFORMED,
          "no Peppol electronic address scheme is recorded for a VAT identifier issued in "
              + isoCountry
              + ", so the electronic address has to be configured rather than derived (property:"
              + " einvoice.documents.buyer-electronic-address)");
    }
    return new PartyIdentifier(scheme, vatIdentifier);
  }

  private static String screenScheme(String scheme) {
    String value = ScreenedText.screen("identifier scheme", scheme, 16);
    String upper = value.toUpperCase(Locale.ROOT);
    for (int i = 0; i < upper.length(); i++) {
      char c = upper.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || c == ':' || c == '.')) {
        throw new EInvoiceException(
            ErrorCodes.IDENTIFIER_MALFORMED,
            "an identifier scheme is an ISO 6523 or EAS code, not free text");
      }
    }
    return upper;
  }
}
