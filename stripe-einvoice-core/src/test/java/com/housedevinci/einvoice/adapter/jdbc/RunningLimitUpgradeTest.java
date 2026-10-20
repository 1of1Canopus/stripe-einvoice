package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.RunningLimit;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * QUESTIONS 29, F-2: the upgrade from the released 0.1.0 schema must not expire the backlog it
 * finds. Thirty running rows aged thirty days are on the database before the upgrade; after it,
 * their clock is the upgrade instant and the first sweep expires none of them.
 */
class RunningLimitUpgradeTest {

  /** The 0.1.0 insert of an inbound row, verbatim in its columns. */
  private static final String INSERT_0_1_0 =
      "INSERT INTO einvoice_inbound_event (event_id, event_type, object_id, api_version,"
          + " livemode, mode, received_at, updated_at, state, attempts, body, body_sha256)"
          + " VALUES (?, 'invoice.finalized', ?, '2026-03-31.clover', true, 'live', ?, ?, ?, 0,"
          + " ?, ?)";

  @Test
  void probe_an_upgraded_backlog_is_mass_expired() throws Exception {
    HikariDataSource ds = PostgresSupport.emptyDatabase("q29_upgrade");
    try {
      Format1.installSchema(ds);
      Instant thirtyDaysAgo = Instant.now().minus(Duration.ofDays(30));
      String[] states = {"RECEIVED", "FETCHED", "MAPPED", "PARKED"};
      try (Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement(INSERT_0_1_0)) {
        for (int i = 0; i < 30; i++) {
          ps.setString(1, "evt_backlog_" + i);
          ps.setString(2, "in_backlog_" + i);
          ps.setTimestamp(3, Timestamp.from(thirtyDaysAgo));
          ps.setTimestamp(4, Timestamp.from(thirtyDaysAgo));
          ps.setString(5, states[i % states.length]);
          ps.setBytes(6, new byte[] {1});
          ps.setString(7, "0".repeat(64));
          ps.executeUpdate();
        }
      }

      Instant before = Instant.now();
      JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of("Europe/Paris")));

      JdbcInboundEventStore store = new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(ds));
      Instant firstSweep = Instant.now().plus(Duration.ofMinutes(1));
      assertThat(store.stranded(firstSweep, RunningLimit.DEFAULT, 1_000)).isEmpty();
      List<InboundEvent> due =
          store.due(
              firstSweep, Duration.ofHours(72), RunningLimit.DEFAULT, 1_000, "2026-03-31.clover");
      assertThat(due).hasSize(30);
      for (InboundEvent row : due) {
        // The clock is the upgrade instant, never the arrival thirty days ago.
        assertThat(row.dueSinceInstant().orElseThrow()).isAfterOrEqualTo(before.minusSeconds(5));
      }
      // And a second initialisation is a no-op: it does not move the clock again.
      Instant clockAfterFirst = due.get(0).dueSinceInstant().orElseThrow();
      JdbcSupport.initializeSchema(ds, Optional.of(ZoneId.of("Europe/Paris")));
      assertThat(store.find(due.get(0).eventId()).orElseThrow().dueSinceInstant())
          .contains(clockAfterFirst);
    } finally {
      PostgresSupport.close(ds);
    }
  }
}
