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
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceAnchor;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.IssuanceEvent;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.LegalNumber;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import com.housedevinci.einvoice.domain.SeriesReport;
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
        com.housedevinci.einvoice.application.IssuanceWriter {

  private static final Logger log = LoggerFactory.getLogger(JdbcIssuanceStore.class);

  static final String SELECT_ISSUANCE_COLUMNS =
      "id, seller_id, mode, stripe_invoice_id, stripe_account_id, stripe_number, series,"
          + " fiscal_year, legal_number, counter, issued_at, allocated_at, document_sha256,"
          + " archive_key, rule_pack_version, state, void_reason, void_rule_id, document_type,"
          + " corrects_issuance_id";

  static final String FIND_BY_SOURCE_FOR_UPDATE =
      "SELECT "
          + SELECT_ISSUANCE_COLUMNS
          + " FROM einvoice_issuance"
          + " WHERE seller_id = ? AND mode = ? AND stripe_invoice_id = ? FOR UPDATE";

  static final String FIND_BY_SOURCE =
      "SELECT "
          + SELECT_ISSUANCE_COLUMNS
          + " FROM einvoice_issuance WHERE seller_id = ? AND mode = ? AND stripe_invoice_id = ?";

  static final String CREATE_SERIES_ROW =
      "INSERT INTO einvoice_series (seller_id, document_type, series, fiscal_year, mode, prefix,"
          + " width, next_number, updated_at) VALUES (?,?,?,?,?,?,?,1,?) ON CONFLICT DO NOTHING";

  // D1-03: the bound and the rendered value both come from the row's own prefix and width, never
  // from the configured definition, so an edited property cannot silently change the format of a
  // live series. D1-01: the row's prefix was resolved from the year placeholder once, at the row's
  // creation, so it already differs per fiscal year - the allocator does not need to know that
  // here.
  //
  // D-PS1-38: an invoice is numbered whatever its date - a sale must get its invoice - and the
  // series keeps the later of the two dates with GREATEST, so last_issue_date never goes back and
  // the series guard (which refuses a backwards date for every type) is never fought. An earlier
  // date is recorded as a finding by the caller of this statement.
  static final String ALLOCATE =
      "UPDATE einvoice_series SET next_number = next_number + 1,"
          + " last_issue_date = GREATEST(last_issue_date, ?), updated_at = ?"
          + " WHERE seller_id = ? AND document_type = ? AND series = ? AND fiscal_year = ?"
          + " AND mode = ? AND next_number <= (power(10, width)::bigint - 1)"
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
      "SELECT last_issue_date FROM einvoice_series"
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
          + " rule_pack_version, state, document_type, corrects_issuance_id)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id";

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
          + " void_reason, void_rule_id FROM einvoice_issuance"
          + " WHERE seller_id = ? AND document_type = ? AND series = ? AND fiscal_year = ?"
          + " AND mode = ? ORDER BY counter";

  /** D1-05's default when a caller does not configure one, matching the property's own default. */
  public static final Duration DEFAULT_ALLOCATION_TIMEOUT = Duration.ofSeconds(5);

  private final JdbcUnitOfWork unitOfWork;
  private final DataSource dataSource;
  private final IssuanceChain chain;
  private final Map<SeriesId, SeriesDefinition> series;
  private final Clock clock;
  private final Duration allocationTimeout;

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
      Clock clock) {
    this(dataSource, chain, series, clock, DEFAULT_ALLOCATION_TIMEOUT);
  }

  public JdbcIssuanceStore(
      DataSource dataSource,
      IssuanceChain chain,
      Map<SeriesId, SeriesDefinition> series,
      Clock clock,
      Duration allocationTimeout) {
    this(
        JdbcUnitOfWork.ownConnection(dataSource),
        dataSource,
        chain,
        series,
        clock,
        allocationTimeout);
  }

  /**
   * The canonical constructor: every statement this store issues runs through {@code unitOfWork}
   * (T-01), including the reads, so a host that allocates and then reads inside one transaction
   * sees its own uncommitted row.
   *
   * <p>{@code dataSource} is kept for one thing only: {@link #openSeries(SeriesKey)}, which the
   * startup check calls before any host transaction exists and which must not join one.
   */
  public JdbcIssuanceStore(
      JdbcUnitOfWork unitOfWork,
      DataSource dataSource,
      IssuanceChain chain,
      Map<SeriesId, SeriesDefinition> series,
      Clock clock,
      Duration allocationTimeout) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.chain = Objects.requireNonNull(chain, "chain");
    this.series = Map.copyOf(series);
    this.clock = Objects.requireNonNull(clock, "clock");
    refuseAmbiguousSeries(this.series);
    this.allocationTimeout = Objects.requireNonNull(allocationTimeout, "allocationTimeout");
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
   */
  @Override
  public Issuance allocate(AllocationRequest request) {
    if (request.seriesKey().documentType() != DocumentTypeCode.COMMERCIAL_INVOICE) {
      throw new EInvoiceException(
          ErrorCodes.DOCUMENT_TYPE_NOT_ISSUABLE,
          "this allocator numbers invoices (380) only. A number of type "
              + request.seriesKey().documentTypeCode()
              + " is allocated only by the pipeline that produces that document; allocating one"
              + " here would leave a number no document will ever carry.");
    }
    return allocateTyped(request, Optional.empty());
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
   * @param correctsIssuanceId the row id of the corrected invoice: present for a credit note,
   *     absent for an invoice
   */
  Issuance allocateTyped(AllocationRequest request, Optional<Long> correctsIssuanceId) {
    SeriesKey key = request.seriesKey();
    Objects.requireNonNull(correctsIssuanceId, "correctsIssuanceId");
    if (key.documentType().isCreditNote() != correctsIssuanceId.isPresent()) {
      throw new EInvoiceException(
          ErrorCodes.CREDIT_TARGET_INVALID,
          key.documentType().isCreditNote()
              ? "a credit note must name the invoice it corrects"
              : "an invoice corrects no other document");
    }
    SeriesDefinition definition = definitionFor(key);
    return unitOfWork.inTransaction(
        unit -> {
          Connection c = unit.connection();
          // D1-12: recorded as the very first thing this unit of work does, before the resume
          // read and before the series-row insert, not just before the counter statement. A
          // lock-order refusal is then raised before anything at all has run, which makes it
          // non-poisoning under any predicate for "has this unit of work written" - belt and
          // braces alongside the read/write classification below.
          unit.locks().seriesRowLock();
          // D1-05: bounds how long this transaction will wait on the series row's lock, so a
          // blocked allocation gives up with a typed refusal rather than joining an unbounded
          // queue. T-05: in a joined transaction the setting outlives this call, so the caller's
          // previous value is restored on every path, including the failing ones.
          String previousLockTimeout = unit.joined() ? currentLockTimeout(unit) : null;
          setLockTimeout(unit);
          try {
            // Resume, never re-allocate. A row in any state returns its existing number; only a
            // miss reaches the counter.
            Optional<Issuance> existing =
                findBySource(c, key.sellerId(), key.mode(), request.stripeInvoiceId(), true);
            if (existing.isPresent()) {
              return existing.get();
            }
            if (correctsIssuanceId.isPresent()) {
              requireCorrectableInvoice(c, key, correctsIssuanceId.get());
            }
            createSeriesRowForNewFiscalYear(c, key, definition);
            LegalNumber number = nextNumber(unit, key, definition, request.issueDate());
            Instant allocatedAt = Timestamps.toStorage(clock.instant());
            return insertIssuance(c, request, number, allocatedAt, correctsIssuanceId);
          } catch (SQLException e) {
            if ("55P03".equals(e.getSQLState())) {
              throw new EInvoiceException(
                  ErrorCodes.ALLOCATION_TIMEOUT,
                  "this allocation waited longer than einvoice.numbering.allocation-timeout ("
                      + allocationTimeout
                      + ") for the series row's lock and gave up rather than join an unbounded"
                      + " queue",
                  e);
            }
            throw e;
          } finally {
            restoreLockTimeout(unit, previousLockTimeout);
          }
        });
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
      int created;
      try {
        created = ps.executeUpdate();
      } catch (SQLException e) {
        if ("EIS01".equals(e.getSQLState())) {
          // S-09: the insert guard binds a series name to one document type across fiscal years.
          throw new EInvoiceException(
              ErrorCodes.CONFIG,
              "series "
                  + key.series()
                  + " already numbers another document type for this seller and mode; one series"
                  + " name serves one document type. Configure a different series name.",
              e);
        }
        throw e;
      }
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
   * D1-03. Renders from the row's own {@code prefix} and {@code width}, read in the same statement
   * as the counter, never from today's configured {@link SeriesDefinition}: the row is immutable on
   * those two columns once it exists (the trigger says so), so this is the only way the rendered
   * format cannot drift from what a restart with an edited property would otherwise produce.
   */
  private LegalNumber nextNumber(
      JdbcUnitOfWork.Unit unit, SeriesKey key, SeriesDefinition definition, LocalDate issueDate)
      throws SQLException {
    Connection c = unit.connection();
    // T-02, D1-12: the ledger entry for this lock is recorded at the head of allocate()'s unit of
    // work, before the resume read, not here - a transaction that already holds the chain lock
    // must be refused before it does anything at all, not only once it reaches the counter.
    Optional<LocalDate> previousDate = lockSeriesRow(c, key);
    boolean inDateOrder = key.documentType().isCreditNote();
    try (PreparedStatement ps =
        c.prepareStatement(inDateOrder ? ALLOCATE_IN_DATE_ORDER : ALLOCATE)) {
      int i = 1;
      ps.setObject(i++, issueDate);
      ps.setObject(i++, ts(Timestamps.toStorage(clock.instant())));
      i = bindSeriesKey(ps, i, key);
      if (inDateOrder) {
        ps.setObject(i, issueDate);
      }
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          long counter = rs.getLong(1);
          String prefix = rs.getString(2);
          int width = rs.getInt(3);
          LegalNumber number = LegalNumber.render(prefix, width, counter);
          if (previousDate.isPresent() && issueDate.isBefore(previousDate.get())) {
            recordOutOfOrder(c, key, number);
          }
          return number;
        }
      }
    }
    throw exhaustedOutOfOrderOrUnconfigured(c, key, definition, issueDate);
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

  private static Optional<LocalDate> lockSeriesRow(Connection c, SeriesKey key)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(LOCK_SERIES)) {
      bindSeriesKey(ps, 1, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new EInvoiceException(
              ErrorCodes.SERIES_NOT_CONFIGURED,
              "the numbering series row disappeared between its creation and this allocation");
        }
        return Optional.ofNullable(rs.getObject(1, LocalDate.class));
      }
    }
  }

  /**
   * D-PS1-27, D-PS1-38. An invoice whose date is earlier than the latest date its series already
   * numbered is still numbered: a sale must get its invoice, and the remedy for the order of dates
   * is a separate design. What this adds is that the fact is not silent. An open finding names the
   * number, in the same transaction as the number itself, so the two commit or roll back together.
   * A credit note never reaches this: its statement refuses the earlier date (T9).
   */
  private void recordOutOfOrder(Connection c, SeriesKey key, LegalNumber number)
      throws SQLException {
    Instant now = Timestamps.toStorage(clock.instant());
    ComplianceFinding finding =
        ComplianceFinding.of(
            key.sellerId(), key.mode(), ErrorCodes.ISSUE_DATE_OUT_OF_ORDER, number.value(), now);
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
    log.warn(
        "einvoice: invoice {} in series {} is dated before the latest date that series already"
            + " numbered; numbered anyway and recorded as finding {}",
        number.value(),
        key.series(),
        ErrorCodes.ISSUE_DATE_OUT_OF_ORDER);
  }

  /**
   * D-CNS-S12, checked by core before a number is consumed: the target exists for this seller and
   * mode, is an invoice, is ISSUED, and its stored hash equals the hash on the latest chained event
   * of its number, which is the ISSUED event. ISSUED is terminal and the hash write-once, so no
   * lock is needed for this to stay true; the cap on what a credit may credit is not this method's
   * job.
   */
  private static void requireCorrectableInvoice(Connection c, SeriesKey key, long targetId)
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

  private EInvoiceException exhaustedOutOfOrderOrUnconfigured(
      Connection c, SeriesKey key, SeriesDefinition definition, LocalDate issueDate)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(READ_SERIES)) {
      bindSeriesKey(ps, 1, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          LocalDate last = rs.getObject("last_issue_date", LocalDate.class);
          if (key.documentType().isCreditNote() && last != null && issueDate.isBefore(last)) {
            return new EInvoiceException(
                ErrorCodes.ISSUE_DATE_OUT_OF_ORDER,
                "this credit note is dated before the latest date its series already numbered;"
                    + " numbering it would make the dates of the series go backwards. Void it"
                    + " upstream and create a new one, which takes today's date.");
          }
          return new EInvoiceException(
              ErrorCodes.SERIES_EXHAUSTED,
              "this numbering series has handed out every number its configured width of "
                  + definition.width()
                  + " can render. Widening it changes the format of a legal number mid-series, so"
                  + " it needs an operator's decision and a new series, not a wider pad.");
        }
      }
    }
    return new EInvoiceException(
        ErrorCodes.SERIES_NOT_CONFIGURED,
        "the numbering series row disappeared between its creation and this allocation");
  }

  private Issuance insertIssuance(
      Connection c,
      AllocationRequest request,
      LegalNumber number,
      Instant allocatedAt,
      Optional<Long> correctsIssuanceId)
      throws SQLException {
    SeriesKey key = request.seriesKey();
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
      ps.setObject(i++, ts(allocatedAt));
      ps.setString(i++, request.rulePackVersion());
      ps.setString(i++, IssuanceState.NUMBERED.name());
      ps.setString(i++, key.documentTypeCode());
      if (correctsIssuanceId.isPresent()) {
        ps.setLong(i, correctsIssuanceId.get());
      } else {
        ps.setNull(i, java.sql.Types.BIGINT);
      }
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
            allocatedAt,
            "",
            "",
            request.rulePackVersion(),
            IssuanceState.NUMBERED,
            "",
            "",
            correctsIssuanceId);
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
                  issuance.correctsIssuanceId());
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
            // is ISSUED or VOID_UNUSED, both of which are.
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
                + " corrects_fiscal_year, corrects_legal_number)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
        ps.setString(i, t.legalNumber());
      } else {
        ps.setNull(i++, java.sql.Types.BIGINT);
        ps.setNull(i++, java.sql.Types.VARCHAR);
        ps.setNull(i++, java.sql.Types.VARCHAR);
        ps.setNull(i++, java.sql.Types.VARCHAR);
        ps.setNull(i++, java.sql.Types.INTEGER);
        ps.setNull(i, java.sql.Types.VARCHAR);
      }
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

  @Override
  public SeriesReport seriesReport(SeriesKey key) {
    return unitOfWork.inReadUnit(
        unit -> {
          Connection c = unit.connection();
          List<SeriesReport.Line> lines = new ArrayList<>();
          long open = 0;
          try (PreparedStatement ps = c.prepareStatement(SERIES_LINES)) {
            bindSeriesKey(ps, 1, key);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                IssuanceState state = IssuanceState.of(rs.getString("state"));
                if (state.open()) {
                  open++;
                }
                lines.add(
                    new SeriesReport.Line(
                        rs.getLong("counter"),
                        rs.getString("legal_number"),
                        state,
                        rs.getString("stripe_invoice_id"),
                        instant(rs.getObject("issued_at", OffsetDateTime.class)),
                        instant(rs.getObject("allocated_at", OffsetDateTime.class)),
                        rs.getString("void_reason"),
                        rs.getString("void_rule_id")));
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
                      + " corrects_series, corrects_fiscal_year, corrects_legal_number"
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
                          + " t.fiscal_year, t.legal_number"
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
                      targetIdentity(rs, 11)));
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
        correctsIssuanceId);
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

  private static IssuanceEvent readEvent(ResultSet rs) throws SQLException {
    SeriesKey key =
        new SeriesKey(
            rs.getString("seller_id"),
            rs.getString("series"),
            rs.getInt("fiscal_year"),
            Mode.of(rs.getString("mode")),
            DocumentTypeCode.of(rs.getString("document_type")));
    long targetId = rs.getLong("corrects_issuance_id");
    Optional<CorrectionTarget> target =
        rs.wasNull()
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
        target);
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
