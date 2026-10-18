package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.IssueDating;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rule EINVOICE-DATES (D-PR36-4, D-PR36-6): a document is archivable only when its dates are
 * exactly the ones the allocator recorded under the series lock, and it carries no date the
 * allocation did not give.
 *
 * <p>It refuses, with {@code FAILED}:
 *
 * <ul>
 *   <li>a VAT point date BT-7 or a VAT point date code BT-8: no allocation gives either, and both
 *       move the VAT point (D-PR36-9, design review R6);
 *   <li>a {@code cac:Delivery} when the allocation has no supply date, and a {@code
 *       cac:InvoicePeriod} when it has no period;
 *   <li>a BT-2 that is not the allocated issue date, text for text;
 *   <li>a missing or different BT-72 when the allocation has a supply date, and a missing or
 *       different BT-73 or BT-74 when it has a period.
 * </ul>
 *
 * <p>A document whose dates could not be read without doubt is {@code NOT_EVALUATED}, which the
 * unit of work also refuses. Example: numbered on 10 January, BT-2 {@code 2026-01-03}: {@code
 * FAILED}, rule {@code EINVOICE-DATES}, nothing archived.
 *
 * <p>Final, JDK and {@code domain} only, and called by the unit of work itself, so no bean and no
 * subclass can replace it.
 */
public final class DocumentDatesRule {

  /** The rule id recorded on the issuance row and the inbound event when the rule refuses. */
  public static final String RULE_ID = "EINVOICE-DATES";

  private DocumentDatesRule() {}

  /**
   * @param found what the document says; {@code null} is not evaluated
   * @param dating what the allocator recorded; never null on any path of the unit of work
   */
  public static DocumentValidator.Report check(DocumentDates.FoundDates found, IssueDating dating) {
    if (found == null || dating == null) {
      return refused(DocumentValidator.Verdict.NOT_EVALUATED, "the dates were not read");
    }
    if (found.readability() != DocumentDates.Readability.READ) {
      return refused(
          DocumentValidator.Verdict.NOT_EVALUATED,
          "the dates could not be read without doubt (" + found.readability() + ")");
    }
    List<String> problems = new ArrayList<>();
    if (found.taxPointDate()) {
      problems.add("a VAT point date (BT-7) that no allocation gives");
    }
    if (found.periodCode()) {
      problems.add("a VAT point date code (BT-8) that no allocation gives");
    }
    if (!same(found.issueDate(), Optional.of(dating.issueDate().toString()))) {
      problems.add("an issue date (BT-2) other than the allocated one");
    }
    Optional<String> supply = dating.supplyDate().map(Object::toString);
    if (supply.isPresent()) {
      if (!found.delivery() || !same(found.deliveryDate(), supply)) {
        problems.add("a delivery date (BT-72) other than the recorded one");
      }
    } else if (found.delivery()) {
      problems.add("a delivery (BG-13) the allocation did not give");
    }
    if (dating.supplyPeriod().isPresent()) {
      Optional<String> start = dating.supplyPeriod().map(p -> p.start().toString());
      Optional<String> end = dating.supplyPeriod().map(p -> p.end().toString());
      if (!found.period() || !same(found.periodStart(), start) || !same(found.periodEnd(), end)) {
        problems.add("an invoicing period (BG-14) other than the recorded one");
      }
    } else if (found.period()) {
      problems.add("an invoicing period (BG-14) the allocation did not give");
    }
    if (problems.isEmpty()) {
      return DocumentValidator.Report.passed();
    }
    return refused(DocumentValidator.Verdict.FAILED, String.join("; ", problems));
  }

  private static boolean same(Optional<String> found, Optional<String> expected) {
    return found.isPresent() && found.equals(expected);
  }

  private static DocumentValidator.Report refused(DocumentValidator.Verdict verdict, String why) {
    // Rule id and a fixed sentence only: no value from the document (checklist line 45).
    return new DocumentValidator.Report(verdict, RULE_ID, List.of(RULE_ID + " (fatal): " + why));
  }
}
