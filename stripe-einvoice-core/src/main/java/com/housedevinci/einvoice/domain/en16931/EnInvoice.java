package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.ScreenedText;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The EN 16931 semantic model of one document: the thing the writers serialise and the validators
 * judge.
 *
 * <p>JDK only, by design and by ArchUnit rule (D-25). No Stripe type, no JAXB annotation, no
 * Jackson annotation, no XML API, no Spring. The pull to annotate this for a marshaller is real and
 * is refused: the bytes are written by a canonical writer precisely so that the prefix, attribute
 * order and empty-element form are ours rather than whichever Jakarta XML Bind implementation is on
 * the classpath at run time.
 *
 * <p><b>The balance rules are invariants, not a validation step.</b> EN 16931's BR-CO-10, BR-CO-13,
 * BR-CO-14 and BR-CO-15 are checked in the canonical constructor, so an unbalanced document cannot
 * be constructed, let alone written or archived. The schematron below will check the same thing;
 * that is the point - a rule this module cannot state itself is a rule it is trusting somebody
 * else's stylesheet to remember.
 *
 * @param number BT-1, the legal number this module allocated
 * @param issueDate BT-2, derived in the seller's tax zone, never from a clock
 * @param dueDate BT-9, optional
 * @param typeCode BT-3
 * @param currency BT-5
 * @param buyerReference BT-10, optional in EN 16931 and mandatory for XRechnung
 * @param note BT-22, optional
 * @param precedingReference BT-25, the document this one corrects
 * @param precedingReferenceDate BT-26
 * @param upstreamNumber the payment provider's own invoice number, carried as a reference so an
 *     auditor can tie the legal document to the account it came from
 * @param seller BG-4
 * @param buyer BG-7
 * @param payment BG-16, optional in EN 16931 and mandatory for XRechnung
 * @param lines BG-25
 * @param taxSubtotals BG-23
 * @param lineTotal BT-106
 * @param taxExclusiveTotal BT-109
 * @param taxTotal BT-110
 * @param taxInclusiveTotal BT-112
 * @param payableTotal BT-115
 */
public record EnInvoice(
    String number,
    LocalDate issueDate,
    LocalDate dueDate,
    DocumentTypeCode typeCode,
    String currency,
    String buyerReference,
    String note,
    String precedingReference,
    LocalDate precedingReferenceDate,
    String upstreamNumber,
    Party seller,
    Party buyer,
    PaymentInstruction payment,
    List<DocumentLine> lines,
    List<TaxSubtotal> taxSubtotals,
    Money lineTotal,
    Money taxExclusiveTotal,
    Money taxTotal,
    Money taxInclusiveTotal,
    Money payableTotal) {

  public EnInvoice {
    number = ScreenedText.screen("invoice number", number, BusinessTerms.ID);
    if (issueDate == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE, "EN 16931 requires an issue date (BR-03)");
    }
    if (typeCode == null) {
      throw new EInvoiceException(
          ErrorCodes.DOCUMENT_TYPE_UNSUPPORTED, "EN 16931 requires a document type code (BR-04)");
    }
    currency = CurrencyExponents.requireSupported(currency);
    buyerReference =
        buyerReference == null || buyerReference.isBlank()
            ? null
            : ScreenedText.screen("buyer reference", buyerReference, BusinessTerms.BUYER_REFERENCE);
    note =
        note == null || note.isBlank()
            ? null
            : ScreenedText.screen("note", note, BusinessTerms.NOTE);
    precedingReference =
        precedingReference == null || precedingReference.isBlank()
            ? null
            : ScreenedText.screen(
                "preceding invoice reference", precedingReference, BusinessTerms.ID);
    upstreamNumber =
        upstreamNumber == null || upstreamNumber.isBlank()
            ? null
            : ScreenedText.screen("upstream invoice number", upstreamNumber, BusinessTerms.ID);
    if (seller == null || buyer == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "EN 16931 requires a seller and a buyer (BR-06, BR-07, BR-08, BR-10)");
    }
    lines = List.copyOf(lines);
    taxSubtotals = List.copyOf(taxSubtotals);
    if (lines.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE, "EN 16931 requires at least one line (BR-16)");
    }
    if (taxSubtotals.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE, "EN 16931 requires at least one VAT breakdown (BR-CO-18)");
    }
    requireUniqueLineIds(lines);
    requireSingleCurrency(
        currency,
        lines,
        taxSubtotals,
        lineTotal,
        taxExclusiveTotal,
        taxTotal,
        taxInclusiveTotal,
        payableTotal);
    typeCode.requireSignConvention(payableTotal);
    if (typeCode.requiresPrecedingReference() && precedingReference == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "a document of type "
              + typeCode.code()
              + " carries a reference to the invoice it corrects (BT-25), or it corrects nothing"
              + " anyone can find");
    }
    requireBalance(
        lines,
        taxSubtotals,
        lineTotal,
        taxExclusiveTotal,
        taxTotal,
        taxInclusiveTotal,
        payableTotal);
  }

  public Optional<LocalDate> dueDateValue() {
    return Optional.ofNullable(dueDate);
  }

  public Optional<String> buyerReferenceValue() {
    return Optional.ofNullable(buyerReference);
  }

  public Optional<String> noteValue() {
    return Optional.ofNullable(note);
  }

  public Optional<String> precedingReferenceValue() {
    return Optional.ofNullable(precedingReference);
  }

  public Optional<LocalDate> precedingReferenceDateValue() {
    return Optional.ofNullable(precedingReferenceDate);
  }

  public Optional<String> upstreamNumberValue() {
    return Optional.ofNullable(upstreamNumber);
  }

  public Optional<PaymentInstruction> paymentValue() {
    return Optional.ofNullable(payment);
  }

  /** True when any line or breakdown group carries a category whose VAT the buyer accounts for. */
  public boolean hasReverseChargeOrIntraCommunity() {
    return taxSubtotals.stream().anyMatch(t -> t.category().requiresBothVatIdentifiers());
  }

  private static void requireUniqueLineIds(List<DocumentLine> lines) {
    // Collapsed comparison, not raw: two ids that differ only by a non-breaking space are one id
    // to every reader of the document and two to a naive set (checklist line 8, C17-37).
    Map<String, String> seen = new LinkedHashMap<>();
    for (DocumentLine line : lines) {
      String collapsed = ScreenedText.collapsed(line.id());
      if (seen.put(collapsed, line.id()) != null) {
        throw new EInvoiceException(
            ErrorCodes.INVALID,
            "two lines carry the same identifier once whitespace is collapsed, so a recipient"
                + " cannot tell them apart (BT-126)");
      }
    }
  }

  private static void requireSingleCurrency(
      String currency, List<DocumentLine> lines, List<TaxSubtotal> subtotals, Money... totals) {
    for (DocumentLine line : lines) {
      requireCurrency(currency, line.netAmount());
    }
    for (TaxSubtotal subtotal : subtotals) {
      requireCurrency(currency, subtotal.taxableAmount());
      requireCurrency(currency, subtotal.taxAmount());
    }
    for (Money total : totals) {
      requireCurrency(currency, total);
    }
  }

  private static void requireCurrency(String currency, Money money) {
    if (money == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE, "a required amount is absent from the document");
    }
    if (!currency.equals(money.currency())) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_UNSUPPORTED,
          "one document carries exactly one currency; this edition does not write the accounting"
              + " currency amount (BT-111) and refuses rather than omitting it (D-16)");
    }
  }

  /**
   * EN 16931's BR-CO-10, BR-CO-13, BR-CO-14 and BR-CO-15, as invariants.
   *
   * <p>BR-CO-11 and BR-CO-12 (allowances and charges at document level) are absent because this
   * edition writes neither; the mapper refuses an invoice carrying a discount rather than writing a
   * document with an allowance it did not model.
   */
  private static void requireBalance(
      List<DocumentLine> lines,
      List<TaxSubtotal> subtotals,
      Money lineTotal,
      Money taxExclusiveTotal,
      Money taxTotal,
      Money taxInclusiveTotal,
      Money payableTotal) {
    Money summedLines = lines.get(0).netAmount();
    for (int i = 1; i < lines.size(); i++) {
      summedLines = summedLines.add(lines.get(i).netAmount());
    }
    requireEqual("BR-CO-10", "the sum of the line net amounts", summedLines, "BT-106", lineTotal);

    Money summedTaxable = subtotals.get(0).taxableAmount();
    Money summedTax = subtotals.get(0).taxAmount();
    for (int i = 1; i < subtotals.size(); i++) {
      summedTaxable = summedTaxable.add(subtotals.get(i).taxableAmount());
      summedTax = summedTax.add(subtotals.get(i).taxAmount());
    }
    requireEqual(
        "BR-CO-13",
        "the sum of the VAT breakdown taxable amounts",
        summedTaxable,
        "BT-109",
        taxExclusiveTotal);
    requireEqual(
        "BR-CO-14", "the sum of the VAT breakdown tax amounts", summedTax, "BT-110", taxTotal);
    requireEqual(
        "BR-CO-15",
        "the tax exclusive amount plus the tax amount",
        taxExclusiveTotal.add(taxTotal),
        "BT-112",
        taxInclusiveTotal);
    requireEqual("BR-CO-16", "the tax inclusive amount", taxInclusiveTotal, "BT-115", payableTotal);
  }

  private static void requireEqual(
      String rule, String whatWasSummed, Money computed, String term, Money stated) {
    if (!computed.isEqualTo(stated)) {
      // Both values named, as D-12 requires. They are the seller's own amounts, not buyer PII.
      throw new EInvoiceException(
          ErrorCodes.DOCUMENT_UNBALANCED,
          rule
              + ": "
              + whatWasSummed
              + " is "
              + computed
              + " and "
              + term
              + " says "
              + stated
              + ". A document that does not balance is refused rather than adjusted: adjusting it"
              + " changes what the seller charged");
    }
  }
}
