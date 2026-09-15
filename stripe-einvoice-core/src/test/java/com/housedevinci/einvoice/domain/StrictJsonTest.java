package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The identity reader (I-12). The body is signature-verified before it gets here, so this is a
 * correctness surface rather than an attack surface - but {@code event.id} becomes the idempotency
 * key for everything downstream, and a key derived from a lenient parse is a key two readers can
 * disagree about.
 */
class StrictJsonTest {

  private static Map<String, Object> parse(String json) {
    return StrictJson.parseObject(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void a_duplicate_key_is_refused_rather_than_resolved_to_the_first_or_the_last() {
    // Two readers disagreeing about which "id" is the id is how one event becomes two rows.
    assertThatThrownBy(() -> parse("{\"id\":\"evt_1\",\"id\":\"evt_2\"}"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_UNREADABLE);
  }

  @Test
  void nesting_past_the_depth_bound_is_refused() {
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      deep.append("{\"a\":");
    }
    deep.append("1");
    for (int i = 0; i < 200; i++) {
      deep.append("}");
    }
    assertThatThrownBy(() -> parse(deep.toString()))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("depth");
  }

  @Test
  void trailing_content_after_the_document_is_refused() {
    assertThatThrownBy(() -> parse("{\"id\":\"evt_1\"} {\"id\":\"evt_2\"}"))
        .isInstanceOf(EInvoiceException.class);
  }

  @Test
  void a_body_that_is_not_valid_utf8_is_refused_rather_than_replaced_character_by_character() {
    byte[] invalid = {'{', '"', 'i', 'd', '"', ':', '"', (byte) 0xC3, '"', '}'};
    assertThatThrownBy(() -> StrictJson.parseObject(invalid))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_UNREADABLE);
  }

  @Test
  void an_unpaired_surrogate_escape_is_refused() {
    assertThatThrownBy(() -> parse("{\"id\":\"\\ud800\"}"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("surrogate");
  }

  @Test
  void a_paired_surrogate_escape_reads_as_one_code_point() {
    assertThat(parse("{\"id\":\"\\ud83d\\ude00\"}")).containsEntry("id", "\uD83D\uDE00");
  }

  @Test
  void the_values_a_document_needs_read_back_exactly() {
    Map<String, Object> event =
        parse(
            "{\"id\":\"evt_1\",\"type\":\"invoice.finalized\",\"livemode\":true,"
                + "\"account\":null,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"in_1\",\"lines\":{\"data\":[]}}}}");
    assertThat(event).containsEntry("id", "evt_1").containsEntry("livemode", Boolean.TRUE);
    assertThat(event.get("account")).isNull();
    assertThat(StrictJson.stringAt(event, "data", "object", "id")).contains("in_1");
  }

  @Test
  void escapes_and_numbers_read_as_json_says_they_do() {
    Map<String, Object> parsed =
        parse("{\"a\":\"x\\ty\\\"z\\\\\\/\",\"b\":-1.5e3,\"c\":[1,2,{\"d\":false}]}");
    assertThat(parsed).containsEntry("a", "x\ty\"z\\/");
    assertThat(parsed.get("b")).isEqualTo(new StrictJson.JsonNumber("-1.5e3"));
    assertThat(parsed.get("c")).isInstanceOf(java.util.List.class);
  }

  @Test
  void a_control_character_inside_a_string_literal_is_refused() {
    assertThatThrownBy(() -> parse("{\"id\":\"a\u0001b\"}")).isInstanceOf(EInvoiceException.class);
  }

  @Test
  void a_document_that_is_not_an_object_is_refused() {
    assertThatThrownBy(() -> parse("[1,2,3]")).isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> parse("")).isInstanceOf(EInvoiceException.class);
  }

  @Test
  void a_non_string_where_an_identifier_belongs_is_not_coerced() {
    Map<String, Object> event = parse("{\"id\":12345}");
    assertThat(StrictJson.stringAt(event, "id")).isEmpty();
  }
}
