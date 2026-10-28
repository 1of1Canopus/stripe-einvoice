package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Identifiers;
import com.housedevinci.einvoice.domain.Mode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where the orphan walk of one seller and mode stands (D-PR52-3): the last key it examined, and the
 * cycle it is in.
 *
 * <p>A <i>cycle</i> is one walk of the whole seller folder, from the empty position to the end of
 * the folder. A cycle starts at the empty position, and only a cycle that started there, stayed on
 * one store and ended on the store's own end-of-folder answer is complete. Every change moves the
 * revision by one, and the store replaces a cursor only from the revision it read: two instances
 * never both move the walk from one position.
 *
 * @param position the last key examined, empty at the start of a cycle
 * @param storeIdentity the SHA-256 of the store's {@link ArchiveStore#describe()} line: a cursor
 *     belongs to one store, root, bucket and prefix, and a change starts a new cycle
 * @param cycleStartedAt when the current cycle left the empty position
 * @param lastCompleteCycleAt when the last complete cycle ended, if any
 * @param lastCycleKeys how many keys the last complete cycle examined, 0 when none completed
 * @param keysThisCycle how many keys the current cycle has examined so far
 * @param revision moved by one on every change; the compare-and-set value
 */
public record OrphanCursor(
    String sellerId,
    Mode mode,
    String position,
    String storeIdentity,
    Instant cycleStartedAt,
    Optional<Instant> lastCompleteCycleAt,
    long lastCycleKeys,
    long keysThisCycle,
    long revision) {

  /**
   * The longest position, in UTF-8 bytes. S3 keys reach 1,024 bytes and filesystem paths 4,096
   * (Linux {@code PATH_MAX}), so this holds a key of either shipped store; the column has the same
   * bound.
   */
  public static final int MAX_POSITION_BYTES = 4096;

  public OrphanCursor {
    Identifiers.validate("seller id", sellerId, 64);
    if (mode == null
        || position == null
        || storeIdentity == null
        || cycleStartedAt == null
        || lastCompleteCycleAt == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an orphan cursor is missing a part");
    }
    if (ArchiveListing.utf8(position).length > MAX_POSITION_BYTES) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "an orphan cursor position is at most " + MAX_POSITION_BYTES + " UTF-8 bytes");
    }
    if (lastCycleKeys < 0 || keysThisCycle < 0 || revision < 0) {
      throw new EInvoiceException(ErrorCodes.INVALID, "an orphan cursor count is never negative");
    }
  }

  /** The first cursor of a seller and mode: the empty position, a cycle starting now. */
  public static OrphanCursor start(String sellerId, Mode mode, String storeIdentity, Instant now) {
    return new OrphanCursor(sellerId, mode, "", storeIdentity, now, Optional.empty(), 0, 0, 0);
  }

  /** After one page: the last key of the page, and the page's keys counted. */
  public OrphanCursor advancedTo(String lastKey, int examined) {
    return new OrphanCursor(
        sellerId,
        mode,
        lastKey,
        storeIdentity,
        cycleStartedAt,
        lastCompleteCycleAt,
        lastCycleKeys,
        keysThisCycle + examined,
        revision + 1);
  }

  /** The end of the folder on the same store: the cycle is complete, the next one starts now. */
  public OrphanCursor completed(Instant now) {
    return new OrphanCursor(
        sellerId, mode, "", storeIdentity, now, Optional.of(now), keysThisCycle, 0, revision + 1);
  }

  /**
   * Another store, root, bucket or prefix: the partial cycle is dropped, never completed, and a new
   * one starts now at the empty position (D-PR52-3, C9).
   */
  public OrphanCursor restartedOn(String newStoreIdentity, Instant now) {
    return new OrphanCursor(
        sellerId,
        mode,
        "",
        newStoreIdentity,
        now,
        lastCompleteCycleAt,
        lastCycleKeys,
        0,
        revision + 1);
  }

  /**
   * True when the current cycle started more than {@code alert} ago and no complete cycle has ended
   * since it started: {@code DEI-283}.
   */
  public boolean stale(Instant now, Duration alert) {
    boolean completedSinceStart =
        lastCompleteCycleAt.map(done -> !done.isBefore(cycleStartedAt)).orElse(false);
    return Duration.between(cycleStartedAt, now).compareTo(alert) > 0 && !completedSinceStart;
  }

  /**
   * How far the current cycle is, against the last complete cycle's count; empty in the first
   * cycle, which has nothing to measure against. Capped at 99 until the cycle really ends.
   */
  public OptionalInt percentDone() {
    if (lastCycleKeys == 0) {
      return OptionalInt.empty();
    }
    return OptionalInt.of((int) Math.min(99, keysThisCycle * 100 / lastCycleKeys));
  }
}
