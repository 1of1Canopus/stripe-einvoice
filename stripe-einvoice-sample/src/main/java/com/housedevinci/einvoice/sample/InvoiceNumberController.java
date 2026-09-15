package com.housedevinci.einvoice.sample;

import com.housedevinci.einvoice.autoconfigure.IssuanceNumberingService;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.SeriesReport;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The host application's endpoints, not the module's.
 *
 * <p>The module ships no HTTP surface: it is a library and cannot authenticate anyone. Everything
 * here is the sample's own, behind the sample's own HTTP Basic, and that is the division a real
 * host keeps too - above all for voiding a number, which is a privileged accounting operation.
 */
@RestController
class InvoiceNumberController {

  private final IssuanceNumberingService numbering;

  InvoiceNumberController(IssuanceNumberingService numbering) {
    this.numbering = numbering;
  }

  /**
   * @param finalizedAt Stripe's own {@code status_transitions.finalized_at} for this invoice. It is
   *     a parameter rather than a clock reading on purpose: BT-2 decides the VAT period, and it
   *     belongs to the invoice, not to the moment this endpoint happened to be called.
   */
  @PostMapping("/invoices/{stripeInvoiceId}/number")
  ResponseEntity<NumberView> allocate(
      @PathVariable String stripeInvoiceId,
      @RequestParam String stripeNumber,
      @RequestParam Instant finalizedAt) {
    Issuance issuance = numbering.allocate(stripeInvoiceId, "", stripeNumber, finalizedAt);
    return ResponseEntity.ok(
        new NumberView(
            issuance.legalNumber().value(),
            issuance.legalNumber().counter(),
            issuance.state().name()));
  }

  @GetMapping("/invoices/{stripeInvoiceId}/number")
  ResponseEntity<NumberView> find(@PathVariable String stripeInvoiceId) {
    return numbering
        .find(stripeInvoiceId)
        .map(
            issuance ->
                ResponseEntity.ok(
                    new NumberView(
                        issuance.legalNumber().value(),
                        issuance.legalNumber().counter(),
                        issuance.state().name())))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** Every number allocated in the series that {@code onDate} falls in, with its disposition. */
  @GetMapping("/series/{onDate}")
  ResponseEntity<ReportView> report(@PathVariable Instant onDate) {
    SeriesReport report = numbering.report(onDate);
    return ResponseEntity.ok(
        new ReportView(
            report.seriesKey().series(),
            report.seriesKey().fiscalYear(),
            report.openCount(),
            report.contiguous(),
            report.lines().stream()
                .map(
                    line ->
                        new LineView(
                            line.counter(),
                            line.legalNumber(),
                            line.state().name(),
                            line.reason().orElse(""),
                            line.issuedAt(),
                            line.allocatedAt()))
                .toList()));
  }

  record NumberView(String legalNumber, long counter, String state) {}

  record ReportView(
      String series, int fiscalYear, long open, boolean contiguous, List<LineView> lines) {}

  record LineView(
      long counter,
      String legalNumber,
      String state,
      String reason,
      Instant issuedAt,
      Instant allocatedAt) {}
}
