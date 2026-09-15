package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LegalNumberTest {

  private static final SeriesDefinition SIX = new SeriesDefinition("INV-2026-", 6, true);

  @Test
  void a_number_is_the_prefix_and_the_counter_padded_to_the_configured_width() {
    assertThat(LegalNumber.render(SIX, 1).value()).isEqualTo("INV-2026-000001");
    assertThat(LegalNumber.render(SIX, 999999).value()).isEqualTo("INV-2026-999999");
  }

  @Test
  void a_counter_past_the_width_is_refused_rather_than_rendered_wider() {
    // N-02. At 10^width the padding stops padding and the number silently changes format. A legal
    // series does not change format mid-way, at any probability.
    assertThatThrownBy(() -> LegalNumber.render(SIX, 1_000_000))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.SERIES_EXHAUSTED);
  }

  @Test
  void the_probe_is_the_widest_number_the_series_can_ever_render() {
    // Validated against the profile's BT-1 rules before any number is allocated, so a rule this
    // series would eventually violate is found on a probe rather than on a consumed number.
    assertThat(LegalNumber.probeOfMaximumWidth(SIX).value()).isEqualTo("INV-2026-999999");
    assertThat(SIX.lastRenderableNumber()).isEqualTo(999_999L);
  }

  @Test
  void a_counter_below_one_is_not_a_number_this_module_ever_issued() {
    assertThatThrownBy(() -> new LegalNumber("INV-2026-000000", 0))
        .isInstanceOf(EInvoiceException.class);
  }
}
