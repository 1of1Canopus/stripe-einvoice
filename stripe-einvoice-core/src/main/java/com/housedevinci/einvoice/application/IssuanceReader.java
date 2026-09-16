package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.SeriesReport;
import java.util.List;
import java.util.Optional;

/**
 * Reads the issuance ledger. Every method takes the seller and the mode, and every statement behind
 * them carries both in its predicate: the webhook path is unauthenticated, so a query that could
 * run without a server-resolved seller is a query that will one day run without one (checklist line
 * 48).
 */
public interface IssuanceReader {

  Optional<Issuance> findBySource(String sellerId, Mode mode, String stripeInvoiceId);

  /** Every number allocated in the series, with its disposition and the open count. */
  SeriesReport seriesReport(SeriesKey seriesKey);

  /**
   * Every issuance row of one series, newest number first.
   *
   * <p>Reconciliation reads whole rows rather than the report's lines: it needs the archive key and
   * the content hash, which are what the archive directions of the sweep compare against.
   */
  List<Issuance> inSeries(SeriesKey seriesKey, int limit);
}
