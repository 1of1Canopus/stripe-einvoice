package com.housedevinci.einvoice.autoconfigure.issuance;

import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.application.StripeInvoiceSource;
import com.housedevinci.einvoice.autoconfigure.TestPostgres;
import com.housedevinci.einvoice.domain.Percentage;
import com.housedevinci.einvoice.domain.Totals;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A host application for the starter's end-to-end tests: the module's beans, a real PostgreSQL, and
 * the three ports a host has to supply until the EN 16931 writers ship.
 */
@Configuration
@EnableAutoConfiguration
public class IssuanceTestApp {

  public static final String SELLER = "acme";
  public static final String PINNED_VERSION = "2026-03-31.clover";
  public static final Instant FINALIZED_AT = Instant.parse("2026-01-15T23:30:00Z");

  @Bean(destroyMethod = "")
  DataSource dataSource() {
    return TestPostgres.dataSource();
  }

  @Bean
  DocumentRenderer renderer() {
    return input ->
        new DocumentRenderer.RenderedDocument(
            ("<Invoice><ID>"
                    + input.legalNumber().value()
                    + "</ID><IssueDate>"
                    + input.issueDate()
                    + "</IssueDate><SourceId>"
                    + input.invoice().id()
                    + "</SourceId></Invoice>")
                .getBytes(StandardCharsets.UTF_8),
            "xml",
            "test");
  }

  @Bean
  DocumentValidator validator() {
    return (bytes, input) -> DocumentValidator.Report.passed();
  }

  @Bean
  RecordingStripeSource stripeSource() {
    return new RecordingStripeSource();
  }

  /** The authoritative source, under the test's control. */
  public static final class RecordingStripeSource implements StripeInvoiceSource {

    private final Map<String, SourceInvoice> invoices = new LinkedHashMap<>();
    private final AtomicInteger fetches = new AtomicInteger();

    public RecordingStripeSource() {
      add("in_1");
    }

    public void add(String invoiceId) {
      invoices.put(invoiceId, invoice(invoiceId));
    }

    public int fetches() {
      return fetches.get();
    }

    @Override
    public SourceInvoice fetchInvoice(String invoiceId) {
      fetches.incrementAndGet();
      SourceInvoice invoice = invoices.get(invoiceId);
      if (invoice == null) {
        throw new com.housedevinci.einvoice.domain.EInvoiceException(
            com.housedevinci.einvoice.domain.ErrorCodes.STRIPE_UNAVAILABLE,
            "the invoice could not be read from the Stripe API");
      }
      return invoice;
    }

    @Override
    public List<String> finalisedInvoiceIds(Instant from, Instant to) {
      return List.copyOf(invoices.keySet());
    }

    @Override
    public String pinnedApiVersion() {
      return PINNED_VERSION;
    }

    private static SourceInvoice invoice(String invoiceId) {
      return new SourceInvoice(
          invoiceId,
          "STRIPE-0001",
          "",
          true,
          "eur",
          "paid",
          FINALIZED_AT,
          new SourceInvoice.SourceParty(
              "Buyer Cooperative",
              "buyer@example.invalid",
              "1 Example Street",
              "",
              "75001",
              "Example City",
              "FR",
              "FR68900000001"),
          List.of(
              new SourceInvoice.SourceLine(
                  "il_1", "One month of service", "txr_20", 1L, 10_000, 12_000)),
          List.of(new Totals.Bucket("txr_20", Percentage.of("20"), false, 2_000)),
          List.of(new SourceInvoice.SourceTaxTreatment("txr_20", "FR", "vat", "standard_rated")),
          10_000,
          2_000,
          12_000,
          true);
    }
  }
}
