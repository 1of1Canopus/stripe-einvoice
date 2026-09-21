package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL container for the whole test JVM.
 *
 * <p>Deliberately a singleton started once and never stopped (Ryuk reaps it): the sibling module
 * paid for a container per test class in wall-clock time on every push, and a numbering test suite
 * that is slow is a numbering test suite people run less often.
 *
 * <p>Isolation between tests is by <b>unique seller id</b>, not by truncation - these tables refuse
 * TRUNCATE and DELETE at the database, which is the property under test.
 */
public final class PostgresSupport {

  private static final PostgreSQLContainer<?> CONTAINER =
      new PostgreSQLContainer<>(
          org.testcontainers.utility.DockerImageName.parse(
                  "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777")
              .asCompatibleSubstituteFor("postgres"));

  private static final AtomicInteger SELLER_SEQUENCE = new AtomicInteger();

  private static volatile HikariDataSource dataSource;

  private PostgresSupport() {}

  public static synchronized DataSource dataSource() {
    if (dataSource == null) {
      if (!CONTAINER.isRunning()) {
        CONTAINER.start();
      }
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(CONTAINER.getJdbcUrl());
      config.setUsername(CONTAINER.getUsername());
      config.setPassword(CONTAINER.getPassword());
      config.setMaximumPoolSize(24);
      dataSource = new HikariDataSource(config);
      JdbcSupport.initializeSchema(dataSource);
    }
    return dataSource;
  }

  /** A DataSource whose connections are opened as {@code role}, for the grant tests. */
  public static DataSource dataSourceAs(String role, String password) {
    dataSource();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(CONTAINER.getJdbcUrl());
    config.setUsername(role);
    config.setPassword(password);
    config.setMaximumPoolSize(2);
    return new HikariDataSource(config);
  }

  public static String jdbcUrl() {
    dataSource();
    return CONTAINER.getJdbcUrl();
  }

  public static String username() {
    dataSource();
    return CONTAINER.getUsername();
  }

  public static String password() {
    dataSource();
    return CONTAINER.getPassword();
  }

  /**
   * A DataSource on a brand new database inside the same container.
   *
   * <p>Isolation by seller id is enough for the allocator, because every statement it runs is
   * seller-scoped. It is not enough for the chain: a hash chain is one trail for the whole
   * database, and a disposed row written by another test class is exactly what the verifier is
   * supposed to complain about. Those tests get their own database rather than a shared one with a
   * weakened assertion.
   */
  public static DataSource freshDatabase(String hint) {
    dataSource();
    String name =
        ("einvoice_" + hint + "_" + SELLER_SEQUENCE.incrementAndGet())
            .toLowerCase(java.util.Locale.ROOT);
    execute("CREATE DATABASE " + name);
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(CONTAINER.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"));
    config.setUsername(CONTAINER.getUsername());
    config.setPassword(CONTAINER.getPassword());
    config.setMaximumPoolSize(4);
    HikariDataSource fresh = new HikariDataSource(config);
    JdbcSupport.initializeSchema(fresh);
    return fresh;
  }

  /** Runs a statement on a DataSource other than the shared one. */
  public static void executeOn(DataSource ds, String sql) {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException("test setup failed: " + e.getMessage(), e);
    }
  }

  /** A seller id no other test uses, so nothing has to be deleted between tests. */
  public static String freshSeller(String hint) {
    return "seller-" + hint + "-" + SELLER_SEQUENCE.incrementAndGet();
  }

  public static JdbcIssuanceStore store(
      String sellerId, SeriesDefinition definition, Clock clock, IssuanceChain chain) {
    return new JdbcIssuanceStore(
        dataSource(),
        chain,
        Map.of(
            new JdbcIssuanceStore.SeriesId(sellerId, "DEFAULT", Mode.LIVE),
            definition,
            new JdbcIssuanceStore.SeriesId(sellerId, "DEFAULT", Mode.TEST),
            definition),
        clock);
  }

  public static void execute(String sql) {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException("test setup failed: " + e.getMessage(), e);
    }
  }

  /** A scalar query against a database other than the shared one. */
  public static long scalarOn(DataSource ds, String sql) {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getLong(1) : -1;
    } catch (SQLException e) {
      throw new IllegalStateException("test query failed: " + e.getMessage(), e);
    }
  }

  public static long scalar(String sql) {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getLong(1) : -1;
    } catch (SQLException e) {
      throw new IllegalStateException("test query failed: " + e.getMessage(), e);
    }
  }
}
