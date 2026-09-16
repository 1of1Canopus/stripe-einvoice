package com.housedevinci.einvoice.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Something an operator has to act on, that is not an outage.
 *
 * <p><b>Why these are not health</b> (I-04). A void that needs a credit note, a terminal mapping
 * failure, a run of refused events and an allocation left open for a week are all business
 * conditions with no automatic remedy. Putting them on a health indicator - which in a default
 * Spring Boot deployment can land in the readiness or liveness group - would let a three-week-old
 * accounting condition take the host application out of the load balancer, and would leave a user
 * who voided one invoice DOWN forever with nothing available to clear it. An indicator that cannot
 * be cleared is one operators learn to ignore.
 *
 * <p>So findings are a list: counts and codes as metrics, a read of the open ones, and an
 * <b>acknowledgement</b> that records who decided it was handled and why - and never deletes the
 * finding.
 *
 * <p>Ids and codes only. No buyer field, no amount, and the acknowledgement reason is screened like
 * every other free text that reaches an auditor-facing report (checklist line 45).
 *
 * @param subjectId what the finding is about: a Stripe invoice id, an event id, an archive key
 */
public record ComplianceFinding(
    String sellerId,
    Mode mode,
    String code,
    String subjectId,
    Instant firstSeen,
    Instant lastSeen,
    Instant acknowledgedAt,
    String acknowledgementReason) {

  /** Long enough for a sentence, short enough to stay a reason. */
  public static final int MAX_REASON_CHARS = 500;

  public ComplianceFinding {
    Identifiers.validate("seller id", sellerId, 64);
    Identifiers.validate("finding code", code, 16);
    if (subjectId == null || subjectId.isBlank()) {
      throw new EInvoiceException(ErrorCodes.INVALID, "a finding needs a subject");
    }
    if (mode == null || firstSeen == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "a finding needs a mode and a first-seen");
    }
    subjectId = subjectId.strip();
    lastSeen = lastSeen == null ? firstSeen : lastSeen;
    acknowledgementReason = acknowledgementReason == null ? "" : acknowledgementReason;
  }

  public static ComplianceFinding of(
      String sellerId, Mode mode, String code, String subjectId, Instant when) {
    Instant at = Timestamps.toStorage(when);
    return new ComplianceFinding(sellerId, mode, code, subjectId, at, at, null, "");
  }

  public boolean open() {
    return acknowledgedAt == null;
  }

  public Optional<String> reason() {
    return acknowledgementReason.isEmpty() ? Optional.empty() : Optional.of(acknowledgementReason);
  }
}
