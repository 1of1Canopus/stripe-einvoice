package com.housedevinci.einvoice.adapter.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.domain.en16931.SellerProfile;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Every fixture, through its profile's <b>official</b> validator chain: the vendored UBL 2.1
 * schema, the CEN EN 16931 schematron, and the profile's own rules.
 *
 * <p>This is the test the writers are written against. "Produces output the reference validators
 * accept" is the only claim this module makes about conformance, and it is worth nothing unless the
 * reference validators are the ones that run here.
 */
class OfficialValidatorTest {

  private static byte[] render(DocumentInput input, SellerProfile seller, UblProfile profile) {
    DocumentRenderer renderer = new En16931DocumentRenderer(seller, profile, null);
    return renderer.render(input).bytes();
  }

  private static DocumentValidator.Report validate(byte[] bytes, UblProfile profile) {
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            profile, SecureXml.DEFAULT_XSLT2_PROCESSOR, Duration.ofSeconds(60), 2)) {
      return validator.validate(bytes, null);
    }
  }

  private static En16931DocumentValidator.Detail detail(byte[] bytes, UblProfile profile) {
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            profile, SecureXml.DEFAULT_XSLT2_PROCESSOR, Duration.ofSeconds(60), 2)) {
      return validator.validateInDetail(bytes, false);
    }
  }

  /**
   * Clean means: every rule ran, and no rule fired above the {@code information} flag. A finding
   * that is only informational is reported rather than suppressed - {@code BR-DE-TMP-32} is the one
   * this edition currently carries, and it has a test of its own so it cannot go unnoticed.
   */
  private static void assertClean(DocumentInput input, SellerProfile seller, UblProfile profile) {
    byte[] bytes = render(input, seller, profile);
    En16931DocumentValidator.Detail report = detail(bytes, profile);
    assertThat(report.findings())
        .as("%s findings on %s", profile.displayName(), input.invoice().id())
        .allMatch(finding -> !finding.fatal(), "informational only");
    assertThat(report.verdict()).isEqualTo(DocumentValidator.Verdict.PASSED);
    assertThat(validate(bytes, profile).verdict()).isEqualTo(DocumentValidator.Verdict.PASSED);
    assertThat(new String(bytes, StandardCharsets.UTF_8)).contains(profile.customizationId());
  }

  @Test
  void a_german_b2b_invoice_is_clean_under_xrechnung() {
    assertClean(
        DocumentFixtures.germanStandardRated(),
        DocumentFixtures.germanSeller(),
        UblProfile.XRECHNUNG_UBL);
  }

  @Test
  void a_german_b2b_invoice_is_clean_under_peppol() {
    assertClean(
        DocumentFixtures.germanStandardRated(),
        DocumentFixtures.germanSeller(),
        UblProfile.PEPPOL_BIS_UBL);
  }

  @Test
  void a_french_b2b_invoice_is_clean_under_peppol() {
    assertClean(
        DocumentFixtures.frenchStandardRated(),
        DocumentFixtures.frenchSeller(),
        UblProfile.PEPPOL_BIS_UBL);
  }

  @Test
  void a_belgian_b2b_invoice_is_clean_under_peppol() {
    assertClean(
        DocumentFixtures.belgianStandardRated(),
        DocumentFixtures.frenchSeller(),
        UblProfile.PEPPOL_BIS_UBL);
  }

  @Test
  void the_one_informational_finding_this_edition_carries_is_named_rather_than_silenced() {
    // BR-DE-TMP-32 asks an invoice to state a delivery date or an invoicing period. This edition
    // carries neither, because the authoritative invoice this module reads does not carry a line
    // period yet. The flag is `information`, so the document is valid; the finding is reported,
    // not filtered, and the documents page says so in words.
    byte[] bytes =
        render(
            DocumentFixtures.germanStandardRated(),
            DocumentFixtures.germanSeller(),
            UblProfile.XRECHNUNG_UBL);
    assertThat(detail(bytes, UblProfile.XRECHNUNG_UBL).findings())
        .extracting(SchematronValidator.Finding::ruleId)
        .containsExactly("BR-DE-TMP-32");
  }

  @Test
  void a_reverse_charge_invoice_is_clean_under_both_profiles() {
    assertClean(
        DocumentFixtures.reverseCharge(),
        DocumentFixtures.germanSeller(),
        UblProfile.XRECHNUNG_UBL);
    assertClean(
        DocumentFixtures.reverseCharge(),
        DocumentFixtures.germanSeller(),
        UblProfile.PEPPOL_BIS_UBL);
  }
}
