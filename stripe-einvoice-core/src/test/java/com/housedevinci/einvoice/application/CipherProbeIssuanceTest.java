package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.Hashes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Issuance;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The properties this pull request claims, each stated in its own name and each proven to go RED
 * with its control removed (checklist lines 59 and 60; the red runs are recorded in the pull
 * request body).
 */
class CipherProbeIssuanceTest {

  @Test
  void probe_the_webhook_payload_is_never_a_data_source_for_a_document() {
    // D-02. The recorded body below claims a different invoice and a different total. If any of it
    // reached the document, the archived bytes would carry those values; they carry the
    // authoritative ones, because the body yields an identity and nothing else.
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_authoritative"));
    String eventId =
        harness.receiveWithBody(
            "invoice.finalized",
            "in_authoritative",
            "{\"id\":\"evt_payload\",\"type\":\"invoice.finalized\",\"livemode\":true,"
                + "\"data\":{\"object\":{\"id\":\"in_authoritative\",\"total\":99999999,"
                + "\"lines\":{\"has_more\":true,\"data\":[{\"id\":\"il_payload\",\"amount\":99999999}]}}}}");

    harness.unitOfWork().process(eventId);

    String document = new String(harness.archivedBytes("in_authoritative"), StandardCharsets.UTF_8);
    assertThat(document).contains("<Total>12000</Total>").doesNotContain("99999999");
    assertThat(document).contains("il_1").doesNotContain("il_payload");
    // And the only thing the body is ever read for has six fields, none of them an amount.
    assertThat(EventIdentity.class.getRecordComponents()).hasSize(6);
  }

  @Test
  void probe_a_replayed_event_id_issues_once() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_replay"));
    String eventId = harness.receive("invoice.finalized", "in_replay");

    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);
    assertThat(harness.unitOfWork().process(eventId).state()).isEqualTo(InboundState.COMPLETED);

    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.archive().size()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
  }

  @Test
  void probe_two_concurrent_events_for_one_invoice_produce_one_document() throws Exception {
    // invoice.finalized and invoice.paid can arrive together, and Stripe gives no ordering. Two
    // different events, one sale: one number, one object, one chained disposition.
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_race"));
    List<String> events =
        List.of(
            harness.receive("invoice.finalized", "in_race"),
            harness.receive("invoice.paid", "in_race"),
            harness.receive("invoice.paid", "in_race"),
            harness.receive("invoice.finalized", "in_race"));

    List<Callable<String>> work =
        events.stream()
            .<Callable<String>>map(
                id ->
                    () -> {
                      try {
                        return harness.unitOfWork().process(id).state().name();
                      } catch (EInvoiceException refused) {
                        // A loser of the insert race is refused by the unique constraint and the
                        // sweeper re-picks it; what must never happen is a second number.
                        return refused.code();
                      }
                    })
            .toList();
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      for (Future<String> outcome : pool.invokeAll(work)) {
        assertThat(outcome.get()).isNotNull();
      }
    }

    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.archive().size()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
  }

  @Test
  void probe_an_archive_key_holding_other_bytes_is_never_overwritten() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_overwrite"));
    String eventId = harness.receive("invoice.finalized", "in_overwrite");
    byte[] theirs = "<Invoice>somebody else</Invoice>".getBytes(StandardCharsets.UTF_8);
    byte[] ours = harness.renderFor("in_overwrite", "INV-2026-000001");
    ArchiveKey key =
        ArchiveKey.of(
            harness.seriesKey(),
            harness.legalNumber("INV-2026-000001"),
            Hashes.sha256Hex(ours),
            "xml");
    harness.archive().putDirectly(key.value(), theirs);

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.ARCHIVE_CONTENT_CONFLICT);
    assertThat(harness.archive().get(key)).contains(theirs);
  }

  @Test
  void probe_a_number_is_consumed_only_by_an_invoice_this_module_agreed_to_document() {
    // Every refusal that depends on data happens before the allocator is called, so a refused
    // invoice leaves the series exactly where it was. The number is the asset an auditor checks
    // first, and a hole in it cannot be repaired afterwards.
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.offByOneCentInOneBucket("in_refused"));
    harness.source().with(TestInvoices.finalised("in_good"));

    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_refused"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_good"));

    Issuance issued = harness.issuance("in_good").orElseThrow();
    assertThat(issued.legalNumber().counter()).isEqualTo(1L);
    assertThat(harness.numberedRows()).isEqualTo(1);
  }

  @Test
  void probe_a_document_cannot_be_issued_without_a_validation_that_actually_ran() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWith(TestValidators.notEvaluated("BR-DE-15"));
    harness.source().with(TestInvoices.finalised("in_notevaluated"));

    assertThat(
            harness
                .unitOfWork()
                .process(harness.receive("invoice.finalized", "in_notevaluated"))
                .code())
        .isEqualTo(ErrorCodes.VALIDATION_NOT_EVALUATED);
    assertThat(harness.archive().size()).isZero();
  }

  @Test
  void probe_no_log_line_or_outcome_carries_a_buyer_field() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_logs", "Confidential Buyer Name", "paid"));

    IssuanceUnitOfWork.Outcome outcome =
        harness.unitOfWork().process(harness.receive("invoice.finalized", "in_logs"));

    assertThat(outcome.toString()).doesNotContain("Confidential");
    assertThat(harness.issuance("in_logs").orElseThrow().toString()).doesNotContain("Confidential");
    // The row keeps ids, hashes and a number - never the buyer. The document is where the buyer is,
    // and the document is in the archive under legal retention.
    assertThat(
            IntStream.range(0, Issuance.class.getRecordComponents().length)
                .mapToObj(i -> Issuance.class.getRecordComponents()[i].getName())
                .toList())
        .doesNotContain("buyer", "customerName", "email");
  }

  @Test
  void probe_a_tampered_archive_is_refused_and_never_re_issued() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_tamper_probe"));
    harness.unitOfWork().process(harness.receive("invoice.finalized", "in_tamper_probe"));
    Issuance issuance = harness.issuance("in_tamper_probe").orElseThrow();
    harness
        .archive()
        .putDirectly(
            issuance.archiveKey(), "<Invoice>rewritten</Invoice>".getBytes(StandardCharsets.UTF_8));

    assertThat(
            harness.unitOfWork().process(harness.receive("invoice.paid", "in_tamper_probe")).code())
        .isEqualTo(ErrorCodes.ARCHIVED_DOCUMENT_TAMPERED);
    assertThat(harness.numberedRows()).isEqualTo(1);
    assertThat(harness.chainedEvents()).isEqualTo(1);
  }

  @Test
  void probe_an_event_for_another_account_never_reaches_the_series() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_other_account"));
    String eventId =
        harness.receive(
            "invoice.finalized", "in_other_account", FakeStripeSource.PINNED, true, "acct_other");

    assertThat(harness.unitOfWork().process(eventId).state())
        .isEqualTo(InboundState.REFUSED_ACCOUNT);
    assertThat(harness.numberedRows()).isZero();
    assertThat(harness.source().fetches()).isZero();
  }

  // D2-01a
  @Test
  void probe_every_event_that_stops_carries_a_recorded_state_and_code() throws Exception {
    // invoice.finalized and invoice.paid arrive together in the normal case, so four events for
    // one invoice, processed concurrently, is the shape of a real claim race - not an edge case.
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_recorded"));
    List<String> events =
        List.of(
            harness.receive("invoice.finalized", "in_recorded"),
            harness.receive("invoice.finalized", "in_recorded"),
            harness.receive("invoice.paid", "in_recorded"),
            harness.receive("invoice.paid", "in_recorded"));

    List<Callable<Void>> work =
        events.stream()
            .<Callable<Void>>map(
                id ->
                    () -> {
                      try {
                        harness.unitOfWork().process(id);
                      } catch (EInvoiceException ignored) {
                        // The row is what is asserted, not what this call returned or threw.
                      }
                      return null;
                    })
            .toList();
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      for (Future<Void> outcome : pool.invokeAll(work)) {
        outcome.get();
      }
    }

    for (String id : events) {
      assertThat(harness.inbound().find(id).orElseThrow().state())
          .describedAs(
              "every event that stops must carry a recorded state and code; a loser left MAPPED"
                  + " with attempts 0 is never re-picked correctly by the retry ceiling")
          .isNotEqualTo(InboundState.MAPPED);
    }
  }

  // D2-01b
  @Test
  void probe_an_allocator_refusal_is_recorded_on_the_inbound_row() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_unconfigured"));
    String eventId = harness.receive("invoice.finalized", "in_unconfigured");

    harness.unitOfWorkWithSeries("NOT-CONFIGURED").process(eventId);

    InboundEvent row = harness.inbound().find(eventId).orElseThrow();
    assertThat(row.lastCode()).describedAs("the allocator's refusal code").isNotEmpty();
    assertThat(row.attempts()).describedAs("so the retry ceiling is reachable").isPositive();
  }

  // D2-03
  @Test
  void probe_a_version_skewed_event_is_re_picked_by_the_sweeper_after_the_pin_moves() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    String skewedVersion = "2019-01-01.old";
    String eventId = harness.receive("invoice.finalized", "in_skew", skewedVersion, true, "");

    harness.unitOfWork().process(eventId);

    assertThat(harness.inbound().find(eventId).orElseThrow().state())
        .isEqualTo(InboundState.REFUSED_VERSION_SKEW);
    assertThat(harness.due())
        .describedAs("still skewed against the pin this application runs under")
        .extracting(InboundEvent::eventId)
        .doesNotContain(eventId);
    assertThat(harness.due(skewedVersion))
        .describedAs(
            "the operator updated einvoice.stripe.api-version to match what this event actually"
                + " carried, and the sweeper must re-pick exactly this row")
        .extracting(InboundEvent::eventId)
        .contains(eventId);
  }

  // D3-02
  @Test
  void probe_a_configuration_that_can_never_validate_consumes_no_number() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWith(
            com.housedevinci.einvoice.application.TestValidators.notEvaluatedNoProcessor(
                "EN16931-NO-XSLT-PROCESSOR"));
    harness.source().with(TestInvoices.finalised("in_no_processor"));
    String eventId = harness.receive("invoice.finalized", "in_no_processor");

    IssuanceUnitOfWork.Outcome outcome = harness.unitOfWork().process(eventId);

    assertThat(outcome.code()).isEqualTo(ErrorCodes.XSLT_PROCESSOR_MISSING);
    assertThat(harness.numberedRows())
        .describedAs("this application can never validate anything - not this invoice's problem")
        .isZero();
  }

  // D2-04
  @Test
  void probe_a_host_validator_that_throws_is_recorded_like_any_other_phase_failure() {
    IssuanceTestHarness harness =
        IssuanceTestHarness.createWith(
            (bytes, input) -> {
              throw new IllegalStateException("a bug in somebody else's validator");
            });
    harness.source().with(TestInvoices.finalised("in_validator_bug"));
    String eventId = harness.receive("invoice.finalized", "in_validator_bug");

    harness.unitOfWork().process(eventId);

    InboundEvent row = harness.inbound().find(eventId).orElseThrow();
    assertThat(row.state())
        .describedAs("never left MAPPED, or the due query re-picks it for ever")
        .isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(row.lastCode()).isEqualTo(ErrorCodes.VALIDATOR_FAILED);
  }

  @Test
  void a_host_renderer_that_throws_is_recorded_like_any_other_phase_failure() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_renderer_bug"));
    String eventId = harness.receive("invoice.finalized", "in_renderer_bug");

    harness
        .unitOfWorkWithRenderer(
            input -> {
              throw new NullPointerException("a bug in somebody else's renderer");
            })
        .process(eventId);

    InboundEvent row = harness.inbound().find(eventId).orElseThrow();
    assertThat(row.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(row.lastCode()).isEqualTo(ErrorCodes.RENDER_FAILED);
  }

  @Test
  void a_host_archive_that_throws_is_recorded_like_any_other_phase_failure() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    harness.source().with(TestInvoices.finalised("in_archive_bug"));
    String eventId = harness.receive("invoice.finalized", "in_archive_bug");

    harness
        .unitOfWorkWithArchive(
            new com.housedevinci.einvoice.application.ArchiveStore() {
              @Override
              public boolean supportsAtomicCreate() {
                return true;
              }

              @Override
              public WriteResult putIfAbsent(ArchiveKey key, byte[] bytes) {
                throw new RuntimeException("a bug in somebody else's archive store");
              }

              @Override
              public java.util.Optional<byte[]> get(ArchiveKey key) {
                return java.util.Optional.empty();
              }

              @Override
              public List<String> list(String prefix, int limit) {
                return List.of();
              }

              @Override
              public String describe() {
                return "a store that throws (test)";
              }
            })
        .process(eventId);

    InboundEvent row = harness.inbound().find(eventId).orElseThrow();
    assertThat(row.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(row.lastCode()).isEqualTo(ErrorCodes.ARCHIVE_FAILED);
  }

  @Test
  void probe_the_strict_reader_refuses_a_body_two_readers_could_disagree_about() {
    assertThatThrownBy(
            () ->
                EventIdentity.from(
                    ("{\"id\":\"evt_1\",\"id\":\"evt_2\",\"type\":\"invoice.finalized\","
                            + "\"livemode\":true,\"data\":{\"object\":{\"id\":\"in_1\"}}}")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(EInvoiceException.class)
        .extracting("code")
        .isEqualTo(ErrorCodes.INBOUND_UNREADABLE);
  }
}
