package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Identifiers;
import com.housedevinci.einvoice.domain.SeriesScope;
import java.time.Instant;
import java.util.Optional;

/**
 * What the allocator is asked for: one number, for one Stripe invoice, in one series scope.
 *
 * <p>Every field comes from an authoritative source. The seller and the mode are resolved from
 * Stripe's own {@code account} and {@code livemode}, never from payload metadata (D-01, D-10).
 *
 * <p><b>No date and no fiscal year (D-PS1-27, D-PS1-41).</b> The issue date (BT-2) of an invoice is
 * the day its number is given, read from the database clock under the series lock, and the fiscal
 * year of a resetting series is the year of that day. Neither exists before the lock is held, so a
 * caller cannot pass either. Nor can a caller pass why the number came late: the allocator derives
 * that from stored history only.
 *
 * @param scope the series, without its fiscal year
 * @param issuedAt Stripe's {@code status_transitions.finalized_at}: the source of the supply date
 *     (BT-72), never the issue date
 * @param servicePeriod the service period every line names, as Stripe's instants ({@code
 *     period.start}, {@code period.end}); empty when the lines name different periods or none
 * @param stripeNumber Stripe's own invoice number, stored as a reference only (Decision 2)
 */
public record AllocationRequest(
    SeriesScope scope,
    String stripeInvoiceId,
    String stripeAccountId,
    String stripeNumber,
    Instant issuedAt,
    Optional<ServicePeriod> servicePeriod,
    String rulePackVersion) {

  /**
   * One service period as Stripe reports it on a line: {@code start} included, {@code end}
   * excluded. The allocator turns both into days in the tax zone.
   */
  public record ServicePeriod(Instant start, Instant end) {

    public ServicePeriod {
      if (start == null || end == null || !end.isAfter(start)) {
        throw new EInvoiceException(
            ErrorCodes.INVALID, "a service period has a start and a later end");
      }
    }
  }

  public AllocationRequest {
    if (scope == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an allocation needs a series scope");
    }
    Identifiers.validate("stripe invoice id", stripeInvoiceId);
    if (issuedAt == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "an allocation needs the invoice's finalisation instant (the supply date's source)");
    }
    servicePeriod = servicePeriod == null ? Optional.empty() : servicePeriod;
    stripeAccountId = stripeAccountId == null ? "" : stripeAccountId;
    stripeNumber = stripeNumber == null ? "" : stripeNumber;
    rulePackVersion = rulePackVersion == null ? "" : rulePackVersion;
  }

  /** A request with no service period: the supply date is the finalisation day. */
  public AllocationRequest(
      SeriesScope scope,
      String stripeInvoiceId,
      String stripeAccountId,
      String stripeNumber,
      Instant issuedAt,
      String rulePackVersion) {
    this(
        scope,
        stripeInvoiceId,
        stripeAccountId,
        stripeNumber,
        issuedAt,
        Optional.empty(),
        rulePackVersion);
  }
}
