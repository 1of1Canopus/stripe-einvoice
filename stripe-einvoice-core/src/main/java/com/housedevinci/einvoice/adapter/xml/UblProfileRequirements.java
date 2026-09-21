package com.housedevinci.einvoice.adapter.xml;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.en16931.Party;
import com.housedevinci.einvoice.domain.en16931.PaymentInstruction;
import com.housedevinci.einvoice.domain.en16931.PostalAddress;
import java.util.Optional;

/**
 * The terms a target profile requires beyond EN 16931 itself, as one body that runs in <b>both</b>
 * passes.
 *
 * <p>These rules used to live only in the writer, which the preflight deliberately does not run -
 * on the invariant that every string reaching the writer arrived through a screened business term,
 * so a writer refusal implied a mapper refusal. That invariant held for the <em>content</em> rules
 * and not for these, because these are <em>presence</em> rules: a buyer with no VAT identifier has
 * no BT-49 under Peppol, and a buyer whose frozen address carries no street has no BR-DE-8 under
 * XRechnung. Both are the buyer's own data, both passed the mapper, and both then cost a legal
 * number at the writer. So the body moved here and the mapper runs it before it returns, while the
 * writer keeps calling it too: a host may hold a renderer and call the writer directly, and a rule
 * that is a second line of defence is not a rule that is checked twice by accident.
 *
 * @param profile the target profile, which decides which of these are mandatory
 * @param buyerReference BT-10
 * @param payment BG-16
 * @param seller BG-4
 * @param buyer BG-7
 * @param reverseChargeOrIntraCommunity true when any breakdown group makes the buyer account for
 *     the VAT, which is the case that needs both parties identified
 */
public final class UblProfileRequirements {

  private UblProfileRequirements() {}

  public static void require(
      UblProfile profile,
      Optional<String> buyerReference,
      Optional<PaymentInstruction> payment,
      Party seller,
      Party buyer,
      boolean reverseChargeOrIntraCommunity) {
    if (profile.requiresBuyerReference() && buyerReference.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          profile.displayName()
              + " requires a buyer reference (BT-10, BR-DE-15). Set"
              + " einvoice.documents.buyer-reference");
    }
    if (profile.requiresPaymentAndContact()) {
      if (payment.isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            profile.displayName()
                + " requires payment instructions (BG-16, BR-DE-1). Set"
                + " einvoice.seller.payment.means-code and einvoice.seller.payment.account-id");
      }
      if (seller.contactValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            profile.displayName()
                + " requires a seller contact (BG-6, BR-DE-2). Set einvoice.seller.contact.*");
      }
      requireBuyerAddressDetail(profile, buyer);
    }
    if (profile.requiresElectronicAddress()) {
      if (seller.electronicAddressValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            profile.displayName()
                + " requires the seller's electronic address (BT-34, PEPPOL-EN16931-R020). Set"
                + " einvoice.seller.electronic-address and .electronic-address-scheme");
      }
      if (buyer.electronicAddressValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            profile.displayName()
                + " requires the buyer's electronic address (BT-49, PEPPOL-EN16931-R010). It is"
                + " derived from the buyer's VAT identifier when one is on the invoice, or"
                + " configured at einvoice.documents.buyer-electronic-address (stripe field:"
                + " customer_tax_ids)");
      }
    }
    if (reverseChargeOrIntraCommunity
        && (seller.vatIdentifierValue().isEmpty() || buyer.vatIdentifierValue().isEmpty())) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "a reverse-charge or intra-Community supply names both parties' VAT identifiers"
              + " (BR-AE-*, BR-IC-*): the buyer accounts for the VAT, and a document that does"
              + " not identify them cannot say who (stripe field: customer_tax_ids)");
    }
  }

  private static void requireBuyerAddressDetail(UblProfile profile, Party buyer) {
    PostalAddress address = buyer.address();
    if (address.line1Value().isEmpty()
        || address.cityValue().isEmpty()
        || address.postCodeValue().isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          profile.displayName()
              + " requires the buyer's street, city and post code (BR-DE-8, BR-DE-9, BR-DE-10)."
              + " Stripe froze the address it had at finalisation (stripe field: customer_address)");
    }
  }
}
