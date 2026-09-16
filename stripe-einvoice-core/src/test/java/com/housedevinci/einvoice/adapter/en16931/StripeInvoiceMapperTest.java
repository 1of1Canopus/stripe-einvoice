package com.housedevinci.einvoice.adapter.en16931;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Percentage;
import com.housedevinci.einvoice.domain.Totals;
import com.housedevinci.einvoice.domain.en16931.EnInvoice;
import com.housedevinci.einvoice.domain.en16931.TaxCategory;
import com.housedevinci.einvoice.domain.en16931.VatexCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The mapper: what it takes from the authoritative invoice, and what it refuses by name.
 *
 * <p>"Unmapped-but-required fields fail with a typed error naming the Stripe field to fill" is the
 * spec's own sentence, and every refusal below is a case of it. The alternative in each case is a
 * document with a blank element, which passes a whitespace-insensitive schema check and fails a
 * business rule at the recipient - days later, where nobody can fix it.
 */
class StripeInvoiceMapperTest {

  private static EnInvoice map(DocumentInput input, UblProfile profile) {
    return new StripeInvoiceMapper(DocumentFixtures.germanSeller(), profile, null).map(input);
  }

  @Test
  void the_legal_number_is_ours_and_stripes_is_a_reference() {
    // Decision 2: we allocate the legal number; Stripe's own is stored and printed as a reference.
    EnInvoice invoice = map(DocumentFixtures.germanStandardRated(), UblProfile.XRECHNUNG_UBL);
    assertThat(invoice.number()).isEqualTo(DocumentFixtures.NUMBER.value());
    assertThat(invoice.upstreamNumberValue()).contains("STRIPE-DE-0007");
  }

  @Test
  void the_issue_date_comes_from_the_input_and_never_from_a_clock() {
    // D-15. The invoice was finalised at 23:30 UTC on 31 March; the seller's tax zone puts it in
    // the next day, and therefore in the next quarter. Nothing here reads a clock to decide that.
    EnInvoice invoice = map(DocumentFixtures.germanStandardRated(), UblProfile.XRECHNUNG_UBL);
    assertThat(invoice.issueDate()).isEqualTo(DocumentFixtures.ISSUE_DATE);
  }

  @Test
  void the_buyer_is_the_frozen_invoice_field_and_carries_no_email() {
    // I-02, and D-17: customer_email is buyer PII that no business term here needs.
    EnInvoice invoice = map(DocumentFixtures.germanStandardRated(), UblProfile.XRECHNUNG_UBL);
    assertThat(invoice.buyer().name()).isEqualTo("Elbe Maschinenbau AG");
    assertThat(invoice.buyer().contactValue()).isEmpty();
  }

  @Test
  void the_buyer_electronic_address_is_derived_from_the_vat_identifier_with_a_peppol_scheme() {
    EnInvoice invoice = map(DocumentFixtures.germanStandardRated(), UblProfile.PEPPOL_BIS_UBL);
    assertThat(invoice.buyer().electronicAddressValue())
        .get()
        .extracting("scheme", "value")
        .containsExactly("9930", "DE987654321");
  }

  @Test
  void the_quantity_and_the_base_quantity_make_the_line_identity_exact() {
    EnInvoice invoice = map(DocumentFixtures.germanStandardRated(), UblProfile.XRECHNUNG_UBL);
    assertThat(invoice.lines()).hasSize(1);
    assertThat(invoice.lines().get(0).quantity()).isEqualByComparingTo("3");
    assertThat(invoice.lines().get(0).netAmount().toPlainString()).isEqualTo("1500.00");
  }

  @Test
  void a_reverse_charge_gets_its_category_and_its_vatex_reason_from_the_upstream_reason() {
    EnInvoice invoice = map(DocumentFixtures.reverseCharge(), UblProfile.XRECHNUNG_UBL);
    assertThat(invoice.taxSubtotals()).hasSize(1);
    assertThat(invoice.taxSubtotals().get(0).category()).isEqualTo(TaxCategory.REVERSE_CHARGE);
    assertThat(invoice.taxSubtotals().get(0).exemptionReasonValue())
        .contains(VatexCode.VATEX_EU_AE);
    assertThat(invoice.taxSubtotals().get(0).taxAmount().isZero()).isTrue();
    assertThat(invoice.hasReverseChargeOrIntraCommunity()).isTrue();
  }

  @Test
  void a_line_with_no_description_is_refused_naming_the_stripe_field() {
    DocumentInput input = withLineDescription(null);
    assertThatThrownBy(() -> map(input, UblProfile.XRECHNUNG_UBL))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.MAPPING_INCOMPLETE);
  }

  @Test
  void a_line_description_past_the_business_term_is_refused_rather_than_truncated() {
    DocumentInput input = withLineDescription("W".repeat(201));
    assertThatThrownBy(() -> map(input, UblProfile.XRECHNUNG_UBL))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("lines.data.description")
        .hasMessageContaining("change what the seller said they sold");
  }

  @Test
  void a_buyer_with_no_country_is_refused_naming_the_stripe_field() {
    DocumentInput input = withBuyerCountry("");
    assertThatThrownBy(() -> map(input, UblProfile.XRECHNUNG_UBL))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("customer_address.country");
  }

  @Test
  void a_buyer_country_that_is_not_an_iso_code_is_refused_before_the_recipient_sees_it() {
    DocumentInput input = withBuyerCountry("XX");
    assertThatThrownBy(() -> map(input, UblProfile.XRECHNUNG_UBL))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.IDENTIFIER_MALFORMED);
  }

  @Test
  void a_tax_rate_the_invoice_reports_no_treatment_for_is_refused() {
    DocumentInput base = DocumentFixtures.germanStandardRated();
    SourceInvoice invoice = base.invoice();
    SourceInvoice stripped = rebuild(invoice, invoice.lines(), invoice.taxBuckets(), List.of());
    DocumentInput input = reinput(base, stripped);
    assertThatThrownBy(() -> map(input, UblProfile.XRECHNUNG_UBL))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("lines.data.taxes.taxability_reason");
  }

  @Test
  void a_zero_rate_with_a_reason_this_pack_cannot_name_is_refused_rather_than_guessed() {
    DocumentInput base = DocumentFixtures.reverseCharge();
    SourceInvoice invoice = base.invoice();
    SourceInvoice unknown =
        rebuild(
            invoice,
            invoice.lines(),
            invoice.taxBuckets(),
            List.of(
                new SourceInvoice.SourceTaxTreatment(
                    "txr_rc0", "DE", "vat", "a_reason_nobody_has_coded")));
    DocumentInput input = reinput(base, unknown);
    assertThatThrownBy(() -> map(input, UblProfile.XRECHNUNG_UBL))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.TAX_CATEGORY_UNKNOWN);
  }

  @Test
  void a_reverse_charge_between_parties_in_one_country_is_refused_not_recategorised() {
    // The upstream's reason and the parties' countries are independent facts. This module does not
    // decide which of the two is wrong.
    DocumentInput base = DocumentFixtures.reverseCharge();
    SourceInvoice invoice = base.invoice();
    SourceInvoice.SourceParty buyer = invoice.buyer();
    SourceInvoice sameCountry =
        new SourceInvoice(
            invoice.id(),
            invoice.number(),
            invoice.accountId(),
            invoice.livemode(),
            invoice.currency(),
            invoice.status(),
            invoice.finalizedAt(),
            new SourceInvoice.SourceParty(
                buyer.name(),
                buyer.email(),
                buyer.line1(),
                buyer.line2(),
                "28217",
                "Bremen",
                "DE",
                "DE987654321"),
            invoice.lines(),
            invoice.taxBuckets(),
            invoice.taxTreatments(),
            invoice.subtotalMinor(),
            invoice.taxMinor(),
            invoice.totalMinor(),
            true);
    DocumentInput input = reinput(base, sameCountry);
    assertThatThrownBy(() -> map(input, UblProfile.XRECHNUNG_UBL))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("both parties are established in the same country");
  }

  @Test
  void a_rate_above_zero_with_an_exemption_reason_is_refused() {
    DocumentInput base = DocumentFixtures.germanStandardRated();
    SourceInvoice invoice = base.invoice();
    SourceInvoice contradictory =
        rebuild(
            invoice,
            invoice.lines(),
            invoice.taxBuckets(),
            List.of(
                new SourceInvoice.SourceTaxTreatment("txr_de19", "DE", "vat", "reverse_charge")));
    DocumentInput input = reinput(base, contradictory);
    assertThatThrownBy(() -> map(input, UblProfile.XRECHNUNG_UBL))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("not one this pack writes as a standard-rated line");
  }

  @Test
  void an_unsupported_currency_is_refused_at_mapping_time() {
    DocumentInput base = DocumentFixtures.germanStandardRated();
    SourceInvoice invoice = base.invoice();
    SourceInvoice exotic =
        new SourceInvoice(
            invoice.id(),
            invoice.number(),
            invoice.accountId(),
            invoice.livemode(),
            "xyz",
            invoice.status(),
            invoice.finalizedAt(),
            invoice.buyer(),
            invoice.lines(),
            invoice.taxBuckets(),
            invoice.taxTreatments(),
            invoice.subtotalMinor(),
            invoice.taxMinor(),
            invoice.totalMinor(),
            true);
    DocumentInput input = reinput(base, exotic);
    assertThatThrownBy(() -> map(input, UblProfile.XRECHNUNG_UBL))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.UNSUPPORTED_CURRENCY);
  }

  @Test
  void a_document_whose_totals_do_not_balance_cannot_be_constructed_at_all() {
    // BR-CO-10, as an invariant rather than as a validation step. The scalars here agree with each
    // other and disagree with the lines - exactly what a schema check would let through.
    DocumentInput base = DocumentFixtures.germanStandardRated();
    SourceInvoice invoice = base.invoice();
    SourceInvoice unbalanced =
        new SourceInvoice(
            invoice.id(),
            invoice.number(),
            invoice.accountId(),
            invoice.livemode(),
            invoice.currency(),
            invoice.status(),
            invoice.finalizedAt(),
            invoice.buyer(),
            List.of(
                new SourceInvoice.SourceLine(
                    "1", "Wartungsvertrag", "txr_de19", 3L, 140_000, 166_600)),
            invoice.taxBuckets(),
            invoice.taxTreatments(),
            150_000,
            28_500,
            178_500,
            true);
    DocumentInput input = reinput(base, unbalanced);
    assertThatThrownBy(() -> map(input, UblProfile.XRECHNUNG_UBL))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.DOCUMENT_UNBALANCED);
  }

  @Test
  void the_rule_pack_records_its_id_and_its_version() {
    // D-14: the pack in force is recorded so a re-validation years from now reads that one.
    assertThat(TaxTreatmentRules.PACK_ID).isEqualTo("en16931-eu-vat");
    assertThat(TaxTreatmentRules.PACK_VERSION).isNotBlank();
    assertThat(DocumentFixtures.germanStandardRated().rulePackVersion())
        .contains(TaxTreatmentRules.PACK_ID, TaxTreatmentRules.PACK_VERSION);
  }

  private static DocumentInput withLineDescription(String description) {
    DocumentInput base = DocumentFixtures.germanStandardRated();
    SourceInvoice invoice = base.invoice();
    SourceInvoice.SourceLine line = invoice.lines().get(0);
    return reinput(
        base,
        rebuild(
            invoice,
            List.of(
                new SourceInvoice.SourceLine(
                    line.id(),
                    description,
                    line.taxRateId(),
                    line.quantity(),
                    line.netMinor(),
                    line.grossMinor())),
            invoice.taxBuckets(),
            invoice.taxTreatments()));
  }

  private static DocumentInput withBuyerCountry(String country) {
    DocumentInput base = DocumentFixtures.germanStandardRated();
    SourceInvoice invoice = base.invoice();
    SourceInvoice.SourceParty buyer = invoice.buyer();
    SourceInvoice changed =
        new SourceInvoice(
            invoice.id(),
            invoice.number(),
            invoice.accountId(),
            invoice.livemode(),
            invoice.currency(),
            invoice.status(),
            invoice.finalizedAt(),
            new SourceInvoice.SourceParty(
                buyer.name(),
                buyer.email(),
                buyer.line1(),
                buyer.line2(),
                buyer.postalCode(),
                buyer.city(),
                country,
                buyer.taxId()),
            invoice.lines(),
            invoice.taxBuckets(),
            invoice.taxTreatments(),
            invoice.subtotalMinor(),
            invoice.taxMinor(),
            invoice.totalMinor(),
            true);
    return reinput(base, changed);
  }

  private static SourceInvoice rebuild(
      SourceInvoice invoice,
      List<SourceInvoice.SourceLine> lines,
      List<Totals.Bucket> buckets,
      List<SourceInvoice.SourceTaxTreatment> treatments) {
    return new SourceInvoice(
        invoice.id(),
        invoice.number(),
        invoice.accountId(),
        invoice.livemode(),
        invoice.currency(),
        invoice.status(),
        invoice.finalizedAt(),
        invoice.buyer(),
        lines,
        buckets,
        treatments,
        invoice.subtotalMinor(),
        invoice.taxMinor(),
        invoice.totalMinor(),
        true);
  }

  private static DocumentInput reinput(DocumentInput base, SourceInvoice invoice) {
    return new DocumentInput(
        invoice, base.seriesKey(), base.legalNumber(), base.issueDate(), base.rulePackVersion());
  }

  /** A percentage is bounded before it multiplies anything (checklist lines 1, 2 and 4). */
  @Test
  void a_rate_written_in_exponent_notation_never_reaches_a_multiplication() {
    assertThatThrownBy(() -> Percentage.of("1E+2000"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.AMOUNT_OUT_OF_BOUNDS);
  }
}
