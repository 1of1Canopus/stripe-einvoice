package com.housedevinci.einvoice.domain;

/**
 * The scope of one numbering series: {@code (seller, series, fiscal year, mode)}.
 *
 * <p>0.1.0 configures one seller and one series, and the columns carry one value each. They exist
 * because adding them later is a schema break and a re-numbering, while carrying them now costs a
 * predicate (Decision 1: single seller, but nothing may preclude many).
 *
 * <p>{@code fiscalYear} is {@link #CONTINUOUS} when the series does not reset per year, so a
 * continuous multi-year series is one row rather than a special case in every statement.
 */
public record SeriesKey(String sellerId, String series, int fiscalYear, Mode mode) {

  /** The fiscal-year value of a series configured with {@code fiscal-year-reset=false}. */
  public static final int CONTINUOUS = 0;

  public SeriesKey {
    Identifiers.validate("seller id", sellerId, 64);
    Identifiers.validate("series name", series, 32);
    if (fiscalYear != CONTINUOUS && (fiscalYear < 1970 || fiscalYear > 9999)) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "fiscal year must be between 1970 and 9999, or 0 for a continuous series");
    }
    if (mode == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "mode must not be null");
    }
  }

  public boolean continuous() {
    return fiscalYear == CONTINUOUS;
  }
}
