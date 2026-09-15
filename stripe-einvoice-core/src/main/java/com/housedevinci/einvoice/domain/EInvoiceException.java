package com.housedevinci.einvoice.domain;

import java.util.Objects;

/**
 * Every failure this module raises, carrying a stable {@link #code()}.
 *
 * <p>The message is built from constants and field <em>names</em>. It never quotes a buyer value, a
 * secret, or a row's free text: an exception is a log line, and a log line is a copy that outlives
 * the document (D-22, checklist lines 45 and 56).
 */
public class EInvoiceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;

  public EInvoiceException(String code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code, "code");
  }

  public EInvoiceException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
  }

  public final String code() {
    return code;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + code + "] " + getMessage();
  }
}
