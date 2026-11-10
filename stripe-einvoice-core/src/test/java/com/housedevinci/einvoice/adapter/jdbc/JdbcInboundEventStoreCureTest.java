package com.housedevinci.einvoice.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.application.InboundEventStore.Recorded;
import com.housedevinci.einvoice.application.InboundIntake;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.Hashes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.RunningLimit;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * D-PR60-2: a copy of an event at the pinned API version cures the row an earlier copy at another
 * version left behind, and never anything else (skew duplicate design, probes 1 to 11 and 17 to
 * 22), against a real PostgreSQL.
 */
class JdbcInboundEventStoreCureTest {

  static final String PIN = "2026-03-31.clover";
  static final String OLD = "2025-12-15.acacia";
  static final String OTHER_OLD = "2025-06-30.basil";
  private static final Instant T0 = Instant.parse("2026-09-15T10:00:00Z");
  private static final Instant T1 = T0.plus(Duration.ofDays(10));

  private static JdbcInboundEventStore store() {
    return new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(PostgresSupport.dataSource()));
  }

  private static String freshId() {
    return "evt_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static byte[] body(String id, String version) {
    return ("{\"id\":\"" + id + "\",\"api_version\":\"" + version + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static InboundEvent copy(String id, String version, Instant at) {
    return copy(id, version, "in_" + id, true, "", Mode.LIVE, at);
  }

  private static InboundEvent copy(
      String id,
      String version,
      String objectId,
      boolean livemode,
      String account,
      Mode mode,
      Instant at) {
    return InboundEvent.received(
        new EventIdentity(id, "invoice.finalized", version, livemode, account, objectId),
        mode,
        "primary",
        body(id, version),
        at);
  }

  private static Recorded record(InboundEventStore store, InboundEvent event, String pin) {
    return store.record(event, body(event.eventId(), event.apiVersion()), pin);
  }

  private static String column(String id, String column) {
    try (Connection c = PostgresSupport.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT " + column + "::text FROM einvoice_inbound_event WHERE event_id = ?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("row %s exists", id).isTrue();
        return rs.getString(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void execute(String sql, String id) {
    try (Connection c = PostgresSupport.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, id);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A wrong copy, routed to skew, exactly as the route writes it. */
  private static String refusedAtOld(JdbcInboundEventStore store) {
    String id = freshId();
    assertThat(record(store, copy(id, OLD, T0), PIN)).isEqualTo(Recorded.FIRST);
    store.transition(
        id, InboundState.REFUSED_VERSION_SKEW, ErrorCodes.API_VERSION_SKEW, "", T0, null);
    return id;
  }

  // ---------------------------------------------------------------------------------- probe 1

  @Test
  void a_copy_at_the_pin_cures_a_row_refused_for_skew() {
    JdbcInboundEventStore store = store();
    String id = refusedAtOld(store);

    assertThat(record(store, copy(id, PIN, T1), PIN)).isEqualTo(Recorded.CURED);

    InboundEvent cured = store.find(id).orElseThrow();
    assertThat(cured.state()).isEqualTo(InboundState.RECEIVED);
    assertThat(cured.apiVersion()).isEqualTo(PIN);
    assertThat(cured.attempts()).isZero();
    assertThat(cured.lastCode()).isEmpty();
    assertThat(cured.cureGeneration()).isEqualTo(1);
    assertThat(store.body(id)).contains(body(id, PIN));
    assertThat(cured.bodySha256()).isEqualTo(Hashes.sha256Hex(body(id, PIN)));
  }

  // ---------------------------------------------------------------------------------- probe 2

  @Test
  void a_copy_at_the_pin_cures_a_row_still_received_at_another_version() {
    JdbcInboundEventStore store = store();
    String id = freshId();
    record(store, copy(id, OLD, T0), PIN);

    assertThat(record(store, copy(id, PIN, T1), PIN)).isEqualTo(Recorded.CURED);

    InboundEvent cured = store.find(id).orElseThrow();
    assertThat(cured.state()).isEqualTo(InboundState.RECEIVED);
    assertThat(cured.apiVersion()).isEqualTo(PIN);
    assertThat(cured.cureGeneration()).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------- probe 3

  @Test
  void a_run_on_the_snapshot_before_a_cure_loses_its_write() {
    JdbcInboundEventStore store = store();
    String id = freshId();
    record(store, copy(id, OLD, T0), PIN);
    InboundEvent snapshot = store.find(id).orElseThrow();
    assertThat(record(store, copy(id, PIN, T1), PIN)).isEqualTo(Recorded.CURED);

    // Rule C, on each compare a run makes: the plain store, the stamp, and the held store a run
    // lock holder writes through. The state is still RECEIVED; only the generation moved.
    assertThat(
            store.transitionFrom(
                id,
                snapshot.state(),
                snapshot.cureGeneration(),
                InboundState.REFUSED_VERSION_SKEW,
                ErrorCodes.API_VERSION_SKEW,
                "",
                T1,
                null))
        .isEmpty();
    assertThat(
            store.stampAttempt(
                id, snapshot.state(), snapshot.cureGeneration(), T1, Duration.ofMinutes(1)))
        .isEmpty();
    JdbcInboundEventStore held =
        JdbcInboundEventStore.heldBy(JdbcUnitOfWork.ownConnection(PostgresSupport.dataSource()));
    assertThat(
            held.transitionFrom(
                id,
                snapshot.state(),
                snapshot.cureGeneration(),
                InboundState.REFUSED_VERSION_SKEW,
                ErrorCodes.API_VERSION_SKEW,
                "",
                T1,
                null))
        .isEmpty();

    InboundEvent stored = store.find(id).orElseThrow();
    assertThat(stored.state()).isEqualTo(InboundState.RECEIVED);
    assertThat(stored.apiVersion()).isEqualTo(PIN);
    assertThat(stored.attempts()).isZero();

    // The cured row's own run, which read the new generation, lands.
    assertThat(
            store.stampAttempt(
                id, stored.state(), stored.cureGeneration(), T1, Duration.ofMinutes(1)))
        .isPresent();
  }

  // ---------------------------------------------------------------------------- probes 4 and 18

  @Test
  void two_concurrent_copies_at_the_pin_cure_once() throws Exception {
    JdbcInboundEventStore store = store();
    for (int round = 0; round < 50; round++) {
      String id = refusedAtOld(store);
      List<Recorded> answers =
          race(
              () -> record(store, copy(id, PIN, T1), PIN),
              () -> record(store, copy(id, PIN, T1), PIN));
      assertThat(answers).containsExactlyInAnyOrder(Recorded.CURED, Recorded.DUPLICATE);
      assertThat(store.find(id).orElseThrow().cureGeneration()).isEqualTo(1);
    }
  }

  /**
   * P6 at every isolation level, interleaved by hand: copy A cures inside an open transaction, copy
   * B waits on the row, A commits. At REPEATABLE READ and SERIALIZABLE B's statement raises 40001;
   * the store runs it once more and answers what READ COMMITTED answers.
   */
  @ParameterizedTest
  @ValueSource(
      ints = {
        Connection.TRANSACTION_READ_COMMITTED,
        Connection.TRANSACTION_REPEATABLE_READ,
        Connection.TRANSACTION_SERIALIZABLE
      })
  void two_concurrent_copies_at_the_pin_cure_once_at_every_isolation_level(int level)
      throws Exception {
    try (HikariDataSource pool = poolAt(level);
        Connection a = PostgresSupport.dataSource().getConnection()) {
      JdbcInboundEventStore store = new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(pool));
      String id = refusedAtOld(store());
      a.setAutoCommit(false);
      a.setTransactionIsolation(level);
      JdbcInboundEventStore first = new JdbcInboundEventStore(JdbcUnitOfWork.using(a));
      assertThat(record(first, copy(id, PIN, T1), PIN)).isEqualTo(Recorded.CURED);

      ExecutorService pool2 = Executors.newSingleThreadExecutor();
      try {
        Future<Recorded> second = pool2.submit(() -> record(store, copy(id, PIN, T1), PIN));
        awaitInsertWaiter();
        a.commit();
        assertThat(second.get(30, TimeUnit.SECONDS))
            .as("a second copy at the pin is a duplicate, never a 503")
            .isEqualTo(Recorded.DUPLICATE);
      } finally {
        pool2.shutdownNow();
      }
      assertThat(store.find(id).orElseThrow().cureGeneration()).isEqualTo(1);
    }
  }

  // ---------------------------------------------------------------------------- probes 5 and 18

  @Test
  void a_wrong_copy_racing_a_right_copy_ends_at_the_pin() throws Exception {
    JdbcInboundEventStore store = store();
    for (int round = 0; round < 50; round++) {
      String id = freshId();
      List<Recorded> answers =
          race(
              () -> record(store, copy(id, OLD, T0), PIN),
              () -> record(store, copy(id, PIN, T1), PIN));
      // Either the wrong copy inserted and the right one cured it, or the right copy inserted and
      // the wrong one is a duplicate. Never two rows, never a row at the old version.
      assertThat(answers)
          .isIn(
              List.of(Recorded.FIRST, Recorded.CURED), List.of(Recorded.DUPLICATE, Recorded.FIRST));
      assertThat(store.find(id).orElseThrow().apiVersion()).isEqualTo(PIN);
    }
  }

  /**
   * P7 at every isolation level: the wrong copy's insert is open, the right copy waits on the
   * conflict, the wrong copy commits. The right copy must cure, at every level (D-SKEW-4).
   */
  @ParameterizedTest
  @ValueSource(
      ints = {
        Connection.TRANSACTION_READ_COMMITTED,
        Connection.TRANSACTION_REPEATABLE_READ,
        Connection.TRANSACTION_SERIALIZABLE
      })
  void a_wrong_copy_racing_a_right_copy_ends_at_the_pin_at_every_isolation_level(int level)
      throws Exception {
    try (HikariDataSource pool = poolAt(level);
        Connection a = PostgresSupport.dataSource().getConnection()) {
      JdbcInboundEventStore store = new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(pool));
      String id = freshId();
      a.setAutoCommit(false);
      a.setTransactionIsolation(level);
      JdbcInboundEventStore wrong = new JdbcInboundEventStore(JdbcUnitOfWork.using(a));
      assertThat(record(wrong, copy(id, OLD, T0), PIN)).isEqualTo(Recorded.FIRST);

      ExecutorService pool2 = Executors.newSingleThreadExecutor();
      try {
        Future<Recorded> right = pool2.submit(() -> record(store, copy(id, PIN, T1), PIN));
        awaitInsertWaiter();
        a.commit();
        assertThat(right.get(30, TimeUnit.SECONDS))
            .as("the right copy cures, never a 503 that leaves the row at the old version")
            .isEqualTo(Recorded.CURED);
      } finally {
        pool2.shutdownNow();
      }
      assertThat(store.find(id).orElseThrow().apiVersion()).isEqualTo(PIN);
    }
  }

  // ---------------------------------------------------------------------------------- probe 6

  @Test
  void the_cure_keeps_a_trace_of_the_refused_copy() {
    JdbcInboundEventStore store = store();
    String id = refusedAtOld(store);
    String firstArrival = column(id, "received_at");

    record(store, copy(id, PIN, T1), PIN);

    assertThat(column(id, "cured_from_api_version")).isEqualTo(OLD);
    assertThat(column(id, "cured_from_body_sha256")).isEqualTo(Hashes.sha256Hex(body(id, OLD)));
    assertThat(column(id, "cured_from_received_at")).isEqualTo(firstArrival);
    assertThat(column(id, "cured_at")).isNotNull();
  }

  // ---------------------------------------------------------------------------------- probe 7

  static Stream<Arguments> identities() {
    List<Arguments> cases = new ArrayList<>();
    for (String storedAt : List.of(PIN, OLD)) {
      for (String field : List.of("event_type", "object_id", "livemode", "stripe_account_id")) {
        cases.add(Arguments.of(field, storedAt));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0} differs, stored at {1}")
  @MethodSource("identities")
  void a_copy_with_another_identity_is_refused_and_reported(String field, String storedAt) {
    JdbcInboundEventStore store = store();
    String id = freshId();
    record(store, copy(id, storedAt, T0), PIN);
    if (!storedAt.equals(PIN)) {
      store.transition(
          id, InboundState.REFUSED_VERSION_SKEW, ErrorCodes.API_VERSION_SKEW, "", T0, null);
    }
    InboundEvent before = store.find(id).orElseThrow();
    InboundEvent forged =
        InboundEvent.received(
            new EventIdentity(
                id,
                field.equals("event_type") ? "invoice.voided" : "invoice.finalized",
                PIN,
                !field.equals("livemode"),
                field.equals("stripe_account_id") ? "acct_other" : "",
                field.equals("object_id") ? "in_other" : "in_" + id),
            Mode.LIVE,
            "primary",
            body(id, PIN),
            T1);

    String seller = PostgresSupport.freshSeller("cure");
    JdbcFindingStore findings =
        new JdbcFindingStore(JdbcUnitOfWork.ownConnection(PostgresSupport.dataSource()));
    InboundIntake intake =
        new InboundIntake(
            store, findings, configuration(seller, PIN), Clock.fixed(T1, ZoneOffset.UTC));

    assertThat(intake.record(forged, body(id, PIN))).isEqualTo(Recorded.IDENTITY_MISMATCH);

    InboundEvent after = store.find(id).orElseThrow();
    assertThat(after).as("nothing was written").isEqualTo(before);
    assertThat(findings.open(seller, Mode.LIVE, 10))
        .anySatisfy(
            finding -> {
              assertThat(finding.code()).isEqualTo(ErrorCodes.INBOUND_IDENTITY_MISMATCH);
              assertThat(finding.subjectId()).isEqualTo(id);
            });
  }

  // ---------------------------------------------------------------------------------- probe 8

  @Test
  void a_copy_at_another_version_never_touches_a_row_at_the_pin() {
    JdbcInboundEventStore store = store();
    String id = freshId();
    record(store, copy(id, PIN, T0), PIN);
    InboundEvent before = store.find(id).orElseThrow();

    assertThat(record(store, copy(id, OLD, T1), PIN)).isEqualTo(Recorded.DUPLICATE);

    assertThat(store.find(id).orElseThrow()).isEqualTo(before);
    assertThat(store.body(id)).contains(body(id, PIN));
  }

  // ---------------------------------------------------------------------------------- probe 9

  static Stream<Arguments> duplicates() {
    return Stream.of(
        Arguments.of(PIN, InboundState.RECEIVED, PIN),
        Arguments.of(PIN, InboundState.REFUSED_VERSION_SKEW, PIN),
        Arguments.of(OLD, InboundState.RECEIVED, OLD),
        Arguments.of(OLD, InboundState.REFUSED_VERSION_SKEW, OLD),
        Arguments.of(OLD, InboundState.REFUSED_VERSION_SKEW, OTHER_OLD));
  }

  @ParameterizedTest(name = "stored {1} at {0}, copy at {2}")
  @MethodSource("duplicates")
  void a_second_copy_at_the_same_version_is_a_duplicate(
      String storedAt, InboundState state, String copyAt) {
    JdbcInboundEventStore store = store();
    String id = freshId();
    record(store, copy(id, storedAt, T0), PIN);
    if (state != InboundState.RECEIVED) {
      store.transition(id, state, ErrorCodes.API_VERSION_SKEW, "", T0, null);
    }
    InboundEvent before = store.find(id).orElseThrow();

    assertThat(record(store, copy(id, copyAt, T1), PIN)).isEqualTo(Recorded.DUPLICATE);

    assertThat(store.find(id).orElseThrow()).isEqualTo(before);
  }

  // --------------------------------------------------------------------------------- probe 10

  static Stream<InboundState> pastTheRoute() {
    return Arrays.stream(InboundState.values())
        .filter(s -> s != InboundState.RECEIVED && s != InboundState.REFUSED_VERSION_SKEW);
  }

  @ParameterizedTest
  @MethodSource("pastTheRoute")
  void a_cure_never_touches_a_row_past_the_route(InboundState state) {
    JdbcInboundEventStore store = store();
    String id = freshId();
    record(store, copy(id, OLD, T0), PIN);
    // Set directly: the probe is about the statement, for every state, including the ones no edge
    // reaches from RECEIVED in one step.
    try (Connection c = PostgresSupport.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE einvoice_inbound_event SET state = ? WHERE event_id = ?")) {
      ps.setString(1, state.name());
      ps.setString(2, id);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    InboundEvent before = store.find(id).orElseThrow();

    assertThat(record(store, copy(id, PIN, T1), PIN)).isEqualTo(Recorded.DUPLICATE);

    InboundEvent after = store.find(id).orElseThrow();
    assertThat(after).isEqualTo(before);
    assertThat(after.apiVersion()).isEqualTo(OLD);
  }

  // --------------------------------------------------------------------------------- probe 11

  @Test
  void a_cured_row_is_due_now_and_its_clocks_start_at_the_cure() {
    JdbcInboundEventStore store = createOnFresh("cure_clock");
    String id = freshId();
    record(store, copy(id, OLD, T0), PIN);
    store.transition(
        id, InboundState.REFUSED_VERSION_SKEW, ErrorCodes.API_VERSION_SKEW, "", T0, null);

    record(store, copy(id, PIN, T1), PIN);

    InboundEvent cured = store.find(id).orElseThrow();
    assertThat(cured.receivedAt()).isEqualTo(T1);
    assertThat(cured.updatedAt()).isEqualTo(T1);
    assertThat(cured.dueSince()).isEqualTo(T1);
    assertThat(cured.nextAttemptAt()).as("a fresh insert's next attempt").isEqualTo(T1);
    RunningLimit limits = new RunningLimit(Duration.ofDays(1), Duration.ofDays(7));
    assertThat(store.due(T1, Duration.ofDays(3), limits, 100, PIN))
        .extracting(InboundEvent::eventId)
        .contains(id);
    assertThat(store.stranded(T1, limits, 100))
        .extracting(InboundEvent::eventId)
        .doesNotContain(id);
    // Retention from the cure: the new body arrived then.
    assertThat(store.purgeOlderThan(T0.plus(Duration.ofDays(1)), 100)).isZero();
    assertThat(store.find(id)).isPresent();
  }

  // --------------------------------------------------------------------------------- probe 17

  enum OnCure {
    /** Equal to the value before the cure. */
    KEPT,
    /** Equal to what a fresh insert of the copy writes. */
    FRESH,
    /** Written by the cure for the trace or the generation; checked on its own. */
    CURE
  }

  /** Rule B as a table (design section 9, change 8). A column missing here fails the probe. */
  static final Map<String, OnCure> RULE_B = new LinkedHashMap<>();

  static {
    RULE_B.put("event_id", OnCure.KEPT);
    RULE_B.put("event_type", OnCure.KEPT);
    RULE_B.put("object_id", OnCure.KEPT);
    RULE_B.put("livemode", OnCure.KEPT);
    RULE_B.put("stripe_account_id", OnCure.KEPT);
    RULE_B.put("mode", OnCure.KEPT);
    RULE_B.put("parked_at", OnCure.KEPT);
    RULE_B.put("retried_at", OnCure.KEPT);
    RULE_B.put("api_version", OnCure.FRESH);
    RULE_B.put("body", OnCure.FRESH);
    RULE_B.put("body_sha256", OnCure.FRESH);
    RULE_B.put("signature_key_id", OnCure.FRESH);
    RULE_B.put("seller_id", OnCure.FRESH);
    RULE_B.put("state", OnCure.FRESH);
    RULE_B.put("attempts", OnCure.FRESH);
    RULE_B.put("next_attempt_at", OnCure.FRESH);
    RULE_B.put("last_code", OnCure.FRESH);
    RULE_B.put("last_rule_id", OnCure.FRESH);
    RULE_B.put("received_at", OnCure.FRESH);
    RULE_B.put("updated_at", OnCure.FRESH);
    RULE_B.put("due_since", OnCure.FRESH);
    RULE_B.put("cure_generation", OnCure.CURE);
    RULE_B.put("cured_from_api_version", OnCure.CURE);
    RULE_B.put("cured_from_body_sha256", OnCure.CURE);
    RULE_B.put("cured_from_received_at", OnCure.CURE);
    RULE_B.put("cured_at", OnCure.CURE);
  }

  @Test
  void every_column_of_a_cured_row_is_classified() throws Exception {
    Set<String> columns = new TreeSet<>();
    try (Connection c = PostgresSupport.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema = current_schema()"
                    + " AND table_name = 'einvoice_inbound_event'");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        columns.add(rs.getString(1));
      }
    }
    assertThat(columns)
        .as("every column of einvoice_inbound_event is named by rule B")
        .isEqualTo(new TreeSet<>(RULE_B.keySet()));

    JdbcInboundEventStore store = store();
    String id = freshId();
    // The row before the cure: every column away from its fresh-insert value where it can be.
    record(store, copy(id, OLD, T0), PIN);
    store.bindSeller(id, "bound_seller", T0);
    store.transition(id, InboundState.FETCHED, "", T0, Duration.ofMinutes(5));
    store.transition(
        id,
        InboundState.PARKED,
        ErrorCodes.INVOICE_NOT_FINALISED,
        "rule-x",
        T0,
        Duration.ofMinutes(5));
    store.transition(
        id, InboundState.FAILED_FETCH, ErrorCodes.STRIPE_UNAVAILABLE, T0, Duration.ofMinutes(5));
    store.transition(
        id, InboundState.REFUSED_VERSION_SKEW, ErrorCodes.API_VERSION_SKEW, "rule-y", T0, null);
    Map<String, String> before = row(id);
    assertThat(before.get("parked_at")).isNotNull();
    assertThat(before.get("retried_at")).isNotNull();

    // The copy comes from a node in the other mode, with another key: neither may leak into a
    // kept column, and both must land in a fresh one.
    InboundEvent cure =
        InboundEvent.received(
            new EventIdentity(id, "invoice.finalized", PIN, true, "", "in_" + id),
            Mode.TEST,
            "retiring",
            body(id, PIN),
            T1);
    assertThat(store.record(cure, body(id, PIN), PIN)).isEqualTo(Recorded.CURED);
    Map<String, String> after = row(id);

    // What a fresh insert of the same copy writes, under another id.
    String control = freshId();
    InboundEvent fresh =
        InboundEvent.received(
            new EventIdentity(control, "invoice.finalized", PIN, true, "", "in_" + id),
            Mode.TEST,
            "retiring",
            body(id, PIN),
            T1);
    assertThat(store.record(fresh, body(id, PIN), PIN)).isEqualTo(Recorded.FIRST);
    Map<String, String> inserted = row(control);

    for (Map.Entry<String, OnCure> e : RULE_B.entrySet()) {
      String col = e.getKey();
      switch (e.getValue()) {
        case KEPT -> assertThat(after.get(col)).as("%s is kept", col).isEqualTo(before.get(col));
        case FRESH ->
            assertThat(after.get(col))
                .as("%s takes the fresh-insert value", col)
                .isEqualTo(inserted.get(col));
        case CURE -> {}
      }
    }
    assertThat(after.get("cure_generation")).isEqualTo("1");
    assertThat(after.get("cured_from_api_version")).isEqualTo(before.get("api_version"));
    assertThat(after.get("cured_from_body_sha256")).isEqualTo(before.get("body_sha256"));
    assertThat(after.get("cured_from_received_at")).isEqualTo(before.get("received_at"));
    assertThat(after.get("cured_at")).isNotNull();
  }

  private static Map<String, String> row(String id) throws SQLException {
    Map<String, String> values = new LinkedHashMap<>();
    try (Connection c = PostgresSupport.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT * FROM einvoice_inbound_event WHERE event_id = ?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
          Object value = rs.getObject(i);
          values.put(
              rs.getMetaData().getColumnName(i),
              value instanceof byte[] bytes ? Hashes.sha256Hex(bytes) : rs.getString(i));
        }
      }
    }
    return values;
  }

  // --------------------------------------------------------------------------------- probe 19

  @Test
  void a_run_that_read_before_two_cures_loses_its_write() {
    // Two pins live at once in one fleet: a node pinned at NEW cures to NEW, a node pinned at OLD
    // cures back to OLD. The version is the one the run read; only the generation tells.
    String newPin = PIN;
    JdbcInboundEventStore store = store();
    String id = freshId();
    record(store, copy(id, OLD, T0), newPin);
    InboundEvent snapshot = store.find(id).orElseThrow();

    assertThat(record(store, copy(id, newPin, T1), newPin)).isEqualTo(Recorded.CURED);
    assertThat(record(store, copy(id, OLD, T1.plusSeconds(1)), OLD)).isEqualTo(Recorded.CURED);
    InboundEvent twice = store.find(id).orElseThrow();
    assertThat(twice.apiVersion()).isEqualTo(snapshot.apiVersion());
    assertThat(twice.state()).isEqualTo(snapshot.state());
    assertThat(twice.cureGeneration()).isEqualTo(2);

    assertThat(
            store.transitionFrom(
                id,
                snapshot.state(),
                snapshot.cureGeneration(),
                InboundState.REFUSED_VERSION_SKEW,
                ErrorCodes.API_VERSION_SKEW,
                "",
                T1,
                null))
        .isEmpty();
    assertThat(
            store.stampAttempt(
                id, snapshot.state(), snapshot.cureGeneration(), T1, Duration.ofMinutes(1)))
        .isEmpty();
    assertThat(store.find(id).orElseThrow().state()).isEqualTo(InboundState.RECEIVED);
  }

  // --------------------------------------------------------------------------------- probe 20

  private static final Pattern IDENTITY_WRITE =
      Pattern.compile("(?<![.\\w])(event_type|object_id|livemode|stripe_account_id)\\s*=");

  @Test
  void no_statement_writes_the_identity_columns_after_the_insert() throws Exception {
    int setClauses = 0;
    for (String file : List.of("JdbcInboundEventStore.java", "JdbcReprocessLedger.java")) {
      String sql = literals(sourceOf(file));
      Matcher update =
          Pattern.compile("(UPDATE einvoice_inbound_event|DO UPDATE)\\s+SET\\s").matcher(sql);
      while (update.find()) {
        int end = sql.indexOf(" WHERE ", update.end());
        String set = sql.substring(update.end(), end < 0 ? sql.length() : end);
        setClauses++;
        assertThat(IDENTITY_WRITE.matcher(set).find())
            .as("%s writes an identity column in: %s", file, set)
            .isFalse();
      }
    }
    // The cure, the transition, the expiry, the stamp, the re-arm, the two re-opens, the seller
    // binding and the rule id: a scan that finds fewer has stopped reading the statements.
    assertThat(setClauses).isGreaterThanOrEqualTo(9);
  }

  private static String sourceOf(String file) throws Exception {
    Path dir = Path.of("src/main/java/com/housedevinci/einvoice/adapter/jdbc");
    return Files.readString(dir.resolve(file));
  }

  /** Every string literal of a source file, in order and joined: the SQL as the JVM builds it. */
  private static String literals(String source) {
    // Comments first: prose in a comment may hold a lone quote, which would pair the wrong ends.
    String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    StringBuilder sql = new StringBuilder();
    Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(code);
    while (m.find()) {
      sql.append(m.group(1));
    }
    return sql.toString();
  }

  // --------------------------------------------------------------------------------- probe 21

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  void record_refuses_a_blank_pin(String pin) {
    JdbcInboundEventStore store = store();
    String id = freshId();
    assertThatThrownBy(() -> store.record(copy(id, PIN, T0), body(id, PIN), pin))
        .isInstanceOf(EInvoiceException.class)
        .extracting(e -> ((EInvoiceException) e).code())
        .isEqualTo(ErrorCodes.CONFIG);
    assertThatThrownBy(() -> store.record(copy(id, PIN, T0), body(id, PIN), null))
        .isInstanceOf(EInvoiceException.class)
        .extracting(e -> ((EInvoiceException) e).code())
        .isEqualTo(ErrorCodes.CONFIG);
    assertThat(store.find(id)).as("refused before any SQL").isEmpty();
  }

  // --------------------------------------------------------------------------------- probe 22

  @Test
  void the_port_compares_and_record_have_no_default_method() throws Exception {
    Method record =
        InboundEventStore.class.getMethod("record", InboundEvent.class, byte[].class, String.class);
    Method transitionFrom =
        InboundEventStore.class.getMethod(
            "transitionFrom",
            String.class,
            InboundState.class,
            int.class,
            InboundState.class,
            String.class,
            String.class,
            Instant.class,
            Duration.class);
    Method stamp =
        InboundEventStore.class.getMethod(
            "stampAttempt",
            String.class,
            InboundState.class,
            int.class,
            Instant.class,
            Duration.class);
    for (Method m : List.of(record, transitionFrom, stamp)) {
      assertThat(m.isDefault()).as("%s has no default method", m.getName()).isFalse();
    }
    assertThat(record.getReturnType()).isEqualTo(Recorded.class);
    // No overload without the generation or the pin survives: a bridge to the old shape would let
    // a host store keep compiling without the check.
    for (Method m : InboundEventStore.class.getMethods()) {
      if (m.getName().equals("record")) {
        assertThat(m).isEqualTo(record);
      }
      if (m.getName().equals("transitionFrom")) {
        assertThat(m).isEqualTo(transitionFrom);
      }
      if (m.getName().equals("stampAttempt")) {
        assertThat(m).isEqualTo(stamp);
      }
    }
  }

  // ---------------------------------------------------------------------------------- helpers

  static IssuanceUnitOfWork.Configuration configuration(String seller, String pin) {
    return new IssuanceUnitOfWork.Configuration(
        seller,
        "",
        Mode.LIVE,
        "DEFAULT",
        true,
        ZoneId.of("Europe/Paris"),
        pin,
        "fr-2026.1",
        Duration.ofMinutes(1),
        Duration.ofHours(1),
        Optional.empty(),
        false);
  }

  private static JdbcInboundEventStore createOnFresh(String hint) {
    DataSource fresh = PostgresSupport.freshDatabase(hint);
    return new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(fresh));
  }

  private static HikariDataSource poolAt(int level) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(PostgresSupport.jdbcUrl());
    config.setUsername(PostgresSupport.username());
    config.setPassword(PostgresSupport.password());
    config.setMaximumPoolSize(2);
    config.setTransactionIsolation(
        switch (level) {
          case Connection.TRANSACTION_SERIALIZABLE -> "TRANSACTION_SERIALIZABLE";
          case Connection.TRANSACTION_REPEATABLE_READ -> "TRANSACTION_REPEATABLE_READ";
          default -> "TRANSACTION_READ_COMMITTED";
        });
    return new HikariDataSource(config);
  }

  private static List<Recorded> race(Callable<Recorded> a, Callable<Recorded> b) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Recorded> fa =
          pool.submit(
              () -> {
                start.await();
                return a.call();
              });
      Future<Recorded> fb =
          pool.submit(
              () -> {
                start.await();
                return b.call();
              });
      start.countDown();
      return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  /** Waits until some session's insert into the inbound table waits on a lock. */
  static void awaitInsertWaiter() throws Exception {
    try (Connection probe = PostgresSupport.dataSource().getConnection()) {
      for (int i = 0; i < 500; i++) {
        try (PreparedStatement ps =
                probe.prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                        + " AND query LIKE 'INSERT INTO einvoice_inbound_event%'");
            ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (rs.getLong(1) > 0) {
            return;
          }
        }
        Thread.sleep(20);
      }
      throw new IllegalStateException("the second copy never waited on the first");
    }
  }
}
