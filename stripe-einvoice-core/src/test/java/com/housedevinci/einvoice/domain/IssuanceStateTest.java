package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The state machine, on its own, with no Spring and no database. */
class IssuanceStateTest {

  @Test
  void an_issued_number_has_nowhere_left_to_go() {
    // The whole point of the enum: an issued number names a legal document that exists, and
    // nothing - not a void, not a retry, not an operator - unpublishes it.
    assertThat(IssuanceState.ISSUED.allowedNext()).isEmpty();
    assertThatThrownBy(() -> IssuanceState.ISSUED.transitionTo(IssuanceState.VOID_UNUSED))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
  }

  @Test
  void a_void_is_reachable_only_from_an_open_or_failed_allocation() {
    assertThat(IssuanceState.NUMBERED.allowedNext()).contains(IssuanceState.VOID_UNUSED);
    assertThat(IssuanceState.ARCHIVING.allowedNext()).contains(IssuanceState.VOID_UNUSED);
    assertThat(IssuanceState.FAILED_VALIDATION.allowedNext())
        .containsExactly(IssuanceState.VOID_UNUSED);
    assertThat(IssuanceState.VOID_UNUSED.allowedNext()).isEmpty();
  }

  @Test
  void which_terminals_a_sweeper_may_retry_is_declared_per_state_not_at_a_call_site() {
    // I-08. An archive failure can improve with time; a validation failure cannot, and retrying it
    // is noise that hides the human decision it needs.
    assertThat(IssuanceState.FAILED_ARCHIVE.retryableTerminal()).isTrue();
    assertThat(IssuanceState.FAILED_VALIDATION.retryableTerminal()).isFalse();
    assertThat(IssuanceState.FAILED_ARCHIVE.allowedNext()).contains(IssuanceState.ARCHIVING);
  }

  @Test
  void open_and_disposed_partition_every_state() {
    for (IssuanceState state : IssuanceState.values()) {
      assertThat(state.open() && state.disposed()).isFalse();
    }
    assertThat(IssuanceState.ISSUED.disposed()).isTrue();
    assertThat(IssuanceState.VOID_UNUSED.disposed()).isTrue();
    assertThat(IssuanceState.NUMBERED.open()).isTrue();
  }

  @Test
  void an_unknown_state_column_is_refused_without_echoing_what_it_held() {
    // A state column that does not decode was written by exactly the actor the ledger exists to
    // detect, and its content is not something to copy into a log line.
    assertThatThrownBy(() -> IssuanceState.of("DROP TABLE einvoice_issuance"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("characters")
        .hasMessageNotContaining("DROP");
  }
}
