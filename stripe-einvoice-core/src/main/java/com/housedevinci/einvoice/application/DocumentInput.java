package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.LegalNumber;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.time.LocalDate;

/**
 * What a renderer and a validator are given: the authoritative invoice, the legal number this
 * module allocated for it, and the issue date.
 *
 * @param issueDate BT-2, derived from {@code status_transitions.finalized_at} in the seller
 *     profile's declared tax zone - never in the JVM's default zone, and never from a clock reading
 * @param rulePackVersion recorded on the issuance and inside the hashed material, so a
 *     re-validation years from now uses the pack that was in force
 */
public record DocumentInput(
    SourceInvoice invoice,
    SeriesKey seriesKey,
    LegalNumber legalNumber,
    LocalDate issueDate,
    String rulePackVersion) {

  public DocumentInput {
    if (invoice == null || seriesKey == null || legalNumber == null || issueDate == null) {
      throw new com.housedevinci.einvoice.domain.EInvoiceException(
          com.housedevinci.einvoice.domain.ErrorCodes.INVALID,
          "a document needs an invoice, a series, a legal number and an issue date");
    }
    rulePackVersion = rulePackVersion == null ? "" : rulePackVersion;
  }
}
