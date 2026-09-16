package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import org.junit.jupiter.api.Test;

/** P3 and P4 of the two-store write, against a real PostgreSQL and the real triggers. */
class IssuanceWriterTest {

  private static final String HASH = "a".repeat(64);
  private static final String OTHER_HASH = "b".repeat(64);

  private static ArchiveKey keyFor(String hash) {
    return new ArchiveKey("live/s/2026/INV-000001-" + hash + ".xml");
  }

  @Test
  void the_archiving_marker_is_written_before_the_object_and_is_idempotent() {
    String seller = PostgresSupport.freshSeller("p3");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_p3"));

    Issuance archiving = store.markArchiving(seller, Mode.LIVE, "in_p3", HASH, keyFor(HASH));
    assertThat(archiving.state()).isEqualTo(IssuanceState.ARCHIVING);
    assertThat(archiving.documentHash()).contains(HASH);
    assertThat(archiving.archiveObjectKey()).contains(keyFor(HASH).value());

    // A retry of the same phase on the same bytes: the row already predicted this object.
    assertThat(store.markArchiving(seller, Mode.LIVE, "in_p3", HASH, keyFor(HASH)).state())
        .isEqualTo(IssuanceState.ARCHIVING);
  }

  @Test
  void one_number_never_names_two_sets_of_bytes() {
    String seller = PostgresSupport.freshSeller("twobytes");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_two"));
    store.markArchiving(seller, Mode.LIVE, "in_two", HASH, keyFor(HASH));

    assertThatThrownBy(
            () -> store.markArchiving(seller, Mode.LIVE, "in_two", OTHER_HASH, keyFor(OTHER_HASH)))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ARCHIVE_CONTENT_CONFLICT);
  }

  @Test
  void issuing_writes_the_state_and_the_chained_disposition_in_one_transaction() {
    String seller = PostgresSupport.freshSeller("p4");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_p4"));
    store.markArchiving(seller, Mode.LIVE, "in_p4", HASH, keyFor(HASH));

    Issuance issued = store.markIssued(seller, Mode.LIVE, "in_p4");
    assertThat(issued.state()).isEqualTo(IssuanceState.ISSUED);
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_issuance_event WHERE seller_id = '"
                    + seller
                    + "' AND state = 'ISSUED'"))
        .isEqualTo(1L);
    // A redelivery after the commit is a read, not a second chained row.
    store.markIssued(seller, Mode.LIVE, "in_p4");
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_issuance_event WHERE seller_id = '"
                    + seller
                    + "' AND state = 'ISSUED'"))
        .isEqualTo(1L);
  }

  @Test
  void an_issued_number_cannot_be_moved_anywhere_by_this_writer() {
    String seller = PostgresSupport.freshSeller("issuedfinal");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_final"));
    store.markArchiving(seller, Mode.LIVE, "in_final", HASH, keyFor(HASH));
    store.markIssued(seller, Mode.LIVE, "in_final");

    assertThatThrownBy(
            () -> store.markFailed(seller, Mode.LIVE, "in_final", IssuanceState.FAILED_ARCHIVE, ""))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
  }

  @Test
  void a_failed_archive_is_retryable_and_a_failed_validation_needs_a_human() {
    String seller = PostgresSupport.freshSeller("failures");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_fail"));

    Issuance failed =
        store.markFailed(seller, Mode.LIVE, "in_fail", IssuanceState.FAILED_ARCHIVE, "");
    assertThat(failed.state().retryableTerminal()).isTrue();
    // The retry walks forward onto the same number, never onto a new one.
    assertThat(store.markArchiving(seller, Mode.LIVE, "in_fail", HASH, keyFor(HASH)).state())
        .isEqualTo(IssuanceState.ARCHIVING);

    String other = PostgresSupport.freshSeller("failures2");
    JdbcIssuanceStore store2 = PostgresSupport.store(other, SIX, CLOCK, CHAIN);
    store2.allocate(request(key(other), "in_fail2"));
    assertThat(
            store2
                .markFailed(
                    other, Mode.LIVE, "in_fail2", IssuanceState.FAILED_VALIDATION, "BR-CO-10")
                .state()
                .retryableTerminal())
        .isFalse();
  }

  @Test
  void a_state_that_is_not_a_failure_state_of_an_issuance_row_is_refused() {
    String seller = PostgresSupport.freshSeller("notafailure");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    store.allocate(request(key(seller), "in_nf"));
    assertThatThrownBy(() -> store.markFailed(seller, Mode.LIVE, "in_nf", IssuanceState.ISSUED, ""))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
  }

  @Test
  void a_phase_on_an_invoice_with_no_number_is_refused() {
    String seller = PostgresSupport.freshSeller("nonumber");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    assertThatThrownBy(() -> store.markIssued(seller, Mode.LIVE, "in_absent"))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.ISSUANCE_NOT_FOUND);
  }
}
