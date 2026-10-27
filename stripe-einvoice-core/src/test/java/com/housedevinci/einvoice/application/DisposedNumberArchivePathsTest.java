package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.ARCHIVING_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_NO_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static com.housedevinci.einvoice.application.DisposedArchiveFixture.disposedWarnings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The sweep's fifth check (D-RTR-4, burned-number-archive-orphan design, section 6 with the review
 * items): an archive object at the key of a burned or voided number. One test per path O1 to O10;
 * O3 needs the D-RTR-5 burn and is tested with it; O11 is the ArchUnit rule in {@code
 * ArchitectureTest}.
 *
 * <p>The object is put into the archive <b>after</b> the burn or the void, to model the PUT that
 * timed out on the client and completed on the store later.
 */
class DisposedNumberArchivePathsTest {

  static final ReconciliationSweep.Settings SETTINGS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);

  /** A burned row with its key, and the first bytes, which are not in the archive. */
  record Burned(Row row, byte[] firstBytes) {

    IssuanceTestHarness harness() {
      return row.harness();
    }

    Issuance now() {
      return row.now();
    }

    String key() {
      return row.before().archiveKey();
    }

    void land() {
      harness().archive().putDirectly(key(), firstBytes.clone());
    }
  }

  /**
   * The row as the section 1 example leaves it. The fixture's PUT lands behind a timeout; the
   * object is taken away again, so the resumed read finds nothing, and the backdated re-render is
   * refused: the number burns with its first hash and key kept.
   */
  static Burned burned(Shape withObject, String invoiceId) {
    Row row = prepare(withObject, invoiceId);
    String key = row.before().archiveKey();
    byte[] first = row.harness().archive().get(new ArchiveKey(key)).orElseThrow();
    row.harness().archive().forget(key);
    row.retryBackdated();
    Issuance after = row.now();
    assertThat(after.state()).as("burned").isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(after.archiveKey()).as("key kept").isEqualTo(key);
    assertThat(row.harness().archive().size()).as("nothing archived").isZero();
    return new Burned(row, first);
  }

  static List<String> codesFor(IssuanceTestHarness harness, String subject) {
    return harness.openFindings().stream()
        .filter(f -> f.subjectId().equals(subject))
        .map(ComplianceFinding::code)
        .toList();
  }

  static ReconciliationSweep.Result sweep(IssuanceTestHarness harness) {
    harness.moveClockForward(Duration.ofHours(1));
    return harness.sweep(SETTINGS).sweep();
  }

  // ------------------------------------------------------------------ O1

  @Test
  void an_object_landing_after_a_burn_from_failed_archive_is_reported() {
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_o1");
    b.land();
    AtomicReference<ReconciliationSweep.Result> result = new AtomicReference<>();

    List<String> warnings = disposedWarnings(() -> result.set(sweep(b.harness())));

    assertThat(result.get().disposedWithObject()).isEqualTo(1);
    assertThat(result.get().archiveDrift()).isZero();
    assertThat(result.get().orphanObjects()).isZero();
    assertThat(result.get().total()).isEqualTo(2); // DEI-279 (direction 1) and DEI-281
    // Subject: the Stripe invoice id, like DEI-273, DEI-275 and DEI-279 (review item 5).
    assertThat(codesFor(b.harness(), "in_o1"))
        .containsExactlyInAnyOrder(
            ErrorCodes.RECON_BURNED_NO_DOCUMENT, ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
    assertThat(warnings)
        .singleElement()
        .satisfies(
            line ->
                assertThat(line)
                    .contains(b.now().legalNumber().value())
                    .contains("FAILED_VALIDATION")
                    .contains(b.key())
                    .contains("hash matches the row"));
    // Alerts, never repairs: the object stays, the row stays burned.
    assertThat(b.harness().archive().size()).isEqualTo(1);
    assertThat(b.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
  }

  @Test
  void the_finding_repeats_while_the_row_is_among_the_newest_numbers() {
    // Review item 1: the finding stays until 500 newer numbers push the row out, not two days.
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_o1_repeat");
    b.land();

    sweep(b.harness());
    b.harness().moveClockForward(Duration.ofDays(60));
    ReconciliationSweep.Result later = b.harness().sweep(SETTINGS).sweep();

    assertThat(later.disposedWithObject()).isEqualTo(1);
    assertThat(b.harness().findingRows("in_o1_repeat")).isEqualTo(2); // 279 and 281, not doubled
  }

  // ------------------------------------------------------------------ O2

  @Test
  void an_object_landing_after_a_burn_from_archiving_is_reported() {
    Burned b = burned(ARCHIVING_WITH_OBJECT, "in_o2");
    b.land();

    ReconciliationSweep.Result result = sweep(b.harness());

    assertThat(result.disposedWithObject()).isEqualTo(1);
    assertThat(codesFor(b.harness(), "in_o2"))
        .contains(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
  }

  // ------------------------------------------------------------------ O4

  @Test
  void an_object_at_a_voided_number_is_reported() {
    Row row = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_o4");
    IssuanceTestHarness harness = row.harness();
    harness
        .store()
        .voidUnused(new VoidRequest(harness.sellerId(), Mode.LIVE, "in_o4", "ops void", ""));
    assertThat(row.now().state()).isEqualTo(IssuanceState.VOID_UNUSED);
    assertThat(row.now().archiveKey()).isEqualTo(row.before().archiveKey());

    ReconciliationSweep.Result result = sweep(harness);

    assertThat(result.disposedWithObject()).isEqualTo(1);
    assertThat(result.voidedNoDocument()).isEqualTo(1);
    assertThat(codesFor(harness, "in_o4"))
        .containsExactlyInAnyOrder(
            ErrorCodes.RECON_VOIDED_NO_DOCUMENT, ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
  }

  // ------------------------------------------------------------------ O5

  @Test
  void an_object_with_other_bytes_at_a_disposed_key_is_reported() {
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_o5");
    b.harness()
        .archive()
        .putDirectly(b.key(), "<Invoice>somebody else</Invoice>".getBytes(StandardCharsets.UTF_8));
    AtomicReference<ReconciliationSweep.Result> result = new AtomicReference<>();

    List<String> warnings = disposedWarnings(() -> result.set(sweep(b.harness())));

    // Review item 3: presence and other bytes are two facts with two operator actions.
    assertThat(result.get().disposedWithObject()).isEqualTo(1);
    assertThat(result.get().archiveDrift()).isEqualTo(1);
    assertThat(codesFor(b.harness(), "in_o5"))
        .contains(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER, ErrorCodes.RECON_ARCHIVE_DRIFT);
    assertThat(warnings)
        .singleElement()
        .satisfies(line -> assertThat(line).contains("hash differs").doesNotContain("<"));
  }

  // ------------------------------------------------------------------ O6

  @Test
  void a_disposed_row_without_a_key_is_not_looked_up() {
    IssuanceTestHarness harness = IssuanceTestHarness.createWith(TestValidators.failing("BR-01"));
    harness.source().with(TestInvoices.finalised("in_o6"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_o6"));
    Issuance row = harness.issuance("in_o6").orElseThrow();
    assertThat(row.state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(row.archiveObjectKey()).isEmpty();
    DisposedArchiveFixture.Counting counting =
        new DisposedArchiveFixture.Counting(harness.archive());
    harness.moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = harness.sweepWith(SETTINGS, counting).sweep();

    assertThat(counting.allLookups()).isZero();
    assertThat(result.disposedWithObject()).isZero();
    assertThat(result.disposedUnverified()).isZero();
  }

  // ------------------------------------------------------------------ O7

  @Test
  void a_read_failure_on_a_disposed_key_is_a_finding() {
    for (RuntimeException failure :
        List.<RuntimeException>of(
            new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "503 on HEAD"),
            new IllegalStateException("a host store's own bug"))) {
      Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_o7");
      b.land();
      DisposedArchiveFixture.Counting failing =
          new DisposedArchiveFixture.Counting(b.harness().archive()).failingOn(b.key(), failure);
      b.harness().moveClockForward(Duration.ofHours(1));

      ReconciliationSweep.Result result = b.harness().sweepWith(SETTINGS, failing).sweep();

      // Review item 2: never counted clean. A failure counted nowhere makes total() lie.
      assertThat(result.disposedUnverified()).as(failure.toString()).isEqualTo(1);
      assertThat(result.disposedWithObject()).as(failure.toString()).isZero();
      assertThat(result.total()).as(failure.toString()).isEqualTo(2); // DEI-279 and DEI-241
      assertThat(codesFor(b.harness(), "in_o7"))
          .as(failure.toString())
          .containsExactlyInAnyOrder(
              ErrorCodes.RECON_BURNED_NO_DOCUMENT, ErrorCodes.ARCHIVE_UNAVAILABLE);
    }
  }

  @Test
  void one_read_per_disposed_key() {
    // D-PR52-2: the S3 store has no fast existence check (the port default reads the bytes), so the
    // check reads each disposed key once and decides on what came back. This reverses item 4 of
    // the design review, which assumed a HEAD.
    Burned found = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_one_read_found");
    found.land();
    DisposedArchiveFixture.Counting counting =
        new DisposedArchiveFixture.Counting(found.harness().archive());
    found.harness().moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = found.harness().sweepWith(SETTINGS, counting).sweep();

    assertThat(result.disposedWithObject()).isEqualTo(1);
    assertThat(counting.getCalls(found.key())).isEqualTo(1);
    assertThat(counting.existsCalls(found.key())).isZero();

    Burned absent = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_one_read_absent");
    DisposedArchiveFixture.Counting quiet =
        new DisposedArchiveFixture.Counting(absent.harness().archive());
    absent.harness().moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result none = absent.harness().sweepWith(SETTINGS, quiet).sweep();

    assertThat(none.disposedWithObject()).isZero();
    assertThat(none.disposedUnverified()).isZero();
    assertThat(quiet.getCalls(absent.key())).isEqualTo(1);
    assertThat(quiet.existsCalls(absent.key())).isZero();
  }

  // ------------------------------------------------------------------ O8

  @Test
  void a_disposed_object_is_reported_once() {
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_o8");
    b.land();

    ReconciliationSweep.Result result = sweep(b.harness());

    assertThat(result.orphanObjects()).isZero();
    assertThat(codesFor(b.harness(), b.key())).doesNotContain(ErrorCodes.RECON_ORPHAN_OBJECT);
    assertThat(result.disposedWithObject()).isEqualTo(1);
  }

  // ------------------------------------------------------------------ O9

  @Test
  void issued_and_open_rows_are_not_checked_twice() {
    Row open = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_o9_open");
    IssuanceTestHarness harness = open.harness();
    harness.source().with(TestInvoices.finalised("in_o9_issued"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_o9_issued"));
    String issuedKey = harness.issuance("in_o9_issued").orElseThrow().archiveKey();
    DisposedArchiveFixture.Counting counting =
        new DisposedArchiveFixture.Counting(harness.archive());
    harness.moveClockForward(Duration.ofHours(1));

    harness.sweepWith(SETTINGS, counting).sweep();

    assertThat(counting.lookups(issuedKey)).as("direction 2 only").isEqualTo(1);
    assertThat(counting.lookups(open.before().archiveKey())).as("open row").isZero();
  }

  // ------------------------------------------------------------------ O10

  /** A number left open with its key recorded, then voided by the operator: VOID_UNUSED, keyed. */
  static String voidedWithKey(IssuanceTestHarness harness, String invoiceId) {
    RetryEdgeFixture.numberWithArchiveDown(harness, invoiceId);
    harness
        .store()
        .voidUnused(new VoidRequest(harness.sellerId(), Mode.LIVE, invoiceId, "ops void", ""));
    Issuance row = harness.issuance(invoiceId).orElseThrow();
    assertThat(row.state()).isEqualTo(IssuanceState.VOID_UNUSED);
    assertThat(row.archiveObjectKey()).isPresent();
    return row.archiveKey();
  }

  @Test
  void the_newest_page_of_disposed_rows_is_looked_up_and_a_full_page_warns() {
    // D-PR52-1: the bound is a page of disposed rows with a key, newest number first, not a page
    // of all rows. Newer issued numbers do not push a disposed row out; only newer disposed ones
    // do.
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String oldest = voidedWithKey(harness, "in_o10_old_" + System.nanoTime());
    harness.moveClockForward(Duration.ofMinutes(5));
    String middle = voidedWithKey(harness, "in_o10_mid_" + System.nanoTime());
    harness.moveClockForward(Duration.ofMinutes(5));
    String newest = voidedWithKey(harness, "in_o10_new_" + System.nanoTime());
    for (int i = 0; i < 3; i++) {
      String fine = "in_o10_issued_" + i + "_" + System.nanoTime();
      harness.source().with(TestInvoices.finalised(fine));
      harness.unitOfWork().process(harness.receive("invoice.finalized", fine));
    }
    DisposedArchiveFixture.Counting counting =
        new DisposedArchiveFixture.Counting(harness.archive());
    harness.moveClockForward(Duration.ofHours(1));
    ReconciliationSweep.Settings pageOfTwo =
        new ReconciliationSweep.Settings(
            Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 2);

    String log = DisposedArchiveFixture.log(() -> harness.sweepWith(pageOfTwo, counting).sweep());

    assertThat(counting.lookups(newest)).isEqualTo(1);
    assertThat(counting.lookups(middle)).isEqualTo(1);
    assertThat(counting.lookups(oldest)).as("past the page of disposed rows").isZero();
    assertThat(log).contains("disposed-number check read").contains("its page limit");
  }

  @Test
  void disposed_rows_with_a_key_are_read_by_seller_and_mode_newest_first_and_bounded() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    IssuanceTestHarness other = IssuanceTestHarness.create();
    String first = "in_disp_first_" + System.nanoTime();
    voidedWithKey(harness, first);
    harness.moveClockForward(Duration.ofMinutes(5));
    String second = "in_disp_second_" + System.nanoTime();
    voidedWithKey(harness, second);
    harness.moveClockForward(Duration.ofMinutes(5));
    String open = "in_disp_open_" + System.nanoTime();
    RetryEdgeFixture.numberWithArchiveDown(harness, open);
    String issued = "in_disp_issued_" + System.nanoTime();
    harness.source().with(TestInvoices.finalised(issued));
    harness.unitOfWork().process(harness.receive("invoice.finalized", issued));
    voidedWithKey(other, "in_disp_foreign_" + System.nanoTime());

    assertThat(harness.reader().disposedWithKey(harness.sellerId(), Mode.LIVE, 10))
        .extracting(Issuance::stripeInvoiceId)
        .containsExactly(second, first);
    assertThat(harness.reader().disposedWithKey(harness.sellerId(), Mode.LIVE, 1))
        .extracting(Issuance::stripeInvoiceId)
        .containsExactly(second);
    assertThat(harness.reader().disposedWithKey(harness.sellerId(), Mode.TEST, 10)).isEmpty();
  }

  // ------------------------------------------------------------------ one check among the others

  // D-PR51-1: every check runs on its own, and the rows are read once, before any archive call.

  @Test
  void an_object_at_a_disposed_key_is_named_while_the_stripe_list_fails() {
    // The key is refused on Friday and the late object lands on Saturday: Saturday's pass names it.
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_pr51_list_down");
    b.land();
    b.harness().moveClockForward(Duration.ofHours(1));
    b.harness()
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_ACCESS_REFUSED, "status 401"));

    assertThatThrownBy(() -> b.harness().sweep(SETTINGS).sweep())
        .isInstanceOf(EInvoiceException.class);

    assertThat(codesFor(b.harness(), "in_pr51_list_down"))
        .contains(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
  }

  @Test
  void the_rows_are_read_before_the_first_archive_call() {
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_pr51_order");
    b.land();
    List<String> calls = new CopyOnWriteArrayList<>();
    IssuanceReader reader = DisposedArchiveFixture.recordingReader(b.harness().reader(), calls, "");
    DisposedArchiveFixture.Counting archive =
        new DisposedArchiveFixture.Counting(b.harness().archive(), calls);
    b.harness().moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = b.harness().sweepWith(SETTINGS, reader, archive).sweep();

    assertThat(result.disposedWithObject()).isEqualTo(1);
    int firstArchiveCall =
        calls.indexOf(
            calls.stream()
                .filter(c -> c.startsWith("exists:") || c.startsWith("get:"))
                .findFirst()
                .orElseThrow());
    assertThat(calls.lastIndexOf("inSeries")).isNotNegative().isLessThan(firstArchiveCall);
    assertThat(calls.lastIndexOf("disposedWithKey")).isNotNegative().isLessThan(firstArchiveCall);
    assertThat(calls).contains("get:" + b.key());
  }

  @Test
  void a_failed_series_read_does_not_stop_the_disposed_check() {
    // D-PR52-1: the check reads its own rows, so the series read is not on its path.
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_pr51_read_down");
    b.land();
    List<String> calls = new CopyOnWriteArrayList<>();
    IssuanceReader reader =
        DisposedArchiveFixture.recordingReader(b.harness().reader(), calls, "inSeries");
    b.harness().moveClockForward(Duration.ofHours(1));

    assertThatThrownBy(() -> b.harness().sweepWith(SETTINGS, reader, b.harness().archive()).sweep())
        .isInstanceOf(EInvoiceException.class);

    assertThat(codesFor(b.harness(), "in_pr51_read_down"))
        .contains(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
  }

  @Test
  void a_failed_disposed_read_names_the_check_and_looks_up_nothing() {
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_disp_read_down");
    b.land();
    List<String> calls = new CopyOnWriteArrayList<>();
    IssuanceReader reader =
        DisposedArchiveFixture.recordingReader(b.harness().reader(), calls, "disposedWithKey");
    DisposedArchiveFixture.Counting archive =
        new DisposedArchiveFixture.Counting(b.harness().archive(), calls);
    b.harness().moveClockForward(Duration.ofHours(1));

    String log =
        DisposedArchiveFixture.log(
            () ->
                assertThatThrownBy(() -> b.harness().sweepWith(SETTINGS, reader, archive).sweep())
                    .isInstanceOf(EInvoiceException.class));

    assertThat(archive.lookups(b.key())).isZero();
    assertThat(log).contains("'disposed read'");
    assertThat(codesFor(b.harness(), "in_disp_read_down"))
        .doesNotContain(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
    assertThat(calls)
        .as("the other checks still ran")
        .contains("openOlderThan", "findBySource", "inSeries");
  }

  @Test
  void a_host_store_failure_message_never_reaches_the_log() {
    // D-PR51-1: a message from a host adapter may carry what the log must not; the code or the
    // class name only.
    Burned b = burned(FAILED_ARCHIVE_WITH_OBJECT, "in_pr51_secret");
    b.land();
    DisposedArchiveFixture.Counting failing =
        new DisposedArchiveFixture.Counting(b.harness().archive())
            .failingOn(b.key(), new IllegalStateException("token=sk_live_never_logged"));
    b.harness().moveClockForward(Duration.ofHours(1));
    AtomicReference<ReconciliationSweep.Result> result = new AtomicReference<>();

    String log =
        DisposedArchiveFixture.log(
            () -> result.set(b.harness().sweepWith(SETTINGS, failing).sweep()));

    assertThat(result.get().disposedUnverified()).isEqualTo(1);
    assertThat(log)
        .contains(IllegalStateException.class.getName())
        .contains(b.now().legalNumber().value())
        .doesNotContain("sk_live_never_logged");
  }
}
