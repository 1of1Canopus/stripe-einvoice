package com.housedevinci.einvoice.domain;

/**
 * {@link ErrorCodes#ISSUE_CLOCK_BEHIND_SERIES}: the clock the issue date comes from cannot be
 * trusted for this allocation, so no number was given (design 5.2, D-PS1-46).
 *
 * <p>One code, two outcomes. A small regression is waited out: the clock catches up, so the event
 * retries with the normal backoff. A regression or a disagreement between the database and the
 * application clock of more than one day does not heal by waiting; the event stops, an operator
 * fixes the clock and re-runs it through the reprocess path. There is never an "accept" release.
 */
public final class IssueClockException extends EInvoiceException {

  private static final long serialVersionUID = 1L;

  private final boolean retryable;

  public IssueClockException(String message, boolean retryable) {
    super(ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES, message);
    this.retryable = retryable;
  }

  /** True when waiting can fix it; false when an operator must fix the clock first. */
  public boolean retryable() {
    return retryable;
  }
}
