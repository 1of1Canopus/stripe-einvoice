package com.housedevinci.einvoice.application;

/**
 * The authoritative read of one invoice (D-02).
 *
 * <p>Every field that reaches a document comes from here, for the object id the event named, with
 * the API version pinned on the request and with every collection paginated <b>to exhaustion</b>. A
 * residual "more pages" after the last page is {@code DEI-204} and a refusal, never a partial
 * document.
 *
 * <p>Implementations are read-only. The documented Stripe key is a restricted key with read scopes
 * on invoices, customers, credit notes and tax rates; this module never mutates Stripe state, and a
 * test asserts no mutating SDK method is referenced.
 */
public interface StripeInvoiceSource {

  /**
   * @throws com.housedevinci.einvoice.domain.EInvoiceException {@code DEI-211} when Stripe is
   *     unreachable, rate-limited or answers 5xx - an outage, retried, never a verdict; {@code
   *     DEI-204} when a collection could not be read to exhaustion
   */
  SourceInvoice fetchInvoice(String invoiceId);

  /** The API version this source pins on every request, for the startup log and the skew check. */
  String pinnedApiVersion();
}
