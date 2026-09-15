package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.Mode;
import java.time.Instant;
import java.util.List;

/**
 * The compliance findings list (I-04): what an operator must act on, with no automatic remedy.
 *
 * <p>Recording is idempotent on {@code (seller, mode, code, subject)} - a sweep that runs every
 * fifteen minutes must not produce a new row every time it sees the same unfixed thing - and an
 * acknowledgement records a reason without deleting anything.
 */
public interface FindingStore {

  /** Records a finding, or refreshes the last-seen of one already open. */
  void record(ComplianceFinding finding);

  /**
   * Marks a finding handled, with a mandatory screened reason. The row stays: an acknowledgement is
   * evidence of a decision, not a delete.
   */
  void acknowledge(
      String sellerId, Mode mode, String code, String subjectId, String reason, Instant when);

  /** Open findings, oldest first. Ids and codes only. */
  List<ComplianceFinding> open(String sellerId, Mode mode, int limit);

  /** Counts per code, for the metrics and the summary. */
  List<CodeCount> openCounts(String sellerId, Mode mode);

  record CodeCount(String code, long count) {}
}
