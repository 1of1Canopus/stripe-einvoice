package com.housedevinci.einvoice.domain;

/**
 * The number this module puts on a legal document as BT-1: the series prefix followed by the
 * counter, zero-padded to the configured width.
 *
 * <p>Stripe's own {@code invoice.number} is stored beside it as a reference and is never the legal
 * number (Decision 2): it is prefixed per customer, holed by drafts, failed finalisations and
 * voids, and its format is Stripe's to change.
 */
public record LegalNumber(String value, long counter) {

  public LegalNumber {
    Identifiers.validate("legal number", value, 64);
    if (counter < 1) {
      throw new EInvoiceException(ErrorCodes.INVALID, "a legal number's counter starts at 1");
    }
  }

  public static LegalNumber render(SeriesDefinition definition, long counter) {
    return render(definition.prefix(), definition.width(), counter);
  }

  /**
   * Renders from a concrete prefix and width, deliberately never from a {@link SeriesDefinition}
   * inside the allocator (D1-03): the series row is the source of truth for both, once the row
   * exists, so a property edit and a restart cannot silently change the format of a live series.
   * The {@link SeriesDefinition} overload above stays for previews of a template that has no row
   * yet (a startup log line, a unit test), where there is nothing else to render from.
   */
  public static LegalNumber render(String prefix, int width, long counter) {
    long last = lastRenderableNumber(width);
    if (counter < 1 || counter > last) {
      throw new EInvoiceException(
          ErrorCodes.SERIES_EXHAUSTED,
          "counter "
              + counter
              + " is outside what width "
              + width
              + " can render (1.."
              + last
              + ")");
    }
    String padded = Long.toString(counter);
    StringBuilder sb = new StringBuilder(prefix);
    for (int i = padded.length(); i < width; i++) {
      sb.append('0');
    }
    return new LegalNumber(sb.append(padded).toString(), counter);
  }

  /**
   * The last counter value a given width can render without silently widening the number (N-02).
   */
  public static long lastRenderableNumber(int width) {
    long max = 1;
    for (int i = 0; i < width; i++) {
      max *= 10;
    }
    return max - 1;
  }

  /**
   * The widest number this series can ever render, used to validate a profile's BT-1 length and
   * pattern rules <em>before</em> a number is allocated, so a validation failure caused by the
   * number itself cannot strand a consumed number (numbering design, section 3).
   */
  public static LegalNumber probeOfMaximumWidth(SeriesDefinition definition) {
    return render(definition, definition.lastRenderableNumber());
  }

  /** The same probe, from a series row's own concrete prefix and width (D1-03). */
  public static LegalNumber probeOfMaximumWidth(String prefix, int width) {
    return render(prefix, width, lastRenderableNumber(width));
  }
}
