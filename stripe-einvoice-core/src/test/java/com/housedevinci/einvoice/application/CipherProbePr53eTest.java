package com.housedevinci.einvoice.application;

import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.ARCHIVING_WITH_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.Shape.FAILED_ARCHIVE_NO_OBJECT;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.burnLine;
import static com.housedevinci.einvoice.application.ArchiveRetryFixture.prepare;
import static org.assertj.core.api.Assertions.assertThat;

import com.housedevinci.einvoice.adapter.jdbc.IssuanceTestHarness;
import com.housedevinci.einvoice.adapter.jdbc.PostgresSupport;
import com.housedevinci.einvoice.application.ArchiveRetryFixture.Row;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Security review pass 1 of the resumed render hash mismatch PR (D-RTR-5, DEI-244). One red probe
 * (D-PR53-1) and three guards that hold on the branch head.
 */
class CipherProbePr53eTest {

  private static final String CODE = ErrorCodes.RENDER_NOT_REPRODUCIBLE;

  /** The inbound row's recorded rule id, which the {@code InboundEvent} record does not expose. */
  private static String lastRuleId(String eventId) throws Exception {
    try (Connection c = PostgresSupport.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT last_rule_id FROM einvoice_inbound_event WHERE event_id = ?")) {
      ps.setString(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("inbound row " + eventId).isTrue();
        String v = rs.getString(1);
        return v == null ? "" : v;
      }
    }
  }

  /**
   * D-PR53-1 (INFO). After a DEI-244 burn, the next event for the same invoice concludes {@code
   * DEI-250} ("the exact bytes did not pass validation; the failing rule id is recorded") with an
   * empty rule id. Nothing was refused and no rule id is on that event row: an operator reading the
   * second event is told the wrong cause.
   */
  @Test
  void probe_a_later_event_on_a_render_mismatch_burn_names_its_rule_id() throws Exception {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_p53e_1");
    ResumedRenderMismatchPathsTest.retryUpgraded(row);
    assertThat(lastRuleId(row.eventId())).isEqualTo(CODE);

    String paid = row.harness().receive("invoice.paid", "in_p53e_1");
    IssuanceUnitOfWork.Outcome outcome = row.harness().unitOfWork().process(paid);

    assertThat(outcome.state()).isEqualTo(InboundState.FAILED_ISSUANCE);
    assertThat(lastRuleId(paid)).as("the later event names why the number burned").isEqualTo(CODE);
  }

  /**
   * Guard. A reprocess of another event of the same invoice, while the burn runs, is refused by the
   * run lock; it never renders, never writes the row, and the burn chains exactly one event. After
   * the burn the reprocess is refused as numbered.
   */
  @Test
  void probe_a_reprocess_during_a_render_mismatch_burn_is_refused() throws Exception {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_p53e_2");
    IssuanceTestHarness harness = row.harness();

    // A FAILED_MAPPING event for the same invoice, the reprocess's entry state.
    AtomicBoolean refusePreflight = new AtomicBoolean(true);
    DocumentRenderer upgraded = ResumedRenderMismatchPathsTest.upgraded(harness);
    DocumentRenderer gated =
        new DocumentRenderer() {
          @Override
          public boolean carriesSupplyDate() {
            return true;
          }

          @Override
          public PreflightReport preflight(MappingInput input) {
            return refusePreflight.get()
                ? PreflightReport.refused(ErrorCodes.SELLER_PROFILE_INCOMPLETE)
                : PreflightReport.passed();
          }

          @Override
          public RenderedDocument render(DocumentInput input) {
            return upgraded.render(input);
          }
        };
    String paid = harness.receive("invoice.paid", "in_p53e_2");
    assertThat(harness.unitOfWorkWithRenderer(gated).process(paid).state())
        .isEqualTo(InboundState.FAILED_MAPPING);
    refusePreflight.set(false);

    CountDownLatch inRender = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    DocumentRenderer blocking =
        ArchiveRetryFixture.editing(
            harness,
            (xml, input) -> {
              inRender.countDown();
              try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("the probe never released the render");
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              return xml + "<!-- renderer 2 -->";
            });
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<IssuanceUnitOfWork.Outcome> a =
          worker.submit(
              () -> row.retry(blocking, TestValidators.passing(), ArchiveRetryFixture.READER));
      assertThat(inRender.await(30, TimeUnit.SECONDS)).as("the burn run is rendering").isTrue();
      int rendersBefore = harness.renderer().renders();

      IssuanceReprocess.Result during =
          harness.reprocessWith(gated).reprocess(new ReprocessRequest(paid, "ops", "retry"));

      assertThat(during.disposition())
          .isEqualTo(IssuanceReprocess.Disposition.REFUSED_RUN_IN_PROGRESS);
      assertThat(harness.renderer().renders()).isEqualTo(rendersBefore);
      release.countDown();
      IssuanceUnitOfWork.Outcome outcome = a.get(30, TimeUnit.SECONDS);

      assertThat(outcome.code()).isEqualTo(CODE);
      assertThat(row.now().state()).isEqualTo(IssuanceState.FAILED_VALIDATION);
      assertThat(row.chained()).containsExactly(burnLine(row.now(), CODE));
      assertThat(harness.archive().size()).isZero();

      IssuanceReprocess.Result after =
          harness.reprocessWith(gated).reprocess(new ReprocessRequest(paid, "ops", "retry"));
      assertThat(after.disposition()).isEqualTo(IssuanceReprocess.Disposition.REFUSED_NUMBERED);
      assertThat(row.chained()).hasSize(1);
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }

  /**
   * Guard. Hash and key match but the recorded object is gone from the archive (deleted after the
   * PUT, before the issue). The retry writes the same bytes again at the same key and issues; no
   * DEI-244, no second key.
   */
  @Test
  void probe_a_matching_hash_with_the_object_gone_is_issued_at_the_recorded_key() {
    Row row = prepare(ARCHIVING_WITH_OBJECT, "in_p53e_3");
    row.harness().archive().forget(row.before().archiveKey());
    assertThat(row.harness().archive().size()).isZero();

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(row.harness().renderer(), TestValidators.passing(), ArchiveRetryFixture.READER);

    assertThat(outcome.state()).isEqualTo(InboundState.COMPLETED);
    assertThat(outcome.code()).isNotEqualTo(CODE);
    assertThat(row.now().state()).isEqualTo(IssuanceState.ISSUED);
    assertThat(row.now().archiveKey()).isEqualTo(row.before().archiveKey());
    assertThat(row.harness().archive().list("", 100)).containsExactly(row.before().archiveKey());
  }

  /**
   * Guard. Only the key differs (same bytes, extension in another case). The pre-read looks at the
   * recorded key, finds nothing, and the comparison burns: the new key is never written.
   */
  @Test
  void probe_a_key_that_differs_only_by_extension_case_burns_and_writes_nothing() {
    Row row = prepare(FAILED_ARCHIVE_NO_OBJECT, "in_p53e_4");
    DocumentRenderer real = row.harness().renderer();
    DocumentRenderer upperCase =
        new DocumentRenderer() {
          @Override
          public RenderedDocument render(DocumentInput input) {
            RenderedDocument d = real.render(input);
            return new RenderedDocument(
                d.bytes(), d.extension().toUpperCase(java.util.Locale.ROOT), d.profile());
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

    IssuanceUnitOfWork.Outcome outcome =
        row.retry(upperCase, TestValidators.passing(), ArchiveRetryFixture.READER);

    assertThat(outcome.code()).isEqualTo(CODE);
    assertThat(row.now().archiveKey()).isEqualTo(row.before().archiveKey());
    assertThat(row.harness().archive().size()).isZero();
  }
}
