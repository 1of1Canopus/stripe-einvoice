package com.housedevinci.einvoice.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The one screening function every free-text value passes through before it reaches a document, a
 * chained row or an auditor-facing report (D-06, checklist lines 7 and 8).
 *
 * <p>It is in {@code domain}, JDK-only, and shared: the XML writers, the PDF text layer and the XMP
 * packet of later pull requests call this and nothing else. A field that reaches the bytes without
 * passing through here is a finding, not a style question.
 *
 * <p>What it refuses, and why each one is a refusal rather than a repair:
 *
 * <ul>
 *   <li><b>C0 control characters</b> other than TAB, LF and CR. They survive schema validation and
 *       make a schematron fail three hops later, at the recipient, where nobody can fix them.
 *   <li><b>Unpaired surrogates.</b> A writer emits them as invalid XML; the document is then
 *       unparseable by the party who has to act on it.
 *   <li><b>A value that collapses to blank</b>, or that is only whitespace. A blank name that
 *       passes a whitespace-insensitive schema check is a business rule failure at the recipient
 *       (C17-37).
 *   <li><b>Anything past its declared length bound.</b> EN 16931 bounds its fields; discovering
 *       that at the recipient rather than here is the same defect one hop too late.
 *   <li><b>An XML 1.0 non-character</b> (U+FFFE, U+FFFF, U+FDD0..U+FDEF, and the last two code
 *       points of every plane, D3-01). These are ordinary Java {@code char} or code point values -
 *       nothing about them is a control character or a surrogate - but no XML 1.0 parser can read
 *       one, so a buyer who puts one in their own name gets a document nothing downstream can open,
 *       discovered only after a legal number has already been consumed for it.
 * </ul>
 *
 * <p>What it does repair, deliberately: Unicode normalisation to NFC, which is a canonical form
 * question rather than a content change, applied before any length or collision check so two
 * spellings of one string cannot disagree about either.
 */
public final class ScreenedText {

  private ScreenedText() {}

  // D1-06. `\s` in Java is ASCII whitespace only: a value made entirely of U+00A0 (NO-BREAK SPACE),
  // U+2007 (FIGURE SPACE) or U+200B (ZERO WIDTH SPACE) is not blank to that class and is blank to
  // every reader of a report and, later, of a document - precisely the defect the collapse step
  // exists to catch. This collapses every Unicode space separator (category Zs) and the invisible
  // format characters used to fake blankness, alongside ordinary ASCII whitespace.
  private static final java.util.regex.Pattern BLANK_RENDERING =
      java.util.regex.Pattern.compile("[\\s\\p{Zs}\\u200B\\u200C\\u200D\\u2060\\uFEFF]+");

  // Bidirectional overrides and isolates: refused outright wherever they appear in a screened
  // value, never merely collapsed, because they change how every character around them displays
  // rather than rendering as visible content themselves.
  private static final java.util.Set<Character> BIDI_CONTROLS =
      java.util.Set.of(
          (char) 0x202A,
          (char) 0x202B,
          (char) 0x202C,
          (char) 0x202D,
          (char) 0x202E,
          (char) 0x2066,
          (char) 0x2067,
          (char) 0x2068,
          (char) 0x2069);

  /**
   * @param field the field's name, for the message - never its value (checklist line 45)
   * @param maxChars the per-field bound, in characters after normalisation
   */
  public static String screen(String field, String value, int maxChars) {
    if (value == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, field + " must not be null");
    }
    String nfc = Normalizer.normalize(value, Normalizer.Form.NFC);
    for (int i = 0; i < nfc.length(); i++) {
      char c = nfc.charAt(i);
      if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
        throw new EInvoiceException(
            ErrorCodes.INVALID,
            field
                + " contains a control character at index "
                + i
                + " (U+"
                + String.format(Locale.ROOT, "%04X", (int) c)
                + ")");
      }
      if (c == 0x7F) {
        throw new EInvoiceException(
            ErrorCodes.INVALID, field + " contains a DELETE control character at index " + i);
      }
      if (BIDI_CONTROLS.contains(c)) {
        throw new EInvoiceException(
            ErrorCodes.INVALID,
            field
                + " contains a bidirectional override or isolate character at index "
                + i
                + " (U+"
                + String.format(Locale.ROOT, "%04X", (int) c)
                + ")");
      }
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= nfc.length() || !Character.isLowSurrogate(nfc.charAt(i + 1))) {
          throw new EInvoiceException(
              ErrorCodes.INVALID, field + " contains an unpaired surrogate at index " + i);
        }
        int codePoint = Character.toCodePoint(c, nfc.charAt(i + 1));
        if (isXmlNonCharacter(codePoint)) {
          throw new EInvoiceException(
              ErrorCodes.INVALID,
              field
                  + " contains an XML non-character at index "
                  + i
                  + " (U+"
                  + String.format(Locale.ROOT, "%06X", codePoint)
                  + ")");
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        throw new EInvoiceException(
            ErrorCodes.INVALID, field + " contains an unpaired surrogate at index " + i);
      } else if (isXmlNonCharacter(c)) {
        throw new EInvoiceException(
            ErrorCodes.INVALID,
            field
                + " contains an XML non-character at index "
                + i
                + " (U+"
                + String.format(Locale.ROOT, "%04X", (int) c)
                + ")");
      }
    }
    // Collapse-then-check, not collapse-then-use: the stored value keeps its own spacing, but a
    // value whose whitespace collapses to nothing is refused rather than written as a blank field.
    String collapsed = BLANK_RENDERING.matcher(nfc).replaceAll(" ").strip();
    if (collapsed.isEmpty()) {
      throw new EInvoiceException(ErrorCodes.INVALID, field + " must not be blank");
    }
    if (nfc.length() > maxChars) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          field + " is " + nfc.length() + " characters, max " + maxChars + " for this field");
    }
    return nfc;
  }

  // D3-01: the 66 code points XML 1.0 declares are not characters at all - U+FFFE/U+FFFF at the
  // end of every plane (0x0 through 0x10), plus the 32-point block U+FDD0..U+FDEF reserved inside
  // the BMP. (codePoint & 0xFFFE) == 0xFFFE is exactly "the last two code points of some plane".
  private static boolean isXmlNonCharacter(int codePoint) {
    return (codePoint & 0xFFFE) == 0xFFFE || (codePoint >= 0xFDD0 && codePoint <= 0xFDEF);
  }

  /** The collapsed form, for comparing two identifiers that must not collide (checklist line 8). */
  public static String collapsed(String value) {
    return BLANK_RENDERING
        .matcher(Normalizer.normalize(value, Normalizer.Form.NFC))
        .replaceAll(" ")
        .strip();
  }
}
