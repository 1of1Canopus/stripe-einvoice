package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The explicit currency exponent table (D-05, checklist line 3).
 *
 * <p><b>There is no default of 2 anywhere in this module.</b> Dividing a Stripe amount by 100 is
 * right for EUR and wrong for JPY (a 10 000 yen invoice becomes 100.00), wrong for KWD and its
 * three-decimal neighbours, and wrong again for the currencies Stripe treats as zero-decimal while
 * ISO 4217 does not. An unlisted currency is refused at mapping time rather than guessed.
 *
 * <p>Two exponents, on purpose, because they are two different numbers:
 *
 * <ul>
 *   <li>{@link #stripeExponent(String)} is how many decimal places Stripe's integer minor unit
 *       carries. It is the divisor that turns {@code amount} into a decimal.
 *   <li>{@link #presentationExponent(String)} is ISO 4217's exponent for the currency: how many
 *       decimal places the amount is written with on the document.
 * </ul>
 *
 * <p>They differ for HUF, TWD, UGX and ISK, which Stripe charges as zero-decimal (its API takes
 * {@code 1000} to mean 1000 forint) while ISO 4217 gives HUF an exponent of 2. Printing {@code
 * 1000} as {@code 10.00} because "the exponent is 2" is the factor-of-100 defect D-05 names, and
 * printing it as {@code 1000} when a recipient expects {@code 1000.00} is a schema failure at the
 * other end. Both numbers are listed, per currency, and the code that needs one says which.
 *
 * <p>Sources, read 2026-09-16:
 *
 * <ul>
 *   <li>Zero-decimal and three-decimal currencies: Stripe API reference, "Zero-decimal currencies"
 *       and "Three-decimal currencies", {@code https://docs.stripe.com/currencies}.
 *   <li>Presentation exponents: ISO 4217, table A.1.
 * </ul>
 */
public final class CurrencyExponents {

  /**
   * Stripe's zero-decimal currencies: an amount of {@code 1000} means one thousand units, not ten.
   * Listed by name rather than derived, exactly as the checklist requires.
   */
  public static final Set<String> STRIPE_ZERO_DECIMAL =
      Set.of(
          "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX", "VND", "VUV",
          "XAF", "XOF", "XPF");

  /**
   * Stripe's three-decimal currencies. Stripe additionally requires these amounts to be a multiple
   * of ten, which {@link #requireStripeAmount} enforces: an amount that is not is a payload we did
   * not understand, not an amount to round.
   */
  public static final Set<String> STRIPE_THREE_DECIMAL = Set.of("BHD", "JOD", "KWD", "OMR", "TND");

  /**
   * Currencies whose ISO 4217 presentation exponent differs from the exponent Stripe charges them
   * in. Each one is a documented, deliberate disagreement rather than a table entry nobody read.
   */
  private static final Map<String, Integer> PRESENTATION_OVERRIDES = presentationOverrides();

  /** Every currency this module will put on a document. An unlisted one is a refusal. */
  private static final Set<String> SUPPORTED = supported();

  private CurrencyExponents() {}

  private static Map<String, Integer> presentationOverrides() {
    Map<String, Integer> map = new LinkedHashMap<>();
    // Stripe charges these as zero-decimal; ISO 4217 gives them an exponent of 2.
    map.put("HUF", 2);
    map.put("TWD", 2);
    map.put("UGX", 0);
    map.put("ISK", 0);
    return Map.copyOf(map);
  }

  private static Set<String> supported() {
    Set<String> set = new java.util.LinkedHashSet<>();
    set.addAll(
        Set.of(
            "AUD", "BGN", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HRK",
            "HUF", "ILS", "INR", "ISK", "MAD", "MXN", "NOK", "NZD", "PLN", "RON", "SEK", "SGD",
            "TRY", "TWD", "USD", "ZAR"));
    set.addAll(STRIPE_ZERO_DECIMAL);
    set.addAll(STRIPE_THREE_DECIMAL);
    return Set.copyOf(set);
  }

  /**
   * @param currency a three-letter code, in any case
   * @return the same code upper-cased
   * @throws EInvoiceException {@link ErrorCodes#UNSUPPORTED_CURRENCY} when it is not on the list
   */
  public static String requireSupported(String currency) {
    String code = normalise(currency);
    if (!SUPPORTED.contains(code)) {
      throw new EInvoiceException(
          ErrorCodes.UNSUPPORTED_CURRENCY,
          "this module has no exponent recorded for currency "
              + code
              + ", and there is no default of 2: an invoice in it is refused rather than written"
              + " with amounts that may be wrong by a factor of 100 (stripe field: currency)");
    }
    return code;
  }

  /** How many decimal places Stripe's integer amount carries for this currency. */
  public static int stripeExponent(String currency) {
    String code = requireSupported(currency);
    if (STRIPE_ZERO_DECIMAL.contains(code)) {
      return 0;
    }
    if (STRIPE_THREE_DECIMAL.contains(code)) {
      return 3;
    }
    if ("HUF".equals(code) || "TWD".equals(code) || "UGX".equals(code) || "ISK".equals(code)) {
      // Stripe's own list: charged as zero-decimal whatever ISO 4217 says.
      return 0;
    }
    return 2;
  }

  /** How many decimal places the amount is written with on the document (ISO 4217). */
  public static int presentationExponent(String currency) {
    String code = requireSupported(currency);
    Integer override = PRESENTATION_OVERRIDES.get(code);
    if (override != null) {
      return override;
    }
    if (STRIPE_ZERO_DECIMAL.contains(code)) {
      return 0;
    }
    if (STRIPE_THREE_DECIMAL.contains(code)) {
      return 3;
    }
    return 2;
  }

  /**
   * Refuses an amount Stripe could not have charged in this currency.
   *
   * @throws EInvoiceException {@link ErrorCodes#AMOUNT_OUT_OF_BOUNDS} when a three-decimal amount
   *     is not a multiple of ten, which Stripe's API requires and which means the value did not
   *     come from where we think it did
   */
  public static long requireStripeAmount(String currency, long minorUnits) {
    String code = requireSupported(currency);
    if (STRIPE_THREE_DECIMAL.contains(code) && minorUnits % 10 != 0) {
      throw new EInvoiceException(
          ErrorCodes.AMOUNT_OUT_OF_BOUNDS,
          "a "
              + code
              + " amount is charged in units of ten by this payment provider, so an amount that is"
              + " not a multiple of ten did not come from it (stripe field: amount)");
    }
    return minorUnits;
  }

  private static String normalise(String currency) {
    if (currency == null || currency.length() != 3) {
      throw new EInvoiceException(
          ErrorCodes.UNSUPPORTED_CURRENCY,
          "a currency is a three-letter ISO 4217 code (stripe field: currency)");
    }
    return currency.toUpperCase(Locale.ROOT);
  }
}
