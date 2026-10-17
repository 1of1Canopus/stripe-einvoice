package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.application.IssuanceReader;
import com.housedevinci.einvoice.application.NumberAllocator;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.SeriesReport;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * What a host application calls: give this Stripe invoice its legal number.
 *
 * <p>The allocator dates the number itself: the issue date is the day the number is given, on the
 * database clock, in the seller profile's declared tax zone, and a resetting series takes that
 * day's fiscal year (D-PS1-27, D-PS1-41). This adds the seller, the series and the mode, and passes
 * {@code finalized_at} as the supply date. A host that calls the allocator inside its own
 * transaction retries {@code DEI-131} and a retryable {@code DEI-132} in a new transaction.
 *
 * <p>The webhook path and a host's direct call go through this same object. The webhook is not a
 * privileged path.
 */
public class IssuanceNumberingService {

  private final NumberAllocator allocator;
  private final IssuanceReader reader;
  private final EInvoiceProperties properties;
  private final ZoneId taxZone;
  private final Mode mode;

  public IssuanceNumberingService(
      NumberAllocator allocator, IssuanceReader reader, EInvoiceProperties properties) {
    this.allocator = allocator;
    this.reader = reader;
    this.properties = properties;
    this.taxZone = ZoneId.of(properties.getSeller().getTaxZone());
    this.mode = Mode.of(properties.getMode());
  }

  /**
   * @param issuedAt Stripe's own {@code status_transitions.finalized_at}: the supply date (BT-72).
   *     The issue date (BT-2) is the day the number is given, never this value
   */
  public Issuance allocate(
      String stripeInvoiceId, String stripeAccountId, String stripeNumber, Instant issuedAt) {
    if (issuedAt == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "the supply date comes from the finalisation timestamp of the invoice itself, never"
              + " from the current time");
    }
    // The issue date is the day the number is given and the fiscal year is that day's: the
    // allocator fixes both under the series lock (D-PS1-27, D-PS1-41). issuedAt is the supply date.
    return allocator.allocate(
        new AllocationRequest(
            seriesKeyFor(issuedAt).scope(),
            stripeInvoiceId,
            stripeAccountId,
            stripeNumber,
            issuedAt,
            ""));
  }

  public Optional<Issuance> find(String stripeInvoiceId) {
    return reader.findBySource(properties.getSeller().getId(), mode, stripeInvoiceId);
  }

  /** Every number allocated in the series that {@code anyDateInTheYear} falls in. */
  public SeriesReport report(Instant anyDateInTheYear) {
    return reader.seriesReport(seriesKeyFor(anyDateInTheYear));
  }

  public SeriesKey seriesKeyFor(Instant issuedAt) {
    int fiscalYear =
        properties.getNumbering().isFiscalYearReset()
            ? issuedAt.atZone(taxZone).getYear()
            : SeriesKey.CONTINUOUS;
    return new SeriesKey(
        properties.getSeller().getId(), properties.getNumbering().getSeries(), fiscalYear, mode);
  }

  public ZoneId taxZone() {
    return taxZone;
  }

  public Mode mode() {
    return mode;
  }
}
