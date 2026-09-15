package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * N-05. Advisory lock keys are database-wide, and a host that installs two modules of this line
 * against one database is the intended deployment. A copied sibling constant would serialise every
 * invoice issuance against every erasure, under load, looking like a database problem.
 */
class AdvisoryLockRegistryTest {

  @Test
  void this_modules_lock_constants_are_distinct_from_each_other_and_from_every_sibling() {
    assertThat(AdvisoryLocks.SCHEMA_CLASS_ID).isNotEqualTo(AdvisoryLocks.CHAIN_CLASS_ID);
    for (int sibling : AdvisoryLocks.SIBLING_TWO_ARG_CLASS_IDS) {
      assertThat(AdvisoryLocks.SCHEMA_CLASS_ID).isNotEqualTo(sibling);
      assertThat(AdvisoryLocks.CHAIN_CLASS_ID).isNotEqualTo(sibling);
    }
    // The one-argument constants cannot collide with a two-argument class id at all - the two
    // forms are separate namespaces - and this is here so that stays a checked fact.
    assertThat(Arrays.stream(AdvisoryLocks.SIBLING_ONE_ARG_CONSTANTS).distinct().count())
        .isEqualTo(AdvisoryLocks.SIBLING_ONE_ARG_CONSTANTS.length);
  }

  @Test
  void every_lock_this_module_takes_uses_the_two_argument_form() throws IOException {
    // The flat one-argument form is one global 64-bit space shared with both siblings. Nothing in
    // this module may use it, in Java or in the schema.
    String adapter = source("adapter/jdbc/AdvisoryLocks.java");
    String store = source("adapter/jdbc/JdbcIssuanceStore.java");
    assertThat(adapter).contains("pg_advisory_xact_lock(?, hashtext(?))");
    assertThat(store).doesNotContain("pg_advisory_xact_lock(?)");

    String schema = resource("/com/housedevinci/einvoice/schema-postgresql.sql");
    assertThat(schema).contains("pg_advisory_xact_lock(1162432073, hashtext(");
    // A one-argument call has exactly one argument before the closing bracket; every call in the
    // schema must name a class id and a key.
    assertThat(schema.split("pg_advisory_xact_lock\\(", -1).length - 1).isEqualTo(1);
  }

  @Test
  void the_schema_and_the_java_constant_name_the_same_lock() throws IOException {
    // Two places hold this value - the DDL takes the lock itself, Java takes it for the chain - and
    // a divergence would mean two instances starting at once do not actually exclude each other.
    String schema = resource("/com/housedevinci/einvoice/schema-postgresql.sql");
    assertThat(schema)
        .contains(
            "pg_advisory_xact_lock("
                + AdvisoryLocks.SCHEMA_CLASS_ID
                + ", hashtext('"
                + AdvisoryLocks.lengthPrefixed(AdvisoryLocks.SCHEMA_KEY)
                + "')");
  }

  private static String source(String path) throws IOException {
    return java.nio.file.Files.readString(
        java.nio.file.Path.of("src/main/java/com/housedevinci/einvoice/" + path));
  }

  private static String resource(String path) throws IOException {
    try (InputStream in = AdvisoryLockRegistryTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
