package com.housedevinci.einvoice.autoconfigure;

import java.util.List;
import java.util.Locale;

/** The tables this module owns, in one place, so no scan can be right about a subset of them. */
public final class EInvoiceTables {

  public static final String SERIES = "einvoice_series";
  public static final String ISSUANCE = "einvoice_issuance";
  public static final String ISSUANCE_EVENT = "einvoice_issuance_event";
  public static final String ISSUANCE_ANCHOR = "einvoice_issuance_anchor";

  /**
   * The durable inbound record. It is guarded here for the same reason as the ledger - a host
   * entity mapped over it would let Hibernate rewrite or delete rows this module's retry and
   * replay paths depend on - even though the table itself is purgeable by this module (I-07).
   */
  public static final String INBOUND_EVENT = "einvoice_inbound_event";

  /** The compliance findings list. Operator-facing, purgeable, and guarded like the rest. */
  public static final String FINDING = "einvoice_finding";

  public static final List<String> ALL =
      List.of(SERIES, ISSUANCE, ISSUANCE_EVENT, ISSUANCE_ANCHOR, INBOUND_EVENT, FINDING);

  private EInvoiceTables() {}

  /**
   * True when a raw mapping string addresses one of our tables.
   *
   * <p>The comparison is deliberately generous, and that is the point of it (N-03). {@code
   * EINVOICE_ISSUANCE}, {@code "einvoice_issuance"}, {@code public.einvoice_issuance} and an
   * unqualified name under a non-{@code public} search path are one table written four ways; a scan
   * that compares raw strings sees four tables and guards none of them. Quoting is stripped, a
   * schema qualifier is dropped, and the comparison is case-insensitive.
   *
   * <p>The one over-refusal this accepts, knowingly: a host table genuinely named {@code
   * "EINVOICE_ISSUANCE"} with quotes - a different physical table in PostgreSQL - is refused too.
   * Refusing to start, with a message that names the table and the reason, is the safe direction
   * for a ledger whose whole protection is that nothing else maps it.
   */
  public static boolean isOurs(String rawName) {
    if (rawName == null || rawName.isBlank()) {
      return false;
    }
    String name = rawName.strip();
    // A @Subselect entity's "table" is its SQL text: look inside it rather than past it.
    if (name.startsWith("(")) {
      String lower = name.toLowerCase(Locale.ROOT);
      return ALL.stream().anyMatch(table -> lower.contains(table));
    }
    int dot = name.lastIndexOf('.');
    if (dot >= 0) {
      name = name.substring(dot + 1);
    }
    name = name.replace("\"", "").replace("`", "").replace("[", "").replace("]", "").strip();
    String canonical = name.toLowerCase(Locale.ROOT);
    return ALL.contains(canonical);
  }
}
