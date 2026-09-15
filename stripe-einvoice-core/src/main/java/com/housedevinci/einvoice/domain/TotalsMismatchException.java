package com.housedevinci.einvoice.domain;

/**
 * Our recomputation and the upstream's own scalar disagree (D-12).
 *
 * <p>The <b>message names the fields and never the values</b>: an amount is business data that ends
 * up in the host's logs, in an alert body and in a metric tag if nobody stops it (checklist line
 * 45). The values are on the exception as accessors instead, so an authorised, deliberate caller -
 * a debug channel that is off by default, an operator tool behind a role - can render them without
 * every log line carrying them.
 *
 * <p>There is no adjustment path and no rounding-difference line. A difference of one cent in one
 * bucket is a refusal, because the alternative ("adjust the largest line") silently changes what
 * the seller charged.
 */
public final class TotalsMismatchException extends EInvoiceException {

  private static final long serialVersionUID = 1L;

  private final String field;
  private final long recomputed;
  private final long upstream;

  public TotalsMismatchException(String field, long recomputed, long upstream) {
    super(
        ErrorCodes.TOTALS_MISMATCH,
        "our recomputation of '"
            + field
            + "' and the invoice's own value disagree. The document is not issued and no number is"
            + " consumed; the two values are on the exception, not in this message");
    this.field = field;
    this.recomputed = recomputed;
    this.upstream = upstream;
  }

  /** The Stripe field whose value we could not reproduce. */
  public String field() {
    return field;
  }

  /** What our own recomputation produced, in minor units. */
  public long recomputed() {
    return recomputed;
  }

  /** What the upstream reported, in minor units. */
  public long upstream() {
    return upstream;
  }
}
