package com.housedevinci.einvoice.adapter.validation;

import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.domain.EInvoiceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link DocumentValidator} for the two UBL profiles: the vendored UBL 2.1 schema, then the
 * profile's full schematron chain, over the <b>exact bytes that will be archived</b>.
 *
 * <p>Three properties this class exists to hold, each from the checklist:
 *
 * <ul>
 *   <li><b>Line 15.</b> What is validated is the byte array the caller is about to hash and store,
 *       never a re-serialisation of a model. The unit of work hands us those bytes and archives the
 *       same array.
 *   <li><b>Lines 18, 57 and 65.</b> A chain that could not run reports {@code NOT_EVALUATED} with
 *       the reason in its rule id - no XSLT 2.0 processor, a timeout, an output bound, an artefact
 *       that no longer hashes to its record. The unit of work refuses on every one of them. There
 *       is no path here that reports {@code PASSED} without having executed the rules.
 *   <li><b>Line 45.</b> The findings this port returns are <b>rule identifiers and severities
 *       only</b>. Some rules' own assertion text interpolates content from the document being
 *       judged, and those findings are persisted, rendered in an operator view and read by whoever
 *       has the logs. The full text is reachable through {@link #validateInDetail} for a caller
 *       that has decided, explicitly, that it may see document content.
 * </ul>
 *
 * <p><b>The severity threshold.</b> {@code fatal}, {@code error} and {@code warning} make a
 * document invalid; {@code information} does not. A warning from the German CIUS is a real defect -
 * the wrong specification identifier arrives as one - and this module writes every field itself, so
 * there is no reason for it to emit one. The one rule this threshold currently lets through is
 * {@code BR-DE-TMP-32}, an information-flag suggestion that an invoice state a delivery date; this
 * edition does not carry one, and says so in the documents page rather than silencing the finding.
 */
public final class En16931DocumentValidator implements DocumentValidator, AutoCloseable {

  /** The rule id reported when no chain could run at all. */
  public static final String NOT_EVALUATED_NO_PROCESSOR = "EN16931-NO-XSLT-PROCESSOR";

  /** The rule id reported when a chain started and did not finish. */
  public static final String NOT_EVALUATED_INCOMPLETE = "EN16931-NOT-EVALUATED";

  private final UblProfile profile;
  private final XsdValidator xsd = new XsdValidator();
  private final SchematronValidator schematron;

  /**
   * @param profile the profile whose chain is run
   * @param processorClassName the XSLT 2.0 processor class name
   * @param timeout the wall-clock bound on one stylesheet run
   * @param concurrency how many validations may run at once
   */
  public En16931DocumentValidator(
      UblProfile profile, String processorClassName, Duration timeout, int concurrency) {
    this.profile = java.util.Objects.requireNonNull(profile, "profile");
    this.schematron = new SchematronValidator(processorClassName, timeout, concurrency);
  }

  /** The vendored stylesheets this profile's chain runs, in order. */
  public List<String> chain() {
    return switch (profile) {
      case XRECHNUNG_UBL ->
          List.of(VendoredArtefacts.EN16931_UBL_XSLT, VendoredArtefacts.XRECHNUNG_UBL_XSLT);
      case PEPPOL_BIS_UBL ->
          List.of(VendoredArtefacts.PEPPOL_CEN_UBL_XSLT, VendoredArtefacts.PEPPOL_BIS_UBL_XSLT);
    };
  }

  @Override
  public Report validate(byte[] bytes, DocumentInput input) {
    Detail detail = validateInDetail(bytes, false);
    List<String> identifiers = new ArrayList<>();
    for (SchematronValidator.Finding finding : detail.findings()) {
      // Identifier and severity only. The rule's own text can interpolate document content.
      identifiers.add(finding.ruleId() + " (" + finding.flag() + ")");
    }
    return new Report(detail.verdict(), detail.ruleId(), List.copyOf(identifiers));
  }

  /**
   * The same run, with the rules' own assertion text.
   *
   * <p><b>The text may quote content from the document being judged</b>, which on an invoice means
   * a buyer's data. It is deliberately not what the port returns, not what is persisted and not
   * what is logged; a caller that wants it is choosing to look.
   *
   * @param creditNote whether the bytes are a UBL {@code CreditNote} rather than an {@code Invoice}
   */
  public Detail validateInDetail(byte[] bytes, boolean creditNote) {
    if (bytes == null || bytes.length == 0) {
      return new Detail(
          Verdict.FAILED,
          "XSD-PARSE",
          List.of(
              new SchematronValidator.Finding(
                  "XSD-PARSE", "fatal", "there are no bytes to validate")));
    }
    List<XsdValidator.Finding> schemaFindings;
    try {
      schemaFindings = xsd.validate(bytes, creditNote);
    } catch (EInvoiceException refused) {
      return notEvaluated(refused);
    }
    if (!schemaFindings.isEmpty()) {
      List<SchematronValidator.Finding> findings = new ArrayList<>();
      for (XsdValidator.Finding finding : schemaFindings) {
        findings.add(new SchematronValidator.Finding(finding.ruleId(), "fatal", finding.message()));
      }
      // The schematron is deliberately not run: over a document that is not structurally UBL it
      // reports rules that never matched anything, and a report of rules that did not match is
      // not a report that they passed.
      return new Detail(Verdict.FAILED, schemaFindings.get(0).ruleId(), findings);
    }

    List<SchematronValidator.Finding> all = new ArrayList<>();
    for (String artefact : chain()) {
      try {
        all.addAll(schematron.validate(bytes, artefact));
      } catch (EInvoiceException notEvaluated) {
        return notEvaluated(notEvaluated);
      }
    }
    for (SchematronValidator.Finding finding : all) {
      if (finding.fatal()) {
        return new Detail(Verdict.FAILED, finding.ruleId(), List.copyOf(all));
      }
    }
    return new Detail(Verdict.PASSED, "", List.copyOf(all));
  }

  /**
   * Every way a chain can fail to reach a verdict, reported as {@code NOT_EVALUATED} with the
   * reason in the rule id. Never {@code FAILED} - that would say the document is wrong, and we do
   * not know that - and never {@code PASSED}.
   */
  private Detail notEvaluated(EInvoiceException cause) {
    String ruleId =
        com.housedevinci.einvoice.domain.ErrorCodes.XSLT_PROCESSOR_MISSING.equals(cause.code())
            ? NOT_EVALUATED_NO_PROCESSOR
            : NOT_EVALUATED_INCOMPLETE;
    return new Detail(
        Verdict.NOT_EVALUATED,
        ruleId,
        List.of(new SchematronValidator.Finding(ruleId, "fatal", cause.getMessage())));
  }

  /** A verdict with the rules' own text beside it. */
  public record Detail(
      Verdict verdict, String ruleId, List<SchematronValidator.Finding> findings) {}

  /** True when an XSLT 2.0 processor is present, for a startup check that says so out loud. */
  public boolean processorAvailable() {
    return schematron.processorAvailable();
  }

  /** The profile this validator judges under. */
  public UblProfile profile() {
    return profile;
  }

  @Override
  public void close() {
    schematron.close();
  }
}
