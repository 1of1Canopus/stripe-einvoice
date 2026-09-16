package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.IssuanceChainVerifier;
import com.housedevinci.einvoice.application.ReconciliationSweep;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The <b>operational</b> signal, and only that (I-04).
 *
 * <p>Health answers "can this module do its work right now": is the database reachable, has the
 * sweeper run, is the reconciliation result fresh, does the chain still verify. Business conditions
 * - a void that needs a credit note, a terminal mapping failure, an open allocation from last week
 * - are <b>not</b> here. They have no automatic remedy, they can be weeks old, and a health
 * contributor that lands in the {@code readiness} or {@code liveness} group takes the pod out of
 * service or restarts it. A free-core user who voided one invoice in Stripe would otherwise be DOWN
 * forever with nothing available to clear it, and an indicator that cannot be cleared is one
 * operators learn to ignore. Those live on the findings list instead.
 *
 * <p>This contributor is registered in a health group of its own ({@code einvoice}), outside
 * readiness and liveness, and a test asserts it is absent from both.
 *
 * <p>"No result" is never "healthy": a sweep that has not run and a reconciliation older than two
 * intervals are both DOWN, because the whole point of the watcher is to notice silence.
 */
public class EInvoiceHealthIndicator implements HealthIndicator {

  private final IssuanceSweeper sweeper;
  private final EInvoiceProperties properties;
  private final Clock clock;

  public EInvoiceHealthIndicator(
      IssuanceSweeper sweeper, EInvoiceProperties properties, Clock clock) {
    this.sweeper = sweeper;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public Health health() {
    Health.Builder health = Health.up();
    boolean down = false;

    Optional<Instant> lastSweep = sweeper.lastSweepAt();
    health.withDetail("lastSweep", lastSweep.map(Instant::toString).orElse("never"));
    if (isStale(lastSweep, properties.getIssuance().getSweepInterval())) {
      down = true;
      health.withDetail("sweep", "stale");
    }

    if (properties.getReconcile().isEnabled()) {
      Optional<Instant> lastReconciliation = sweeper.lastReconciliationAt();
      health.withDetail(
          "lastReconciliation", lastReconciliation.map(Instant::toString).orElse("never"));
      if (isStale(lastReconciliation, properties.getReconcile().getInterval())) {
        down = true;
        health.withDetail("reconciliation", "stale");
      }
      // Counts only. A finding's subject is an id and its code is a constant, and neither belongs
      // in a health payload that any operator dashboard will happily render and cache.
      sweeper
          .lastReconciliationResult()
          .map(ReconciliationSweep.Result::total)
          .ifPresent(total -> health.withDetail("openFindingsAtLastSweep", total));
    }

    Optional<IssuanceSweeper.VerifiedChain> chain = sweeper.lastChainCheck();
    health.withDetail(
        "chain",
        chain.map(verified -> verified.report().status().name()).orElse("NOT_VERIFIED_YET"));
    if (chain.isEmpty()
        || isStale(
            chain.map(IssuanceSweeper.VerifiedChain::completedAt),
            properties.getReconcile().getInterval())
        || !chain.get().report().intact()) {
      down = true;
    }
    chain
        .map(verified -> verified.report().status())
        .filter(status -> status == IssuanceChainVerifier.Status.INTACT_UNKEYED)
        .ifPresent(status -> health.withDetail("chainKeyed", false));

    return down ? health.status("DOWN").build() : health.build();
  }

  /** Absent is stale, and two intervals is the bound: one interval is a race with the scheduler. */
  private boolean isStale(Optional<Instant> last, Duration interval) {
    return last.isEmpty() || last.get().isBefore(clock.instant().minus(interval.multipliedBy(2)));
  }
}
