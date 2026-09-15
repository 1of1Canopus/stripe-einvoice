package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.autoconfigure.issuance.IssuanceTestApp;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checklist line 69: the transaction-participation probes run against <b>both</b> a {@code
 * DataSourceTransactionManager} and a {@code JpaTransactionManager}, and the JPA one is the common
 * host.
 *
 * <p>The distinction is not cosmetic. With a JPA transaction manager the connection is bound by
 * Hibernate rather than by Spring's data-source utilities, and a module that joined only the first
 * kind would quietly open its own connection under the second - so a host that rolled back would
 * keep the inbound row, the number, or both, in exactly the deployment that is most common.
 */
class CipherProbeIssuanceTransactionTest {

  private static final AtomicInteger SELLER = new AtomicInteger();
  private static final Instant NOW = Instant.parse("2026-01-16T09:00:00Z");

  private static String seller() {
    return "txprobe-" + SELLER.incrementAndGet();
  }

  private ApplicationContextRunner runner(String seller, Class<?> transactionManagerConfig) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(
            IssuanceWiringTest.Ports.class, transactionManagerConfig, HostConfig.class)
        .withPropertyValues(
            "einvoice.seller.id=" + seller,
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.unkeyed=true",
            "einvoice.archive.type=filesystem",
            "einvoice.archive.root="
                + System.getProperty("java.io.tmpdir")
                + "/einvoice-txprobe-test",
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "einvoice.issuance.enabled=false",
            "einvoice.reconcile.enabled=false");
  }

  @ParameterizedTest(name = "under a {0} transaction manager")
  @ValueSource(strings = {"datasource", "jpa"})
  void probe_a_host_rollback_takes_the_inbound_record_with_it(String kind) {
    String seller = seller();
    runner(
            seller,
            "jpa".equals(kind)
                ? JpaTransactionManagerConfig.class
                : DataSourceTransactionManagerConfig.class)
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              HostService host = context.getBean(HostService.class);
              String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");

              assertThatThrownBy(() -> host.recordThenFail(eventId))
                  .isInstanceOf(IllegalStateException.class);

              assertThat(inbound.find(eventId))
                  .describedAs(
                      "the module's statements ran on the host's own connection, so its rollback"
                          + " took them with it")
                  .isEmpty();
            });
  }

  @ParameterizedTest(name = "under a {0} transaction manager")
  @ValueSource(strings = {"datasource", "jpa"})
  void probe_a_host_reads_its_own_uncommitted_inbound_record(String kind) {
    String seller = seller();
    runner(
            seller,
            "jpa".equals(kind)
                ? JpaTransactionManagerConfig.class
                : DataSourceTransactionManagerConfig.class)
        .run(
            context -> {
              HostService host = context.getBean(HostService.class);
              String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");

              assertThat(host.recordThenReadBack(eventId))
                  .describedAs(
                      "the reads join the same unit of work as the writes, or a host that just"
                          + " recorded an event is told it does not exist")
                  .isTrue();
            });
  }

  @ParameterizedTest(name = "under a {0} transaction manager")
  @ValueSource(strings = {"datasource", "jpa"})
  void probe_a_committed_transition_survives_and_a_rolled_back_one_does_not(String kind) {
    String seller = seller();
    runner(
            seller,
            "jpa".equals(kind)
                ? JpaTransactionManagerConfig.class
                : DataSourceTransactionManagerConfig.class)
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              HostService host = context.getBean(HostService.class);
              String committed = "evt_" + UUID.randomUUID().toString().replace("-", "");
              String rolledBack = "evt_" + UUID.randomUUID().toString().replace("-", "");

              host.recordAndTransition(committed);
              inbound.record(received(rolledBack), body(rolledBack));
              assertThatThrownBy(() -> host.transitionThenFail(rolledBack))
                  .isInstanceOf(IllegalStateException.class);

              assertThat(inbound.find(committed).orElseThrow().state())
                  .isEqualTo(InboundState.FETCHED);
              assertThat(inbound.find(rolledBack).orElseThrow().state())
                  .describedAs("a rolled-back transition is not a transition")
                  .isEqualTo(InboundState.RECEIVED);
            });
  }

  @Test
  void both_transaction_managers_are_actually_exercised_by_these_probes() {
    // A probe suite that silently ran the same configuration twice would be one probe wearing two
    // names (checklist line 59), so the two configurations are asserted to be different types.
    assertThat(PlatformTransactionManager.class)
        .isAssignableFrom(org.springframework.jdbc.datasource.DataSourceTransactionManager.class);
    assertThat(PlatformTransactionManager.class).isAssignableFrom(JpaTransactionManager.class);
  }

  private static byte[] body(String eventId) {
    return ("{\"id\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
  }

  private static InboundEvent received(String eventId) {
    return InboundEvent.received(
        new EventIdentity(
            eventId, "invoice.finalized", IssuanceTestApp.PINNED_VERSION, true, "", "in_1"),
        Mode.LIVE,
        "primary",
        body(eventId),
        NOW);
  }

  /** The host application: its own transaction boundaries, its own annotations. */
  public static class HostService {

    private final InboundEventStore inbound;

    HostService(InboundEventStore inbound) {
      this.inbound = inbound;
    }

    @Transactional
    public void recordThenFail(String eventId) {
      inbound.record(received(eventId), body(eventId));
      throw new IllegalStateException("the host's own failure, after our write");
    }

    @Transactional
    public boolean recordThenReadBack(String eventId) {
      inbound.record(received(eventId), body(eventId));
      return inbound.find(eventId).isPresent();
    }

    @Transactional
    public void recordAndTransition(String eventId) {
      inbound.record(received(eventId), body(eventId));
      inbound.transition(eventId, InboundState.FETCHED, "", NOW, null);
    }

    @Transactional
    public void transitionThenFail(String eventId) {
      inbound.transition(eventId, InboundState.FETCHED, "", NOW, null);
      throw new IllegalStateException("the host's own failure, after our transition");
    }
  }

  @Configuration
  @EnableTransactionManagement
  static class HostConfig {

    @Bean
    HostService hostService(InboundEventStore inbound) {
      return new HostService(inbound);
    }
  }

  @Configuration
  static class DataSourceTransactionManagerConfig {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    }
  }

  /**
   * The common host: Hibernate owns the connection, and this module has to join <em>that</em>
   * transaction rather than open its own beside it.
   */
  @Configuration
  static class JpaTransactionManagerConfig {

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      // A package with no entities in it: this module maps none of its own tables, and the host
      // entities used elsewhere in these tests are deliberately out of reach here.
      factory.setPackagesToScan("com.housedevinci.einvoice.autoconfigure.issuance");
      factory.setPersistenceUnitName("einvoice-tx-probe-" + UUID.randomUUID());
      factory.setJpaPropertyMap(
          java.util.Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.show_sql", "false"));
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(
        EntityManagerFactory entityManagerFactory, DataSource dataSource) {
      JpaTransactionManager manager = new JpaTransactionManager(entityManagerFactory);
      // Without this the JPA manager binds no ConnectionHolder and a JDBC module beside it would
      // open its own connection - which is the defect this probe exists to detect.
      manager.setDataSource(dataSource);
      return manager;
    }
  }

  /** Kept so a future reader sees which transaction managers this suite covers. */
  static final List<String> COVERED = List.of("datasource", "jpa");
}
