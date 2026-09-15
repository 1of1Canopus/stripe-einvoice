package com.housedevinci.einvoice.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * PostgreSQL's {@code timestamptz} stores microseconds. A JVM {@link Instant} carries nanoseconds,
 * so a value hashed before the insert and re-read after it differ in the last three digits and the
 * chain reads BROKEN on a perfectly good row (checklist line 43).
 *
 * <p>Every timestamp that reaches hashed material or a column goes through here first, on both the
 * producer's and the verifier's side.
 */
public final class Timestamps {

  private Timestamps() {}

  public static Instant toStorage(Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
  }
}
