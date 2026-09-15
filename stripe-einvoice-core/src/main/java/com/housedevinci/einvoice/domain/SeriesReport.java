package com.housedevinci.einvoice.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every number allocated in one series, with its disposition. This is the page an auditor reads, so
 * it is written for that reader: an unexplained hole and a hole with a chained reason must never
 * look alike (numbering design, deviation-3 ruling).
 *
 * <p>Each line carries the issue date (BT-2, which decides the fiscal year) <em>and</em> the
 * allocation timestamp. An invoice finalised on 31 December and processed on 2 January takes a
 * number from the closing year's series, after numbers of the new year already exist: correct,
 * unavoidable, and indistinguishable from tampering on a date-ordered list unless both timestamps
 * are on the page where the question is asked (N-08).
 */
public record SeriesReport(SeriesKey seriesKey, List<Line> lines, long openCount, long nextNumber) {

  public SeriesReport {
    lines = List.copyOf(lines);
  }

  /**
   * @param state the disposition: ISSUED, VOID_UNUSED, or an allocation still open
   * @param issuedAt BT-2, the date that decides the VAT period
   * @param allocatedAt when this module consumed the counter value
   */
  public record Line(
      long counter,
      String legalNumber,
      IssuanceState state,
      String stripeInvoiceId,
      Instant issuedAt,
      Instant allocatedAt,
      String voidReason,
      String voidRuleId) {

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
