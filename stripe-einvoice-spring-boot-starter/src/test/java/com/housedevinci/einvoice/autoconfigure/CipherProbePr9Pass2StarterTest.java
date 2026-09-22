package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pass 2, 2026-09-22 (P2-03). The new append-only table and the three starter guards that key off
 * {@link EInvoiceTables#ALL}. All three probes RED on 3efc0a0.
 *
 * <p>{@code EInvoiceTables} documents itself as "the tables this module owns, in one place, so no
 * scan can be right about a subset of them". A table added to the schema and not to that list is
 * outside the persistence-mapping guard, outside the database-view guard and outside the startup
 * warning that tells an operator the runtime role owns a table it should not - which is the one
 * warning R-02's claim depends on.
 */
class CipherProbePr9Pass2StarterTest {

  private static final String TABLE = "einvoice_reprocess_request";

  private AnnotationConfigApplicationContext context;

  @AfterEach
  void close() {
    if (context != null) {
      context.close();
    }
  }

  @Entity(name = "HostOverReprocessRecord")
  @Table(name = TABLE)
  static class OverReprocessRecord {
    @Id Long seq;
  }

  @Test
  void probe_the_reprocess_record_is_one_of_the_tables_this_module_guards() {
    assertThat(EInvoiceTables.ALL)
        .as("a module-owned table missing from the one list every guard reads")
        .contains(TABLE);
    assertThat(EInvoiceTables.isOurs(TABLE)).isTrue();
    assertThat(EInvoiceTables.isOurs("PUBLIC." + TABLE.toUpperCase(java.util.Locale.ROOT)))
        .isTrue();
  }

  @Test
  void probe_a_host_entity_mapped_over_the_reprocess_record_refuses_startup() {
    context = new AnnotationConfigApplicationContext();
    context.registerBean("hostEntityClass", Class.class, () -> OverReprocessRecord.class);
    context.register(GuardConfig.class, OneFactory.class);

    assertThatThrownBy(() -> context.refresh())
        .as(
            "a host entity over the privileged reprocess record can insert a CONCLUDED row for an"
                + " orphaned request and silence DEI-276; the guard must refuse to start")
        .rootCause()
        .hasMessageContaining(TABLE);
  }

  @Test
  void probe_a_database_view_over_the_reprocess_record_refuses_startup() {
    TestPostgres.execute("CREATE OR REPLACE VIEW v_host_reprocess AS SELECT * FROM " + TABLE);
    try {
      assertThatThrownBy(
              () -> DatabaseViewGuard.refuseViewsOverOurTables(TestPostgres.dataSource()))
          .as("the actor and reason of every privileged re-open, readable through a host view")
          .hasMessageContaining("v_host_reprocess");
    } finally {
      TestPostgres.execute("DROP VIEW v_host_reprocess");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class GuardConfig {
    @Bean
    static PersistenceMappingGuard guard(ConfigurableListableBeanFactory beanFactory) {
      return new PersistenceMappingGuard(beanFactory);
    }

    @Bean(destroyMethod = "")
    DataSource dataSource() {
      return TestPostgres.dataSource();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OneFactory {
    @Bean
    EntityManagerFactory entityManagerFactory(
        org.springframework.beans.factory.ObjectProvider<Class<?>> entity) {
      return PersistenceMappingGuardTest.factoryFor("host", entity.getObject());
    }
  }
}
