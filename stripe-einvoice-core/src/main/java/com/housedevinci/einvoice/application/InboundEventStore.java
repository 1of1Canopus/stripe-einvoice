package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
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
   * Events the sweeper should pick up: anything not terminal, and any retryable terminal whose
   * next-attempt time has come and which is still inside the retry ceiling (I-08, I-10).
   */
  List<InboundEvent> due(Instant now, Duration retryCeiling, int limit);

  /** Rows that never reached a terminal state and are older than the retention ceiling (I-07). */
  int purgeOlderThan(Instant cutoff, int limit);

  /** Counts per state, for the compliance findings list and the metrics. Never a body. */
  List<StateCount> countsByState();

  record StateCount(InboundState state, long count, Instant oldest) {}
}
