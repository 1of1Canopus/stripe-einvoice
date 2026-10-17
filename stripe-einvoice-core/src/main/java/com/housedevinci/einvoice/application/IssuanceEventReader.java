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
   * Every series row's document type, for the verifier's type cross-check (S-01). The binding of a
   * series to a type lives in the series row, which is not chained, so the verifier compares it
   * with the type each chained event claims - or, for a 0.1.0 event, with the invoice type every
   * 0.1.0 event has by definition.
   */
  List<SeriesType> seriesTypes();

  /** One series row's identity and type. */
  record SeriesType(
      String sellerId, String mode, String series, int fiscalYear, String documentType) {}

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
   * @param documentType the row's BT-3 code (format 2): a rewritten type disagrees with the chain
   * @param correctedIdentity for a credit note, the corrected invoice's row id, seller, mode,
   *     series, fiscal year and legal number as the rows hold them now, joined through the link;
   *     empty for an invoice. Swapping two ids changes this string, so it disagrees with the chain
   *     (D-PS1-37)
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
      String voidRuleId,
      String documentType,
      String correctedIdentity) {

    /** A 0.1.0-shaped invoice row, for callers that predate the document type. */
    public DisposedIssuance(
        String sellerId,
        String mode,
        String series,
        int fiscalYear,
        String stripeInvoiceId,
        String legalNumber,
        String state,
        String voidReason,
        String voidRuleId) {
      this(
          sellerId,
          mode,
          series,
          fiscalYear,
          stripeInvoiceId,
          legalNumber,
          state,
          voidReason,
          voidRuleId,
          "380",
          "");
    }
  }
}
