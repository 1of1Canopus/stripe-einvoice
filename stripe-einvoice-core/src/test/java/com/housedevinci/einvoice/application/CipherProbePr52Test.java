package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Security review of PR 52 (fix/stray-archive-object-check), pass 1 on b7a945a. Each probe fails on
 * that head and passes once its finding is fixed.
 *
 * <ul>
 *   <li>D-PR52-1: the disposed-number check reads rows by allocation (series years of the window,
 *       newest page-size counters), while the risk starts at disposal. A number voided after its
 *       series year left the window, or voided when it is already older than one page, is never
 *       looked up.
 *   <li>D-PR52-2: the "cheap question first" is not cheap on the shipped S3 store, which has no
 *       {@code exists} override: an object found is downloaded twice.
 *   <li>D-PR52-3 (on main since 0.1.0): the orphan direction compares the newest page of the
 *       window's rows with the first page, in key order, of the whole seller prefix. Last year's
 *       objects are reported as orphans, and a real orphan past that page is never seen.
 *   <li>D-PR51-5 (reproduced here): the orphan direction lists {@code live/<seller>} without a
 *       trailing slash, so another seller whose id starts with this one is reported under it.
 * </ul>
 */
class CipherProbePr52Test {

  static final ReconciliationSweep.Settings TWO_DAYS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(2), Duration.ZERO, Duration.ofHours(6), 10, 500);

  static List<String> codesFor(IssuanceTestHarness harness, String subject) {
    return harness.openFindings().stream()
        .filter(f -> f.subjectId().equals(subject))
        .map(ComplianceFinding::code)
        .toList();
  }

  static void issue(IssuanceTestHarness harness, String invoiceId) {
    harness.source().with(TestInvoices.finalised(invoiceId));
    harness.unitOfWork().process(harness.receive("invoice.finalized", invoiceId));
  }

  // ------------------------------------------------------------------ D-PR52-1

  /**
   * Design path O4 across a year end. A 2026 number is stuck in FAILED_ARCHIVE over the new year
   * (the case D-PR50-B1 was about); the operator voids it on 10 January 2027 while its PUT is on
   * the store. The 2026 series is no longer read by the pass, so the object is never looked up.
   */
  @Test
  void probe_a_late_object_at_a_number_voided_after_its_series_year_is_not_reported() {
    Row row = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_pr52_year");
    IssuanceTestHarness harness = row.harness();
    harness.moveClockTo(Instant.parse("2027-01-10T09:00:00Z"));
    harness
        .store()
        .voidUnused(new VoidRequest(harness.sellerId(), Mode.LIVE, "in_pr52_year", "ops void", ""));
    assertThat(row.now().state()).isEqualTo(IssuanceState.VOID_UNUSED);
    assertThat(harness.archive().get(new ArchiveKey(row.before().archiveKey()))).isPresent();

    ReconciliationSweep.Result result = harness.sweep(TWO_DAYS).sweep();

    assertThat(result.disposedWithObject()).isEqualTo(1);
    assertThat(codesFor(harness, "in_pr52_year"))
        .contains(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
  }

  /**
   * Design path O4 on a busy series. The stuck number is already older than one page of newer
   * numbers when the operator voids it, so it is never in the rows the check reads, not even once.
   * The O10 residual assumed the row is new when it is disposed; a void is not.
   */
  @Test
  void probe_a_late_object_at_an_old_number_voided_on_a_busy_series_is_not_reported() {
    Row row = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_pr52_busy");
    IssuanceTestHarness harness = row.harness();
    issue(harness, "in_pr52_busy_a");
    issue(harness, "in_pr52_busy_b");
    harness.moveClockForward(Duration.ofHours(1));
    harness
        .store()
        .voidUnused(new VoidRequest(harness.sellerId(), Mode.LIVE, "in_pr52_busy", "ops void", ""));
    ReconciliationSweep.Settings pageOfTwo =
        new ReconciliationSweep.Settings(
            Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 2);

    ReconciliationSweep.Result result = harness.sweep(pageOfTwo).sweep();

    assertThat(result.disposedWithObject()).isEqualTo(1);
    assertThat(codesFor(harness, "in_pr52_busy"))
        .contains(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
  }

  // ------------------------------------------------------------------ D-PR52-2

  /**
   * S3ArchiveStore does not override {@code exists}; the port's default reads the bytes. The check
   * then reads them a second time. A store shaped like it is modelled here: no {@code exists}
   * override, every {@code get} counted.
   */
  @Test
  void probe_a_found_object_is_downloaded_twice_on_a_store_without_a_head() {
    Row row = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_pr52_head");
    IssuanceTestHarness harness = row.harness();
    harness
        .store()
        .voidUnused(new VoidRequest(harness.sellerId(), Mode.LIVE, "in_pr52_head", "ops void", ""));
    String key = row.before().archiveKey();
    NoHeadStore store = new NoHeadStore(harness.archive());
    harness.moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = harness.sweepWith(TWO_DAYS, store).sweep();

    assertThat(result.disposedWithObject()).isEqualTo(1);
    assertThat(store.gets.getOrDefault(key, 0)).as("downloads of the disposed key").isEqualTo(1);
  }

  /** Shaped like S3ArchiveStore: {@code exists} is the port default. */
  static final class NoHeadStore implements ArchiveStore {
    private final ArchiveStore delegate;
    final Map<String, Integer> gets = new ConcurrentHashMap<>();

    NoHeadStore(ArchiveStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean supportsAtomicCreate() {
      return delegate.supportsAtomicCreate();
    }

    @Override
    public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
      return delegate.putIfAbsent(key, bytes);
    }

    @Override
    public Optional<byte[]> get(ArchiveKey key) {
      gets.merge(key.value(), 1, Integer::sum);
      return delegate.get(key);
    }

    @Override
    public List<String> list(String prefix, int limit) {
      return delegate.list(prefix, limit);
    }

    @Override
    public String describe() {
      return "no-head archive (probe)";
    }
  }

  // ------------------------------------------------------------------ D-PR52-3

  /**
   * Default settings, one invoice. Issued in January 2026; the pass of 10 March 2027 reads the 2027
   * series only, but lists the whole seller prefix, so the 2026 document is an "orphan".
   */
  @Test
  void probe_last_years_issued_document_is_reported_as_an_orphan() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_pr52_lastyear");
    String key = harness.issuance("in_pr52_lastyear").orElseThrow().archiveKey();
    assertThat(harness.issuance("in_pr52_lastyear").orElseThrow().state())
        .isEqualTo(IssuanceState.ISSUED);
    harness.moveClockTo(Instant.parse("2027-03-10T09:00:00Z"));

    ReconciliationSweep.Result result = harness.sweep(TWO_DAYS).sweep();

    assertThat(result.orphanObjects()).isZero();
    assertThat(codesFor(harness, key)).doesNotContain(ErrorCodes.RECON_ORPHAN_OBJECT);
  }

  /**
   * One series, one year, more objects than one page. The list page holds the oldest keys and the
   * row page the newest: the oldest issued document is a false orphan, and an object nobody
   * predicted, written after it, is never seen.
   */
  @Test
  void probe_a_real_orphan_past_the_first_list_page_is_never_seen() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_pr52_page_a");
    issue(harness, "in_pr52_page_b");
    String oldest = harness.issuance("in_pr52_page_a").orElseThrow().archiveKey();
    String stray =
        Mode.LIVE.wire()
            + "/"
            + harness.sellerId()
            + "/2026/INV-2026-999999-"
            + "0".repeat(64)
            + ".xml";
    harness.archive().putDirectly(stray, "<Invoice/>".getBytes(StandardCharsets.UTF_8));
    harness.moveClockForward(Duration.ofHours(1));
    ReconciliationSweep.Settings pageOfOne =
        new ReconciliationSweep.Settings(
            Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 1);

    harness.sweep(pageOfOne).sweep();

    assertThat(codesFor(harness, oldest)).doesNotContain(ErrorCodes.RECON_ORPHAN_OBJECT);
    assertThat(codesFor(harness, stray)).contains(ErrorCodes.RECON_ORPHAN_OBJECT);
  }

  // ------------------------------------------------------------------ D-PR51-5

  /**
   * The in-memory store lists by byte prefix, as ListObjectsV2 does without a delimiter. Seller
   * {@code <id>} lists {@code live/<id>} and so also reads {@code live/<id>x/...}: another seller's
   * legal number and document hash become a finding of this seller.
   */
  @Test
  void probe_a_seller_reports_a_longer_named_sellers_object_as_its_orphan() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String other =
        Mode.LIVE.wire()
            + "/"
            + harness.sellerId()
            + "x/2026/INV-2026-000001-"
            + "a".repeat(64)
            + ".xml";
    harness.archive().putDirectly(other, "<Invoice/>".getBytes(StandardCharsets.UTF_8));
    harness.moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = harness.sweep(TWO_DAYS).sweep();

    assertThat(result.orphanObjects()).isZero();
    assertThat(codesFor(harness, other)).isEmpty();
  }
}
