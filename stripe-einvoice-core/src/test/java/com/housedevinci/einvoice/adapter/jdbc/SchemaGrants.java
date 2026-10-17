package com.housedevinci.einvoice.adapter.jdbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * Applies the GRANT statements of {@code docs/schema-grants.sql} to a test role, by program.
 *
 * <p>The grants file is the documented runtime role. A probe that hard-codes its own copy of the
 * grants tests the copy, not the file: a grant added to or missing from the file would never turn a
 * probe red. So every grant probe reads the file itself.
 */
final class SchemaGrants {

  private static final Path FILE = Path.of("..", "docs", "schema-grants.sql");

  private SchemaGrants() {}

  /** Creates {@code role} if needed and grants it exactly what the documented role holds. */
  static DataSource roleWithDocumentedGrants(DataSource owner, String role, String password) {
    PostgresSupport.executeOn(
        owner,
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
            + role
            + "') THEN CREATE ROLE "
            + role
            + " LOGIN PASSWORD '"
            + password
            + "'; END IF; END $$");
    PostgresSupport.executeOn(owner, "GRANT USAGE ON SCHEMA public TO " + role);
    for (String grant : grants()) {
      PostgresSupport.executeOn(owner, grant.replace("einvoice_runtime", role));
    }
    return PostgresSupport.asRole(owner, role, password);
  }

  static List<String> grants() {
    String text;
    try {
      text = Files.readString(FILE);
    } catch (IOException e) {
      throw new UncheckedIOException("docs/schema-grants.sql must be readable by the probes", e);
    }
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : text.lines().toList()) {
      String code = line.contains("--") ? line.substring(0, line.indexOf("--")) : line;
      if (code.isBlank()) {
        continue;
      }
      current.append(code.strip()).append(' ');
      if (code.strip().endsWith(";")) {
        String statement = current.toString().strip();
        if (statement.toUpperCase(Locale.ROOT).startsWith("GRANT ")) {
          statements.add(statement.substring(0, statement.length() - 1));
        }
        current.setLength(0);
      }
    }
    if (statements.isEmpty()) {
      throw new IllegalStateException("docs/schema-grants.sql has no GRANT statement");
    }
    return statements;
  }
}
