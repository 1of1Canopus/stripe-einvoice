package com.housedevinci.einvoice.sample;

import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.SourceInvoice;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A renderer and a validator so the sample can run the whole issuance path today.
 *
 * <p><b>What this produces is not an EN 16931 document and is not a legal invoice.</b> Its root
 * element is {@code PlaceholderDocument} precisely so that nobody, and no tool, can mistake it for
 * one. The XRechnung and Peppol BIS UBL writers and the official validator suites arrive in the
 * next increment of this module; until they do, an application that wants to issue supplies its own
 * {@link DocumentRenderer} and {@link DocumentValidator}, and the starter refuses to wire the
 * issuance path at all when neither is present.
 *
 * <p>What it does demonstrate is the property the unit of work depends on and that a real writer
 * will have to keep: <b>the same input renders the same bytes</b>, under any time zone and any
 * locale. A renderer without that property has a retry that writes a second document under one
 * number.
 */
@Configuration(proxyBeanMethods = false)
class PlaceholderDocuments {

  private static final DateTimeFormatter ISO_DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

  /** The rule id the sample's validator reports, so the absence of real rules is visible. */
  static final String NO_RULES = "SAMPLE-NO-RULE-PACK";

  @Bean
  DocumentRenderer placeholderRenderer() {
    return input -> {
      StringBuilder out = new StringBuilder();
      out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      out.append("<!-- Not an EN 16931 document. A placeholder produced by the sample app. -->\n");
      out.append("<PlaceholderDocument>");
      out.append("<LegalNumber>").append(input.legalNumber().value()).append("</LegalNumber>");
      out.append("<IssueDate>").append(ISO_DATE.format(input.issueDate())).append("</IssueDate>");
      out.append("<StripeInvoiceId>").append(input.invoice().id()).append("</StripeInvoiceId>");
      out.append("<StripeNumber>").append(input.invoice().number()).append("</StripeNumber>");
      out.append("<Currency>").append(input.invoice().currency()).append("</Currency>");
      out.append("<TotalMinorUnits>")
          .append(input.invoice().totalMinor())
          .append("</TotalMinorUnits>");
      for (SourceInvoice.SourceLine line : input.invoice().lines()) {
        out.append("<Line id=\"")
            .append(line.id())
            .append("\">")
            .append(line.netMinor())
            .append("</Line>");
      }
      out.append("</PlaceholderDocument>");
      return new DocumentRenderer.RenderedDocument(
          out.toString().getBytes(StandardCharsets.UTF_8), "xml", "placeholder");
    };
  }

  /**
   * Accepts the placeholder, and says out loud that no rule pack ran.
   *
   * <p>A validator in a sample application is a tempting place to write "return PASSED" and move
   * on. This one does return PASSED, because refusing would stop the sample from demonstrating
   * anything - and it carries the rule id above so the report shows which rules were behind that
   * verdict, which is none.
   */
  @Bean
  DocumentValidator placeholderValidator() {
    return (bytes, input) ->
        new DocumentValidator.Report(
            DocumentValidator.Verdict.PASSED,
            NO_RULES,
            java.util.List.of("no EN 16931 rule pack is installed in this sample"));
  }
}
