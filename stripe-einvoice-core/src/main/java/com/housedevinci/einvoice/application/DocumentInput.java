package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.LegalNumber;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.time.LocalDate;

/**
 * What a renderer and a validator are given: the {@link MappingInput} the preflight already
 * approved, plus the legal number this module allocated for it.
 *
 * <p>The composition is the point. The render input <b>is</b> the preflight input plus the number,
 * so "both passes saw the same invoice, the same series and the same issue date" is a fact about
 * the types rather than a sentence in a document: there is one {@code MappingInput}, constructed
 * once before the allocator runs and carried through P2.
 *
 * @param unnumbered everything that existed before the number did
 * @param legalNumber BT-1
 */
public record DocumentInput(MappingInput unnumbered, LegalNumber legalNumber) {

  public DocumentInput {
    if (unnumbered == null || legalNumber == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "a document needs a mapping input and a legal number");
    }
  }

  public SourceInvoice invoice() {
    return unnumbered.invoice();
  }

  public SeriesKey seriesKey() {
    return unnumbered.seriesKey();
  }

  /**
   * BT-2, derived from {@code status_transitions.finalized_at} in the seller profile's declared tax
   * zone - never in the JVM's default zone, and never from a clock reading.
   */
  public LocalDate issueDate() {
    return unnumbered.issueDate();
  }

  /**
   * Recorded on the issuance and inside the hashed material, so a re-validation years from now uses
   * the pack that was in force.
   */
  public String rulePackVersion() {
    return unnumbered.rulePackVersion();
  }
}
