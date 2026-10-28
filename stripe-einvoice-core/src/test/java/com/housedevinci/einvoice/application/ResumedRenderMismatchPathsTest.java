package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.ARCHIVING_NO_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.ARCHIVING_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_NO_HASH;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_NO_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.burnLine;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Hashes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesReport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * D-RTR-5: a resumed run whose bytes pass validation but differ from the recorded document. One
 * test per path of the resumed render hash mismatch design (R1 to R15, and items 1 and 4 of its
 * security review).
 *
 * <p>Example. {@code F-2026-0042}: attempt 1 records hash H1 and its key, the PUT fails. The host
 * upgrades its renderer. The retry finds no object, renders H2, and H2 passes. Before this change
 * the store refused H2 with {@code DEI-240}, the row stayed {@code FAILED_ARCHIVE}, nothing was
 * chained, and the sweep re-enqueued it to the ceiling. Now the number burns with {@code DEI-244},
 * H1 and its key are kept, one event is chained, and nothing is archived.
 */
class ResumedRenderMismatchPathsTest {

  private static final ReconciliationSweep.Settings SETTINGS =
      new ReconciliationSweep.Settings(
          Duration.ofDays(30), Duration.ZERO, Duration.ofHours(6), 10, 500);

  private static final String CODE = ErrorCodes.RENDER_NOT_REPRODUCIBLE;

  /** A renderer upgraded between two attempts: valid bytes, not the same bytes. */
  static DocumentRenderer upgraded(IssuanceTestHarness harness) {
    return ArchiveRetryFixture.editing(harness, (xml, input) -> xml + "<!-- renderer 2 -->");
  }

  /** The same bytes as the harness renderer, under another extension, so another key. */
  static DocumentRenderer otherExtension(IssuanceTestHarness harness) {
    DocumentRenderer real = harness.renderer();
    return new DocumentRenderer() {
      @Override
      public RenderedDocument render(DocumentInput input) {
        RenderedDocument d = real.render(input);
        return new RenderedDocument(d.bytes(), "pdf", d.profile());
      }

      @Override
      public PreflightReport preflight(MappingInput input) {
        return real.preflight(input);
      }

      @Override
      public boolean carriesSupplyDate() {
        return true;
      }
    };
  }

  static IssuanceUnitOfWork.Outcome retryUpgraded(Row row) {
    return row.retry(upgraded(row.harness()), TestValidators.passing(), ArchiveRetryFixture.READER);
  }

  /** Burned with DEI-244: H1 and its key kept on the row and the event, nothing archived. */
  static void burnedAsMismatch(Row row, IssuanceUnitOfWork.Outcome outcome) {
    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(outcome.code()).isEqualTo(CODE);
    assertThat(row.event().nextAttemptAt()).as("terminal, no backoff").isNull();
    var now = row.now();
    assertThat(now.state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(now.voidRuleId()).isEqualTo(CODE);
    assertThat(now.voidReason()).isEmpty();
    assertThat(now.documentSha256()).isEqualTo(row.before().documentSha256());
    assertThat(now.archiveKey()).isEqualTo(row.before().archiveKey());
    assertThat(row.chained()).containsExactly(burnLine(now, CODE));
    assertThat(row.harness().archive().size()).isZero();
  }

  /** WARN lines written while {@code action} runs that name a render mismatch burn. */
  static List<String> mismatchWarnings(Runnable action) {
    java.io.PrintStream original = System.err;
    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    return captured
        .toString(StandardCharsets.UTF_8)
        .lines()
        .filter(l -> l.contains(" WARN ") && l.contains(CODE))
        .toList();
  }

  // ------------------------------------------------------------------ R1, R2, item 1

  @Test
  void a_resumed_render_with_another_hash_burns_the_number() {
    // The P8 pin of the archive retry refusal paths, rewritten: it was DEI-240, row open.
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_rm_r1");

    burnedAsMismatch(row, retryUpgraded(row));
  }

  @Test
  void a_resumed_archiving_row_with_another_hash_burns_the_number() {
    Row row = prepare(ARCHIVING_NO_OBJECT, "in_rm_r2");

    burnedAsMismatch(row, retryUpgraded(row));
  }

  @Test
  void a_resumed_render_with_another_extension_burns_the_number() {
    // Security review item 1: the same bytes under another extension are another key.
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_rm_ext");

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(
            otherExtension(row.harness()), TestValidators.passing(), ArchiveRetryFixture.READER);

    burnedAsMismatch(row, outcome);
  }

  @Test
  void a_render_mismatch_burn_logs_both_hashes_and_no_content() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_rm_warn");
    AtomicReference<byte[]> rendered = new AtomicReference<>();
    DocumentRenderer upgraded = upgraded(row.harness());
    DocumentRenderer capturing =
        ArchiveRetryFixture.editing(
            row.harness(),
            (xml, input) -> {
              byte[] bytes = upgraded.render(input).bytes();
              rendered.set(bytes);
              return new String(bytes, StandardCharsets.UTF_8);
            });

    List<String> warnings =
        mismatchWarnings(
            () -> row.retry(capturing, TestValidators.passing(), ArchiveRetryFixture.READER));

    assertThat(warnings)
        .singleElement()
        .satisfies(
            w ->
                assertThat(w)
                    .contains(row.before().legalNumber().value())
                    .contains(row.before().documentSha256())
                    .contains(Hashes.sha256Hex(rendered.get()))
                    .doesNotContain("<")
                    .doesNotContain("Buyer Cooperative"));
  }

  // ------------------------------------------------------------------ R3

  @Test
  void a_reprocess_with_another_hash_burns_the_number() {
    // Same shape as S7 of the archive retry refusal paths: a reprocess re-opens the numbered row
    // in place (D-SRE-2) and meets the same comparison as the sweeper's retry.
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_rm_r3");
    IssuanceTestHarness harness = row.harness();
    AtomicBoolean profileIncomplete = new AtomicBoolean(true);
    DocumentRenderer upgraded = upgraded(harness);
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
            return upgraded.render(input);
          }
        };
    String paid = harness.receive("invoice.paid", "in_rm_r3");
    assertThat(harness.unitOfWorkWithRenderer(repairable).process(paid).state())
        .isEqualTo(InboundState.FAILED_MAPPING);
    profileIncomplete.set(false);
    long numbers = harness.numberedRows();

    IssuanceReprocess.Result result =
        harness.reprocessWith(repairable).reprocess(new ReprocessRequest(paid, "ops", "fixed"));

    assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
    assertThat(result.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(result.code()).isEqualTo(CODE);
    assertThat(harness.numberedRows()).isEqualTo(numbers);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(row.now().voidRuleId()).isEqualTo(CODE);
    assertThat(row.chained()).containsExactly(burnLine(row.now(), CODE));
    assertThat(harness.archive().size()).isZero();
  }

  // ------------------------------------------------------------------ R4, R5, R6

  @Test
  void other_bytes_at_the_key_are_still_a_content_conflict() {
    Row row = prepare(ARCHIVING_WITH_OBJECT, "in_rm_r4");
    row.harness()
        .archive()
        .putDirectly(row.before().archiveKey(), "planted".getBytes(StandardCharsets.UTF_8));
    int renders = row.harness().renderer().renders();

    IssuanceUnitOfWork.Outcome outcome = retryUpgraded(row);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.ARCHIVE_CONTENT_CONFLICT);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_ARCHIVE);
    assertThat(row.now().voidRuleId()).isEmpty();
    assertThat(row.chained()).isEmpty();
    assertThat(row.harness().renderer().renders()).isEqualTo(renders);
  }

  @Test
  void a_resumed_render_with_the_same_hash_is_issued() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_rm_r5");

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(row.harness().renderer(), TestValidators.passing(), ArchiveRetryFixture.READER);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(row.now().state()).isEqualTo(IssuanceState.ISSUED);
    assertThat(row.now().documentSha256()).isEqualTo(row.before().documentSha256());
    assertThat(row.harness().archive().size()).isEqualTo(1);
  }

  @Test
  void a_refusal_is_reported_before_the_hash_check() {
    // R6, D-RTR-6: the backdated bytes differ from H1 and are refused. The refusal names them.
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_rm_r6");

    IssuanceUnitOfWork.Outcome outcome = row.retryBackdated();

    assertThat(outcome.code()).isEqualTo(ErrorCodes.VALIDATION_REFUSED);
    assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(row.now().voidRuleId()).isEqualTo(DocumentDatesRule.RULE_ID).isNotEqualTo(CODE);
  }

  // ------------------------------------------------------------------ R8, R8b

  @Test
  void a_first_attempt_has_no_hash_check() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_rm_r8"));
    String eventId = harness.receive("invoice.finalized", "in_rm_r8");

    IssuanceUnitOfWork.Outcome outcome =
        harness
            .unitOfWorkWith(
                upgraded(harness),
                TestValidators.passing(),
                ArchiveRetryFixture.READER,
                harness.archive())
            .process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(outcome.code()).isNotEqualTo(CODE);
    assertThat(harness.issuance("in_rm_r8").orElseThrow().state()).isEqualTo(IssuanceState.ISSUED);
  }

  @Test
  void a_failed_marker_row_has_no_hash_check() {
    // Security review item 4, R8b: the marker itself failed, so there is no H1 to compare.
    Row row = prepare(FAILED_ARCHIVE_NO_HASH, "in_rm_r8b");

    IssuanceUnitOfWork.Outcome outcome = retryUpgraded(row);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(row.now().state()).isEqualTo(IssuanceState.ISSUED);
    assertThat(row.harness().archive().size()).isEqualTo(1);
  }

  // ------------------------------------------------------------------ R12, R13, R14

  @Test
  void the_sweep_reports_a_render_mismatch_burn_and_does_not_enqueue_it() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_rm_r12");
    retryUpgraded(row);
    row.harness().moveClockForward(Duration.ofHours(7));

    ReconciliationSweep.Result result = row.harness().sweep(SETTINGS).sweep();

    assertThat(result.burnedNoDocument()).isEqualTo(1);
    assertThat(result.missingIssuance()).isZero();
    assertThat(result.stuckIssuances()).isZero();
    assertThat(row.harness().openFindings())
        .extracting(ComplianceFinding::code)
        .contains(ErrorCodes.RECON_BURNED_NO_DOCUMENT)
        .doesNotContain(ErrorCodes.RECON_MISSING_ISSUANCE, ErrorCodes.RECON_STUCK_ISSUANCE);
    assertThat(row.harness().inbound().find("recon-in_rm_r12")).isEmpty();
  }

  @Test
  void a_later_event_after_a_render_mismatch_burn_is_terminal() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_rm_r13");
    retryUpgraded(row);
    int renders = row.harness().renderer().renders();

    String paid = row.harness().receive("invoice.paid", "in_rm_r13");
    IssuanceUnitOfWork.Outcome outcome = row.harness().unitOfWork().process(paid);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(row.harness().inbound().find(paid).orElseThrow().nextAttemptAt()).isNull();
    assertThat(row.harness().renderer().renders()).isEqualTo(renders);
    assertThat(row.now().voidRuleId()).isEqualTo(CODE);
    assertThat(row.chained()).hasSize(1);
  }

  @Test
  void the_series_report_shows_a_render_mismatch_burn() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_rm_r14");
    retryUpgraded(row);

    SeriesReport report = row.harness().store().seriesReport(row.before().seriesKey());

    assertThat(report.lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
              assertThat(line.voidRuleId()).isEqualTo(CODE);
              assertThat(line.voidReason()).isEmpty();
            });
  }

  // ------------------------------------------------------------------ R15

  @Test
  void a_void_during_a_render_mismatch_wins() {
    // Security review item 4, R15: an operator void lands between the render and the burn.
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_rm_r15");
    IssuanceTestHarness harness = row.harness();
    DocumentRenderer voidingWhileRendering =
        ArchiveRetryFixture.editing(
            harness,
            (xml, input) -> {
              harness
                  .store()
                  .voidUnused(
                      new VoidRequest(
                          harness.sellerId(), Mode.LIVE, "in_rm_r15", "renderer drift", "OPS-1"));
              return xml + "<!-- renderer 2 -->";
            });

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(voidingWhileRendering, TestValidators.passing(), ArchiveRetryFixture.READER);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.NUMBER_VOIDED);
    assertThat(row.now().state()).isEqualTo(IssuanceState.VOID_UNUSED);
    assertThat(row.now().voidRuleId()).isEqualTo("OPS-1");
    assertThat(row.chained())
        .singleElement()
        .satisfies(e -> assertThat(e).startsWith("VOID_UNUSED|"));
    assertThat(harness.archive().size()).isZero();
  }
}
