package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Security review of the date-order change (D-PS1-27), pass 2. D-PR36-8: the two-clock tolerance is
 * a security bound (whoever controls only the database clock can move an issue date by at most this
 * much). Raising it from 5 minutes to 59 minutes is a weaker mode and must WARN at every startup.
 */
class CipherProbePr36Pass2StarterTest {

  @Test
  void probe_a_widened_clock_tolerance_starts_without_a_warning() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger module = (Logger) LoggerFactory.getLogger("com.housedevinci.einvoice");
    module.addAppender(appender);
    try {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EInvoiceAutoConfiguration.class))
          .withUserConfiguration(CipherProbeStarterTest.DataSourceConfig.class)
          .withPropertyValues(
              "einvoice.seller.id=probe-p36tol",
              "einvoice.seller.tax-zone=Europe/Paris",
              "einvoice.numbering.prefix=INV-{fiscalYear}-",
              "einvoice.chain.unkeyed=true",
              "einvoice.numbering.max-clock-regression=59m")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(
                        appender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN)
                            .map(ILoggingEvent::getFormattedMessage))
                    .anySatisfy(m -> assertThat(m).contains("max-clock-regression"));
              });
    } finally {
      module.detachAppender(appender);
    }
  }

  /**
   * Design review R1 (P17). The starter's unit of work is {@code @ConditionalOnMissingBean}, so a
   * host can declare its own and hand it any date reader. That is a weaker mode and must WARN at
   * every startup, naming the reader.
   */
  @Test
  void probe_a_host_unit_of_work_bean_with_its_own_date_reader_starts_without_a_warning() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger module = (Logger) LoggerFactory.getLogger("com.housedevinci.einvoice");
    module.addAppender(appender);
    try {
      new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
          .withUserConfiguration(HostReader.class)
          .withPropertyValues(
              "einvoice.seller.id=probe-p36r1",
              "einvoice.seller.tax-zone=Europe/Paris",
              "einvoice.numbering.prefix=INV-{fiscalYear}-",
              "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
              "einvoice.chain.hmac-key-id=k1",
              "einvoice.archive.type=filesystem",
              "einvoice.archive.root=" + System.getProperty("java.io.tmpdir") + "/einvoice-p36r1",
              "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(
                        context.getBean(
                            com.housedevinci.einvoice.application.IssuanceUnitOfWork.class))
                    .isSameAs(context.getBean("hostUnitOfWork"));
                assertThat(
                        appender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN)
                            .map(ILoggingEvent::getFormattedMessage))
                    .anySatisfy(
                        m -> assertThat(m).contains("EINVOICE-DATES").contains("HostReader"));
              });
    } finally {
      module.detachAppender(appender);
    }
  }

  @org.springframework.context.annotation.Configuration
  static class HostReader extends IssuanceWiringTest.Ports {

    @org.springframework.context.annotation.Bean
    com.housedevinci.einvoice.application.IssuanceUnitOfWork hostUnitOfWork(
        com.housedevinci.einvoice.application.InboundEventStore inbound,
        com.housedevinci.einvoice.application.StripeInvoiceSource source,
        com.housedevinci.einvoice.adapter.jdbc.JdbcIssuanceStore store,
        com.housedevinci.einvoice.application.DocumentRenderer renderer,
        com.housedevinci.einvoice.application.DocumentValidator validator,
        com.housedevinci.einvoice.application.ArchiveStore archive,
        com.housedevinci.einvoice.application.PreflightSupport preflightSupport,
        java.time.Clock clock,
        EInvoiceProperties properties) {
      com.housedevinci.einvoice.application.DocumentDates lenient =
          bytes ->
              new com.housedevinci.einvoice.application.DocumentDates.FoundDates(
                  com.housedevinci.einvoice.application.DocumentDates.Readability.READ,
                  java.util.Optional.of("2026-01-10"),
                  false,
                  false,
                  java.util.Optional.empty(),
                  false,
                  java.util.Optional.empty(),
                  java.util.Optional.empty(),
                  false);
      return new com.housedevinci.einvoice.application.IssuanceUnitOfWork(
          inbound,
          source,
          store,
          store,
          store,
          store,
          renderer,
          validator,
          lenient,
          archive,
          preflightSupport,
          clock,
          EInvoiceIssuanceAutoConfiguration.configuration(properties, source));
    }
  }
}
