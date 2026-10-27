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
    Optional<Long> correctsIssuanceId,
    Optional<IssueDating> dating) {

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
    dating = dating == null ? Optional.empty() : dating;
    // D-PS1-40: the issue date is derived from the allocation instant, so the two are one value.
    if (dating.isPresent()
        && !dating.get().allocatedAt().equals(Timestamps.toStorage(allocatedAt))) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "an issuance's dating must come from its own allocation instant");
    }
    // The database CHECK says the same thing; the value object refuses it first, so a row read
    // back in any other shape fails loudly instead of travelling (credit-note series design).
    if (seriesKey.documentType().isCreditNote() != correctsIssuanceId.isPresent()) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "a credit note names the invoice it corrects, and an invoice names none");
    }
  }

  /** A row with no recorded dating: the shape of every row written before 0.2.0. */
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
      String voidRuleId,
      Optional<Long> correctsIssuanceId) {
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
        correctsIssuanceId,
        Optional.empty());
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
        Optional.empty(),
        Optional.empty());
  }

  /**
   * BT-2 of this document: the numbering day for a row written by 0.2.0 or later, and for an older
   * row the day of {@code issuedAt} in {@code taxZone}, which is what 0.1.x wrote.
   */
  public java.time.LocalDate issueDateIn(java.time.ZoneId taxZone) {
    return dating
        .map(IssueDating::issueDate)
        .orElseGet(() -> java.time.LocalDate.ofInstant(issuedAt, taxZone));
  }

  /**
   * The content hash the row recorded before its archive write, absent until then. Write-once.
   *
   * <p>On {@code ARCHIVING} and {@code ISSUED} it names the archived bytes. On a {@code
   * FAILED_VALIDATION} row burned after a resumed run (QUESTIONS 38) it names the <em>first</em>
   * bytes, which passed and were not in the archive when the resumed run read the key; the rule id
   * names the refusal of the later bytes, and the run logged both hashes at WARN.
   */
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
