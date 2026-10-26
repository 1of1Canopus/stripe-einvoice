package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 45 pass 1 (D-PR45-B1, QUESTIONS 45). No race. A row a crashed run left
 * MAPPED, or a FAILED_ISSUANCE row with a retry due, is re-run while Stripe cannot be read. The
 * FAILED_FETCH write is not an edge from either state: DEI-210 escapes process, nothing is
 * recorded, no backoff is set. The MAPPED row stays due at once (next_attempt_at NULL, no retry
 * ceiling for a running state), so every sweep tick calls Stripe again and logs DEI-210 for as long
 * as the fetch fails - without end if the failure is permanent (a revoked restricted key).
 *
 * <p>Install under stripe-einvoice-core/src/test/java/com/housedevinci/einvoice/application/.
 *
 * <p>Build of the sweeper retry edges design (Revision 2): the probes went in unchanged and were
 * red, 3 of 3. Their escape assertions stand as written. One outcome assertion of the first probe
 * counted Stripe calls over three direct {@code process} calls; a direct call is not filtered by
 * the due query (design section 6, the reprocess path relies on it), so each one fetches. What the
 * probe meant, "every sweep tick calls Stripe again", is now asserted on the sweep's own input:
 * during the backoff the row is not due, so a tick does not run it. The per-sweep call count is the
 * design's own test, {@code
 * StripeAccessRefusedTest.with_a_revoked_key_three_sweeps_call_stripe_once_per_row}.
 */
class CipherProbePr45B1Test {

  @Test
  void probe_a_crashed_mapped_row_rerun_while_stripe_is_down_loops_on_dei_210() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pr45_b1_mapped"));
    String eventId = harness.receive("invoice.finalized", "in_pr45_b1_mapped");
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);
    harness.inbound().transition(eventId, InboundState.MAPPED, "", harness.now(), null);
    harness
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "stripe is down"));

    int before = harness.source().fetches();
    for (int tick = 0; tick < 3; tick++) {
      assertThatCode(() -> harness.unitOfWork().process(eventId))
          .as("a Stripe outage on a re-run is recorded, never DEI-210")
          .doesNotThrowAnyException();
    }
    InboundEvent row = harness.inbound().find(eventId).orElseThrow();
    assertThat(row.attempts()).as("each failed attempt is counted").isGreaterThan(0);
    assertThat(row.nextAttempt()).as("a backoff is scheduled").isPresent();
    assertThat(harness.source().fetches() - before).as("one fetch per direct call").isEqualTo(3);
    assertThat(harness.due())
        .extracting(InboundEvent::eventId)
        .as("inside its backoff the row is not due, so a sweep tick does not call Stripe")
        .doesNotContain(eventId);
  }

  @Test
  void probe_a_failed_issuance_retry_while_stripe_is_down_escapes_dei_210() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pr45_b1_failed_issuance"));
    String eventId = harness.receive("invoice.finalized", "in_pr45_b1_failed_issuance");
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);
    harness.inbound().transition(eventId, InboundState.MAPPED, "", harness.now(), null);
    harness
        .inbound()
        .transition(
            eventId,
            InboundState.FAILED_ISSUANCE,
            ErrorCodes.STRIPE_UNAVAILABLE,
            harness.now(),
            Duration.ofMinutes(1));
    harness
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "stripe is down"));

    assertThatCode(() -> harness.unitOfWork().process(eventId))
        .as("a Stripe outage on a retry is recorded, never DEI-210")
        .doesNotThrowAnyException();
  }

  /** The same class on the route refusals: a PARKED row re-run after the host moved its pin. */
  @Test
  void probe_a_parked_row_rerun_after_a_pin_change_escapes_dei_210() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pr45_b1_parked"));
    String eventId = harness.receive("invoice.finalized", "in_pr45_b1_parked");
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);
    harness
        .inbound()
        .transition(
            eventId,
            InboundState.PARKED,
            ErrorCodes.INVOICE_NOT_FINALISED,
            harness.now(),
            Duration.ofMinutes(1));

    assertThatCode(() -> harness.unitOfWorkPinnedTo("2027-01-01.next").process(eventId))
        .as("a version-skew refusal of a parked row is recorded, never DEI-210")
        .doesNotThrowAnyException();
  }
}
