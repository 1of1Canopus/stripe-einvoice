package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * D-A1-C08: every guard function pins its search path, and the final block of the script refuses
 * (EIU03) when one does not. The reviewer's operator probes show the pin works; these two show the
 * check that keeps it from being lost.
 */
class SchemaSearchPathPinTest {

  private static final String PIN = " SET search_path = pg_catalog, pg_temp;";

  private DataSource ds;

  @AfterEach
  void close() {
    PostgresSupport.close(ds);
  }

  private static String script() throws java.io.IOException {
    try (var in =
        JdbcSupport.class.getResourceAsStream("/com/housedevinci/einvoice/schema-postgresql.sql")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void every_guard_function_of_the_script_carries_the_pinned_search_path() {
    ds = PostgresSupport.freshDatabase("pinall");

    assertThat(
            PostgresSupport.scalarOn(
                ds,
                "SELECT count(*) FROM pg_proc WHERE proname IN ('einvoice_series_guard',"
                    + " 'einvoice_issuance_guard', 'einvoice_series_insert_guard',"
                    + " 'einvoice_issuance_insert_guard', 'einvoice_schema_guard',"
                    + " 'einvoice_append_only', 'einvoice_anchor_monotonic')"
                    + " AND proconfig @> ARRAY['search_path=pg_catalog, pg_temp']::text[]"))
        .isEqualTo(7);
  }

  @Test
  void a_guard_function_without_the_pin_makes_the_final_block_refuse_with_eiu03() throws Exception {
    ds = PostgresSupport.freshDatabase("pinmissing");
    String unpinned = script().replace("LANGUAGE plpgsql" + PIN, "LANGUAGE plpgsql;");
    assertThat(unpinned).isNotEqualTo(script());

    String lastState = null;
    String lastMessage = "";
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(true);
      for (String sql : CipherProbePr33Test.split(unpinned)) {
        try (Statement st = c.createStatement()) {
          st.execute(sql);
          lastState = null;
        } catch (SQLException refused) {
          lastState = refused.getSQLState();
          lastMessage = refused.getMessage();
        }
      }
    }

    assertThat(lastState).isEqualTo("EIU03");
    assertThat(lastMessage).contains("search path");
  }
}
