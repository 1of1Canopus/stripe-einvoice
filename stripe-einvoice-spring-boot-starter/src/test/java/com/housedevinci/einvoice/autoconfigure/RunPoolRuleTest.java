package com.housedevinci.einvoice.autoconfigure;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.RunPermits;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * The pool rule of the one-run-per-event design (section 6, D-ORPE-4, D-ORPE-7b and 7d): {@code 3 x
 * concurrency + 1 (module jobs) + 1 (host) <= maximum pool size}, refused at startup.
 */
class RunPoolRuleTest {

  /** Never started: the rule reads the configuration, not a connection. */
  private static HikariDataSource pool(int maximum) {
    HikariDataSource pool = new HikariDataSource();
    pool.setJdbcUrl("jdbc:postgresql://localhost:1/never-opened");
    pool.setMaximumPoolSize(maximum);
    return pool;
  }

  private static void startup(DataSource dataSource, int concurrency) {
    new EInvoiceIssuanceAutoConfiguration()
        .einvoiceRunPoolRule(dataSource, new RunPermits(concurrency))
        .afterSingletonsInstantiated();
  }

  /** T12: concurrency 3 needs 11 connections; Hikari's default pool of 10 is refused. */
  @Test
  void concurrency_three_on_a_pool_of_ten_refuses_the_startup() {
    try (HikariDataSource ten = pool(10)) {
      assertThatThrownBy(() -> startup(ten, 3))
          .isInstanceOf(EInvoiceException.class)
          .hasMessageContaining("einvoice.issuance.concurrency")
          .hasMessageContaining("spring.datasource.hikari.maximum-pool-size")
          .hasMessageContaining("= 11")
          .extracting("code")
          .isEqualTo(ErrorCodes.CONFIG);
    }
  }

  /** The default: concurrency 2 needs 8, within Hikari's default 10. */
  @Test
  void the_default_concurrency_passes_on_the_default_pool() {
    try (HikariDataSource ten = pool(10)) {
      assertThatCode(() -> startup(ten, 2)).doesNotThrowAnyException();
    }
  }

  /** T21 (D-ORPE-7b): a proxied Hikari pool is unwrapped and checked, not only WARNed. */
  @Test
  void a_proxied_hikari_pool_is_checked_and_refused() {
    try (HikariDataSource ten = pool(10)) {
      DataSource proxied = new DelegatingDataSource(new DelegatingDataSource(ten));

      assertThatThrownBy(() -> startup(proxied, 3))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.CONFIG);
    }
  }

  /** A pool that does not unwrap to Hikari cannot be checked: a WARN, never a silent pass. */
  @Test
  void a_pool_that_is_not_hikari_starts_with_a_warning() {
    DataSource opaque =
        (DataSource)
            java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "isWrapperFor" -> false;
                      case "hashCode" -> System.identityHashCode(proxy);
                      case "equals" -> proxy == args[0];
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    java.io.PrintStream err = System.err;
    java.io.PrintStream out = System.out;
    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    java.io.PrintStream both = new java.io.PrintStream(captured, true);
    System.setErr(both);
    System.setOut(both);
    try {
      startup(opaque, 64);
    } finally {
      System.setErr(err);
      System.setOut(out);
    }
    assertThat(captured.toString()).contains("WARN").contains("could not be checked");
  }

  /**
   * T23 (D-ORPE-7d): the reserve counts the module jobs as one connection because every job runs on
   * the one sweeper thread. A second scheduler, or a Spring {@code @Scheduled} job, breaks the
   * build here rather than the sum at run time.
   */
  @Test
  void every_module_job_is_scheduled_on_the_one_sweeper_executor() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.housedevinci.einvoice");

    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(IssuanceSweeper.class.getName())
        .should()
        .callMethodWhere(
            target(owner(assignableTo(Executors.class))).and(target(nameMatching("new.*Sched.*"))))
        .orShould()
        .callConstructorWhere(target(owner(assignableTo(ScheduledThreadPoolExecutor.class))))
        .check(classes);
    noMethods()
        .should()
        .beAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
        .check(classes);

    long schedulers =
        Arrays.stream(IssuanceSweeper.class.getDeclaredFields())
            .map(Field::getType)
            .filter(ScheduledExecutorService.class::isAssignableFrom)
            .count();
    assertThat(schedulers).isEqualTo(1);
  }
}
