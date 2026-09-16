package com.housedevinci.einvoice.domain.en16931;

/**
 * The per-field length bounds EN 16931 places on its text business terms (checklist line 7).
 *
 * <p>A field bound is part of screening, not a nicety: a buyer name long enough to exceed the
 * recipient's own limit fails at the recipient, days later, where nobody can fix it. Bounding it
 * here turns that into a typed refusal naming the field, at the moment the invoice is mapped.
 *
 * <p>The numbers below follow the EN 16931 semantic data model's maximum lengths for the
 * corresponding business terms; where the standard states no maximum, the bound is this module's
 * own, deliberately generous but finite, because "no maximum" and "unbounded" are not the same
 * thing when the value came from the internet.
 */
public final class BusinessTerms {

  /** BT-1 invoice number, BT-25 preceding invoice reference. */
  public static final int ID = 64;

  /** BT-10 buyer reference (a Leitweg-ID is 46 characters at most). */
  public static final int BUYER_REFERENCE = 64;

  /** BT-27, BT-44, BT-28, BT-45: party names and trading names. */
  public static final int PARTY_NAME = 200;

  /** BT-35, BT-36, BT-50, BT-51: address lines. */
  public static final int ADDRESS_LINE = 150;

  /** BT-37, BT-52: city. */
  public static final int CITY = 100;

  /** BT-38, BT-53: post code. */
  public static final int POST_CODE = 20;

  /** BT-41, BT-56: contact name. */
  public static final int CONTACT_NAME = 100;

  /** BT-42, BT-57: contact telephone. */
  public static final int TELEPHONE = 50;

  /** BT-43, BT-58: contact email. */
  public static final int EMAIL = 254;

  /** BT-31, BT-32, BT-48: VAT and tax registration identifiers. */
  public static final int TAX_IDENTIFIER = 40;

  /** BT-34, BT-49: electronic address. */
  public static final int ELECTRONIC_ADDRESS = 200;

  /** BT-153: item name. */
  public static final int ITEM_NAME = 200;

  /** BT-154: item description. */
  public static final int ITEM_DESCRIPTION = 1000;

  /** BT-22: invoice note. */
  public static final int NOTE = 1000;

  /** BT-120, BT-121: VAT exemption reason text. */
  public static final int EXEMPTION_REASON = 1000;

  /** BT-84, BT-85: payment account identifier and account name. */
  public static final int PAYMENT_ACCOUNT = 100;

  private BusinessTerms() {}
}
