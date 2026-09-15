package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The six fields, and nothing else, out of a signature-verified body (D-02, I-12). */
class EventIdentityTest {

  private static final String EVENT =
      "{\"id\":\"evt_1\",\"type\":\"invoice.finalized\",\"api_version\":\"2026-03-31.clover\","
          + "\"livemode\":true,\"account\":\"acct_1\","
          + "\"data\":{\"object\":{\"id\":\"in_1\",\"total\":4200,"
          + "\"lines\":{\"has_more\":true,\"data\":[]}}}}";

  private static EventIdentity read(String body) {
    return EventIdentity.from(body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void the_identity_is_read_and_the_embedded_object_is_not() {
    EventIdentity identity = read(EVENT);
    assertThat(identity.eventId()).isEqualTo("evt_1");
    assertThat(identity.type()).isEqualTo("invoice.finalized");
    assertThat(identity.apiVersion()).isEqualTo("2026-03-31.clover");
    assertThat(identity.livemode()).isTrue();
    assertThat(identity.accountId()).isEqualTo("acct_1");
    assertThat(identity.objectId()).isEqualTo("in_1");
    // Six accessors, and none of them is a way to the payload's own totals or lines.
    assertThat(EventIdentity.class.getRecordComponents()).hasSize(6);
  }

  @Test
  void a_platform_account_event_reads_as_the_platform_account_not_as_null() {
    EventIdentity identity =
        read(
            "{\"id\":\"evt_2\",\"type\":\"invoice.paid\",\"livemode\":false,\"account\":null,"
                + "\"data\":{\"object\":{\"id\":\"in_2\"}}}");
    assertThat(identity.accountId()).isEmpty();
    assertThat(identity.livemode()).isFalse();
  }

  @Test
  void a_missing_livemode_is_unreadable_rather_than_defaulted_to_live() {
    assertThatThrownBy(
            () ->
                read(
                    "{\"id\":\"evt_3\",\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\"in_3\"}}}"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_UNREADABLE);
  }

  @Test
  void an_identifier_that_would_not_go_in_a_bind_parameter_is_unreadable() {
    assertThatThrownBy(
            () ->
                read(
                    "{\"id\":\"evt 4\",\"type\":\"invoice.paid\",\"livemode\":true,"
                        + "\"data\":{\"object\":{\"id\":\"in_4\"}}}"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_UNREADABLE);
  }

  @Test
  void a_numeric_event_id_is_not_coerced_into_a_string_key() {
    assertThatThrownBy(
            () ->
                read(
                    "{\"id\":1234,\"type\":\"invoice.paid\",\"livemode\":true,"
                        + "\"data\":{\"object\":{\"id\":\"in_5\"}}}"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_UNREADABLE);
  }

  @Test
  void an_absent_object_id_is_unreadable_rather_than_an_event_about_nothing() {
    assertThatThrownBy(
            () ->
                read("{\"id\":\"evt_6\",\"type\":\"invoice.paid\",\"livemode\":true,\"data\":{}}"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_UNREADABLE);
  }
}
