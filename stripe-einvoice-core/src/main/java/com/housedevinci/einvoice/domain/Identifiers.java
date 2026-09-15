package com.housedevinci.einvoice.domain;

import java.nio.charset.StandardCharsets;

/**
 * Boundary validation for the identifiers that end up in a primary key, in a SQL bind parameter and
 * inside hashed material: seller id, series name, Stripe object ids.
 *
 * <p>The charset is deliberately narrow. An identifier is a key, not free text, and two identifiers
 * that differ only by whitespace or case would address one series while reading as two.
 */
public final class Identifiers {

  /** Bytes, not characters: these values are length-prefixed into the chain's canonical form. */
  public static final int MAX_BYTES = 255;

  private Identifiers() {}

  public static String validate(String kind, String value, int maxBytes) {
    if (value == null || value.isBlank()) {
      throw new EInvoiceException(ErrorCodes.INVALID, kind + " must not be null or blank");
    }
    if (!value.equals(value.strip())) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, kind + " must not start or end with whitespace");
    }
    int bytes = value.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > maxBytes) {
      throw new EInvoiceException(
          ErrorCodes.INVALID, kind + " is " + bytes + " bytes, max " + maxBytes);
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_'
              || c == '.'
              || c == ':'
              || c == '+';
      if (!ok) {
        // Reported by code point, never echoed: an identifier can carry a customer's own string.
        throw new EInvoiceException(
            ErrorCodes.INVALID,
            kind
                + " contains a character not allowed at index "
                + i
                + " (U+"
                + Integer.toHexString(c)
                + ")");
      }
    }
    return value;
  }

  public static String validate(String kind, String value) {
    return validate(kind, value, MAX_BYTES);
  }
}
