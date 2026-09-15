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
import com.housedevinci.einvoice.domain.SeriesKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
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
 * <p>Four directions:
 *
 * <ol>
 *   <li><b>Stripe to us.</b> Invoices finalised in the window with no {@code ISSUED} row are a
 *       finding, and are re-enqueued as a synthetic inbound record through the same idempotent path
 *       - never issued inline, so there is exactly one code path that can produce a document.
 *   <li><b>Us to the archive.</b> Every {@code ISSUED} row must have its object. A bounded sample
 *       of the newest rows is also re-hashed, which is what detects a store that quietly overwrote;
 *       the exhaustive re-hash on a slower cadence is Pro.
 *   <li><b>The archive to us.</b> Objects that no row predicted.
 *   <li><b>Stuck.</b> Numbers allocated and still open past {@code alert-after}.
 * </ol>
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
  private final IssuanceUnitOfWork.Configuration configuration;

  /**
   * @param window how far back to ask Stripe; {@code grace} keeps the sweep from reporting an
   *     invoice that is simply still in flight
   * @param driftSample how many of the newest documents to re-hash per sweep. The exhaustive rescan
   *     is Pro; a bounded sample in the free core is what makes a silently overwriting store
   *     detectable at all rather than theoretical
   */
  public record Settings(
      Duration window, Duration grace, Duration alertAfter, int driftSample, int pageSize) {

    public Settings {
      window = window == null ? Duration.ofDays(2) : window;
      grace = grace == null ? Duration.ofMinutes(15) : grace;
      alertAfter = alertAfter == null ? Duration.ofHours(6) : alertAfter;
      driftSample = driftSample <= 0 ? 10 : driftSample;
      pageSize = pageSize <= 0 ? 500 : pageSize;
    }
  }

  /** What one sweep found. Counts and a completion time: no ids, no buyer field. */
  public record Result(
      Instant completedAt,
      int stripeInvoicesChecked,
      int missingIssuance,
      int archiveMissing,
      int archiveDrift,
      int orphanObjects,
      int stuckIssuances) {

    public int total() {
      return missingIssuance + archiveMissing + archiveDrift + orphanObjects + stuckIssuances;
    }
  }

  public ReconciliationSweep(
      StripeInvoiceSource source,
      IssuanceReader reader,
      InboundEventStore inbound,
      ArchiveStore archive,
      FindingStore findings,
      java.time.Clock clock,
      Settings settings,
      IssuanceUnitOfWork.Configuration configuration) {
    this.source = source;
    this.reader = reader;
    this.inbound = inbound;
    this.archive = archive;
    this.findings = findings;
    this.clock = clock;
    this.settings = settings;
    this.configuration = configuration;
  }

  public Result sweep() {
    Instant now = clock.instant();
    Instant to = now.minus(settings.grace());
    Instant from = to.minus(settings.window());
    int checked = 0;
    int missing = 0;
    for (String invoiceId : source.finalisedInvoiceIds(from, to)) {
      checked++;
      Optional<Issuance> issuance =
          reader.findBySource(configuration.sellerId(), configuration.mode(), invoiceId);
      if (issuance.isEmpty() || issuance.get().state() != IssuanceState.ISSUED) {
        missing++;
        record(ErrorCodes.RECON_MISSING_ISSUANCE, invoiceId, now);
        enqueue(invoiceId, now);
      }
    }

    int archiveMissing = 0;
    int drift = 0;
    int stuck = 0;
    Set<String> known = new HashSet<>();
    int checkedForDrift = 0;
    for (SeriesKey key : seriesKeys(from, to, now)) {
      for (Issuance issuance : reader.inSeries(key, settings.pageSize())) {
        issuance.archiveObjectKey().ifPresent(known::add);
        if (issuance.state() == IssuanceState.ISSUED) {
          Optional<String> problem =
              checkArchived(issuance, checkedForDrift++ < settings.driftSample());
          if (problem.isPresent()) {
            if (ErrorCodes.RECON_ARCHIVE_MISSING.equals(problem.get())) {
              archiveMissing++;
            } else {
              drift++;
            }
            record(problem.get(), issuance.stripeInvoiceId(), now);
          }
        } else if (issuance.state().open()
            && issuance.allocatedAt().isBefore(now.minus(settings.alertAfter()))) {
          stuck++;
          record(ErrorCodes.RECON_STUCK_ISSUANCE, issuance.stripeInvoiceId(), now);
        }
      }
    }

    int orphans = 0;
    for (String key :
        archive.list(
            configuration.mode().wire() + "/" + configuration.sellerId(), settings.pageSize())) {
      if (!known.contains(key) && !key.startsWith(ArchiveCapabilityProbe.SCRATCH_PREFIX)) {
        orphans++;
        // Alerted, never deleted: an object we cannot explain is evidence of something, and a
        // library that tidies it away has destroyed the only copy of that something.
        record(ErrorCodes.RECON_ORPHAN_OBJECT, key, now);
      }
    }

    Result result = new Result(now, checked, missing, archiveMissing, drift, orphans, stuck);
    log.info(
        "einvoice: reconciliation checked {} finalised invoices and raised {} findings"
            + " (missing {}, archive missing {}, drift {}, orphan {}, stuck {})",
        checked,
        result.total(),
        missing,
        archiveMissing,
        drift,
        orphans,
        stuck);
    return result;
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
