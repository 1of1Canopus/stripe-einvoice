package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.SeriesReport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the issuance ledger. Every method takes the seller and the mode, and every statement behind
 * them carries both in its predicate: the webhook path is unauthenticated, so a query that could
 * run without a server-resolved seller is a query that will one day run without one (checklist line
 * 48).
 */
public interface IssuanceReader {

  Optional<Issuance> findBySource(String sellerId, Mode mode, String stripeInvoiceId);

  /**
   * The states of every issuance row for this Stripe invoice, under <b>any</b> seller and mode;
   * empty when the invoice holds no number anywhere.
   *
   * <p>The one read here without a seller and a mode in its predicate, on purpose (D-SRE-6). It
   * answers a single question, "may this inbound row hold a number, and is that number still
   * open?", and returns states only, no row data and no number. A scoped read asked by an instance
   * of the other mode would say "no number" for a live numbered row, and the row would be moved to
   * a state that never holds one. Unscoped, a wrong mode can only make a row look numbered, which
   * keeps it under {@code FAILED_ISSUANCE} where the stuck check sees it: fail closed. Every
   * decision that a row holds a number goes through this method: the {@code FETCHED} read of a run,
   * the reprocess eligibility, and the choice between the in-place re-open and the re-open to
   * {@code RECEIVED}.
   */
  Set<IssuanceState> issuanceStates(String stripeInvoiceId);

  /** Every number allocated in the series, with its disposition and the open count. */
  SeriesReport seriesReport(SeriesKey seriesKey);

  /**
   * Every issuance row of one series, newest number first.
   *
   * <p>Reconciliation reads whole rows rather than the report's lines: it needs the archive key and
   * the content hash, which are what the archive directions of the sweep compare against.
   */
  List<Issuance> inSeries(SeriesKey seriesKey, int limit);

  /**
   * Open numbers of this seller and mode allocated before {@code before}, oldest first, in every
   * series and every fiscal year (D-PR50-B1).
   *
   * <p>The stuck check reads this, not {@link #inSeries}: a number left open is stuck whatever year
   * it was allocated in and however many newer numbers came after it. Example: the application is
   * down from 30 December to 5 January; a number allocated on 30 December and never archived is
   * still named by the first pass of January. "Open" is {@link IssuanceState#open()}.
   */
  List<Issuance> openOlderThan(String sellerId, Mode mode, Instant before, int limit);

  /**
   * Burned or voided rows of this seller and mode that recorded an archive key, newest number
   * first, in every series and every fiscal year (D-PR52-1).
   *
   * <p>The disposed-number check of the reconciliation reads this, not {@link #inSeries}: the risk
   * starts when a number is burned or voided, which can be long after it was numbered. Example: a
   * 2026 number stays open over the new year and is voided on 10 January 2027 while its PUT is on
   * the store; the 2027 pass still looks it up. "Disposed" here is {@code FAILED_VALIDATION} or
   * {@code VOID_UNUSED}: the number will never carry a document.
   */
  List<Issuance> disposedWithKey(String sellerId, Mode mode, int limit);
}
