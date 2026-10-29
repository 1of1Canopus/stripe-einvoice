package com.housedevinci.einvoice.application;

import com.housedevinci.einvoice.domain.ArchiveKey;
import com.housedevinci.einvoice.domain.ComplianceFinding;
import com.housedevinci.einvoice.domain.EInvoiceException;
import com.housedevinci.einvoice.domain.ErrorCodes;
import com.housedevinci.einvoice.domain.EventIdentity;
import com.housedevinci.einvoice.domain.Hashes;
import com.housedevinci.einvoice.domain.InboundEvent;
import com.housedevinci.einvoice.domain.Issuance;
import com.housedevinci.einvoice.domain.IssuanceState;
import com.housedevinci.einvoice.domain.Mode;
import com.housedevinci.einvoice.domain.SeriesKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sweep that closes both orphan directions, and the only thing in this module that can notice a
 * sale with no document at all.
 *
 * <p><b>This is free-core, and not negotiable</b> (I-03). A control that closes a finding does not
 * become a paid feature: without the sweep, D-09 - <i>a finalised sale with no legal invoice, and
 * nothing that notices</i> - has no mitigation whatsoever, because the event that would have told
 * us is precisely the one that never arrived. The <em>dashboard</em> is Pro; the sweep, the archive
 * checks, the stuck check and the signal are here.
 *
 * <p>Five checks:
 *
 * <ol>
 *   <li><b>Stripe to us.</b> Invoices finalised in the window with no {@code ISSUED} row are a
 *       finding, and are re-enqueued as a synthetic inbound record through the same idempotent path
 *       - never issued inline, so there is exactly one code path that can produce a document.
 *   <li><b>Us to the archive.</b> Every {@code ISSUED} row must have its object. A bounded sample
 *       of the newest rows is also re-hashed, which is what detects a store that quietly overwrote;
 *       the exhaustive re-hash on a slower cadence is Pro.
 *   <li><b>The archive to us.</b> Objects that no row predicted, found by a walk of the seller
 *       folder that resumes where the last pass stopped (D-PR52-3): one listing of at most {@code
 *       orphan-pages x page-size} keys after the saved position, each page of keys looked up
 *       exactly against every row of the seller and mode, in any year and any state. Every key is
 *       examined within one cycle; {@code DEI-283} says when a cycle takes longer than {@code
 *       orphan-cycle-alert}.
 *   <li><b>Stuck.</b> Numbers allocated and still open past {@code alert-after}, in any series
 *       year, read before the Stripe call so a refused key or an outage cannot skip them.
 *   <li><b>Disposed numbers with an object</b> (D-RTR-4). A burned or voided row that recorded a
 *       key, in any year, is read at that key: an object there is {@code DEI-281}, and {@code
 *       DEI-275} too when its bytes do not hash to the row's hash. A lookup that fails is {@code
 *       DEI-241}, counted as unverified, never as clean. Example: the PUT for {@code F-2026-0042}
 *       timed out, the resumed run found nothing and burned the number, and the PUT completed on
 *       the store afterwards.
 * </ol>
 *
 * <p>The us-to-archive check reads the newest {@code page-size} rows of each series the window
 * touches. The disposed-number check reads its own page: the newest {@code page-size} burned or
 * voided rows with a key, in every year (D-PR52-1). The orphan walk reads no rows in advance: it
 * asks the database about the keys it listed.
 *
 * <p>It <b>alerts and never repairs</b>: no auto-delete, no auto-void, no overwrite. Every one of
 * these conditions has a human decision behind it that a library must not take.
 */
public final class ReconciliationSweep {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationSweep.class);

  /** Where a re-enqueued event says it came from, instead of a webhook signature key id. */
  public static final String RECONCILIATION_SOURCE = "reconciliation";

  private final StripeInvoiceSource source;
  private final IssuanceReader reader;
  private final InboundEventStore inbound;
  private final ArchiveStore archive;
  private final FindingStore findings;
  private final java.time.Clock clock;
  private final Settings settings;
  private final ReprocessLedger reprocessLedger;
  private final OrphanCursorStore cursors;
  private final IssuanceUnitOfWork.Configuration configuration;

  /**
   * @param window how far back to ask Stripe; {@code grace} keeps the sweep from reporting an
   *     invoice that is simply still in flight
   * @param driftSample how many of the newest documents to re-hash per sweep. The exhaustive rescan
   *     is Pro; a bounded sample in the free core is what makes a silently overwriting store
   *     detectable at all rather than theoretical
   * @param orphanPages how many pages of {@code pageSize} keys the orphan walk examines per pass, 1
   *     to {@value #MAX_ORPHAN_PAGES}
   * @param orphanCycleAlert how long a walk of the whole seller folder may take before {@code
   *     DEI-283}
   */
  public record Settings(
      Duration window,
      Duration grace,
      Duration alertAfter,
      int driftSample,
      int pageSize,
      int orphanPages,
      Duration orphanCycleAlert) {

    /** The largest {@code orphan-pages}: 100 pages of 10,000 keys is a million keys per pass. */
    public static final int MAX_ORPHAN_PAGES = 100;

    public Settings {
      window = window == null ? Duration.ofDays(2) : window;
      grace = grace == null ? Duration.ofMinutes(15) : grace;
      alertAfter = alertAfter == null ? Duration.ofHours(6) : alertAfter;
      driftSample = driftSample <= 0 ? 10 : driftSample;
      pageSize = pageSize <= 0 ? 500 : pageSize;
      orphanPages = orphanPages <= 0 ? 4 : orphanPages;
      orphanCycleAlert = orphanCycleAlert == null ? Duration.ofDays(7) : orphanCycleAlert;
      if (orphanPages > MAX_ORPHAN_PAGES) {
        throw new EInvoiceException(
            ErrorCodes.CONFIG, "einvoice.reconcile.orphan-pages is at most " + MAX_ORPHAN_PAGES);
      }
      if (orphanCycleAlert.isNegative() || orphanCycleAlert.isZero()) {
        throw new EInvoiceException(
            ErrorCodes.CONFIG, "einvoice.reconcile.orphan-cycle-alert must be positive");
      }
    }

    /** The settings of 0.1.x, with the orphan walk's defaults: 4 pages, a 7-day cycle alert. */
    public Settings(
        Duration window, Duration grace, Duration alertAfter, int driftSample, int pageSize) {
      this(window, grace, alertAfter, driftSample, pageSize, 4, Duration.ofDays(7));
    }
  }

  /**
   * What one sweep found. Counts and a completion time: no ids, no buyer field.
   *
   * <p>{@code disposedWithObject} counts burned or voided numbers with an object at their key
   * ({@code DEI-281}); {@code disposedUnverified} counts those whose lookup failed ({@code
   * DEI-241}). Both are in {@link #total()}. {@code orphanCycleStale} is 1 when this pass raised
   * {@code DEI-283}, and is in {@link #total()}. {@code orphanKeysExamined} counts the keys the
   * orphan walk examined in this pass: a count of work, not of findings, so not in {@link
   * #total()}. Built only by the sweep.
   */
  public record Result(
      Instant completedAt,
      int stripeInvoicesChecked,
      int missingIssuance,
      int archiveMissing,
      int archiveDrift,
      int orphanObjects,
      int stuckIssuances,
      int unfinishedReprocesses,
      int voidedNoDocument,
      int burnedNoDocument,
      int accessRefused,
      int disposedWithObject,
      int disposedUnverified,
      int orphanCycleStale,
      int orphanKeysExamined) {

    public int total() {
      return missingIssuance
          + archiveMissing
          + archiveDrift
          + orphanObjects
          + stuckIssuances
          + unfinishedReprocesses
          + voidedNoDocument
          + burnedNoDocument
          + accessRefused
          + disposedWithObject
          + disposedUnverified
          + orphanCycleStale;
    }
  }

  public ReconciliationSweep(
      StripeInvoiceSource source,
      IssuanceReader reader,
      InboundEventStore inbound,
      ArchiveStore archive,
      FindingStore findings,
      ReprocessLedger reprocessLedger,
      OrphanCursorStore cursors,
      java.time.Clock clock,
      Settings settings,
      IssuanceUnitOfWork.Configuration configuration) {
    this.cursors = java.util.Objects.requireNonNull(cursors, "cursors");
    this.source = source;
    this.reader = reader;
    this.inbound = inbound;
    this.archive = archive;
    this.findings = findings;
    this.reprocessLedger = reprocessLedger;
    this.clock = clock;
    this.settings = settings;
    this.configuration = configuration;
  }

  /**
   * One pass. Every check runs in every pass, each on its own (D-PR51-1): a check whose dependency
   * fails (Stripe refuses the key, the archive is down, a read times out) is logged and the next
   * check still runs. Example: the Stripe key is revoked on Friday and an archived invoice is
   * deleted on Saturday; Saturday's pass still names the missing object. The pass then throws the
   * first failure, so the sweeper still logs it and the stale signal ({@code DEI-274}) still fires:
   * a pass with a failed check is never a completed pass.
   */
  public Result sweep() {
    Instant now = clock.instant();
    Instant to = now.minus(settings.grace());
    Instant from = to.minus(settings.window());
    Counts counts = new Counts();
    Failures failures = new Failures();

    // D-SRE-5a: an event stopped on a refused Stripe key (DEI-219, no next attempt) is named once
    // per event id; record() dedupes, so a row stopped for a week is one finding, not one per pass.
    failures.run("key refused", () -> keyRefused(counts, now));
    // D-PR50-B1: open numbers by seller and mode, every series year, oldest first.
    failures.run("stuck", () -> stuck(counts, now));
    failures.run("stripe to us", () -> stripeToUs(counts, from, to, now));
    // The rows are read once, before any archive call, so an archive failure in the next check
    // cannot leave the orphan check with half a set of known keys.
    Optional<List<Issuance>> rows = failures.call("series read", () -> seriesRows(from, to, now));
    // D-PR52-1: disposed rows are read by when they can matter, not by when they were numbered:
    // every year, newest first, a page of disposed rows with a key. Read here, before any archive
    // call, like the series rows.
    Optional<List<Issuance>> disposed = failures.call("disposed read", this::disposedRows);
    if (rows.isPresent()) {
      failures.run("us to archive", () -> usToArchive(counts, rows.get(), now));
    }
    if (disposed.isPresent()) {
      failures.run("disposed numbers", () -> disposedNumbers(counts, disposed.get(), now));
    }
    if (rows.isEmpty()) {
      log.warn("einvoice: reconciliation check 'us to archive' not run: the series read failed");
    }
    // D-PR52-3, C10: the walk asks the database about the keys it lists, so it needs no row read
    // in advance, and a failed series read does not blind it.
    failures.run("archive to us", () -> archiveToUs(counts, now));
    // Its own check (C9): a listing that fails on every pass still raises DEI-283 after the alert,
    // next to its DEI-241.
    failures.run("orphan cycle", () -> orphanCycle(counts, now));
    failures.run("unfinished reprocess", () -> unfinishedReprocess(counts, now));

    Result result =
        new Result(
            now,
            counts.checked,
            counts.missing,
            counts.archiveMissing,
            counts.drift,
            counts.orphans,
            counts.stuck,
            counts.unfinished,
            counts.voided,
            counts.burned,
            counts.keyRefused,
            counts.disposedWithObject,
            counts.disposedUnverified,
            counts.orphanCycleStale,
            counts.orphanKeysExamined);
    log.info(
        "einvoice: reconciliation checked {} finalised invoices and raised {} findings"
            + " (missing {}, archive missing {}, drift {}, orphan {}, stuck {},"
            + " unfinished reprocess {}, voided {}, burned {}, key refused {},"
            + " disposed with object {}, disposed unverified {}, orphan cycle stale {});"
            + " orphan walk examined {} keys, {}; failed checks {}",
        counts.checked,
        result.total(),
        counts.missing,
        counts.archiveMissing,
        counts.drift,
        counts.orphans,
        counts.stuck,
        counts.unfinished,
        counts.voided,
        counts.burned,
        counts.keyRefused,
        counts.disposedWithObject,
        counts.disposedUnverified,
        counts.orphanCycleStale,
        counts.orphanKeysExamined,
        counts.cycleProgress,
        failures.failed);
    failures.throwFirst();
    return result;
  }

  private void keyRefused(Counts counts, Instant now) {
    for (String eventId :
        inbound.accessRefused(
            configuration.sellerId(), configuration.mode(), settings.pageSize())) {
      counts.keyRefused++;
      record(ErrorCodes.STRIPE_ACCESS_REFUSED, eventId, now);
    }
  }

  private void stuck(Counts counts, Instant now) {
    // Scoped to the window's series years and run after the Stripe list call, this check never
    // named a number left open across a year end or through a refused key or an outage. Example: a
    // number allocated on 30 December stays open; the key is refused until 3 January; this pass
    // still names it on 30 December evening.
    List<Issuance> openNumbers =
        reader.openOlderThan(
            configuration.sellerId(),
            configuration.mode(),
            now.minus(settings.alertAfter()),
            settings.pageSize());
    for (Issuance open : openNumbers) {
      counts.stuck++;
      record(ErrorCodes.RECON_STUCK_ISSUANCE, open.stripeInvoiceId(), now);
    }
    if (openNumbers.size() >= settings.pageSize()) {
      // Oldest first, so the numbers past the page are the newer ones; the count says how bad.
      log.warn(
          "einvoice: the stuck check named the {} oldest open numbers, its page limit;"
              + " more may be open",
          openNumbers.size());
    }
  }

  private void stripeToUs(Counts counts, Instant from, Instant to, Instant now) {
    for (String invoiceId : source.finalisedInvoiceIds(from, to)) {
      counts.checked++;
      Optional<Issuance> issuance =
          reader.findBySource(configuration.sellerId(), configuration.mode(), invoiceId);
      if (issuance.isEmpty() || issuance.get().state() != IssuanceState.ISSUED) {
        // RC-01: "no ISSUED row" is three conditions, not one. A number that was voided, or burned
        // by a validation refusal, already has its reason recorded and chained; re-enqueueing it
        // hands the pipeline an event it can only refuse again, every sweep, for ever. Those are
        // reported under their own code and never enqueued. Only a sale with no recorded reason is
        // re-enqueued, through the same idempotent path as before.
        switch (issuance.map(i -> i.state()).orElse(null)) {
          case IssuanceState.VOID_UNUSED -> {
            counts.voided++;
            record(ErrorCodes.RECON_VOIDED_NO_DOCUMENT, invoiceId, now);
          }
          case IssuanceState.FAILED_VALIDATION -> {
            counts.burned++;
            record(ErrorCodes.RECON_BURNED_NO_DOCUMENT, invoiceId, now);
          }
          case null, default -> {
            counts.missing++;
            record(ErrorCodes.RECON_MISSING_ISSUANCE, invoiceId, now);
            enqueue(invoiceId, now);
          }
        }
      }
    }
  }

  private List<Issuance> seriesRows(Instant from, Instant to, Instant now) {
    List<Issuance> rows = new ArrayList<>();
    for (SeriesKey key : seriesKeys(from, to, now)) {
      rows.addAll(reader.inSeries(key, settings.pageSize()));
    }
    return rows;
  }

  private void usToArchive(Counts counts, List<Issuance> rows, Instant now) {
    int checkedForDrift = 0;
    for (Issuance issuance : rows) {
      if (issuance.state() == IssuanceState.ISSUED) {
        Optional<String> problem =
            checkArchived(issuance, checkedForDrift++ < settings.driftSample());
        if (problem.isPresent()) {
          if (ErrorCodes.RECON_ARCHIVE_MISSING.equals(problem.get())) {
            counts.archiveMissing++;
          } else {
            counts.drift++;
          }
          record(problem.get(), issuance.stripeInvoiceId(), now);
        }
      }
    }
  }

  private List<Issuance> disposedRows() {
    List<Issuance> disposed =
        reader.disposedWithKey(configuration.sellerId(), configuration.mode(), settings.pageSize());
    if (disposed.size() >= settings.pageSize()) {
      // Newest first, so the rows past the page are the older numbers; the count says how many.
      log.warn(
          "einvoice: the disposed-number check read {} disposed numbers, its page limit;"
              + " older ones were not looked up",
          disposed.size());
    }
    return disposed;
  }

  /**
   * The disposed-number check (D-RTR-4): every burned or voided row with a key, in any year, is
   * read at that key (D-PR52-1). Its rows are read before any archive call. An object there is
   * {@code DEI-281}, and {@code DEI-275} too when its bytes do not hash to the row's hash. A read
   * that fails is {@code DEI-241}, counted as unverified and never as clean, and the next row is
   * still read.
   */
  private void disposedNumbers(Counts counts, List<Issuance> rows, Instant now) {
    for (Issuance issuance : rows) {
      if (!disposedWithoutDocument(issuance.state()) || issuance.archiveObjectKey().isEmpty()) {
        continue;
      }
      switch (checkDisposed(issuance)) {
        case ABSENT -> {
          // The common case: the number has no document, and the archive agrees.
        }
        case SAME_BYTES -> {
          counts.disposedWithObject++;
          record(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER, issuance.stripeInvoiceId(), now);
        }
        case OTHER_BYTES -> {
          counts.disposedWithObject++;
          counts.drift++;
          record(ErrorCodes.RECON_OBJECT_FOR_DISPOSED_NUMBER, issuance.stripeInvoiceId(), now);
          record(ErrorCodes.RECON_ARCHIVE_DRIFT, issuance.stripeInvoiceId(), now);
        }
        case UNVERIFIED -> {
          counts.disposedUnverified++;
          record(ErrorCodes.ARCHIVE_UNAVAILABLE, issuance.stripeInvoiceId(), now);
        }
      }
    }
  }

  private enum DisposedCheck {
    ABSENT,
    SAME_BYTES,
    OTHER_BYTES,
    UNVERIFIED
  }

  /** Burned or voided: the number will never carry a document. */
  private static boolean disposedWithoutDocument(IssuanceState state) {
    return state == IssuanceState.FAILED_VALIDATION || state == IssuanceState.VOID_UNUSED;
  }

  /**
   * One disposed row with a key: one read of that key (D-PR52-2). The S3 store has no fast
   * existence check (the port default reads the bytes), and a read of a missing key costs the same
   * as a HEAD, so one read answers both "is there an object" and "is it our bytes". A failure is
   * {@code UNVERIFIED}, never {@code ABSENT}, because "could not look" is not "nothing there". The
   * log carries the code or the exception class, never a message: a message from a host adapter may
   * carry what this log must not (D-PR51-1).
   */
  private DisposedCheck checkDisposed(Issuance issuance) {
    ArchiveKey key = new ArchiveKey(issuance.archiveKey());
    Optional<byte[]> stored;
    try {
      stored = archive.get(key);
    } catch (RuntimeException e) {
      log.warn(
          "einvoice: reconciliation could not look up the archive key of {} number {} ({})",
          issuance.state(),
          issuance.legalNumber().value(),
          e instanceof EInvoiceException einvoice ? einvoice.code() : e.getClass().getName());
      return DisposedCheck.UNVERIFIED;
    }
    if (stored.isEmpty()) {
      return DisposedCheck.ABSENT;
    }
    boolean same = Hashes.sha256Hex(stored.get()).equals(issuance.documentSha256());
    // Ids and hashes only, never content.
    log.warn(
        "einvoice: an archive object exists at the key of burned or voided number {} ({}): {},"
            + " {}. Alerted, never deleted",
        issuance.legalNumber().value(),
        issuance.state(),
        key.value(),
        same ? "hash matches the row" : "hash differs");
    return same ? DisposedCheck.SAME_BYTES : DisposedCheck.OTHER_BYTES;
  }

  /**
   * The orphan walk (D-PR52-3): one listing after the saved position, an exact lookup per page,
   * findings before the cursor moves.
   *
   * <ol>
   *   <li>Read the cursor of this seller and mode, or insert the first one. A cursor saved on
   *       another store (root, bucket or prefix) starts a new cycle; its partial cycle never
   *       counts.
   *   <li>One {@code listAfter} call for {@code orphan-pages x page-size} keys (C11).
   *   <li>The order guard (C5): strictly ascending UTF-8 bytes, every key strictly after the
   *       position and inside the seller folder. Anything else is {@code DEI-284}, and nothing
   *       moves.
   *   <li>Per page of {@code page-size} keys: a name that is not a valid archive key is an orphan
   *       with no lookup, under a bounded subject (C6); the rest go to one lookup. The findings are
   *       written, then the cursor moves to the page's last key with a compare-and-set (C7).
   *   <li>On the store's end-of-folder answer the cycle completes, unless it examined no key while
   *       an issued row recorded one: an empty or unmounted archive is {@code DEI-241}, never a
   *       complete cycle (C8).
   * </ol>
   *
   * <p>Inside a page the order stays "list, then look up": a row is committed before its object is
   * written, so a listed key always has its row (L12).
   */
  private void archiveToUs(Counts counts, Instant now) {
    String sellerId = configuration.sellerId();
    Mode mode = configuration.mode();
    String folder = ArchiveKey.sellerFolder(mode, sellerId);
    String identity = Hashes.sha256Hex(archive.describe());
    Optional<OrphanCursor> stored = cursors.read(sellerId, mode);
    OrphanCursor cursor;
    if (stored.isEmpty()) {
      cursor = OrphanCursor.start(sellerId, mode, identity, now);
      if (!cursors.insert(cursor)) {
        lostToAnotherInstance(counts);
        return;
      }
    } else if (!stored.get().storeIdentity().equals(identity)) {
      cursor = stored.get().restartedOn(identity, now);
      if (!cursors.replace(stored.get(), cursor)) {
        lostToAnotherInstance(counts);
        return;
      }
      log.warn(
          "einvoice: the archive store is not the one the orphan walk was on ({}); the walk"
              + " starts a new cycle from the start of the seller folder",
          archive.describe());
    } else {
      cursor = stored.get();
    }

    int pageSize = settings.pageSize();
    int limit = settings.orphanPages() * pageSize;
    ArchiveListing listing = archive.listAfter(folder, cursor.position(), limit);
    requireOrdered(listing, folder, cursor.position(), limit);
    List<String> keys = listing.keys();
    for (int from = 0; from < keys.size(); from += pageSize) {
      List<String> page = keys.subList(from, Math.min(keys.size(), from + pageSize));
      List<String> valid = page.stream().filter(ArchiveKey::isValid).toList();
      Set<String> known = valid.isEmpty() ? Set.of() : reader.knownKeys(sellerId, mode, valid);
      for (String key : page) {
        if (!known.contains(key)) {
          counts.orphans++;
          // Alerted, never deleted: an object we cannot explain is evidence of something, and a
          // library that tidies it away has destroyed the only copy of that something.
          record(ErrorCodes.RECON_ORPHAN_OBJECT, OrphanSubject.of(key), now);
        }
      }
      OrphanCursor next = cursor.advancedTo(page.get(page.size() - 1), page.size());
      if (!cursors.replace(cursor, next)) {
        lostToAnotherInstance(counts);
        return;
      }
      cursor = next;
      counts.orphanKeysExamined += page.size();
    }
    if (!listing.endOfFolder()) {
      counts.cycleProgress =
          cursor.percentDone().isPresent()
              ? "cycle " + cursor.percentDone().getAsInt() + "% done"
              : "first cycle";
      return;
    }
    if (cursor.keysThisCycle() == 0 && cursors.hasArchivedDocuments(sellerId, mode)) {
      throw new EInvoiceException(
          ErrorCodes.ARCHIVE_UNAVAILABLE,
          "the archive lists nothing for a seller with archived documents: an empty or"
              + " unmounted archive is never a complete orphan cycle");
    }
    OrphanCursor completed = cursor.completed(now);
    if (!cursors.replace(cursor, completed)) {
      lostToAnotherInstance(counts);
      return;
    }
    counts.cycleProgress = "cycle complete";
  }

  /**
   * C5: the walk moves only on a listing in strict UTF-8 byte order, strictly after the saved
   * position, inside the seller folder and no longer than asked. A name a writer chose can never
   * reach this guard: the shipped stores turn every name into a unique key (D1), so this guard
   * checks the store's own order and nothing else.
   */
  private static void requireOrdered(
      ArchiveListing listing, String folder, String after, int limit) {
    if (listing.keys().size() > limit) {
      throw unordered("returned more keys than asked");
    }
    byte[] previous = ArchiveListing.utf8(after);
    for (String key : listing.keys()) {
      if (key == null || !key.startsWith(folder)) {
        throw unordered("returned a key outside the seller folder");
      }
      byte[] current = ArchiveListing.utf8(key);
      if (current.length > OrphanCursor.MAX_POSITION_BYTES) {
        throw unordered(
            "returned a key longer than " + OrphanCursor.MAX_POSITION_BYTES + " UTF-8 bytes");
      }
      if (java.util.Arrays.compareUnsigned(current, previous) <= 0) {
        throw unordered("returned keys that are not strictly ascending after the saved position");
      }
      previous = current;
    }
  }

  private static EInvoiceException unordered(String what) {
    return new EInvoiceException(
        ErrorCodes.ARCHIVE_LISTING_UNORDERED,
        "the archive listing "
            + what
            + " in UTF-8 byte order. The orphan walk moved nothing and completed no cycle.");
  }

  private static void lostToAnotherInstance(Counts counts) {
    // Not a failure: another instance moved the walk first, and its pass records the findings.
    counts.cycleProgress = "another instance moved the walk first";
    log.info("einvoice: another instance moved the orphan walk first; this pass stops it here");
  }

  /**
   * C9: {@code DEI-283} when the current cycle started more than {@code orphan-cycle-alert} ago. A
   * completion starts the next cycle, so the alert holds for every cycle, not only the first
   * (D-PR57-1). Its own check, so a walk that fails on every pass still raises it.
   */
  private void orphanCycle(Counts counts, Instant now) {
    Optional<OrphanCursor> cursor = cursors.read(configuration.sellerId(), configuration.mode());
    if (cursor.isPresent() && cursor.get().stale(now, settings.orphanCycleAlert())) {
      counts.orphanCycleStale = 1;
      record(
          ErrorCodes.RECON_ORPHAN_CYCLE_STALE,
          ArchiveKey.sellerFolder(configuration.mode(), configuration.sellerId()),
          now);
      log.warn(
          "einvoice: the orphan walk has not completed a cycle in {} (DEI-283): raise"
              + " einvoice.reconcile.orphan-pages, or find why the archive listing fails",
          settings.orphanCycleAlert());
    }
  }

  private void unfinishedReprocess(Counts counts, Instant now) {
    // R-03: a REQUESTED row with no CONCLUDED means one thing - a process that died between the
    // privileged re-open and the end of its run - because a run that throws still concludes with
    // the code that escaped. That is an operator's problem: the event may be running again with
    // nobody watching the outcome, so it is a finding on the event id rather than a log line.
    for (ReprocessLedger.ReprocessRecord open :
        reprocessLedger.unfinished(
            configuration.sellerId(),
            configuration.mode(),
            now.minus(settings.alertAfter()),
            settings.pageSize())) {
      counts.unfinished++;
      record(ErrorCodes.RECON_REPROCESS_UNFINISHED, open.eventId(), now);
    }
  }

  /** What one pass has counted so far; a failed check keeps what it counted before it failed. */
  private static final class Counts {
    int checked;
    int missing;
    int voided;
    int burned;
    int archiveMissing;
    int drift;
    int stuck;
    int orphans;
    int unfinished;
    int keyRefused;
    int disposedWithObject;
    int disposedUnverified;
    int orphanCycleStale;
    int orphanKeysExamined;
    String cycleProgress = "walk not run";
  }

  /**
   * The failures of one pass. A failed check is logged by name and code and kept; the first one is
   * thrown at the end of the pass with the others attached as suppressed, so nothing is swallowed.
   */
  private static final class Failures {
    private final List<String> failed = new ArrayList<>();
    private RuntimeException first;

    void run(String check, Runnable body) {
      call(
          check,
          () -> {
            body.run();
            return Boolean.TRUE;
          });
    }

    <T> Optional<T> call(String check, java.util.function.Supplier<T> body) {
      try {
        return Optional.of(body.get());
      } catch (RuntimeException e) {
        failed.add(check);
        // The code only: a message from a host adapter may carry what this log must not.
        log.warn(
            "einvoice: reconciliation check '{}' failed ({}); the other checks still run",
            check,
            e instanceof EInvoiceException einvoice ? einvoice.code() : e.getClass().getName());
        // D-PR51-3: an adapter behind a circuit breaker may throw one exception object from
        // every call. Attaching it to itself throws "Self-suppression not permitted", which would
        // escape the pass with no code and skip every later check; attaching it twice adds noise.
        if (first == null) {
          first = e;
        } else if (e != first && !java.util.Arrays.asList(first.getSuppressed()).contains(e)) {
          first.addSuppressed(e);
        }
        return Optional.empty();
      }
    }

    void throwFirst() {
      if (first != null) {
        throw first;
      }
    }
  }

  /** Empty when the document is where it should be and still hashes to what the row recorded. */
  private Optional<String> checkArchived(Issuance issuance, boolean rehash) {
    Optional<String> key = issuance.archiveObjectKey();
    if (key.isEmpty()) {
      return Optional.of(ErrorCodes.RECON_ARCHIVE_MISSING);
    }
    ArchiveKey archiveKey = new ArchiveKey(key.get());
    if (!rehash) {
      return archive.exists(archiveKey)
          ? Optional.empty()
          : Optional.of(ErrorCodes.RECON_ARCHIVE_MISSING);
    }
    Optional<byte[]> stored = archive.get(archiveKey);
    if (stored.isEmpty()) {
      return Optional.of(ErrorCodes.RECON_ARCHIVE_MISSING);
    }
    // This is what catches a store that ignored the conditional header and overwrote: the object
    // is there, the row's hash says it is not the one we wrote (I-06).
    return Hashes.sha256Hex(stored.get()).equals(issuance.documentSha256())
        ? Optional.empty()
        : Optional.of(ErrorCodes.RECON_ARCHIVE_DRIFT);
  }

  /**
   * Re-enqueues a missing invoice through the same durable path a webhook would use.
   *
   * <p>The event id is derived from the invoice id, so a sweep every fifteen minutes enqueues one
   * row rather than one per sweep, and the signature key id records that this did not come from a
   * signed request - a reconciliation row is not evidence that Stripe sent us anything.
   */
  private void enqueue(String invoiceId, Instant now) {
    try {
      EventIdentity identity =
          new EventIdentity(
              "recon-" + invoiceId,
              "invoice.finalized",
              configuration.pinnedApiVersion(),
              configuration.mode() == com.housedevinci.einvoice.domain.Mode.LIVE,
              configuration.stripeAccountId(),
              invoiceId);
      InboundEvent event =
          InboundEvent.received(
              identity, configuration.mode(), RECONCILIATION_SOURCE, new byte[0], now);
      inbound.record(event, null);
    } catch (EInvoiceException e) {
      log.warn(
          "einvoice: reconciliation could not enqueue an invoice it found unissued ({})", e.code());
    }
  }

  private void record(String code, String subject, Instant now) {
    findings.record(
        ComplianceFinding.of(configuration.sellerId(), configuration.mode(), code, subject, now));
  }

  /** The fiscal years the window touches; a sweep on 2 January looks at last year too. */
  private Set<SeriesKey> seriesKeys(Instant from, Instant to, Instant now) {
    Set<Integer> years = new TreeSet<>();
    if (!configuration.fiscalYearReset()) {
      years.add(SeriesKey.CONTINUOUS);
    } else {
      years.add(LocalDate.ofInstant(from, configuration.taxZone()).getYear());
      years.add(LocalDate.ofInstant(to, configuration.taxZone()).getYear());
      years.add(LocalDate.ofInstant(now, configuration.taxZone()).getYear());
    }
    List<SeriesKey> keys = new ArrayList<>();
    for (int year : years) {
      keys.add(
          new SeriesKey(
              configuration.sellerId(), configuration.series(), year, configuration.mode()));
    }
    return new java.util.LinkedHashSet<>(keys);
  }
}
