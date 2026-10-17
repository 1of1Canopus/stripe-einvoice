package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThatCode;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** D1-10: the default schema step against the role docs/schema-grants.sql documents. */
class CipherProbeSchemaRoleTest {

  @Test
  void probe_the_default_schema_step_works_as_the_documented_runtime_role() {
    // The grants are read from docs/schema-grants.sql itself, not copied here: the file is what an
    // operator applies, so the file is what must work.
    DataSource runtime =
        SchemaGrants.roleWithDocumentedGrants(
            PostgresSupport.dataSource(), "einvoice_schema_probe", "probe");
    assertThatCode(() -> JdbcSupport.initializeSchema(runtime))
        .describedAs(
            "einvoice.initialize-schema defaults to true, so the default configuration must work"
                + " as the role the grants document, or the default must not be true")
        .doesNotThrowAnyException();
  }
}
