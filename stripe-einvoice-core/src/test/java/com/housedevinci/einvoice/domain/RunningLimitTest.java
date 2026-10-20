package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * QUESTIONS 29, the domain half: the {@code EXPIRED} terminal, the two limits and the rule that
 * says when a row's clock starts again. No Spring, no database.
 */
class RunningLimitTest {

  private static final Set<InboundState> RUNNING =
      EnumSet.of(
          InboundState.RECEIVED, InboundState.FETCHED, InboundState.MAPPED, InboundState.PARKED);

  // ---- probe 4: EXPIRED is a reported terminal, never a silent one -------------------------

  @Test
  void expired_is_terminal_drops_the_body_and_has_no_way_out() {
    assertThat(InboundState.EXPIRED.terminal()).isTrue();
    assertThat(InboundState.EXPIRED.retryableTerminal()).isFalse();
    assertThat(InboundState.EXPIRED.refused()).isFalse();
    assertThat(InboundState.EXPIRED.keepsBody()).isFalse();
    assertThat(InboundState.EXPIRED.allowedNext()).isEmpty();
  }

  @Test
  void only_the_four_running_states_may_expire() {
    for (InboundState state : InboundState.values()) {
      assertThat(state.allowedNext().contains(InboundState.EXPIRED))
          .as("%s -> EXPIRED", state)
          .isEqualTo(RUNNING.contains(state));
    }
  }

  @Test
  void a_refused_row_cannot_be_expired_even_though_it_shares_the_received_edges() {
    // The refused states share RECEIVED's successor set in the switch. EXPIRED must not ride
    // along: a refused row is not running and is not the time limit's business.
    assertThatThrownBy(() -> InboundState.REFUSED_MODE.transitionTo(InboundState.EXPIRED))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_ILLEGAL_TRANSITION);
  }

  // ---- probe 9 / 14: the limits refuse a value that means "no limit" or "too short" --------

  @Test
  void the_defaults_are_seventy_two_hours_and_ninety_days() {
    assertThat(RunningLimit.DEFAULT.running()).isEqualTo(Duration.ofHours(72));
    assertThat(RunningLimit.DEFAULT.parked()).isEqualTo(Duration.ofDays(90));
  }

  @Test
  void a_zero_or_negative_limit_is_refused_never_read_as_no_limit() {
    for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
      assertThatThrownBy(() -> new RunningLimit(bad, Duration.ofDays(90)))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.CONFIG);
      assertThatThrownBy(() -> new RunningLimit(Duration.ofHours(72), bad))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.CONFIG);
    }
    assertThatThrownBy(() -> new RunningLimit(null, Duration.ofDays(90)))
        .isInstanceOf(EInvoiceException.class);
  }

  @Test
  void a_parked_limit_below_the_running_limit_is_refused() {
    assertThatThrownBy(() -> new RunningLimit(Duration.ofHours(72), Duration.ofHours(71)))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("parked-ceiling");
    assertThat(new RunningLimit(Duration.ofHours(72), Duration.ofHours(72)).parked())
        .isEqualTo(Duration.ofHours(72));
  }

  @Test
  void a_running_limit_not_above_the_longest_retry_backoff_is_refused() {
    RunningLimit limit = new RunningLimit(Duration.ofHours(1), Duration.ofDays(90));
    assertThatThrownBy(() -> limit.requireLongerThan(Duration.ofHours(1)))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("running-ceiling")
        .hasMessageContaining("max-retry-backoff");
    limit.requireLongerThan(Duration.ofMinutes(59));
  }

  @Test
  void the_cutoffs_are_now_minus_each_limit() {
    Instant now = Instant.parse("2026-10-20T10:00:00Z");
    assertThat(RunningLimit.DEFAULT.runningCutoff(now)).isEqualTo(now.minus(Duration.ofHours(72)));
    assertThat(RunningLimit.DEFAULT.parkedCutoff(now)).isEqualTo(now.minus(Duration.ofDays(90)));
  }

  // ---- the clock rule (F-1, and the park re-check) -----------------------------------------

  @Test
  void a_self_edge_never_restarts_the_clock() {
    for (InboundState state : InboundState.values()) {
      assertThat(RunningLimit.restartsClock(state, state, false)).as("%s", state).isFalse();
      assertThat(RunningLimit.restartsClock(state, state, true)).as("%s", state).isFalse();
    }
  }

  @Test
  void forward_progress_restarts_the_clock() {
    // F-1: a parked invoice finalised on day 80 moves PARKED -> FETCHED -> MAPPED. Each of those
    // moves is progress and must not inherit the 80 days.
    assertThat(RunningLimit.restartsClock(InboundState.RECEIVED, InboundState.FETCHED, false))
        .isTrue();
    assertThat(RunningLimit.restartsClock(InboundState.FETCHED, InboundState.MAPPED, true))
        .isTrue();
    assertThat(RunningLimit.restartsClock(InboundState.FAILED_ISSUANCE, InboundState.MAPPED, true))
        .isTrue();
    assertThat(
            RunningLimit.restartsClock(InboundState.FAILED_ISSUANCE, InboundState.FETCHED, false))
        .isTrue();
    assertThat(
            RunningLimit.restartsClock(
                InboundState.REFUSED_VERSION_SKEW, InboundState.FETCHED, false))
        .isTrue();
  }

  @Test
  void the_park_re_check_keeps_the_clock_of_the_park() {
    // A re-check of a draft goes PARKED -> FETCHED -> PARKED on every backoff, and through
    // FAILED_FETCH when Stripe blinks. Restarting the clock there means the parked limit never
    // fires for the one row it exists for: a draft nobody will ever finalise.
    assertThat(RunningLimit.restartsClock(InboundState.PARKED, InboundState.FETCHED, true))
        .isFalse();
    assertThat(RunningLimit.restartsClock(InboundState.FETCHED, InboundState.PARKED, true))
        .isFalse();
    assertThat(RunningLimit.restartsClock(InboundState.PARKED, InboundState.FAILED_FETCH, true))
        .isFalse();
    assertThat(RunningLimit.restartsClock(InboundState.FAILED_FETCH, InboundState.FETCHED, true))
        .isFalse();
    assertThat(RunningLimit.restartsClock(InboundState.FAILED_FETCH, InboundState.PARKED, true))
        .isFalse();
  }

  @Test
  void a_conclusion_does_not_restart_the_clock() {
    // Only a running state reads the clock; writing a terminal leaves it where it was so the row
    // carries the instant it was last running.
    for (InboundState to : InboundState.values()) {
      if (to.terminal()) {
        assertThat(RunningLimit.restartsClock(InboundState.MAPPED, to, false))
            .as("MAPPED -> %s", to)
            .isFalse();
      }
    }
  }

  @Test
  void a_parked_row_and_a_fetched_row_that_was_ever_parked_are_governed_by_the_parked_limit() {
    // The FETCHED hop of a park re-check carries the park's clock, so it must be read against the
    // park's limit; otherwise the sweep can expire a live re-check in the middle of its run.
    assertThat(RunningLimit.parkedLimitGoverns(InboundState.PARKED, false)).isTrue();
    assertThat(RunningLimit.parkedLimitGoverns(InboundState.FETCHED, true)).isTrue();
    assertThat(RunningLimit.parkedLimitGoverns(InboundState.FETCHED, false)).isFalse();
    assertThat(RunningLimit.parkedLimitGoverns(InboundState.RECEIVED, true)).isFalse();
    assertThat(RunningLimit.parkedLimitGoverns(InboundState.MAPPED, true)).isFalse();
  }
}
