package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.adapter.jdbc.AbstractJdbcUnitOfWork;
import com.housedevinci.einvoice.adapter.jdbc.LockLedger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The unit of work that joins the host's transaction (D1-04).
 *
 * <p>When a Spring transaction is active on this {@code DataSource}, the allocation runs on that
 * transaction's connection and is committed - or rolled back - by the host, with no commit of our
 * own. When none is active, it behaves exactly as {@code JdbcUnitOfWork.ownConnection}: the direct
 * call outside a transaction stays a supported path and keeps its guarantee.
 *
 * <p>Propagation, stated rather than implied: {@code REQUIRED} and {@code MANDATORY} join; {@code
 * REQUIRES_NEW} suspends the host's transaction and we join the inner one, so the number survives
 * the host's rollback - the documented, per-call escape hatch; {@code NOT_SUPPORTED} and {@code
 * NEVER} fall to the no-transaction path. A transaction never crosses a thread boundary, so an
 * allocation dispatched to another thread is on the no-transaction path whatever the dispatcher was
 * in. JTA is out of scope.
 */
public final class SpringManagedUnitOfWork extends AbstractJdbcUnitOfWork {

  private static final Logger log = LoggerFactory.getLogger(SpringManagedUnitOfWork.class);

  /**
   * The key the lock ledger is bound under. Transaction-scoped state the framework already manages
   * (T-02a), not an ambient registry of ours: it exists only between a transaction's start and its
   * completion, and it is unbound by a synchronization on both outcomes.
   */
  private static final Object LEDGER_KEY = new Object();

  private final DataSource dataSource;

  public SpringManagedUnitOfWork(DataSource dataSource) {
    this.dataSource = unwrap(Objects.requireNonNull(dataSource, "dataSource"));
  }

  /**
   * A {@link TransactionAwareDataSourceProxy} hands out a transaction-aware connection proxy while
   * binding its holder under the <em>target</em> data source. Asking the utilities about the proxy
   * would answer "not transactional" for a connection that is, and we would then commit inside the
   * host's transaction. Unwrap once, here, rather than reason about it at every call.
   */
  private static DataSource unwrap(DataSource dataSource) {
    DataSource current = dataSource;
    while (current instanceof TransactionAwareDataSourceProxy proxy) {
      DataSource target = proxy.getTargetDataSource();
      if (target == null) {
        return current;
      }
      current = target;
    }
    return current;
  }

  @Override
  protected Connection acquire() {
    return DataSourceUtils.getConnection(dataSource);
  }

  @Override
  protected void release(Connection connection) {
    DataSourceUtils.releaseConnection(connection, dataSource);
  }

  @Override
  protected boolean joined(Connection connection) throws SQLException {
    return TransactionSynchronizationManager.isActualTransactionActive()
        && DataSourceUtils.isConnectionTransactional(connection, dataSource);
  }

  @Override
  protected LockLedger ledger(Connection connection) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return LockLedger.forOneTransaction();
    }
    LockLedger bound = (LockLedger) TransactionSynchronizationManager.getResource(LEDGER_KEY);
    if (bound != null) {
      return bound;
    }
    LockLedger ledger = LockLedger.forOneTransaction();
    TransactionSynchronizationManager.bindResource(LEDGER_KEY, ledger);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(LEDGER_KEY);
          }
        });
    return ledger;
  }

  /**
   * Refuses the host's commit after this unit of work has already written (T-03).
   *
   * <p>The holder is what the data-source transaction manager consults at commit, so this works for
   * a {@code TransactionTemplate} and for {@code @Transactional} alike; the aspect's status is the
   * fallback for a manager that binds no holder of ours. A host that catches our typed refusal and
   * commits anyway gets an {@code UnexpectedRollbackException} instead of a counter with no
   * issuance row.
   */
  @Override
  protected void markRollbackOnly(Connection connection) {
    Object resource = TransactionSynchronizationManager.getResource(dataSource);
    if (resource instanceof ConnectionHolder holder) {
      holder.setRollbackOnly();
      return;
    }
    try {
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    } catch (IllegalStateException noTransactionStatus) {
      log.error(
          "einvoice: this unit of work failed after writing inside a transaction it could not mark"
              + " rollback-only. Roll the transaction back: committing it would record a partial"
              + " allocation.",
          noTransactionStatus);
    }
  }

  @Override
  public Participation participation() {
    return Participation.JOIN;
  }
}
