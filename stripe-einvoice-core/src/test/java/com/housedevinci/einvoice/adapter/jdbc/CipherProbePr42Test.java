package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.Mode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Security review of PR 42 (fix/archive-retry-refusal), pass 1. Each probe fails on head 4a7bd08
 * and names its register row.
 */
class CipherProbePr42Test {

  private static JdbcIssuanceStore store(DataSource ds, String seller, JdbcUnitOfWork unitOfWork) {
    return TestStores.store(
        unitOfWork,
        ds,
        CHAIN,
        Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
        CLOCK,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT);
  }

  /**
   * D-PR42-1. A joined caller (a host transaction) on a pool of two. The README says two
   * connections are enough. The caller already holds one, and the first session check asks for two
   * more, so it waits for the pool and fails with DEI-102. The builder's joined test passes only
   * because it runs the check first on a free pool.
   */
  @Test
  void probe_a_joined_caller_on_a_pool_of_two_is_not_starved_by_the_session_check()
      throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("cjoined", 2);
    try {
      String seller = PostgresSupport.freshSeller("cjoined");
      JdbcIssuanceStore own = store(ds, seller, JdbcUnitOfWork.ownConnection(ds));
      Issuance row = own.allocate(request(key(seller), "in_cjoined"));
      try (Connection caller = ds.getConnection()) {
        caller.setAutoCommit(false);
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        Optional<String> ran;
        try {
          ran = joined.whileHeld(row.id(), held -> "ran");
        } catch (com.housedevinci.einvoice.domain.EInvoiceException e) {
          ran = Optional.of("refused " + e.code());
        }
        caller.commit();
        assertThat(ran).as("the joined holder ran under the lock").contains("ran");
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }

  /**
   * D-PR42-3. Advisory locks belong to one database. The TRY statement's pg_locks pre-check has no
   * database filter, so row 4242 held in database A blocks row 4242 in database B: every pair of
   * databases on one cluster contends on the same small row ids.
   */
  @Test
  void probe_a_row_lock_in_another_database_does_not_block_this_one() throws Exception {
    DataSource a = PostgresSupport.freshDatabase("cxdba", 2);
    DataSource b = PostgresSupport.freshDatabase("cxdbb", 2);
    try (RowLockHolder held = RowLockHolder.hold(a, 4242L);
        Connection c = b.getConnection()) {
      Optional<String> taken = IssuanceRowLocks.tryLock(c, 4242L);
      if (taken.isPresent()) {
        IssuanceRowLocks.unlock(c, 4242L, taken.get());
      }
      assertThat(taken).as("a lock in database A is not a holder in database B").isPresent();
    } finally {
      PostgresSupport.close(a);
      PostgresSupport.close(b);
    }
  }

  /**
   * D-PR42-4. A failed release (for example the backend was killed by a failover) logs DEI-104, the
   * code of a pooler refusal. An operator alert on DEI-104 then points at the pooler configuration
   * for a connection loss.
   */
  @Test
  void probe_an_eviction_is_not_logged_as_a_pooler_refusal() throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("cevict", 1);
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try (Connection c = ds.getConnection()) {
      assertThat(IssuanceRowLocks.tryLock(c, 78L)).isPresent();
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
      IssuanceRowLocks.evict(c, 78L);
    } finally {
      System.setErr(original);
      PostgresSupport.close(ds);
    }
    String log = captured.toString(StandardCharsets.UTF_8);
    assertThat(log).contains("issuance row 78");
    assertThat(log).doesNotContain(ErrorCodes.SESSION_NOT_PINNED);
  }

  /**
   * D-PR42-5. The README note on the empty mount point names neither the harm (a refused resume can
   * burn a number whose document is on the unmounted volume) nor a check an operator can copy.
   */
  @Test
  void probe_the_readme_names_the_mount_point_harm_and_a_concrete_check() throws Exception {
    Path readme = Path.of("..", "README.md");
    String text = Files.readString(readme, StandardCharsets.UTF_8);
    int at = text.indexOf("### The filesystem archive and its mount point");
    assertThat(at).isNotNegative();
    int end = text.indexOf("\n## ", at + 1);
    int next = text.indexOf("\n### ", at + 1);
    int stop = next > 0 && (end < 0 || next < end) ? next : (end < 0 ? text.length() : end);
    String section = text.substring(at, stop);
    assertThat(section).as("names the burn harm").containsIgnoringCase("burn");
    assertThat(section)
        .as("names a concrete mount check")
        .containsAnyOf("RequiresMountsFor", "mountpoint");
  }
}
