package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.en16931.Contact;
import com.housedevinci.einvoice.domain.en16931.Party;
import com.housedevinci.einvoice.domain.en16931.PartyIdentifier;
import com.housedevinci.einvoice.domain.en16931.PaymentInstruction;
import com.housedevinci.einvoice.domain.en16931.PostalAddress;
import com.housedevinci.einvoice.domain.en16931.SellerProfile;
import com.housedevinci.einvoice.domain.en16931.VatIdentifier;

/**
 * Builds the seller profile from configuration, and refuses at <b>startup</b> anything that would
 * refuse at the first invoice.
 *
 * <p>This is where the seller's own identifiers are validated: the VAT identifier's country shape
 * and, where one exists, its check digit; the IBAN's mod-97; a GLN's GS1 check. The seller's fields
 * come from a properties file rather than from the internet, which makes them no less capable of
 * being mistyped - and a mistyped VAT identifier on a legal document is a rejection at the
 * recipient, weeks of correspondence, and a corrective invoice.
 *
 * <p>Every refusal names <b>the property</b>, never the value: a message is read out of a boot log
 * by somebody who then has to go and fix a file, and the value may be an identifier that belongs in
 * no log line.
 */
final class SellerProfiles {

  private SellerProfiles() {}

  /**
   * @param properties the module's configuration
   * @param profile the target document profile, which decides what is mandatory
   * @return the validated profile
   * @throws EInvoiceException {@link ErrorCodes#SELLER_PROFILE_INCOMPLETE} naming the property
   */
  static SellerProfile build(EInvoiceProperties properties, UblProfile profile) {
    EInvoiceProperties.Seller seller = properties.getSeller();
    EInvoiceProperties.Address address = seller.getAddress();
    EInvoiceProperties.Contact contact = seller.getContact();
    EInvoiceProperties.Payment payment = seller.getPayment();

    require(seller.getName(), "einvoice.seller.name", "the seller's registered name (BT-27)");
    require(
        address.getCountry(),
        "einvoice.seller.address.country",
        "the seller's country (BT-40), an ISO 3166-1 alpha-2 code");

    PostalAddress postal =
        new PostalAddress(
            blankToNull(address.getLine1()),
            blankToNull(address.getLine2()),
            blankToNull(address.getCity()),
            blankToNull(address.getPostalCode()),
            blankToNull(address.getCountrySubdivision()),
            address.getCountry());

    VatIdentifier vat =
        seller.getVatId().isBlank()
            ? null
            : VatIdentifier.parse("einvoice.seller.vat-id", seller.getVatId());

    PartyIdentifier legal =
        seller.getLegalId().isBlank()
            ? null
            : new PartyIdentifier(
                requireScheme(
                    seller.getLegalIdScheme(),
                    "einvoice.seller.legal-id-scheme",
                    "einvoice.seller.legal-id"),
                seller.getLegalId());

    PartyIdentifier electronic = electronicAddress(seller, vat);

    Contact sellerContact =
        contact.getName().isBlank()
                && contact.getTelephone().isBlank()
                && contact.getEmail().isBlank()
            ? null
            : new Contact(contact.getName(), contact.getTelephone(), contact.getEmail());

    Party party =
        new Party(
            seller.getName(),
            blankToNull(seller.getTradingName()),
            postal,
            vat,
            blankToNull(seller.getTaxRegistrationId()),
            legal,
            electronic,
            sellerContact);

    PaymentInstruction instruction =
        payment.getAccountId().isBlank()
            ? null
            : new PaymentInstruction(
                payment.getMeansCode(),
                payment.getAccountId(),
                blankToNull(payment.getAccountName()),
                blankToNull(payment.getServiceProviderId()));

    SellerProfile sellerProfile =
        new SellerProfile(
            party, instruction, blankToNull(properties.getDocuments().getBuyerReference()));
    // Everything the chosen profile's own rules demand, checked once, here, rather than on the
    // first invoice - by which time a number has been allocated and somebody is reading a stack
    // trace instead of a property name.
    sellerProfile.requireCompleteFor(
        profile.displayName(),
        profile.requiresPaymentAndContact(),
        profile.requiresBuyerReference(),
        profile.requiresElectronicAddress());
    return sellerProfile;
  }

  /** BT-49, when the buyer's own VAT identifier cannot supply it. */
  static PartyIdentifier buyerElectronicAddress(EInvoiceProperties properties) {
    EInvoiceProperties.Documents documents = properties.getDocuments();
    if (documents.getBuyerElectronicAddress().isBlank()) {
      return null;
    }
    return new PartyIdentifier(
        requireScheme(
            documents.getBuyerElectronicAddressScheme(),
            "einvoice.documents.buyer-electronic-address-scheme",
            "einvoice.documents.buyer-electronic-address"),
        documents.getBuyerElectronicAddress());
  }

  private static PartyIdentifier electronicAddress(
      EInvoiceProperties.Seller seller, VatIdentifier vat) {
    if (!seller.getElectronicAddress().isBlank()) {
      return new PartyIdentifier(
          requireScheme(
              seller.getElectronicAddressScheme(),
              "einvoice.seller.electronic-address-scheme",
              "einvoice.seller.electronic-address"),
          seller.getElectronicAddress());
    }
    if (vat == null) {
      return null;
    }
    String scheme = PartyIdentifier.VAT_ELECTRONIC_ADDRESS_SCHEMES.get(vat.issuingCountry());
    if (scheme == null) {
      // Deriving it would mean inventing a scheme, and a scheme outside the Peppol EAS code list
      // fails PEPPOL-EN16931-CL008 at the access point rather than here.
      return null;
    }
    return new PartyIdentifier(scheme, vat.value());
  }

  private static String requireScheme(String scheme, String property, String companion) {
    if (scheme == null || scheme.isBlank()) {
      throw new EInvoiceException(
          ErrorCodes.SELLER_PROFILE_INCOMPLETE,
          property
              + " is required whenever "
              + companion
              + " is set: an identifier without the scheme it was issued under is a number nobody"
              + " can resolve");
    }
    return scheme;
  }

  private static void require(String value, String property, String what) {
    if (value == null || value.isBlank()) {
      throw new EInvoiceException(
          ErrorCodes.SELLER_PROFILE_INCOMPLETE, property + " is required: it carries " + what);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
