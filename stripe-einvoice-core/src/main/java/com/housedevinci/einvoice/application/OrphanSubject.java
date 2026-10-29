package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.Hashes;
import java.util.Locale;

/**
 * The finding subject of a listed name that is not a valid archive key (D-PR52-3, C6 and D4).
 *
 * <p>Such a name can be anything a writer of the volume or the bucket chose: 1,024 bytes, control
 * characters, characters outside the basic plane. The finding table holds 512 characters, and a
 * finding that cannot be written would stop the walk at that name for ever. So the subject is
 * bounded: the name escaped, cut at {@value #PRINTABLE_CHARS} characters, then {@code #sha256:} and
 * the hash of the full name's UTF-8 bytes, which tells two long names apart.
 *
 * <p>Escape first, then cut, never inside an escape. Cut first and then escape, and 200 surrogate
 * halves become 1,200 characters. Example: a key of 256 four-byte characters gives a subject of 200
 * characters of {@code \}{@code uXXXX} escapes plus 72 of hash, 272 in all.
 */
public final class OrphanSubject {

  /** How much of the escaped name the subject keeps. */
  public static final int PRINTABLE_CHARS = 200;

  private static final String HASH_MARK = "#sha256:";

  private OrphanSubject() {}

  /** The subject for a listed name: the name itself when it is a valid key, else bounded. */
  public static String of(String listedName) {
    if (ArchiveKey.isValid(listedName)) {
      return listedName;
    }
    StringBuilder subject = new StringBuilder();
    for (int i = 0; i < listedName.length(); i++) {
      char c = listedName.charAt(i);
      String token =
          c >= 0x20 && c <= 0x7e
              ? String.valueOf(c)
              : String.format(Locale.ROOT, "\\u%04x", (int) c);
      if (subject.length() + token.length() > PRINTABLE_CHARS) {
        break;
      }
      subject.append(token);
    }
    return subject
        .append(HASH_MARK)
        .append(Hashes.sha256Hex(ArchiveListing.utf8(listedName)))
        .toString();
  }
}
