package com.housedevinci.einvoice.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * How long an inbound row may stay in a running state before it stops being due and is reported
 * (QUESTIONS 29).
 *
 * <p><b>The example it exists for.</b> A Stripe invoice stays a draft for ever. Its event is {@code
 * PARKED}; every backoff the sweeper re-fetches it, finds a draft and parks it again. Without a
 * limit that goes on until the retention purge deletes the row, and nobody is ever told. With it,
 * the row is moved to {@link InboundState#EXPIRED} once, with a finding on its event id, and an
 * operator's reprocess starts it again.
 *
 * <p><b>What the limit measures.</b> Time since the row last made progress ({@code due_since}),
 * never time since arrival: a draft finalised after two months is normal, and its next steps must
 * not inherit the two months. {@link #restartsClock} says which moves are progress.
 *
 * @param running the limit for {@code RECEIVED}, {@code FETCHED} and {@code MAPPED}: steps that
 *     complete in seconds when nothing is wrong
 * @param parked the limit for {@code PARKED}, and for the {@code FETCHED} hop of a row that was
 *     ever parked; never shorter than {@code running}
 */
public record RunningLimit(Duration running, Duration parked) {

  /** {@code einvoice.issuance.running-ceiling} = PT72H, {@code parked-ceiling} = P90D. */
  public static final RunningLimit DEFAULT =
      new RunningLimit(Duration.ofHours(72), Duration.ofDays(90));

  public RunningLimit {
    requirePositive("einvoice.issuance.running-ceiling", running);
    requirePositive("einvoice.issuance.parked-ceiling", parked);
    if (parked.compareTo(running) < 0) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.issuance.parked-ceiling ("
              + parked
              + ") is shorter than einvoice.issuance.running-ceiling ("
              + running
              + "). A parked invoice is the long-lived case; it cannot have the shorter limit.");
    }
  }

  /**
   * Refuses a running limit that a row's own scheduled retry cannot fit inside (F-5): the row would
   * be reported as stuck by a configuration that stopped it.
   */
  public void requireLongerThan(Duration maxRetryBackoff) {
    if (maxRetryBackoff != null && running.compareTo(maxRetryBackoff) <= 0) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.issuance.running-ceiling ("
              + running
              + ") must be longer than einvoice.issuance.max-retry-backoff ("
              + maxRetryBackoff
              + "), or a row expires before its own next retry is due.");
    }
  }

  public Instant runningCutoff(Instant now) {
    return now.minus(running);
  }

  public Instant parkedCutoff(Instant now) {
    return now.minus(parked);
  }

  /**
   * True when moving a row from {@code from} to {@code to} starts its clock again.
   *
   * <p>A move into a running state is progress, with two exceptions. A self-edge is not. And the
   * park re-check is not: a draft is re-checked as {@code PARKED -> FETCHED -> PARKED}, and through
   * {@code FAILED_FETCH} when Stripe is briefly unreachable, so restarting the clock on those moves
   * would mean the parked limit never fires. A move into a terminal state leaves the clock where it
   * was; only a running state reads it.
   *
   * @param everParked whether the row was ever parked ({@code parked_at} is set)
   */
  public static boolean restartsClock(InboundState from, InboundState to, boolean everParked) {
    if (from == to || to.terminal()) {
      return false;
    }
    return !(everParked && parkReCheck(from, to));
  }

  /** True when the parked limit, not the running one, applies to a row in {@code state}. */
  public static boolean parkedLimitGoverns(InboundState state, boolean everParked) {
    return state == InboundState.PARKED || (state == InboundState.FETCHED && everParked);
  }

  private static boolean parkReCheck(InboundState from, InboundState to) {
    return switch (from) {
      case PARKED, FAILED_FETCH -> to == InboundState.FETCHED || to == InboundState.PARKED;
      case FETCHED -> to == InboundState.PARKED;
      default -> false;
    };
  }

  private static void requirePositive(String property, Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          property + " must be a positive duration; zero or negative is never read as no limit");
    }
  }
}
