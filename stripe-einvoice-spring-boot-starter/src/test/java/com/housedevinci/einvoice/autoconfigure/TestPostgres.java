package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.adapter.jdbc.JdbcSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** One PostgreSQL container for the starter's tests too, started once and reused. */
public final class TestPostgres {

  private static final PostgreSQLContainer<?> CONTAINER =
      new PostgreSQLContainer<>(
          org.testcontainers.utility.DockerImageName.parse(
                  "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777")
              .asCompatibleSubstituteFor("postgres"));

  private static volatile HikariDataSource dataSource;

  private TestPostgres() {}

  public static synchronized DataSource dataSource() {
    if (dataSource == null) {
      CONTAINER.start();
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(CONTAINER.getJdbcUrl());
      config.setUsername(CONTAINER.getUsername());
      config.setPassword(CONTAINER.getPassword());
      config.setMaximumPoolSize(8);
      dataSource = new HikariDataSource(config);
      JdbcSupport.initializeSchema(dataSource);
    }
    return dataSource;
  }

  public static long count(String sql) {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getLong(1) : -1;
    } catch (SQLException e) {
      throw new IllegalStateException("test query failed: " + e.getMessage(), e);
    }
  }

  public static void execute(String sql) {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException("test setup failed: " + e.getMessage(), e);
    }
  }
}
