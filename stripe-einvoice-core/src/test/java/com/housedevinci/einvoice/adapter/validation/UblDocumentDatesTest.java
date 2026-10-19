package com.housedevinci.einvoice.adapter.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentDates.FoundDates;
import com.housedevinci.einvoice.application.DocumentDates.Readability;
import com.housedevinci.einvoice.application.DocumentDatesRule;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentValidator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The module's date reader (D-PR36-6): direct children only, by namespace and local name, every
 * text as written, and never an exception.
 */
class UblDocumentDatesTest {

  private static final String INVOICE_OPEN =
      "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\""
          + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
          + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\">";
  private static final String CREDIT_NOTE_OPEN =
      "<CreditNote xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2\""
          + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
          + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\">";

  private final UblDocumentDates reader = new UblDocumentDates();

  private FoundDates read(String xml) {
    return reader.read(xml.getBytes(StandardCharsets.UTF_8));
  }

  private FoundDates invoice(String body) {
    return read(INVOICE_OPEN + body + "</Invoice>");
  }

  @Test
  void the_module_renderer_output_reads_its_dates() {
    DocumentInput input = DocumentFixtures.germanStandardRated();
    byte[] bytes =
        new En16931DocumentRenderer(DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
            .render(input)
            .bytes();

    FoundDates found = reader.read(bytes);

    assertThat(found.readability()).isEqualTo(Readability.READ);
    assertThat(found.issueDate()).contains(input.issueDate().toString());
    assertThat(found.deliveryDate()).contains(input.supplyDate().orElseThrow().toString());
    assertThat(found.taxPointDate()).isFalse();
    assertThat(found.period()).isFalse();
    assertThat(DocumentDatesRule.check(found, input.dating()).archivable()).isTrue();
  }

  @Test
  void every_element_is_read_at_its_path() {
    FoundDates found =
        invoice(
            "<cbc:IssueDate>2026-01-10</cbc:IssueDate><cbc:TaxPointDate>2026-01-01</cbc:TaxPointDate>"
                + "<cac:InvoicePeriod><cbc:StartDate>2026-01-01</cbc:StartDate>"
                + "<cbc:EndDate>2026-01-31</cbc:EndDate><cbc:DescriptionCode>432</cbc:DescriptionCode>"
                + "</cac:InvoicePeriod>"
                + "<cac:Delivery><cbc:ActualDeliveryDate>2026-01-03</cbc:ActualDeliveryDate></cac:Delivery>");

    assertThat(found.readability()).isEqualTo(Readability.READ);
    assertThat(found.issueDate()).contains("2026-01-10");
    assertThat(found.taxPointDate()).isTrue();
    assertThat(found.period()).isTrue();
    assertThat(found.periodStart()).contains("2026-01-01");
    assertThat(found.periodEnd()).contains("2026-01-31");
    assertThat(found.periodCode()).isTrue();
    assertThat(found.delivery()).isTrue();
    assertThat(found.deliveryDate()).contains("2026-01-03");
  }

  @Test
  void a_zoned_issue_date_is_returned_as_written() {
    assertThat(invoice("<cbc:IssueDate>2026-04-01+14:00</cbc:IssueDate>").issueDate())
        .contains("2026-04-01+14:00");
  }

  @Test
  void a_padded_issue_date_is_refused() {
    FoundDates found = invoice("<cbc:IssueDate> 2026-01-10</cbc:IssueDate>");
    assertThat(found.issueDate()).contains(" 2026-01-10");

    DocumentInput input = DocumentFixtures.germanStandardRated();
    String rendered =
        new String(
            new En16931DocumentRenderer(
                    DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
                .render(input)
                .bytes(),
            StandardCharsets.UTF_8);
    String padded =
        rendered.replace(
            "<cbc:IssueDate>" + input.issueDate() + "</cbc:IssueDate>",
            "<cbc:IssueDate> " + input.issueDate() + "</cbc:IssueDate>");
    assertThat(padded).isNotEqualTo(rendered);
    assertThat(
            DocumentDatesRule.check(
                    reader.read(padded.getBytes(StandardCharsets.UTF_8)), input.dating())
                .verdict())
        .isEqualTo(DocumentValidator.Verdict.FAILED);
  }

  @Test
  void a_credit_note_billing_reference_date_is_not_read() {
    FoundDates found =
        read(
            CREDIT_NOTE_OPEN
                + "<cbc:IssueDate>2026-03-12</cbc:IssueDate>"
                + "<cac:BillingReference><cac:InvoiceDocumentReference><cbc:ID>INV-1</cbc:ID>"
                + "<cbc:IssueDate>2026-01-03</cbc:IssueDate></cac:InvoiceDocumentReference>"
                + "</cac:BillingReference></CreditNote>");

    assertThat(found.readability()).isEqualTo(Readability.READ);
    assertThat(found.issueDate()).contains("2026-03-12");
  }

  @Test
  void a_line_period_and_a_line_delivery_are_not_read() {
    FoundDates found =
        invoice(
            "<cbc:IssueDate>2026-01-10</cbc:IssueDate><cac:InvoiceLine><cac:InvoicePeriod>"
                + "<cbc:StartDate>2025-01-01</cbc:StartDate></cac:InvoicePeriod><cac:Delivery>"
                + "<cbc:ActualDeliveryDate>2025-01-01</cbc:ActualDeliveryDate></cac:Delivery>"
                + "</cac:InvoiceLine>");

    assertThat(found.readability()).isEqualTo(Readability.READ);
    assertThat(found.period()).isFalse();
    assertThat(found.delivery()).isFalse();
  }

  @Test
  void a_dateless_first_delivery_cannot_hide_a_dated_second() {
    FoundDates found =
        invoice(
            "<cbc:IssueDate>2026-01-10</cbc:IssueDate>"
                + "<cac:Delivery><cac:DeliveryLocation><cbc:ID>x</cbc:ID></cac:DeliveryLocation></cac:Delivery>"
                + "<cac:Delivery><cbc:ActualDeliveryDate>2026-01-03</cbc:ActualDeliveryDate></cac:Delivery>");

    assertThat(found.readability()).isEqualTo(Readability.AMBIGUOUS);
  }

  @Test
  void any_element_twice_is_ambiguous() {
    for (String body :
        new String[] {
          "<cbc:IssueDate>2026-01-10</cbc:IssueDate><cbc:IssueDate>2026-01-03</cbc:IssueDate>",
          "<cbc:TaxPointDate>2026-01-10</cbc:TaxPointDate><cbc:TaxPointDate>2026-01-03</cbc:TaxPointDate>",
          "<cac:InvoicePeriod/><cac:InvoicePeriod/>",
          "<cac:Delivery><cbc:ActualDeliveryDate>2026-01-03</cbc:ActualDeliveryDate>"
              + "<cbc:ActualDeliveryDate>2026-01-04</cbc:ActualDeliveryDate></cac:Delivery>",
          "<cac:InvoicePeriod><cbc:StartDate>2026-01-01</cbc:StartDate>"
              + "<cbc:StartDate>2025-01-01</cbc:StartDate></cac:InvoicePeriod>",
          "<cac:InvoicePeriod><cbc:EndDate>2026-01-01</cbc:EndDate>"
              + "<cbc:EndDate>2025-01-01</cbc:EndDate></cac:InvoicePeriod>",
          "<cac:InvoicePeriod><cbc:DescriptionCode>3</cbc:DescriptionCode>"
              + "<cbc:DescriptionCode>432</cbc:DescriptionCode></cac:InvoicePeriod>"
        }) {
      assertThat(invoice(body).readability()).as(body).isEqualTo(Readability.AMBIGUOUS);
    }
  }

  @Test
  void a_foreign_namespace_is_ignored() {
    FoundDates found =
        invoice(
            "<cbc:IssueDate>2026-01-10</cbc:IssueDate>"
                + "<x:TaxPointDate xmlns:x=\"urn:x\">2026-01-01</x:TaxPointDate>"
                + "<cac:Delivery><x:ActualDeliveryDate xmlns:x=\"urn:x\">2026-01-03</x:ActualDeliveryDate>"
                + "</cac:Delivery>");

    assertThat(found.taxPointDate()).isFalse();
    assertThat(found.delivery()).isTrue();
    assertThat(found.deliveryDate()).isEmpty();
  }

  @Test
  void a_cdata_date_is_its_text() {
    assertThat(invoice("<cbc:IssueDate><![CDATA[2026-01-10]]></cbc:IssueDate>").issueDate())
        .contains("2026-01-10");
  }

  @Test
  void a_date_with_anything_but_text_inside_is_unreadable() {
    assertThat(
            invoice("<cbc:IssueDate>2026-01<x:y xmlns:x=\"urn:x\"/>-10</cbc:IssueDate>")
                .readability())
        .isEqualTo(Readability.UNREADABLE);
    assertThat(invoice("<cbc:IssueDate>2026-01<!-- c -->-10</cbc:IssueDate>").readability())
        .isEqualTo(Readability.UNREADABLE);
  }

  @Test
  void what_is_not_a_ubl_document_is_unreadable() {
    assertThat(reader.read(null).readability()).isEqualTo(Readability.UNREADABLE);
    assertThat(reader.read(new byte[0]).readability()).isEqualTo(Readability.UNREADABLE);
    assertThat(read("not xml").readability()).isEqualTo(Readability.UNREADABLE);
    assertThat(read("<Invoice><cbc:IssueDate/></Invoice>").readability())
        .isEqualTo(Readability.UNREADABLE);
    assertThat(read("<Invoice><IssueDate>2026-01-10</IssueDate></Invoice>").readability())
        .isEqualTo(Readability.UNREADABLE);
    assertThat(
            read("<rsm:CrossIndustryInvoice xmlns:rsm=\"urn:un:unece:uncefact:data:standard:"
                    + "CrossIndustryInvoice:100\"/>")
                .readability())
        .isEqualTo(Readability.UNREADABLE);
    assertThat(
            read("<!DOCTYPE Invoice [<!ENTITY d \"2026-01-10\">]>"
                    + INVOICE_OPEN
                    + "<cbc:IssueDate>&d;</cbc:IssueDate></Invoice>")
                .readability())
        .isEqualTo(Readability.UNREADABLE);
  }
}
