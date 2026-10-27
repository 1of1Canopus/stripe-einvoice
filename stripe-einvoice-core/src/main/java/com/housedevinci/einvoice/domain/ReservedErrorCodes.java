package com.housedevinci.einvoice.domain;

import static java.util.Map.entry;

import java.util.Map;
import java.util.Objects;

/**
 * Codes a reviewed design has already given a meaning, before or after a code tree defines them
 * (D-ORPE-8, D-PR49-3).
 *
 * <p>A stable code is a public promise: a host alerts on it. Two meanings for one code break that
 * promise at the first release that ships both. A design page reserves codes long before the code
 * that raises them is built, so a free constant could take one of them and no duplicate check over
 * the free code table alone would notice. This list is that check's other half: a test fails when a
 * value in {@link ErrorCodes} equals a code here under another name, or when a name here is defined
 * there under another code. A code is reserved here the day its design is approved; a comment in a
 * design page is not a reservation. When the code is built, the entry stays: it then pins the name
 * to the code.
 */
public final class ReservedErrorCodes {

  /** One reservation: the name the design gives the code, and the design that gives it. */
  public record Reservation(String name, String source) {

    public Reservation {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(source, "source");
    }
  }

  private static final String PRO_SLICE_1 = "Pro slice 1 error-code table";
  private static final String ONE_RUN = "design: one run per event (D-PR49-1)";
  private static final String RENDER_MISMATCH = "design: resumed render hash mismatch (D-RTR-5)";
  private static final String MARKER = "design: archive root marker (D-PR49-3)";
  private static final String DISPOSED = "design: burned number archive orphan (D-RTR-4)";
  private static final String RUNNING_LIMIT = "design: running row time limit (QUESTIONS 29)";

  /** Code to reservation. */
  public static final Map<String, Reservation> RESERVED =
      Map.ofEntries(
          pro("DEI-123", "DOCUMENT_TYPE_NOT_ISSUABLE"),
          pro("DEI-124", "DOCUMENT_TYPE_MISMATCH"),
          pro("DEI-125", "KIND_REGISTRY_CONFLICT"),
          pro("DEI-126", "ISOLATION_REFUSED"),
          pro("DEI-127", "ISSUE_DATE_OUT_OF_ORDER"),
          pro("DEI-128", "CREDIT_TARGET_INVALID"),
          pro("DEI-129", "NUMBER_IN_FLIGHT_WITHOUT_KIND"),
          pro("DEI-214", "EVENT_AWAITING_KIND"),
          pro("DEI-215", "OPERATOR_CLOSED"),
          pro("DEI-216", "RELEASE_REQUIRED"),
          // D-PR49-1, decided on QUESTIONS 50: reserved here too, so a branch cut before this
          // constant existed cannot give DEI-218 a second meaning without T15 failing.
          entry("DEI-218", new Reservation("RUN_IN_HOST_TRANSACTION", ONE_RUN)),
          entry("DEI-244", new Reservation("RENDER_NOT_REPRODUCIBLE", RENDER_MISMATCH)),
          entry("DEI-246", new Reservation("ARCHIVE_UNMARKED", MARKER)),
          // Not DEI-247: that is ROW_LOCK_RELEASE_FAILED since PR 42. DEI-24x is full and DEI-25x
          // is validation, so the foreign-archive refusal joins DEI-230, the archived-bytes code.
          entry("DEI-232", new Reservation("ARCHIVE_FOREIGN", MARKER)),
          entry("DEI-266", new Reservation("INBOUND_CEILING_REACHED", RUNNING_LIMIT)),
          entry("DEI-281", new Reservation("RECON_OBJECT_FOR_DISPOSED_NUMBER", DISPOSED)),
          entry("DEI-282", new Reservation("RECON_STUCK_INBOUND", RUNNING_LIMIT)),
          pro("DEI-600", "CREDIT_TARGET_NOT_ISSUED"),
          pro("DEI-601", "CREDIT_TARGET_UNKNOWN"),
          pro("DEI-602", "CREDIT_TARGET_TAMPERED"),
          pro("DEI-603", "CREDIT_TARGET_UNREACHABLE"),
          pro("DEI-604", "CREDIT_EXCEEDS_INVOICE"),
          pro("DEI-605", "CREDIT_TAX_ROUNDING_EXCEEDS"),
          pro("DEI-606", "CREDIT_UNKNOWN_STRIPE_CREDITS"),
          pro("DEI-607", "CREDIT_CURRENCY_MISMATCH"),
          pro("DEI-608", "CREDIT_RATE_MISMATCH"),
          pro("DEI-609", "CREDIT_LINE_UNRESOLVED"),
          pro("DEI-610", "CREDIT_ZERO_TOTAL"),
          pro("DEI-611", "CREDIT_DATE_BEFORE_TARGET"),
          pro("DEI-620", "LICENCE_NOT_VALID"),
          pro("DEI-621", "LICENCE_IN_GRACE"),
          pro("DEI-622", "LICENCE_EXPIRED"),
          pro("DEI-623", "LICENCE_PARKED_AWAITING_RELEASE"),
          pro("DEI-624", "LICENCE_KEY_INVALID"),
          pro("DEI-630", "PRO_SCHEMA_VERSION_UNREADABLE"));

  private static Map.Entry<String, Reservation> pro(String code, String name) {
    return entry(code, new Reservation(name, PRO_SLICE_1));
  }

  private ReservedErrorCodes() {}
}
