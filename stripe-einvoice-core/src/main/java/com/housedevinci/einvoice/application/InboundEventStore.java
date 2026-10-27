package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.RunningLimit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The durable record of every signature-valid event, written <b>before</b> any routing decision and
 * before the endpoint answers (D-09, I-01).
 *
 * <p>Stripe's own redelivery is a backstop, never the retry mechanism: it stops after three days,
 * and by then an outage that lasted four has cost the seller a legal document with nothing to show
 * it ever existed.
 */
public interface InboundEventStore {

  /**
   * Records one verified event with its raw body.
   *
   * @return false when this event id was already recorded - a duplicate delivery, which is a no-op
   *     and still answered 200
   */
  boolean record(InboundEvent event, byte[] body);

  Optional<InboundEvent> find(String eventId);

  /** The raw body while the row still carries it; empty once a terminal state nulled it (I-07). */
  Optional<byte[]> body(String eventId);

  /**
   * Moves one event to its next state, recording the stable error code that took it there.
   *
   * <p>The raw body is nulled here, by the state machine's own rule, and not by a caller that has
   * to remember: {@link InboundState#keepsBody()} decides.
   *
   * @param retryIn when the sweeper may re-pick a retryable terminal; null for "not again"
   */
  default InboundEvent transition(
      String eventId, InboundState next, String code, Instant now, Duration retryIn) {
    return transition(eventId, next, code, "", now, retryIn);
  }

  /**
   * @param ruleId the failing validation rule, recorded beside the state so the operator's later
   *     void can name it rather than infer it from prose
   */
  InboundEvent transition(
      String eventId, InboundState next, String code, String ruleId, Instant now, Duration retryIn);

  /**
   * Moves one event only if it is still in the state the caller last saw (D-PR9-F1).
   *
   * <p>The compare and the write are one locked statement. Two workers can run one event at once (a
   * worker and the sweeper, or a re-open and a retry). A run whose view is stale must not write
   * over what the other run concluded, and must not move the row back. Example: worker B read
   * {@code RECEIVED} and waited in the Stripe fetch; worker A reached {@code COMPLETED}; B's {@code
   * FETCHED} write expects {@code RECEIVED} and returns empty.
   *
   * <p>No default implementation: one built from {@link #find} and {@link #transition} would not be
   * atomic, and that weaker form must not pass silently.
   *
   * <p>Isolation (D-PR45-2): at REPEATABLE READ or SERIALIZABLE the locking read of a row that
   * another run moved raises SQLState {@code 40001}. When the store owns the connection, the
   * bundled implementation runs the move once more in a fresh transaction and so answers empty (or
   * lands). When the transaction is the host's, the failure passes through unchanged ({@code
   * DEI-102}): that transaction is already aborted and the host retries it.
   *
   * @param expected the state this run last read or wrote
   * @return the moved row, or empty when the row no longer holds {@code expected} and nothing was
   *     written
   * @throws com.housedevinci.einvoice.domain.EInvoiceException {@code DEI-210} when the row holds
   *     {@code expected} and {@code next} is not an edge from it: the caller's own bug, not a race
   */
  Optional<InboundEvent> transitionFrom(
      String eventId,
      InboundState expected,
      InboundState next,
      String code,
      String ruleId,
      Instant now,
      Duration retryIn);

  /**
   * Stamps the start of one run (D-PR45-1): {@code attempts + 1} and {@code next_attempt_at = now +
   * retryIn}, with no state change, only if the row still holds {@code expected}.
   *
   * <p>One run is one attempt (D-ORPE-3). The stamp is the only place a run moves {@code attempts};
   * the conclusions a run lock holder writes add nothing. The stamp matters only when the run dies:
   * every conclusion overwrites {@code next_attempt_at}, and a dead run's row is not due again
   * before the stamped time. So a renderer that kills the JVM on one invoice costs one backoff per
   * crash, not one crash per sweep tick.
   *
   * <p>No default implementation: a stamp built from {@link #find} and {@link #transition} would
   * move the state machine, and a store that cannot stamp must not pass silently.
   *
   * @return the stamped row, or empty when the row no longer holds {@code expected} and nothing was
   *     written
   */
  Optional<InboundEvent> stampAttempt(
      String eventId, InboundState expected, Instant now, Duration retryIn);

  /** The failing validation rule recorded with the last transition, when there was one. */
  Optional<String> lastRuleId(String eventId);

  /** Binds the resolved seller to the row once routing has resolved it from the account (D-01). */
  InboundEvent bindSeller(String eventId, String sellerId, Instant now);

  /**
   * Events the sweeper should pick up: a running row whose next attempt has come and which is still
   * inside its time limit ({@link RunningLimit}, QUESTIONS 29), any retryable terminal whose
   * next-attempt time has come and which is still inside the retry ceiling (I-08, I-10), and a
   * {@code REFUSED_VERSION_SKEW} row whose recorded {@code api_version} now equals {@code
   * pinnedApiVersion} - a configuration change has cured exactly that row, and none of the ones
   * still refused (D2-03). {@code REFUSED_MODE} and {@code REFUSED_ACCOUNT} are never re-picked
   * here: an upgrade does not cure either.
   *
   * <p>The running branch is the exact complement of {@link #stranded} for the same {@code now} and
   * the same limits: a running row whose next attempt has come is in one of the two, never in both
   * and never in neither.
   */
  List<InboundEvent> due(
      Instant now,
      Duration retryCeiling,
      RunningLimit runningLimit,
      int limit,
      String pinnedApiVersion);

  /**
   * Running rows past their time limit, oldest clock first (QUESTIONS 29). A row 0.1.0 wrote during
   * the upgrade, with no clock yet, is never in it.
   */
  List<InboundEvent> stranded(Instant now, RunningLimit runningLimit, int limit);

  /**
   * Moves one stranded row to {@code EXPIRED} with {@code DEI-266} and records {@code finding}, in
   * one transaction. Conditional on the row still holding the state and the clock it was read with:
   * a row that moved in between is not touched, nothing is recorded, and the answer is false - a
   * skip, never an exception (F-6). A finding acknowledged for an earlier expiry of the same event
   * is opened again: a second expiry is news.
   *
   * <p>A row whose invoice has a live run is never expired (one-run-per-event design, row 16): the
   * write first tries the invoice's run lock in its own transaction, and a held lock is a skip with
   * the answer false, nothing written, nothing recorded. Example: a run holds {@code
   * INV-2026-000001} and waits 73 hours on the archive; every sweep in that time skips its {@code
   * MAPPED} row; the run then writes {@code COMPLETED}. An implementation outside this module must
   * take the same lock, or an expiry can report a stuck row for a document that was issued.
   */
  boolean expire(InboundEvent stranded, ComplianceFinding finding, Instant now);

  /**
   * Rows older than the retention ceiling, any state (I-07), except a row whose Stripe invoice
   * holds an open number, numbered, archiving or failed archive (D-SRE-7, D-PR50-1): the reprocess
   * needs that row to resume the number, so it stays until the number resolves and leaves at the
   * next purge after.
   */
  int purgeOlderThan(Instant cutoff, int limit);

  /**
   * Makes due now every row stopped on a refused Stripe key ({@code DEI-219}, state {@code
   * FAILED_FETCH} or {@code FAILED_ISSUANCE}, no next attempt) whose recorded {@code livemode} and
   * Stripe account are the given ones (D-SRE-3). Touches {@code next_attempt_at} only, and only
   * where it is null, so a row a live run has stamped is never moved.
   *
   * <p>No default implementation: a store that cannot re-arm would leave the rows stopped for ever.
   *
   * @return how many rows were made due
   */
  int rearmAccessRefused(boolean livemode, String stripeAccountId, Instant now);

  /**
   * The event ids of one seller's rows stopped on a refused Stripe key and not re-armed, oldest
   * first, for the reconciliation pass's finding (D-SRE-5a). No default implementation.
   */
  List<String> accessRefused(String sellerId, Mode mode, int limit);

  /** Counts per state, for the compliance findings list and the metrics. Never a body. */
  List<StateCount> countsByState();

  record StateCount(InboundState state, long count, Instant oldest) {}
}
