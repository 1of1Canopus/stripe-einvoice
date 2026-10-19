package com.housedevinci.einvoice.application;

import java.util.Optional;

/**
 * Reads the document-level dates out of the exact bytes that will be archived (D-PR36-6). It only
 * reads: it never compares, and it knows nothing about the allocation. The comparison is {@link
 * DocumentDatesRule}, which is not replaceable.
 *
 * <p>The unit of work calls this after the validator and before the archive, whatever renderer and
 * validator are wired, so a host validator cannot skip the date check. The module's own reader is
 * {@code com.housedevinci.einvoice.adapter.validation.UblDocumentDates}. A host that passes any
 * other reader to {@link IssuanceUnitOfWork} gets a WARN at every startup, because the guarantee
 * then rests on that reader.
 *
 * <p>A reader must not throw: a document it cannot read is {@link Readability#UNREADABLE}. If one
 * throws anyway, the unit of work refuses the document as not evaluated.
 */
public interface DocumentDates {

  FoundDates read(byte[] bytes);

  /** Whether the dates could be read without doubt. */
  enum Readability {
    /** Every element was found at most once, at the document level. */
    READ,
    /** Not a document this reader understands: another root, a parse error, a {@code DOCTYPE}. */
    UNREADABLE,
    /** An element the rule compares appears more than once, so there is no single answer. */
    AMBIGUOUS
  }

  /**
   * What the document says, as written. The texts are never trimmed and never parsed, so {@code "
   * 2026-01-10"} and {@code "2026-01-10+14:00"} stay unequal to {@code "2026-01-10"}.
   *
   * @param readability whether the rest of the record means anything
   * @param issueDate BT-2, {@code /*}{@code /cbc:IssueDate}
   * @param taxPointDate whether BT-7 ({@code cbc:TaxPointDate}) is present at the document level
   * @param delivery whether a document-level {@code cac:Delivery} is present
   * @param deliveryDate BT-72, {@code cac:Delivery/cbc:ActualDeliveryDate}
   * @param period whether a document-level {@code cac:InvoicePeriod} (BG-14) is present
   * @param periodStart BT-73, {@code cac:InvoicePeriod/cbc:StartDate}
   * @param periodEnd BT-74, {@code cac:InvoicePeriod/cbc:EndDate}
   * @param periodCode whether BT-8 ({@code cac:InvoicePeriod/cbc:DescriptionCode}) is present
   */
  record FoundDates(
      Readability readability,
      Optional<String> issueDate,
      boolean taxPointDate,
      boolean delivery,
      Optional<String> deliveryDate,
      boolean period,
      Optional<String> periodStart,
      Optional<String> periodEnd,
      boolean periodCode) {

    public FoundDates {
      readability = readability == null ? Readability.UNREADABLE : readability;
      issueDate = issueDate == null ? Optional.empty() : issueDate;
      deliveryDate = deliveryDate == null ? Optional.empty() : deliveryDate;
      periodStart = periodStart == null ? Optional.empty() : periodStart;
      periodEnd = periodEnd == null ? Optional.empty() : periodEnd;
    }

    public static FoundDates unreadable() {
      return of(Readability.UNREADABLE);
    }

    public static FoundDates ambiguous() {
      return of(Readability.AMBIGUOUS);
    }

    private static FoundDates of(Readability readability) {
      return new FoundDates(
          readability,
          Optional.empty(),
          false,
          false,
          Optional.empty(),
          false,
          Optional.empty(),
          Optional.empty(),
          false);
    }
  }
}
