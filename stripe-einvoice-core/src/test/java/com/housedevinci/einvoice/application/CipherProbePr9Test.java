package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Review pass 1 on the privileged reprocess and the chained failure disposition (PR 9).
 *
 * <p>Every probe here states an invariant the branch's own documents promise, and fails on the
 * branch as it stands unless it says otherwise in its own comment.
 */
class CipherProbePr9Test {

  private static final String ACTOR = "ops-jane";
  private static final String REASON = "seller VAT identifier corrected in the profile";

  /** The renderer of the builder's own probe: refuses until the profile is corrected. */
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

  /** One text column, read straight out of the shared database. */
  private static String text(String sql) {
    try (Connection c = PostgresSupport.dataSource().getConnection();
        Statement st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      return rs.next() ? String.valueOf(rs.getString(1)) : "";
    } catch (SQLException e) {
      throw new IllegalStateException("test query failed: " + e.getMessage(), e);
    }
  }

  /**
   * Re-pointed by the design ruling (R-01): the durable record of who asked is the append-only
   * reprocess table written in the re-open's own transaction, not the finding row - which was
   * dropped with this change, because an upsert keyed on the subject cannot hold two decisions.
   */
  private static long acknowledged(String subjectId) {
    return PostgresSupport.scalar(
        "SELECT count(*) FROM einvoice_reprocess_request WHERE event_id = '"
            + subjectId
            + "' AND kind = 'REQUESTED' AND actor <> ''");
  }

  // -------------------------------------------------------------------------------------------
  // D9-01. The record of who asked is written after the row is already re-opened, and in its own
  // transaction. An operator reason the acknowledgement's own screen refuses - one the request's
  // screen accepts, because the stored line is the actor prefixed to it and then truncated -
  // throws between the two, and leaves the event re-opened, due, and unrecorded. The sweeper then
  // issues a legal number for an event no operator is recorded as having re-opened.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_crafted_reason_leaves_the_event_reopened_and_numbered_with_no_record() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, "in_cipher_pr9_1");
    renderer.repair();

    // 500 characters, every one of them accepted by the request's own screen: the emoji is a
    // paired surrogate here. The stored line is "ops-jane: " + this, truncated to 500 characters,
    // which cuts the pair in half.
    String reason = "a".repeat(489) + "😀" + "b".repeat(9);
    ReprocessRequest request = new ReprocessRequest(eventId, ACTOR, reason);
    assertThat(request.reason()).hasSize(500);

    try {
      harness.reprocessWith(renderer).reprocess(request);
    } catch (RuntimeException expected) {
      // The failure itself is not the finding; what it leaves behind is.
    }

    // And the consequence, because a re-opened row is due immediately: the unattended sweeper
    // finishes the job, and a legal number is consumed for a privileged action with no actor and
    // no reason anywhere in the database.
    for (var due : harness.due()) {
      if ("in_cipher_pr9_1".equals(due.objectId())) {
        harness.unitOfWorkWithRenderer(renderer).process(due.eventId());
      }
    }
    assertThat(harness.numberedRows() == 0 || acknowledged(eventId) == 1)
        .as("a legal number was consumed with no record of who re-opened the event")
        .isTrue();

    // Whatever happened, the event must not have been left running with nobody recorded as having
    // asked for it. Either the call was refused before the re-open, or the record exists.
    InboundState after = harness.inbound().find(eventId).orElseThrow().state();
    // "Recorded" means the acknowledgement exists: the design's own durable record of who asked
    // and why is the acknowledgement, not the bare finding row that record() inserts first.
    boolean recorded = acknowledged(eventId) > 0;
    assertThat(recorded || after == InboundState.FAILED_MAPPING)
        .as("event %s left in %s with no recorded operator decision", eventId, after)
        .isTrue();
  }

  // -------------------------------------------------------------------------------------------
  // D9-02. New in this pull request: the burn's chained event validates the rule id with the
  // identifier charset, inside markFailed's transaction, and markFailed is called at the one site
  // in the pipeline that is not wrapped. A host validator - always somebody else's code in 0.1.0 -
  // that reports "BR-DE-15 (fatal)" therefore throws out of process(), leaving the inbound row in
  // MAPPED with a number allocated, no disposition, no chained event, and next_attempt_at null,
  // which is due immediately and for ever: the D2-01 loop this module already fixed once.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_hostile_rule_id_from_a_host_validator_strands_a_burned_number() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWith(TestValidators.failing("BR-DE-15 (fatal)"));
    harness.source().with(TestInvoices.finalised("in_cipher_pr9_2"));
    String eventId = harness.receive("invoice.finalized", "in_cipher_pr9_2");

    IssuanceUnitOfWork.Outcome outcome;
    try {
      outcome = harness.unitOfWork().process(eventId);
    } catch (RuntimeException thrown) {
      outcome = null;
    }

    assertThat(outcome)
        .as("a rule id from a host port must not throw out of process()")
        .isNotNull();
    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .as("the event must reach a recorded disposition, not stay MAPPED and due for ever")
        .isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(harness.issuance("in_cipher_pr9_2").orElseThrow().state())
        .as("the number that was consumed must carry its burn")
        .isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(harness.due()).extracting("eventId").doesNotContain(eventId);
  }

  // -------------------------------------------------------------------------------------------
  // D9-03. What the durable record proves to an auditor. The design and the public documents say
  // the re-opened row carries DEI-264 and that who asked and why is recorded durably. A successful
  // run erases the code from the row, and the single finding row is overwritten by the next call,
  // so the only surviving evidence is the last operator's reason.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_the_record_of_a_privileged_reprocess_is_erased_and_overwritten() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, "in_cipher_pr9_3");

    // First operator: the profile was not corrected, so the run refuses again and the event stays
    // eligible. A real, recorded privileged action all the same.
    harness
        .reprocessWith(renderer)
        .reprocess(
            new ReprocessRequest(eventId, "ops-alice", "first attempt, profile not yet fixed"));
    // Second operator, after the correction.
    renderer.repair();
    harness.reprocessWith(renderer).reprocess(new ReprocessRequest(eventId, "ops-bob", REASON));

    // Re-pointed by the design ruling: the record is the append-only table, and the assertion is
    // unchanged in substance - both privileged calls stay readable as separate decisions, and the
    // record outlives the successful run that used to erase the row's DEI-264 marker.
    String stored =
        text(
            "SELECT string_agg(actor || '=' || reason, '|' ORDER BY seq)"
                + " FROM einvoice_reprocess_request WHERE event_id = '"
                + eventId
                + "' AND kind = 'REQUESTED'");
    assertThat(stored)
        .as("both privileged calls must remain readable as separate recorded decisions")
        .contains("ops-alice")
        .contains("ops-bob")
        .contains("first attempt, profile not yet fixed");
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_reprocess_request WHERE event_id = '"
                    + eventId
                    + "'"))
        .as("two calls leave two requests and two conclusions, and no row was rewritten")
        .isEqualTo(4);
  }

  // -------------------------------------------------------------------------------------------
  // D9-04. After D7-03 the verifier requires a chained event for a FAILED_VALIDATION row, while
  // the domain's own predicate for "settled and recorded in the chain" still answers false for it.
  // Two definitions of the same thing, in the layer whose whole point is that the enum is the one
  // transition table.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_the_domain_and_the_verifier_disagree_on_a_burned_number() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWith(TestValidators.failing("BR-DE-15"));
    harness.source().with(TestInvoices.finalised("in_cipher_pr9_4"));
    String eventId = harness.receive("invoice.finalized", "in_cipher_pr9_4");
    harness.unitOfWork().process(eventId);
    assertThat(harness.issuance("in_cipher_pr9_4").orElseThrow().state())
        .isEqualTo(IssuanceState.FAILED_VALIDATION);

    List<String> crossChecked = new ArrayList<>();
    for (var disposed : harness.store().disposedIssuances()) {
      crossChecked.add(disposed.state());
    }
    assertThat(crossChecked).contains(IssuanceState.FAILED_VALIDATION.name());
    assertThat(IssuanceState.FAILED_VALIDATION.disposed())
        .as("the verifier demands a chained event for this state; the domain says it has none")
        .isTrue();
  }

  // -------------------------------------------------------------------------------------------
  // D9-05, confirmation rather than a finding: a re-opened row is due immediately, so the sweeper
  // can be inside the pipeline for the same event while the operator's own call runs. Two runs,
  // one number, one chained event. This probe passes on the branch as it stands.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_reopened_row_run_twice_at_once_still_numbers_once() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String eventId = refusedEvent(harness, renderer, "in_cipher_pr9_5");
    renderer.repair();
    // Re-pointed: the bare re-open is gone, because a re-open with no record is the defect this
    // pass closes. The same in-flight state is reached through the recorded path.
    assertThat(
            harness
                .reprocessLedger()
                .reopenAndRecord(
                    new ReprocessRequest(eventId, "ops-jane", "in-flight re-open for the race"),
                    harness.sellerId(),
                    com.housedevinci.einvoice.domain.Mode.LIVE,
                    ErrorCodes.REPROCESS_REQUESTED,
                    Instant.parse("2026-01-16T09:05:00Z"))
                .isPresent())
        .isTrue();

    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<InboundState> run =
          () -> {
            start.await();
            return harness.unitOfWorkWithRenderer(renderer).process(eventId).state();
          };
      List<Future<InboundState>> futures = pool.invokeAll(List.of(run, run));
      for (Future<InboundState> future : futures) {
        assertThat(future.get()).isEqualTo(InboundState.COMPLETED);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
    assertThat(harness.archive().size()).isEqualTo(1);
  }
}
