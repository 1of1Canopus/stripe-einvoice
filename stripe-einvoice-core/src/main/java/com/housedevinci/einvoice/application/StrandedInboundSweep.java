package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.RunningLimit;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The time limit on running inbound rows (QUESTIONS 29): no inbound event is due for ever.
 *
 * <p>Example: a Stripe invoice stays a draft. Its event is {@code PARKED} and every backoff the
 * sweeper re-checks it. Ninety days after it was parked ({@code einvoice.issuance.parked-ceiling}),
 * this sweep moves the row to {@code EXPIRED} with {@code DEI-266} and records the finding {@code
 * DEI-282} on its event id, once. The row is no longer due. An operator who wants it back calls the
 * reprocess, which starts its clock again.
 *
 * <p>It runs in the issuance sweep, before the due query, with the same {@code now}, so a running
 * row is in exactly one of the two. It is not in the reconciliation sweep on purpose: that one can
 * be switched off, and a limit that hides rows from the due query with nothing left to report them
 * is the silent terminal this exists to prevent.
 *
 * <p>It reports and stops; it never repairs. Each row is its own transaction, and a row that moved
 * between the read and the write is skipped, not an error, so one row never stops the rest.
 */
public final class StrandedInboundSweep {

  private static final Logger log = LoggerFactory.getLogger(StrandedInboundSweep.class);

  private final InboundEventStore inbound;
  private final RunningLimit runningLimit;
  private final int pageSize;
  private final IssuanceUnitOfWork.Configuration configuration;

  public StrandedInboundSweep(
      InboundEventStore inbound,
      RunningLimit runningLimit,
      int pageSize,
      IssuanceUnitOfWork.Configuration configuration) {
    this.inbound = Objects.requireNonNull(inbound, "inbound");
    this.runningLimit = Objects.requireNonNull(runningLimit, "runningLimit");
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    if (pageSize < 1) {
      throw new EInvoiceException(ErrorCodes.CONFIG, "the stranded-row page size must be positive");
    }
    this.pageSize = pageSize;
  }

  public RunningLimit runningLimit() {
    return runningLimit;
  }

  /**
   * Expires every running row past its limit at {@code now}, up to one page.
   *
   * @return how many rows this call moved to {@code EXPIRED}; each one has its finding
   */
  public int expire(Instant now) {
    int expired = 0;
    for (InboundEvent row : inbound.stranded(now, runningLimit, pageSize)) {
      try {
        ComplianceFinding finding =
            ComplianceFinding.of(
                configuration.sellerId(),
                row.mode(),
                ErrorCodes.RECON_STUCK_INBOUND,
                row.eventId(),
                now);
        if (inbound.expire(row, finding, now)) {
          expired++;
          log.warn(
              "einvoice: inbound event {} stayed {} past its time limit and is now EXPIRED ({});"
                  + " finding {} raised. A reprocess starts it again.",
              row.eventId(),
              row.state(),
              ErrorCodes.INBOUND_CEILING_REACHED,
              ErrorCodes.RECON_STUCK_INBOUND);
        }
      } catch (EInvoiceException e) {
        // F-6: one row that cannot be written must not take the rest of the page with it. It is
        // still stranded, so the next sweep tries it again.
        log.warn(
            "einvoice: could not expire inbound event {} ({}); the next sweep tries again",
            row.eventId(),
            e.code());
      }
    }
    return expired;
  }
}
