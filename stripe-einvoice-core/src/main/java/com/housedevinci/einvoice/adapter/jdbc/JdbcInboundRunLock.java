package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.application.InboundRunLock;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import javax.sql.DataSource;

/**
 * The inbound run lock in PostgreSQL (D-PR45-1): a session advisory lock per Stripe invoice, built
 * on the PR 43 token proof, held on one connection for the whole run.
 *
 * <p>Key: class {@link AdvisoryLocks#RUN_CLASS_ID}, and the first four bytes of SHA-256 of the
 * stored row's object id. No mode and nothing from the instance's settings (D-ORPE-5). The token
 * lives in its own session setting, {@code einvoice.run_lock}, never the row lock's (D-ORPE-7c).
 *
 * <p>One connection path: <b>own connection</b> (the worker, the sweeper, a host call outside a
 * transaction). R5's session check runs first, with no connection held, so a transaction pooler is
 * refused with {@code DEI-104} before anything is written. Every held write is its own transaction
 * on the holder's connection, proof first. A release that fails ends the session and aborts the
 * connection, so a pooled connection never carries a lock.
 *
 * <p><b>Never joined</b> (D-PR49-1, QUESTIONS 50). A run on a thread with a caller's transaction is
 * refused with {@link ErrorCodes#RUN_IN_HOST_TRANSACTION} before the lock is tried. A joined run
 * whose write failed would abort the caller's transaction before its release, so the lock would
 * stay on the caller's pooled connection and the invoice would answer "busy" with no run live.
 */
public final class JdbcInboundRunLock implements InboundRunLock {

  private final JdbcUnitOfWork unitOfWork;
  private final DataSource dataSource;
  private final ToIntFunction<String> key;

  /**
   * @param unitOfWork the unit of work the module's inbound store uses
   * @param dataSource the data source behind it, for R5's session check
   */
  public JdbcInboundRunLock(JdbcUnitOfWork unitOfWork, DataSource dataSource) {
    this(unitOfWork, dataSource, JdbcInboundRunLock::runKey);
  }

  /** Test seam for a forced key collision (T11). Package-private. */
  JdbcInboundRunLock(JdbcUnitOfWork unitOfWork, DataSource dataSource, ToIntFunction<String> key) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.key = Objects.requireNonNull(key, "key");
  }

  /**
   * The second key: the first four bytes of SHA-256 of the object id, big-endian. Computed in Java,
   * because {@code hashtext} is not promised stable across PostgreSQL versions.
   */
  public static int runKey(String objectId) {
    if (objectId == null || objectId.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "an inbound event without an object id has no run lock key");
    }
    byte[] digest;
    try {
      digest =
          MessageDigest.getInstance("SHA-256").digest(objectId.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
    return ((digest[0] & 0xFF) << 24)
        | ((digest[1] & 0xFF) << 16)
        | ((digest[2] & 0xFF) << 8)
        | (digest[3] & 0xFF);
  }

  IssuanceRowLocks.SessionLock lockFor(InboundEvent storedRow) {
    return new IssuanceRowLocks.SessionLock(
        AdvisoryLocks.RUN_CLASS_ID,
        key.applyAsInt(storedRow.objectId()),
        IssuanceRowLocks.RUN_TOKEN_SETTING,
        "run");
  }

  @Override
  public <T> Optional<T> whileHeld(InboundEvent storedRow, Function<HeldRun, T> work) {
    Objects.requireNonNull(storedRow, "storedRow");
    Objects.requireNonNull(work, "work");
    IssuanceRowLocks.SessionLock lock = lockFor(storedRow);
    String eventId = storedRow.eventId();
    Optional<Optional<T>> done = attempt(lock, eventId, work);
    if (done.isEmpty()) {
      // The first own-connection holder on this data source: R5's check needs two free
      // connections, so it runs here with none held, then the lock is tried again.
      JdbcIssuanceStore.verifySessionPinning(dataSource);
      done = attempt(lock, eventId, work);
    }
    return done.orElseThrow(
        () ->
            new EInvoiceException(
                ErrorCodes.SESSION_NOT_PINNED, "the session check did not settle for this pool"));
  }

  private <T> Optional<Optional<T>> attempt(
      IssuanceRowLocks.SessionLock lock, String eventId, Function<HeldRun, T> work) {
    return unitOfWork.onOneConnection(
        (connection, bound, joined) -> {
          if (joined) {
            // D-PR49-1: refused before the TRY, so nothing is locked, read or written, and the
            // caller's transaction sees no statement of ours.
            throw new EInvoiceException(
                ErrorCodes.RUN_IN_HOST_TRANSACTION,
                "inbound event "
                    + eventId
                    + " was not run: a run never joins a caller's transaction; call it outside"
                    + " one");
          }
          if (!JdbcIssuanceStore.sessionPinned(dataSource)) {
            return Optional.empty();
          }
          return Optional.of(heldOwn(connection, lock, eventId, work));
        });
  }

  private <T> Optional<T> heldOwn(
      Connection connection,
      IssuanceRowLocks.SessionLock lock,
      String eventId,
      Function<HeldRun, T> work)
      throws SQLException {
    Object autosave = IssuanceRowLocks.autosaveNever(connection);
    try {
      Optional<String> token = IssuanceRowLocks.tryLock(connection, lock, false);
      if (token.isEmpty()) {
        return Optional.empty();
      }
      HeldConnectionUnitOfWork held =
          new HeldConnectionUnitOfWork(connection, lock, token.get(), () -> lost(eventId));
      Proof proof =
          () -> {
            boolean holds;
            try {
              holds = IssuanceRowLocks.prove(connection, lock, token.get());
              if (!connection.getAutoCommit()) {
                connection.commit();
              }
            } catch (SQLException | RuntimeException e) {
              holds = false;
            }
            if (!holds) {
              throw lost(eventId);
            }
          };
      boolean released = false;
      try {
        T result;
        try {
          result = work.apply(new HeldRun(JdbcInboundEventStore.heldBy(held), proof));
        } catch (RuntimeException failure) {
          throw classified(failure, connection, lock, token.get(), eventId);
        }
        released = IssuanceRowLocks.unlock(connection, lock, token.get(), false);
        if (!released) {
          throw lost(eventId);
        }
        return Optional.of(result);
      } finally {
        if (!released && !IssuanceRowLocks.unlock(connection, lock, token.get(), false)) {
          // D-ORPE-2: the session that held the lock is not ours any more, or the release failed.
          // The connection goes, so no pool hands out a session that may still carry a lock.
          IssuanceRowLocks.terminate(connection);
        }
      }
    } finally {
      IssuanceRowLocks.restoreAutosave(connection, autosave);
    }
  }

  /**
   * D-ORPE-2: a failure out of the run whose session no longer holds the lock is a lost lock. A
   * killed session first fails the next statement on its connection as an outage ({@code DEI-102});
   * the proof then tells the two apart. A failure while the lock still holds keeps its own code.
   */
  private static RuntimeException classified(
      RuntimeException failure,
      Connection connection,
      IssuanceRowLocks.SessionLock lock,
      String token,
      String eventId) {
    if (failure instanceof EInvoiceException typed
        && ErrorCodes.SESSION_NOT_PINNED.equals(typed.code())) {
      return failure;
    }
    boolean holds;
    try {
      holds = IssuanceRowLocks.prove(connection, lock, token);
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
    } catch (SQLException | RuntimeException e) {
      holds = false;
    }
    if (holds) {
      return failure;
    }
    EInvoiceException lost = lost(eventId);
    lost.addSuppressed(failure);
    return lost;
  }

  /** D-ORPE-2: a lost lock is DEI-104, never the busy code. Names the event, never the token. */
  static EInvoiceException lost(String eventId) {
    return new EInvoiceException(
        ErrorCodes.SESSION_NOT_PINNED,
        "the run lock of inbound event "
            + eventId
            + " is no longer held by this run on its database session; this run writes nothing"
            + " more");
  }
}
