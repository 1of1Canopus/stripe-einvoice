package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Probes over the starter: what it must never auto-configure, and what it must always say. */
class CipherProbeStarterTest {

  private static final AtomicInteger SELLER = new AtomicInteger();
  private static final String TEST_SECRET =
      java.util.Base64.getEncoder()
          .encodeToString(
              "einvoice-test-chain-secret-0002!".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  @Test
  void cipher_probe_the_module_auto_configures_no_http_endpoint_at_all() {
    // N-04. This module is a library and cannot authenticate anyone, so it ships no endpoint - and
    // above all no void endpoint, which would hand an unauthenticated caller a way to burn a
    // numbering series one number at a time. The host writes that endpoint itself, behind its own
    // authorization, and the docs say so in one line.
    List<String> webAnnotated =
        sources()
            .filter(
                path -> {
                  String text = read(path);
                  return text.contains("@RestController")
                      || text.contains("@Controller")
                      || text.contains("@RequestMapping")
                      || text.contains("@GetMapping")
                      || text.contains("@PostMapping")
                      || text.contains("@Endpoint")
                      || text.contains("@WebEndpoint");
                })
            .map(Path::toString)
            .toList();
    assertThat(webAnnotated).isEmpty();
  }

  @Test
  void cipher_probe_test_mode_warns_at_every_startup_and_not_once() {
    // A weaker mode that announces itself once, on the first use, is a weaker mode an operator
    // reading one boot log a month never sees.
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger startup = (Logger) LoggerFactory.getLogger(EInvoiceStartupCheck.class);
    startup.addAppender(appender);
    try {
      String seller = "probe-" + SELLER.incrementAndGet();
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EInvoiceAutoConfiguration.class))
          .withUserConfiguration(DataSourceConfig.class)
          .withPropertyValues(
              "einvoice.mode=test",
              "einvoice.seller.id=" + seller,
              "einvoice.seller.tax-zone=Europe/Paris",
              "einvoice.numbering.prefix=TEST-{fiscalYear}-",
              "einvoice.chain.hmac-secret=" + TEST_SECRET,
              "einvoice.chain.hmac-key-id=k1")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(
                        appender.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .map(ILoggingEvent::getFormattedMessage))
                    .anySatisfy(
                        message ->
                            assertThat(message)
                                .contains("einvoice.mode=test")
                                .contains("Nothing here is a legal invoice"));
              });
    } finally {
      startup.detachAppender(appender);
    }
  }

  @Test
  void cipher_probe_an_unkeyed_log_announces_itself_at_every_startup() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger startup = (Logger) LoggerFactory.getLogger(EInvoiceStartupCheck.class);
    startup.addAppender(appender);
    try {
      String seller = "probe-" + SELLER.incrementAndGet();
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EInvoiceAutoConfiguration.class))
          .withUserConfiguration(DataSourceConfig.class)
          .withPropertyValues(
              "einvoice.seller.id=" + seller,
              "einvoice.seller.tax-zone=Europe/Paris",
              "einvoice.numbering.prefix=INV-{fiscalYear}-",
              "einvoice.chain.unkeyed=true")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(
                        appender.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .map(ILoggingEvent::getFormattedMessage))
                    .anySatisfy(
                        message ->
                            assertThat(message)
                                .contains("einvoice.chain.unkeyed=true")
                                .contains("INTACT_UNKEYED"));
              });
    } finally {
      startup.detachAppender(appender);
    }
  }

  @Test
  void cipher_probe_no_log_line_in_this_module_carries_a_buyer_field() {
    // Checklist line 45. There is no buyer field in this pull request's model yet, and that is
    // exactly when to pin it: the mapper arrives next, and the first log line that prints a
    // customer name will be written by someone debugging at four in the afternoon.
    List<String> offenders =
        sources()
            .filter(
                path -> {
                  String text = read(path).toLowerCase(java.util.Locale.ROOT);
                  return text.contains("log.info(\"einvoice: customer")
                      || text.contains("customer_name")
                      || text.contains("customeremail")
                      || text.contains("buyername");
                })
            .map(Path::toString)
            .toList();
    assertThat(offenders).isEmpty();
  }

  private static Stream<Path> sources() {
    try {
      return Stream.concat(
              Files.walk(Path.of("src/main/java")),
              Files.walk(Path.of("../stripe-einvoice-core/src/main/java")))
          .filter(path -> path.toString().endsWith(".java"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class DataSourceConfig {
    @Bean(destroyMethod = "")
    DataSource dataSource() {
      return TestPostgres.dataSource();
    }
  }
}
