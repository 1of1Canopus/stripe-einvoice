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
 * <p>The only thing this adds to the allocator is the decision the allocator must not make on its
 * own - which fiscal year a given invoice date belongs to. That is {@code finalized_at} converted
 * in the seller profile's declared tax zone, never {@code LocalDate.now()} and never {@code
 * ZoneId.systemDefault()}: an invoice finalised at 23:30 UTC on 31 December belongs to the year its
 * tax authority says it does, not to the one the container's clock suggests (D-15).
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
   * @param issuedAt BT-2: Stripe's own {@code status_transitions.finalized_at}, not a clock reading
   */
  public Issuance allocate(
      String stripeInvoiceId, String stripeAccountId, String stripeNumber, Instant issuedAt) {
    if (issuedAt == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "the invoice date (BT-2) comes from the finalisation timestamp of the invoice itself,"
              + " never from the current time");
    }
    return allocator.allocate(
        new AllocationRequest(
            seriesKeyFor(issuedAt), stripeInvoiceId, stripeAccountId, stripeNumber, issuedAt, ""));
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
