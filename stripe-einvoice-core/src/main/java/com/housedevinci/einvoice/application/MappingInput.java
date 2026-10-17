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
 * consumers, on purpose.
 *
 * <p>It carries no issue date. BT-2 is the day the number is given, read under the series lock
 * (D-PS1-27), so it does not exist yet; {@link DocumentInput} adds it from the allocation.
 *
 * @param invoice the authoritative invoice, re-fetched and read to exhaustion
 * @param seriesKey the series this document would be numbered in if it were numbered on its supply
 *     day; provisional, because the allocator picks the fiscal year from the numbering day
 * @param supplyDate the day of {@code finalized_at} in the seller's tax zone (the source of BT-72),
 *     never BT-2
 * @param rulePackVersion the rule pack in force for this run, recorded in the hashed material
 */
public record MappingInput(
    SourceInvoice invoice, SeriesKey seriesKey, LocalDate supplyDate, String rulePackVersion) {

  public MappingInput {
    if (invoice == null || seriesKey == null || supplyDate == null) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "a mapping needs an invoice, a series and a supply date");
    }
    rulePackVersion = rulePackVersion == null ? "" : rulePackVersion;
  }
}
