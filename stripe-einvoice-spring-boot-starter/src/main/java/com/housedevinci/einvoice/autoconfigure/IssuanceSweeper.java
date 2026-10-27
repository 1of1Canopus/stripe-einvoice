package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.ReconciliationSweep;
import com.housedevinci.einvoice.application.StrandedInboundSweep;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.RunningLimit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * The three background jobs of the free core: re-pick what is due, reconcile, and purge.
 *
 * <p>It runs on its own single-threaded scheduler rather than on Spring's {@code @Scheduled}
 * infrastructure, deliberately: a library that needed the host to remember
 * {@code @EnableScheduling} would have a control that is off in exactly the applications whose
 * operator did not read the documentation.
 *
 * <p>The scheduler only ever <em>submits</em> to the worker, so the bound on concurrent issuance is
 * the worker's and there is one of them.
 */
public final class IssuanceSweeper
    implements InitializingBean, DisposableBean, ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(IssuanceSweeper.class);

  private final IssuanceUnitOfWork unitOfWork;
  private final InboundEventStore inbound;
  private final IssuanceWorker worker;
  private final ReconciliationSweep reconciliation;
  private final IssuanceChainVerifier verifier;
  private final StrandedInboundSweep stranded;
  private final Clock clock;
  private final EInvoiceProperties properties;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "einvoice-sweeper");
            thread.setDaemon(true);
            return thread;
          });

  private final AtomicReference<Instant> lastSweep = new AtomicReference<>();
  private final AtomicReference<Instant> lastReconciliation = new AtomicReference<>();
  private final AtomicReference<ReconciliationSweep.Result> lastResult = new AtomicReference<>();
  private final AtomicReference<VerifiedChain> lastChainCheck = new AtomicReference<>();
  private final AtomicReference<Integer> lastExpired = new AtomicReference<>();

  /** Above these the limits WARN at every start: they are allowed, and they are weaker. */
  static final Duration RUNNING_CEILING_WARN_ABOVE = Duration.ofDays(7);

  static final Duration PARKED_CEILING_WARN_ABOVE = Duration.ofDays(365);

  /** A chain verification with the time it finished, so a stale one is not read as a fresh one. */
  public record VerifiedChain(IssuanceChainVerifier.Report report, Instant completedAt) {}

  public IssuanceSweeper(
      IssuanceUnitOfWork unitOfWork,
      InboundEventStore inbound,
      IssuanceWorker worker,
      ReconciliationSweep reconciliation,
      IssuanceChainVerifier verifier,
      StrandedInboundSweep stranded,
      Clock clock,
      EInvoiceProperties properties) {
    this.unitOfWork = unitOfWork;
    this.inbound = inbound;
    this.worker = worker;
    this.reconciliation = reconciliation;
    this.verifier = verifier;
    this.stranded = stranded;
    this.clock = clock;
    this.properties = properties;
  }

  @Override
  public void afterPropertiesSet() {
    warnAboutWeakLimits(stranded.runningLimit());
    Duration sweep = properties.getIssuance().getSweepInterval();
    scheduler.scheduleWithFixedDelay(
        this::sweepOnce, sweep.toMillis(), sweep.toMillis(), TimeUnit.MILLISECONDS);
    if (properties.getReconcile().isEnabled()) {
      Duration interval = properties.getReconcile().getInterval();
      scheduler.scheduleWithFixedDelay(
          this::reconcileOnce, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }
    // I-11: the chain is verified on a schedule and the indicator reads the cache. A full walk
    // grows without bound and a health endpoint is polled every few seconds by three different
    // things; an on-demand verification stays available for the auditor path.
    Duration chain = properties.getReconcile().getInterval();
    scheduler.scheduleWithFixedDelay(
        this::verifyChainOnce, 0, chain.toMillis(), TimeUnit.MILLISECONDS);
    Duration purge = Duration.ofHours(1);
    scheduler.scheduleWithFixedDelay(
        this::purgeOnce, purge.toMillis(), purge.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Re-picks every event that is due: running, parked, a retryable terminal inside the ceiling, or
   * a version-skew refusal the currently configured pin has cured (D2-03).
   */
  public int sweepOnce() {
    try {
      // QUESTIONS 29: first expire what has been running too long, then read what is due, with the
      // same instant. A running row is then in exactly one of the two.
      Instant now = clock.instant();
      lastExpired.set(stranded.expire(now));
      List<InboundEvent> due =
          inbound.due(
              now,
              properties.getIssuance().getRetryCeiling(),
              stranded.runningLimit(),
              properties.getIssuance().getQueueCapacity(),
              unitOfWork.configuration().pinnedApiVersion());
      for (InboundEvent event : due) {
        worker.submit(event.eventId());
      }
      lastSweep.set(clock.instant());
      return due.size();
    } catch (RuntimeException e) {
      // A sweep that fails must not kill the schedule: scheduleWithFixedDelay cancels the task on
      // an escaping throwable, and a silently cancelled sweeper is the watcher that was not there.
      log.warn("einvoice: the issuance sweep failed and will run again next interval", e);
      return 0;
    }
  }

  /**
   * D-SRE-3: once at startup, on this scheduler's thread, after every startup check has passed (the
   * application is ready). The key comes from the environment, so a fixed key means a restart: the
   * events a refused key stopped become due without waiting for a new sale.
   */
  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    scheduler.execute(this::rearmAccessRefusedOnce);
  }

  /**
   * The startup re-arm of rows stopped on a refused Stripe key; a failure is logged, never fatal.
   */
  public int rearmAccessRefusedOnce() {
    try {
      return unitOfWork.rearmAccessRefused();
    } catch (RuntimeException e) {
      log.warn("einvoice: the startup re-arm of events stopped on a refused key failed", e);
      return 0;
    }
  }

  public Optional<ReconciliationSweep.Result> reconcileOnce() {
    try {
      ReconciliationSweep.Result result = reconciliation.sweep();
      lastReconciliation.set(clock.instant());
      lastResult.set(result);
      return Optional.of(result);
    } catch (RuntimeException e) {
      log.warn("einvoice: reconciliation failed and will run again next interval", e);
      return Optional.empty();
    }
  }

  public int purgeOnce() {
    try {
      Instant cutoff = clock.instant().minus(properties.getInbound().getRetention());
      int purged = inbound.purgeOlderThan(cutoff, properties.getInbound().getPurgeBatch());
      if (purged > 0) {
        // Counts only: what was purged is buyer data by definition.
        log.info("einvoice: purged {} inbound event rows past the retention ceiling", purged);
      }
      return purged;
    } catch (RuntimeException e) {
      log.warn("einvoice: the inbound purge failed and will run again next interval", e);
      return 0;
    }
  }

  public Optional<VerifiedChain> verifyChainOnce() {
    try {
      VerifiedChain verified = new VerifiedChain(verifier.verify(), clock.instant());
      lastChainCheck.set(verified);
      return Optional.of(verified);
    } catch (RuntimeException e) {
      log.warn("einvoice: the chain verification failed and will run again next interval", e);
      return Optional.empty();
    }
  }

  /** How many rows the last sweep moved to {@code EXPIRED}; absent before the first sweep. */
  public Optional<Integer> lastExpiredCount() {
    return Optional.ofNullable(lastExpired.get());
  }

  private static void warnAboutWeakLimits(RunningLimit limit) {
    if (limit.running().compareTo(RUNNING_CEILING_WARN_ABOVE) > 0) {
      log.warn(
          "einvoice: einvoice.issuance.running-ceiling is {}, above {}. A row stuck in a running"
              + " state is then re-run for that long before anyone is told. Keep the default (PT72H)"
              + " unless an outage of that length is normal here.",
          limit.running(),
          RUNNING_CEILING_WARN_ABOVE);
    }
    if (limit.parked().compareTo(PARKED_CEILING_WARN_ABOVE) > 0) {
      log.warn(
          "einvoice: einvoice.issuance.parked-ceiling is {}, above {}. A draft that is never"
              + " finalised is then re-checked for that long before anyone is told.",
          limit.parked(),
          PARKED_CEILING_WARN_ABOVE);
    }
  }

  /** The cached chain status. Absent, or older than two intervals, is never "healthy". */
  public Optional<VerifiedChain> lastChainCheck() {
    return Optional.ofNullable(lastChainCheck.get());
  }

  /** When the sweeper last completed. Absent means it has not run yet, which is never "healthy". */
  public Optional<Instant> lastSweepAt() {
    return Optional.ofNullable(lastSweep.get());
  }

  public Optional<Instant> lastReconciliationAt() {
    return Optional.ofNullable(lastReconciliation.get());
  }

  public Optional<ReconciliationSweep.Result> lastReconciliationResult() {
    return Optional.ofNullable(lastResult.get());
  }

  @Override
  public void destroy() {
    scheduler.shutdownNow();
  }
}
