package com.housedevinci.einvoice.domain;

/**
 * Why an invoice was numbered on a later day than its supply date (Q32 (iii), design 5.6).
 *
 * <p>Derived by the allocator from <b>stored</b> history only: the inbound rows of the invoice,
 * their park and retry marks, the reprocess ledger and the reconciliation source. No caller passes
 * a cause, and {@code AllocationRequest} has no field for one (an ArchUnit rule holds that).
 *
 * <p>When several apply, the first in this order wins: {@link #REPROCESS}, {@link #SWEEP}, {@link
 * #PARK}, {@link #RETRY}, {@link #LATE_WEBHOOK}, then {@link #UNRECORDED}.
 */
public enum DateGapCause {
  /** Numbered on the supply day itself. */
  NONE,
  /** An operator re-opened a refused event before it was numbered. */
  REPROCESS,
  /** The reconciliation sweep found the invoice and re-enqueued it. */
  SWEEP,
  /** The event was parked (the invoice was not finalised yet when it was first read). */
  PARK,
  /** A fetch or an allocation failed and was retried with backoff. */
  RETRY,
  /** The first event for the invoice reached this module on a later day than the supply day. */
  LATE_WEBHOOK,
  /**
   * A later day with none of the causes above in the stored history: a worker queue that crossed
   * midnight, or a host that called the allocator directly for an older invoice.
   */
  UNRECORDED;

  /** The value as the database stores it. */
  public String wire() {
    return name();
  }

  public static DateGapCause of(String wire) {
    for (DateGapCause cause : values()) {
      if (cause.name().equals(wire)) {
        return cause;
      }
    }
    throw new EInvoiceException(ErrorCodes.INVALID, "not a date-gap cause this module records");
  }
}
