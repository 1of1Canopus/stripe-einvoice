package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.TestInvoices;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * D-RTR-7, R5: a pooler in transaction mode would turn the session row lock into no lock at all, so
 * the module refuses it with {@code DEI-104} before a number is taken. The same pooler in session
 * mode issues. Real PgBouncer 1.26 in front of a real PostgreSQL.
 */
class PgBouncerSessionPinningTest {

  private static final Network NETWORK = Network.newNetwork();

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse(
                      "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777")
                  .asCompatibleSubstituteFor("postgres"))
          .withNetwork(NETWORK)
          .withNetworkAliases("pg");

  private static GenericContainer<?> transactionMode;
  private static GenericContainer<?> sessionMode;

  private static GenericContainer<?> bouncer(String mode) {
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
            .withEnv("DEFAULT_POOL_SIZE", "4")
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

  @BeforeAll
  static void start() {
    POSTGRES.start();
    HikariConfig direct = new HikariConfig();
    direct.setJdbcUrl(POSTGRES.getJdbcUrl());
    direct.setUsername(POSTGRES.getUsername());
    direct.setPassword(POSTGRES.getPassword());
    try (HikariDataSource ds = new HikariDataSource(direct)) {
      JdbcSupport.initializeSchema(ds);
    }
    transactionMode = bouncer("transaction");
    sessionMode = bouncer("session");
  }

  @AfterAll
  static void stop() {
    if (transactionMode != null) {
      transactionMode.stop();
    }
    if (sessionMode != null) {
      sessionMode.stop();
    }
    POSTGRES.stop();
    NETWORK.close();
  }

  @Test
  void transaction_pooling_is_refused_before_a_number_is_taken() {
    try (HikariDataSource ds = through(transactionMode)) {
      IssuanceTestHarness harness = IssuanceTestHarness.createOn(ds);
      assertThatThrownBy(() -> harness.store().verifySessionPinning())
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.SESSION_NOT_PINNED);

      harness.source().with(TestInvoices.finalised("in_bouncer_tx"));
      String eventId = harness.receive("invoice.finalized", "in_bouncer_tx");
      IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

      // T10 (one run per event, path 13): the run lock refuses the pooler at acquisition, before
      // any write. The row stays as it was recorded.
      assertThat(outcome.state()).isEqualTo(InboundState.RECEIVED);
      assertThat(outcome.code()).isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
      assertThat(harness.inbound().find(eventId).orElseThrow().attempts())
          .as("nothing was written, not even the stamp")
          .isZero();
      assertThat(harness.issuance("in_bouncer_tx")).as("no number was taken").isEmpty();
    }
  }

  @Test
  void session_pooling_issues() {
    try (HikariDataSource ds = through(sessionMode)) {
      IssuanceTestHarness harness = IssuanceTestHarness.createOn(ds);
      harness.store().verifySessionPinning();

      harness.source().with(TestInvoices.finalised("in_bouncer_session"));
      String eventId = harness.receive("invoice.finalized", "in_bouncer_session");
      IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

      assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    }
  }
}
