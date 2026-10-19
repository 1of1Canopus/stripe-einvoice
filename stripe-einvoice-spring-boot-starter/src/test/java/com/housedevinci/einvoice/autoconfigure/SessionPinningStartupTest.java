package com.housedevinci.einvoice.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.JdbcIssuanceStore;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * D-PR42-1: the starter runs the issuance row lock's session check once at startup, outside any
 * transaction, so a host transaction on a small pool never waits for it. A refused connection path
 * fails the startup; an unreachable database only WARNs.
 */
class SessionPinningStartupTest {

  private static JdbcIssuanceStore store(DataSource ds) {
    return new JdbcIssuanceStore(
        ds,
        IssuanceChain.keyed(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "k1"),
        Map.of(
            new JdbcIssuanceStore.SeriesId("seller-startup", "DEFAULT", Mode.LIVE),
            new SeriesDefinition("INV-{fiscalYear}-", 6, true)),
        ZoneId.of("Europe/Paris"),
        Clock.systemUTC());
  }

  private static void runAtStartup(JdbcIssuanceStore store) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("store", store));
    new EInvoiceIssuanceAutoConfiguration()
        .einvoiceSessionPinningCheck(beans.getBeanProvider(JdbcIssuanceStore.class))
        .afterSingletonsInstantiated();
  }

  /** A DataSource proxy: Object's own methods by identity, getConnection from {@code source}. */
  private interface ConnectionSource {
    Connection get() throws SQLException;
  }

  private static DataSource dataSource(ConnectionSource source) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  case "toString" -> "test data source";
                  case "getConnection" -> source.get();
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  /** One server session behind every "connection": what a transaction pooler looks like. */
  private static DataSource oneSessionForEveryone(DataSource real) throws SQLException {
    Connection shared = real.getConnection();
    Connection notClosing =
        (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) ->
                    "close".equals(method.getName()) ? null : method.invoke(shared, args));
    return dataSource(() -> notClosing);
  }

  @Test
  void a_pinned_pool_is_verified_at_startup() {
    JdbcIssuanceStore store = store(TestPostgres.dataSource());

    runAtStartup(store);

    assertThat(store.sessionPinningVerified()).isTrue();
  }

  @Test
  void a_pool_that_shares_one_session_fails_the_startup() throws Exception {
    JdbcIssuanceStore store = store(oneSessionForEveryone(TestPostgres.dataSource()));

    assertThatThrownBy(() -> runAtStartup(store))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.SESSION_NOT_PINNED);
  }

  @Test
  void an_unreachable_database_only_warns_and_leaves_the_check_for_later() {
    DataSource down =
        dataSource(
            () -> {
              throw new SQLException("connection refused", "08001");
            });
    JdbcIssuanceStore store = store(down);

    runAtStartup(store);

    assertThat(store.sessionPinningVerified()).isFalse();
  }
}
