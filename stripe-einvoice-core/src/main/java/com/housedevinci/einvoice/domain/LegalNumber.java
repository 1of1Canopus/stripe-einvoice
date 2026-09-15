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
    if (counter < 1 || counter > definition.lastRenderableNumber()) {
      throw new EInvoiceException(
          ErrorCodes.SERIES_EXHAUSTED,
          "counter "
              + counter
              + " is outside what width "
              + definition.width()
              + " can render (1.."
              + definition.lastRenderableNumber()
              + ")");
    }
    String padded = Long.toString(counter);
    StringBuilder sb = new StringBuilder(definition.prefix());
    for (int i = padded.length(); i < definition.width(); i++) {
      sb.append('0');
    }
    return new LegalNumber(sb.append(padded).toString(), counter);
  }

  /**
   * The widest number this series can ever render, used to validate a profile's BT-1 length and
   * pattern rules <em>before</em> a number is allocated, so a validation failure caused by the
   * number itself cannot strand a consumed number (numbering design, section 3).
   */
  public static LegalNumber probeOfMaximumWidth(SeriesDefinition definition) {
    return render(definition, definition.lastRenderableNumber());
  }
}
