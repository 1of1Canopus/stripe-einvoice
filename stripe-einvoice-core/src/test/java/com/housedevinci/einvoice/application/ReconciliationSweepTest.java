package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The sweep is the module's only defence against the failure that is silent by construction: a sale
 * with no document, whose event never arrived. It is free core (I-03), and these are its
 * directions.
 */
class ReconciliationSweepTest {

  private static final ReconciliationSweep.Settings SETTINGS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);

  @Test
  void a_finalised_invoice_with_no_issuance_row_is_found_and_re_enqueued_through_the_same_path() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_lost"));
    harness.moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.stripeInvoicesChecked()).isEqualTo(1);
    assertThat(result.missingIssuance()).isEqualTo(1);
    assertThat(harness.openFindings())
        .extracting(ComplianceFinding::code)
        .contains(ErrorCodes.RECON_MISSING_ISSUANCE);

    // Re-enqueued, never issued inline: there is exactly one code path that can make a document.
    assertThat(harness.inbound().find("recon-in_lost")).isPresent();
    assertThat(harness.issuance("in_lost")).isEmpty();

    assertThat(harness.unitOfWork().process("recon-in_lost").state())
        .isEqualTo(InboundState.COMPLETED);
    assertThat(harness.issuance("in_lost")).isPresent();
  }

  @Test
  void the_same_unfixed_condition_does_not_multiply_findings() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_repeat"));
    harness.moveClockForward(Duration.ofHours(1));

    harness.sweep(SETTINGS).sweep();
    harness.sweep(SETTINGS).sweep();
    harness.sweep(SETTINGS).sweep();

    assertThat(harness.openFindings())
        .filteredOn(finding -> finding.subjectId().equals("in_repeat"))
        .hasSize(1);
  }

  @Test
  void an_issued_row_whose_object_is_gone_is_found() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_gone"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_gone"));
    // A second document stays, so the folder is not empty: an archive that lists nothing for a
    // seller with documents is an unmounted volume, a check failure of its own (D-PR52-3, C8).
    harness.source().with(TestInvoices.finalised("in_gone_kept"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_gone_kept"));
    harness.archive().forget(harness.issuance("in_gone").orElseThrow().archiveKey());
    harness.moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.archiveMissing()).isEqualTo(1);
    assertThat(harness.openFindings())
        .extracting(ComplianceFinding::code)
        .contains(ErrorCodes.RECON_ARCHIVE_MISSING);
  }

  @Test
  void an_archive_store_that_silently_overwrites_is_detected_by_reconciliation() {
    // I-06's other half. A store that ignores the conditional header answers 200 and keeps the
    // second writer's bytes; the object is present, so only a re-hash can see it. The exhaustive
    // rescan is Pro, and this bounded sample of the newest documents is why the free core can
    // still notice at all.
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_overwritten"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_overwritten"));
    Issuance issuance = harness.issuance("in_overwritten").orElseThrow();
    harness
        .archive()
        .putDirectly(
            issuance.archiveKey(),
            "<Invoice>somebody else</Invoice>".getBytes(StandardCharsets.UTF_8));
    harness.moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.archiveDrift()).isEqualTo(1);
    assertThat(harness.openFindings())
        .extracting(ComplianceFinding::code)
        .contains(ErrorCodes.RECON_ARCHIVE_DRIFT);
  }

  @Test
  void an_archive_object_that_no_row_predicted_is_found_and_never_deleted() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String orphan =
        "live/" + harness.sellerId() + "/2026/INV-2026-000099-" + "c".repeat(64) + ".xml";
    harness.archive().putDirectly(orphan, "<Invoice/>".getBytes(StandardCharsets.UTF_8));

    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.orphanObjects()).isEqualTo(1);
    assertThat(harness.openFindings())
        .extracting(ComplianceFinding::code)
        .contains(ErrorCodes.RECON_ORPHAN_OBJECT);
    // Still there. An object we cannot explain is evidence of something, and a library that tidies
    // it away has destroyed the only copy of that something.
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  @Test
  void a_neighbour_seller_whose_id_starts_with_ours_is_not_an_orphan_of_ours() {
    // D-PR51-5: one archive, two sellers, "acme" and "acme2". The listing under "live/acme" without
    // a trailing slash also matches every key of "acme2".
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String neighbour =
        "live/" + harness.sellerId() + "2/2026/INV-2026-000001-" + "d".repeat(64) + ".xml";
    harness.archive().putDirectly(neighbour, "<Invoice/>".getBytes(StandardCharsets.UTF_8));

    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.orphanObjects()).isZero();
    assertThat(harness.openFindings())
        .extracting(ComplianceFinding::code)
        .doesNotContain(ErrorCodes.RECON_ORPHAN_OBJECT);
  }

  @Test
  void a_number_open_past_the_alert_after_is_found() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_stuck"));
    harness.renderer().breakWith(new IssuanceCrashTest.KillSignal());
    String eventId = harness.receive("invoice.finalized", "in_stuck");
    assertThatThrownBy(() -> harness.unitOfWork().process(eventId))
        .isInstanceOf(IssuanceCrashTest.KillSignal.class);

    harness.moveClockForward(Duration.ofHours(8));
    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.stuckIssuances()).isEqualTo(1);
    assertThat(harness.openFindings())
        .extracting(ComplianceFinding::code)
        .contains(ErrorCodes.RECON_STUCK_ISSUANCE);
  }

  // D-PR50-B1: the stuck check reads open numbers by seller and mode, any series year, oldest
  // first, and runs before the Stripe list call. Each test below was red on main at 7f08181.

  /** A refused key throws at the list call; the stuck number is still named by that pass. */
  @Test
  void a_number_stuck_while_the_stripe_list_fails_is_named_by_the_same_pass() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String stuck = "in_stuck_list_down_" + System.nanoTime();
    RetryEdgeFixture.numberWithArchiveDown(harness, stuck);
    harness.moveClockForward(Duration.ofHours(7));
    harness
        .source()
        .breakWith(new EInvoiceException(ErrorCodes.STRIPE_ACCESS_REFUSED, "status 401"));

    assertThatThrownBy(() -> harness.sweep(SETTINGS).sweep()).isInstanceOf(EInvoiceException.class);

    assertThat(harness.openFindings())
        .anySatisfy(
            f -> {
              assertThat(f.code()).isEqualTo(ErrorCodes.RECON_STUCK_ISSUANCE);
              assertThat(f.subjectId()).isEqualTo(stuck);
            });
  }

  /** The application is down across the year end: the first pass of the new year names it. */
  @Test
  void a_number_stuck_in_a_year_the_window_no_longer_touches_is_named() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String stuck = "in_stuck_last_year_" + System.nanoTime();
    RetryEdgeFixture.numberWithArchiveDown(harness, stuck);
    harness.moveClockTo(Instant.parse("2027-03-10T09:00:00Z"));

    ReconciliationSweep.Result result =
        harness
            .sweep(
                new ReconciliationSweep.Settings(
                    Duration.ofDays(2), Duration.ZERO, Duration.ofHours(6), 10, 500))
            .sweep();

    assertThat(result.stuckIssuances()).isEqualTo(1);
    assertThat(harness.openFindings()).extracting(ComplianceFinding::subjectId).contains(stuck);
  }

  /** More newer numbers than one page came after it: it is still named, oldest first. */
  @Test
  void a_stuck_number_behind_a_page_of_newer_numbers_is_named() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String stuck = "in_stuck_old_" + System.nanoTime();
    RetryEdgeFixture.numberWithArchiveDown(harness, stuck);
    for (int i = 0; i < 2; i++) {
      String fine = "in_fine_newer_" + i + "_" + System.nanoTime();
      harness.source().with(TestInvoices.finalised(fine));
      harness.unitOfWork().process(harness.receive("invoice.finalized", fine));
    }
    harness.moveClockForward(Duration.ofHours(7));

    ReconciliationSweep.Result result =
        harness
            .sweep(
                new ReconciliationSweep.Settings(
                    Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 1))
            .sweep();

    assertThat(result.stuckIssuances()).isEqualTo(1);
    assertThat(harness.openFindings())
        .filteredOn(f -> f.code().equals(ErrorCodes.RECON_STUCK_ISSUANCE))
        .extracting(ComplianceFinding::subjectId)
        .containsExactly(stuck);
  }

  /**
   * One stuck number is one finding and one count, never two (the series loop no longer counts).
   */
  @Test
  void a_stuck_number_inside_the_window_is_counted_once() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String stuck = "in_stuck_once_" + System.nanoTime();
    RetryEdgeFixture.numberWithArchiveDown(harness, stuck);
    harness.moveClockForward(Duration.ofHours(7));

    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.stuckIssuances()).isEqualTo(1);
    assertThat(harness.openFindings())
        .filteredOn(f -> f.code().equals(ErrorCodes.RECON_STUCK_ISSUANCE))
        .extracting(ComplianceFinding::subjectId)
        .containsExactly(stuck);
  }

  /** The read behind the stuck check: open states, older than the cutoff, oldest first, scoped. */
  @Test
  void open_numbers_are_read_by_seller_and_mode_oldest_first_and_bounded() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    IssuanceTestHarness other = IssuanceTestHarness.create();
    String first = "in_open_first_" + System.nanoTime();
    RetryEdgeFixture.numberWithArchiveDown(harness, first);
    harness.moveClockForward(Duration.ofMinutes(5));
    String second = "in_open_second_" + System.nanoTime();
    RetryEdgeFixture.numberWithArchiveDown(harness, second);
    harness.moveClockForward(Duration.ofMinutes(5));
    String issued = "in_issued_" + System.nanoTime();
    harness.source().with(TestInvoices.finalised(issued));
    harness.unitOfWork().process(harness.receive("invoice.finalized", issued));
    harness.moveClockForward(Duration.ofMinutes(5));
    String young = "in_open_young_" + System.nanoTime();
    RetryEdgeFixture.numberWithArchiveDown(harness, young);
    String foreign = "in_open_foreign_" + System.nanoTime();
    RetryEdgeFixture.numberWithArchiveDown(other, foreign);
    Instant cutoff = harness.issuance(young).orElseThrow().allocatedAt();

    assertThat(harness.reader().openOlderThan(harness.sellerId(), Mode.LIVE, cutoff, 10))
        .extracting(Issuance::stripeInvoiceId)
        .containsExactly(first, second);
    assertThat(harness.reader().openOlderThan(harness.sellerId(), Mode.LIVE, cutoff, 1))
        .extracting(Issuance::stripeInvoiceId)
        .containsExactly(first);
    assertThat(harness.reader().openOlderThan(harness.sellerId(), Mode.TEST, cutoff, 10)).isEmpty();
  }

  @Test
  void a_document_that_is_where_it_should_be_raises_nothing() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_fine"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_fine"));
    harness.moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = harness.sweep(SETTINGS).sweep();

    assertThat(result.total()).isZero();
    assertThat(harness.openFindings()).isEmpty();
  }

  @Test
  void an_acknowledgement_records_a_reason_and_does_not_delete_the_finding() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_ack"));
    harness.moveClockForward(Duration.ofHours(1));
    harness.sweep(SETTINGS).sweep();

    harness
        .findings()
        .acknowledge(
            harness.sellerId(),
            Mode.LIVE,
            ErrorCodes.RECON_MISSING_ISSUANCE,
            "in_ack",
            "issued by hand from the accountant's copy",
            java.time.Instant.parse("2026-02-01T10:00:00Z"));

    assertThat(harness.openFindings())
        .filteredOn(finding -> finding.subjectId().equals("in_ack"))
        .isEmpty();
    // The row stays: an acknowledgement is evidence of a decision, not a delete with extra steps.
    assertThat(harness.findingRows("in_ack")).isEqualTo(1);
    assertThat(
            harness.findings().openCounts(harness.sellerId(), Mode.LIVE).stream()
                .map(FindingStore.CodeCount::code))
        .doesNotContain(ErrorCodes.RECON_MISSING_ISSUANCE);
  }

  @Test
  void an_acknowledgement_reason_is_screened_like_every_other_auditor_facing_text() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_screened"));
    harness.moveClockForward(Duration.ofHours(1));
    harness.sweep(SETTINGS).sweep();

    assertThatThrownBy(
            () ->
                harness
                    .findings()
                    .acknowledge(
                        harness.sellerId(),
                        Mode.LIVE,
                        ErrorCodes.RECON_MISSING_ISSUANCE,
                        "in_screened",
                        String.valueOf((char) 0x202E) + "reversed" + (char) 0x202C + " text",
                        java.time.Instant.parse("2026-02-01T10:00:00Z")))
        .isInstanceOf(EInvoiceException.class);
  }

  @Test
  void acknowledging_something_that_is_not_open_is_refused_rather_than_silently_accepted() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    assertThatThrownBy(
            () ->
                harness
                    .findings()
                    .acknowledge(
                        harness.sellerId(),
                        Mode.LIVE,
                        ErrorCodes.RECON_STUCK_ISSUANCE,
                        "in_nothing",
                        "nothing to see",
                        java.time.Instant.parse("2026-02-01T10:00:00Z")))
        .isInstanceOf(EInvoiceException.class);
  }

  @Test
  void findings_carry_ids_and_codes_and_no_buyer_field() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_pii", "Sensitive Buyer Name", "open"));
    harness.moveClockForward(Duration.ofHours(1));
    harness.sweep(SETTINGS).sweep();

    assertThat(harness.openFindings())
        .allSatisfy(finding -> assertThat(finding.toString()).doesNotContain("Sensitive"));
  }
}
