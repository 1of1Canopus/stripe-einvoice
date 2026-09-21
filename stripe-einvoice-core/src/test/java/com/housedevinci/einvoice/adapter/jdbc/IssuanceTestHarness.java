package com.housedevinci.einvoice.adapter.jdbc;

import com.housedevinci.einvoice.application.DeterministicRenderer;
import com.housedevinci.einvoice.application.DocumentInput;
import com.housedevinci.einvoice.application.DocumentValidator;
import com.housedevinci.einvoice.application.FakeStripeSource;
import com.housedevinci.einvoice.application.InMemoryArchiveStore;
import com.housedevinci.einvoice.application.IssuanceReader;
import com.housedevinci.einvoice.application.IssuanceUnitOfWork;
import com.housedevinci.einvoice.application.IssuanceWriter;
import com.housedevinci.einvoice.application.TestValidators;
import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceChain;
import com.housedevinci.einvoice.domain.LegalNumber;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesDefinition;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One wired unit of work over a real PostgreSQL, with the Stripe API, the renderer, the validator
 * and the object store under the test's control.
 *
 * <p>The database is real on purpose: the properties under test - resume by source id, the
 * append-only triggers, the declared transitions, the chained disposition - are the database's as
 * much as the code's, and a mock of the store would assert that this class agrees with itself.
 */
public final class IssuanceTestHarness {

  private static final Instant DEFAULT_NOW = Instant.parse("2026-01-16T09:00:00Z");

  private final String sellerId;
  private final ZoneId taxZone;
  private final JdbcIssuanceStore store;
  private final JdbcInboundEventStore inbound;
  private final FakeStripeSource source = new FakeStripeSource();
  private final DeterministicRenderer renderer = new DeterministicRenderer();
  private final InMemoryArchiveStore archive = new InMemoryArchiveStore();
  private final DocumentValidator validator;
  private final Optional<Duration> closedYearCutoff;
  private final MovableClock clock = new MovableClock(DEFAULT_NOW);
  private String pinnedApiVersion = FakeStripeSource.PINNED;

  private IssuanceTestHarness(
      DocumentValidator validator, Optional<Duration> closedYearCutoff, String zone) {
    this.validator = validator;
    this.closedYearCutoff = closedYearCutoff;
    this.taxZone = ZoneId.of(zone);
    this.sellerId = PostgresSupport.freshSeller("uow");
    this.store =
        new JdbcIssuanceStore(
            PostgresSupport.dataSource(),
            IssuanceChain.keyed(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "k1"),
            Map.of(
                new JdbcIssuanceStore.SeriesId(sellerId, "DEFAULT", Mode.LIVE),
                new SeriesDefinition("INV-{fiscalYear}-", 6, true)),
            clock);
    this.inbound =
        new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(PostgresSupport.dataSource()));
  }

  public static IssuanceTestHarness create() {
    return new IssuanceTestHarness(TestValidators.passing(), Optional.empty(), "Europe/Paris");
  }

  public static IssuanceTestHarness createWith(DocumentValidator validator) {
    return new IssuanceTestHarness(validator, Optional.empty(), "Europe/Paris");
  }

  public static IssuanceTestHarness createWithCutoff(Optional<Duration> cutoff) {
    return new IssuanceTestHarness(TestValidators.passing(), cutoff, "Europe/Paris");
  }

  public static IssuanceTestHarness createInZone(String zone) {
    return new IssuanceTestHarness(TestValidators.passing(), Optional.empty(), zone);
  }

  public IssuanceUnitOfWork unitOfWork() {
    return unitOfWorkPinnedTo(pinnedApiVersion);
  }

  public IssuanceUnitOfWork unitOfWorkPinnedTo(String apiVersion) {
    return new IssuanceUnitOfWork(
        inbound,
        source,
        store,
        store,
        store,
        renderer,
        validator,
        archive,
        preflightSupport,
        clock,
        configurationFor("DEFAULT", apiVersion));
  }

  /**
   * A unit of work with a substitute allocator, everything else unchanged - for a probe that needs
   * P1 to fail in a way the real series never fails in (D2-01).
   */
  public IssuanceUnitOfWork unitOfWorkWithAllocator(
      com.housedevinci.einvoice.application.NumberAllocator allocator) {
    return new IssuanceUnitOfWork(
        inbound,
        source,
        allocator,
        store,
        store,
        renderer,
        validator,
        archive,
        preflightSupport,
        clock,
        configurationFor("DEFAULT", pinnedApiVersion));
  }

  /**
   * A unit of work whose series is not the one configured on the store, so the allocator refuses
   * with {@code SERIES_NOT_CONFIGURED} - the shape every P1 refusal has (D2-01).
   */
  public IssuanceUnitOfWork unitOfWorkWithSeries(String series) {
    return new IssuanceUnitOfWork(
        inbound,
        source,
        store,
        store,
        store,
        renderer,
        validator,
        archive,
        preflightSupport,
        clock,
        configurationFor(series, pinnedApiVersion));
  }

  /**
   * A unit of work with a substitute renderer, everything else unchanged - for a probe that needs
   * P2's render step to fail in a way this module's own renderer never fails in (D2-04).
   */
  public IssuanceUnitOfWork unitOfWorkWithRenderer(
      com.housedevinci.einvoice.application.DocumentRenderer substituteRenderer) {
    return new IssuanceUnitOfWork(
        inbound,
        source,
        store,
        store,
        store,
        substituteRenderer,
        validator,
        archive,
        preflightSupport,
        clock,
        configurationFor("DEFAULT", pinnedApiVersion));
  }

  /**
   * A unit of work with a substitute archive, everything else unchanged - for a probe that needs
   * P3's write to fail in a way this module's own archive stores never fail in (D2-04).
   */
  public IssuanceUnitOfWork unitOfWorkWithArchive(
      com.housedevinci.einvoice.application.ArchiveStore substituteArchive) {
    return new IssuanceUnitOfWork(
        inbound,
        source,
        store,
        store,
        store,
        renderer,
        validator,
        substituteArchive,
        preflightSupport,
        clock,
        configurationFor("DEFAULT", pinnedApiVersion));
  }

  private IssuanceUnitOfWork.Configuration configurationFor(String series, String apiVersion) {
    return new IssuanceUnitOfWork.Configuration(
        sellerId,
        "",
        Mode.LIVE,
        series,
        true,
        taxZone,
        apiVersion,
        "fr-2026.1",
        Duration.ofMinutes(1),
        Duration.ofHours(1),
        closedYearCutoff,
        false);
  }

  /** Records one verified event exactly as the endpoint would, and returns its id. */
  public String receive(String type, String objectId) {
    return receive(type, objectId, FakeStripeSource.PINNED, true, "");
  }

  /** Records an event whose raw body is the test's own, for the payload-as-a-source probe. */
  public String receiveWithBody(String type, String objectId, String rawBody) {
    String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
    byte[] body = rawBody.getBytes(StandardCharsets.UTF_8);
    EventIdentity identity =
        new EventIdentity(eventId, type, FakeStripeSource.PINNED, true, "", objectId);
    inbound.record(
        InboundEvent.received(identity, Mode.LIVE, "primary", body, clock.instant()), body);
    return eventId;
  }

  public String receive(
      String type, String objectId, String apiVersion, boolean livemode, String account) {
    String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
    byte[] body = ("{\"id\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
    EventIdentity identity =
        new EventIdentity(eventId, type, apiVersion, livemode, account, objectId);
    inbound.record(
        InboundEvent.received(identity, Mode.LIVE, "primary", body, clock.instant()), body);
    return eventId;
  }

  private final JdbcFindingStore findings =
      new JdbcFindingStore(JdbcUnitOfWork.ownConnection(PostgresSupport.dataSource()));

  private final com.housedevinci.einvoice.application.PreflightSupport preflightSupport =
      new com.housedevinci.einvoice.application.PreflightSupport(findings, clock);

  /** The preflight-support recorder this harness's units of work share. */
  public com.housedevinci.einvoice.application.PreflightSupport preflightSupport() {
    return preflightSupport;
  }

  public JdbcFindingStore findings() {
    return findings;
  }

  /** The free-core sweep, wired over the same stores the unit of work writes to. */
  public com.housedevinci.einvoice.application.ReconciliationSweep sweep(
      com.housedevinci.einvoice.application.ReconciliationSweep.Settings settings) {
    return new com.housedevinci.einvoice.application.ReconciliationSweep(
        source, store, inbound, archive, findings, clock, settings, unitOfWork().configuration());
  }

  /** Rows in the findings table for one subject, acknowledged or not. */
  public long findingRows(String subjectId) {
    return PostgresSupport.scalar(
        "SELECT count(*) FROM einvoice_finding WHERE seller_id = '"
            + sellerId
            + "' AND subject_id = '"
            + subjectId
            + "'");
  }

  public List<com.housedevinci.einvoice.domain.ComplianceFinding> openFindings() {
    return findings.open(sellerId, Mode.LIVE, 100);
  }

  public FakeStripeSource source() {
    return source;
  }

  public DeterministicRenderer renderer() {
    return renderer;
  }

  public InMemoryArchiveStore archive() {
    return archive;
  }

  public JdbcInboundEventStore inbound() {
    return inbound;
  }

  public IssuanceReader reader() {
    return store;
  }

  public IssuanceWriter writer() {
    return store;
  }

  public JdbcIssuanceStore store() {
    return store;
  }

  public String sellerId() {
    return sellerId;
  }

  public Optional<Issuance> issuance(String stripeInvoiceId) {
    return store.findBySource(sellerId, Mode.LIVE, stripeInvoiceId);
  }

  public Optional<byte[]> archived(Issuance issuance) {
    return archive.get(new ArchiveKey(issuance.archiveKey()));
  }

  public byte[] archivedBytes(String stripeInvoiceId) {
    return archived(issuance(stripeInvoiceId).orElseThrow()).orElseThrow();
  }

  /** How many numbers this seller has consumed, whatever state they are in. */
  public long numberedRows() {
    return PostgresSupport.scalar(
        "SELECT count(*) FROM einvoice_issuance WHERE seller_id = '" + sellerId + "'");
  }

  public long chainedEvents() {
    return PostgresSupport.scalar(
        "SELECT count(*) FROM einvoice_issuance_event WHERE seller_id = '" + sellerId + "'");
  }

  /** Due against this harness's own configured pin - what the sweeper would see unchanged. */
  public List<InboundEvent> due() {
    return due(pinnedApiVersion);
  }

  /**
   * Due against a given pin, simulating an operator who updated {@code einvoice.stripe.api-version}
   * and restarted (D2-03): a {@code REFUSED_VERSION_SKEW} row whose recorded {@code api_version}
   * equals {@code pin} is re-picked.
   */
  public List<InboundEvent> due(String pin) {
    return inbound.due(clock.instant(), Duration.ofHours(72), 100, pin);
  }

  public SeriesKey seriesKey() {
    return new SeriesKey(sellerId, "DEFAULT", 2026, Mode.LIVE);
  }

  public LegalNumber legalNumber(String value) {
    return new LegalNumber(value, 1);
  }

  /** The bytes this fixture renders to, computed the way the unit of work computes them. */
  public byte[] renderFor(String invoiceId, String number) {
    return renderer
        .render(
            new DocumentInput(
                new com.housedevinci.einvoice.application.MappingInput(
                    source.fetchInvoice(invoiceId),
                    seriesKey(),
                    LocalDate.ofInstant(source.fetchInvoice(invoiceId).finalizedAt(), taxZone),
                    "fr-2026.1"),
                legalNumber(number)))
        .bytes();
  }

  /**
   * Kills the process in the middle of P4's transaction: the ISSUED update and the chain append
   * both run, and then the connection is rolled back without a commit - which is exactly what a
   * crash between the two statements and the commit leaves behind.
   */
  public void killDuringChainAppend(String stripeInvoiceId) {
    try (java.sql.Connection connection = PostgresSupport.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      JdbcIssuanceStore killed =
          new JdbcIssuanceStore(
              JdbcUnitOfWork.using(connection),
              PostgresSupport.dataSource(),
              IssuanceChain.keyed(
                  "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "k1"),
              Map.of(
                  new JdbcIssuanceStore.SeriesId(sellerId, "DEFAULT", Mode.LIVE),
                  new SeriesDefinition("INV-{fiscalYear}-", 6, true)),
              clock,
              JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT);
      killed.markIssued(sellerId, Mode.LIVE, stripeInvoiceId);
      connection.rollback();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("test setup failed: " + e.getMessage(), e);
    }
  }

  public long anchorRowCount() {
    return PostgresSupport.scalar("SELECT row_count FROM einvoice_issuance_anchor WHERE id = 1");
  }

  public void moveClockForward(Duration by) {
    clock.moveForward(by);
  }

  public void moveClockTo(Instant instant) {
    clock.moveTo(instant);
  }

  /** A clock the test moves, so a retry ceiling or a fiscal year is crossed without sleeping. */
  static final class MovableClock extends Clock {

    private volatile Instant now;

    MovableClock(Instant now) {
      this.now = now;
    }

    void moveForward(Duration by) {
      now = now.plus(by);
    }

    void moveTo(Instant instant) {
      now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
