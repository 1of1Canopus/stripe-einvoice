package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Mode;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The durable record that an application is running on a renderer with no pre-allocation preflight.
 *
 * <p>An outcome is a transient object and a failed disposition only exists when something failed.
 * An application that ran for a month on a third-party renderer without a preflight, issuing
 * successfully the whole time, would otherwise have one line in a boot log nobody kept as the only
 * evidence that every one of those documents was numbered before its fields were screened. So the
 * fact is raised as a compliance finding - the list that is already operator-facing, already
 * acknowledgeable, and already where this module puts "a human should know".
 *
 * <p>Once per application start, per renderer: the findings store is idempotent on {@code (seller,
 * mode, code, subject)}, so a restart refreshes the last-seen rather than adding a row, and the
 * in-memory guard here keeps one invoice per second from writing one row per second.
 *
 * <p>Deliberately <b>not</b> in the chained material. This is a fact about the application, not
 * about any document; putting it on the chain would make an operator's configuration change look
 * like a change to an invoice.
 */
public final class PreflightSupport {

  private static final Logger log = LoggerFactory.getLogger(PreflightSupport.class);

  private final FindingStore findings;
  private final Clock clock;
  private final Set<String> alreadyRecorded = ConcurrentHashMap.newKeySet();

  public PreflightSupport(FindingStore findings, Clock clock) {
    this.findings = java.util.Objects.requireNonNull(findings, "findings");
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
  }

  /**
   * @param renderer the wired renderer's class name, which is what an operator has to act on
   */
  public void notSupported(String sellerId, Mode mode, String renderer) {
    String subject = renderer == null || renderer.isBlank() ? "unknown renderer" : renderer;
    if (!alreadyRecorded.add(sellerId + "|" + mode.wire() + "|" + subject)) {
      return;
    }
    log.warn(
        "einvoice: the wired document renderer {} implements no preflight, so every invoice is"
            + " numbered before its fields are screened and a buyer-controlled field this module"
            + " cannot map costs one legal number. Compliance finding {} is open until the"
            + " renderer overrides DocumentRenderer.preflight.",
        subject,
        ErrorCodes.PREFLIGHT_NOT_SUPPORTED);
    findings.record(
        ComplianceFinding.of(
            sellerId, mode, ErrorCodes.PREFLIGHT_NOT_SUPPORTED, subject, clock.instant()));
  }
}
