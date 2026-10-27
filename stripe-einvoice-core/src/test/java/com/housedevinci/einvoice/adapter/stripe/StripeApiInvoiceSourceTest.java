package com.housedevinci.einvoice.adapter.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Totals;
import com.stripe.StripeClient;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The authoritative read, measured on the wire against a local server that answers like Stripe. */
class StripeApiInvoiceSourceTest {

  private static final String INVOICE =
      """
      {"id":"in_1","object":"invoice","number":"AB-0001","currency":"eur","status":"paid",
       "livemode":true,"subtotal":10000,"total":12000,
       "customer_name":"Buyer Cooperative","customer_email":"buyer@example.invalid",
       "customer_address":{"line1":"1 Example Street","postal_code":"75001","city":"Example City",
                           "country":"FR"},
       "customer_tax_ids":[{"type":"eu_vat","value":"FR68900000001"}],
       "status_transitions":{"finalized_at":1768520000},
       "total_taxes":[{"amount":2000,"tax_behavior":"exclusive",
                       "tax_rate_details":{"tax_rate":"txr_20"}}]}
      """;

  private static final String RATE =
      "{\"id\":\"txr_20\",\"object\":\"tax_rate\",\"percentage\":20.0,\"inclusive\":false}";

  private static String linePage(String id, boolean hasMore) {
    return "{\"object\":\"list\",\"has_more\":"
        + hasMore
        + ",\"data\":[{\"id\":\""
        + id
        + "\",\"object\":\"line_item\",\"amount\":10000,\"description\":\"One month of service\","
        + "\"taxes\":[{\"amount\":2000,\"taxable_amount\":10000,"
        + "\"tax_rate_details\":{\"tax_rate\":\"txr_20\"}}]}]}";
  }

  private static StripeClient client(StripeStub stub) {
    return StripeClient.builder()
        .setApiKey("rk_test_notarealkey")
        .setApiBase(stub.baseUrl())
        .setMaxNetworkRetries(0)
        .build();
  }

  @Test
  void every_request_carries_the_pinned_api_version() {
    try (StripeStub stub = new StripeStub()) {
      stub.answer("/v1/invoices/in_1", INVOICE)
          .answer("/v1/invoices/in_1/lines", linePage("il_1", false))
          .answer("/v1/tax_rates/txr_20", RATE);

      new StripeApiInvoiceSource(client(stub), StripeApiInvoiceSource.SDK_API_VERSION)
          .fetchInvoice("in_1");

      assertThat(stub.versionHeaders())
          .isNotEmpty()
          .allSatisfy(
              header -> assertThat(header).isEqualTo(StripeApiInvoiceSource.SDK_API_VERSION));
    }
  }

  @Test
  void a_second_page_of_lines_is_fetched_to_exhaustion() {
    try (StripeStub stub = new StripeStub()) {
      stub.answer("/v1/invoices/in_1", INVOICE)
          .answer("/v1/invoices/in_1/lines", linePage("il_1", true))
          .answer("/v1/invoices/in_1/lines", linePage("il_2", false))
          .answer("/v1/tax_rates/txr_20", RATE);

      SourceInvoice invoice =
          new StripeApiInvoiceSource(client(stub), StripeApiInvoiceSource.SDK_API_VERSION)
              .fetchInvoice("in_1");

      assertThat(invoice.lines())
          .extracting(SourceInvoice.SourceLine::id)
          .containsExactly("il_1", "il_2");
      assertThat(stub.requests()).filteredOn(request -> request.contains("/lines")).hasSize(2);
      assertThat(stub.requests().get(2)).contains("starting_after=il_1");
    }
  }

  @Test
  void a_residual_has_more_is_refused_rather_than_mapped_as_a_partial_invoice() {
    try (StripeStub stub = new StripeStub()) {
      // A server that always says "there is more" is the shape of the bug this refuses: a document
      // that balances on its totals and itemises a fraction of the sale.
      stub.answer("/v1/invoices/in_1", INVOICE)
          .answer("/v1/invoices/in_1/lines", linePage("il_1", true))
          .answer("/v1/tax_rates/txr_20", RATE);

      assertThatThrownBy(
              () ->
                  new StripeApiInvoiceSource(client(stub), StripeApiInvoiceSource.SDK_API_VERSION)
                      .fetchInvoice("in_1"))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.TRUNCATED_COLLECTION);
    }
  }

  @Test
  void the_frozen_buyer_and_the_frozen_line_text_are_what_is_mapped() {
    try (StripeStub stub = new StripeStub()) {
      stub.answer("/v1/invoices/in_1", INVOICE)
          .answer("/v1/invoices/in_1/lines", linePage("il_1", false))
          .answer("/v1/tax_rates/txr_20", RATE);

      SourceInvoice invoice =
          new StripeApiInvoiceSource(client(stub), StripeApiInvoiceSource.SDK_API_VERSION)
              .fetchInvoice("in_1");

      assertThat(invoice.buyer().name()).isEqualTo("Buyer Cooperative");
      assertThat(invoice.buyer().taxId()).isEqualTo("FR68900000001");
      assertThat(invoice.lines().get(0).description()).isEqualTo("One month of service");
      assertThat(invoice.finalizedAt()).isEqualTo(Instant.ofEpochSecond(1768520000L));
      assertThat(invoice.finalised()).isTrue();
      // The live customer object was never asked for: nothing in the requests is /v1/customers.
      assertThat(stub.requests())
          .noneSatisfy(request -> assertThat(request).contains("/v1/customers"));
    }
  }

  @Test
  void the_recomputed_totals_of_a_mapped_invoice_reconcile() {
    try (StripeStub stub = new StripeStub()) {
      stub.answer("/v1/invoices/in_1", INVOICE)
          .answer("/v1/invoices/in_1/lines", linePage("il_1", false))
          .answer("/v1/tax_rates/txr_20", RATE);

      SourceInvoice invoice =
          new StripeApiInvoiceSource(client(stub), StripeApiInvoiceSource.SDK_API_VERSION)
              .fetchInvoice("in_1");

      Totals.reconcile(invoice.totals());
    }
  }

  @Test
  void a_stripe_outage_is_a_retryable_code_and_never_a_verdict() {
    try (StripeStub stub = new StripeStub()) {
      stub.answer("/v1/invoices/in_1", "{\"error\":{\"type\":\"api_error\"}}");
      stub.failWith(500);

      assertThatThrownBy(
              () ->
                  new StripeApiInvoiceSource(client(stub), StripeApiInvoiceSource.SDK_API_VERSION)
                      .fetchInvoice("in_1"))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.STRIPE_UNAVAILABLE);
    }
  }

  /**
   * A revoked or under-scoped key (design section 5): 401 and 403 do not improve with time, so they
   * carry their own code and the run stops. A rate limit and a server error stay retryable.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "401, DEI-219",
    "403, DEI-219",
    "429, DEI-211",
    "500, DEI-211",
    "404, DEI-211"
  })
  void a_refused_key_has_its_own_code_and_every_other_failure_stays_retryable(
      int status, String code) {
    try (StripeStub stub = new StripeStub()) {
      stub.answer(
          "/v1/invoices/in_1",
          "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"refused\"}}");
      stub.failWith(status);

      assertThatThrownBy(
              () ->
                  new StripeApiInvoiceSource(client(stub), StripeApiInvoiceSource.SDK_API_VERSION)
                      .fetchInvoice("in_1"))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(code);
    }
  }

  @Test
  void a_refused_key_on_the_reconciliation_list_has_its_own_code() {
    try (StripeStub stub = new StripeStub()) {
      stub.answer("/v1/invoices", "{\"error\":{\"type\":\"invalid_request_error\"}}");
      stub.failWith(401);

      assertThatThrownBy(
              () ->
                  new StripeApiInvoiceSource(client(stub), StripeApiInvoiceSource.SDK_API_VERSION)
                      .finalisedInvoiceIds(
                          java.time.Instant.parse("2026-01-01T00:00:00Z"),
                          java.time.Instant.parse("2026-01-02T00:00:00Z")))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo("DEI-219");
    }
  }

  @Test
  void the_reconciliation_list_reads_only_invoices_finalised_inside_the_window() {
    try (StripeStub stub = new StripeStub()) {
      stub.answer(
          "/v1/invoices",
          "{\"object\":\"list\",\"has_more\":false,\"data\":["
              + "{\"id\":\"in_inside\",\"object\":\"invoice\",\"status\":\"paid\","
              + "\"status_transitions\":{\"finalized_at\":1768520000}},"
              + "{\"id\":\"in_draft\",\"object\":\"invoice\",\"status\":\"draft\"},"
              + "{\"id\":\"in_outside\",\"object\":\"invoice\",\"status\":\"paid\","
              + "\"status_transitions\":{\"finalized_at\":1000000}}]}");

      java.util.List<String> ids =
          new StripeApiInvoiceSource(client(stub), StripeApiInvoiceSource.SDK_API_VERSION)
              .finalisedInvoiceIds(
                  Instant.ofEpochSecond(1768000000L), Instant.ofEpochSecond(1769000000L));

      assertThat(ids).containsExactly("in_inside");
    }
  }

  @Test
  void a_pin_this_sdk_cannot_speak_is_refused_at_construction() {
    try (StripeStub stub = new StripeStub()) {
      assertThatThrownBy(() -> new StripeApiInvoiceSource(client(stub), "2019-02-19"))
          .isInstanceOf(EInvoiceException.class)
          .extracting("code")
          .isEqualTo(ErrorCodes.CONFIG);
    }
  }

  @Test
  void a_line_with_no_tax_rate_is_refused_by_name_rather_than_issued_untaxed() {
    try (StripeStub stub = new StripeStub()) {
      stub.answer("/v1/invoices/in_1", INVOICE)
          .answer(
              "/v1/invoices/in_1/lines",
              "{\"object\":\"list\",\"has_more\":false,\"data\":[{\"id\":\"il_1\","
                  + "\"object\":\"line_item\",\"amount\":10000,\"description\":\"x\"}]}")
          .answer("/v1/tax_rates/txr_20", RATE);

      assertThatThrownBy(
              () ->
                  new StripeApiInvoiceSource(client(stub), StripeApiInvoiceSource.SDK_API_VERSION)
                      .fetchInvoice("in_1"))
          .isInstanceOf(EInvoiceException.class)
          .hasMessageContaining("tax_rate")
          .extracting("code")
          .isEqualTo(ErrorCodes.MAPPING_INCOMPLETE);
    }
  }

  @Test
  void nothing_this_adapter_does_is_a_write() {
    try (StripeStub stub = new StripeStub()) {
      stub.answer("/v1/invoices/in_1", INVOICE)
          .answer("/v1/invoices/in_1/lines", linePage("il_1", false))
          .answer("/v1/tax_rates/txr_20", RATE);

      StripeApiInvoiceSource source =
          new StripeApiInvoiceSource(
              client(stub), StripeApiInvoiceSource.SDK_API_VERSION, Duration.ofDays(30));
      source.fetchInvoice("in_1");

      assertThat(stub.requests()).allSatisfy(request -> assertThat(request).startsWith("GET "));
    }
  }
}
