package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Security review of PR 42, pass 1: the R5 session check against real PgBouncer 1.26, same pinned
 * images as the builder's test. Each probe fails on head 4a7bd08.
 */
class CipherProbePr42BouncerTest {

  private static final Network NETWORK = Network.newNetwork();

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse(
                      "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777")
                  .asCompatibleSubstituteFor("postgres"))
          .withNetwork(NETWORK)
          .withNetworkAliases("pg");

  private static GenericContainer<?> roundRobin;
  private static GenericContainer<?> statementMode;
  private static GenericContainer<?> sessionRoundRobin;

  private static GenericContainer<?> bouncer(String mode, boolean roundRobinServers) {
    GenericContainer<?> c =
        new GenericContainer<>(
                DockerImageName.parse(
                    "edoburu/pgbouncer@sha256:9c78945868a6a142c7fc40ccd843bbe5a606df163c7ffce4de70e0d628d696a2"))
            .withNetwork(NETWORK)
            .withEnv("DB_HOST", "pg")
            .withEnv("DB_PORT", "5432")
            .withEnv("DB_USER", POSTGRES.getUsername())
            .withEnv("DB_PASSWORD", POSTGRES.getPassword())
            .withEnv("AUTH_TYPE", "scram-sha-256")
            .withEnv("POOL_MODE", mode)
            .withEnv("DEFAULT_POOL_SIZE", "2")
            .withEnv("SERVER_ROUND_ROBIN", roundRobinServers ? "1" : "0")
            .withEnv("MAX_PREPARED_STATEMENTS", "200")
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());
    c.start();
    return c;
  }

  private static HikariDataSource through(GenericContainer<?> bouncer) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:postgresql://"
            + bouncer.getHost()
            + ":"
            + bouncer.getMappedPort(5432)
            + "/"
            + POSTGRES.getDatabaseName());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    config.setMaximumPoolSize(4);
    config.setConnectionTimeout(10_000);
    return new HikariDataSource(config);
  }

  /** Two server connections exist and are idle, as on any pooler that has served traffic. */
  private static void warm(HikariDataSource ds) throws Exception {
    List<Connection> open = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      Connection c = ds.getConnection();
      c.setAutoCommit(false);
      try (Statement st = c.createStatement();
          ResultSet rs = st.executeQuery("SELECT 1")) {
        rs.next();
      }
      open.add(c);
    }
    for (Connection c : open) {
      c.rollback();
      c.close();
    }
  }

  @BeforeAll
  static void start() {
    POSTGRES.start();
    roundRobin = bouncer("transaction", true);
    statementMode = bouncer("statement", false);
    sessionRoundRobin = bouncer("session", true);
  }

  @AfterAll
  static void stop() {
    for (GenericContainer<?> c : List.of(roundRobin, statementMode, sessionRoundRobin)) {
      c.stop();
    }
    POSTGRES.stop();
    NETWORK.close();
  }

  /**
   * D-PR42-2. PgBouncer in transaction mode with server_round_robin = 1 and two idle server
   * connections. Connection two gets the other server, and connection one gets its first server
   * back with the session setting still on it, so the check passes a transaction pooler.
   */
  @org.junit.jupiter.api.Disabled(
      "QUESTIONS 43 / D-PR42-2: design stop, the stronger check awaits the security ruling")
  @Test
  void probe_a_round_robin_transaction_pooler_is_refused() throws Exception {
    try (HikariDataSource ds = through(roundRobin)) {
      warm(ds);
      assertThatThrownBy(() -> IssuanceRowLocks.verifySessionPinning(ds))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
    }
  }

  /**
   * D-PR42-2, second shape. Statement mode refuses the check's own transaction, and the refusal
   * comes out as DEI-102 (store unavailable, retried for ever) instead of DEI-104.
   */
  @org.junit.jupiter.api.Disabled(
      "QUESTIONS 43 / D-PR42-2: design stop, the stronger check awaits the security ruling")
  @Test
  void probe_statement_pooling_is_refused_as_not_pinned() throws Exception {
    try (HikariDataSource ds = through(statementMode)) {
      assertThatThrownBy(() -> IssuanceRowLocks.verifySessionPinning(ds))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
    }
  }

  /** Not a probe: the control. Session mode with round robin must still pass. */
  @Test
  void session_pooling_with_round_robin_still_passes() throws Exception {
    try (HikariDataSource ds = through(sessionRoundRobin)) {
      warm(ds);
      IssuanceRowLocks.verifySessionPinning(ds);
      assertThat(ds.isRunning()).isTrue();
    }
  }
}
