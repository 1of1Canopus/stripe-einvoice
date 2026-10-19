package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.TestInvoices;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * D-A1-C03: one series name binds one document type for a seller and mode, under any interleaving
 * and at every isolation level (series-name design, sections 3.1 and 6, tests 1, 1b, 2 and 6).
 *
 * <p>Example of the defect: two writers create series {@code RACE} at the same instant, one for
 * 2026 as an invoice ({@code 380}) and one for 2027 as a credit note ({@code 381}). Before the name
 * table, both committed, and one series name numbered two document types.
 *
 * <p>Each case holds the first writer's transaction open, waits until the second writer is seen
 * waiting on a lock held by the first (in {@code pg_stat_activity}), and only then commits or rolls
 * back the first. The outcomes asserted per level are the ones measured on the pinned image.
 */
class SeriesNameRaceTest {

  private static final SeriesDefinition INVOICES =
      new SeriesDefinition("INV-{fiscalYear}-", 6, true);
  private static final SeriesDefinition RACE_INVOICES =
      new SeriesDefinition("R-{fiscalYear}-", 6, true);
  private static final SeriesDefinition RACE_CREDITS =
      new SeriesDefinition("RC-{fiscalYear}-", 6, true);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-20T10:00:00Z"), ZoneOffset.UTC);
  private static final Instant SALE = Instant.parse("2026-03-19T10:00:00Z");
  private static final Duration WAIT = Duration.ofSeconds(10);

  // ------------------------------------------------------------------ helpers

  private static Connection host(DataSource ds, int level) throws SQLException {
    Connection c = ds.getConnection();
    c.setAutoCommit(false);
    c.setTransactionIsolation(level);
    return c;
  }

  private static int pid(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** Waits until some backend is waiting on a lock that {@code blocker} holds. */
  private static boolean waitsOn(int blocker) throws Exception {
    return waitsOn(PostgresSupport.dataSource(), blocker);
  }

  private static boolean waitsOn(DataSource on, int blocker) throws Exception {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      try (Connection c = on.getConnection();
          PreparedStatement ps =
              c.prepareStatement(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                      + " AND pg_blocking_pids(pid) @> ARRAY[?]::int[]")) {
        ps.setInt(1, blocker);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (rs.getLong(1) > 0) {
            return true;
          }
        }
      }
      Thread.sleep(20);
    }
    return false;
  }

  private static void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static String nameRow(String seller, String type) {
    return "INSERT INTO einvoice_series_name (seller_id, mode, series, document_type, created_at)"
        + " VALUES ('"
        + seller
        + "', 'live', 'RACE', '"
        + type
        + "', now())";
  }

  private static String yearRow(String seller, String type, int year) {
    return "INSERT INTO einvoice_series (seller_id, document_type, series, fiscal_year, mode,"
        + " prefix, width, next_number, updated_at) VALUES ('"
        + seller
        + "', '"
        + type
        + "', 'RACE', "
        + year
        + ", 'live', 'R"
        + year
        + "-', 6, 1, now())";
  }

  private static long count(String sql) {
    return PostgresSupport.scalar(sql);
  }

  private static String sqlState(Throwable t) {
    Throwable cur = t;
    while (cur != null) {
      if (cur instanceof SQLException se) {
        return se.getSQLState();
      }
      cur = cur.getCause();
    }
    return "none";
  }

  // ------------------------------------------------------------------ test 1 at three levels

  /**
   * Test 1 (the review's probe) at each isolation level: year rows only, no name rows. It states
   * the property; with the name table it holds because the foreign key refuses both inserts at once
   * (C5), so it is not the proof that the race is closed. Test 1b is.
   */
  @ParameterizedTest
  @ValueSource(
      ints = {
        Connection.TRANSACTION_READ_COMMITTED,
        Connection.TRANSACTION_REPEATABLE_READ,
        Connection.TRANSACTION_SERIALIZABLE
      })
  void year_rows_alone_never_bind_one_name_to_two_types(int level) throws Exception {
    String seller = PostgresSupport.freshSeller("c03t1");
    DataSource ds = PostgresSupport.dataSource();
    String second;
    try (Connection first = host(ds, level)) {
      try {
        exec(first, yearRow(seller, "380", 2026));
      } catch (SQLException refused) {
        first.rollback();
      }
      CompletableFuture<String> other =
          CompletableFuture.supplyAsync(
              () -> {
                try (Connection c = host(ds, level)) {
                  exec(c, yearRow(seller, "381", 2027));
                  c.commit();
                  return "committed";
                } catch (SQLException refused) {
                  return refused.getSQLState();
                }
              });
      second = other.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      first.commit();
    }
    assertThat(second).as("C5: no name row, so the foreign key refuses at once").isEqualTo("23503");
    assertThat(
            count(
                "SELECT count(DISTINCT document_type) FROM einvoice_series WHERE seller_id = '"
                    + seller
                    + "' AND series = 'RACE'"))
        .isLessThanOrEqualTo(1);
  }

  // ------------------------------------------------------------------ test 1b

  /**
   * Test 1b, the real raw-insert shape (R2): each writer inserts its name row and its year row in
   * one transaction. The second writer read the name key before the first committed, so its
   * snapshot cannot show the first writer's row. It waits on the primary key while the first holds,
   * then fails with {@code 23505} at every level (C4, measured), and the committed state is one
   * name row of type {@code 380} and one year row.
   */
  @ParameterizedTest
  @ValueSource(
      ints = {
        Connection.TRANSACTION_READ_COMMITTED,
        Connection.TRANSACTION_REPEATABLE_READ,
        Connection.TRANSACTION_SERIALIZABLE
      })
  void one_series_name_never_binds_two_types_when_each_writer_inserts_name_and_year_rows(int level)
      throws Exception {
    String seller = PostgresSupport.freshSeller("c03t1b");
    DataSource ds = PostgresSupport.dataSource();
    String outcome;
    try (Connection first = host(ds, level)) {
      exec(first, nameRow(seller, "380"));
      exec(first, yearRow(seller, "380", 2026));
      CompletableFuture<String> second =
          CompletableFuture.supplyAsync(
              () -> {
                try (Connection c = host(ds, level)) {
                  try {
                    exec(
                        c,
                        "SELECT count(*) FROM einvoice_series_name WHERE seller_id = '"
                            + seller
                            + "' AND mode = 'live' AND series = 'RACE'");
                    exec(c, nameRow(seller, "381"));
                    exec(c, yearRow(seller, "381", 2027));
                    c.commit();
                    return "committed";
                  } catch (SQLException refused) {
                    c.rollback();
                    return refused.getSQLState();
                  }
                } catch (SQLException e) {
                  return "connection " + e.getSQLState();
                }
              });
      assertThat(waitsOn(pid(first)))
          .as("the second writer waits on the first writer's name key")
          .isTrue();
      assertThat(second).isNotDone();
      first.commit();
      outcome = second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }
    assertThat(outcome).as("C4, measured at every level").isEqualTo("23505");
    assertThat(
            count(
                "SELECT count(*) FROM einvoice_series_name WHERE seller_id = '"
                    + seller
                    + "' AND document_type = '380'"))
        .isEqualTo(1);
    assertThat(
            count("SELECT count(*) FROM einvoice_series_name WHERE seller_id = '" + seller + "'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM einvoice_series WHERE seller_id = '"
                    + seller
                    + "' AND series = 'RACE' AND fiscal_year = 2026 AND document_type = '380'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM einvoice_series WHERE seller_id = '" + seller + "'"))
        .isEqualTo(1);
  }

  /** Test 1b, variant (C3): the first writer rolls back, so the second commits and 381 binds. */
  @ParameterizedTest
  @ValueSource(
      ints = {
        Connection.TRANSACTION_READ_COMMITTED,
        Connection.TRANSACTION_REPEATABLE_READ,
        Connection.TRANSACTION_SERIALIZABLE
      })
  void the_second_writer_binds_the_name_when_the_first_rolls_back(int level) throws Exception {
    String seller = PostgresSupport.freshSeller("c03t1bv");
    DataSource ds = PostgresSupport.dataSource();
    String outcome;
    try (Connection first = host(ds, level)) {
      exec(first, nameRow(seller, "380"));
      exec(first, yearRow(seller, "380", 2026));
      CompletableFuture<String> second =
          CompletableFuture.supplyAsync(
              () -> {
                try (Connection c = host(ds, level)) {
                  try {
                    exec(c, nameRow(seller, "381"));
                    exec(c, yearRow(seller, "381", 2027));
                    c.commit();
                    return "committed";
                  } catch (SQLException refused) {
                    c.rollback();
                    return refused.getSQLState();
                  }
                } catch (SQLException e) {
                  return "connection " + e.getSQLState();
                }
              });
      assertThat(waitsOn(pid(first))).isTrue();
      first.rollback();
      outcome = second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }
    assertThat(outcome).isEqualTo("committed");
    assertThat(
            count(
                "SELECT count(*) FROM einvoice_series_name WHERE seller_id = '"
                    + seller
                    + "' AND document_type = '381'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(DISTINCT document_type) FROM einvoice_series WHERE seller_id = '"
                    + seller
                    + "'"))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------ test 2: the allocator (P1)

  /** Two stores of one seller that disagree on the type of {@code RACE}, each joining a host. */
  private record Race(String seller, long target) {

    static Race prepare(String hint) {
      String seller = PostgresSupport.freshSeller(hint);
      JdbcIssuanceStore own =
          TestStores.store(
              PostgresSupport.dataSource(),
              DocumentSeriesFixture.CHAIN,
              winnerSeries(seller),
              CLOCK);
      Issuance numbered =
          own.allocate(
              new AllocationRequest(
                  new SeriesKey(seller, "INV", 2026, Mode.LIVE).scope(),
                  "in_target",
                  "",
                  "S-target",
                  SALE,
                  "r1"));
      String sha = DocumentSeriesFixture.sha("in_target");
      own.markArchiving(
          seller,
          Mode.LIVE,
          "in_target",
          sha,
          new ArchiveKey("live/" + numbered.legalNumber().value() + "-" + sha + ".xml"));
      Issuance issued = own.markIssued(seller, Mode.LIVE, "in_target");
      return new Race(seller, issued.id());
    }

    static Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> winnerSeries(String seller) {
      return Map.of(
          new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE),
          INVOICES,
          new JdbcIssuanceStore.SeriesId(seller, "RACE", Mode.LIVE),
          RACE_INVOICES);
    }

    Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> loserSeries() {
      return Map.of(
          new JdbcIssuanceStore.SeriesId(seller, "RACE", Mode.LIVE, DocumentTypeCode.CREDIT_NOTE),
          RACE_CREDITS);
    }

    JdbcIssuanceStore joined(
        Connection host, Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> series) {
      return TestStores.store(
          JdbcUnitOfWork.using(host),
          PostgresSupport.dataSource(),
          DocumentSeriesFixture.CHAIN,
          series,
          CLOCK,
          WAIT);
    }

    /** The winner: the first invoice of RACE in 2026. */
    Issuance invoice(JdbcIssuanceStore store) {
      return store.allocate(
          new AllocationRequest(
              new SeriesKey(seller, "RACE", 2026, Mode.LIVE).scope(),
              "in_race",
              "",
              "S-race",
              SALE,
              "r1"));
    }

    /** The loser: the first credit note of RACE, dated in 2027. */
    Issuance creditNote(JdbcIssuanceStore store) {
      SeriesKey key = new SeriesKey(seller, "RACE", 2027, Mode.LIVE, DocumentTypeCode.CREDIT_NOTE);
      return store.allocateTyped(
          new AllocationRequest(key.scope(), "cn_race", "", "S-cn", SALE, "r1"),
          Optional.of(target),
          Optional.of(LocalDate.parse("2027-01-10")));
    }
  }

  /**
   * Test 2 (R1): the same race through the allocator, at each level, in joined host transactions.
   * The loser has done its resume read before the winner commits. Exactly one commits. The loser
   * gets, per level (C1, measured): READ COMMITTED {@code CONFIG} naming the series; REPEATABLE
   * READ and SERIALIZABLE {@code ALLOCATION_TIMEOUT} with a fixed message and no driver text. The
   * loser's series row and issuance row never exist.
   */
  @ParameterizedTest
  @ValueSource(
      ints = {
        Connection.TRANSACTION_READ_COMMITTED,
        Connection.TRANSACTION_REPEATABLE_READ,
        Connection.TRANSACTION_SERIALIZABLE
      })
  void the_allocator_never_binds_one_name_to_two_types_at_any_level(int level) throws Exception {
    Race race = Race.prepare("c03t2");
    DataSource ds = PostgresSupport.dataSource();
    Throwable lost;
    try (Connection winnerHost = host(ds, level)) {
      race.invoice(race.joined(winnerHost, Race.winnerSeries(race.seller())));
      CompletableFuture<Throwable> loser =
          CompletableFuture.supplyAsync(
              () -> {
                try (Connection loserHost = host(ds, level)) {
                  try {
                    race.creditNote(race.joined(loserHost, race.loserSeries()));
                    loserHost.commit();
                    return null;
                  } catch (RuntimeException refused) {
                    loserHost.rollback();
                    return refused;
                  }
                } catch (SQLException e) {
                  return e;
                }
              });
      assertThat(waitsOn(pid(winnerHost))).as("the loser waits on the winner's name row").isTrue();
      winnerHost.commit();
      lost = loser.get(WAIT.toSeconds() * 2, TimeUnit.SECONDS);
    }

    assertThat(lost).as("exactly one of the two commits").isInstanceOf(EInvoiceException.class);
    EInvoiceException refusal = (EInvoiceException) lost;
    if (level == Connection.TRANSACTION_READ_COMMITTED) {
      assertThat(refusal.code()).isEqualTo(ErrorCodes.CONFIG);
      assertThat(refusal.getMessage()).contains("RACE");
    } else {
      assertThat(refusal.code()).isEqualTo(ErrorCodes.ALLOCATION_TIMEOUT);
      assertThat(refusal.getMessage())
          .contains("RACE")
          .contains("concurrent")
          .doesNotContain("ERROR")
          .doesNotContain("could not serialize");
    }
    assertThat(
            count(
                "SELECT count(*) FROM einvoice_series WHERE seller_id = '"
                    + race.seller()
                    + "' AND series = 'RACE' AND document_type = '381'"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM einvoice_issuance WHERE seller_id = '"
                    + race.seller()
                    + "' AND stripe_invoice_id = 'cn_race'"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM einvoice_issuance WHERE seller_id = '"
                    + race.seller()
                    + "' AND stripe_invoice_id = 'in_race'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM einvoice_series_name WHERE seller_id = '"
                    + race.seller()
                    + "' AND series = 'RACE' AND document_type = '380'"))
        .isEqualTo(1);
  }

  /**
   * Test 2, owned transaction: the pipeline's own unit of work, with the session default raised to
   * REPEATABLE READ. The loser is recorded with {@code DEI-117} and a next attempt (retryable); the
   * retry runs with a fresh snapshot (C2) and is refused with {@code CONFIG}, for good.
   */
  @Test
  void an_owned_transaction_at_repeatable_read_is_parked_as_retryable_then_refused_by_name()
      throws Exception {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String seller = harness.sellerId();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(PostgresSupport.jdbcUrl());
    config.setUsername(PostgresSupport.username());
    config.setPassword(PostgresSupport.password());
    config.setMaximumPoolSize(2);
    config.setConnectionInitSql("SET default_transaction_isolation = 'repeatable read'");
    try (HikariDataSource repeatable = new HikariDataSource(config)) {
      Clock at = Clock.fixed(harness.now(), ZoneOffset.UTC);
      JdbcIssuanceStore allocator =
          TestStores.store(
              JdbcUnitOfWork.ownConnection(repeatable),
              repeatable,
              DocumentSeriesFixture.CHAIN,
              Map.of(
                  new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE),
                  new SeriesDefinition("INV-{fiscalYear}-", 6, true)),
              harness.taxZone(),
              at,
              WAIT,
              TestIssueClock.following(at));
      harness.source().with(TestInvoices.finalised("in_owned"));
      String eventId = harness.receive("invoice.finalized", "in_owned");
      IssuanceUnitOfWork pipeline = harness.unitOfWorkWithAllocator(allocator);

      IssuanceUnitOfWork.Outcome first;
      try (Connection winner =
          host(PostgresSupport.dataSource(), Connection.TRANSACTION_READ_COMMITTED)) {
        exec(
            winner,
            "INSERT INTO einvoice_series_name (seller_id, mode, series, document_type, created_at)"
                + " VALUES ('"
                + seller
                + "', 'live', 'DEFAULT', '381', now())");
        CompletableFuture<IssuanceUnitOfWork.Outcome> run =
            CompletableFuture.supplyAsync(() -> pipeline.process(eventId));
        assertThat(waitsOn(pid(winner))).isTrue();
        winner.commit();
        first = run.get(WAIT.toSeconds() * 2, TimeUnit.SECONDS);
      }
      assertThat(first.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
      assertThat(first.code()).isEqualTo(ErrorCodes.ALLOCATION_TIMEOUT);
      InboundEvent parked = harness.inbound().find(eventId).orElseThrow();
      assertThat(parked.nextAttemptAt()).as("retryable: a next attempt is scheduled").isNotNull();

      IssuanceUnitOfWork.Outcome retry = pipeline.process(eventId);
      assertThat(retry.code()).isEqualTo(ErrorCodes.CONFIG);
      assertThat(harness.inbound().find(eventId).orElseThrow().nextAttemptAt())
          .as("a type mismatch is not cured by waiting")
          .isNull();
      assertThat(harness.numberedRows()).isZero();
    }
  }

  // ------------------------------------------------------------------ test 6: the wait is bounded

  /**
   * Test 6: the wait on another transaction's uncommitted name row runs under {@code SET LOCAL
   * lock_timeout}, so it ends as {@code DEI-117} after {@code allocation-timeout} (C6), never as an
   * unbounded queue.
   */
  @Test
  void the_wait_on_an_uncommitted_name_row_is_bounded_by_the_allocation_timeout() throws Exception {
    String seller = PostgresSupport.freshSeller("c03t6");
    JdbcIssuanceStore store =
        TestStores.store(
            PostgresSupport.dataSource(),
            DocumentSeriesFixture.CHAIN,
            Map.of(new JdbcIssuanceStore.SeriesId(seller, "RACE", Mode.LIVE), RACE_INVOICES),
            CLOCK,
            Duration.ofMillis(200));
    Throwable waited;
    try (Connection holder =
        host(PostgresSupport.dataSource(), Connection.TRANSACTION_READ_COMMITTED)) {
      exec(holder, nameRow(seller, "380"));
      CompletableFuture<Throwable> attempt =
          CompletableFuture.supplyAsync(
              () ->
                  catchThrowable(
                      () ->
                          store.allocate(
                              new AllocationRequest(
                                  new SeriesKey(seller, "RACE", 2026, Mode.LIVE).scope(),
                                  "in_wait",
                                  "",
                                  "S-wait",
                                  SALE,
                                  "r1"))));
      try {
        waited = attempt.get(5, TimeUnit.SECONDS);
      } catch (TimeoutException unbounded) {
        holder.rollback();
        attempt.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        throw new AssertionError("the wait on the name row was not bounded by the timeout");
      }
      holder.rollback();
    }
    assertThat(waited).isInstanceOf(EInvoiceException.class);
    assertThat(((EInvoiceException) waited).code()).isEqualTo(ErrorCodes.ALLOCATION_TIMEOUT);
    assertThat(sqlState(waited)).isEqualTo("55P03");
  }

  // ------------------------------------------------------------------ D-C03-B6: both unique
  // indexes

  /**
   * Guard for the rule on {@code einvoice_series_name} (D-PR40-4). The shipped name table has
   * exactly two unique or exclusion indexes, the primary key and the type key, and the type key's
   * columns contain the primary key's, so it can never refuse a row the primary key accepts.
   */
  @Test
  void the_name_table_has_only_unique_indexes_its_primary_key_implies() {
    HikariDataSource ds = (HikariDataSource) PostgresSupport.freshDatabase("p40bidx");
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT i.indexrelid::regclass::text, i.indpred IS NULL, i.indexprs IS NULL,"
                    + " (SELECT bool_and(pk = ANY (i.indkey::int2[])) FROM unnest("
                    + "   (SELECT p.indkey::int2[] FROM pg_index p"
                    + "     WHERE p.indrelid = i.indrelid AND p.indisprimary)) AS pk)"
                    + " FROM pg_index i"
                    + " WHERE i.indrelid = 'einvoice_series_name'::regclass"
                    + "   AND (i.indisunique OR i.indisexclusion) ORDER BY 1")) {
      java.util.List<String> names = new java.util.ArrayList<>();
      while (rs.next()) {
        names.add(rs.getString(1));
        assertThat(rs.getBoolean(2)).as("not partial: " + rs.getString(1)).isTrue();
        assertThat(rs.getBoolean(3)).as("no expression: " + rs.getString(1)).isTrue();
        assertThat(rs.getBoolean(4)).as("covers the primary key: " + rs.getString(1)).isTrue();
      }
      assertThat(names)
          .containsExactly("einvoice_series_name_pkey", "einvoice_series_name_type_key");
    } catch (SQLException e) {
      throw new AssertionError(e);
    } finally {
      ds.close();
    }
  }

  /**
   * A fresh database where a writer can be held between its heap insert and its index inserts.
   * PostgreSQL inserts index entries in index OID order, so a test-only, non-unique index whose
   * expression waits on an advisory lock (only in sessions with {@code c03b.pause = on}) is created
   * before the primary key and the type key, which are then re-created. Nothing else changes.
   */
  private static HikariDataSource pausable(String hint) {
    HikariDataSource ds = (HikariDataSource) PostgresSupport.freshDatabase(hint);
    for (String sql :
        new String[] {
          "CREATE FUNCTION c03b_pause(text) RETURNS text LANGUAGE plpgsql IMMUTABLE AS $$ BEGIN"
              + " IF current_setting('c03b.pause', true) = 'on' THEN"
              + " PERFORM pg_advisory_xact_lock(424303); END IF; RETURN $1; END $$",
          "ALTER TABLE einvoice_series DROP CONSTRAINT einvoice_series_name_fk",
          "ALTER TABLE einvoice_series_name DROP CONSTRAINT einvoice_series_name_type_key",
          "ALTER TABLE einvoice_series_name DROP CONSTRAINT einvoice_series_name_pkey",
          "CREATE INDEX c03b_pause_first ON einvoice_series_name ((c03b_pause(series)))",
          "ALTER TABLE einvoice_series_name ADD CONSTRAINT einvoice_series_name_pkey"
              + " PRIMARY KEY (seller_id, mode, series)",
          "ALTER TABLE einvoice_series_name ADD CONSTRAINT einvoice_series_name_type_key"
              + " UNIQUE (seller_id, mode, series, document_type)",
          "ALTER TABLE einvoice_series ADD CONSTRAINT einvoice_series_name_fk FOREIGN KEY"
              + " (seller_id, mode, series, document_type) REFERENCES einvoice_series_name"
              + " (seller_id, mode, series, document_type)"
        }) {
      PostgresSupport.executeOn(ds, sql);
    }
    return ds;
  }

  private static HikariDataSource paused(HikariDataSource ds) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(ds.getJdbcUrl());
    config.setUsername(ds.getUsername());
    config.setPassword(ds.getPassword());
    config.setMaximumPoolSize(2);
    config.setConnectionInitSql("SET c03b.pause = 'on'");
    return new HikariDataSource(config);
  }

  private static JdbcIssuanceStore opener(DataSource ds, String seller, DocumentTypeCode type) {
    return TestStores.store(
        ds,
        DocumentSeriesFixture.CHAIN,
        Map.of(
            new JdbcIssuanceStore.SeriesId(seller, "RACE", Mode.LIVE, type),
            type == DocumentTypeCode.CREDIT_NOTE ? RACE_CREDITS : RACE_INVOICES),
        CLOCK);
  }

  /**
   * D-C03-B6, deterministic. Writer A (held) has inserted its name row into the heap but not into
   * any index when writer B inserts the same name, same type, and commits. A then meets B's row in
   * the primary key and in the type key. Both are unique indexes; with only the primary key as the
   * conflict target, the type key raised {@code 23505} and A's allocation died with DEI-102. With
   * no target, both are arbiters and A does nothing, then reads B's matching type and goes on.
   */
  @Test
  void a_writer_that_meets_the_same_name_in_the_type_key_first_does_nothing_and_goes_on()
      throws Exception {
    String seller = PostgresSupport.freshSeller("c03b");
    try (HikariDataSource ds = pausable("c03brace");
        HikariDataSource held = paused(ds);
        Connection gate = ds.getConnection()) {
      exec(gate, "SELECT pg_advisory_lock(424303)");
      CompletableFuture<Throwable> a =
          CompletableFuture.supplyAsync(
              () ->
                  catchThrowable(
                      () ->
                          opener(held, seller, DocumentTypeCode.COMMERCIAL_INVOICE)
                              .openSeries(new SeriesKey(seller, "RACE", 2026, Mode.LIVE))));
      assertThat(waitsOn(ds, pid(gate))).as("writer A is held inside its insert").isTrue();
      opener(ds, seller, DocumentTypeCode.COMMERCIAL_INVOICE)
          .openSeries(new SeriesKey(seller, "RACE", 2027, Mode.LIVE));
      exec(gate, "SELECT pg_advisory_unlock(424303)");
      assertThat(a.get(WAIT.toSeconds(), TimeUnit.SECONDS)).as("writer A").isNull();
      assertThat(
              PostgresSupport.scalarOn(
                  ds,
                  "SELECT count(*) FROM einvoice_series_name WHERE seller_id = '"
                      + seller
                      + "' AND document_type = '380'"))
          .isEqualTo(1);
      assertThat(
              PostgresSupport.scalarOn(
                  ds, "SELECT count(*) FROM einvoice_series WHERE seller_id = '" + seller + "'"))
          .isEqualTo(2);
    }
  }

  /**
   * The same interleaving with the other type: doing nothing must not hide the binding. Writer A
   * (credit notes) does nothing on the name, reads B's invoice type, and is refused with CONFIG
   * naming the series; no credit-note row exists.
   */
  @Test
  void doing_nothing_on_the_name_never_hides_a_second_type() throws Exception {
    String seller = PostgresSupport.freshSeller("c03bt");
    try (HikariDataSource ds = pausable("c03btype");
        HikariDataSource held = paused(ds);
        Connection gate = ds.getConnection()) {
      exec(gate, "SELECT pg_advisory_lock(424303)");
      CompletableFuture<Throwable> a =
          CompletableFuture.supplyAsync(
              () ->
                  catchThrowable(
                      () ->
                          opener(held, seller, DocumentTypeCode.CREDIT_NOTE)
                              .openSeries(
                                  new SeriesKey(
                                      seller,
                                      "RACE",
                                      2027,
                                      Mode.LIVE,
                                      DocumentTypeCode.CREDIT_NOTE))));
      assertThat(waitsOn(ds, pid(gate))).isTrue();
      opener(ds, seller, DocumentTypeCode.COMMERCIAL_INVOICE)
          .openSeries(new SeriesKey(seller, "RACE", 2026, Mode.LIVE));
      exec(gate, "SELECT pg_advisory_unlock(424303)");
      Throwable refused = a.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      assertThat(refused).isInstanceOf(EInvoiceException.class);
      assertThat(((EInvoiceException) refused).code()).isEqualTo(ErrorCodes.CONFIG);
      assertThat(refused.getMessage()).contains("RACE");
      assertThat(
              PostgresSupport.scalarOn(
                  ds,
                  "SELECT count(*) FROM einvoice_series WHERE seller_id = '"
                      + seller
                      + "' AND document_type = '381'"))
          .isZero();
    }
  }
}
