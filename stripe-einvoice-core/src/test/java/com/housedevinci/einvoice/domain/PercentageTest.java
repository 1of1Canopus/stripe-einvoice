package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Checklist lines 1, 2 and 4: a rate is bounded on magnitude, not only on scale. */
class PercentageTest {

  @Test
  void a_rate_reads_back_as_written() {
    assertThat(Percentage.of("19.6").value()).isEqualByComparingTo("19.6");
    assertThat(Percentage.of("0").text()).isEqualTo("0");
    assertThat(Percentage.of("20.00").text()).isEqualTo("20");
  }

  @Test
  void exponent_notation_is_refused_at_the_text_not_after_bigdecimal_accepted_it() {
    // new BigDecimal("1E+2000") is a legal call and a negative scale afterwards; the check that
    // catches it is the one on the characters.
    for (String hostile : new String[] {"1E+2000", "1e3", "2,5", " 1_0", "٢٠"}) {
      assertThatThrownBy(() -> Percentage.of(hostile))
          .as(hostile)
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.AMOUNT_OUT_OF_BOUNDS);
    }
  }

  @Test
  void a_negative_scale_is_refused_even_when_it_arrives_as_a_bigdecimal() {
    assertThatThrownBy(() -> new Percentage(new BigDecimal("1E+3")))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.AMOUNT_OUT_OF_BOUNDS);
  }

  @Test
  void a_rate_outside_zero_to_one_hundred_is_refused() {
    assertThatThrownBy(() -> Percentage.of("100.0001")).isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> Percentage.of("-1")).isInstanceOf(EInvoiceException.class);
    assertThat(Percentage.of("100").value()).isEqualByComparingTo("100");
  }

  @Test
  void more_decimal_places_than_a_rate_carries_are_refused() {
    assertThatThrownBy(() -> Percentage.of("19.123456")).isInstanceOf(EInvoiceException.class);
    assertThat(Percentage.of("19.1234").value()).isEqualByComparingTo("19.1234");
  }

  @Test
  void a_blank_or_null_rate_is_refused() {
    assertThatThrownBy(() -> Percentage.of("  ")).isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> Percentage.of(null)).isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> new Percentage(null)).isInstanceOf(EInvoiceException.class);
  }
}
