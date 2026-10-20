package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.instant;
import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.ts;

import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.RunningLimit;
import com.housedevinci.einvoice.domain.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The durable inbound record in PostgreSQL: plain JDBC, no JPA mapping, every statement through the
 * unit of work so a host transaction is joined rather than raced.
 *
 * <p>This is the one table in the module that is <b>not</b> append-only and <b>is</b> purgeable
 * (I-07), and the two reasons are worth keeping in view of each other: the issuance row is legal
 * evidence, this row is a transport artifact holding the buyer's own details. So the guards here
 * are a state machine and a retention ceiling rather than a trigger that refuses every DELETE.
 */
public final class JdbcInboundEventStore implements InboundEventStore {

  static final String COLUMNS =
      "event_id, event_type, object_id, api_version, livemode, stripe_account_id, seller_id,"
          + " mode, signature_key_id, received_at, updated_at, state, attempts, next_attempt_at,"
          + " last_code, last_rule_id, body_sha256, (body IS NOT NULL) AS body_present, due_since";

  static final String INSERT =
      "INSERT INTO einvoice_inbound_event (event_id, event_type, object_id, api_version, livemode,"
          + " stripe_account_id, seller_id, mode, signature_key_id, received_at, updated_at,"
          + " state, attempts, next_attempt_at, last_code, last_rule_id, body, body_sha256,"
          + " due_since) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
          + " ON CONFLICT (event_id) DO NOTHING";

  static final String FIND =
      "SELECT " + COLUMNS + " FROM einvoice_inbound_event WHERE event_id = ?";

  static final String FIND_FOR_UPDATE = FIND + " FOR UPDATE";

  /**
   * The state move. It also keeps two marks that later transitions never clear (D-PS1-27, design
   * 5.6): the first time the event was parked and the first time it failed with a retry scheduled.
   * The allocator reads them to say why an invoice was numbered after its supply day; the state and
   * the last code are overwritten by the next run, so they cannot say it.
   *
   * <p>It also restarts the time-limit clock {@code due_since} on progress (QUESTIONS 29, F-1), by
   * {@link RunningLimit#restartsClock}. The rule depends on whether the row was ever parked, and
   * {@code parked_at} on the right of a {@code SET} is the value before this statement, so the two
   * answers are bound and the row picks its own: no read, no race with the {@code parked_at} mark.
   */
  static final String TRANSITION =
      "UPDATE einvoice_inbound_event SET state = ?, last_code = ?, updated_at = ?,"
          + " attempts = attempts + 1, next_attempt_at = ?, body = CASE WHEN ? THEN body ELSE NULL"
          + " END, parked_at = CASE WHEN ? THEN coalesce(parked_at, ?) ELSE parked_at END,"
          + " retried_at = CASE WHEN ? THEN coalesce(retried_at, ?) ELSE retried_at END,"
          + " due_since = CASE WHEN (CASE WHEN parked_at IS NULL THEN ? ELSE ? END) THEN ?"
          + " ELSE due_since END"
          + " WHERE event_id = ?";

  /** {@link #TRANSITION} with the compare in the statement: the state the move was decided on. */
  static final String TRANSITION_FROM = TRANSITION + " AND state = ?";

  /**
   * Which clock a running row is measured against (QUESTIONS 29): the parked limit for a parked row
   * and for the {@code FETCHED} hop of a row that was ever parked, the running limit for every
   * other. Two parameters: the parked cutoff, then the running cutoff. Mirrors {@link
   * RunningLimit#parkedLimitGoverns}.
   */
  static final String CUTOFF =
      "CASE WHEN state = 'PARKED' OR (state = 'FETCHED' AND parked_at IS NOT NULL)"
          + " THEN ?::timestamptz ELSE ?::timestamptz END";

  /**
   * Running rows past their limit. A null clock is a row 0.1.0 wrote during the upgrade (F-2): it
   * is read as "not past any limit", never as the epoch, so it stays in {@link #DUE} instead.
   */
  static final String STRANDED =
      "SELECT "
          + COLUMNS
          + " FROM einvoice_inbound_event"
          + " WHERE state IN ('RECEIVED','FETCHED','MAPPED','PARKED')"
          + " AND due_since IS NOT NULL AND due_since < "
          + CUTOFF
          + " ORDER BY due_since LIMIT ?";

  /**
   * The expiry. Conditional on the state and the clock the sweep read: a row that moved, or moved
   * and came back to the same state, has another clock and is left alone. It keeps its own compare
   * instead of {@code transitionFrom}, which compares the state only.
   */
  static final String EXPIRE =
      "UPDATE einvoice_inbound_event SET state = '"
          + InboundState.EXPIRED.name()
          + "', last_code = ?, updated_at = ?, attempts = attempts + 1, next_attempt_at = NULL,"
          + " body = NULL WHERE event_id = ? AND state = ? AND due_since = ?";

  /**
   * The finding of an expiry. Unlike the ordinary upsert it opens an acknowledged finding again:
   * the operator acknowledged an earlier expiry of this event, and this is a new one. The earlier
   * reason stays on the row.
   */
  static final String RECORD_EXPIRY_FINDING =
      "INSERT INTO einvoice_finding (seller_id, mode, code, subject_id, first_seen, last_seen)"
          + " VALUES (?,?,?,?,?,?)"
          + " ON CONFLICT (seller_id, mode, code, subject_id) DO UPDATE"
          + " SET last_seen = EXCLUDED.last_seen, acknowledged_at = NULL";

  /**
   * The privileged re-open (QUESTIONS 26), run by {@code JdbcReprocessLedger} in the same
   * transaction as the record of who asked (D9-03) - which is why the statement lives here, beside
   * the table it writes, and is executed there, beside the row that attributes it.
   *
   * <p>Conditional on the state, so the eligibility check and the write are one statement and two
   * simultaneous operator calls produce exactly one winner. {@code attempts} is cleared because
   * this is a new decision by a human, not the next attempt of the old one. The time-limit clock
   * {@code due_since} starts again (QUESTIONS 29): an expired row whose clock ran from its old
   * value would be expired again on the next sweep without ever running. The retry ceiling of a
   * retryable terminal is still measured from arrival.
   *
   * <p>Parameters: last code, updated at, due since, event id.
   */
  static final String REOPEN_FOR_REPROCESS =
      "UPDATE einvoice_inbound_event SET state = 'RECEIVED', last_code = ?, updated_at = ?,"
          + " due_since = ?, attempts = 0, next_attempt_at = NULL"
          + " WHERE event_id = ? AND (state IN ('"
          + InboundState.FAILED_MAPPING.name()
          + "', '"
          + InboundState.EXPIRED.name()
          + "') OR (state = '"
          + InboundState.FAILED_ISSUANCE.name()
          + "' AND last_code = '"
          + com.housedevinci.einvoice.application.IssuanceReprocess.ELIGIBLE_ISSUANCE_CODE
          + "'))";

  static final String BIND_SELLER =
      "UPDATE einvoice_inbound_event SET seller_id = ?, updated_at = ? WHERE event_id = ?";

  /**
   * The sweeper's query. Not terminal, or a retryable terminal whose next attempt has come and
   * which is still inside the retry ceiling measured from arrival (I-08). Ordered oldest first, so
   * a backlog drains in the order the sales happened.
   */
  static final String DUE =
      "SELECT "
          + COLUMNS
          + " FROM einvoice_inbound_event WHERE"
          // Still running: picked up whenever its time has come, and immediately when it has none,
          // while it is inside its time limit (QUESTIONS 29). >= here and < in STRANDED: a clock
          // exactly on the cutoff is in one of the two, never in neither (F-3).
          + " (state IN ('RECEIVED','FETCHED','MAPPED','PARKED')"
          + "   AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
          + "   AND (due_since IS NULL OR due_since >= "
          + CUTOFF
          + "))"
          // A retryable terminal is re-picked only when a next attempt was actually scheduled. A
          // null there is the pipeline saying "this one cannot improve by running again" - an
          // archive conflict, a tampered object, a validation refusal - and it must not be read as
          // "due now", which is what the same column means for a row that is still running.
          + " OR (state IN ('FAILED_FETCH','FAILED_ISSUANCE') AND received_at > ?"
          + "   AND next_attempt_at IS NOT NULL AND next_attempt_at <= ?)"
          // D2-03: a version-skew refusal a configuration change has actually cured. REFUSED_MODE
          // and REFUSED_ACCOUNT are deliberately absent - neither is cured by an upgrade, and
          // re-running either silently would be worse than leaving it for an operator.
          + " OR (state = 'REFUSED_VERSION_SKEW' AND api_version = ?)"
          + " ORDER BY received_at LIMIT ?";

  private final JdbcUnitOfWork unitOfWork;

  public JdbcInboundEventStore(JdbcUnitOfWork unitOfWork) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public boolean record(InboundEvent event, byte[] body) {
    return unitOfWork.inTransaction(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(INSERT)) {
            int i = 1;
            ps.setString(i++, event.eventId());
            ps.setString(i++, event.type());
            ps.setString(i++, event.objectId());
            ps.setString(i++, event.apiVersion());
            ps.setBoolean(i++, event.livemode());
            ps.setString(i++, event.stripeAccountId());
            ps.setString(i++, event.sellerId());
            ps.setString(i++, event.mode().wire());
            ps.setString(i++, event.signatureKeyId());
            ps.setObject(i++, ts(event.receivedAt()));
            ps.setObject(i++, ts(event.updatedAt()));
            ps.setString(i++, event.state().name());
            ps.setInt(i++, event.attempts());
            ps.setObject(i++, ts(event.nextAttemptAt()));
            ps.setString(i++, event.lastCode());
            ps.setString(i++, "");
            ps.setBytes(i++, body);
            ps.setString(i++, event.bodySha256());
            ps.setObject(i, ts(event.dueSinceInstant().orElse(event.receivedAt())));
            // ON CONFLICT DO NOTHING: a redelivery of an event we already hold is a no-op that is
            // still answered 200. Stripe delivers at least once and says so.
            return ps.executeUpdate() == 1;
          }
        });
  }

  @Override
  public Optional<InboundEvent> find(String eventId) {
    return unitOfWork.inReadUnit(unit -> find(unit.connection(), eventId, false));
  }

  private Optional<InboundEvent> find(Connection c, String eventId, boolean forUpdate)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(forUpdate ? FIND_FOR_UPDATE : FIND)) {
      ps.setString(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(read(rs)) : Optional.empty();
      }
    }
  }

  @Override
  public Optional<byte[]> body(String eventId) {
    return unitOfWork.inReadUnit(
        unit -> {
          try (PreparedStatement ps =
              unit.connection()
                  .prepareStatement("SELECT body FROM einvoice_inbound_event WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.ofNullable(rs.getBytes(1)) : Optional.<byte[]>empty();
            }
          }
        });
  }

  @Override
  public InboundEvent transition(
      String eventId,
      InboundState next,
      String code,
      String ruleId,
      Instant now,
      Duration retryIn) {
    return move(eventId, Optional.empty(), next, code, ruleId, now, retryIn)
        .orElseThrow(
            () ->
                new EInvoiceException(
                    ErrorCodes.INBOUND_UNREADABLE, "no inbound event is recorded under that id"));
  }

  @Override
  public Optional<InboundEvent> transitionFrom(
      String eventId,
      InboundState expected,
      InboundState next,
      String code,
      String ruleId,
      Instant now,
      Duration retryIn) {
    Objects.requireNonNull(expected, "expected");
    return move(eventId, Optional.of(expected), next, code, ruleId, now, retryIn);
  }

  /**
   * The one state move. With an expected state it is a compare-and-set (D-PR9-F1): the row lock is
   * taken, the state compared, and the update itself repeats the compare, so a row that moved is
   * left exactly as it is and the answer is empty.
   *
   * <p>At REPEATABLE READ and SERIALIZABLE the locking read of a row another transaction moved and
   * committed raises SQLState {@code 40001} instead of re-reading (D-PR45-2). When this store owns
   * the connection, the whole move is run once more in a fresh transaction: the new snapshot sees
   * the committed move, so the compare answers empty or the move lands. A second {@code 40001}
   * stays an unavailable-store error. When the transaction is the caller's, the error passes
   * through unchanged: that transaction is already aborted and cannot be retried from here.
   */
  private Optional<InboundEvent> move(
      String eventId,
      Optional<InboundState> expected,
      InboundState next,
      String code,
      String ruleId,
      Instant now,
      Duration retryIn) {
    try {
      return attempt(eventId, expected, next, code, ruleId, now, retryIn, true);
    } catch (SnapshotLost first) {
      return attempt(eventId, expected, next, code, ruleId, now, retryIn, false);
    }
  }

  /** Raised inside an owned transaction only; never leaves {@link #move}. */
  private static final class SnapshotLost extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SnapshotLost() {
      super(null, null, false, false);
    }
  }

  private Optional<InboundEvent> attempt(
      String eventId,
      Optional<InboundState> expected,
      InboundState next,
      String code,
      String ruleId,
      Instant now,
      Duration retryIn,
      boolean mayRetry) {
    return unitOfWork.inTransaction(
        unit -> {
          try {
            return run(unit, eventId, expected, next, code, ruleId, now, retryIn);
          } catch (SQLException e) {
            if (mayRetry && !unit.joined() && "40001".equals(e.getSQLState())) {
              throw new SnapshotLost();
            }
            throw e;
          }
        });
  }

  private Optional<InboundEvent> run(
      JdbcUnitOfWork.Unit unit,
      String eventId,
      Optional<InboundState> expected,
      InboundState next,
      String code,
      String ruleId,
      Instant now,
      Duration retryIn)
      throws SQLException {
    Connection c = unit.connection();
    InboundEvent current =
        find(c, eventId, true)
            .orElseThrow(
                () ->
                    new EInvoiceException(
                        ErrorCodes.INBOUND_UNREADABLE,
                        "no inbound event is recorded under that id"));
    if (expected.isPresent() && current.state() != expected.get()) {
      return Optional.<InboundEvent>empty();
    }
    // Refused in the enum, here, for the same reason the issuance transition is: a worker
    // and a sweeper can both reach a conclusion about one row, and the second one to write
    // must not be able to move it backwards.
    InboundState resolved = current.state().transitionTo(next);
    Instant when = Timestamps.toStorage(now);
    try (PreparedStatement ps = c.prepareStatement(TRANSITION_FROM)) {
      int i = 1;
      ps.setString(i++, resolved.name());
      ps.setString(i++, code == null ? "" : code);
      ps.setObject(i++, ts(when));
      if (retryIn == null) {
        ps.setNull(i++, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        ps.setObject(i++, ts(Timestamps.toStorage(now.plus(retryIn))));
      }
      // I-07: the state machine decides whether the body survives, not the caller.
      ps.setBoolean(i++, resolved.keepsBody());
      ps.setBoolean(i++, resolved == InboundState.PARKED);
      ps.setObject(i++, ts(when));
      ps.setBoolean(
          i++,
          retryIn != null
              && (resolved == InboundState.FAILED_FETCH
                  || resolved == InboundState.FAILED_ISSUANCE));
      ps.setObject(i++, ts(when));
      ps.setBoolean(i++, RunningLimit.restartsClock(current.state(), resolved, false));
      ps.setBoolean(i++, RunningLimit.restartsClock(current.state(), resolved, true));
      ps.setObject(i++, ts(when));
      ps.setString(i++, eventId);
      // The row is locked, so this repeats the compare above rather than racing it: the
      // statement itself refuses a row whose state is not the one the move was decided on.
      ps.setString(i, current.state().name());
      if (ps.executeUpdate() != 1) {
        throw new EInvoiceException(
            ErrorCodes.INBOUND_UNREADABLE, "the inbound event changed under its own row lock");
      }
    }
    if (ruleId != null && !ruleId.isBlank()) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE einvoice_inbound_event SET last_rule_id = ? WHERE event_id = ?")) {
        ps.setString(1, ruleId);
        ps.setString(2, eventId);
        ps.executeUpdate();
      }
    }
    return find(c, eventId, false);
  }

  @Override
  public InboundEvent bindSeller(String eventId, String sellerId, Instant now) {
    return unitOfWork.inTransaction(
        unit -> {
          Connection c = unit.connection();
          try (PreparedStatement ps = c.prepareStatement(BIND_SELLER)) {
            ps.setString(1, sellerId);
            ps.setObject(2, ts(Timestamps.toStorage(now)));
            ps.setString(3, eventId);
            ps.executeUpdate();
          }
          return find(c, eventId, false)
              .orElseThrow(
                  () ->
                      new EInvoiceException(
                          ErrorCodes.INBOUND_UNREADABLE,
                          "no inbound event is recorded under that id"));
        });
  }

  @Override
  public List<InboundEvent> due(
      Instant now,
      Duration retryCeiling,
      RunningLimit runningLimit,
      int limit,
      String pinnedApiVersion) {
    Objects.requireNonNull(runningLimit, "runningLimit");
    return unitOfWork.inReadUnit(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(DUE)) {
            int i = 1;
            ps.setObject(i++, ts(now));
            ps.setObject(i++, ts(runningLimit.parkedCutoff(now)));
            ps.setObject(i++, ts(runningLimit.runningCutoff(now)));
            ps.setObject(i++, ts(now.minus(retryCeiling)));
            ps.setObject(i++, ts(now));
            ps.setString(i++, pinnedApiVersion == null ? "" : pinnedApiVersion);
            ps.setInt(i, Math.max(1, limit));
            return readAll(ps);
          }
        });
  }

  @Override
  public List<InboundEvent> stranded(Instant now, RunningLimit runningLimit, int limit) {
    Objects.requireNonNull(runningLimit, "runningLimit");
    return unitOfWork.inReadUnit(
        unit -> {
          try (PreparedStatement ps = unit.connection().prepareStatement(STRANDED)) {
            ps.setObject(1, ts(runningLimit.parkedCutoff(now)));
            ps.setObject(2, ts(runningLimit.runningCutoff(now)));
            ps.setInt(3, Math.max(1, limit));
            return readAll(ps);
          }
        });
  }

  @Override
  public boolean expire(InboundEvent stranded, ComplianceFinding finding, Instant now) {
    Objects.requireNonNull(finding, "finding");
    Instant clock =
        stranded
            .dueSinceInstant()
            .orElseThrow(
                () ->
                    new EInvoiceException(
                        ErrorCodes.INVALID, "a row with no time-limit clock is never stranded"));
    if (!stranded.state().allowedNext().contains(InboundState.EXPIRED)) {
      // Not a running state: nothing to expire, and never an exception from the sweep's loop.
      return false;
    }
    return unitOfWork.inTransaction(
        unit -> {
          Connection c = unit.connection();
          try (PreparedStatement ps = c.prepareStatement(EXPIRE)) {
            int i = 1;
            ps.setString(i++, ErrorCodes.INBOUND_CEILING_REACHED);
            ps.setObject(i++, ts(Timestamps.toStorage(now)));
            ps.setString(i++, stranded.eventId());
            ps.setString(i++, stranded.state().name());
            ps.setObject(i, ts(clock));
            if (ps.executeUpdate() != 1) {
              return false;
            }
          }
          try (PreparedStatement ps = c.prepareStatement(RECORD_EXPIRY_FINDING)) {
            int i = 1;
            ps.setString(i++, finding.sellerId());
            ps.setString(i++, finding.mode().wire());
            ps.setString(i++, finding.code());
            ps.setString(i++, finding.subjectId());
            ps.setObject(i++, ts(finding.firstSeen()));
            ps.setObject(i, ts(finding.lastSeen()));
            ps.executeUpdate();
          }
          return true;
        });
  }

  private static List<InboundEvent> readAll(PreparedStatement ps) throws SQLException {
    List<InboundEvent> rows = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        rows.add(read(rs));
      }
    }
    return List.copyOf(rows);
  }

  @Override
  public int purgeOlderThan(Instant cutoff, int limit) {
    return unitOfWork.inTransaction(
        unit -> {
          try (PreparedStatement ps =
              unit.connection()
                  .prepareStatement(
                      "DELETE FROM einvoice_inbound_event WHERE event_id IN ("
                          + " SELECT event_id FROM einvoice_inbound_event"
                          + " WHERE received_at < ? ORDER BY received_at LIMIT ?)")) {
            ps.setObject(1, ts(cutoff));
            ps.setInt(2, Math.max(1, limit));
            return ps.executeUpdate();
          }
        });
  }

  @Override
  public List<StateCount> countsByState() {
    return unitOfWork.inReadUnit(
        unit -> {
          List<StateCount> counts = new ArrayList<>();
          try (PreparedStatement ps =
                  unit.connection()
                      .prepareStatement(
                          "SELECT state, count(*), min(received_at) FROM einvoice_inbound_event"
                              + " GROUP BY state ORDER BY state");
              ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              counts.add(
                  new StateCount(
                      InboundState.of(rs.getString(1)),
                      rs.getLong(2),
                      instant(rs.getObject(3, OffsetDateTime.class))));
            }
          }
          return List.copyOf(counts);
        });
  }

  private static InboundEvent read(ResultSet rs) throws SQLException {
    return new InboundEvent(
        rs.getString("event_id"),
        rs.getString("event_type"),
        rs.getString("object_id"),
        rs.getString("api_version"),
        rs.getBoolean("livemode"),
        rs.getString("stripe_account_id"),
        rs.getString("seller_id"),
        Mode.of(rs.getString("mode")),
        rs.getString("signature_key_id"),
        instant(rs.getObject("received_at", OffsetDateTime.class)),
        instant(rs.getObject("updated_at", OffsetDateTime.class)),
        InboundState.of(rs.getString("state")),
        rs.getInt("attempts"),
        instant(rs.getObject("next_attempt_at", OffsetDateTime.class)),
        rs.getString("last_code"),
        rs.getString("body_sha256"),
        rs.getBoolean("body_present"),
        instant(rs.getObject("due_since", OffsetDateTime.class)));
  }

  @Override
  public Optional<String> lastRuleId(String eventId) {
    return unitOfWork.inReadUnit(
        unit -> {
          try (PreparedStatement ps =
              unit.connection()
                  .prepareStatement(
                      "SELECT last_rule_id FROM einvoice_inbound_event WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
              if (rs.next()) {
                String value = rs.getString(1);
                return value == null || value.isEmpty()
                    ? Optional.<String>empty()
                    : Optional.of(value);
              }
              return Optional.<String>empty();
            }
          }
        });
  }
}
