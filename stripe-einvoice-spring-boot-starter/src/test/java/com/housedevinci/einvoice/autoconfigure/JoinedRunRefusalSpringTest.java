package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.AdvisoryLocks;
import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.IssuanceReprocess;
import com.housedevinci.einvoice.application.ReprocessRequest;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D-PR49-1 in a started application: a reprocess called inside the host's transaction is refused
 * with {@code DEI-218} before the run lock is tried. It records no ledger row, leaves the event as
 * it was, and leaves no run lock on any session. The core probe ({@code CipherProbePr49Test})
 * covers the lock left behind by a failed joined run; this test covers the Spring transaction path.
 */
class JoinedRunRefusalSpringTest {

  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EInvoiceAutoConfiguration.class, EInvoiceIssuanceAutoConfiguration.class))
        .withUserConfiguration(IssuanceWiringTest.Ports.class, HostTransactions.class)
        .withPropertyValues(
            "einvoice.seller.id=pr49joined",
            "einvoice.seller.tax-zone=Europe/Paris",
            "einvoice.numbering.prefix=INV-{fiscalYear}-",
            "einvoice.chain.hmac-secret=ZWludm9pY2UtdGVzdC1jaGFpbi1zZWNyZXQtMDAwMSE=",
            "einvoice.chain.hmac-key-id=k1",
            "einvoice.archive.type=filesystem",
            "einvoice.archive.root="
                + System.getProperty("java.io.tmpdir")
                + "/einvoice-pr49-joined",
            "einvoice.stripe.webhook-secrets.primary=whsec_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  }

  /** A row a mapping refusal left behind: the one start a reprocess accepts. */
  private static String refusedEvent(InboundEventStore inbound) {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String eventId = "evt_pr49joined_" + suffix;
    Instant now = Instant.now();
    EventIdentity identity =
        new EventIdentity(eventId, "invoice.finalized", "", false, "", "in_pr49joined_" + suffix);
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    inbound.record(InboundEvent.received(identity, Mode.TEST, "primary", body, now), body);
    inbound.transition(eventId, InboundState.FETCHED, "", now, null);
    inbound.transition(
        eventId, InboundState.FAILED_MAPPING, ErrorCodes.SELLER_PROFILE_INCOMPLETE, now, null);
    return eventId;
  }

  private static long runLocks() {
    return TestPostgres.count(
        "SELECT count(*) FROM pg_catalog.pg_locks WHERE locktype = 'advisory' AND objsubid = 2"
            + " AND classid = "
            + (AdvisoryLocks.RUN_CLASS_ID & 0xFFFFFFFFL)
            + " AND database = (SELECT oid FROM pg_catalog.pg_database"
            + " WHERE datname = pg_catalog.current_database())");
  }

  @Test
  void a_reprocess_inside_a_host_transaction_is_refused_and_records_nothing() {
    runner()
        .run(
            context -> {
              InboundEventStore inbound = context.getBean(InboundEventStore.class);
              IssuanceReprocess reprocess = context.getBean(IssuanceReprocess.class);
              String eventId = refusedEvent(inbound);
              TransactionTemplate host =
                  new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

              assertThatThrownBy(
                      () ->
                          host.executeWithoutResult(
                              status ->
                                  reprocess.reprocess(
                                      new ReprocessRequest(eventId, "ops", "pr49 joined refusal"))))
                  .isInstanceOf(EInvoiceException.class)
                  .extracting("code")
                  .isEqualTo(ErrorCodes.RUN_IN_HOST_TRANSACTION);

              assertThat(
                      TestPostgres.count(
                          "SELECT count(*) FROM einvoice_reprocess_request WHERE event_id = '"
                              + eventId
                              + "'"))
                  .as("no ledger row for a refused reprocess")
                  .isZero();
              assertThat(inbound.find(eventId).orElseThrow().state())
                  .isEqualTo(InboundState.FAILED_MAPPING);
              assertThat(runLocks()).as("run locks left on any session").isZero();

              // Outside a transaction the same call is not refused for being joined.
              IssuanceReprocess.Result result =
                  reprocess.reprocess(new ReprocessRequest(eventId, "ops", "pr49 own connection"));
              assertThat(result.disposition()).isEqualTo(IssuanceReprocess.Disposition.REPROCESSED);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class HostTransactions {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }
}
