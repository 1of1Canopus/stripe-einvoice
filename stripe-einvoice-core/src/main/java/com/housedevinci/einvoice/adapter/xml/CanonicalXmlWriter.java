package com.housedevinci.einvoice.adapter.xml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes Canonical XML 1.1 form directly.
 *
 * <h2>Why this exists instead of JAXB</h2>
 *
 * <p>The bytes this library emits are the bytes spec 02 signs and spec 03 submits. That makes
 * namespace prefixes, attribute order and empty-element form part of the contract, not
 * implementation details. A JAXB marshaller guarantees none of the three: they depend on which
 * Jakarta XML Bind implementation is on the classpath at run time, so "deterministic" would mean
 * "deterministic on the machine we tested". Emitting canonical form directly also means signing
 * does not have to canonicalise, so it cannot change what was validated.
 *
 * <p>The vendored XSDs are then the check that the structure written by hand is right, and the
 * vendored schematron is the check that it is an invoice; both are stronger checks than trusting a
 * code generator.
 *
 * <p>Carried over from this owner's Morocco e-invoicing generators, where it was written and
 * reviewed for the same property. Only the package and the wording of this comment changed: a
 * canonical writer that has already survived a security pass is not something to write twice.
 *
 * <h2>What canonical form means here</h2>
 *
 * <ul>
 *   <li>UTF-8, no byte-order mark, no XML declaration (canonical form has none).
 *   <li>Every element written as a start tag and an end tag; never {@code <a/>}.
 *   <li>Namespace declarations first, sorted by prefix, the default namespace before all of them;
 *       then attributes sorted by namespace URI, then local name.
 *   <li>Text escapes {@code &}, {@code <}, {@code >} and carriage return; attribute values escape
 *       {@code &}, {@code <}, {@code "}, tab, line feed and carriage return.
 *   <li>No {@code xsi:schemaLocation}, no processing instructions, no comments, no whitespace
 *       between elements: nothing that carries information the caller did not put there.
 * </ul>
 *
 * <p>A superfluous namespace declaration is one already in scope with the same URI; it is dropped,
 * as C14N requires. Declaring every namespace on the root and using every one of them is the shape
 * this module emits, so there is nothing to drop in practice.
 *
 * <p>Not thread-safe: one writer produces one document.
 */
public final class CanonicalXmlWriter {

  private static final Comparator<Attribute> ATTRIBUTE_ORDER =
      Comparator.comparing((Attribute a) -> a.namespaceUri).thenComparing(a -> a.localName);

  private final StringBuilder out = new StringBuilder(4096);
  private final Deque<String> openElements = new ArrayDeque<>();
  private final Deque<Map<String, String>> scopes = new ArrayDeque<>();

  private String pendingQName;
  private List<Attribute> pendingAttributes;
  private Map<String, String> pendingNamespaces;

  /** A writer with nothing written yet. */
  public CanonicalXmlWriter() {
    scopes.push(new LinkedHashMap<>());
  }

  private record Attribute(String namespaceUri, String localName, String qName, String value) {}

  /**
   * Opens an element. Attributes and namespace declarations may be added until the first call to
   * {@link #text} or to another {@link #start} or {@link #end}.
   *
   * @param qName the qualified name exactly as it will be written, for example {@code cbc:ID}
   * @return this writer
   */
  public CanonicalXmlWriter start(String qName) {
    Objects.requireNonNull(qName, "qName");
    requireValidQName(qName);
    flushPending();
    pendingQName = qName;
    pendingAttributes = new ArrayList<>(4);
    pendingNamespaces = new LinkedHashMap<>();
    return this;
  }

  /**
   * Declares a namespace on the element being opened.
   *
   * @param prefix the prefix, or the empty string for the default namespace
   * @param uri the namespace URI
   * @return this writer
   */
  public CanonicalXmlWriter namespace(String prefix, String uri) {
    requireOpening();
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(uri, "uri");
    if (!prefix.isEmpty()) {
      requireValidQName(prefix);
    }
    requireValidText(uri);
    if (uri.equals(inScope(prefix))) {
      return this; // superfluous declaration: canonical form drops it
    }
    pendingNamespaces.put(prefix, uri);
    return this;
  }

  /**
   * Adds an attribute to the element being opened.
   *
   * @param namespaceUri the attribute's namespace URI, empty for an unprefixed attribute
   * @param localName the local name
   * @param qName the qualified name as it will be written
   * @param value the value, escaped by this writer
   * @return this writer
   */
  public CanonicalXmlWriter attribute(
      String namespaceUri, String localName, String qName, String value) {
    requireOpening();
    Objects.requireNonNull(namespaceUri, "namespaceUri");
    Objects.requireNonNull(localName, "localName");
    Objects.requireNonNull(qName, "qName");
    Objects.requireNonNull(value, "value");
    requireValidQName(qName);
    requireValidText(value);
    pendingAttributes.add(new Attribute(namespaceUri, localName, qName, value));
    return this;
  }

  /**
   * Adds an unprefixed attribute.
   *
   * @param name the attribute name
   * @param value the value
   * @return this writer
   */
  public CanonicalXmlWriter attribute(String name, String value) {
    return attribute("", name, name, value);
  }

  /**
   * Writes character data inside the element being opened.
   *
   * @param value the text, escaped by this writer
   * @return this writer
   */
  public CanonicalXmlWriter text(String value) {
    Objects.requireNonNull(value, "value");
    requireValidText(value);
    flushPending();
    if (openElements.isEmpty()) {
      throw new IllegalStateException("text outside any element");
    }
    escapeText(value, out);
    return this;
  }

  /**
   * Convenience for a leaf element with text and no attributes.
   *
   * @param qName the qualified name
   * @param value the text
   * @return this writer
   */
  public CanonicalXmlWriter element(String qName, String value) {
    return start(qName).text(value).end();
  }

  /**
   * Closes the innermost open element.
   *
   * @return this writer
   */
  public CanonicalXmlWriter end() {
    flushPending();
    if (openElements.isEmpty()) {
      throw new IllegalStateException("no element is open");
    }
    out.append("</").append(openElements.pop()).append('>');
    scopes.pop();
    return this;
  }

  /**
   * @return the canonical bytes
   * @throws IllegalStateException if an element is still open
   */
  public byte[] toBytes() {
    flushPending();
    if (!openElements.isEmpty()) {
      throw new IllegalStateException("document has unclosed elements");
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private void requireOpening() {
    if (pendingQName == null) {
      throw new IllegalStateException("no element is being opened");
    }
  }

  private String inScope(String prefix) {
    for (Map<String, String> scope : scopes) {
      String uri = scope.get(prefix);
      if (uri != null) {
        return uri;
      }
    }
    return null;
  }

  private void flushPending() {
    if (pendingQName == null) {
      return;
    }
    Map<String, String> scope = new LinkedHashMap<>(pendingNamespaces);
    out.append('<').append(pendingQName);

    // Namespace declarations first: the default namespace, then the prefixes in sorted order.
    List<String> prefixes = new ArrayList<>(pendingNamespaces.keySet());
    prefixes.sort(Comparator.naturalOrder());
    for (String prefix : prefixes) {
      out.append(' ');
      if (prefix.isEmpty()) {
        out.append("xmlns");
      } else {
        out.append("xmlns:").append(prefix);
      }
      out.append("=\"");
      escapeAttribute(pendingNamespaces.get(prefix), out);
      out.append('"');
    }

    pendingAttributes.sort(ATTRIBUTE_ORDER);
    for (Attribute a : pendingAttributes) {
      out.append(' ').append(a.qName).append("=\"");
      escapeAttribute(a.value, out);
      out.append('"');
    }
    out.append('>');

    openElements.push(pendingQName);
    scopes.push(scope);
    pendingQName = null;
    pendingAttributes = null;
    pendingNamespaces = null;
  }

  /**
   * Refuses a qualified name this writer cannot canonicalise: empty, or carrying whitespace, markup
   * ({@code <} {@code >} {@code &} {@code "} {@code '}), {@code =}, {@code /} or a control
   * character. Every qName in the two generators is a constant; this is a programming error, never
   * an invoice refusal, so it throws {@link IllegalArgumentException}.
   */
  private static void requireValidQName(String qName) {
    if (qName.isEmpty()) {
      throw new IllegalArgumentException("empty qName");
    }
    for (int i = 0; i < qName.length(); i++) {
      char c = qName.charAt(i);
      if (Character.isWhitespace(c)
          || isC0Control(c)
          || Character.isSurrogate(c)
          || c == '<'
          || c == '>'
          || c == '&'
          || c == '"'
          || c == '\''
          || c == '='
          || c == '/') {
        throw new IllegalArgumentException("qName '" + qName + "' contains an illegal character");
      }
    }
  }

  /**
   * Refuses text or an attribute value this writer cannot canonicalise instead of writing it raw or
   * substituting it: a C0 control character other than tab, line feed or carriage return, an
   * unpaired UTF-16 surrogate, or an XML 1.0 non-character (D3-01). {@link
   * IllegalArgumentException} because these are programming errors on the caller's part; {@code
   * ScreenedText} in the domain package already refuses all three, and more, before any value
   * reaches this class through the production path. This is the second line of defence for a caller
   * that does not go through it.
   */
  private static void requireValidText(String s) {
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      int codePoint = s.codePointAt(i);
      int codePointCount = Character.charCount(codePoint);
      if (codePointCount == 1) {
        if (Character.isSurrogate(c)) {
          throw new IllegalArgumentException("unpaired surrogate at index " + i);
        }
        if (isC0Control(c)) {
          throw new IllegalArgumentException("control character at index " + i);
        }
      }
      if (isXmlNonCharacter(codePoint)) {
        throw new IllegalArgumentException("XML non-character at index " + i);
      }
      i += codePointCount;
    }
  }

  private static boolean isC0Control(char c) {
    return c <= 0x1F && c != '\t' && c != '\n' && c != '\r';
  }

  // D3-01: same clause as ScreenedText.isXmlNonCharacter - kept here too because this writer must
  // refuse on its own for a caller that does not go through ScreenedText.
  private static boolean isXmlNonCharacter(int codePoint) {
    return (codePoint & 0xFFFE) == 0xFFFE || (codePoint >= 0xFDD0 && codePoint <= 0xFDEF);
  }

  private static void escapeText(String s, StringBuilder sb) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '\r' -> sb.append("&#xD;");
        default -> sb.append(c);
      }
    }
  }

  private static void escapeAttribute(String s, StringBuilder sb) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '"' -> sb.append("&quot;");
        case '\t' -> sb.append("&#x9;");
        case '\n' -> sb.append("&#xA;");
        case '\r' -> sb.append("&#xD;");
        default -> sb.append(c);
      }
    }
  }
}
