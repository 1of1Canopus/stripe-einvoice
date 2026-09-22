package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.Identifiers;
import com.housedevinci.einvoice.domain.ScreenedText;

/**
 * An operator's decision to run one refused event through the pipeline again, after the defect that
 * refused it was corrected.
 *
 * <p>Who and why are mandatory, because this is the one path in the module that can turn a recorded
 * refusal into a legal document, and "someone re-ran it" is not an audit trail. The reason is
 * screened and bounded exactly like a void reason: it is not ours, it is not trusted, and it is
 * never interpolated into anything.
 *
 * @param actor the host application's own identifier for the human who asked - a user id or a
 *     username, never an email address or another buyer-adjacent field
 */
public record ReprocessRequest(String eventId, String actor, String reason) {

  /** The reason's bound, the same as a void reason's and a finding acknowledgement's. */
  public static final int MAX_REASON_CHARS = ComplianceFinding.MAX_REASON_CHARS;

  /** The actor's bound, matching the record's own column and its CHECK constraint. */
  public static final int MAX_ACTOR_BYTES = 64;

  public ReprocessRequest {
    // Refused, never truncated, and before a single statement runs: an over-long actor or reason
    // is an operator's input error, and shortening it silently would store something nobody wrote.
    Identifiers.validate("stripe event id", eventId);
    actor = Identifiers.validate("reprocess actor", actor, MAX_ACTOR_BYTES);
    reason = ScreenedText.screen("reprocess reason", reason, MAX_REASON_CHARS);
  }

  // No justification(): the record holds the actor and the reason as two columns. A single
  // composed line had to be bounded again after composition, and bounding it meant truncating a
  // value both screens had accepted - which cut a surrogate pair in half and threw between the
  // re-open and the record (D9-01), and could not be split back into who and why anyway (D9-05).
}
