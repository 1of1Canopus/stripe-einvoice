package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.RunningLimit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * QUESTIONS 29 through the real pipeline: one test per path of the design page (section 8 and the
 * review's additions). Each harness has a database of its own, because the stranded query reads
 * every row of the table.
 */
class RunningLimitPathsTest {

  private final List<IssuanceTestHarness> opened = new ArrayList<>();

  @AfterEach
  void close() {
    opened.forEach(
        h -> com.housedevinci.einvoice.adapter.jdbc.PostgresSupport.close(h.dataSource()));
  }

  private IssuanceTestHarness harness() {
    IssuanceTestHarness h = IssuanceTestHarness.createOnFreshDatabase("q29_paths");
    opened.add(h);
    return h;
  }

  private static StrandedInboundSweep sweep(IssuanceTestHarness h, InboundEventStore inbound) {
    return new StrandedInboundSweep(
        inbound, RunningLimit.DEFAULT, 500, h.unitOfWork().configuration());
  }

  private static StrandedInboundSweep sweep(IssuanceTestHarness h) {
    return sweep(h, h.inbound());
  }

  private static boolean due(IssuanceTestHarness h, String eventId) {
    return h.due().stream().anyMatch(e -> e.eventId().equals(eventId));
  }

  private static Optional<ComplianceFinding> stuckFinding(IssuanceTestHarness h, String eventId) {
    return h.openFindings().stream()
        .filter(f -> f.code().equals(ErrorCodes.RECON_STUCK_INBOUND))
        .filter(f -> f.subjectId().equals(eventId))
        .findFirst();
  }

  // ---- probe 2 -----------------------------------------------------------------------------

  @Test
  void probe_a_parked_row_is_stranded_unqueryable() {
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.draft("in_q29_draft"));
    String eventId = h.receive("invoice.paid", "in_q29_draft");
    assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.PARKED);

    h.moveClockForward(Duration.ofDays(200));
    assertThat(due(h, eventId)).isFalse();
    assertThat(sweep(h).expire(h.now())).isEqualTo(1);

    InboundEvent row = h.inbound().find(eventId).orElseThrow();
    assertThat(row.state()).isEqualTo(InboundState.EXPIRED);
    assertThat(row.lastCode()).isEqualTo(ErrorCodes.INBOUND_CEILING_REACHED);
    assertThat(stuckFinding(h, eventId)).isPresent();
    assertThat(h.issuance("in_q29_draft")).isEmpty();
    // Once: the next sweep finds nothing to expire and records nothing new.
    assertThat(sweep(h).expire(h.now().plusSeconds(60))).isZero();
    assertThat(h.findingRows(eventId)).isEqualTo(1);
  }

  @Test
  void a_draft_re_checked_through_the_pipeline_for_ninety_days_is_reported_on_day_ninety() {
    // The example of the brief, on the real re-check path: every backoff the sweeper runs the
    // event, the fetch finds a draft, the row goes PARKED -> FETCHED -> PARKED.
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.draft("in_q29_forever"));
    String eventId = h.receive("invoice.paid", "in_q29_forever");
    h.unitOfWork().process(eventId);
    for (int day = 1; day < 90; day++) {
      h.moveClockForward(Duration.ofDays(1));
      assertThat(sweep(h).expire(h.now())).as("day %s", day).isZero();
      assertThat(due(h, eventId)).as("day %s", day).isTrue();
      assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.PARKED);
    }
    h.moveClockForward(Duration.ofDays(1).plusSeconds(1));
    assertThat(sweep(h).expire(h.now())).isEqualTo(1);
    assertThat(due(h, eventId)).isFalse();
    assertThat(stuckFinding(h, eventId)).isPresent();
  }

  // ---- probe 3, pipeline half --------------------------------------------------------------

  @Test
  void probe_a_legitimate_parked_row_is_not_expired() {
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.draft("in_q29_month"));
    String eventId = h.receive("invoice.paid", "in_q29_month");
    h.unitOfWork().process(eventId);
    h.moveClockForward(Duration.ofDays(30));

    assertThat(sweep(h).expire(h.now())).isZero();
    assertThat(due(h, eventId)).isTrue();
    assertThat(stuckFinding(h, eventId)).isEmpty();
  }

  // ---- probe 10, pipeline half -------------------------------------------------------------

  @Test
  void probe_a_resuming_parked_row_is_expired() {
    // Parked, finalised on day 80: the run that picks it up issues it, and no sweep in between
    // or after expires it.
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.draft("in_q29_late"));
    String eventId = h.receive("invoice.paid", "in_q29_late");
    h.unitOfWork().process(eventId);
    h.moveClockForward(Duration.ofDays(80));
    h.source().with(TestInvoices.finalised("in_q29_late"));

    assertThat(sweep(h).expire(h.now())).isZero();
    assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
    assertThat(sweep(h).expire(h.now().plusSeconds(1))).isZero();
    assertThat(stuckFinding(h, eventId)).isEmpty();
  }

  // ---- probe 4 -----------------------------------------------------------------------------

  @Test
  void probe_expired_is_a_silent_terminal() {
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.draft("in_q29_silent"));
    String eventId = h.receive("invoice.paid", "in_q29_silent");
    h.unitOfWork().process(eventId);
    h.moveClockForward(Duration.ofDays(91));
    sweep(h).expire(h.now());

    InboundEvent row = h.inbound().find(eventId).orElseThrow();
    assertThat(row.state().terminal()).isTrue();
    assertThat(row.bodyPresent()).isFalse();
    assertThat(h.inbound().body(eventId)).isEmpty();
    assertThat(stuckFinding(h, eventId)).isPresent();
    // A worker or sweeper submission of an EXPIRED row answers what is stored and runs nothing.
    int fetches = h.source().fetches();
    assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.EXPIRED);
    assertThat(h.source().fetches()).isEqualTo(fetches);
  }

  // ---- probes 5 and 12 ---------------------------------------------------------------------

  @Test
  void probe_reprocess_of_an_expired_row_cannot_refetch() {
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.draft("in_q29_back"));
    String eventId = h.receive("invoice.paid", "in_q29_back");
    h.unitOfWork().process(eventId);
    h.moveClockForward(Duration.ofDays(91));
    sweep(h).expire(h.now());
    assertThat(h.inbound().body(eventId)).isEmpty();

    // The seller finally finalises the draft; the operator re-opens the event.
    h.source().with(TestInvoices.finalised("in_q29_back"));
    IssuanceReprocess.Result result =
        h.reprocess().reprocess(new ReprocessRequest(eventId, "ops-q29", "draft was finalised"));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(result.legalNumber()).isNotBlank();
    assertThat(h.reprocessRecords(eventId)).hasSize(2);
  }

  @Test
  void probe_expired_is_unrecoverable() {
    // The reprocess of a row whose draft is still a draft parks it with a fresh clock: the next
    // sweep does not expire it again without it ever running.
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.draft("in_q29_still"));
    String eventId = h.receive("invoice.paid", "in_q29_still");
    h.unitOfWork().process(eventId);
    h.moveClockForward(Duration.ofDays(91));
    sweep(h).expire(h.now());

    h.reprocess().reprocess(new ReprocessRequest(eventId, "ops-q29", "seller says it is coming"));
    InboundEvent row = h.inbound().find(eventId).orElseThrow();
    assertThat(row.state()).isEqualTo(InboundState.PARKED);
    assertThat(row.dueSinceInstant()).contains(h.now());
    assertThat(sweep(h).expire(h.now().plusSeconds(60))).isZero();
    assertThat(due(h, eventId)).isFalse(); // its backoff has not come yet
  }

  // ---- probe 6 -----------------------------------------------------------------------------

  @Test
  void probe_recon_enqueue_recovers_a_stranded_row() {
    // Why reprocess must accept EXPIRED: the reconciliation re-enqueue of an invoice whose
    // recon- row already exists is deduplicated away and runs nothing.
    IssuanceTestHarness h = harness();
    EventIdentity identity =
        new EventIdentity(
            "recon-in_q29_recon",
            "invoice.finalized",
            "2026-03-31.clover",
            true,
            "",
            "in_q29_recon");
    InboundEvent first =
        InboundEvent.received(
            identity, Mode.LIVE, ReconciliationSweep.RECONCILIATION_SOURCE, new byte[0], h.now());
    assertThat(h.inbound().record(first, null)).isTrue();
    h.moveClockForward(Duration.ofDays(4));
    assertThat(sweep(h).expire(h.now())).isEqualTo(1);

    InboundEvent again =
        InboundEvent.received(
            identity, Mode.LIVE, ReconciliationSweep.RECONCILIATION_SOURCE, new byte[0], h.now());
    assertThat(h.inbound().record(again, null)).isFalse();
    assertThat(h.inbound().find(identity.eventId()).orElseThrow().state())
        .isEqualTo(InboundState.EXPIRED);
    assertThat(IssuanceReprocess.eligible(h.inbound().find(identity.eventId()).orElseThrow()))
        .isTrue();
  }

  // ---- probe 7 -----------------------------------------------------------------------------

  @Test
  void probe_a_voided_row_is_re_opened_by_the_ceiling() {
    // A void concludes on FAILED_ISSUANCE with no next attempt. The limit must never touch it.
    IssuanceTestHarness h = harness();
    EventIdentity identity =
        new EventIdentity(
            "evt_q29_void", "invoice.finalized", "2026-03-31.clover", true, "", "in_v");
    h.inbound()
        .record(
            InboundEvent.received(identity, Mode.LIVE, "primary", new byte[] {1}, h.now()), null);
    h.inbound().transition("evt_q29_void", InboundState.FETCHED, "", h.now(), null);
    h.inbound().transition("evt_q29_void", InboundState.MAPPED, "", h.now(), null);
    h.inbound()
        .transition(
            "evt_q29_void", InboundState.FAILED_ISSUANCE, ErrorCodes.NUMBER_VOIDED, h.now(), null);
    h.moveClockForward(Duration.ofDays(400));

    assertThat(sweep(h).expire(h.now())).isZero();
    assertThat(h.inbound().find("evt_q29_void").orElseThrow().state())
        .isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(due(h, "evt_q29_void")).isFalse();
  }

  // ---- probe 11 ----------------------------------------------------------------------------

  @Test
  void probe_a_concurrently_completed_row_throws_the_sweep() {
    // F-6: between the stranded read and the first expiry the first row concludes elsewhere. The
    // sweep skips it with no finding and still expires the second one.
    IssuanceTestHarness h = harness();
    String first = h.receive("invoice.paid", "in_q29_c1");
    String second = h.receive("invoice.paid", "in_q29_c2");
    h.moveClockForward(Duration.ofDays(4));
    InboundEventStore racing =
        new DelegatingInboundEventStore(h.inbound()) {
          @Override
          public List<InboundEvent> stranded(Instant now, RunningLimit limit, int pageSize) {
            List<InboundEvent> rows = super.stranded(now, limit, pageSize);
            h.inbound().transition(first, InboundState.FETCHED, "", now, null);
            h.inbound().transition(first, InboundState.COMPLETED, "", now, null);
            return rows;
          }
        };

    assertThat(sweep(h, racing).expire(h.now())).isEqualTo(1);
    assertThat(h.inbound().find(first).orElseThrow().state()).isEqualTo(InboundState.COMPLETED);
    assertThat(stuckFinding(h, first)).isEmpty();
    assertThat(h.inbound().find(second).orElseThrow().state()).isEqualTo(InboundState.EXPIRED);
    assertThat(stuckFinding(h, second)).isPresent();
  }

  @Test
  void a_row_whose_write_fails_does_not_stop_the_rest_of_the_page() {
    IssuanceTestHarness h = harness();
    String first = h.receive("invoice.paid", "in_q29_f1");
    String second = h.receive("invoice.paid", "in_q29_f2");
    h.moveClockForward(Duration.ofDays(4));
    InboundEventStore failing =
        new DelegatingInboundEventStore(h.inbound()) {
          @Override
          public boolean expire(InboundEvent row, ComplianceFinding finding, Instant now) {
            if (row.eventId().equals(first)) {
              throw new com.housedevinci.einvoice.domain.EInvoiceException(
                  ErrorCodes.STORE_UNAVAILABLE, "simulated");
            }
            return super.expire(row, finding, now);
          }
        };

    assertThat(sweep(h, failing).expire(h.now())).isEqualTo(1);
    assertThat(h.inbound().find(first).orElseThrow().state()).isEqualTo(InboundState.RECEIVED);
    assertThat(h.inbound().find(second).orElseThrow().state()).isEqualTo(InboundState.EXPIRED);
  }

  // ---- probe 15 ----------------------------------------------------------------------------

  @Test
  void probe_reprocess_accepts_a_third_state() {
    // D-PR50-2 (on main): FAILED_ISSUANCE is accepted with any code; the re-open statement itself
    // refuses a numbered invoice.
    Set<InboundState> accepted =
        EnumSet.of(InboundState.FAILED_MAPPING, InboundState.FAILED_ISSUANCE, InboundState.EXPIRED);
    for (InboundState state : InboundState.values()) {
      for (String code : new String[] {"", ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES}) {
        InboundEvent row =
            new InboundEvent(
                "evt_q29_enum",
                "invoice.finalized",
                "in_enum",
                "2026-03-31.clover",
                true,
                "",
                "",
                Mode.LIVE,
                "primary",
                Instant.EPOCH,
                Instant.EPOCH,
                state,
                0,
                null,
                code,
                "",
                false,
                Instant.EPOCH);
        boolean expected = accepted.contains(state);
        assertThat(IssuanceReprocess.eligible(row))
            .as("%s / '%s'", state, code)
            .isEqualTo(expected);
      }
    }
  }

  @Test
  void a_numbered_row_that_expired_is_reopened_in_place_and_resumes_its_number() {
    // A MAPPED row can hold a number when it expires. QUESTIONS 48: the reprocess re-opens it in
    // place, never to RECEIVED, and resumes that same number once the archive is back.
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.finalised("in_q29_numbered"));
    String eventId = h.receive("invoice.finalized", "in_q29_numbered");
    h.archive()
        .failWith(
            new com.housedevinci.einvoice.domain.EInvoiceException(
                ErrorCodes.ARCHIVE_UNAVAILABLE, "down"));
    h.unitOfWork().process(eventId);
    assertThat(h.issuance("in_q29_numbered")).isPresent();
    // Simulate the crash loop: the row is left MAPPED with its number.
    com.housedevinci.einvoice.adapter.jdbc.PostgresSupport.executeOn(
        h.dataSource(),
        "UPDATE einvoice_inbound_event SET state = 'MAPPED', next_attempt_at = NULL"
            + " WHERE event_id = '"
            + eventId
            + "'");
    h.moveClockForward(Duration.ofDays(4));
    assertThat(sweep(h).expire(h.now())).isEqualTo(1);
    String number = RetryEdgeFixture.number(h, "in_q29_numbered");
    h.archive().heal();

    IssuanceReprocess.Result result =
        h.reprocess().reprocess(new ReprocessRequest(eventId, "ops-q29", "archive is back"));
    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(result.legalNumber()).isEqualTo(number);
  }
}
