package com.housedevinci.einvoice.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over UTF-8 text, for the unkeyed issuance chain and for content hashes. */
public final class Hashes {

  private Hashes() {}

  /** SHA-256 over the exact bytes of a document, which is what the archive key carries. */
  public static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes == null ? new byte[0] : bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** Refuses anything that is not 64 lower-case hex characters, by name and without echoing it. */
  public static String requireSha256Hex(String field, String value) {
    if (value == null || value.length() != 64) {
      throw new EInvoiceException(ErrorCodes.INVALID, field + " must be 64 hexadecimal characters");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
      if (!hex) {
        throw new EInvoiceException(
            ErrorCodes.INVALID, field + " must be lower-case hexadecimal (index " + i + ")");
      }
    }
    return value;
  }

  public static String sha256Hex(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
