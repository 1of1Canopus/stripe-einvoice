package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The orphan walk (D-PR52-3, design "the orphan check examines every key, against every row",
 * Revision 3): one test per listing path of section 5 and per rule of the revisions. Each runs on a
 * real PostgreSQL through the harness, with the archive under the test's control.
 */
class OrphanWalkPathsTest {

  /** The module defaults: page 500, 4 pages a pass, a 7-day cycle alert. */
  private static final ReconciliationSweep.Settings DEFAULTS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(2), Duration.ZERO, Duration.ofHours(6), 10, 500);

  /** One key a page, one page a pass: every pass moves the walk by exactly one key. */
  private static final ReconciliationSweep.Settings ONE_KEY =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 1, 1, Duration.ofDays(7));

  private static final byte[] BYTES = "<Invoice/>".getBytes(StandardCharsets.UTF_8);

  // ------------------------------------------------------------------ helpers

  static void issue(IssuanceTestHarness harness, String invoiceId) {
    harness.source().with(TestInvoices.finalised(invoiceId));
    harness.unitOfWork().process(harness.receive("invoice.finalized", invoiceId));
  }

  static String key(IssuanceTestHarness harness, String invoiceId) {
    return harness.issuance(invoiceId).orElseThrow().archiveKey();
  }

  static String stray(IssuanceTestHarness harness, String number) {
    return "live/" + harness.sellerId() + "/2026/" + number + "-" + "0".repeat(64) + ".xml";
  }

  static List<String> codesFor(IssuanceTestHarness harness, String subject) {
    return harness.openFindings().stream()
        .filter(f -> f.subjectId().equals(subject))
        .map(ComplianceFinding::code)
        .toList();
  }

  static OrphanCursor cursor(IssuanceTestHarness harness) {
    return harness.orphanCursors().read(harness.sellerId(), Mode.LIVE).orElseThrow();
  }

  static ReconciliationSweep sweep(
      IssuanceTestHarness harness,
      ReconciliationSweep.Settings settings,
      IssuanceReader reader,
      ArchiveStore archive,
      FindingStore findings,
      OrphanCursorStore cursors) {
    return new ReconciliationSweep(
        harness.source(),
        reader,
        harness.inbound(),
        archive,
        findings,
        harness.reprocessLedger(),
        cursors,
        Clock.fixed(harness.now(), ZoneOffset.UTC),
        settings,
        harness.unitOfWork().configuration());
  }

  /** A port whose one method throws {@code failure} while {@code when} holds; the rest delegate. */
  @SuppressWarnings("unchecked")
  static <T> T failing(
      Class<T> type,
      T target,
      String method,
      Function<Object[], Boolean> when,
      RuntimeException e) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, m, args) -> {
              if (m.getName().equals(method) && when.apply(args)) {
                throw e;
              }
              try {
                return m.invoke(target, args);
              } catch (InvocationTargetException ite) {
                throw ite.getCause();
              }
            });
  }

  static EInvoiceException down() {
    return new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "down (test)");
  }

  /** An archive whose listing a test rewrites; everything else goes to the harness's store. */
  static class Scripted implements ArchiveStore {
    final ArchiveStore delegate;
    final List<String> listedAfter = new CopyOnWriteArrayList<>();
    String description;

    Scripted(ArchiveStore delegate) {
      this.delegate = delegate;
      this.description = delegate.describe();
    }

    ArchiveListing listing(String prefix, String startAfter, int limit) {
      return delegate.listAfter(prefix, startAfter, limit);
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
      return delegate.get(key);
    }

    @Override
    public ArchiveListing listAfter(String prefix, String startAfter, int limit) {
      listedAfter.add(startAfter);
      return listing(prefix, startAfter, limit);
    }

    @Override
    public String describe() {
      return description;
    }
  }

  // ------------------------------------------------------------------ section 7, T1 to T18

  /** T1, L9. Default settings, a pass in the next year: last year's document is known. */
  @Test
  void an_issued_document_of_last_year_is_never_an_orphan() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_t1");
    harness.moveClockTo(Instant.parse("2027-03-10T09:00:00Z"));

    ReconciliationSweep.Result result = harness.sweep(DEFAULTS).sweep();

    assertThat(result.orphanObjects()).isZero();
    assertThat(result.orphanKeysExamined()).isEqualTo(1);
    assertThat(codesFor(harness, key(harness, "in_t1"))).isEmpty();
  }

  /** T2. Page 1, 3 issued and 1 stray sorting last: the stray within 4 passes, the rest never. */
  @Test
  void an_orphan_past_the_first_page_is_found_within_one_cycle() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_t2_a");
    issue(harness, "in_t2_b");
    issue(harness, "in_t2_c");
    String stray = stray(harness, "INV-2026-999999");
    harness.archive().putDirectly(stray, BYTES);
    harness.moveClockForward(Duration.ofHours(1));

    for (int pass = 1; pass <= 3; pass++) {
      harness.sweep(ONE_KEY).sweep();
      assertThat(codesFor(harness, stray)).as("pass %d", pass).isEmpty();
    }
    harness.sweep(ONE_KEY).sweep();

    assertThat(codesFor(harness, stray)).containsExactly(ErrorCodes.RECON_ORPHAN_OBJECT);
    for (String issued : List.of("in_t2_a", "in_t2_b", "in_t2_c")) {
      assertThat(codesFor(harness, key(harness, issued))).isEmpty();
    }
  }

  /** T3. A new sweep instance continues after the stored key, not from the start. */
  @Test
  void the_cursor_survives_a_restart() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_t3_a");
    issue(harness, "in_t3_b");
    issue(harness, "in_t3_c");
    List<String> keys =
        List.of(key(harness, "in_t3_a"), key(harness, "in_t3_b"), key(harness, "in_t3_c")).stream()
            .sorted(ArchiveListing.UTF8_ORDER)
            .toList();

    harness.sweep(ONE_KEY).sweep();
    assertThat(cursor(harness).position()).isEqualTo(keys.get(0));
    Scripted restarted = new Scripted(harness.archive());
    harness.sweepWith(ONE_KEY, restarted).sweep();

    assertThat(restarted.listedAfter).containsExactly(keys.get(0));
    assertThat(cursor(harness).position()).isEqualTo(keys.get(1));
  }

  /** T5, L6, D-PR51-5 on the in-memory store, which lists by byte prefix like S3. */
  @Test
  void a_neighbour_seller_whose_id_starts_with_ours_is_not_an_orphan_of_ours() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String neighbour =
        "live/" + harness.sellerId() + "2/2026/INV-2026-000001-" + "d".repeat(64) + ".xml";
    harness.archive().putDirectly(neighbour, BYTES);

    ReconciliationSweep.Result result = harness.sweep(DEFAULTS).sweep();

    assertThat(result.orphanObjects()).isZero();
    assertThat(result.orphanKeysExamined()).isZero();
  }

  /** T6, L7. */
  @Test
  void a_test_mode_key_is_never_examined_by_the_live_sweep() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String testKey =
        "test/" + harness.sellerId() + "/2026/INV-2026-000001-" + "e".repeat(64) + ".xml";
    harness.archive().putDirectly(testKey, BYTES);

    ReconciliationSweep.Result result = harness.sweep(DEFAULTS).sweep();

    assertThat(result.orphanKeysExamined()).isZero();
    assertThat(codesFor(harness, testKey)).isEmpty();
  }

  /** T7, L8. The lookup finds the disposed row: DEI-281 by its own check, never DEI-272. */
  @Test
  void a_disposed_key_is_not_an_orphan() {
    Row row = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_t7");
    IssuanceTestHarness harness = row.harness();
    harness
        .store()
        .voidUnused(new VoidRequest(harness.sellerId(), Mode.LIVE, "in_t7", "ops void", ""));
    harness.moveClockForward(Duration.ofHours(1));

    harness.sweep(DEFAULTS).sweep();

    assertThat(codesFor(harness, row.before().archiveKey())).isEmpty();
    assertThat(codesFor(harness, "in_t7")).contains(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER);
  }

  /** T8a, L14. A failed listing moves nothing, and the pass fails. */
  @Test
  void a_failed_listing_does_not_move_the_cursor() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_t8a");
    harness.sweep(DEFAULTS).sweep();
    OrphanCursor before = cursor(harness);
    Scripted broken =
        new Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            throw down();
          }
        };

    assertThatThrownBy(() -> harness.sweepWith(DEFAULTS, broken).sweep())
        .isInstanceOf(EInvoiceException.class);

    assertThat(cursor(harness)).isEqualTo(before);
  }

  /** T8b, L14. A failed lookup moves nothing. */
  @Test
  void a_failed_lookup_does_not_move_the_cursor() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_t8b");
    IssuanceReader reader =
        failing(IssuanceReader.class, harness.store(), "knownKeys", args -> true, down());

    assertThatThrownBy(
            () ->
                sweep(
                        harness,
                        DEFAULTS,
                        reader,
                        harness.archive(),
                        harness.findings(),
                        harness.orphanCursors())
                    .sweep())
        .isInstanceOf(EInvoiceException.class);

    assertThat(cursor(harness).position()).isEmpty();
    assertThat(cursor(harness).revision()).isZero();
  }

  /** T8c, C7. Findings first, then the cursor: a failed finding write moves nothing. */
  @Test
  void a_failed_finding_write_does_not_move_the_cursor() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String stray = stray(harness, "INV-2026-000777");
    harness.archive().putDirectly(stray, BYTES);
    FindingStore findings =
        failing(
            FindingStore.class,
            harness.findings(),
            "record",
            args -> ((ComplianceFinding) args[0]).code().equals(ErrorCodes.RECON_ORPHAN_OBJECT),
            down());

    assertThatThrownBy(
            () ->
                sweep(
                        harness,
                        DEFAULTS,
                        harness.store(),
                        harness.archive(),
                        findings,
                        harness.orphanCursors())
                    .sweep())
        .isInstanceOf(EInvoiceException.class);

    assertThat(cursor(harness).position()).isEmpty();
    harness.sweep(DEFAULTS).sweep();
    assertThat(codesFor(harness, stray)).containsExactly(ErrorCodes.RECON_ORPHAN_OBJECT);
  }

  /** T9. The end of the folder completes the cycle and empties the cursor. */
  @Test
  void a_short_page_completes_the_cycle_and_restarts() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_t9_a");
    issue(harness, "in_t9_b");

    harness.sweep(DEFAULTS).sweep();

    OrphanCursor after = cursor(harness);
    assertThat(after.position()).isEmpty();
    assertThat(after.lastCompleteCycleAt()).isPresent();
    assertThat(after.lastCycleKeys()).isEqualTo(2);
    assertThat(after.keysThisCycle()).isZero();
  }

  /** T12, L10. The capability probe's scratch key is outside every seller folder. */
  @Test
  void the_probes_scratch_key_is_never_reported() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    ArchiveCapabilityProbe.probe(harness.archive(), "t12", false);

    ReconciliationSweep.Result result = harness.sweep(DEFAULTS).sweep();

    assertThat(result.orphanObjects()).isZero();
  }

  /**
   * T13, L11. The marker's reserved key sits at the root, outside the folder. Under the seller
   * folder nothing is reserved: a key ending in the marker's name is an orphan like any other.
   */
  @Test
  void the_marker_key_is_never_reported_and_nothing_is_reserved_under_the_folder() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.archive().putDirectly(".einvoice-archive", BYTES);
    String underFolder = "live/" + harness.sellerId() + "/2026/x.einvoice-archive";
    harness.archive().putDirectly(underFolder, BYTES);

    ReconciliationSweep.Result result = harness.sweep(DEFAULTS).sweep();

    assertThat(result.orphanObjects()).isEqualTo(1);
    assertThat(codesFor(harness, ".einvoice-archive")).isEmpty();
    assertThat(codesFor(harness, underFolder)).containsExactly(ErrorCodes.RECON_ORPHAN_OBJECT);
  }

  /**
   * T14, L12. A document is issued while the pass lists: its row commits before its object is
   * written, and the walk lists before it looks up, so the new key always has its row.
   */
  @Test
  void a_key_recorded_before_the_put_is_never_an_orphan() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    Scripted issuingWhileListing =
        new Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            issue(harness, "in_t14");
            return delegate.listAfter(prefix, startAfter, limit);
          }
        };

    ReconciliationSweep.Result result = harness.sweepWith(DEFAULTS, issuingWhileListing).sweep();

    assertThat(result.orphanKeysExamined()).isEqualTo(1);
    assertThat(result.orphanObjects()).isZero();
  }

  /**
   * T15, L13. A name outside the key alphabet is reported under a bounded subject, never looked up.
   */
  @Test
  void a_key_outside_the_alphabet_is_reported_and_not_parsed() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String odd = "live/" + harness.sellerId() + "/2026/résumé (1).xml";
    harness.archive().putDirectly(odd, BYTES);
    List<Collection<String>> asked = new CopyOnWriteArrayList<>();
    IssuanceReader recording =
        failing(
            IssuanceReader.class,
            harness.store(),
            "knownKeys",
            args -> {
              asked.add(new ArrayList<>((Collection<String>) args[2]));
              return false;
            },
            down());

    ReconciliationSweep.Result result =
        sweep(
                harness,
                DEFAULTS,
                recording,
                harness.archive(),
                harness.findings(),
                harness.orphanCursors())
            .sweep();

    assertThat(result.orphanObjects()).isEqualTo(1);
    assertThat(asked).allSatisfy(keys -> assertThat(keys).doesNotContain(odd));
    assertThat(codesFor(harness, OrphanSubject.of(odd)))
        .containsExactly(ErrorCodes.RECON_ORPHAN_OBJECT);
    assertThat(OrphanSubject.of(odd)).contains("r\\u00e9sum\\u00e9 (1).xml#sha256:");
  }

  /**
   * T16, L15. Another instance moves the walk while this one lists: this one's compare-and-set
   * loses, it records no move of its own, and the walk is where the winner left it.
   */
  @Test
  void two_instances_never_skip_a_page() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_t16_a");
    issue(harness, "in_t16_b");
    issue(harness, "in_t16_c");
    Scripted slow =
        new Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            ArchiveListing mine = delegate.listAfter(prefix, startAfter, limit);
            harness.sweep(ONE_KEY).sweep(); // the other instance, faster
            harness.sweep(ONE_KEY).sweep();
            return mine;
          }
        };
    harness.sweep(ONE_KEY).sweep();
    OrphanCursor afterFirst = cursor(harness);

    ReconciliationSweep.Result loser = harness.sweepWith(ONE_KEY, slow).sweep();

    assertThat(loser.orphanKeysExamined()).isZero();
    // The winner's two passes: the second key, then the third, which ends the folder.
    OrphanCursor now = cursor(harness);
    assertThat(now.revision()).isEqualTo(afterFirst.revision() + 3);
    assertThat(now.lastCycleKeys()).isEqualTo(3);
  }

  /**
   * The first-insert race: a second insert of the same seller and mode is a lost compare-and-set.
   */
  @Test
  void a_second_first_cursor_is_a_lost_compare_and_set() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    OrphanCursor first =
        OrphanCursor.start(harness.sellerId(), Mode.LIVE, "a".repeat(64), harness.now());

    assertThat(harness.orphanCursors().insert(first)).isTrue();
    assertThat(harness.orphanCursors().insert(first)).isFalse();
    assertThat(harness.orphanCursors().replace(first.advancedTo("x", 1), first.advancedTo("y", 1)))
        .isFalse();
  }

  /** T18, C9. No complete cycle within the alert is DEI-283, counted in the total. */
  @Test
  void a_cycle_not_completed_within_the_alert_is_a_finding() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_t18_a");
    issue(harness, "in_t18_b");
    issue(harness, "in_t18_c");
    ReconciliationSweep.Settings hourAlert =
        new ReconciliationSweep.Settings(
            Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 1, 1, Duration.ofHours(1));
    String folder = ArchiveKey.sellerFolder(Mode.LIVE, harness.sellerId());

    ReconciliationSweep.Result first = harness.sweep(hourAlert).sweep();
    assertThat(first.orphanCycleStale()).as("never before the alert").isZero();
    harness.moveClockForward(Duration.ofHours(2));
    ReconciliationSweep.Result late = harness.sweep(hourAlert).sweep();

    assertThat(late.orphanCycleStale()).isEqualTo(1);
    assertThat(late.total()).isGreaterThanOrEqualTo(1);
    assertThat(codesFor(harness, folder)).containsExactly(ErrorCodes.RECON_ORPHAN_CYCLE_STALE);
  }

  /** C9. DEI-283 is its own check: a listing that fails on every pass still raises it. */
  @Test
  void a_listing_that_always_fails_still_raises_the_cycle_alert() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    ReconciliationSweep.Settings hourAlert =
        new ReconciliationSweep.Settings(
            Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 1, 1, Duration.ofHours(1));
    Scripted broken =
        new Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            throw down();
          }
        };
    assertThatThrownBy(() -> harness.sweepWith(hourAlert, broken).sweep())
        .isInstanceOf(EInvoiceException.class);
    harness.moveClockForward(Duration.ofHours(2));

    assertThatThrownBy(() -> harness.sweepWith(hourAlert, broken).sweep())
        .isInstanceOf(EInvoiceException.class);

    assertThat(codesFor(harness, ArchiveKey.sellerFolder(Mode.LIVE, harness.sellerId())))
        .containsExactly(ErrorCodes.RECON_ORPHAN_CYCLE_STALE);
  }

  // ------------------------------------------------------------------ Revision 2 and 3

  /** T22, C5. Out of order: DEI-284, nothing moves, nothing is reported. */
  @Test
  void a_store_that_lists_out_of_order_fails_the_check_and_moves_nothing() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.archive().putDirectly(stray(harness, "INV-2026-000001"), BYTES);
    harness.archive().putDirectly(stray(harness, "INV-2026-000002"), BYTES);
    Scripted reversed =
        new Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            ArchiveListing real = delegate.listAfter(prefix, startAfter, limit);
            return new ArchiveListing(real.keys().reversed(), real.endOfFolder());
          }
        };

    assertThatThrownBy(() -> harness.sweepWith(DEFAULTS, reversed).sweep())
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_LISTING_UNORDERED);

    assertThat(cursor(harness).position()).isEmpty();
    assertThat(cursor(harness).lastCompleteCycleAt()).isEmpty();
    assertThat(harness.openFindings())
        .extracting(ComplianceFinding::code)
        .doesNotContain(ErrorCodes.RECON_ORPHAN_OBJECT);
  }

  /** C5. A key at or before the saved position, or outside the folder, is refused the same way. */
  @Test
  void a_listing_that_repeats_the_position_or_leaves_the_folder_is_refused() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String neighbour = "live/" + harness.sellerId() + "x/2026/a.xml";
    Scripted foreign =
        new Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            return new ArchiveListing(List.of(neighbour), true);
          }
        };
    Scripted empty =
        new Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            return new ArchiveListing(List.of(prefix, prefix), false);
          }
        };

    for (Scripted store : List.of(foreign, empty)) {
      assertThatThrownBy(() -> harness.sweepWith(DEFAULTS, store).sweep())
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.ARCHIVE_LISTING_UNORDERED);
    }
    assertThat(codesFor(harness, neighbour)).isEmpty();
  }

  /** T23, C5. Fewer keys than asked without the store's end answer is not the end. */
  @Test
  void a_short_answer_that_is_not_the_end_does_not_complete_a_cycle() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_t23");
    Scripted shortNotEnd =
        new Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            return new ArchiveListing(delegate.listAfter(prefix, startAfter, limit).keys(), false);
          }
        };

    harness.sweepWith(DEFAULTS, shortNotEnd).sweep();

    OrphanCursor after = cursor(harness);
    assertThat(after.lastCompleteCycleAt()).isEmpty();
    assertThat(after.position()).isEqualTo(key(harness, "in_t23"));
  }

  /** T25, C10. The series read fails; the walk still examines its page. */
  @Test
  void a_failed_series_read_does_not_stop_the_orphan_walk() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String stray = stray(harness, "INV-2026-000555");
    harness.archive().putDirectly(stray, BYTES);
    IssuanceReader reader =
        failing(IssuanceReader.class, harness.store(), "inSeries", args -> true, down());

    assertThatThrownBy(
            () ->
                sweep(
                        harness,
                        DEFAULTS,
                        reader,
                        harness.archive(),
                        harness.findings(),
                        harness.orphanCursors())
                    .sweep())
        .isInstanceOf(EInvoiceException.class);

    assertThat(codesFor(harness, stray)).containsExactly(ErrorCodes.RECON_ORPHAN_OBJECT);
  }

  /** T26, C8. An empty listing while an issued row holds a key: DEI-241, never a complete cycle. */
  @Test
  void an_empty_listing_with_archived_rows_fails_the_check() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_t26");
    harness.archive().forget(key(harness, "in_t26"));

    assertThatThrownBy(() -> harness.sweep(DEFAULTS).sweep())
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE);

    assertThat(cursor(harness).lastCompleteCycleAt()).isEmpty();
  }

  /** C8, Q2. A seller with no documents yet walks anyway, and completes an empty cycle. */
  @Test
  void a_seller_with_no_documents_completes_an_empty_cycle() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();

    ReconciliationSweep.Result result = harness.sweep(DEFAULTS).sweep();

    assertThat(result.orphanKeysExamined()).isZero();
    assertThat(cursor(harness).lastCompleteCycleAt()).isPresent();
  }

  /**
   * L23, C9. Another store, root, bucket or prefix: the partial cycle is dropped, never counted.
   */
  @Test
  void a_changed_store_starts_a_new_cycle_and_never_completes_the_old_one() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_l23_a");
    issue(harness, "in_l23_b");
    harness.sweep(ONE_KEY).sweep();
    assertThat(cursor(harness).position()).isNotEmpty();
    Scripted moved = new Scripted(harness.archive());
    moved.description = "s3 archive in bucket other under tenant-b/";

    harness.sweepWith(ONE_KEY, moved).sweep();

    assertThat(moved.listedAfter).containsExactly("");
    OrphanCursor after = cursor(harness);
    assertThat(after.keysThisCycle()).isEqualTo(1);
    assertThat(after.lastCompleteCycleAt()).isEmpty();
  }

  /**
   * Review probe, C6. A 1,000-byte name cannot be a finding subject as it stands, and must never
   * stop the walk: it is an orphan with no lookup, under a bounded subject, and the walk moves past
   * it to the stray after it.
   */
  @Test
  void probe_a_1000_byte_stray_key_stops_the_walk_for_ever() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String longKey = "live/" + harness.sellerId() + "/2026/" + "x".repeat(1000);
    String after = "live/" + harness.sellerId() + "/2026/y.xml";
    harness.archive().putDirectly(longKey, BYTES);
    harness.archive().putDirectly(after, BYTES);

    harness.sweep(ONE_KEY).sweep();
    harness.sweep(ONE_KEY).sweep();

    assertThat(codesFor(harness, OrphanSubject.of(longKey)))
        .containsExactly(ErrorCodes.RECON_ORPHAN_OBJECT);
    assertThat(codesFor(harness, after)).containsExactly(ErrorCodes.RECON_ORPHAN_OBJECT);
    assertThat(cursor(harness).lastCycleKeys()).isEqualTo(2);
  }

  /** C9. "Cycle n% done" needs the last cycle's count; the first cycle has none. */
  @Test
  void the_progress_of_a_cycle_is_measured_against_the_last_complete_one() {
    OrphanCursor first = OrphanCursor.start("acme", Mode.LIVE, "a".repeat(64), Instant.EPOCH);
    assertThat(first.advancedTo("k", 3).percentDone()).isEmpty();
    OrphanCursor second = first.advancedTo("k", 4).completed(Instant.EPOCH).advancedTo("k", 2);
    assertThat(second.percentDone()).hasValue(50);
    assertThat(second.advancedTo("z", 10).percentDone()).hasValue(99);
  }
}
