package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 50 pass 2 (fix/sweeper-retry-edges, head 9974dc2). Adversarial variants of
 * the two fixes. Every method passes on 9974dc2 and stays as a regression guard.
 */
class CipherProbePr50Pass2Test {

  private static final String RULE = "BR-DE-15";

  private record Voided(IssuanceTestHarness harness, String eventId) {}

  private static Voided burnedAndVoided(String objectId) {
    IssuanceTestHarness harness = IssuanceTestHarness.createWith(TestValidators.failing(RULE));
    harness.source().with(TestInvoices.finalised(objectId));
    String eventId = harness.receive("invoice.finalized", objectId);
    harness.unitOfWork().process(eventId);
    harness
        .store()
        .voidUnused(
            new VoidRequest(harness.sellerId(), Mode.LIVE, objectId, "never a document", RULE));
    assertThat(harness.issuance(objectId).orElseThrow().state())
        .isEqualTo(IssuanceState.VOID_UNUSED);
    return new Voided(harness, eventId);
  }

  /** D-PR50-1 variant: a voided number's row leaves on schedule, never before its retention. */
  @Test
  void guard_a_voided_number_row_is_purged_on_schedule_not_early() {
    String objectId = "in_pr50p2_void_" + System.nanoTime();
    Voided voided = burnedAndVoided(objectId);
    IssuanceTestHarness harness = voided.harness();
    String eventId = voided.eventId();
    PostgresSupport.execute(
        "UPDATE einvoice_inbound_event SET received_at = timestamptz '1998-06-10T00:00:00Z'"
            + " WHERE event_id = '"
            + eventId
            + "'");

    harness.inbound().purgeOlderThan(Instant.parse("1998-06-10T00:00:00Z"), 10);
    assertThat(harness.inbound().find(eventId))
        .as("received exactly at the cutoff: still inside retention, kept")
        .isPresent();
    harness.inbound().purgeOlderThan(Instant.parse("1998-06-01T00:00:00Z"), 10);
    assertThat(harness.inbound().find(eventId)).as("inside retention: kept").isPresent();

    harness.inbound().purgeOlderThan(Instant.parse("1998-06-10T00:00:00.000001Z"), 10);
    assertThat(harness.inbound().find(eventId)).as("past retention: purged").isEmpty();
  }

  /** D-PR50-1 boundary: the three open states still keep their row past retention. */
  @Test
  void guard_every_open_state_keeps_its_row_past_retention() {
    for (IssuanceState open :
        new IssuanceState[] {
          IssuanceState.NUMBERED, IssuanceState.ARCHIVING, IssuanceState.FAILED_ARCHIVE
        }) {
      String objectId = "in_pr50p2_open_" + open.name() + "_" + System.nanoTime();
      Voided voided = burnedAndVoided(objectId);
      IssuanceTestHarness harness = voided.harness();
      String eventId = voided.eventId();
      // Force the state the purge reads; the trigger is bypassed only for this fixture row.
      PostgresSupport.execute(
          "BEGIN; ALTER TABLE einvoice_issuance DISABLE TRIGGER USER;"
              + " UPDATE einvoice_issuance SET state = '"
              + open.name()
              + "' WHERE stripe_invoice_id = '"
              + objectId
              + "'; ALTER TABLE einvoice_issuance ENABLE TRIGGER USER; COMMIT;"
              + " UPDATE einvoice_inbound_event SET received_at = timestamptz"
              + " '1998-01-01T00:00:00Z' WHERE event_id = '"
              + eventId
              + "'");

      harness.inbound().purgeOlderThan(Instant.parse("1998-01-02T00:00:00Z"), 10);

      assertThat(harness.inbound().find(eventId)).as(open + " keeps its row").isPresent();
      PostgresSupport.execute(
          "DELETE FROM einvoice_inbound_event WHERE event_id = '" + eventId + "'");
    }
  }

  /**
   * D-PR50-2 side effect: a voided number on a FAILED_ISSUANCE row with a code other than the
   * burn's, and on a FAILED_MAPPING row. Each is REFUSED_NUMBERED and runs, records and takes
   * nothing.
   */
  @Test
  void guard_a_voided_number_reprocess_runs_nothing_records_nothing_takes_no_number() {
    for (String[] start :
        new String[][] {
          {InboundState.FAILED_ISSUANCE.name(), ErrorCodes.STRIPE_UNAVAILABLE},
          {InboundState.FAILED_ISSUANCE.name(), ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES},
          {InboundState.FAILED_MAPPING.name(), ErrorCodes.MAPPING_INCOMPLETE}
        }) {
      String objectId = "in_pr50p2_reproc_" + System.nanoTime();
      Voided voided = burnedAndVoided(objectId);
      IssuanceTestHarness harness = voided.harness();
      String eventId = voided.eventId();
      PostgresSupport.execute(
          "UPDATE einvoice_inbound_event SET state = '"
              + start[0]
              + "', last_code = '"
              + start[1]
              + "', next_attempt_at = NULL WHERE event_id = '"
              + eventId
              + "'");
      InboundEvent before = harness.inbound().find(eventId).orElseThrow();
      int fetches = harness.source().fetches();
      long counter = harness.seriesCounter();
      long chained = harness.chainedEvents();
      long numbered = harness.numberedRows();

      IssuanceReprocess.Result result =
          harness.reprocess().reprocess(new ReprocessRequest(eventId, "operator-1", "try again"));

      assertThat(result.disposition())
          .as(start[0] + " " + start[1])
          .isEqualTo(IssuanceReprocess.Disposition.REFUSED_NUMBERED);
      assertThat(harness.source().fetches()).as("no pipeline run").isEqualTo(fetches);
      assertThat(harness.reprocessRecords(eventId)).as("no request recorded").isEmpty();
      assertThat(harness.seriesCounter()).as("no number taken").isEqualTo(counter);
      assertThat(harness.numberedRows()).isEqualTo(numbered);
      assertThat(harness.chainedEvents()).as("nothing chained").isEqualTo(chained);
      InboundEvent after = harness.inbound().find(eventId).orElseThrow();
      assertThat(after.state()).isEqualTo(before.state());
      assertThat(after.lastCode()).isEqualTo(before.lastCode());
      assertThat(after.attempts()).isEqualTo(before.attempts());
      assertThat(harness.issuance(objectId).orElseThrow().state())
          .isEqualTo(IssuanceState.VOID_UNUSED);
    }
  }
}
