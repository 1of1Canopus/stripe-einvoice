package com.housedevinci.einvoice.adapter.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentDates.FoundDates;
import com.housedevinci.einvoice.application.DocumentDates.Readability;
import com.housedevinci.einvoice.application.DocumentDatesRule;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentValidator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Security review of PR 39 (D-PR36-6), pass 1: the reader and the two callers of the one rule.
 */
class CipherProbePr39ReaderTest {

  private static final String CBC =
      "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
  private static final String OPEN =
      "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\""
          + " xmlns:cbc=\"" + CBC + "\""
          + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\""
          + " xmlns:ext=\"urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2\">";
  private final UblDocumentDates reader = new UblDocumentDates();
  private final DocumentInput input = DocumentFixtures.germanStandardRated();
  private final String day = input.issueDate().toString();
  private final String week = input.issueDate().minusDays(7).toString();

  private FoundDates invoice(String body) {
    return reader.read((OPEN + body + "</Invoice>").getBytes(StandardCharsets.UTF_8));
  }

  private String rendered() {
    return new String(
        new En16931DocumentRenderer(DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
            .render(input)
            .bytes(),
        StandardCharsets.UTF_8);
  }

  @Test
  void an_issue_date_nested_in_an_extension_is_not_read() {
    FoundDates found =
        invoice(
            "<ext:UBLExtensions><ext:UBLExtension><ext:ExtensionContent><cbc:IssueDate>" + day
                + "</cbc:IssueDate></ext:ExtensionContent></ext:UBLExtension></ext:UBLExtensions>"
                + "<cbc:IssueDate>" + week + "</cbc:IssueDate>");
    assertThat(found.readability()).isEqualTo(Readability.READ);
    assertThat(found.issueDate()).contains(week);
  }

  @Test
  void a_second_prefix_for_cbc_is_still_a_second_issue_date() {
    FoundDates found =
        invoice(
            "<cbc:IssueDate>" + day + "</cbc:IssueDate><b:IssueDate xmlns:b=\"" + CBC + "\">"
                + week + "</b:IssueDate>");
    assertThat(found.readability()).isEqualTo(Readability.AMBIGUOUS);
  }

  @Test
  void a_prefix_rebound_to_another_namespace_is_not_bt2() {
    FoundDates found =
        invoice("<cbc:IssueDate xmlns:cbc=\"urn:other\">" + day + "</cbc:IssueDate>");
    assertThat(found.issueDate()).isEmpty();
  }

  @Test
  void the_right_date_in_a_comment_only_is_not_read() {
    FoundDates found =
        invoice("<!-- <cbc:IssueDate>" + day + "</cbc:IssueDate> --><cbc:IssueDate>" + week
            + "</cbc:IssueDate>");
    assertThat(found.issueDate()).contains(week);
    FoundDates onlyComment = invoice("<!-- <cbc:IssueDate>" + day + "</cbc:IssueDate> -->");
    assertThat(onlyComment.issueDate()).isEmpty();
  }

  @Test
  void a_comment_or_instruction_inside_the_date_is_unreadable() {
    assertThat(invoice("<cbc:IssueDate>2026-01-<!--x-->10</cbc:IssueDate>").readability())
        .isEqualTo(Readability.UNREADABLE);
    assertThat(invoice("<cbc:IssueDate>2026-01-<?pi x?>10</cbc:IssueDate>").readability())
        .isEqualTo(Readability.UNREADABLE);
  }

  @Test
  void unicode_or_line_whitespace_is_another_text() {
    for (String padded : new String[] {day + "\n", " " + day, day + " ", "\t" + day}) {
      FoundDates found = invoice("<cbc:IssueDate>" + padded + "</cbc:IssueDate>");
      assertThat(DocumentDatesRule.check(found, input.dating()).archivable())
          .as("padded %s", padded.codePoints().boxed().toList())
          .isFalse();
    }
  }

  @Test
  void a_character_reference_is_the_date_it_spells() {
    String spelled = day.substring(0, day.length() - 1) + "&#" + (int) day.charAt(day.length() - 1) + ";";
    assertThat(invoice("<cbc:IssueDate>" + spelled + "</cbc:IssueDate>").issueDate()).contains(day);
  }

  @Test
  void a_bt7_or_bt8_in_a_line_is_not_document_level_and_a_document_level_bt8_is_refused() {
    FoundDates found =
        invoice("<cbc:IssueDate>" + day + "</cbc:IssueDate><cac:InvoicePeriod><cbc:DescriptionCode>"
            + "432</cbc:DescriptionCode></cac:InvoicePeriod>");
    assertThat(found.periodCode()).isTrue();
    assertThat(DocumentDatesRule.check(found, input.dating()).archivable()).isFalse();
  }

  @Test
  void a_lower_case_root_is_unreadable() {
    FoundDates found =
        reader.read(
            ("<invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"/>")
                .getBytes(StandardCharsets.UTF_8));
    assertThat(found.readability()).isEqualTo(Readability.UNREADABLE);
  }

  @Test
  void a_deep_document_never_escapes_the_reader() {
    StringBuilder deep = new StringBuilder("<cbc:IssueDate>" + day + "</cbc:IssueDate>");
    int depth = 200_000;
    deep.append("<a>".repeat(depth)).append("</a>".repeat(depth));
    assertThatCode(() -> invoice(deep.toString())).doesNotThrowAnyException();
  }

  /** One rule, two callers: the module validator never passes bytes the unit of work refuses. */
  @Test
  void the_validator_and_the_unit_of_work_rule_agree_on_the_same_bytes() {
    String xml = rendered();
    String issue = "<cbc:IssueDate>" + day + "</cbc:IssueDate>";
    Map<String, String> variants = new LinkedHashMap<>();
    variants.put("true", xml);
    variants.put("backdated", xml.replace(issue, "<cbc:IssueDate>" + week + "</cbc:IssueDate>"));
    variants.put("padded", xml.replace(issue, "<cbc:IssueDate>" + day + " </cbc:IssueDate>"));
    variants.put("cdata", xml.replace(issue, "<cbc:IssueDate><![CDATA[" + day + "]]></cbc:IssueDate>"));
    variants.put("comment inside", xml.replace(issue, "<cbc:IssueDate>" + day + "<!--x--></cbc:IssueDate>"));
    variants.put(
        "bt7", xml.replace(issue, issue + "<cbc:TaxPointDate>" + week + "</cbc:TaxPointDate>"));
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            UblProfile.XRECHNUNG_UBL, SecureXml.DEFAULT_XSLT2_PROCESSOR, Duration.ofSeconds(60), 2)) {
      for (Map.Entry<String, String> v : variants.entrySet()) {
        byte[] bytes = v.getValue().getBytes(StandardCharsets.UTF_8);
        DocumentValidator.Report byValidator = validator.validate(bytes, input);
        DocumentValidator.Report byRule = DocumentDatesRule.check(reader.read(bytes), input.dating());
        System.out.println(
            "CIPHER two callers " + v.getKey() + ": validator=" + byValidator.verdict() + " rule="
                + byRule.verdict());
        if (byValidator.archivable()) {
          assertThat(byRule.archivable()).as(v.getKey()).isTrue();
        }
      }
      assertThat(validator.validate(xml.getBytes(StandardCharsets.UTF_8), input).archivable())
          .isTrue();
    }
  }
}
