package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** What the allocator does under concurrency, rollback, and a backend that dies mid-transaction. */
class ConcurrentAllocationTest {

  @Test
  void two_hundred_virtual_threads_on_one_series_allocate_a_dense_range() throws Exception {
    String seller = PostgresSupport.freshSeller("burst");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    ConcurrentLinkedQueue<Long> counters = new ConcurrentLinkedQueue<>();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(200);

    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 200; i++) {
        int n = i;
        threads.execute(
            () -> {
              try {
                start.await();
                counters.add(
                    store.allocate(request(key(seller), "in_burst_" + n)).legalNumber().counter());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(counters).hasSize(200);
    assertThat(counters.stream().sorted().toList())
        .isEqualTo(IntStream.rangeClosed(1, 200).mapToObj(Long::valueOf).toList());
  }

  @Test
  void fifty_virtual_threads_crossing_the_year_boundary_allocate_a_dense_range_in_both_years()
      throws Exception {
    // N-01's concurrent case: two fiscal years are opened by racing allocations, with no restart
    // and no startup seed. Both must start at 1 and neither may skip.
    String seller = PostgresSupport.freshSeller("boundary");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    Instant lastOfYear = Instant.parse("2026-12-31T23:59:30Z");
    Instant firstOfYear = Instant.parse("2027-01-01T00:00:30Z");
    ConcurrentLinkedQueue<String> allocated = new ConcurrentLinkedQueue<>();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(50);

    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 50; i++) {
        int n = i;
        boolean newYear = i % 2 == 0;
        threads.execute(
            () -> {
              try {
                start.await();
                SeriesKey key = new SeriesKey(seller, "DEFAULT", newYear ? 2027 : 2026, Mode.LIVE);
                Issuance issuance =
                    store.allocate(
                        new AllocationRequest(
                            key,
                            "in_boundary_" + n,
                            "",
                            "STRIPE",
                            newYear ? firstOfYear : lastOfYear,
                            "fr"));
                allocated.add(key.fiscalYear() + ":" + issuance.legalNumber().counter());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    }

    List<Long> of2026 =
        allocated.stream()
            .filter(a -> a.startsWith("2026:"))
            .map(a -> Long.parseLong(a.substring(5)))
            .sorted()
            .toList();
    List<Long> of2027 =
        allocated.stream()
            .filter(a -> a.startsWith("2027:"))
            .map(a -> Long.parseLong(a.substring(5)))
            .sorted()
            .toList();
    assertThat(of2026).isEqualTo(IntStream.rangeClosed(1, 25).mapToObj(Long::valueOf).toList());
    assertThat(of2027).isEqualTo(IntStream.rangeClosed(1, 25).mapToObj(Long::valueOf).toList());
  }

  @Test
  void two_concurrent_threads_for_one_stripe_invoice_produce_one_row() throws Exception {
    String seller = PostgresSupport.freshSeller("race");
    JdbcIssuanceStore store = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    ConcurrentLinkedQueue<String> numbers = new ConcurrentLinkedQueue<>();
    AtomicInteger claimed = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(8);

    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 8; i++) {
        threads.execute(
            () -> {
              try {
                start.await();
                numbers.add(store.allocate(request(key(seller), "in_one")).legalNumber().value());
              } catch (EInvoiceException e) {
                // The loser of the insert race: its counter increment rolled back with it, so the
                // series has no gap, and the caller re-reads the winner's number.
                assertThat(e.code()).isEqualTo(ErrorCodes.ISSUANCE_ALREADY_CLAIMED);
                claimed.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(numbers.stream().distinct().toList()).hasSize(1);
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_issuance WHERE seller_id = '" + seller + "'"))
        .isEqualTo(1L);
    // Whatever the interleaving, the counter advanced by exactly one for exactly one sale.
    assertThat(
            PostgresSupport.scalar(
                "SELECT next_number FROM einvoice_series WHERE seller_id = '" + seller + "'"))
        .isEqualTo(2L);
  }

  @Test
  void a_rolled_back_allocation_does_not_burn_a_number() {
    // The whole reason this module does not use a SEQUENCE: nextval is not rolled back, so every
    // failed attempt would leave a permanent hole in a legal series.
    String seller = PostgresSupport.freshSeller("rollback");
    DataSource failing =
        InterceptingDataSource.intercepting(
            PostgresSupport.dataSource(),
            (sql, c) -> {
              if (sql.startsWith("INSERT INTO einvoice_issuance ")) {
                throw new java.sql.SQLException("injected failure after the counter advanced");
              }
            });
    JdbcIssuanceStore store =
        new JdbcIssuanceStore(
            failing,
            CHAIN,
            Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
            CLOCK);

    try {
      store.allocate(request(key(seller), "in_rollback"));
    } catch (RuntimeException expected) {
      // the injected failure
    }

    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_series WHERE seller_id = '" + seller + "'"))
        .isZero();

    JdbcIssuanceStore healthy = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    assertThat(healthy.allocate(request(key(seller), "in_after")).legalNumber().counter())
        .isEqualTo(1L);
  }

  @Test
  void a_backend_killed_mid_transaction_does_not_burn_a_number() {
    // The crash table's second row: the counter advanced in an uncommitted transaction, and the
    // backend dies before the commit. PostgreSQL rolls it back; the number never existed.
    String seller = PostgresSupport.freshSeller("kill");
    JdbcIssuanceStore seed = PostgresSupport.store(seller, SIX, CLOCK, CHAIN);
    seed.allocate(request(key(seller), "in_before_kill"));

    DataSource killing =
        InterceptingDataSource.intercepting(
            PostgresSupport.dataSource(),
            (sql, c) -> {
              if (sql.startsWith("UPDATE einvoice_series SET next_number")) {
                terminate(pidOf(c));
              }
            });
    JdbcIssuanceStore store =
        new JdbcIssuanceStore(
            killing,
            CHAIN,
            Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
            CLOCK);

    try {
      store.allocate(request(key(seller), "in_killed"));
    } catch (RuntimeException expected) {
      // the connection is gone
    }

    assertThat(
            PostgresSupport.scalar(
                "SELECT next_number FROM einvoice_series WHERE seller_id = '" + seller + "'"))
        .isEqualTo(2L);
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_issuance WHERE seller_id = '"
                    + seller
                    + "' AND stripe_invoice_id = 'in_killed'"))
        .isZero();
  }

  private static int pidOf(Connection connection) {
    try (PreparedStatement ps = connection.prepareStatement("SELECT pg_backend_pid()");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void terminate(int pid) {
    try (Connection c = PostgresSupport.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT pg_terminate_backend(?)")) {
      ps.setInt(1, pid);
      ps.execute();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
