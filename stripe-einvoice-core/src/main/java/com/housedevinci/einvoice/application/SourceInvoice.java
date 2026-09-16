package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Identifiers;
import com.housedevinci.einvoice.domain.Totals;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * One invoice as the <b>authoritative API</b> reports it, never as the webhook payload carried it
 * (D-02).
 *
 * <p><b>Frozen fields only</b> (I-02). Stripe freezes the buyer onto the invoice at finalisation -
 * {@code customer_name}, {@code customer_email}, {@code customer_address}, {@code
 * customer_tax_ids}, and each line's {@code description} - precisely because the customer record
 * keeps moving afterwards. A legal invoice carries the buyer as at the issue date, so this record
 * holds the frozen values and an adapter that reads the live {@code customer} object for a field
 * that has a frozen counterpart is a defect with a test against it.
 *
 * <p><b>A truncated collection cannot be represented.</b> {@code linesComplete} is a parameter the
 * adapter must assert rather than a flag a reader must remember to check: an invoice whose lines
 * were paginated and not exhausted throws on construction, so no partial invoice exists anywhere
 * downstream. An invoice with more than one page of lines, mapped from a first page, produces a
 * document that balances on its scalars while itemising a fraction of the sale - the worst defect
 * this module can ship, invisible to schema, to schematron and to a small golden file.
 */
public record SourceInvoice(
    String id,
    String number,
    String accountId,
    boolean livemode,
    String currency,
    String status,
    Instant finalizedAt,
    SourceParty buyer,
    List<SourceLine> lines,
    List<Totals.Bucket> taxBuckets,
    List<SourceTaxTreatment> taxTreatments,
    long subtotalMinor,
    long taxMinor,
    long totalMinor,
    boolean linesComplete) {

  /**
   * A line of the invoice, with the text frozen onto it rather than the product's live name.
   *
   * <p>{@code quantity} is the upstream's own integer quantity. EN 16931 writes BT-131 (the line
   * net amount) as BT-129 x BT-146 / BT-149, and carrying the quantity lets the writer set BT-129
   * and BT-149 to it and BT-146 to this line's own net amount - exact for every line, with no
   * division and therefore no rounding difference. Dividing the amount by the quantity to invent a
   * unit price is the alternative, and it produces a document whose lines do not add up to its own
   * total on the first line where the division is not exact.
   */
  public record SourceLine(
      String id,
      String description,
      String taxRateId,
      long quantity,
      long netMinor,
      long grossMinor) {

    public SourceLine {
      if (quantity <= 0) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            "a line carries a quantity above zero (stripe field: lines.data.quantity)");
      }
    }
  }

  /**
   * What the upstream says about one tax rate, beyond the percentage the totals arithmetic needs.
   *
   * <p>This is where the EN 16931 tax category comes from, and D-14 is explicit about why: the
   * upstream computed and charged the tax, so our rule pack <em>checks</em> its answer and never
   * overrides it. {@code taxabilityReason} is the upstream's own word for why a line was taxed the
   * way it was ({@code standard_rated}, {@code reverse_charge}, {@code zero_rated}, {@code
   * customer_exempt}, {@code excluded_territory}, ...), and it is the field a category is derived
   * from rather than being inferred from the geography of the two parties.
   *
   * @param taxRateId the id the buckets and the lines both name
   * @param country the rate's own jurisdiction, ISO 3166-1 alpha-2, blank when the upstream has
   *     none
   * @param taxType the upstream's tax type, for example {@code vat}
   * @param taxabilityReason the upstream's reason, blank when it reports none
   */
  public record SourceTaxTreatment(
      String taxRateId, String country, String taxType, String taxabilityReason) {

    public SourceTaxTreatment {
      Identifiers.validate("tax rate id", taxRateId);
      country = country == null ? "" : country;
      taxType = taxType == null ? "" : taxType;
      taxabilityReason = taxabilityReason == null ? "" : taxabilityReason;
    }
  }

  /** The buyer, frozen onto the invoice at finalisation. */
  public record SourceParty(
      String name,
      String email,
      String line1,
      String line2,
      String postalCode,
      String city,
      String country,
      String taxId) {}

  public SourceInvoice {
    Identifiers.validate("stripe invoice id", id);
    if (!linesComplete) {
      throw new EInvoiceException(
          ErrorCodes.TRUNCATED_COLLECTION,
          "this invoice's lines were not read to exhaustion. A document built from a first page"
              + " balances on its totals while itemising part of the sale, so no such invoice is"
              + " constructed at all (stripe field: lines.has_more)");
    }
    if (currency == null || currency.length() != 3) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "the invoice carries no three-letter currency (stripe field: currency)");
    }
    currency = currency.toLowerCase(Locale.ROOT);
    status = status == null ? "" : status;
    number = number == null ? "" : number;
    accountId = accountId == null ? "" : accountId;
    lines = List.copyOf(lines);
    taxBuckets = List.copyOf(taxBuckets);
    taxTreatments = taxTreatments == null ? List.of() : List.copyOf(taxTreatments);
    if (buyer == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "the invoice carries no buyer (stripe fields: customer_name, customer_address)");
    }
  }

  /** True once Stripe has finalised the invoice; before that there is nothing to issue. */
  public boolean finalised() {
    return finalizedAt != null && !"draft".equals(status);
  }

  /** True when the invoice was voided upstream. A document already issued is never withdrawn. */
  public boolean voided() {
    return "void".equals(status);
  }

  /**
   * What the upstream said about one tax rate, for the mapper that has to name a category for it.
   *
   * @param taxRateId the rate a line or a breakdown group names
   * @return the treatment, or empty when the upstream reported none - which the mapper turns into a
   *     refusal naming the field rather than into a guess
   */
  public java.util.Optional<SourceTaxTreatment> treatmentOf(String taxRateId) {
    return taxTreatments.stream().filter(t -> t.taxRateId().equals(taxRateId)).findFirst();
  }

  /**
   * The recomputation input, so the totals check reads one shape wherever the invoice came from.
   */
  public Totals.Input totals() {
    return new Totals.Input(
        lines.stream()
            .map(l -> new Totals.Line(l.id(), l.taxRateId(), l.netMinor(), l.grossMinor()))
            .toList(),
        taxBuckets,
        subtotalMinor,
        taxMinor,
        totalMinor);
  }
}
