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
    long subtotalMinor,
    long taxMinor,
    long totalMinor,
    boolean linesComplete) {

  /** A line of the invoice, with the text frozen onto it rather than the product's live name. */
  public record SourceLine(
      String id, String description, String taxRateId, long netMinor, long grossMinor) {}

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
