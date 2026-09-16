package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.ScreenedText;
import java.util.Optional;

/**
 * BG-5 and BG-8, a party's postal address.
 *
 * <p>Every text component is screened on construction, through the one screening function the whole
 * module shares (D-06): there is no path from a buyer-supplied address line to the bytes of a
 * document that does not pass through here. The country code is validated against the ISO list, not
 * against a pattern.
 *
 * @param line1 BT-35 / BT-50, the street
 * @param line2 BT-36 / BT-51, the additional street line, optional
 * @param city BT-37 / BT-52
 * @param postCode BT-38 / BT-53, optional in EN 16931 and mandatory in XRechnung
 * @param countrySubdivision BT-39 / BT-54, optional
 * @param country BT-40 / BT-55, ISO 3166-1 alpha-2
 */
public record PostalAddress(
    String line1,
    String line2,
    String city,
    String postCode,
    String countrySubdivision,
    String country) {

  public PostalAddress {
    line1 = screenOptional("address line 1", line1, BusinessTerms.ADDRESS_LINE);
    line2 = screenOptional("address line 2", line2, BusinessTerms.ADDRESS_LINE);
    city = screenOptional("city", city, BusinessTerms.CITY);
    postCode = screenOptional("post code", postCode, BusinessTerms.POST_CODE);
    countrySubdivision =
        screenOptional("country subdivision", countrySubdivision, BusinessTerms.CITY);
    country = CountryCode.validate("country", country);
  }

  public Optional<String> line2Value() {
    return Optional.ofNullable(line2);
  }

  public Optional<String> cityValue() {
    return Optional.ofNullable(city);
  }

  public Optional<String> postCodeValue() {
    return Optional.ofNullable(postCode);
  }

  public Optional<String> line1Value() {
    return Optional.ofNullable(line1);
  }

  public Optional<String> countrySubdivisionValue() {
    return Optional.ofNullable(countrySubdivision);
  }

  private static String screenOptional(String field, String value, int max) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return ScreenedText.screen(field, value, max);
  }
}
