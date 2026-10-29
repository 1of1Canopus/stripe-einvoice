package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * One answer of {@link ArchiveStore#listAfter}: keys in UTF-8 byte order, and whether the store
 * said there are no more.
 *
 * <p><b>The end of the folder is a value the store gives, never a count</b> (D-PR52-3, C5). S3 may
 * answer with fewer keys than asked and still have more; a host store may page as it likes. So a
 * short page is not the end. Example: a request for 2,000 keys returns 1,000 with {@code
 * IsTruncated=true}; that is not the end of the folder, and the orphan walk must not stamp a cycle
 * complete on it.
 *
 * @param keys the keys, strictly ascending in UTF-8 byte order, each strictly after the requested
 *     start; never null
 * @param endOfFolder true only when the store has said there is no key after the last one
 */
public record ArchiveListing(List<String> keys, boolean endOfFolder) {

  /**
   * The longest key a store may list, in UTF-8 bytes: the bound of {@link ArchiveStore#listAfter}
   * and of the orphan cursor's saved position. S3 keys stop at 1,024 bytes. A filesystem store cuts
   * a longer name and marks it, so a name a writer chooses never reaches this bound (D-PR57-3).
   */
  public static final int MAX_KEY_BYTES = 4096;

  /**
   * UTF-8 byte order, unsigned: the order S3 lists in, and the order every store of this module
   * sorts and filters with. Java's {@code String.compareTo} compares UTF-16 units and disagrees
   * with it on characters outside the basic plane. Example: U+FF61 sorts after U+1F600 in UTF-16
   * units (0xFF61 against 0xD83D) and before it in UTF-8 bytes (0xEF against 0xF0).
   */
  public static final Comparator<String> UTF8_ORDER =
      (a, b) -> Arrays.compareUnsigned(utf8(a), utf8(b));

  public ArchiveListing {
    if (keys == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an archive listing needs a key list");
    }
    keys = List.copyOf(keys);
  }

  /** The UTF-8 bytes of a key, the form the order and the saved position use. */
  public static byte[] utf8(String key) {
    return key.getBytes(StandardCharsets.UTF_8);
  }
}
