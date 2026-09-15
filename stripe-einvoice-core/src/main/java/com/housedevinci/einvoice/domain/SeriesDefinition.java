package com.housedevinci.einvoice.domain;

/**
 * What a configured series renders: its prefix, the zero-padding width, and whether it restarts
 * each fiscal year.
 *
 * <p>Both the startup seed and the allocator's new-fiscal-year insert read the same definition, so
 * a row created on 1 January at 00:00:01 by a process nobody restarted renders exactly what the
 * configured series renders (N-01).
 */
public record SeriesDefinition(String prefix, int width, boolean resetPerFiscalYear) {

  public static final int MIN_WIDTH = 4;
  public static final int MAX_WIDTH = 12;

  public SeriesDefinition {
    if (prefix == null || prefix.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG, "einvoice.numbering.prefix is required and has no default");
    }
    if (prefix.length() > 32 || !prefix.matches("[A-Z0-9-]{1,32}")) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.numbering.prefix must match [A-Z0-9-] and be at most 32 characters");
    }
    if (width < MIN_WIDTH || width > MAX_WIDTH) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.numbering.width must be between " + MIN_WIDTH + " and " + MAX_WIDTH);
    }
  }

  /**
   * The last counter value this width can render without silently widening the number (N-02). At
   * width 6 that is 999999: one invoice a minute for eleven years, so the default is not a real
   * limit - but a series that outgrows its width changes the format of a legal number mid-series,
   * and that is refused at any probability.
   */
  public long lastRenderableNumber() {
    long max = 1;
    for (int i = 0; i < width; i++) {
      max *= 10;
    }
    return max - 1;
  }
}
