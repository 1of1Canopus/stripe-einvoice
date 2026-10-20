package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * D-PR45-2. Under REPEATABLE READ a locking read of a row that another transaction changed and
 * committed raises SQLState 40001. The store retries once when it owns the connection, and lets the
 * error through when the transaction is the host's.
 */
class JdbcInboundEventStoreSnapshotTest {

  private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

  private static HikariDataSource repeatableReadPool() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(PostgresSupport.jdbcUrl());
    config.setUsername(PostgresSupport.username());
    config.setPassword(PostgresSupport.password());
    config.setMaximumPoolSize(2);
    config.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
    return new HikariDataSource(config);
  }

  private static String record(JdbcInboundEventStore store) {
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
    return id;
  }

  private static Connection plain() throws Exception {
    return DriverManager.getConnection(
        PostgresSupport.jdbcUrl(), PostgresSupport.username(), PostgresSupport.password());
  }

  @Test
  void the_retry_lands_when_the_concurrent_change_left_the_expected_state() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (HikariDataSource hostPool = repeatableReadPool();
        Connection a = plain()) {
      JdbcInboundEventStore store =
          new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(hostPool));
      String id = record(store);
      a.setAutoCommit(false);
      try (PreparedStatement ps =
          a.prepareStatement(
              "UPDATE einvoice_inbound_event SET seller_id = 'seller_x' WHERE event_id = ?")) {
        ps.setString(1, id);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }
      Future<Optional<InboundEvent>> b =
          pool.submit(
              () ->
                  store.transitionFrom(
                      id, InboundState.RECEIVED, InboundState.FETCHED, "", "", NOW, null));
      awaitLockWaiter();
      a.commit();

      Optional<InboundEvent> answer = b.get(30, TimeUnit.SECONDS);
      assertThat(answer).isPresent();
      assertThat(answer.orElseThrow().state()).isEqualTo(InboundState.FETCHED);
      assertThat(store.find(id).orElseThrow().state()).isEqualTo(InboundState.FETCHED);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void a_host_transaction_gets_the_error_unchanged() throws Exception {
    try (HikariDataSource hostPool = repeatableReadPool();
        Connection host = plain();
        Connection other = plain()) {
      String id = record(new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(hostPool)));
      host.setAutoCommit(false);
      host.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try (PreparedStatement ps = host.prepareStatement("SELECT 1 FROM einvoice_inbound_event");
          ResultSet rs = ps.executeQuery()) {
        rs.next(); // the snapshot is taken here
      }
      try (PreparedStatement ps =
          other.prepareStatement(
              "UPDATE einvoice_inbound_event SET state = 'FETCHED' WHERE event_id = ?")) {
        ps.setString(1, id);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }
      JdbcInboundEventStore store = new JdbcInboundEventStore(JdbcUnitOfWork.using(host));

      assertThatThrownBy(
              () ->
                  store.transitionFrom(
                      id, InboundState.RECEIVED, InboundState.FETCHED, "", "", NOW, null))
          .isInstanceOf(EInvoiceException.class)
          .satisfies(
              e -> {
                assertThat(((EInvoiceException) e).getCause())
                    .isInstanceOf(java.sql.SQLException.class);
                assertThat(((java.sql.SQLException) e.getCause()).getSQLState()).isEqualTo("40001");
              });
      host.rollback();
    }
  }

  private static void awaitLockWaiter() throws Exception {
    try (Connection probe = plain()) {
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
