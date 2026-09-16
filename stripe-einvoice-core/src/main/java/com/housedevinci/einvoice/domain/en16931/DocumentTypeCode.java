package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;

/**
 * BT-3, the document type code, from UNTDID 1001 (D-13).
 *
 * <p>The sign convention is carried by the <b>type</b>, never by the amounts. A refund is a credit
 * note (381) with positive amounts; it is not an invoice (380) with negative ones. Several profiles
 * reject negative line amounts outright, so an invoice encoded the second way reconciles
 * arithmetically at our end and means nothing legally at the recipient's.
 *
 * <p>Only the three codes this edition writes are here. An unmapped Stripe object and state
 * combination is a refusal, not a default (D-13): a type code invented for a case nobody thought
 * about is a legal assertion nobody made.
 */
public enum DocumentTypeCode {

  /** 380, commercial invoice. Positive amounts, a positive total. */
  COMMERCIAL_INVOICE("380", false),

  /** 381, credit note. Positive amounts; the type is what says they are credited. */
  CREDIT_NOTE("381", true),

  /**
   * 384, corrected invoice. Positive amounts, and a mandatory reference to the invoice it corrects
   * (BT-25/BT-26). Written by this module only when a caller asks for it explicitly.
   */
  CORRECTED_INVOICE("384", false);

  private final String untdid1001;
  private final boolean creditNote;

  DocumentTypeCode(String untdid1001, boolean creditNote) {
    this.untdid1001 = untdid1001;
    this.creditNote = creditNote;
  }

  /** The code as it is written into BT-3. */
  public String code() {
    return untdid1001;
  }

  /** True when the document is written in the UBL {@code CreditNote} syntax rather than Invoice. */
  public boolean isCreditNote() {
    return creditNote;
  }

  /** True when this type must carry a reference to the document it corrects (BT-25). */
  public boolean requiresPrecedingReference() {
    return this != COMMERCIAL_INVOICE;
  }

  /**
   * Refuses a total whose sign this type cannot carry.
   *
   * @throws EInvoiceException {@link ErrorCodes#DOCUMENT_TYPE_UNSUPPORTED} on a negative total
   */
  public void requireSignConvention(Money payable) {
    if (payable.isNegative()) {
      throw new EInvoiceException(
          ErrorCodes.DOCUMENT_TYPE_UNSUPPORTED,
          "a document of type "
              + untdid1001
              + " carries positive amounts. A negative total is a credit note (type code 381) with"
              + " positive amounts, never an invoice with negative ones");
    }
  }

  /**
   * @param code a UNTDID 1001 code
   * @return the matching type
   * @throws EInvoiceException {@link ErrorCodes#DOCUMENT_TYPE_UNSUPPORTED} for anything else
   */
  public static DocumentTypeCode of(String code) {
    for (DocumentTypeCode type : values()) {
      if (type.untdid1001.equals(code)) {
        return type;
      }
    }
    throw new EInvoiceException(
        ErrorCodes.DOCUMENT_TYPE_UNSUPPORTED,
        "this edition writes document type codes 380, 381 and 384 only");
  }
}
