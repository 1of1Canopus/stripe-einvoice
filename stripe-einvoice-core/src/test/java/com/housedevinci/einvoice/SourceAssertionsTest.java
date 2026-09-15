package com.housedevinci.einvoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Two properties that are cheaper to assert over the source than to re-derive in review, and that a
 * future change would otherwise break silently.
 */
class SourceAssertionsTest {

  private static final Path MAIN = Path.of("src/main/java");

  @Test
  void no_sequence_no_nextval_and_no_generated_value_touches_the_legal_number() throws IOException {
    // Checklist line 23. A PostgreSQL sequence is deliberately non-transactional: nextval is not
    // rolled back, so every failed transaction, every crash between allocation and archive and
    // every retry after a timeout would leave a permanent hole in a legal series. This is the
    // single mechanism the spec originally named and the one mechanism that cannot hold the
    // property, so its absence is asserted rather than remembered.
    List<String> offenders =
        sources()
            .filter(
                path -> {
                  // Comments are stripped first: this module's javadoc explains at length why a
                  // sequence cannot carry a legal number, and that prose is the opposite of a
                  // violation.
                  String text = withoutComments(read(path)).toLowerCase(Locale.ROOT);
                  return text.contains("nextval")
                      || text.contains("@generatedvalue")
                      || text.contains("create sequence");
                })
            .map(Path::toString)
            .toList();
    assertThat(offenders).isEmpty();

    // Comments are stripped here too: the schema explains at length why it has no sequence, and
    // that explanation must not read as a use.
    String ddl =
        resource("/com/housedevinci/einvoice/schema-postgresql.sql")
            .replaceAll("(?m)--.*$", "")
            .toLowerCase(Locale.ROOT);
    assertThat(ddl).doesNotContain("nextval");
    assertThat(ddl).doesNotContain("create sequence");
    // bigserial appears on exactly two columns, both surrogate row ids, and the schema says so
    // where each is used. The pin is what stops a third one appearing on a numbering column.
    assertThat(ddl.split("bigserial", -1).length - 1).isEqualTo(2);
  }

  @Test
  void every_statement_over_the_series_and_the_ledger_carries_the_seller_and_the_mode() {
    // Checklist line 48. The webhook path is unauthenticated: a statement that can run without a
    // server-resolved seller is a statement that will one day run without one.
    String store =
        read(MAIN.resolve("com/housedevinci/einvoice/adapter/jdbc/JdbcIssuanceStore.java"));
    List<String> statements = sqlConstantsOf(store);
    assertThat(statements).isNotEmpty();
    for (String statement : statements) {
      String sql = statement.toLowerCase(Locale.ROOT);
      boolean touchesScopedTable =
          sql.contains("einvoice_series") || sql.contains("einvoice_issuance ");
      if (touchesScopedTable) {
        assertThat(sql)
            .describedAs("statement without a seller predicate: %s", statement)
            .contains("seller_id");
        assertThat(sql)
            .describedAs("statement without a mode predicate: %s", statement)
            .contains("mode");
      }
    }
  }

  @Test
  void the_only_unscoped_reads_are_the_chains_own_and_they_take_no_caller_input() {
    // Said out loud rather than left as an exception to the rule above. The issuance log and its
    // anchor are one trail for the whole database - that is what makes them provable - so the
    // verifier reads them whole. Those statements take no parameter that a caller controls: a
    // sequence cursor and a page size, both this module's own.
    String store =
        read(MAIN.resolve("com/housedevinci/einvoice/adapter/jdbc/JdbcIssuanceStore.java"));
    assertThat(store).contains("FROM einvoice_issuance_event WHERE seq > ? ORDER BY seq LIMIT ?");
    assertThat(store).contains("FROM einvoice_issuance_anchor");
    assertThat(store).doesNotContain("FROM einvoice_issuance_event WHERE seller_id = ?");
  }

  @Test
  void nothing_in_this_module_is_synchronized_on_a_jdbc_path() {
    // Virtual threads pin a carrier while they hold a monitor. The allocator's whole job is to
    // block on a row lock, so a synchronized block anywhere on that path would turn a burst of 200
    // virtual threads into 200 pinned carriers.
    List<String> offenders =
        sources().filter(path -> read(path).contains("synchronized")).map(Path::toString).toList();
    assertThat(offenders).isEmpty();
  }

  /**
   * Every SQL string this adapter builds, reassembled.
   *
   * <p>The statements are written as concatenated literals for line length, so a scan over raw
   * literals would see "INSERT INTO einvoice_series" on its own and report a missing predicate that
   * is on the next line. Each constant and each inline statement is joined back together first.
   */
  private static List<String> sqlConstantsOf(String source) {
    List<String> statements = new java.util.ArrayList<>();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile(
                "(?s)(?:=|prepareStatement\\()\\s*(\"(?:[^\"\\\\]|\\\\.)*\"(?:\\s*\\+\\s*\"(?:[^\"\\\\]|\\\\.)*\")*)")
            .matcher(withoutComments(source));
    while (matcher.find()) {
      StringBuilder joined = new StringBuilder();
      java.util.regex.Matcher literal =
          java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(matcher.group(1));
      while (literal.find()) {
        joined.append(literal.group(1));
      }
      String sql = joined.toString().strip();
      String upper = sql.toUpperCase(Locale.ROOT);
      if (upper.startsWith("SELECT ")
          || upper.startsWith("UPDATE ")
          || upper.startsWith("INSERT INTO ")
          || upper.startsWith("DELETE ")) {
        statements.add(sql);
      }
    }
    return statements;
  }

  private static String withoutComments(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
  }

  private static Stream<Path> sources() {
    try {
      return Files.walk(MAIN).filter(path -> path.toString().endsWith(".java"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String resource(String path) {
    try (InputStream in = SourceAssertionsTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
