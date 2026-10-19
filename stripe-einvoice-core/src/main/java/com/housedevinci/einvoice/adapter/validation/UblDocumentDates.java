package com.housedevinci.einvoice.adapter.validation;

import com.housedevinci.einvoice.application.DocumentDates;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * The module's own {@link DocumentDates} reader, for UBL 2.1 {@code Invoice} and {@code CreditNote}
 * documents (D-PR36-6).
 *
 * <p>It reads the root's <b>direct children</b> by namespace and local name, and the direct
 * children of the two date containers, and nothing deeper:
 *
 * <ul>
 *   <li>{@code /*}{@code /cbc:IssueDate} (BT-2) and {@code /*}{@code /cbc:TaxPointDate} (BT-7);
 *   <li>{@code /*}{@code /cac:Delivery} and its {@code cbc:ActualDeliveryDate} (BT-72);
 *   <li>{@code /*}{@code /cac:InvoicePeriod} (BG-14) and its {@code cbc:StartDate} (BT-73), {@code
 *       cbc:EndDate} (BT-74) and {@code cbc:DescriptionCode} (BT-8).
 * </ul>
 *
 * <p>Nested copies are ignored: the earlier invoice's date in {@code cac:BillingReference} (BT-26),
 * line periods (BG-26), and anything in another namespace. Example: a credit note with BT-26 {@code
 * 2026-01-03} and BT-2 {@code 2026-03-12} reads BT-2 {@code 2026-03-12}.
 *
 * <p>Any of these elements twice is {@code AMBIGUOUS}. Another root, a parse error, a {@code
 * DOCTYPE}, or a date element with anything but text inside is {@code UNREADABLE}. Texts are
 * returned exactly as written, never trimmed and never parsed. This class never throws.
 */
public final class UblDocumentDates implements DocumentDates {

  static final String NS_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
  static final String NS_CREDIT_NOTE = "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2";
  static final String NS_CBC =
      "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
  static final String NS_CAC =
      "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";

  /** Thrown inside {@link #read} only, to leave the walk; never leaves this class. */
  private static final class Unreadable extends Exception {
    private static final long serialVersionUID = 1L;

    Unreadable() {
      super(null, null, false, false);
    }
  }

  /** Thrown inside {@link #read} only, to leave the walk; never leaves this class. */
  private static final class Ambiguous extends Exception {
    private static final long serialVersionUID = 1L;

    Ambiguous() {
      super(null, null, false, false);
    }
  }

  @Override
  public FoundDates read(byte[] bytes) {
    try {
      return walk(parse(bytes));
    } catch (Unreadable e) {
      return FoundDates.unreadable();
    } catch (Ambiguous e) {
      return FoundDates.ambiguous();
    } catch (RuntimeException e) {
      // Design review R3: the module's reader never throws. A parser's own runtime failure is a
      // document this reader could not read, and the rule answers NOT_EVALUATED for it.
      return FoundDates.unreadable();
    }
  }

  private static Element parse(byte[] bytes) throws Unreadable {
    if (bytes == null || bytes.length == 0) {
      throw new Unreadable();
    }
    try {
      DocumentBuilder builder = SecureXml.documentBuilderFactory().newDocumentBuilder();
      builder.setEntityResolver(SecureXml.refusingEntityResolver());
      builder.setErrorHandler(SILENT);
      return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    } catch (javax.xml.parsers.ParserConfigurationException
        | org.xml.sax.SAXException
        | java.io.IOException e) {
      throw new Unreadable();
    }
  }

  private static FoundDates walk(Element root) throws Unreadable, Ambiguous {
    if (root == null || !isUblRoot(root)) {
      throw new Unreadable();
    }
    Optional<Element> delivery = single(root, NS_CAC, "Delivery");
    Optional<Element> period = single(root, NS_CAC, "InvoicePeriod");
    Optional<String> issue = text(single(root, NS_CBC, "IssueDate"));
    boolean taxPoint = single(root, NS_CBC, "TaxPointDate").isPresent();
    Optional<String> deliveryDate = Optional.empty();
    if (delivery.isPresent()) {
      deliveryDate = text(single(delivery.get(), NS_CBC, "ActualDeliveryDate"));
    }
    Optional<String> start = Optional.empty();
    Optional<String> end = Optional.empty();
    boolean code = false;
    if (period.isPresent()) {
      start = text(single(period.get(), NS_CBC, "StartDate"));
      end = text(single(period.get(), NS_CBC, "EndDate"));
      code = single(period.get(), NS_CBC, "DescriptionCode").isPresent();
    }
    return new FoundDates(
        Readability.READ,
        issue,
        taxPoint,
        delivery.isPresent(),
        deliveryDate,
        period.isPresent(),
        start,
        end,
        code);
  }

  private static boolean isUblRoot(Element root) {
    return (NS_INVOICE.equals(root.getNamespaceURI()) && "Invoice".equals(root.getLocalName()))
        || (NS_CREDIT_NOTE.equals(root.getNamespaceURI())
            && "CreditNote".equals(root.getLocalName()));
  }

  /** The one direct child with this name; empty when there is none; ambiguous when two. */
  private static Optional<Element> single(Element parent, String ns, String local)
      throws Ambiguous {
    List<Element> found = new ArrayList<>(1);
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e
          && ns.equals(e.getNamespaceURI())
          && local.equals(e.getLocalName())) {
        found.add(e);
      }
    }
    if (found.size() > 1) {
      throw new Ambiguous();
    }
    return found.stream().findFirst();
  }

  /** The text exactly as written (design review R7: no strip), from text nodes only. */
  private static Optional<String> text(Optional<Element> element) throws Unreadable {
    if (element.isEmpty()) {
      return Optional.empty();
    }
    StringBuilder out = new StringBuilder();
    for (Node n = element.get().getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n.getNodeType() != Node.TEXT_NODE && n.getNodeType() != Node.CDATA_SECTION_NODE) {
        throw new Unreadable();
      }
      out.append(n.getNodeValue());
    }
    return Optional.of(out.toString());
  }

  /** Errors are a verdict here, not console output. */
  private static final ErrorHandler SILENT =
      new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
          // A warning does not change what the document says.
        }

        @Override
        public void error(SAXParseException exception) throws SAXParseException {
          throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXParseException {
          throw exception;
        }
      };
}
