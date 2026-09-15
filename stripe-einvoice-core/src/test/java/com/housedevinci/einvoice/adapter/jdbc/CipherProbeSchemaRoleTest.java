package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/** D1-10: the default schema step against the role docs/schema-grants.sql documents. */
class CipherProbeSchemaRoleTest {

  @Test
  void probe_the_default_schema_step_works_as_the_documented_runtime_role() {
    String role = "einvoice_schema_probe";
    PostgresSupport.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
            + role
            + "') THEN CREATE ROLE "
            + role
            + " LOGIN PASSWORD 'probe'; END IF; END $$");
    PostgresSupport.execute("GRANT CONNECT ON DATABASE test TO " + role);
    PostgresSupport.execute("GRANT USAGE ON SCHEMA public TO " + role);
    PostgresSupport.execute(
        "GRANT SELECT, INSERT ON einvoice_series, einvoice_issuance, einvoice_issuance_event,"
            + " einvoice_issuance_anchor TO "
            + role);
    PostgresSupport.execute(
        "GRANT UPDATE ON einvoice_series, einvoice_issuance, einvoice_issuance_anchor TO " + role);
    assertThatCode(() -> JdbcSupport.initializeSchema(PostgresSupport.dataSourceAs(role, "probe")))
        .describedAs(
            "einvoice.initialize-schema defaults to true, so the default configuration must work"
                + " as the role the grants document, or the default must not be true")
        .doesNotThrowAnyException();
  }
}
