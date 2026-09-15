package com.housedevinci.einvoice.adapter.jdbc;

import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.instant;
import static com.housedevinci.einvoice.adapter.jdbc.JdbcSupport.ts;

import com.housedevinci.einvoice.application.AllocationRequest;
import com.housedevinci.einvoice.application.IssuanceEventReader;
import com.housedevinci.einvoice.application.IssuanceReader;
import com.housedevinci.einvoice.application.NumberAllocator;
import com.housedevinci.einvoice.application.NumberVoider;
import com.housedevinci.einvoice.application.VoidRequest;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
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
    implements NumberAllocator, NumberVoider, IssuanceReader, IssuanceEventReader, IssuanceAnchor {

  private static final Logger log = LoggerFactory.getLogger(JdbcIssuanceStore.class);

  static final String SELECT_ISSUANCE_COLUMNS =
      "id, seller_id, mode, stripe_invoice_id, stripe_account_id, stripe_number, series,"
          + " fiscal_year, legal_number, counter, issued_at, allocated_at, document_sha256,"
          + " archive_key, rule_pack_version, state, void_reason, void_rule_id";

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
      "INSERT INTO einvoice_series"
          + " (seller_id, series, fiscal_year, mode, prefix, width, next_number, updated_at)"
          + " VALUES (?,?,?,?,?,?,1,?) ON CONFLICT DO NOTHING";

  static final String ALLOCATE =
      "UPDATE einvoice_series SET next_number = next_number + 1, updated_at = ?"
          + " WHERE seller_id = ? AND series = ? AND fiscal_year = ? AND mode = ?"
          + " AND next_number <= ?"
          + " RETURNING next_number - 1";

  static final String READ_SERIES =
      "SELECT prefix, width, next_number FROM einvoice_series"
          + " WHERE seller_id = ? AND series = ? AND fiscal_year = ? AND mode = ?";

  static final String INSERT_ISSUANCE =
      "INSERT INTO einvoice_issuance (seller_id, mode, stripe_invoice_id, stripe_account_id,"
          + " stripe_number, series, fiscal_year, legal_number, counter, issued_at, allocated_at,"
          + " rule_pack_version, state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id";

  static final String VOID_ISSUANCE =
      "UPDATE einvoice_issuance SET state = ?, void_reason = ?, void_rule_id = ?"
          + " WHERE seller_id = ? AND mode = ? AND stripe_invoice_id = ?";

  static final String SERIES_LINES =
      "SELECT counter, legal_number, state, stripe_invoice_id, issued_at, allocated_at,"
          + " void_reason, void_rule_id FROM einvoice_issuance"
          + " WHERE seller_id = ? AND series = ? AND fiscal_year = ? AND mode = ?"
          + " ORDER BY counter";

  private final DataSource dataSource;
  private final IssuanceChain chain;
  private final Map<SeriesId, SeriesDefinition> series;
  private final Clock clock;

  /** The configuration key of a series, before a fiscal year is known. */
  public record SeriesId(String sellerId, String series, Mode mode) {}

  public JdbcIssuanceStore(
      DataSource dataSource,
      IssuanceChain chain,
      Map<SeriesId, SeriesDefinition> series,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.chain = Objects.requireNonNull(chain, "chain");
    this.series = Map.copyOf(series);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  // ---------------------------------------------------------------------------------------------
  // Allocation
  // ---------------------------------------------------------------------------------------------

  @Override
  public Issuance allocate(AllocationRequest request) {
    SeriesKey key = request.seriesKey();
    SeriesDefinition definition = definitionFor(key);
    return JdbcSupport.inTransaction(
        dataSource,
        c -> {
          // Resume, never re-allocate. A row in any state returns its existing number; only a miss
          // reaches the counter.
          Optional<Issuance> existing =
              findBySource(c, key.sellerId(), key.mode(), request.stripeInvoiceId(), true);
          if (existing.isPresent()) {
            return existing.get();
          }
          createSeriesRowForNewFiscalYear(c, key, definition);
          long counter = nextCounter(c, key, definition);
          LegalNumber number = LegalNumber.render(definition, counter);
          Instant allocatedAt = Timestamps.toStorage(clock.instant());
          return insertIssuance(c, request, number, allocatedAt);
        });
  }

  private SeriesDefinition definitionFor(SeriesKey key) {
    SeriesDefinition definition =
        series.get(new SeriesId(key.sellerId(), key.series(), key.mode()));
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
  private void createSeriesRowForNewFiscalYear(
      Connection c, SeriesKey key, SeriesDefinition definition) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(CREATE_SERIES_ROW)) {
      int i = 1;
      ps.setString(i++, key.sellerId());
      ps.setString(i++, key.series());
      ps.setInt(i++, key.fiscalYear());
      ps.setString(i++, key.mode().wire());
      ps.setString(i++, definition.prefix());
      ps.setInt(i++, definition.width());
      ps.setObject(i, ts(Timestamps.toStorage(clock.instant())));
      if (ps.executeUpdate() > 0) {
        log.info(
            "einvoice: opened numbering series seller={} series={} year={} mode={} at 1",
            key.sellerId(),
            key.series(),
            key.fiscalYear(),
            key.mode().wire());
      }
    }
  }

  private long nextCounter(Connection c, SeriesKey key, SeriesDefinition definition)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(ALLOCATE)) {
      int i = 1;
      ps.setObject(i++, ts(Timestamps.toStorage(clock.instant())));
      ps.setString(i++, key.sellerId());
      ps.setString(i++, key.series());
      ps.setInt(i++, key.fiscalYear());
      ps.setString(i++, key.mode().wire());
      // N-02: the bound is in the statement, so the counter is never consumed past what the
      // configured width can render. A number that silently gets wider is a format change to a
      // legal series, and widening it is an operator's decision, never the allocator's.
      ps.setLong(i, definition.lastRenderableNumber());
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getLong(1);
        }
      }
    }
    throw exhaustedOrUnconfigured(c, key, definition);
  }

  private EInvoiceException exhaustedOrUnconfigured(
      Connection c, SeriesKey key, SeriesDefinition definition) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(READ_SERIES)) {
      int i = 1;
      ps.setString(i++, key.sellerId());
      ps.setString(i++, key.series());
      ps.setInt(i++, key.fiscalYear());
      ps.setString(i, key.mode().wire());
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
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
      Connection c, AllocationRequest request, LegalNumber number, Instant allocatedAt)
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
      ps.setString(i, IssuanceState.NUMBERED.name());
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
            "");
      }
    } catch (SQLException e) {
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
   */
  public void openSeries(SeriesKey key) {
    SeriesDefinition definition = definitionFor(key);
    JdbcSupport.withConnection(
        dataSource,
        c -> {
          createSeriesRowForNewFiscalYear(c, key, definition);
          return null;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Disposition
  // ---------------------------------------------------------------------------------------------

  @Override
  public Issuance voidUnused(VoidRequest request) {
    return JdbcSupport.inTransaction(
        dataSource,
        c -> {
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
                  request.ruleId());
          appendEvent(c, IssuanceEvent.voided(voided, clock.instant()));
          return voided;
        });
  }

  /**
   * Appends one chained event and advances the anchor, in the caller's transaction.
   *
   * <p>The chain lock is this module's own two-argument constant (N-05), and it is taken in a
   * transaction that holds no series counter row lock: nothing in this module ever holds both
   * (N-07).
   */
  private void appendEvent(Connection c, IssuanceEvent event) throws SQLException {
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
                + " void_rule_id, chain_version, key_id, prev_hash, hash)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
      ps.setString(i, linked.hash());
      ps.executeUpdate();
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
    return JdbcSupport.withConnection(
        dataSource, c -> findBySource(c, sellerId, mode, stripeInvoiceId, false));
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
  public SeriesReport seriesReport(SeriesKey key) {
    return JdbcSupport.withConnection(
        dataSource,
        c -> {
          List<SeriesReport.Line> lines = new ArrayList<>();
          long open = 0;
          try (PreparedStatement ps = c.prepareStatement(SERIES_LINES)) {
            int i = 1;
            ps.setString(i++, key.sellerId());
            ps.setString(i++, key.series());
            ps.setInt(i++, key.fiscalYear());
            ps.setString(i, key.mode().wire());
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
            int i = 1;
            ps.setString(i++, key.sellerId());
            ps.setString(i++, key.series());
            ps.setInt(i++, key.fiscalYear());
            ps.setString(i, key.mode().wire());
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
    return JdbcSupport.withConnection(
        dataSource,
        c -> {
          List<IssuanceEvent> events = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT seq, ts, seller_id, mode, stripe_account_id, series, fiscal_year,"
                      + " legal_number, counter, stripe_invoice_id, stripe_number, state,"
                      + " issued_at, document_sha256, archive_key, rule_pack_version, void_reason,"
                      + " void_rule_id, chain_version, key_id, prev_hash, hash"
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
    return JdbcSupport.withConnection(
        dataSource,
        c -> {
          List<DisposedIssuance> disposed = new ArrayList<>();
          try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT seller_id, mode, legal_number, state FROM einvoice_issuance"
                          + " WHERE state IN ('ISSUED', 'VOID_UNUSED') ORDER BY id");
              ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              disposed.add(
                  new DisposedIssuance(
                      rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
          }
          return disposed;
        });
  }

  @Override
  public Optional<Anchor> anchor() {
    return JdbcSupport.withConnection(
        dataSource,
        c -> {
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

  private static Issuance readIssuance(ResultSet rs) throws SQLException {
    SeriesKey key =
        new SeriesKey(
            rs.getString("seller_id"),
            rs.getString("series"),
            rs.getInt("fiscal_year"),
            Mode.of(rs.getString("mode")));
    return new Issuance(
        rs.getLong("id"),
        key,
        rs.getString("stripe_invoice_id"),
        rs.getString("stripe_account_id"),
        rs.getString("stripe_number"),
        new LegalNumber(rs.getString("legal_number"), rs.getLong("counter")),
        instant(rs.getObject("issued_at", OffsetDateTime.class)),
        instant(rs.getObject("allocated_at", OffsetDateTime.class)),
        rs.getString("document_sha256"),
        rs.getString("archive_key"),
        rs.getString("rule_pack_version"),
        IssuanceState.of(rs.getString("state")),
        rs.getString("void_reason"),
        rs.getString("void_rule_id"));
  }

  private static IssuanceEvent readEvent(ResultSet rs) throws SQLException {
    SeriesKey key =
        new SeriesKey(
            rs.getString("seller_id"),
            rs.getString("series"),
            rs.getInt("fiscal_year"),
            Mode.of(rs.getString("mode")));
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
        rs.getString("document_sha256"),
        rs.getString("archive_key"),
        rs.getString("rule_pack_version"),
        rs.getString("void_reason"),
        rs.getString("void_rule_id"),
        rs.getString("chain_version"),
        rs.getString("key_id"),
        rs.getString("prev_hash"),
        rs.getString("hash"));
  }
}
