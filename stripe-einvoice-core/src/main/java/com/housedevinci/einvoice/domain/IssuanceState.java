package com.housedevinci.einvoice.domain;

import java.util.Set;

/**
 * The state of one issuance row, as a plain enum with an explicit transition set. No Spring State
 * Machine, no sealed hierarchy: roughly forty lines is the whole mechanism, and the same values are
 * the {@code state} column's domain and the database trigger's transition table, so a transition is
 * refused in two independent places.
 *
 * <p><b>Which states live here.</b> An issuance row exists from the moment a number is allocated:
 * {@code NUMBERED} is its first state. The steps before a number exists - the event was received,
 * the invoice was fetched, the model was mapped - belong to the inbound event record, which arrives
 * with the issuance unit of work, and they are deliberately absent from this enum. A state column
 * that names a state nothing ever writes is a lie to the next reader (issuance design, I-05).
 *
 * <p><b>Retryable versus final terminals</b> (I-08) is declared per state, here, and never decided
 * at a call site: {@code FAILED_ARCHIVE} can improve with time and is re-picked by the sweeper up
 * to the retry ceiling; {@code FAILED_VALIDATION} cannot, and needs a human.
 */
public enum IssuanceState {

  /** A number is allocated and bound to one Stripe invoice. No document exists yet. */
  NUMBERED,

  /**
   * The bytes exist, were validated, and their hash and archive key are recorded: the row predicted
   * the object before the write, so an orphan object is an indexed query rather than a bucket walk.
   */
  ARCHIVING,

  /**
   * A legal document exists. Terminal, and the only state that means anything to a tax authority.
   */
  ISSUED,

  /**
   * The exact bytes did not pass schema or schematron. Final: retrying cannot change the outcome.
   */
  FAILED_VALIDATION,

  /** The archive write failed. Retryable until the retry ceiling, then a compliance finding. */
  FAILED_ARCHIVE,

  /**
   * The number will never carry a document, and why is recorded. Set only by an operator through a
   * privileged service method, with a mandatory screened reason and a chained event. Never
   * re-allocated: two documents could otherwise carry one number.
   */
  VOID_UNUSED;

  public Set<IssuanceState> allowedNext() {
    return switch (this) {
      case NUMBERED -> Set.of(ARCHIVING, FAILED_VALIDATION, FAILED_ARCHIVE, VOID_UNUSED);
      case ARCHIVING -> Set.of(ISSUED, FAILED_ARCHIVE, VOID_UNUSED);
      case FAILED_ARCHIVE -> Set.of(ARCHIVING, VOID_UNUSED);
      case FAILED_VALIDATION -> Set.of(VOID_UNUSED);
      case ISSUED, VOID_UNUSED -> Set.of();
    };
  }

  /** True when a sweeper may re-pick this terminal; false when it needs a human (I-08). */
  public boolean retryableTerminal() {
    return this == FAILED_ARCHIVE;
  }

  /** True while the number is allocated and has not reached a disposition. */
  public boolean open() {
    return this == NUMBERED || this == ARCHIVING || this == FAILED_ARCHIVE;
  }

  /** True when the number's disposition is settled and recorded in the chain. */
  public boolean disposed() {
    return this == ISSUED || this == VOID_UNUSED;
  }

  public IssuanceState transitionTo(IssuanceState next) {
    if (next == null) {
      throw new EInvoiceException(ErrorCodes.INVALID, "next state must not be null");
    }
    if (!allowedNext().contains(next)) {
      throw new EInvoiceException(
          ErrorCodes.ILLEGAL_TRANSITION,
          "an issuance in state " + name() + " cannot move to " + next.name());
    }
    return next;
  }

  public static IssuanceState of(String value) {
    for (IssuanceState state : values()) {
      if (state.name().equals(value)) {
        return state;
      }
    }
    throw new EInvoiceException(
        ErrorCodes.INVALID, "unknown issuance state in the state column: " + sanitise(value));
  }

  /** Only the shape of an unknown value is reported, never the value itself. */
  private static String sanitise(String value) {
    return value == null ? "null" : "<" + value.length() + " characters>";
  }
}
