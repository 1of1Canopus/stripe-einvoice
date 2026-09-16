package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;

/**
 * BT-121, the VAT exemption reason code, from the VATEX code list EN 16931 references (D-14).
 *
 * <p>Each constant carries the legal reference the code stands for, and that text is what BT-120
 * (the reason in words) is written from. The pair exists because a recipient's system reads the
 * code and a recipient's accountant reads the text, and they must not be able to disagree: one
 * value, written twice, from one place.
 *
 * <p>This is the subset the rule packs in this edition can reach. A treatment outside it is a
 * refusal at mapping time (D-14): "never infer" means an exemption this module cannot name is an
 * exemption it does not write.
 *
 * <p>Source: the VATEX code list published with EN 16931 by CEF/DIGIT, read 2026-09-16 at {@code
 * https://docs.peppol.eu/poacc/billing/3.0/codelist/vatex/}.
 */
public enum VatexCode {

  /** Intra-Community supply of goods. */
  VATEX_EU_IC("VATEX-EU-IC", "Intra-Community supply", TaxCategory.INTRA_COMMUNITY),

  /** Reverse charge under Article 194 of Council Directive 2006/112/EC. */
  VATEX_EU_AE("VATEX-EU-AE", "Reverse charge", TaxCategory.REVERSE_CHARGE),

  /** Export outside the EU, Article 146 of Council Directive 2006/112/EC. */
  VATEX_EU_G("VATEX-EU-G", "Export outside the EU", TaxCategory.EXPORT),

  /** Article 132 of Council Directive 2006/112/EC: exemptions in the public interest. */
  VATEX_EU_132(
      "VATEX-EU-132",
      "Exempt based on article 132 of Council Directive 2006/112/EC",
      TaxCategory.EXEMPT),

  /** Article 143 of Council Directive 2006/112/EC: exemptions on importation. */
  VATEX_EU_143(
      "VATEX-EU-143",
      "Exempt based on article 143 of Council Directive 2006/112/EC",
      TaxCategory.EXEMPT),

  /**
   * Article 148 of Council Directive 2006/112/EC: exemptions related to international transport.
   */
  VATEX_EU_148(
      "VATEX-EU-148",
      "Exempt based on article 148 of Council Directive 2006/112/EC",
      TaxCategory.EXEMPT),

  /** Article 151 of Council Directive 2006/112/EC: diplomatic and international bodies. */
  VATEX_EU_151(
      "VATEX-EU-151",
      "Exempt based on article 151 of Council Directive 2006/112/EC",
      TaxCategory.EXEMPT),

  /** Outside the scope of VAT: not a supply in the taxing jurisdiction at all. */
  VATEX_EU_O("VATEX-EU-O", "Not subject to VAT", TaxCategory.OUT_OF_SCOPE),

  /** Zero rated goods, where a member state applies a zero rate rather than an exemption. */
  VATEX_EU_Z("VATEX-EU-Z", "Zero rated goods", TaxCategory.ZERO_RATED);

  private final String code;
  private final String legalReference;
  private final TaxCategory category;

  VatexCode(String code, String legalReference, TaxCategory category) {
    this.code = code;
    this.legalReference = legalReference;
    this.category = category;
  }

  /** The code as it is written into BT-121. */
  public String code() {
    return code;
  }

  /** The reason in words, as it is written into BT-120. One value, two fields. */
  public String legalReference() {
    return legalReference;
  }

  /** The category this reason belongs to. A reason on the wrong category is a refusal. */
  public TaxCategory category() {
    return category;
  }

  /**
   * Refuses a reason code that does not belong to the category it was put on.
   *
   * @throws EInvoiceException {@link ErrorCodes#EXEMPTION_REASON_REQUIRED}
   */
  public void requireCategory(TaxCategory declared) {
    if (category != declared) {
      throw new EInvoiceException(
          ErrorCodes.EXEMPTION_REASON_REQUIRED,
          "exemption reason "
              + code
              + " belongs to tax category "
              + category.code()
              + ", not to "
              + declared.code());
    }
  }

  /**
   * @param code a VATEX code
   * @return the matching reason
   * @throws EInvoiceException {@link ErrorCodes#EXEMPTION_REASON_REQUIRED} for a code this edition
   *     cannot name
   */
  public static VatexCode of(String code) {
    for (VatexCode reason : values()) {
      if (reason.code.equals(code)) {
        return reason;
      }
    }
    throw new EInvoiceException(
        ErrorCodes.EXEMPTION_REASON_REQUIRED,
        "this edition writes a VAT exemption only under a VATEX reason code it can name; an"
            + " exemption it cannot name is not one it will assert on the seller's behalf");
  }
}
