package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Security review, PR 40 pass 1 (D-A1-C03 build). Each probe failed on the head it was written
 * against.
 */
class CipherProbePr40Test {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-20T10:00:00Z"), ZoneOffset.UTC);
  private static final Instant SALE = Instant.parse("2026-03-19T10:00:00Z");

  private static Map<JdbcIssuanceStore.SeriesId, SeriesDefinition> series(String seller) {
    return Map.of(
        new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE),
        new SeriesDefinition("I-{fiscalYear}-", 6, true),
        new JdbcIssuanceStore.SeriesId(seller, "GAMMA", Mode.LIVE),
        new SeriesDefinition("G-{fiscalYear}-", 6, true),
        new JdbcIssuanceStore.SeriesId(seller, "DELTA", Mode.LIVE),
        new SeriesDefinition("D-{fiscalYear}-", 6, true));
  }

  private static AllocationRequest invoice(String seller, String series, String stripeId) {
    return new AllocationRequest(
        new SeriesKey(seller, series, 2026, Mode.LIVE).scope(),
        stripeId,
        "",
        "S-" + stripeId,
        SALE,
        "r1");
  }

  private static JdbcIssuanceStore joined(Connection host, String seller) {
    return TestStores.store(
        JdbcUnitOfWork.using(host),
        PostgresSupport.dataSource(),
        DocumentSeriesFixture.CHAIN,
        series(seller),
        CLOCK,
        Duration.ofSeconds(10));
  }

  private static Connection serializable(DataSource ds) throws Exception {
    Connection c = ds.getConnection();
    c.setAutoCommit(false);
    c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    return c;
  }

  /**
   * D-PR40-1 (INFO). The series stage maps every SQLSTATE 40001 to DEI-117 with a message that says
   * this series "was created by a concurrent transaction". Under SERIALIZABLE a 40001 at the name
   * step also comes from a read/write dependency between two transactions that open two DIFFERENT
   * new series. Example: host B starts, host A opens GAMMA and commits, then host B opens DELTA and
   * is aborted at the name step. Nobody created DELTA, yet the message says somebody did. The code
   * (DEI-117, retryable) is right; the stated cause is not.
   */
  @Test
  void probe_a_serialization_failure_is_not_reported_as_a_concurrent_creation_of_this_series()
      throws Exception {
    String seller = PostgresSupport.freshSeller("pr40ssi");
    DataSource ds = PostgresSupport.dataSource();
    TestStores.store(ds, DocumentSeriesFixture.CHAIN, series(seller), CLOCK)
        .allocate(invoice(seller, "INV", "in_seed"));

    Throwable refused;
    try (Connection b = serializable(ds)) {
      // host B's snapshot is taken before host A commits; B has written nothing yet
      try (java.sql.Statement st = b.createStatement()) {
        st.execute("SELECT 1");
      }
      try (Connection a = serializable(ds)) {
        joined(a, seller).allocate(invoice(seller, "GAMMA", "in_g"));
        a.commit();
      }
      refused = catchThrowable(() -> joined(b, seller).allocate(invoice(seller, "DELTA", "in_d")));
      b.rollback();
    }

    assertThat(refused).as("host B is aborted by SSI").isInstanceOf(EInvoiceException.class);
    EInvoiceException e = (EInvoiceException) refused;
    assertThat(e.code()).isEqualTo(ErrorCodes.ALLOCATION_TIMEOUT);
    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM einvoice_series_name WHERE seller_id = '"
                    + seller
                    + "' AND series = 'DELTA'"))
        .as("no transaction ever created DELTA")
        .isEqualTo(0L);
    assertThat(e.getMessage())
        .as("the message must not assert a concurrent creation that did not happen")
        .doesNotContain("was created by a concurrent transaction");
  }
}
