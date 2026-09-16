package com.housedevinci.einvoice.adapter.stripe;

import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Percentage;
import com.housedevinci.einvoice.domain.Totals;
import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Address;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.model.StripeCollection;
import com.stripe.model.TaxRate;
import com.stripe.param.InvoiceLineItemListParams;
import com.stripe.param.InvoiceListParams;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The authoritative read of an invoice, through the Stripe SDK (D-02).
 *
 * <p><b>The payload is never a source.</b> Everything a document carries is read here, for the
 * object id the event named, and the collections are paginated to exhaustion: an invoice with more
 * than one page of lines, mapped from a webhook payload's embedded first page, produces a document
 * that balances on its scalars while itemising a fraction of the sale - invisible to schema, to
 * schematron and to a small golden file.
 *
 * <p><b>The pinned version is the SDK's own.</b> The SDK sends {@code Stripe-Version} on every
 * request, and the model classes are generated for exactly that version, so a pin that names a
 * different one would be a claim this code cannot honour. The pin is therefore {@link
 * #SDK_API_VERSION}, it is refused at construction if configuration names another, and an event
 * whose {@code api_version} is off it is recorded and refused rather than deserialised leniently.
 * {@code deserializeUnsafe} appears nowhere in this module.
 *
 * <p><b>Read-only.</b> Only {@code retrieve} and {@code list} are called, on invoices, line items
 * and tax rates; a test asserts no mutating method of the SDK is referenced anywhere in this
 * module, and the documented API key is a restricted read key.
 *
 * <p><b>Only frozen fields</b> (I-02): {@code customer_name}, {@code customer_email}, {@code
 * customer_address}, {@code customer_tax_ids} and each line's own {@code description}. The live
 * {@code customer} object is not read and is not expanded: a buyer who corrects their address in
 * March must not change what a January invoice says.
 */
public final class StripeApiInvoiceSource implements StripeInvoiceSource {

  /** The version this SDK's models are generated for, and therefore the only honest pin. */
  public static final String SDK_API_VERSION = Stripe.API_VERSION;

  /** 100 lines a page: an invoice past this many lines is not one we map silently. */
  static final int PAGE_SIZE = 100;

  static final int MAX_LINE_PAGES = 200;

  private final StripeClient client;
  private final String pinnedApiVersion;
  private final Duration listLookback;

  public StripeApiInvoiceSource(StripeClient client, String pinnedApiVersion) {
    this(client, pinnedApiVersion, Duration.ofDays(30));
  }

  /**
   * @param listLookback how far before the reconciliation window to start listing. Stripe filters
   *     the list by {@code created}, not by finalisation, and a subscription invoice can sit as a
   *     draft for weeks before it is finalised - so the lookback is what decides whether such an
   *     invoice is visible to the sweep at all. Documented, bounded, and a property.
   */
  public StripeApiInvoiceSource(
      StripeClient client, String pinnedApiVersion, Duration listLookback) {
    this.client = Objects.requireNonNull(client, "client");
    this.pinnedApiVersion = Objects.requireNonNull(pinnedApiVersion, "pinnedApiVersion");
    this.listLookback = Objects.requireNonNull(listLookback, "listLookback");
    if (!SDK_API_VERSION.equals(pinnedApiVersion)) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.stripe.api-version names a version this SDK cannot speak. The models are"
              + " generated for one version and the SDK sends that version on every request, so a"
              + " pin naming another would be a claim the code cannot keep. Either upgrade the"
              + " module or remove the override.");
    }
  }

  @Override
  public String pinnedApiVersion() {
    return pinnedApiVersion;
  }

  @Override
  public SourceInvoice fetchInvoice(String invoiceId) {
    try {
      Invoice invoice = client.invoices().retrieve(invoiceId);
      List<InvoiceLineItem> lines = allLines(invoiceId);
      return map(invoice, lines);
    } catch (StripeException e) {
      throw unavailable(e);
    }
  }

  @Override
  public List<String> finalisedInvoiceIds(Instant from, Instant to) {
    List<String> ids = new ArrayList<>();
    try {
      String startingAfter = null;
      for (int page = 0; page < MAX_LINE_PAGES; page++) {
        InvoiceListParams.Builder params =
            InvoiceListParams.builder()
                .setLimit((long) PAGE_SIZE)
                .setCreated(
                    InvoiceListParams.Created.builder()
                        .setGte(from.minus(listLookback).getEpochSecond())
                        .setLte(to.getEpochSecond())
                        .build());
        if (startingAfter != null) {
          params.setStartingAfter(startingAfter);
        }
        StripeCollection<Invoice> page1 = client.invoices().list(params.build());
        List<Invoice> data = page1.getData();
        for (Invoice invoice : data) {
          Instant finalizedAt = finalizedAt(invoice);
          if (finalizedAt != null
              && !finalizedAt.isBefore(from)
              && !finalizedAt.isAfter(to)
              && !"draft".equals(invoice.getStatus())) {
            ids.add(invoice.getId());
          }
        }
        if (!Boolean.TRUE.equals(page1.getHasMore()) || data.isEmpty()) {
          return List.copyOf(ids);
        }
        startingAfter = data.get(data.size() - 1).getId();
      }
      throw new EInvoiceException(
          ErrorCodes.TRUNCATED_COLLECTION,
          "the invoice list did not read to exhaustion inside the page bound, so this sweep's"
              + " result would be a partial answer presented as a complete one");
    } catch (StripeException e) {
      throw unavailable(e);
    }
  }

  /**
   * Every line, to exhaustion. A residual "more pages" after the bound is a refusal, never a
   * partial document.
   */
  private List<InvoiceLineItem> allLines(String invoiceId) throws StripeException {
    List<InvoiceLineItem> lines = new ArrayList<>();
    String startingAfter = null;
    for (int page = 0; page < MAX_LINE_PAGES; page++) {
      InvoiceLineItemListParams.Builder params =
          InvoiceLineItemListParams.builder().setLimit((long) PAGE_SIZE);
      if (startingAfter != null) {
        params.setStartingAfter(startingAfter);
      }
      StripeCollection<InvoiceLineItem> collection =
          client.invoices().lineItems().list(invoiceId, params.build());
      List<InvoiceLineItem> data = collection.getData();
      lines.addAll(data);
      if (!Boolean.TRUE.equals(collection.getHasMore())) {
        return lines;
      }
      if (data.isEmpty()) {
        break;
      }
      startingAfter = data.get(data.size() - 1).getId();
    }
    throw new EInvoiceException(
        ErrorCodes.TRUNCATED_COLLECTION,
        "this invoice's lines still report more pages after the page bound. A document built from"
            + " part of them would balance on its totals while itemising part of the sale, so it is"
            + " refused (stripe field: lines.has_more)");
  }

  private SourceInvoice map(Invoice invoice, List<InvoiceLineItem> lines) throws StripeException {
    Map<String, TaxRate> rates = new LinkedHashMap<>();
    List<Totals.Bucket> buckets = new ArrayList<>();
    if (invoice.getTotalTaxes() != null) {
      for (Invoice.TotalTax tax : invoice.getTotalTaxes()) {
        String rateId = rateIdOf(tax);
        TaxRate rate = rate(rates, rateId);
        buckets.add(
            new Totals.Bucket(
                rateId,
                percentageOf(rate),
                "inclusive".equals(tax.getTaxBehavior()),
                required(tax.getAmount(), "total_taxes.amount")));
      }
    }

    List<SourceInvoice.SourceLine> mapped = new ArrayList<>();
    for (InvoiceLineItem line : lines) {
      long amount = required(line.getAmount(), "lines.data.amount");
      long tax = 0;
      long taxable = amount;
      String rateId = "";
      if (line.getTaxes() != null && !line.getTaxes().isEmpty()) {
        for (InvoiceLineItem.Tax lineTax : line.getTaxes()) {
          tax += lineTax.getAmount() == null ? 0 : lineTax.getAmount();
          if (lineTax.getTaxableAmount() != null) {
            taxable = lineTax.getTaxableAmount();
          }
          if (lineTax.getTaxRateDetails() != null
              && lineTax.getTaxRateDetails().getTaxRate() != null) {
            rateId = lineTax.getTaxRateDetails().getTaxRate();
          }
        }
      }
      if (rateId.isEmpty()) {
        // A line with no tax rate cannot be recomputed and cannot carry an EN 16931 category with
        // an exemption reason, so it is refused by name rather than issued as an untaxed line.
        throw new EInvoiceException(
            ErrorCodes.MAPPING_INCOMPLETE,
            "a line carries no tax rate, so no EN 16931 tax category can be established for it"
                + " (stripe field: lines.data.taxes.tax_rate_details.tax_rate)");
      }
      mapped.add(
          new SourceInvoice.SourceLine(
              line.getId(), line.getDescription(), rateId, taxable, taxable + tax));
    }

    Address address = invoice.getCustomerAddress();
    SourceInvoice.SourceParty buyer =
        new SourceInvoice.SourceParty(
            invoice.getCustomerName(),
            invoice.getCustomerEmail(),
            address == null ? null : address.getLine1(),
            address == null ? null : address.getLine2(),
            address == null ? null : address.getPostalCode(),
            address == null ? null : address.getCity(),
            address == null ? null : address.getCountry(),
            taxIdOf(invoice));

    return new SourceInvoice(
        invoice.getId(),
        invoice.getNumber(),
        // The connected account is the event's field and the configuration's, never a field of
        // the invoice: routing may not be decided by anything the object itself carries (D-01).
        "",
        Boolean.TRUE.equals(invoice.getLivemode()),
        invoice.getCurrency(),
        invoice.getStatus(),
        finalizedAt(invoice),
        buyer,
        mapped,
        buckets,
        required(invoice.getSubtotal(), "subtotal"),
        totalTax(invoice),
        required(invoice.getTotal(), "total"),
        true);
  }

  private static String rateIdOf(Invoice.TotalTax tax) {
    if (tax.getTaxRateDetails() == null || tax.getTaxRateDetails().getTaxRate() == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "a tax bucket names no tax rate, so its category cannot be established (stripe field:"
              + " total_taxes.tax_rate_details.tax_rate)");
    }
    return tax.getTaxRateDetails().getTaxRate();
  }

  private TaxRate rate(Map<String, TaxRate> cache, String rateId) throws StripeException {
    TaxRate cached = cache.get(rateId);
    if (cached != null) {
      return cached;
    }
    TaxRate rate = client.taxRates().retrieve(rateId);
    cache.put(rateId, rate);
    return rate;
  }

  private static Percentage percentageOf(TaxRate rate) {
    if (rate == null || rate.getPercentage() == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "a tax rate carries no percentage (stripe field: tax_rate.percentage)");
    }
    // Bounded on magnitude and scale before it multiplies anything (checklist lines 1, 2, 4).
    return new Percentage(rate.getPercentage());
  }

  private static long totalTax(Invoice invoice) {
    if (invoice.getTotalTaxes() == null) {
      return 0;
    }
    long total = 0;
    for (Invoice.TotalTax tax : invoice.getTotalTaxes()) {
      total = Math.addExact(total, required(tax.getAmount(), "total_taxes.amount"));
    }
    return total;
  }

  private static String taxIdOf(Invoice invoice) {
    if (invoice.getCustomerTaxIds() == null || invoice.getCustomerTaxIds().isEmpty()) {
      return "";
    }
    // The tax ids frozen onto the invoice, never the live customer's current ones (I-02).
    return invoice.getCustomerTaxIds().get(0).getValue();
  }

  private static Instant finalizedAt(Invoice invoice) {
    if (invoice.getStatusTransitions() == null
        || invoice.getStatusTransitions().getFinalizedAt() == null) {
      return null;
    }
    return Instant.ofEpochSecond(invoice.getStatusTransitions().getFinalizedAt());
  }

  private static long required(Long value, String field) {
    if (value == null) {
      throw new EInvoiceException(
          ErrorCodes.MAPPING_INCOMPLETE,
          "the invoice carries no value for a field a document needs (stripe field: "
              + field
              + ")");
    }
    return value;
  }

  /**
   * An outage, a rate limit or a 5xx. Never a verdict about the sale: the caller records a
   * retryable terminal and the sweeper comes back.
   *
   * <p>The message carries the status code and nothing else - a Stripe error body can quote a
   * customer's own field value back at us.
   */
  private static EInvoiceException unavailable(StripeException e) {
    return new EInvoiceException(
        ErrorCodes.STRIPE_UNAVAILABLE,
        "the Stripe API could not be read (status " + e.getStatusCode() + ")",
        e);
  }
}
