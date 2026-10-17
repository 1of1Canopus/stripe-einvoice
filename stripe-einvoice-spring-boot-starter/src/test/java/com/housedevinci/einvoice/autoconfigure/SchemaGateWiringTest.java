package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The schema version gate as the starter wires it: with {@code initialize-schema=false} an
 * unprepared schema stops startup by name, and with the default the upgrade from a 0.1.0 schema
 * runs with the configured tax zone.
 */
class SchemaGateWiringTest {

  private static final String SECRET =
      java.util.Base64.getEncoder()
          .encodeToString("einvoice-test-chain-secret-0001!".getBytes(StandardCharsets.UTF_8));

  private static ApplicationContextRunner runner(DataSource ds, String... extra) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EInvoiceAutoConfiguration.class))
        .withBean(DataSource.class, () -> ds)
        .withPropertyValues(
            "einvoice.seller.id=seller-gate",
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=" + SECRET,
            "einvoice.chain.hmac-key-id=k1")
        .withPropertyValues(extra);
  }

  @Test
  void an_unprepared_schema_with_initialisation_off_refuses_startup_by_name() {
    try (HikariDataSource ds = TestPostgres.emptyDatabase("gate_unprepared")) {
      runner(ds, "einvoice.initialize-schema=false")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(EInvoiceException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCodes.SCHEMA_NOT_PREPARED);
              });
    }
  }

  @Test
  void the_upgrade_from_0_1_0_runs_with_the_configured_tax_zone() throws Exception {
    try (HikariDataSource ds = TestPostgres.emptyDatabase("gate_upgrade")) {
      String released;
      try (var in =
          getClass()
              .getResourceAsStream("/com/housedevinci/einvoice/schema-postgresql-0.1.0.sql")) {
        released = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      try (Connection c = ds.getConnection();
          Statement st = c.createStatement()) {
        st.execute(released);
        // One 0.1.0 row, numbered at 23:30 UTC on 20 January: 21 January in Paris.
        st.execute(
            "INSERT INTO einvoice_series (seller_id, series, fiscal_year, mode, prefix, width,"
                + " next_number, updated_at) VALUES ('seller-gate', 'DEFAULT', 2026, 'live',"
                + " 'INV-2026-', 6, 1, now())");
        st.execute("UPDATE einvoice_series SET next_number = 2 WHERE seller_id = 'seller-gate'");
        st.execute(
            "INSERT INTO einvoice_issuance (seller_id, mode, stripe_invoice_id, series,"
                + " fiscal_year, legal_number, counter, issued_at, allocated_at, state) VALUES"
                + " ('seller-gate', 'live', 'in_old', 'DEFAULT', 2026, 'INV-2026-000001', 1,"
                + " '2026-01-20T23:30:00Z', now(), 'NUMBERED')");
      }
      runner(ds)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                try (Connection c = ds.getConnection();
                    Statement st = c.createStatement();
                    var rs =
                        st.executeQuery(
                            "SELECT last_issue_date::text FROM einvoice_series"
                                + " WHERE seller_id = 'seller-gate' AND fiscal_year = 2026")) {
                  rs.next();
                  assertThat(rs.getString(1)).isEqualTo("2026-01-21");
                }
              });
    }
  }
}
