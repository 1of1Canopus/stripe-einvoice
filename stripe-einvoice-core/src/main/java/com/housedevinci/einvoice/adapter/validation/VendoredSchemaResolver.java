package com.housedevinci.einvoice.adapter.validation;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

/**
 * Resolves the UBL schema set's own imports against the vendored copy, and refuses everything else
 * (checklist lines 11 and 14).
 *
 * <p><b>Not by file name alone</b> (C17-13). A resolver that matches on the last path segment hands
 * back whatever file carries that name, from wherever, which makes the name the whole of the
 * security decision. Here the name selects a <em>candidate</em> inside the checksum manifest - so
 * only a file this module vendors can ever be a candidate - and the candidate is then accepted only
 * if its own {@code targetNamespace}, read out of the file, is the namespace that was asked for.
 * The namespace is read from the vendored bytes rather than from a table somebody maintains, so the
 * check cannot drift away from what the files actually declare.
 *
 * <p><b>A stable system id.</b> What is handed back carries the canonical vendored path as its
 * system id, not the relative path the importing schema happened to use. Xerces keys a loaded
 * grammar on (namespace, system id): returning the requester's own spelling makes one file load
 * twice under two ids, and the second load collides with the first on every global component it
 * declares.
 *
 * <p>Anything that is not a vendored file - an absolute URL, a path with {@code ..}, a name this
 * module does not carry - <b>throws</b> rather than returning empty, so a schema or a document that
 * tried to reach the network fails loudly instead of being quietly under-validated.
 */
final class VendoredSchemaResolver implements LSResourceResolver {

  /** Vendored path to the {@code targetNamespace} its own bytes declare. Read once, then cached. */
  private static final Map<String, String> NAMESPACES = new ConcurrentHashMap<>();

  @Override
  public LSInput resolveResource(
      String type, String namespaceUri, String publicId, String systemId, String baseUri) {
    String path = candidate(systemId);
    if (path == null || !VendoredArtefacts.expectedChecksums().containsKey(path)) {
      throw new EInvoiceException(
          ErrorCodes.XML_REFUSED,
          "a schema asked this module to resolve something it does not vendor ("
              + (systemId == null ? "no system id" : systemId)
              + "). Nothing is fetched, so the request is refused rather than answered with"
              + " whichever file happened to be reachable");
    }
    if (namespaceUri != null && !namespaceUri.equals(targetNamespaceOf(path))) {
      throw new EInvoiceException(
          ErrorCodes.XML_REFUSED,
          "a schema import named a vendored file whose own target namespace is not the namespace"
              + " that was asked for. Matching on the file name alone would have accepted it");
    }
    // The canonical path as the system id, never the requester's relative spelling: one file has
    // to have one identity or its global components are loaded twice and collide with themselves.
    return new ClasspathInput(publicId, VendoredArtefacts.ROOT + path, baseUri, path);
  }

  /**
   * The vendored file a {@code schemaLocation} names.
   *
   * <p>Only the last path segment is read, and it selects inside the checksum manifest - the set
   * this module vendors and nothing else - so a {@code ../} in the requester's own spelling reaches
   * nothing outside it. The vendored UBL schemas import each other exactly that way ({@code
   * ../common/UBL-CommonAggregateComponents-2.1.xsd}), so refusing the sequence outright would
   * refuse the set's own imports while adding nothing: the escape it guards against is already
   * impossible once the name has to be one of fourteen known files, whose namespace is then checked
   * as well.
   *
   * <p>A URL is a different matter and is refused: a schema that names one is a schema trying to
   * fetch.
   */
  private static String candidate(String systemId) {
    if (systemId == null || systemId.contains("://")) {
      return null;
    }
    int slash = systemId.lastIndexOf('/');
    String name = slash < 0 ? systemId : systemId.substring(slash + 1);
    if (name.isEmpty()) {
      return null;
    }
    for (String path : VendoredArtefacts.expectedChecksums().keySet()) {
      if (path.endsWith("/" + name)) {
        return path;
      }
    }
    return null;
  }

  /** The {@code targetNamespace} a vendored schema declares, read from its own bytes. */
  private static String targetNamespaceOf(String path) {
    return NAMESPACES.computeIfAbsent(
        path,
        p -> {
          try (InputStream in = VendoredArtefacts.open(p)) {
            javax.xml.stream.XMLStreamReader reader =
                secureStreamReaderFactory().createXMLStreamReader(in);
            try {
              while (reader.hasNext()) {
                if (reader.next() == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                  String declared = reader.getAttributeValue(null, "targetNamespace");
                  return declared == null ? "" : declared;
                }
              }
            } finally {
              reader.close();
            }
            return "";
          } catch (java.io.IOException | javax.xml.stream.XMLStreamException e) {
            throw new EInvoiceException(
                ErrorCodes.ARTEFACT_TAMPERED,
                "a vendored schema could not be read to establish its own namespace",
                e);
          }
        });
  }

  private static javax.xml.stream.XMLInputFactory secureStreamReaderFactory() {
    javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newInstance();
    factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
    factory.setProperty(
        javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    factory.setXMLResolver(
        (publicId, systemId, baseUri, namespace) -> {
          throw new javax.xml.stream.XMLStreamException("external entities are refused");
        });
    return factory;
  }

  /** An {@link LSInput} over one vendored file. */
  private static final class ClasspathInput implements LSInput {

    private final String publicId;
    private final String systemId;
    private final String baseUri;
    private final String path;

    ClasspathInput(String publicId, String systemId, String baseUri, String path) {
      this.publicId = publicId;
      this.systemId = systemId;
      this.baseUri = baseUri;
      this.path = path;
    }

    @Override
    public java.io.Reader getCharacterStream() {
      return null;
    }

    @Override
    public void setCharacterStream(java.io.Reader characterStream) {
      // Nothing to set: this input is read-only and backed by the classpath.
    }

    @Override
    public InputStream getByteStream() {
      return VendoredArtefacts.open(path);
    }

    @Override
    public void setByteStream(InputStream byteStream) {
      // Nothing to set, for the same reason.
    }

    @Override
    public String getStringData() {
      return null;
    }

    @Override
    public void setStringData(String stringData) {
      // Nothing to set, for the same reason.
    }

    @Override
    public String getSystemId() {
      return systemId;
    }

    @Override
    public void setSystemId(String systemId) {
      // Nothing to set, for the same reason.
    }

    @Override
    public String getPublicId() {
      return publicId;
    }

    @Override
    public void setPublicId(String publicId) {
      // Nothing to set, for the same reason.
    }

    @Override
    public String getBaseURI() {
      return baseUri;
    }

    @Override
    public void setBaseURI(String baseUri) {
      // Nothing to set, for the same reason.
    }

    @Override
    public String getEncoding() {
      return "UTF-8";
    }

    @Override
    public void setEncoding(String encoding) {
      // Nothing to set, for the same reason.
    }

    @Override
    public boolean getCertifiedText() {
      return false;
    }

    @Override
    public void setCertifiedText(boolean certifiedText) {
      // Nothing to set, for the same reason.
    }
  }
}
