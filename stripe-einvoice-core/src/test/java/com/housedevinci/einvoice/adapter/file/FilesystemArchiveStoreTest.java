package com.housedevinci.einvoice.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Write-once on a filesystem, including when two writers race for one key. */
class FilesystemArchiveStoreTest {

  @TempDir Path root;

  private static final ArchiveKey KEY =
      new ArchiveKey("live/acme/2026/INV-000001-" + "a".repeat(64) + ".xml");
  private static final byte[] BYTES = "<Invoice/>".getBytes(StandardCharsets.UTF_8);

  @Test
  void an_existing_archive_key_with_identical_bytes_succeeds() {
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThat(store.putIfAbsent(KEY, BYTES)).isEqualTo(ArchiveStore.WriteResult.CREATED);
    // What a resumed issuance looks like: the same input renders the same bytes at the same key.
    assertThat(store.putIfAbsent(KEY, BYTES)).isEqualTo(ArchiveStore.WriteResult.ALREADY_IDENTICAL);
    assertThat(store.get(KEY)).contains(BYTES);
  }

  @Test
  void an_existing_archive_key_with_different_bytes_is_refused() {
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    assertThatThrownBy(() -> store.putIfAbsent(KEY, "<Other/>".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_CONTENT_CONFLICT);
    assertThat(store.get(KEY)).contains(BYTES);
  }

  @Test
  void two_writers_racing_for_one_key_produce_one_object_and_no_torn_file() throws Exception {
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    byte[] large = new byte[512 * 1024];
    IntStream.range(0, large.length).forEach(i -> large[i] = (byte) (i % 251));
    List<Callable<ArchiveStore.WriteResult>> writers =
        IntStream.range(0, 8)
            .<Callable<ArchiveStore.WriteResult>>mapToObj(i -> () -> store.putIfAbsent(KEY, large))
            .toList();
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      for (Future<ArchiveStore.WriteResult> result : pool.invokeAll(writers)) {
        assertThat(result.get())
            .isIn(ArchiveStore.WriteResult.CREATED, ArchiveStore.WriteResult.ALREADY_IDENTICAL);
      }
    }
    assertThat(store.get(KEY)).contains(large);
    assertThat(store.list("live", 100)).containsExactly(KEY.value());
  }

  @Test
  void a_part_file_left_by_a_crash_is_never_listed_as_a_document() throws IOException {
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    store.putIfAbsent(KEY, BYTES);
    Files.writeString(root.resolve("live/acme/2026/.einvoice-123.part"), "half");
    assertThat(store.list("live", 100)).containsExactly(KEY.value());
  }

  @Test
  void a_key_that_would_leave_the_archive_root_is_refused_twice_over() {
    FilesystemArchiveStore store = new FilesystemArchiveStore(root);
    assertThatThrownBy(() -> new ArchiveKey("../../etc/passwd"))
        .isInstanceOf(EInvoiceException.class);
    assertThat(store.list("../..", 10)).isEmpty();
  }

  @Test
  void a_missing_object_reads_as_absent_rather_than_as_empty_bytes() {
    assertThat(new FilesystemArchiveStore(root).get(KEY)).isEmpty();
  }

  @Test
  void the_description_carries_the_root_and_nothing_else() {
    assertThat(new FilesystemArchiveStore(root).describe()).startsWith("filesystem archive at ");
  }
}
