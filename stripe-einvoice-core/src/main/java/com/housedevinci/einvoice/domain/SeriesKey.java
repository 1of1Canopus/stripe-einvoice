package com.housedevinci.einvoice.domain;

import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;

/**
 * The scope of one numbering series: {@code (seller, document type, series, fiscal year, mode)}.
 *
 * <p>0.1.0 configures one seller and one series, and the columns carry one value each. They exist
 * because adding them later is a schema break and a re-numbering, while carrying them now costs a
 * predicate (Decision 1: single seller, but nothing may preclude many).
 *
 * <p>{@code fiscalYear} is {@link #CONTINUOUS} when the series does not reset per year, so a
 * continuous multi-year series is one row rather than a special case in every statement.
 *
 * <p><b>The document type is part of the key (credit-note series design).</b> Every numbered row
 * belongs to exactly one typed series, bound when the series row is created and immutable after
 * that. Invoices (BT-3 {@code 380}) and credit notes ({@code 381}) count in separate series; one
 * series name serves one type only. No mandate requires the separation; it is there because
 * auditors and accounting packages expect it. The corrected invoice ({@code 384}) has no series in
 * this edition and is refused here.
 */
public record SeriesKey(
    String sellerId, String series, int fiscalYear, Mode mode, DocumentTypeCode documentType) {

  /** The fiscal-year value of a series configured with {@code fiscal-year-reset=false}. */
  public static final int CONTINUOUS = 0;

  public SeriesKey {
    Identifiers.validate("seller id", sellerId, 64);
    Identifiers.validate("series name", series, 32);
    if (fiscalYear != CONTINUOUS && (fiscalYear < 1970 || fiscalYear > 9999)) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "fiscal year must be between 1970 and 9999, or 0 for a continuous series");
    }
    if (mode == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "mode must not be null");
    }
    if (documentType == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "document type must not be null");
    }
    if (documentType == DocumentTypeCode.CORRECTED_INVOICE) {
      throw new EInvoiceException(
          ErrorCodes.DOCUMENT_TYPE_UNSUPPORTED,
          "this edition numbers invoices (380) and credit notes (381) only; a corrected invoice"
              + " (384) has no series");
    }
  }

  /** An invoice series ({@code 380}), the only type 0.1.0 numbered. */
  public SeriesKey(String sellerId, String series, int fiscalYear, Mode mode) {
    this(sellerId, series, fiscalYear, mode, DocumentTypeCode.COMMERCIAL_INVOICE);
  }

  /** This series without its fiscal year. */
  public SeriesScope scope() {
    return new SeriesScope(sellerId, series, mode, documentType);
  }

  public boolean continuous() {
    return fiscalYear == CONTINUOUS;
  }

  /** The BT-3 code as the database stores it. */
  public String documentTypeCode() {
    return documentType.code();
  }
}
