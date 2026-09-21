package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.ScreenedText;
import java.time.LocalDate;
import java.util.List;
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
    // One body, two passes. BT-1 is the only rule that needs the number; everything else - every
    // screen, every required field, every BR-CO-* balance invariant - lives in UnnumberedInvoice
    // and is run from here by building one. A screen cannot be in the render path and missing from
    // the preflight path, because there is only one path.
    number = ScreenedText.screen("invoice number", number, BusinessTerms.ID);
    UnnumberedInvoice body =
        new UnnumberedInvoice(
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
    issueDate = body.issueDate();
    dueDate = body.dueDate();
    typeCode = body.typeCode();
    currency = body.currency();
    buyerReference = body.buyerReference();
    note = body.note();
    precedingReference = body.precedingReference();
    precedingReferenceDate = body.precedingReferenceDate();
    upstreamNumber = body.upstreamNumber();
    seller = body.seller();
    buyer = body.buyer();
    payment = body.payment();
    lines = body.lines();
    taxSubtotals = body.taxSubtotals();
    lineTotal = body.lineTotal();
    taxExclusiveTotal = body.taxExclusiveTotal();
    taxTotal = body.taxTotal();
    taxInclusiveTotal = body.taxInclusiveTotal();
    payableTotal = body.payableTotal();
  }

  /** This document without its number: the stage the preflight runs, for a caller that wants it. */
  public UnnumberedInvoice unnumbered() {
    return new UnnumberedInvoice(
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

}
