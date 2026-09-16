package com.housedevinci.einvoice.autoconfigure;

import com.housedevinci.einvoice.application.FindingStore;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.Mode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * The read of open compliance findings, as an actuator endpoint.
 *
 * <p>An actuator endpoint rather than a controller, deliberately: this module is a library and
 * cannot authenticate anyone, and actuator is the one surface a host application already knows it
 * has to protect. It is also <b>read-only</b> - acknowledging a finding is a service method the
 * host exposes behind its own authorisation, for the same reason the void operation is (N-04).
 *
 * <p>Ids, codes, counts and timestamps. No buyer field, no amount, and no acknowledgement text.
 */
@Endpoint(id = "einvoicefindings")
public class IssuanceFindingsEndpoint {

  /** Enough to see a pattern; an exhaustive export is the auditor path, which is Pro. */
  static final int MAX_FINDINGS = 200;

  private final FindingStore findings;
  private final String sellerId;
  private final Mode mode;

  public IssuanceFindingsEndpoint(FindingStore findings, String sellerId, Mode mode) {
    this.findings = findings;
    this.sellerId = sellerId;
    this.mode = mode;
  }

  @ReadOperation
  public Map<String, Object> findings() {
    List<ComplianceFinding> open = findings.open(sellerId, mode, MAX_FINDINGS);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("mode", mode.wire());
    response.put(
        "counts",
        findings.openCounts(sellerId, mode).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    FindingStore.CodeCount::code,
                    FindingStore.CodeCount::count,
                    (a, b) -> a,
                    LinkedHashMap::new)));
    response.put(
        "open",
        open.stream()
            .map(
                finding -> {
                  Map<String, Object> entry = new LinkedHashMap<>();
                  entry.put("code", finding.code());
                  entry.put("subject", finding.subjectId());
                  entry.put("firstSeen", finding.firstSeen().toString());
                  entry.put("lastSeen", finding.lastSeen().toString());
                  return entry;
                })
            .toList());
    return response;
  }
}
