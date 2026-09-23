package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.IssuanceEvent;
import java.util.List;

/** Pages the chained issuance log in sequence order, for the verifier and for an auditor export. */
public interface IssuanceEventReader {

  List<IssuanceEvent> readAfter(long sequenceExclusive, int limit);

  /**
   * Issuance rows whose disposition is settled, for the verifier's cross-check: issued, voided
   * unused, and - since D7-03 - burned by a validation or render refusal, each of which appends a
   * chained event. Not {@code NUMBERED} or {@code ARCHIVING}, which are still open, and not {@code
   * FAILED_ARCHIVE}, which is retryable.
   */
  List<DisposedIssuance> disposedIssuances();

  /**
   * The current state of a disposed issuance row, as the mapping table holds it.
   *
   * <p>D1-02: the full row identity, not just {@code sellerId|mode|legalNumber|state}. That four-
   * value tuple omits the series, the fiscal year and the source object id, so a single legitimate
   * chained event would vouch for every row that happens to share those four values - including a
   * forged, already-disposed row inserted out of band in a different fiscal year with the same
   * number.
   *
   * <p>RC-02: the two void columns are part of that identity. The justification for a hole in the
   * issued sequence is what an auditor is shown, it is chained, and the runtime role can
   * legitimately {@code UPDATE} this table - so if the row and the chain disagree about why a
   * number was burned, that is exactly the disagreement this cross-check exists to report.
   *
   * @param legalNumber the rendered number, which is what the log and the row must agree on
   * @param stripeInvoiceId the source object id: one sale, one number, for all time
   * @param voidReason the operator's or the pipeline's reason, screened, as the row holds it
   * @param voidRuleId the failing rule id or refusal code the disposition recorded
   */
  record DisposedIssuance(
      String sellerId,
      String mode,
      String series,
      int fiscalYear,
      String stripeInvoiceId,
      String legalNumber,
      String state,
      String voidReason,
      String voidRuleId) {}
}
