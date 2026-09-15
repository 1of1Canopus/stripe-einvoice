package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * N-07. The invariant is not "the locks are taken in a safe order" - it is that <b>no transaction
 * holds both</b>.
 *
 * <p>An ordering that nothing exercises is a claim that is true by accident, and a claim that is
 * true by accident stops being true silently. This asserts the real thing, over the adapter's own
 * transaction boundaries: every transaction this module opens either takes the series counter's row
 * lock or takes the chain's advisory lock, never both.
 */
class LockOrderingTest {

  @Test
  void no_transaction_acquires_both_the_series_lock_and_the_chain_lock() {
    String seller = PostgresSupport.freshSeller("locks");
    List<InterceptingDataSource.Recording> recordings = new ArrayList<>();
    DataSource recording =
        InterceptingDataSource.recording(PostgresSupport.dataSource(), recordings);
    JdbcIssuanceStore store =
        new JdbcIssuanceStore(
            recording,
            CHAIN,
            Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
            CLOCK);

    store.allocate(request(key(seller), "in_locks"));
    store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_locks", "lock ordering check", ""));
    store.seriesReport(key(seller));

    assertThat(recordings).isNotEmpty();
    boolean sawSeriesLock = false;
    boolean sawChainLock = false;
    for (InterceptingDataSource.Recording recording1 : recordings) {
      boolean series =
          recording1.statements.stream()
              .anyMatch(sql -> sql.startsWith("UPDATE einvoice_series SET next_number"));
      boolean chain =
          recording1.statements.stream().anyMatch(sql -> sql.contains("pg_advisory_xact_lock"));
      sawSeriesLock |= series;
      sawChainLock |= chain;
      assertThat(series && chain)
          .describedAs(
              "a transaction took both the series row lock and the chain lock: %s",
              recording1.statements)
          .isFalse();
    }
    // Both halves of the invariant were actually exercised, so this is not a test that would pass
    // against a module that takes no locks at all.
    assertThat(sawSeriesLock).isTrue();
    assertThat(sawChainLock).isTrue();
  }
}
