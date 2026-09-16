package com.housedevinci.einvoice.domain;

import java.util.Set;

/**
 * How far one received Stripe event got, as a plain enum with an explicit transition set. No Spring
 * State Machine, no sealed hierarchy.
 *
 * <p><b>Two state machines, on purpose.</b> This one belongs to the inbound event row and covers
 * the steps that happen <em>before</em> a number exists: received, fetched from the authoritative
 * API, mapped. {@link IssuanceState} belongs to the issuance row and starts at {@code NUMBERED}.
 * Merging them would put states in a column that nothing ever writes there, which is the defect
 * design review I-05 objected to.
 *
 * <p><b>A signature-valid refusal is a state here, not a 400</b> (I-01). An event whose API version
 * is off the pin, whose {@code livemode} disagrees with this application's mode, or whose account
 * resolves to no seller, is recorded with its reason and answered 200: retrying cannot help it, and
 * a 400 that repeats would let Stripe disable the endpoint and take every <em>good</em> event with
 * it. Those rows keep their body and are replayed through the same path once the pin is updated.
 *
 * <p><b>Retryable versus final</b> (I-08) is declared here, per state, and never decided at a call
 * site: a fetch or an archive failure can improve with time, a mapping or a totals refusal cannot.
 */
public enum InboundState {

  /** Signature verified, body durably recorded, nothing decided yet. */
  RECEIVED,

  /** The invoice was re-fetched from the Stripe API, paginated to exhaustion. */
  FETCHED,

  /** The invoice mapped, and our totals agree with Stripe's. A number may now be allocated. */
  MAPPED,

  /** A prerequisite is not there yet - the invoice is not finalised. Retried, never dropped. */
  PARKED,

  /** The issuance reached {@code ISSUED}. Terminal, and the only success. */
  COMPLETED,

  /** Recorded, then dropped: an event type this module does not act on, or a void with no issue. */
  DROPPED,

  /** {@code api_version} is not the pinned one. Replayable once the pin is updated. */
  REFUSED_VERSION_SKEW,

  /** A test-mode event in a live application, or the reverse (D-01). */
  REFUSED_MODE,

  /** The event's account resolves to no configured seller. Never a fallback to a default. */
  REFUSED_ACCOUNT,

  /** Stripe was unreachable. Retryable up to the retry ceiling, then a compliance finding. */
  FAILED_FETCH,

  /** A field a document requires is absent, or a value will not go on one. Needs a human. */
  FAILED_MAPPING,

  /** Our recomputation disagrees with Stripe's own scalars. Needs a human (D-12). */
  FAILED_TOTALS,

  /** The issuance row carries the failure - validation or archive. Retryability lives there. */
  FAILED_ISSUANCE;

  /** The set this state may move to. A move to the same state is always allowed and is a no-op. */
  public Set<InboundState> allowedNext() {
    return switch (this) {
      case RECEIVED, REFUSED_VERSION_SKEW, REFUSED_MODE, REFUSED_ACCOUNT ->
          Set.of(
              FETCHED,
              PARKED,
              DROPPED,
              COMPLETED,
              REFUSED_VERSION_SKEW,
              REFUSED_MODE,
              REFUSED_ACCOUNT,
              FAILED_FETCH);
      case FETCHED ->
          Set.of(
              MAPPED, PARKED, DROPPED, COMPLETED, FAILED_MAPPING, FAILED_TOTALS, FAILED_ISSUANCE);
      case MAPPED -> Set.of(COMPLETED, FAILED_ISSUANCE, FETCHED);
      case PARKED, FAILED_FETCH -> Set.of(FETCHED, PARKED, DROPPED, FAILED_FETCH);
      case FAILED_ISSUANCE -> Set.of(FETCHED, MAPPED, COMPLETED, FAILED_ISSUANCE);
      case COMPLETED, DROPPED, FAILED_MAPPING, FAILED_TOTALS -> Set.of();
    };
  }

  /** True when no further work happens on this row unless a sweeper or a replay re-picks it. */
  public boolean terminal() {
    return this != RECEIVED && this != FETCHED && this != MAPPED && this != PARKED;
  }

  /** True when the routing decision refused a signature-valid event (I-01). */
  public boolean refused() {
    return this == REFUSED_VERSION_SKEW || this == REFUSED_MODE || this == REFUSED_ACCOUNT;
  }

  /** True when a sweeper may re-pick this terminal; false when it needs a human (I-08). */
  public boolean retryableTerminal() {
    return this == FAILED_FETCH || this == FAILED_ISSUANCE;
  }

  /**
   * True while the raw signed body must be kept (I-07).
   *
   * <p>The body is the largest store of buyer data in this module - a full invoice payload with
   * name, address, email, tax id and line descriptions - so it lives exactly as long as it can
   * still be of use: while the event can run again, be retried by the sweeper, or be replayed after
   * a pin update. At every other terminal it is nulled and only the event id, type, object id,
   * seller, mode, signature key id, arrival time and a SHA-256 of the body remain.
   */
  public boolean keepsBody() {
    return !terminal() || retryableTerminal() || refused();
  }

  public InboundState transitionTo(InboundState next) {
    if (next == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "next inbound state must not be null");
    }
    if (next == this) {
      return this;
    }
    if (!allowedNext().contains(next)) {
      throw new EInvoiceException(
          ErrorCodes.INBOUND_ILLEGAL_TRANSITION,
          "an inbound event in state " + name() + " cannot move to " + next.name());
    }
    return next;
  }

  public static InboundState of(String value) {
    for (InboundState state : values()) {
      if (state.name().equals(value)) {
        return state;
      }
    }
    throw new EInvoiceException(
        ErrorCodes.INVALID, "unknown inbound state in the state column: " + sanitise(value));
  }

  /** Only the shape of an unknown value is reported, never the value itself. */
  private static String sanitise(String value) {
    return value == null ? "null" : "<" + value.length() + " characters>";
  }
}
