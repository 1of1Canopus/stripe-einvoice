package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.JdbcIssuanceStore;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Security review, pass 2 of PR 43: the startup session check on a plain pool. */
class CipherProbePr43StartupTest {

  /**
   * D-PR43-2. A plain pool of one connection can never give the check its second connection. Today
   * the check waits the pool's connection timeout while it holds the only connection, the startup
   * only WARNs that the check "runs before the first lock instead", and every issuance then repeats
   * that wait and ends DEI-102. The startup must refuse it as a configuration error instead.
   */
  @Test
  void probe_a_pool_of_one_fails_the_startup_as_a_configuration_error() {
    HikariDataSource base = (HikariDataSource) TestPostgres.dataSource();
    try (HikariDataSource one = new HikariDataSource()) {
      one.setJdbcUrl(base.getJdbcUrl());
      one.setUsername(base.getUsername());
      one.setPassword(base.getPassword());
      one.setMaximumPoolSize(1);
      one.setConnectionTimeout(1_000);
      JdbcIssuanceStore store =
          new JdbcIssuanceStore(
              one,
              IssuanceChain.keyed(
                  "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "k1"),
              Map.of(
                  new JdbcIssuanceStore.SeriesId("seller-pool-one", "DEFAULT", Mode.LIVE),
                  new SeriesDefinition("INV-{fiscalYear}-", 6, true)),
              ZoneId.of("Europe/Paris"),
              Clock.systemUTC());
      StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("store", store));
      assertThatThrownBy(
              () ->
                  new EInvoiceIssuanceAutoConfiguration()
                      .einvoiceSessionPinningCheck(beans.getBeanProvider(JdbcIssuanceStore.class))
                      .afterSingletonsInstantiated())
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isNotEqualTo(ErrorCodes.STORE_UNAVAILABLE);
    }
  }
}
