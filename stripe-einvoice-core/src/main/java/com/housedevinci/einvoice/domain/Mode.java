package com.housedevinci.einvoice.domain;

import java.util.Locale;

/**
 * Live or test, decided at the routing layer from Stripe's own {@code livemode} flag and never from
 * metadata (D-01, checklist line 27).
 *
 * <p>It is part of the series primary key and of every statement's predicate, so a test event
 * cannot reach the live series by construction rather than by a filter someone can forget.
 */
public enum Mode {
  LIVE("live"),
  TEST("test");

  private final String wire;

  Mode(String wire) {
    this.wire = wire;
  }

  /** The value stored in the {@code mode} column and printed in the archive key. */
  public String wire() {
    return wire;
  }

  public static Mode of(String value) {
    if (value == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "mode must not be null");
    }
    String normalised = value.strip().toLowerCase(Locale.ROOT);
    for (Mode mode : values()) {
      if (mode.wire.equals(normalised)) {
        return mode;
      }
    }
    throw new EInvoiceException(
        ErrorCodes.INVALID, "mode must be one of 'live' or 'test' (einvoice.mode)");
  }
}
