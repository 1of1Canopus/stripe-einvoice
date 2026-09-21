package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.util.List;

/**
 * What a renderer answers when it is asked, before any number is allocated, whether it could
 * produce a document for this invoice at all.
 *
 * <p>A typed report rather than a boolean, because the refusal is written onto the inbound event
 * row and an operator greps for the code. A boolean would force the call site to invent a code, and
 * an invented code is exactly what that row exists to avoid.
 *
 * <p>Ids and codes only, like every other operator-facing record in this module: {@code findings}
 * carries business-term names and rule ids, never a buyer field and never an amount.
 *
 * @param verdict what the renderer concluded
 * @param code this module's stable error code for a refusal, empty otherwise
 * @param findings zero or more rule or term identifiers, for an operator reading the log
 */
public record PreflightReport(Verdict verdict, String code, List<String> findings) {

  /** The three answers a renderer can give, and there is deliberately no fourth. */
  public enum Verdict {
    /** The mapping ran to completion. Allocating a number for this invoice is not wasted. */
    PASSED,
    /** The mapping refused. No number is allocated, and {@code code} says why. */
    REFUSED,
    /**
     * This renderer does not implement a preflight at all - a third-party implementation written
     * before the port had one. Allocation proceeds exactly as it did before the preflight existed,
     * and the fact is recorded rather than swallowed: a startup warning, a compliance finding once
     * per application start, and the verdict on the outcome. Never a silent {@code PASSED}.
     */
    NOT_SUPPORTED
  }

  public PreflightReport {
    if (verdict == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "a preflight report needs a verdict");
    }
    code = code == null ? "" : code;
    findings = findings == null ? List.of() : List.copyOf(findings);
    if (verdict == Verdict.REFUSED && code.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, "a preflight refusal carries the code the inbound row records");
    }
  }

  public static PreflightReport passed() {
    return new PreflightReport(Verdict.PASSED, "", List.of());
  }

  public static PreflightReport refused(String code) {
    return new PreflightReport(Verdict.REFUSED, code, List.of());
  }

  public static PreflightReport refused(String code, List<String> findings) {
    return new PreflightReport(Verdict.REFUSED, code, findings);
  }

  public static PreflightReport notSupported() {
    return new PreflightReport(Verdict.NOT_SUPPORTED, "", List.of());
  }

  public boolean refused() {
    return verdict == Verdict.REFUSED;
  }
}
