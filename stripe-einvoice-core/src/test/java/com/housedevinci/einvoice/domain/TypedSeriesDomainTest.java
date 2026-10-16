package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** The typed series and the corrected-invoice link in the domain, with no database. */
class TypedSeriesDomainTest {

  private static final IssuanceChain KEYED =
      IssuanceChain.keyed(
          "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "k1");
  private static final Instant WHEN = Instant.parse("2026-03-10T10:00:00Z");
  private static final CorrectionTarget TARGET =
      new CorrectionTarget(7, "seller", Mode.LIVE, "INV", 2026, "INV-2026-000001");

  private static SeriesKey creditKey() {
    return new SeriesKey("seller", "CN", 2026, Mode.LIVE, DocumentTypeCode.CREDIT_NOTE);
  }

  private static IssuanceEvent creditEvent(CorrectionTarget target) {
    return new IssuanceEvent(
        0,
        WHEN,
        creditKey(),
        "cn_1",
        "",
        "",
        new LegalNumber("CN-2026-000001", 1),
        IssuanceState.ISSUED,
        WHEN,
        "a".repeat(64),
        "k",
        "r1",
        "",
        "",
        "",
        "",
        "",
        "",
        Optional.of(target));
  }

  @Test
  void the_four_argument_key_is_an_invoice_series_and_384_has_no_series() {
    assertThat(new SeriesKey("seller", "INV", 2026, Mode.LIVE).documentType())
        .isEqualTo(DocumentTypeCode.COMMERCIAL_INVOICE);
    assertThatThrownBy(
            () -> new SeriesKey("seller", "X", 2026, Mode.LIVE, DocumentTypeCode.CORRECTED_INVOICE))
        .extracting("code")
        .isEqualTo(ErrorCodes.DOCUMENT_TYPE_UNSUPPORTED);
  }

  @Test
  void a_credit_note_names_its_invoice_and_an_invoice_names_none() {
    LegalNumber n = new LegalNumber("CN-2026-000001", 1);
    assertThatThrownBy(
            () ->
                new Issuance(
                    1,
                    creditKey(),
                    "cn_1",
                    "",
                    "",
                    n,
                    WHEN,
                    WHEN,
                    "",
                    "",
                    "",
                    IssuanceState.NUMBERED,
                    "",
                    ""))
        .extracting("code")
        .isEqualTo(ErrorCodes.INVALID);
    assertThatThrownBy(
            () ->
                new Issuance(
                    1,
                    new SeriesKey("seller", "INV", 2026, Mode.LIVE),
                    "in_1",
                    "",
                    "",
                    n,
                    WHEN,
                    WHEN,
                    "",
                    "",
                    "",
                    IssuanceState.NUMBERED,
                    "",
                    "",
                    Optional.of(7L)))
        .extracting("code")
        .isEqualTo(ErrorCodes.INVALID);
  }

  @Test
  void every_field_of_the_corrected_invoice_identity_is_hashed() {
    // D-PS1-37: not the surrogate id alone. Each field, changed on its own, changes the hash.
    String base = KEYED.hashOf(creditEvent(TARGET), IssuanceChain.GENESIS);
    List<UnaryOperator<CorrectionTarget>> changes =
        List.of(
            t ->
                new CorrectionTarget(
                    8, t.sellerId(), t.mode(), t.series(), t.fiscalYear(), t.legalNumber()),
            t ->
                new CorrectionTarget(
                    t.issuanceId(), "other", t.mode(), t.series(), t.fiscalYear(), t.legalNumber()),
            t ->
                new CorrectionTarget(
                    t.issuanceId(),
                    t.sellerId(),
                    Mode.TEST,
                    t.series(),
                    t.fiscalYear(),
                    t.legalNumber()),
            t ->
                new CorrectionTarget(
                    t.issuanceId(),
                    t.sellerId(),
                    t.mode(),
                    "INV2",
                    t.fiscalYear(),
                    t.legalNumber()),
            t ->
                new CorrectionTarget(
                    t.issuanceId(), t.sellerId(), t.mode(), t.series(), 2027, t.legalNumber()),
            t ->
                new CorrectionTarget(
                    t.issuanceId(),
                    t.sellerId(),
                    t.mode(),
                    t.series(),
                    t.fiscalYear(),
                    "INV-2026-000002"));
    for (UnaryOperator<CorrectionTarget> change : changes) {
      assertThat(KEYED.hashOf(creditEvent(change.apply(TARGET)), IssuanceChain.GENESIS))
          .isNotEqualTo(base);
    }
  }

  @Test
  void the_typed_form_hashes_the_document_type() {
    IssuanceEvent credit = KEYED.link(creditEvent(TARGET), IssuanceChain.GENESIS);
    assertThat(credit.chainVersion()).isEqualTo(IssuanceChain.TYPED_KEYED_VERSION);
    assertThat(KEYED.verify(credit, IssuanceChain.GENESIS)).isTrue();
    IssuanceEvent asInvoice =
        new IssuanceEvent(
            credit.sequence(),
            credit.timestamp(),
            new SeriesKey("seller", "CN", 2026, Mode.LIVE),
            credit.stripeInvoiceId(),
            credit.stripeAccountId(),
            credit.stripeNumber(),
            credit.legalNumber(),
            credit.state(),
            credit.issuedAt(),
            credit.documentSha256(),
            credit.archiveKey(),
            credit.rulePackVersion(),
            credit.voidReason(),
            credit.voidRuleId(),
            credit.chainVersion(),
            credit.keyId(),
            credit.prevHash(),
            credit.hash());
    assertThat(KEYED.verify(asInvoice, IssuanceChain.GENESIS)).isFalse();
  }

  @Test
  void an_older_form_row_that_claims_a_credit_note_is_refused_whatever_its_hash() {
    // S-01: ei2h hashes no type, so a credit-note type on it can only have been written later.
    IssuanceEvent credit = creditEvent(TARGET);
    IssuanceEvent relabelled =
        credit.withChain(IssuanceChain.GENESIS, IssuanceChain.KEYED_VERSION, "k1", "x");
    assertThat(KEYED.verify(relabelled, IssuanceChain.GENESIS)).isFalse();
    assertThat(KEYED.accepts(IssuanceChain.KEYED_VERSION)).isTrue();
    assertThat(KEYED.accepts(IssuanceChain.CANONICAL_VERSION)).isFalse();
    assertThat(IssuanceChain.unkeyed().accepts(IssuanceChain.TYPED_VERSION)).isTrue();
    assertThat(IssuanceChain.unkeyed().accepts(IssuanceChain.TYPED_KEYED_VERSION)).isFalse();
  }
}
