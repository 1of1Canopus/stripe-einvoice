package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.housedevinci.einvoice.adapter.file.FilesystemArchiveStore;
import com.housedevinci.einvoice.application.ArchiveListing;
import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Security review of PR 57, pass 1 on 1ef0995: D-PR57-6. The DEI-285 case rule runs only when the
 * starter's archive bean is the filesystem store itself ({@code store instanceof
 * FilesystemArchiveStore}). A host that decorates the store (metrics, tracing, a retry wrapper),
 * or builds the sweep without the starter, gets no refusal: on a case-insensitive volume seller
 * {@code Acme} starts, shares a folder with {@code acme}, and each walk reports the other's
 * documents. Runs where the temporary folder ignores letter case (macOS, Windows defaults); named
 * as skipped elsewhere.
 */
class CipherProbePr57StarterTest {

  @TempDir Path root;

  /** A host's decorator: every call goes to the filesystem store. */
  record Counting(ArchiveStore delegate) implements ArchiveStore {
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
      return delegate.listAfter(prefix, startAfter, limit);
    }

    @Override
    public String describe() {
      return delegate.describe();
    }
  }

  @Test
  void probe_a_wrapped_filesystem_store_skips_the_seller_case_refusal() {
    FilesystemArchiveStore filesystem = new FilesystemArchiveStore(root, Clock.systemUTC());
    assumeThat(filesystem.ignoresLetterCase()).as("needs a case-insensitive volume").isTrue();
    assertThatThrownBy(
            () -> new ArchiveStoreStartupProbe(filesystem, false, "Acme", Mode.LIVE)
                .afterPropertiesSet())
        .as("precondition: the bare store is refused")
        .isInstanceOf(EInvoiceException.class);

    assertThatThrownBy(
            () -> new ArchiveStoreStartupProbe(new Counting(filesystem), false, "Acme", Mode.LIVE)
                .afterPropertiesSet())
        .isInstanceOfSatisfying(
            EInvoiceException.class,
            e -> org.assertj.core.api.Assertions.assertThat(e.code())
                .isEqualTo(ErrorCodes.ARCHIVE_SELLER_CASE));
  }
}
