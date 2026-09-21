package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The privileged {@code reprocess(eventId)}, against a real PostgreSQL, a real series counter and
 * the real inbound state machine.
 *
 * <p>The claim under test: one recorded event whose mapping was refused can be re-run by an
 * operator after the defect that refused it - an incomplete seller profile, a rule pack - has been
 * corrected, and that path can never re-run a passed event, never touch a number that already
 * exists, never allocate twice, and is never reachable from the sweeper.
 */
class CipherProbeReprocessTest {

  private static final String INVOICE_ID = "in_test_1";
  private static final String ACTOR = "ops-jane";
  private static final String REASON = "seller VAT identifier corrected in the profile";

  /**
   * A renderer whose preflight refuses until the seller profile is corrected. The refusal is the
   * mapper's own configuration code, which is the case QUESTIONS 26 exists for: a defect of the
   * application, not of the invoice.
   */
  private static final class RepairableRenderer implements DocumentRenderer {

    private final DeterministicRenderer delegate = new DeterministicRenderer();
    private boolean repaired;

    void repair() {
      this.repaired = true;
    }

    @Override
    public PreflightReport preflight(MappingInput input) {
      return repaired
          ? PreflightReport.passed()
          : PreflightReport.refused(ErrorCodes.SELLER_PROFILE_INCOMPLETE);
    }

    @Override
    public RenderedDocument render(DocumentInput input) {
      return delegate.render(input);
    }
  }

  private static String refusedEvent(IssuanceTestHarness harness, RepairableRenderer renderer) {
    harness.source().with(TestInvoices.finalised(INVOICE_ID));
    String eventId = harness.receive("invoice.finalized", INVOICE_ID);
    IssuanceUnitOfWork.Outcome first = harness.unitOfWorkWithRenderer(renderer).process(eventId);
    assertThat(first.state()).isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(harness.numberedRows()).isZero();
    return eventId;
  }

  private static ReprocessRequest request(String eventId) {
    return new ReprocessRequest(eventId, ACTOR, REASON);
  }

  // -------------------------------------------------------------------------------------------
  // Probe 1. Nothing changed, so nothing changes.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_refusal_stays_refused_when_nothing_was_corrected() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer);
    long counterBefore = harness.seriesCounter();

    IssuanceReprocess.Result result = harness.reprocessWith(renderer).reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(result.legalNumber()).isNull();
    assertThat(harness.numberedRows()).isZero();
    assertThat(harness.chainedEvents()).isZero();
    assertThat(harness.archive().size()).isZero();
    assertThat(harness.seriesCounter()).isEqualTo(counterBefore);
    harness.moveClockForward(Duration.ofHours(2));
    assertThat(harness.due()).extracting("eventId").doesNotContain(eventId);
  }

  // -------------------------------------------------------------------------------------------
  // Probe 2. The whole point: a corrected profile issues, exactly once.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_corrected_profile_numbers_exactly_once() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer);
    renderer.repair();

    IssuanceReprocess.Result result = harness.reprocessWith(renderer).reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(result.legalNumber()).isNotNull();
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
    assertThat(harness.archive().size()).isEqualTo(1);

    // And the second call on the same event is a no-op with a stated result, not a second number.
    IssuanceReprocess.Result again = harness.reprocessWith(renderer).reprocess(request(eventId));
    assertThat(again.disposition()).isEqualTo(IssuanceReprocess.Disposition.REFUSED_NOT_ELIGIBLE);
    assertThat(again.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(again.legalNumber()).isEqualTo(result.legalNumber());
    assertThat(harness.numberedRows()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // Probe 3. The sweeper neither re-picks the refusal nor knows this path exists.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_the_sweeper_never_re_picks_a_mapping_refusal_and_never_reprocesses() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer);
    renderer.repair();

    // Everything the sweeper does with a due list, run against a cured application: the row is not
    // in it, so the correction alone never issues anything. Only the explicit call does.
    harness.moveClockForward(Duration.ofDays(1));
    assertThat(harness.due()).extracting("eventId").doesNotContain(eventId);
    // The shared database carries other tests' events too; only this invoice's are this probe's.
    for (var due : harness.due()) {
      if (INVOICE_ID.equals(due.objectId())) {
        harness.unitOfWorkWithRenderer(renderer).process(due.eventId());
      }
    }
    assertThat(harness.numberedRows()).isZero();
    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .isEqualTo(InboundState.FAILED_MAPPING);
  }

  // -------------------------------------------------------------------------------------------
  // Probe 4. A passed event is refused.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_passed_event_is_refused() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised(INVOICE_ID));
    String eventId = harness.receive("invoice.finalized", INVOICE_ID);
    IssuanceUnitOfWork.Outcome issued = harness.unitOfWork().process(eventId);
    assertThat(issued.state()).isEqualTo(InboundState.COMPLETED);
    long chainedBefore = harness.chainedEvents();

    IssuanceReprocess.Result result = harness.reprocess().reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REFUSED_NOT_ELIGIBLE);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(chainedBefore);
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // Probe 5. Two operators, one event, one number.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_concurrent_calls_on_one_event_number_once() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer);
    renderer.repair();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<IssuanceReprocess.Result> call =
          () -> harness.reprocessWith(renderer).reprocess(request(eventId));
      List<Future<IssuanceReprocess.Result>> futures = pool.invokeAll(List.of(call, call));
      List<IssuanceReprocess.Disposition> dispositions =
          List.of(futures.get(0).get().disposition(), futures.get(1).get().disposition());
      assertThat(dispositions).containsOnlyOnce(IssuanceReprocess.Disposition.REPROCESSED);
    } finally {
      pool.shutdownNow();
    }
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // Probe 6. An event id nobody recorded.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_an_unknown_event_is_refused() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();

    assertThatThrownBy(() -> harness.reprocess().reprocess(request("evt_does_not_exist")))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_UNREADABLE);
    assertThat(harness.numberedRows()).isZero();
  }

  // -------------------------------------------------------------------------------------------
  // Probe 7. The guard: a number already exists for this invoice, from any route at all.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_an_invoice_that_already_carries_a_number_is_refused() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer);
    renderer.repair();
    // A number allocated for this same invoice by another route, which is what makes this a guard
    // rather than a restatement of the pipeline's own order.
    var invoice = harness.source().fetchInvoice(INVOICE_ID);
    harness
        .store()
        .allocate(
            new AllocationRequest(
                harness.seriesKey(),
                invoice.id(),
                invoice.accountId(),
                invoice.number(),
                invoice.finalizedAt(),
                "fr-2026.1"));
    long numberedBefore = harness.numberedRows();

    IssuanceReprocess.Result result = harness.reprocessWith(renderer).reprocess(request(eventId));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REFUSED_NUMBERED);
    assertThat(result.legalNumber()).isNotNull();
    assertThat(harness.numberedRows()).isEqualTo(numberedBefore);
    assertThat(harness.archive().size()).isZero();
    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .isEqualTo(InboundState.FAILED_MAPPING);
  }

  // -------------------------------------------------------------------------------------------
  // Probe 8. Who asked, when, and why - durably, not in a log line.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_reprocess_leaves_a_durable_record_of_who_asked_and_why() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer);
    renderer.repair();

    harness.reprocessWith(renderer).reprocess(request(eventId));

    assertThat(harness.findingRows(eventId)).isEqualTo(1);
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_finding WHERE subject_id = '"
                    + eventId
                    + "' AND code = '"
                    + ErrorCodes.REPROCESSED
                    + "' AND acknowledged_at IS NOT NULL AND ack_reason LIKE '%"
                    + ACTOR
                    + "%' AND ack_reason LIKE '%VAT identifier corrected%'"))
        .isEqualTo(1);
    // The row also says the event was re-opened deliberately, not by a retry.
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_inbound_event WHERE event_id = '"
                    + eventId
                    + "' AND state = 'COMPLETED'"))
        .isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // Probe 9. The request refuses what it is handed, before anything is re-opened.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_an_operator_reason_is_mandatory_and_screened() {
    assertThatThrownBy(() -> new ReprocessRequest("evt_1", ACTOR, "  "))
        .isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> new ReprocessRequest("evt_1", ACTOR, "corrected profile"))
        .isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> new ReprocessRequest("evt_1", "", REASON))
        .isInstanceOf(EInvoiceException.class);
  }
}
