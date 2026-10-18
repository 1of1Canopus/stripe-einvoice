package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentRenderer;
import java.util.function.Function;

/**
 * Test renderers that declare they write the supply date (D-PS1-44), so the intake wiring check and
 * the unit of work let them number invoices. The preflight stays the port's default.
 */
public final class TestRenderers {

  private TestRenderers() {}

  /**
   * A minimal document in UBL namespaces with the allocation's dates, so the unit of work's date
   * check (D-PR36-6) can read it. {@code cbc} is the default namespace.
   */
  public static byte[] datedUbl(DocumentInput input) {
    StringBuilder out =
        new StringBuilder(
            "<inv:Invoice xmlns:inv=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\""
                + " xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
                + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\">");
    out.append("<ID>").append(input.legalNumber().value()).append("</ID>");
    out.append("<IssueDate>").append(input.issueDate()).append("</IssueDate>");
    input
        .supplyDate()
        .ifPresent(
            d ->
                out.append("<cac:Delivery><ActualDeliveryDate>")
                    .append(d)
                    .append("</ActualDeliveryDate></cac:Delivery>"));
    input
        .supplyPeriod()
        .ifPresent(
            p ->
                out.append("<cac:InvoicePeriod><StartDate>")
                    .append(p.start())
                    .append("</StartDate><EndDate>")
                    .append(p.end())
                    .append("</EndDate></cac:InvoicePeriod>"));
    out.append("<SourceId>").append(input.invoice().id()).append("</SourceId></inv:Invoice>");
    return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  public static DocumentRenderer supplying(
      Function<DocumentInput, DocumentRenderer.RenderedDocument> render) {
    return new DocumentRenderer() {
      @Override
      public boolean carriesSupplyDate() {
        return true;
      }

      @Override
      public RenderedDocument render(DocumentInput input) {
        return render.apply(input);
      }
    };
  }
}
