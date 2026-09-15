package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.FindingStore;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.Mode;
import java.time.Clock;
import java.util.List;

/**
 * Acknowledging a compliance finding: a <b>service method, never an endpoint</b>.
 *
 * <p>Same reason as the void operation (N-04). This module is a library and cannot authenticate
 * anyone, so a module that auto-configured an acknowledgement endpoint would hand an
 * unauthenticated caller a way to silence every alert it has. The host application decides who may
 * call this, and the reason it records is screened and mandatory.
 *
 * <p>An acknowledgement never deletes: the row stays, with who-decided-what written on it.
 */
public class IssuanceFindingService {

  private final FindingStore findings;
  private final String sellerId;
  private final Mode mode;
  private final Clock clock;

  public IssuanceFindingService(FindingStore findings, String sellerId, Mode mode, Clock clock) {
    this.findings = findings;
    this.sellerId = sellerId;
    this.mode = mode;
    this.clock = clock;
  }

  public List<ComplianceFinding> open(int limit) {
    return findings.open(sellerId, mode, limit);
  }

  public void acknowledge(String code, String subjectId, String reason) {
    findings.acknowledge(sellerId, mode, code, subjectId, reason, clock.instant());
  }
}
