package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.instant;
import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.ts;

import com.housedevinci.einvoice.application.InboundEventStore;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.InboundState;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
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
          + " last_code, last_rule_id, body_sha256, (body IS NOT NULL) AS body_present";

  static final String INSERT =
      "INSERT INTO einvoice_inbound_event (event_id, event_type, object_id, api_version, livemode,"
          + " stripe_account_id, seller_id, mode, signature_key_id, received_at, updated_at,"
          + " state, attempts, next_attempt_at, last_code, last_rule_id, body, body_sha256)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (event_id) DO NOTHING";

  static final String FIND =
      "SELECT " + COLUMNS + " FROM einvoice_inbound_event WHERE event_id = ?";

  static final String FIND_FOR_UPDATE = FIND + " FOR UPDATE";

  /**
   * The state move. It also keeps two marks that later transitions never clear (D-PS1-27, design
   * 5.6): the first time the event was parked and the first time it failed with a retry scheduled.
   * The allocator reads them to say why an invoice was numbered after its supply day; the state and
   * the last code are overwritten by the next run, so they cannot say it.
   */
  static final String TRANSITION =
      "UPDATE einvoice_inbound_event SET state = ?, last_code = ?, updated_at = ?,"
          + " attempts = attempts + 1, next_attempt_at = ?, body = CASE WHEN ? THEN body ELSE NULL"
          + " END, parked_at = CASE WHEN ? THEN coalesce(parked_at, ?) ELSE parked_at END,"
          + " retried_at = CASE WHEN ? THEN coalesce(retried_at, ?) ELSE retried_at END"
          + " WHERE event_id = ?";

  /** {@link #TRANSITION} with the compare in the statement: the state the move was decided on. */
  static final String TRANSITION_FROM = TRANSITION + " AND state = ?";

  /**
   * A conclusion written by a run that holds the run lock (D-ORPE-3): no {@code + 1}. One run is
   * one attempt, and the run's only {@code + 1} is its stamp. Writes outside a run keep {@link
   * #TRANSITION_FROM}.
   */
  static final String HELD_TRANSITION_FROM =
      TRANSITION_FROM.replace(" attempts = attempts + 1,", "");

  /**
   * The stamp of a run that took the run lock (D-PR45-1): one attempt more, and the time a
   * successor may start if this run dies. No state change; a compare on the state the run decided
   * on.
   */
  static final String STAMP_ATTEMPT =
      "UPDATE einvoice_inbound_event SET attempts = attempts + 1, next_attempt_at = ?,"
          + " updated_at = ? WHERE event_id = ? AND state = ?";

  /**
   * D-SRE-7: a row whose invoice holds an open number (numbered, archiving or failed archive: the
   * states whose {@code IssuanceState.open()} is true) outlives retention. A burned, voided or
   * issued number has nothing to resume, so its row leaves at retention like any other (D-PR50-1).
   * The reprocess needs the row to resume the number; the stuck check keeps naming it. Any seller
   * and mode, like every "does this row hold a number" question (D-SRE-6). Repeated in the {@code
   * DELETE}, so a number allocated between the candidate read and the delete still keeps its row.
   */
  static final String OPEN_NUMBER_KEEPS_ROW =
      " AND NOT EXISTS (SELECT 1 FROM einvoice_issuance i WHERE i.stripe_invoice_id = e.object_id"
          + " AND i.state IN ("
          + java.util.Arrays.stream(IssuanceState.values())
              .filter(IssuanceState::open)
              .map(state -> "'" + state.name() + "'")
              .collect(java.util.stream.Collectors.joining(","))
          + "))";

  /**
   * D-SRE-3: the re-arm of rows stopped on a refused key, scoped to one mode and account, and only
   * where no next attempt is set (a live run's stamp is never moved).
   */
  static final String REARM_ACCESS_REFUSED =
      "UPDATE einvoice_inbound_event SET next_attempt_at = ?, updated_at = ?"
          + " WHERE last_code = '"
          + ErrorCodes.STRIPE_ACCESS_REFUSED
          + "' AND state IN ('"
          + InboundState.FAILED_FETCH.name()
          + "','"
          + InboundState.FAILED_ISSUANCE.name()
          + "') AND next_attempt_at IS NULL AND livemode = ? AND stripe_account_id = ?";

  /** D-SRE-5a: the rows the reconciliation pass names, one finding each. */
  static final String ACCESS_REFUSED_ROWS =
      "SELECT event_id FROM einvoice_inbound_event WHERE seller_id = ? AND mode = ?"
          + " AND last_code = '"
          + ErrorCodes.STRIPE_ACCESS_REFUSED
          + "' AND state IN ('"
          + InboundState.FAILED_FETCH.name()
          + "','"
          + InboundState.FAILED_ISSUANCE.name()
          + "') AND next_attempt_at IS NULL ORDER BY received_at LIMIT ?";

  /** The retention purge's candidates; each is deleted under the run key (design row 10). */
  static final String PURGE_CANDIDATES =
      "SELECT e.event_id, e.object_id FROM einvoice_inbound_event e WHERE e.received_at < ?"
          + OPEN_NUMBER_KEEPS_ROW
          + " ORDER BY e.received_at LIMIT ?";

  /** Tried, never waited for: a row whose run is live is skipped this hour. */
  static final String PURGE_TRY_RUN_KEY = "SELECT pg_catalog.pg_try_advisory_xact_lock(?, ?)";

  static final String PURGE_ONE =
      "DELETE FROM einvoice_inbound_event e WHERE e.event_id = ? AND e.received_at < ?"
          + OPEN_NUMBER_KEEPS_ROW;

  /**
   * The privileged re-open (QUESTIONS 26), run by {@code JdbcReprocessLedger} in the same
   * transaction as the record of who asked (D9-03) - which is why the statement lives here, beside
   * the table it writes, and is executed there, beside the row that attributes it.
   *
   * <p>Conditional on the state, so the eligibility check and the write are one statement and two
   * simultaneous operator calls produce exactly one winner. {@code attempts} is cleared because
   * this is a new decision by a human, not the next attempt of the old one, and the retry ceiling
   * is measured from arrival either way.
   */
  static final String REOPEN_FOR_REPROCESS =
      "UPDATE einvoice_inbound_event SET state = 'RECEIVED', last_code = ?, updated_at = ?,"
          + " attempts = 0, next_attempt_at = NULL"
          + " WHERE event_id = ? AND state IN ('"
          + InboundState.FAILED_MAPPING.name()
          + "','"
          + InboundState.FAILED_ISSUANCE.name()
          + "')"
          // D-SRE-6: a row whose invoice holds a number, under any seller and mode, is never
          // re-opened to RECEIVED, the state that never holds one. Checked in the statement, so
          // the decision and the write cannot come apart.
          + " AND NOT EXISTS (SELECT 1 FROM einvoice_issuance i"
          + " WHERE i.stripe_invoice_id = einvoice_inbound_event.object_id)";

  /**
   * The in-place re-open of a numbered row (D-SRE-2, D-SRE-8), beside {@link #REOPEN_FOR_REPROCESS}
   * and executed by {@code JdbcReprocessLedger} in the same transaction as the record of who asked.
   * It is a re-open, not an {@code allowedNext} edge: no edge leaves a numbered state towards a
   * state that cannot hold a number. The row goes to (or stays) {@code FAILED_ISSUANCE} with its
   * code kept. Conditional on everything the caller read (state, code, attempts, last update) and
   * on a number existing for the invoice, so two simultaneous operator calls give one winner.
   */
  static final String REOPEN_IN_PLACE =
      "UPDATE einvoice_inbound_event SET state = '"
          + InboundState.FAILED_ISSUANCE.name()
          + "', updated_at = ?, attempts = 0, next_attempt_at = NULL"
          + " WHERE event_id = ? AND state = ? AND last_code = ? AND attempts = ?"
          + " AND updated_at = ? AND state IN ('"
          + InboundState.FAILED_MAPPING.name()
          + "','"
          + InboundState.FAILED_ISSUANCE.name()
          + "') AND EXISTS (SELECT 1 FROM einvoice_issuance i"
          + " WHERE i.stripe_invoice_id = einvoice_inbound_event.object_id)";

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
          // Still running: picked up whenever its time has come, and immediately when it has none.
          + " (state IN ('RECEIVED','FETCHED','MAPPED','PARKED')"
          + "   AND (next_attempt_at IS NULL OR next_attempt_at <= ?))"
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

  /** False for the store a run lock holder writes through: its conclusions add no attempt. */
  private final boolean countsAttempts;

  public JdbcInboundEventStore(JdbcUnitOfWork unitOfWork) {
    this(unitOfWork, true);
  }

  private JdbcInboundEventStore(JdbcUnitOfWork unitOfWork, boolean countsAttempts) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.countsAttempts = countsAttempts;
  }

  /** The store a run lock holder writes through: every unit proves the lock (D-PR45-1). */
  static JdbcInboundEventStore heldBy(JdbcUnitOfWork held) {
    return new JdbcInboundEventStore(held, false);
  }

  @Override
  public Optional<InboundEvent> stampAttempt(
      String eventId, InboundState expected, Instant now, Duration retryIn) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(retryIn, "retryIn");
    return unitOfWork.inTransaction(
        unit -> {
          Connection c = unit.connection();
          Instant when = Timestamps.toStorage(now);
          try (PreparedStatement ps = c.prepareStatement(STAMP_ATTEMPT)) {
            ps.setObject(1, ts(Timestamps.toStorage(now.plus(retryIn))));
            ps.setObject(2, ts(when));
            ps.setString(3, eventId);
            ps.setString(4, expected.name());
            if (ps.executeUpdate() != 1) {
              return Optional.<InboundEvent>empty();
            }
          }
          return find(c, eventId, false);
        });
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
            ps.setString(i, event.bodySha256());
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
    try (PreparedStatement ps =
        c.prepareStatement(countsAttempts ? TRANSITION_FROM : HELD_TRANSITION_FROM)) {
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
      Instant now, Duration retryCeiling, int limit, String pinnedApiVersion) {
    return unitOfWork.inReadUnit(
        unit -> {
          List<InboundEvent> due = new ArrayList<>();
          try (PreparedStatement ps = unit.connection().prepareStatement(DUE)) {
            ps.setObject(1, ts(now));
            ps.setObject(2, ts(now.minus(retryCeiling)));
            ps.setObject(3, ts(now));
            ps.setString(4, pinnedApiVersion == null ? "" : pinnedApiVersion);
            ps.setInt(5, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                due.add(read(rs));
              }
            }
          }
          return List.copyOf(due);
        });
  }

  /**
   * Deletes up to {@code limit} rows past the retention ceiling, each in its own transaction under
   * its run key (design one-run-per-event, row 10). A row whose run is live is skipped this hour:
   * deleting it would let the run write into a row that no longer exists, and its next sweep
   * re-create nothing. The key is tried, never waited for.
   */
  @Override
  public int purgeOlderThan(Instant cutoff, int limit) {
    List<String[]> candidates =
        unitOfWork.inReadUnit(
            unit -> {
              List<String[]> rows = new ArrayList<>();
              try (PreparedStatement ps = unit.connection().prepareStatement(PURGE_CANDIDATES)) {
                ps.setObject(1, ts(cutoff));
                ps.setInt(2, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                    rows.add(new String[] {rs.getString(1), rs.getString(2)});
                  }
                }
              }
              return rows;
            });
    int purged = 0;
    for (String[] row : candidates) {
      purged += purgeOne(row[0], row[1], cutoff);
    }
    return purged;
  }

  @Override
  public int rearmAccessRefused(boolean livemode, String stripeAccountId, Instant now) {
    Objects.requireNonNull(stripeAccountId, "stripeAccountId");
    return unitOfWork.inTransaction(
        unit -> {
          Instant at = Timestamps.toStorage(now);
          try (PreparedStatement ps = unit.connection().prepareStatement(REARM_ACCESS_REFUSED)) {
            ps.setObject(1, ts(at));
            ps.setObject(2, ts(at));
            ps.setBoolean(3, livemode);
            ps.setString(4, stripeAccountId);
            return ps.executeUpdate();
          }
        });
  }

  @Override
  public List<String> accessRefused(String sellerId, Mode mode, int limit) {
    return unitOfWork.inReadUnit(
        unit -> {
          List<String> ids = new ArrayList<>();
          try (PreparedStatement ps = unit.connection().prepareStatement(ACCESS_REFUSED_ROWS)) {
            ps.setString(1, sellerId);
            ps.setString(2, mode.wire());
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                ids.add(rs.getString(1));
              }
            }
          }
          return ids;
        });
  }

  private int purgeOne(String eventId, String objectId, Instant cutoff) {
    return unitOfWork.inTransaction(
        unit -> {
          Connection c = unit.connection();
          try (PreparedStatement lock = c.prepareStatement(PURGE_TRY_RUN_KEY)) {
            lock.setInt(1, AdvisoryLocks.RUN_CLASS_ID);
            lock.setInt(2, JdbcInboundRunLock.runKey(objectId));
            try (ResultSet rs = lock.executeQuery()) {
              rs.next();
              if (!rs.getBoolean(1)) {
                return 0;
              }
            }
          }
          try (PreparedStatement ps = c.prepareStatement(PURGE_ONE)) {
            ps.setString(1, eventId);
            ps.setObject(2, ts(cutoff));
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
        rs.getBoolean("body_present"));
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
