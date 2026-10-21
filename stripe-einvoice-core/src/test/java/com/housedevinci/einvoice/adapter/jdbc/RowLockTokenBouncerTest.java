package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.ArchiveStore;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.InMemoryArchiveStore;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.MappingInput;
import com.housedevinci.einvoice.application.PreflightReport;
import com.housedevinci.einvoice.application.TestInvoices;
import com.housedevinci.einvoice.application.TestValidators;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Revision 3 (D-PR42-2): the acquisition token proof, under a real transaction pooler with one
 * server connection, so that a "foreign" client's statements land on our backend between our
 * transactions. The startup check is bypassed through the test seam on purpose: these tests put the
 * per-lock proof itself under attack.
 */
class RowLockTokenBouncerTest {

  private static final Network NETWORK = Network.newNetwork();

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse(
                      "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777")
                  .asCompatibleSubstituteFor("postgres"))
          .withNetwork(NETWORK)
          .withNetworkAliases("pg");

  private static GenericContainer<?> plain;
  private static GenericContainer<?> resetAlways;

  private static GenericContainer<?> bouncer(boolean resetAfterEveryTransaction) {
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
            .withEnv("POOL_MODE", "transaction")
            .withEnv("DEFAULT_POOL_SIZE", "1")
            .withEnv("MAX_PREPARED_STATEMENTS", "200")
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());
    if (resetAfterEveryTransaction) {
      c.withEnv("SERVER_RESET_QUERY", "DISCARD ALL").withEnv("SERVER_RESET_QUERY_ALWAYS", "1");
    }
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
    HikariDataSource ds = new HikariDataSource(config);
    JdbcIssuanceStore.trustSessionPinningForTest(ds);
    return ds;
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
    plain = bouncer(false);
    resetAlways = bouncer(true);
  }

  @AfterAll
  static void stop() {
    for (GenericContainer<?> c : List.of(plain, resetAlways)) {
      c.stop();
    }
    POSTGRES.stop();
    NETWORK.close();
  }

  private static JdbcIssuanceStore store(DataSource ds, String seller) {
    return TestStores.store(
        JdbcUnitOfWork.ownConnection(ds),
        ds,
        CHAIN,
        Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
        CLOCK,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT);
  }

  /** A statement from a client that is not this module, landing on the one server connection. */
  private static void foreign(DataSource ds, String sql) {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static long count(DataSource ds, String sql) {
    return PostgresSupport.scalarOn(ds, sql);
  }

  // ------------------------------------------------------------------ case 1, P1

  @Test
  void probe_reset_always_pid_match_is_not_proof() {
    try (HikariDataSource ds = through(resetAlways)) {
      String seller = PostgresSupport.freshSeller("reset");
      JdbcIssuanceStore store = store(ds, seller);
      Issuance row = store.allocate(request(key(seller), "in_reset"));

      // After the TRY's own transaction the pooler runs DISCARD ALL: the lock and the token are
      // gone, the pid is the same. The first held transaction must refuse (D-PR43-3: not only the
      // release at the end).
      java.util.concurrent.atomic.AtomicBoolean pastFirstRead =
          new java.util.concurrent.atomic.AtomicBoolean();
      assertThatThrownBy(
              () ->
                  store.whileHeld(
                      row.id(),
                      held -> {
                        Optional<Issuance> read =
                            held.reader().findBySource(seller, Mode.LIVE, "in_reset");
                        pastFirstRead.set(true);
                        return read;
                      }))
          .isInstanceOf(EInvoiceException.class)
          .satisfies(
              e -> {
                assertThat(((EInvoiceException) e).code()).isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
                // C6: the message names the row and never carries the token.
                assertThat(e.getMessage())
                    .contains("issuance row " + row.id())
                    .doesNotContainPattern("[0-9a-f]{32}");
              });
      assertThat(pastFirstRead).as("the first held read refused").isFalse();
    }
  }

  // ------------------------------------------------------------------ case 2, P2

  @Test
  void probe_release_frees_another_holders_lock() throws Exception {
    try (HikariDataSource ds = through(plain);
        Connection a = ds.getConnection();
        Connection b = ds.getConnection()) {
      long id = 4_343L;
      String tokenA = IssuanceRowLocks.tryLock(a, id).orElseThrow();
      foreign(ds, "SELECT pg_advisory_unlock_all()");
      Optional<String> tokenB = IssuanceRowLocks.tryLock(b, id);
      assertThat(tokenB).as("B takes the key on the same backend").isPresent();

      assertThat(IssuanceRowLocks.unlock(a, id, tokenA))
          .as("A's release must not free B's lock")
          .isFalse();
      assertThat(count(ds, heldSql(id))).as("B still holds the key").isEqualTo(1L);
      assertThat(IssuanceRowLocks.unlock(b, id, tokenB.get())).isTrue();
    }
  }

  private static String heldSql(long id) {
    return "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted AND objsubid = 2"
        + " AND classid = "
        + (AdvisoryLocks.ROW_CLASS_ID & 0xFFFFFFFFL)
        + " AND objid = "
        + (id & 0xFFFFFFFFL);
  }

  // ------------------------------------------------------------------ C2

  @Test
  void probe_a_second_key_on_one_backend_never_passes_the_first_proof() throws Exception {
    try (HikariDataSource ds = through(plain);
        Connection a = ds.getConnection();
        Connection b = ds.getConnection()) {
      String tokenA = IssuanceRowLocks.tryLock(a, 7L).orElseThrow();
      String tokenB = IssuanceRowLocks.tryLock(b, 9L).orElseThrow();

      assertThat(IssuanceRowLocks.prove(a, 7L, tokenA)).isFalse();
      assertThat(IssuanceRowLocks.unlock(a, 7L, tokenA)).isFalse();
      assertThat(count(ds, heldSql(9L))).as("row 9 still held").isEqualTo(1L);
      assertThat(IssuanceRowLocks.unlock(b, 9L, tokenB)).isTrue();
      // Row 7 stays held on that backend until it ends: the stated C2 picture.
      foreign(ds, "SELECT pg_advisory_unlock_all()");
    }
  }

  // ------------------------------------------------------------------ P3 (C4)

  /** The harness archive, with a foreign unlock run on our backend at one chosen moment. */
  private static ArchiveStore unlockingOn(
      InMemoryArchiveStore inner, DataSource ds, boolean onRead, boolean onPut) {
    return new ArchiveStore() {
      @Override
      public boolean supportsAtomicCreate() {
        return true;
      }

      @Override
      public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
        if (onPut) {
          foreign(ds, "SELECT pg_advisory_unlock_all()");
        }
        return inner.putIfAbsent(key, bytes);
      }

      @Override
      public Optional<byte[]> get(ArchiveKey key) {
        if (onRead) {
          foreign(ds, "SELECT pg_advisory_unlock_all()");
        }
        return inner.get(key);
      }

      @Override
      public List<String> list(String prefix, int limit) {
        return inner.list(prefix, limit);
      }

      @Override
      public String describe() {
        return "foreign-unlocking archive (test)";
      }
    };
  }

  private static DocumentRenderer backdating(IssuanceTestHarness harness) {
    DocumentRenderer real = harness.renderer();
    return new DocumentRenderer() {
      @Override
      public RenderedDocument render(DocumentInput input) {
        RenderedDocument d = real.render(input);
        String xml =
            new String(d.bytes(), StandardCharsets.UTF_8)
                .replace(
                    "<IssueDate>" + input.issueDate() + "</IssueDate>",
                    "<IssueDate>" + input.issueDate().minusDays(7) + "</IssueDate>");
        return new RenderedDocument(
            xml.getBytes(StandardCharsets.UTF_8), d.extension(), d.profile());
      }

      @Override
      public PreflightReport preflight(MappingInput input) {
        return real.preflight(input);
      }

      @Override
      public boolean carriesSupplyDate() {
        return true;
      }
    };
  }

  @Test
  void probe_foreign_unlock_after_the_pre_read_refuses_the_burn() {
    try (HikariDataSource ds = through(plain)) {
      IssuanceTestHarness harness = IssuanceTestHarness.createOn(ds);
      harness.source().with(TestInvoices.finalised("in_p3_burn"));
      String eventId = harness.receive("invoice.finalized", "in_p3_burn");
      harness.archive().failWith(new Error("killed before the PUT"));
      try {
        harness.unitOfWork().process(eventId);
      } catch (Error expected) {
        // the row is ARCHIVING with a hash and no object
      }
      harness.archive().heal();
      Issuance before = harness.issuance("in_p3_burn").orElseThrow();
      assertThat(before.state()).isEqualTo(IssuanceState.ARCHIVING);
      String events =
          "SELECT count(*) FROM einvoice_issuance_event WHERE stripe_invoice_id = 'in_p3_burn'";
      String counter =
          "SELECT next_number FROM einvoice_series WHERE seller_id = '" + harness.sellerId() + "'";
      long eventsBefore = count(ds, events);
      long counterBefore = count(ds, counter);

      IssuanceUnitOfWork.Outcome outcome =
          harness
              .unitOfWorkWith(
                  backdating(harness),
                  TestValidators.passing(),
                  new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
                  unlockingOn(harness.archive(), ds, true, false))
              .process(eventId);

      assertThat(outcome.code()).isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
      assertThat(harness.issuance("in_p3_burn").orElseThrow().state())
          .as("not burned")
          .isEqualTo(IssuanceState.ARCHIVING);
      assertThat(count(ds, events)).as("nothing chained").isEqualTo(eventsBefore);
      assertThat(count(ds, counter)).as("series unchanged").isEqualTo(counterBefore);
    }
  }

  @Test
  void probe_foreign_unlock_during_the_put_refuses_mark_issued() {
    try (HikariDataSource ds = through(plain)) {
      IssuanceTestHarness harness = IssuanceTestHarness.createOn(ds);
      harness.source().with(TestInvoices.finalised("in_p3_put"));
      String eventId = harness.receive("invoice.finalized", "in_p3_put");

      IssuanceUnitOfWork.Outcome outcome =
          harness
              .unitOfWorkWith(
                  harness.renderer(),
                  TestValidators.passing(),
                  new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
                  unlockingOn(harness.archive(), ds, false, true))
              .process(eventId);

      assertThat(outcome.code()).isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
      assertThat(harness.issuance("in_p3_put").orElseThrow().state())
          .as("still resumable")
          .isEqualTo(IssuanceState.ARCHIVING);
      assertThat(harness.inbound().find(eventId).orElseThrow().nextAttemptAt())
          .as("retried")
          .isNotNull();
    }
  }
}
