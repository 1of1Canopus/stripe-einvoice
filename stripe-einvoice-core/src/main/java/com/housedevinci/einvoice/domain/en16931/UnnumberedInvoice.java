package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.LegalNumber;
import com.housedevinci.einvoice.domain.ScreenedText;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything an EN 16931 document is, except its legal number: the stage of the model that can be
 * built before a number has been allocated, and therefore the stage a pre-allocation preflight can
 * run.
 *
 * <p><b>This is where every screen and every balance invariant lives.</b> {@link EnInvoice}'s own
 * constructor screens BT-1 and then builds one of these, so "the preflight checks less than the
 * render does" is not something this code can express: there is one body, and both passes run it. A
 * screen added here is added to both; a screen removed here is removed from both, which is why the
 * probe that detects a missing screen walks the upstream payload's own shape rather than comparing
 * the two passes to each other.
 *
 * <p>JDK only, like the rest of the model.
 */
public record UnnumberedInvoice(
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

  public UnnumberedInvoice {
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

  /**
   * The number-dependent tail, and nothing else: BT-1 is screened by {@link EnInvoice}'s own
   * constructor and every other rule has already run here.
   *
   * @param number the legal number this module allocated for the document
   */
  public EnInvoice numbered(LegalNumber number) {
    if (number == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE, "EN 16931 requires an invoice number (BT-1, BR-02)");
    }
    return new EnInvoice(
        number.value(),
        issueDate,
        dueDate,
        typeCode,
        currency,
        buyerReference,
        note,
        precedingReference,
        precedingReferenceDate,
        upstreamNumber,
        seller,
        buyer,
        payment,
        lines,
        taxSubtotals,
        lineTotal,
        taxExclusiveTotal,
        taxTotal,
        taxInclusiveTotal,
        payableTotal);
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
