package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.util.concurrent.Semaphore;

/**
 * The bound on live runs, shared by every caller of {@link IssuanceUnitOfWork#process(String)}
 * (D-ORPE-4).
 *
 * <p>A live run holds a pool connection for its whole length (the run lock), so the number of live
 * runs must be bounded for the pool rule to mean anything. The worker's threads are one caller; a
 * host thread calling {@link IssuanceReprocess} is another, and it is not bounded by the worker.
 * Both take a permit here before they take any connection.
 *
 * <ul>
 *   <li>A worker thread <b>waits</b> for a permit. It holds no connection while it waits, so no
 *       wait cycle can form.
 *   <li>A host-thread call <b>never waits</b>: no permit, and it refuses at once with {@code
 *       DEI-102} ("no database capacity now, retry later"), takes no connection and records
 *       nothing.
 * </ul>
 *
 * <p>Example: two permits, two worker runs live, an operator clicks "reprocess" ten times. All ten
 * clicks refuse with {@code DEI-102}; the pool still holds at most two run-lock connections.
 */
public final class RunPermits {

  private final int permits;
  private final Semaphore semaphore;

  /**
   * @param permits {@code einvoice.issuance.concurrency}; at least one
   */
  public RunPermits(int permits) {
    if (permits < 1) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG, "einvoice.issuance.concurrency must be at least 1");
    }
    this.permits = permits;
    this.semaphore = new Semaphore(permits, true);
  }

  /** The bound, for the pool rule and the startup log. */
  public int permits() {
    return permits;
  }

  /** Free permits now, for tests and metrics. */
  public int available() {
    return semaphore.availablePermits();
  }

  /**
   * A worker thread's permit: waits. An interrupt while waiting (a shutdown) is {@code DEI-102}
   * with the interrupt flag restored; nothing was taken and nothing written.
   */
  Permit acquire() {
    try {
      semaphore.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EInvoiceException(
          ErrorCodes.STORE_UNAVAILABLE, "interrupted while waiting for an issuance run permit");
    }
    return new Permit(semaphore);
  }

  /** A host thread's permit: never waits, {@code DEI-102} when none is free. */
  Permit tryAcquire() {
    if (!semaphore.tryAcquire()) {
      throw new EInvoiceException(
          ErrorCodes.STORE_UNAVAILABLE,
          "every issuance run permit (einvoice.issuance.concurrency) is in use; nothing was"
              + " recorded. Call again later.");
    }
    return new Permit(semaphore);
  }

  /** One taken permit, released once by {@link #close()}. */
  static final class Permit implements AutoCloseable {
    private final Semaphore semaphore;
    private boolean released;

    private Permit(Semaphore semaphore) {
      this.semaphore = semaphore;
    }

    @Override
    public void close() {
      if (!released) {
        released = true;
        semaphore.release();
      }
    }
  }
}
