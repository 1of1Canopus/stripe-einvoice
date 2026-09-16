package com.housedevinci.einvoice.domain;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * A tax rate percentage, bounded on magnitude and on scale (checklist lines 1, 2 and 4).
 *
 * <p>Stripe sends rate percentages as decimal numbers, and a decimal from anywhere is {@code new
 * BigDecimal("1E+2000")} until something says otherwise: a legal value with a magnitude that turns
 * one multiplication into a megabyte of digits and a negative {@code scale()} that makes every
 * later {@code setScale} pathological. A {@code scale() > n} check alone does not catch it, which
 * is why the magnitude is bounded in integer digits and exponent notation is refused at the text.
 */
public record Percentage(BigDecimal value) {

  /** Rates run 0..100. Two integer digits plus the boundary value is all a rate can need. */
  public static final int MAX_INTEGER_DIGITS = 3;

  /** Stripe carries four decimal places on a rate; more is not a rate, it is a payload. */
  public static final int MAX_SCALE = 4;

  public Percentage {
    if (value == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "a tax percentage must not be null");
    }
    if (value.scale() < 0 || value.scale() > MAX_SCALE) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
          "a tax percentage carries at most "
              + MAX_SCALE
              + " decimal places and no negative scale");
    }
    if (value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
          "a tax percentage carries at most " + MAX_INTEGER_DIGITS + " integer digits");
    }
    if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS, "a tax percentage lies between 0 and 100");
    }
  }

  /**
   * Parses a percentage from text.
   *
   * <p>Exponent notation, thousands separators and locale number formats are refused at the text
   * rather than after {@code BigDecimal} has accepted them: the constructor accepts a great deal
   * more than a tax rate ever is.
   */
  public static Percentage of(String text) {
    if (text == null || text.isBlank()) {
      throw new EInvoiceException(ErrorCodes.INVALID, "a tax percentage must not be blank");
    }
    String trimmed = text.strip();
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      // ASCII digits only, never Character.isDigit: U+0660 ARABIC-INDIC DIGIT ZERO and its
      // friends are digits by that predicate, and BigDecimal parses them - so a rate written in
      // another script would be accepted here and print as something else downstream.
      boolean ok = (c >= '0' && c <= '9') || c == '.' || (c == '-' && i == 0);
      if (!ok) {
        throw new EInvoiceException(
            ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
            "a tax percentage is plain decimal digits with at most one point: no exponent, no"
                + " separator, no locale formatting (offending character at index "
                + i
                + ")");
      }
    }
    try {
      return new Percentage(new BigDecimal(trimmed));
    } catch (NumberFormatException notANumber) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS, "a tax percentage is not a decimal number");
    }
  }

  /** The canonical text of the rate, locale-independent, for hashed material and documents. */
  public String text() {
    return value.stripTrailingZeros().toPlainString().toLowerCase(Locale.ROOT);
  }
}
