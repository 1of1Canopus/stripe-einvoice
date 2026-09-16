package com.housedevinci.einvoice.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recompute every total and every per-category bucket, then compare against the upstream's own
 * scalars. Equality is required; a difference is a refusal, never an adjustment (D-12, checklist
 * line 5).
 *
 * <p><b>The breakdown is the point.</b> A check that recomputes the three scalars and skips the
 * per-rate buckets reports PASSED on a document whose breakdown is wrong, and a wrong breakdown
 * passes schema and schematron at the recipient too. So the buckets are compared first, and an
 * invoice whose scalars agree while one bucket is a cent out is refused.
 *
 * <p><b>Minor units, and no currency exponent here.</b> Everything below is integer arithmetic in
 * the currency's own minor unit, exactly as the upstream carries it. That makes this comparison
 * exact and independent of the exponent table - the Stripe minor-unit exponent versus the ISO 4217
 * presentation exponent (D-05) is the mapper's problem, and it is deliberately not smuggled in
 * here.
 *
 * <p>Rounding is <b>half-up per tax-rate bucket</b>, in one place. Per line it would be a different
 * number - two lines of 3 333 at 19 % round to 1 266 per line and 1 267 per bucket - and only one
 * of those is what the recipient's own rules recompute.
 */
public final class Totals {

  private Totals() {}

  /**
   * One invoice line, in minor units.
   *
   * @param netMinor the amount excluding tax
   * @param grossMinor the amount including tax, which is what an inclusive-tax rate applies to
   */
  public record Line(String lineId, String taxRateId, long netMinor, long grossMinor) {}

  /** One tax-rate bucket as the upstream reports it: the rate, and the tax it charged. */
  public record Bucket(String taxRateId, Percentage percentage, boolean inclusive, long taxMinor) {}

  /** What the upstream says, beside what it is made of. */
  public record Input(
      List<Line> lines, List<Bucket> buckets, long subtotalMinor, long taxMinor, long totalMinor) {}

  /**
   * @throws TotalsMismatchException when a recomputed value and the upstream's own disagree
   * @throws EInvoiceException {@link ErrorCodes#MAPPING_INCOMPLETE} when the invoice cannot be
   *     recomputed at all, {@link ErrorCodes#AMOUNT_OUT_OF_BOUNDS} when a sum leaves the range
   */
  public static void reconcile(Input input) {
    if (input.lines().isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "the invoice carries no line: a document that itemises nothing is not one this module"
              + " will issue (stripe field: lines)");
    }
    Map<String, Bucket> byRate = new LinkedHashMap<>();
    for (Bucket bucket : input.buckets()) {
      byRate.put(bucket.taxRateId(), bucket);
    }
    Map<String, long[]> baseByRate = new LinkedHashMap<>();
    long netSum = 0;
    for (Line line : input.lines()) {
      if (!byRate.containsKey(line.taxRateId())) {
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            "a line carries a tax rate that the invoice's own tax breakdown does not list, so no"
                + " category can be recomputed for it (stripe field: lines.data.tax_rates)");
      }
      netSum = add(netSum, line.netMinor());
      long[] base = baseByRate.computeIfAbsent(line.taxRateId(), id -> new long[2]);
      base[0] = add(base[0], line.netMinor());
      base[1] = add(base[1], line.grossMinor());
    }

    // The breakdown first: it is the check that a scalar comparison cannot stand in for.
    long taxSum = 0;
    for (Bucket bucket : input.buckets()) {
      long[] base = baseByRate.getOrDefault(bucket.taxRateId(), new long[2]);
      long recomputed = taxOf(bucket, base[0], base[1]);
      if (recomputed != bucket.taxMinor()) {
        throw new TotalsMismatchException(
            "total_details.breakdown.taxes.amount", recomputed, bucket.taxMinor());
      }
      taxSum = add(taxSum, bucket.taxMinor());
    }

    if (netSum != input.subtotalMinor()) {
      throw new TotalsMismatchException("subtotal", netSum, input.subtotalMinor());
    }
    if (taxSum != input.taxMinor()) {
      throw new TotalsMismatchException("tax", taxSum, input.taxMinor());
    }
    long total = add(input.subtotalMinor(), input.taxMinor());
    if (total != input.totalMinor()) {
      // A discount, a coupon or a customer credit balance lands here. 0.1.0 refuses by name rather
      // than issuing a document whose total it cannot account for line by line.
      throw new TotalsMismatchException("total", total, input.totalMinor());
    }
    if (input.totalMinor() <= 0) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "this invoice's total is not positive. In EN 16931 a refund is a credit note (type code"
              + " 381) with positive amounts, never an invoice with negative ones, and credit notes"
              + " are not in this edition");
    }
  }

  /**
   * Half-up, per bucket. Exclusive tax applies to the net; inclusive tax is already inside the
   * gross, so it is extracted rather than added - {@code gross * p / (100 + p)} - which is a
   * different number from {@code net * p / 100} on the cent.
   */
  private static long taxOf(Bucket bucket, long netMinor, long grossMinor) {
    BigDecimal percentage = bucket.percentage().value();
    BigDecimal base = BigDecimal.valueOf(bucket.inclusive() ? grossMinor : netMinor);
    BigDecimal divisor =
        bucket.inclusive() ? BigDecimal.valueOf(100).add(percentage) : BigDecimal.valueOf(100);
    if (divisor.signum() == 0) {
      return 0;
    }
    return base.multiply(percentage).divide(divisor, 0, RoundingMode.HALF_UP).longValueExact();
  }

  /** A sum that leaves the range is a refusal, never a wrap-around into a plausible number. */
  private static long add(long a, long b) {
    try {
      return Math.addExact(a, b);
    } catch (ArithmeticException overflow) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
          "the invoice's line amounts sum past the range this module will carry");
    }
  }
}
