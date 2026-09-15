package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** N-09: the database refusal probes the server, never a configured dialect string. */
class DatabaseProbeTest {

  @Test
  void a_server_that_is_not_postgresql_is_refused_with_a_typed_error() {
    // The scenario this matters in is the one where the configuration is what is wrong: an
    // application pointed at another server with a PostgreSQL dialect still configured would pass
    // a property check and then run UPDATE ... RETURNING against a server that does not have it.
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:einvoice-dialect-probe;DB_CLOSE_DELAY=-1");
    try (HikariDataSource h2 = new HikariDataSource(config)) {
      assertThatThrownBy(() -> JdbcSupport.requirePostgreSql(h2))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.UNSUPPORTED_DATABASE);
    }
  }

  @Test
  void a_real_postgresql_passes_the_same_probe() {
    DataSource postgres = PostgresSupport.dataSource();
    assertThatCode(() -> JdbcSupport.requirePostgreSql(postgres)).doesNotThrowAnyException();
  }

  @Test
  void the_schema_step_is_idempotent() {
    // The host may own its own migrations and run ours twice, or two instances may start together.
    assertThatCode(() -> JdbcSupport.initializeSchema(PostgresSupport.dataSource()))
        .doesNotThrowAnyException();
    assertThatCode(() -> JdbcSupport.initializeSchema(PostgresSupport.dataSource()))
        .doesNotThrowAnyException();
  }
}
