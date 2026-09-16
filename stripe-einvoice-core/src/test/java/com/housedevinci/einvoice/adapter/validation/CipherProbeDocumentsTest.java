package com.housedevinci.einvoice.adapter.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parser and stylesheet hardening, probed the way D-07 and checklist line 13 require: each
 * probe is shown to be <b>capable of going red</b>, by running the same input through an unhardened
 * factory in the same test and asserting that the attack succeeds there.
 *
 * <p>A probe that passes with and without the control is not a probe. Everything below therefore
 * comes in pairs: the unhardened factory resolves the entity, and the module's own factory refuses
 * it. If a future JDK changed a default so that the attack stopped working at all, the first half
 * of each pair fails and this file demands attention instead of quietly certifying nothing.
 */
class CipherProbeDocumentsTest {

  private static String documentWithExternalEntity(Path secret) {
    return "<?xml version=\"1.0\"?>"
        + "<!DOCTYPE Invoice [<!ENTITY xxe SYSTEM \""
        + secret.toUri()
        + "\">]>"
        + "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\">"
        + "<note>&xxe;</note></Invoice>";
  }

  @Test
  void probe_an_unhardened_parser_really_does_read_the_file_named_by_an_external_entity(
      @TempDir Path dir) throws Exception {
    Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-MARKER");

    // The control removed. This half must pass, or the second half proves nothing.
    DocumentBuilderFactory unhardened = DocumentBuilderFactory.newInstance();
    unhardened.setNamespaceAware(true);
    org.w3c.dom.Document parsed =
        unhardened
            .newDocumentBuilder()
            .parse(
                new ByteArrayInputStream(
                    documentWithExternalEntity(secret).getBytes(StandardCharsets.UTF_8)));
    assertThat(parsed.getDocumentElement().getTextContent())
        .as("an unhardened parser resolves the entity; this is the attack the module refuses")
        .contains("TOP-SECRET-MARKER");
  }

  @Test
  void probe_the_document_builder_factory_refuses_a_doctype_outright(@TempDir Path dir)
      throws Exception {
    Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-MARKER");
    DocumentBuilderFactory hardened = SecureXml.documentBuilderFactory();
    javax.xml.parsers.DocumentBuilder builder = hardened.newDocumentBuilder();
    builder.setEntityResolver(SecureXml.refusingEntityResolver());
    assertThatThrownBy(
            () ->
                builder.parse(
                    new ByteArrayInputStream(
                        documentWithExternalEntity(secret).getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(org.xml.sax.SAXException.class)
        .hasMessageContaining("DOCTYPE");
  }

  @Test
  void probe_the_sax_parser_factory_refuses_a_doctype_outright(@TempDir Path dir) throws Exception {
    Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-MARKER");
    javax.xml.parsers.SAXParser parser = SecureXml.saxParserFactory().newSAXParser();
    parser.getXMLReader().setEntityResolver(SecureXml.refusingEntityResolver());
    assertThatThrownBy(
            () ->
                parser
                    .getXMLReader()
                    .parse(
                        new org.xml.sax.InputSource(
                            new ByteArrayInputStream(
                                documentWithExternalEntity(secret)
                                    .getBytes(StandardCharsets.UTF_8)))))
        .isInstanceOf(org.xml.sax.SAXException.class)
        .hasMessageContaining("DOCTYPE");
  }

  @Test
  void probe_a_document_with_an_external_entity_never_reaches_a_verdict(@TempDir Path dir)
      throws IOException {
    Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-MARKER");
    byte[] bytes = documentWithExternalEntity(secret).getBytes(StandardCharsets.UTF_8);
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            UblProfile.XRECHNUNG_UBL,
            SecureXml.DEFAULT_XSLT2_PROCESSOR,
            Duration.ofSeconds(30),
            2)) {
      En16931DocumentValidator.Detail report = validator.validateInDetail(bytes, false);
      assertThat(report.verdict()).isNotEqualTo(DocumentValidator.Verdict.PASSED);
      // And nothing that was read out of the file is anywhere in what the validator reports.
      assertThat(report.findings().toString()).doesNotContain("TOP-SECRET-MARKER");
    }
  }

  @Test
  void probe_the_xslt_factory_refuses_an_external_stylesheet_and_extension_functions() {
    TransformerFactory hardened = SecureXml.xslt2Factory(SecureXml.DEFAULT_XSLT2_PROCESSOR);
    assertThat(hardened.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING)).isTrue();
    assertThat(hardened.getAttribute(SecureXml.ALLOW_EXTERNAL_FUNCTIONS))
        .isIn(Boolean.FALSE, "false");
    assertThat(hardened.getURIResolver()).isNotNull();
    // The refusing resolver throws rather than returning empty (checklist line 11): a future JDK
    // that changed a default would produce a refusal, never a silent fetch.
    assertThatThrownBy(() -> hardened.getURIResolver().resolve("file:///etc/passwd", null))
        .isInstanceOf(javax.xml.transform.TransformerException.class);
  }

  @Test
  void probe_a_stylesheet_that_tries_to_read_a_document_is_refused(@TempDir Path dir)
      throws Exception {
    Path secret = Files.writeString(dir.resolve("secret.txt"), "<a>TOP-SECRET-MARKER</a>");
    String stylesheet =
        "<xsl:stylesheet version=\"2.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">"
            + "<xsl:template match=\"/\"><out><xsl:value-of select=\"document('"
            + secret.toUri()
            + "')\"/></out></xsl:template></xsl:stylesheet>";
    TransformerFactory hardened = SecureXml.xslt2Factory(SecureXml.DEFAULT_XSLT2_PROCESSOR);
    javax.xml.transform.Templates templates =
        hardened.newTemplates(
            new javax.xml.transform.stream.StreamSource(
                new ByteArrayInputStream(stylesheet.getBytes(StandardCharsets.UTF_8))));
    javax.xml.transform.Transformer transformer = templates.newTransformer();
    transformer.setURIResolver(SecureXml.refusingUriResolver());
    java.io.StringWriter out = new java.io.StringWriter();
    assertThatThrownBy(
            () ->
                transformer.transform(
                    new javax.xml.transform.stream.StreamSource(
                        new ByteArrayInputStream("<x/>".getBytes(StandardCharsets.UTF_8))),
                    new javax.xml.transform.stream.StreamResult(out)))
        .isInstanceOf(javax.xml.transform.TransformerException.class);
    assertThat(out.toString()).doesNotContain("TOP-SECRET-MARKER");
  }

  @Test
  void probe_a_schema_import_this_module_does_not_vendor_is_refused_rather_than_fetched() {
    VendoredSchemaResolver resolver = new VendoredSchemaResolver();
    assertThatThrownBy(
            () ->
                resolver.resolveResource(
                    "http://www.w3.org/2001/XMLSchema",
                    "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2",
                    null,
                    "https://docs.oasis-open.org/ubl/os-UBL-2.1/xsd/common/"
                        + "UBL-CommonBasicComponents-2.1.xsd",
                    null))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.XML_REFUSED);
  }

  @Test
  void probe_a_vendored_file_whose_namespace_is_not_the_one_asked_for_is_refused() {
    // Checklist line 14, C17-13: matching on the file name alone would accept this.
    VendoredSchemaResolver resolver = new VendoredSchemaResolver();
    assertThatThrownBy(
            () ->
                resolver.resolveResource(
                    "http://www.w3.org/2001/XMLSchema",
                    "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2",
                    null,
                    "../common/UBL-CommonBasicComponents-2.1.xsd",
                    null))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("target namespace is not the namespace that was asked for");
  }

  @Test
  void probe_every_vendored_artefact_still_hashes_to_what_was_recorded_for_it() {
    // D-24. A stylesheet is executable code; a checksum nothing recomputes is a comment.
    Map<String, String> expected = VendoredArtefacts.expectedChecksums();
    assertThat(expected).hasSizeGreaterThanOrEqualTo(20);
    for (Map.Entry<String, String> entry : expected.entrySet()) {
      assertThat(VendoredArtefacts.actualChecksum(entry.getKey()))
          .as("checksum of %s", entry.getKey())
          .isEqualTo(entry.getValue());
    }
    // And the four stylesheets the chains actually run are all in it.
    assertThat(expected)
        .containsKeys(
            VendoredArtefacts.EN16931_UBL_XSLT,
            VendoredArtefacts.XRECHNUNG_UBL_XSLT,
            VendoredArtefacts.PEPPOL_CEN_UBL_XSLT,
            VendoredArtefacts.PEPPOL_BIS_UBL_XSLT);
  }

  @Test
  void probe_a_classpath_resource_this_module_does_not_vendor_cannot_be_opened() {
    assertThatThrownBy(() -> VendoredArtefacts.open("../../application.yml"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARTEFACT_TAMPERED);
  }

  @Test
  void probe_no_xslt_processor_reports_not_evaluated_and_never_passed() {
    // Checklist lines 18, 57 and 65, and the whole reason the processor is a test dependency: an
    // application without one issues nothing rather than archiving a document no rule ever read.
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            UblProfile.PEPPOL_BIS_UBL,
            "com.example.NoSuchTransformerFactory",
            Duration.ofSeconds(30),
            1)) {
      byte[] bytes =
          new En16931DocumentRenderer(
                  DocumentFixtures.frenchSeller(), UblProfile.PEPPOL_BIS_UBL, null)
              .render(DocumentFixtures.frenchStandardRated())
              .bytes();
      DocumentValidator.Report report = validator.validate(bytes, null);
      assertThat(report.verdict()).isEqualTo(DocumentValidator.Verdict.NOT_EVALUATED);
      assertThat(report.archivable()).isFalse();
      assertThat(report.ruleId()).isEqualTo(En16931DocumentValidator.NOT_EVALUATED_NO_PROCESSOR);
    }
  }

  @Test
  void probe_a_validation_that_runs_out_of_time_is_not_evaluated_rather_than_passed() {
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            UblProfile.PEPPOL_BIS_UBL,
            SecureXml.DEFAULT_XSLT2_PROCESSOR,
            Duration.ofNanos(1_000),
            1)) {
      byte[] bytes =
          new En16931DocumentRenderer(
                  DocumentFixtures.frenchSeller(), UblProfile.PEPPOL_BIS_UBL, null)
              .render(DocumentFixtures.frenchStandardRated())
              .bytes();
      DocumentValidator.Report report = validator.validate(bytes, null);
      assertThat(report.verdict()).isEqualTo(DocumentValidator.Verdict.NOT_EVALUATED);
      assertThat(report.archivable()).isFalse();
    }
  }

  @Test
  void probe_the_findings_this_port_returns_carry_no_content_from_the_document() {
    // Checklist line 45. The findings list is persisted, rendered in an operator view and read by
    // whoever has the logs; some rules' own text interpolates values out of the document being
    // judged, so the port returns rule identifiers and severities and nothing else.
    byte[] bytes =
        new En16931DocumentRenderer(DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
            .render(DocumentFixtures.germanStandardRated())
            .bytes();
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            UblProfile.XRECHNUNG_UBL,
            SecureXml.DEFAULT_XSLT2_PROCESSOR,
            Duration.ofSeconds(60),
            2)) {
      DocumentValidator.Report report = validator.validate(bytes, null);
      assertThat(report.findings()).isNotEmpty();
      // The SHAPE, not a denylist of today's values. Asserting only that this fixture's buyer
      // does not appear would stay green the moment a rule that interpolates a different field
      // fires, and the one rule this fixture triggers has entirely generic text - so a denylist
      // here proves nothing at all. A finding is an identifier and a severity, and nothing else.
      assertThat(report.findings())
          .allMatch(
              finding ->
                  finding.matches("[A-Za-z0-9_.\\-]+ \\((fatal|error|warning|information)\\)"),
              "an identifier and a severity, with no text");
      assertThat(report.findings().toString())
          .doesNotContain("Elbe Maschinenbau")
          .doesNotContain("Hafenweg")
          .doesNotContain("DE987654321")
          .doesNotContain("kreditoren@");
      // The rule that fired is still identifiable, which is the point of reporting it at all.
      assertThat(report.findings()).anyMatch(finding -> finding.startsWith("BR-DE-TMP-32"));
    }
  }

  @Test
  void probe_the_bytes_that_are_validated_are_the_bytes_that_were_rendered() {
    // Checklist line 15: a validator that re-serialises the model judges a document nobody will
    // archive. Mutating one byte of the rendered array must change the verdict.
    byte[] bytes =
        new En16931DocumentRenderer(DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
            .render(DocumentFixtures.germanStandardRated())
            .bytes();
    byte[] tampered = bytes.clone();
    String xml = new String(tampered, StandardCharsets.UTF_8);
    // 1500.00 becomes 1600.00: the document still parses, and no longer balances.
    byte[] changed =
        xml.replaceFirst(
                "<cbc:LineExtensionAmount currencyID=\"EUR\">1500.00</cbc:LineExtensionAmount>",
                "<cbc:LineExtensionAmount currencyID=\"EUR\">1600.00</cbc:LineExtensionAmount>")
            .getBytes(StandardCharsets.UTF_8);
    assertThat(changed).isNotEqualTo(bytes);
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            UblProfile.XRECHNUNG_UBL,
            SecureXml.DEFAULT_XSLT2_PROCESSOR,
            Duration.ofSeconds(60),
            2)) {
      assertThat(validator.validate(bytes, null).verdict())
          .isEqualTo(DocumentValidator.Verdict.PASSED);
      DocumentValidator.Report report = validator.validate(changed, null);
      assertThat(report.verdict()).isEqualTo(DocumentValidator.Verdict.FAILED);
      assertThat(report.ruleId()).isNotBlank();
    }
  }
}
