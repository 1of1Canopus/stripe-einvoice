package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.RunningLimit;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * QUESTIONS 29 against a real PostgreSQL: the due query, the stranded query, the clock and the
 * expiry. A database of its own, because both queries read every row of the table.
 */
class RunningLimitStoreTest {

  private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
  private static final String PIN = "2026-03-31.clover";
  private static final byte[] BODY =
      "{\"id\":\"evt\",\"type\":\"invoice.finalized\"}".getBytes(StandardCharsets.UTF_8);
  private static final RunningLimit LIMIT = RunningLimit.DEFAULT;

  private static DataSource ds;

  @BeforeAll
  static void database() {
    ds = PostgresSupport.freshDatabase("q29_store");
  }

  @AfterAll
  static void close() {
    PostgresSupport.close(ds);
  }

  private static JdbcInboundEventStore store() {
    return new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(ds));
  }

  private static String freshId() {
    return "evt_" + UUID.randomUUID().toString().replace("-", "");
  }

  /** Records an event at {@code at}: its clock starts there. */
  private static String receivedAt(JdbcInboundEventStore store, Instant at) {
    String id = freshId();
    EventIdentity identity = new EventIdentity(id, "invoice.finalized", PIN, true, "", "in_" + id);
    store.record(InboundEvent.received(identity, Mode.LIVE, "primary", BODY, at), BODY);
    return id;
  }

  private static Set<String> ids(List<InboundEvent> rows) {
    return rows.stream().map(InboundEvent::eventId).collect(Collectors.toSet());
  }

  private static Set<String> due(JdbcInboundEventStore store, Instant now) {
    return ids(store.due(now, Duration.ofHours(72), LIMIT, 10_000, PIN));
  }

  private static Set<String> stranded(JdbcInboundEventStore store, Instant now) {
    return ids(store.stranded(now, LIMIT, 10_000));
  }

  private static void setClock(String id, Instant dueSince) {
    try (var c = ds.getConnection();
        var ps =
            c.prepareStatement(
                "UPDATE einvoice_inbound_event SET due_since = ? WHERE event_id = ?")) {
      ps.setObject(1, JdbcSupport.ts(dueSince));
      ps.setString(2, id);
      ps.executeUpdate();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ComplianceFinding finding(String id, Instant at) {
    return ComplianceFinding.of("seller_q29", Mode.LIVE, ErrorCodes.RECON_STUCK_INBOUND, id, at);
  }

  // ---- probe 1 -----------------------------------------------------------------------------

  @Test
  void probe_a_running_row_is_due_for_ever() {
    JdbcInboundEventStore store = store();
    String id = receivedAt(store, NOW.minus(Duration.ofDays(30)));
    store.transition(id, InboundState.FETCHED, "", NOW.minus(Duration.ofDays(30)), null);
    store.transition(id, InboundState.MAPPED, "", NOW.minus(Duration.ofDays(30)), null);

    assertThat(due(store, NOW)).doesNotContain(id);
    assertThat(stranded(store, NOW)).contains(id);
  }

  // ---- probe 3 -----------------------------------------------------------------------------

  @Test
  void probe_a_legitimate_parked_row_is_not_expired() {
    JdbcInboundEventStore store = store();
    Instant t0 = NOW.minus(Duration.ofDays(30));
    String id = receivedAt(store, t0);
    store.transition(id, InboundState.FETCHED, "", t0, null);
    store.transition(id, InboundState.PARKED, ErrorCodes.INVOICE_NOT_FINALISED, t0, null);

    assertThat(due(store, NOW)).contains(id);
    assertThat(stranded(store, NOW)).doesNotContain(id);
  }

  // ---- probe 10, store half: F-1 -----------------------------------------------------------

  @Test
  void probe_a_resuming_parked_row_is_expired() {
    // Parked on day 0, finalised on day 80: PARKED -> FETCHED -> MAPPED. Neither step may inherit
    // the 80 days.
    JdbcInboundEventStore store = store();
    Instant t0 = NOW.minus(Duration.ofDays(80));
    String id = receivedAt(store, t0);
    store.transition(id, InboundState.FETCHED, "", t0, null);
    store.transition(id, InboundState.PARKED, ErrorCodes.INVOICE_NOT_FINALISED, t0, null);

    InboundEvent fetched = store.transition(id, InboundState.FETCHED, "", NOW, null);
    assertThat(stranded(store, NOW.plusSeconds(1))).doesNotContain(id);
    assertThat(due(store, NOW.plusSeconds(1))).contains(id);
    assertThat(fetched.dueSinceInstant()).contains(t0);

    InboundEvent mapped = store.transition(id, InboundState.MAPPED, "", NOW, null);
    assertThat(mapped.dueSinceInstant()).contains(NOW);
    assertThat(stranded(store, NOW.plus(Duration.ofHours(71)))).doesNotContain(id);
    assertThat(stranded(store, NOW.plus(Duration.ofHours(73)))).contains(id);
  }

  @Test
  void probe_a_resuming_failed_issuance_row_is_expired() {
    JdbcInboundEventStore store = store();
    Instant t0 = NOW.minus(Duration.ofDays(4));
    String id = receivedAt(store, t0);
    store.transition(id, InboundState.FETCHED, "", t0, null);
    store.transition(id, InboundState.MAPPED, "", t0, null);
    store.transition(
        id, InboundState.FAILED_ISSUANCE, ErrorCodes.ARCHIVE_UNAVAILABLE, t0, Duration.ofDays(4));

    InboundEvent mapped = store.transition(id, InboundState.MAPPED, "", NOW, null);
    assertThat(mapped.dueSinceInstant()).contains(NOW);
    assertThat(stranded(store, NOW.plusSeconds(1))).doesNotContain(id);
  }

  @Test
  void the_park_re_check_keeps_the_parks_clock_so_a_draft_for_ever_is_reported() {
    // The brief's example: a draft re-checked every backoff for 91 days. Each re-check is
    // PARKED -> FETCHED -> PARKED; restarting the clock there would hide it for ever.
    JdbcInboundEventStore store = store();
    Instant t0 = NOW.minus(Duration.ofDays(91));
    String id = receivedAt(store, t0);
    store.transition(id, InboundState.FETCHED, "", t0, null);
    store.transition(id, InboundState.PARKED, ErrorCodes.INVOICE_NOT_FINALISED, t0, null);
    for (int day = 1; day <= 90; day++) {
      Instant at = t0.plus(Duration.ofDays(day));
      if (day % 30 == 0) {
        // Stripe blinks now and then: that re-check fails before the fetch and is retried.
        store.transition(
            id, InboundState.FAILED_FETCH, ErrorCodes.STRIPE_UNAVAILABLE, at, Duration.ofHours(1));
      }
      store.transition(id, InboundState.FETCHED, "", at, null);
      store.transition(id, InboundState.PARKED, ErrorCodes.INVOICE_NOT_FINALISED, at, null);
    }
    assertThat(store.find(id).orElseThrow().dueSinceInstant()).contains(t0);
    assertThat(stranded(store, NOW)).contains(id);
    assertThat(due(store, NOW)).doesNotContain(id);
  }

  // ---- probe 8: exactly one of due and stranded, never both (F-3) --------------------------

  @Test
  void probe_due_and_stranded_overlap_or_gap() {
    JdbcInboundEventStore store = store();
    Instant now = NOW.plus(Duration.ofDays(400));
    List<String> running = new ArrayList<>();
    List<String> all = new ArrayList<>();
    Duration[] ages = {
      Duration.ZERO,
      Duration.ofHours(71),
      LIMIT.running(), // exactly on the running cutoff
      Duration.ofHours(73),
      Duration.ofDays(89),
      LIMIT.parked(), // exactly on the parked cutoff
      Duration.ofDays(91)
    };
    for (InboundState state : InboundState.values()) {
      for (Duration age : ages) {
        for (boolean everParked : new boolean[] {false, true}) {
          String id = receivedAt(store, now.minus(Duration.ofDays(1)));
          if (everParked) {
            store.transition(id, InboundState.PARKED, "", now.minus(Duration.ofDays(1)), null);
          }
          PostgresSupport.executeOn(
              ds,
              "UPDATE einvoice_inbound_event SET state = '"
                  + state.name()
                  + "', next_attempt_at = NULL WHERE event_id = '"
                  + id
                  + "'");
          setClock(id, now.minus(age));
          all.add(id);
          if (!state.terminal()) {
            running.add(id);
          }
        }
      }
    }
    Set<String> due = due(store, now);
    Set<String> stranded = stranded(store, now);
    for (String id : all) {
      assertThat(due.contains(id) && stranded.contains(id)).as("both: %s", id).isFalse();
    }
    for (String id : running) {
      assertThat(due.contains(id) ^ stranded.contains(id))
          .as("running row in exactly one: %s", store.find(id).orElseThrow())
          .isTrue();
    }
    // The equality case is in the due set: >= on the due side, < on the stranded side.
    assertThat(running).isNotEmpty();
  }

  @Test
  void a_row_written_without_a_clock_is_due_and_never_stranded() {
    // F-2, dual read: a 0.1.0 writer between ADD COLUMN and SET NOT NULL leaves a null clock.
    // Simulated on a copy of the table's shape; read as "not past any limit", never as the epoch.
    DataSource legacy = PostgresSupport.freshDatabase("q29_null_clock");
    try {
      PostgresSupport.executeOn(
          legacy, "ALTER TABLE einvoice_inbound_event ALTER COLUMN due_since DROP NOT NULL");
      JdbcInboundEventStore store = new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(legacy));
      String id = freshId();
      EventIdentity identity = new EventIdentity(id, "invoice.finalized", PIN, true, "", "in_x");
      store.record(
          InboundEvent.received(
              identity, Mode.LIVE, "primary", BODY, NOW.minus(Duration.ofDays(400))),
          BODY);
      PostgresSupport.executeOn(
          legacy,
          "UPDATE einvoice_inbound_event SET due_since = NULL WHERE event_id = '" + id + "'");

      assertThat(store.find(id).orElseThrow().dueSinceInstant()).isEmpty();
      assertThat(ids(store.due(NOW, Duration.ofHours(72), LIMIT, 100, PIN))).contains(id);
      assertThat(ids(store.stranded(NOW, LIMIT, 100))).doesNotContain(id);
    } finally {
      PostgresSupport.close(legacy);
    }
  }

  // ---- the expiry write --------------------------------------------------------------------

  @Test
  void expiry_moves_the_row_drops_the_body_and_records_one_finding_on_the_event_id() {
    JdbcInboundEventStore store = store();
    String id = receivedAt(store, NOW.minus(Duration.ofDays(4)));
    InboundEvent row = store.find(id).orElseThrow();

    assertThat(store.expire(row, finding(id, NOW), NOW)).isTrue();

    InboundEvent expired = store.find(id).orElseThrow();
    assertThat(expired.state()).isEqualTo(InboundState.EXPIRED);
    assertThat(expired.lastCode()).isEqualTo(ErrorCodes.INBOUND_CEILING_REACHED);
    assertThat(expired.bodyPresent()).isFalse();
    assertThat(expired.nextAttempt()).isEmpty();
    assertThat(openFindings(id)).isEqualTo(1);
    assertThat(due(store, NOW)).doesNotContain(id);
    assertThat(stranded(store, NOW)).doesNotContain(id);
    // A second sweep with the same stale read writes nothing and records nothing new.
    assertThat(store.expire(row, finding(id, NOW.plusSeconds(60)), NOW.plusSeconds(60))).isFalse();
    assertThat(findingLastSeen(id)).isEqualTo(Timestamp.from(NOW));
  }

  @Test
  void probe_a_concurrently_completed_row_throws_the_sweep() {
    // F-6, store half: read stranded, the row concludes elsewhere, then the expiry runs. A skip,
    // no finding, no exception.
    JdbcInboundEventStore store = store();
    String id = receivedAt(store, NOW.minus(Duration.ofDays(4)));
    InboundEvent row = store.find(id).orElseThrow();
    store.transition(id, InboundState.FETCHED, "", NOW, null);
    store.transition(id, InboundState.COMPLETED, "", NOW, null);

    assertThat(store.expire(row, finding(id, NOW), NOW)).isFalse();
    assertThat(store.find(id).orElseThrow().state()).isEqualTo(InboundState.COMPLETED);
    assertThat(openFindings(id)).isZero();
  }

  @Test
  void a_row_that_moved_and_came_back_has_a_new_clock_and_is_not_expired_on_the_old_read() {
    JdbcInboundEventStore store = store();
    String id = receivedAt(store, NOW.minus(Duration.ofDays(4)));
    store.transition(id, InboundState.FETCHED, "", NOW.minus(Duration.ofDays(4)), null);
    InboundEvent staleRead = store.find(id).orElseThrow();
    store.transition(id, InboundState.MAPPED, "", NOW, null);
    store.transition(id, InboundState.FETCHED, "", NOW, null);

    assertThat(store.expire(staleRead, finding(id, NOW), NOW)).isFalse();
    assertThat(store.find(id).orElseThrow().state()).isEqualTo(InboundState.FETCHED);
  }

  @Test
  void a_second_expiry_opens_an_acknowledged_finding_again() {
    JdbcInboundEventStore store = store();
    JdbcFindingStore findings = new JdbcFindingStore(JdbcUnitOfWork.ownConnection(ds));
    String id = receivedAt(store, NOW.minus(Duration.ofDays(4)));
    store.expire(store.find(id).orElseThrow(), finding(id, NOW), NOW);
    findings.acknowledge(
        "seller_q29", Mode.LIVE, ErrorCodes.RECON_STUCK_INBOUND, id, "draft abandoned", NOW);
    assertThat(openFindings(id)).isZero();

    // The operator re-opens it (the reprocess statement), it runs, it stalls again.
    reopen(id, NOW.plusSeconds(10));
    InboundEvent again = store.find(id).orElseThrow();
    assertThat(again.state()).isEqualTo(InboundState.RECEIVED);
    assertThat(again.dueSinceInstant()).contains(NOW.plusSeconds(10));
    Instant later = NOW.plus(Duration.ofDays(4));
    assertThat(stranded(store, later)).contains(id);
    assertThat(store.expire(store.find(id).orElseThrow(), finding(id, later), later)).isTrue();
    assertThat(openFindings(id)).isEqualTo(1);
  }

  // ---- probe 5, store half: the re-open restarts the clock ---------------------------------

  @Test
  void probe_expired_is_unrecoverable() {
    JdbcInboundEventStore store = store();
    String id = receivedAt(store, NOW.minus(Duration.ofDays(4)));
    store.expire(store.find(id).orElseThrow(), finding(id, NOW), NOW);

    reopen(id, NOW.plusSeconds(5));

    // Re-opened, due on the very next sweep, and not expired again by a clock from arrival.
    assertThat(due(store, NOW.plusSeconds(6))).contains(id);
    assertThat(stranded(store, NOW.plusSeconds(6))).doesNotContain(id);
  }

  private static void reopen(String id, Instant at) {
    try (var c = ds.getConnection();
        var ps = c.prepareStatement(JdbcInboundEventStore.REOPEN_FOR_REPROCESS)) {
      ps.setString(1, ErrorCodes.REPROCESS_REQUESTED);
      ps.setObject(2, JdbcSupport.ts(at));
      ps.setObject(3, JdbcSupport.ts(at));
      ps.setString(4, id);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static long openFindings(String id) {
    return PostgresSupport.scalarOn(
        ds,
        "SELECT count(*) FROM einvoice_finding WHERE code = '"
            + ErrorCodes.RECON_STUCK_INBOUND
            + "' AND acknowledged_at IS NULL AND subject_id = '"
            + id
            + "'");
  }

  private static Timestamp findingLastSeen(String id) {
    try (var c = ds.getConnection();
        var ps =
            c.prepareStatement("SELECT last_seen FROM einvoice_finding WHERE subject_id = ?")) {
      ps.setString(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getTimestamp(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
