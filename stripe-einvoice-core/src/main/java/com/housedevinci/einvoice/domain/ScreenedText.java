package com.housedevinci.einvoice.domain;

import java.text.Normalizer;

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
 * </ul>
 *
 * <p>What it does repair, deliberately: Unicode normalisation to NFC, which is a canonical form
 * question rather than a content change, applied before any length or collision check so two
 * spellings of one string cannot disagree about either.
 */
public final class ScreenedText {

  private ScreenedText() {}

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
                + String.format("%04X", (int) c)
                + ")");
      }
      if (c == 0x7F) {
        throw new EInvoiceException(
            ErrorCodes.INVALID, field + " contains a DELETE control character at index " + i);
      }
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= nfc.length() || !Character.isLowSurrogate(nfc.charAt(i + 1))) {
          throw new EInvoiceException(
              ErrorCodes.INVALID, field + " contains an unpaired surrogate at index " + i);
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        throw new EInvoiceException(
            ErrorCodes.INVALID, field + " contains an unpaired surrogate at index " + i);
      }
    }
    // Collapse-then-check, not collapse-then-use: the stored value keeps its own spacing, but a
    // value whose whitespace collapses to nothing is refused rather than written as a blank field.
    String collapsed = nfc.replaceAll("\\s+", " ").strip();
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

  /** The collapsed form, for comparing two identifiers that must not collide (checklist line 8). */
  public static String collapsed(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip();
  }
}
