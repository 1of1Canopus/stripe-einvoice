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
}
