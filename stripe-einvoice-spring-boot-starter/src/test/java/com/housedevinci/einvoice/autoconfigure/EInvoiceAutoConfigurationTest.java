package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What the starter wires, what it refuses to wire, and what it says out loud while doing it. */
class EInvoiceAutoConfigurationTest {

  private static final AtomicInteger SELLER = new AtomicInteger();

  /**
   * Obviously synthetic, and computed rather than pasted: a base64 blob in a test file reads like a
   * real key to the next person who greps for one, and this module's secrets come from the
   * environment and nowhere else.
   */
  private static final String TEST_SECRET =
      java.util.Base64.getEncoder()
          .encodeToString(
              "einvoice-test-chain-secret-0001!".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  private ApplicationContextRunner runner(String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EInvoiceAutoConfiguration.class))
        .withUserConfiguration(DataSourceConfig.class)
        .withPropertyValues(properties);
  }

  private static String[] validConfiguration(String seller) {
    return new String[] {
      "einvoice.seller.id=" + seller,
      "einvoice.seller.tax-zone=Europe/Paris",
      "einvoice.numbering.prefix=INV-{fiscalYear}-",
      "einvoice.chain.hmac-secret=" + TEST_SECRET,
      "einvoice.chain.hmac-key-id=k1"
    };
  }

  private static String seller() {
    return "acme-" + SELLER.incrementAndGet();
  }

  @Test
  void a_configured_application_gets_an_allocator_a_voider_and_a_verifier() {
    String seller = seller();
    runner(validConfiguration(seller))
        .run(
            context -> {
              assertThat(context).hasSingleBean(IssuanceNumberingService.class);
              assertThat(context).hasSingleBean(IssuanceVoidService.class);
              assertThat(context).hasSingleBean(IssuanceChainVerifier.class);
              // The startup check opened this year's series row.
              assertThat(context.getBean(IssuanceNumberingService.class).mode())
                  .isEqualTo(Mode.LIVE);
            });
  }

  @Test
  void the_chain_secret_is_required_and_has_no_default() {
    // Without it, the issuance log can be recomputed by anyone who can write to it, which is the
    // opposite of what it is for. There is no quiet fallback to an unkeyed log.
    String seller = seller();
    runner(
            "einvoice.seller.id=" + seller,
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("einvoice.chain.hmac-secret");
            });
  }

  @Test
  void a_secret_beside_unkeyed_true_is_refused_rather_than_silently_preferred() {
    // A log is keyed from row 1 or unkeyed forever. A configuration that says both cannot be
    // resolved by picking one.
    String seller = seller();
    runner(
            "einvoice.seller.id=" + seller,
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.unkeyed=true",
            "einvoice.chain.hmac-secret=" + TEST_SECRET)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("keyed from row 1 or unkeyed forever");
            });
  }

  @Test
  void a_prefix_outside_the_declared_charset_refuses_startup_with_a_config_code() {
    String seller = seller();
    runner(
            "einvoice.seller.id=" + seller,
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=inv 2026/",
            "einvoice.chain.hmac-secret=" + TEST_SECRET,
            "einvoice.chain.hmac-key-id=k1")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .extracting("code")
                  .isEqualTo(ErrorCodes.CONFIG);
            });
  }

  @Test
  void an_unknown_tax_zone_is_refused_at_startup_rather_than_at_the_first_invoice() {
    // D-15: the zone is required and has no default, and an invoice finalised at 23:30 UTC belongs
    // to the day its tax authority says it does.
    String seller = seller();
    runner(
            "einvoice.seller.id=" + seller,
            "einvoice.seller.tax-zone=Mars/Olympus",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=" + TEST_SECRET,
            "einvoice.chain.hmac-key-id=k1")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void the_numbering_service_derives_the_fiscal_year_in_the_sellers_tax_zone() {
    // 31 December 23:30 UTC is already 1 January in Paris, and the invoice belongs to the new year
    // for that seller. Nothing here reads the JVM's default zone.
    String seller = seller();
    runner(validConfiguration(seller))
        .run(
            context -> {
              IssuanceNumberingService service = context.getBean(IssuanceNumberingService.class);
              Instant lateOnNewYearsEve = Instant.parse("2026-12-31T23:30:00Z");
              assertThat(service.seriesKeyFor(lateOnNewYearsEve).fiscalYear()).isEqualTo(2027);
              assertThat(service.seriesKeyFor(Instant.parse("2026-12-31T22:30:00Z")).fiscalYear())
                  .isEqualTo(2026);
            });
  }

  @Test
  void the_void_service_records_a_disposition_and_the_number_is_not_reused() {
    String seller = seller();
    runner(validConfiguration(seller))
        .run(
            context -> {
              IssuanceNumberingService numbering = context.getBean(IssuanceNumberingService.class);
              IssuanceVoidService voids = context.getBean(IssuanceVoidService.class);
              Instant issuedAt = Instant.parse("2026-03-01T09:00:00Z");
              Issuance first = numbering.allocate("in_auto_1", "", "STRIPE-1", issuedAt);

              Issuance voided = voids.voidUnused("in_auto_1", "wrong customer selected", "OPS-7");
              assertThat(voided.state()).isEqualTo(IssuanceState.VOID_UNUSED);
              assertThat(voided.legalNumber().value()).isEqualTo(first.legalNumber().value());

              Issuance second = numbering.allocate("in_auto_2", "", "STRIPE-2", issuedAt);
              assertThat(second.legalNumber().counter())
                  .isEqualTo(first.legalNumber().counter() + 1);

              assertThat(numbering.report(issuedAt).lines()).hasSize(2);
              assertThat(numbering.find("in_auto_1")).isPresent();
            });
  }

  @Test
  void a_host_supplied_clock_is_the_only_source_of_now() {
    String seller = seller();
    Instant fixed = Instant.parse("2026-06-01T12:00:00Z");
    runner(validConfiguration(seller))
        .withBean(Clock.class, () -> Clock.fixed(fixed, ZoneOffset.UTC))
        .run(
            context -> {
              Issuance issuance =
                  context
                      .getBean(IssuanceNumberingService.class)
                      .allocate("in_clock", "", "STRIPE-C", fixed);
              assertThat(issuance.allocatedAt()).isEqualTo(fixed);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class DataSourceConfig {
    // destroyMethod = "": the pool is shared by every context this class starts.
    @org.springframework.context.annotation.Bean(destroyMethod = "")
    DataSource dataSource() {
      return TestPostgres.dataSource();
    }

    @Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor noop() {
      return beanFactory -> {};
    }
  }
}
