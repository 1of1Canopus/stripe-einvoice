package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which of this module's two locks the current transaction already holds.
 *
 * <p><b>The invariant, restated (T-02b).</b> It used to be "no transaction holds both locks", which
 * was true by accident: every call opened its own transaction, the series counter row lock was
 * taken by the allocation and the chain's advisory lock by the disposition, and nothing could hold
 * both. Once an allocation can join a host's transaction, a host that wraps an allocation and a
 * disposition in one {@code @Transactional} holds both - and that is a correct program, not an
 * error. Refusing it would turn correct host code into a runtime failure found in production.
 *
 * <p>So the enforced property is the one that actually prevents a deadlock: <b>nothing takes the
 * chain lock before the series counter's row lock.</b> Series row then chain is allowed and logged
 * at DEBUG, because holding the series row lock while the chain lock is contended lengthens the
 * head-of-line blocking of that whole series. Chain then series is refused with a typed error.
 *
 * <p>The ledger is scoped to the <em>transaction</em>, not to an object: in joined mode the starter
 * binds it as a Spring transaction resource, and in the two self-contained implementations the
 * connection is the transaction, so the unit of work holds it.
 */
public interface LockLedger {

  /** Records that this transaction is about to take the series counter's row lock. */
  void seriesRowLock();

  /** Records that this transaction is about to take the chain's advisory lock. */
  void chainLock();

  /**
   * Records that this transaction executed a statement that was not a read (D1-12). Scoped with the
   * rest of the ledger - the same transaction, the same suspend/resume lifecycle (D1-11) - rather
   * than tracked per call: a refusal raised by one call must still poison the caller's commit when
   * an <em>earlier</em> call in the same transaction already wrote, exactly the "dispose, then a
   * refused allocate" shape a host is free to write across two of this module's methods in one
   * transaction.
   */
  void markWritten();

  /** True once {@link #markWritten()} has been called for this transaction. */
  boolean written();

  /** A ledger for one transaction, held by whoever owns that transaction's scope. */
  static LockLedger forOneTransaction() {
    return new Scoped();
  }

  /** The default implementation; the scope is whatever object holds this instance. */
  final class Scoped implements LockLedger {

    private static final Logger log = LoggerFactory.getLogger(LockLedger.class);

    private boolean series;
    private boolean chain;
    private boolean written;

    @Override
    public void seriesRowLock() {
      if (chain) {
        throw new EInvoiceException(
            ErrorCodes.LOCK_ORDER_VIOLATION,
            "this transaction already holds the issuance chain's advisory lock and is now asking"
                + " for the series counter's row lock. Nothing in this module takes the chain lock"
                + " before the series row lock: two transactions doing it in opposite orders"
                + " deadlock, and a deadlocked allocation is a stalled invoice. Allocate first,"
                + " then dispose, or use two transactions.");
      }
      series = true;
    }

    @Override
    public void chainLock() {
      if (series) {
        log.debug(
            "einvoice: this transaction holds the series counter row lock and is taking the chain"
                + " lock; both are held until the caller commits, which blocks every other"
                + " allocation on that series for that long");
      }
      chain = true;
    }

    @Override
    public void markWritten() {
      written = true;
    }

    @Override
    public boolean written() {
      return written;
    }
  }
}
