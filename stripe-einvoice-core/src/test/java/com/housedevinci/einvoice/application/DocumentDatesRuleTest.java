package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.application.DocumentDates.FoundDates;
import com.housedevinci.einvoice.application.DocumentDates.Readability;
import com.housedevinci.einvoice.domain.IssueDating;
import com.housedevinci.einvoice.domain.SupplyPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** EINVOICE-DATES as a pure function: what the document says against what the allocator gave. */
class DocumentDatesRuleTest {

  private static final LocalDate ISSUE = LocalDate.parse("2026-01-10");
  private static final LocalDate SUPPLY = LocalDate.parse("2026-01-03");

  private static IssueDating withSupply() {
    return new IssueDating(
        ISSUE,
        Instant.parse("2026-01-10T09:00:00Z"),
        Optional.of(SUPPLY),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static IssueDating withPeriod() {
    return new IssueDating(
        ISSUE,
        Instant.parse("2026-01-10T09:00:00Z"),
        Optional.empty(),
        Optional.of(new SupplyPeriod(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"))),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static FoundDates delivered(String issue, String delivery) {
    return new FoundDates(
        Readability.READ,
        Optional.ofNullable(issue),
        false,
        delivery != null,
        Optional.ofNullable(delivery),
        false,
        Optional.empty(),
        Optional.empty(),
        false);
  }

  private static FoundDates periodic(String start, String end, boolean code, boolean delivery) {
    return new FoundDates(
        Readability.READ,
        Optional.of("2026-01-10"),
        false,
        delivery,
        Optional.empty(),
        true,
        Optional.ofNullable(start),
        Optional.ofNullable(end),
        code);
  }

  private static DocumentValidator.Verdict verdict(FoundDates found, IssueDating dating) {
    DocumentValidator.Report report = DocumentDatesRule.check(found, dating);
    if (!report.archivable()) {
      assertThat(report.ruleId()).isEqualTo("EINVOICE-DATES");
    }
    return report.verdict();
  }

  @Test
  void the_allocated_dates_pass() {
    assertThat(verdict(delivered("2026-01-10", "2026-01-03"), withSupply()))
        .isEqualTo(DocumentValidator.Verdict.PASSED);
    assertThat(verdict(periodic("2026-01-01", "2026-01-31", false, false), withPeriod()))
        .isEqualTo(DocumentValidator.Verdict.PASSED);
  }

  @Test
  void another_issue_date_or_none_fails() {
    assertThat(verdict(delivered("2026-01-03", "2026-01-03"), withSupply()))
        .isEqualTo(DocumentValidator.Verdict.FAILED);
    assertThat(verdict(delivered(null, "2026-01-03"), withSupply()))
        .isEqualTo(DocumentValidator.Verdict.FAILED);
  }

  @Test
  void a_padded_or_zoned_date_is_another_text() {
    assertThat(verdict(delivered(" 2026-01-10", "2026-01-03"), withSupply()))
        .isEqualTo(DocumentValidator.Verdict.FAILED);
    assertThat(verdict(delivered("2026-01-10+14:00", "2026-01-03"), withSupply()))
        .isEqualTo(DocumentValidator.Verdict.FAILED);
  }

  @Test
  void a_missing_or_moved_delivery_date_fails() {
    assertThat(verdict(delivered("2026-01-10", null), withSupply()))
        .isEqualTo(DocumentValidator.Verdict.FAILED);
    assertThat(verdict(delivered("2026-01-10", "2025-12-03"), withSupply()))
        .isEqualTo(DocumentValidator.Verdict.FAILED);
  }

  @Test
  void a_dateless_delivery_container_does_not_carry_the_supply_date() {
    FoundDates emptyDelivery =
        new FoundDates(
            Readability.READ,
            Optional.of("2026-01-10"),
            false,
            true,
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            false);
    assertThat(verdict(emptyDelivery, withSupply())).isEqualTo(DocumentValidator.Verdict.FAILED);
  }

  @Test
  void an_ungiven_delivery_container_is_refused() {
    assertThat(verdict(periodic("2026-01-01", "2026-01-31", false, true), withPeriod()))
        .isEqualTo(DocumentValidator.Verdict.FAILED);
  }

  @Test
  void an_ungiven_period_is_refused() {
    FoundDates both =
        new FoundDates(
            Readability.READ,
            Optional.of("2026-01-10"),
            false,
            true,
            Optional.of("2026-01-03"),
            true,
            Optional.empty(),
            Optional.empty(),
            false);
    assertThat(verdict(both, withSupply())).isEqualTo(DocumentValidator.Verdict.FAILED);
  }

  @Test
  void a_moved_or_half_period_fails() {
    assertThat(verdict(periodic("2025-12-01", "2026-01-31", false, false), withPeriod()))
        .isEqualTo(DocumentValidator.Verdict.FAILED);
    assertThat(verdict(periodic("2026-01-01", null, false, false), withPeriod()))
        .isEqualTo(DocumentValidator.Verdict.FAILED);
  }

  @Test
  void a_vat_point_date_or_its_code_fails() {
    FoundDates taxPoint =
        new FoundDates(
            Readability.READ,
            Optional.of("2026-01-10"),
            true,
            true,
            Optional.of("2026-01-03"),
            false,
            Optional.empty(),
            Optional.empty(),
            false);
    assertThat(verdict(taxPoint, withSupply())).isEqualTo(DocumentValidator.Verdict.FAILED);
    assertThat(verdict(periodic("2026-01-01", "2026-01-31", true, false), withPeriod()))
        .isEqualTo(DocumentValidator.Verdict.FAILED);
  }

  @Test
  void an_unreadable_ambiguous_or_absent_reading_is_not_evaluated() {
    assertThat(verdict(FoundDates.unreadable(), withSupply()))
        .isEqualTo(DocumentValidator.Verdict.NOT_EVALUATED);
    assertThat(verdict(FoundDates.ambiguous(), withSupply()))
        .isEqualTo(DocumentValidator.Verdict.NOT_EVALUATED);
    assertThat(verdict(null, withSupply())).isEqualTo(DocumentValidator.Verdict.NOT_EVALUATED);
  }

  @Test
  void a_null_input_is_not_evaluated() {
    assertThat(verdict(delivered("2026-01-10", "2026-01-03"), null))
        .isEqualTo(DocumentValidator.Verdict.NOT_EVALUATED);
  }

  @Test
  void a_record_with_null_parts_is_unreadable_and_empty() {
    FoundDates found = new FoundDates(null, null, false, false, null, false, null, null, false);
    assertThat(found.readability()).isEqualTo(Readability.UNREADABLE);
    assertThat(found.issueDate()).isEmpty();
    assertThat(found.deliveryDate()).isEmpty();
    assertThat(found.periodStart()).isEmpty();
    assertThat(found.periodEnd()).isEmpty();
  }

  @Test
  void the_findings_carry_no_document_value() {
    DocumentValidator.Report report =
        DocumentDatesRule.check(delivered("2026-01-03", "2025-11-30"), withSupply());
    assertThat(report.findings()).noneMatch(f -> f.contains("2026") || f.contains("2025"));
  }
}
