package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T19 (D-PR52-3, C1 and D6): the orphan walk's lookup uses its partial index, in the generic plan
 * that pgjdbc runs after five executions and in a custom plan. 300,000 rows over 100 sellers, a
 * page of 500 keys. Without the {@code archive_key <> ''} term the planner cannot prove the index
 * condition from {@code = ANY(array)} and reads the whole table.
 */
@Timeout(300)
class KnownKeysIndexTest {

  private static DataSource db;

  @BeforeAll
  static void seed() throws Exception {
    db = PostgresSupport.freshDatabase("knownkeys");
    try (Connection c = db.getConnection();
        Statement st = c.createStatement()) {
      // A fresh database of this test alone; the guard triggers would refuse rows with no series.
      st.execute("ALTER TABLE einvoice_issuance DISABLE TRIGGER USER");
      st.execute(
          "INSERT INTO einvoice_issuance (seller_id, mode, stripe_invoice_id, series, fiscal_year,"
              + " legal_number, counter, issued_at, allocated_at, archive_key, state,"
              + " document_type)"
              + " SELECT 's' || (g % 100), 'live', 'in_' || g, 'DEFAULT', 2026, 'INV-' || g, g,"
              + " now(), now(),"
              + " CASE WHEN g % 10 = 0 THEN '' ELSE 'live/s' || (g % 100) || '/2026/INV-' || g"
              + " || '-' || md5(g::text) || md5(g::text) || '.xml' END, 'ISSUED', '380'"
              + " FROM generate_series(1, 300000) g");
      st.execute("ALTER TABLE einvoice_issuance ENABLE TRIGGER USER");
      st.execute("ANALYZE einvoice_issuance");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"force_generic_plan", "force_custom_plan"})
  void the_key_lookup_uses_the_partial_index(String planMode) throws Exception {
    String statement = JdbcIssuanceStore.KNOWN_KEYS;
    int n = 0;
    StringBuilder numbered = new StringBuilder();
    for (char ch : statement.toCharArray()) {
      numbered.append(ch == '?' ? "$" + (++n) : String.valueOf(ch));
    }
    String keys =
        IntStream.rangeClosed(1, 500)
            .map(i -> i * 100 + 7)
            .mapToObj(g -> "'live/s7/2026/INV-" + g + "-x.xml'")
            .collect(Collectors.joining(","));
    StringBuilder plan = new StringBuilder();
    try (Connection c = db.getConnection();
        Statement st = c.createStatement()) {
      st.execute("SET plan_cache_mode = " + planMode);
      st.execute("PREPARE known(text, text, text[]) AS " + numbered);
      try (ResultSet rs =
          st.executeQuery("EXPLAIN EXECUTE known('s7', 'live', ARRAY[" + keys + "]::text[])")) {
        while (rs.next()) {
          plan.append(rs.getString(1)).append('\n');
        }
      }
      st.execute("DEALLOCATE known");
    }

    assertThat(plan.toString())
        .as(planMode)
        .contains("einvoice_issuance_archive_key")
        .doesNotContain("Seq Scan");
  }

  @Test
  void the_lookup_answers_with_the_recorded_keys_only() {
    JdbcIssuanceStore reader =
        TestStores.store(
            db,
            com.housedevinci.einvoice.domain.IssuanceChain.keyed(
                "0123456789abcdef0123456789abcdef"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "k1"),
            java.util.Map.of(),
            java.time.Clock.systemUTC());
    String recorded = "live/s7/2026/INV-107-" + md5Twice(107) + ".xml";

    assertThat(
            reader.knownKeys(
                "s7",
                com.housedevinci.einvoice.domain.Mode.LIVE,
                java.util.List.of(recorded, "live/s7/2026/INV-207-x.xml")))
        .containsExactly(recorded);
    assertThat(
            reader.knownKeys(
                "s8", com.housedevinci.einvoice.domain.Mode.LIVE, java.util.List.of(recorded)))
        .as("another seller")
        .isEmpty();
    assertThat(
            reader.knownKeys(
                "s7", com.housedevinci.einvoice.domain.Mode.TEST, java.util.List.of(recorded)))
        .as("the other mode")
        .isEmpty();
  }

  private static String md5Twice(int g) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("MD5")
              .digest(String.valueOf(g).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      String hex = java.util.HexFormat.of().formatHex(digest);
      return hex + hex;
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
