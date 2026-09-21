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

  public ReprocessRequest {
    Identifiers.validate("stripe event id", eventId);
    actor = Identifiers.validate("reprocess actor", actor, 64);
    reason = ScreenedText.screen("reprocess reason", reason, MAX_REASON_CHARS);
  }

  /** What the durable record says: who asked, and why, in one screened, bounded line. */
  public String justification() {
    String line = actor + ": " + reason;
    return line.length() <= MAX_REASON_CHARS ? line : line.substring(0, MAX_REASON_CHARS);
  }
}
