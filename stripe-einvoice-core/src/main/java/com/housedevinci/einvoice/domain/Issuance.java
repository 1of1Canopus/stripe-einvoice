package com.housedevinci.einvoice.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * One sale, one number, for all time: the mapping row between a Stripe invoice and the legal number
 * this module allocated for it.
 *
 * <p>Append plus a state column, and nothing else. The immutable half is written once at allocation
 * and refused by a database trigger afterwards; the mutable half is the state, the archived
 * document's hash and key (write-once from the moment they are non-empty), and the void reason.
 */
public record Issuance(
    long id,
    SeriesKey seriesKey,
    String stripeInvoiceId,
    String stripeAccountId,
    String stripeNumber,
    LegalNumber legalNumber,
    Instant issuedAt,
    Instant allocatedAt,
    String documentSha256,
    String archiveKey,
    String rulePackVersion,
    IssuanceState state,
    String voidReason,
    String voidRuleId,
    Optional<Long> correctsIssuanceId) {

  public Issuance {
    Identifiers.validate("stripe invoice id", stripeInvoiceId);
    if (seriesKey == null || legalNumber == null || state == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "an issuance needs a series key, a legal number and a state");
    }
    if (allocatedAt == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an issuance needs an allocation timestamp");
    }
    stripeAccountId = stripeAccountId == null ? "" : stripeAccountId;
    stripeNumber = stripeNumber == null ? "" : stripeNumber;
    documentSha256 = documentSha256 == null ? "" : documentSha256;
    archiveKey = archiveKey == null ? "" : archiveKey;
    rulePackVersion = rulePackVersion == null ? "" : rulePackVersion;
    voidReason = voidReason == null ? "" : voidReason;
    voidRuleId = voidRuleId == null ? "" : voidRuleId;
    correctsIssuanceId = correctsIssuanceId == null ? Optional.empty() : correctsIssuanceId;
    // The database CHECK says the same thing; the value object refuses it first, so a row read
    // back in any other shape fails loudly instead of travelling (credit-note series design).
    if (seriesKey.documentType().isCreditNote() != correctsIssuanceId.isPresent()) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "a credit note names the invoice it corrects, and an invoice names none");
    }
  }

  /** An invoice row, which corrects nothing: the shape every 0.1.0 row has. */
  public Issuance(
      long id,
      SeriesKey seriesKey,
      String stripeInvoiceId,
      String stripeAccountId,
      String stripeNumber,
      LegalNumber legalNumber,
      Instant issuedAt,
      Instant allocatedAt,
      String documentSha256,
      String archiveKey,
      String rulePackVersion,
      IssuanceState state,
      String voidReason,
      String voidRuleId) {
    this(
        id,
        seriesKey,
        stripeInvoiceId,
        stripeAccountId,
        stripeNumber,
        legalNumber,
        issuedAt,
        allocatedAt,
        documentSha256,
        archiveKey,
        rulePackVersion,
        state,
        voidReason,
        voidRuleId,
        Optional.empty());
  }

  /** The archived document's content hash, absent while the number has no document. */
  public Optional<String> documentHash() {
    return documentSha256.isEmpty() ? Optional.empty() : Optional.of(documentSha256);
  }

  public Optional<String> archiveObjectKey() {
    return archiveKey.isEmpty() ? Optional.empty() : Optional.of(archiveKey);
  }

  /** The reason an operator gave for voiding, absent unless this number was voided. */
  public Optional<String> voidedReason() {
    return voidReason.isEmpty() ? Optional.empty() : Optional.of(voidReason);
  }

  /**
   * The validation rule whose failure caused the void, when there was one. Its rate is the honest
   * measure of how close to gap-free this module runs, so it is recorded rather than inferred.
   */
  public Optional<String> voidedRuleId() {
    return voidRuleId.isEmpty() ? Optional.empty() : Optional.of(voidRuleId);
  }
}
