package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 50 pass 1 (fix/sweeper-retry-edges, head e33170e).
 *
 * <p>probe_* methods fail on e33170e and pass once their finding is fixed. guard_* methods pass on
 * e33170e: they pin properties the review verified (the edge table, concurrency) and stay as
 * regression guards.
 *
 * <p>Install under stripe-einvoice-core/src/test/java/com/housedevinci/einvoice/application/.
 */
class CipherProbePr50Test {

  private static final Duration WAIT = Duration.ofSeconds(30);

  // ---------------------------------------------------------------------------------------------
  // D-PR50-1 (LOW, retention). The purge guard keeps every row whose invoice holds a number that
  // is "neither issued nor voided". A burned number (FAILED_VALIDATION) is not open: nothing can
  // resume it, the reprocess refuses it. Its inbound row, with the buyer's raw body, now outlives
  // the retention ceiling until an operator voids the number, for no purpose the row serves.
  // ---------------------------------------------------------------------------------------------

  @Test
  void probe_a_burned_number_keeps_its_inbound_row_past_retention() {
    IssuanceTestHarness withValidator =
        IssuanceTestHarness.createWith(TestValidators.failing("BR-DE-15"));
    String objectId = "in_pr50_burned_" + System.nanoTime();
    withValidator.source().with(TestInvoices.finalised(objectId));
    String eventId = withValidator.receive("invoice.finalized", objectId);
    withValidator.unitOfWork().process(eventId);
    assertThat(withValidator.issuance(objectId).orElseThrow().state())
        .isEqualTo(IssuanceState.FAILED_VALIDATION);
    IssuanceReprocess.Result tried =
        withValidator.reprocess().reprocess(new ReprocessRequest(eventId, "operator-1", "again"));
    assertThat(tried.disposition())
        .as("a burned number has no resume path, so the row is not kept for one")
        .isNotEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    // Older than every other row of the shared database, and a cutoff only this row passes.
    PostgresSupport.execute(
        "UPDATE einvoice_inbound_event SET received_at = timestamptz '1999-01-01T00:00:00Z'"
            + " WHERE event_id = '"
            + eventId
            + "'");

    withValidator.inbound().purgeOlderThan(Instant.parse("1999-01-02T00:00:00Z"), 10);

    assertThat(withValidator.inbound().find(eventId))
        .as("a row whose number is burned is purged at retention like any concluded row")
        .isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // D-PR50-2 (LOW, recovery). concludeBeforeFetch treats every FAILED_ISSUANCE start as numbered.
  // A FAILED_ISSUANCE row can hold no number: the allocator refused before P1 (DEI-132 clock
  // behind, store outage, timeout). An outage on its retry rewrites the code to DEI-211. The row
  // then is unnumbered FAILED_ISSUANCE with a code the reprocess does not accept (eligible() wants
  // DEI-132, eligibleNumbered wants an open number). Past the DUE ceiling it has no path back and
  // no finding (DEI-273 reads issuance rows; there is none). The same holds for an unnumbered
  // MAPPED start (crash between the MAPPED write and the claim).
  // ---------------------------------------------------------------------------------------------

  @Test
  void probe_an_unnumbered_failed_issuance_row_loses_its_reprocess_path_after_an_outage() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String objectId = "in_pr50_unnumbered_" + System.nanoTime();
    harness.source().with(TestInvoices.finalised(objectId));
    String eventId = harness.receive("invoice.finalized", objectId);
    harness.inbound().transition(eventId, InboundState.FETCHED, "", harness.now(), null);
    harness
        .inbound()
        .transition(
            eventId,
            InboundState.FAILED_ISSUANCE,
            ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES,
            harness.now(),
            Duration.ofMinutes(1));
    assertThat(harness.issuance(objectId)).as("the allocator refused: no number").isEmpty();
    assertThat(IssuanceReprocess.eligible(RetryEdgeFixture.row(harness, eventId))).isTrue();

    harness
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "stripe is down"));
    harness.unitOfWork().process(eventId);
    harness.source().heal();
    InboundEvent after = RetryEdgeFixture.row(harness, eventId);
    assertThat(after.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(after.lastCode()).isEqualTo(ErrorCodes.STRIPE_UNAVAILABLE);

    IssuanceReprocess.Result result =
        harness.reprocess().reprocess(new ReprocessRequest(eventId, "operator-1", "clock fixed"));

    assertThat(result.disposition())
        .as("an unnumbered row the mapping labelled FAILED_ISSUANCE keeps an operator path")
        .isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
  }

  // ---------------------------------------------------------------------------------------------
  // D-PR50-B1 (builder's finding, QUESTIONS 57; rated LOW, pre-existing on main). The stuck check
  // (DEI-273) reads open numbers only through inSeries(key, pageSize): the newest pageSize
  // numbers of the fiscal years the 2-day window touches, and only after the Stripe list call
  // succeeded. DEI-270 (missing issuance) names the same invoice only while its finalized_at is
  // inside the window. D-SRE-2, D-SRE-5b and D-SRE-7 name DEI-273 as the alert for every undue
  // open number. If no pass completes in the two days after finalisation, and the window then no
  // longer touches the number's fiscal year, neither code ever names it.
  // ---------------------------------------------------------------------------------------------

  /**
   * The key is refused (or Stripe's list is down) for the window after a number goes stuck: the
   * pass throws at the list call, before the stuck check. Once Stripe answers again the window no
   * longer touches the number's fiscal year, so it is never named.
   */
  @Test
  void probe_a_stuck_number_is_never_named_when_the_list_fails_until_the_year_turns() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String stuck = "in_pr50_stuck_year_" + System.nanoTime();
    RetryEdgeFixture.numberWithArchiveDown(harness, stuck);
    harness.moveClockForward(Duration.ofHours(7));
    harness
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_ACCESS_REFUSED, "status 401"));
    ReconciliationSweep refused =
        harness.sweep(new ReconciliationSweep.Settings(null, null, null, 0, 0));
    assertThatThrownBy(refused::sweep).isInstanceOf(EInvoiceException.class);
    harness.source().heal();
    harness.moveClockTo(Instant.parse("2027-01-10T09:00:00Z"));

    harness.sweep(new ReconciliationSweep.Settings(null, null, null, 0, 0)).sweep();

    assertThat(harness.issuance(stuck).orElseThrow().state().open()).isTrue();
    assertThat(harness.openFindings())
        .as("an open number is named by the first pass that completes, whatever its year")
        .anySatisfy(
            f -> {
              assertThat(f.code()).isEqualTo(ErrorCodes.RECON_STUCK_ISSUANCE);
              assertThat(f.subjectId()).isEqualTo(stuck);
            });
  }

  // ---------------------------------------------------------------------------------------------
  // Guards (green on e33170e). Independent of SweeperRetryEdgeTableTest: every start state x
  // every pre-FETCHED conclusion (plus the refused key), with and without a number.
  // ---------------------------------------------------------------------------------------------

  @Test
  void guard_every_cell_records_a_backoff_or_a_stop_and_never_escapes() {
    List<String> failures = new ArrayList<>();
    for (boolean numbered : new boolean[] {false, true}) {
      for (InboundState start : InboundState.values()) {
        if (start == InboundState.COMPLETED
            || start == InboundState.DROPPED
            || start == InboundState.FAILED_MAPPING
            || start == InboundState.FAILED_TOTALS
            || start == InboundState.EXPIRED) {
          continue; // process answers the stored outcome; not a cell (design section 2)
        }
        for (RetryEdgeFixture.Conclusion conclusion : RetryEdgeFixture.Conclusion.values()) {
          String cell = (numbered ? "numbered " : "unnumbered ") + start + " x " + conclusion;
          try {
            check(numbered, start, conclusion, failures, cell);
          } catch (RuntimeException e) {
            failures.add(cell + ": escaped " + e);
          }
        }
      }
    }
    assertThat(failures).isEmpty();
  }

  private static void check(
      boolean numbered,
      InboundState start,
      RetryEdgeFixture.Conclusion conclusion,
      List<String> failures,
      String cell) {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String objectId = "in_pr50_cell_" + System.nanoTime();
    String number = null;
    if (numbered) {
      RetryEdgeFixture.numberWithArchiveDown(harness, objectId);
      number = RetryEdgeFixture.number(harness, objectId);
    } else {
      harness.source().with(TestInvoices.finalised(objectId));
    }
    long counter = harness.seriesCounter();
    String eventId = RetryEdgeFixture.receiveFor(harness, conclusion, objectId);
    RetryEdgeFixture.moveTo(harness, eventId, start);
    int attemptsBefore = RetryEdgeFixture.row(harness, eventId).attempts();

    RetryEdgeFixture.run(harness, conclusion, eventId);

    InboundEvent row = RetryEdgeFixture.row(harness, eventId);
    if (row.attempts() <= attemptsBefore) {
      failures.add(cell + ": attempt not counted");
    }
    if (row.nextAttempt().isPresent() && !row.nextAttempt().get().isAfter(harness.now())) {
      failures.add(cell + ": due again at once (a sweep tick would loop)");
    }
    if (RetryEdgeFixture.ACCESS_REFUSED.equals(row.lastCode()) && row.nextAttempt().isPresent()) {
      failures.add(cell + ": a refused key is retried");
    }
    boolean retryable =
        conclusion == RetryEdgeFixture.Conclusion.FAILED_FETCH
            || (numbered && conclusion.retriedWhenMapped());
    if (retryable
        && (row.state() == InboundState.FAILED_FETCH || row.state() == InboundState.FAILED_ISSUANCE)
        && row.nextAttempt().isEmpty()) {
      failures.add(cell + ": a retryable failure recorded no backoff");
    }
    if (ErrorCodes.INBOUND_ILLEGAL_TRANSITION.equals(row.lastCode())) {
      failures.add(cell + ": DEI-210 recorded");
    }
    // Section 3: only a row that itself reached FETCHED, MAPPED or FAILED_ISSUANCE may hold the
    // number. A sibling event of a numbered invoice that never got going (RECEIVED, PARKED,
    // REFUSED_*, FAILED_FETCH) carries no number; the seed row does.
    boolean numberCapable =
        start == InboundState.FETCHED
            || start == InboundState.MAPPED
            || start == InboundState.FAILED_ISSUANCE;
    if (numbered) {
      if (numberCapable && RetryEdgeFixture.PRE_FETCHED.contains(row.state())) {
        failures.add(cell + ": a numbered row ended pre-FETCHED in " + row.state());
      }
      if (!number.equals(RetryEdgeFixture.number(harness, objectId))) {
        failures.add(cell + ": the number changed");
      }
    }
    if (harness.seriesCounter() != counter) {
      failures.add(cell + ": a number was taken by a refused run");
    }
  }

  /** QUESTIONS 55 / deviation (e): a reprocess racing the sweeper's run on one numbered row. */
  @Test
  void guard_a_reprocess_racing_the_sweeper_on_one_numbered_row_takes_no_second_number()
      throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    int rows = 8;
    List<String> events = new ArrayList<>();
    List<String> objects = new ArrayList<>();
    List<String> numbers = new ArrayList<>();
    for (int i = 0; i < rows; i++) {
      String objectId = "in_pr50_race_" + i + "_" + System.nanoTime();
      events.add(RetryEdgeFixture.numberWithArchiveDown(harness, objectId));
      objects.add(objectId);
      numbers.add(RetryEdgeFixture.number(harness, objectId));
    }
    long counter = harness.seriesCounter();
    long numbered = harness.numberedRows();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (String eventId : events) {
        CountDownLatch go = new CountDownLatch(1);
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return harness
                      .reprocess()
                      .reprocess(new ReprocessRequest(eventId, "operator-1", "archive back"));
                }));
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return harness.unitOfWork().process(eventId);
                }));
        go.countDown();
      }
      for (Future<?> f : futures) {
        assertThatCode(() -> f.get(WAIT.toSeconds(), TimeUnit.SECONDS)).doesNotThrowAnyException();
      }
    } finally {
      pool.shutdownNow();
    }
    // Whatever lost a race is due later; run what is left once, as the sweeper would.
    for (String eventId : events) {
      if (RetryEdgeFixture.row(harness, eventId).state() != InboundState.COMPLETED) {
        harness.reprocess().reprocess(new ReprocessRequest(eventId, "operator-1", "again"));
      }
    }
    assertThat(harness.seriesCounter()).as("no second number").isEqualTo(counter);
    assertThat(harness.numberedRows()).isEqualTo(numbered);
    for (int i = 0; i < rows; i++) {
      assertThat(RetryEdgeFixture.row(harness, events.get(i)).state())
          .isEqualTo(InboundState.COMPLETED);
      assertThat(RetryEdgeFixture.number(harness, objects.get(i))).isEqualTo(numbers.get(i));
      assertThat(harness.issuance(objects.get(i)).orElseThrow().state())
          .isEqualTo(IssuanceState.ISSUED);
    }
  }
}
