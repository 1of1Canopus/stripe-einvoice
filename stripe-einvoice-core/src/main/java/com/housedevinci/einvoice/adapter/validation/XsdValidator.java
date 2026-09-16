package com.housedevinci.einvoice.adapter.validation;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Structural validation of the <b>exact bytes that will be archived</b> against the vendored UBL
 * 2.1 schema (checklist line 15).
 *
 * <p>This runs before the schematron, because a schematron over a document that is not structurally
 * UBL reports rules that never matched anything and calls the result a pass. It is not a
 * conformance statement on its own: a document can satisfy the schema and be a nonsense invoice,
 * which is exactly what the schematron is for.
 *
 * <p>Compiled schemas are cached per document type; {@link Schema} is thread-safe and {@link
 * Validator} is not, so a validator is created per call and never shared.
 */
public final class XsdValidator {

  private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

  /** One finding: a schema error, with no document content quoted (checklist line 45). */
  public record Finding(String ruleId, String message) {}

  /**
   * @param bytes the exact bytes that will be archived
   * @param creditNote whether the document is a UBL {@code CreditNote} rather than an {@code
   *     Invoice}
   * @return the findings; empty means the document is structurally UBL 2.1
   */
  public List<Finding> validate(byte[] bytes, boolean creditNote) {
    String path =
        creditNote ? VendoredArtefacts.UBL_CREDIT_NOTE_XSD : VendoredArtefacts.UBL_INVOICE_XSD;
    Schema schema = compiled.computeIfAbsent(path, XsdValidator::compile);
    Validator validator = schema.newValidator();
    try {
      validator.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
      validator.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    } catch (SAXException e) {
      throw new EInvoiceException(
          ErrorCodes.XML_REFUSED, "this JDK cannot be configured securely for XML", e);
    }
    validator.setResourceResolver(new VendoredSchemaResolver());
    CollectingErrorHandler handler = new CollectingErrorHandler();
    validator.setErrorHandler(handler);
    // The bytes are parsed with the hardened parser, so a DOCTYPE in them is a refusal before the
    // schema ever sees the document (D-07).
    try {
      javax.xml.parsers.SAXParser parser = SecureXml.saxParserFactory().newSAXParser();
      parser.getXMLReader().setEntityResolver(SecureXml.refusingEntityResolver());
      validator.validate(
          new javax.xml.transform.sax.SAXSource(
              parser.getXMLReader(), new org.xml.sax.InputSource(new ByteArrayInputStream(bytes))));
    } catch (SAXException refused) {
      // A DOCTYPE, an entity, or XML that does not parse at all. Never a pass, and never a
      // message carrying the document's own content.
      return List.of(
          new Finding(
              "XSD-PARSE",
              "the bytes are not well-formed XML this module will read, or they carry a document"
                  + " type declaration, which is refused outright"));
    } catch (IOException | javax.xml.parsers.ParserConfigurationException e) {
      throw new EInvoiceException(
          ErrorCodes.XML_REFUSED, "the document could not be read for schema validation", e);
    }
    return handler.findings;
  }

  private static Schema compile(String path) {
    VendoredArtefacts.requireIntact(path);
    try (InputStream in = VendoredArtefacts.open(path)) {
      javax.xml.validation.SchemaFactory factory = SecureXml.schemaFactory();
      factory.setResourceResolver(new VendoredSchemaResolver());
      factory.setErrorHandler(new FailingErrorHandler());
      // The system id is the vendored path, so the set's own relative imports resolve through the
      // resolver above rather than against a file system location.
      StreamSource source = new StreamSource(in, VendoredArtefacts.ROOT + path);
      return factory.newSchema(source);
    } catch (SAXException | IOException e) {
      throw new EInvoiceException(
          ErrorCodes.ARTEFACT_TAMPERED,
          "a vendored UBL schema could not be compiled, so no document can be checked against it",
          e);
    }
  }

  /** Collects schema errors without ever quoting the offending value. */
  private static final class CollectingErrorHandler implements ErrorHandler {

    private final List<Finding> findings = new ArrayList<>();

    @Override
    public void warning(SAXParseException exception) {
      // A schema warning is not a defect in the document.
    }

    @Override
    public void error(SAXParseException exception) {
      findings.add(finding(exception));
    }

    @Override
    public void fatalError(SAXParseException exception) {
      findings.add(finding(exception));
    }

    /**
     * The message is the parser's, and the parser's messages name elements and types rather than
     * values - with one exception, an enumeration or pattern failure, which quotes the value. The
     * message is therefore reduced to its location, which is structure and not content.
     */
    private static Finding finding(SAXParseException exception) {
      return new Finding(
          "XSD",
          "the document does not match the UBL 2.1 schema at line "
              + exception.getLineNumber()
              + ", column "
              + exception.getColumnNumber());
    }
  }

  /** A schema that will not compile is a build failure, never a document that passes. */
  private static final class FailingErrorHandler implements ErrorHandler {

    @Override
    public void warning(SAXParseException exception) {
      // Nothing: a warning while compiling a vendored schema is not a reason to stop.
    }

    @Override
    public void error(SAXParseException exception) throws SAXException {
      throw exception;
    }

    @Override
    public void fatalError(SAXParseException exception) throws SAXException {
      throw exception;
    }
  }
}
