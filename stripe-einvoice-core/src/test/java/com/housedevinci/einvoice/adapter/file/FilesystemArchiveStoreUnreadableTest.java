package com.housedevinci.einvoice.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D-RTR-8: an object the store cannot read is a failure, never "absent". {@code Files.exists}
 * answers false when it cannot tell, so it must not decide absence.
 *
 * <p>Two kinds of unreadable, so an assertion runs on every platform. A symbolic link loop is
 * refused by the kernel for every user, root included. A mode 000 entry is refused for an ordinary
 * user only; where the process can read it anyway (root, or a filesystem that ignores modes) the
 * test asserts that the bytes are returned, which is the correct answer there.
 */
class FilesystemArchiveStoreUnreadableTest {

  @TempDir Path root;

  private static final String SHA = "a".repeat(64);
  private static final ArchiveKey KEY = new ArchiveKey("live/acme/2026/INV-000001-" + SHA + ".xml");
  private static final byte[] BYTES = "<Invoice/>".getBytes(StandardCharsets.UTF_8);

  private static void assertUnavailable(Throwable thrown) {
    assertThat(thrown)
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE);
  }

  private static void loop(Path link) throws IOException {
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(link, link.getFileName());
  }

  private static boolean mode000(Path path) throws IOException {
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));
    return !Files.isReadable(path);
  }

  private static void restore(Path path, String perms) throws IOException {
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
  }

  // read: the object itself cannot be read

  @Test
  void get_of_an_object_that_cannot_be_read_is_a_failure_not_absent() throws IOException {
    loop(root.resolve(KEY.value()));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> store.get(KEY))
        .satisfies(FilesystemArchiveStoreUnreadableTest::assertUnavailable);
  }

  @Test
  void get_of_a_mode_000_object_is_a_failure_not_absent() throws IOException {
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    Path file = root.resolve(KEY.value());
    try {
      if (mode000(file)) {
        assertThatThrownBy(() -> store.get(KEY))
            .satisfies(FilesystemArchiveStoreUnreadableTest::assertUnavailable);
      } else {
        assertThat(store.get(KEY)).contains(BYTES);
      }
    } finally {
      restore(file, "rw-------");
    }
  }

  // exists-like: the directory above the object cannot be searched, so the object cannot be seen

  @Test
  void get_under_a_directory_that_cannot_be_searched_is_a_failure_not_absent() throws IOException {
    loop(root.resolve("live/acme/2026"));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> store.get(KEY))
        .satisfies(FilesystemArchiveStoreUnreadableTest::assertUnavailable);
  }

  @Test
  void get_under_a_mode_000_directory_is_a_failure_not_absent() throws IOException {
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    Path dir = root.resolve("live/acme/2026");
    try {
      if (mode000(dir)) {
        assertThatThrownBy(() -> store.get(KEY))
            .satisfies(FilesystemArchiveStoreUnreadableTest::assertUnavailable);
      } else {
        assertThat(store.get(KEY)).contains(BYTES);
      }
    } finally {
      restore(dir, "rwx------");
    }
  }

  @Test
  void a_key_that_is_truly_absent_is_still_empty() {
    assertThat(new FilesystemArchiveStore(root).get(KEY)).isEmpty();
  }

  // list: the directory cannot be listed

  @Test
  void list_of_a_directory_that_cannot_be_read_is_a_failure_not_empty() throws IOException {
    loop(root.resolve("live/acme"));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> store.list("live/acme", 10))
        .satisfies(FilesystemArchiveStoreUnreadableTest::assertUnavailable);
  }

  /**
   * The listed folder itself cannot be read: the listing fails, it is never empty. A folder below
   * it is the next test.
   */
  @Test
  void list_of_a_mode_000_directory_is_a_failure_not_empty() throws IOException {
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    Path dir = root.resolve("live/acme/2026");
    try {
      if (mode000(dir)) {
        assertThatThrownBy(() -> store.list("live/acme/2026", 10))
            .satisfies(FilesystemArchiveStoreUnreadableTest::assertUnavailable);
      } else {
        assertThat(store.list("live/acme/2026", 10)).containsExactly(KEY.value());
      }
    } finally {
      restore(dir, "rwx------");
    }
  }

  /**
   * D-PR57-2: a mode 000 folder below the listed folder is never hidden and never stops the walk.
   * It is listed as {@code <path>#unlistable}, an invalid key the orphan walk reports.
   */
  @Test
  void list_of_a_mode_000_directory_below_the_prefix_names_it_not_empty() throws IOException {
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    Path dir = root.resolve("live/acme/2026");
    try {
      if (mode000(dir)) {
        assertThat(store.list("live", 10)).containsExactly("live/acme/2026#unlistable");
      } else {
        assertThat(store.list("live", 10)).containsExactly(KEY.value());
      }
    } finally {
      restore(dir, "rwx------");
    }
  }

  @Test
  void list_of_a_prefix_that_does_not_exist_is_still_empty() {
    assertThat(new FilesystemArchiveStore(root).list("live/none", 10)).isEmpty();
  }
}
