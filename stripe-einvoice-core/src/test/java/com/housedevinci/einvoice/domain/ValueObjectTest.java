package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The small value objects that everything else is built from. */
class ValueObjectTest {

  @Test
  void a_mode_comes_from_a_declared_wire_value_and_nothing_else() {
    assertThat(Mode.of("live")).isEqualTo(Mode.LIVE);
    assertThat(Mode.of(" TEST ")).isEqualTo(Mode.TEST);
    assertThatThrownBy(() -> Mode.of("sandbox")).isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> Mode.of(null)).isInstanceOf(EInvoiceException.class);
  }

  @Test
  void an_identifier_is_a_key_and_not_free_text() {
    assertThat(Identifiers.validate("seller id", "acme-fr")).isEqualTo("acme-fr");
    assertThatThrownBy(() -> Identifiers.validate("seller id", " acme"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("whitespace");
    assertThatThrownBy(() -> Identifiers.validate("seller id", "acme fr"))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("U+20");
    assertThatThrownBy(() -> Identifiers.validate("seller id", ""))
        .isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> Identifiers.validate("seller id", "a".repeat(65), 64))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("max 64");
  }

  @Test
  void a_series_key_carries_the_seller_the_year_and_the_mode() {
    SeriesKey key = new SeriesKey("acme", "DEFAULT", 2026, Mode.LIVE);
    assertThat(key.continuous()).isFalse();
    assertThat(new SeriesKey("acme", "DEFAULT", SeriesKey.CONTINUOUS, Mode.LIVE).continuous())
        .isTrue();
    assertThatThrownBy(() -> new SeriesKey("acme", "DEFAULT", 1492, Mode.LIVE))
        .isInstanceOf(EInvoiceException.class);
    assertThatThrownBy(() -> new SeriesKey("acme", "DEFAULT", 2026, null))
        .isInstanceOf(EInvoiceException.class);
  }

  @Test
  void a_timestamp_is_truncated_to_what_the_database_actually_stores() {
    // Checklist line 43. PostgreSQL stores microseconds; a value hashed with nanoseconds and read
    // back without them reports BROKEN on a perfectly good row.
    Instant nanos = Instant.parse("2026-01-15T10:00:00Z").plusNanos(123_456_789);
    assertThat(Timestamps.toStorage(nanos).getNano()).isEqualTo(123_456_000);
    assertThat(Timestamps.toStorage(null)).isNull();
  }

  @Test
  void an_issuance_reports_absence_with_optional_and_never_with_null() {
    Issuance open =
        new Issuance(
            1L,
            new SeriesKey("acme", "DEFAULT", 2026, Mode.LIVE),
            "in_1",
            null,
            null,
            new LegalNumber("INV-2026-000001", 1),
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:00:00Z"),
            null,
            null,
            null,
            IssuanceState.NUMBERED,
            null,
            null);
    assertThat(open.documentHash()).isEmpty();
    assertThat(open.archiveObjectKey()).isEmpty();
    assertThat(open.voidedReason()).isEmpty();
    assertThat(open.voidedRuleId()).isEmpty();
    assertThat(open.stripeAccountId()).isEmpty();
  }

  @Test
  void an_error_carries_a_stable_code_that_survives_its_message() {
    EInvoiceException e = new EInvoiceException(ErrorCodes.SERIES_EXHAUSTED, "message");
    assertThat(e.code()).isEqualTo("DEI-112");
    assertThat(e.toString()).contains("DEI-112");
  }
}
