package com.housedevinci.einvoice.domain.en16931;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Percentage;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The rules EN 16931 attaches to a tax category, a document type and an identifier, held as
 * invariants of the model rather than as a validation step somebody remembers to call.
 *
 * <p>The schematron checks the same things a moment later. That is the point: a rule this module
 * cannot state itself is a rule it is trusting somebody else's stylesheet to remember, and the
 * refusal it produces names a business term rather than quoting a rule id in German.
 */
class ModelInvariantTest {

  @Test
  void a_zero_rate_category_with_no_exemption_reason_is_refused() {
    // D-14: recipients reject a zero-rated line that does not say which rule zero-rated it.
    assertThatThrownBy(
            () ->
                new TaxSubtotal(
                    TaxCategory.REVERSE_CHARGE,
                    Percentage.of("0"),
                    Money.ofMinor(10_000, "EUR"),
                    Money.zero("EUR"),
                    null))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.EXEMPTION_REASON_REQUIRED);
  }

  @Test
  void an_exemption_reason_on_the_wrong_category_is_refused() {
    assertThatThrownBy(
            () ->
                new TaxSubtotal(
                    TaxCategory.EXPORT,
                    Percentage.of("0"),
                    Money.ofMinor(10_000, "EUR"),
                    Money.zero("EUR"),
                    VatexCode.VATEX_EU_AE))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("belongs to tax category AE");
  }

  @Test
  void a_zero_rate_category_carrying_a_rate_is_refused() {
    assertThatThrownBy(() -> TaxCategory.REVERSE_CHARGE.requireRate(new BigDecimal("19")))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("will not assert on the seller's behalf");
  }

  @Test
  void the_standard_category_carrying_a_rate_of_zero_is_refused() {
    // A zero-rated line under S says nothing about which rule zero-rated it, which is what the
    // zero-rate categories and their VATEX reasons exist for.
    assertThatThrownBy(() -> TaxCategory.STANDARD.requireRate(BigDecimal.ZERO))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("with an exemption reason");
  }

  @Test
  void a_zero_rate_bucket_that_nevertheless_charged_tax_is_refused() {
    assertThatThrownBy(
            () ->
                new TaxSubtotal(
                    TaxCategory.EXEMPT,
                    Percentage.of("0"),
                    Money.ofMinor(10_000, "EUR"),
                    Money.ofMinor(100, "EUR"),
                    VatexCode.VATEX_EU_132))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.DOCUMENT_UNBALANCED);
  }

  @Test
  void a_negative_line_amount_is_refused_whatever_the_document_type() {
    assertThatThrownBy(
            () ->
                new DocumentLine(
                    "1",
                    BigDecimal.ONE,
                    DocumentLine.UNIT_PIECE,
                    Money.parse("-1.00", "EUR"),
                    "A line",
                    null,
                    TaxCategory.STANDARD,
                    Percentage.of("19"),
                    null))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.DOCUMENT_TYPE_UNSUPPORTED);
  }

  @Test
  void a_line_with_no_quantity_is_refused_naming_the_stripe_field() {
    assertThatThrownBy(
            () ->
                new DocumentLine(
                    "1",
                    BigDecimal.ZERO,
                    DocumentLine.UNIT_PIECE,
                    Money.ofMinor(100, "EUR"),
                    "A line",
                    null,
                    TaxCategory.STANDARD,
                    Percentage.of("19"),
                    null))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("lines.data.quantity");
  }

  @Test
  void the_untdid_codes_are_the_ones_the_recipients_code_lists_carry() {
    assertThat(TaxCategory.STANDARD.code()).isEqualTo("S");
    assertThat(TaxCategory.REVERSE_CHARGE.code()).isEqualTo("AE");
    assertThat(TaxCategory.INTRA_COMMUNITY.code()).isEqualTo("K");
    assertThat(DocumentTypeCode.COMMERCIAL_INVOICE.code()).isEqualTo("380");
    assertThat(DocumentTypeCode.CREDIT_NOTE.code()).isEqualTo("381");
    assertThat(DocumentTypeCode.CORRECTED_INVOICE.code()).isEqualTo("384");
  }

  @Test
  void a_code_outside_the_set_en16931_restricts_is_refused() {
    assertThatThrownBy(() -> TaxCategory.of("XX"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.TAX_CATEGORY_UNKNOWN);
    assertThatThrownBy(() -> DocumentTypeCode.of("999"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.DOCUMENT_TYPE_UNSUPPORTED);
    assertThatThrownBy(() -> VatexCode.of("VATEX-INVENTED"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("not one it will assert on the seller's behalf");
    assertThat(VatexCode.of("VATEX-EU-AE")).isEqualTo(VatexCode.VATEX_EU_AE);
  }

  @Test
  void a_french_vat_identifier_is_checked_against_its_own_key_and_its_siren_luhn() {
    VatIdentifier valid = VatIdentifier.parse("a VAT identifier", "FR25 900 000 019");
    assertThat(valid.value()).isEqualTo("FR25900000019");
    assertThat(valid.checkDigitVerified()).isTrue();
    assertThat(valid.issuingCountry()).isEqualTo("FR");

    assertThatThrownBy(() -> VatIdentifier.parse("a VAT identifier", "FR26900000019"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("does not agree with its SIREN");
    assertThatThrownBy(() -> VatIdentifier.parse("a VAT identifier", "FR25900000018"))
        .isInstanceOf(EInvoiceException.class)
        .isInstanceOf(EInvoiceException.class);
  }

  @Test
  void an_identifier_this_module_cannot_check_is_accepted_and_reported_as_not_verified() {
    // D-06: "an unvalidatable identifier is reported as not-verified rather than passed". Germany
    // has no check digit this module can compute, so the shape is all that is claimed.
    VatIdentifier german = VatIdentifier.parse("a VAT identifier", "DE123456789");
    assertThat(german.checkDigitVerified()).isFalse();
    assertThat(german.issuingCountry()).isEqualTo("DE");
  }

  @Test
  void a_vat_identifier_with_no_resolvable_country_or_the_wrong_length_is_refused() {
    assertThatThrownBy(() -> VatIdentifier.parse("a VAT identifier", "ZZ123456789"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("country prefix");
    assertThatThrownBy(() -> VatIdentifier.parse("a VAT identifier", "DE1234"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("that country issues between");
  }

  @Test
  void a_vat_identifier_never_prints_itself() {
    // D-17: an identifier is a party's identity and belongs in no log line.
    assertThat(VatIdentifier.parse("a VAT identifier", "DE123456789").toString())
        .doesNotContain("123456789");
  }

  @Test
  void a_gln_is_checked_against_its_gs1_check_digit() {
    assertThat(new PartyIdentifier("0088", "4260000000004").value()).isEqualTo("4260000000004");
    assertThatThrownBy(() -> new PartyIdentifier("0088", "4260000000001"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("GS1 check digit");
  }

  @Test
  void an_iban_is_checked_against_its_mod_97_and_never_printed() {
    PaymentInstruction ok = new PaymentInstruction("58", "DE02 1203 0000 0000 2020 51", null, null);
    assertThat(ok.accountIdentifier()).isEqualTo("DE02120300000000202051");
    assertThat(ok.toString()).doesNotContain("DE02");
    assertThatThrownBy(() -> new PaymentInstruction("58", "DE02120300000000202052", null, null))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("mod-97");
  }

  @Test
  void a_payment_means_code_is_a_untdid_code_and_not_free_text() {
    assertThatThrownBy(() -> new PaymentInstruction("bank", "DE02120300000000202051", null, null))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("UNTDID 4461");
  }

  @Test
  void a_country_code_is_checked_against_the_iso_list_and_not_against_a_pattern() {
    assertThat(CountryCode.validate("country", " de ")).isEqualTo("DE");
    assertThatThrownBy(() -> CountryCode.validate("country", "XX"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("reject the document days after it was filed");
    assertThat(CountryCode.isEuVatArea("FR")).isTrue();
    assertThat(CountryCode.isEuVatArea("CH")).isFalse();
  }

  @Test
  void an_electronic_address_scheme_is_a_code_and_not_free_text() {
    assertThatThrownBy(() -> new PartyIdentifier("email address", "x@y.invalid"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("ISO 6523 or EAS code");
  }

  @Test
  void an_electronic_address_derived_from_a_country_with_no_recorded_scheme_is_refused() {
    // Inventing a scheme fails PEPPOL-EN16931-CL008 at the access point instead of here.
    assertThatThrownBy(() -> PartyIdentifier.fromVatIdentifier("CH", "CHE123456789"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("einvoice.documents.buyer-electronic-address");
    assertThat(PartyIdentifier.fromVatIdentifier("FR", "FR25900000019").scheme()).isEqualTo("9957");
  }
}
