package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.RunningLimit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Forwards every call; a test overrides the one it races or breaks. */
class DelegatingInboundEventStore implements InboundEventStore {

  private final InboundEventStore delegate;

  DelegatingInboundEventStore(InboundEventStore delegate) {
    this.delegate = delegate;
  }

  @Override
  public boolean record(InboundEvent event, byte[] body) {
    return delegate.record(event, body);
  }

  @Override
  public Optional<InboundEvent> find(String eventId) {
    return delegate.find(eventId);
  }

  @Override
  public Optional<byte[]> body(String eventId) {
    return delegate.body(eventId);
  }

  @Override
  public InboundEvent transition(
      String eventId,
      InboundState next,
      String code,
      String ruleId,
      Instant now,
      Duration retryIn) {
    return delegate.transition(eventId, next, code, ruleId, now, retryIn);
  }

  @Override
  public Optional<InboundEvent> transitionFrom(
      String eventId,
      InboundState expected,
      InboundState next,
      String code,
      String ruleId,
      Instant now,
      Duration retryIn) {
    return delegate.transitionFrom(eventId, expected, next, code, ruleId, now, retryIn);
  }

  @Override
  public Optional<InboundEvent> stampAttempt(
      String eventId, InboundState expected, Instant now, Duration retryIn) {
    return delegate.stampAttempt(eventId, expected, now, retryIn);
  }

  @Override
  public Optional<String> lastRuleId(String eventId) {
    return delegate.lastRuleId(eventId);
  }

  @Override
  public InboundEvent bindSeller(String eventId, String sellerId, Instant now) {
    return delegate.bindSeller(eventId, sellerId, now);
  }

  @Override
  public List<InboundEvent> due(
      Instant now, Duration retryCeiling, RunningLimit runningLimit, int limit, String pin) {
    return delegate.due(now, retryCeiling, runningLimit, limit, pin);
  }

  @Override
  public List<InboundEvent> stranded(Instant now, RunningLimit runningLimit, int limit) {
    return delegate.stranded(now, runningLimit, limit);
  }

  @Override
  public boolean expire(InboundEvent stranded, ComplianceFinding finding, Instant now) {
    return delegate.expire(stranded, finding, now);
  }

  @Override
  public int purgeOlderThan(Instant cutoff, int limit) {
    return delegate.purgeOlderThan(cutoff, limit);
  }

  @Override
  public int rearmAccessRefused(boolean livemode, String stripeAccountId, Instant now) {
    return delegate.rearmAccessRefused(livemode, stripeAccountId, now);
  }

  @Override
  public List<String> accessRefused(
      String sellerId, com.housedevinci.einvoice.domain.Mode mode, int limit) {
    return delegate.accessRefused(sellerId, mode, limit);
  }

  @Override
  public List<StateCount> countsByState() {
    return delegate.countsByState();
  }
}
