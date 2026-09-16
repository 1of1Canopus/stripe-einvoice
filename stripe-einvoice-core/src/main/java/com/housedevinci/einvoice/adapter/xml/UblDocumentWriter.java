package com.housedevinci.einvoice.adapter.xml;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Percentage;
import com.housedevinci.einvoice.domain.en16931.Contact;
import com.housedevinci.einvoice.domain.en16931.DocumentLine;
import com.housedevinci.einvoice.domain.en16931.EnInvoice;
import com.housedevinci.einvoice.domain.en16931.Money;
import com.housedevinci.einvoice.domain.en16931.Party;
import com.housedevinci.einvoice.domain.en16931.PaymentInstruction;
import com.housedevinci.einvoice.domain.en16931.PostalAddress;
import com.housedevinci.einvoice.domain.en16931.TaxSubtotal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Writes an {@link EnInvoice} as OASIS UBL 2.1, in Canonical XML 1.1 form, under a chosen {@link
 * UblProfile}.
 *
 * <p><b>No string templating anywhere</b> (D-06, checklist line 9). Every value reaches the bytes
 * through {@link CanonicalXmlWriter}, which escapes; every text value reached the model through
 * {@code ScreenedText}, which refuses what escaping cannot make safe. There is no concatenation of
 * a caller's value into markup in this class, and an ArchUnit rule and a reflection test hold that
 * line for the fields somebody adds next.
 *
 * <p><b>Byte determinism</b> (D-15, checklist lines 16 and 17). This class reads no clock, no
 * default time zone and no default locale. Dates come from the model, already converted in the
 * seller's tax zone, and are written with an ISO formatter bound to {@link Locale#ROOT}. Amounts
 * are written by {@link Money#toPlainString()}, which is plain notation at the currency's exponent.
 * Every collection walked here is a {@code List}; no map's iteration order reaches the output.
 *
 * <p>Element order follows the UBL 2.1 XSD sequence exactly, and the vendored schemas are the check
 * that it does. A credit note is a {@code CreditNote} document with positive amounts: the document
 * type carries the sign, never the numbers.
 */
public final class UblDocumentWriter {

  private static final String NS_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
  private static final String NS_CREDIT_NOTE =
      "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2";
  private static final String NS_CAC =
      "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
  private static final String NS_CBC =
      "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

  /** ISO 8601, bound to {@link Locale#ROOT} so no locale's calendar can reach a legal date. */
  private static final DateTimeFormatter ISO_DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT);

  private final UblProfile profile;

  public UblDocumentWriter(UblProfile profile) {
    this.profile = Objects.requireNonNull(profile, "profile");
  }

  /** The profile this writer writes under. */
  public UblProfile profile() {
    return profile;
  }

  /**
   * @param invoice the document
   * @return the canonical bytes, byte-identical for equal input under any locale and time zone
   */
  public byte[] write(EnInvoice invoice) {
    Objects.requireNonNull(invoice, "invoice");
    requireProfileRequirements(invoice);

    boolean creditNote = invoice.typeCode().isCreditNote();
    String root = creditNote ? "CreditNote" : "Invoice";
    String currency = invoice.currency();

    CanonicalXmlWriter w = new CanonicalXmlWriter();
    w.start(root)
        .namespace("", creditNote ? NS_CREDIT_NOTE : NS_INVOICE)
        .namespace("cac", NS_CAC)
        .namespace("cbc", NS_CBC);

    w.element("cbc:CustomizationID", profile.customizationId());
    w.element("cbc:ProfileID", profile.profileId());
    w.element("cbc:ID", invoice.number());
    w.element("cbc:IssueDate", date(invoice.issueDate()));
    if (!creditNote) {
      invoice.dueDateValue().ifPresent(d -> w.element("cbc:DueDate", date(d)));
      w.element("cbc:InvoiceTypeCode", invoice.typeCode().code());
    } else {
      w.element("cbc:CreditNoteTypeCode", invoice.typeCode().code());
    }
    invoice.noteValue().ifPresent(n -> w.element("cbc:Note", n));
    w.element("cbc:DocumentCurrencyCode", currency);
    invoice.buyerReferenceValue().ifPresent(r -> w.element("cbc:BuyerReference", r));

    invoice
        .precedingReferenceValue()
        .ifPresent(
            reference -> {
              w.start("cac:BillingReference");
              w.start("cac:InvoiceDocumentReference");
              w.element("cbc:ID", reference);
              invoice
                  .precedingReferenceDateValue()
                  .ifPresent(d -> w.element("cbc:IssueDate", date(d)));
              w.end();
              w.end();
            });

    // BT-4, the payment provider's own number, as a reference an auditor can follow back to the
    // account. It is never the legal number: that is cbc:ID above, and it is ours (Decision 2).
    invoice
        .upstreamNumberValue()
        .ifPresent(
            upstream -> {
              w.start("cac:AdditionalDocumentReference");
              w.element("cbc:ID", upstream);
              w.element("cbc:DocumentTypeCode", "130");
              w.end();
            });

    w.start("cac:AccountingSupplierParty");
    party(w, invoice.seller(), true);
    w.end();
    w.start("cac:AccountingCustomerParty");
    party(w, invoice.buyer(), false);
    w.end();

    invoice.paymentValue().ifPresent(payment -> paymentMeans(w, payment));

    w.start("cac:TaxTotal");
    amount(w, "cbc:TaxAmount", invoice.taxTotal());
    for (TaxSubtotal subtotal : invoice.taxSubtotals()) {
      w.start("cac:TaxSubtotal");
      amount(w, "cbc:TaxableAmount", subtotal.taxableAmount());
      amount(w, "cbc:TaxAmount", subtotal.taxAmount());
      w.start("cac:TaxCategory");
      w.element("cbc:ID", subtotal.category().code());
      w.element("cbc:Percent", percent(subtotal.ratePercentage()));
      subtotal
          .exemptionReasonValue()
          .ifPresent(
              reason -> {
                w.element("cbc:TaxExemptionReasonCode", reason.code());
                w.element("cbc:TaxExemptionReason", reason.legalReference());
              });
      w.start("cac:TaxScheme");
      w.element("cbc:ID", "VAT");
      w.end();
      w.end();
      w.end();
    }
    w.end();

    w.start("cac:LegalMonetaryTotal");
    amount(w, "cbc:LineExtensionAmount", invoice.lineTotal());
    amount(w, "cbc:TaxExclusiveAmount", invoice.taxExclusiveTotal());
    amount(w, "cbc:TaxInclusiveAmount", invoice.taxInclusiveTotal());
    amount(w, "cbc:PayableAmount", invoice.payableTotal());
    w.end();

    String lineElement = creditNote ? "cac:CreditNoteLine" : "cac:InvoiceLine";
    String quantityElement = creditNote ? "cbc:CreditedQuantity" : "cbc:InvoicedQuantity";
    for (DocumentLine line : invoice.lines()) {
      w.start(lineElement);
      w.element("cbc:ID", line.id());
      w.start(quantityElement)
          .attribute("unitCode", line.unitCode())
          .text(quantity(line.quantity()))
          .end();
      amount(w, "cbc:LineExtensionAmount", line.netAmount());
      w.start("cac:Item");
      line.itemDescriptionValue().ifPresent(d -> w.element("cbc:Description", d));
      w.element("cbc:Name", line.itemName());
      w.start("cac:ClassifiedTaxCategory");
      w.element("cbc:ID", line.category().code());
      w.element("cbc:Percent", percent(line.ratePercentage()));
      w.start("cac:TaxScheme");
      w.element("cbc:ID", "VAT");
      w.end();
      w.end();
      w.end();
      w.start("cac:Price");
      // BT-146 with BT-149: the line's own net amount, per its own quantity. Exact by
      // construction - BT-131 = BT-129 x BT-146 / BT-149 reduces to the amount that was charged,
      // with no division and therefore no rounding difference on any line.
      amount(w, "cbc:PriceAmount", line.netAmount());
      w.start("cbc:BaseQuantity")
          .attribute("unitCode", line.unitCode())
          .text(quantity(line.quantity()))
          .end();
      w.end();
      w.end();
    }

    w.end();
    return w.toBytes();
  }

  /**
   * Refuses, before any byte is written, a document the chosen profile's own rules would reject.
   *
   * <p>The schematron would catch every one of these a moment later. Catching them here means the
   * refusal names the missing field and the property that fills it, rather than quoting a rule id
   * in German at an operator who has never read the CIUS.
   */
  private void requireProfileRequirements(EnInvoice invoice) {
    if (profile.requiresBuyerReference() && invoice.buyerReferenceValue().isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          profile.displayName()
              + " requires a buyer reference (BT-10, BR-DE-15). Set"
              + " einvoice.documents.buyer-reference");
    }
    if (profile.requiresPaymentAndContact()) {
      if (invoice.paymentValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            profile.displayName()
                + " requires payment instructions (BG-16, BR-DE-1). Set"
                + " einvoice.seller.payment.means-code and einvoice.seller.payment.account-id");
      }
      if (invoice.seller().contactValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            profile.displayName()
                + " requires a seller contact (BG-6, BR-DE-2). Set einvoice.seller.contact.*");
      }
      requireBuyerAddressDetail(invoice);
    }
    if (profile.requiresElectronicAddress()) {
      if (invoice.seller().electronicAddressValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            profile.displayName()
                + " requires the seller's electronic address (BT-34, PEPPOL-EN16931-R020). Set"
                + " einvoice.seller.electronic-address and .electronic-address-scheme");
      }
      if (invoice.buyer().electronicAddressValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            profile.displayName()
                + " requires the buyer's electronic address (BT-49, PEPPOL-EN16931-R010). It is"
                + " derived from the buyer's VAT identifier when one is on the invoice, or"
                + " configured at einvoice.documents.buyer-electronic-address (stripe field:"
                + " customer_tax_ids)");
      }
    }
    if (invoice.hasReverseChargeOrIntraCommunity()) {
      if (invoice.seller().vatIdentifierValue().isEmpty()
          || invoice.buyer().vatIdentifierValue().isEmpty()) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            "a reverse-charge or intra-Community supply names both parties' VAT identifiers"
                + " (BR-AE-*, BR-IC-*): the buyer accounts for the VAT, and a document that does"
                + " not identify them cannot say who (stripe field: customer_tax_ids)");
      }
    }
  }

  private void requireBuyerAddressDetail(EnInvoice invoice) {
    PostalAddress address = invoice.buyer().address();
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

  private static void party(CanonicalXmlWriter w, Party party, boolean seller) {
    w.start("cac:Party");
    party
        .electronicAddressValue()
        .ifPresent(
            endpoint ->
                w.start("cbc:EndpointID")
                    .attribute("schemeID", endpoint.scheme())
                    .text(endpoint.value())
                    .end());
    party
        .legalIdentifierValue()
        .ifPresent(
            identifier -> {
              w.start("cac:PartyIdentification");
              w.start("cbc:ID")
                  .attribute("schemeID", identifier.scheme())
                  .text(identifier.value())
                  .end();
              w.end();
            });
    w.start("cac:PartyName");
    w.element("cbc:Name", party.tradingNameValue().orElse(party.name()));
    w.end();
    address(w, party.address());
    party
        .vatIdentifierValue()
        .ifPresent(
            vat -> {
              w.start("cac:PartyTaxScheme");
              w.element("cbc:CompanyID", vat.value());
              w.start("cac:TaxScheme");
              w.element("cbc:ID", "VAT");
              w.end();
              w.end();
            });
    if (seller) {
      party
          .taxRegistrationIdentifierValue()
          .ifPresent(
              registration -> {
                // BT-32 carries a local tax registration under a scheme that is not VAT. The
                // second PartyTaxScheme is how UBL says that, and FC is the German "Finanzamt"
                // scheme XRechnung's own examples use.
                w.start("cac:PartyTaxScheme");
                w.element("cbc:CompanyID", registration);
                w.start("cac:TaxScheme");
                w.element("cbc:ID", "FC");
                w.end();
                w.end();
              });
    }
    w.start("cac:PartyLegalEntity");
    w.element("cbc:RegistrationName", party.name());
    party
        .legalIdentifierValue()
        .ifPresent(
            identifier ->
                w.start("cbc:CompanyID")
                    .attribute("schemeID", identifier.scheme())
                    .text(identifier.value())
                    .end());
    w.end();
    party.contactValue().ifPresent(contact -> contact(w, contact));
    w.end();
  }

  private static void contact(CanonicalXmlWriter w, Contact contact) {
    w.start("cac:Contact");
    w.element("cbc:Name", contact.name());
    w.element("cbc:Telephone", contact.telephone());
    w.element("cbc:ElectronicMail", contact.email());
    w.end();
  }

  private static void address(CanonicalXmlWriter w, PostalAddress a) {
    w.start("cac:PostalAddress");
    a.line1Value().ifPresent(line -> w.element("cbc:StreetName", line));
    a.line2Value().ifPresent(line -> w.element("cbc:AdditionalStreetName", line));
    a.cityValue().ifPresent(city -> w.element("cbc:CityName", city));
    a.postCodeValue().ifPresent(code -> w.element("cbc:PostalZone", code));
    a.countrySubdivisionValue().ifPresent(sub -> w.element("cbc:CountrySubentity", sub));
    w.start("cac:Country");
    w.element("cbc:IdentificationCode", a.country());
    w.end();
    w.end();
  }

  private static void paymentMeans(CanonicalXmlWriter w, PaymentInstruction payment) {
    w.start("cac:PaymentMeans");
    w.element("cbc:PaymentMeansCode", payment.meansCode());
    w.start("cac:PayeeFinancialAccount");
    w.element("cbc:ID", payment.accountIdentifier());
    payment.accountNameValue().ifPresent(name -> w.element("cbc:Name", name));
    payment
        .serviceProviderIdentifierValue()
        .ifPresent(
            bic -> {
              w.start("cac:FinancialInstitutionBranch");
              w.element("cbc:ID", bic);
              w.end();
            });
    w.end();
    w.end();
  }

  private static void amount(CanonicalXmlWriter w, String qName, Money money) {
    w.start(qName).attribute("currencyID", money.currency()).text(money.toPlainString()).end();
  }

  /** Rates are written at scale 2, always, so 19 and 19.00 are never both possible. */
  private static String percent(Percentage rate) {
    BigDecimal value = rate == null ? BigDecimal.ZERO : rate.value();
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** Quantities are written at their own scale, in plain notation, with no locale involved. */
  private static String quantity(BigDecimal value) {
    return value.toPlainString();
  }

  private static String date(LocalDate date) {
    return ISO_DATE.format(date);
  }
}
