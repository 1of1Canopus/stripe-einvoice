package com.housedevinci.einvoice.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Every number allocated in one series, with its disposition. This is the page an auditor reads, so
 * it is written for that reader: an unexplained hole and a hole with a chained reason must never
 * look alike (numbering design, deviation-3 ruling).
 *
 * <p>Each line carries the issue date (BT-2), the supply date or period, the allocation time and
 * the cause of any day between supply and issue. From 0.2.0 an invoice's BT-2 is the day its number
 * was given, so issue dates go up with the numbers by construction; an invoice finalised on 31
 * December and numbered on 2 January is dated 2 January, in the new year's series, and carries the
 * December day as its supply date (D-PS1-27, D-PS1-41). Lines from 0.1.x keep a computed
 * out-of-order flag, because their BT-2 was the finalisation day (N-08).
 */
public record SeriesReport(SeriesKey seriesKey, List<Line> lines, long openCount, long nextNumber) {

  public SeriesReport {
    lines = List.copyOf(lines);
  }

  /**
   * @param state the disposition: ISSUED, VOID_UNUSED, or an allocation still open
   * @param issuedAt Stripe's finalisation instant (the supply date's source; BT-2 before 0.2.0)
   * @param allocatedAt when this module consumed the counter value (the database clock)
   * @param issueDate BT-2 as the document carries it: the numbering day from 0.2.0, the day of
   *     {@code issuedAt} on an older line
   * @param dating the dates the number was given with; empty on a line from before 0.2.0
   * @param dateOutOfOrder true when this line's BT-2 is earlier than a BT-2 of a lower number in
   *     the same series. Only an older line can show it: 0.1.x dated an invoice with {@code
   *     finalized_at}, so a late number carried an earlier date (D-PS1-27)
   */
  public record Line(
      long counter,
      String legalNumber,
      IssuanceState state,
      String stripeInvoiceId,
      Instant issuedAt,
      Instant allocatedAt,
      String voidReason,
      String voidRuleId,
      LocalDate issueDate,
      Optional<IssueDating> dating,
      boolean dateOutOfOrder) {

    public Line {
      dating = dating == null ? Optional.empty() : dating;
    }

    /** A line read without its dates, as reports before 0.2.0 had them. */
    public Line(
        long counter,
        String legalNumber,
        IssuanceState state,
        String stripeInvoiceId,
        Instant issuedAt,
        Instant allocatedAt,
        String voidReason,
        String voidRuleId) {
      this(
          counter,
          legalNumber,
          state,
          stripeInvoiceId,
          issuedAt,
          allocatedAt,
          voidReason,
          voidRuleId,
          null,
          Optional.empty(),
          false);
    }

    /** The supply date (BT-72), when the line was numbered by 0.2.0 or later and carries one. */
    public Optional<LocalDate> supplyDate() {
      return dating.flatMap(IssueDating::supplyDate);
    }

    /** Why the line was numbered after its supply day; empty for a line from before 0.2.0. */
    public Optional<DateGapCause> cause() {
      return dating.flatMap(IssueDating::cause);
    }

    public Optional<String> reason() {
      return voidReason == null || voidReason.isEmpty()
          ? Optional.empty()
          : Optional.of(voidReason);
    }
  }

  /**
   * True when every counter value from 1 to the last allocated one is present in {@link #lines()}.
   * The report enumerates what the series holds; this says out loud whether the enumeration itself
   * is contiguous, which is a different question from whether every number carries a document.
   */
  public boolean contiguous() {
    long expected = 1;
    for (Line line : lines) {
      if (line.counter() != expected++) {
        return false;
      }
    }
    return true;
  }
}
