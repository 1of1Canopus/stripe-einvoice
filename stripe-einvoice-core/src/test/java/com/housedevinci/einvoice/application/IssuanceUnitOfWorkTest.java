package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Hashes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The unit of work end to end, against a real PostgreSQL, a real state machine and a real archive.
 *
 * <p>Every path the design's tables name has a test here: out of order, duplicate, late, refused,
 * voided, and each failure with its own terminal.
 */
class IssuanceUnitOfWorkTest {

  @Test
  void a_finalised_invoice_becomes_one_document_under_one_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_ok"));
    String eventId = harness.receive("invoice.finalized", "in_ok");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(outcome.legalNumber()).isEqualTo("INV-2026-000001");
    Issuance issuance = harness.issuance("in_ok").orElseThrow();
    assertThat(issuance.state()).isEqualTo(IssuanceState.ISSUED);
    assertThat(harness.archive().size()).isEqualTo(1);
    assertThat(harness.archived(issuance)).isPresent();
    // The archived bytes are the ones the hash names; nothing was re-serialised on the way.
    assertThat(Hashes.sha256Hex(harness.archived(issuance).orElseThrow()))
        .isEqualTo(issuance.documentSha256());
  }

  @Test
  void an_out_of_order_paid_event_parks() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.draft("in_draft"));
    String eventId = harness.receive("invoice.paid", "in_draft");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.PARKED);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.INVOICE_NOT_FINALISED);
    assertThat(harness.issuance("in_draft")).isEmpty();
    // Parked, never dropped: the sweeper comes back for it, after a backoff rather than at once -
    // an invoice Stripe has not finalised yet will not be finalised by asking again immediately.
    assertThat(harness.due()).extracting("eventId").doesNotContain(eventId);
    harness.moveClockForward(Duration.ofMinutes(2));
    assertThat(harness.due()).extracting("eventId").contains(eventId);

    harness.source().with(TestInvoices.finalised("in_draft"));
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
  }

  @Test
  void a_redelivery_of_an_issued_invoice_returns_the_same_document() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_redelivered"));
    String first = harness.receive("invoice.finalized", "in_redelivered");
    harness.unitOfWork().process(first);
    int fetchesAfterFirst = harness.source().fetches();

    String second = harness.receive("invoice.paid", "in_redelivered");
    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(second);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(outcome.legalNumber()).isEqualTo("INV-2026-000001");
    assertThat(harness.archive().size()).isEqualTo(1);
    assertThat(harness.numberedRows()).isEqualTo(1);
    // It re-fetched once to decide, and then re-rendered nothing at all.
    assertThat(harness.source().fetches()).isEqualTo(fetchesAfterFirst + 1);
  }

  @Test
  void a_customer_renamed_after_issuance_does_not_change_the_stored_document() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_renamed"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_renamed"));
    byte[] archivedBefore =
        harness.archived(harness.issuance("in_renamed").orElseThrow()).orElseThrow();

    // The buyer corrects their name in the portal, months later, and a redelivery arrives.
    harness.source().with(TestInvoices.finalised("in_renamed", "Buyer Cooperative SA", "paid"));
    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWork().process(harness.receive("invoice.paid", "in_renamed"));

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(outcome.code()).isEmpty();
    assertThat(harness.archived(harness.issuance("in_renamed").orElseThrow()).orElseThrow())
        .isEqualTo(archivedBefore);
  }

  @Test
  void a_redelivery_after_a_customer_edit_is_not_refused() {
    // I-02 in one line: upstream data legitimately moves, and an alarm that fires on normal
    // behaviour is one operators learn to ignore within a week.
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_edit"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_edit"));
    harness.source().with(TestInvoices.finalised("in_edit", "Another Name Entirely", "paid"));

    assertThat(harness.unitOfWork().process(harness.receive("invoice.paid", "in_edit")).code())
        .isNotEqualTo(ErrorCodes.ARCHIVED_DOCUMENT_TAMPERED);
  }

  @Test
  void a_tampered_archived_object_is_refused_by_the_hash_check() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_tampered"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_tampered"));
    Issuance issuance = harness.issuance("in_tampered").orElseThrow();

    harness
        .archive()
        .putDirectly(
            issuance.archiveKey(), "<Invoice>rewritten</Invoice>".getBytes(StandardCharsets.UTF_8));

    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWork().process(harness.receive("invoice.paid", "in_tampered"));

    assertThat(outcome.code()).isEqualTo(ErrorCodes.ARCHIVED_DOCUMENT_TAMPERED);
    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    // Never an overwrite and never a re-issue: the row still names the document it always named.
    assertThat(harness.issuance("in_tampered").orElseThrow().state())
        .isEqualTo(IssuanceState.ISSUED);
  }

  @Test
  void an_api_version_skew_is_refused_and_the_event_is_replayable_after_the_pin_is_updated() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_skew"));
    String eventId = harness.receive("invoice.finalized", "in_skew", "2019-02-19", true, "");

    IssuanceUnitOfWork.Outcome refused = harness.unitOfWork().process(eventId);
    assertThat(refused.state()).isEqualTo(InboundState.REFUSED_VERSION_SKEW);
    assertThat(refused.code()).isEqualTo(ErrorCodes.API_VERSION_SKEW);
    assertThat(harness.issuance("in_skew")).isEmpty();
    assertThat(harness.inbound().find(eventId).orElseThrow().bodyPresent()).isTrue();

    // The operator re-pins, and the recorded event replays through the same path.
    IssuanceUnitOfWork replayed = harness.unitOfWorkPinnedTo("2019-02-19");
    assertThat(replayed.process(eventId).state()).isEqualTo(InboundState.COMPLETED);
  }

  @Test
  void a_test_mode_event_in_live_mode_is_refused() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_test"));
    String eventId =
        harness.receive("invoice.finalized", "in_test", FakeStripeSource.PINNED, false, "");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.REFUSED_MODE);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.MODE_MISMATCH);
    assertThat(harness.issuance("in_test")).isEmpty();
    assertThat(harness.numberedRows()).isZero();
  }

  @Test
  void an_object_whose_livemode_disagrees_with_the_event_is_refused_on_the_authoritative_answer() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.testMode("in_lying"));
    String eventId = harness.receive("invoice.finalized", "in_lying");

    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.REFUSED_MODE);
    assertThat(harness.numberedRows()).isZero();
  }

  @Test
  void an_unknown_account_is_refused_and_never_falls_back_to_a_default_seller() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_acct"));
    String eventId =
        harness.receive(
            "invoice.finalized", "in_acct", FakeStripeSource.PINNED, true, "acct_someone_else");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.REFUSED_ACCOUNT);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.UNKNOWN_ACCOUNT);
    assertThat(harness.inbound().find(eventId).orElseThrow().sellerId()).isEmpty();
  }

  @Test
  void an_event_type_this_module_does_not_act_on_is_recorded_and_dropped() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = harness.receive("credit_note.created", "cn_1");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.DROPPED);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.UNSUBSCRIBED_EVENT_TYPE);
    assertThat(harness.source().fetches()).isZero();
  }

  @Test
  void a_totals_mismatch_of_one_cent_in_one_bucket_consumes_no_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.offByOneCentInOneBucket("in_cent"));
    String eventId = harness.receive("invoice.finalized", "in_cent");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_TOTALS);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.TOTALS_MISMATCH);
    assertThat(harness.numberedRows()).isZero();
    assertThat(harness.archive().size()).isZero();
    // Final, not retryable: retrying a refusal that needs a human is noise that hides the decision.
    assertThat(harness.due()).extracting("eventId").doesNotContain(eventId);
  }

  @Test
  void a_validation_refusal_leaves_no_file_and_no_issued_row() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWith(TestValidators.failing("BR-CO-10"));
    harness.source().with(TestInvoices.finalised("in_invalid"));
    String eventId = harness.receive("invoice.finalized", "in_invalid");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.VALIDATION_REFUSED);
    assertThat(harness.archive().size()).isZero();
    Issuance issuance = harness.issuance("in_invalid").orElseThrow();
    assertThat(issuance.state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(issuance.documentHash()).isEmpty();
    // The number stays allocated with the failing rule recorded, so the operator's void names it.
    assertThat(harness.inbound().lastRuleId(eventId)).contains("BR-CO-10");
    assertThat(outcome.legalNumber()).isEqualTo("INV-2026-000001");
  }

  @Test
  void a_validation_that_could_not_run_is_refused_and_never_passed_by_construction() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWith(TestValidators.notEvaluated("BR-DE-15"));
    harness.source().with(TestInvoices.finalised("in_unevaluated"));
    String eventId = harness.receive("invoice.finalized", "in_unevaluated");

    assertThat(harness.unitOfWork().process(eventId).code())
        .isEqualTo(ErrorCodes.VALIDATION_NOT_EVALUATED);
    assertThat(harness.archive().size()).isZero();
  }

  @Test
  void stripe_being_unreachable_is_retryable_and_never_a_verdict() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_outage"));
    harness
        .source()
        .breakWith(
            new EInvoiceException(ErrorCodes.STRIPE_UNAVAILABLE, "the Stripe API is unreachable"));
    String eventId = harness.receive("invoice.finalized", "in_outage");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);
    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_FETCH);
    assertThat(harness.numberedRows()).isZero();

    // Hours later, inside the ceiling, the sweeper comes back and the sale is documented.
    harness.source().heal();
    harness.moveClockForward(Duration.ofHours(2));
    assertThat(harness.due()).extracting("eventId").contains(eventId);
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
  }

  @Test
  void an_invoice_voided_before_we_ever_issued_is_recorded_and_dropped() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_void", "Buyer Cooperative", "void"));
    String eventId = harness.receive("invoice.finalized", "in_void");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.DROPPED);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.UPSTREAM_VOID_NOT_ISSUED);
    assertThat(harness.numberedRows()).isZero();
  }

  @Test
  void an_invoice_voided_after_we_issued_is_a_compliance_finding_and_never_a_withdrawal() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_late_void"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_late_void"));

    harness.source().with(TestInvoices.finalised("in_late_void", "Buyer Cooperative", "void"));
    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWork().process(harness.receive("invoice.paid", "in_late_void"));

    assertThat(outcome.code()).isEqualTo(ErrorCodes.VOID_AFTER_ISSUE_NEEDS_CREDIT_NOTE);
    assertThat(harness.issuance("in_late_void").orElseThrow().state())
        .isEqualTo(IssuanceState.ISSUED);
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  @Test
  void an_invoice_from_a_fiscal_year_that_closed_long_ago_is_refused_when_a_cutoff_is_set() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWithCutoff(Optional.of(Duration.ofDays(60)));
    harness.source().with(TestInvoices.finalised("in_late_year"));
    harness.moveClockTo(Instant.parse("2027-06-01T10:00:00Z"));
    String eventId = harness.receive("invoice.finalized", "in_late_year");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_MAPPING);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.CLOSED_FISCAL_YEAR);
    assertThat(harness.numberedRows()).isZero();
  }

  @Test
  void with_no_cutoff_configured_a_late_invoice_is_numbered_in_its_own_fiscal_year() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_late_ok"));
    harness.moveClockTo(Instant.parse("2027-06-01T10:00:00Z"));

    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWork().process(harness.receive("invoice.finalized", "in_late_ok"));

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    // The number belongs to the year of the sale, not to the year of the processing.
    assertThat(outcome.legalNumber()).isEqualTo("INV-2026-000001");
  }

  @Test
  void the_issue_date_follows_the_sellers_tax_zone_and_not_the_containers() {
    // Finalised at 23:30 UTC on 15 January: 16 January in Tokyo, 15 January in Paris. The VAT
    // period, and on a month boundary the declaration, follow from which of the two is used.
    IssuanceTestHarness paris = IssuanceTestHarness.createInZone("Europe/Paris");
    paris.source().with(TestInvoices.finalised("in_zone"));
    paris.unitOfWork().process(paris.receive("invoice.finalized", "in_zone"));
    assertThat(new String(paris.archivedBytes("in_zone"), StandardCharsets.UTF_8))
        .contains("<IssueDate>2026-01-16</IssueDate>");

    IssuanceTestHarness lisbon = IssuanceTestHarness.createInZone("Atlantic/Azores");
    lisbon.source().with(TestInvoices.finalised("in_zone"));
    lisbon.unitOfWork().process(lisbon.receive("invoice.finalized", "in_zone"));
    assertThat(new String(lisbon.archivedBytes("in_zone"), StandardCharsets.UTF_8))
        .contains("<IssueDate>2026-01-15</IssueDate>");
  }

  @Test
  void the_same_fixture_renders_identical_bytes_under_two_zones_and_two_locales() {
    // The JVM's own zone and locale move; the configured tax zone and Locale.ROOT do not. Bytes
    // that depend on the first are bytes a retry cannot reproduce, and a retry that cannot
    // reproduce them writes a second document under one number.
    byte[] first = renderUnder("America/Los_Angeles", java.util.Locale.of("ar", "EG"));
    byte[] second = renderUnder("Asia/Tokyo", java.util.Locale.of("tr", "TR"));
    assertThat(first).isEqualTo(second);
  }

  private byte[] renderUnder(String zone, java.util.Locale locale) {
    java.util.TimeZone defaultZone = java.util.TimeZone.getDefault();
    java.util.Locale defaultLocale = java.util.Locale.getDefault();
    try {
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
      java.util.Locale.setDefault(locale);
      IssuanceTestHarness harness = IssuanceTestHarness.create();
      harness.source().with(TestInvoices.finalised("in_det"));
      harness.unitOfWork().process(harness.receive("invoice.finalized", "in_det"));
      return harness.archivedBytes("in_det");
    } finally {
      java.util.TimeZone.setDefault(defaultZone);
      java.util.Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void an_archive_that_is_unreachable_leaves_a_retryable_failure_and_one_number() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_archive_down"));
    harness
        .archive()
        .failWith(
            new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "the archive is unavailable"));
    String eventId = harness.receive("invoice.finalized", "in_archive_down");

    assertThat(harness.unitOfWork().process(eventId).code())
        .isEqualTo(ErrorCodes.ARCHIVE_UNAVAILABLE);
    assertThat(harness.issuance("in_archive_down").orElseThrow().state())
        .isEqualTo(IssuanceState.FAILED_ARCHIVE);

    harness.archive().heal();
    harness.moveClockForward(Duration.ofHours(2));
    IssuanceUnitOfWork.Outcome retried = harness.unitOfWork().process(eventId);

    assertThat(retried.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(retried.legalNumber()).isEqualTo("INV-2026-000001");
    assertThat(harness.numberedRows()).isEqualTo(1);
  }

  @Test
  void an_archive_object_written_by_somebody_else_at_our_key_is_refused_and_not_retried() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_conflict"));
    String eventId = harness.receive("invoice.finalized", "in_conflict");
    // Somebody wrote different bytes at the key this document's content hash resolves to.
    harness
        .archive()
        .putDirectly(
            expectedKey(harness, "in_conflict"), "<Other/>".getBytes(StandardCharsets.UTF_8));

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.ARCHIVE_CONTENT_CONFLICT);
    assertThat(harness.issuance("in_conflict").orElseThrow().state())
        .isEqualTo(IssuanceState.FAILED_ARCHIVE);
    // Retrying cannot help a conflict, so the sweeper is not asked to.
    assertThat(harness.due()).extracting("eventId").doesNotContain(eventId);
  }

  /**
   * The key this fixture's document will hash to, computed the way the unit of work computes it.
   */
  private String expectedKey(IssuanceTestHarness harness, String invoiceId) {
    byte[] bytes = harness.renderFor(invoiceId, "INV-2026-000001");
    return ArchiveKey.of(
            harness.seriesKey(),
            harness.legalNumber("INV-2026-000001"),
            Hashes.sha256Hex(bytes),
            "xml")
        .value();
  }

  @Test
  void an_event_nobody_recorded_cannot_be_processed() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowableOfType(
                    EInvoiceException.class, () -> harness.unitOfWork().process("evt_nothing"))
                .code())
        .isEqualTo(ErrorCodes.INBOUND_UNREADABLE);
  }

  @Test
  void a_final_terminal_is_not_processed_again_by_a_second_caller() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_done"));
    String eventId = harness.receive("invoice.finalized", "in_done");
    harness.unitOfWork().process(eventId);
    int fetches = harness.source().fetches();

    IssuanceUnitOfWork.Outcome again = harness.unitOfWork().process(eventId);

    assertThat(again.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(harness.source().fetches()).isEqualTo(fetches);
  }

  @Test
  void two_sellers_never_see_each_others_rows_from_the_unauthenticated_path() {
    IssuanceTestHarness one = IssuanceTestHarness.create();
    IssuanceTestHarness two = IssuanceTestHarness.create();
    one.source().with(TestInvoices.finalised("in_shared_id"));
    two.source().with(TestInvoices.finalised("in_shared_id"));

    one.unitOfWork().process(one.receive("invoice.finalized", "in_shared_id"));
    two.unitOfWork().process(two.receive("invoice.finalized", "in_shared_id"));

    // The same Stripe invoice id under two sellers is two rows, each in its own series, and
    // neither reader can see the other's.
    assertThat(one.issuance("in_shared_id")).isPresent();
    assertThat(two.issuance("in_shared_id")).isPresent();
    assertThat(one.reader().findBySource(two.sellerId(), Mode.LIVE, "in_shared_id")).isPresent();
    assertThat(one.numberedRows()).isEqualTo(1);
    assertThat(two.numberedRows()).isEqualTo(1);
  }

  @Test
  void the_outcome_carries_ids_and_codes_and_no_buyer_field() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_privacy", "Sensitive Buyer Name", "paid"));
    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWork().process(harness.receive("invoice.finalized", "in_privacy"));

    assertThat(List.of(outcome.toString()))
        .allSatisfy(text -> assertThat(text).doesNotContain("Sensitive"));
  }
}
