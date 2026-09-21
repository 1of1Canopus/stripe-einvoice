package com.housedevinci.einvoice.adapter.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.validation.En16931DocumentValidator;
import com.housedevinci.einvoice.adapter.validation.SecureXml;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.domain.EInvoiceException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The buyer controls their own name, address and email, and those strings land verbatim in a
 * document a government platform will read (D-06, checklist lines 7, 8 and 9).
 *
 * <p>Two properties, and they are different: what escaping handles, and what escaping cannot. A
 * closing tag or an entity reference is <b>escaped</b> and the document still says the right thing;
 * a C0 control, an unpaired surrogate, a bidi override or a value that collapses to blank is
 * <b>refused</b>, because escaping would preserve a character that makes a schematron fail three
 * hops later at the recipient, or makes the rendered document say something other than the XML.
 */
class HostileStringTest {

  /** A C0 control character. Named rather than written, so the source file stays plain text. */
  private static final String BELL = String.valueOf((char) 0x07);

  /** An unpaired high surrogate: a writer emits it as invalid XML. */
  private static final String LONE_HIGH_SURROGATE = String.valueOf((char) 0xD800);

  /** A right-to-left override: the rendered document says one thing, the XML another. */
  private static final String RTL_OVERRIDE = String.valueOf((char) 0x202E);

  /** A no-break space and a zero-width space: blank to a reader, not blank to a naive check. */
  private static final String INVISIBLE_BLANK = String.valueOf((char) 0x00A0) + (char) 0x200B;

  private static DocumentInput withBuyerName(String name) {
    DocumentInput base = DocumentFixtures.germanStandardRated();
    SourceInvoice invoice = base.invoice();
    SourceInvoice.SourceParty buyer = invoice.buyer();
    SourceInvoice hostile =
        new SourceInvoice(
            invoice.id(),
            invoice.number(),
            invoice.accountId(),
            invoice.livemode(),
            invoice.currency(),
            invoice.status(),
            invoice.finalizedAt(),
            new SourceInvoice.SourceParty(
                name,
                buyer.email(),
                buyer.line1(),
                buyer.line2(),
                buyer.postalCode(),
                buyer.city(),
                buyer.country(),
                buyer.taxId()),
            invoice.lines(),
            invoice.taxBuckets(),
            invoice.taxTreatments(),
            invoice.subtotalMinor(),
            invoice.taxMinor(),
            invoice.totalMinor(),
            true);
    return reinput(base, hostile);
  }

  static DocumentInput reinput(DocumentInput base, SourceInvoice invoice) {
    return new DocumentInput(
        new com.housedevinci.einvoice.application.MappingInput(
            invoice, base.seriesKey(), base.issueDate(), base.rulePackVersion()),
        base.legalNumber());
  }

  private static byte[] render(DocumentInput input) {
    return new En16931DocumentRenderer(
            DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
        .render(input)
        .bytes();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Elbe AG</cbc:Name><cbc:Name>Somebody Else",
        "Elbe AG]]><!--",
        "Elbe AG &amp; Co &entity; KG",
        "Elbe AG\" schemeID=\"0088",
        "Elbe <AG> & Co"
      })
  void markup_in_a_buyer_name_is_escaped_and_the_document_still_parses(String name) {
    byte[] bytes = render(withBuyerName(name));
    String xml = new String(bytes, StandardCharsets.UTF_8);
    // The value survived as text, and created no second element of its own.
    assertThat(countOf(xml, "<cbc:Name>")).isEqualTo(countOf(xml, "</cbc:Name>"));
    assertThat(xml).doesNotContain("<cbc:Name>Somebody Else</cbc:Name>");
    // And the bytes are still a document the official chain can read and judge.
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            UblProfile.XRECHNUNG_UBL,
            SecureXml.DEFAULT_XSLT2_PROCESSOR,
            Duration.ofSeconds(60),
            2)) {
      assertThat(validator.validateInDetail(bytes, false).verdict())
          .isEqualTo(DocumentValidator.Verdict.PASSED);
    }
  }

  @Test
  void a_value_escaping_cannot_make_safe_is_refused_rather_than_written() {
    List<String> refused =
        List.of(
            "Elbe" + BELL + "AG",
            "Elbe" + LONE_HIGH_SURROGATE + "AG",
            "Elbe" + RTL_OVERRIDE + "AG",
            INVISIBLE_BLANK,
            "   ");
    for (String name : refused) {
      assertThatThrownBy(() -> render(withBuyerName(name)))
          .as("a buyer name of %d characters that must not be written", name.length())
          .isInstanceOf(EInvoiceException.class);
    }
  }

  @Test
  void a_value_past_its_business_term_bound_is_refused_here_rather_than_at_the_recipient() {
    assertThatThrownBy(() -> render(withBuyerName("E".repeat(201))))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("max 200");
  }

  @Test
  void two_line_identifiers_that_collapse_to_the_same_value_are_refused() {
    // Checklist line 8: a whitespace-insensitive comparison at the recipient would read these as
    // one line, and the document would itemise a sale twice under one identifier.
    DocumentInput base = DocumentFixtures.belgianStandardRated();
    SourceInvoice invoice = base.invoice();
    List<SourceInvoice.SourceLine> collided =
        List.of(
            new SourceInvoice.SourceLine("1", "Platformlicentie", "txr_be21", 1L, 40_000, 48_400),
            new SourceInvoice.SourceLine(
                "1" + (char) 0x00A0, "Aanvullende gebruikers", "txr_be21", 4L, 20_000, 24_200));
    SourceInvoice hostile =
        new SourceInvoice(
            invoice.id(),
            invoice.number(),
            invoice.accountId(),
            invoice.livemode(),
            invoice.currency(),
            invoice.status(),
            invoice.finalizedAt(),
            invoice.buyer(),
            collided,
            invoice.taxBuckets(),
            invoice.taxTreatments(),
            invoice.subtotalMinor(),
            invoice.taxMinor(),
            invoice.totalMinor(),
            true);
    DocumentInput input = reinput(base, hostile);
    assertThatThrownBy(
            () ->
                new En16931DocumentRenderer(
                        DocumentFixtures.frenchSeller(), UblProfile.PEPPOL_BIS_UBL, null)
                    .render(input))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("cannot tell them apart");
  }

  // D3-01: XML 1.0 non-characters. Not control characters, not surrogates, not markup - perfectly
  // ordinary Java string content that no XML 1.0 parser can read once it is in the document.
  @ParameterizedTest
  @ValueSource(ints = {0xFFFF, 0xFFFE, 0x0091})
  void probe_a_buyer_name_no_xml_parser_can_read_is_refused_before_the_bytes(int codePoint) {
    String name = "Elbe" + (char) codePoint + "AG";
    if (codePoint == 0x0091) {
      // A C1 control, legal in XML 1.0: must pass, and the resulting bytes must parse.
      byte[] bytes = render(withBuyerName(name));
      assertThatCode(
              () ->
                  SecureXml.documentBuilderFactory()
                      .newDocumentBuilder()
                      .parse(new ByteArrayInputStream(bytes)))
          .doesNotThrowAnyException();
      return;
    }
    assertThatThrownBy(() -> render(withBuyerName(name))).isInstanceOf(EInvoiceException.class);
  }

  private static int countOf(String haystack, String needle) {
    int count = 0;
    int from = 0;
    while (true) {
      int at = haystack.indexOf(needle, from);
      if (at < 0) {
        return count;
      }
      count++;
      from = at + needle.length();
    }
  }
}
