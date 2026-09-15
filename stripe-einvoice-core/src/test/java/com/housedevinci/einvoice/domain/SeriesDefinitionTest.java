package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SeriesDefinitionTest {

  @Test
  void a_prefix_is_required_and_has_no_default() {
    assertThatThrownBy(() -> new SeriesDefinition("", 6, true))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.CONFIG);
  }

  @Test
  void a_prefix_outside_the_declared_charset_is_refused_at_startup() {
    // The prefix is printed on a legal document and is part of a unique key. Free text there is a
    // format nobody agreed to.
    assertThatThrownBy(() -> new SeriesDefinition("inv/2026 ", 6, true))
        .isInstanceOf(EInvoiceException.class);
  }

  @Test
  void a_width_outside_four_to_twelve_is_refused_at_startup() {
    assertThatThrownBy(() -> new SeriesDefinition("INV-", 3, true))
        .isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> new SeriesDefinition("INV-", 13, true))
        .isInstanceOf(EInvoiceException.class);
    assertThat(new SeriesDefinition("INV-", 12, true).lastRenderableNumber())
        .isEqualTo(999_999_999_999L);
  }
}
