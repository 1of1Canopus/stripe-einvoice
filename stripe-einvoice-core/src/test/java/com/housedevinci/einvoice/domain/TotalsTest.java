package com.housedevinci.einvoice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * We recompute, then compare, and never adjust (D-12, checklist line 5). A check that recomputes
 * the scalars and ignores the per-category breakdown is not a check, so the breakdown is where most
 * of these tests are.
 */
class TotalsTest {

  private static Totals.Line line(String id, String rate, long net, long gross) {
    return new Totals.Line(id, rate, net, gross);
  }

  private static Totals.Bucket bucket(String rate, String pct, boolean inclusive, long tax) {
    return new Totals.Bucket(rate, Percentage.of(pct), inclusive, tax);
  }

  @Test
  void an_exclusive_tax_invoice_that_balances_is_accepted() {
    Totals.Input input =
        new Totals.Input(
            List.of(line("il_1", "txr_20", 10_000, 12_000), line("il_2", "txr_20", 2_500, 3_000)),
            List.of(bucket("txr_20", "20", false, 2_500)),
            12_500,
            2_500,
            15_000);
    assertThatCode(() -> Totals.reconcile(input)).doesNotThrowAnyException();
  }

  @Test
  void a_totals_mismatch_of_one_cent_in_one_bucket_is_refused() {
    // The document would still balance on its scalars and still validate: this is the bug class
    // that only a breakdown comparison can see.
    Totals.Input input =
        new Totals.Input(
            List.of(line("il_1", "txr_20", 10_000, 12_000), line("il_2", "txr_5", 2_000, 2_100)),
            List.of(bucket("txr_20", "20", false, 2_000), bucket("txr_5", "5", false, 101)),
            12_000,
            2_101,
            14_101);
    assertThatThrownBy(() -> Totals.reconcile(input))
        .isInstanceOf(TotalsMismatchException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.TOTALS_MISMATCH);
  }

  @Test
  void the_refusal_names_the_fields_and_carries_the_values_out_of_band() {
    Totals.Input input =
        new Totals.Input(
            List.of(line("il_1", "txr_20", 10_000, 12_000)),
            List.of(bucket("txr_20", "20", false, 2_000)),
            9_999,
            2_000,
            11_999);
    assertThatThrownBy(() -> Totals.reconcile(input))
        .isInstanceOf(TotalsMismatchException.class)
        .hasMessageContaining("subtotal")
        .hasMessageNotContaining("9999")
        .hasMessageNotContaining("10000")
        .satisfies(
            e -> {
              TotalsMismatchException mismatch = (TotalsMismatchException) e;
              assertThat(mismatch.field()).isEqualTo("subtotal");
              assertThat(mismatch.recomputed()).isEqualTo(10_000);
              assertThat(mismatch.upstream()).isEqualTo(9_999);
            });
  }

  @Test
  void an_inclusive_tax_invoice_reconciles_or_is_refused() {
    // Gross 12 000 at 20 % inclusive: tax = 12000 * 20 / 120 = 2 000, net 10 000.
    Totals.Input good =
        new Totals.Input(
            List.of(line("il_1", "txr_20i", 10_000, 12_000)),
            List.of(bucket("txr_20i", "20", true, 2_000)),
            10_000,
            2_000,
            12_000);
    assertThatCode(() -> Totals.reconcile(good)).doesNotThrowAnyException();

    Totals.Input off =
        new Totals.Input(
            List.of(line("il_1", "txr_20i", 10_000, 12_000)),
            List.of(bucket("txr_20i", "20", true, 1_999)),
            10_000,
            1_999,
            11_999);
    assertThatThrownBy(() -> Totals.reconcile(off)).isInstanceOf(TotalsMismatchException.class);
  }

  @Test
  void rounding_is_half_up_per_bucket_and_never_per_line() {
    // Two lines of 3 333 at 19 %: per line 633.27 -> 633 each = 1 266; per bucket 6 666 * 19 % =
    // 1 266.54 -> 1 267. The bucket is the unit, and one of the two answers is wrong.
    Totals.Input input =
        new Totals.Input(
            List.of(line("il_1", "txr_19", 3_333, 3_966), line("il_2", "txr_19", 3_333, 3_966)),
            List.of(bucket("txr_19", "19", false, 1_267)),
            6_666,
            1_267,
            7_933);
    assertThatCode(() -> Totals.reconcile(input)).doesNotThrowAnyException();
  }

  @Test
  void a_line_whose_tax_rate_has_no_bucket_is_refused_by_name() {
    Totals.Input input =
        new Totals.Input(
            List.of(line("il_1", "txr_20", 10_000, 12_000)), List.of(), 10_000, 0, 10_000);
    assertThatThrownBy(() -> Totals.reconcile(input))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("tax_rates")
        .extracting("code")
        .isEqualTo(ErrorCodes.MAPPING_INCOMPLETE);
  }

  @Test
  void a_total_that_is_not_subtotal_plus_tax_is_refused_rather_than_explained() {
    // A discount or a customer credit balance lands here. 0.1.0 refuses it by name instead of
    // issuing a document whose total it cannot account for.
    Totals.Input input =
        new Totals.Input(
            List.of(line("il_1", "txr_20", 10_000, 12_000)),
            List.of(bucket("txr_20", "20", false, 2_000)),
            10_000,
            2_000,
            11_000);
    assertThatThrownBy(() -> Totals.reconcile(input))
        .isInstanceOf(TotalsMismatchException.class)
        .hasMessageContaining("total");
  }

  @Test
  void a_zero_or_negative_total_is_refused_because_a_380_carries_positive_amounts() {
    Totals.Input negative =
        new Totals.Input(
            List.of(line("il_1", "txr_0", -10_000, -10_000)),
            List.of(bucket("txr_0", "0", false, 0)),
            -10_000,
            0,
            -10_000);
    assertThatThrownBy(() -> Totals.reconcile(negative))
        .isInstanceOf(EInvoiceException.class)
        .hasMessageContaining("credit note");
  }

  @Test
  void an_invoice_with_no_line_is_refused_rather_than_summing_to_zero() {
    Totals.Input empty = new Totals.Input(List.of(), List.of(), 0, 0, 0);
    assertThatThrownBy(() -> Totals.reconcile(empty))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.MAPPING_INCOMPLETE);
  }

  @Test
  void a_line_sum_that_would_overflow_a_long_is_refused_not_wrapped() {
    Totals.Input input =
        new Totals.Input(
            List.of(
                line("il_1", "txr_0", Long.MAX_VALUE, Long.MAX_VALUE), line("il_2", "txr_0", 1, 1)),
            List.of(bucket("txr_0", "0", false, 0)),
            0,
            0,
            1);
    assertThatThrownBy(() -> Totals.reconcile(input))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.AMOUNT_OUT_OF_BOUNDS);
  }
}
