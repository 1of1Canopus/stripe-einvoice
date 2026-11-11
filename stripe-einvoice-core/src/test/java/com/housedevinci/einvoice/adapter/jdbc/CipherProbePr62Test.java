package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.FakeStripeSource;
import com.housedevinci.einvoice.application.InboundEventStore.Recorded;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.application.TestInvoices;
import com.housedevinci.einvoice.domain.DateGapCause;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Security review of the skew cure (PR 62), pass 1, promoted into the suite. Each test was a probe
 * red on 9661643; the guard_ test is the control it is measured against.
 */
class CipherProbePr62Test {

  private static final String OLD = "2025-12-15.acacia";
  private static final String NEW = FakeStripeSource.PINNED;

  private static final Instant JAN_3 = Instant.parse("2026-01-03T10:00:00Z");
  private static final Instant JAN_3_LATER = Instant.parse("2026-01-03T11:00:00Z");
  private static final Instant JAN_10 = Instant.parse("2026-01-10T10:00:00Z");

  private static SourceInvoice finalisedAt(String id, Instant finalizedAt) {
    SourceInvoice base = TestInvoices.finalised(id);
    return new SourceInvoice(
        base.id(),
        base.number(),
        base.accountId(),
        base.livemode(),
        base.currency(),
        base.status(),
        finalizedAt,
        base.buyer(),
        base.lines(),
        base.taxBuckets(),
        base.taxTreatments(),
        base.subtotalMinor(),
        base.taxMinor(),
        base.totalMinor(),
        true);
  }

  private static Optional<DateGapCause> causeOf(IssuanceTestHarness h, String invoiceId) {
    return h.issuance(invoiceId).orElseThrow().dating().orElseThrow().cause();
  }

  private static byte[] body(String id, String version) {
    return ("{\"id\":\"" + id + "\",\"api_version\":\"" + version + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static InboundEvent copy(String id, String invoice, String version, Instant at) {
    return InboundEvent.received(
        new EventIdentity(id, "invoice.finalized", version, true, "", invoice),
        Mode.LIVE,
        "primary",
        body(id, version),
        at);
  }

  // ------------------------------------------------------------------------------ D-PR62-1

  /**
   * The control. The webhook came on time, on 3 January, and was refused for skew. The pin then
   * moved to its version and the same row ran on 10 January. No park, no retry, and the first
   * arrival is on the supply day, so the recorded cause is UNRECORDED: nothing in the history
   * explains the gap. This is main's behaviour, kept on the branch.
   */
  @Test
  void guard_a_skew_row_replayed_by_a_pin_move_is_not_a_late_webhook() {
    IssuanceTestHarness h = IssuanceTestHarness.create().pinnedTo(NEW);
    String invoice = "in_p62_ctl_" + System.nanoTime();
    h.moveClockTo(JAN_3_LATER);
    h.source().with(finalisedAt(invoice, JAN_3));
    String eventId = h.receive("invoice.finalized", invoice, OLD, true, "");
    assertThat(h.unitOfWork().process(eventId).state())
        .isEqualTo(InboundState.REFUSED_VERSION_SKEW);

    h.moveClockTo(JAN_10);
    h.pinnedTo(OLD);
    assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);

    assertThat(causeOf(h, invoice)).contains(DateGapCause.UNRECORDED);
  }

  /**
   * D-PR62-1. The same history, repaired by the cure instead: a copy at the pin arrives on 10
   * January. The cure resets {@code received_at}; the cause deriver reads the first arrival, kept
   * in {@code cured_from_received_at}. The webhook came on 3 January and the module refused it, so
   * the cause is UNRECORDED, as for the pin move, never LATE_WEBHOOK.
   */
  @Test
  void a_cured_row_is_not_recorded_as_a_late_webhook() {
    IssuanceTestHarness h = IssuanceTestHarness.create().pinnedTo(NEW);
    String invoice = "in_p62_cure_" + System.nanoTime();
    h.moveClockTo(JAN_3_LATER);
    h.source().with(finalisedAt(invoice, JAN_3));
    String eventId = h.receive("invoice.finalized", invoice, OLD, true, "");
    assertThat(h.unitOfWork().process(eventId).state())
        .isEqualTo(InboundState.REFUSED_VERSION_SKEW);

    h.moveClockTo(JAN_10);
    InboundEvent atThePin = copy(eventId, invoice, NEW, h.now());
    assertThat(h.inbound().record(atThePin, body(eventId, NEW), NEW)).isEqualTo(Recorded.CURED);
    assertThat(h.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);

    assertThat(causeOf(h, invoice))
        .as("the webhook came on the supply day; a cure is not a late webhook")
        .isNotEqualTo(Optional.of(DateGapCause.LATE_WEBHOOK))
        .contains(DateGapCause.UNRECORDED);
  }

  // ------------------------------------------------------------------------------ D-PR62-2

  private static Instant instantColumn(String id, String column) {
    try (Connection c = PostgresSupport.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT " + column + " FROM einvoice_inbound_event WHERE event_id = ?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * D-PR62-2. Two cures (two pins live at once, the case D-SKEW-2 covers): OLD at T0, cured to NEW
   * at T1, cured back to OLD at T2 on a node still pinned to OLD. The first arrival T0 is kept
   * across both cures (D-SKEW-8).
   */
  @Test
  void a_second_cure_keeps_the_first_arrival() {
    JdbcInboundEventStore store =
        new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(PostgresSupport.dataSource()));
    String id = "evt_" + UUID.randomUUID().toString().replace("-", "");
    String invoice = "in_" + id;
    Instant t0 = Instant.parse("2026-09-15T10:00:00Z");
    Instant t1 = t0.plus(Duration.ofDays(1));
    Instant t2 = t1.plus(Duration.ofDays(1));

    assertThat(store.record(copy(id, invoice, OLD, t0), body(id, OLD), NEW))
        .isEqualTo(Recorded.FIRST);
    store.transition(
        id, InboundState.REFUSED_VERSION_SKEW, ErrorCodes.API_VERSION_SKEW, "", t0, null);
    assertThat(store.record(copy(id, invoice, NEW, t1), body(id, NEW), NEW))
        .isEqualTo(Recorded.CURED);
    assertThat(instantColumn(id, "cured_from_received_at")).isEqualTo(t0);

    assertThat(store.record(copy(id, invoice, OLD, t2), body(id, OLD), OLD))
        .isEqualTo(Recorded.CURED);
    assertThat(instantColumn(id, "cured_from_received_at"))
        .as("the first arrival of the event, kept across every cure")
        .isEqualTo(t0);
  }

  // ------------------------------------------------------------------------------ D-PR62-3

  private static IssuanceUnitOfWork.Configuration configurationPinnedTo(String pin) {
    return new IssuanceUnitOfWork.Configuration(
        "seller-1",
        "",
        Mode.LIVE,
        "INV",
        false,
        ZoneId.of("Europe/Paris"),
        pin,
        "fr-2026.1",
        Duration.ofMinutes(1),
        Duration.ofHours(1),
        Optional.empty(),
        false);
  }

  /**
   * D-PR62-3. A blank pin is refused when the configuration is built, so the application does not
   * start. The store keeps its own check (D-SKEW-10), but no signed webhook reaches it with a blank
   * pin and gets a 400.
   */
  @Test
  void a_blank_pin_is_refused_when_the_configuration_is_built() {
    for (String pin : new String[] {"", " ", "\t", null}) {
      assertThatThrownBy(() -> configurationPinnedTo(pin))
          .as("pin [%s]", pin)
          .isInstanceOf(EInvoiceException.class)
          .extracting(e -> ((EInvoiceException) e).code())
          .isEqualTo(ErrorCodes.CONFIG);
    }
  }
}
