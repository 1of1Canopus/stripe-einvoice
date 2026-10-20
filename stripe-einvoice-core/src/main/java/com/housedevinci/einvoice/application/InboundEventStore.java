package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
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
   */
  boolean expire(InboundEvent stranded, ComplianceFinding finding, Instant now);

  /** Rows that never reached a terminal state and are older than the retention ceiling (I-07). */
  int purgeOlderThan(Instant cutoff, int limit);

  /** Counts per state, for the compliance findings list and the metrics. Never a body. */
  List<StateCount> countsByState();

  record StateCount(InboundState state, long count, Instant oldest) {}
}
