package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.util.Optional;

/**
 * The issuing party, as the operator configured it, validated where it is built.
 *
 * <p>This is the half of D-06 that is easy to forget. The buyer's fields arrive from the internet
 * and are obviously untrusted; the seller's arrive from a properties file and are just as capable
 * of carrying a mistyped VAT identifier, an IBAN with a transposed pair, or a Leitweg-ID that no
 * authority will accept. Both go through the same screening and the same identifier checks, and the
 * failure for either is a typed refusal naming the <em>property</em>, at startup, rather than a
 * rejected document a week later.
 *
 * <p>What is <b>not</b> here, deliberately: anything selectable by a payload. The seller is
 * resolved from the platform's own account field and static configuration, never from metadata, a
 * custom field or a customer record (D-01, D-10).
 *
 * @param party the seller as EN 16931 models it
 * @param payment BG-16, required by the XRechnung profile
 * @param defaultBuyerReference BT-10, required by the XRechnung profile (BR-DE-15). A Leitweg-ID
 *     for a public-sector buyer, or the reference the buyer asked to see quoted.
 */
public record SellerProfile(Party party, PaymentInstruction payment, String defaultBuyerReference) {

  public SellerProfile {
    if (party == null) {
      throw new EInvoiceException(
          ErrorCodes.SELLER_PROFILE_INCOMPLETE,
          "a seller profile carries the issuing party (properties: einvoice.seller.*)");
    }
    defaultBuyerReference =
        defaultBuyerReference == null || defaultBuyerReference.isBlank()
            ? null
            : com.housedevinci.einvoice.domain.ScreenedText.screen(
                "einvoice.documents.buyer-reference",
                defaultBuyerReference,
                BusinessTerms.BUYER_REFERENCE);
  }

  public Optional<PaymentInstruction> paymentValue() {
    return Optional.ofNullable(payment);
  }

  public Optional<String> defaultBuyerReferenceValue() {
    return Optional.ofNullable(defaultBuyerReference);
  }

  /**
   * Refuses a profile that cannot produce a document in the given profile, naming the property to
   * fill.
   *
   * <p>Called once at startup, so an application whose seller profile is short of a field XRechnung
   * demands fails to start rather than failing on its first real invoice - by which time a number
   * has been allocated and an operator is reading a stack trace instead of a property name.
   */
  public void requireCompleteFor(
      String profileName,
      boolean requiresPaymentAndContact,
      boolean requiresBuyerReference,
      boolean requiresElectronicAddress) {
    if (requiresPaymentAndContact) {
      if (payment == null) {
        throw new EInvoiceException(
            ErrorCodes.SELLER_PROFILE_INCOMPLETE,
            profileName
                + " requires payment instructions on every invoice (BR-DE-1). Set"
                + " einvoice.seller.payment.means-code and einvoice.seller.payment.account-id");
      }
      if (party.contactValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.SELLER_PROFILE_INCOMPLETE,
            profileName
                + " requires a seller contact with a telephone number and an email address"
                + " (BR-DE-2, BR-DE-6, BR-DE-7). Set einvoice.seller.contact.name,"
                + " einvoice.seller.contact.telephone and einvoice.seller.contact.email");
      }
      if (party.address().line1Value().isEmpty()
          || party.address().cityValue().isEmpty()
          || party.address().postCodeValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.SELLER_PROFILE_INCOMPLETE,
            profileName
                + " requires the seller's street, city and post code (BR-DE-3, BR-DE-4, BR-DE-5)."
                + " Set einvoice.seller.address.line1, .city and .postal-code");
      }
      if (party.vatIdentifierValue().isEmpty()
          && party.taxRegistrationIdentifierValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.SELLER_PROFILE_INCOMPLETE,
            profileName
                + " requires the seller's VAT identifier or tax registration identifier"
                + " (BR-DE-16). Set einvoice.seller.vat-id or einvoice.seller.tax-registration-id");
      }
    }
    if (requiresBuyerReference && defaultBuyerReference == null) {
      throw new EInvoiceException(
          ErrorCodes.SELLER_PROFILE_INCOMPLETE,
          profileName
              + " requires a buyer reference on every invoice (BR-DE-15). Set"
              + " einvoice.documents.buyer-reference; for a public-sector buyer in Germany that is"
              + " the Leitweg-ID the authority issued");
    }
    if (requiresElectronicAddress && party.electronicAddressValue().isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.SELLER_PROFILE_INCOMPLETE,
          profileName
              + " requires the seller's electronic address (PEPPOL-EN16931-R020). Set"
              + " einvoice.seller.electronic-address and einvoice.seller.electronic-address-scheme");
    }
  }
}
