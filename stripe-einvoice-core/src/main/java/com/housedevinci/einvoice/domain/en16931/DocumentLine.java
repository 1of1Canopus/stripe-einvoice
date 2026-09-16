package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Percentage;
import com.housedevinci.einvoice.domain.ScreenedText;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * BG-25, one invoice line.
 *
 * <p><b>Quantity, price and base quantity.</b> EN 16931 defines BT-131 (the line net amount) as
 * BT-129 (invoiced quantity) x BT-146 (item net price) / BT-149 (item price base quantity). This
 * module writes BT-129 and BT-149 as the same quantity and BT-146 as the line's own net amount,
 * which makes that identity exact for every line, with no division and no rounding anywhere. The
 * obvious alternative - dividing the amount by the quantity to get a unit price - introduces a
 * rounding difference on the first line where the division is not exact, and a document whose lines
 * do not add up to its own total is the one defect this module exists to refuse.
 *
 * @param id BT-126, unique within the document
 * @param quantity BT-129 and BT-149
 * @param unitCode BT-130, UN/ECE Recommendation 20. {@code C62} is "one, piece".
 * @param netAmount BT-131, exclusive of VAT
 * @param itemName BT-153
 * @param itemDescription BT-154, optional
 * @param category BT-151, the line's VAT category
 * @param ratePercentage BT-152, the line's VAT rate
 * @param exemptionReason BT-121 at line level, required by the zero-rate categories
 */
public record DocumentLine(
    String id,
    BigDecimal quantity,
    String unitCode,
    Money netAmount,
    String itemName,
    String itemDescription,
    TaxCategory category,
    Percentage ratePercentage,
    VatexCode exemptionReason) {

  /** UN/ECE Recommendation 20: one piece. What a subscription line is counted in. */
  public static final String UNIT_PIECE = "C62";

  public DocumentLine {
    id = ScreenedText.screen("line id", id, BusinessTerms.ID);
    itemName = ScreenedText.screen("item name", itemName, BusinessTerms.ITEM_NAME);
    itemDescription =
        itemDescription == null || itemDescription.isBlank()
            ? null
            : ScreenedText.screen(
                "item description", itemDescription, BusinessTerms.ITEM_DESCRIPTION);
    unitCode = ScreenedText.screen("unit code", unitCode, 8);
    if (quantity == null || quantity.signum() <= 0) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "a line carries a quantity above zero (stripe field: lines.data.quantity)");
    }
    if (quantity.scale() < 0 || quantity.scale() > 4) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
          "a quantity carries no negative scale and at most four decimal places");
    }
    if (quantity.precision() - quantity.scale() > 9) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS, "a quantity carries at most nine integer digits");
    }
    if (netAmount == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE, "a line carries a net amount (BT-131)");
    }
    if (netAmount.isNegative()) {
      throw new EInvoiceException(
          ErrorCodes.DOCUMENT_TYPE_UNSUPPORTED,
          "a negative line amount is refused: several profiles reject one outright, and a refund is"
              + " a credit note with positive amounts rather than an invoice with negative ones");
    }
    if (category == null) {
      throw new EInvoiceException(
          ErrorCodes.TAX_CATEGORY_UNKNOWN,
          "EN 16931 requires a VAT category on every line (BR-CO-04)");
    }
    category.requireRate(ratePercentage == null ? null : ratePercentage.value());
    category.requireReason(exemptionReason);
    if (exemptionReason != null) {
      exemptionReason.requireCategory(category);
    }
  }

  public Optional<String> itemDescriptionValue() {
    return Optional.ofNullable(itemDescription);
  }

  public Optional<VatexCode> exemptionReasonValue() {
    return Optional.ofNullable(exemptionReason);
  }

  /** The key a VAT breakdown group is formed on: one bucket per category and rate (BG-23). */
  public TaxKey taxKey() {
    return new TaxKey(category, ratePercentage, exemptionReason);
  }

  /** The (category, rate, reason) triple that identifies one VAT breakdown group. */
  public record TaxKey(TaxCategory category, Percentage rate, VatexCode reason) {}
}
