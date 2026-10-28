package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.Mode;
import java.util.Optional;

/**
 * The saved position of the orphan walk, one per seller and mode (D-PR52-3).
 *
 * <p>The walk reads the cursor, lists one bounded page after it, records what it found, and only
 * then moves the cursor, with a compare-and-set on the revision it read. A lost compare-and-set is
 * not a failure: another instance moved the walk first, and this one stops for the pass.
 *
 * <p><b>Threat model, stated.</b> The runtime role can write this table, so it can move the cursor
 * and skip keys. That is refused by design (L25): the same role can insert an issuance row naming a
 * stray key, which hides it more simply. The orphan check is against a writer of the volume or the
 * bucket who has no database access, and that writer cannot touch the cursor.
 */
public interface OrphanCursorStore {

  /** The cursor of this seller and mode; empty before the first pass. */
  Optional<OrphanCursor> read(String sellerId, Mode mode);

  /**
   * Inserts the first cursor of a seller and mode.
   *
   * @return false when another instance inserted one first: a lost compare-and-set
   */
  boolean insert(OrphanCursor cursor);

  /**
   * Replaces {@code expected} with {@code next} only if the stored revision is still {@code
   * expected.revision()}.
   *
   * @return false when the stored cursor moved since it was read: a lost compare-and-set
   */
  boolean replace(OrphanCursor expected, OrphanCursor next);

  /**
   * True when an issued row of this seller and mode recorded an archive key. A cycle that lists
   * nothing while this is true is an empty or unmounted archive, never a complete cycle (D-PR52-3,
   * C8).
   */
  boolean hasArchivedDocuments(String sellerId, Mode mode);
}
