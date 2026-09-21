package com.housedevinci.einvoice.adapter.en16931;

import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.adapter.xml.UblProfileRequirements;
import com.housedevinci.einvoice.application.MappingInput;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Percentage;
import com.housedevinci.einvoice.domain.Totals;
import com.housedevinci.einvoice.domain.en16931.BusinessTerms;
import com.housedevinci.einvoice.domain.en16931.Contact;
import com.housedevinci.einvoice.domain.en16931.DocumentLine;
import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;
import com.housedevinci.einvoice.domain.en16931.Money;
import com.housedevinci.einvoice.domain.en16931.Party;
import com.housedevinci.einvoice.domain.en16931.PartyIdentifier;
import com.housedevinci.einvoice.domain.en16931.PostalAddress;
import com.housedevinci.einvoice.domain.en16931.SellerProfile;
import com.housedevinci.einvoice.domain.en16931.TaxCategory;
import com.housedevinci.einvoice.domain.en16931.TaxSubtotal;
import com.housedevinci.einvoice.domain.en16931.UnnumberedInvoice;
import com.housedevinci.einvoice.domain.en16931.VatIdentifier;
import com.housedevinci.einvoice.domain.en16931.VatexCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the authoritative invoice onto the EN 16931 semantic model.
 *
 * <p>Two rules run through every line of this class.
 *
 * <p><b>A field EN 16931 requires and the upstream does not have is a refusal naming the upstream
 * field</b>, never a blank element and never a placeholder. A document with an empty buyer city
 * passes a whitespace-insensitive schema check and fails a business rule at the recipient, days
 * later, where nobody can fix it.
 *
 * <p><b>Nothing the buyer or a dashboard user controls selects anything</b> (D-10). Metadata,
 * custom fields and the memo are not read at all in this edition. The seller comes from
 * configuration; the buyer's own fields are screened input to specific business terms and select no
 * profile, series, rule pack or address.
 *
 * <p>The buyer is taken from the fields the upstream <b>froze onto the invoice</b> at finalisation
 * (I-02), never from the live customer record, because a legal invoice carries the buyer as at the
 * issue date and the customer record keeps moving afterwards.
 */
public final class StripeInvoiceMapper {

  private final SellerProfile seller;
  private final UblProfile profile;
  private final PartyIdentifier configuredBuyerElectronicAddress;

  /**
   * @param seller the configured issuing party
   * @param profile the target profile, which decides which fields are mandatory
   * @param configuredBuyerElectronicAddress BT-49 when it cannot be derived from the buyer's VAT
   *     identifier; {@code null} when none is configured
   */
  public StripeInvoiceMapper(
      SellerProfile seller, UblProfile profile, PartyIdentifier configuredBuyerElectronicAddress) {
    this.seller = java.util.Objects.requireNonNull(seller, "seller");
    this.profile = java.util.Objects.requireNonNull(profile, "profile");
    this.configuredBuyerElectronicAddress = configuredBuyerElectronicAddress;
  }

  /**
   * Every screen, every required field and every balance invariant - and not one thing that needs
   * the legal number.
   *
   * <p>This is the body the preflight runs and the body the render runs. The render calls {@link
   * UnnumberedInvoice#numbered} on the result; the preflight discards it. "The preflight checks
   * less than the render does" is therefore not expressible here.
   *
   * @param input the issue date, the series and the authoritative invoice
   * @return the semantic model minus BT-1, balanced by its own constructor
   */
  public UnnumberedInvoice map(MappingInput input) {
    SourceInvoice source = input.invoice();
    String currency = source.currency();
    Party buyer = buyer(source);
    Party sellerParty = seller.party();

    List<TaxSubtotal> subtotals = new ArrayList<>();
    Map<String, TaxTreatmentRules.Treatment> byRate = new LinkedHashMap<>();
    Map<String, Long> taxableByRate = taxableByRate(source);
    for (Totals.Bucket bucket : source.taxBuckets()) {
      TaxTreatmentRules.Treatment treatment =
          TaxTreatmentRules.of(source, bucket.taxRateId(), bucket.percentage(), sellerParty, buyer);
      byRate.put(bucket.taxRateId(), treatment);
      long taxable = taxableByRate.getOrDefault(bucket.taxRateId(), 0L);
      subtotals.add(
          new TaxSubtotal(
              treatment.category(),
              rateOf(treatment.category(), bucket.percentage()),
              Money.ofMinor(taxable, currency),
              Money.ofMinor(bucket.taxMinor(), currency),
              treatment.reason()));
    }

    List<DocumentLine> lines = new ArrayList<>();
    for (SourceInvoice.SourceLine line : source.lines()) {
      TaxTreatmentRules.Treatment treatment = byRate.get(line.taxRateId());
      if (treatment == null) {
        throw new EInvoiceException(
            ErrorCodes.TAX_CATEGORY_UNKNOWN,
            "a line names a tax rate the invoice's own breakdown does not list, so no category can"
                + " be written for it (stripe field: lines.data.taxes.tax_rate_details.tax_rate)");
      }
      Percentage rate = rateOfLine(source, line.taxRateId());
      lines.add(
          new DocumentLine(
              line.id(),
              BigDecimal.valueOf(line.quantity()),
              DocumentLine.UNIT_PIECE,
              Money.ofMinor(line.netMinor(), currency),
              itemName(line),
              null,
              treatment.category(),
              rateOf(treatment.category(), rate),
              treatment.reason()));
    }

    Money lineTotal = Money.ofMinor(source.subtotalMinor(), currency);
    Money taxTotal = Money.ofMinor(source.taxMinor(), currency);
    Money inclusive = Money.ofMinor(source.totalMinor(), currency);

    UnnumberedInvoice document =
        new UnnumberedInvoice(
            input.issueDate(),
            null,
            DocumentTypeCode.COMMERCIAL_INVOICE,
            currency,
            buyerReference(),
            null,
            null,
            null,
            source.number().isBlank() ? null : source.number(),
            sellerParty,
            buyer,
            seller.payment(),
            lines,
            subtotals,
            lineTotal,
            lineTotal,
            taxTotal,
            inclusive,
            inclusive);

    // The profile's presence rules, run here rather than only in the writer (D5-01). BT-49 is
    // derived from the buyer's own VAT identifier and BR-DE-8 from their frozen address, so a
    // rule that lives only downstream of the allocator charges the buyer's missing tax id one
    // legal number. Same body as the writer's, on the document this mapper is about to return.
    UblProfileRequirements.require(
        profile,
        document.buyerReferenceValue(),
        document.paymentValue(),
        document.seller(),
        document.buyer(),
        document.hasReverseChargeOrIntraCommunity());
    return document;
  }

  /**
   * The buyer reference (BT-10). It comes from configuration, and from nothing the buyer or a
   * dashboard user can write.
   *
   * <p>Reading it from an invoice's metadata or a checkout custom field is the obvious next feature
   * and is deliberately absent: those are fields the <em>buyer</em> controls, and letting one of
   * them reach a business term needs an explicit allowlist with bounded values that the spec does
   * not describe (D-10). Adding one is a mechanism, not a mapping, so it is a numbered question
   * rather than a quiet extra branch here.
   */
  private String buyerReference() {
    return seller.defaultBuyerReferenceValue().orElse(null);
  }

  private static String itemName(SourceInvoice.SourceLine line) {
    String description = line.description();
    if (description == null || description.isBlank()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "a line carries no description, and EN 16931 requires an item name on every line"
              + " (BT-153, BR-25) (stripe field: lines.data.description)");
    }
    if (description.length() > BusinessTerms.ITEM_NAME) {
      // Truncating would change what the seller said they sold. The refusal names the field.
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "a line description is longer than the "
              + BusinessTerms.ITEM_NAME
              + " characters EN 16931 allows for an item name, and truncating it would change what"
              + " the seller said they sold (stripe field: lines.data.description)");
    }
    return description;
  }

  /** The percentage EN 16931 writes for a category: zero for every zero-rate category. */
  private static Percentage rateOf(TaxCategory category, Percentage upstream) {
    if (category.isZeroRated()) {
      return Percentage.of("0");
    }
    if (upstream == null) {
      throw new EInvoiceException(
          ErrorCodes.TAX_CATEGORY_UNKNOWN,
          "a standard-rated line carries no rate (stripe field: tax_rate.percentage)");
    }
    return upstream;
  }

  private static Percentage rateOfLine(SourceInvoice source, String taxRateId) {
    for (Totals.Bucket bucket : source.taxBuckets()) {
      if (bucket.taxRateId().equals(taxRateId)) {
        return bucket.percentage();
      }
    }
    return null;
  }

  /** The taxable base per rate, summed from the lines, in minor units. */
  private static Map<String, Long> taxableByRate(SourceInvoice source) {
    Map<String, Long> map = new LinkedHashMap<>();
    for (SourceInvoice.SourceLine line : source.lines()) {
      map.merge(line.taxRateId(), line.netMinor(), Math::addExact);
    }
    return map;
  }

  private Party buyer(SourceInvoice source) {
    SourceInvoice.SourceParty frozen = source.buyer();
    PostalAddress address =
        new PostalAddress(
            frozen.line1(),
            frozen.line2(),
            frozen.city(),
            frozen.postalCode(),
            null,
            requireBuyerCountry(frozen));
    VatIdentifier vat =
        frozen.taxId() == null || frozen.taxId().isBlank()
            ? null
            : VatIdentifier.parse(
                "the buyer's VAT identifier (stripe field: customer_tax_ids)", frozen.taxId());
    return new Party(
        requireBuyerName(frozen),
        null,
        address,
        vat,
        null,
        null,
        buyerElectronicAddress(vat, address.country()),
        null);
  }

  /**
   * BT-49. Derived from the buyer's VAT identifier when this module knows the Peppol scheme for its
   * issuing country, configured otherwise, absent when neither - which the writer turns into a
   * refusal for the Peppol profile and accepts for XRechnung, where the term is optional.
   */
  private PartyIdentifier buyerElectronicAddress(VatIdentifier vat, String country) {
    if (configuredBuyerElectronicAddress != null) {
      return configuredBuyerElectronicAddress;
    }
    if (vat == null) {
      return null;
    }
    String scheme = PartyIdentifier.VAT_ELECTRONIC_ADDRESS_SCHEMES.get(vat.issuingCountry());
    if (scheme == null) {
      return null;
    }
    return new PartyIdentifier(scheme, vat.value());
  }

  private static String requireBuyerName(SourceInvoice.SourceParty frozen) {
    if (frozen.name() == null || frozen.name().isBlank()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "EN 16931 requires the buyer's name (BT-44, BR-07) and the invoice carries none (stripe"
              + " field: customer_name)");
    }
    return frozen.name();
  }

  private static String requireBuyerCountry(SourceInvoice.SourceParty frozen) {
    if (frozen.country() == null || frozen.country().isBlank()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "EN 16931 requires the buyer's country (BT-55, BR-11) and the invoice carries none"
              + " (stripe field: customer_address.country)");
    }
    return frozen.country();
  }

  /** The seller contact, exposed so a startup check can report what is configured. */
  public Optional<Contact> sellerContact() {
    return seller.party().contactValue();
  }

  /** The profile this mapper maps for. */
  public UblProfile profile() {
    return profile;
  }

  /** The reason code this mapper would write for an unmapped treatment: none, by design. */
  public static Optional<VatexCode> noReason() {
    return Optional.empty();
  }
}
