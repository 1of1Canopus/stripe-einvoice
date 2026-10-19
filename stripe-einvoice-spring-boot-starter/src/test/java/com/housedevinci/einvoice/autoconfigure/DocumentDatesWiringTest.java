package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.housedevinci.einvoice.application.DocumentDates;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D-PR36-6 through Spring: the starter builds the module's date reader inline. It is not a bean, so
 * a host validator bean cannot remove the date check and a host reader bean is never used.
 *
 * <p>{@link IssuanceWiringTest.Ports} brings a host renderer that writes {@code <Invoice/>} and a
 * host validator that answers PASSED without reading: before this change that event was archived.
 */
class DocumentDatesWiringTest {

  private static WebApplicationContextRunner runner(Class<?> configuration, String seller) {
    return new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(configuration)
        .withPropertyValues(
            "einvoice.seller.id=" + seller,
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
            "einvoice.chain.hmac-key-id=k1",
            "einvoice.archive.type=filesystem",
            "einvoice.archive.root=" + System.getProperty("java.io.tmpdir") + "/einvoice-" + seller,
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  }

  /** Records invoice.finalized for in_1 and drives it through the context's unit of work. */
  private static InboundEvent issue(AssertableWebApplicationContext context) {
    InboundEventStore inbound = context.getBean(InboundEventStore.class);
    StripeInvoiceSource source = context.getBean(StripeInvoiceSource.class);
    String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
    byte[] body = ("{\"id\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
    EventIdentity identity =
        new EventIdentity(
            eventId, "invoice.finalized", source.pinnedApiVersion(), true, "", "in_1");
    inbound.record(
        InboundEvent.received(identity, Mode.LIVE, "primary", body, Instant.now()), body);
    context.getBean(IssuanceUnitOfWork.class).process(eventId);
    return inbound.find(eventId).orElseThrow();
  }

  private static ListAppender<ILoggingEvent> listen() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger("com.housedevinci.einvoice")).addAppender(appender);
    return appender;
  }

  private static void stop(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger("com.housedevinci.einvoice")).detachAppender(appender);
  }

  @Test
  void a_host_validator_bean_does_not_remove_the_date_check() {
    runner(IssuanceWiringTest.Ports.class, "dates-hostval")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(DocumentDates.class)).isEmpty();

              InboundEvent event = issue(context);

              assertThat(event.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
              assertThat(event.lastCode()).isEqualTo(ErrorCodes.VALIDATION_NOT_EVALUATED);
              assertThat(context.getBean(InboundEventStore.class).lastRuleId(event.eventId()))
                  .contains("EINVOICE-DATES");
            });
  }

  @Test
  void the_date_reader_is_not_a_bean() {
    ListAppender<ILoggingEvent> appender = listen();
    try {
      runner(PortsWithHostReader.class, "dates-hostreader")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(DocumentDates.class)).hasSize(1);

                InboundEvent event = issue(context);

                // The host reader would read READ with a wrong date (VALIDATION_REFUSED). The
                // module reader reads <Invoice/> as UNREADABLE: NOT_EVALUATED proves which ran.
                assertThat(event.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
                assertThat(event.lastCode()).isEqualTo(ErrorCodes.VALIDATION_NOT_EVALUATED);
                assertThat(
                        appender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN)
                            .map(ILoggingEvent::getFormattedMessage))
                    .noneMatch(m -> m.contains("is not the module's own"));
              });
    } finally {
      stop(appender);
    }
  }

  @Configuration
  static class PortsWithHostReader extends IssuanceWiringTest.Ports {

    @Bean
    DocumentDates hostDates() {
      return bytes ->
          new DocumentDates.FoundDates(
              DocumentDates.Readability.READ,
              Optional.of("1999-01-01"),
              false,
              false,
              Optional.empty(),
              false,
              Optional.empty(),
              Optional.empty(),
              false);
    }
  }
}
