package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import org.junit.jupiter.api.Test;

/** I-06: the capability is probed with a real conditional write, never believed. */
class ArchiveCapabilityProbeTest {

  @Test
  void a_store_without_atomic_create_is_refused_at_startup() {
    // The store below declares the capability and does not have it, which is exactly what an S3
    // gateway that ignores If-None-Match looks like from the client side.
    InMemoryArchiveStore overwriting = InMemoryArchiveStore.silentlyOverwriting();
    assertThatThrownBy(() -> ArchiveCapabilityProbe.probe(overwriting, "boot1", false))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_NOT_ATOMIC);
  }

  @Test
  void a_write_once_store_passes_the_probe() {
    assertThatCode(() -> ArchiveCapabilityProbe.probe(new InMemoryArchiveStore(), "boot2", false))
        .doesNotThrowAnyException();
  }

  @Test
  void the_weaker_mode_lets_a_non_atomic_store_start_and_says_so() {
    assertThatCode(
            () ->
                ArchiveCapabilityProbe.probe(
                    InMemoryArchiveStore.silentlyOverwriting(), "boot3", true))
        .doesNotThrowAnyException();
  }

  @Test
  void an_unreachable_store_is_an_outage_and_not_a_capability_answer() {
    InMemoryArchiveStore store = new InMemoryArchiveStore();
    store.failWith(
        new EInvoiceException(
            ErrorCodes.ARCHIVE_UNAVAILABLE, "the document archive is unavailable"));
    assertThatThrownBy(() -> ArchiveCapabilityProbe.probe(store, "boot4", false))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE);
  }

  @Test
  void the_probe_needs_a_value_that_differs_per_startup() {
    assertThatThrownBy(() -> ArchiveCapabilityProbe.probe(new InMemoryArchiveStore(), "!!", false))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INVALID);
  }
}
