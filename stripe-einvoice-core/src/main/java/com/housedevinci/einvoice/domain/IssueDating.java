package com.housedevinci.einvoice.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The dates of one numbered document, fixed under the series lock when its number is given
 * (D-PS1-27, design 5.1 and 5.6). Immutable on the row and chained on every event of the number.
 *
 * <p>For an invoice ({@code 380}) the issue date (BT-2) is the <b>numbering day</b>: the database
 * instant read after the series row lock, as a date in the seller's tax zone. The sale's own date
 * travels separately, as the supply date (BT-72) or the invoicing period (BG-14), so a late number
 * carries a true issue date and still says when the sale happened.
 *
 * <p>A credit note ({@code 381}) carries only its issue date in this edition; the rest is empty.
 *
 * @param issueDate BT-2
 * @param allocatedAt the database instant the issue date was derived from (microseconds)
 * @param supplyDate BT-72: the day of Stripe's {@code finalized_at} in the tax zone, a proxy for
 *     the supply date, absent when a period is carried instead or for a credit note
 * @param supplyPeriod BG-14, when every line names the same service period
 * @param previousNumber the number given just before this one in the same series, read under the
 *     lock; absent for the first number of a series
 * @param previousIssueDate the series' latest issue date before this number, read under the lock
 * @param cause why the issue day is later than the supply day; absent for a credit note
 */
public record IssueDating(
    LocalDate issueDate,
    Instant allocatedAt,
    Optional<LocalDate> supplyDate,
    Optional<SupplyPeriod> supplyPeriod,
    Optional<String> previousNumber,
    Optional<LocalDate> previousIssueDate,
    Optional<DateGapCause> cause) {

  public IssueDating {
    if (issueDate == null || allocatedAt == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "a numbered document needs its issue date and allocation instant");
    }
    allocatedAt = Timestamps.toStorage(allocatedAt);
    supplyDate = supplyDate == null ? Optional.empty() : supplyDate;
    supplyPeriod = supplyPeriod == null ? Optional.empty() : supplyPeriod;
    previousNumber =
        previousNumber == null ? Optional.empty() : previousNumber.filter(n -> !n.isEmpty());
    previousIssueDate = previousIssueDate == null ? Optional.empty() : previousIssueDate;
    cause = cause == null ? Optional.empty() : cause;
    if (supplyDate.isPresent() && supplyPeriod.isPresent()) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "a document carries a delivery date or an invoicing period, not both");
    }
  }

  /** The dating of a credit note: its issue date only (the 381 path is unchanged here). */
  public static IssueDating ofCreditNote(LocalDate issueDate, Instant allocatedAt) {
    return new IssueDating(
        issueDate,
        allocatedAt,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /** The first day of the supply: the delivery date, or the start of the period. */
  public Optional<LocalDate> supplyStart() {
    return supplyDate.or(() -> supplyPeriod.map(SupplyPeriod::start));
  }

  /**
   * Whole days from {@code day} to the issue date, never below zero. The allocator measures
   * lateness from the day Stripe finalised the invoice, never from a period's end: Stripe bills a
   * subscription at the start of its period, so the end can lie in the future (D-PR36-1).
   */
  public long daysAfter(LocalDate day) {
    return Math.max(0, ChronoUnit.DAYS.between(day, issueDate));
  }

  /**
   * The dating as one comparable string, for the verifier's cross-check of a row against its
   * chained event (D-PR36-2). Empty for a row or event written before 0.2.0.
   */
  public static String identity(Optional<IssueDating> dating) {
    return dating
        .map(
            d ->
                d.allocatedAt()
                    + "/"
                    + d.issueDate()
                    + "/"
                    + d.supplyDate().map(Object::toString).orElse("")
                    + "/"
                    + d.supplyPeriod().map(p -> p.start() + ".." + p.end()).orElse("")
                    + "/"
                    + d.previousNumber().orElse("")
                    + "/"
                    + d.previousIssueDate().map(Object::toString).orElse("")
                    + "/"
                    + d.cause().map(DateGapCause::wire).orElse(""))
        .orElse("");
  }
}
