package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

/**
 * The one screening function. Every free-text value that reaches a document, a chained row or an
 * auditor-facing report passes through it, so its refusals are the module's refusals.
 */
class ScreenedTextTest {

  @Test
  void a_c0_control_character_is_refused() {
    String withBell = "cancelled" + (char) 7 + " by ops";
    assertThatThrownBy(() -> ScreenedText.screen("void reason", withBell, 500))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("control character")
        .hasMessageNotContaining("cancelled");
  }

  @Test
  void tab_line_feed_and_carriage_return_survive() {
    assertThat(ScreenedText.screen("note", "line one\nline two\tindented", 500))
        .isEqualTo("line one\nline two\tindented");
  }

  @Test
  void an_unpaired_surrogate_is_refused() {
    // A writer emits one of these as invalid XML, and the document then cannot be parsed by the
    // party who has to act on it.
    String unpaired = "name " + (char) 0xD83D;
    assertThatThrownBy(() -> ScreenedText.screen("buyer name", unpaired, 100))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("unpaired surrogate");
    assertThat(ScreenedText.screen("buyer name", "paired 😀", 100)).contains("😀");
  }

  @Test
  void a_value_that_collapses_to_blank_is_refused_rather_than_written_as_a_blank_field() {
    // C17-37: a whitespace-insensitive schema check does not protect a business rule that compares
    // raw strings at the recipient.
    assertThatThrownBy(() -> ScreenedText.screen("void reason", "   \t  ", 500))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void a_value_past_its_field_bound_is_refused_here_rather_than_at_the_recipient() {
    assertThatThrownBy(() -> ScreenedText.screen("void reason", "x".repeat(501), 500))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("max 500");
  }

  @Test
  void the_value_is_normalised_to_nfc_before_anything_else_looks_at_it() {
    String decomposed = "étude";
    String screened = ScreenedText.screen("buyer name", decomposed, 100);
    assertThat(Normalizer.isNormalized(screened, Normalizer.Form.NFC)).isTrue();
    assertThat(screened).isEqualTo("étude");
    assertThat(ScreenedText.collapsed("  two   words ")).isEqualTo("two words");
  }

  @Test
  void a_null_is_a_typed_refusal_and_never_a_null_pointer() {
    assertThatThrownBy(() -> ScreenedText.screen("void reason", null, 10))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INVALID);
  }
}
