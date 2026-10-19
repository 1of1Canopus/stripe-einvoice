package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.validation.UblDocumentDates;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Security review of PR 39 (D-PR36-6), pass 1. The claim under attack: the bytes the date reader
 * reads are exactly the bytes hashed, keyed and archived, whatever any port does with the array it
 * is given or the array it gave.
 */
class CipherProbePr39Test {

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void backdate(byte[] bytes, DocumentInput input) {
    byte[] moved =
        new String(bytes, StandardCharsets.UTF_8)
            .replace(
                "<IssueDate>" + input.issueDate() + "</IssueDate>",
                "<IssueDate>" + input.issueDate().minusDays(7) + "</IssueDate>")
            .getBytes(StandardCharsets.UTF_8);
    System.arraycopy(moved, 0, bytes, 0, Math.min(moved.length, bytes.length));
  }

  /**
   * A host renderer keeps the array it handed to {@code RenderedDocument}; a colluding host
   * validator backdates that array and its own; a host reader reads, then backdates its own array.
   * The row's hash, the archived object and the rendered bytes must still be one and the same.
   */
  @Test
  void a_port_that_keeps_its_array_cannot_change_the_archived_or_hashed_bytes() throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_c39"));
    String eventId = harness.receive("invoice.finalized", "in_c39");
    DocumentRenderer real = harness.renderer();
    AtomicReference<byte[]> kept = new AtomicReference<>();
    AtomicReference<byte[]> asRendered = new AtomicReference<>();
    DocumentRenderer keeping =
        new DocumentRenderer() {
          @Override
          public RenderedDocument render(DocumentInput input) {
            RenderedDocument d = real.render(input);
            byte[] mine = d.bytes();
            asRendered.set(mine.clone());
            kept.set(mine);
            return new RenderedDocument(mine, d.extension(), d.profile());
          }

          @Override
          public PreflightReport preflight(MappingInput input) {
            return real.preflight(input);
          }

          @Override
          public boolean carriesSupplyDate() {
            return real.carriesSupplyDate();
          }
        };
    DocumentValidator colluding =
        (bytes, input) -> {
          backdate(bytes, input);
          backdate(kept.get(), input);
          return DocumentValidator.Report.passed();
        };
    UblDocumentDates module = new UblDocumentDates();
    DocumentDates rewritingReader =
        bytes -> {
          DocumentDates.FoundDates found = module.read(bytes);
          java.util.Arrays.fill(bytes, (byte) ' ');
          return found;
        };

    IssuanceUnitOfWork.Outcome outcome =
        harness
            .unitOfWorkWith(keeping, colluding, rewritingReader, harness.archive())
            .process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    Issuance row = harness.issuance("in_c39").orElseThrow();
    byte[] archived = harness.archivedBytes("in_c39");
    assertThat(sha256(archived)).as("archived = rendered").isEqualTo(sha256(asRendered.get()));
    assertThat(row.documentSha256()).as("row hash = archived").isEqualTo(sha256(archived));
    assertThat(new String(archived, StandardCharsets.UTF_8))
        .contains("<IssueDate>" + row.dating().orElseThrow().issueDate() + "</IssueDate>");
  }
}
