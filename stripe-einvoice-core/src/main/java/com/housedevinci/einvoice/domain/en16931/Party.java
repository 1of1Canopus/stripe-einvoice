package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.ScreenedText;
import java.util.Optional;

/**
 * BG-4 (seller) and BG-7 (buyer).
 *
 * <p>Every text component is screened on construction. Identifiers are validated for <b>both</b>
 * parties, which is the half of D-06 that is easy to forget: the seller's identifiers come from
 * configuration and are just as capable of being mistyped as the buyer's are of being hostile.
 *
 * @param name BT-27 / BT-44, the registered name
 * @param tradingName BT-28 / BT-45, optional
 * @param address BG-5 / BG-8
 * @param vatIdentifier BT-31 / BT-48, optional here and required by some tax categories
 * @param taxRegistrationIdentifier BT-32, the seller's local tax registration, optional
 * @param legalIdentifier BT-30 / BT-47, the legal registration with its scheme, optional
 * @param electronicAddress BT-34 / BT-49, mandatory for Peppol
 * @param contact BG-6 / BG-9, mandatory on the seller for XRechnung
 */
public record Party(
    String name,
    String tradingName,
    PostalAddress address,
    VatIdentifier vatIdentifier,
    String taxRegistrationIdentifier,
    PartyIdentifier legalIdentifier,
    PartyIdentifier electronicAddress,
    Contact contact) {

  public Party {
    name = ScreenedText.screen("party name", name, BusinessTerms.PARTY_NAME);
    tradingName =
        tradingName == null || tradingName.isBlank()
            ? null
            : ScreenedText.screen("party trading name", tradingName, BusinessTerms.PARTY_NAME);
    if (address == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "EN 16931 requires a postal address for each party (BR-08, BR-10)");
    }
    taxRegistrationIdentifier =
        taxRegistrationIdentifier == null || taxRegistrationIdentifier.isBlank()
            ? null
            : ScreenedText.screen(
                "tax registration identifier",
                taxRegistrationIdentifier,
                BusinessTerms.TAX_IDENTIFIER);
  }

  public Optional<String> tradingNameValue() {
    return Optional.ofNullable(tradingName);
  }

  public Optional<VatIdentifier> vatIdentifierValue() {
    return Optional.ofNullable(vatIdentifier);
  }

  public Optional<String> taxRegistrationIdentifierValue() {
    return Optional.ofNullable(taxRegistrationIdentifier);
  }

  public Optional<PartyIdentifier> legalIdentifierValue() {
    return Optional.ofNullable(legalIdentifier);
  }

  public Optional<PartyIdentifier> electronicAddressValue() {
    return Optional.ofNullable(electronicAddress);
  }

  public Optional<Contact> contactValue() {
    return Optional.ofNullable(contact);
  }

  /** The party's country, which the VAT rules read to decide a cross-border treatment. */
  public String country() {
    return address.country();
  }
}
