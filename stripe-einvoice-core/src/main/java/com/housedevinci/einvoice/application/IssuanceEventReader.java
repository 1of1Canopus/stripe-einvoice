package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.IssuanceEvent;
import java.util.List;

/** Pages the chained issuance log in sequence order, for the verifier and for an auditor export. */
public interface IssuanceEventReader {

  List<IssuanceEvent> readAfter(long sequenceExclusive, int limit);

  /** Issuance rows whose disposition is settled, for the verifier's cross-check. */
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
   * @param legalNumber the rendered number, which is what the log and the row must agree on
   * @param stripeInvoiceId the source object id: one sale, one number, for all time
   */
  record DisposedIssuance(
      String sellerId,
      String mode,
      String series,
      int fiscalYear,
      String stripeInvoiceId,
      String legalNumber,
      String state) {}
}
