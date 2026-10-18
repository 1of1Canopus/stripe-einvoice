package com.housedevinci.einvoice.adapter.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.xml.UblDocumentWriter;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.SourceInvoice;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.IssueDating;
import com.housedevinci.einvoice.domain.SupplyPeriod;
import com.housedevinci.einvoice.domain.en16931.EnInvoice;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * D-PS1-44: the supply date is never dropped. BT-72 or BG-14 is written under both free profiles,
 * the official validators pass, and an invoice that carries neither is refused, never written.
 */
class SupplyDateDocumentTest {

  private static final LocalDate FEB_1 = LocalDate.parse("2026-02-01");
  private static final LocalDate FEB_28 = LocalDate.parse("2026-02-28");

  private static DocumentInput withPeriod(DocumentInput base) {
    IssueDating d = base.dating();
    return new DocumentInput(
        base.unnumbered(),
        base.legalNumber(),
        base.seriesKey(),
        new IssueDating(
            d.issueDate(),
            d.allocatedAt(),
            Optional.empty(),
            Optional.of(new SupplyPeriod(FEB_1, FEB_28)),
            Optional.empty(),
            Optional.empty(),
            d.cause()));
  }

  private static En16931DocumentValidator.Detail detail(byte[] bytes, UblProfile profile) {
    try (En16931DocumentValidator validator =
        new En16931DocumentValidator(
            profile, SecureXml.DEFAULT_XSLT2_PROCESSOR, Duration.ofSeconds(60), 2)) {
      return validator.validateInDetail(bytes, false);
    }
  }

  @Test
  void t_supply_xrechnung_an_invoicing_period_is_written_and_kosit_passes() {
    byte[] bytes =
        new En16931DocumentRenderer(DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
            .render(withPeriod(DocumentFixtures.germanStandardRated()))
            .bytes();
    String xml = new String(bytes, StandardCharsets.UTF_8);

    assertThat(xml)
        .contains(
            "<cac:InvoicePeriod><cbc:StartDate>2026-02-01</cbc:StartDate>"
                + "<cbc:EndDate>2026-02-28</cbc:EndDate></cac:InvoicePeriod>")
        .doesNotContain("cac:Delivery");
    En16931DocumentValidator.Detail report = detail(bytes, UblProfile.XRECHNUNG_UBL);
    assertThat(report.findings()).isEmpty();
    assertThat(report.verdict())
        .isEqualTo(com.housedevinci.einvoice.application.DocumentValidator.Verdict.PASSED);
  }

  @Test
  void t_supply_peppol_the_delivery_date_and_the_period_both_pass_peppol() {
    for (DocumentInput input :
        List.of(
            DocumentFixtures.belgianStandardRated(),
            withPeriod(DocumentFixtures.belgianStandardRated()))) {
      byte[] bytes =
          new En16931DocumentRenderer(
                  DocumentFixtures.frenchSeller(), UblProfile.PEPPOL_BIS_UBL, null)
              .render(input)
              .bytes();
      assertThat(new String(bytes, StandardCharsets.UTF_8))
          .containsAnyOf("<cbc:ActualDeliveryDate>", "<cac:InvoicePeriod>");
      assertThat(detail(bytes, UblProfile.PEPPOL_BIS_UBL).verdict())
          .isEqualTo(com.housedevinci.einvoice.application.DocumentValidator.Verdict.PASSED);
    }
  }

  @Test
  void the_issue_date_written_is_the_numbering_day_and_the_delivery_date_the_supply_day() {
    DocumentInput base = DocumentFixtures.germanStandardRated();
    IssueDating d = base.dating();
    DocumentInput late =
        new DocumentInput(
            base.unnumbered(),
            base.legalNumber(),
            base.seriesKey(),
            new IssueDating(
                d.issueDate().plusDays(9),
                d.allocatedAt().plus(Duration.ofDays(9)),
                d.supplyDate(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                d.cause()));
    String xml =
        new String(
            new En16931DocumentRenderer(
                    DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
                .render(late)
                .bytes(),
            StandardCharsets.UTF_8);

    assertThat(xml).contains("<cbc:IssueDate>" + d.issueDate().plusDays(9) + "</cbc:IssueDate>");
    assertThat(xml)
        .contains(
            "<cbc:ActualDeliveryDate>"
                + d.supplyDate().orElseThrow()
                + "</cbc:ActualDeliveryDate>");
  }

  /**
   * M13 and M14: an invoice with neither BT-72 nor BG-14 is refused by the writer, never written.
   */
  @Test
  void t_supply_not_carried_the_writer_refuses_an_invoice_with_no_supply_date() {
    EnInvoice withSupply =
        new En16931DocumentRenderer(DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
            .model(DocumentFixtures.germanStandardRated());
    EnInvoice without =
        new EnInvoice(
            withSupply.number(),
            withSupply.issueDate(),
            withSupply.dueDate(),
            withSupply.typeCode(),
            withSupply.currency(),
            withSupply.buyerReference(),
            withSupply.note(),
            withSupply.precedingReference(),
            withSupply.precedingReferenceDate(),
            withSupply.upstreamNumber(),
            withSupply.seller(),
            withSupply.buyer(),
            withSupply.payment(),
            withSupply.lines(),
            withSupply.taxSubtotals(),
            withSupply.lineTotal(),
            withSupply.taxExclusiveTotal(),
            withSupply.taxTotal(),
            withSupply.taxInclusiveTotal(),
            withSupply.payableTotal());

    assertThatThrownBy(() -> new UblDocumentWriter(UblProfile.XRECHNUNG_UBL).write(without))
        .extracting("code")
        .isEqualTo(ErrorCodes.SUPPLY_DATE_NOT_CARRIED);
    assertThat(
            new En16931DocumentRenderer(
                    DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null)
                .carriesSupplyDate())
        .isTrue();
  }

  /** T-supply-mixed-periods: lines with different periods, or one without, give no period. */
  @Test
  void t_supply_mixed_periods_only_a_period_every_line_names_is_carried() {
    Instant a = Instant.parse("2026-02-01T00:00:00Z");
    Instant b = Instant.parse("2026-03-01T00:00:00Z");
    Instant c = Instant.parse("2026-04-01T00:00:00Z");
    SourceInvoice.SourceLine first =
        new SourceInvoice.SourceLine("il_1", "x", "txr", 1, 100, 120, a, b);
    SourceInvoice.SourceLine same =
        new SourceInvoice.SourceLine("il_2", "y", "txr", 1, 100, 120, a, b);
    SourceInvoice.SourceLine other =
        new SourceInvoice.SourceLine("il_3", "z", "txr", 1, 100, 120, b, c);
    SourceInvoice.SourceLine none = new SourceInvoice.SourceLine("il_4", "w", "txr", 1, 100, 120);

    assertThat(invoiceWith(List.of(first, same)).commonServicePeriod())
        .contains(new AllocationRequest.ServicePeriod(a, b));
    assertThat(invoiceWith(List.of(first, other)).commonServicePeriod()).isEmpty();
    assertThat(invoiceWith(List.of(first, none)).commonServicePeriod()).isEmpty();
    // A one-off item: Stripe sets start = end, which is not a service period.
    assertThat(
            invoiceWith(
                    List.of(new SourceInvoice.SourceLine("il_5", "v", "txr", 1, 100, 120, a, a)))
                .commonServicePeriod())
        .isEmpty();
  }

  private static SourceInvoice invoiceWith(List<SourceInvoice.SourceLine> lines) {
    SourceInvoice base = DocumentFixtures.germanStandardRated().invoice();
    return new SourceInvoice(
        base.id(),
        base.number(),
        base.accountId(),
        base.livemode(),
        base.currency(),
        base.status(),
        base.finalizedAt(),
        base.buyer(),
        lines,
        base.taxBuckets(),
        base.taxTreatments(),
        base.subtotalMinor(),
        base.taxMinor(),
        base.totalMinor(),
        true);
  }
}
