package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The intake state machine, on its own: no Spring, no database, no Stripe. */
class InboundStateTest {

  @Test
  void a_signature_valid_refusal_is_a_terminal_state_on_the_row_not_a_lost_event() {
    // I-01. A version skew, a mode mismatch and an unknown account are recorded, answered 200 and
    // replayable; none of them is a 400 and none of them is a log line only.
    for (InboundState refused :
        new InboundState[] {
          InboundState.REFUSED_VERSION_SKEW, InboundState.REFUSED_MODE, InboundState.REFUSED_ACCOUNT
        }) {
      assertThat(refused.terminal()).isTrue();
      assertThat(refused.keepsBody()).isTrue();
      assertThat(refused.allowedNext()).contains(InboundState.FETCHED);
    }
  }

  @Test
  void which_terminals_the_sweeper_may_retry_is_declared_per_state_not_at_a_call_site() {
    // I-08. A fetch failure and an archive failure can improve with time; a mapping or a totals
    // refusal cannot, and retrying them is noise that hides the human decision they need.
    assertThat(InboundState.FAILED_FETCH.retryableTerminal()).isTrue();
    assertThat(InboundState.FAILED_ISSUANCE.retryableTerminal()).isTrue();
    assertThat(InboundState.FAILED_MAPPING.retryableTerminal()).isFalse();
    assertThat(InboundState.FAILED_TOTALS.retryableTerminal()).isFalse();
    assertThat(InboundState.COMPLETED.retryableTerminal()).isFalse();
  }

  @Test
  void only_a_state_that_can_never_run_again_gives_up_its_raw_body() {
    // I-07. The raw body is the largest PII store in the module, so it is nulled the moment the
    // event can no longer be retried or replayed - and kept exactly while it can.
    for (InboundState state : InboundState.values()) {
      boolean canRunAgain = !state.terminal() || state.retryableTerminal() || state.refused();
      assertThat(state.keepsBody()).isEqualTo(canRunAgain);
    }
    assertThat(InboundState.COMPLETED.keepsBody()).isFalse();
    assertThat(InboundState.FAILED_MAPPING.keepsBody()).isFalse();
    assertThat(InboundState.DROPPED.keepsBody()).isFalse();
  }

  @Test
  void a_completed_event_has_nowhere_left_to_go() {
    assertThat(InboundState.COMPLETED.allowedNext()).isEmpty();
    assertThatThrownBy(() -> InboundState.COMPLETED.transitionTo(InboundState.FETCHED))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_ILLEGAL_TRANSITION);
  }

  @Test
  void re_running_an_event_that_is_already_where_it_is_is_not_an_error() {
    // The worker and the sweeper can both reach the same conclusion about one row; the second one
    // to write it must not fail, or a redelivery becomes an incident.
    assertThat(InboundState.RECEIVED.transitionTo(InboundState.RECEIVED))
        .isEqualTo(InboundState.RECEIVED);
    assertThat(InboundState.REFUSED_MODE.transitionTo(InboundState.REFUSED_MODE))
        .isEqualTo(InboundState.REFUSED_MODE);
  }

  @Test
  void a_parked_event_is_retried_and_never_dropped() {
    assertThat(InboundState.PARKED.terminal()).isFalse();
    assertThat(InboundState.PARKED.allowedNext()).contains(InboundState.FETCHED);
  }

  @Test
  void an_unknown_state_column_is_refused_without_echoing_what_it_held() {
    assertThatThrownBy(() -> InboundState.of("'; DROP TABLE einvoice_inbound_event"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("characters")
        .hasMessageNotContaining("DROP");
  }

  @Test
  void every_state_is_either_running_or_terminal_and_never_both() {
    for (InboundState state : InboundState.values()) {
      assertThat(state.terminal() && !state.allowedNext().isEmpty() && !state.refused())
          .as("%s", state)
          .isEqualTo(state.retryableTerminal());
    }
  }
}
