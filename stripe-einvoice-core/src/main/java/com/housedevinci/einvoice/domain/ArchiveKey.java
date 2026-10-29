package com.housedevinci.einvoice.domain;

import java.util.Locale;
import java.util.Optional;

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
    Optional<String> problem = problem(value);
    if (problem.isPresent()) {
      throw new EInvoiceException(ErrorCodes.INVALID, problem.get());
    }
  }

  /**
   * True when {@code value} is a key this module could have written. The orphan walk asks this of
   * every listed name: a name that is not a valid key cannot be recorded by any row, so it is an
   * orphan without a database lookup (D-PR52-3, C6).
   */
  public static boolean isValid(String value) {
    return problem(value).isEmpty();
  }

  /**
   * The folder that holds every key of one seller in one mode, with its trailing slash: {@code
   * live/acme/}. The slash is the seller boundary (D-PR51-5): without it, {@code live/acme} also
   * names every key of seller {@code acme2}. The parts go through the same checks as a key, so a
   * seller id {@code .} is refused here as it is in a key (D-PR52-3, C4).
   */
  public static String sellerFolder(Mode mode, String sellerId) {
    if (mode == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "a seller folder needs a mode");
    }
    String folder = mode.wire() + "/" + Identifiers.validate("seller id", sellerId, 64);
    Optional<String> problem = problem(folder);
    if (problem.isPresent()) {
      throw new EInvoiceException(ErrorCodes.INVALID, problem.get());
    }
    return folder + "/";
  }

  private static Optional<String> problem(String value) {
    if (value == null || value.isBlank()) {
      return Optional.of("an archive key must not be blank");
    }
    if (value.length() > MAX_CHARS) {
      return Optional.of("an archive key is at most " + MAX_CHARS + " characters");
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
        return Optional.of(
            "an archive key carries an unexpected character at index "
                + i
                + " (U+"
                + Integer.toHexString(c)
                + ")");
      }
    }
    if (value.startsWith("/") || value.contains("//") || value.contains("..")) {
      return Optional.of(
          "an archive key is relative, has no empty element and never traverses upwards");
    }
    // An element "." names its parent folder: a filesystem normalises "live/./2026" to
    // "live/2026", so seller "." would list every seller of the mode (D-PR52-3, C4).
    for (String element : value.split("/", -1)) {
      if (element.equals(".")) {
        return Optional.of("an archive key has no element equal to \".\"");
      }
    }
    return Optional.empty();
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
