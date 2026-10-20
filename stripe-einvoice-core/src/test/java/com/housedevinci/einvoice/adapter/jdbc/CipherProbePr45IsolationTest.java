package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Security review, PR 45 pass 1 (D-PR45-2). A host whose pool runs REPEATABLE READ or SERIALIZABLE
 * (the allocator already supports both and maps 40001, D-PR40-1). Run B's compare-and-set waits on
 * the row lock that run A holds while A moves the row; A commits. At READ COMMITTED B re-reads,
 * sees the move and answers empty (a lost race). At REPEATABLE READ the locking read raises 40001
 * instead, mapped to DEI-1xx STORE_UNAVAILABLE, and that escapes process: the lost race the fix
 * promises to answer quietly becomes an error.
 *
 * <p>Install under stripe-einvoice-core/src/test/java/com/housedevinci/einvoice/adapter/jdbc/.
 */
class CipherProbePr45IsolationTest {

  private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

  @ParameterizedTest
  @ValueSource(ints = {Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE})
  void probe_a_lost_compare_and_set_at_repeatable_read_escapes_as_store_unavailable(int level)
      throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(PostgresSupport.jdbcUrl());
    config.setUsername(PostgresSupport.username());
    config.setPassword(PostgresSupport.password());
    config.setMaximumPoolSize(2);
    config.setTransactionIsolation(
        level == Connection.TRANSACTION_SERIALIZABLE
            ? "TRANSACTION_SERIALIZABLE"
            : "TRANSACTION_REPEATABLE_READ");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (HikariDataSource hostPool = new HikariDataSource(config);
        Connection a =
            DriverManager.getConnection(
                PostgresSupport.jdbcUrl(),
                PostgresSupport.username(),
                PostgresSupport.password())) {
      JdbcInboundEventStore store =
          new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(hostPool));
      String id = "evt_" + UUID.randomUUID().toString().replace("-", "");
      byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
      store.record(
          InboundEvent.received(
              new EventIdentity(id, "invoice.finalized", "2026-03-31.clover", true, "", "in_1"),
              Mode.LIVE,
              "primary",
              body,
              NOW),
          body);

      // Run A: holds the row lock and moves the row, not yet committed.
      a.setAutoCommit(false);
      try (PreparedStatement ps =
          a.prepareStatement(
              "UPDATE einvoice_inbound_event SET state = 'FETCHED' WHERE event_id = ?")) {
        ps.setString(1, id);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }
      // Run B: its compare-and-set (expects RECEIVED) waits on A's lock.
      Future<Optional<InboundEvent>> b =
          pool.submit(
              () ->
                  store.transitionFrom(
                      id, InboundState.RECEIVED, InboundState.FETCHED, "", "", NOW, null));
      awaitLockWaiter(a);
      a.commit();

      Optional<InboundEvent> answer = Optional.empty();
      Throwable escaped = null;
      try {
        answer = b.get(30, TimeUnit.SECONDS);
      } catch (ExecutionException e) {
        escaped = e.getCause();
      }
      assertThat(escaped).as("a lost race answers empty at every isolation level").isNull();
      assertThat(answer).isEmpty();
      assertThat(store.find(id).orElseThrow().state()).isEqualTo(InboundState.FETCHED);
    } finally {
      pool.shutdownNow();
    }
  }

  private static void awaitLockWaiter(Connection holder) throws Exception {
    try (Connection probe =
        DriverManager.getConnection(
            PostgresSupport.jdbcUrl(), PostgresSupport.username(), PostgresSupport.password())) {
      for (int i = 0; i < 300; i++) {
        try (PreparedStatement ps =
                probe.prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                        + " AND query LIKE '%einvoice_inbound_event%FOR UPDATE%'");
            ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (rs.getLong(1) > 0) {
            return;
          }
        }
        Thread.sleep(20);
      }
      throw new IllegalStateException("run B never waited on the row lock");
    }
  }
}
