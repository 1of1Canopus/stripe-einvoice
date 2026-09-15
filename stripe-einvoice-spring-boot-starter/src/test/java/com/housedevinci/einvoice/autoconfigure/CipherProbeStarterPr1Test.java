package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Cipher probe for PR 1, starter side. FAILS on 71ce8d6. */
class CipherProbeStarterPr1Test {

  // D1-07
  @Test
  void probe_the_view_guard_sees_a_view_when_it_runs_as_the_runtime_role() throws Exception {
    TestPostgres.execute("DROP VIEW IF EXISTS v_probe_invoices");
    TestPostgres.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'einvoice_probe_rt')"
            + " THEN CREATE ROLE einvoice_probe_rt LOGIN PASSWORD 'probe'; END IF; END $$");
    TestPostgres.execute(
        "GRANT SELECT, INSERT ON einvoice_series, einvoice_issuance, einvoice_issuance_event,"
            + " einvoice_issuance_anchor TO einvoice_probe_rt");
    TestPostgres.execute("GRANT USAGE ON SCHEMA public TO einvoice_probe_rt");
    // The host's own view over our ledger, created by the owner as it would be in production.
    TestPostgres.execute("CREATE VIEW v_probe_invoices AS SELECT * FROM einvoice_issuance");
    TestPostgres.execute("GRANT SELECT ON v_probe_invoices TO einvoice_probe_rt");
    DataSource asRuntimeRole = runtimeRoleDataSource();
    try {
      assertThatThrownBy(() -> DatabaseViewGuard.refuseViewsOverOurTables(asRuntimeRole))
          .describedAs(
              "the guard must refuse the view when it runs as the role the grants document,"
                  + " not only as the table owner")
          .hasMessageContaining("v_probe_invoices");
    } finally {
      TestPostgres.execute("DROP VIEW IF EXISTS v_probe_invoices");
    }
  }

  private static DataSource runtimeRoleDataSource() throws Exception {
    java.lang.reflect.Method m = TestPostgres.class.getDeclaredMethod("dataSource");
    m.setAccessible(true);
    HikariDataSource shared = (HikariDataSource) m.invoke(null);
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(shared.getJdbcUrl());
    config.setUsername("einvoice_probe_rt");
    config.setPassword("probe");
    config.setMaximumPoolSize(2);
    return new HikariDataSource(config);
  }
}
