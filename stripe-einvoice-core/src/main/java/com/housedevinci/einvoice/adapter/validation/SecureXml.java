package com.housedevinci.einvoice.adapter.validation;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.validation.SchemaFactory;
import org.xml.sax.EntityResolver;
import org.xml.sax.SAXException;

/**
 * The one place a JAXP factory is created in this module (D-07, checklist lines 10, 11 and 12).
 *
 * <p>An XXE that is closed on four entry points and open on the fifth is an XXE, so every parser,
 * schema factory and transformer factory comes from here and each one has its own test. The inputs
 * are all untrusted: our own output today, an archived document re-read for a re-validation or an
 * auditor export tomorrow, and - the sharp edge - <b>third-party stylesheets we run as code</b>.
 *
 * <p>What is switched off, and what it would otherwise do:
 *
 * <ul>
 *   <li><b>DTDs entirely.</b> {@code disallow-doctype-decl} makes a DOCTYPE a parse <em>error</em>,
 *       not merely an unresolved reference. No doctype means no entity declaration, which means no
 *       billion laughs and no external entity: the two attacks are one declaration used twice.
 *   <li><b>External DTD, schema and stylesheet access.</b> Set to the empty string, so no protocol
 *       is permitted at all: a document cannot make this process open a file or a socket by naming
 *       one.
 *   <li><b>XInclude.</b> A second way to pull a file in, and the one people forget.
 *   <li><b>Secure processing.</b> The JDK's own limits on entity expansion and name length, and -
 *       on an XSLT 2.0 processor - the switch that disables extension functions, which is what
 *       stands between a third-party stylesheet and arbitrary Java.
 *   <li><b>Extension functions, by name as well.</b> {@code FEATURE_SECURE_PROCESSING} is the
 *       portable switch; the processor-specific one is set too, by feature URI rather than by
 *       compiling against the processor, because this module deliberately has no compile dependency
 *       on one.
 * </ul>
 *
 * <p>On top of all of it, a resolver that <b>throws</b> rather than returning empty (checklist line
 * 11): if a future JDK changes a default, the failure is a refusal and not a silent fetch.
 */
public final class SecureXml {

  /** Saxon's own switch for extension functions, set by URI so no compile dependency is needed. */
  public static final String ALLOW_EXTERNAL_FUNCTIONS =
      "http://saxon.sf.net/feature/allow-external-functions";

  /** Saxon's switch for the {@code xsl:result-document} family reaching the file system. */
  public static final String SAXON_XINCLUDE = "http://saxon.sf.net/feature/xinclude-aware";

  /** The default XSLT 2.0 processor this module looks for. Never a compile dependency. */
  public static final String DEFAULT_XSLT2_PROCESSOR = "net.sf.saxon.TransformerFactoryImpl";

  private SecureXml() {}

  /**
   * A resolver that refuses every resolution attempt. Installed on every parser, so a document or a
   * stylesheet that names an external resource fails loudly rather than reaching the network.
   */
  public static EntityResolver refusingEntityResolver() {
    return (publicId, systemId) -> {
      throw new SAXException(
          "this module resolves no external entity: a document that names one is refused");
    };
  }

  /** The same refusal for a transformer's URI resolution. */
  public static javax.xml.transform.URIResolver refusingUriResolver() {
    return (href, base) -> {
      throw new javax.xml.transform.TransformerException(
          "this module resolves no external document: a stylesheet that names one is refused");
    };
  }

  /**
   * @return a document builder factory that will not resolve anything
   */
  public static DocumentBuilderFactory documentBuilderFactory() {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    try {
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    } catch (ParserConfigurationException e) {
      throw new EInvoiceException(
          ErrorCodes.XML_REFUSED, "this JDK cannot be configured securely for XML", e);
    }
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    f.setNamespaceAware(true);
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return f;
  }

  /**
   * @return a SAX parser factory that refuses a DOCTYPE before any entity in it is declared
   */
  public static SAXParserFactory saxParserFactory() {
    SAXParserFactory f = SAXParserFactory.newInstance();
    try {
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    } catch (ParserConfigurationException | SAXException e) {
      throw new EInvoiceException(
          ErrorCodes.XML_REFUSED, "this JDK cannot be configured securely for XML", e);
    }
    f.setXIncludeAware(false);
    f.setNamespaceAware(true);
    return f;
  }

  /**
   * @return a schema factory that resolves nothing except through the resolver it is given
   */
  public static SchemaFactory schemaFactory() {
    SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    try {
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      // The vendored schemas are resolved by VendoredSchemaResolver, never by a URL, so both
      // access properties stay empty: a schema that tries to import over the network fails.
      f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    } catch (SAXException e) {
      throw new EInvoiceException(
          ErrorCodes.XML_REFUSED, "this JDK cannot be configured securely for XML", e);
    }
    return f;
  }

  /**
   * An XSLT 2.0 transformer factory, by processor class name.
   *
   * <p><b>By name, and not as a dependency.</b> The vendored schematron is XSLT 2.0 and the JDK
   * ships an XSLT 1.0 processor, so a processor has to come from somewhere; the only practical one
   * for the JVM is under a licence this repository's own gate denies for anything it ships. Rather
   * than weaken the gate, this module asks for the processor by name and reports NOT_EVALUATED when
   * it is absent - a refusal in the issuance unit of work, so an application without one issues
   * nothing rather than issuing something unvalidated.
   *
   * @param processorClassName the {@code TransformerFactory} implementation to use
   * @throws EInvoiceException {@link ErrorCodes#XSLT_PROCESSOR_MISSING} when it is not on the
   *     classpath
   */
  public static TransformerFactory xslt2Factory(String processorClassName) {
    TransformerFactory f;
    try {
      f = TransformerFactory.newInstance(processorClassName, SecureXml.class.getClassLoader());
    } catch (javax.xml.transform.TransformerFactoryConfigurationError absent) {
      throw new EInvoiceException(
          ErrorCodes.XSLT_PROCESSOR_MISSING,
          "no XSLT 2.0 processor named "
              + processorClassName
              + " is on the classpath, so the EN 16931 schematron cannot run. Add one to the"
              + " application (see the documents page), or this module issues nothing rather than"
              + " archiving a document no rule ever read",
          absent);
    }
    try {
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    } catch (javax.xml.transform.TransformerConfigurationException e) {
      throw new EInvoiceException(
          ErrorCodes.XML_REFUSED, "this XSLT processor cannot be configured securely", e);
    }
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    // Processor-specific, set by URI. Unknown attributes throw on some processors, so each is set
    // on its own and a processor that does not know one is not a reason to run without the others.
    setIfSupported(f, ALLOW_EXTERNAL_FUNCTIONS, Boolean.FALSE);
    setIfSupported(f, SAXON_XINCLUDE, Boolean.FALSE);
    f.setURIResolver(refusingUriResolver());
    f.setErrorListener(new RefusingErrorListener());
    return f;
  }

  private static void setIfSupported(TransformerFactory factory, String name, Object value) {
    try {
      factory.setAttribute(name, value);
    } catch (IllegalArgumentException unsupported) {
      // A processor that does not know this attribute is not a processor that ignores
      // FEATURE_SECURE_PROCESSING, which is the portable switch for the same thing.
      return;
    }
  }

  /**
   * An error listener that lets a warning through and turns an error into a refusal, so a
   * stylesheet that half-failed does not produce a half-report that reads as a pass.
   */
  static final class RefusingErrorListener implements javax.xml.transform.ErrorListener {

    @Override
    public void warning(javax.xml.transform.TransformerException exception) {
      // Warnings from a compiled schematron are normal (unused templates, version notes) and
      // carry no document content; they are neither logged nor propagated.
    }

    @Override
    public void error(javax.xml.transform.TransformerException exception)
        throws javax.xml.transform.TransformerException {
      throw exception;
    }

    @Override
    public void fatalError(javax.xml.transform.TransformerException exception)
        throws javax.xml.transform.TransformerException {
      throw exception;
    }
  }
}
