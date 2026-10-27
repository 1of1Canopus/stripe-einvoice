package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.AdvisoryLocks;
import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.JdbcInboundRunLock;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.RunningLimit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Security review, pull request 46, pass 2. Attacks on the surfaces the fix pass added: the expiry
 * under the run lock (design row 16), the in-place resume of a numbered {@code EXPIRED} row
 * (QUESTIONS 48), and every edge into and out of {@code EXPIRED}. Each test here held on the
 * reviewed head; they stay as regressions.
 */
class CipherProbePr46Pass2Test {

  private static final long WAIT = 30;
  private static final String RULE = "BR-DE-15";
  private static final Set<InboundState> RUNNING =
      EnumSet.of(
          InboundState.RECEIVED, InboundState.FETCHED, InboundState.MAPPED, InboundState.PARKED);

  private final List<IssuanceTestHarness> opened = new ArrayList<>();

  @AfterEach
  void close() {
    opened.forEach(h -> PostgresSupport.close(h.dataSource()));
  }

  private IssuanceTestHarness track(IssuanceTestHarness h) {
    opened.add(h);
    return h;
  }

  private static StrandedInboundSweep sweep(IssuanceTestHarness h) {
    return new StrandedInboundSweep(
        h.inbound(), RunningLimit.DEFAULT, 500, h.unitOfWork().configuration());
  }

  private static ReprocessRequest request(String eventId) {
    return new ReprocessRequest(eventId, "ops-review", "pass 2 probe");
  }

  /** A renderer that stops at the render, after the number is claimed, until released. */
  static final class HeldRenderer implements DocumentRenderer {
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private final DocumentRenderer delegate;

    HeldRenderer(DocumentRenderer delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean carriesSupplyDate() {
      return delegate.carriesSupplyDate();
    }

    @Override
    public PreflightReport preflight(MappingInput input) {
      return delegate.preflight(input);
    }

    @Override
    public RenderedDocument render(DocumentInput input) {
      reached.countDown();
      try {
        if (!release.await(WAIT, TimeUnit.SECONDS)) {
          throw new IllegalStateException("never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return delegate.render(input);
    }
  }

  private static int terminateRunLock(IssuanceTestHarness h, String objectId) throws SQLException {
    try (Connection c = h.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT pg_catalog.pg_terminate_backend(pid) FROM pg_catalog.pg_locks"
                    + " WHERE locktype = 'advisory' AND objsubid = 2"
                    + " AND classid = CAST(? AS bigint)::oid AND objid = CAST(? AS bigint)::oid")) {
      ps.setLong(1, AdvisoryLocks.RUN_CLASS_ID & 0xFFFFFFFFL);
      ps.setLong(2, JdbcInboundRunLock.runKey(objectId) & 0xFFFFFFFFL);
      int ended = 0;
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ended++;
        }
      }
      return ended;
    }
  }

  // (a) D-PR46-2, lost-lock variant. A run claims INV-...-000001, stops in the render, and its
  // session is ended by the server (DEI-104). The run is no longer live: the expiry takes the free
  // lock and expires the MAPPED row. The old run wakes up and must write nothing; the operator's
  // reprocess then resumes the same number. Never a second number, never a DEI-210 escape.
  @Test
  void an_expiry_after_the_holder_lost_its_lock_leaves_one_resumable_number() throws Exception {
    IssuanceTestHarness h = track(IssuanceTestHarness.createOnFreshDatabase("cipher_pr46p2_lost"));
    h.source().with(TestInvoices.finalised("in_p46p2_lost"));
    String eventId = h.receive("invoice.finalized", "in_p46p2_lost");
    HeldRenderer held = new HeldRenderer(h.renderer());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> run =
          pool.submit(() -> h.unitOfWorkWithSourceAndRenderer(h.source(), held).process(eventId));
      assertThat(held.reached.await(WAIT, TimeUnit.SECONDS)).isTrue();
      String number = RetryEdgeFixture.number(h, "in_p46p2_lost");
      long counter = h.seriesCounter();

      assertThat(terminateRunLock(h, "in_p46p2_lost")).isEqualTo(1);
      h.moveClockForward(Duration.ofHours(73));
      assertThat(sweep(h).expire(h.now()))
          .as("a holder whose lock is gone is not live; its row may expire")
          .isEqualTo(1);
      assertThat(h.inbound().find(eventId).orElseThrow().state()).isEqualTo(InboundState.EXPIRED);

      held.release.countDown();
      IssuanceUnitOfWork.Outcome old = run.get(WAIT, TimeUnit.SECONDS);
      assertThat(old.code()).isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
      assertThat(h.inbound().find(eventId).orElseThrow().state())
          .as("the dead holder writes nothing over the expiry")
          .isEqualTo(InboundState.EXPIRED);
      assertThat(h.archive().size()).isZero();
      assertThat(h.openFindings())
          .anyMatch(
              f ->
                  f.code().equals(ErrorCodes.RECON_STUCK_INBOUND) && f.subjectId().equals(eventId));

      IssuanceReprocess.Result result = h.reprocess().reprocess(request(eventId));
      assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
      assertThat(result.state()).isEqualTo(InboundState.COMPLETED);
      assertThat(result.legalNumber()).isEqualTo(number);
      assertThat(h.numberedRows()).isEqualTo(1);
      assertThat(h.seriesCounter()).isEqualTo(counter);
      assertThat(h.archive().size()).isEqualTo(1);
    } finally {
      held.release.countDown();
      pool.shutdownNow();
    }
  }

  // (d) QUESTIONS 48 under concurrency. One invoice holds an open number. Its own row (MAPPED
  // when it expired) and a second event of the same invoice (expired from RECEIVED, never ran) are
  // both EXPIRED. Six operator calls at once, three per row, plus a worker submission of each row.
  // Exactly one number ever exists, the counter never moves, and one document is archived.
  @Test
  void concurrent_reprocesses_of_numbered_expired_rows_never_claim_a_second_number()
      throws Exception {
    IssuanceTestHarness h =
        track(IssuanceTestHarness.createOnFreshDatabase("cipher_pr46p2_concurrent"));
    String seed = RetryEdgeFixture.numberWithArchiveDown(h, "in_p46p2_conc");
    String number = RetryEdgeFixture.number(h, "in_p46p2_conc");
    h.inbound().transition(seed, InboundState.MAPPED, "", h.now(), null);
    h.inbound()
        .transition(seed, InboundState.EXPIRED, ErrorCodes.INBOUND_CEILING_REACHED, h.now(), null);
    String other = h.receive("invoice.paid", "in_p46p2_conc");
    h.inbound()
        .transition(other, InboundState.EXPIRED, ErrorCodes.INBOUND_CEILING_REACHED, h.now(), null);
    long counter = h.seriesCounter();

    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<Object>> calls = new ArrayList<>();
    try {
      for (String id : List.of(seed, other)) {
        for (int i = 0; i < 3; i++) {
          calls.add(pool.submit(gated(go, () -> h.reprocess().reprocess(request(id)))));
        }
        calls.add(pool.submit(gated(go, () -> h.unitOfWork().process(id))));
      }
      go.countDown();
      int reprocessed = 0;
      for (Future<Object> call : calls) {
        Object answer;
        try {
          answer = call.get(WAIT, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
          assertThat(e.getCause())
              .as("only a full permit pool may refuse")
              .isInstanceOf(EInvoiceException.class);
          assertThat(((EInvoiceException) e.getCause()).code())
              .isEqualTo(ErrorCodes.STORE_UNAVAILABLE);
          continue;
        }
        if (answer instanceof IssuanceReprocess.Result r
            && r.disposition() == IssuanceReprocess.Disposition.REPROCESSED) {
          reprocessed++;
          assertThat(r.legalNumber()).isEqualTo(number);
        }
      }
      assertThat(reprocessed).isGreaterThanOrEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    // Whatever is still EXPIRED is asked once more, one call after the other. The first row to
    // resume issues the number; the other row's invoice is then ISSUED, so it is refused as
    // numbered with the issued number (D-SRE-2: nothing to finish) and keeps its finding.
    int completed = 0;
    for (String id : List.of(seed, other)) {
      if (h.inbound().find(id).orElseThrow().state() == InboundState.EXPIRED) {
        IssuanceReprocess.Result late = h.reprocess().reprocess(request(id));
        if (late.disposition() == IssuanceReprocess.Disposition.REFUSED_NUMBERED) {
          assertThat(late.legalNumber()).isEqualTo(number);
          assertThat(h.inbound().find(id).orElseThrow().state()).isEqualTo(InboundState.EXPIRED);
          continue;
        }
      }
      assertThat(h.inbound().find(id).orElseThrow().state()).isEqualTo(InboundState.COMPLETED);
      completed++;
    }
    assertThat(completed).isGreaterThanOrEqualTo(1);
    // The harm pass 1 named for a false "stuck" report: an operator voids the number. An issued
    // number cannot be voided.
    assertThatThrownBy(
            () ->
                h.store()
                    .voidUnused(
                        new VoidRequest(
                            h.sellerId(), Mode.LIVE, "in_p46p2_conc", "looks stuck", RULE)))
        .isInstanceOf(EInvoiceException.class);
    assertThat(h.numberedRows()).isEqualTo(1);
    assertThat(h.seriesCounter()).isEqualTo(counter);
    assertThat(h.issuance("in_p46p2_conc").orElseThrow().state()).isEqualTo(IssuanceState.ISSUED);
    assertThat(RetryEdgeFixture.number(h, "in_p46p2_conc")).isEqualTo(number);
    assertThat(h.archive().size()).isEqualTo(1);
  }

  private static Callable<Object> gated(CountDownLatch go, Callable<Object> work) {
    return () -> {
      go.await(WAIT, TimeUnit.SECONDS);
      return work.call();
    };
  }

  // (d) A burned number (FAILED_VALIDATION) and then the same number voided (VOID_UNUSED): an
  // EXPIRED row of that invoice, its own or another event's, is refused as numbered. Nothing is
  // re-opened, nothing is recorded, the counter does not move.
  @Test
  void an_expired_row_of_a_burned_or_voided_number_is_refused_and_allocates_nothing() {
    IssuanceTestHarness h = IssuanceTestHarness.createWith(TestValidators.failing(RULE));
    h.source().with(TestInvoices.finalised("in_p46p2_burn"));
    String seed = h.receive("invoice.finalized", "in_p46p2_burn");
    h.unitOfWork().process(seed);
    assertThat(h.issuance("in_p46p2_burn").orElseThrow().state())
        .isEqualTo(IssuanceState.FAILED_VALIDATION);
    long counter = h.seriesCounter();

    String burnedOwn = seed;
    h.inbound().transition(burnedOwn, InboundState.MAPPED, "", h.now(), null);
    h.inbound()
        .transition(
            burnedOwn, InboundState.EXPIRED, ErrorCodes.INBOUND_CEILING_REACHED, h.now(), null);
    String burnedOther = h.receive("invoice.paid", "in_p46p2_burn");
    h.inbound()
        .transition(
            burnedOther, InboundState.EXPIRED, ErrorCodes.INBOUND_CEILING_REACHED, h.now(), null);
    for (String id : List.of(burnedOwn, burnedOther)) {
      assertRefusedNumbered(h, id, counter);
    }

    h.store()
        .voidUnused(
            new VoidRequest(h.sellerId(), Mode.LIVE, "in_p46p2_burn", "never a document", RULE));
    assertThat(h.issuance("in_p46p2_burn").orElseThrow().state())
        .isEqualTo(IssuanceState.VOID_UNUSED);
    String voidedOther = h.receive("invoice.payment_succeeded", "in_p46p2_burn");
    h.inbound()
        .transition(
            voidedOther, InboundState.EXPIRED, ErrorCodes.INBOUND_CEILING_REACHED, h.now(), null);
    for (String id : List.of(burnedOwn, burnedOther, voidedOther)) {
      assertRefusedNumbered(h, id, counter);
    }
  }

  private static void assertRefusedNumbered(IssuanceTestHarness h, String id, long counter) {
    IssuanceReprocess.Result r = h.reprocess().reprocess(request(id));
    assertThat(r.disposition()).as(id).isEqualTo(IssuanceReprocess.Disposition.REFUSED_NUMBERED);
    assertThat(h.inbound().find(id).orElseThrow().state()).isEqualTo(InboundState.EXPIRED);
    assertThat(h.reprocessRecords(id)).isEmpty();
    assertThat(h.seriesCounter()).isEqualTo(counter);
    assertThat(h.numberedRows()).isEqualTo(1);
  }

  // (e) Every edge into EXPIRED at the store: a row in each of the fifteen states, read, then aged
  // past both limits. Only the four running states expire; every other state is untouched by the
  // expiry, called directly with the row as read. And no edge leaves EXPIRED through the store's
  // transition: only a reprocess re-opens it.
  @Test
  void only_the_four_running_states_expire_and_no_transition_leaves_expired() {
    IssuanceTestHarness h = track(IssuanceTestHarness.createOnFreshDatabase("cipher_pr46p2_edges"));
    Map<InboundState, String> rows = new EnumMap<>(InboundState.class);
    for (InboundState start : InboundState.values()) {
      String objectId = "in_p46p2_edge_" + start.name().toLowerCase(java.util.Locale.ROOT);
      String id = h.receive("invoice.finalized", objectId);
      RetryEdgeFixture.moveTo(h, id, start);
      rows.put(start, id);
    }
    h.moveClockForward(Duration.ofDays(91));
    StrandedInboundSweep sweep = sweep(h);
    for (InboundState start : InboundState.values()) {
      InboundEvent read = h.inbound().find(rows.get(start)).orElseThrow();
      if (read.dueSinceInstant().isEmpty()) {
        continue;
      }
      boolean moved =
          h.inbound()
              .expire(
                  read,
                  new com.housedevinci.einvoice.domain.ComplianceFinding(
                      h.sellerId(),
                      Mode.LIVE,
                      ErrorCodes.RECON_STUCK_INBOUND,
                      read.eventId(),
                      h.now(),
                      h.now(),
                      null,
                      null),
                  h.now());
      assertThat(moved).as("%s -> EXPIRED", start).isEqualTo(RUNNING.contains(start));
    }
    assertThat(sweep.expire(h.now())).isZero();
    for (InboundState start : InboundState.values()) {
      InboundState now = h.inbound().find(rows.get(start)).orElseThrow().state();
      assertThat(now)
          .as("%s after the expiry", start)
          .isEqualTo(RUNNING.contains(start) ? InboundState.EXPIRED : start);
    }
    String expired = rows.get(InboundState.EXPIRED);
    for (InboundState next : InboundState.values()) {
      if (next == InboundState.EXPIRED) {
        continue;
      }
      assertThatThrownBy(
              () -> h.inbound().transition(expired, next, "", h.now(), null), "EXPIRED -> " + next)
          .isInstanceOf(EInvoiceException.class);
    }
    assertThat(h.inbound().find(expired).orElseThrow().state()).isEqualTo(InboundState.EXPIRED);
  }
}
