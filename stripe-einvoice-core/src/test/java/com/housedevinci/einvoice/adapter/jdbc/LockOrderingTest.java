package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The lock invariant, restated for a module whose allocation can join a caller's transaction
 * (T-02b).
 *
 * <p>It used to read "no transaction holds both locks", which was true because every call opened
 * its own transaction. A caller that wraps an allocation and a disposition in one transaction now
 * holds both, and that is a correct program. What prevents a deadlock is the order, so that is what
 * is asserted and enforced: <b>nothing takes the chain lock before the series counter's row
 * lock.</b>
 */
class LockOrderingTest {

  @Test
  void nothing_takes_the_chain_lock_before_the_series_row_lock() {
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
    for (InterceptingDataSource.Recording one : recordings) {
      int series = indexOf(one.statements, sql -> sql.startsWith("UPDATE einvoice_series SET"));
      int chain = indexOf(one.statements, sql -> sql.contains("pg_advisory_xact_lock"));
      sawSeriesLock |= series >= 0;
      sawChainLock |= chain >= 0;
      if (series >= 0 && chain >= 0) {
        assertThat(series)
            .describedAs(
                "a transaction took the chain lock before the series row lock: %s", one.statements)
            .isLessThan(chain);
      }
    }
    // Both halves were actually exercised, so this is not a test that would pass against a module
    // that takes no locks at all.
    assertThat(sawSeriesLock).isTrue();
    assertThat(sawChainLock).isTrue();
  }

  @Test
  void a_transaction_holding_the_chain_lock_is_refused_the_series_row_lock() throws Exception {
    String seller = PostgresSupport.freshSeller("lockorder-refuse");
    PostgresSupport.store(seller, SIX, CLOCK, CHAIN).allocate(request(key(seller), "in_order"));
    try (Connection host = PostgresSupport.dataSource().getConnection()) {
      host.setAutoCommit(false);
      JdbcIssuanceStore store = storeOn(host, seller);
      store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_order", "order check", ""));
      assertThatThrownBy(() -> store.allocate(request(key(seller), "in_order_2")))
          .describedAs(
              "two transactions taking these locks in opposite orders deadlock, and a deadlocked"
                  + " allocation is a stalled invoice")
          .isInstanceOf(EInvoiceException.class)
          .extracting(e -> ((EInvoiceException) e).code())
          .isEqualTo(ErrorCodes.LOCK_ORDER_VIOLATION);
      host.rollback();
    }
  }

  @Test
  void one_transaction_may_hold_the_series_row_lock_and_then_the_chain_lock() throws Exception {
    String seller = PostgresSupport.freshSeller("lockorder-allow");
    try (Connection host = PostgresSupport.dataSource().getConnection()) {
      host.setAutoCommit(false);
      JdbcIssuanceStore store = storeOn(host, seller);
      store.allocate(request(key(seller), "in_allowed"));
      store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_allowed", "allowed order", ""));
      host.commit();
    }
    assertThat(
            PostgresSupport.store(seller, SIX, CLOCK, CHAIN)
                .findBySource(seller, Mode.LIVE, "in_allowed"))
        .describedAs("allocate then dispose in one caller transaction is a correct host program")
        .isPresent();
  }

  private static JdbcIssuanceStore storeOn(Connection host, String seller) {
    return new JdbcIssuanceStore(
        JdbcUnitOfWork.using(host),
        PostgresSupport.dataSource(),
        CHAIN,
        Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
        CLOCK,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT);
  }

  private static int indexOf(List<String> statements, java.util.function.Predicate<String> match) {
    for (int i = 0; i < statements.size(); i++) {
      if (match.test(statements.get(i))) {
        return i;
      }
    }
    return -1;
  }
}
