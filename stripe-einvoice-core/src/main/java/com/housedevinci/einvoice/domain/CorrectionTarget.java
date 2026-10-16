package com.housedevinci.einvoice.domain;

import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;

/**
 * The invoice a credit note corrects, by its full identity (D-PS1-37).
 *
 * <p>The issuance id alone is a surrogate: nothing chains the mapping from an id to an invoice,
 * because an invoice's chained events hash its legal number, not its row id. A role that outranks
 * the triggers could swap two ids, and a credit note whose hash covered only the id would still
 * verify while it pointed at another invoice. So the chained form of a credit note carries the
 * target's seller, mode, series, fiscal year and legal number, read from the target row when the
 * event is appended, and the id beside them.
 *
 * <p>A credit note may reference only an invoice ({@code 380}) of its own seller and mode that this
 * module issued (D-CNS-S12). That bound is enforced where the row is written; this value only
 * carries what was checked.
 */
public record CorrectionTarget(
    long issuanceId,
    String sellerId,
    Mode mode,
    String series,
    int fiscalYear,
    String legalNumber) {

  public CorrectionTarget {
    if (issuanceId <= 0) {
      throw new EInvoiceException(ErrorCodes.INVALID, "a correction target needs a row id");
    }
    Identifiers.validate("seller id", sellerId, 64);
    Identifiers.validate("series name", series, 32);
    if (mode == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "mode must not be null");
    }
    if (legalNumber == null || legalNumber.isEmpty()) {
      throw new EInvoiceException(ErrorCodes.INVALID, "a correction target needs a legal number");
    }
  }

  /** The type every correction target has. A credit note against a credit note is refused. */
  public static DocumentTypeCode requiredType() {
    return DocumentTypeCode.COMMERCIAL_INVOICE;
  }
}
