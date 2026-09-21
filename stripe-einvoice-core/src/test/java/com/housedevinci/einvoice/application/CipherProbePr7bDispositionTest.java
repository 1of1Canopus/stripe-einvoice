package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pass 2 on D5-03. The branch's own probe went green because the D5-01 fix refuses its fixture at
 * the preflight, so no issuance row exists at all and "state != NUMBERED" is true vacuously. This
 * one forces the case D5-03 is actually about: a preflight that PASSES and a render that then
 * refuses after the number was allocated.
 */
class CipherProbePr7bDispositionTest {

  /** Passes the preflight, refuses at render - the renderer-fault case, both catch paths. */
  private record LateRefusal(boolean unchecked) implements DocumentRenderer {
    @Override
    public RenderedDocument render(DocumentInput input) {
      if (unchecked) {
        throw new IllegalStateException("a third-party renderer blew up");
      }
      throw new EInvoiceException(ErrorCodes.MAPPING_INCOMPLETE, "refused after the number");
    }

    @Override
    public PreflightReport preflight(MappingInput input) {
      return PreflightReport.passed();
    }
  }

  private void run(boolean unchecked, String expectedCode) {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = harness.receive("invoice.finalized", "in_late");
    harness.source().with(TestInvoices.finalised("in_late"));
    eventId = harness.receive("invoice.finalized", "in_late");
    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWorkWithRenderer(new LateRefusal(unchecked)).process(eventId);
    Optional<Issuance> issuance = harness.issuance("in_late");
    System.out.println(
        "D7-03 unchecked="
            + unchecked
            + " outcome="
            + outcome.state()
            + " "
            + outcome.code()
            + " numbered="
            + harness.numberedRows()
            + " state="
            + issuance.map(i -> i.state().name()).orElse("<none>")
            + " preflight="
            + outcome.preflight());
    assertThat(issuance).as("the number was allocated, so there is a row to read").isPresent();
    assertThat(issuance.map(i -> i.state().name()).orElse("<none>"))
        .as("a consumed number that will never carry a document carries a disposition")
        .isNotEqualTo("NUMBERED");
    assertThat(outcome.code()).isEqualTo(expectedCode);
  }

  @Test
  void probe_a_checked_render_refusal_after_the_number_carries_a_disposition() {
    run(false, ErrorCodes.MAPPING_INCOMPLETE);
  }

  @Test
  void probe_an_unchecked_render_fault_after_the_number_carries_a_disposition() {
    run(true, ErrorCodes.RENDER_FAILED);
  }
}
