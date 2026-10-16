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
 *
 * <p><b>Two forms per family (credit-note series design, section 3).</b> 0.2.0 writes {@code ei3}
 * (unkeyed) and {@code ei4h} (keyed): the 0.1.0 field list plus the document type, beside the
 * series, and the full identity of the invoice a credit note corrects (D-PS1-37). The older forms
 * {@code ei1} and {@code ei2h} are still verified, never written. The version is the first element
 * of the hashed material, so a row cannot be re-labelled with an older form to hide a field without
 * breaking its own hash. An older-form row is an invoice by definition: it hashes no type, so a
 * reader that found anything else on it would be reading a rewrite (S-01).
 *
 * <p>What versioning does not buy on an unkeyed trail (S-10): a role that can bypass the triggers
 * can re-chain an {@code ei3} trail wholesale after rewriting it, because the anchor's own
 * monotonic guard is a trigger too. The new form is a real control for {@code ei4h}, and for {@code
 * ei3} only where the anchor head is also witnessed outside this database.
 */
public final class IssuanceChain {

  public static final String GENESIS = "0".repeat(64);
  public static final String CANONICAL_VERSION = "ei1";
  public static final String KEYED_VERSION = "ei2h";

  /** The unkeyed form 0.2.0 writes: {@link #CANONICAL_VERSION} plus the type and the target. */
  public static final String TYPED_VERSION = "ei3";

  /** The keyed form 0.2.0 writes: {@link #KEYED_VERSION} plus the type and the target. */
  public static final String TYPED_KEYED_VERSION = "ei4h";

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

  /** The form this chain writes: {@link #TYPED_KEYED_VERSION} or {@link #TYPED_VERSION}. */
  public String version() {
    return isKeyed() ? TYPED_KEYED_VERSION : TYPED_VERSION;
  }

  /** True when {@code version} is a form of this chain's family that a verifier accepts. */
  public boolean accepts(String version) {
    return isKeyed()
        ? KEYED_VERSION.equals(version) || TYPED_KEYED_VERSION.equals(version)
        : CANONICAL_VERSION.equals(version) || TYPED_VERSION.equals(version);
  }

  /** True for the 0.1.0 forms, which hash no document type and no correction target. */
  public static boolean untypedForm(String version) {
    return CANONICAL_VERSION.equals(version) || KEYED_VERSION.equals(version);
  }

  static String canonical(IssuanceEvent e, String version, String keyId) {
    boolean typed = !untypedForm(version);
    StringBuilder sb = new StringBuilder(version);
    field(sb, keyId);
    field(sb, e.timestamp().toString());
    field(sb, e.seriesKey().sellerId());
    field(sb, e.seriesKey().mode().wire());
    field(sb, e.stripeAccountId());
    if (typed) {
      field(sb, e.seriesKey().documentTypeCode());
    }
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
    if (typed) {
      // D-PS1-37: the whole identity, never the surrogate id alone. An invoice carries six null
      // markers here, so a target added to an invoice's event changes its hash as well.
      CorrectionTarget t = e.corrects().orElse(null);
      field(sb, t == null ? null : Long.toString(t.issuanceId()));
      field(sb, t == null ? null : t.sellerId());
      field(sb, t == null ? null : t.mode().wire());
      field(sb, t == null ? null : t.series());
      field(sb, t == null ? null : Integer.toString(t.fiscalYear()));
      field(sb, t == null ? null : t.legalNumber());
    }
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
    return hashOf(e, prevHash, version());
  }

  private String hashOf(IssuanceEvent e, String prevHash, String version) {
    String material = canonical(e, version, keyId) + "|" + prevHash.length() + ":" + prevHash;
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

  /**
   * Recomputes a row in the form the row itself claims, provided that form belongs to this chain's
   * family. An older-form row that carries a credit-note type or a target is refused here: it
   * hashes neither, so the value can only have been written after the row was (S-01).
   */
  public boolean verify(IssuanceEvent e, String expectedPrev) {
    String version = e.chainVersion();
    if (!accepts(version)) {
      return false;
    }
    if (untypedForm(version)
        && (e.seriesKey().documentType() != CorrectionTarget.requiredType()
            || e.corrects().isPresent())) {
      return false;
    }
    return expectedPrev.equals(e.prevHash()) && hashOf(e, expectedPrev, version).equals(e.hash());
  }
}
