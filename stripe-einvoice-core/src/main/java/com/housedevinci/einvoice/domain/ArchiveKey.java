package com.housedevinci.einvoice.domain;

import java.util.Locale;

/**
 * The archive key of one document: {@code <mode>/<seller>/<fiscal year>/<number>-<sha256>.<ext>}.
 *
 * <p><b>Content-addressed, so a retry is not a second document.</b> The key carries the SHA-256 of
 * the exact bytes, and the bytes are deterministic for one input (D-15), so a retry after a crash
 * computes the same key and writes the same object. Combined with a write-once store, "one number,
 * two documents" stops being a race anyone can win.
 *
 * <p>The mode is the first element rather than a suffix: a test-mode document and a live document
 * for the same seller then cannot share a prefix, so an operator listing the live root sees only
 * live documents and a misconfigured test run cannot bury one among them (D-01).
 */
public record ArchiveKey(String value) {

  /** Bounded to what the column holds; the parts are all ours, so this is a guard, not a limit. */
  public static final int MAX_CHARS = 512;

  public ArchiveKey {
    if (value == null || value.isBlank()) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an archive key must not be blank");
    }
    if (value.length() > MAX_CHARS) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "an archive key is at most " + MAX_CHARS + " characters");
    }
    // Every element of a key is ours - mode, seller id, fiscal year, legal number, content hash -
    // so none of this can be triggered by a buyer's string today. It is checked anyway, because a
    // store resolves a key against a filesystem path or a bucket namespace, and "no caller can
    // reach this" is a property of today's callers.
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_'
              || c == '.'
              || c == '/';
      if (!ok) {
        throw new EInvoiceException(
            ErrorCodes.INVALID,
            "an archive key carries an unexpected character at index "
                + i
                + " (U+"
                + Integer.toHexString(c)
                + ")");
      }
    }
    if (value.startsWith("/") || value.contains("//") || value.contains("..")) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "an archive key is relative, has no empty element and never traverses upwards");
    }
  }

  /** The key for one issued document. The extension comes from the renderer, not from a caller. */
  public static ArchiveKey of(
      SeriesKey seriesKey, LegalNumber number, String documentSha256, String extension) {
    Hashes.requireSha256Hex("document hash", documentSha256);
    String year = seriesKey.continuous() ? "continuous" : String.valueOf(seriesKey.fiscalYear());
    return new ArchiveKey(
        seriesKey.mode().wire()
            + "/"
            + seriesKey.sellerId()
            + "/"
            + year
            + "/"
            + number.value()
            + "-"
            + documentSha256.toLowerCase(Locale.ROOT)
            + "."
            + Identifiers.validate("document extension", extension, 8));
  }

  @Override
  public String toString() {
    return value;
  }
}
