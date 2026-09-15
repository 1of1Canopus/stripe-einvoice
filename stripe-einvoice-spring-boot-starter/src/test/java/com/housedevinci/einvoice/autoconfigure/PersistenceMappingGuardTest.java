package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.domain.ErrorCodes;
import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * One test per way a JPA mapping can reach this module's tables.
 *
 * <p>The module ships no entity of its own, which is what removes the Hibernate read and write
 * paths rather than protecting them one by one. That argument is only as good as this guard, so
 * every path the mapping model offers gets its own case here - primary table, upper case,
 * schema-qualified, secondary table, collection table, subselect, a database view, a second
 * factory, and a lazily created one - and a clean application must still start.
 */
class PersistenceMappingGuardTest {

  private AnnotationConfigApplicationContext context;

  @AfterEach
  void close() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void a_host_entity_mapped_over_the_issuance_table_refuses_startup() {
    assertRefused(HostEntities.OverPrimaryTable.class, "einvoice_issuance");
  }

  @Test
  void a_quoted_uppercase_table_name_is_still_matched() {
    // EINVOICE_ISSUANCE, einvoice_issuance and public.einvoice_issuance are one table written
    // three ways. A scan that compares raw strings sees three tables and guards none of them.
    assertRefused(HostEntities.OverUpperCaseTable.class, "EINVOICE_ISSUANCE");
  }

  @Test
  void a_schema_qualified_table_name_is_still_matched() {
    assertRefused(HostEntities.OverQualifiedTable.class, "einvoice_issuance");
  }

  @Test
  void a_host_entity_mapped_over_a_secondary_table_refuses_startup() {
    assertRefused(HostEntities.OverSecondaryTable.class, "einvoice_series");
  }

  @Test
  void a_host_entity_with_a_collection_table_over_our_log_refuses_startup() {
    assertRefused(HostEntities.OverCollectionTable.class, "einvoice_issuance_event");
  }

  @Test
  void a_host_entity_mapped_by_subselect_over_our_table_refuses_startup() {
    // A @Subselect entity's mapped "table" is its SQL text, so the text is searched rather than
    // compared.
    assertRefused(HostEntities.OverSubselect.class, "einvoice_issuance");
  }

  @Test
  void a_clean_application_starts() {
    context = contextWith(HostEntities.Clean.class);
    assertThatCode(() -> context.refresh()).doesNotThrowAnyException();
    assertThat(context.getBean(EntityManagerFactory.class)).isNotNull();
  }

  @Test
  void a_second_entity_manager_factory_is_scanned_too() {
    // A guard wired to "the" factory is wired to one of them.
    context = new AnnotationConfigApplicationContext();
    context.register(GuardConfig.class, TwoFactories.class);
    assertRefusal(() -> context.refresh(), "einvoice_issuance");
  }

  @Test
  void a_lazily_created_entity_manager_factory_is_still_scanned() {
    // A scan that iterates the context once at startup misses a @Lazy factory. A factory nobody
    // looked at is not a factory that cannot reach our tables.
    context = new AnnotationConfigApplicationContext();
    context.register(GuardConfig.class, LazyFactory.class);
    assertRefusal(() -> context.refresh(), "einvoice_issuance");
  }

  @Test
  void a_database_view_over_our_tables_refuses_startup() {
    // The one path the metamodel cannot see: the host maps v_invoices, Hibernate is told about
    // v_invoices, the scan says clean, and the host reads our rows.
    TestPostgres.execute(
        "CREATE OR REPLACE VIEW v_host_invoices AS SELECT * FROM einvoice_issuance");
    try {
      assertThatThrownBy(
              () -> DatabaseViewGuard.refuseViewsOverOurTables(TestPostgres.dataSource()))
          .hasMessageContaining("v_host_invoices")
          .hasMessageContaining("einvoice_issuance");
    } finally {
      TestPostgres.execute("DROP VIEW v_host_invoices");
    }
    assertThatCode(() -> DatabaseViewGuard.refuseViewsOverOurTables(TestPostgres.dataSource()))
        .doesNotThrowAnyException();
  }

  private void assertRefused(Class<?> entity, String table) {
    context = contextWith(entity);
    assertRefusal(() -> context.refresh(), table);
  }

  private static void assertRefusal(Runnable refresh, String table) {
    assertThatThrownBy(refresh::run)
        .rootCause()
        .hasMessageContaining(table)
        .hasMessageContaining("append-only legal ledger");
  }

  private static AnnotationConfigApplicationContext contextWith(Class<?> entity) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean("hostEntityClass", Class.class, () -> entity);
    context.register(GuardConfig.class, OneFactory.class);
    return context;
  }

  static EntityManagerFactory factoryFor(String unit, Class<?>... managed) {
    LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
    bean.setPersistenceUnitName(unit);
    bean.setDataSource(TestPostgres.dataSource());
    bean.setManagedTypes(
        org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of(
            java.util.Arrays.stream(managed).map(Class::getName).toArray(String[]::new)));
    JpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
    bean.setJpaVendorAdapter(adapter);
    Map<String, Object> properties = new HashMap<>();
    // The host's own schema is not this module's business; nothing here creates a table.
    properties.put("hibernate.hbm2ddl.auto", "none");
    bean.setJpaPropertyMap(properties);
    bean.afterPropertiesSet();
    return bean.getObject();
  }

  @Configuration(proxyBeanMethods = false)
  static class GuardConfig {
    @Bean
    static PersistenceMappingGuard guard(ConfigurableListableBeanFactory beanFactory) {
      return new PersistenceMappingGuard(beanFactory);
    }

    // destroyMethod = "": the pool is shared by every test in this class, and Spring would
    // otherwise close it when the first context shuts down.
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
      return factoryFor("host", entity.getObject());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class TwoFactories {
    @Bean
    EntityManagerFactory cleanFactory() {
      return factoryFor("clean", HostEntities.Clean.class);
    }

    @Bean
    EntityManagerFactory secondFactory() {
      return factoryFor("second", HostEntities.OverPrimaryTable.class);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class LazyFactory {
    @Bean
    @Lazy
    EntityManagerFactory lazyFactory() {
      return factoryFor("lazy", HostEntities.OverPrimaryTable.class);
    }
  }

  @Test
  void the_refusal_carries_this_modules_own_error_code() {
    context = contextWith(HostEntities.OverPrimaryTable.class);
    assertThatThrownBy(() -> context.refresh())
        .rootCause()
        .isInstanceOf(com.housedevinci.einvoice.domain.EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.PERSISTENCE_MAPPING_REFUSED);
  }
}
