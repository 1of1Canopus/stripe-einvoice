package com.housedevinci.einvoice.domain;

import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;

/**
 * A numbering series before its fiscal year is known: {@code (seller, series, mode, type)}.
 *
 * <p>An allocation request names a scope, not a {@link SeriesKey}. The fiscal year of a resetting
 * series is the year of the issue day, and the issue day is the day the number is given, read from
 * the database clock under the series lock (D-PS1-41). No caller can know it in advance, so no
 * caller passes it.
 */
public record SeriesScope(
    String sellerId, String series, Mode mode, DocumentTypeCode documentType) {

  public SeriesScope {
    // The same rules as the key, checked by building one in a year every key accepts.
    new SeriesKey(sellerId, series, SeriesKey.CONTINUOUS, mode, documentType);
  }

  /** An invoice scope ({@code 380}). */
  public SeriesScope(String sellerId, String series, Mode mode) {
    this(sellerId, series, mode, DocumentTypeCode.COMMERCIAL_INVOICE);
  }

  /** The series of this scope in one fiscal year, or {@link SeriesKey#CONTINUOUS}. */
  public SeriesKey inFiscalYear(int fiscalYear) {
    return new SeriesKey(sellerId, series, fiscalYear, mode, documentType);
  }
}
