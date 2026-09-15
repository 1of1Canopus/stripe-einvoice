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

  /**
   * D1-01. The token a resetting series's prefix must carry so the fiscal year is part of the
   * rendered number rather than of a property file: two documents of one seller must never carry
   * the same number, and a static prefix cannot promise that across a fiscal-year boundary. This
   * record only carries the template; {@link #resolvePrefix(int)} substitutes the year, and the
   * substituted, concrete value is what a series row stores forever (see {@code SeriesDefinition}'s
   * use in the allocator, which renders from the row, never from this template, once a row exists).
   */
  public static final String YEAR_PLACEHOLDER = "{fiscalYear}";

  public SeriesDefinition {
    if (prefix == null || prefix.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG, "einvoice.numbering.prefix is required and has no default");
    }
    // The placeholder is the one lowercase substring this value may carry; strip it before
    // checking the declared charset so the check is still exactly [A-Z0-9-] otherwise.
    String withoutPlaceholder = prefix.replace(YEAR_PLACEHOLDER, "");
    if (prefix.length() > 32 || !withoutPlaceholder.matches("[A-Z0-9-]*")) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.numbering.prefix must match [A-Z0-9-], optionally with the literal "
              + YEAR_PLACEHOLDER
              + " placeholder, and be at most 32 characters");
    }
    if (width < MIN_WIDTH || width > MAX_WIDTH) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.numbering.width must be between " + MIN_WIDTH + " and " + MAX_WIDTH);
    }
  }

  /** True when this template names the placeholder that {@link #resolvePrefix(int)} substitutes. */
  public boolean hasYearPlaceholder() {
    return prefix.contains(YEAR_PLACEHOLDER);
  }

  /**
   * The concrete prefix for one fiscal year: the placeholder substituted by the year, or the
   * template unchanged when there is none (a continuous series, or one that does not reset).
   *
   * <p>Called exactly once per series row, at the row's creation (startup seed or the allocator's
   * new-fiscal-year insert, numbering design N-01): the resolved value is what the row stores, and
   * every later allocation renders from the row (D1-03), never by calling this again with whatever
   * the configuration happens to say that day.
   */
  public String resolvePrefix(int fiscalYear) {
    return hasYearPlaceholder()
        ? prefix.replace(YEAR_PLACEHOLDER, Integer.toString(fiscalYear))
        : prefix;
  }

  /**
   * The last counter value this width can render without silently widening the number (N-02). At
   * width 6 that is 999999: one invoice a minute for eleven years, so the default is not a real
   * limit - but a series that outgrows its width changes the format of a legal number mid-series,
   * and that is refused at any probability.
   */
  public long lastRenderableNumber() {
    return LegalNumber.lastRenderableNumber(width);
  }
}
