package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.ARCHIVING_NO_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.ARCHIVING_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_NO_HASH;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_NO_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.burnLine;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Hashes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.RuleIds;
import com.housedevinci.einvoice.domain.SeriesReport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * QUESTIONS 38, D-DD-B1: what a resumed issuance row may conclude. One test per path of the archive
 * retry refusal design, revision 2 (S1 to S15, plus the pin Q2; the P8 pin moved to {@link
 * ResumedRenderMismatchPathsTest} with D-RTR-5).
 *
 * <p>Example of the defect: {@code F-2026-0042} fails its archive write on 10 January; the retry
 * renders BT-2 = 3 January; EINVOICE-DATES refuses; the row could not record it and the event ended
 * on DEI-113 with the number still open. Example of the trap the fix must not fall into: the first
 * PUT landed behind a timeout, so a valid document for the number is in the archive, and a burn
 * would put a false statement in the chain.
 */
class ArchiveRetryRefusalPathsTest {

  private static final ReconciliationSweep.Settings SETTINGS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);

  private static void burned(
      Row row, IssuanceUnitOfWork.Outcome outcome, String code, String rule) {
    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(outcome.code()).isEqualTo(code).isNotEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
    var now = row.now();
    assertThat(now.state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(now.voidRuleId()).isEqualTo(rule);
    assertThat(now.voidReason()).isEmpty();
    // D-RTR-2: the burn writes the rule id and nothing else.
    assertThat(now.documentSha256()).isEqualTo(row.before().documentSha256());
    assertThat(now.archiveKey()).isEqualTo(row.before().archiveKey());
    assertThat(row.chained()).containsExactly(burnLine(now, rule));
    assertThat(row.harness().archive().size()).isZero();
  }

  /** Not burned: the row is still open, no chained event, and the event is due again. */
  private static void retriedLater(Row row, IssuanceUnitOfWork.Outcome outcome, String code) {
    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(outcome.code()).isEqualTo(code);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_ARCHIVE);
    assertThat(row.now().voidRuleId()).isEmpty();
    assertThat(row.chained()).isEmpty();
    assertThat(row.event().nextAttemptAt()).isNotNull();
  }

  // ------------------------------------------------------------------ S1

  @Test
  void a_refusal_on_retry_without_a_hash_burns_the_number() {
    Row row = prepare(FAILED_ARCHIVE_NO_HASH, "in_s1_p5");

    List<String> warnings =
        ArchiveRetryFixture.resumedBurnWarnings(
            () ->
                burned(
                    row,
                    row.retryBackdated(),
                    ErrorCodes.VALIDATION_REFUSED,
                    DocumentDatesRule.RULE_ID));

    assertThat(row.now().documentHash()).isEmpty();
    assertThat(warnings).as("no recorded hash, so nothing to tell apart").isEmpty();
  }

  @Test
  void a_validator_refusal_on_retry_burns_the_number() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s1_p6b");

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(
            row.harness().renderer(),
            TestValidators.failing("  BR-DE-15  "),
            ArchiveRetryFixture.READER);

    burned(row, outcome, ErrorCodes.VALIDATION_REFUSED, RuleIds.normalise("  BR-DE-15  "));
  }

  @Test
  void a_refusal_on_a_resumed_archiving_row_with_no_object_burns_the_number() {
    Row row = prepare(ARCHIVING_NO_OBJECT, "in_s1_p7");

    burned(row, row.retryBackdated(), ErrorCodes.VALIDATION_REFUSED, DocumentDatesRule.RULE_ID);
  }

  @Test
  void a_burn_after_a_recorded_hash_logs_both_hashes_and_no_content() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s1_warn");
    java.util.concurrent.atomic.AtomicReference<byte[]> refused =
        new java.util.concurrent.atomic.AtomicReference<>();
    DocumentRenderer backdated = ArchiveRetryFixture.backdating(row.harness(), 7);
    DocumentRenderer capturing =
        ArchiveRetryFixture.editing(
            row.harness(),
            (xml, input) -> {
              byte[] bytes = backdated.render(input).bytes();
              refused.set(bytes);
              return new String(bytes, StandardCharsets.UTF_8);
            });

    List<String> warnings =
        ArchiveRetryFixture.resumedBurnWarnings(
            () -> row.retry(capturing, TestValidators.passing(), ArchiveRetryFixture.READER));

    assertThat(warnings)
        .singleElement()
        .satisfies(
            w ->
                assertThat(w)
                    .contains(row.before().documentSha256())
                    .contains(Hashes.sha256Hex(refused.get()))
                    .contains(DocumentDatesRule.RULE_ID)
                    .contains(row.before().legalNumber().value())
                    .doesNotContain("Buyer Cooperative")
                    .doesNotContain("<IssueDate>"));
  }

  // ------------------------------------------------------------------ S2

  @Test
  void a_not_evaluated_verdict_on_retry_burns_the_number() {
    Row fromValidator = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s2_validator");
    burned(
        fromValidator,
        fromValidator.retry(
            fromValidator.harness().renderer(),
            TestValidators.notEvaluated("SCHEMATRON"),
            ArchiveRetryFixture.READER),
        ErrorCodes.VALIDATION_NOT_EVALUATED,
        "SCHEMATRON");

    Row fromReader = prepare(ARCHIVING_NO_OBJECT, "in_s2_reader");
    burned(
        fromReader,
        fromReader.retry(
            fromReader.harness().renderer(),
            TestValidators.passing(),
            bytes -> {
              throw new IllegalStateException("reader bug");
            }),
        ErrorCodes.VALIDATION_NOT_EVALUATED,
        DocumentDatesRule.RULE_ID);
  }

  // ------------------------------------------------------------------ S3

  @Test
  void a_render_refusal_on_retry_burns_the_number() {
    Row refusal = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s3_refusal");
    burned(
        refusal,
        refusal.retry(
            ArchiveRetryFixture.throwing(
                refusal.harness(),
                new EInvoiceException(ErrorCodes.SELLER_PROFILE_INCOMPLETE, "profile")),
            TestValidators.passing(),
            ArchiveRetryFixture.READER),
        ErrorCodes.SELLER_PROFILE_INCOMPLETE,
        ErrorCodes.SELLER_PROFILE_INCOMPLETE);

    Row bug = prepare(ARCHIVING_NO_OBJECT, "in_s3_bug");
    burned(
        bug,
        bug.retry(
            ArchiveRetryFixture.throwing(bug.harness(), new IllegalStateException("renderer bug")),
            TestValidators.passing(),
            ArchiveRetryFixture.READER),
        ErrorCodes.RENDER_FAILED,
        ErrorCodes.RENDER_FAILED);
  }

  // ------------------------------------------------------------------ S4

  @Test
  void a_validator_exception_on_retry_does_not_burn() {
    Row refusal = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s4_refusal");
    IssuanceUnitOfWork.Outcome first =
        refusal.retry(
            refusal.harness().renderer(),
            (bytes, input) -> {
              throw new EInvoiceException(ErrorCodes.VALIDATION_TIMEOUT, "timeout");
            },
            ArchiveRetryFixture.READER);
    retriedLater(refusal, first, ErrorCodes.VALIDATION_TIMEOUT);

    Row bug = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s4_bug");
    IssuanceUnitOfWork.Outcome second =
        bug.retry(
            bug.harness().renderer(),
            (bytes, input) -> {
              throw new IllegalStateException("validator bug");
            },
            ArchiveRetryFixture.READER);
    assertThat(second.code()).isEqualTo(ErrorCodes.VALIDATOR_FAILED);
    assertThat(bug.now().state()).isEqualTo(IssuanceState.FAILED_ARCHIVE);
    assertThat(bug.chained()).isEmpty();
  }

  // ------------------------------------------------------------------ S5

  @Test
  void a_resumed_row_whose_object_exists_is_issued_without_a_render() {
    for (ArchiveRetryFixture.Shape shape :
        List.of(ARCHIVING_WITH_OBJECT, FAILED_ARCHIVE_WITH_OBJECT)) {
      Row row = prepare(shape, "in_s5_" + shape.ordinal());
      int renders = row.harness().renderer().renders();
      ArchiveRetryFixture.Counting validator =
          new ArchiveRetryFixture.Counting(TestValidators.failing("WOULD-BURN"));
      ArchiveRetryFixture.CountingDates dates = new ArchiveRetryFixture.CountingDates();

      IssuanceUnitOfWork.Outcome outcome =
          row.retry(ArchiveRetryFixture.backdating(row.harness(), 7), validator, dates);

      assertThat(outcome.state()).as(shape.name()).isEqualTo(InboundState.COMPLETED);
      assertThat(row.now().state()).isEqualTo(IssuanceState.ISSUED);
      assertThat(row.now().documentSha256()).isEqualTo(row.before().documentSha256());
      assertThat(row.harness().renderer().renders()).isEqualTo(renders);
      assertThat(validator.calls).hasValue(0);
      assertThat(dates.calls).hasValue(0);
      assertThat(row.harness().archive().size()).isEqualTo(1);
      assertThat(row.chained())
          .singleElement()
          .satisfies(e -> assertThat(e).startsWith("ISSUED|" + row.before().documentSha256()));
    }
  }

  // ------------------------------------------------------------------ S6, S6b

  @Test
  void an_archive_read_failure_on_resume_is_retried() {
    Row unavailable = prepare(ARCHIVING_WITH_OBJECT, "in_s6_unavailable");
    IssuanceUnitOfWork.Outcome first =
        unavailable.retry(
            unavailable.harness().renderer(),
            TestValidators.failing("WOULD-BURN"),
            ArchiveRetryFixture.READER,
            ArchiveRetryFixture.unreadable(
                unavailable.harness().archive(),
                new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "503")));
    retriedLater(unavailable, first, ErrorCodes.ARCHIVE_UNAVAILABLE);

    Row bug = prepare(FAILED_ARCHIVE_WITH_OBJECT, "in_s6_bug");
    IssuanceUnitOfWork.Outcome second =
        bug.retry(
            bug.harness().renderer(),
            TestValidators.failing("WOULD-BURN"),
            ArchiveRetryFixture.READER,
            ArchiveRetryFixture.unreadable(
                bug.harness().archive(), new IllegalStateException("store bug")));
    retriedLater(bug, second, ErrorCodes.ARCHIVE_FAILED);
  }

  @Test
  void other_bytes_at_the_key_are_a_content_conflict() {
    Row row = prepare(ARCHIVING_WITH_OBJECT, "in_s6b");
    row.harness()
        .archive()
        .putDirectly(row.before().archiveKey(), "planted".getBytes(StandardCharsets.UTF_8));

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(
            row.harness().renderer(),
            TestValidators.failing("WOULD-BURN"),
            ArchiveRetryFixture.READER);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.ARCHIVE_CONTENT_CONFLICT);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_ARCHIVE);
    assertThat(row.chained()).isEmpty();
    assertThat(row.event().nextAttemptAt()).as("a conflict does not heal by waiting").isNull();
  }

  // ------------------------------------------------------------------ pin: Q2

  // P8 (D-RTR-5): the pin a_different_hash_on_retry_is_a_content_conflict is rewritten as
  // ResumedRenderMismatchPathsTest.a_resumed_render_with_another_hash_burns_the_number.

  @Test
  void an_object_landing_after_the_burn_is_reported() {
    // D-RTR-4: the PUT timed out, the read found nothing, the object lands after the burn. The
    // sweep's fifth check reports it; the full path table is DisposedNumberArchivePathsTest.
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_q2");
    row.retryBackdated();
    row.harness()
        .archive()
        .putDirectly(row.before().archiveKey(), "late".getBytes(StandardCharsets.UTF_8));
    row.harness().moveClockForward(Duration.ofHours(1));

    ReconciliationSweep.Result result = row.harness().sweep(SETTINGS).sweep();

    assertThat(result.burnedNoDocument()).isEqualTo(1);
    assertThat(result.orphanObjects()).isZero();
    assertThat(result.disposedWithObject()).isEqualTo(1);
    assertThat(result.archiveDrift()).isEqualTo(1); // "late" is not the recorded bytes
  }

  // ------------------------------------------------------------------ S7

  @Test
  void a_reprocess_that_resumes_a_numbered_row_meets_the_same_refusal_as_the_retry() {
    // S7. Until the sweeper retry edges, IssuanceReprocess refused any event whose invoice held a
    // number (REFUSED_NUMBERED), so it could not reach a resumed FAILED_ARCHIVE row. D-SRE-2
    // (approved by the security review, round 2) re-opens a numbered row in place and resumes its
    // number, so the reprocess now reaches that row and goes through the run's own refusal: the
    // backdated document is refused by EINVOICE-DATES and the number burns, exactly as on the
    // sweeper's retry (S1 to S6). No second number, no document, the burn chained once.
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s7");
    IssuanceTestHarness harness = row.harness();
    AtomicBoolean profileIncomplete = new AtomicBoolean(true);
    DocumentRenderer backdated = ArchiveRetryFixture.backdating(harness, 3);
    DocumentRenderer repairable =
        new DocumentRenderer() {
          @Override
          public boolean carriesSupplyDate() {
            return true;
          }

          @Override
          public PreflightReport preflight(MappingInput input) {
            return profileIncomplete.get()
                ? PreflightReport.refused(ErrorCodes.SELLER_PROFILE_INCOMPLETE)
                : PreflightReport.passed();
          }

          @Override
          public RenderedDocument render(DocumentInput input) {
            return backdated.render(input);
          }
        };
    String paid = harness.receive("invoice.paid", "in_s7");
    assertThat(harness.unitOfWorkWithRenderer(repairable).process(paid).state())
        .isEqualTo(InboundState.FAILED_MAPPING);
    profileIncomplete.set(false);
    long numbers = harness.numberedRows();

    IssuanceReprocess.Result result =
        harness.reprocessWith(repairable).reprocess(new ReprocessRequest(paid, "ops", "fixed"));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(result.code()).isEqualTo(ErrorCodes.VALIDATION_REFUSED);
    assertThat(harness.numberedRows()).isEqualTo(numbers);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(row.now().voidRuleId()).isEqualTo(DocumentDatesRule.RULE_ID);
    assertThat(row.chained()).containsExactly(burnLine(row.now(), DocumentDatesRule.RULE_ID));
    assertThat(harness.archive().size()).isZero();
  }

  // ------------------------------------------------------------------ S8

  @Test
  void the_sweep_reports_a_burned_retry_and_does_not_enqueue_it() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s8");
    row.retryBackdated();
    row.harness().moveClockForward(Duration.ofHours(7));

    ReconciliationSweep.Result result = row.harness().sweep(SETTINGS).sweep();

    assertThat(result.burnedNoDocument()).isEqualTo(1);
    assertThat(result.missingIssuance()).isZero();
    assertThat(result.stuckIssuances()).isZero();
    assertThat(row.harness().openFindings())
        .extracting(ComplianceFinding::code)
        .contains(ErrorCodes.RECON_BURNED_NO_DOCUMENT)
        .doesNotContain(ErrorCodes.RECON_MISSING_ISSUANCE, ErrorCodes.RECON_STUCK_ISSUANCE);
    assertThat(row.harness().inbound().find("recon-in_s8")).isEmpty();
  }

  // ------------------------------------------------------------------ S9

  @Test
  void a_void_during_the_retry_wins() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s9_race");
    IssuanceTestHarness harness = row.harness();
    DocumentRenderer voidingWhileRendering =
        ArchiveRetryFixture.editing(
            harness,
            (xml, input) -> {
              harness
                  .store()
                  .voidUnused(
                      new VoidRequest(
                          harness.sellerId(), Mode.LIVE, "in_s9_race", "archive lost", "OPS-1"));
              return xml.replace(
                  "<IssueDate>" + input.issueDate() + "</IssueDate>",
                  "<IssueDate>" + input.issueDate().minusDays(7) + "</IssueDate>");
            });
    IssuanceUnitOfWork.Outcome outcome =
        row.retry(voidingWhileRendering, TestValidators.passing(), ArchiveRetryFixture.READER);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.NUMBER_VOIDED);
    assertThat(row.now().state()).isEqualTo(IssuanceState.VOID_UNUSED);
    assertThat(row.now().voidRuleId()).isEqualTo("OPS-1");
    assertThat(row.chained())
        .singleElement()
        .satisfies(e -> assertThat(e).startsWith("VOID_UNUSED|"));
  }

  @Test
  void a_burned_retry_can_be_voided() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s9_after");
    row.retryBackdated();

    row.harness()
        .store()
        .voidUnused(
            new VoidRequest(
                row.harness().sellerId(), Mode.LIVE, "in_s9_after", "dates refused", "OPS-2"));

    assertThat(row.now().state()).isEqualTo(IssuanceState.VOID_UNUSED);
    assertThat(row.now().voidRuleId()).isEqualTo("OPS-2");
    assertThat(row.now().voidReason()).isEqualTo("dates refused");
    assertThat(row.chained()).hasSize(2);
  }

  // ------------------------------------------------------------------ S10

  @Test
  void a_second_burn_writes_no_second_event() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s10");
    row.retryBackdated();

    row.harness()
        .writer()
        .markFailed(
            row.harness().sellerId(),
            Mode.LIVE,
            "in_s10",
            IssuanceState.FAILED_VALIDATION,
            "OTHER-RULE");

    assertThat(row.now().voidRuleId()).isEqualTo(DocumentDatesRule.RULE_ID);
    assertThat(row.chained()).hasSize(1);
  }

  // ------------------------------------------------------------------ S13
  // The verifier reads the whole log, so it runs on its own database:
  // IssuanceChainVerifierTest.the_verifier_reads_a_burned_retry_as_intact.

  // ------------------------------------------------------------------ S14

  @Test
  void the_series_report_shows_a_burned_retry() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s14");
    row.retryBackdated();

    SeriesReport report = row.harness().store().seriesReport(row.before().seriesKey());

    assertThat(report.lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
              assertThat(line.voidRuleId()).isEqualTo(DocumentDatesRule.RULE_ID);
              assertThat(line.voidReason()).isEmpty();
            });
  }

  // ------------------------------------------------------------------ S15

  @Test
  void a_later_event_for_a_burned_retry_is_terminal() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_s15");
    row.retryBackdated();
    int renders = row.harness().renderer().renders();

    String paid = row.harness().receive("invoice.paid", "in_s15");
    IssuanceUnitOfWork.Outcome outcome = row.harness().unitOfWork().process(paid);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.VALIDATION_REFUSED);
    assertThat(row.harness().inbound().find(paid).orElseThrow().nextAttemptAt()).isNull();
    assertThat(row.harness().renderer().renders()).isEqualTo(renders);
    assertThat(row.chained()).hasSize(1);
  }
}
