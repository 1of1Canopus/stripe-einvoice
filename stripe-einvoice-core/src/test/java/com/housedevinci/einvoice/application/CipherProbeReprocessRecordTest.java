package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.JdbcReprocessLedger;
import com.housedevinci.einvoice.adapter.jdbc.JdbcUnitOfWork;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The durable record of a privileged re-open (D9-03, and R-01 to R-05 of the design ruling).
 *
 * <p>The property under test: <b>every call that re-opens a row leaves an actor, a reason and a
 * time that no later call can overwrite and that outlives the run it describes.</b> The findings
 * table could not carry it - one upsert per {@code (seller, mode, code, subject)} - so this is an
 * append-only table with two rows per call and no update path at all.
 *
 * <p>What it is not: hash-chained. A role that owns the schema can disable the trigger and rewrite
 * a row, and nothing here will report that. The issuance chain is the tamper-evident record.
 */
class CipherProbeReprocessRecordTest {

  private static final String INVOICE_ID = "in_record_1";
  private static final String ACTOR = "ops-jane";
  private static final String REASON = "seller VAT identifier corrected in the profile";

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

  private static String refusedEvent(
      IssuanceTestHarness harness, RepairableRenderer renderer, String invoiceId) {
    harness.source().with(TestInvoices.finalised(invoiceId));
    String eventId = harness.receive("invoice.finalized", invoiceId);
    assertThat(harness.unitOfWorkWithRenderer(renderer).process(eventId).state())
        .isEqualTo(InboundState.FAILED_MAPPING);
    return eventId;
  }

  private static long rowsFor(String eventId) {
    return PostgresSupport.scalar(
        "SELECT count(*) FROM einvoice_reprocess_request WHERE event_id = '" + eventId + "'");
  }

  // -------------------------------------------------------------------------------------------
  // 1. The record survives the run it describes.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_the_record_survives_a_successful_run() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, INVOICE_ID);
    renderer.repair();

    IssuanceReprocess.Result result =
        harness.reprocessWith(renderer).reprocess(new ReprocessRequest(eventId, ACTOR, REASON));

    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    List<ReprocessLedger.ReprocessRecord> records = harness.reprocessRecords(eventId);
    assertThat(records).hasSize(2);
    assertThat(records.get(0).actor()).isEqualTo(ACTOR);
    assertThat(records.get(0).reason()).isEqualTo(REASON);
    assertThat(records.get(1).outcomeState()).isEqualTo(InboundState.COMPLETED.name());
    assertThat(records.get(1).legalNumber()).isEqualTo(result.legalNumber());
    // The event row itself has moved on - COMPLETED, no code - which is exactly why the record
    // cannot live there.
    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .isEqualTo(InboundState.COMPLETED);
  }

  // -------------------------------------------------------------------------------------------
  // 2. A second call appends; it never rewrites the first.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_second_reprocess_appends_and_leaves_the_first_record_untouched() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, "in_record_2");

    harness
        .reprocessWith(renderer)
        .reprocess(new ReprocessRequest(eventId, "ops-alice", "first attempt, profile not fixed"));
    List<ReprocessLedger.ReprocessRecord> afterFirst = harness.reprocessRecords(eventId);
    renderer.repair();
    harness.reprocessWith(renderer).reprocess(new ReprocessRequest(eventId, "ops-bob", REASON));

    List<ReprocessLedger.ReprocessRecord> afterSecond = harness.reprocessRecords(eventId);
    assertThat(afterSecond).hasSize(4);
    assertThat(afterSecond.subList(0, 2)).isEqualTo(afterFirst);
    assertThat(afterSecond.get(2).actor()).isEqualTo("ops-bob");
    assertThat(afterSecond.get(0).actor()).isEqualTo("ops-alice");
  }

  // -------------------------------------------------------------------------------------------
  // 3. The re-open and the record are one transaction: a failure leaves neither.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_failure_between_the_reopen_and_the_record_leaves_neither() throws SQLException {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, "in_record_3");

    try (Connection connection = PostgresSupport.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      JdbcReprocessLedger killed = new JdbcReprocessLedger(JdbcUnitOfWork.using(connection));
      assertThat(
              killed
                  .reopenAndRecord(
                      new ReprocessRequest(eventId, ACTOR, REASON),
                      harness.sellerId(),
                      Mode.LIVE,
                      ErrorCodes.REPROCESS_REQUESTED,
                      harness.now())
                  .isPresent())
          .isTrue();
      // The process dies here: the caller's transaction never commits.
      connection.rollback();
    }

    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .as("the event was not left re-opened")
        .isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(rowsFor(eventId)).as("and nothing claims anybody asked").isZero();
  }

  // -------------------------------------------------------------------------------------------
  // 4. Refused, never truncated (R-01). This is the regression D9-01 was: a reason both screens
  // accepted, shortened after composition, cutting a surrogate pair in half.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_bound_length_reason_is_stored_byte_identical() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, "in_record_4");
    renderer.repair();
    // 500 characters exactly, with a paired surrogate at the bound: the input that used to be
    // truncated into an unpaired half.
    String reason = "a".repeat(489) + "😀" + "b".repeat(9);
    assertThat(reason).hasSize(500);

    harness.reprocessWith(renderer).reprocess(new ReprocessRequest(eventId, ACTOR, reason));

    ReprocessLedger.ReprocessRecord requested = harness.reprocessRecords(eventId).get(0);
    assertThat(requested.reason()).isEqualTo(reason);
    assertThat(requested.actor()).isEqualTo(ACTOR);
  }

  @Test
  void probe_an_over_bound_actor_or_reason_is_refused_before_any_write() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, "in_record_5");
    renderer.repair();

    assertThatThrownBy(() -> new ReprocessRequest(eventId, ACTOR, "a".repeat(501)))
        .isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> new ReprocessRequest(eventId, "o".repeat(65), REASON))
        .isInstanceOf(EInvoiceException.class);

    assertThat(rowsFor(eventId)).isZero();
    assertThat(harness.numberedRows()).isZero();
    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .isEqualTo(InboundState.FAILED_MAPPING);
  }

  // -------------------------------------------------------------------------------------------
  // 5. Nothing else writes here, and the retention purge does not touch it (R-05).
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_the_background_jobs_never_write_to_the_record() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, "in_record_6");
    renderer.repair();
    harness.reprocessWith(renderer).reprocess(new ReprocessRequest(eventId, ACTOR, REASON));
    long before = rowsFor(eventId);

    harness.moveClockForward(Duration.ofDays(2));
    for (var due : harness.due()) {
      if ("in_record_6".equals(due.objectId())) {
        harness.unitOfWorkWithRenderer(renderer).process(due.eventId());
      }
    }
    harness
        .sweep(
            new ReconciliationSweep.Settings(
                Duration.ofDays(7), Duration.ofMinutes(5), Duration.ofHours(6), 5, 50))
        .sweep();
    // The retention purge deletes inbound rows; the record of who re-opened one is not a transport
    // artifact and is not purged with it.
    harness.inbound().purgeOlderThan(java.time.Instant.parse("2030-01-01T00:00:00Z"), 500);

    assertThat(rowsFor(eventId)).isEqualTo(before);
  }

  // -------------------------------------------------------------------------------------------
  // 6. An orphan request is a finding with a code (R-03).
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_an_unfinished_reprocess_is_reported_once_and_a_finished_one_never() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String finished = refusedEvent(harness, renderer, "in_record_7");
    renderer.repair();
    harness.reprocessWith(renderer).reprocess(new ReprocessRequest(finished, ACTOR, REASON));

    // A process that died: the REQUESTED row committed and no conclusion ever followed.
    String orphaned = refusedEvent(harness, new RepairableRenderer(), "in_record_8");
    harness
        .reprocessLedger()
        .reopenAndRecord(
            new ReprocessRequest(orphaned, ACTOR, REASON),
            harness.sellerId(),
            Mode.LIVE,
            ErrorCodes.REPROCESS_REQUESTED,
            harness.now());

    harness.moveClockForward(Duration.ofDays(1));
    ReconciliationSweep.Result first =
        harness
            .sweep(
                new ReconciliationSweep.Settings(
                    Duration.ofDays(7), Duration.ofMinutes(5), Duration.ofHours(6), 5, 50))
            .sweep();
    harness
        .sweep(
            new ReconciliationSweep.Settings(
                Duration.ofDays(7), Duration.ofMinutes(5), Duration.ofHours(6), 5, 50))
        .sweep();

    assertThat(first.unfinishedReprocesses()).isPositive();
    assertThat(harness.findingRows(orphaned))
        .as("raised once per subject, like every other finding")
        .isEqualTo(1);
    assertThat(harness.findingRows(finished))
        .as("a request that concluded is not an orphan, whatever the outcome was")
        .isZero();
  }

  // -------------------------------------------------------------------------------------------
  // 7. A run that throws still concludes (R-04), so an orphan only ever means a dead process.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_throwing_pipeline_still_concludes_with_the_error_code() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, "in_record_9");
    renderer.repair();
    // A store that throws inside the pipeline, after the re-open: the exception reaches the
    // caller, and the record still says how the run ended.
    harness.source().breakWith(new IllegalStateException("the upstream client exploded"));

    assertThatThrownBy(
            () ->
                harness
                    .reprocessWith(renderer)
                    .reprocess(new ReprocessRequest(eventId, ACTOR, REASON)))
        .isInstanceOf(RuntimeException.class);

    List<ReprocessLedger.ReprocessRecord> records = harness.reprocessRecords(eventId);
    assertThat(records).hasSize(2);
    assertThat(records.get(1).kind()).isEqualTo("CONCLUDED");
    assertThat(records.get(1).outcomeCode()).isNotEmpty();
    assertThat(records.get(1).outcomeState()).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // 8. The structural guards beside the in-code ones (R-05).
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_the_table_refuses_a_second_conclusion_an_update_and_a_delete() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, "in_record_10");
    renderer.repair();
    harness.reprocessWith(renderer).reprocess(new ReprocessRequest(eventId, ACTOR, REASON));
    long requestSeq = harness.reprocessRecords(eventId).get(0).seq();

    assertThatThrownBy(
            () ->
                harness
                    .reprocessLedger()
                    .conclude(
                        requestSeq,
                        new ReprocessLedger.ReprocessRecord.Outcome(
                            eventId,
                            harness.sellerId(),
                            Mode.LIVE,
                            java.util.Optional.of(InboundState.COMPLETED),
                            "",
                            "INV-2026-000001"),
                        harness.now()))
        .as("one conclusion per request: a second is a rewrite with extra steps")
        .isInstanceOf(RuntimeException.class);

    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    "UPDATE einvoice_reprocess_request SET reason = 'somebody else said this'"
                        + " WHERE event_id = '"
                        + eventId
                        + "'"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    "DELETE FROM einvoice_reprocess_request WHERE event_id = '" + eventId + "'"))
        .isInstanceOf(RuntimeException.class);
    assertThat(rowsFor(eventId)).isEqualTo(2);
  }
}
