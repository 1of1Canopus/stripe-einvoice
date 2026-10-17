package com.housedevinci.einvoice.adapter.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentValidator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Security review of the date-order change (D-PS1-27), pass 2. Attacks on the new rule
 * EINVOICE-DATES of the module's own validator.
 */
class CipherProbePr36Pass2DocumentTest {

  private static String rendered(DocumentInput input) {
    return new String(
        new En16931DocumentRenderer(DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
            .render(input)
            .bytes(),
        StandardCharsets.UTF_8);
  }

  private static DocumentValidator.Report validate(String xml, DocumentInput input) {
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            UblProfile.XRECHNUNG_UBL,
            SecureXml.DEFAULT_XSLT2_PROCESSOR,
            Duration.ofSeconds(60),
            2)) {
      return validator.validate(xml.getBytes(StandardCharsets.UTF_8), input);
    }
  }

  /**
   * Extra check. UBL allows more than one document-level cac:Delivery and the rule reads only the
   * first. A second delivery a week earlier is refused by UBL-SR-24 before the rule runs.
   */
  @Test
  void a_second_delivery_with_another_date_is_refused_by_the_schematron() {
    DocumentInput input = DocumentFixtures.germanStandardRated();
    String xml = rendered(input);
    String supply = input.supplyDate().orElseThrow().toString();
    String second =
        "<cac:Delivery><cbc:ActualDeliveryDate>"
            + input.supplyDate().orElseThrow().minusDays(7)
            + "</cbc:ActualDeliveryDate></cac:Delivery>";
    String doubled =
        xml.replaceFirst(
            "(<cac:Delivery><cbc:ActualDeliveryDate>"
                + supply
                + "</cbc:ActualDeliveryDate></cac:Delivery>)",
            "$1" + java.util.regex.Matcher.quoteReplacement(second));
    assertThat(doubled).contains(second);

    DocumentValidator.Report report = validate(doubled, input);
    System.out.println("CIPHER two deliveries: " + report.verdict() + " " + report.findings());
    assertThat(report.archivable()).isFalse();
  }

  /** Extra check: an issue date with a time-zone suffix is a different string and is refused. */
  @Test
  void an_issue_date_with_a_zone_suffix_is_not_archivable() {
    DocumentInput input = DocumentFixtures.germanStandardRated();
    String xml =
        rendered(input)
            .replaceFirst(
                "<cbc:IssueDate>" + input.issueDate() + "</cbc:IssueDate>",
                "<cbc:IssueDate>" + input.issueDate() + "+14:00</cbc:IssueDate>");
    DocumentValidator.Report report = validate(xml, input);
    System.out.println("CIPHER zone suffix: " + report.verdict() + " " + report.findings());
    assertThat(report.archivable()).isFalse();
  }

  /** Extra check: the right dates in a foreign namespace do not satisfy the rule. */
  @Test
  void a_delivery_in_another_namespace_does_not_satisfy_the_rule() {
    DocumentInput input = DocumentFixtures.germanStandardRated();
    String supply = input.supplyDate().orElseThrow().toString();
    String xml =
        rendered(input)
            .replace(
                "<cac:Delivery><cbc:ActualDeliveryDate>"
                    + supply
                    + "</cbc:ActualDeliveryDate></cac:Delivery>",
                "<cac:Delivery><x:ActualDeliveryDate xmlns:x=\"urn:x\">"
                    + supply
                    + "</x:ActualDeliveryDate></cac:Delivery>");
    DocumentValidator.Report report = validate(xml, input);
    System.out.println("CIPHER foreign ns: " + report.verdict() + " " + report.findings());
    assertThat(report.archivable()).isFalse();
  }

  /**
   * D-PR36-9. The rule's own javadoc says a document with dates the allocation did not give is not
   * archivable. A VAT point date (BT-7) 30 days before the supply day passes: no allocation gives
   * one, and the module's renderer never writes one.
   */
  @Test
  void probe_a_tax_point_date_the_allocation_did_not_give_is_archivable() {
    DocumentInput input = DocumentFixtures.germanStandardRated();
    String xml =
        rendered(input)
            .replaceFirst(
                "(<cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode><cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>)",
                "<cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode><cbc:TaxPointDate>"
                    + input.supplyDate().orElseThrow().minusDays(30)
                    + "</cbc:TaxPointDate><cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>");
    DocumentValidator.Report report = validate(xml, input);
    assertThat(xml).contains("<cbc:TaxPointDate>");
    assertThat(report.archivable()).isFalse();
  }

  /**
   * D-PR36-8. The rule runs only when the allocation input is given; a null input skips it and the
   * backdated document is archivable. No production caller passes null today; a skipped control
   * must still fail closed.
   */
  @Test
  void probe_a_null_allocation_input_skips_the_date_rule() {
    DocumentInput input = DocumentFixtures.germanStandardRated();
    String xml =
        rendered(input)
            .replaceFirst(
                "<cbc:IssueDate>" + input.issueDate() + "</cbc:IssueDate>",
                "<cbc:IssueDate>" + input.issueDate().minusDays(7) + "</cbc:IssueDate>");
    assertThat(validate(xml, null).archivable()).isFalse();
  }

  /** Extra check: the true dates pass the rule, for a delivery date and for a period. */
  @Test
  void the_true_dates_pass_the_rule_for_a_delivery_and_for_a_period() {
    DocumentInput delivery = DocumentFixtures.germanStandardRated();
    assertThat(validate(rendered(delivery), delivery).archivable()).isTrue();
    com.housedevinci.einvoice.domain.IssueDating d = delivery.dating();
    DocumentInput period =
        new DocumentInput(
            delivery.unnumbered(),
            delivery.legalNumber(),
            delivery.seriesKey(),
            new com.housedevinci.einvoice.domain.IssueDating(
                d.issueDate(),
                d.allocatedAt(),
                java.util.Optional.empty(),
                java.util.Optional.of(
                    new com.housedevinci.einvoice.domain.SupplyPeriod(
                        java.time.LocalDate.parse("2026-02-01"),
                        java.time.LocalDate.parse("2026-02-28"))),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                d.cause()));
    DocumentValidator.Report report = validate(rendered(period), period);
    System.out.println("CIPHER period: " + report.verdict() + " " + report.findings());
    assertThat(report.archivable()).isTrue();
  }
}
