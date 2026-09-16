package com.housedevinci.einvoice.domain.en16931;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checklist lines 1, 2, 3 and 4: every bound on an amount, and the stable code behind each. */
class MoneyTest {

  @Test
  void a_scientific_notation_amount_is_refused_before_any_arithmetic() {
    // Checklist line 1: the defect a `scale() > n` check walks straight past. `1E+2000` has
    // scale -2000, so it passes "scale is not too large" and then amplifies every later step.
    assertThatThrownBy(() -> new Money(new BigDecimal("1E+2000"), "EUR"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.AMOUNT_OUT_OF_BOUNDS);
  }

  @Test
  void a_magnitude_past_the_bound_is_refused_in_integer_digits() {
    BigDecimal tooBig = new BigDecimal("1000000000000.00"); // thirteen integer digits
    assertThatThrownBy(() -> new Money(tooBig, "EUR"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("integer digits");
    assertThat(new Money(new BigDecimal("999999999999.00"), "EUR").amount())
        .isEqualByComparingTo("999999999999.00");
  }

  @Test
  void a_scale_past_the_bound_is_refused() {
    assertThatThrownBy(() -> new Money(new BigDecimal("1.234567"), "EUR"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("decimal places");
  }

  @ParameterizedTest
  @ValueSource(strings = {"1e5", "1E5", "1,000.00", "1 000.00", "١٢", "12.3.4", "+5"})
  void the_parser_refuses_exponents_separators_and_other_scripts(String text) {
    // Checklist line 2. U+0661 U+0662 are ARABIC-INDIC digits: Character.isDigit says yes and
    // BigDecimal parses them, so an amount in another script would print as something else.
    assertThatThrownBy(() -> Money.parse(text, "EUR"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.AMOUNT_OUT_OF_BOUNDS);
  }

  @Test
  void an_unknown_currency_is_refused_rather_than_given_an_exponent_of_two() {
    // Checklist line 3.
    assertThatThrownBy(() -> Money.ofMinor(1000, "XYZ"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.UNSUPPORTED_CURRENCY);
  }

  @Test
  void a_zero_decimal_currency_is_not_divided_by_a_hundred() {
    // The defect D-05 names by name: ten thousand yen must not become one hundred.
    assertThat(Money.ofMinor(10_000, "JPY").toPlainString()).isEqualTo("10000");
    assertThat(Money.ofMinor(10_000, "EUR").toPlainString()).isEqualTo("100.00");
  }

  @Test
  void a_three_decimal_currency_carries_three_places_and_must_be_a_multiple_of_ten() {
    assertThat(Money.ofMinor(10_500, "KWD").toPlainString()).isEqualTo("10.500");
    assertThatThrownBy(() -> Money.ofMinor(10_501, "KWD"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("multiple of ten");
  }

  @Test
  void a_currency_stripe_charges_as_zero_decimal_is_written_at_its_iso_exponent() {
    // The two exponents are different numbers and both are listed. Stripe charges 1000 HUF as
    // the integer 1000; ISO 4217 writes HUF with two decimal places.
    assertThat(CurrencyExponents.stripeExponent("HUF")).isZero();
    assertThat(CurrencyExponents.presentationExponent("HUF")).isEqualTo(2);
    assertThat(Money.ofMinor(1000, "HUF").toPlainString()).isEqualTo("1000.00");
  }

  @Test
  void an_amount_that_does_not_fit_the_presentation_exponent_is_refused_not_rounded() {
    Money awkward = Money.parse("10.005", "EUR");
    assertThatThrownBy(awkward::toPlainString)
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("would change what was charged");
  }

  @Test
  void two_currencies_never_combine() {
    assertThatThrownBy(() -> Money.ofMinor(100, "EUR").add(Money.ofMinor(100, "USD")))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("exactly one currency");
  }

  @Test
  void rendering_is_locale_independent() {
    // Checklist line 17, at the smallest scale it can be tested at: a locale whose decimal
    // separator is a comma must not change the bytes of an amount.
    Locale previous = Locale.getDefault();
    TimeZone zone = TimeZone.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("de-DE"));
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
      assertThat(Money.ofMinor(123_456, "EUR").toPlainString()).isEqualTo("1234.56");
      Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"));
      assertThat(Money.ofMinor(123_456, "EUR").toPlainString()).isEqualTo("1234.56");
    } finally {
      Locale.setDefault(previous);
      TimeZone.setDefault(zone);
    }
  }

  @Test
  void the_string_form_always_carries_the_currency() {
    assertThat(Money.ofMinor(1999, "EUR")).hasToString("19.99 EUR");
  }
}
