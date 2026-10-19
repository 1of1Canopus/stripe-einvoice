package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.DocumentSeriesFixture.MARCH_10;
import static com.housedevinci.einvoice.adapter.jdbc.DocumentSeriesFixture.MARCH_3;
import static com.housedevinci.einvoice.adapter.jdbc.DocumentSeriesFixture.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Typed series, the corrected-invoice link and the series' latest issue date, against a real
 * PostgreSQL (credit-note series design S-01..S-12, credit-note slice A1, D-PS1-37, D-PS1-38).
 *
 * <p>Every probe here names the control it proves and is shown RED in the pull request with that
 * control removed. The credit-note path is reached through the store's package-private allocation,
 * the only door a credit-note number has in this edition.
 */
class CipherProbeDocumentSeriesTest {

  // ---------------------------------------------------------------- S-02, D-PS1-06, probe 7-7

  @Test
  void probe_free_core_refuses_to_allocate_a_type_it_cannot_render() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("notissuable");
    f.store.openSeries(f.credits(Mode.LIVE));
    long before = f.nextNumber("CN");

    assertThatThrownBy(() -> f.store.allocate(request(f.credits(Mode.LIVE), "cn_direct", MARCH_10)))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.DOCUMENT_TYPE_NOT_ISSUABLE);
    assertThat(f.nextNumber("CN")).isEqualTo(before).isEqualTo(1L);
    assertThat(f.rows()).isZero();
  }

  @Test
  void probe_an_unconfigured_seller_and_type_refuses_and_consumes_no_number() {
    String seller = PostgresSupport.freshSeller("untyped");
    JdbcIssuanceStore invoicesOnly =
        TestStores.store(
            PostgresSupport.dataSource(),
            DocumentSeriesFixture.CHAIN,
            Map.of(
                new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE),
                DocumentSeriesFixture.INVOICES),
            DocumentSeriesFixture.CLOCK);
    DocumentSeriesFixture f = new DocumentSeriesFixture(PostgresSupport.dataSource(), seller);
    Issuance target = f.issuedInvoice("in_untyped", MARCH_10);

    // No fallback from the credit-note type to the invoice series: the same series name, asked
    // for as a credit note, is not configured.
    var asCredit =
        new com.housedevinci.einvoice.domain.SeriesKey(
            seller, "INV", 2026, Mode.LIVE, DocumentTypeCode.CREDIT_NOTE);
    assertThatThrownBy(
            () ->
                invoicesOnly.allocateTyped(
                    request(asCredit, "cn_untyped", MARCH_10),
                    Optional.of(target.id()),
                    DocumentSeriesFixture.creditDate(asCredit, MARCH_10)))
        .extracting("code")
        .isEqualTo(ErrorCodes.SERIES_NOT_CONFIGURED);
    assertThat(f.nextNumber("INV")).isEqualTo(2L);
    assertThat(f.rows()).isEqualTo(1L);
  }

  @Test
  void probe_a_credit_note_never_advances_the_invoice_counter() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("ownseries");
    Issuance invoice = f.issuedInvoice("in_own", MARCH_10);
    Issuance credit = f.credit("cn_own", MARCH_10, invoice.id());

    assertThat(credit.legalNumber().value()).isEqualTo("CN-2026-000001");
    assertThat(credit.seriesKey().documentType()).isEqualTo(DocumentTypeCode.CREDIT_NOTE);
    assertThat(credit.correctsIssuanceId()).contains(invoice.id());
    assertThat(f.nextNumber("INV")).as("invoice counter, on the row itself").isEqualTo(2L);
    assertThat(f.nextNumber("CN")).isEqualTo(2L);
    // and a resume returns the same number, without moving anything
    assertThat(f.credit("cn_own", MARCH_10, invoice.id()).legalNumber())
        .isEqualTo(credit.legalNumber());
    assertThat(f.nextNumber("CN")).isEqualTo(2L);
  }

  // ---------------------------------------------------------------- corrects_issuance_id

  @Test
  void probe_a_credit_note_row_without_a_target_is_refused_by_the_database() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("notarget");
    assertThatThrownBy(() -> PostgresSupport.execute(directRow(f.seller, "381", "CN", "NULL")))
        .hasMessageContaining("einvoice_issuance_correction_shape");
  }

  @Test
  void probe_an_invoice_row_with_a_target_is_refused_by_the_database() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("invtarget");
    Issuance target = f.issuedInvoice("in_target", MARCH_10);
    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    directRow(f.seller, "380", "INV", Long.toString(target.id()))))
        .hasMessageContaining("einvoice_issuance_correction_shape");
  }

  @Test
  void the_allocator_refuses_a_credit_without_a_target_and_an_invoice_with_one() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("javashape");
    Issuance target = f.issuedInvoice("in_shape", MARCH_10);
    assertThatThrownBy(
            () ->
                f.store.allocateTyped(
                    request(f.credits(Mode.LIVE), "cn_none", MARCH_10),
                    Optional.empty(),
                    DocumentSeriesFixture.creditDate(f.credits(Mode.LIVE), MARCH_10)))
        .extracting("code")
        .isEqualTo(ErrorCodes.CREDIT_TARGET_INVALID);
    assertThatThrownBy(
            () ->
                f.store.allocateTyped(
                    request(f.invoices(Mode.LIVE), "in_with", MARCH_10),
                    Optional.of(target.id()),
                    Optional.empty()))
        .extracting("code")
        .isEqualTo(ErrorCodes.CREDIT_TARGET_INVALID);
    assertThat(f.nextNumber("INV")).isEqualTo(2L);
  }

  @Test
  void probe_the_corrected_invoice_link_and_the_type_are_immutable() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("immutable");
    Issuance a = f.issuedInvoice("in_a", MARCH_10);
    Issuance b = f.issuedInvoice("in_b", MARCH_10);
    Issuance credit = f.credit("cn_a", MARCH_10, a.id());

    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    "UPDATE einvoice_issuance SET corrects_issuance_id = "
                        + b.id()
                        + " WHERE id = "
                        + credit.id()))
        .hasMessageContaining("only state");
    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    "UPDATE einvoice_issuance SET document_type = '381', corrects_issuance_id = "
                        + a.id()
                        + " WHERE id = "
                        + b.id()))
        .hasMessageContaining("only state");
  }

  // ---------------------------------------------------------------- D-CNS-S12, probe 7-8

  @Test
  void probe_a_credit_note_against_a_number_with_no_document_is_refused() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("nodocument");
    Issuance numbered = f.invoice("in_numbered", MARCH_10);
    Issuance voided = f.invoice("in_voided", MARCH_10);
    f.store.voidUnused(new VoidRequest(f.seller, Mode.LIVE, "in_voided", "never sent", ""));
    Issuance burned = f.invoice("in_burned", MARCH_10);
    f.store.markFailed(
        f.seller, Mode.LIVE, "in_burned", IssuanceState.FAILED_VALIDATION, "BR-CO-10");

    for (Issuance target : new Issuance[] {numbered, voided, burned}) {
      assertTargetRefused(f, target.id());
    }
  }

  @Test
  void probe_a_credit_note_against_a_credit_note_is_refused() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("cnoncn");
    Issuance invoice = f.issuedInvoice("in_first", MARCH_10);
    Issuance credit = f.credit("cn_first", MARCH_10, invoice.id());
    f.issue(credit, Mode.LIVE);
    assertTargetRefused(f, credit.id());
  }

  @Test
  void probe_a_credit_note_against_another_seller_or_mode_is_refused() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("crossseller");
    DocumentSeriesFixture other = DocumentSeriesFixture.shared("crossseller-other");
    Issuance foreign = other.issuedInvoice("in_foreign", MARCH_10);
    Issuance testMode = f.issuedInvoice("in_testmode", MARCH_10, Mode.TEST);
    assertTargetRefused(f, foreign.id());
    assertTargetRefused(f, testMode.id());
  }

  @Test
  void probe_a_credit_note_against_an_invoice_whose_stored_hash_left_its_chain_is_refused() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("hashdrift");
    Issuance invoice = f.issuedInvoice("in_drift", MARCH_10);
    // A role that outranks the triggers rewrites the stored hash. The chained ISSUED event still
    // carries the original one, and the chain is the trusted side.
    PostgresSupport.execute(
        "ALTER TABLE einvoice_issuance DISABLE TRIGGER einvoice_issuance_guard");
    try {
      PostgresSupport.execute(
          "UPDATE einvoice_issuance SET document_sha256 = '"
              + "b".repeat(64)
              + "' WHERE id = "
              + invoice.id());
    } finally {
      PostgresSupport.execute(
          "ALTER TABLE einvoice_issuance ENABLE TRIGGER einvoice_issuance_guard");
    }
    assertTargetRefused(f, invoice.id());
  }

  @Test
  void probe_the_database_refuses_a_bad_target_for_every_writer() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("dbbound");
    DocumentSeriesFixture other = DocumentSeriesFixture.shared("dbbound-other");
    Issuance numbered = f.invoice("in_open", MARCH_10);
    Issuance foreign = other.issuedInvoice("in_foreign", MARCH_10);
    Issuance good = f.issuedInvoice("in_good", MARCH_10);

    for (Issuance bad : new Issuance[] {numbered, foreign}) {
      assertThatThrownBy(
              () ->
                  PostgresSupport.execute(
                      directRow(f.seller, "381", "CN", Long.toString(bad.id()))))
          .hasMessageContaining("a credit note may correct only an issued invoice");
    }
    // The same insert against a good target passes the guard: the guard refuses the target, not
    // the writer.
    PostgresSupport.execute(directRow(f.seller, "381", "CN", Long.toString(good.id())));
  }

  // ---------------------------------------------------------------- T9 and D-PS1-38

  @Test
  void probe_a_backdated_credit_behind_the_series_last_date_is_refused_before_numbering() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("backdated");
    Issuance invoice = f.issuedInvoice("in_bd", MARCH_3);
    f.credit("cn_later", MARCH_10, invoice.id());

    assertThatThrownBy(() -> f.credit("cn_earlier", MARCH_3, invoice.id()))
        .extracting("code")
        .isEqualTo(ErrorCodes.ISSUE_DATE_OUT_OF_ORDER);
    assertThat(f.nextNumber("CN")).isEqualTo(2L);
    assertThat(lastIssueDate(f, "CN")).isEqualTo("2026-03-10");
    // the same day is in order
    assertThat(f.credit("cn_same_day", MARCH_10, invoice.id()).legalNumber().counter())
        .isEqualTo(2L);
  }

  @Test
  void probe_an_invoice_finalised_earlier_is_numbered_dated_today_and_never_out_of_order() {
    // D-PS1-38 as rewritten by D-PS1-27: a sale still gets its invoice, but its issue date is the
    // numbering day, so the series' dates cannot go backwards and DEI-127 is never raised for an
    // invoice. The late supply is recorded instead: DEI-137 names the number. The fixture clock
    // is 20 March.
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("outoforder");
    Issuance first = f.invoice("in_march_10", MARCH_10);
    assertThat(lastIssueDate(f, "INV")).isEqualTo("2026-03-20");

    Issuance late = f.invoice("in_march_3", MARCH_3);

    assertThat(late.legalNumber().counter()).as("a sale gets its invoice").isEqualTo(2L);
    assertThat(late.dating().orElseThrow().issueDate())
        .isEqualTo(java.time.LocalDate.parse("2026-03-20"));
    assertThat(late.dating().orElseThrow().supplyDate())
        .contains(java.time.LocalDate.parse("2026-03-03"));
    assertThat(late.dating().orElseThrow().previousNumber()).contains(first.legalNumber().value());
    assertThat(lastIssueDate(f, "INV")).as("never backwards").isEqualTo("2026-03-20");
    assertThat(
            f.scalar(
                "SELECT count(*) FROM einvoice_finding WHERE seller_id = '"
                    + f.seller
                    + "' AND code = '"
                    + ErrorCodes.ISSUE_DATE_OUT_OF_ORDER
                    + "'"))
        .as("an invoice is never out of date order")
        .isZero();
    assertThat(
            f.scalar(
                "SELECT count(*) FROM einvoice_finding WHERE seller_id = '"
                    + f.seller
                    + "' AND code = '"
                    + ErrorCodes.ISSUED_LATE
                    + "' AND subject_id = '"
                    + late.legalNumber().value()
                    + "' AND acknowledged_at IS NULL"))
        .as("an open finding names the late number")
        .isEqualTo(1L);
  }

  @Test
  void last_issue_date_moves_only_with_the_counter_and_never_backwards() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("dateguard");
    f.invoice("in_dg", MARCH_10);
    String where = " WHERE seller_id = '" + f.seller + "' AND series = 'INV' AND mode = 'live'";

    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    "UPDATE einvoice_series SET last_issue_date = DATE '2026-03-11'" + where))
        .hasMessageContaining("only advances by one");
    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    "UPDATE einvoice_series SET next_number = next_number + 1,"
                        + " last_issue_date = DATE '2026-03-09'"
                        + where))
        .hasMessageContaining("never goes backwards");
    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    "UPDATE einvoice_series SET next_number = next_number + 1,"
                        + " last_issue_date = NULL"
                        + where))
        .hasMessageContaining("never goes backwards");
    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    "INSERT INTO einvoice_series (seller_id, document_type, series, fiscal_year,"
                        + " mode, prefix, width, next_number, updated_at, last_issue_date)"
                        + " VALUES ('"
                        + f.seller
                        + "', '380', 'INV', 2027, 'live', 'INV-2027-', 6, 1, now(),"
                        + " DATE '2099-01-01')"))
        .hasMessageContaining("starts with no issue date");
  }

  // ---------------------------------------------------------------- probe 7-1, S-04, S-09

  @Test
  void probe_a_series_row_type_cannot_be_updated() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("typeflip");
    f.invoice("in_tf", MARCH_10);
    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    "UPDATE einvoice_series SET document_type = '381', next_number = next_number"
                        + " + 1 WHERE seller_id = '"
                        + f.seller
                        + "' AND series = 'INV'"))
        .hasMessageContaining("identity is immutable");
  }

  @Test
  void probe_one_series_name_serves_one_type_in_a_year_and_across_years() {
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("namebind");
    f.invoice("in_nb", MARCH_10);
    String insert =
        "INSERT INTO einvoice_series (seller_id, document_type, series, fiscal_year, mode,"
            + " prefix, width, next_number, updated_at) VALUES ('"
            + f.seller
            + "', '381', 'INV', %d, 'live', 'X-%d-', 6, 1, now())";
    assertThatThrownBy(() -> PostgresSupport.execute(String.format(insert, 2026, 2026)))
        .hasMessageContaining("serves one type");
    assertThatThrownBy(() -> PostgresSupport.execute(String.format(insert, 2027, 2027)))
        .hasMessageContaining("serves one type");
  }

  @Test
  void the_store_refuses_one_series_name_for_two_types_and_overlapping_prefixes() {
    String seller = PostgresSupport.freshSeller("config");
    SeriesDefinition inv = DocumentSeriesFixture.INVOICES;
    assertThatThrownBy(
            () ->
                TestStores.store(
                    PostgresSupport.dataSource(),
                    DocumentSeriesFixture.CHAIN,
                    Map.of(
                        new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE),
                        inv,
                        new JdbcIssuanceStore.SeriesId(
                            seller, "INV", Mode.LIVE, DocumentTypeCode.CREDIT_NOTE),
                        DocumentSeriesFixture.CREDITS),
                    DocumentSeriesFixture.CLOCK))
        .extracting("code")
        .isEqualTo(ErrorCodes.CONFIG);
    // probe 7-2, first half: "INV-2026-" and "INV-2026-0" can render the same string
    assertThatThrownBy(
            () ->
                TestStores.store(
                    PostgresSupport.dataSource(),
                    DocumentSeriesFixture.CHAIN,
                    Map.of(
                        new JdbcIssuanceStore.SeriesId(seller, "INV", Mode.LIVE),
                        inv,
                        new JdbcIssuanceStore.SeriesId(
                            seller, "CN", Mode.LIVE, DocumentTypeCode.CREDIT_NOTE),
                        new SeriesDefinition("INV-{fiscalYear}-0", 5, true)),
                    DocumentSeriesFixture.CLOCK))
        .extracting("code")
        .isEqualTo(ErrorCodes.CONFIG);
  }

  @Test
  void probe_two_series_rendering_one_string_are_refused_by_the_unique_number() {
    // probe 7-2, second half: with the startup check out of the way (rows written directly), the
    // constraint refuses the second row that carries an existing legal number.
    DocumentSeriesFixture f = DocumentSeriesFixture.shared("collision");
    Issuance invoice = f.issuedInvoice("in_coll", MARCH_10);
    assertThatThrownBy(
            () ->
                PostgresSupport.execute(
                    "INSERT INTO einvoice_issuance (seller_id, mode, stripe_invoice_id, series,"
                        + " fiscal_year, legal_number, counter, issued_at, allocated_at, state,"
                        + " document_type, corrects_issuance_id) VALUES ('"
                        + f.seller
                        + "', 'live', 'cn_coll', 'CN', 2026, '"
                        + invoice.legalNumber().value()
                        + "', 1, now(), now(), 'NUMBERED', '381', "
                        + invoice.id()
                        + ")"))
        .hasMessageContaining("einvoice_issuance_number_unique");
  }

  // ---------------------------------------------------------------- helpers

  private static void assertTargetRefused(DocumentSeriesFixture f, long targetId) {
    long before = f.nextNumber("CN");
    long rows = f.rows();
    assertThatThrownBy(() -> f.credit("cn_refused_" + targetId, MARCH_10, targetId))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.CREDIT_TARGET_INVALID);
    assertThat(f.nextNumber("CN")).as("no number consumed").isEqualTo(before);
    assertThat(f.rows()).isEqualTo(rows);
  }

  private static String directRow(String seller, String type, String series, String target) {
    return "INSERT INTO einvoice_issuance (seller_id, mode, stripe_invoice_id, series, fiscal_year,"
        + " legal_number, counter, issued_at, allocated_at, state, document_type,"
        + " corrects_issuance_id) VALUES ('"
        + seller
        + "', 'live', 'direct_"
        + type
        + "_"
        + target
        + "', '"
        + series
        + "', 2026, 'DIRECT-"
        + type
        + "-"
        + target
        + "', 1, now(), now(), 'NUMBERED', '"
        + type
        + "', "
        + target
        + ")";
  }

  private static String lastIssueDate(DocumentSeriesFixture f, String series) {
    try (var c = f.dataSource.getConnection();
        var st = c.createStatement();
        var rs =
            st.executeQuery(
                "SELECT last_issue_date::text FROM einvoice_series WHERE seller_id = '"
                    + f.seller
                    + "' AND series = '"
                    + series
                    + "' AND mode = 'live'")) {
      return rs.next() ? rs.getString(1) : null;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static long openFindings(DocumentSeriesFixture f) {
    return f.scalar(
        "SELECT count(*) FROM einvoice_finding WHERE seller_id = '"
            + f.seller
            + "' AND acknowledged_at IS NULL");
  }
}
