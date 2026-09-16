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
  void cipher_probe_the_only_endpoint_this_module_maps_is_the_signed_webhook() {
    // N-04, restated for the endpoint this pull request adds. The module is a library and cannot
    // authenticate anyone, so it maps exactly one HTTP endpoint: the Stripe webhook, whose
    // authentication is an HMAC over the exact bytes it received. Everything privileged - voiding
    // a number, acknowledging a finding - stays a service method the host exposes behind its own
    // authorization, because an auto-configured one would hand an unauthenticated caller a way to
    // burn a series or to silence every alert.
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
                      || text.contains("@DeleteMapping")
                      || text.contains("@PutMapping");
                })
            .map(path -> path.getFileName().toString())
            .toList();
    assertThat(webAnnotated).containsExactly("StripeWebhookController.java");

    String controller =
        read(
            Path.of(
                "src/main/java/com/housedevinci/einvoice/autoconfigure/StripeWebhookController.java"));
    assertThat(controller).contains("@PostMapping").doesNotContain("@GetMapping");
    assertThat(controller.split("@PostMapping", -1).length - 1).isEqualTo(1);

    // Actuator endpoints are a surface the host already protects, and the one this module adds is
    // a read. A write operation there would be the void endpoint by another name.
    List<String> actuatorEndpoints =
        sources()
            .filter(path -> read(path).contains("@Endpoint") || read(path).contains("@WebEndpoint"))
            .map(path -> path.getFileName().toString())
            .toList();
    assertThat(actuatorEndpoints).containsExactly("IssuanceFindingsEndpoint.java");
    String endpoint =
        read(
            Path.of(
                "src/main/java/com/housedevinci/einvoice/autoconfigure/IssuanceFindingsEndpoint.java"));
    assertThat(endpoint)
        .contains("@ReadOperation")
        .doesNotContain("@WriteOperation")
        .doesNotContain("@DeleteOperation");
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
    // Checklist line 45. A message built from constants and field *names* is what this module is
    // supposed to produce - "stripe field: customer_name" tells an operator what to fix and quotes
    // nobody. What is refused is a buyer's *value* reaching a log line or a message: the accessors
    // below interpolated into either.
    java.util.regex.Pattern valueInAMessage =
        java.util.regex.Pattern.compile(
            "(?s)(log\\.[a-z]+\\(|EInvoiceException\\(|\"\\s*\\+\\s*)[^;]{0,400}?"
                + "(getCustomerName|getCustomerEmail|getCustomerAddress|\\bbuyer\\(\\)|"
                + "customerName\\(\\)|customerEmail\\(\\)|\\.description\\(\\))");
    List<String> offenders =
        sources()
            .filter(
                path -> {
                  String text = read(path);
                  // The mapper reads those accessors; what it may not do is put their result in a
                  // message. Lines that only *call* them are fine, so the match has to see the
                  // accessor inside a log or exception argument.
                  return valueInAMessage.matcher(withoutComments(text)).find();
                })
            .map(Path::toString)
            .toList();
    assertThat(offenders).isEmpty();
  }

  /** Comments explain at length which Stripe fields exist; that prose is not a log line. */
  private static String withoutComments(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
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
