package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The durable record of every privileged re-open, and the only way to perform one (D9-03).
 *
 * <p>The findings table cannot carry this: it is upserted on {@code (seller, mode, code, subject)},
 * so the next operator's call overwrites the previous one's, and it is the one table in the schema
 * that must stay mutable. One action in this module can turn a recorded refusal into a legal
 * document, and it is the action whose record was least protected.
 *
 * <p>Two rows per call and <b>no update path</b>: {@code REQUESTED} is written <em>in the same
 * transaction as the re-open itself</em>, so an event that is running again is always attributed;
 * {@code CONCLUDED} is appended when the run ends, with the outcome state and code - including the
 * code of an exception that escaped the pipeline (R-04), so an orphan {@code REQUESTED} means one
 * thing only: the process died mid-run.
 *
 * <p><b>Append-only against the application role, and not hash-chained</b> (R-02). The runtime role
 * holds {@code SELECT} and {@code INSERT} and the triggers refuse {@code UPDATE}, {@code DELETE}
 * and {@code TRUNCATE}; a role that owns the schema can disable those triggers and rewrite a row,
 * and no verifier in this module will report it. The issuance chain is the tamper-evident record.
 * This is not it, and nothing in the public text says otherwise.
 */
public interface ReprocessLedger {

  /**
   * Re-opens one terminal mapping refusal and records who asked, in one transaction.
   *
   * <p>The {@code UPDATE} is conditional on the state still being {@code FAILED_MAPPING}, so the
   * eligibility check and the write cannot come apart and two simultaneous operator calls produce
   * exactly one winner. Either both statements commit or neither does: there is no ordering of
   * failures that leaves an event re-opened with nobody recorded as having asked (D9-01).
   *
   * @return the sequence of the {@code REQUESTED} row, or empty when the row was no longer eligible
   */
  OptionalLong reopenAndRecord(
      ReprocessRequest request, String sellerId, Mode mode, String code, Instant now);

  /**
   * Appends the {@code CONCLUDED} row for one request.
   *
   * @param requestSeq the sequence {@link #reopenAndRecord} returned
   * @param outcome where the pipeline stopped - state empty when it threw - and the code it stopped
   *     with, which for a throw is the error code that escaped
   */
  void conclude(long requestSeq, ReprocessRecord.Outcome outcome, Instant now);

  /** Every recorded row for one event, oldest first. Ids and codes only. */
  List<ReprocessRecord> forEvent(String eventId);

  /**
   * Requests with no conclusion that are older than {@code olderThan}, scoped to one seller and
   * mode: a process that died between the re-open and the end of the run. The reconciliation sweep
   * raises {@code DEI-276} for each.
   */
  List<ReprocessRecord> unfinished(String sellerId, Mode mode, Instant olderThan, int limit);

  /**
   * One recorded row.
   *
   * @param kind {@code REQUESTED} or {@code CONCLUDED}
   */
  record ReprocessRecord(
      long seq,
      String kind,
      OptionalLong requestSeq,
      String eventId,
      String sellerId,
      Mode mode,
      String actor,
      String reason,
      Instant at,
      String outcomeState,
      String outcomeCode,
      String legalNumber) {

    public static final String REQUESTED = "REQUESTED";
    public static final String CONCLUDED = "CONCLUDED";

    /** What a finished run concluded, or what escaped it. */
    public record Outcome(
        String eventId,
        String sellerId,
        Mode mode,
        Optional<InboundState> state,
        String code,
        String legalNumber) {

      public Outcome {
        state = state == null ? Optional.empty() : state;
        code = code == null ? "" : code;
        legalNumber = legalNumber == null ? "" : legalNumber;
      }
    }
  }
}
