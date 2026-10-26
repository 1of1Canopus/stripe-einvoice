package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A revoked or under-scoped Stripe key (design section 5): the run stops with no retry, the
 * reconciliation pass names each stopped event once, and the first success after a refusal makes
 * the stopped rows due again. Each test has its own database: the re-arm and the findings reader
 * read the whole inbound table of one mode and account.
 */
class StripeAccessRefusedTest {

  private static EInvoiceException refused() {
    return new EInvoiceException(RetryEdgeFixture.ACCESS_REFUSED, "status 401");
  }

  /** Three events refused for the key, each stopped, none re-armed yet. */
  private static List<String> threeRefused(IssuanceTestHarness harness, String prefix) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String objectId = prefix + i;
      harness.source().with(TestInvoices.finalised(objectId));
      harness.source().breakFor(objectId, refused());
      String eventId = harness.receive("invoice.finalized", objectId);
      harness.unitOfWork().process(eventId);
      ids.add(eventId);
    }
    return ids;
  }

  @Test
  void a_revoked_key_stops_the_row_with_no_retry() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createOn(PostgresSupport.freshDatabase("sre_stop"));
    String eventId = threeRefused(harness, "in_sre_stop_").get(0);

    InboundEvent row = RetryEdgeFixture.row(harness, eventId);
    assertThat(row.state()).isEqualTo(InboundState.FAILED_FETCH);
    assertThat(row.lastCode()).isEqualTo(RetryEdgeFixture.ACCESS_REFUSED);
    assertThat(row.nextAttempt()).isEmpty();
  }

  @Test
  void with_a_revoked_key_three_sweeps_call_stripe_once_per_row() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createOn(PostgresSupport.freshDatabase("sre_calls"));
    int before = harness.source().fetches();
    threeRefused(harness, "in_sre_calls_");
    for (int sweep = 0; sweep < 3; sweep++) {
      harness.moveClockForward(Duration.ofHours(2));
      for (InboundEvent due : harness.due()) {
        harness.unitOfWork().process(due.eventId());
      }
    }
    assertThat(harness.source().fetches() - before).isEqualTo(3);
  }

  @Test
  void three_stopped_rows_and_two_reconciliation_passes_give_three_findings() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createOn(PostgresSupport.freshDatabase("sre_findings"));
    List<String> ids = threeRefused(harness, "in_sre_findings_");
    ReconciliationSweep.Settings settings =
        new ReconciliationSweep.Settings(null, null, null, 0, 0);

    harness.sweep(settings).sweep();
    harness.sweep(settings).sweep();

    List<ComplianceFinding> named =
        harness.openFindings().stream()
            .filter(f -> RetryEdgeFixture.ACCESS_REFUSED.equals(f.code()))
            .toList();
    assertThat(named)
        .extracting(ComplianceFinding::subjectId)
        .containsExactlyInAnyOrderElementsOf(ids);
    for (String id : ids) {
      assertThat(harness.findingRows(id)).as("one finding per event, deduped").isEqualTo(1);
    }
  }

  @Test
  void one_refusal_then_one_success_makes_all_three_rows_due_at_once() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createOn(PostgresSupport.freshDatabase("sre_rearm"));
    IssuanceUnitOfWork unitOfWork = harness.unitOfWork();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String objectId = "in_sre_rearm_" + i;
      harness.source().with(TestInvoices.finalised(objectId));
      harness.source().breakFor(objectId, refused());
      String eventId = harness.receive("invoice.finalized", objectId);
      unitOfWork.process(eventId);
      ids.add(eventId);
    }
    assertThat(harness.due()).extracting(InboundEvent::eventId).doesNotContainAnyElementsOf(ids);
    harness.source().with(TestInvoices.draft("in_sre_rearm_ok"));
    String fine = harness.receive("invoice.finalized", "in_sre_rearm_ok");

    unitOfWork.process(fine);

    assertThat(harness.due()).extracting(InboundEvent::eventId).containsAll(ids);
  }

  @Test
  void one_hundred_successful_fetches_issue_one_re_arm() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createOn(PostgresSupport.freshDatabase("sre_hundred"));
    AtomicInteger rearms = new AtomicInteger();
    IssuanceUnitOfWork unitOfWork =
        harness.unitOfWorkWithRun(
            SweeperRetryEdgesProbeTest.counting(harness.inbound(), "rearmAccessRefused", rearms),
            harness.runLock(),
            harness.permits(),
            harness.renderer());
    harness.source().with(TestInvoices.finalised("in_sre_hundred_refused"));
    harness.source().breakFor("in_sre_hundred_refused", refused());
    unitOfWork.process(harness.receive("invoice.finalized", "in_sre_hundred_refused"));
    harness.source().with(TestInvoices.draft("in_sre_hundred_ok"));
    String parked = harness.receive("invoice.finalized", "in_sre_hundred_ok");

    for (int i = 0; i < 100; i++) {
      unitOfWork.process(parked);
    }

    assertThat(rearms.get()).isEqualTo(1);
  }
}
