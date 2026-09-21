package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.time.LocalDate;

/**
 * Everything a renderer needs to map one invoice <b>except the legal number</b>: the stage of the
 * input that exists before the allocator has run.
 *
 * <p>This is what {@link DocumentRenderer#preflight(MappingInput)} is given, and it is the same
 * object the render is given a moment later inside a {@link DocumentInput}. One construction, two
 * consumers, on purpose: the issue date is derived once, in the seller's tax zone, and handed to
 * both. A second derivation would be a second date rule, and two date rules drift - the preflight
 * could pass an invoice whose render then lands in another fiscal year, with nothing in either
 * screen list having changed.
 *
 * @param invoice the authoritative invoice, re-fetched and read to exhaustion
 * @param seriesKey the series this document would be numbered in
 * @param issueDate BT-2, already derived in the seller's declared tax zone, never from a clock
 * @param rulePackVersion the rule pack in force for this run, recorded in the hashed material
 */
public record MappingInput(
    SourceInvoice invoice, SeriesKey seriesKey, LocalDate issueDate, String rulePackVersion) {

  public MappingInput {
    if (invoice == null || seriesKey == null || issueDate == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "a mapping needs an invoice, a series and an issue date");
    }
    rulePackVersion = rulePackVersion == null ? "" : rulePackVersion;
  }
}
