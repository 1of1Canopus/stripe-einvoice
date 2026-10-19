package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Security review of the date-order change (D-PS1-27), pass 2. D-PR36-7: the date rule lives in one
 * implementation of a port the host may replace (README: "you may still supply your own
 * DocumentRenderer or DocumentValidator bean and ours backs off"). With a host validator, nothing
 * compares the document's dates with the allocation.
 */
class CipherProbePr36Pass2Test {

  @Test
  void probe_a_host_validator_archives_a_document_dated_other_than_its_numbering_day() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(Instant.parse("2026-01-10T10:00:00Z"));
    SourceInvoice base = TestInvoices.finalised("in_hv");
    harness
        .source()
        .with(
            new SourceInvoice(
                base.id(),
                base.number(),
                base.accountId(),
                base.livemode(),
                base.currency(),
                base.status(),
                Instant.parse("2026-01-10T09:00:00Z"),
                base.buyer(),
                base.lines(),
                base.taxBuckets(),
                base.taxTreatments(),
                base.subtotalMinor(),
                base.taxMinor(),
                base.totalMinor(),
                true));
    String eventId = harness.receive("invoice.finalized", "in_hv");
    DocumentRenderer real = harness.renderer();
    DocumentRenderer backdating =
        new DocumentRenderer() {
          @Override
          public RenderedDocument render(DocumentInput input) {
            RenderedDocument d = real.render(input);
            String xml = new String(d.bytes(), StandardCharsets.UTF_8);
            String moved =
                xml.replace(
                    "<IssueDate>" + input.issueDate() + "</IssueDate>",
                    "<IssueDate>" + input.issueDate().minusDays(7) + "</IssueDate>");
            return new RenderedDocument(
                moved.getBytes(StandardCharsets.UTF_8), d.extension(), d.profile());
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

    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWorkWithRenderer(backdating).process(eventId);

    System.out.println(
        "CIPHER host validator: "
            + outcome
            + " bytes="
            + (outcome.state() == InboundState.COMPLETED
                ? new String(harness.archivedBytes("in_hv"), StandardCharsets.UTF_8)
                : ""));
    // A document whose BT-2 is not the allocated day must never reach the archive.
    assertThat(outcome.state()).isNotEqualTo(InboundState.COMPLETED);
  }

  /**
   * Design review R2 (P20). The validator is handed an array and may keep it. A host validator that
   * rewrites the issue date in that array and then answers PASSED must not change what is archived:
   * the archive holds the bytes that were rendered and checked.
   */
  @Test
  void probe_a_validator_that_rewrites_its_array_changes_the_archived_bytes() {
    DocumentValidator rewriting =
        (bytes, input) -> {
          String xml = new String(bytes, StandardCharsets.UTF_8);
          byte[] moved =
              xml.replace(
                      "<IssueDate>" + input.issueDate() + "</IssueDate>",
                      "<IssueDate>" + input.issueDate().minusDays(7) + "</IssueDate>")
                  .getBytes(StandardCharsets.UTF_8);
          System.arraycopy(moved, 0, bytes, 0, Math.min(moved.length, bytes.length));
          return DocumentValidator.Report.passed();
        };
    IssuanceTestHarness harness = IssuanceTestHarness.createWith(rewriting);
    harness.source().with(TestInvoices.finalised("in_rw"));
    String eventId = harness.receive("invoice.finalized", "in_rw");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    String issueDate =
        harness.issuance("in_rw").orElseThrow().dating().orElseThrow().issueDate().toString();
    assertThat(new String(harness.archivedBytes("in_rw"), StandardCharsets.UTF_8))
        .contains("<IssueDate>" + issueDate + "</IssueDate>");
  }

  /**
   * Design review R3 (P18). A host date reader that throws must not leave the event MAPPED, picked
   * again for ever: it ends FAILED_ISSUANCE, not evaluated, with nothing archived.
   */
  @Test
  void probe_a_throwing_date_reader_leaves_the_event_mapped() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_thr"));
    String eventId = harness.receive("invoice.finalized", "in_thr");
    DocumentDates throwing =
        bytes -> {
          throw new NullPointerException("a host reader's own bug");
        };

    IssuanceUnitOfWork.Outcome outcome =
        harness
            .unitOfWorkWith(
                harness.renderer(), TestValidators.passing(), throwing, harness.archive())
            .process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(outcome.code()).isEqualTo(ErrorCodes.VALIDATION_NOT_EVALUATED);
    assertThat(harness.issuance("in_thr").orElseThrow().state())
        .isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(harness.inbound().lastRuleId(eventId)).contains("EINVOICE-DATES");
    assertThat(harness.archive().size()).isZero();
  }
}
