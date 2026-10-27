package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.application.RetryEdgeFixture.Conclusion;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The sweeper's edge table (D-PR45-B1, design section 4), cell by cell, through {@code process}.
 *
 * <p>Rows: every {@link InboundState} a row can hold when a run starts, {@code FETCHED} twice (with
 * and without a number for its invoice, D-SRE-4). Columns: the five conclusions a run reaches
 * before {@code FETCHED}, and the revoked key. Each cell asserts: no exception; the written state
 * is an edge from the start or the start itself; the state, the code and the next attempt match the
 * table; and a start that may hold a number never ends in a pre-{@code FETCHED} state.
 *
 * <p>The start list is built from {@code InboundState.values()}: a state with no row here fails the
 * suite, so a new state (QUESTIONS 29's {@code EXPIRED}) gets its row in the PR that adds it.
 */
class SweeperRetryEdgeTableTest {

  /** Which start states may hold a number (design section 3); the others never do. */
  private static final Map<InboundState, Holds> STARTS =
      Map.ofEntries(
          Map.entry(InboundState.RECEIVED, Holds.NEVER),
          Map.entry(InboundState.FETCHED, Holds.EITHER),
          Map.entry(InboundState.MAPPED, Holds.ALWAYS),
          Map.entry(InboundState.PARKED, Holds.NEVER),
          Map.entry(InboundState.FAILED_FETCH, Holds.NEVER),
          Map.entry(InboundState.FAILED_ISSUANCE, Holds.ALWAYS),
          Map.entry(InboundState.REFUSED_VERSION_SKEW, Holds.NEVER),
          Map.entry(InboundState.REFUSED_MODE, Holds.NEVER),
          Map.entry(InboundState.REFUSED_ACCOUNT, Holds.NEVER),
          Map.entry(InboundState.COMPLETED, Holds.CONCLUDED),
          Map.entry(InboundState.DROPPED, Holds.CONCLUDED),
          Map.entry(InboundState.FAILED_MAPPING, Holds.CONCLUDED),
          Map.entry(InboundState.FAILED_TOTALS, Holds.CONCLUDED));

  private enum Holds {
    NEVER,
    EITHER,
    ALWAYS,
    /** Returns the stored outcome before any route; not a row of the table. */
    CONCLUDED
  }

  static Stream<Arguments> cells() {
    List<Arguments> cells = new ArrayList<>();
    for (InboundState start : InboundState.values()) {
      Holds holds = STARTS.get(start);
      if (holds == null) {
        throw new IllegalStateException(
            "the edge table has no row for " + start + ": add it (design section 4)");
      }
      for (Conclusion conclusion : Conclusion.values()) {
        switch (holds) {
          case NEVER, CONCLUDED -> cells.add(Arguments.of(start, conclusion, false));
          case ALWAYS -> cells.add(Arguments.of(start, conclusion, true));
          case EITHER -> {
            cells.add(Arguments.of(start, conclusion, false));
            cells.add(Arguments.of(start, conclusion, true));
          }
        }
      }
    }
    return cells.stream();
  }

  @ParameterizedTest(name = "{0} x {1}, numbered: {2}")
  @MethodSource("cells")
  void every_cell_of_the_edge_table_is_recorded_as_the_table_says(
      InboundState start, Conclusion conclusion, boolean numbered) {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String objectId = "in_sre_" + start.name().toLowerCase() + "_" + conclusion.ordinal();
    String number = null;
    if (numbered) {
      RetryEdgeFixture.numberWithArchiveDown(harness, objectId);
      number = RetryEdgeFixture.number(harness, objectId);
    }
    String eventId = RetryEdgeFixture.receiveFor(harness, conclusion, objectId);
    RetryEdgeFixture.moveTo(harness, eventId, start);
    InboundEvent before = RetryEdgeFixture.row(harness, eventId);

    AtomicReference<IssuanceUnitOfWork.Outcome> outcome = new AtomicReference<>();
    assertThatCode(() -> outcome.set(RetryEdgeFixture.run(harness, conclusion, eventId)))
        .as("a pre-FETCHED conclusion is recorded, never DEI-210")
        .doesNotThrowAnyException();
    InboundEvent after = RetryEdgeFixture.row(harness, eventId);

    if (STARTS.get(start) == Holds.CONCLUDED) {
      assertThat(after.state()).as("a concluded row is answered, not run").isEqualTo(start);
      assertThat(after.lastCode()).isEqualTo(before.lastCode());
      return;
    }
    assertThat(after.state() == start || start.allowedNext().contains(after.state()))
        .as("%s -> %s is a declared edge", start, after.state())
        .isTrue();

    boolean mapped = numbered;
    InboundState expected = mapped ? InboundState.FAILED_ISSUANCE : conclusion.target;
    assertThat(after.state()).isEqualTo(expected);
    assertThat(after.lastCode()).isEqualTo(conclusion.code);
    assertThat(outcome.get().code()).isEqualTo(conclusion.code);
    boolean retried = mapped ? conclusion.retriedWhenMapped() : conclusion.retriedWhenUnmapped();
    assertThat(after.nextAttempt().isPresent())
        .as("a next attempt is scheduled for %s x %s: %s", start, conclusion, retried)
        .isEqualTo(retried);
    if (numbered) {
      assertThat(RetryEdgeFixture.PRE_FETCHED)
          .as("a start holding a number never ends before FETCHED")
          .doesNotContain(after.state());
      assertThat(RetryEdgeFixture.number(harness, objectId)).isEqualTo(number);
    }
  }

  /** The post-fetch mode check is the same column as the route's (design section 2). */
  @Test
  void a_numbered_mapped_row_whose_fetched_invoice_is_test_mode_keeps_its_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String seed = RetryEdgeFixture.numberWithArchiveDown(harness, "in_sre_post_fetch_mode");
    harness.inbound().transition(seed, InboundState.MAPPED, "", harness.now(), null);
    harness.source().with(TestInvoices.testMode("in_sre_post_fetch_mode"));

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(seed);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.MODE_MISMATCH);
    assertThat(RetryEdgeFixture.row(harness, seed).nextAttempt()).isPresent();
  }

  @Test
  void a_parked_row_whose_fetched_invoice_is_test_mode_is_refused() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.testMode("in_sre_parked_mode"));
    String eventId = harness.receive("invoice.finalized", "in_sre_parked_mode");
    RetryEdgeFixture.moveTo(harness, eventId, InboundState.PARKED);

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.REFUSED_MODE);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.MODE_MISMATCH);
  }

  /**
   * D-PR50-CI-1. The table leaves about a hundred due rows in the suite's shared database, all
   * received at the harness's start time. A row concluded later with a backoff must still be seen
   * by the harness's due helper, whatever ran before it.
   */
  @Test
  void a_row_with_a_backoff_is_seen_as_due_behind_a_full_page_of_older_due_rows() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    for (int i = 0; i < 150; i++) {
      harness.receive("invoice.finalized", "in_sre_crowd_" + System.nanoTime() + "_" + i);
    }
    harness.moveClockForward(java.time.Duration.ofMinutes(1));
    String eventId = harness.receive("invoice.finalized", "in_sre_behind_" + System.nanoTime());
    harness
        .source()
        .breakWith(
            new com.housedevinci.einvoice.domain.EInvoiceException(
                ErrorCodes.STRIPE_UNAVAILABLE, "stripe is down"));
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.FAILED_FETCH);
    harness.moveClockForward(java.time.Duration.ofHours(2));

    assertThat(harness.due()).extracting(InboundEvent::eventId).contains(eventId);
  }
}
