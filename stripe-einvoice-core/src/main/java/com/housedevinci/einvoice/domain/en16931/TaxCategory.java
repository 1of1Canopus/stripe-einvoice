package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.math.BigDecimal;

/**
 * BT-95/BT-102/BT-151, the VAT category code, from UNTDID 5305 as EN 16931 restricts it (D-14).
 *
 * <p>A category is a <b>VAT position</b>, not a formatting choice. Each one below carries the two
 * rules EN 16931 attaches to it - whether the rate must be zero, and whether an exemption reason is
 * required - so that a zero-rated line with no reason code is refused here rather than rejected by
 * the recipient. "Recipients reject a zero-rated line with no reason" is the whole point of D-14,
 * and a category enum that does not know that is a code list, not a control.
 *
 * <p>The two seller-side obligations that come with the zero-rate categories are noted on each
 * constant, because they are what the seller is asserting by issuing the document.
 */
public enum TaxCategory {

  /** S - standard rate. A positive rate, no exemption reason. */
  STANDARD("S", false, false),

  /** Z - zero rated goods. Rate zero, and EN 16931 wants a reason. */
  ZERO_RATED("Z", true, true),

  /** E - exempt from VAT. Rate zero, reason required. */
  EXEMPT("E", true, true),

  /**
   * AE - VAT reverse charge. Rate zero, reason required, and both parties' VAT identifiers are
   * mandatory: the buyer accounts for the VAT, and a document that does not identify them cannot
   * say who.
   */
  REVERSE_CHARGE("AE", true, true),

  /**
   * K - VAT exempt for intra-community supply of goods and services. Rate zero, reason required,
   * both VAT identifiers mandatory, and the two parties must be in different member states.
   */
  INTRA_COMMUNITY("K", true, true),

  /** G - free export item, VAT not charged. Rate zero, reason required. */
  EXPORT("G", true, true),

  /** O - services outside the scope of tax. Rate zero, reason required, and no other category. */
  OUT_OF_SCOPE("O", true, true);

  private final String untdid5305;
  private final boolean zeroRated;
  private final boolean reasonRequired;

  TaxCategory(String untdid5305, boolean zeroRated, boolean reasonRequired) {
    this.untdid5305 = untdid5305;
    this.zeroRated = zeroRated;
    this.reasonRequired = reasonRequired;
  }

  /** The code as it is written into BT-95, BT-102 and BT-151. */
  public String code() {
    return untdid5305;
  }

  /** True when EN 16931 requires the rate on this category to be zero. */
  public boolean isZeroRated() {
    return zeroRated;
  }

  /** True when EN 16931 requires an exemption reason code or text on this category. */
  public boolean isReasonRequired() {
    return reasonRequired;
  }

  /** True when both parties must carry a VAT identifier for this category to be legible. */
  public boolean requiresBothVatIdentifiers() {
    return this == REVERSE_CHARGE || this == INTRA_COMMUNITY;
  }

  /**
   * Refuses a rate this category cannot carry.
   *
   * @throws EInvoiceException {@link ErrorCodes#TAX_CATEGORY_UNKNOWN} when a zero-rate category
   *     carries a non-zero rate, or the standard rate carries zero
   */
  public void requireRate(BigDecimal percentage) {
    if (percentage == null) {
      throw new EInvoiceException(
          ErrorCodes.TAX_CATEGORY_UNKNOWN, "a tax category needs a rate to go with it");
    }
    if (zeroRated && percentage.signum() != 0) {
      throw new EInvoiceException(
          ErrorCodes.TAX_CATEGORY_UNKNOWN,
          "tax category "
              + untdid5305
              + " is written with a rate of zero; a non-zero rate on it is a VAT position this"
              + " module will not assert on the seller's behalf");
    }
    if (!zeroRated && percentage.signum() == 0) {
      throw new EInvoiceException(
          ErrorCodes.TAX_CATEGORY_UNKNOWN,
          "a rate of zero under the standard category is refused: a zero-rated line carries one of"
              + " the zero-rate categories (Z, E, AE, K, G, O) with an exemption reason, so the"
              + " recipient knows which rule it was zero-rated under");
    }
  }

  /**
   * Refuses a category with no exemption reason where EN 16931 requires one (D-14).
   *
   * @throws EInvoiceException {@link ErrorCodes#EXEMPTION_REASON_REQUIRED}
   */
  public void requireReason(VatexCode reason) {
    if (reasonRequired && reason == null) {
      throw new EInvoiceException(
          ErrorCodes.EXEMPTION_REASON_REQUIRED,
          "tax category "
              + untdid5305
              + " needs a VATEX exemption reason code: a recipient rejects a zero-rated line that"
              + " does not say which rule zero-rated it");
    }
    if (!reasonRequired && reason != null) {
      throw new EInvoiceException(
          ErrorCodes.EXEMPTION_REASON_REQUIRED,
          "tax category " + untdid5305 + " is not an exemption and carries no exemption reason");
    }
  }

  /**
   * @param code a UNTDID 5305 code
   * @return the matching category
   * @throws EInvoiceException {@link ErrorCodes#TAX_CATEGORY_UNKNOWN} for a code outside the set EN
   *     16931 restricts UNTDID 5305 to
   */
  public static TaxCategory of(String code) {
    for (TaxCategory category : values()) {
      if (category.untdid5305.equals(code)) {
        return category;
      }
    }
    throw new EInvoiceException(
        ErrorCodes.TAX_CATEGORY_UNKNOWN,
        "EN 16931 restricts the VAT category to S, Z, E, AE, K, G and O");
  }
}
