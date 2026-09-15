package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.sql.Connection;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Cipher pass-2 probes (D1-13). Each one FAILS without the fix. */
class CipherProbePass2Test {

  // D1-13
  @Test
  void probe_a_caller_owned_unit_that_failed_after_writing_refuses_further_work() throws Exception {
    String seller = PostgresSupport.freshSeller("poisoned");
    try (Connection host = PostgresSupport.dataSource().getConnection()) {
      host.setAutoCommit(false);
      JdbcIssuanceStore store = storeOn(host, seller);
      store.allocate(request(key(seller), "in_poison"));

      // Drives the unit into a post-write refusal: the void takes the chain lock (a write), and
      // the next allocation asks for the series row lock out of order - DEI-120, raised after
      // this unit of work had already written.
      store.voidUnused(new VoidRequest(seller, Mode.LIVE, "in_poison", "poison the unit", ""));
      assertThatThrownBy(() -> store.allocate(request(key(seller), "in_poison_2")))
          .isInstanceOf(EInvoiceException.class)
          .extracting(e -> ((EInvoiceException) e).code())
          .isEqualTo(ErrorCodes.LOCK_ORDER_VIOLATION);

      // The unit refuses to run anything else at all now, with its own code - not the lock-order
      // code again, and not a silent retry - because it cannot roll back a connection it does not
      // own.
      assertThatThrownBy(() -> store.allocate(request(key(seller), "in_poison_3")))
          .describedAs(
              "a caller-owned unit that already failed after writing must refuse every further"
                  + " call, rather than let the host commit a half-written unit of work")
          .isInstanceOf(EInvoiceException.class)
          .extracting(e -> ((EInvoiceException) e).code())
          .isEqualTo(ErrorCodes.UNIT_OF_WORK_POISONED);

      // The connection is still the caller's: not closed, not committed, and rollback-able.
      assertThat(host.isClosed()).isFalse();
      assertThatCode(host::rollback)
          .describedAs("the poisoned unit must never touch the caller's own connection lifecycle")
          .doesNotThrowAnyException();
    }
  }

  private static JdbcIssuanceStore storeOn(Connection host, String seller) {
    return new JdbcIssuanceStore(
        JdbcUnitOfWork.using(host),
        PostgresSupport.dataSource(),
        CHAIN,
        Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
        CLOCK,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT);
  }
}
