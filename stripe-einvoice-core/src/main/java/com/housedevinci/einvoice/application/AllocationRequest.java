package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Identifiers;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What the allocator is asked for: one number, for one Stripe invoice, in one series.
 *
 * <p>Every field comes from an authoritative source. The seller and the mode are resolved from
 * Stripe's own {@code account} and {@code livemode}, never from payload metadata (D-01, D-10); the
 * fiscal year is derived from {@code issuedAt} in the seller profile's declared tax zone, never in
 * the JVM's default zone (D-15).
 *
 * @param issuedAt the instant BT-2 is derived from (Stripe's finalisation time for an invoice)
 * @param issueDate BT-2 itself: {@code issuedAt} as a calendar date in the seller's tax zone, as
 *     the caller derived it. The series records the latest one it numbered ({@code
 *     last_issue_date}), so it must be the same date the document carries, and only the caller
 *     knows the zone
 * @param stripeNumber Stripe's own invoice number, stored as a reference only (Decision 2)
 */
public record AllocationRequest(
    SeriesKey seriesKey,
    String stripeInvoiceId,
    String stripeAccountId,
    String stripeNumber,
    Instant issuedAt,
    LocalDate issueDate,
    String rulePackVersion) {

  public AllocationRequest {
    if (seriesKey == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an allocation needs a series key");
    }
    Identifiers.validate("stripe invoice id", stripeInvoiceId);
    if (issuedAt == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "an allocation needs the invoice date (BT-2), never a clock reading");
    }
    if (issueDate == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "an allocation needs BT-2 as a date in the seller's tax zone, never one derived here");
    }
    stripeAccountId = stripeAccountId == null ? "" : stripeAccountId;
    stripeNumber = stripeNumber == null ? "" : stripeNumber;
    rulePackVersion = rulePackVersion == null ? "" : rulePackVersion;
  }
}
