package com.housedevinci.einvoice.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe's webhook signature, verified over the <b>exact bytes received</b>, against a keyring.
 *
 * <p><b>Why this module computes the HMAC itself</b> rather than calling the SDK's helper. The
 * helper takes the payload as a {@code String}: getting one means the container already decoded the
 * request with the request's charset, and a body that is not valid UTF-8 comes back changed - the
 * check then fails on bytes Stripe really sent, or passes on bytes nobody sent (D-19, checklist
 * line 50). The helper also takes one secret, while Stripe's own rotation procedure leaves two
 * live, so a single-secret verifier means either downtime or a rotation nobody performs (D-11).
 * Both are properties of the boundary, not of the SDK, so the boundary owns them: this is a length-
 * prefixed HMAC-SHA256 over {@code t + "." + body} and a constant-time comparison, and nothing
 * else.
 *
 * <p>Nothing here is ever logged, echoed or put in a message: not the header, not a secret, not the
 * body, not which id failed (checklist line 45).
 */
public final class WebhookSignature {

  /** The header Stripe sends. Never logged, at any level. */
  public static final String HEADER = "Stripe-Signature";

  private static final int SIGNATURE_HEX_LENGTH = 64;

  private WebhookSignature() {}

  /**
   * Verifies the body against every active secret and returns the id of the one that verified.
   *
   * @param body the exact bytes received, never a re-encoding
   * @param header the {@code Stripe-Signature} header value
   * @param secretsById the active keyring: every id is tried, so a rotation window verifies
   * @param tolerance how far the signed timestamp may sit from now, in either direction
   * @param now from an injected {@link java.time.Clock}, never a direct clock reading
   * @throws EInvoiceException {@link ErrorCodes#SIGNATURE_INVALID} when nothing verifies
   */
  public static String verify(
      byte[] body,
      String header,
      Map<String, String> secretsById,
      Duration tolerance,
      Instant now) {
    if (body == null || header == null || header.isBlank() || secretsById == null) {
      throw refused();
    }
    long timestamp = timestampOf(header);
    long skew = Math.abs(now.getEpochSecond() - timestamp);
    if (skew > Math.max(0, tolerance.toSeconds())) {
      // Distinguished in the message but not in the code: a caller must answer 400 either way, and
      // an endpoint that tells the internet which half of the check failed is a free oracle.
      throw new EInvoiceException(
          ErrorCodes.SIGNATURE_INVALID,
          "the signed timestamp is outside the configured tolerance"
              + " (einvoice.webhook.tolerance)");
    }
    List<byte[]> presented = signaturesOf(header);
    if (presented.isEmpty()) {
      throw refused();
    }
    byte[] signedPayload = signedPayload(timestamp, body);
    for (Map.Entry<String, String> entry : secretsById.entrySet()) {
      byte[] expected = hmacSha256(entry.getValue(), signedPayload);
      for (byte[] candidate : presented) {
        if (MessageDigest.isEqual(expected, candidate)) {
          return entry.getKey();
        }
      }
    }
    throw refused();
  }

  private static byte[] signedPayload(long timestamp, byte[] body) {
    byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
    byte[] payload = new byte[prefix.length + body.length];
    System.arraycopy(prefix, 0, payload, 0, prefix.length);
    System.arraycopy(body, 0, payload, prefix.length, body.length);
    return payload;
  }

  private static long timestampOf(String header) {
    for (String element : header.split(",")) {
      String[] pair = element.split("=", 2);
      if (pair.length == 2 && "t".equals(pair[0].strip())) {
        try {
          return Long.parseLong(pair[1].strip());
        } catch (NumberFormatException notANumber) {
          throw refused();
        }
      }
    }
    throw refused();
  }

  private static List<byte[]> signaturesOf(String header) {
    List<byte[]> signatures = new ArrayList<>();
    for (String element : header.split(",")) {
      String[] pair = element.split("=", 2);
      if (pair.length != 2 || !"v1".equals(pair[0].strip())) {
        continue;
      }
      String hex = pair[1].strip();
      if (hex.length() != SIGNATURE_HEX_LENGTH) {
        continue;
      }
      try {
        signatures.add(HexFormat.of().parseHex(hex));
      } catch (IllegalArgumentException notHex) {
        // A malformed element is skipped, never fatal: the header may legitimately carry elements
        // of a scheme we do not implement, and only a v1 that verifies is a pass.
      }
    }
    return signatures;
  }

  private static byte[] hmacSha256(String secret, byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(payload);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  private static EInvoiceException refused() {
    return new EInvoiceException(
        ErrorCodes.SIGNATURE_INVALID,
        "no active webhook secret verifies this request over the bytes received");
  }
}
