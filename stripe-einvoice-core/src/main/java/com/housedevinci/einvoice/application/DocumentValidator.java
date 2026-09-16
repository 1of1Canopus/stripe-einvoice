package com.housedevinci.einvoice.application;

import java.util.List;

/**
 * Validates the <b>exact bytes that will be archived</b>, never a re-serialisation of the model
 * (D-06, checklist line 15). A refusal leaves no file and consumes no number: the issuance stays at
 * its allocated number, which an operator voids with a reason.
 *
 * <p>Schema, schematron and the official validator suites arrive with the writers' own design. The
 * contract that matters here is the one the unit of work relies on: a validator that could not run
 * reports {@link Verdict#NOT_EVALUATED} with a reason, and this module treats that as a refusal -
 * never as a pass by construction (checklist lines 18, 57 and 65).
 */
public interface DocumentValidator {

  Report validate(byte[] bytes, DocumentInput input);

  /**
   * Whether this validator can run at all, as a fact about the application rather than about one
   * invoice (D3-02). {@code true} by default, matching every validator that has no such
   * data-independent condition; a validator whose ability to run depends on something present or
   * absent at startup (an XSLT 2.0 processor on the classpath, for one) overrides this so the unit
   * of work can refuse before the allocator runs, rather than discover the same fact once per
   * invoice after a number is already spent. The same shape as {@link
   * ArchiveStore#supportsAtomicCreate()}: a capability the port asks about itself, not a value it
   * computes from the document.
   */
  default boolean canValidate() {
    return true;
  }

  enum Verdict {
    /** Every rule ran and every rule passed. */
    PASSED,
    /** A rule ran and failed. The failing rule id is recorded on the issuance. */
    FAILED,
    /** A rule could not run at all. Refused: an unevaluated rule is not a passed rule. */
    NOT_EVALUATED
  }

  /**
   * @param ruleId the failing or unevaluated rule, recorded on the void so the rate of refusals is
   *     measurable rather than anecdotal
   * @param findings messages with no buyer value in them (checklist line 45)
   */
  record Report(Verdict verdict, String ruleId, List<String> findings) {

    public Report {
      if (verdict == null) {
        throw new com.housedevinci.einvoice.domain.EInvoiceException(
            com.housedevinci.einvoice.domain.ErrorCodes.VALIDATION_NOT_EVALUATED,
            "a validator returned no verdict, which is not a pass");
      }
      ruleId = ruleId == null ? "" : ruleId;
      findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static Report passed() {
      return new Report(Verdict.PASSED, "", List.of());
    }

    public boolean archivable() {
      return verdict == Verdict.PASSED;
    }
  }
}
