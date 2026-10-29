package com.housedevinci.einvoice.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Security review, re-verification pass on the D-RTR-8 fix. {@code probe_*} tests fail on the
 * reviewed head; {@code confirm_*} tests pin behaviour the review checked and found correct.
 */
class CipherProbePr41Test {

  @TempDir Path base;

  private static final String SHA = "a".repeat(64);
  private static final ArchiveKey KEY = new ArchiveKey("live/acme/2026/INV-000001-" + SHA + ".xml");
  private static final byte[] BYTES = "<Invoice/>".getBytes(StandardCharsets.UTF_8);

  private static void assertUnavailable(Throwable thrown) {
    assertThat(thrown)
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE);
  }

  // D-RTR-9: a missing archive root is "the archive is not there", not "no object".

  @Test
  void probe_get_reports_absent_when_the_archive_root_itself_is_gone() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    // The volume is unmounted or the directory was moved while the process runs.
    Files.move(root, base.resolve("archive-moved"));
    assertThatThrownBy(() -> store.get(KEY)).satisfies(CipherProbePr41Test::assertUnavailable);
  }

  @Test
  void probe_list_reports_empty_when_the_archive_root_itself_is_gone() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    Files.move(root, base.resolve("archive-moved"));
    assertThatThrownBy(() -> store.list("live/acme", 100))
        .satisfies(CipherProbePr41Test::assertUnavailable);
  }

  // D-RTR-10: get opens whatever sits at the key - a FIFO blocks the thread forever, a link
  // leaves the root.

  @Test
  void probe_get_blocks_forever_on_a_fifo_at_the_key() throws Exception {
    Path root = Files.createDirectories(base.resolve("archive"));
    Path target = root.resolve(KEY.value());
    Files.createDirectories(target.getParent());
    Process mkfifo = new ProcessBuilder("mkfifo", target.toString()).start();
    assertThat(mkfifo.waitFor()).isZero();
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    // Bounded: the store must answer, whatever the answer is. A write end opened from here
    // would release the blocked open, so none is opened; a timeout is the failure.
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () ->
            assertThatThrownBy(() -> store.get(KEY))
                .satisfies(CipherProbePr41Test::assertUnavailable));
  }

  @Test
  void probe_get_follows_a_symbolic_link_out_of_the_archive_root() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    Path outside = Files.writeString(base.resolve("outside.txt"), "not an archived document");
    Path target = root.resolve(KEY.value());
    Files.createDirectories(target.getParent());
    Files.createSymbolicLink(target, outside);
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> store.get(KEY)).satisfies(CipherProbePr41Test::assertUnavailable);
  }

  // D-RTR-11: list still turns "cannot tell" into "nothing here".

  @Test
  void probe_list_silently_drops_an_entry_it_cannot_stat() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    Path odd = root.resolve("live/acme/2026/INV-000002-" + SHA + ".xml");
    Files.createSymbolicLink(odd, odd.getFileName());
    // The sweep lists to find objects nobody can explain. An entry the store cannot classify is
    // either reported or a failure; it is never invisible.
    List<String> listed;
    try {
      listed = store.list("live/acme", 100);
    } catch (EInvoiceException e) {
      assertUnavailable(e);
      return;
    }
    assertThat(listed).contains("live/acme/2026/INV-000002-" + SHA + ".xml");
  }

  @Test
  void probe_list_reports_empty_when_the_prefix_is_a_file() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    Files.createDirectories(root.resolve("live"));
    Files.writeString(root.resolve("live/acme"), "x");
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> store.list("live/acme", 100))
        .satisfies(CipherProbePr41Test::assertUnavailable);
  }

  // Confirmed correct on the reviewed head.

  @Test
  void confirm_get_of_a_directory_at_the_key_is_unavailable() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    Files.createDirectories(root.resolve(KEY.value()));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> store.get(KEY)).satisfies(CipherProbePr41Test::assertUnavailable);
  }

  @Test
  void confirm_get_under_a_parent_that_is_a_file_is_unavailable() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    Files.createDirectories(root.resolve("live/acme"));
    Files.writeString(root.resolve("live/acme/2026"), "x");
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> store.get(KEY)).satisfies(CipherProbePr41Test::assertUnavailable);
  }

  @Test
  void confirm_get_of_a_name_too_long_is_unavailable() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    Files.createDirectories(root.resolve("live/acme/2026"));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    ArchiveKey longName = new ArchiveKey("live/acme/2026/" + "n".repeat(300) + ".xml");
    assertThatThrownBy(() -> store.get(longName)).satisfies(CipherProbePr41Test::assertUnavailable);
  }

  @Test
  void confirm_traversal_keys_are_refused_before_the_store() {
    assertThatThrownBy(() -> new ArchiveKey("live/../../etc/passwd"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INVALID);
    Path root = base.resolve("archive");
    assertThat(new FilesystemArchiveStore(root).list("../", 10)).isEmpty();
  }

  @Test
  void confirm_list_of_a_readable_but_not_searchable_directory_is_unavailable() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    Path year = root.resolve("live/acme/2026");
    Files.setPosixFilePermissions(year, PosixFilePermissions.fromString("r--r--r--"));
    try {
      if (Files.isReadable(year.resolve(KEY.value().substring(15)))) {
        return; // root, or a filesystem that ignores modes: nothing to observe
      }
      // D-PR57-2 (ruled 2026-10-29, QUESTIONS 65): an entry below the listed folder that cannot
      // be read is named, never hidden and never "absent"; it no longer fails the whole listing.
      assertThat(store.list("live/acme", 100)).containsExactly(KEY.value() + "#unlistable");
      assertThatThrownBy(() -> store.get(KEY)).satisfies(CipherProbePr41Test::assertUnavailable);
    } finally {
      Files.setPosixFilePermissions(year, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }
}
