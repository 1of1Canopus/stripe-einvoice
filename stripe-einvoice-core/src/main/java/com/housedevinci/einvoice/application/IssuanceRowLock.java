package com.housedevinci.einvoice.application;

import java.util.Optional;
import java.util.function.Function;

/**
 * The lock one issuance row is held under from just before its ARCHIVING marker until its outcome
 * has committed (D-RTR-7).
 *
 * <p>Why it exists. Two workers can run the same invoice at once - {@code invoice.finalized} and
 * {@code invoice.paid} arrive together, or two renderer versions are live during a rolling deploy.
 * Worker A writes the marker and starts its archive write. Without this lock, worker B resumes the
 * row, reads the key before A's object lands, finds nothing, renders other bytes, is refused and
 * burns the number; A's document then lands in the archive next to a ledger that calls the number
 * unused.
 *
 * <p>The contract:
 *
 * <ul>
 *   <li><b>Try only.</b> {@link #whileHeld} never waits. Held by someone else: it returns empty,
 *       and the caller retries later having read, rendered and written nothing.
 *   <li><b>Decide under the lock.</b> The ports handed to {@code work} read and write on the lock
 *       holder's own connection, and the caller re-reads the row through them before deciding.
 *   <li><b>One connection per holder.</b> {@code work} must not need a second database connection
 *       while it holds the lock: every issuance read and write it makes goes through the ports it
 *       is given, so a pool of size P serves P concurrent holders.
 *   <li><b>Released after the outcome.</b> When {@code work} returns or throws, the lock is
 *       released; a release that fails closes the connection rather than pooling it.
 *   <li><b>Fail closed.</b> A connection path that does not keep one server session (a pooler in
 *       transaction mode) is refused with {@code DEI-104}; this never runs unlocked.
 * </ul>
 */
public interface IssuanceRowLock {

  /** The issuance ports bound to the lock holder's connection. */
  record Held(IssuanceReader reader, IssuanceWriter writer) {}

  /**
   * Runs {@code work} while holding the lock of issuance row {@code issuanceId}.
   *
   * @return empty when another worker holds the lock; otherwise what {@code work} returned
   * @throws com.housedevinci.einvoice.domain.EInvoiceException {@code DEI-104} when the connection
   *     path does not keep one session, {@code DEI-102} when the store is unreachable
   */
  <T> Optional<T> whileHeld(long issuanceId, Function<Held, T> work);

  /**
   * Refuses, with {@code DEI-104}, a connection path that does not keep one server session. The
   * unit of work calls it before a number is allocated, so a refused deployment consumes none.
   */
  void verifySessionPinning();
}
