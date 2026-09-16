package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * An amount on a legal document: a {@link BigDecimal} and a currency, bounded on both ends (D-05,
 * checklist lines 1, 2 and 4).
 *
 * <p>Never {@code double}, never {@code float}, and never an unbounded {@code BigDecimal}. {@code
 * new BigDecimal("1E+2000")} is a legal amount to that constructor: it has a magnitude that turns
 * one line into a megabyte of digits and a <em>negative</em> {@code scale()}, which makes every
 * later {@code setScale} and {@code toPlainString} pathological. A {@code scale() > n} check alone
 * does not catch it, so the magnitude is bounded in integer digits and exponent notation is refused
 * at the text before {@code BigDecimal} ever sees it.
 *
 * <p>The scale an amount is <em>written</em> at is the currency's ISO 4217 presentation exponent,
 * from {@link CurrencyExponents}, applied in exactly one place: {@link #toPlainString()}. There is
 * no rounding here - a value that does not fit the presentation exponent exactly is a refusal, not
 * a rounded number, because rounding an amount at the writer changes what the seller charged.
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

  /**
   * Twelve integer digits: a thousand billion units of any currency. Above that the value did not
   * come from an invoice, and the bound is expressed in integer digits rather than in a precision
   * count that can itself overflow.
   */
  public static final int MAX_INTEGER_DIGITS = 12;

  /**
   * Four decimal places. EN 16931 writes amounts at the currency's exponent (BR-DEC-*), but a unit
   * price (BT-146) legitimately carries more, and Stripe's {@code unit_amount_decimal} carries up
   * to twelve. Four is what this module will put on a document; more is refused by name.
   */
  public static final int MAX_SCALE = 4;

  public Money {
    if (amount == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an amount must not be null");
    }
    currency = CurrencyExponents.requireSupported(currency);
    if (amount.scale() < 0) {
      // A negative scale is what exponent notation leaves behind. Refused before any arithmetic.
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
          "an amount written in exponent notation is refused: its scale is negative, which makes"
              + " every later rounding step behave differently from the one a reader expects");
    }
    if (amount.scale() > MAX_SCALE) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
          "an amount carries at most " + MAX_SCALE + " decimal places on a document");
    }
    if (integerDigits(amount) > MAX_INTEGER_DIGITS) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
          "an amount carries at most " + MAX_INTEGER_DIGITS + " integer digits");
    }
  }

  /**
   * Builds an amount from an integer minor-unit value as the payment provider carries it.
   *
   * @param minorUnits the provider's integer amount
   * @param currency the invoice currency
   */
  public static Money ofMinor(long minorUnits, String currency) {
    String code = CurrencyExponents.requireSupported(currency);
    CurrencyExponents.requireStripeAmount(code, minorUnits);
    int exponent = CurrencyExponents.stripeExponent(code);
    BigDecimal value = BigDecimal.valueOf(minorUnits, exponent);
    int presentation = CurrencyExponents.presentationExponent(code);
    if (presentation > exponent) {
      // HUF and TWD: charged as zero-decimal, written with two decimals. Adding the places is
      // exact - it changes how the number is spelled, never what it is.
      value = value.setScale(presentation, RoundingMode.UNNECESSARY);
    }
    return new Money(value, code);
  }

  /**
   * Parses an amount from text a host application or an upstream API supplied.
   *
   * <p>Exponent notation, thousands separators, locale digit shapes and anything else {@code
   * BigDecimal}'s constructor accepts are refused <b>at the text</b>. {@code Character.isDigit} is
   * deliberately not used: U+0660 ARABIC-INDIC DIGIT ZERO is a digit by that predicate and {@code
   * BigDecimal} parses it, so an amount written in another script would be accepted here and print
   * as something else downstream.
   */
  public static Money parse(String text, String currency) {
    if (text == null || text.isBlank()) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an amount must not be blank");
    }
    String trimmed = text.strip();
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || c == '.' || (c == '-' && i == 0);
      if (!ok) {
        throw new EInvoiceException(
            ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
            "an amount is plain decimal digits with at most one point: no exponent, no separator,"
                + " no locale formatting (offending character at index "
                + i
                + ")");
      }
    }
    try {
      return new Money(new BigDecimal(trimmed), currency);
    } catch (NumberFormatException notANumber) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS, "an amount is not a decimal number");
    }
  }

  /** Zero, at the currency's presentation exponent. */
  public static Money zero(String currency) {
    return ofMinor(0, currency);
  }

  public Money add(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  public Money subtract(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  /** Value equality across spellings: 1.0 and 1.00 are the same amount. */
  public boolean isEqualTo(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount) == 0;
  }

  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount);
  }

  /**
   * The amount as it is written on the document: plain notation, at the currency's ISO 4217
   * presentation exponent, locale-independent.
   *
   * @throws EInvoiceException {@link ErrorCodes#AMOUNT_OUT_OF_BOUNDS} when the value does not fit
   *     that exponent exactly. Rounding here would change what the seller charged, so it is a
   *     refusal instead.
   */
  public String toPlainString() {
    int exponent = CurrencyExponents.presentationExponent(currency);
    try {
      return amount.setScale(exponent, RoundingMode.UNNECESSARY).toPlainString();
    } catch (ArithmeticException doesNotFit) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
          "an amount in "
              + currency
              + " does not fit the "
              + exponent
              + " decimal places that currency is written with, and rounding it at the writer would"
              + " change what was charged");
    }
  }

  /** The amount at a stated scale, for a unit price, which EN 16931 allows more places on. */
  public String toPlainString(int scale) {
    if (scale < 0 || scale > MAX_SCALE) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "a scale for an amount lies between 0 and " + MAX_SCALE);
    }
    try {
      return amount.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    } catch (ArithmeticException doesNotFit) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
          "an amount does not fit " + scale + " decimal places without being rounded");
    }
  }

  private void requireSameCurrency(Money other) {
    if (other == null || !currency.equals(other.currency)) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "two amounts in different currencies cannot be combined: an invoice carries exactly one"
              + " currency (stripe field: currency)");
    }
  }

  private static int integerDigits(BigDecimal value) {
    BigDecimal stripped = value.abs().setScale(0, RoundingMode.DOWN);
    return stripped.signum() == 0 ? 1 : stripped.precision();
  }

  @Override
  public String toString() {
    // Never a locale-dependent format, and never a bare number: a log line that shows an amount
    // without its currency is a log line that will be misread.
    return String.format(Locale.ROOT, "%s %s", amount.toPlainString(), currency);
  }
}
