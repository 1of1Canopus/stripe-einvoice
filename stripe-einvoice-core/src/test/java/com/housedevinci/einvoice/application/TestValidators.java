package com.housedevinci.einvoice.application;

import java.util.List;

/** The three verdicts a validator can reach, so each one's consequence is exercised. */
public final class TestValidators {

  private TestValidators() {}

  public static DocumentValidator passing() {
    return (bytes, input) -> DocumentValidator.Report.passed();
  }

  public static DocumentValidator failing(String ruleId) {
    return (bytes, input) ->
        new DocumentValidator.Report(
            DocumentValidator.Verdict.FAILED, ruleId, List.of("a rule failed"));
  }

  /** A rule that could not run. Never a pass by construction (checklist lines 18, 57, 65). */
  public static DocumentValidator notEvaluated(String ruleId) {
    return (bytes, input) ->
        new DocumentValidator.Report(
            DocumentValidator.Verdict.NOT_EVALUATED, ruleId, List.of("the schematron was absent"));
  }

  /**
   * A configuration that can never validate anything, ever - not this one invoice's problem, the
   * application's (D3-02). {@code canValidate()} says so before phase 1 runs; {@code validate()}
   * still reports {@code NOT_EVALUATED} for a caller that does not ask first.
   */
  public static DocumentValidator notEvaluatedNoProcessor(String ruleId) {
    return new DocumentValidator() {
      @Override
      public DocumentValidator.Report validate(byte[] bytes, DocumentInput input) {
        return new DocumentValidator.Report(
            DocumentValidator.Verdict.NOT_EVALUATED, ruleId, List.of("no XSLT 2.0 processor"));
      }

      @Override
      public boolean canValidate() {
        return false;
      }
    };
  }
}
