package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.Percentage;
import com.housedevinci.einvoice.domain.Totals;
import java.time.Instant;
import java.util.List;

/** Fixture invoices. Synthetic parties and identifiers only, never anything real (D-21). */
public final class TestInvoices {

  public static final Instant FINALIZED_AT = Instant.parse("2026-01-15T23:30:00Z");

  private TestInvoices() {}

  public static SourceInvoice finalised(String invoiceId) {
    return finalised(invoiceId, "Buyer Cooperative", "paid");
  }

  public static SourceInvoice finalised(String invoiceId, String buyerName, String status) {
    return new SourceInvoice(
        invoiceId,
        "STRIPE-0001",
        "",
        true,
        "eur",
        status,
        FINALIZED_AT,
        new SourceInvoice.SourceParty(
            buyerName,
            "buyer@example.invalid",
            "1 Example Street",
            "",
            "75001",
            "Example City",
            "FR",
            "FR00000000000"),
        List.of(
            new SourceInvoice.SourceLine("il_1", "One month of service", "txr_20", 10_000, 12_000)),
        List.of(new Totals.Bucket("txr_20", Percentage.of("20"), false, 2_000)),
        10_000,
        2_000,
        12_000,
        true);
  }

  /** Finalisation has not happened yet: an invoice.paid that overtook its invoice.finalized. */
  public static SourceInvoice draft(String invoiceId) {
    SourceInvoice finalised = finalised(invoiceId);
    return new SourceInvoice(
        finalised.id(),
        finalised.number(),
        finalised.accountId(),
        finalised.livemode(),
        finalised.currency(),
        "draft",
        null,
        finalised.buyer(),
        finalised.lines(),
        finalised.taxBuckets(),
        finalised.subtotalMinor(),
        finalised.taxMinor(),
        finalised.totalMinor(),
        true);
  }

  /** The scalars agree and one bucket is a cent out: the defect only a breakdown check sees. */
  public static SourceInvoice offByOneCentInOneBucket(String invoiceId) {
    SourceInvoice good = finalised(invoiceId);
    return new SourceInvoice(
        good.id(),
        good.number(),
        good.accountId(),
        good.livemode(),
        good.currency(),
        good.status(),
        good.finalizedAt(),
        good.buyer(),
        good.lines(),
        List.of(new Totals.Bucket("txr_20", Percentage.of("20"), false, 1_999)),
        10_000,
        1_999,
        11_999,
        true);
  }

  public static SourceInvoice testMode(String invoiceId) {
    SourceInvoice live = finalised(invoiceId);
    return new SourceInvoice(
        live.id(),
        live.number(),
        live.accountId(),
        false,
        live.currency(),
        live.status(),
        live.finalizedAt(),
        live.buyer(),
        live.lines(),
        live.taxBuckets(),
        live.subtotalMinor(),
        live.taxMinor(),
        live.totalMinor(),
        true);
  }
}
