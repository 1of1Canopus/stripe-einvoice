package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.en16931.DocumentFixtures;
import com.housedevinci.einvoice.adapter.en16931.En16931DocumentRenderer;
import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.validation.En16931DocumentValidator;
import com.housedevinci.einvoice.adapter.validation.SecureXml;
import com.housedevinci.einvoice.adapter.validation.UblDocumentDates;
import com.housedevinci.einvoice.adapter.xml.UblProfile;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * D-PR36-6: the rule EINVOICE-DATES runs in the unit of work, on every path to the archive,
 * whatever renderer and validator are wired. One test per path of the design page (P1 to P21).
 *
 * <p>Example of the defect this closes: an invoice numbered on 16 January, a host renderer writes
 * BT-2 9 January, a host validator answers PASSED without reading; the archive held it.
 */
class DocumentDatesPathsTest {

  private static final UblDocumentDates MODULE_READER = new UblDocumentDates();

  /** A renderer that edits the harness renderer's bytes, the way a host renderer would. */
  private static DocumentRenderer editing(
      IssuanceTestHarness harness,
      java.util.function.BiFunction<String, DocumentInput, String> edit) {
    DocumentRenderer real = harness.renderer();
    return new DocumentRenderer() {
      @Override
      public RenderedDocument render(DocumentInput input) {
        RenderedDocument d = real.render(input);
        String xml = new String(d.bytes(), StandardCharsets.UTF_8);
        return new RenderedDocument(
            edit.apply(xml, input).getBytes(StandardCharsets.UTF_8), d.extension(), d.profile());
      }

      @Override
      public PreflightReport preflight(MappingInput input) {
        return real.preflight(input);
      }

      @Override
      public boolean carriesSupplyDate() {
        return true;
      }
    };
  }

  private static DocumentRenderer backdating(IssuanceTestHarness harness, int days) {
    return editing(
        harness,
        (xml, input) ->
            xml.replace(
                "<IssueDate>" + input.issueDate() + "</IssueDate>",
                "<IssueDate>" + input.issueDate().minusDays(days) + "</IssueDate>"));
  }

  /** Registers the invoice with the fake Stripe and records its invoice.finalized event. */
  private static String receive(IssuanceTestHarness harness, String invoiceId) {
    harness.source().with(TestInvoices.finalised(invoiceId));
    return harness.receive("invoice.finalized", invoiceId);
  }

  private static IssuanceUnitOfWork lenient(IssuanceTestHarness harness, DocumentRenderer r) {
    return harness.unitOfWorkWith(r, TestValidators.passing(), MODULE_READER, harness.archive());
  }

  private static void refusedOnDates(
      IssuanceTestHarness harness,
      String invoiceId,
      String eventId,
      IssuanceUnitOfWork.Outcome outcome,
      String code) {
    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(outcome.code()).isEqualTo(code);
    assertThat(harness.issuance(invoiceId).orElseThrow().state())
        .isEqualTo(IssuanceState.FAILED_VALIDATION);
    assertThat(harness.inbound().lastRuleId(eventId)).contains(DocumentDatesRule.RULE_ID);
    assertThat(harness.archive().size()).isZero();
  }

  /**
   * The WARN lines written while {@code action} runs. The core's test logger is slf4j-simple, which
   * resolves {@code System.err} at each write.
   */
  private static List<String> warningsDuring(Runnable action) {
    java.io.PrintStream original = System.err;
    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    return captured
        .toString(StandardCharsets.UTF_8)
        .lines()
        .filter(l -> l.contains(" WARN ") && l.contains("einvoice: the document date reader"))
        .toList();
  }

  // ------------------------------------------------------------------ P1, P3: module ports

  private static SourceInvoice germanInvoice() {
    return DocumentFixtures.germanStandardRated().invoice();
  }

  private static En16931DocumentValidator moduleValidator() {
    return new En16931DocumentValidator(
        UblProfile.XRECHNUNG_UBL, SecureXml.DEFAULT_XSLT2_PROCESSOR, Duration.ofSeconds(60), 2);
  }

  private static DocumentRenderer moduleRenderer() {
    return new En16931DocumentRenderer(
        DocumentFixtures.germanSeller(), UblProfile.XRECHNUNG_UBL, null);
  }

  @Test
  void the_true_dates_archive_with_the_module_renderer_and_validator() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(Instant.parse("2026-04-01T08:00:00Z"));
    harness.source().with(germanInvoice());
    String eventId = harness.receive("invoice.finalized", germanInvoice().id());
    try (En16931DocumentValidator validator = moduleValidator()) {
      IssuanceUnitOfWork.Outcome outcome =
          harness
              .unitOfWorkWith(moduleRenderer(), validator, MODULE_READER, harness.archive())
              .process(eventId);

      assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
      assertThat(harness.archive().size()).isEqualTo(1);
    }
  }

  @Test
  void a_backdating_host_renderer_is_refused_with_the_module_validator() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(Instant.parse("2026-04-01T08:00:00Z"));
    harness.source().with(germanInvoice());
    String eventId = harness.receive("invoice.finalized", germanInvoice().id());
    DocumentRenderer real = moduleRenderer();
    DocumentRenderer backdating =
        new DocumentRenderer() {
          @Override
          public RenderedDocument render(DocumentInput input) {
            RenderedDocument d = real.render(input);
            String moved =
                new String(d.bytes(), StandardCharsets.UTF_8)
                    .replace(
                        "<cbc:IssueDate>" + input.issueDate() + "</cbc:IssueDate>",
                        "<cbc:IssueDate>" + input.issueDate().minusDays(7) + "</cbc:IssueDate>");
            return new RenderedDocument(
                moved.getBytes(StandardCharsets.UTF_8), d.extension(), d.profile());
          }

          @Override
          public PreflightReport preflight(MappingInput input) {
            return real.preflight(input);
          }

          @Override
          public boolean carriesSupplyDate() {
            return true;
          }
        };
    try (En16931DocumentValidator validator = moduleValidator()) {
      IssuanceUnitOfWork.Outcome outcome =
          harness
              .unitOfWorkWith(backdating, validator, MODULE_READER, harness.archive())
              .process(eventId);

      refusedOnDates(
          harness, germanInvoice().id(), eventId, outcome, ErrorCodes.VALIDATION_REFUSED);
    }
  }

  // ------------------------------------------------------------------ P4, P5: host ports

  @Test
  void a_host_renderer_and_host_validator_cannot_move_the_delivery_date() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = receive(harness, "in_p4");
    DocumentRenderer movesDelivery =
        editing(
            harness,
            (xml, input) ->
                xml.replace(
                    "<ActualDeliveryDate>" + input.supplyDate().orElseThrow() + "<",
                    "<ActualDeliveryDate>"
                        + input.supplyDate().orElseThrow().minusMonths(1)
                        + "<"));

    IssuanceUnitOfWork.Outcome outcome = lenient(harness, movesDelivery).process(eventId);

    refusedOnDates(harness, "in_p4", eventId, outcome, ErrorCodes.VALIDATION_REFUSED);
  }

  @Test
  void a_hand_wired_unit_of_work_checks_the_dates() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = receive(harness, "in_p5");

    IssuanceUnitOfWork.Outcome outcome = lenient(harness, backdating(harness, 7)).process(eventId);

    refusedOnDates(harness, "in_p5", eventId, outcome, ErrorCodes.VALIDATION_REFUSED);
  }

  @Test
  void a_hand_wired_unit_of_work_with_a_foreign_reader_warns() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    DocumentDates foreign = MODULE_READER::read;

    List<String> warnings =
        warningsDuring(
            () ->
                harness.unitOfWorkWith(
                    harness.renderer(), TestValidators.passing(), foreign, harness.archive()));

    assertThat(warnings)
        .singleElement()
        .satisfies(
            m ->
                assertThat(m)
                    .contains("is not the module's own")
                    .contains("EINVOICE-DATES")
                    .contains(foreign.getClass().getName()));
  }

  @Test
  void a_subclass_or_a_namesake_is_not_the_module_reader() {
    assertThat(IssuanceUnitOfWork.isModuleReader(MODULE_READER)).isTrue();
    assertThat(IssuanceUnitOfWork.isModuleReader(MODULE_READER::read)).isFalse();
    assertThat(IssuanceUnitOfWork.isModuleReader(bytes -> DocumentDates.FoundDates.unreadable()))
        .isFalse();
  }

  @Test
  void the_module_reader_does_not_warn() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    List<String> warnings =
        warningsDuring(
            () ->
                harness.unitOfWorkWith(
                    harness.renderer(),
                    TestValidators.passing(),
                    MODULE_READER,
                    harness.archive()));

    assertThat(warnings).isEmpty();
  }

  // ------------------------------------------------------------------ P7, P8, P9, P10

  @Test
  void a_resumed_run_is_checked_against_the_row_dates() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(Instant.parse("2026-01-16T09:00:00Z"));
    String eventId = receive(harness, "in_p7");
    harness.renderer().breakWith(new Error("crash after NUMBERED"));
    try {
      harness.unitOfWork().process(eventId);
    } catch (Error expected) {
      // The crash the recovery column of the unit of work describes: NUMBERED, nothing rendered.
    }
    harness.renderer().heal();
    harness.moveClockTo(Instant.parse("2026-01-17T09:00:00Z"));
    // The resumed run renders "today" instead of the row's numbering day.
    DocumentRenderer today =
        editing(
            harness,
            (xml, input) ->
                xml.replace(
                    "<IssueDate>2026-01-16</IssueDate>", "<IssueDate>2026-01-17</IssueDate>"));

    IssuanceUnitOfWork.Outcome refused = lenient(harness, today).process(eventId);
    refusedOnDates(harness, "in_p7", eventId, refused, ErrorCodes.VALIDATION_REFUSED);
  }

  @Test
  void a_resumed_run_with_the_row_dates_completes() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.moveClockTo(Instant.parse("2026-01-16T09:00:00Z"));
    String eventId = receive(harness, "in_p7b");
    harness.renderer().breakWith(new Error("crash after NUMBERED"));
    try {
      harness.unitOfWork().process(eventId);
    } catch (Error expected) {
      // As above.
    }
    harness.renderer().heal();
    harness.moveClockTo(Instant.parse("2026-01-17T09:00:00Z"));

    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
    assertThat(new String(harness.archivedBytes("in_p7b"), StandardCharsets.UTF_8))
        .contains("<IssueDate>2026-01-16</IssueDate>");
  }

  @Test
  void a_reprocessed_run_goes_through_the_same_check() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = receive(harness, "in_p7r");
    java.util.concurrent.atomic.AtomicBoolean profileIncomplete =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    DocumentRenderer backdated = backdating(harness, 3);
    DocumentRenderer repairable =
        new DocumentRenderer() {
          @Override
          public boolean carriesSupplyDate() {
            return true;
          }

          @Override
          public PreflightReport preflight(MappingInput input) {
            return profileIncomplete.get()
                ? PreflightReport.refused(ErrorCodes.SELLER_PROFILE_INCOMPLETE)
                : PreflightReport.passed();
          }

          @Override
          public RenderedDocument render(DocumentInput input) {
            return backdated.render(input);
          }
        };
    assertThat(harness.unitOfWorkWithRenderer(repairable).process(eventId).state())
        .isEqualTo(InboundState.FAILED_MAPPING);
    profileIncomplete.set(false);

    IssuanceReprocess.Result result =
        harness.reprocessWith(repairable).reprocess(new ReprocessRequest(eventId, "ops", "fixed"));

    assertThat(result.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(harness.inbound().lastRuleId(eventId)).contains(DocumentDatesRule.RULE_ID);
    assertThat(harness.archive().size()).isZero();
  }

  @Test
  void an_archive_retry_is_checked_again() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = receive(harness, "in_p8");
    harness.archive().failWith(new EInvoiceException(ErrorCodes.ARCHIVE_UNAVAILABLE, "timeout"));
    assertThat(harness.unitOfWork().process(eventId).state())
        .isEqualTo(InboundState.FAILED_ISSUANCE);
    harness.archive().heal();

    IssuanceUnitOfWork.Outcome retry = lenient(harness, backdating(harness, 7)).process(eventId);

    // Nothing is archived and the event is terminal. The row cannot record the refusal:
    // FAILED_ARCHIVE has no FAILED_VALIDATION successor (IssuanceState), so the event concludes on
    // the store's illegal-transition code, not on EINVOICE-DATES. The internal open-questions log,
    // item 38, asks for the edge.
    assertThat(retry.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(retry.code()).isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
    assertThat(harness.archive().size()).isZero();
    assertThat(harness.issuance("in_p8").orElseThrow().state())
        .isEqualTo(IssuanceState.FAILED_ARCHIVE);
  }

  @Test
  void a_0_1_0_row_is_checked_against_its_legacy_date() {
    Instant issuedAt = Instant.parse("2026-01-15T23:30:00Z");
    Issuance legacy =
        new Issuance(
            1L,
            DocumentFixtures.SERIES,
            "in_legacy",
            "",
            "",
            DocumentFixtures.NUMBER,
            issuedAt,
            issuedAt,
            "",
            "",
            "fr-2026.1",
            IssuanceState.NUMBERED,
            "",
            "",
            Optional.empty(),
            Optional.empty());
    DocumentInput input =
        DocumentInput.numbered(
            DocumentFixtures.germanStandardRated().unnumbered(),
            legacy,
            java.time.ZoneId.of("Europe/Paris"));
    byte[] bytes = new DeterministicRenderer().render(input).bytes();
    String other =
        new String(bytes, StandardCharsets.UTF_8)
            .replace("<IssueDate>2026-01-16</IssueDate>", "<IssueDate>2026-01-15</IssueDate>");

    assertThat(input.issueDate()).hasToString("2026-01-16");
    assertThat(DocumentDatesRule.check(MODULE_READER.read(bytes), input.dating()).archivable())
        .isTrue();
    assertThat(
            DocumentDatesRule.check(
                    MODULE_READER.read(other.getBytes(StandardCharsets.UTF_8)), input.dating())
                .verdict())
        .isEqualTo(DocumentValidator.Verdict.FAILED);
  }

  @Test
  void a_redelivery_does_not_write_and_does_not_re_check() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    AtomicInteger reads = new AtomicInteger();
    DocumentDates counting =
        bytes -> {
          reads.incrementAndGet();
          return MODULE_READER.read(bytes);
        };
    IssuanceUnitOfWork unitOfWork =
        harness.unitOfWorkWith(
            harness.renderer(), TestValidators.passing(), counting, harness.archive());
    assertThat(unitOfWork.process(receive(harness, "in_p10")).state())
        .isEqualTo(InboundState.COMPLETED);
    assertThat(reads).hasValue(1);

    IssuanceUnitOfWork.Outcome again =
        unitOfWork.process(harness.receive("invoice.paid", "in_p10"));

    assertThat(again.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(reads).hasValue(1);
    assertThat(harness.archive().size()).isEqualTo(1);
  }

  // ------------------------------------------------------------------ P13, P14, P15, P19

  @Test
  void two_document_level_deliveries_are_ambiguous() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = receive(harness, "in_p13");
    DocumentRenderer twoDeliveries =
        editing(
            harness,
            (xml, input) ->
                xml.replace(
                    "</cac:Delivery>",
                    "</cac:Delivery><cac:Delivery><ActualDeliveryDate>"
                        + input.supplyDate().orElseThrow().minusDays(7)
                        + "</ActualDeliveryDate></cac:Delivery>"));

    IssuanceUnitOfWork.Outcome outcome = lenient(harness, twoDeliveries).process(eventId);

    refusedOnDates(harness, "in_p13", eventId, outcome, ErrorCodes.VALIDATION_NOT_EVALUATED);
  }

  @Test
  void a_tax_point_date_is_refused_on_a_lenient_path() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = receive(harness, "in_p14");
    DocumentRenderer taxPoint =
        editing(
            harness,
            (xml, input) ->
                xml.replace(
                    "</IssueDate>",
                    "</IssueDate><TaxPointDate>"
                        + input.supplyDate().orElseThrow().minusDays(30)
                        + "</TaxPointDate>"));

    IssuanceUnitOfWork.Outcome outcome = lenient(harness, taxPoint).process(eventId);

    refusedOnDates(harness, "in_p14", eventId, outcome, ErrorCodes.VALIDATION_REFUSED);
  }

  @Test
  void a_vat_point_date_code_is_refused_on_a_lenient_path() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = receive(harness, "in_p19");
    DocumentRenderer code =
        editing(
            harness,
            (xml, input) ->
                xml.replace(
                    "</IssueDate>",
                    "</IssueDate><cac:InvoicePeriod><DescriptionCode>432</DescriptionCode>"
                        + "</cac:InvoicePeriod>"));

    IssuanceUnitOfWork.Outcome outcome = lenient(harness, code).process(eventId);

    refusedOnDates(harness, "in_p19", eventId, outcome, ErrorCodes.VALIDATION_REFUSED);
  }

  @Test
  void a_non_ubl_document_is_not_evaluated() {
    int n = 0;
    for (UnaryOperator<String> foreign :
        List.<UnaryOperator<String>>of(
            xml ->
                "<Invoice><IssueDate>"
                    + xml.substring(xml.indexOf("<IssueDate>") + 11, xml.indexOf("</IssueDate>"))
                    + "</IssueDate></Invoice>",
            xml -> "<!DOCTYPE x [<!ENTITY e \"2026-01-16\">]>" + xml,
            xml ->
                xml.replace(
                    "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2",
                    "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"))) {
      IssuanceTestHarness harness = IssuanceTestHarness.create();
      String id = "in_p15_" + (++n);
      String eventId = receive(harness, id);

      IssuanceUnitOfWork.Outcome outcome =
          lenient(harness, editing(harness, (xml, input) -> foreign.apply(xml))).process(eventId);

      refusedOnDates(harness, id, eventId, outcome, ErrorCodes.VALIDATION_NOT_EVALUATED);
    }
  }

  // ------------------------------------------------------------------ P16

  @Test
  void a_host_archive_store_never_sees_a_backdated_document() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String eventId = receive(harness, "in_p16");
    InMemoryArchiveStore inner = harness.archive();
    AtomicInteger writes = new AtomicInteger();
    ArchiveStore counting =
        new ArchiveStore() {
          @Override
          public boolean supportsAtomicCreate() {
            return true;
          }

          @Override
          public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
            writes.incrementAndGet();
            return inner.putIfAbsent(key, bytes);
          }

          @Override
          public Optional<byte[]> get(ArchiveKey key) {
            return inner.get(key);
          }

          @Override
          public List<String> list(String prefix, int limit) {
            return inner.list(prefix, limit);
          }

          @Override
          public String describe() {
            return "counting host store";
          }
        };

    IssuanceUnitOfWork.Outcome outcome =
        harness
            .unitOfWorkWith(
                backdating(harness, 7), TestValidators.passing(), MODULE_READER, counting)
            .process(eventId);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(writes).hasValue(0);
  }

  // ------------------------------------------------------------------ R3: a reader with no answer

  @Test
  void a_reader_that_answers_nothing_or_refuses_is_not_evaluated() {
    int n = 0;
    for (DocumentDates broken :
        List.<DocumentDates>of(
            bytes -> null,
            bytes -> {
              throw new EInvoiceException(ErrorCodes.XML_REFUSED, "refused");
            })) {
      IssuanceTestHarness harness = IssuanceTestHarness.create();
      String id = "in_r3_" + (++n);
      String eventId = receive(harness, id);

      IssuanceUnitOfWork.Outcome outcome =
          harness
              .unitOfWorkWith(
                  harness.renderer(), TestValidators.passing(), broken, harness.archive())
              .process(eventId);

      refusedOnDates(harness, id, eventId, outcome, ErrorCodes.VALIDATION_NOT_EVALUATED);
    }
  }
}
