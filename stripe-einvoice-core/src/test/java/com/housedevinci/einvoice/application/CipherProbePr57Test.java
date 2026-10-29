package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.OrphanWalkPathsTest.codesFor;
import static com.housedevinci.einvoice.application.OrphanWalkPathsTest.cursor;
import static com.housedevinci.einvoice.application.OrphanWalkPathsTest.down;
import static com.housedevinci.einvoice.application.OrphanWalkPathsTest.issue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.housedevinci.einvoice.adapter.file.FilesystemArchiveStore;
import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Security review of PR 57 (fix/orphan-check-window), pass 1 on 1ef0995. Each probe fails on that
 * head and passes once its finding is fixed, except D-PR57-7 and D-PR57-8: those pass on the head
 * and fail on the mutation no suite test catches. Ids D-PR57-1 to D-PR57-8 (D-PR57-5 is in
 * CipherProbePr57S3Test, the starter half of D-PR57-6 in CipherProbePr57StarterTest).
 */
class CipherProbePr57Test {

  private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

  /** One key a page, one page a pass, a one-hour cycle alert. */
  private static final ReconciliationSweep.Settings ONE_KEY_HOUR_ALERT =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 1, 1, Duration.ofHours(1));

  @TempDir Path root;

  private FilesystemArchiveStore store() {
    return new FilesystemArchiveStore(root, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static boolean runningAsRoot() {
    return "root".equals(System.getProperty("user.name"));
  }

  private static void sh(Path dir, String script) throws Exception {
    Process p = new ProcessBuilder("/bin/sh", "-c", script).directory(dir.toFile()).start();
    assertThat(p.waitFor()).as("shell step: %s", script).isZero();
  }

  // ---------------------------------------------------------------- D-PR57-1 (MEDIUM)

  /**
   * D-PR57-1. {@code OrphanCursor.completed(now)} sets {@code cycleStartedAt} and {@code
   * lastCompleteCycleAt} to the same instant, and {@code stale} treats a completion at the start
   * instant as "completed since the start". So once one cycle has completed, DEI-283 never fires
   * again on that store: a listing that fails on every pass, or a cycle that takes months, is
   * never raised.
   */
  @Test
  void probe_the_cycle_alert_never_fires_after_the_first_complete_cycle() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_pr57_alert");
    harness.sweep(ONE_KEY_HOUR_ALERT).sweep();
    assertThat(cursor(harness).lastCompleteCycleAt()).as("precondition: one cycle done").isPresent();

    OrphanWalkPathsTest.Scripted broken =
        new OrphanWalkPathsTest.Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            throw down();
          }
        };
    harness.moveClockForward(Duration.ofHours(2));
    assertThatThrownBy(() -> harness.sweepWith(ONE_KEY_HOUR_ALERT, broken).sweep())
        .isInstanceOf(EInvoiceException.class);
    harness.moveClockForward(Duration.ofHours(2));
    assertThatThrownBy(() -> harness.sweepWith(ONE_KEY_HOUR_ALERT, broken).sweep())
        .isInstanceOf(EInvoiceException.class);

    assertThat(codesFor(harness, ArchiveKey.sellerFolder(Mode.LIVE, harness.sellerId())))
        .as("four hours with no complete cycle, alert one hour")
        .containsExactly(ErrorCodes.RECON_ORPHAN_CYCLE_STALE);
  }

  /** D-PR57-1, the rule itself on the record. */
  @Test
  void probe_a_cursor_completed_once_is_never_stale_again() {
    OrphanCursor done =
        OrphanCursor.start("acme", Mode.LIVE, "0".repeat(64), NOW)
            .advancedTo("live/acme/2026/a.xml", 1)
            .completed(NOW);

    assertThat(done.stale(NOW.plus(Duration.ofDays(30)), Duration.ofDays(7))).isTrue();
  }

  // ---------------------------------------------------------------- D-PR57-2 (MEDIUM)

  /**
   * D-PR57-2. One folder the service cannot read, anywhere under the seller folder, makes {@code
   * visitFileFailed} throw: the whole listing fails on every pass ({@code DEI-241}), and no key of
   * the seller is examined again until someone finds the folder. The message carries no path by
   * design, so the operator sees "the document archive is unavailable" while every read works.
   * The D1 invariant, applied to entries: what a volume writer creates can produce a reported
   * orphan, never a stopped walk.
   */
  @Test
  void probe_an_unreadable_folder_stops_the_filesystem_walk_for_ever() throws Exception {
    assumeThat(runningAsRoot()).as("root reads every folder").isFalse();
    Files.createDirectories(root.resolve("live/acme/2026"));
    Files.write(root.resolve("live/acme/2026/INV-a.xml"), new byte[] {1});
    Path locked = Files.createDirectories(root.resolve("live/acme/zz-locked"));
    Files.write(locked.resolve("hidden.xml"), new byte[] {1});
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    try {
      ArchiveListing listing = store().listAfter("live/acme/", "", 10);

      assertThat(listing.keys()).contains("live/acme/2026/INV-a.xml");
      assertThat(listing.keys())
          .as("the unreadable folder is itself reported")
          .anyMatch(k -> k.startsWith("live/acme/zz-locked") && !ArchiveKey.isValid(k));
      assertThat(listing.endOfFolder()).isTrue();
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }

  /**
   * D-PR57-2, second door (QUESTIONS 63, refused as a residual). A tree deeper than the path
   * limit, built with relative mkdir, makes the walk's absolute stat fail with "File name too
   * long": the same throw, the same stopped walk. The same correction closes it.
   */
  @Test
  void probe_a_tree_deeper_than_the_path_limit_stops_the_filesystem_walk_for_ever()
      throws Exception {
    Files.createDirectories(root.resolve("live/acme/2026"));
    Files.write(root.resolve("live/acme/2026/INV-a.xml"), new byte[] {1});
    String level = "d".repeat(100);
    sh(
        root.resolve("live/acme"),
        "i=0; while [ $i -lt 50 ]; do mkdir " + level + " && cd -P " + level + " || exit 1;"
            + " i=$((i+1)); done; : > deep.xml");
    try {
      ArchiveListing listing = store().listAfter("live/acme/", "", 10);

      assertThat(listing.keys()).contains("live/acme/2026/INV-a.xml");
      assertThat(listing.keys())
          .as("the part of the tree past the path limit is reported, not fatal")
          .anyMatch(k -> k.startsWith("live/acme/" + level) && !ArchiveKey.isValid(k));
    } finally {
      // Removed bottom-up with relative names, which works past the path limit.
      sh(
          root.resolve("live/acme"),
          "i=0; while [ $i -lt 50 ]; do cd -P " + level + " || exit 1; i=$((i+1)); done;"
              + " rm -f deep.xml; i=0; while [ $i -lt 50 ]; do cd -P .. && rmdir " + level
              + " || exit 1; i=$((i+1)); done");
    }
  }

  // ---------------------------------------------------------------- D-PR57-3 (MEDIUM)

  /**
   * D-PR57-3. A name that does not decode becomes U+FFFD per byte: three UTF-8 bytes for one. Six
   * 255-byte names of {@code \xff} are a 1.5 KB path, well under PATH_MAX, but a 4.6 KB key. The
   * store emits it as {@code <decoded>#inode:...}; the order guard refuses any key over 4,096
   * bytes with {@code DEI-284}, on every pass. A name a writer chose reaches the guard, which D1
   * says can never happen. Linux only: macOS refuses such names.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void probe_an_undecodable_name_longer_than_the_cursor_bound_stops_the_walk_for_ever()
      throws Exception {
    Files.createDirectories(root.resolve("live/acme/2026"));
    Files.write(root.resolve("live/acme/2026/INV-a.xml"), new byte[] {1});
    sh(
        root.resolve("live/acme"),
        "n=$(printf '\\377%.0s' $(seq 1 255)); i=0; while [ $i -lt 5 ]; do mkdir \"$n\" && cd -P"
            + " \"$n\" || exit 1; i=$((i+1)); done; : > \"$n\"");

    ArchiveListing listing = store().listAfter("live/acme/", "", 10);

    assertThat(listing.keys())
        .as("every emitted key fits the cursor and the order guard")
        .allMatch(k -> ArchiveListing.utf8(k).length <= OrphanCursor.MAX_POSITION_BYTES);
    assertThat(listing.keys()).hasSize(2).contains("live/acme/2026/INV-a.xml");
  }

  // ---------------------------------------------------------------- D-PR57-4 (LOW)

  /**
   * D-PR57-4. The empty-mount rule tests the cycle's key count, not the listing. A volume that
   * goes away in the middle of a cycle answers "empty, end of folder" after a non-empty position:
   * the cycle is stamped complete although the keys after the position were never examined.
   */
  @Test
  void probe_an_archive_that_goes_empty_mid_cycle_completes_the_cycle() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    issue(harness, "in_pr57_mid_a");
    issue(harness, "in_pr57_mid_b");
    issue(harness, "in_pr57_mid_c");
    harness.sweep(ONE_KEY_HOUR_ALERT).sweep();
    assertThat(cursor(harness).position()).as("precondition: mid cycle").isNotEmpty();

    OrphanWalkPathsTest.Scripted unmounted =
        new OrphanWalkPathsTest.Scripted(harness.archive()) {
          @Override
          ArchiveListing listing(String prefix, String startAfter, int limit) {
            return new ArchiveListing(List.of(), true);
          }
        };
    try {
      harness.sweepWith(ONE_KEY_HOUR_ALERT, unmounted).sweep();
    } catch (EInvoiceException expectedOnceFixed) {
      // A failed check is a correct answer here; a completed cycle is not.
    }

    assertThat(cursor(harness).lastCompleteCycleAt())
        .as("two of three keys never examined")
        .isEmpty();
  }

  // ---------------------------------------------------------------- D-PR57-7 (INFO)

  /**
   * D-PR57-7. R6 and D4 require a test that pins the subject bound with a key made of 4-byte
   * characters. None exists: the only long-key test uses ASCII, so "cut, then escape" passes the
   * whole suite. With it, 200 surrogate halves become 1,200 characters, the finding write fails
   * (varchar 512) and the walk stops at that name. This probe passes on 1ef0995 and fails on that
   * mutation; it belongs in the suite.
   */
  @Test
  void probe_a_key_of_four_byte_characters_keeps_its_subject_bound_and_the_walk_moves() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String folder = ArchiveKey.sellerFolder(Mode.LIVE, harness.sellerId());
    String wide = folder + "\uD83D\uDE00".repeat(256);
    String after = folder + "\uDBFF\uDFFF.xml";
    harness.archive().putDirectly(wide, new byte[] {1});
    harness.archive().putDirectly(after, new byte[] {1});

    harness.sweep(ONE_KEY_HOUR_ALERT).sweep();
    harness.sweep(ONE_KEY_HOUR_ALERT).sweep();

    assertThat(OrphanSubject.of(wide).length()).isLessThanOrEqualTo(512);
    assertThat(codesFor(harness, OrphanSubject.of(wide)))
        .containsExactly(ErrorCodes.RECON_ORPHAN_OBJECT);
    assertThat(codesFor(harness, OrphanSubject.of(after)))
        .as("the walk moved past the wide name")
        .containsExactly(ErrorCodes.RECON_ORPHAN_OBJECT);
  }

  // ---------------------------------------------------------------- D-PR57-8 (INFO)

  /**
   * D-PR57-8. D7: a failed delete of the case probe's scratch file never stops startup. No test
   * holds it: rethrowing in that catch passes the whole suite. A user append-only folder (macOS
   * {@code chflags uappnd}) lets the create succeed and the delete fail. Passes on 1ef0995, fails
   * on that mutation.
   */
  @Test
  @EnabledOnOs(OS.MAC)
  void probe_a_case_probe_that_cannot_remove_its_scratch_file_still_answers() throws Exception {
    Path scratch = Files.createDirectories(root.resolve(ArchiveCapabilityProbe.SCRATCH_PREFIX));
    sh(scratch, "chflags uappnd .");
    try {
      assertThat(store().ignoresLetterCase()).isTrue();
    } finally {
      sh(scratch, "chflags nouappnd .");
    }
  }

  // ---------------------------------------------------------------- D-PR57-6 (LOW), core path

  /**
   * D-PR57-6. The DEI-285 rule lives only in the starter's startup bean, behind {@code instanceof
   * FilesystemArchiveStore}. A host that builds the store and the sweep itself, or decorates the
   * store, walks {@code live/Acme/} on a volume that ignores case with no refusal. The store is
   * the one place every path goes through. Runs where the temporary folder ignores letter case.
   */
  @Test
  void probe_the_filesystem_store_walks_a_mixed_case_seller_on_a_case_insensitive_volume() {
    FilesystemArchiveStore store = store();
    assumeThat(store.ignoresLetterCase()).as("needs a case-insensitive volume").isTrue();

    assertThatThrownBy(() -> store.listAfter("live/Acme/", "", 1))
        .isInstanceOfSatisfying(
            EInvoiceException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCodes.ARCHIVE_SELLER_CASE));
  }
}
