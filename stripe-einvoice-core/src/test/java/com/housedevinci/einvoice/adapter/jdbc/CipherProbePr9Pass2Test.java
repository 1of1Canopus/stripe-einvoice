package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.DeterministicRenderer;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentRenderer;
import com.housedevinci.einvoice.application.DocumentRenderer.RenderedDocument;
import com.housedevinci.einvoice.application.IssuanceReprocess;
import com.housedevinci.einvoice.application.MappingInput;
import com.housedevinci.einvoice.application.PreflightReport;
import com.housedevinci.einvoice.application.ReconciliationSweep;
import com.housedevinci.einvoice.application.ReprocessRequest;
import com.housedevinci.einvoice.application.TestInvoices;
import com.housedevinci.einvoice.application.TestValidators;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.RuleIds;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Second security pass on the privileged reprocess (pass 2, 2026-09-22).
 *
 * <p>Pass 1's findings are re-verified by their own probes; these are the attacks on the surfaces
 * the fixes introduced: the new append-only table and the claim made about it, the normalisation
 * that replaced the rule-id refusal, the enum-derived verifier list, and the new reconciliation
 * check that reads a table with no tenant filter on it.
 */
class CipherProbePr9Pass2Test {

  private static final String ROLE = "einvoice_runtime_pr9";
  private static final String PASSWORD = "einvoice-runtime-pr9";
  private static final String TABLE = "einvoice_reprocess_request";

  private static DataSource runtime;

  @BeforeAll
  static void createRuntimeRole() {
    PostgresSupport.dataSource();
    PostgresSupport.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
            + ROLE
            + "') THEN CREATE ROLE "
            + ROLE
            + " LOGIN PASSWORD '"
            + PASSWORD
            + "'; END IF; END $$");
    // Exactly the grants docs/schema-grants.sql prescribes for this table, and nothing else.
    PostgresSupport.execute("GRANT SELECT, INSERT ON " + TABLE + " TO " + ROLE);
    PostgresSupport.execute("GRANT USAGE ON SEQUENCE " + TABLE + "_seq_seq TO " + ROLE);
    PostgresSupport.execute("GRANT CONNECT ON DATABASE test TO " + ROLE);
    runtime = PostgresSupport.dataSourceAs(ROLE, PASSWORD);
  }

  private static void asRuntime(String sql) throws SQLException {
    try (Connection c = runtime.getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static String string(String sql) {
    try (Connection c = PostgresSupport.dataSource().getConnection();
        Statement st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new IllegalStateException("test query failed: " + e.getMessage(), e);
    }
  }

  /** Refuses the preflight until it is repaired: the only way into the one eligible state. */
  private static final class RepairableRenderer implements DocumentRenderer {

    private final DeterministicRenderer delegate = new DeterministicRenderer();
    private boolean repaired;

    void repair() {
      this.repaired = true;
    }

    @Override
    public PreflightReport preflight(MappingInput input) {
      return repaired
          ? PreflightReport.passed()
          : PreflightReport.refused(ErrorCodes.SELLER_PROFILE_INCOMPLETE);
    }

    @Override
    public RenderedDocument render(DocumentInput input) {
      return delegate.render(input);
    }
  }

  // -------------------------------------------------------------------------------------------
  // R-02. The public claim is "append-only against the application role: SELECT and INSERT only,
  // and a trigger that refuses UPDATE, DELETE and TRUNCATE". Both halves, asserted separately,
  // because a statement refused by a missing grant proves nothing about a trigger that is absent.
  // GREEN on 3efc0a0; RED when the two guard rows are removed from the schema's trigger list.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_the_reprocess_record_is_append_only_by_grant_and_by_trigger() throws Exception {
    String eventId = "evt_pr9p2_grant";
    PostgresSupport.execute(
        "INSERT INTO "
            + TABLE
            + " (kind, event_id, seller_id, mode, actor, reason, at)"
            + " VALUES ('REQUESTED', '"
            + eventId
            + "', 'seller-grant', 'live', 'ops-jane', 'a stated reason', now())");

    // The runtime role may append and read, and nothing else.
    asRuntime(
        "INSERT INTO "
            + TABLE
            + " (kind, event_id, seller_id, mode, actor, reason, at)"
            + " VALUES ('REQUESTED', '"
            + eventId
            + "', 'seller-grant', 'live', 'ops-bob', 'appended by the app role', now())");

    for (String sql :
        new String[] {
          "UPDATE " + TABLE + " SET reason = 'rewritten' WHERE event_id = '" + eventId + "'",
          "DELETE FROM " + TABLE + " WHERE event_id = '" + eventId + "'",
          "TRUNCATE " + TABLE
        }) {
      assertThatThrownBy(() -> asRuntime(sql))
          .as("the application role must not be able to run: " + sql)
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> PostgresSupport.execute(sql))
          .as("and the trigger must refuse it even where no grant stands in the way: " + sql)
          .hasMessageContaining("append-only");
    }

    assertThat(
            PostgresSupport.scalar(
                "SELECT count(*) FROM " + TABLE + " WHERE event_id = '" + eventId + "'"))
        .isEqualTo(2L);
  }

  // -------------------------------------------------------------------------------------------
  // D9-02, the shapes the first probe did not try. A rule id is host free text: it may be blank,
  // two thousand characters, or carry control characters, an unpaired surrogate or a bidi
  // override. None may throw out of the transaction that records a consumed number's burn, and
  // none may reach a chained row in a form the screen elsewhere would refuse.
  // GREEN on 3efc0a0; RED when both normalisation points are removed.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_every_hostile_rule_id_shape_still_burns_and_chains() {
    String[] hostile = {
      "   ",
      "BR-DE-15 (fatal)".repeat(125),
      "BR" + (char) 0x00 + "DE" + (char) 0x07 + "-15" + (char) 0x7F,
      (char) 0xD83D + " drop table einvoice_issuance; --",
      (char) 0x202E + "reversed" + (char) 0x202C
    };
    for (int i = 0; i < hostile.length; i++) {
      String invoiceId = "in_pr9p2_rule_" + i;
      IssuanceTestHarness harness =
          IssuanceTestHarness.createWith(TestValidators.failing(hostile[i]));
      harness.source().with(TestInvoices.finalised(invoiceId));
      String eventId = harness.receive("invoice.finalized", invoiceId);
      long chainedBefore = harness.chainedEvents();

      try {
        harness.unitOfWork().process(eventId);
      } catch (RuntimeException thrown) {
        throw new AssertionError(
            "a rule id of shape " + i + " threw out of process(): " + thrown.getMessage(), thrown);
      }

      assertThat(harness.inbound().find(eventId).orElseThrow().state())
          .as("shape " + i + ": the event must reach a recorded disposition")
          .isEqualTo(InboundState.FAILED_ISSUANCE);
      assertThat(harness.issuance(invoiceId).orElseThrow().state())
          .as("shape " + i + ": the consumed number must carry its burn")
          .isEqualTo(IssuanceState.FAILED_VALIDATION);
      assertThat(harness.chainedEvents())
          .as("shape " + i + ": the burn must be chained")
          .isGreaterThan(chainedBefore);
      assertThat(harness.due()).extracting("eventId").doesNotContain(eventId);

      String stored =
          string(
              "SELECT void_rule_id FROM einvoice_issuance_event WHERE seller_id = '"
                  + harness.sellerId()
                  + "' ORDER BY seq DESC LIMIT 1");
      assertThat(stored)
          .as("shape " + i + ": what is chained is the normalised form, bounded and printable")
          .isEqualTo(RuleIds.normalise(hostile[i]));
      assertThat(stored.length()).isLessThanOrEqualTo(RuleIds.MAX_CHARS);
    }
  }

  // -------------------------------------------------------------------------------------------
  // D9-04. The fix builds the verifier's IN list from the enum. Asserted for every enum value at
  // once, rather than for the one state that drifted, so the next state added cannot drift either.
  // GREEN on 3efc0a0; RED when disposed() drops FAILED_VALIDATION.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_the_disposed_predicate_and_the_verifier_list_agree_for_every_state() throws Exception {
    Method disposedStates = JdbcIssuanceStore.class.getDeclaredMethod("disposedStates");
    disposedStates.setAccessible(true);
    String sqlList = (String) disposedStates.invoke(null);

    Set<String> inSql =
        Arrays.stream(sqlList.split(","))
            .map(token -> token.trim().replace("'", ""))
            .filter(token -> !token.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> byPredicate =
        Arrays.stream(IssuanceState.values())
            .filter(IssuanceState::disposed)
            .map(Enum::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(inSql).isEqualTo(byPredicate);
    for (IssuanceState state : IssuanceState.values()) {
      assertThat(state.disposed() && state.open())
          .as(state + " cannot be both settled and open")
          .isFalse();
    }
  }

  // -------------------------------------------------------------------------------------------
  // P2-01, new on this HEAD. Every other read the reconciliation sweep makes is filtered by seller
  // and mode; ReprocessLedger.unfinished is not. A sweep therefore raises DEI-276 under its own
  // seller, naming an event id that belongs to another seller - or to the other mode of the same
  // seller - and that finding is what the operator endpoint serves. RED on 3efc0a0.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_an_unfinished_reprocess_of_another_seller_is_reported_under_this_one() {
    IssuanceTestHarness other = IssuanceTestHarness.create();
    RepairableRenderer refusing = new RepairableRenderer();
    String foreignInvoice = "in_pr9p2_foreign";
    other.source().with(TestInvoices.finalised(foreignInvoice));
    String foreignEventId = other.receive("invoice.finalized", foreignInvoice);
    assertThat(other.unitOfWorkWithRenderer(refusing).process(foreignEventId).state())
        .isEqualTo(InboundState.FAILED_MAPPING);
    // A process that died mid-run, under another seller: REQUESTED with no CONCLUDED.
    assertThat(
            other
                .reprocessLedger()
                .reopenAndRecord(
                    new ReprocessRequest(
                        foreignEventId, "ops-elsewhere", "another seller's operator"),
                    other.sellerId(),
                    Mode.LIVE,
                    ErrorCodes.REPROCESS_REQUESTED,
                    other.now()))
        .isPresent();

    IssuanceTestHarness mine = IssuanceTestHarness.create();
    mine.moveClockForward(Duration.ofDays(1));
    ReconciliationSweep.Result result =
        mine.sweep(
                new ReconciliationSweep.Settings(
                    Duration.ofDays(7), Duration.ofMinutes(5), Duration.ofHours(6), 5, 50))
            .sweep();

    assertThat(mine.findingRows(foreignEventId))
        .as("a sweep must not raise a finding under its own seller for another seller's event")
        .isZero();
    assertThat(result.unfinishedReprocesses()).as("and must not count it either").isZero();
  }

  // -------------------------------------------------------------------------------------------
  // P2-02, new on this HEAD. A run that throws concludes with the escaped code (R-04). A
  // RuntimeException that is not an EInvoiceException concludes with DEI-200 INBOUND_UNREADABLE,
  // which is a real code with a real meaning - "the inbound event could not be read" - and is not
  // what happened. The record is the evidence; a wrong code in it is a wrong statement about a
  // privileged action. RED on 3efc0a0.
  // -------------------------------------------------------------------------------------------

  @Test
  void probe_a_non_typed_failure_is_not_concluded_as_an_unreadable_inbound_event() {
    IssuanceTestHarness harness = IssuanceTestHarness.create();
    RepairableRenderer renderer = new RepairableRenderer();
    String invoiceId = "in_pr9p2_untyped";
    harness.source().with(TestInvoices.finalised(invoiceId));
    String eventId = harness.receive("invoice.finalized", invoiceId);
    assertThat(harness.unitOfWorkWithRenderer(renderer).process(eventId).state())
        .isEqualTo(InboundState.FAILED_MAPPING);
    renderer.repair();
    harness.source().breakWith(new IllegalStateException("the upstream client exploded"));

    IssuanceReprocess reprocess = harness.reprocessWith(renderer);
    assertThatThrownBy(
            () ->
                reprocess.reprocess(
                    new ReprocessRequest(eventId, "ops-jane", "the profile was corrected")))
        .isInstanceOf(RuntimeException.class);

    var records = harness.reprocessRecords(eventId);
    assertThat(records).hasSize(2);
    assertThat(records.get(1).outcomeCode())
        .as(
            "an untyped failure must not be recorded as DEI-200, which means the inbound event"
                + " could not be read - it was read, and the run failed for another reason")
        .isNotEqualTo(ErrorCodes.INBOUND_UNREADABLE);
  }
}
