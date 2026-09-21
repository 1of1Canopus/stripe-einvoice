package com.housedevinci.einvoice.domain;

import java.time.Instant;

/**
 * One chained row in the issuance log: a number reached a disposition, and this is the proof that
 * it did so under our hand.
 *
 * <p>The log is append-only at the database level and is separate from {@link Issuance}, which has
 * a mutable state column. A chain over a mutable row cannot verify - the row's own later,
 * legitimate update changes the hashed material - so the mapping row holds the current state and
 * this log holds the immutable history. The verifier cross-checks the two: an issuance whose state
 * is disposed and whose latest chained event disagrees is BROKEN, which is how an out-of-band
 * UPDATE by a role that outranks the triggers is caught.
 */
public record IssuanceEvent(
    long sequence,
    Instant timestamp,
    SeriesKey seriesKey,
    String stripeInvoiceId,
    String stripeAccountId,
    String stripeNumber,
    LegalNumber legalNumber,
    IssuanceState state,
    Instant issuedAt,
    String documentSha256,
    String archiveKey,
    String rulePackVersion,
    String voidReason,
    String voidRuleId,
    String chainVersion,
    String keyId,
    String prevHash,
    String hash) {

  public IssuanceEvent withChain(String prevHash, String chainVersion, String keyId, String hash) {
    return new IssuanceEvent(
        sequence,
        timestamp,
        seriesKey,
        stripeInvoiceId,
        stripeAccountId,
        stripeNumber,
        legalNumber,
        state,
        issuedAt,
        documentSha256,
        archiveKey,
        rulePackVersion,
        voidReason,
        voidRuleId,
        chainVersion,
        keyId,
        prevHash,
        hash);
  }

  public IssuanceEvent withSequence(long sequence) {
    return new IssuanceEvent(
        sequence,
        timestamp,
        seriesKey,
        stripeInvoiceId,
        stripeAccountId,
        stripeNumber,
        legalNumber,
        state,
        issuedAt,
        documentSha256,
        archiveKey,
        rulePackVersion,
        voidReason,
        voidRuleId,
        chainVersion,
        keyId,
        prevHash,
        hash);
  }

  /**
   * The disposition event for a number burned by a refusal after it was allocated (D7-03).
   *
   * <p>The refusal's own identifier - a schematron rule id, or the render refusal's code - travels
   * where a void's rule id travels, so the chain itself can tell an auditor why a number is missing
   * from the issued sequence instead of pointing at a mutable row.
   */
  public static IssuanceEvent failed(
      Issuance issuance, IssuanceState state, String ruleId, Instant when) {
    IssuanceEvent base = of(issuance, state, when);
    return new IssuanceEvent(
        base.sequence(),
        base.timestamp(),
        base.seriesKey(),
        base.stripeInvoiceId(),
        base.stripeAccountId(),
        base.stripeNumber(),
        base.legalNumber(),
        base.state(),
        base.issuedAt(),
        base.documentSha256(),
        base.archiveKey(),
        base.rulePackVersion(),
        base.voidReason(),
        ruleId == null || ruleId.isBlank()
            ? base.voidRuleId()
            : Identifiers.validate("failure rule id", ruleId, 64),
        base.chainVersion(),
        base.keyId(),
        base.prevHash(),
        base.hash());
  }

  /** The disposition event for a number that will never carry a document. */
  public static IssuanceEvent voided(Issuance issuance, Instant when) {
    return of(issuance, IssuanceState.VOID_UNUSED, when);
  }

  public static IssuanceEvent of(Issuance issuance, IssuanceState state, Instant when) {
    return new IssuanceEvent(
        0L,
        Timestamps.toStorage(when),
        issuance.seriesKey(),
        issuance.stripeInvoiceId(),
        issuance.stripeAccountId(),
        issuance.stripeNumber(),
        issuance.legalNumber(),
        state,
        Timestamps.toStorage(issuance.issuedAt()),
        issuance.documentSha256(),
        issuance.archiveKey(),
        issuance.rulePackVersion(),
        issuance.voidReason(),
        issuance.voidRuleId(),
        "",
        "",
        "",
        "");
  }
}
