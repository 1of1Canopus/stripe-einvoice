package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.InboundEvent;
import java.util.Optional;
import java.util.function.Function;

/**
 * At most one run of one Stripe invoice is in progress at any time (D-PR45-1, design
 * one-run-per-event).
 *
 * <p>A "run" is one call of {@link IssuanceUnitOfWork#process(String)}, from its first decision to
 * its last write. Without this lock the sweeper can re-pick a row that a worker is still running,
 * and two runs then render one number at once; the slower one writes over the faster one.
 *
 * <p>The contract:
 *
 * <ul>
 *   <li><b>Keyed on the invoice.</b> The key comes from the stored row's {@code object_id} only,
 *       never from the instance's settings (D-ORPE-5). Two events of one invoice ({@code
 *       invoice.finalized} and {@code invoice.paid}), and two instances of different modes on one
 *       database, contend on one key.
 *   <li><b>Try only.</b> {@link #whileHeld} never waits. Held by another run: it returns empty and
 *       the caller writes nothing. No path ever waits on this lock, so it joins no wait cycle.
 *   <li><b>Writes under proof.</b> Every inbound write of the run goes through {@link
 *       HeldRun#inbound()}, which proves, in the same transaction, that this run still holds the
 *       lock. A run that lost it is refused with {@code DEI-104} and writes nothing.
 *   <li><b>Acquire failures keep their code.</b> A pool timeout or an outage while taking the lock
 *       is {@code DEI-102} or {@code DEI-103}, never "busy" (D-ORPE-7a). A connection path that
 *       does not keep one server session is {@code DEI-104}.
 *   <li><b>Released after the run.</b> When {@code work} returns or throws. A release that fails
 *       closes the connection instead of pooling it.
 * </ul>
 */
public interface InboundRunLock {

  /**
   * The inbound port bound to the lock holder's own connection, and the proof of the lock.
   *
   * @param inbound every write through it proves the lock first, in its own transaction
   * @param proof re-checks the lock on the holder's connection, for the steps that act on the
   *     number outside the inbound row
   */
  record HeldRun(InboundEventStore inbound, Proof proof) {}

  /** Re-checks, on the holder's connection, that this run still holds the lock. */
  @FunctionalInterface
  interface Proof {
    /**
     * @throws com.housedevinci.einvoice.domain.EInvoiceException {@code DEI-104} when the lock is
     *     lost: the server or the network ended the holder's session
     */
    void assertHeld();
  }

  /**
   * Runs {@code work} while holding the run lock of the invoice {@code storedRow} names.
   *
   * @param storedRow the row as read from the store; its {@code object_id} is the key
   * @return empty when another run holds the lock; otherwise what {@code work} returned
   */
  <T> Optional<T> whileHeld(InboundEvent storedRow, Function<HeldRun, T> work);
}
