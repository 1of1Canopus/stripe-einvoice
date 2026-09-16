package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** The authoritative source, under the test's control rather than Stripe's. */
public final class FakeStripeSource implements StripeInvoiceSource {

  public static final String PINNED = "2026-03-31.clover";

  private final Map<String, SourceInvoice> invoices = new LinkedHashMap<>();
  private final AtomicInteger fetches = new AtomicInteger();
  private Throwable failure;

  public FakeStripeSource with(SourceInvoice invoice) {
    invoices.put(invoice.id(), invoice);
    return this;
  }

  /**
   * Stripe is down, rate-limited, or answering 5xx: an outage, never a verdict about the sale.
   *
   * @param failure a {@code RuntimeException} for an outage probe, or a test's own {@code Error}
   *     to simulate a crash that no ordinary exception handling reaches
   */
  public void breakWith(Throwable failure) {
    this.failure = failure;
  }

  public void heal() {
    this.failure = null;
  }

  public int fetches() {
    return fetches.get();
  }

  @Override
  public SourceInvoice fetchInvoice(String invoiceId) {
    fetches.incrementAndGet();
    throwIfSet();
    SourceInvoice invoice = invoices.get(invoiceId);
    if (invoice == null) {
      throw new EInvoiceException(
          ErrorCodes.STRIPE_UNAVAILABLE, "the invoice could not be read from the Stripe API");
    }
    return invoice;
  }

  private void throwIfSet() {
    if (failure instanceof RuntimeException re) {
      throw re;
    }
    if (failure instanceof Error err) {
      throw err;
    }
  }

  @Override
  public java.util.List<String> finalisedInvoiceIds(java.time.Instant from, java.time.Instant to) {
    throwIfSet();
    return invoices.values().stream()
        .filter(SourceInvoice::finalised)
        .filter(
            invoice -> !invoice.finalizedAt().isBefore(from) && !invoice.finalizedAt().isAfter(to))
        .map(SourceInvoice::id)
        .toList();
  }

  @Override
  public String pinnedApiVersion() {
    return PINNED;
  }
}
