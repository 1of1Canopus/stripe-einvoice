package com.housedevinci.einvoice.adapter.en16931;

import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Percentage;
import com.housedevinci.einvoice.domain.en16931.CountryCode;
import com.housedevinci.einvoice.domain.en16931.Party;
import com.housedevinci.einvoice.domain.en16931.TaxCategory;
import com.housedevinci.einvoice.domain.en16931.VatexCode;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns the upstream's own tax treatment into an EN 16931 category and a VATEX reason (D-14).
 *
 * <p><b>This checks; it never overrides.</b> Stripe computed the tax and charged it, so the
 * category is derived from Stripe's own {@code taxability_reason} rather than inferred from the
 * geography of the two parties. Where the derived category and the geography disagree - a reverse
 * charge to a buyer in the seller's own country, an intra-Community supply to a buyer outside the
 * EU VAT area - the result is a <b>refusal naming both</b>, never a quiet correction of one to fit
 * the other. Deciding, on the seller's behalf, that the tax they charged was wrong is not something
 * a library gets to do silently.
 *
 * <p>A treatment this table cannot name is a refusal too: "never infer" means an exemption this
 * module cannot cite is an exemption it does not write. The refusal names the Stripe field so the
 * operator knows where to look.
 *
 * <p>The pack has an id and a version, both recorded on the issuance and inside the hashed material
 * by the unit of work, so a re-validation years from now reads the pack that was in force.
 */
public final class TaxTreatmentRules {

  /** The pack's id, written into the issuance row beside the version. */
  public static final String PACK_ID = "en16931-eu-vat";

  /**
   * The pack's version. Bumped whenever a row below changes, never silently: the version is part of
   * what a re-validation years from now reads back.
   */
  public static final String PACK_VERSION = "2026.09";

  private TaxTreatmentRules() {}

  /** The category and the reason for one tax rate, as a pair that is always consistent. */
  public record Treatment(TaxCategory category, VatexCode reason) {}

  /**
   * @param invoice the authoritative invoice
   * @param taxRateId the rate the line or the breakdown group names
   * @param percentage the rate, already bounded
   * @param seller the issuing party
   * @param buyer the buyer as this document will carry them
   * @throws EInvoiceException {@link ErrorCodes#TAX_CATEGORY_UNKNOWN} when the upstream reported no
   *     reason this pack can name, {@link ErrorCodes#EXEMPTION_REASON_REQUIRED} when the reason and
   *     the geography disagree
   */
  public static Treatment of(
      SourceInvoice invoice, String taxRateId, Percentage percentage, Party seller, Party buyer) {
    Optional<SourceInvoice.SourceTaxTreatment> upstream = invoice.treatmentOf(taxRateId);
    if (upstream.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.TAX_CATEGORY_UNKNOWN,
          "the invoice reports no tax treatment for one of its own rates, so no EN 16931 category"
              + " can be established for it and none is invented (stripe field:"
              + " lines.data.taxes.taxability_reason)");
    }
    String reason = upstream.get().taxabilityReason().toLowerCase(Locale.ROOT);
    boolean zeroRate = percentage == null || percentage.value().signum() == 0;

    if (!zeroRate) {
      if (!reason.isEmpty()
          && !"standard_rated".equals(reason)
          && !"proportionally_rated".equals(reason)) {
        throw new EInvoiceException(
            ErrorCodes.TAX_CATEGORY_UNKNOWN,
            "a rate above zero arrived with the taxability reason '"
                + reason
                + "', which is not one this pack writes as a standard-rated line. The document is"
                + " refused rather than written under a category the seller did not charge under"
                + " (stripe field: lines.data.taxes.taxability_reason)");
      }
      return new Treatment(TaxCategory.STANDARD, null);
    }

    return switch (reason) {
      case "reverse_charge" -> {
        requireCrossBorderInEu(seller, buyer, "reverse charge");
        yield new Treatment(TaxCategory.REVERSE_CHARGE, VatexCode.VATEX_EU_AE);
      }
      case "customer_exempt" -> new Treatment(TaxCategory.EXEMPT, VatexCode.VATEX_EU_132);
      case "zero_rated" -> new Treatment(TaxCategory.ZERO_RATED, VatexCode.VATEX_EU_Z);
      case "excluded_territory", "not_subject_to_tax", "not_collecting", "not_supported" ->
          new Treatment(TaxCategory.OUT_OF_SCOPE, VatexCode.VATEX_EU_O);
      case "portion_product_exempt", "portion_reduced_rated", "portion_standard_rated" ->
          throw new EInvoiceException(
              ErrorCodes.TAX_CATEGORY_UNKNOWN,
              "this invoice splits one line across several tax treatments, which EN 16931 models as"
                  + " several lines and this edition does not yet split. Refused rather than"
                  + " written under whichever treatment happened to be read last (stripe field:"
                  + " lines.data.taxes.taxability_reason)");
      default ->
          throw new EInvoiceException(
              ErrorCodes.TAX_CATEGORY_UNKNOWN,
              "a zero rate arrived with a taxability reason this rule pack cannot name, so the"
                  + " VAT position it stands for cannot be cited on the document. A zero-rated line"
                  + " with no exemption reason is rejected by the recipient anyway (stripe field:"
                  + " lines.data.taxes.taxability_reason; rule pack: "
                  + PACK_ID
                  + " "
                  + PACK_VERSION
                  + ")");
    };
  }

  /**
   * A reverse charge is a cross-border supply inside the EU VAT area. The check exists because the
   * upstream's reason and the parties' countries are two independent facts, and a document where
   * they disagree is one somebody has to explain to a tax authority.
   */
  private static void requireCrossBorderInEu(Party seller, Party buyer, String what) {
    String sellerCountry = seller.country();
    String buyerCountry = buyer.country();
    if (sellerCountry.equals(buyerCountry)) {
      throw new EInvoiceException(
          ErrorCodes.EXEMPTION_REASON_REQUIRED,
          "the upstream reports a "
              + what
              + " while both parties are established in the same country. This module does not"
              + " decide which of the two is wrong; it refuses (stripe fields: customer_address,"
              + " lines.data.taxes.taxability_reason)");
    }
    if (!CountryCode.isEuVatArea(sellerCountry) || !CountryCode.isEuVatArea(buyerCountry)) {
      throw new EInvoiceException(
          ErrorCodes.EXEMPTION_REASON_REQUIRED,
          "the upstream reports a "
              + what
              + " while one of the parties is outside the EU VAT area, where a supply is an export"
              + " (category G) under a different rule. Refused rather than recategorised");
    }
  }
}
