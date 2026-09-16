package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Signature verification over the exact received bytes, against a keyring (D-11, D-19). */
class WebhookSignatureTest {

  private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
  private static final byte[] BODY = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);

  private static String header(String secret, Instant when, byte[] body) {
    long t = when.getEpochSecond();
    return "t=" + t + ",v1=" + mac(secret, t + "." + new String(body, StandardCharsets.UTF_8));
  }

  private static String mac(String secret, String payload) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, String> keyring(String... idsAndSecrets) {
    Map<String, String> keyring = new LinkedHashMap<>();
    for (int i = 0; i < idsAndSecrets.length; i += 2) {
      keyring.put(idsAndSecrets[i], idsAndSecrets[i + 1]);
    }
    return keyring;
  }

  @Test
  void the_id_that_verified_is_the_one_recorded_on_the_row() {
    Map<String, String> keyring =
        keyring(
            "old",
            "whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "new",
            "whsec_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    String signed = header("whsec_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", NOW, BODY);
    assertThat(WebhookSignature.verify(BODY, signed, keyring, Duration.ofMinutes(5), NOW))
        .isEqualTo("new");
  }

  @Test
  void a_second_webhook_secret_id_verifies_so_a_rotation_is_not_an_outage() {
    // Stripe's own rotation leaves two secrets live at once. One unrotatable secret means either
    // downtime or a rotation nobody ever performs.
    Map<String, String> keyring =
        keyring(
            "old",
            "whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "new",
            "whsec_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    String signed = header("whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", NOW, BODY);
    assertThat(WebhookSignature.verify(BODY, signed, keyring, Duration.ofMinutes(5), NOW))
        .isEqualTo("old");
  }

  @Test
  void the_signature_is_verified_over_the_exact_received_bytes() {
    // One byte of difference - here a byte sequence that is not valid UTF-8, which a
    // container-decoded String would have replaced - and the check fails. That is the property: we
    // never verify a re-encoding of what arrived.
    byte[] raw = {'{', '"', 'a', '"', ':', '"', (byte) 0xF0, (byte) 0x9F, '"', '}'};
    String secret = "whsec_cccccccccccccccccccccccccccccccc";
    String signed =
        "t=" + NOW.getEpochSecond() + ",v1=" + macOverBytes(secret, NOW.getEpochSecond(), raw);
    assertThat(
            WebhookSignature.verify(raw, signed, keyring("k", secret), Duration.ofMinutes(5), NOW))
        .isEqualTo("k");
    byte[] mangled = new String(raw, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
    assertThat(mangled).isNotEqualTo(raw);
    assertThatThrownBy(
            () ->
                WebhookSignature.verify(
                    mangled, signed, keyring("k", secret), Duration.ofMinutes(5), NOW))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.SIGNATURE_INVALID);
  }

  private static String macOverBytes(String secret, long t, byte[] body) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      hmac.update((t + ".").getBytes(StandardCharsets.UTF_8));
      hmac.update(body);
      return HexFormat.of().formatHex(hmac.doFinal());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void a_forged_body_under_a_valid_timestamp_is_refused() {
    String secret = "whsec_dddddddddddddddddddddddddddddddd";
    String signed = header(secret, NOW, BODY);
    byte[] forged = "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                WebhookSignature.verify(
                    forged, signed, keyring("k", secret), Duration.ofMinutes(5), NOW))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.SIGNATURE_INVALID);
  }

  @Test
  void a_replay_outside_the_tolerance_window_is_refused_in_both_directions() {
    String secret = "whsec_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    String old = header(secret, NOW.minusSeconds(3600), BODY);
    String future = header(secret, NOW.plusSeconds(3600), BODY);
    for (String signed : new String[] {old, future}) {
      assertThatThrownBy(
              () ->
                  WebhookSignature.verify(
                      BODY, signed, keyring("k", secret), Duration.ofMinutes(5), NOW))
          .isInstanceOf(EInvoiceException.class)
          .hasMessageContaining("tolerance");
    }
  }

  @Test
  void a_refusal_never_echoes_the_header_the_secret_or_the_body() {
    String secret = "whsec_ffffffffffffffffffffffffffffffff";
    String signed = header(secret, NOW, BODY);
    assertThatThrownBy(
            () ->
                WebhookSignature.verify(
                    BODY,
                    signed,
                    keyring("k", "whsec_99999999999999999999999999999999"),
                    Duration.ofMinutes(5),
                    NOW))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageNotContaining("whsec_")
        .hasMessageNotContaining(signed)
        .hasMessageNotContaining("evt_1");
  }

  @Test
  void a_malformed_or_missing_header_is_refused_without_a_parse_exception() {
    Map<String, String> keyring = keyring("k", "whsec_ffffffffffffffffffffffffffffffff");
    for (String bad :
        new String[] {
          null,
          "",
          "v1=deadbeef",
          "t=notanumber,v1=deadbeef",
          "t=1758000000",
          "t=1758000000,v1=nothex!!",
          "t=99999999999999999999,v1=deadbeef"
        }) {
      assertThatThrownBy(
              () -> WebhookSignature.verify(BODY, bad, keyring, Duration.ofMinutes(5), NOW))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.SIGNATURE_INVALID);
    }
  }

  @Test
  void an_empty_keyring_verifies_nothing_rather_than_everything() {
    String signed = header("whsec_ffffffffffffffffffffffffffffffff", NOW, BODY);
    assertThatThrownBy(
            () -> WebhookSignature.verify(BODY, signed, Map.of(), Duration.ofMinutes(5), NOW))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.SIGNATURE_INVALID);
  }

  @Test
  void one_valid_v1_among_several_verifies_the_event() {
    // A rotation window can put more than one v1 element in the header.
    String secret = "whsec_11111111111111111111111111111111";
    long t = NOW.getEpochSecond();
    String signed =
        "t="
            + t
            + ",v1="
            + "0".repeat(64)
            + ",v1="
            + mac(secret, t + "." + new String(BODY, StandardCharsets.UTF_8));
    assertThat(
            WebhookSignature.verify(BODY, signed, keyring("k", secret), Duration.ofMinutes(5), NOW))
        .isEqualTo("k");
  }
}
