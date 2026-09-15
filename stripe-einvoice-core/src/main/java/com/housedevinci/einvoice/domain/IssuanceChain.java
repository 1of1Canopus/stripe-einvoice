package com.housedevinci.einvoice.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hash chain over {@link IssuanceEvent}s. The sibling modules' audit chain, reused as an algorithm
 * and a canonical form - never as a namespace (numbering design, N-05: the advisory lock constant
 * is this module's own, because advisory lock keys are database-wide and both modules are sold to
 * the same customer for the same database).
 *
 * <p>{@code hash = H(canonical(event, keyId) || prevHash)}, where {@code H} is SHA-256 ({@link
 * #unkeyed()}, version {@code ei1}, key id {@link #UNKEYED_KEY_ID}) or HMAC-SHA-256 with a secret
 * ({@link #keyed(byte[], String)}, version {@code ei2h}). The canonical form is length-prefixed, so
 * no rewrite can move a boundary between two fields without changing the hash, and the key id is
 * inside the hashed material from row 1, so key rotation is data rather than a format break.
 */
public final class IssuanceChain {

  public static final String GENESIS = "0".repeat(64);
  public static final String CANONICAL_VERSION = "ei1";
  public static final String KEYED_VERSION = "ei2h";
  public static final String UNKEYED_KEY_ID = "none";
  public static final int MIN_KEY_BYTES = 32;

  private static final IssuanceChain UNKEYED = new IssuanceChain(null, UNKEYED_KEY_ID);

  private final byte[] key;
  private final String keyId;

  private IssuanceChain(byte[] key, String keyId) {
    this.key = key;
    this.keyId = Objects.requireNonNull(keyId, "keyId");
  }

  public static IssuanceChain unkeyed() {
    return UNKEYED;
  }

  public static IssuanceChain keyed(byte[] secret, String keyId) {
    Objects.requireNonNull(secret, "secret");
    Objects.requireNonNull(keyId, "keyId");
    if (secret.length < MIN_KEY_BYTES) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.chain.hmac-secret must be at least " + MIN_KEY_BYTES + " bytes");
    }
    if (keyId.isBlank() || UNKEYED_KEY_ID.equals(keyId)) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.chain.hmac-key-id must not be blank or '"
              + UNKEYED_KEY_ID
              + "' (reserved for unkeyed trails)");
    }
    return new IssuanceChain(secret.clone(), keyId);
  }

  public boolean isKeyed() {
    return key != null;
  }

  public String keyId() {
    return keyId;
  }

  public String version() {
    return isKeyed() ? KEYED_VERSION : CANONICAL_VERSION;
  }

  static String canonical(IssuanceEvent e, String version, String keyId) {
    StringBuilder sb = new StringBuilder(version);
    field(sb, keyId);
    field(sb, e.timestamp().toString());
    field(sb, e.seriesKey().sellerId());
    field(sb, e.seriesKey().mode().wire());
    field(sb, e.stripeAccountId());
    field(sb, e.seriesKey().series());
    field(sb, Integer.toString(e.seriesKey().fiscalYear()));
    field(sb, e.legalNumber().value());
    field(sb, Long.toString(e.legalNumber().counter()));
    field(sb, e.stripeInvoiceId());
    field(sb, e.stripeNumber());
    field(sb, e.state().name());
    field(sb, e.issuedAt() == null ? null : e.issuedAt().toString());
    field(sb, e.documentSha256());
    field(sb, e.archiveKey());
    field(sb, e.rulePackVersion());
    field(sb, e.voidReason());
    field(sb, e.voidRuleId());
    return sb.toString();
  }

  private static void field(StringBuilder sb, String value) {
    sb.append('|');
    if (value == null) {
      sb.append('-');
      return;
    }
    sb.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
  }

  public String hashOf(IssuanceEvent e, String prevHash) {
    String material = canonical(e, version(), keyId) + "|" + prevHash.length() + ":" + prevHash;
    if (key == null) {
      return Hashes.sha256Hex(material);
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("HmacSHA256 not available", ex);
    }
  }

  public IssuanceEvent link(IssuanceEvent e, String prevHash) {
    return e.withChain(prevHash, version(), keyId, hashOf(e, prevHash));
  }

  public boolean verify(IssuanceEvent e, String expectedPrev) {
    return expectedPrev.equals(e.prevHash()) && hashOf(e, expectedPrev).equals(e.hash());
  }
}
