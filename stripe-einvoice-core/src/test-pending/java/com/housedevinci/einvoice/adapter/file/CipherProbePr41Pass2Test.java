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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Security review, pass 2 on the D-RTR-9 to D-RTR-11 fixes. {@code probe_*} tests fail on the
 * reviewed head; {@code confirm_*} tests pin behaviour the review checked and found correct.
 */
class CipherProbePr41Pass2Test {

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

  // D-RTR-12: NOFOLLOW_LINKS covers the last name only. A linked folder on the way still leaves
  // the root, for reads and for writes.

  @Test
  void probe_get_follows_a_linked_folder_out_of_the_archive_root() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive/live"));
    Path outside = Files.createDirectories(base.resolve("outside/2026"));
    Files.writeString(outside.resolve(Path.of(KEY.value()).getFileName().toString()), "outside");
    Files.createSymbolicLink(root.resolve("acme"), base.resolve("outside"));
    FilesystemArchiveStore store = new FilesystemArchiveStore(base.resolve("archive"));
    assertThatThrownBy(() -> store.get(KEY))
        .satisfies(CipherProbePr41Pass2Test::assertUnavailable);
  }

  @Test
  void probe_put_writes_through_a_linked_folder_out_of_the_archive_root() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    Path outside = Files.createDirectories(base.resolve("outside"));
    Files.createSymbolicLink(root.resolve("live"), outside);
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    Throwable thrown = null;
    try {
      store.putIfAbsent(KEY, BYTES);
    } catch (RuntimeException e) {
      thrown = e;
    }
    assertThat(Files.exists(outside.resolve("acme")))
        .as("nothing written outside the root")
        .isFalse();
    assertThat(thrown).satisfies(CipherProbePr41Pass2Test::assertUnavailable);
  }

  @Test
  void probe_list_walks_a_linked_folder_out_of_the_archive_root() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    Path outside = Files.createDirectories(base.resolve("outside/acme/2026"));
    Files.writeString(outside.resolve("secret.txt"), "outside");
    Files.createSymbolicLink(root.resolve("live"), base.resolve("outside"));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> store.list("live/acme", 10))
        .satisfies(CipherProbePr41Pass2Test::assertUnavailable);
  }

  // Confirmed correct on the reviewed head.

  @Test
  void confirm_put_over_a_folder_at_the_key_is_unavailable() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    Files.createDirectories(root.resolve(KEY.value()));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> store.putIfAbsent(KEY, BYTES))
        .satisfies(CipherProbePr41Pass2Test::assertUnavailable);
  }

  @Test
  void confirm_a_root_that_is_a_link_to_a_folder_is_followed_by_design() throws IOException {
    Path real = Files.createDirectories(base.resolve("volume"));
    Path root = Files.createSymbolicLink(base.resolve("archive"), real);
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    assertThat(store.get(KEY)).contains(BYTES);
    assertThat(store.list("live", 10)).containsExactly(KEY.value());
  }

  @Test
  void confirm_a_root_link_that_dangles_is_unavailable() throws IOException {
    Path root = Files.createSymbolicLink(base.resolve("archive"), base.resolve("missing"));
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> store.get(KEY))
        .satisfies(CipherProbePr41Pass2Test::assertUnavailable);
    assertThatThrownBy(() -> store.list("live", 10))
        .satisfies(CipherProbePr41Pass2Test::assertUnavailable);
  }

  @Test
  void confirm_list_on_a_deep_tree_finishes() throws IOException {
    Path root = Files.createDirectories(base.resolve("archive"));
    Path deep = root.resolve("live");
    for (int i = 0; i < 300; i++) {
      deep = deep.resolve("d");
    }
    Files.createDirectories(deep);
    Files.writeString(deep.resolve("x"), "x");
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThat(store.list("live", 10)).hasSize(1);
  }
}
