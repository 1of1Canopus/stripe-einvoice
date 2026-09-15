package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.JdbcIssuanceStore;
import com.housedevinci.einvoice.adapter.jdbc.JdbcSupport;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D1-04 and its design review (T-01 … T-07): what an allocation does inside a host's transaction.
 *
 * <p>The core probe pins the port at the boundary with a caller-supplied connection; these are the
 * regression tests for the finding itself, because the finding is about a host that uses Spring
 * transactions and never sees a {@code Connection}.
 */
class CipherProbeTransactionParticipationTest {

  private static final AtomicInteger SELLER = new AtomicInteger();

  private static final String TEST_SECRET =
      java.util.Base64.getEncoder()
          .encodeToString(
              "einvoice-test-chain-secret-0002!".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  private static final Instant ISSUED_AT = Instant.parse("2026-03-01T09:00:00Z");

  private static String seller() {
    return "txhost-" + SELLER.incrementAndGet();
  }

  private ApplicationContextRunner runner(String seller, Class<?> dataSourceConfiguration) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EInvoiceAutoConfiguration.class))
        .withUserConfiguration(dataSourceConfiguration, HostConfig.class)
        .withPropertyValues(
            "einvoice.seller.id=" + seller,
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=" + TEST_SECRET,
            "einvoice.chain.hmac-key-id=k1");
  }

  // Probe 4 - a TransactionTemplate rollback, over a plain DataSource.
  @Test
  void an_allocation_in_a_transaction_template_is_rolled_back_with_it() {
    String seller = seller();
    runner(seller, PlainDataSourceConfig.class)
        .run(
            context -> {
              IssuanceNumberingService numbering = context.getBean(IssuanceNumberingService.class);
              TransactionTemplate template =
                  new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
              assertThatThrownBy(
                      () ->
                          template.executeWithoutResult(
                              status -> {
                                numbering.allocate("in_tt", "", "STRIPE-TT", ISSUED_AT);
                                throw new IllegalStateException("the host's own failure");
                              }))
                  .isInstanceOf(IllegalStateException.class);
              assertThat(numbering.find("in_tt"))
                  .describedAs("a rollback must never leave a consumed number behind")
                  .isEmpty();
              assertThat(nextNumber(seller)).isEqualTo(1L);
            });
  }

  // Probe 4 - the same, with the host's DataSource wrapped in a transaction-aware proxy.
  @Test
  void an_allocation_is_rolled_back_when_the_host_data_source_is_transaction_aware() {
    String seller = seller();
    runner(seller, TransactionAwareDataSourceConfig.class)
        .run(
            context -> {
              IssuanceNumberingService numbering = context.getBean(IssuanceNumberingService.class);
              TransactionTemplate template =
                  new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
              assertThatThrownBy(
                      () ->
                          template.executeWithoutResult(
                              status -> {
                                numbering.allocate("in_tap", "", "STRIPE-TAP", ISSUED_AT);
                                throw new IllegalStateException("the host's own failure");
                              }))
                  .isInstanceOf(IllegalStateException.class);
              assertThat(numbering.find("in_tap"))
                  .describedAs(
                      "a transaction-aware proxy must not be mistaken for a non-transactional"
                          + " DataSource, or the store commits inside the host's transaction")
                  .isEmpty();
              assertThat(nextNumber(seller)).isEqualTo(1L);
            });
  }

  // Probe 5 - @Transactional on a host service.
  @Test
  void an_allocation_in_a_host_transactional_method_is_rolled_back_with_it() {
    String seller = seller();
    runner(seller, PlainDataSourceConfig.class)
        .run(
            context -> {
              HostService host = context.getBean(HostService.class);
              IssuanceNumberingService numbering = context.getBean(IssuanceNumberingService.class);
              assertThatThrownBy(() -> host.allocateThenFail("in_tx"))
                  .isInstanceOf(IllegalStateException.class);
              assertThat(numbering.find("in_tx")).isEmpty();
              assertThat(nextNumber(seller)).isEqualTo(1L);
            });
  }

  // Probe 6 - the documented, per-call escape hatch.
  @Test
  void a_requires_new_allocation_survives_the_host_rollback_by_design() {
    String seller = seller();
    runner(seller, PlainDataSourceConfig.class)
        .run(
            context -> {
              HostOuterService host = context.getBean(HostOuterService.class);
              IssuanceNumberingService numbering = context.getBean(IssuanceNumberingService.class);
              assertThatThrownBy(() -> host.allocateInANewTransactionThenFail("in_rn"))
                  .isInstanceOf(IllegalStateException.class);
              assertThat(numbering.find("in_rn"))
                  .describedAs(
                      "REQUIRES_NEW suspends the host's transaction: the number is kept, on"
                          + " purpose, and this is the only supported way to ask for that")
                  .isPresent();
            });
  }

  // D1-11 - suspend/resume on the lock ledger's synchronization.
  @Test
  void probe_a_requires_new_allocation_does_not_inherit_the_outer_lock_ledger() {
    String seller = seller();
    runner(seller, PlainDataSourceConfig.class)
        .run(
            context -> {
              IssuanceNumberingService numbering = context.getBean(IssuanceNumberingService.class);
              HostOuterService host = context.getBean(HostOuterService.class);
              numbering.allocate("in_outer", "", "STRIPE-O", ISSUED_AT);

              assertThatCode(() -> host.voidThenAllocateInANewTransaction("in_outer", "in_inner"))
                  .describedAs(
                      "the outer transaction's chain lock must not be visible to a REQUIRES_NEW"
                          + " transaction, which holds neither lock")
                  .doesNotThrowAnyException();
              assertThat(numbering.find("in_inner")).isPresent();
            });
  }

  // Probe 7 - a read-only host transaction is a caller's annotation, not an outage.
  @Test
  void an_allocation_in_a_read_only_host_transaction_is_refused_by_name() {
    String seller = seller();
    runner(seller, PlainDataSourceConfig.class)
        .run(
            context -> {
              HostService host = context.getBean(HostService.class);
              assertThatThrownBy(() -> host.allocateReadOnly("in_ro"))
                  .isInstanceOf(EInvoiceException.class)
                  .extracting(e -> ((EInvoiceException) e).code())
                  .isEqualTo(ErrorCodes.HOST_TRANSACTION_READ_ONLY);
            });
  }

  // Probe 11 - a host that catches our typed refusal cannot commit what we half-wrote.
  @Test
  void a_host_that_swallows_our_refusal_after_we_wrote_cannot_commit() {
    String seller = seller();
    runner(seller, PlainDataSourceConfig.class)
        .run(
            context -> {
              HostService host = context.getBean(HostService.class);
              IssuanceNumberingService numbering = context.getBean(IssuanceNumberingService.class);
              numbering.allocate("in_swallow", "", "STRIPE-SW", ISSUED_AT);
              long counterBefore = nextNumber(seller);

              assertThatThrownBy(() -> host.voidThenAllocateAndSwallowTheRefusal("in_swallow"))
                  .describedAs(
                      "a counter with no issuance row appears in no series report line; the commit"
                          + " has to be refused rather than recorded")
                  .isInstanceOf(UnexpectedRollbackException.class);

              assertThat(nextNumber(seller)).isEqualTo(counterBefore);
              assertThat(numbering.find("in_swallow"))
                  .get()
                  .extracting(Issuance::state)
                  .describedAs("and the swallowed disposition was rolled back with it")
                  .isNotEqualTo(com.housedevinci.einvoice.domain.IssuanceState.VOID_UNUSED);
            });
  }

  // D1-12 - a refusal raised after a locking read, and before any write, must not poison the
  // host's own unrelated work in the same transaction.
  @Test
  void probe_a_pre_write_refusal_does_not_poison_the_host_transaction() {
    String seller = seller();
    runner(seller, PlainDataSourceConfig.class)
        .run(
            context -> {
              HostService host = context.getBean(HostService.class);
              assertThatCode(host::swallowANotFoundVoidThenCommit)
                  .describedAs(
                      "the void path only ran SELECT ... FOR UPDATE before refusing with"
                          + " ISSUANCE_NOT_FOUND; nothing was written, so the host's own commit"
                          + " must succeed")
                  .doesNotThrowAnyException();
            });
  }

  // Probe 12 - a host reads its own uncommitted allocation through our reader (T-01).
  @Test
  void a_host_transaction_reads_its_own_uncommitted_allocation() {
    String seller = seller();
    runner(seller, PlainDataSourceConfig.class)
        .run(
            context -> {
              HostService host = context.getBean(HostService.class);
              assertThat(host.allocateThenReadBack("in_readback"))
                  .describedAs(
                      "the reads run in the same unit of work as the writes, or a host that just"
                          + " allocated is told there is no number and allocates again")
                  .isPresent();
              assertThat(host.allocateThenCountItsOwnReportLines("in_readback_report"))
                  .isPositive();
            });
  }

  // Probe 13 - the startup checks stay on the DataSource and do not join (T-01, the other half).
  @Test
  void the_startup_checks_do_not_join_an_active_host_transaction() {
    String seller = seller();
    runner(seller, PlainDataSourceConfig.class)
        .run(
            context -> {
              HostService host = context.getBean(HostService.class);
              assertThatThrownBy(() -> host.runStartupChecksThenFail(seller))
                  .isInstanceOf(IllegalStateException.class);
              assertThat(
                      TestPostgres.count(
                          "SELECT count(*) FROM einvoice_series WHERE seller_id = '"
                              + seller
                              + "' AND fiscal_year = 2031"))
                  .describedAs(
                      "a startup check runs before any host transaction exists; joining one would"
                          + " make a failing host roll back the schema and series seeding")
                  .isEqualTo(1L);
            });
  }

  private static long nextNumber(String seller) {
    return TestPostgres.count(
        "SELECT coalesce((SELECT next_number FROM einvoice_series WHERE seller_id = '"
            + seller
            + "' AND series = 'DEFAULT' AND fiscal_year = 2026 AND mode = 'live'), 1)");
  }

  /** The host application: its own transactions, its own annotations. */
  public static class HostService {

    private final IssuanceNumberingService numbering;
    private final IssuanceVoidService voids;
    private final JdbcIssuanceStore store;
    private final DataSource dataSource;

    HostService(
        IssuanceNumberingService numbering,
        IssuanceVoidService voids,
        JdbcIssuanceStore store,
        DataSource dataSource) {
      this.numbering = numbering;
      this.voids = voids;
      this.store = store;
      this.dataSource = dataSource;
    }

    @Transactional
    public void allocateThenFail(String invoiceId) {
      numbering.allocate(invoiceId, "", "STRIPE-H", ISSUED_AT);
      throw new IllegalStateException("the host's own failure, after the number was allocated");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void allocateInItsOwnTransaction(String invoiceId) {
      numbering.allocate(invoiceId, "", "STRIPE-RN", ISSUED_AT);
    }

    @Transactional(readOnly = true)
    public void allocateReadOnly(String invoiceId) {
      numbering.allocate(invoiceId, "", "STRIPE-RO", ISSUED_AT);
    }

    @Transactional
    public Optional<Issuance> allocateThenReadBack(String invoiceId) {
      numbering.allocate(invoiceId, "", "STRIPE-RB", ISSUED_AT);
      return numbering.find(invoiceId);
    }

    @Transactional
    public int allocateThenCountItsOwnReportLines(String invoiceId) {
      numbering.allocate(invoiceId, "", "STRIPE-RP", ISSUED_AT);
      return numbering.report(ISSUED_AT).lines().size();
    }

    @Transactional
    public void voidThenAllocateAndSwallowTheRefusal(String existingInvoiceId) {
      voids.voidUnused(existingInvoiceId, "a disposition the host decided on", "OPS-11");
      try {
        numbering.allocate("in_after_refusal", "", "STRIPE-AR", ISSUED_AT);
      } catch (EInvoiceException refused) {
        // The host decides to carry on. It must not be able to commit what we half-wrote.
      }
    }

    @Transactional
    public void swallowANotFoundVoidThenCommit() {
      try {
        voids.voidUnused("in_absent_invoice", "pre-write refusal probe", "");
      } catch (EInvoiceException expected) {
        // A refusal the host is meant to catch: nothing was written, so this must not poison the
        // commit below.
      }
    }

    @Transactional
    public void runStartupChecksThenFail(String seller) {
      JdbcSupport.requirePostgreSql(dataSource);
      store.openSeries(new SeriesKey(seller, "DEFAULT", 2031, Mode.LIVE));
      throw new IllegalStateException("the host's own failure, after the startup checks ran");
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class HostConfig {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    HostService hostService(
        IssuanceNumberingService numbering,
        IssuanceVoidService voids,
        JdbcIssuanceStore store,
        DataSource dataSource) {
      return new HostService(numbering, voids, store, dataSource);
    }

    @Bean
    HostOuterService hostOuterService(HostService inner, IssuanceVoidService voids) {
      return new HostOuterService(inner, voids);
    }
  }

  /** A second bean, so REQUIRES_NEW is a real propagation boundary and not a self-invocation. */
  public static class HostOuterService {

    private final HostService inner;
    private final IssuanceVoidService voids;

    HostOuterService(HostService inner, IssuanceVoidService voids) {
      this.inner = inner;
      this.voids = voids;
    }

    @Transactional
    public void allocateInANewTransactionThenFail(String invoiceId) {
      inner.allocateInItsOwnTransaction(invoiceId);
      throw new IllegalStateException("the host's own failure, outside the inner transaction");
    }

    /** D1-11: takes the chain lock here, in the outer transaction, then a REQUIRES_NEW inner. */
    @Transactional
    public void voidThenAllocateInANewTransaction(String existingInvoiceId, String newInvoiceId) {
      voids.voidUnused(existingInvoiceId, "ledger probe", "");
      inner.allocateInItsOwnTransaction(newInvoiceId);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class PlainDataSourceConfig {

    // destroyMethod = "": the pool is shared by every context these probes start.
    @Bean(destroyMethod = "")
    DataSource dataSource() {
      return TestPostgres.dataSource();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class TransactionAwareDataSourceConfig {

    @Bean(destroyMethod = "")
    DataSource dataSource() {
      return new TransactionAwareDataSourceProxy(TestPostgres.dataSource());
    }
  }
}
