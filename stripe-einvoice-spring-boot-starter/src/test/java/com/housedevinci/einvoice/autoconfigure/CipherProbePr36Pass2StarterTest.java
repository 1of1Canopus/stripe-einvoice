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
 * Security review of the date-order change (D-PS1-27), pass 2. D-PR36-8: the two-clock tolerance
 * is a security bound (whoever controls only the database clock can move an issue date by at most
 * this much). Raising it from 5 minutes to 59 minutes is a weaker mode and must WARN at every
 * startup.
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
}
