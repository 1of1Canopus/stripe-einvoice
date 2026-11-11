package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.application.InboundEventStore.Recorded;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one way an event is recorded (D-PR60-2): the webhook endpoint and the reconciliation pass
 * both come through here.
 *
 * <p>It passes the store the pin the route reads, {@code configuration.pinnedApiVersion()}, never
 * the SDK's constant, so a copy cures a row exactly when the route would accept it (D-SKEW-10). It
 * raises the finding of an identity mismatch under the configured seller and mode (D-SKEW-5): that
 * is a decision about the event, so it lives here and not in the controller.
 */
public final class InboundIntake {

  private static final Logger log = LoggerFactory.getLogger(InboundIntake.class);

  private final InboundEventStore inbound;
  private final FindingStore findings;
  private final IssuanceUnitOfWork.Configuration configuration;
  private final Clock clock;

  public InboundIntake(
      InboundEventStore inbound,
      FindingStore findings,
      IssuanceUnitOfWork.Configuration configuration,
      Clock clock) {
    this.inbound = Objects.requireNonNull(inbound, "inbound");
    this.findings = Objects.requireNonNull(findings, "findings");
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Records one event at the configured pin.
   *
   * <p>On {@link Recorded#IDENTITY_MISMATCH} nothing was written to the inbound row: one WARN with
   * {@code DEI-223} and the event id, and a finding on the event id. The caller answers as for a
   * duplicate.
   *
   * @return the store's answer; {@link Recorded#runs()} says whether to hand the row to a worker
   */
  public Recorded record(InboundEvent event, byte[] body) {
    Recorded recorded = inbound.record(event, body, configuration.pinnedApiVersion());
    if (recorded == Recorded.IDENTITY_MISMATCH) {
      log.warn(
          "einvoice: event {} arrived again with another event type, object, mode or account than"
              + " the recorded one; nothing was written and a finding was raised ({})",
          event.eventId(),
          ErrorCodes.INBOUND_IDENTITY_MISMATCH);
      findings.record(
          ComplianceFinding.of(
              configuration.sellerId(),
              configuration.mode(),
              ErrorCodes.INBOUND_IDENTITY_MISMATCH,
              event.eventId(),
              clock.instant()));
    }
    return recorded;
  }
}
