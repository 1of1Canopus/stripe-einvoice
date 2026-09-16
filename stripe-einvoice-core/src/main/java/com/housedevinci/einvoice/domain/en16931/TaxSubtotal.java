package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Percentage;
import java.util.Optional;

/**
 * BG-23, one VAT breakdown group: everything on the document taxed at one category and rate.
 *
 * <p>The breakdown is the part of the totals a scalar comparison cannot stand in for (C17-17,
 * D-12). A document whose three headline amounts agree while one bucket is a cent out passes schema
 * and schematron at the recipient too, and is wrong.
 *
 * @param category BT-118
 * @param ratePercentage BT-119
 * @param taxableAmount BT-116
 * @param taxAmount BT-117
 * @param exemptionReason BT-121 and BT-120, required by the zero-rate categories
 */
public record TaxSubtotal(
    TaxCategory category,
    Percentage ratePercentage,
    Money taxableAmount,
    Money taxAmount,
    VatexCode exemptionReason) {

  public TaxSubtotal {
    if (category == null) {
      throw new EInvoiceException(
          ErrorCodes.TAX_CATEGORY_UNKNOWN, "a VAT breakdown group carries a category (BR-47)");
    }
    if (taxableAmount == null || taxAmount == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "a VAT breakdown group carries a taxable amount and a tax amount (BR-45, BR-46)");
    }
    category.requireRate(ratePercentage == null ? null : ratePercentage.value());
    category.requireReason(exemptionReason);
    if (exemptionReason != null) {
      exemptionReason.requireCategory(category);
    }
    if (category.isZeroRated() && !taxAmount.isZero()) {
      throw new EInvoiceException(
          ErrorCodes.DOCUMENT_UNBALANCED,
          "tax category " + category.code() + " carries a tax amount of zero");
    }
    if (taxableAmount.isNegative() || taxAmount.isNegative()) {
      throw new EInvoiceException(
          ErrorCodes.DOCUMENT_TYPE_UNSUPPORTED,
          "a VAT breakdown group carries positive amounts; the document type carries the sign");
    }
  }

  public Optional<VatexCode> exemptionReasonValue() {
    return Optional.ofNullable(exemptionReason);
  }
}
