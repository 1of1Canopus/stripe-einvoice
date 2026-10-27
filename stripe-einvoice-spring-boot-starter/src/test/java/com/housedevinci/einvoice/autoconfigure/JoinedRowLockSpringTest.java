package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.JdbcIssuanceStore;
import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D-PR43-1 (candidate B) under Spring: a held step that fails after the host's transaction wrote
 * leaves that transaction unable to commit. A caught exception and a commit get {@code
 * UnexpectedRollbackException} and commit nothing (B3); a host that marks rollback-only rolls back
 * (B4); a NESTED call keeps the outer transaction's own rows (B5).
 */
class JoinedRowLockSpringTest {

  private static final AtomicInteger SEQUENCE = new AtomicInteger();
  private static final String SHA = "d".repeat(64);

  private record Fixture(DataSource ds, JdbcIssuanceStore store, String seller, String invoice) {}

  private static Fixture fixture(String hint) {
    DataSource ds = TestPostgres.dataSource();
    String seller = "spring-joined-" + hint + "-" + SEQUENCE.incrementAndGet();
    String invoice = "in_spring_" + hint + "_" + SEQUENCE.get();
    JdbcIssuanceStore store =
        new JdbcIssuanceStore(
            new SpringManagedUnitOfWork(ds),
            ds,
            IssuanceChain.keyed(
                "einvoice-test-chain-secret-0001!".getBytes(StandardCharsets.UTF_8), "k1"),
            Map.of(
                new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE),
                new SeriesDefinition("INV-{fiscalYear}-", 6, true)),
            ZoneId.of("Europe/Paris"),
            Clock.systemUTC(),
            JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT,
            JdbcIssuanceStore.DEFAULT_MAX_CLOCK_REGRESSION);
    store.verifySessionPinning();
    TestPostgres.execute(
        "CREATE OR REPLACE FUNCTION test_refuse_issued_event_spring() RETURNS trigger AS $$ BEGIN"
            + " IF NEW.stripe_invoice_id LIKE 'in_spring_%' AND NEW.state = 'ISSUED' THEN RAISE"
            + " EXCEPTION 'test refusal'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
    TestPostgres.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname ="
            + " 'test_refuse_spring') THEN CREATE TRIGGER test_refuse_spring BEFORE INSERT ON"
            + " einvoice_issuance_event FOR EACH ROW EXECUTE FUNCTION"
            + " test_refuse_issued_event_spring(); END IF; END $$");
    return new Fixture(ds, store, seller, invoice);
  }

  private static Issuance allocate(Fixture f) {
    return f.store()
        .allocate(
            new AllocationRequest(
                new SeriesKey(f.seller(), "DEFAULT", 2026, Mode.LIVE).scope(),
                f.invoice(),
                "",
                "STRIPE-0001",
                Instant.now().minus(Duration.ofHours(1)),
                "fr-2026.1"));
  }

  /** Unit 1 writes ARCHIVING; unit 2 fails at the chained ISSUED event; the step is caught. */
  private static void halfWritten(Fixture f, Issuance row) {
    try {
      f.store()
          .whileHeld(
              row.id(),
              held -> {
                held.writer()
                    .markArchiving(
                        f.seller(),
                        Mode.LIVE,
                        f.invoice(),
                        SHA,
                        ArchiveKey.of(row.seriesKey(), row.legalNumber(), SHA, "xml"));
                try {
                  held.writer().markIssued(f.seller(), Mode.LIVE, f.invoice());
                } catch (EInvoiceException e) {
                  return e.code();
                }
                return "issued";
              });
    } catch (EInvoiceException caught) {
      // the host catches and carries on to its commit
    }
  }

  private static long rows(Fixture f) {
    return TestPostgres.count(
        "SELECT count(*) FROM einvoice_issuance WHERE stripe_invoice_id = '" + f.invoice() + "'");
  }

  @Test
  void a_caught_failure_and_a_commit_raise_unexpected_rollback_and_commit_nothing() {
    Fixture f = fixture("b3");
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(f.ds()));

    assertThatThrownBy(() -> tx.executeWithoutResult(status -> halfWritten(f, allocate(f))))
        .isInstanceOf(UnexpectedRollbackException.class);
    assertThat(rows(f)).isZero();
  }

  @Test
  void a_host_that_marks_rollback_only_rolls_back() {
    Fixture f = fixture("b4");
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(f.ds()));

    tx.executeWithoutResult(
        status -> {
          halfWritten(f, allocate(f));
          status.setRollbackOnly();
        });
    assertThat(rows(f)).isZero();
  }

  @Test
  void a_nested_call_keeps_the_outer_transactions_own_rows() {
    Fixture f = fixture("b5");
    TestPostgres.execute("CREATE TABLE IF NOT EXISTS test_outer_rows (tag text)");
    DataSourceTransactionManager manager = new DataSourceTransactionManager(f.ds());
    TransactionTemplate outer = new TransactionTemplate(manager);
    TransactionTemplate nested = new TransactionTemplate(manager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    String tag = f.invoice();

    outer.executeWithoutResult(
        status -> {
          new org.springframework.jdbc.core.JdbcTemplate(f.ds())
              .update("INSERT INTO test_outer_rows VALUES (?)", tag);
          try {
            nested.executeWithoutResult(inner -> halfWritten(f, allocate(f)));
          } catch (UnexpectedRollbackException expected) {
            // the nested call is rolled back to its savepoint; the outer goes on
          }
          new org.springframework.jdbc.core.JdbcTemplate(f.ds())
              .update("INSERT INTO test_outer_rows VALUES (?)", tag);
        });

    assertThat(rows(f)).as("our writes are gone with the nested savepoint").isZero();
    assertThat(TestPostgres.count("SELECT count(*) FROM test_outer_rows WHERE tag = '" + tag + "'"))
        .as("the outer transaction's own rows are kept")
        .isEqualTo(2L);
  }
}
