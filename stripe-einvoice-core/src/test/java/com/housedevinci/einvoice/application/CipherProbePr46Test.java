package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.RunningLimit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Security review, pull request 46 (QUESTIONS 29, the running-row time limit), pass 1. Each probe
 * named {@code probe_<what goes wrong>} fails on the reviewed head and must flip green in the fix
 * pass. The probe named {@code not_a_finding_*} is an attack that held; it stays as a regression.
 */
class CipherProbePr46Test {

  private final List<IssuanceTestHarness> opened = new ArrayList<>();

  @AfterEach
  void close() {
    opened.forEach(
        h -> com.housedevinci.einvoice.adapter.jdbc.PostgresSupport.close(h.dataSource()));
  }

  private IssuanceTestHarness harness() {
    IssuanceTestHarness h = IssuanceTestHarness.createOnFreshDatabase("cipher_pr46");
    opened.add(h);
    return h;
  }

  private static StrandedInboundSweep sweep(IssuanceTestHarness h) {
    return new StrandedInboundSweep(
        h.inbound(), RunningLimit.DEFAULT, 500, h.unitOfWork().configuration());
  }

  private static boolean dueOrStranded(IssuanceTestHarness h, String eventId) {
    boolean due = h.due().stream().anyMatch(e -> e.eventId().equals(eventId));
    boolean stranded =
        h.inbound().stranded(h.now(), RunningLimit.DEFAULT, 500).stream()
            .anyMatch(e -> e.eventId().equals(eventId));
    return due || stranded;
  }

  // D-PR46-1. QUESTIONS 49, confirmed, and the proposed fix is not enough. A draft parked on day
  // 0 meets one Stripe 503 on day 10. The row is FAILED_FETCH, its clock is still day 0 (the park
  // re-check keeps it), and it is in neither query: never re-checked, never expired, no DEI-282.
  // Measuring the retryable branch from due_since with the running limit (the builder's
  // recommendation) still leaves it out: the clock is ten days old.
  @Test
  void probe_a_parked_row_that_meets_one_stripe_error_is_silent() {
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.draft("in_p46_blink"));
    String eventId = h.receive("invoice.paid", "in_p46_blink");
    assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.PARKED);
    var parkedClock = h.inbound().find(eventId).orElseThrow().dueSinceInstant();

    h.moveClockForward(Duration.ofDays(10));
    h.source().breakWith(new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "503"));
    assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.FAILED_FETCH);
    h.source().heal();
    InboundEvent row = h.inbound().find(eventId).orElseThrow();
    assertThat(row.dueSinceInstant()).as("the clock is still the park's").isEqualTo(parkedClock);

    h.moveClockForward(Duration.ofDays(2));
    assertThat(dueOrStranded(h, eventId))
        .as("a parked row inside its 90-day limit must be re-checked, or expired and reported")
        .isTrue();
  }

  // D-PR46-2. The expiry lands inside a live run, between the run's FETCHED write and its MAPPED
  // write (the preflight). This is the window Q46 opens: a FETCHED row that was ever parked keeps
  // the park's clock. The run's next write then throws DEI-210 out of process(), which PR 45
  // forbids for every write before the claim ("never DEI-210"). No number may be taken.
  @Test
  void probe_an_expiry_inside_a_live_run_throws_out_of_process() {
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.draft("in_p46_race"));
    String eventId = h.receive("invoice.paid", "in_p46_race");
    h.unitOfWork().process(eventId);
    h.moveClockForward(Duration.ofDays(90).minusMinutes(1));
    h.source().with(TestInvoices.finalised("in_p46_race"));

    AtomicInteger expiredInsideTheRun = new AtomicInteger(-1);
    DocumentRenderer real = h.renderer();
    DocumentRenderer racing =
        new DocumentRenderer() {
          @Override
          public RenderedDocument render(DocumentInput input) {
            return real.render(input);
          }

          @Override
          public PreflightReport preflight(MappingInput input) {
            h.moveClockForward(Duration.ofMinutes(2));
            expiredInsideTheRun.set(sweep(h).expire(h.now()));
            return real.preflight(input);
          }

          @Override
          public boolean carriesSupplyDate() {
            return real.carriesSupplyDate();
          }
        };

    assertThatCode(() -> h.unitOfWorkWithRenderer(racing).process(eventId))
        .as("a row moved by the expiry is answered from what is stored, never DEI-210")
        .doesNotThrowAnyException();
    assertThat(expiredInsideTheRun.get()).isEqualTo(1);
    assertThat(h.inbound().find(eventId).orElseThrow().state()).isEqualTo(InboundState.EXPIRED);
    assertThat(h.numberedRows()).isZero();
  }

  // D-PR46-3. The expiry lands after the claim. The run holds a number and waits on the archive
  // for longer than the running limit (a host archive with no timeout). The sweep expires the
  // MAPPED row under the run; the run then issues the invoice and its COMPLETED write throws.
  // End state: a legal document ISSUED, its inbound row EXPIRED with "stuck" DEI-282, and a
  // reprocess that refuses it because it is numbered.
  @Test
  void probe_an_expiry_after_the_claim_expires_an_issuing_row() {
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.finalised("in_p46_claim"));
    String eventId = h.receive("invoice.finalized", "in_p46_claim");
    ArchiveStore real = h.archive();
    AtomicBoolean hung = new AtomicBoolean();
    AtomicInteger expiredInsideTheRun = new AtomicInteger(-1);
    ArchiveStore slow =
        new ArchiveStore() {
          @Override
          public boolean supportsAtomicCreate() {
            return real.supportsAtomicCreate();
          }

          @Override
          public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
            if (hung.compareAndSet(false, true)) {
              h.moveClockForward(Duration.ofHours(73));
              expiredInsideTheRun.set(sweep(h).expire(h.now()));
            }
            return real.putIfAbsent(key, bytes);
          }

          @Override
          public Optional<byte[]> get(ArchiveKey key) {
            return real.get(key);
          }

          @Override
          public boolean exists(ArchiveKey key) {
            return real.exists(key);
          }

          @Override
          public List<String> list(String prefix, int limit) {
            return real.list(prefix, limit);
          }

          @Override
          public String describe() {
            return real.describe();
          }
        };

    assertThatCode(() -> h.unitOfWorkWithArchive(slow).process(eventId))
        .doesNotThrowAnyException();
    assertThat(expiredInsideTheRun.get())
        .as("a row whose run holds a number is never expired under that run")
        .isZero();
    assertThat(h.inbound().find(eventId).orElseThrow().state())
        .isEqualTo(InboundState.COMPLETED);
  }

  // Attack that held: an operator reprocesses the same EXPIRED row twice. The second request
  // finds a running row, writes nothing, and records no second re-open.
  @Test
  void not_a_finding_a_second_reprocess_of_an_expired_row_is_refused() {
    IssuanceTestHarness h = harness();
    h.source().with(TestInvoices.draft("in_p46_twice"));
    String eventId = h.receive("invoice.paid", "in_p46_twice");
    h.unitOfWork().process(eventId);
    h.moveClockForward(Duration.ofDays(91));
    assertThat(sweep(h).expire(h.now())).isEqualTo(1);

    IssuanceReprocess.Result first =
        h.reprocess().reprocess(new ReprocessRequest(eventId, "ops-a", "still a draft"));
    assertThat(first.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(first.state()).isEqualTo(InboundState.PARKED);
    IssuanceReprocess.Result second =
        h.reprocess().reprocess(new ReprocessRequest(eventId, "ops-b", "again"));
    assertThat(second.disposition()).isNotEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(h.reprocessRecords(eventId)).hasSize(2);
  }
}
