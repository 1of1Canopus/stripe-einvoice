package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.MappingInput;
import com.housedevinci.einvoice.application.PreflightReport;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Probe 7. The preflight runs <b>before</b> the allocator's transaction and borrows nothing.
 *
 * <p>Under both transaction managers, because the JPA one is the common host and a module that
 * opened a connection under it would do so in exactly the deployment most applications run.
 *
 * <p>The measurement is taken inside the renderer, which is the only code running during the call:
 * whether a Spring transaction is active, and how many connections the module's own DataSource has
 * handed out between the entry and the exit of the preflight.
 */
class CipherProbePreflightTransactionTest {

  private static final AtomicInteger SELLER = new AtomicInteger();

  private static String seller() {
    return "preflighttx-" + SELLER.incrementAndGet();
  }

  private ApplicationContextRunner runner(String seller, Class<?> transactionManagerConfig) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(CountingPorts.class, transactionManagerConfig)
        .withPropertyValues(
            "einvoice.seller.id=" + seller,
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
            "einvoice.chain.hmac-key-id=k1",
            "einvoice.archive.type=filesystem",
            "einvoice.archive.root="
                + System.getProperty("java.io.tmpdir")
                + "/einvoice-preflighttx-test",
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "einvoice.issuance.enabled=false",
            "einvoice.reconcile.enabled=false");
  }

  @ParameterizedTest(name = "under a {0} transaction manager")
  @ValueSource(strings = {"datasource", "jpa"})
  void probe_preflight_borrows_no_connection_and_opens_no_transaction(String kind) {
    String seller = seller();
    runner(
            seller,
            "jpa".equals(kind)
                ? CipherProbeIssuanceTransactionTest.JpaTransactionManagerConfig.class
                : CipherProbeIssuanceTransactionTest.DataSourceTransactionManagerConfig.class)
        .run(
            context -> {
              RecordingRenderer renderer = context.getBean(RecordingRenderer.class);
              CountingDataSource dataSource = context.getBean(CountingDataSource.class);

              renderer.preflight(
                  new MappingInput(
                      invoice(),
                      new SeriesKey(seller, "DEFAULT", 2026, Mode.LIVE),
                      LocalDate.of(2026, 1, 16),
                      "test"));

              assertThat(renderer.transactionActive)
                  .describedAs("the preflight runs before the allocator opens its transaction")
                  .isFalse();
              assertThat(renderer.synchronizationActive)
                  .describedAs("and joins none of the host's either")
                  .isFalse();
              assertThat(dataSource.borrowed.get() - renderer.borrowedAtEntry)
                  .describedAs("and borrows no connection while it runs")
                  .isZero();
            });
  }

  private static SourceInvoice invoice() {
    return new SourceInvoice(
        "in_preflight_tx",
        "STRIPE-1",
        "",
        true,
        "eur",
        "paid",
        java.time.Instant.parse("2026-01-15T23:30:00Z"),
        new SourceInvoice.SourceParty(
            "Buyer Cooperative",
            "b@example.invalid",
            "1 Example Street",
            "",
            "75001",
            "Lyon",
            "FR",
            null),
        java.util.List.of(
            new SourceInvoice.SourceLine(
                "il_1", "One month of service", "txr_20", 1L, 10_000, 12_000)),
        java.util.List.of(
            new com.housedevinci.einvoice.domain.Totals.Bucket(
                "txr_20", com.housedevinci.einvoice.domain.Percentage.of("20"), false, 2_000)),
        java.util.List.of(
            new SourceInvoice.SourceTaxTreatment("txr_20", "FR", "vat", "standard_rated")),
        10_000,
        2_000,
        12_000,
        true);
  }

  /** Records what was true while the preflight ran. */
  static final class RecordingRenderer implements DocumentRenderer {

    private final CountingDataSource dataSource;
    volatile boolean transactionActive = true;
    volatile boolean synchronizationActive = true;
    volatile int borrowedAtEntry;

    RecordingRenderer(CountingDataSource dataSource) {
      this.dataSource = dataSource;
    }

    @Override
    public PreflightReport preflight(MappingInput input) {
      transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
      synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();
      borrowedAtEntry = dataSource.borrowed.get();
      return PreflightReport.passed();
    }

    @Override
    public RenderedDocument render(DocumentInput input) {
      return new RenderedDocument(
          "<Invoice/>".getBytes(java.nio.charset.StandardCharsets.UTF_8), "xml", "test");
    }
  }

  @Configuration
  static class CountingPorts {

    @Bean(destroyMethod = "")
    CountingDataSource dataSource() {
      return new CountingDataSource(TestPostgres.dataSource());
    }

    @Bean
    RecordingRenderer renderer(CountingDataSource dataSource) {
      return new RecordingRenderer(dataSource);
    }

    @Bean
    DocumentValidator validator() {
      return (bytes, input) -> DocumentValidator.Report.passed();
    }

    @Bean
    StripeInvoiceSource source() {
      return new com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp
          .RecordingStripeSource();
    }
  }

  /** Counts every connection the module is handed. */
  static final class CountingDataSource implements DataSource {

    private final DataSource delegate;
    final AtomicInteger borrowed = new AtomicInteger();

    CountingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
      borrowed.incrementAndGet();
      return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      borrowed.incrementAndGet();
      return delegate.getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }
  }
}
