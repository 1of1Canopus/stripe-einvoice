package com.housedevinci.einvoice.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A strict JSON reader for the one job this module has: reading an event's identity out of a
 * signature-verified webhook body (I-12).
 *
 * <p>It is not a general-purpose parser and is deliberately unpleasant to extend. It refuses what a
 * lenient parser resolves silently:
 *
 * <ul>
 *   <li><b>duplicate keys</b> - taking the first or the last is a choice, and two readers that
 *       choose differently disagree about which event this is;
 *   <li><b>malformed UTF-8</b> - a replacement character changes the bytes the signature covered;
 *   <li><b>unpaired surrogates</b> and raw C0 control characters inside string literals;
 *   <li><b>depth past {@link #MAX_DEPTH}</b> and any trailing content after the document.
 * </ul>
 *
 * <p>Numbers are kept as their literal text: nothing here needs their value, and turning one into a
 * {@code double} on the way past is how a magnitude becomes somebody else's problem. Everything
 * that reaches a document is re-fetched from the Stripe API and never read from this body (D-02).
 */
public final class StrictJson {

  /** Deep enough for any Stripe event, shallow enough that recursion is bounded. */
  public static final int MAX_DEPTH = 64;

  /**
   * A JSON number, kept as its literal text and as its own type.
   *
   * <p>Its own type, because {@code "id": 12345} and {@code "id": "12345"} are different documents
   * and an identifier reader must be able to tell them apart (I-12): a parser that hands both back
   * as a {@code String} has coerced the first one, quietly, on the path where the result becomes an
   * idempotency key.
   */
  public record JsonNumber(String literal) {}

  private final String text;
  private int at;
  private int depth;

  private StrictJson(String text) {
    this.text = text;
  }

  /** Parses one JSON object. The body is already capped in size before it reaches here. */
  public static Map<String, Object> parseObject(byte[] body) {
    if (body == null || body.length == 0) {
      throw unreadable("the request body is empty");
    }
    StrictJson parser = new StrictJson(decodeUtf8(body));
    parser.skipWhitespace();
    if (parser.at >= parser.text.length() || parser.text.charAt(parser.at) != '{') {
      throw unreadable("a Stripe event body must be a JSON object");
    }
    Object value = parser.readValue();
    parser.skipWhitespace();
    if (parser.at != parser.text.length()) {
      throw unreadable("the request body carries content after the JSON document");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> object = (Map<String, Object>) value;
    return object;
  }

  /** The string at a path, or empty when the path is absent or does not hold a string. */
  public static Optional<String> stringAt(Map<String, Object> root, String... path) {
    Object current = root;
    for (String key : path) {
      if (!(current instanceof Map<?, ?> map)) {
        return Optional.empty();
      }
      current = map.get(key);
    }
    return current instanceof String s ? Optional.of(s) : Optional.empty();
  }

  /** The boolean at a key, or empty when it is absent or is not a JSON boolean. */
  public static Optional<Boolean> booleanAt(Map<String, Object> root, String key) {
    Object value = root.get(key);
    return value instanceof Boolean b ? Optional.of(b) : Optional.empty();
  }

  private static String decodeUtf8(byte[] body) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      CharBuffer decoded = decoder.decode(ByteBuffer.wrap(body));
      return decoded.toString();
    } catch (CharacterCodingException e) {
      throw unreadable("the request body is not valid UTF-8");
    }
  }

  private Object readValue() {
    skipWhitespace();
    if (at >= text.length()) {
      throw unreadable("the JSON document ends where a value was expected");
    }
    char c = text.charAt(at);
    return switch (c) {
      case '{' -> readObject();
      case '[' -> readArray();
      case '"' -> readString();
      case 't' -> readLiteral("true", Boolean.TRUE);
      case 'f' -> readLiteral("false", Boolean.FALSE);
      case 'n' -> readLiteral("null", null);
      default -> readNumber();
    };
  }

  private Map<String, Object> readObject() {
    enter();
    Map<String, Object> object = new LinkedHashMap<>();
    at++; // {
    skipWhitespace();
    if (peek() == '}') {
      at++;
      leave();
      return object;
    }
    while (true) {
      skipWhitespace();
      if (peek() != '"') {
        throw unreadable("a JSON object key must be a string");
      }
      String key = readString();
      skipWhitespace();
      if (peek() != ':') {
        throw unreadable("a JSON object key must be followed by ':'");
      }
      at++;
      Object value = readValue();
      if (object.containsKey(key)) {
        // Never "the first wins" and never "the last wins": both are a decision, and the point of
        // this reader is that the key it derives is one nobody can argue about.
        throw unreadable("the JSON object carries the same key twice");
      }
      object.put(key, value);
      skipWhitespace();
      char next = peek();
      if (next == ',') {
        at++;
        continue;
      }
      if (next == '}') {
        at++;
        leave();
        return object;
      }
      throw unreadable("a JSON object member must be followed by ',' or '}'");
    }
  }

  private List<Object> readArray() {
    enter();
    List<Object> values = new ArrayList<>();
    at++; // [
    skipWhitespace();
    if (peek() == ']') {
      at++;
      leave();
      return values;
    }
    while (true) {
      values.add(readValue());
      skipWhitespace();
      char next = peek();
      if (next == ',') {
        at++;
        continue;
      }
      if (next == ']') {
        at++;
        leave();
        return values;
      }
      throw unreadable("a JSON array element must be followed by ',' or ']'");
    }
  }

  private String readString() {
    at++; // opening quote
    StringBuilder out = new StringBuilder();
    while (true) {
      if (at >= text.length()) {
        throw unreadable("a JSON string is not terminated");
      }
      char c = text.charAt(at++);
      if (c == '"') {
        return out.toString();
      }
      if (c == '\\') {
        out.append(readEscape());
        continue;
      }
      if (c < 0x20) {
        throw unreadable("a JSON string carries a raw control character");
      }
      if (Character.isHighSurrogate(c)) {
        if (at >= text.length() || !Character.isLowSurrogate(text.charAt(at))) {
          throw unreadable("a JSON string carries an unpaired surrogate");
        }
        out.append(c).append(text.charAt(at++));
        continue;
      }
      if (Character.isLowSurrogate(c)) {
        throw unreadable("a JSON string carries an unpaired surrogate");
      }
      out.append(c);
    }
  }

  private String readEscape() {
    if (at >= text.length()) {
      throw unreadable("a JSON string ends inside an escape");
    }
    char c = text.charAt(at++);
    return switch (c) {
      case '"' -> "\"";
      case '\\' -> "\\";
      case '/' -> "/";
      case 'b' -> "\b";
      case 'f' -> "\f";
      case 'n' -> "\n";
      case 'r' -> "\r";
      case 't' -> "\t";
      case 'u' -> readUnicodeEscape();
      default -> throw unreadable("a JSON string carries an unknown escape");
    };
  }

  private String readUnicodeEscape() {
    char first = readHex4();
    if (Character.isHighSurrogate(first)) {
      if (at + 1 < text.length() && text.charAt(at) == '\\' && text.charAt(at + 1) == 'u') {
        at += 2;
        char second = readHex4();
        if (!Character.isLowSurrogate(second)) {
          throw unreadable("a JSON string carries an unpaired surrogate escape");
        }
        return new String(new char[] {first, second});
      }
      throw unreadable("a JSON string carries an unpaired surrogate escape");
    }
    if (Character.isLowSurrogate(first)) {
      throw unreadable("a JSON string carries an unpaired surrogate escape");
    }
    return String.valueOf(first);
  }

  private char readHex4() {
    if (at + 4 > text.length()) {
      throw unreadable("a JSON \\u escape is truncated");
    }
    int value = 0;
    for (int i = 0; i < 4; i++) {
      int digit = Character.digit(text.charAt(at + i), 16);
      if (digit < 0) {
        throw unreadable("a JSON \\u escape is not hexadecimal");
      }
      value = value * 16 + digit;
    }
    at += 4;
    return (char) value;
  }

  private Object readLiteral(String literal, Object value) {
    if (!text.startsWith(literal, at)) {
      throw unreadable("the JSON document carries an unknown literal");
    }
    at += literal.length();
    return value;
  }

  /**
   * Numbers are kept as text. Nothing on this path needs their value, and a parser that turns a
   * literal into a {@code double} on the way past has made a magnitude decision on behalf of code
   * that never asked for one (checklist lines 1 and 2).
   */
  private JsonNumber readNumber() {
    int start = at;
    if (peek() == '-') {
      at++;
    }
    boolean digits = false;
    while (at < text.length() && isNumberChar(text.charAt(at))) {
      digits |= Character.isDigit(text.charAt(at));
      at++;
    }
    if (!digits) {
      throw unreadable("the JSON document carries a value that is not JSON");
    }
    return new JsonNumber(text.substring(start, at));
  }

  private static boolean isNumberChar(char c) {
    return Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
  }

  private char peek() {
    if (at >= text.length()) {
      throw unreadable("the JSON document ends where more was expected");
    }
    return text.charAt(at);
  }

  private void skipWhitespace() {
    while (at < text.length()) {
      char c = text.charAt(at);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        at++;
      } else {
        return;
      }
    }
  }

  private void enter() {
    if (++depth > MAX_DEPTH) {
      throw unreadable("the JSON document nests past the depth bound of " + MAX_DEPTH);
    }
  }

  private void leave() {
    depth--;
  }

  /** The message never quotes the body: it is buyer data, and it may be an attacker's. */
  private static EInvoiceException unreadable(String what) {
    return new EInvoiceException(ErrorCodes.INBOUND_UNREADABLE, what);
  }
}
