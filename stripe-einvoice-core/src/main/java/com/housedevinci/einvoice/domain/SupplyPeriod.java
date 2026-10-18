package com.housedevinci.einvoice.domain;

import java.time.LocalDate;

/**
 * The invoicing period (EN 16931 BG-14: BT-73 start, BT-74 end), both days included.
 *
 * <p>Written instead of a single delivery date when every line of the invoice names the same
 * service period (design 5.5).
 */
public record SupplyPeriod(LocalDate start, LocalDate end) {

  public SupplyPeriod {
    if (start == null || end == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an invoicing period needs both its days");
    }
    if (end.isBefore(start)) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "an invoicing period ends on or after its start (BR-29)");
    }
  }
}
