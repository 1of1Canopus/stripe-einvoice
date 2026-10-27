package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CHAIN;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.CLOCK;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.SIX;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.key;
import static com.housedevinci.einvoice.adapter.jdbc.NumberAllocatorTest.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Security review, pass 2 of PR 43 (the row lock token proof). */
class CipherProbePr43Test {

  private static JdbcIssuanceStore store(DataSource ds, String seller, JdbcUnitOfWork unitOfWork) {
    return TestStores.store(
        unitOfWork,
        ds,
        CHAIN,
        Map.of(new JdbcIssuanceStore.SeriesId(seller, "DEFAULT", Mode.LIVE), SIX),
        CLOCK,
        JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT);
  }

  /**
   * D-PR43-1. Joined mode: a held store write fails at the SQL level (here a lock timeout on the
   * issuance row) and the run handles it, as concludeIssuance does. The caller's transaction is now
   * aborted, so the UNLOCK cannot run. The run must keep its own outcome and must not close the
   * caller's connection under the caller's transaction manager.
   */
  @Test
  void probe_an_aborted_joined_transaction_keeps_its_outcome_and_the_callers_connection()
      throws Exception {
    DataSource ds = PostgresSupport.freshDatabase("abortjoin", 3);
    try {
      String seller = PostgresSupport.freshSeller("abortjoin");
      JdbcIssuanceStore own = store(ds, seller, JdbcUnitOfWork.ownConnection(ds));
      Issuance row = own.allocate(request(key(seller), "in_abortjoin"));
      try (Connection blocker = ds.getConnection();
          Connection caller = ds.getConnection()) {
        blocker.setAutoCommit(false);
        try (Statement st = blocker.createStatement()) {
          st.execute(
              "SELECT 1 FROM einvoice_issuance WHERE stripe_invoice_id = 'in_abortjoin' FOR UPDATE");
        }
        caller.setAutoCommit(false);
        try (Statement st = caller.createStatement()) {
          st.execute("SET LOCAL lock_timeout = '300ms'");
        }
        JdbcIssuanceStore joined = store(ds, seller, JdbcUnitOfWork.using(caller));
        AtomicReference<Object> outcome = new AtomicReference<>();
        try {
          Optional<String> handled =
              joined.whileHeld(
                  row.id(),
                  held -> {
                    try {
                      held.writer()
                          .markFailed(
                              seller, Mode.LIVE, "in_abortjoin", IssuanceState.FAILED_ARCHIVE, "");
                      return "written";
                    } catch (EInvoiceException e) {
                      return e.code();
                    }
                  });
          outcome.set(handled.orElse("absent"));
        } catch (EInvoiceException e) {
          outcome.set(e);
        }
        assertThat(outcome.get())
            .as("the run's own outcome, not a lost-lock refusal raised by the release")
            .isInstanceOf(String.class)
            .isNotEqualTo(ErrorCodes.SESSION_NOT_PINNED);
        assertThat(caller.isClosed() || !caller.isValid(2))
            .as("the caller's connection is still the caller's to roll back")
            .isFalse();
        caller.rollback();
        blocker.rollback();
      }
    } finally {
      PostgresSupport.close(ds);
    }
  }
}
