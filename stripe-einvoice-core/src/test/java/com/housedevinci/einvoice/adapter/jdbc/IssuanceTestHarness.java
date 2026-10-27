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
  private final TestIssueClock issueClock = TestIssueClock.following(clock);
  private String pinnedApiVersion = FakeStripeSource.PINNED;

  private final javax.sql.DataSource dataSource;

  private IssuanceTestHarness(
      DocumentValidator validator, Optional<Duration> closedYearCutoff, String zone) {
    this(validator, closedYearCutoff, zone, PostgresSupport.dataSource());
  }

  private IssuanceTestHarness(
      DocumentValidator validator,
      Optional<Duration> closedYearCutoff,
      String zone,
      javax.sql.DataSource dataSource) {
    this.dataSource = dataSource;
    this.findings = new JdbcFindingStore(JdbcUnitOfWork.ownConnection(dataSource));
    this.reprocessLedger = new JdbcReprocessLedger(JdbcUnitOfWork.ownConnection(dataSource));
    this.preflightSupport =
        new com.housedevinci.einvoice.application.PreflightSupport(findings, clock);
    this.validator = validator;
    this.closedYearCutoff = closedYearCutoff;
    this.taxZone = ZoneId.of(zone);
    this.sellerId = PostgresSupport.freshSeller("uow");
    this.store =
        TestStores.store(
            JdbcUnitOfWork.ownConnection(dataSource),
            dataSource,
            IssuanceChain.keyed(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "k1"),
            Map.of(
                new JdbcIssuanceStore.SeriesId(sellerId, "DEFAULT", Mode.LIVE),
                new SeriesDefinition("INV-{fiscalYear}-", 6, true)),
            taxZone,
            clock,
            JdbcIssuanceStore.DEFAULT_ALLOCATION_TIMEOUT,
            issueClock);
    this.inbound = new JdbcInboundEventStore(JdbcUnitOfWork.ownConnection(dataSource));
    this.runLock = new JdbcInboundRunLock(JdbcUnitOfWork.ownConnection(dataSource), dataSource);
  }

  private com.housedevinci.einvoice.application.InboundRunLock runLock;

  /**
   * Defence in depth only. The units of work built after this call run with no run lock: every run
   * is granted, and its writes go to the plain store. Used by the tests written before the run lock
   * (D-PR45-1) that drive two runs of one event at once on purpose, to keep proving the layers
   * under the lock - the compare-and-set writes (D-PR9-F1), the allocator's one number per invoice
   * and the PR 43 row lock - which the design keeps on purpose (D-ORPE-6c). Never a production
   * path: the module wires {@code JdbcInboundRunLock} and nothing else.
   */
  public IssuanceTestHarness withoutRunLock() {
    this.runLock =
        new com.housedevinci.einvoice.application.InboundRunLock() {
          @Override
          public <T> Optional<T> whileHeld(
              InboundEvent storedRow, java.util.function.Function<HeldRun, T> work) {
            return Optional.of(work.apply(new HeldRun(inbound, () -> {})));
          }
        };
    return this;
  }

  /** Enough permits for every race test of this suite; T18 builds its own bound. */
  private final com.housedevinci.einvoice.application.RunPermits permits =
      new com.housedevinci.einvoice.application.RunPermits(8);

  /** The run lock every unit of work of this harness takes (D-PR45-1). */
  public com.housedevinci.einvoice.application.InboundRunLock runLock() {
    return runLock;
  }

  /** The permits every unit of work of this harness shares. */
  public com.housedevinci.einvoice.application.RunPermits permits() {
    return permits;
  }

  /**
   * The store's real row lock, handing the work the test's own substitute ports instead of the
   * lock-bound ones: for the probes that inject a store fault into a reader or a writer.
   */
  public static com.housedevinci.einvoice.application.IssuanceRowLock lockWith(
      JdbcIssuanceStore store, IssuanceReader reader, IssuanceWriter writer) {
    return new com.housedevinci.einvoice.application.IssuanceRowLock() {
      @Override
      public <T> Optional<T> whileHeld(long issuanceId, java.util.function.Function<Held, T> work) {
        return store.whileHeld(issuanceId, held -> work.apply(new Held(reader, writer)));
      }

      @Override
      public void verifySessionPinning() {
        store.verifySessionPinning();
      }
    };
  }

  /** A harness over a data source the test chose: a small pool, a pooler, a fresh database. */
  public static IssuanceTestHarness createOn(javax.sql.DataSource dataSource) {
    return new IssuanceTestHarness(
        TestValidators.passing(), Optional.empty(), "Europe/Paris", dataSource);
  }

  /** The data source every store of this harness uses. */
  public javax.sql.DataSource dataSource() {
    return dataSource;
  }

  /** A unit of work whose row lock and renderer the test chooses, for the D-RTR-7 lock paths. */
  public IssuanceUnitOfWork unitOfWorkWithLock(
      com.housedevinci.einvoice.application.IssuanceRowLock lock,
      com.housedevinci.einvoice.application.DocumentRenderer substituteRenderer) {
    return new IssuanceUnitOfWork(
        inbound,
        runLock,
        permits,
        source,
        store,
        store,
        store,
        lock,
        substituteRenderer,
        validator,
        new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
        archive,
        preflightSupport,
        clock,
        configurationFor("DEFAULT", pinnedApiVersion));
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

  /**
   * A unit of work in another mode over the same database and seller: a second instance of the
   * other mode, the case D-SRE-6 is about. The configured series exists only in live mode, so a run
   * of this instance that reaches the allocator is refused there.
   */
  public IssuanceUnitOfWork unitOfWorkInMode(Mode mode) {
    IssuanceUnitOfWork.Configuration live = configurationFor("DEFAULT", pinnedApiVersion);
    return new IssuanceUnitOfWork(
        inbound,
        runLock,
        permits,
        source,
        store,
        store,
        store,
        store,
        renderer,
        validator,
        new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
        archive,
        preflightSupport,
        clock,
        new IssuanceUnitOfWork.Configuration(
            live.sellerId(),
            live.stripeAccountId(),
            mode,
            live.series(),
            live.fiscalYearReset(),
            live.taxZone(),
            live.pinnedApiVersion(),
            live.rulePackVersion(),
            live.retryBackoff(),
            live.maxRetryBackoff(),
            live.closedYearCutoff(),
            live.readBackAfterWrite()));
  }

  public IssuanceUnitOfWork unitOfWorkPinnedTo(String apiVersion) {
    return new IssuanceUnitOfWork(
        inbound,
        runLock,
        permits,
        source,
        store,
        store,
        store,
        store,
        renderer,
        validator,
        new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
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
        runLock,
        permits,
        source,
        allocator,
        store,
        store,
        store,
        renderer,
        validator,
        new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
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
        runLock,
        permits,
        source,
        store,
        store,
        store,
        store,
        renderer,
        validator,
        new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
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
        runLock,
        permits,
        source,
        store,
        store,
        store,
        store,
        substituteRenderer,
        validator,
        new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
        archive,
        preflightSupport,
        clock,
        configurationFor("DEFAULT", pinnedApiVersion));
  }

  /**
   * A unit of work with a substitute Stripe source and renderer, for a probe that holds one worker
   * inside the fetch or the render while another runs the same event (D-PR9-F1).
   */
  /**
   * A unit of work whose inbound store, run lock and permits the test chooses, for the one run per
   * event paths (D-PR45-1): a spy store, a colliding key, a small permit bound.
   */
  public IssuanceUnitOfWork unitOfWorkWithRun(
      com.housedevinci.einvoice.application.InboundEventStore substituteInbound,
      com.housedevinci.einvoice.application.InboundRunLock substituteLock,
      com.housedevinci.einvoice.application.RunPermits substitutePermits,
      com.housedevinci.einvoice.application.DocumentRenderer substituteRenderer) {
    return new IssuanceUnitOfWork(
        substituteInbound,
        substituteLock,
        substitutePermits,
        source,
        store,
        store,
        store,
        store,
        substituteRenderer,
        validator,
        new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
        archive,
        preflightSupport,
        clock,
        configurationFor("DEFAULT", pinnedApiVersion));
  }

  public IssuanceUnitOfWork unitOfWorkWithSourceAndRenderer(
      com.housedevinci.einvoice.application.StripeInvoiceSource substituteSource,
      com.housedevinci.einvoice.application.DocumentRenderer substituteRenderer) {
    return new IssuanceUnitOfWork(
        inbound,
        runLock,
        permits,
        substituteSource,
        store,
        store,
        store,
        store,
        substituteRenderer,
        validator,
        new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
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
        runLock,
        permits,
        source,
        store,
        store,
        store,
        store,
        renderer,
        validator,
        new com.housedevinci.einvoice.adapter.validation.UblDocumentDates(),
        substituteArchive,
        preflightSupport,
        clock,
        configurationFor("DEFAULT", pinnedApiVersion));
  }

  /**
   * A unit of work with every document port chosen by the test, for the date-check paths
   * (D-PR36-6): a host renderer, a host validator, a host date reader and a host archive.
   */
  public IssuanceUnitOfWork unitOfWorkWith(
      com.housedevinci.einvoice.application.DocumentRenderer substituteRenderer,
      DocumentValidator substituteValidator,
      com.housedevinci.einvoice.application.DocumentDates substituteDates,
      com.housedevinci.einvoice.application.ArchiveStore substituteArchive) {
    return new IssuanceUnitOfWork(
        inbound,
        runLock,
        permits,
        source,
        store,
        store,
        store,
        store,
        substituteRenderer,
        substituteValidator,
        substituteDates,
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

  private final JdbcFindingStore findings;

  private final JdbcReprocessLedger reprocessLedger;

  /** The durable record of every privileged re-open, over this harness's own database. */
  public JdbcReprocessLedger reprocessLedger() {
    return reprocessLedger;
  }

  /** Rows in the reprocess record for one event, oldest first. */
  public java.util.List<com.housedevinci.einvoice.application.ReprocessLedger.ReprocessRecord>
      reprocessRecords(String eventId) {
    return reprocessLedger.forEvent(eventId);
  }

  private final com.housedevinci.einvoice.application.PreflightSupport preflightSupport;

  /** The privileged reprocess, over this harness's own stores and the default renderer. */
  public com.housedevinci.einvoice.application.IssuanceReprocess reprocess() {
    return reprocessFor(unitOfWork());
  }

  /** The privileged reprocess over a unit of work whose renderer the test controls. */
  public com.housedevinci.einvoice.application.IssuanceReprocess reprocessWith(
      com.housedevinci.einvoice.application.DocumentRenderer substituteRenderer) {
    return reprocessFor(unitOfWorkWithRenderer(substituteRenderer));
  }

  public com.housedevinci.einvoice.application.IssuanceReprocess reprocessFor(
      IssuanceUnitOfWork unitOfWork) {
    return new com.housedevinci.einvoice.application.IssuanceReprocess(
        unitOfWork, inbound, store, reprocessLedger, clock);
  }

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
        source,
        store,
        inbound,
        archive,
        findings,
        reprocessLedger,
        clock,
        settings,
        unitOfWork().configuration());
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

  /** The series counter itself: the number the next allocation would take. */
  public long seriesCounter() {
    return PostgresSupport.scalar(
        "SELECT next_number FROM einvoice_series WHERE seller_id = '" + sellerId + "'");
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
    return inbound.due(clock.instant(), Duration.ofHours(72), DUE_PAGE, pin);
  }

  /**
   * The page a test asks the due query for. The test database is shared by the whole suite, and the
   * due query is oldest first over every seller, so a page of 100 made "is this event due?" depend
   * on how many other tests left due rows before it (D-PR50-CI-1: the edge table leaves about a
   * hundred). The production page is {@code einvoice.issuance.queue-capacity}; its bound is tested
   * on the store, not through this helper.
   */
  static final int DUE_PAGE = 100_000;

  public SeriesKey seriesKey() {
    return new SeriesKey(sellerId, "DEFAULT", 2026, Mode.LIVE);
  }

  public LegalNumber legalNumber(String value) {
    return new LegalNumber(value, 1);
  }

  /** The bytes this fixture renders to, computed the way the unit of work computes them. */
  public byte[] renderFor(String invoiceId, String number) {
    return renderer.render(renderInput(invoiceId, number)).bytes();
  }

  /**
   * The render input the unit of work builds: from the stored issuance when the number exists, or,
   * before it does, with the dates the allocator will give it now - the numbering day on the
   * harness clock and the finalisation day as the supply date (D-PS1-27).
   */
  private DocumentInput renderInput(String invoiceId, String number) {
    var invoice = source.fetchInvoice(invoiceId);
    LocalDate supplyDay = LocalDate.ofInstant(invoice.finalizedAt(), taxZone);
    var mapping =
        new com.housedevinci.einvoice.application.MappingInput(
            invoice, seriesKey(), supplyDay, "fr-2026.1");
    Optional<Issuance> stored = store.findBySource(sellerId, Mode.LIVE, invoiceId);
    if (stored.isPresent()) {
      return DocumentInput.numbered(mapping, stored.get(), taxZone);
    }
    return new DocumentInput(
        mapping,
        legalNumber(number),
        seriesKey(),
        new com.housedevinci.einvoice.domain.IssueDating(
            LocalDate.ofInstant(clock.instant(), taxZone),
            clock.instant(),
            Optional.of(supplyDay),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
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
          TestStores.store(
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

  /** The harness clock's current instant, for a test that writes a record itself. */
  public Instant now() {
    return clock.instant();
  }

  /** Moves the database clock away from the application clock, as a wrong NTP step would. */
  public void shiftDatabaseClock(Duration by) {
    issueClock.shift(by);
  }

  /** The harness's tax zone, in which every date of its documents is derived. */
  public ZoneId taxZone() {
    return taxZone;
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
