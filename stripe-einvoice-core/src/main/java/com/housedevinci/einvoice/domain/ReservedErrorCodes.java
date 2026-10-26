package com.housedevinci.einvoice.domain;

import java.util.Map;

/**
 * Codes an approved plan has already given a meaning, before any code tree defines them (D-ORPE-8).
 *
 * <p>A stable code is a public promise: a host alerts on it. Two meanings for one code break that
 * promise at the first release that ships both. The Pro edition's codes live in plans until they
 * are built, so a free constant could take one of them and no duplicate check over the free code
 * table alone would notice. This list is that check's other half: a test fails when a value in
 * {@link ErrorCodes} equals a code here under another name. When the Pro code exists, its own build
 * asserts that each of its codes is on this list.
 */
public final class ReservedErrorCodes {

  /** Code to name, as the approved plan names it. */
  public static final Map<String, String> RESERVED =
      Map.of(
          "DEI-214", "EVENT_AWAITING_KIND",
          "DEI-215", "OPERATOR_CLOSED",
          "DEI-216", "RELEASE_REQUIRED");

  private ReservedErrorCodes() {}
}
