package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.instant;
import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.ts;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.application.IssuanceEventReader;
import com.housedevinci.einvoice.application.IssuanceReader;
import com.housedevinci.einvoice.application.NumberAllocator;
import com.housedevinci.einvoice.application.NumberVoider;
import com.housedevinci.einvoice.application.VoidRequest;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.CorrectionTarget;
import com.housedevinci.einvoice.domain.DateGapCause;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceAnchor;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.IssuanceEvent;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.IssueClockException;
import com.housedevinci.einvoice.domain.IssueDating;
import com.housedevinci.einvoice.domain.LegalNumber;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.SeriesReport;
import com.housedevinci.einvoice.domain.SeriesScope;
import com.housedevinci.einvoice.domain.SupplyPeriod;
import com.housedevinci.einvoice.domain.Timestamps;
import com.housedevinci.einvoice.domain.en16931.DocumentTypeCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The numbering series and the issuance ledger in PostgreSQL: allocation, disposition, the series
 * report, and the chained issuance log.
 *
 * <p><b>Plain JDBC, and no JPA mapping at all.</b> This module ships no entity, no repository
 * interface and nothing cacheable over {@code einvoice_series} or {@code einvoice_issuance}. That
 * removes the Hibernate read and write paths rather than protecting them one by one: with no entity
 * there is no {@code find}, no derived query, no JPQL, no projection, no lazy attribute, no first-
 * or second-level cache and no dirty checking to reason about. What remains is a host that maps its
 * own entity or view over our tables, which the starter's persistence-mapping guard refuses at
 * startup, and a host's own raw JDBC, which no scan can see and which the chain's cross-check
 * reports as BROKEN.
 *
 * <p><b>Every statement carries the seller and the mode.</b> The webhook path is unauthenticated,
 * so a statement that could run without a server-resolved seller is a statement that will one day
 * run without one (checklist line 48). A test greps these constants for the predicate.
 */
public final class JdbcIssuanceStore
    implements NumberAllocator,
        NumberVoider,
        IssuanceReader,
        IssuanceEventReader,
        IssuanceAnchor,
        com.housedevinci.einvoice.application.IssuanceWriter,
        com.housedevinci.einvoice.application.IssuanceRowLock {

  private static final Logger log = LoggerFactory.getLogger(JdbcIssuanceStore.class);

  /** The dates a number was given with (D-PS1-27): the same columns on the row and the event. */
  static final String DATING_COLUMNS =
      "issue_date, supply_date, supply_period_start, supply_period_end, prev_legal_number,"
          + " prev_issue_date, date_gap_cause";

  static final String SELECT_ISSUANCE_COLUMNS =
      "id, seller_id, mode, stripe_invoice_id, stripe_account_id, stripe_number, series,"
          + " fiscal_year, legal_number, counter, issued_at, allocated_at, document_sha256,"
          + " archive_key, rule_pack_version, state, void_reason, void_rule_id, document_type,"
          + " corrects_issuance_id, "
          + DATING_COLUMNS;

  static final String FIND_BY_SOURCE_FOR_UPDATE =
      "SELECT "
          + SELECT_ISSUANCE_COLUMNS
          + " FROM einvoice_issuance"
          + " WHERE seller_id = ? AND mode = ? AND stripe_invoice_id = ? FOR UPDATE";

  static final String FIND_BY_SOURCE =
      "SELECT "
          + SELECT_ISSUANCE_COLUMNS
          + " FROM einvoice_issuance WHERE seller_id = ? AND mode = ? AND stripe_invoice_id = ?";

  /**
   * D-SRE-6: any seller, any mode; the states only. The one unscoped read of the issuance table,
   * named in {@code SourceAssertionsTest}.
   */
  static final String STATES_FOR_SOURCE =
      "SELECT state FROM einvoice_issuance WHERE stripe_invoice_id = ?";

  static final String CREATE_SERIES_ROW =
      "INSERT INTO einvoice_series (seller_id, document_type, series, fiscal_year, mode, prefix,"
          + " width, next_number, updated_at) VALUES (?,?,?,?,?,?,?,1,?) ON CONFLICT DO NOTHING";

  // D-A1-C03: the one type a series name numbers, for a seller and mode, in every fiscal year.
  static final String READ_SERIES_NAME =
      "SELECT document_type FROM einvoice_series_name WHERE seller_id = ? AND mode = ?"
          + " AND series = ?";

  // No conflict target, on purpose (D-C03-B6): the table has two unique indexes, the primary key
  // and the type key the foreign key needs. PostgreSQL does nothing only on the indexes named as
  // arbiters, so naming the primary key alone let a concurrent writer of the same name raise 23505
  // from the type key. With no target both are arbiters. A row of the other type still conflicts
  // on the primary key, and the read after this statement refuses it with CONFIG.
  static final String CREATE_SERIES_NAME =
      "INSERT INTO einvoice_series_name (seller_id, mode, series, document_type, created_at)"
          + " VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING";

  // D1-03: the bound and the rendered value both come from the row's own prefix and width, never
  // from the configured definition, so an edited property cannot silently change the format of a
  // live series. D1-01: the row's prefix was resolved from the year placeholder once, at the row's
  // creation, so it already differs per fiscal year - the allocator does not need to know that
  // here.
  //
  // D-PS1-27, design 5.1 step 6: the issue day is the numbering day, read under the row lock, and
  // the statement refuses one earlier than the series' latest date, inside itself, so no
  // read-then-write window exists. The checks before it name the cause of a refusal; this
  // predicate is what holds if one of them is ever wrong. GREATEST and the series guard stay as
  // D-PS1-38 built them. A NULL date is accepted only on a series that has numbered nothing.
  static final String ALLOCATE =
      "UPDATE einvoice_series SET next_number = next_number + 1,"
          + " last_issue_date = GREATEST(last_issue_date, ?), updated_at = ?"
          + " WHERE seller_id = ? AND document_type = ? AND series = ? AND fiscal_year = ?"
          + " AND mode = ? AND next_number <= (power(10, width)::bigint - 1)"
          + " AND ((last_issue_date IS NULL AND next_number = 1) OR last_issue_date <= ?)"
          + " RETURNING next_number - 1, prefix, width";

  // T9: a credit note is numbered only in date order. The predicate is in the counter statement
  // itself, under the row lock, so no read-then-write window exists; zero rows updated is re-read
  // to tell "out of order" from "exhausted".
  static final String ALLOCATE_IN_DATE_ORDER =
      "UPDATE einvoice_series SET next_number = next_number + 1,"
          + " last_issue_date = GREATEST(last_issue_date, ?), updated_at = ?"
          + " WHERE seller_id = ? AND document_type = ? AND series = ? AND fiscal_year = ?"
          + " AND mode = ? AND next_number <= (power(10, width)::bigint - 1)"
          + " AND (last_issue_date IS NULL OR last_issue_date <= ?)"
          + " RETURNING next_number - 1, prefix, width";

  // The row lock, taken by its own statement before the counter moves, so the date the series
  // held before this allocation is read under the same lock the counter is moved under.
  static final String LOCK_SERIES =
      "SELECT next_number, last_issue_date, prefix, width FROM einvoice_series"
          + " WHERE seller_id = ? AND document_type = ? AND series = ? AND fiscal_year = ?"
          + " AND mode = ? FOR UPDATE";

  static final String READ_SERIES =
      "SELECT prefix, width, next_number, last_issue_date FROM einvoice_series"
          + " WHERE seller_id = ? AND document_type = ? AND series = ? AND fiscal_year = ?"
          + " AND mode = ?";

  // D-CNS-S12: the invoice a credit note corrects, read within the credit note's own seller and
  // mode. A target of another seller or mode therefore reads as absent.
  static final String READ_CORRECTION_TARGET =
      "SELECT id, seller_id, mode, series, fiscal_year, legal_number, document_type, state,"
          + " document_sha256 FROM einvoice_issuance WHERE id = ? AND seller_id = ? AND mode = ?";

  static final String LATEST_EVENT_OF_NUMBER =
      "SELECT state, document_sha256 FROM einvoice_issuance_event"
          + " WHERE seller_id = ? AND mode = ? AND legal_number = ? ORDER BY seq DESC LIMIT 1";

  static final String RECORD_FINDING =
      "INSERT INTO einvoice_finding (seller_id, mode, code, subject_id, first_seen, last_seen)"
          + " VALUES (?,?,?,?,?,?)"
          + " ON CONFLICT (seller_id, mode, code, subject_id) DO UPDATE"
          + " SET last_seen = EXCLUDED.last_seen";

  static final String SERIES_TYPES =
      "SELECT seller_id, mode, series, fiscal_year, document_type FROM einvoice_series";

  private static final String SET_LOCK_TIMEOUT_PREFIX = "SET LOCAL lock_timeout = '";

  static final String INSERT_ISSUANCE =
      "INSERT INTO einvoice_issuance (seller_id, mode, stripe_invoice_id, stripe_account_id,"
          + " stripe_number, series, fiscal_year, legal_number, counter, issued_at, allocated_at,"
          + " rule_pack_version, state, document_type, corrects_issuance_id, "
          + DATING_COLUMNS
          + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id";

  static final String MARK_ARCHIVING =
      "UPDATE einvoice_issuance SET state = ?, document_sha256 = ?, archive_key = ?"
          + " WHERE seller_id = ? AND mode = ? AND stripe_invoice_id = ?";

  static final String MARK_STATE =
      "UPDATE einvoice_issuance SET state = ?"
          + " WHERE seller_id = ? AND mode = ? AND stripe_invoice_id = ?";

  /**
   * RC-02: a burn records its rule id <b>on the row</b>, in the statement that moves the state, and
   * not only in the chained event. The auditor-facing series report reads the row, the verifier now
   * compares the two, and a column the row never carried could not be compared with anything.
   */
  static final String BURN_ISSUANCE =
      "UPDATE einvoice_issuance SET state = ?, void_rule_id = ?"
          + " WHERE seller_id = ? AND mode = ? AND stripe_invoice_id = ?";

  static final String VOID_ISSUANCE =
      "UPDATE einvoice_issuance SET state = ?, void_reason = ?, void_rule_id = ?"
          + " WHERE seller_id = ? AND mode = ? AND stripe_invoice_id = ?";

  static final String SERIES_LINES =
      "SELECT counter, legal_number, state, stripe_invoice_id, issued_at, allocated_at,"
          + " void_reason, void_rule_id, "
          + DATING_COLUMNS
          + " FROM einvoice_issuance"
          + " WHERE seller_id = ? AND document_type = ? AND series = ? AND fiscal_year = ?"
          + " AND mode = ? ORDER BY counter";

  /** D1-05's default when a caller does not configure one, matching the property's own default. */
  public static final Duration DEFAULT_ALLOCATION_TIMEOUT = Duration.ofSeconds(5);

  /** Design 5.2's default for {@code einvoice.numbering.max-clock-regression}. */
  public static final Duration DEFAULT_MAX_CLOCK_REGRESSION = Duration.ofMinutes(5);

  /** The largest {@code max-clock-regression} accepted (design 5.2). */
  public static final Duration MAX_CLOCK_REGRESSION_BOUND = Duration.ofHours(1);

  private final JdbcUnitOfWork unitOfWork;
  private final DataSource dataSource;
  private final IssuanceChain chain;
  private final Map<SeriesId, SeriesDefinition> series;
  private final ZoneId taxZone;
  private final Clock clock;
  private final Duration allocationTimeout;
  private final Duration maxClockRegression;
  private final IssueClock issueClock;

  /**
   * The data sources R5's check has passed for. Per data source, not per store: the connection path
   * is a property of the pool, and a second store on the same pool must not need two free
   * connections again.
   */
  private static final java.util.Set<DataSource> SESSION_PINNED =
      java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

  /** A ReentrantLock, not a monitor: a virtual thread blocked in JDBC inside one pins (JDK 21). */
  private static final java.util.concurrent.locks.ReentrantLock PINNING_CHECK =
      new java.util.concurrent.locks.ReentrantLock();

  /**
   * The configuration key of a series, before a fiscal year is known: one per seller, document type
   * and mode. The allocator resolves a request to exactly one of these and never falls back from
   * one type to another (credit-note series design, section 3).
   */
  public record SeriesId(String sellerId, String series, Mode mode, DocumentTypeCode documentType) {

    public SeriesId {
      if (documentType == null) {
        throw new EInvoiceException(ErrorCodes.INVALID, "a series id needs a document type");
      }
    }

    /** An invoice series ({@code 380}). */
    public SeriesId(String sellerId, String series, Mode mode) {
      this(sellerId, series, mode, DocumentTypeCode.COMMERCIAL_INVOICE);
    }
  }

  public JdbcIssuanceStore(
      DataSource dataSource,
      IssuanceChain chain,
      Map<SeriesId, SeriesDefinition> series,
      ZoneId taxZone,
      Clock clock) {
    this(dataSource, chain, series, taxZone, clock, DEFAULT_ALLOCATION_TIMEOUT);
  }

  public JdbcIssuanceStore(
      DataSource dataSource,
      IssuanceChain chain,
      Map<SeriesId, SeriesDefinition> series,
      ZoneId taxZone,
      Clock clock,
      Duration allocationTimeout) {
    this(
        JdbcUnitOfWork.ownConnection(dataSource),
        dataSource,
        chain,
        series,
        taxZone,
        clock,
        allocationTimeout,
        DEFAULT_MAX_CLOCK_REGRESSION);
  }

  /**
   * The canonical public constructor: every statement this store issues runs through {@code
   * unitOfWork} (T-01), including the reads, so a host that allocates and then reads inside one
   * transaction sees its own uncommitted row.
   *
   * <p>{@code dataSource} is kept for two things only: {@link #openSeries(SeriesKey)} and {@link
   * #checkSeriesDates()}, which the startup check calls before any host transaction exists, and the
   * findings a refused allocation records, which must outlive the rollback of the refusal.
   *
   * @param taxZone the seller's tax zone: every instant becomes a date here, once, in Java
   * @param clock the application clock, for every "now" except the issue instant, which comes from
   *     the database; the two must agree within {@code maxClockRegression} (D-PS1-46)
   * @param maxClockRegression how far the issue clock may run behind the series, or apart from the
   *     application clock, before the refusal raises a finding; at most one hour
   */
  public JdbcIssuanceStore(
      JdbcUnitOfWork unitOfWork,
      DataSource dataSource,
      IssuanceChain chain,
      Map<SeriesId, SeriesDefinition> series,
      ZoneId taxZone,
      Clock clock,
      Duration allocationTimeout,
      Duration maxClockRegression) {
    this(
        unitOfWork,
        dataSource,
        chain,
        series,
        taxZone,
        clock,
        allocationTimeout,
        maxClockRegression,
        IssueClock.DATABASE);
  }

  /** Package-private: a test in this package may shift the issue clock (see {@link IssueClock}). */
  JdbcIssuanceStore(
      JdbcUnitOfWork unitOfWork,
      DataSource dataSource,
      IssuanceChain chain,
      Map<SeriesId, SeriesDefinition> series,
      ZoneId taxZone,
      Clock clock,
      Duration allocationTimeout,
      Duration maxClockRegression,
      IssueClock issueClock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.chain = Objects.requireNonNull(chain, "chain");
    this.series = Map.copyOf(series);
    this.taxZone = Objects.requireNonNull(taxZone, "taxZone");
    this.clock = Objects.requireNonNull(clock, "clock");
    refuseAmbiguousSeries(this.series);
    this.allocationTimeout = Objects.requireNonNull(allocationTimeout, "allocationTimeout");
    this.maxClockRegression = requireRegressionBound(maxClockRegression);
    this.issueClock = Objects.requireNonNull(issueClock, "issueClock");
  }

  /** The same store, every statement on {@code bound} (D-RTR-7). No startup check runs again. */
  private JdbcIssuanceStore(JdbcIssuanceStore base, JdbcUnitOfWork bound) {
    this.unitOfWork = bound;
    this.dataSource = base.dataSource;
    this.chain = base.chain;
    this.series = base.series;
    this.taxZone = base.taxZone;
    this.clock = base.clock;
    this.allocationTimeout = base.allocationTimeout;
    this.maxClockRegression = base.maxClockRegression;
    this.issueClock = base.issueClock;
  }

  /**
   * D-RTR-7. Tries the session lock of one issuance row on one connection, and runs {@code work}
   * with ports bound to that connection, so the holder never asks the pool for a second one. The
   * lock is released after {@code work} returns or throws; a failed release evicts the connection.
   * R5's check runs once per store, before any connection is held, and refuses a connection path
   * that does not keep one session.
   */
  @Override
  public <T> Optional<T> whileHeld(
      long issuanceId,
      java.util.function.Function<com.housedevinci.einvoice.application.IssuanceRowLock.Held, T>
          work) {
    Objects.requireNonNull(work, "work");
    Optional<Optional<T>> done = attempt(issuanceId, work);
    if (done.isEmpty()) {
      // D-PR42-1: the first own-connection holder on this data source. The session check needs
      // two free connections, so it runs here with none held, then the lock is tried again.
      verifySessionPinning();
      done = attempt(issuanceId, work);
    }
    return done.orElseThrow(
        () ->
            new EInvoiceException(
                ErrorCodes.SESSION_NOT_PINNED, "the session check did not settle for this pool"));
  }

  /**
   * One try at the lock. Empty when an own-connection holder must first run R5's check, which it
   * never does while it holds a connection. A joined holder needs no check: every statement from
   * the TRY to the UNLOCK runs inside the caller's one open transaction, which any pooler keeps on
   * one server connection.
   */
  private <T> Optional<Optional<T>> attempt(
      long issuanceId,
      java.util.function.Function<com.housedevinci.einvoice.application.IssuanceRowLock.Held, T>
          work) {
    return unitOfWork.onOneConnection(
        (connection, bound, joined) -> {
          if (joined) {
            return Optional.of(heldJoined(connection, bound, issuanceId, work));
          }
          if (!sessionPinned()) {
            return Optional.empty();
          }
          Object autosave = IssuanceRowLocks.autosaveNever(connection);
          try {
            Optional<String> token = IssuanceRowLocks.tryLock(connection, issuanceId, false);
            if (token.isEmpty()) {
              return Optional.of(Optional.<T>empty());
            }
            // Every held transaction proves this acquisition first (P1).
            JdbcUnitOfWork held = new HeldConnectionUnitOfWork(connection, issuanceId, token.get());
            boolean released = false;
            try {
              JdbcIssuanceStore locked = new JdbcIssuanceStore(this, held);
              T result =
                  work.apply(
                      new com.housedevinci.einvoice.application.IssuanceRowLock.Held(
                          locked, locked));
              released = IssuanceRowLocks.unlock(connection, issuanceId, token.get(), false);
              if (!released) {
                // P2: the release refused to free a lock that is no longer this acquisition's.
                throw IssuanceRowLocks.lostLock(issuanceId);
              }
              return Optional.of(Optional.of(result));
            } finally {
              if (!released
                  && !IssuanceRowLocks.unlock(connection, issuanceId, token.get(), false)) {
                IssuanceRowLocks.evict(connection, issuanceId);
              }
            }
          } finally {
            IssuanceRowLocks.restoreAutosave(connection, autosave);
          }
        });
  }

  /**
   * Joined mode (D-PR43-1, candidate B; D-PR43-6). Refuses autosave ALWAYS, tries the lock inside
   * the caller's transaction, runs every unit under a savepoint, and at the end, each step on its
   * own: the release; the poison when a unit failed after the transaction wrote; the caller's
   * rollback-only. The run's own outcome is what the caller gets. A caller's connection is never
   * aborted or closed.
   */
  private <T> Optional<T> heldJoined(
      java.sql.Connection connection,
      JdbcUnitOfWork bound,
      long issuanceId,
      java.util.function.Function<com.housedevinci.einvoice.application.IssuanceRowLock.Held, T>
          work)
      throws java.sql.SQLException {
    if (!(bound instanceof AbstractJdbcUnitOfWork caller)) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "the issuance row lock in joined mode needs one of this module's unit of work types");
    }
    IssuanceRowLocks.refuseAutosaveAlways(connection);
    Optional<String> token = IssuanceRowLocks.tryLock(connection, issuanceId, true);
    if (token.isEmpty()) {
      return Optional.empty();
    }
    JoinedHeldUnitOfWork.Scope scope = new JoinedHeldUnitOfWork.Scope();
    JdbcIssuanceStore locked =
        new JdbcIssuanceStore(
            this,
            new JoinedHeldUnitOfWork(
                connection, caller.ledger(connection), issuanceId, token.get(), scope));
    T result = null;
    RuntimeException failure = null;
    try {
      result =
          work.apply(
              new com.housedevinci.einvoice.application.IssuanceRowLock.Held(locked, locked));
    } catch (RuntimeException e) {
      failure = e;
    }
    boolean released = false;
    try {
      released = IssuanceRowLocks.unlock(connection, issuanceId, token.get(), true);
    } catch (RuntimeException e) {
      released = false;
    }
    IssuanceRowLocks.Poisoned poisoned = null;
    if (scope.poisonPending()) {
      try {
        poisoned = IssuanceRowLocks.poison(connection, IssuanceRowLocks.POISON);
      } catch (RuntimeException e) {
        log.error("einvoice: the poison statement failed unexpectedly", e);
      }
      try {
        caller.markRollbackOnly(connection);
      } catch (RuntimeException e) {
        log.error("einvoice: the caller's transaction could not be marked rollback-only", e);
      }
    }
    if (!released) {
      log.error(
          "einvoice: the archive lock of issuance row {} could not be released ({}); this call's"
              + " writes are in your transaction, and the lock on the row stays until this"
              + " database session ends",
          issuanceId,
          ErrorCodes.ROW_LOCK_RELEASE_FAILED);
    }
    if (failure != null) {
      if (poisoned != null && poisoned.seen() != null) {
        failure.addSuppressed(poisoned.seen());
      }
      throw failure;
    }
    if (scope.poisonPending()) {
      EInvoiceException refused =
          new EInvoiceException(
              scope.failureCode(),
              "an issuance step failed in this call after the transaction had written through the"
                  + " e-invoice store; the transaction is aborted and cannot commit. Roll it back"
                  + " and call again");
      if (poisoned != null && poisoned.seen() != null) {
        refused.addSuppressed(poisoned.seen());
      }
      throw refused;
    }
    return Optional.of(result);
  }

  /**
   * Test seam: marks a data source as checked without running R5's startup check, so a test can put
   * a transaction pooler under the per-lock proof itself (Revision 3). Package-private.
   */
  static void trustSessionPinningForTest(DataSource dataSource) {
    PINNING_CHECK.lock();
    try {
      SESSION_PINNED.add(dataSource);
    } finally {
      PINNING_CHECK.unlock();
    }
  }

  /** The data source this store reads and writes, for the starter's startup checks. */
  public DataSource dataSource() {
    return dataSource;
  }

  /** True once R5's check has passed for this store's data source (for a startup probe). */
  public boolean sessionPinningVerified() {
    return sessionPinned();
  }

  private boolean sessionPinned() {
    return sessionPinned(dataSource);
  }

  /** R5's cache, shared with the inbound run lock: one check per data source. */
  static boolean sessionPinned(DataSource dataSource) {
    PINNING_CHECK.lock();
    try {
      return SESSION_PINNED.contains(dataSource);
    } finally {
      PINNING_CHECK.unlock();
    }
  }

  /**
   * R5, once per store: refuses with {@code DEI-104} a connection path that does not keep one
   * server session, before any issuance row lock is taken. Public so a host can run it at startup.
   */
  public void verifySessionPinning() {
    verifySessionPinning(dataSource);
  }

  /** R5 for one data source, run at most once; the inbound run lock calls it too. */
  static void verifySessionPinning(DataSource dataSource) {
    PINNING_CHECK.lock();
    try {
      if (!SESSION_PINNED.contains(dataSource)) {
        IssuanceRowLocks.verifySessionPinning(dataSource);
        SESSION_PINNED.add(dataSource);
      }
    } finally {
      PINNING_CHECK.unlock();
    }
  }

  /** The bound of design 5.2: above zero, and at most one hour. */
  public static Duration requireRegressionBound(Duration maxClockRegression) {
    if (maxClockRegression == null
        || maxClockRegression.isNegative()
        || maxClockRegression.isZero()
        || maxClockRegression.compareTo(MAX_CLOCK_REGRESSION_BOUND) > 0) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "einvoice.numbering.max-clock-regression must be above zero and at most "
              + MAX_CLOCK_REGRESSION_BOUND
              + ". A wider bound would let a wrong clock date real invoices for longer.");
    }
    return maxClockRegression;
  }

  /** The tax zone every date of this store is derived in. */
  public ZoneId taxZone() {
    return taxZone;
  }

  // ---------------------------------------------------------------------------------------------
  // Allocation
  // ---------------------------------------------------------------------------------------------

  /**
   * Allocates an <b>invoice</b> number. Any other document type is refused here with {@link
   * ErrorCodes#DOCUMENT_TYPE_NOT_ISSUABLE}, before a statement runs (S-02, D-PS1-06): this request
   * carries no credit lines, no target and no amounts, so nothing could check a credit note behind
   * it, and a credit-note number with no document is a hole in a legal series. Credit-note numbers
   * are allocated only by the pipeline that produces the document.
   *
   * <p><b>The issue date is the numbering day (D-PS1-27).</b> Inside the allocation transaction, in
   * this order: a first read of the database clock picks the fiscal year, the series row is created
   * if needed and locked by its own statement, the clock is read again, and only that second
   * reading, turned into a date once in the tax zone, becomes BT-2. Numbers and dates of one series
   * then go up together by construction, whatever retried, parked or waited before. What is left to
   * refuse is a clock that cannot be trusted: {@link ErrorCodes#ISSUE_YEAR_CHANGED}, {@link
   * ErrorCodes#ISSUE_CLOCK_BEHIND_SERIES} and {@link ErrorCodes#SERIES_DATE_IN_FUTURE}, each before
   * the counter moves.
   */
  @Override
  public Issuance allocate(AllocationRequest request) {
    if (request.scope().documentType() != DocumentTypeCode.COMMERCIAL_INVOICE) {
      throw new EInvoiceException(
          ErrorCodes.DOCUMENT_TYPE_NOT_ISSUABLE,
          "this allocator numbers invoices (380) only. A number of type "
              + request.scope().documentType().code()
              + " is allocated only by the pipeline that produces that document; allocating one"
              + " here would leave a number no document will ever carry.");
    }
    return allocateTyped(request, Optional.empty(), Optional.empty());
  }

  /**
   * The allocation itself, for every document type this store numbers.
   *
   * <p>Package-private on purpose: the only caller outside this package will be the document
   * pipeline that holds the credit note's context (the document-kind seam, a later change). A
   * credit note ({@code 381}) must name the invoice it corrects; that invoice must be an issued
   * invoice of the same seller and mode whose stored hash still matches its chained issue event
   * (D-CNS-S12). The check runs after the resume read and before the counter moves, so a refusal
   * consumes no number; the insert guard in the database checks the same thing again for every
   * writer.
   *
   * <p>A credit note keeps the dating rule it had (T9, unchanged by the date-order change): its
   * issue date is the caller's, and its series refuses an earlier one. Whether a credit note's
   * issue date should also be its numbering day is open (D-PS1-43).
   *
   * @param correctsIssuanceId the row id of the corrected invoice: present for a credit note,
   *     absent for an invoice
   * @param creditNoteDate the credit note's issue date: present for a credit note, absent for an
   *     invoice
   */
  Issuance allocateTyped(
      AllocationRequest request,
      Optional<Long> correctsIssuanceId,
      Optional<LocalDate> creditNoteDate) {
    SeriesScope scope = request.scope();
    Objects.requireNonNull(correctsIssuanceId, "correctsIssuanceId");
    Objects.requireNonNull(creditNoteDate, "creditNoteDate");
    boolean creditNote = scope.documentType().isCreditNote();
    if (creditNote != correctsIssuanceId.isPresent()) {
      throw new EInvoiceException(
          ErrorCodes.CREDIT_TARGET_INVALID,
          creditNote
              ? "a credit note must name the invoice it corrects"
              : "an invoice corrects no other document");
    }
    if (creditNote != creditNoteDate.isPresent()) {
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          creditNote
              ? "a credit note is allocated with its issue date"
              : "an invoice's issue date is its numbering day; no caller passes one");
    }
    SeriesDefinition definition = definitionFor(scope);
    // Findings of a refusal must outlive the rollback of that refusal, so they are collected here
    // and written on their own connection once the allocation transaction is over.
    List<ComplianceFinding> refusalFindings = new ArrayList<>();
    try {
      return unitOfWork.inTransaction(
          unit -> {
            Connection c = unit.connection();
            // D1-12: recorded as the very first thing this unit of work does, before the resume
            // read and before the series-row insert, not just before the counter statement. A
            // lock-order refusal is then raised before anything at all has run, which makes it
            // non-poisoning under any predicate for "has this unit of work written".
            unit.locks().seriesRowLock();
            // D1-05: bounds how long this transaction will wait on the series row's lock. T-05: in
            // a joined transaction the setting outlives this call, so the caller's previous value
            // is restored on every path, including the failing ones.
            String previousLockTimeout = unit.joined() ? currentLockTimeout(unit) : null;
            setLockTimeout(unit);
            try {
              // Resume, never re-allocate. A row in any state returns its existing number, with
              // the dates it was given; only a miss reaches the counter.
              Optional<Issuance> existing =
                  findBySource(c, scope.sellerId(), scope.mode(), request.stripeInvoiceId(), true);
              if (existing.isPresent()) {
                return existing.get();
              }
              if (creditNote) {
                requireCorrectableInvoice(c, scope, correctsIssuanceId.get());
                return allocateCreditNote(
                    unit, request, definition, correctsIssuanceId.get(), creditNoteDate.get());
              }
              return allocateInvoice(unit, request, definition, refusalFindings);
            } catch (SQLException e) {
              if ("55P03".equals(e.getSQLState())) {
                throw new EInvoiceException(
                    ErrorCodes.ALLOCATION_TIMEOUT,
                    "this allocation waited longer than einvoice.numbering.allocation-timeout ("
                        + allocationTimeout
                        + ") for a lock on the series (its row, or the name of a series another"
                        + " transaction is creating) and gave up rather than join an unbounded"
                        + " queue",
                    e);
              }
              throw e;
            } finally {
              restoreLockTimeout(unit, previousLockTimeout);
            }
          });
    } finally {
      recordOutsideTheAllocation(refusalFindings);
    }
  }

  /**
   * One invoice number, dated the day it is given (design 5.1 to 5.6).
   *
   * @param refusalFindings where a refusal leaves the finding it raises; written after rollback
   */
  private Issuance allocateInvoice(
      JdbcUnitOfWork.Unit unit,
      AllocationRequest request,
      SeriesDefinition definition,
      List<ComplianceFinding> refusalFindings)
      throws SQLException {
    Connection c = unit.connection();
    SeriesScope scope = request.scope();
    // 5.3, D-PS1-41: the first reading only picks the series. The row is created if this is a new
    // fiscal year (N-01), then locked by its own statement.
    int fiscalYear = fiscalYearOf(definition, dayOf(issueClock.read(c)));
    SeriesKey key = scope.inFiscalYear(fiscalYear);
    createSeriesRowForNewFiscalYear(c, key, definition);
    LockedSeries locked = lockSeriesRow(c, key);
    // 5.1, D-PS1-40: the reading that dates the invoice is taken after the lock is held, so a
    // transaction that waited for the lock across midnight dates its invoice on the new day.
    Instant at = issueClock.read(c);
    LocalDate issueDay = dayOf(at);
    if (fiscalYearOf(definition, issueDay) != fiscalYear) {
      // Refused rather than rolled back and re-picked inside the call: the allocator may run in
      // the host's own transaction (T-01), which it must not roll back. Nothing was numbered; the
      // only side effect is the empty series row created above, which is harmless.
      throw new EInvoiceException(
          ErrorCodes.ISSUE_YEAR_CHANGED,
          "the fiscal year changed while this allocation waited for its series; nothing was"
              + " numbered. Retry in a new transaction: it picks the new year's series.");
    }
    refuseUntrustedClock(key, locked, at, issueDay, request.issuedAt(), refusalFindings);

    LegalNumber number = moveCounter(c, key, definition, issueDay, at);
    LocalDate supplyDay = dayOf(request.issuedAt());
    Optional<SupplyPeriod> period = request.servicePeriod().map(this::supplyPeriodOf);
    IssueDating draft =
        new IssueDating(
            issueDay,
            at,
            period.isPresent() ? Optional.empty() : Optional.of(supplyDay),
            period,
            locked.previousNumber(),
            Optional.ofNullable(locked.lastIssueDate()),
            Optional.empty());
    // D-PR36-1: lateness is measured from the day Stripe finalised the invoice, for a delivery
    // date and an invoicing period alike. One value feeds the cause and the finding.
    long daysLate = draft.daysAfter(supplyDay);
    DateGapCause cause =
        JdbcDateGapCauses.derive(c, scope, request.stripeInvoiceId(), daysLate, supplyDay, taxZone);
    IssueDating dating =
        new IssueDating(
            draft.issueDate(),
            draft.allocatedAt(),
            draft.supplyDate(),
            draft.supplyPeriod(),
            draft.previousNumber(),
            draft.previousIssueDate(),
            Optional.of(cause));
    if (daysLate > 1) {
      // Not a refusal: the number and a true issue date are given. The delay is not silent: an
      // open finding names the number, in the same transaction as the number itself.
      recordFinding(c, scope, ErrorCodes.ISSUED_LATE, number.value());
      log.warn(
          "einvoice: invoice {} in series {} was numbered {} days after its supply date (cause {});"
              + " recorded as finding {}",
          number.value(),
          key.series(),
          daysLate,
          cause.wire(),
          ErrorCodes.ISSUED_LATE);
    }
    return insertIssuance(c, key, request, number, Optional.empty(), dating);
  }

  /**
   * The refusals of design 5.2, 5.4 and 5.8, D-PS1-42 and D-PS1-46, all under the row lock and
   * before the counter moves. Each names the series in its finding; none can be released by a
   * caller.
   */
  private void refuseUntrustedClock(
      SeriesKey key,
      LockedSeries locked,
      Instant at,
      LocalDate issueDay,
      Instant finalizedAt,
      List<ComplianceFinding> refusalFindings) {
    LocalDate last = locked.lastIssueDate();
    // 5.8, M9: a series that numbered rows and has no latest date cannot prove its order; the
    // upgrade's backfill did not run. Never read as "anything goes".
    if (last == null && locked.nextNumber() > 1) {
      throw new EInvoiceException(
          ErrorCodes.SCHEMA_UPGRADE_REFUSED,
          "this series has numbered rows and no latest issue date, so the order of its dates cannot"
              + " be checked. Run the bundled schema script with einvoice.tax_zone set.");
    }
    // D-PS1-46: two independent clocks must agree before a legal date is written. A database
    // clock that is wrong forward would otherwise date real invoices on a day that has not come.
    Duration apart = Duration.between(clock.instant(), at).abs();
    if (apart.compareTo(maxClockRegression) > 0) {
      refusalFindings.add(seriesFinding(key, ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES));
      throw new IssueClockException(
          "the database clock and the application clock are "
              + apart.toSeconds()
              + " seconds apart, more than einvoice.numbering.max-clock-regression ("
              + maxClockRegression
              + "). Nothing was numbered. Fix whichever clock is wrong (NTP)"
              + (apart.compareTo(Duration.ofDays(1)) > 0 ? ", then reprocess the event." : "."),
          apart.compareTo(Duration.ofDays(1)) <= 0);
    }
    // D-PS1-42: a latest date more than one day ahead is not a clock that will catch up soon. A
    // hand edit or a forward jump put it there; waiting would stop the series until that day.
    if (last != null && last.isAfter(issueDay.plusDays(1))) {
      refusalFindings.add(seriesFinding(key, ErrorCodes.SERIES_DATE_IN_FUTURE));
      throw new EInvoiceException(
          ErrorCodes.SERIES_DATE_IN_FUTURE,
          "this series' latest issue date is more than one day after today on the database clock."
              + " Nothing was numbered. First check the database and application clocks against"
              + " an external time source (NTP): if both stepped back together, fix them and"
              + " retry, and do not touch the series. Only if both clocks are right, look in the"
              + " issuance chain for who moved the series row, then correct last_issue_date as"
              + " the schema owner with a recorded reason.");
    }
    // 5.2: the issue day is earlier than a date this series already gave. Only a clock that
    // stepped back does that; waiting fixes it, so a small regression retries.
    if (last != null && issueDay.isBefore(last)) {
      Duration behind = Duration.between(at, last.atStartOfDay(taxZone).toInstant());
      throw regression(key, behind, "behind the latest issue date of this series", refusalFindings);
    }
    // The invoice was finalised later than the database clock says it is now: this clock is behind
    // Stripe's. An issue date before the supply date would be untrue, so it waits as well.
    if (finalizedAt.isAfter(at)) {
      Duration behind = Duration.between(at, finalizedAt);
      throw regression(key, behind, "behind the invoice's own finalisation time", refusalFindings);
    }
  }

  private IssueClockException regression(
      SeriesKey key, Duration behind, String what, List<ComplianceFinding> refusalFindings) {
    boolean retryable = behind.compareTo(Duration.ofDays(1)) <= 0;
    if (behind.compareTo(maxClockRegression) >= 0) {
      refusalFindings.add(seriesFinding(key, ErrorCodes.ISSUE_CLOCK_BEHIND_SERIES));
    }
    return new IssueClockException(
        "the database clock is "
            + behind.toSeconds()
            + " seconds "
            + what
            + ". Nothing was numbered. "
            + (retryable
                ? "The allocation is retried once the clock has caught up."
                : "Fix the database clock or the tax zone, then reprocess the event."),
        retryable);
  }

  /** The counter statement of design 5.1 step 6, with the backward-date predicate inside it. */
  private LegalNumber moveCounter(
      Connection c, SeriesKey key, SeriesDefinition definition, LocalDate issueDay, Instant at)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(ALLOCATE)) {
      int i = 1;
      ps.setObject(i++, issueDay);
      ps.setObject(i++, ts(at));
      i = bindSeriesKey(ps, i, key);
      ps.setObject(i, issueDay);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return LegalNumber.render(rs.getString(2), rs.getInt(3), rs.getLong(1));
        }
      }
    }
    // Every date predicate was already checked under this lock, so zero rows is the width.
    throw exhausted(definition);
  }

  /** A credit note: the T9 rule exactly as before, with the caller's date. */
  private Issuance allocateCreditNote(
      JdbcUnitOfWork.Unit unit,
      AllocationRequest request,
      SeriesDefinition definition,
      long correctsIssuanceId,
      LocalDate issueDate)
      throws SQLException {
    Connection c = unit.connection();
    SeriesKey key = request.scope().inFiscalYear(fiscalYearOf(definition, issueDate));
    createSeriesRowForNewFiscalYear(c, key, definition);
    lockSeriesRow(c, key);
    Instant now = Timestamps.toStorage(clock.instant());
    try (PreparedStatement ps = c.prepareStatement(ALLOCATE_IN_DATE_ORDER)) {
      int i = 1;
      ps.setObject(i++, issueDate);
      ps.setObject(i++, ts(now));
      i = bindSeriesKey(ps, i, key);
      ps.setObject(i, issueDate);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          LegalNumber number = LegalNumber.render(rs.getString(2), rs.getInt(3), rs.getLong(1));
          return insertIssuance(
              c,
              key,
              request,
              number,
              Optional.of(correctsIssuanceId),
              IssueDating.ofCreditNote(issueDate, now));
        }
      }
    }
    throw exhaustedOrOutOfOrder(c, key, definition, issueDate);
  }

  private int fiscalYearOf(SeriesDefinition definition, LocalDate day) {
    return definition.resetPerFiscalYear() ? day.getYear() : SeriesKey.CONTINUOUS;
  }

  /** The one place an instant becomes a calendar date (design 5.1 step 5). */
  private LocalDate dayOf(Instant instant) {
    return LocalDate.ofInstant(instant, taxZone);
  }

  /** Stripe's period is start-inclusive and end-exclusive; BG-14's end day is included. */
  private SupplyPeriod supplyPeriodOf(AllocationRequest.ServicePeriod period) {
    LocalDate start = dayOf(period.start());
    LocalDate end = dayOf(period.end().minusSeconds(1));
    return new SupplyPeriod(start, end.isBefore(start) ? start : end);
  }

  private static ComplianceFinding seriesFinding(SeriesKey key, String code) {
    return ComplianceFinding.of(
        key.sellerId(),
        key.mode(),
        code,
        seriesSubject(key),
        Instant.EPOCH); // timestamps are set when the finding is written
  }

  /**
   * The subject a series finding names: type, series and fiscal year (seller and mode are keys).
   */
  static String seriesSubject(SeriesKey key) {
    return key.documentTypeCode() + "/" + key.series() + "/" + key.fiscalYear();
  }

  private void recordFinding(Connection c, SeriesScope scope, String code, String subject)
      throws SQLException {
    Instant now = Timestamps.toStorage(clock.instant());
    ComplianceFinding finding =
        ComplianceFinding.of(scope.sellerId(), scope.mode(), code, subject, now);
    writeFinding(c, finding);
  }

  private static void writeFinding(Connection c, ComplianceFinding finding) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(RECORD_FINDING)) {
      int i = 1;
      ps.setString(i++, finding.sellerId());
      ps.setString(i++, finding.mode().wire());
      ps.setString(i++, finding.code());
      ps.setString(i++, finding.subjectId());
      ps.setObject(i++, ts(finding.firstSeen()));
      ps.setObject(i, ts(finding.lastSeen()));
      ps.executeUpdate();
    }
  }

  /**
   * Writes the findings a refused allocation raised, on a connection of its own, after the
   * allocation transaction has rolled back. Best effort by design: the refusal itself is what the
   * caller must see, so a failure here is logged and never replaces it.
   */
  private void recordOutsideTheAllocation(List<ComplianceFinding> findings) {
    if (findings.isEmpty()) {
      return;
    }
    Instant now = Timestamps.toStorage(clock.instant());
    try {
      JdbcSupport.withConnection(
          dataSource,
          c -> {
            for (ComplianceFinding f : findings) {
              writeFinding(
                  c, ComplianceFinding.of(f.sellerId(), f.mode(), f.code(), f.subjectId(), now));
            }
            return null;
          });
      for (ComplianceFinding f : findings) {
        log.warn(
            "einvoice: numbering refused for series {} ({}); recorded as finding {}",
            f.subjectId(),
            f.code(),
            f.code());
      }
    } catch (EInvoiceException e) {
      log.warn("einvoice: could not record the finding of a refused allocation ({})", e.code());
    }
  }

  /**
   * {@code SET LOCAL} does not accept a bind parameter for its value - PostgreSQL parses it before
   * the extended-protocol parameter substitution applies - so the milliseconds are rendered into
   * the statement text. The value is our own validated {@link Duration}, never request input, so
   * this is not a concatenation of anything an attacker or a buyer ever supplies.
   */
  private void setLockTimeout(JdbcUnitOfWork.Unit unit) throws SQLException {
    try (Statement st = unit.plumbing().createStatement()) {
      st.execute(SET_LOCK_TIMEOUT_PREFIX + allocationTimeout.toMillis() + "ms'");
    }
  }

  private static String currentLockTimeout(JdbcUnitOfWork.Unit unit) throws SQLException {
    try (Statement st = unit.plumbing().createStatement();
        ResultSet rs = st.executeQuery("SHOW lock_timeout")) {
      return rs.next() ? rs.getString(1) : "0";
    }
  }

  /**
   * T-05. In a joined transaction {@code SET LOCAL} lives until the caller commits, so leaving our
   * value in place would silently re-time-out the caller's own later statements. Restored on every
   * path, and a restore that cannot run because the transaction is already aborted is logged rather
   * than allowed to replace the failure the caller needs to see.
   */
  private static void restoreLockTimeout(JdbcUnitOfWork.Unit unit, String previous) {
    if (previous == null) {
      return;
    }
    try (Statement st = unit.plumbing().createStatement()) {
      st.execute(SET_LOCK_TIMEOUT_PREFIX + previous + "'");
    } catch (SQLException e) {
      log.debug(
          "einvoice: could not restore the caller's lock_timeout (SQLState {}); the transaction is"
              + " failing anyway and will not outlive this call",
          e.getSQLState());
    }
  }

  private SeriesDefinition definitionFor(SeriesKey key) {
    return definitionFor(key.scope());
  }

  private SeriesDefinition definitionFor(SeriesScope key) {
    SeriesDefinition definition =
        series.get(new SeriesId(key.sellerId(), key.series(), key.mode(), key.documentType()));
    if (definition == null) {
      throw new EInvoiceException(
          ErrorCodes.SERIES_NOT_CONFIGURED,
          "no series is configured for this seller, series name and mode. A numbering series is"
              + " never auto-created from nothing: its start value would be whatever the first"
              + " caller happened to want. Configure einvoice.numbering for it.");
    }
    return definition;
  }

  /**
   * N-01. A new fiscal year of an <em>already configured</em> series creates its row here, inside
   * the allocation transaction, rather than only at startup.
   *
   * <p>Seeding at startup alone is not a race, it is a certainty with a date attached: an
   * application last restarted in November has no row for the new year, and every invoice finalised
   * after midnight on 1 January fails until someone restarts it, on the day of the year with the
   * least staff. Auto-creation is dangerous only when the start value is a free parameter; for a
   * new year of a configured series it is 1, so concurrent creators agree by construction and
   * {@code ON CONFLICT DO NOTHING} settles the rest.
   */
  /**
   * D1-01. The prefix stored on the row is the template's placeholder substituted <em>for this
   * fiscal year</em>, resolved once, here, at the row's creation - never re-resolved later. That is
   * what makes two documents of one seller unable to share a number across a fiscal-year boundary:
   * the 2026 row and the 2027 row carry different, concrete prefixes from the moment either exists.
   */
  private void createSeriesRowForNewFiscalYear(
      Connection c, SeriesKey key, SeriesDefinition definition) throws SQLException {
    try {
      bindSeriesName(c, key);
      insertSeriesRow(c, key, definition);
    } catch (SQLException e) {
      if ("40001".equals(e.getSQLState())) {
        // D-A1-C03, series-name design 4: at REPEATABLE READ or SERIALIZABLE, a name row or year
        // row another transaction committed after this snapshot cannot be skipped by ON CONFLICT
        // DO NOTHING, and PostgreSQL aborts the transaction instead. SERIALIZABLE also raises
        // 40001 when two transactions open two different new series (D-PR40-1), so the cause is
        // not known to be a creation of this series. All that is known is a conflict while the
        // series was being opened. DEI-117 already means "retry with a new snapshot". A host that
        // joined us retries its whole transaction, as for any 40001. Fixed text: the driver's
        // message is not repeated.
        throw new EInvoiceException(
            ErrorCodes.ALLOCATION_TIMEOUT,
            "a concurrent transaction conflicted with this one while series "
                + key.series()
                + " was being opened (serialization failure under REPEATABLE READ or"
                + " SERIALIZABLE). Nothing was numbered; retry in a new transaction.",
            e);
      }
      throw e;
    }
  }

  /**
   * D-A1-C03. Binds the series name to this key's document type, or refuses when the name already
   * numbers the other type. The name row's primary key serialises two writers of one new name; the
   * foreign key from every per-year row carries the type, so no per-year row of the other type can
   * exist, whatever writes it.
   *
   * <p>Read first, so the common case (the name exists) costs one read and no write. When the name
   * is new, the insert waits for any other transaction creating the same name. At READ COMMITTED it
   * then returns no row if the other committed, and the second read, with a new snapshot, sees the
   * winner's type. At REPEATABLE READ and SERIALIZABLE it fails with {@code 40001} instead, mapped
   * by the caller.
   */
  private void bindSeriesName(Connection c, SeriesKey key) throws SQLException {
    Optional<String> bound = readSeriesName(c, key);
    if (bound.isEmpty()) {
      try (PreparedStatement ps = c.prepareStatement(CREATE_SERIES_NAME)) {
        int i = 1;
        ps.setString(i++, key.sellerId());
        ps.setString(i++, key.mode().wire());
        ps.setString(i++, key.series());
        ps.setString(i++, key.documentTypeCode());
        ps.setObject(i, ts(Timestamps.toStorage(clock.instant())));
        if (ps.executeUpdate() > 0) {
          return;
        }
      }
      bound = readSeriesName(c, key);
    }
    if (bound.isEmpty()) {
      // Unreachable on the shipped schema: name rows are never deleted (trigger guard), READ
      // COMMITTED re-reads with a new snapshot and sees the committed winner, and REPEATABLE READ
      // and SERIALIZABLE raise 40001 instead of doing nothing. Reaching it means the insert did
      // nothing on an index the primary key does not imply, so it is a schema defect, not a race.
      // The text is fixed and carries no driver text.
      throw new EInvoiceException(
          ErrorCodes.SCHEMA_NOT_PREPARED,
          "the name row of series "
              + key.series()
              + " was not written and no row for that name exists: the table einvoice_series_name"
              + " carries a unique or exclusion index its primary key does not imply. Nothing was"
              + " numbered. Remove that index; retrying will not help.");
    }
    if (!bound.get().equals(key.documentTypeCode())) {
      throw new EInvoiceException(
          ErrorCodes.CONFIG,
          "series "
              + key.series()
              + " already numbers another document type for this seller and mode; one series"
              + " name serves one document type. Configure a different series name.");
    }
  }

  private static Optional<String> readSeriesName(Connection c, SeriesKey key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(READ_SERIES_NAME)) {
      ps.setString(1, key.sellerId());
      ps.setString(2, key.mode().wire());
      ps.setString(3, key.series());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
      }
    }
  }

  private void insertSeriesRow(Connection c, SeriesKey key, SeriesDefinition definition)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(CREATE_SERIES_ROW)) {
      int i = 1;
      ps.setString(i++, key.sellerId());
      ps.setString(i++, key.documentTypeCode());
      ps.setString(i++, key.series());
      ps.setInt(i++, key.fiscalYear());
      ps.setString(i++, key.mode().wire());
      ps.setString(i++, definition.resolvePrefix(key.fiscalYear()));
      ps.setInt(i++, definition.width());
      ps.setObject(i, ts(Timestamps.toStorage(clock.instant())));
      int created = ps.executeUpdate();
      if (created > 0) {
        log.info(
            "einvoice: opened numbering series seller={} type={} series={} year={} mode={} at 1",
            key.sellerId(),
            key.documentTypeCode(),
            key.series(),
            key.fiscalYear(),
            key.mode().wire());
      }
    }
  }

  /**
   * The series row under its lock, read by its own statement before anything is dated (5.1 step 3):
   * the counter, the latest issue date, and the format to render the previous number with.
   */
  private record LockedSeries(long nextNumber, LocalDate lastIssueDate, String prefix, int width) {

    /** The number given just before this allocation, absent for the first of a series. */
    Optional<String> previousNumber() {
      return nextNumber > 1
          ? Optional.of(LegalNumber.render(prefix, width, nextNumber - 1).value())
          : Optional.empty();
    }
  }

  private static int bindSeriesKey(PreparedStatement ps, int start, SeriesKey key)
      throws SQLException {
    int i = start;
    ps.setString(i++, key.sellerId());
    ps.setString(i++, key.documentTypeCode());
    ps.setString(i++, key.series());
    ps.setInt(i++, key.fiscalYear());
    ps.setString(i++, key.mode().wire());
    return i;
  }

  private static LockedSeries lockSeriesRow(Connection c, SeriesKey key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(LOCK_SERIES)) {
      bindSeriesKey(ps, 1, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new EInvoiceException(
              ErrorCodes.SERIES_NOT_CONFIGURED,
              "the numbering series row disappeared between its creation and this allocation");
        }
        return new LockedSeries(
            rs.getLong("next_number"),
            rs.getObject("last_issue_date", LocalDate.class),
            rs.getString("prefix"),
            rs.getInt("width"));
      }
    }
  }

  /**
   * D-CNS-S12, checked by core before a number is consumed: the target exists for this seller and
   * mode, is an invoice, is ISSUED, and its stored hash equals the hash on the latest chained event
   * of its number, which is the ISSUED event. ISSUED is terminal and the hash write-once, so no
   * lock is needed for this to stay true; the cap on what a credit may credit is not this method's
   * job.
   */
  private static void requireCorrectableInvoice(Connection c, SeriesScope key, long targetId)
      throws SQLException {
    String legalNumber;
    String sha;
    try (PreparedStatement ps = c.prepareStatement(READ_CORRECTION_TARGET)) {
      ps.setLong(1, targetId);
      ps.setString(2, key.sellerId());
      ps.setString(3, key.mode().wire());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw targetInvalid("no invoice of this seller and mode has that row id");
        }
        if (!CorrectionTarget.requiredType().code().equals(rs.getString("document_type"))) {
          throw targetInvalid("the target is not an invoice; a credit note corrects an invoice");
        }
        if (IssuanceState.of(rs.getString("state")) != IssuanceState.ISSUED) {
          throw targetInvalid(
              "the target invoice was never issued, so no document exists for it to correct");
        }
        legalNumber = rs.getString("legal_number");
        sha = stripped(rs.getString("document_sha256"));
      }
    }
    try (PreparedStatement ps = c.prepareStatement(LATEST_EVENT_OF_NUMBER)) {
      ps.setString(1, key.sellerId());
      ps.setString(2, key.mode().wire());
      ps.setString(3, legalNumber);
      try (ResultSet rs = ps.executeQuery()) {
        if (sha.isEmpty()
            || !rs.next()
            || !IssuanceState.ISSUED.name().equals(rs.getString(1))
            || !sha.equals(stripped(rs.getString(2)))) {
          throw targetInvalid(
              "the target invoice's stored document hash does not match its chained issue event");
        }
      }
    }
  }

  private static EInvoiceException targetInvalid(String why) {
    return new EInvoiceException(ErrorCodes.CREDIT_TARGET_INVALID, why);
  }

  private static String stripped(String value) {
    return value == null ? "" : value.strip();
  }

  private static EInvoiceException exhausted(SeriesDefinition definition) {
    return new EInvoiceException(
        ErrorCodes.SERIES_EXHAUSTED,
        "this numbering series has handed out every number its configured width of "
            + definition.width()
            + " can render. Widening it changes the format of a legal number mid-series, so"
            + " it needs an operator's decision and a new series, not a wider pad.");
  }

  private EInvoiceException exhaustedOrOutOfOrder(
      Connection c, SeriesKey key, SeriesDefinition definition, LocalDate issueDate)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(READ_SERIES)) {
      bindSeriesKey(ps, 1, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          LocalDate last = rs.getObject("last_issue_date", LocalDate.class);
          if (last != null && issueDate.isBefore(last)) {
            return new EInvoiceException(
                ErrorCodes.ISSUE_DATE_OUT_OF_ORDER,
                "this credit note is dated before the latest date its series already numbered;"
                    + " numbering it would make the dates of the series go backwards. Void it"
                    + " upstream and create a new one, which takes today's date.");
          }
          return exhausted(definition);
        }
      }
    }
    return new EInvoiceException(
        ErrorCodes.SERIES_NOT_CONFIGURED,
        "the numbering series row disappeared between its creation and this allocation");
  }

  private Issuance insertIssuance(
      Connection c,
      SeriesKey key,
      AllocationRequest request,
      LegalNumber number,
      Optional<Long> correctsIssuanceId,
      IssueDating dating)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_ISSUANCE)) {
      int i = 1;
      ps.setString(i++, key.sellerId());
      ps.setString(i++, key.mode().wire());
      ps.setString(i++, request.stripeInvoiceId());
      ps.setString(i++, request.stripeAccountId());
      ps.setString(i++, request.stripeNumber());
      ps.setString(i++, key.series());
      ps.setInt(i++, key.fiscalYear());
      ps.setString(i++, number.value());
      ps.setLong(i++, number.counter());
      ps.setObject(i++, ts(Timestamps.toStorage(request.issuedAt())));
      ps.setObject(i++, ts(dating.allocatedAt()));
      ps.setString(i++, request.rulePackVersion());
      ps.setString(i++, IssuanceState.NUMBERED.name());
      ps.setString(i++, key.documentTypeCode());
      if (correctsIssuanceId.isPresent()) {
        ps.setLong(i++, correctsIssuanceId.get());
      } else {
        ps.setNull(i++, java.sql.Types.BIGINT);
      }
      bindDating(ps, i, Optional.of(dating));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return new Issuance(
            rs.getLong(1),
            key,
            request.stripeInvoiceId(),
            request.stripeAccountId(),
            request.stripeNumber(),
            number,
            Timestamps.toStorage(request.issuedAt()),
            dating.allocatedAt(),
            "",
            "",
            request.rulePackVersion(),
            IssuanceState.NUMBERED,
            "",
            "",
            correctsIssuanceId,
            Optional.of(dating));
      }
    } catch (SQLException e) {
      if ("EIT01".equals(e.getSQLState())) {
        throw new EInvoiceException(
            ErrorCodes.CREDIT_TARGET_INVALID,
            "the database refused the corrected invoice: a credit note may correct only an issued"
                + " invoice of the same seller and mode whose stored hash matches its chain",
            e);
      }
      if ("23505".equals(e.getSQLState())
          && e.getMessage() != null
          && e.getMessage().contains("einvoice_issuance_number_unique")) {
        // Invariant 3 as a constraint: two series of one seller and mode rendered the same
        // string. The startup check refuses overlapping prefixes first; this is the backstop, and
        // it is a configuration fault, never a claim race to re-read.
        throw new EInvoiceException(
            ErrorCodes.CONFIG,
            "two numbering series of this seller and mode rendered the same legal number; give"
                + " each series a prefix that no other series' numbers can start with",
            e);
      }
      if ("23505".equals(e.getSQLState())) {
        // The backstop, and the only place in this module where a SQL state changes control flow.
        // Two callers missed the resume read together; the loser's counter increment rolls back
        // with its insert, so there is no gap, and the caller re-reads the winner's number.
        throw new EInvoiceException(
            ErrorCodes.ISSUANCE_ALREADY_CLAIMED,
            "another transaction allocated a number for this Stripe invoice first; re-read it"
                + " rather than allocating a second one",
            e);
      }
      throw e;
    }
  }

  /** The dating columns, in {@link #DATING_COLUMNS} order: shared by the row and the event. */
  private static int bindDating(PreparedStatement ps, int start, Optional<IssueDating> dating)
      throws SQLException {
    int i = start;
    IssueDating d = dating.orElse(null);
    ps.setObject(i++, d == null ? null : d.issueDate(), java.sql.Types.DATE);
    ps.setObject(i++, d == null ? null : d.supplyDate().orElse(null), java.sql.Types.DATE);
    ps.setObject(
        i++,
        d == null ? null : d.supplyPeriod().map(SupplyPeriod::start).orElse(null),
        java.sql.Types.DATE);
    ps.setObject(
        i++,
        d == null ? null : d.supplyPeriod().map(SupplyPeriod::end).orElse(null),
        java.sql.Types.DATE);
    ps.setString(i++, d == null ? "" : d.previousNumber().orElse(""));
    ps.setObject(i++, d == null ? null : d.previousIssueDate().orElse(null), java.sql.Types.DATE);
    ps.setString(i++, d == null ? "" : d.cause().map(DateGapCause::wire).orElse(""));
    return i;
  }

  /** The dating a row or an event carries; empty for one written before 0.2.0. */
  private static Optional<IssueDating> readDating(ResultSet rs, Instant allocatedAt)
      throws SQLException {
    LocalDate issueDate = rs.getObject("issue_date", LocalDate.class);
    if (issueDate == null || allocatedAt == null) {
      return Optional.empty();
    }
    LocalDate start = rs.getObject("supply_period_start", LocalDate.class);
    LocalDate end = rs.getObject("supply_period_end", LocalDate.class);
    String cause = rs.getString("date_gap_cause");
    return Optional.of(
        new IssueDating(
            issueDate,
            allocatedAt,
            Optional.ofNullable(rs.getObject("supply_date", LocalDate.class)),
            start == null && end == null
                ? Optional.empty()
                : Optional.of(new SupplyPeriod(start, end)),
            Optional.ofNullable(rs.getString("prev_legal_number")),
            Optional.ofNullable(rs.getObject("prev_issue_date", LocalDate.class)),
            cause == null || cause.isEmpty()
                ? Optional.empty()
                : Optional.of(DateGapCause.of(cause))));
  }

  /**
   * Opens a series row if it does not exist yet, outside any allocation.
   *
   * <p>The startup check calls this so a fresh database is ready before the first invoice arrives.
   * It is a convenience, never the mechanism: the allocator opens a new fiscal year's row itself,
   * inside the allocation transaction, because an application last restarted in November would
   * otherwise stop invoicing at midnight on 1 January (N-01).
   *
   * <p>T-01. This is the one store method that stays on the {@code DataSource} rather than on the
   * unit of work, with the other startup checks: it runs before a host transaction exists and must
   * not join one, or a failing startup check would poison the caller's transaction and a host that
   * seeds a series inside a long transaction would hold the row lock for its duration.
   */
  public void openSeries(SeriesKey key) {
    SeriesDefinition definition = definitionFor(key);
    JdbcSupport.withConnection(
        dataSource,
        c -> {
          createSeriesRowForNewFiscalYear(c, key, definition);
          refuseIfRowDisagreesWithConfiguration(c, key, definition);
          return null;
        });
  }

  static final String SERIES_DATES_UNTRUSTED =
      "SELECT seller_id, mode, document_type, series, fiscal_year, next_number, last_issue_date"
          + " FROM einvoice_series WHERE seller_id = ANY (?)"
          + " AND ((last_issue_date IS NULL AND next_number > 1) OR last_issue_date > ?)"
          + " ORDER BY seller_id, mode, document_type, series, fiscal_year";

  /**
   * The startup half of design 5.4 and 5.8 (D-PS1-42): refuses to start when a configured seller's
   * series carries a latest issue date more than one day after today on the database clock, with
   * {@link ErrorCodes#SERIES_DATE_IN_FUTURE} and a finding per series, or has numbered rows and no
   * latest date, with {@link ErrorCodes#SCHEMA_UPGRADE_REFUSED}. The same checks run before every
   * allocation; this one makes the condition visible before the first invoice is refused.
   *
   * <p>On the {@code DataSource}, like {@link #openSeries(SeriesKey)}: it runs before a host
   * transaction exists.
   */
  public void checkSeriesDates() {
    String[] sellers =
        series.keySet().stream().map(SeriesId::sellerId).distinct().sorted().toArray(String[]::new);
    if (sellers.length == 0) {
      return;
    }
    List<ComplianceFinding> future = new ArrayList<>();
    boolean missing =
        JdbcSupport.withConnection(
            dataSource,
            c -> {
              LocalDate today = dayOf(issueClock.read(c));
              boolean anyMissing = false;
              try (PreparedStatement ps = c.prepareStatement(SERIES_DATES_UNTRUSTED)) {
                ps.setArray(1, c.createArrayOf("varchar", sellers));
                ps.setObject(2, today.plusDays(1));
                try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                    SeriesKey key =
                        new SeriesKey(
                            rs.getString("seller_id"),
                            rs.getString("series"),
                            rs.getInt("fiscal_year"),
                            Mode.of(rs.getString("mode")),
                            DocumentTypeCode.of(rs.getString("document_type")));
                    if (rs.getObject("last_issue_date", LocalDate.class) == null) {
                      anyMissing = true;
                    } else {
                      future.add(seriesFinding(key, ErrorCodes.SERIES_DATE_IN_FUTURE));
                    }
                  }
                }
              }
              return anyMissing;
            });
    recordOutsideTheAllocation(future);
    if (missing) {
      throw new EInvoiceException(
          ErrorCodes.SCHEMA_UPGRADE_REFUSED,
          "a numbering series has numbered rows and no latest issue date, so the order of its"
              + " dates cannot be checked. Run the bundled schema script with einvoice.tax_zone"
              + " set.");
    }
    if (!future.isEmpty()) {
      throw new EInvoiceException(
          ErrorCodes.SERIES_DATE_IN_FUTURE,
          future.size()
              + " numbering series carry a latest issue date more than one day after today on the"
              + " database clock (findings "
              + ErrorCodes.SERIES_DATE_IN_FUTURE
              + " name them). First check the database and application clocks against an"
              + " external time source (NTP): if both stepped back together, fix them and"
              + " restart, and do not touch the series. Only if both clocks are right, look in"
              + " the issuance chain for who moved each row, then correct last_issue_date as the"
              + " schema owner with a recorded reason.");
    }
  }

  /**
   * D1-03. Only the startup path compares the row against today's configuration - the allocator
   * itself never does (a probe rebuilding the store with an edited definition must still render
   * from the existing row, not be refused by it). An operator who edits {@code
   * einvoice.numbering.prefix} or {@code .width} for a series that already has a row and restarts
   * gets a named refusal here, rather than a silently ignored property.
   */
  private void refuseIfRowDisagreesWithConfiguration(
      Connection c, SeriesKey key, SeriesDefinition definition) throws SQLException {
    String expectedPrefix = definition.resolvePrefix(key.fiscalYear());
    try (PreparedStatement ps = c.prepareStatement(READ_SERIES)) {
      bindSeriesKey(ps, 1, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return;
        }
        String rowPrefix = rs.getString(1);
        int rowWidth = rs.getInt(2);
        if (!expectedPrefix.equals(rowPrefix) || rowWidth != definition.width()) {
          throw new EInvoiceException(
              ErrorCodes.CONFIG,
              "einvoice.numbering for seller="
                  + key.sellerId()
                  + " series="
                  + key.series()
                  + " year="
                  + key.fiscalYear()
                  + " mode="
                  + key.mode().wire()
                  + " resolves to prefix="
                  + expectedPrefix
                  + " width="
                  + definition.width()
                  + ", but the existing series row already carries prefix="
                  + rowPrefix
                  + " width="
                  + rowWidth
                  + ". Both are immutable once a series exists (a legal number's format cannot"
                  + " change mid-series); revert the property or start a new series name.");
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Disposition
  // ---------------------------------------------------------------------------------------------

  @Override
  public Issuance voidUnused(VoidRequest request) {
    return unitOfWork.inTransaction(
        unit -> {
          Connection c = unit.connection();
          Issuance issuance =
              findBySource(c, request.sellerId(), request.mode(), request.stripeInvoiceId(), true)
                  .orElseThrow(
                      () ->
                          new EInvoiceException(
                              ErrorCodes.ISSUANCE_NOT_FOUND,
                              "no number is allocated for that Stripe invoice under this seller and"
                                  + " mode"));
          // Refused in the enum here, and again by the trigger below: an ISSUED number names a
          // legal document that exists, and voiding it would be an attempt to unpublish it.
          issuance.state().transitionTo(IssuanceState.VOID_UNUSED);
          try (PreparedStatement ps = c.prepareStatement(VOID_ISSUANCE)) {
            int i = 1;
            ps.setString(i++, IssuanceState.VOID_UNUSED.name());
            ps.setString(i++, request.reason());
            ps.setString(i++, request.ruleId());
            ps.setString(i++, request.sellerId());
            ps.setString(i++, request.mode().wire());
            ps.setString(i, request.stripeInvoiceId());
            ps.executeUpdate();
          }
          Issuance voided =
              new Issuance(
                  issuance.id(),
                  issuance.seriesKey(),
                  issuance.stripeInvoiceId(),
                  issuance.stripeAccountId(),
                  issuance.stripeNumber(),
                  issuance.legalNumber(),
                  issuance.issuedAt(),
                  issuance.allocatedAt(),
                  issuance.documentSha256(),
                  issuance.archiveKey(),
                  issuance.rulePackVersion(),
                  IssuanceState.VOID_UNUSED,
                  request.reason(),
                  request.ruleId(),
                  issuance.correctsIssuanceId(),
                  // The void event carries the dates the number was given with, like every other
                  // event of the number; the verifier compares them with the row (D-PR36-2).
                  issuance.dating());
          appendEvent(unit, voided, IssuanceEvent.voided(voided, clock.instant()));
          return voided;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // The two database phases of the two-store write (design 5, P3 and P4)
  // ---------------------------------------------------------------------------------------------

  /**
   * P3: record the bytes we are about to write, with their hash and their key, <b>before</b> the
   * object store is touched (I-05).
   *
   * <p>Every object in the archive then has a row that predicted it: an orphan is an indexed query
   * instead of a bucket walk, and a crash between the PUT and the commit is attributable rather
   * than inferred. Both columns become write-once at the trigger from this moment (N-06).
   *
   * <p>Idempotent by design, because this is a retry path: a row already in {@code ARCHIVING} or
   * {@code ISSUED} with the same hash is returned unchanged. A row in either state with a
   * <em>different</em> hash is a refusal - the same number would otherwise name two sets of bytes.
   */
  @Override
  public Issuance markArchiving(
      String sellerId,
      Mode mode,
      String stripeInvoiceId,
      String documentSha256,
      com.housedevinci.einvoice.domain.ArchiveKey key) {
    com.housedevinci.einvoice.domain.Hashes.requireSha256Hex("document hash", documentSha256);
    return unitOfWork.inTransaction(
        unit -> {
          Connection c = unit.connection();
          Issuance issuance = requireIssuance(c, sellerId, mode, stripeInvoiceId);
          if (!issuance.documentSha256().isEmpty()) {
            if (!issuance.documentSha256().equals(documentSha256)) {
              throw new EInvoiceException(
                  ErrorCodes.ARCHIVE_CONTENT_CONFLICT,
                  "this number already names a document with a different content hash. Two"
                      + " different sets of bytes cannot carry one legal number, so this is"
                      + " refused rather than reconciled.");
            }
            // A retry of the same phase, on the same bytes. The hash and the key are already
            // written and are write-once, but the state may have moved to FAILED_ARCHIVE in
            // between - so the marker is re-asserted rather than assumed, or the retry would
            // arrive at ISSUED from a state that cannot reach it.
            if (issuance.state() == IssuanceState.ARCHIVING
                || issuance.state() == IssuanceState.ISSUED) {
              return issuance;
            }
            issuance.state().transitionTo(IssuanceState.ARCHIVING);
            try (PreparedStatement ps = c.prepareStatement(MARK_STATE)) {
              int i = 1;
              ps.setString(i++, IssuanceState.ARCHIVING.name());
              ps.setString(i++, sellerId);
              ps.setString(i++, mode.wire());
              ps.setString(i, stripeInvoiceId);
              ps.executeUpdate();
            }
            return findBySource(c, sellerId, mode, stripeInvoiceId, false).orElseThrow();
          }
          issuance.state().transitionTo(IssuanceState.ARCHIVING);
          try (PreparedStatement ps = c.prepareStatement(MARK_ARCHIVING)) {
            int i = 1;
            ps.setString(i++, IssuanceState.ARCHIVING.name());
            ps.setString(i++, documentSha256);
            ps.setString(i++, key.value());
            ps.setString(i++, sellerId);
            ps.setString(i++, mode.wire());
            ps.setString(i, stripeInvoiceId);
            ps.executeUpdate();
          }
          return findBySource(c, sellerId, mode, stripeInvoiceId, false).orElseThrow();
        });
  }

  /**
   * P4: the document exists. The state and the chained disposition commit together, so a crash
   * before the commit falls back to P3 - byte-identical, on the same number, with no second one.
   */
  @Override
  public Issuance markIssued(String sellerId, Mode mode, String stripeInvoiceId) {
    return unitOfWork.inTransaction(
        unit -> {
          Connection c = unit.connection();
          Issuance issuance = requireIssuance(c, sellerId, mode, stripeInvoiceId);
          if (issuance.state() == IssuanceState.ISSUED) {
            return issuance; // a redelivery, or a retry after the commit went through
          }
          issuance.state().transitionTo(IssuanceState.ISSUED);
          try (PreparedStatement ps = c.prepareStatement(MARK_STATE)) {
            int i = 1;
            ps.setString(i++, IssuanceState.ISSUED.name());
            ps.setString(i++, sellerId);
            ps.setString(i++, mode.wire());
            ps.setString(i, stripeInvoiceId);
            ps.executeUpdate();
          }
          Issuance issued = findBySource(c, sellerId, mode, stripeInvoiceId, false).orElseThrow();
          appendEvent(
              unit, issued, IssuanceEvent.of(issued, IssuanceState.ISSUED, clock.instant()));
          return issued;
        });
  }

  /**
   * A failure state on the issuance row. Whether a sweeper may re-pick it is the enum's answer, per
   * state, and never this call site's (I-08).
   */
  @Override
  public Issuance markFailed(
      String sellerId, Mode mode, String stripeInvoiceId, IssuanceState state, String ruleId) {
    if (state != IssuanceState.FAILED_VALIDATION && state != IssuanceState.FAILED_ARCHIVE) {
      throw new EInvoiceException(
          ErrorCodes.ILLEGAL_TRANSITION,
          "only FAILED_VALIDATION and FAILED_ARCHIVE are failure states of an issuance row");
    }
    return unitOfWork.inTransaction(
        unit -> {
          Connection c = unit.connection();
          Issuance issuance = requireIssuance(c, sellerId, mode, stripeInvoiceId);
          if (issuance.state() == state) {
            return issuance;
          }
          issuance.state().transitionTo(state);
          String burnRuleId =
              ruleId == null || ruleId.isBlank()
                  ? issuance.voidRuleId()
                  : com.housedevinci.einvoice.domain.RuleIds.normalise(ruleId);
          if (state == IssuanceState.FAILED_VALIDATION) {
            try (PreparedStatement ps = c.prepareStatement(BURN_ISSUANCE)) {
              int i = 1;
              ps.setString(i++, state.name());
              ps.setString(i++, burnRuleId);
              ps.setString(i++, sellerId);
              ps.setString(i++, mode.wire());
              ps.setString(i, stripeInvoiceId);
              ps.executeUpdate();
            }
          } else {
            try (PreparedStatement ps = c.prepareStatement(MARK_STATE)) {
              int i = 1;
              ps.setString(i++, state.name());
              ps.setString(i++, sellerId);
              ps.setString(i++, mode.wire());
              ps.setString(i, stripeInvoiceId);
              ps.executeUpdate();
            }
          }
          Issuance failed = findBySource(c, sellerId, mode, stripeInvoiceId, false).orElseThrow();
          if (state == IssuanceState.FAILED_VALIDATION) {
            // D7-03: a number burned by a validation or a render refusal leaves a gap in the
            // issued sequence, and the numbering rules require a gap to be explainable. Without
            // this the only explanation lived on a row that can legitimately change; now it is in
            // the chain, with the failing rule id or the refusal code on it. FAILED_ARCHIVE is
            // deliberately not chained: it is retryable, not a disposition, and its eventual fate
            // is ISSUED, VOID_UNUSED or - since QUESTIONS 38, after a resumed run that found no
            // object at its key - FAILED_VALIDATION, all of which are. The event copies the row,
            // so a burned resumed row's event carries the first attempt's hash and key.
            appendEvent(unit, failed, IssuanceEvent.failed(failed, state, ruleId, clock.instant()));
          }
          return failed;
        });
  }

  private Issuance requireIssuance(Connection c, String sellerId, Mode mode, String invoiceId)
      throws SQLException {
    return findBySource(c, sellerId, mode, invoiceId, true)
        .orElseThrow(
            () ->
                new EInvoiceException(
                    ErrorCodes.ISSUANCE_NOT_FOUND,
                    "no number is allocated for that Stripe invoice under this seller and mode"));
  }

  /**
   * Appends one chained event and advances the anchor, in the caller's transaction.
   *
   * <p>The chain lock is this module's own two-argument constant (N-05), and it is recorded in the
   * transaction's lock ledger before it is taken: a transaction may hold the series counter's row
   * lock and then this one, never the reverse (N-07 as restated by T-02b).
   */
  private void appendEvent(JdbcUnitOfWork.Unit unit, Issuance issuance, IssuanceEvent untargeted)
      throws SQLException {
    Connection c = unit.connection();
    // D-PS1-37: a credit note's event carries the corrected invoice's whole identity, read from
    // that invoice's own row now, never the surrogate id alone and never a caller's copy.
    IssuanceEvent event =
        issuance.correctsIssuanceId().isPresent()
            ? untargeted.withCorrection(
                correctionTarget(c, issuance, issuance.correctsIssuanceId().get()))
            : untargeted;
    unit.locks().chainLock();
    AdvisoryLocks.lock(c, AdvisoryLocks.CHAIN_CLASS_ID, AdvisoryLocks.CHAIN_KEY);
    String prev = IssuanceChain.GENESIS;
    long count = 0;
    boolean anchored = false;
    try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT head_hash, row_count, keyed FROM einvoice_issuance_anchor WHERE id = 1");
        ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        prev = rs.getString(1);
        count = rs.getLong(2);
        refuseIfKeyedModeDiffers(rs.getBoolean(3));
        anchored = true;
      }
    }
    if (!anchored && hasRows(c)) {
      throw new EInvoiceException(
          ErrorCodes.CHAIN_BROKEN,
          "the issuance log has rows and no anchor row. The anchor is the only record of what the"
              + " rows ought to claim, and it is never re-derived from the rows themselves;"
              + " archive the pair and start a new log.");
    }
    IssuanceEvent linked = chain.link(event, prev);
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO einvoice_issuance_anchor (id, head_hash, row_count, updated_at, keyed)"
                + " VALUES (1, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET"
                + " head_hash = EXCLUDED.head_hash, row_count = EXCLUDED.row_count,"
                + " updated_at = EXCLUDED.updated_at")) {
      int i = 1;
      ps.setString(i++, linked.hash());
      ps.setLong(i++, count + 1);
      ps.setObject(i++, ts(linked.timestamp()));
      ps.setBoolean(i, chain.isKeyed());
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO einvoice_issuance_event (ts, seller_id, mode, stripe_account_id, series,"
                + " fiscal_year, legal_number, counter, stripe_invoice_id, stripe_number, state,"
                + " issued_at, document_sha256, archive_key, rule_pack_version, void_reason,"
                + " void_rule_id, chain_version, key_id, prev_hash, hash, document_type,"
                + " corrects_issuance_id, corrects_seller_id, corrects_mode, corrects_series,"
                + " corrects_fiscal_year, corrects_legal_number, allocated_at, "
                + DATING_COLUMNS
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      int i = 1;
      ps.setObject(i++, ts(linked.timestamp()));
      ps.setString(i++, linked.seriesKey().sellerId());
      ps.setString(i++, linked.seriesKey().mode().wire());
      ps.setString(i++, linked.stripeAccountId());
      ps.setString(i++, linked.seriesKey().series());
      ps.setInt(i++, linked.seriesKey().fiscalYear());
      ps.setString(i++, linked.legalNumber().value());
      ps.setLong(i++, linked.legalNumber().counter());
      ps.setString(i++, linked.stripeInvoiceId());
      ps.setString(i++, linked.stripeNumber());
      ps.setString(i++, linked.state().name());
      ps.setObject(i++, ts(linked.issuedAt()));
      ps.setString(i++, linked.documentSha256());
      ps.setString(i++, linked.archiveKey());
      ps.setString(i++, linked.rulePackVersion());
      ps.setString(i++, linked.voidReason());
      ps.setString(i++, linked.voidRuleId());
      ps.setString(i++, linked.chainVersion());
      ps.setString(i++, linked.keyId());
      ps.setString(i++, linked.prevHash());
      ps.setString(i++, linked.hash());
      ps.setString(i++, linked.seriesKey().documentTypeCode());
      Optional<CorrectionTarget> target = linked.corrects();
      if (target.isPresent()) {
        CorrectionTarget t = target.get();
        ps.setLong(i++, t.issuanceId());
        ps.setString(i++, t.sellerId());
        ps.setString(i++, t.mode().wire());
        ps.setString(i++, t.series());
        ps.setInt(i++, t.fiscalYear());
        ps.setString(i++, t.legalNumber());
      } else {
        ps.setNull(i++, java.sql.Types.BIGINT);
        ps.setNull(i++, java.sql.Types.VARCHAR);
        ps.setNull(i++, java.sql.Types.VARCHAR);
        ps.setNull(i++, java.sql.Types.VARCHAR);
        ps.setNull(i++, java.sql.Types.INTEGER);
        ps.setNull(i++, java.sql.Types.VARCHAR);
      }
      ps.setObject(i++, ts(linked.dating().map(IssueDating::allocatedAt).orElse(null)));
      bindDating(ps, i, linked.dating());
      ps.executeUpdate();
    }
  }

  private static CorrectionTarget correctionTarget(Connection c, Issuance credit, long targetId)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(READ_CORRECTION_TARGET)) {
      ps.setLong(1, targetId);
      ps.setString(2, credit.seriesKey().sellerId());
      ps.setString(3, credit.seriesKey().mode().wire());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw targetInvalid("the corrected invoice's row is gone or belongs to another seller");
        }
        return new CorrectionTarget(
            rs.getLong("id"),
            rs.getString("seller_id"),
            Mode.of(rs.getString("mode")),
            rs.getString("series"),
            rs.getInt("fiscal_year"),
            rs.getString("legal_number"));
      }
    }
  }

  private void refuseIfKeyedModeDiffers(boolean anchorKeyed) {
    if (anchorKeyed != chain.isKeyed()) {
      throw new EInvoiceException(
          ErrorCodes.CHAIN_BROKEN,
          "this issuance log is "
              + (anchorKeyed ? "keyed" : "unkeyed")
              + " and this application is configured "
              + (chain.isKeyed() ? "keyed" : "unkeyed")
              + ". A log is keyed from row 1 or unkeyed forever; mixing the two would make the"
              + " unkeyed rows re-computable by anyone who can write them.");
    }
  }

  private static boolean hasRows(Connection c) throws SQLException {
    try (PreparedStatement ps =
            c.prepareStatement("SELECT 1 FROM einvoice_issuance_event LIMIT 1");
        ResultSet rs = ps.executeQuery()) {
      return rs.next();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------------------------

  @Override
  public Optional<Issuance> findBySource(String sellerId, Mode mode, String stripeInvoiceId) {
    return unitOfWork.inReadUnit(
        unit -> findBySource(unit.connection(), sellerId, mode, stripeInvoiceId, false));
  }

  private Optional<Issuance> findBySource(
      Connection c, String sellerId, Mode mode, String stripeInvoiceId, boolean forUpdate)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(forUpdate ? FIND_BY_SOURCE_FOR_UPDATE : FIND_BY_SOURCE)) {
      ps.setString(1, sellerId);
      ps.setString(2, mode.wire());
      ps.setString(3, stripeInvoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(readIssuance(rs)) : Optional.empty();
      }
    }
  }

  @Override
  public java.util.Set<IssuanceState> issuanceStates(String stripeInvoiceId) {
    Objects.requireNonNull(stripeInvoiceId, "stripeInvoiceId");
    return unitOfWork.inReadUnit(
        unit -> {
          java.util.Set<IssuanceState> states = java.util.EnumSet.noneOf(IssuanceState.class);
          try (PreparedStatement ps = unit.connection().prepareStatement(STATES_FOR_SOURCE)) {
            ps.setString(1, stripeInvoiceId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                states.add(IssuanceState.valueOf(rs.getString(1)));
              }
            }
          }
          return java.util.Set.copyOf(states);
        });
  }

  static final String IN_SERIES =
      "SELECT "
          + SELECT_ISSUANCE_COLUMNS
          + " FROM einvoice_issuance"
          + " WHERE seller_id = ? AND document_type = ? AND series = ? AND fiscal_year = ?"
          + " AND mode = ? ORDER BY counter DESC LIMIT ?";

  @Override
  public List<Issuance> inSeries(SeriesKey key, int limit) {
    return unitOfWork.inReadUnit(
        unit -> {
          List<Issuance> rows = new ArrayList<>();
          try (PreparedStatement ps = unit.connection().prepareStatement(IN_SERIES)) {
            int i = bindSeriesKey(ps, 1, key);
            ps.setInt(i, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                rows.add(readIssuance(rs));
              }
            }
          }
          return List.copyOf(rows);
        });
  }

  /**
   * D-PR50-B1: the stuck check's read. Seller and mode in the predicate, any series and any year,
   * oldest first. The open states come from {@code IssuanceState.open()}, so the domain and this
   * query cannot disagree. Served by {@code einvoice_issuance_open (state, allocated_at)}.
   */
  static final String OPEN_OLDER_THAN =
      "SELECT "
          + SELECT_ISSUANCE_COLUMNS
          + " FROM einvoice_issuance WHERE seller_id = ? AND mode = ? AND state IN ("
          + java.util.Arrays.stream(IssuanceState.values())
              .filter(IssuanceState::open)
              .map(state -> "'" + state.name() + "'")
              .collect(java.util.stream.Collectors.joining(","))
          + ") AND allocated_at < ? ORDER BY allocated_at, id LIMIT ?";

  @Override
  public List<Issuance> openOlderThan(String sellerId, Mode mode, Instant before, int limit) {
    Objects.requireNonNull(sellerId, "sellerId");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(before, "before");
    return unitOfWork.inReadUnit(
        unit -> {
          List<Issuance> rows = new ArrayList<>();
          try (PreparedStatement ps = unit.connection().prepareStatement(OPEN_OLDER_THAN)) {
            ps.setString(1, sellerId);
            ps.setString(2, mode.wire());
            ps.setObject(3, ts(before));
            ps.setInt(4, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                rows.add(readIssuance(rs));
              }
            }
          }
          return List.copyOf(rows);
        });
  }

  @Override
  public SeriesReport seriesReport(SeriesKey key) {
    return unitOfWork.inReadUnit(
        unit -> {
          Connection c = unit.connection();
          List<SeriesReport.Line> lines = new ArrayList<>();
          long open = 0;
          LocalDate latest = null;
          try (PreparedStatement ps = c.prepareStatement(SERIES_LINES)) {
            bindSeriesKey(ps, 1, key);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                IssuanceState state = IssuanceState.of(rs.getString("state"));
                if (state.open()) {
                  open++;
                }
                Instant issuedAt = instant(rs.getObject("issued_at", OffsetDateTime.class));
                Instant allocatedAt = instant(rs.getObject("allocated_at", OffsetDateTime.class));
                Optional<IssueDating> dating = readDating(rs, allocatedAt);
                // BT-2 as the document carries it: the numbering day, or on a 0.1.x line the
                // finalisation day, which is how a late 0.1.x number carried an earlier date.
                LocalDate issueDate =
                    dating.map(IssueDating::issueDate).orElseGet(() -> dayOf(issuedAt));
                boolean outOfOrder = latest != null && issueDate.isBefore(latest);
                latest = latest == null || issueDate.isAfter(latest) ? issueDate : latest;
                lines.add(
                    new SeriesReport.Line(
                        rs.getLong("counter"),
                        rs.getString("legal_number"),
                        state,
                        rs.getString("stripe_invoice_id"),
                        issuedAt,
                        allocatedAt,
                        rs.getString("void_reason"),
                        rs.getString("void_rule_id"),
                        issueDate,
                        dating,
                        outOfOrder));
              }
            }
          }
          long next = 1;
          try (PreparedStatement ps = c.prepareStatement(READ_SERIES)) {
            bindSeriesKey(ps, 1, key);
            try (ResultSet rs = ps.executeQuery()) {
              if (rs.next()) {
                next = rs.getLong("next_number");
              }
            }
          }
          return new SeriesReport(key, lines, open, next);
        });
  }

  @Override
  public List<IssuanceEvent> readAfter(long sequenceExclusive, int limit) {
    return unitOfWork.inReadUnit(
        unit -> {
          Connection c = unit.connection();
          List<IssuanceEvent> events = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT seq, ts, seller_id, mode, stripe_account_id, series, fiscal_year,"
                      + " legal_number, counter, stripe_invoice_id, stripe_number, state,"
                      + " issued_at, document_sha256, archive_key, rule_pack_version, void_reason,"
                      + " void_rule_id, chain_version, key_id, prev_hash, hash, document_type,"
                      + " corrects_issuance_id, corrects_seller_id, corrects_mode,"
                      + " corrects_series, corrects_fiscal_year, corrects_legal_number,"
                      + " allocated_at, "
                      + DATING_COLUMNS
                      + " FROM einvoice_issuance_event WHERE seq > ? ORDER BY seq LIMIT ?")) {
            ps.setLong(1, sequenceExclusive);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                events.add(readEvent(rs));
              }
            }
          }
          return events;
        });
  }

  @Override
  public List<DisposedIssuance> disposedIssuances() {
    return unitOfWork.inReadUnit(
        unit -> {
          Connection c = unit.connection();
          List<DisposedIssuance> disposed = new ArrayList<>();
          // D1-02: the full row identity, not just seller|mode|legal_number|state, so a forged row
          // in a different series, fiscal year or with a different source id cannot be vouched for
          // by a legitimate event that merely happens to share the number and the state.
          try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT i.seller_id, i.mode, i.series, i.fiscal_year, i.stripe_invoice_id,"
                          + " i.legal_number, i.state, i.void_reason, i.void_rule_id,"
                          + " i.document_type, t.id, t.seller_id, t.mode, t.series,"
                          + " t.fiscal_year, t.legal_number, i.allocated_at, i.issue_date,"
                          + " i.supply_date, i.supply_period_start, i.supply_period_end,"
                          + " i.prev_legal_number, i.prev_issue_date, i.date_gap_cause"
                          + " FROM einvoice_issuance i"
                          + " LEFT JOIN einvoice_issuance t ON t.id = i.corrects_issuance_id"
                          + " WHERE i.state IN ("
                          + disposedStates()
                          + ") ORDER BY i.id");
              ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              disposed.add(
                  new DisposedIssuance(
                      rs.getString(1),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getInt(4),
                      rs.getString(5),
                      rs.getString(6),
                      rs.getString(7),
                      rs.getString(8),
                      rs.getString(9),
                      rs.getString(10),
                      targetIdentity(rs, 11),
                      IssueDating.identity(
                          readDating(
                              rs, instant(rs.getObject("allocated_at", OffsetDateTime.class))))));
            }
          }
          return disposed;
        });
  }

  @Override
  public List<SeriesType> seriesTypes() {
    return unitOfWork.inReadUnit(
        unit -> {
          List<SeriesType> types = new ArrayList<>();
          try (PreparedStatement ps = unit.connection().prepareStatement(SERIES_TYPES);
              ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              types.add(
                  new SeriesType(
                      rs.getString(1),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getInt(4),
                      rs.getString(5)));
            }
          }
          return List.copyOf(types);
        });
  }

  /**
   * The corrected invoice's identity, as the cross-check compares it: empty for an invoice, or for
   * a credit note whose link points at no row (which the cross-check then reports).
   */
  private static String targetIdentity(ResultSet rs, int first) throws SQLException {
    long id = rs.getLong(first);
    if (rs.wasNull()) {
      return "";
    }
    return id
        + "/"
        + rs.getString(first + 1)
        + "/"
        + rs.getString(first + 2)
        + "/"
        + rs.getString(first + 3)
        + "/"
        + rs.getInt(first + 4)
        + "/"
        + rs.getString(first + 5);
  }

  @Override
  public Optional<Anchor> anchor() {
    return unitOfWork.inReadUnit(
        unit -> {
          Connection c = unit.connection();
          try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT head_hash, row_count, keyed FROM einvoice_issuance_anchor"
                          + " WHERE id = 1");
              ResultSet rs = ps.executeQuery()) {
            return rs.next()
                ? Optional.of(new Anchor(rs.getString(1), rs.getLong(2), rs.getBoolean(3)))
                : Optional.<Anchor>empty();
          }
        });
  }

  /**
   * The settled states, from the enum itself (D9-04). A literal list here and a predicate in the
   * domain were two definitions of one thing, and the one with the SQL in it is the one that goes
   * stale. Built from {@code IssuanceState.values()}, so the next chained state is added once.
   */
  private static String disposedStates() {
    return java.util.Arrays.stream(IssuanceState.values())
        .filter(IssuanceState::disposed)
        .map(state -> "'" + state.name() + "'")
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private static Issuance readIssuance(ResultSet rs) throws SQLException {
    SeriesKey key =
        new SeriesKey(
            rs.getString("seller_id"),
            rs.getString("series"),
            rs.getInt("fiscal_year"),
            Mode.of(rs.getString("mode")),
            DocumentTypeCode.of(rs.getString("document_type")));
    long corrects = rs.getLong("corrects_issuance_id");
    Optional<Long> correctsIssuanceId = rs.wasNull() ? Optional.empty() : Optional.of(corrects);
    return new Issuance(
        rs.getLong("id"),
        key,
        rs.getString("stripe_invoice_id"),
        rs.getString("stripe_account_id"),
        rs.getString("stripe_number"),
        new LegalNumber(rs.getString("legal_number"), rs.getLong("counter")),
        instant(rs.getObject("issued_at", OffsetDateTime.class)),
        instant(rs.getObject("allocated_at", OffsetDateTime.class)),
        documentHash(rs),
        rs.getString("archive_key"),
        rs.getString("rule_pack_version"),
        IssuanceState.of(rs.getString("state")),
        rs.getString("void_reason"),
        rs.getString("void_rule_id"),
        correctsIssuanceId,
        readDating(rs, instant(rs.getObject("allocated_at", OffsetDateTime.class))));
  }

  /**
   * D1-08. {@code document_sha256} is {@code char(64)}, which blank-pads an unset value to sixty-
   * four spaces on read; the in-memory value object's unset spelling is {@code ""}. One logical
   * value must have one spelling at the single point that builds it, before either spelling can
   * reach hashed material or a public reader.
   */
  private static String documentHash(ResultSet rs) throws SQLException {
    String value = rs.getString("document_sha256");
    return value == null ? "" : value.strip();
  }

  private static boolean plantedIdentity(ResultSet rs) throws SQLException {
    for (String column :
        new String[] {
          "corrects_seller_id", "corrects_mode", "corrects_series", "corrects_legal_number"
        }) {
      if (rs.getString(column) != null) {
        return true;
      }
    }
    rs.getInt("corrects_fiscal_year");
    return !rs.wasNull();
  }

  private static IssuanceEvent readEvent(ResultSet rs) throws SQLException {
    SeriesKey key =
        new SeriesKey(
            rs.getString("seller_id"),
            rs.getString("series"),
            rs.getInt("fiscal_year"),
            Mode.of(rs.getString("mode")),
            DocumentTypeCode.of(rs.getString("document_type")));
    long targetId = rs.getLong("corrects_issuance_id");
    boolean noTarget = rs.wasNull();
    if (noTarget && plantedIdentity(rs)) {
      // D-A1-C06: an identity without its id is never hashed and never read; the table refuses
      // it, so a row that has it was written around the table, and the verifier reports BROKEN.
      throw new EInvoiceException(
          ErrorCodes.INVALID,
          "chained event "
              + rs.getLong("seq")
              + " carries a corrected-invoice identity without"
              + " its row id");
    }
    Optional<CorrectionTarget> target =
        noTarget
            ? Optional.empty()
            : Optional.of(
                new CorrectionTarget(
                    targetId,
                    rs.getString("corrects_seller_id"),
                    Mode.of(rs.getString("corrects_mode")),
                    rs.getString("corrects_series"),
                    rs.getInt("corrects_fiscal_year"),
                    rs.getString("corrects_legal_number")));
    return new IssuanceEvent(
        rs.getLong("seq"),
        instant(rs.getObject("ts", OffsetDateTime.class)),
        key,
        rs.getString("stripe_invoice_id"),
        rs.getString("stripe_account_id"),
        rs.getString("stripe_number"),
        new LegalNumber(rs.getString("legal_number"), rs.getLong("counter")),
        IssuanceState.of(rs.getString("state")),
        instant(rs.getObject("issued_at", OffsetDateTime.class)),
        documentHash(rs),
        rs.getString("archive_key"),
        rs.getString("rule_pack_version"),
        rs.getString("void_reason"),
        rs.getString("void_rule_id"),
        rs.getString("chain_version"),
        rs.getString("key_id"),
        rs.getString("prev_hash"),
        rs.getString("hash"),
        target,
        readDating(rs, instant(rs.getObject("allocated_at", OffsetDateTime.class))));
  }

  /**
   * Refused at construction, so at startup and before any row exists (credit-note series design,
   * invariant 3 and S-09): two series of one seller and mode whose numbers could render the same
   * string, and one series name configured for two document types. The unique index on {@code
   * (seller_id, mode, legal_number)} is the backstop if a check here is ever wrong.
   *
   * <p>"Could render the same string" is tested on the prefixes, resolved for this year and the
   * next: when one resolved prefix starts with the other, a number of the shorter one can carry the
   * longer one's characters in its digits, so the check refuses without trying to be clever.
   */
  private void refuseAmbiguousSeries(Map<SeriesId, SeriesDefinition> configured) {
    List<Map.Entry<SeriesId, SeriesDefinition>> entries = new ArrayList<>(configured.entrySet());
    int year = clock.instant().atZone(java.time.ZoneOffset.UTC).getYear();
    for (int a = 0; a < entries.size(); a++) {
      for (int b = a + 1; b < entries.size(); b++) {
        SeriesId left = entries.get(a).getKey();
        SeriesId right = entries.get(b).getKey();
        if (!left.sellerId().equals(right.sellerId()) || left.mode() != right.mode()) {
          continue;
        }
        if (left.series().equals(right.series())) {
          throw new EInvoiceException(
              ErrorCodes.CONFIG,
              "series "
                  + left.series()
                  + " is configured for two document types; one series name serves one type");
        }
        for (int y : new int[] {year, year + 1}) {
          String l = entries.get(a).getValue().resolvePrefix(y);
          String r = entries.get(b).getValue().resolvePrefix(y);
          if (l.startsWith(r) || r.startsWith(l)) {
            throw new EInvoiceException(
                ErrorCodes.CONFIG,
                "series "
                    + left.series()
                    + " and "
                    + right.series()
                    + " of one seller and mode have prefixes ("
                    + l
                    + ", "
                    + r
                    + ") that can render the same legal number. Give each series a prefix that"
                    + " does not start with another's.");
          }
        }
      }
    }
  }
}
