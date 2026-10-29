# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **The orphan walk's properties and codes (D-PR52-3).** `einvoice.reconcile.orphan-pages`
  (default `4`, from 1 to 100) and `einvoice.reconcile.orphan-cycle-alert` (default `P7D`). New codes
  `DEI-283 RECON_ORPHAN_CYCLE_STALE`, `DEI-284 ARCHIVE_LISTING_UNORDERED`, `DEI-285
  ARCHIVE_SELLER_CASE`. New table `einvoice_orphan_cursor` (the runtime role needs `SELECT, INSERT,
  UPDATE`, see `docs/schema-grants.sql`) and partial index `einvoice_issuance_archive_key`.
- **No inbound event is due for ever (QUESTIONS 29).** A row that stays running without progress
  past a limit stops being due. It is moved to the new terminal state `EXPIRED` with `DEI-266`
  (`INBOUND_CEILING_REACHED`), and the finding `DEI-282` (`RECON_STUCK_INBOUND`) is raised once on
  its event id. Example: an `invoice.paid` event for a draft that is never finalised was re-checked
  every backoff until the retention purge deleted it, and nobody was told; now it expires on day 90
  and the findings list names it. Two new properties: `einvoice.issuance.running-ceiling` (default
  `PT72H`, for `RECEIVED`, `FETCHED`, `MAPPED`) and `einvoice.issuance.parked-ceiling` (default
  `P90D`). Zero, negative, a running limit not longer than `max-retry-backoff`, or a parked limit
  below the running one are refused at startup; above 7 and 365 days they WARN at every start.
  The limit measures time since the row last made progress, kept in the new column
  `einvoice_inbound_event.due_since`. A re-check of a draft (`PARKED -> FETCHED -> PARKED`) is not
  progress. The schema upgrade gives existing rows the upgrade instant, so a backlog is never
  expired on the first sweep. `IssuanceReprocess` accepts `EXPIRED` and starts the clock again.
  An `EXPIRED` row whose invoice holds an open number is re-opened in place and resumes that number,
  like a numbered `FAILED_ISSUANCE` row (QUESTIONS 48).
- **A refused Stripe key stops the event (D-PR45-B1, design "sweeper retry edges" section 5).**
  HTTP 401 and 403 from the Stripe API are the new code `DEI-219 STRIPE_ACCESS_REFUSED`; every
  other status stays `DEI-211`. The event stops with no retry. Each reconciliation pass records one
  finding per stopped event (`DEI-219`, subject the event id), before its own Stripe call. The
  first successful Stripe call after a refusal, and every startup (on the sweeper's thread once the
  application is ready), makes the stopped events due again: only rows of this instance's mode and
  Stripe account, and only rows with no next attempt. New port methods
  `InboundEventStore.rearmAccessRefused` and `InboundEventStore.accessRefused`, new
  `IssuanceUnitOfWork.rearmAccessRefused()` and `IssuanceUnitOfWork.stripeSource()`; the
  reconciliation sweep bean reads through the unit of work's source. `ReconciliationSweep.Result`
  gains `accessRefused`.

- **One run per invoice (D-PR45-1).** A run of the issuance pipeline holds a session advisory lock
  keyed on the Stripe invoice for its whole length (new port `InboundRunLock`, adapter
  `JdbcInboundRunLock`, class id `0x45494E52`, session setting `einvoice.run_lock`). A second run
  of the same invoice writes nothing and answers the stored state with the new code
  `DEI-217 INBOUND_RUN_IN_PROGRESS`; a lock lost mid-run is `DEI-104` at WARN. Every inbound write
  of a run proves the lock in its own transaction, and the run proves it again before it takes a
  number and before it announces an archive write. The retention purge skips a row whose run is
  live. `IssuanceReprocess` takes the lock before it re-opens a row and gains the disposition
  `REFUSED_RUN_IN_PROGRESS`. See "One run per invoice" in the README.
- **The time limit never expires a live run (D-PR46-2, one-run-per-event design rows 16 and 17).**
  The expiry tries the invoice's run lock in its own transaction and skips a held row this pass,
  writing and recording nothing. Before, a run that held a number and waited past the running
  limit on the archive had its `MAPPED` row expired under it with a "stuck" finding, then issued
  the invoice and threw `DEI-210` on its `COMPLETED` write. The operator's re-open of an `EXPIRED`
  row goes through the run lock like any reprocess.
- **A run never joins a caller's transaction (D-PR49-1).** `IssuanceReprocess` and
  `IssuanceUnitOfWork.process` called inside a transaction are refused with the new code
  `DEI-218 RUN_IN_HOST_TRANSACTION` before the run lock is tried, and record nothing. A failed
  joined run would have left its run lock on the caller's pooled connection, so the invoice
  answered `DEI-217` with no run live until the pool retired that connection.
- **Run permits and the pool rule (D-ORPE-4).** `RunPermits`, one per application, of
  `einvoice.issuance.concurrency`, shared by the worker (which waits) and `IssuanceReprocess`
  (which refuses at once with `DEI-102`). The starter refuses at startup (`DEI-100`) a Hikari pool
  smaller than `3 x concurrency + 2`, unwrapping a proxied pool first. The rule runs in every
  started context with a unit of work, the starter's or one the host builds (D-PR49-2).
- **Reserved error codes (D-ORPE-8, D-PR49-3).** `ReservedErrorCodes` lists every code a reviewed
  design already gave a meaning, each with its name and the design that reserves it: the Pro slice
  1 code table (`DEI-123` to `DEI-129`, `DEI-214` to `DEI-216`, `DEI-600` to `DEI-630`),
  `DEI-218`, `DEI-244 RENDER_NOT_REPRODUCIBLE`, `DEI-246 ARCHIVE_UNMARKED`, `DEI-232
  ARCHIVE_FOREIGN`, `DEI-266`, `DEI-281` and `DEI-282`. A test fails the build when a code in
  `ErrorCodes` repeats a value, takes a reserved code under another name, or defines a reserved
  name under another code, and when one name is reserved under two codes. The archive marker
  design first reserved `DEI-247` for `ARCHIVE_FOREIGN`; `DEI-247` is `ROW_LOCK_RELEASE_FAILED`,
  so the marker's refusal is `DEI-232`.

- **Invoice dates go up with their numbers (D-PS1-27).** The issue date (BT-2) of an invoice is
  now the day its number is given: the database clock (`pg_catalog.clock_timestamp()`), read after
  the series row lock, turned into a date once, in Java, in the seller's tax zone. The day of the
  sale is written as the delivery date (BT-72, the day of `finalized_at`) or, when every line names
  the same Stripe service period, as the invoicing period (BG-14). Example: an invoice finalised on
  10 March, parked, and numbered on 12 March is dated 12 March and delivered 10 March.
- **What the record shows.** Every invoice row and its chained events carry the allocation instant,
  the issue date, the supply date or period, the previous number of the series and its issue date,
  and a cause for any day between supply and issue (`NONE`, `REPROCESS`, `SWEEP`, `PARK`, `RETRY`,
  `LATE_WEBHOOK`, `UNRECORDED`), derived from stored history only. The series report shows them; a
  line from before 0.2.0 has a computed `dateOutOfOrder()` flag. More than one day between supply
  and issue raises the finding `DEI-137`.
- **Clock refusals, none of them releasable by hand.** `DEI-132` when the database clock is behind
  the series, behind the invoice's own finalisation time, or apart from the application clock by
  more than `einvoice.numbering.max-clock-regression` (new property, default `5m`, at most `1h`,
  refused at startup outside); retried while small, terminal beyond one day, and then eligible for
  `IssuanceReprocess` after the clock is fixed. `DEI-130` when a series' latest issue date is more
  than one day ahead, at startup and before each allocation, with a finding. `DEI-131` when the
  fiscal year changes under the lock; retried in a new transaction.
- **`DEI-280`.** A document renderer that does not declare `carriesSupplyDate()` is refused before
  a number exists; the UBL writer refuses an invoice with neither BT-72 nor BG-14.

- **Number series per document type (schema format 2).** The document type (BT-3) is part of the
  series key: invoices (`380`) and credit notes (`381`) count in separate series, a series name
  serves one type, and a legal number is unique per seller and mode across series. This edition
  still issues invoices only: the allocator refuses any other type with `DEI-123` before it locks
  anything.
- **The corrected-invoice link.** `einvoice_issuance.corrects_issuance_id`, required for a credit
  note and forbidden for an invoice by a `CHECK`, immutable, and bounded by the database: the target
  must be an invoice of the same seller and mode that this module issued, whose stored hash still
  matches its chained issue event (`DEI-128`). The chained event of a credit note records the
  corrected invoice's full identity.
- **The latest issue date of a series** (`einvoice_series.last_issue_date`). It moves only with
  the counter and never backwards. A credit note dated before it is refused (`DEI-127`). An invoice
  cannot be dated before it, since its issue date is the numbering day.
- **Chain forms `ei3` and `ei4h`.** New events hash the document type and the corrected invoice.
  The verifier still reads `ei1` and `ei2h`, reports how many rows it checked in each form, and
  reports BROKEN when a series row's type disagrees with its events.
- **Schema version gate.** A one-row `einvoice_schema` table, read at every boot. Codes `DEI-133`
  (version row unreadable), `DEI-134` (upgrade refused, data unchanged), `DEI-135` (schema newer
  than this version), `DEI-136` (schema not upgraded and `initialize-schema=false`).

### Changed

- **BREAKING CHANGE: `ArchiveStore.listAfter` and `IssuanceReader.knownKeys` (D-PR52-3).** Two new
  abstract methods for implementers. `listAfter(prefix, startAfter, limit)` returns an
  `ArchiveListing(keys, endOfFolder)`: keys strictly after `startAfter`, in UTF-8 byte order, and
  `endOfFolder` only when the store says there are no more. It has no default on purpose: a
  default that reads the whole folder on every pass is the cost the walk exists to avoid. `list`
  becomes a default method that reads one `listAfter` page. `knownKeys(seller, mode, keys)` returns
  which of the keys a row of that seller and mode records.
- **BREAKING CHANGE: `ReconciliationSweep` takes an `OrphanCursorStore`** (the starter wires
  `JdbcOrphanCursorStore`), after the reprocess ledger. `ReconciliationSweep.Settings` gains
  `orphanPages` and `orphanCycleAlert` (the five-argument constructor is kept, with the defaults).
  `ReconciliationSweep.Result` gains `orphanCycleStale` (in `total()`) and `orphanKeysExamined`
  (not in `total()`: a count of work).
- **BREAKING CHANGE: a seller id with upper-case letters on a filesystem archive that ignores
  letter case is refused at startup** (`DEI-285`, no opt-out). On macOS and Windows volumes,
  sellers `Acme` and `acme` share one folder, and each would be shown the other's documents. If
  this seller has issued documents, move the archive to a case-sensitive volume and keep the seller
  id. Only a seller with no issued documents may change its id to lower case: numbering is per
  seller id, so `Acme` restarting as `acme` would issue `INV-2026-000001` a second time. The probe
  tests the archive root only; case folding set on a folder below it is not detected. The rule is
  in `FilesystemArchiveStore` itself: `putIfAbsent` and `listAfter` refuse such a seller, so a store
  wrapped by the host, or built without the starter, is refused too (D-PR57-6). At startup the
  starter lists the seller folder once, with a limit of 1, through whatever wraps the store.
- **BREAKING CHANGE: an S3 directory bucket is refused** when `S3ArchiveStore` is built
  (`DEI-284`): a name ending in `--x-s3`, or `--xa--s3` for an access point. Its listing has no
  order and no start-after. The name check is a convenience; the walk's order guard is the control.
- **A seller id `.` is refused** by `ArchiveKey`, which now refuses any element equal to `.`
  (`ArchiveKey.sellerFolder` builds the walk's folder from the same checks), and at startup.
- **`FilesystemArchiveStore(Path, Clock)`.** The starter passes its clock. The one-argument
  constructor is kept and hides no temporary file, since it cannot tell a write in flight from a
  leftover.

- **`InboundEventStore.due` takes the running-row limit (QUESTIONS 29).** The port gains
  `stranded(now, limit, pageSize)` and `expire(row, finding, now)`, and `due` gains a
  `RunningLimit` argument; `IssuanceUnitOfWork.due` and the `IssuanceSweeper` constructor change to
  match. `InboundEvent` gains a `dueSince` component; the 0.1.0 constructor is kept.

- **Running-row limit with the compare-and-set (D-PR46-1, D-Q29-3).** `transition` and
  `transitionFrom` share one statement and restart the running clock by the same rule. The expiry
  keeps its own compare on state and clock, because `transitionFrom` compares the state only. A
  `FAILED_FETCH` row that was ever parked is now judged by its park clock against the parked limit,
  so one Stripe error no longer leaves it neither due nor stranded.
- **The sweeper's retry edges (D-PR45-B1).** A re-run that reached a refusal or a fetch failure
  from a state with no edge to it threw `DEI-210` and recorded nothing, every sweep tick. Ten new
  edges: `PARKED` and `FAILED_FETCH` to the three refusals; `FETCHED` to the three refusals and
  `FAILED_FETCH`, taken only when the issuance table holds no number for the invoice. A row that
  may hold a number (`MAPPED`, `FAILED_ISSUANCE`, a numbered `FETCHED`) never writes a
  pre-`FETCHED` state: the conclusion is written `FAILED_ISSUANCE` with its own code, with a
  backoff (none for `DEI-205` and `DEI-219`). No new edge leaves a numbered state.
- **New port method `IssuanceReader.anyIssuanceFor(stripeInvoiceId)` (D-SRE-6).** The one reader
  method with no seller and mode: "does this row hold a number?" is asked by invoice id only, so an
  instance of the other mode cannot see a live number as absent. Implementers must add it.
- **The reprocess resumes a numbered row (D-SRE-2, D-SRE-8).** `IssuanceReprocess` now accepts a
  `FAILED_ISSUANCE` row with any code, and a `FAILED_MAPPING` row, whose invoice holds a number. It
  re-opens it in place (`FAILED_ISSUANCE`, code kept, never `RECEIVED`) with one conditional
  statement on the row as read and the ledger entry in the same transaction, then resumes the same
  number. New port method `ReprocessLedger.reopenInPlaceAndRecord`. The re-open to `RECEIVED`
  refuses a numbered row in its own statement; `REFUSED_NUMBERED` now means a number appeared
  between the read and that statement.
- **`REFUSED_NUMBERED` in full (D-PR50-4).** The answer means the invoice holds a number that the
  reprocess may not reopen: an issued, burned or voided one, or a number that appeared between the
  read and the re-open statement. It runs nothing, records nothing and takes no number.
- **The answer for a closed number changed since 0.1.0 (D-PR50-4).** In 0.1.0 a reprocess of an
  event whose invoice holds a voided number answered `REFUSED_NOT_ELIGIBLE`. It now answers
  `REFUSED_NUMBERED`. A script that branches on the answer must handle the new value.

- **The reprocess accepts `FAILED_ISSUANCE` with any code when the invoice holds no number
  (D-PR50-2).** A row the allocator refused before the claim, or a `MAPPED` row that crashed
  before it, can be mapped to `FAILED_ISSUANCE` by a later outage and pass the retry ceiling with
  no number; it now has an operator path. The re-open to `RECEIVED` keeps its no-number guard.
  `IssuanceReprocess.ELIGIBLE_ISSUANCE_CODE` (unreleased) is removed.
- **The retention purge keeps a row whose number is open (D-SRE-7).** A row whose invoice holds a
  open number (numbered, archiving or failed archive) outlives `einvoice.inbound.retention`, so the reprocess
  can still resume that number; it leaves at the first purge after the number resolves.

- **BREAKING CHANGE: `InboundEventStore.stampAttempt` (D-PR45-1).** A new abstract method on the
  port, with no default: a run stamps its row as it starts (`attempts + 1`, a next attempt one
  backoff away, no state change, a compare on the state). One run is now one attempt: the
  conclusions a run writes no longer add one, so a Stripe outage still retries at 1, 2, 4 minutes.
  A run that dies is retried after its backoff, not at the next sweep tick.
- **BREAKING CHANGE: `IssuanceUnitOfWork` takes an `InboundRunLock` and `RunPermits`**, after the
  inbound store. A host that builds the unit of work by hand passes `new
  JdbcInboundRunLock(unitOfWork, dataSource)` and one `new RunPermits(concurrency)` shared with
  every caller.
- **The PR 45 "carry on behind a run in flight" branch is removed.** Under the run lock no other run
  moves the row; a compare that still fails means a writer outside the lock, logged at WARN, and
  the run writes nothing more.

- **BREAKING CHANGE: `InboundEventStore.transitionFrom` (D-PR9-F1).** A new abstract method on the port: a state
  move that lands only if the row still holds an expected state, and answers empty otherwise. A
  host that supplies its own `InboundEventStore` bean must implement it with the compare and the
  write in one locked statement. There is no default method on purpose: one built from `find`
  then `transition` would not be atomic.

- **The date check is part of the unit of work (D-PR36-6).** EINVOICE-DATES used to live only in
  the module's validator, so a host `DocumentValidator` bean (the starter's backs off) removed it.
  Now `IssuanceUnitOfWork` reads the dates from the exact bytes to be archived and compares them
  with the allocation, after the validator and before the archive, on every path. Example: a host
  validator that answers `PASSED` without reading can no longer archive an invoice numbered on
  10 January and dated 3 January. The rule also refuses a VAT point date code (BT-8), a delivery
  or invoicing period the allocation did not give, and any of these elements twice. The
  validator, the date reader and the archive each get their own copy of the bytes, so a port that
  keeps its array and rewrites it cannot change what is archived. A non-UBL document is refused.
- **BREAKING CHANGE: `IssuanceUnitOfWork` takes a `DocumentDates` reader.** A host that builds the
  unit of work by hand adds one argument after the validator: `new UblDocumentDates()` (package
  `com.housedevinci.einvoice.adapter.validation`). There is no constructor without it: one that
  skipped the check would be the defect again. Any other reader works, and is logged as a WARN at
  every startup. The starter builds the reader itself; it is not a bean.
- **Upgrade from 0.1.x.** The bundled schema script upgrades a 0.1.x database in place, as the
  schema owner, in one transaction, rewriting no number, hash or archived byte. Stop every 0.1.x
  instance first; see "Upgrading a 0.1.x database to format 2" in `docs/index.md`. The runtime role
  needs the new `SELECT` grant on `einvoice_schema` (`docs/schema-grants.sql`).
- **BT-2 changes meaning at 0.2.0.** It was the day of `finalized_at`; it is now the numbering day.
  The fiscal year of a resetting series follows it: an invoice finalised on 31 December and
  numbered on 2 January takes a number in the new year's series. `closed-year-cutoff` now applies
  to the supply date.
- **`AllocationRequest` takes a `SeriesScope`** (seller, series, mode, type) instead of a
  `SeriesKey`, no issue date, and an optional service period. A direct caller of
  `NumberAllocator.allocate` passes neither a date nor a fiscal year: the allocator decides both
  under the lock. A host that allocates inside its own transaction retries `DEI-131` and a
  retryable `DEI-132` in a new transaction. `IssuanceNumberingService` keeps its signature.
- **`JdbcIssuanceStore` takes the tax zone** in every public constructor, and the canonical one
  also takes `max-clock-regression`. `DocumentRenderer` gains `carriesSupplyDate()` (default
  `false`). `MappingInput` carries the supply date instead of an issue date; `DocumentInput` adds
  the allocated series and dates; `UnnumberedInvoice.numbered` takes the issue date and supply
  fields; `EnInvoice`, `SourceInvoice.SourceLine`, `Issuance`, `IssuanceEvent` and
  `SeriesReport.Line` gain components, with the earlier constructors kept.
- **Schema format 2 gains columns** (still format 2, not yet released): the dating columns on
  `einvoice_issuance` and `einvoice_issuance_event`, `parked_at` and `retried_at` on
  `einvoice_inbound_event`, two `CHECK` constraints, and the dating columns join the issuance
  guard's immutable list. The completeness check before the version stamp covers them.
- `SeriesKey`, `Issuance`, `IssuanceEvent`, `IssuanceEventReader.DisposedIssuance` and
  `IssuanceChainVerifier.Report` carry the new fields. The earlier constructors of the first four
  remain and mean an invoice.

### Fixed

- **The orphan check examines every key, against every row (D-PR52-3, D-PR51-5).** Up to 0.1.x
  the check compared the newest `page-size` rows of the window's series years with the first
  `page-size` keys of the seller folder, which are the oldest ones. Three examples of what went
  wrong. (1) One invoice issued in January 2026: on 10 March 2027 the pass read the 2027 series
  only, and reported the 2026 document as an orphan (`DEI-272`) on every pass, for ever. (2) A seller
  with 800 documents: an object written as `live/acme/2026/INV-2026-999999-...xml` sorted after the
  first 500 keys and was never reported. (3) The listing had no trailing slash: seller `acme` also
  listed `live/acme2/...` and reported another seller's number and hash as its own orphan.
  Now the check walks the seller folder (`<mode>/<seller>/`, with the slash) and saves where it
  stopped, one position per seller and mode (`einvoice_orphan_cursor`). Each pass makes one
  listing of `orphan-pages x page-size` keys after the saved position (2,000 by default) and asks
  the database, once per page, which of those keys a row records, in any year and any state. A key
  no row records is `DEI-272`. The findings are written before the position moves. Every key is
  examined within one cycle (one walk of the whole folder); with the defaults and a 15-minute
  interval that is 192,000 keys a day. Rules that come with it:
  - The listing must be in strict UTF-8 byte order, after the saved position and inside the
    folder; otherwise the check fails with the new code `DEI-284` and nothing moves.
  - A listed name that is not a valid archive key (other characters, over 512 characters) is an
    orphan with no lookup, under a bounded subject: the name escaped, cut at 200 characters, then
    `#sha256:` and the hash of the full name. It never stops the walk.
  - The filesystem store hides only its own write in flight: the last element
    `.einvoice-<digits>.part`, a regular file, changed within the last hour and not in the future.
    Up to 0.1.x any path with a `/.einvoice-` element was hidden. A name that does not decode (Linux
    accepts any bytes) is listed as `<path>#inode:<file key>`, so two such names are two orphans.
  - No entry a volume writer creates stops the filesystem walk (D-PR57-2). An entry below the
    seller folder that cannot be read or entered (a folder with mode `000`, a tree deeper than the
    path limit) is listed as `<path>#unlistable` and reported as an orphan; one WARN gives the
    count. Only a failure on the archive root or the seller folder fails the listing.
  - No listed key is longer than 4,096 UTF-8 bytes (`ArchiveListing.MAX_KEY_BYTES`, the cursor's
    bound too). The filesystem store cuts a longer name at a code point boundary and keeps its
    `#inode:` or `#unreadable-name` suffix (D-PR57-3). Example: six 255-byte names of `\xff`
    decode to about 4.6 KB and used to stop the walk with `DEI-284`.
  - The S3 store asks for `encoding-type=url` on every listing (D-PR57-5). A key with a control
    character such as `\x01` failed the XML parse on every pass, and `INV-\r.xml` and `INV-\n.xml`
    arrived as one key; both are now listed as written.
  - A cycle completes only on the store's own end-of-folder answer, on the same store. An empty
    listing while an issued row recorded a key is `DEI-241` (an unmounted volume), never a complete
    cycle. An empty end after a position that is not empty is never a completion on its own: the
    folder is listed once from the start. A key there completes the cycle, because a store that
    reports the end one call late gives that answer (D-PR57-9). No key there rewinds the walk to the
    start, and the cycle keeps its start time (D-PR57-4). A changed store, root, bucket or prefix
    starts a new cycle.
  - The orphan check no longer waits for the series read: a failed series read does not stop it.
  - `DEI-283` (`RECON_ORPHAN_CYCLE_STALE`) when no cycle completed within `orphan-cycle-alert`
    (7 days by default), counted from the start of the cycle, so at the earliest 7 days after the
    upgrade. It holds for every cycle: each completion starts the next one (D-PR57-1).

  **Upgrade note.** 0.1.x left false `DEI-272` findings open (example 1), and findings never close by
  themselves. This lists the open ones whose subject a row now records:

  ```sql
  SELECT f.subject_id FROM einvoice_finding f
   WHERE f.code = 'DEI-272' AND f.acknowledged_at IS NULL
     AND EXISTS (SELECT 1 FROM einvoice_issuance i
                  WHERE i.seller_id = f.seller_id AND i.mode = f.mode
                    AND i.archive_key = f.subject_id);
  ```

  Acknowledge them with `IssuanceFindingService`, with a reason. Nothing is acknowledged
  automatically. The schema upgrade builds the index `einvoice_issuance_archive_key` inside its
  transaction, which blocks writes to `einvoice_issuance` while it runs; on a large table, upgrade
  in a quiet window.

  **Upgrade note, archive listing.** (1) The S3 store now sends `encoding-type=url` on every
  `ListObjectsV2` request. AWS S3 supports it; an S3-compatible server that does not will fail the
  listing (`DEI-241`) rather than return keys it cannot carry. (2) Startup lists the seller folder
  once, so the archive credentials need list permission (`s3:ListBucket` on the prefix) even with
  `einvoice.reconcile.enabled=false`; on a filesystem archive that is one walk of the seller folder
  per start. (3) Entries the filesystem walk cannot read now appear as `DEI-272` findings with a
  subject ending in `#unlistable`; they no longer fail the check with `DEI-241`.

- **An archive object at a burned or voided number is reported (D-RTR-4).** A PUT can time out on
  the client and complete on the store after the number was burned by a resumed run or voided by an
  operator. The row keeps its key, so the orphan check counted the object as known and nothing
  reported it. Example: the PUT for `F-2026-0042` times out, the retry finds nothing and burns the
  number, and the first PUT completes 15 seconds later. The sweep now has a fifth check, run on its
  own like the others: every `FAILED_VALIDATION` or `VOID_UNUSED` row with a key, in any year, is
  read at that key, once (D-PR52-2: the S3 store has no fast existence check, and a read of a
  missing key costs the same). The rows come from the new port method
  `IssuanceReader.disposedWithKey`: burned or voided rows with a key, of the seller and mode, every
  series and year, newest number first, a page of `page-size` with a WARN when full (D-PR52-1: a
  2026 number voided on 10 January 2027 while its PUT lands is still looked up). Implementers must
  add it. It is served by the existing `einvoice_issuance_open` index. An object there is the new
  code
  `DEI-281 RECON_OBJECT_FOR_DISPOSED_NUMBER`, subject the Stripe invoice id; when its bytes do not
  hash to the row's hash it is `DEI-275` too. A lookup that fails is `DEI-241` and is counted, never
  read as clean. The WARN line names the number, state, key and hash match, no content and no
  exception message. Nothing is deleted. `ReconciliationSweep.Result` gains `disposedWithObject`
  and `disposedUnverified`, both in `total()`: a source break for code that calls its constructor,
  which only the sweep does. The finding repeats on every pass until the row leaves the newest
  `page-size` disposed numbers with a key.

- **A resumed run whose new bytes differ from the recorded document burns the number (D-RTR-5).**
  `F-2026-0042`'s first archive write fails after its hash H1 is recorded. The host upgrades its
  renderer; the retry finds no object, renders H2, and H2 passes. It used to end on `DEI-240`
  with the row left `FAILED_ARCHIVE`, nothing chained, and the sweep re-enqueueing it to the retry
  ceiling. It now burns the number: `FAILED_VALIDATION` with rule id `DEI-244`, new code
  `RENDER_NOT_REPRODUCIBLE`; H1 and its key stay on the row and in the one chained event; nothing
  is archived; one WARN names both hashes and both keys, no content; the sweep reports `DEI-279`
  and enqueues nothing. Another extension with the same bytes is another key and burns too.
  `DEI-240` keeps its one meaning: the store holds other bytes at the key. Renderers must be
  byte-stable across a retry (README, `DocumentRenderer`).
  A later event for any burned number now records the burn's rule id with its `DEI-250` (it had
  none), so after a `DEI-244` burn it does not read as a validation refusal (D-PR53-1).

- **The stuck check names every open number (D-PR50-B1).** `DEI-273` read only the newest page of
  the series years its two-day window touched, and only after the Stripe list call, which throws
  on a refused key or an outage. A number left open across a year end, or through an outage that
  lasted past the window, was never named. Example: a number allocated on 30 December stays open;
  the Stripe key is refused until 3 January; no pass named it. The check now runs first, before
  any Stripe call, and reads open numbers of the seller and mode in every series and year, oldest
  first, through the new port method `IssuanceReader.openOlderThan` (served by the existing
  `einvoice_issuance_open` index). Implementers must add it. When it reads a full page it logs a
  WARN, since more may be open.
- **Every reconciliation check runs in every pass (D-PR51-1).** A refused Stripe key or a Stripe
  outage threw at the list call and skipped the archive checks (`DEI-271`, `DEI-275`, `DEI-272`)
  and the unfinished-reprocess check (`DEI-276`) for as long as it lasted. Each check now runs on
  its own: one that fails is logged at WARN with its name and code, and the next one still runs.
  Example: the key is revoked on Friday and an archived invoice is deleted on Saturday; Saturday's
  pass still names the missing object. The pass then throws its first failure, so a pass with a
  failed check is still never "completed" and `DEI-274` still fires.

- **A refusal on a resumed run is recorded (QUESTIONS 38, D-DD-B1).** A run that resumed a row in
  `ARCHIVING` or `FAILED_ARCHIVE` and was then refused by the validator, the date check or the
  renderer ended on `DEI-113` (illegal transition), with the number still open and re-enqueued by
  the sweep. Two new edges, `ARCHIVING -> FAILED_VALIDATION` and `FAILED_ARCHIVE ->
  FAILED_VALIDATION`, in the enum and in the database trigger, burn the number with its rule id
  and a chained event. Example: `F-2026-0042`'s archive write times out on 10 January; after an
  upgrade the retry renders BT-2 = 3 January; the event now ends on `DEI-250` with
  `EINVOICE-DATES`, the row is `FAILED_VALIDATION`, and the sweep reports `DEI-279` instead of
  re-enqueueing it.
- **A resumed row reads its own archive key before it renders.** Same bytes as recorded: the number
  is issued with no render, because the document already exists. Other bytes: `DEI-240`. A read
  failure: retried, never a burn. No object: the run renders and checks again. A burn of a row that
  had recorded a hash keeps that hash and logs both hashes at WARN.
- **Two workers can no longer burn a number whose archive write is in flight (D-RTR-7).** During a
  rolling deploy, worker A wrote its ARCHIVING marker and was uploading; worker B resumed the row,
  found no object yet, rendered other bytes, was refused and burned the number; A's document then
  landed. The row is now held under a session-level advisory lock from just before the marker until
  the outcome is recorded. A second worker gets `DEI-245` and retries; it reads, renders and writes
  nothing. **Breaking for hand-wired hosts:** `IssuanceUnitOfWork` takes an `IssuanceRowLock` after
  the writer (pass the same `JdbcIssuanceStore`), and `JdbcUnitOfWork` gains `onOneConnection`.
- **`DEI-104`: a pooler in transaction mode is refused** before a number is taken, because it
  would silently turn the session lock into no lock. Session pooling and direct connections work.
- A redelivery whose archive read fails is retried (`DEI-241`), never reported as tampered.
- **Joined mode keeps the run's own outcome and never commits a half-written invoice (D-PR43-1,
  D-PR43-6).** Inside a caller's transaction each locked step runs under a JDBC savepoint, so a
  failed step no longer aborts the transaction before the lock is released, and the run keeps its
  real error code instead of `DEI-104`. When a step fails after the transaction wrote through the
  store, the module aborts the transaction on purpose (a `DO` block raising SQLState `DE248`,
  confirmed by a statement that must fail with `25P02`), so the caller's commit commits nothing;
  Spring callers also get `UnexpectedRollbackException`. The same now applies to a typed refusal
  after a write outside the lock. The caller's connection is never aborted or closed. Two new
  public error codes: `DEI-248` (the caller's transaction was already aborted before this call)
  and `DEI-249` (pgjdbc `autosave=always`, or an unreadable driver, refused in joined mode; own
  connections set `autosave` NEVER themselves).
- **The row lock proves it is still ours, on every locked transaction (D-PR42-2).** Under a
  transaction pooler, a backend reset or another client could free or take our lock while the
  backend id stayed the same, and our release could then free another client's lock. Taking the
  lock now writes a random token into the session; every locked transaction first checks the token
  and the lock together and stops with `DEI-104` before it writes; the release frees only when that
  check holds. Statement pooling is refused by its SQLState (`08P01`). The session setting
  `einvoice.row_lock` is reserved.
- **Security review of the row lock, pass 1 (D-PR42-1, 3, 4, 5).** The starter runs the session
  check once at startup, outside any transaction, and a joined holder needs none, so a host
  transaction on a pool of two is never starved. The lock's pre-check reads only this database's
  locks: row 42 of a staging database on the same cluster no longer waits for production row 42. A
  failed lock release has its own code, `DEI-247`. The README mount-point note names the harm and
  gives `RequiresMountsFor=` and `mountpoint -q` checks.
- **A burn writes the rule id and nothing else.** The trigger refuses a move to
  `FAILED_VALIDATION` that also changes the document hash, the archive key or the void reason; the
  void reason changes only with a move to `VOID_UNUSED`.
- **A lost inbound compare-and-set under REPEATABLE READ or SERIALIZABLE answers quietly (D-PR45-2).**
  When the store owns the connection, a `40001` on the state move is retried once in a fresh
  transaction, so the loser of a worker and sweeper race steps aside instead of logging `DEI-102`.
  In a host transaction the error passes through unchanged.
- **Two runs on one event no longer fail with `DEI-210` before the number (D-PR9-F1).** A worker
  and the sweeper, or a re-open and a retry, can run one event at once. A run wrote the inbound
  row before the number claim against whatever the row held at that moment. Example: run B waits
  in the preflight while run A issues the invoice; B then writes `MAPPED` over `COMPLETED`, and
  `DEI-210` escapes `process`. Every inbound write before the claim now lands only if the row
  still holds the state the run last saw. When the row moved, the run reads it again: it answers
  with the stored outcome (here `COMPLETED` and A's number), or carries on behind a run that is
  still in flight. A row another run moved forward is never moved back to `FETCHED`.

- **An unreadable archive object is a failure, not "absent" (D-RTR-8).** The filesystem archive
  used `Files.exists`, which answers false when it cannot tell (no permission, a link loop). A
  read failure looked like "no object", so a resumed run could burn a number while the document
  was on disk, and `list` hid a directory it could not read. `get` now reads the bytes and only
  `NoSuchFileException` is absent; `list` reads the directory attributes and reports any other
  I/O error as `ARCHIVE_UNAVAILABLE`. Example: the archive directory loses its permissions during
  a deploy; the retry now fails and retries later instead of re-rendering and burning the number.
  The S3 store was checked: only `NoSuchKeyException` is absent, a 403 is a failure.
- **A missing archive root is not "no object" (D-RTR-9).** When the archive volume drops or the
  root folder is moved, every key answers "no such file". `get` and `list` now read the root's
  attributes before any empty answer, and a missing root, or a root that is not a folder, is
  `ARCHIVE_UNAVAILABLE`. Example: the volume drops at 10:00 and an invoice is redelivered at
  10:05; it is retried later instead of ending as tampered.
- **The archive reads only regular files, and never follows links (D-RTR-10).** `get` and the
  write-once comparison share one reader: it reads the attributes without following links,
  refuses anything that is not a regular file, and opens with `NOFOLLOW_LINKS`. Example: a named
  pipe at a key used to block the call for ever; a symbolic link used to return the bytes of a
  file outside the archive root. Both are now `ARCHIVE_UNAVAILABLE`.
- **`list` reports entries it cannot classify (D-RTR-11).** The listing uses `Files.find`, so a
  broken link or an odd entry is listed (the sweep can then flag it) instead of dropped. A prefix
  that exists but is not a folder is `ARCHIVE_UNAVAILABLE`, not an empty list.
- **A linked folder inside the archive no longer leads out of it (D-RTR-12).** The no-follow flag
  guards only the last name of a path. Every folder name from the root down is now checked and
  must be a plain folder, in `get`, `putIfAbsent` (before and after the folders are made) and
  `list`; `list` also re-checks the root after the walk. Example: `live/acme` linking to
  `/srv/other` used to let a legal invoice be written there. It is now `ARCHIVE_UNAVAILABLE`.
- The reference guard now refuses the moved-private document names in any letter case (for
  example a lower-case copy of a private document), in content and in file names. The bare
  review and release words stay upper case only, because in lower case they are plain English.
- The reference guard also refuses the review word followed by a dot or a dash, and the release word followed by
  a dot, in any letter case (for example a lower-case copy of a review document, or a release runbook with
  another extension). Its self-test now pins the upper-case form of five document names and the combined
  file-name cases (lower-case private name, the slug re-scan, a mixed-case jar member).
- **Security review of the invoice date order (D-PR36-1 to 5).** Lateness (`DEI-137`, the cause)
  is counted from the day Stripe finalised the invoice, also when an invoicing period is written.
  The chain verifier compares each disposed row's dates with its chained event, and a void event
  now carries the dates. `DEI-130` tells the operator to check both clocks against NTP before
  touching the series. The module's validator refuses a document whose BT-2, BT-72 or BG-14
  differs from the allocation (rule `EINVOICE-DATES`). A test reads the production issue clock
  against the real time in three session zones.
- **Security review of the invoice date order, pass 2 (D-PR36-7, 8, 9).** A value of
  `einvoice.numbering.max-clock-regression` above `5m` now logs a WARN at every startup. The module's
  validator answers `NOT_EVALUATED` (rule `EINVOICE-DATES`) when it is given no allocation input,
  so a skipped date check no longer reads `PASSED`. The rule also refuses a document-level
  `cbc:TaxPointDate` (BT-7): it checks BT-2, BT-7, BT-72 and BG-14.
- The reference guard (`tools/check-private-references.sh`) now reads file names as well as
  file content, with the same pattern: every tracked path in the tree, every member name and
  the file name of each built jar. One shape is admitted by name only, a security review
  document at the root of `docs/`; its text is still scanned. The tree listing is read
  NUL-separated, and a control byte in a path, a symlink and a submodule entry are refused. In
  a jar, a member name holding a control byte, a `..` segment or a leading `/`, two members
  with one name (also after case folding), an empty jar and a listing that does not match the
  jar's entry count are refused before extraction. The guard runs in the C locale, so a byte
  that is not valid UTF-8 no longer hides a name, and a refused name is printed with every
  non-printable byte escaped.
- The jar side of the reference guard checks that extraction was one to one: no symlink in the
  extracted folder, and the number of regular files and their total bytes equal what the jar's
  central directory lists. Before, a member at `./a.txt` next to `a.txt`, the data of a
  directory entry, or a symlink member's stored target could carry text the content scan never
  read. Member names with an empty or `.` segment are refused by name. Content hits are printed
  with control bytes escaped, so a carriage return in a hit cannot start a workflow command.
- **Security review of the typed number series (D-A1-C01, C02, C04, C05, C06, C07).** The version
  stamp of the schema script is now one final block that checks every format-2 condition and
  refuses with `EIU03` (`DEI-134`); the documented by-hand command is
  `psql -X -v ON_ERROR_STOP=1 --single-transaction`. The two new insert guards qualify every table
  they read with the trigger's schema, so a temporary table cannot shadow it. (The window that
  refused an issue date no time zone derives from the invoice instant left with the request's issue
  date; a far date is now refused as `DEI-130`.) The backfill refuses a tax
  zone that is not a PostgreSQL region id. A chained event's six `corrects_*` columns are empty
  together or filled together, and a half-filled row is a decode error. A probe now guards the
  pre-seam clause of the chain verifier.
- Build: the reference guard (`tools/check-private-references.sh`) now also refuses the first names of the studio's agents in the tree, the main jar and the sources jar, so public text keeps to roles. Case-sensitive and whole-word; the script's header lists each name left out and why. The jar scan reads class files as text, so a string length byte cannot read as a name on GNU grep.
- After the security review of that change: a tab, form feed, vertical tab or carriage return
  before a name no longer hides it; only the JDK cipher class may follow a dot or a slash; the
  check-name exception is the exact word "probes"; an agent's lower-case address is refused; a
  jar member that is itself an archive is refused (fail closed); the guard job and the jar step
  are probed for unconditional wiring by exact command.
- Build: Maven wrapper pinned to 3.9.16 and Dependabot ignores Maven >= 3.10 until central-publishing-maven-plugin supports it; 7-day Dependabot cooldown.
- **The pre-sign vulnerability gate's verdict document is now bound to its coverage document.**
  A grype report and the CycloneDX document it is paired with are refused unless both name the
  same scan target and the same scanner version, closing the gap where a stale or mismatched
  file pairing could answer with matches nobody computed over the staged set. `--min-artifacts`
  and `--expect-digests` are now mandatory for the Grype arm of `check-vulnerability-report.py`,
  same as `--sbom`, with no default that lets a caller pass a weaker check unknowingly. The
  release workflow's job-summary line no longer kills the step under `set -euo pipefail` when
  the gate printed no coverage line.

### Security

- **One series name, one document type, under concurrent writers (D-A1-C03).** A series name was
  bound to one type by a check inside the insert trigger. That check could not see a row another
  transaction had not committed yet, so two writers that created series `RACE` for 2026 as an
  invoice and for 2027 as a credit note at the same instant both committed. A new table,
  `einvoice_series_name`, now holds one row per seller, mode and series name, with its type. Its
  primary key makes a second writer of the same name wait and fail, at every isolation level, and
  a foreign key from every series row carries the type. The race-prone check in the trigger is
  gone. What the losing writer sees: `DEI-100` naming the series at READ COMMITTED; `DEI-117`
  (retryable, fixed message) at REPEATABLE READ and SERIALIZABLE, where PostgreSQL aborts the
  transaction. A raw insert of a series row of the other type fails on the foreign key (`23503`).
  The runtime role needs the new grant `SELECT, INSERT` on `einvoice_series_name`
  (`docs/schema-grants.sql`). The upgrade fills one name row per existing name, and refuses with
  `EIU04` (`DEI-134`), changing nothing, a database that already binds one name to two types; only
  an unreleased build can have written one. The final block of the script checks the name table,
  its two guard triggers, and that the foreign key exists, carries the type, is validated and has
  its own triggers enabled. An extra unique index on `einvoice_series_name` that its primary key
  does not imply is refused with `DEI-136` and a fixed message, not reported as a retryable race
  (D-PR40-4). Example: an operator adds `UNIQUE (seller_id, series)`; a seller who used `RACE` in
  test mode gets `DEI-136` in live mode, not `DEI-117` for ever. The schema comment states the rule
  for any further index on that table.
- **The planted-operator exposure is stated for upgraded databases (D-ADV-1).** The exposure is
  any database where the application role may `CREATE` in schema `public`: the default on
  PostgreSQL 14 and older, and kept on 15 and newer after an upgrade or a dump restore. Only a
  database created new on 15 or newer refuses by default. The `REVOKE CREATE ON SCHEMA public
  FROM PUBLIC` workaround stays. An operator planted before the `REVOKE` keeps working, so
  SECURITY-NOTES now also gives the queries that list the operators and functions the role owns.
- **Every guard function pins its search path (D-A1-C08).** Each trigger function the schema
  script creates now carries `SET search_path = pg_catalog, pg_temp`, so a role that can create
  objects in `public` (the default on PostgreSQL 14 and older, and kept on 15 and newer after an
  upgrade or a dump restore) cannot plant a look-alike `<>`
  operator that disarms a guard. The last block of the script checks the setting on every guard
  function and refuses with `EIU03` (`DEI-134`) otherwise. The backfill now accepts only `UTC` or
  a zone name with a slash, because legacy names such as `CET` are read differently by PostgreSQL
  and the JVM (D-A1-C09).

- **The final block checks the real trigger, not its name (D-A1-C12).** For each of the 15 guard
  triggers it reads which function the trigger calls and whether it is on. It refuses with `EIU03`
  (`DEI-134`) when a trigger is disabled, is bound to another function, or its function lacks the
  pinned search path.
- **`tools.jackson.core:jackson-databind` pinned to 3.1.7 (GHSA-q4xh-88c3-wmh7, HIGH, CVSS 7.5,
  denial of service; GHSA-cxp5-3px4-pw24, HIGH, quadratic-time forward-reference completion;
  GHSA-wv8q-qhhj-9h54, HIGH, retains every unknown raw type ID).** The Spring Boot 4.1.1
  dependency BOM pins the Jackson 3 line at 3.1.5, below all three fixed versions; the parent
  POM now overrides `jackson-bom.version` to 3.1.7, resolved across every module (verified with
  `dependency:tree` on the starter and the sample). The Stripe SDK dependency (`stripe-java`)
  uses Gson, not Jackson, so it carries no Jackson 2 line and is not affected by this advisory
  or by CVE fixes in the `com.fasterxml.jackson.core` 2.18.x/2.21.x/2.22.x series.

## [0.1.0] - 2026-09-23

### Fixed

- **The sample application starts from a clean clone again.** Its `application.yml` configured a
  Stripe webhook secret, so the starter's intake wiring check correctly refused a context with no
  `StripeInvoiceSource` in it, and the shipped application could not start without a Stripe account.
  The sample now ships as the numbering and rendering demo it is described as - `einvoice.issuance.enabled:
  false`, no webhook secret, no API key - and says so at startup; the new `intake` Spring profile
  (`application-intake.yml`) turns the Stripe intake on with the two environment variables the
  README documents. The wiring check is unchanged and no weaker: a host that configures intake and
  is missing a part is still refused, and the refusal now names the sample as the worked example of
  both states.
- **`einvoice.issuance.enabled=false` now turns the whole Stripe intake off, not just the sweeper.**
  The property is documented, here and in the module's own refusal message, as "run the numbering
  API only"; it used to leave the unauthenticated webhook endpoint mapped, the worker built and the
  inbound event store writing, so a host that had turned intake off still accepted Stripe events,
  recorded each one durably, answered 200 so nothing retried and nothing alerted, and issued
  nothing. The webhook controller, the worker, the reconciliation sweep, the privileged reprocess
  and the inbound event store are no longer registered in that state, and the intake wiring check
  now says at startup exactly what is off - including that a still-configured webhook secret is
  being ignored. The numbering API, the archive, the findings store, service and endpoint, the
  preflight support, the reprocess ledger and the health indicator are unaffected, asserted bean by
  bean.
- **The placeholder Stripe secrets this repository prints are refused by name.**
  `whsec_from_your_stripe_dashboard` and `rk_test_your_restricted_read_scoped_key`, which the README
  and the sample's `intake` profile name as the values to replace, were well formed by every check
  the module applied, so pasting the documented command verbatim started an application whose
  unauthenticated webhook endpoint was guarded by a string published on GitHub. Both are now in the
  refused-placeholder set, alongside `whsec_changeme` and friends, and a test reads the literals
  back out of the shipped files so a future documented placeholder cannot escape the same refusal.
- **The release preflight job checks the repository out, and verifies the tag before it runs any of
  it.** It runs two scripts from `tools/` and had no checkout step, so both exited 127 and no gate
  was ever evaluated. The ancestry check and the signature check now run in that job, before the
  first line of repository code, as well as in the publishing job. The job stays free of secrets and
  of the deployment environment, and the sample smoke check joins the list of checks it requires
  `main` to carry.

- **A Stripe invoice whose only number was voided is terminal, and nothing loops.** The module's own
  documented remedy for a burned number is an operator void; until now every later event for that
  invoice resumed onto the voided row, raised an illegal transition that escaped the unit of work,
  left the inbound row in a running state with no code, and was re-picked on every sweep - each time
  paying a Stripe re-fetch and a full render - while the reconciliation sweep re-enqueued it instead
  of reporting it. A later event is now recorded terminally with the new code `DEI-122`, the
  reconciliation sweep reports `DEI-278` (voided) or `DEI-279` (burned, awaiting a void) and
  enqueues neither, and the claim-race loser follows the winner's state rather than concluding on
  the success terminal. The guard is an exhaustive switch over the issuance states with no
  `default`, so a new state cannot fall through to the allocator again. **A void remains
  irreversible and is unrecoverable inside this module**: one Stripe invoice maps to one number for
  all time, so the remedy for a sale that must still be documented is a new Stripe invoice upstream,
  and a credit note when it is already paid - which this edition does not produce.
- **The justification for a burned or voided number can no longer be rewritten unreported.** The
  failing rule id is now written on the issuance row in the same statement that burns the number;
  the reason and the rule id change only in the statement that records the disposition, so a bare
  `UPDATE` by the runtime role is refused by the trigger; and the verifier's cross-check compares
  both columns against the chained event, so a rewrite by a role that outranks the triggers is
  reported as `BROKEN` instead of `INTACT`.

- **Tomcat pinned to 11.0.25.** Spring Boot 4.1.1 manages 11.0.24, which carries three CRITICAL
  advisories (GHSA-9xv2-5v5q-p794, GHSA-h3x4-894j-xpx5, GHSA-gcx9-497g-6cp6) in the DIGEST and FORM
  authenticators and in access control. The starter brings `tomcat-embed-core` in with the web
  dependency the webhook controller needs, so this was not only the sample's problem. Found by the
  new vulnerability gate on its first real run.

- **A numbering-only host could not start.** The starter contributes a health group naming its own
  contributor, and the contributor was conditional on the issuance sweeper, so an application that
  added this library for numbering failed to start with `Health contributor 'einvoiceIssuance' ...
  does not exist` - a bean name it never chose. The contributor now exists whenever the starter
  does and reports `issuance: not configured` where there is no pipeline. Found by running the
  documented quick start from a clean clone.
- **A configured intake with no Stripe API key started silently.** The startup wiring check asked
  for a `DocumentRenderer` and a `DocumentValidator` but not for the authoritative reader, so an
  application with a webhook secret and no `einvoice.stripe.api-key` started with an endpoint that
  recorded events nothing would ever fetch, number or archive. It is now refused by name at startup,
  with the same remedy as the other two.
- **A blank `einvoice.stripe.api-key` took down a numbering-only host.** A YAML file that offers an
  environment variable with a fallback makes the property present and blank, which was enough to
  build the Stripe client and throw "einvoice.stripe.api-key is required" at startup. Blank is now
  no key; an application that *is* configured to receive events and has no reader is still refused
  by name.
- **The sample declared no Stripe SDK**, so the documented `spring-boot:run` could never wire an
  authoritative reader whatever key was exported. It declares it now, and the refusal message names
  both ways that bean comes to exist.
- **The quick start did not work from a clean clone.** `cd stripe-einvoice-sample && ../mvnw
  spring-boot:run` cannot resolve its sibling modules; the README now installs first, and leads with
  the one command that produces a validated Peppol document and a validated XRechnung from a clean
  clone with no Stripe account (about two minutes, measured).
- **A Grype report that scanned nothing passed as a clean release.** The vulnerability gate already
  refused an OSV report listing no packages at all; the same emptiness through Grype's own
  `artifacts` array - absent or empty - exited 0. Both formats now refuse a report of a scan that
  looked at nothing with the same message, on the same reasoning: a scanner exit that means "we
  could not scan" must never be indistinguishable from a clean tree.
- **The release gate named the wrong scanner when a Grype report listed no artifacts.** The message now states Grype's own causes (jars not staged, wrong input path, empty SBOM) instead of OSV-Scanner's flag. Wording only; the refusal itself is unchanged (second security pass, INFO).
- **The reconciliation sweep's reprocess check crossed the tenant boundary.**
  `ReprocessLedger.unfinished` took no seller and no mode, so a sweep could raise `DEI-276` under
  its own seller for another seller's - or another mode's - orphaned reprocess. It now takes both
  and is filtered the same way as every other read the sweep makes (second security pass, P2-01).
- **A reprocess run that failed with an untyped error was concluded as `DEI-200`**, the code for "no
  inbound event is recorded", which is not what happened - the event was read; the run failed for
  another reason. Concluded now with the new `DEI-277`, "the reprocess run failed with an error this
  module does not type". A ledger failure while concluding no longer replaces the original exception
  on its way to the caller (second security pass, P2-02).
- **`einvoice_reprocess_request` was missing from `EInvoiceTables.ALL`**, so the persistence-mapping
  guard, the database-view guard and the schema-owner startup warning all skipped the one table whose
  only protection is a trigger. It is on the list now (second security pass, P2-03).

- A resetting series' rendered number now carries the fiscal year (a required `{fiscalYear}`
  placeholder in the prefix, resolved once at the series row's creation), so a second fiscal year
  can no longer reissue the first year's legal numbers.
- Every allocation renders from the series row's own prefix and width, never from the running
  configuration, so an edited property cannot silently change the format of a live series.
- The disposition cross-check behind the "every number has a recorded disposition" claim is now
  keyed on the full row identity, not on the number and state alone.
- `einvoice.numbering.allocation-timeout` is now applied to the allocation transaction; a lock
  wait past the bound is a typed refusal instead of an unbounded wait.
- The screening function now refuses a value that is blank only under a wider, Unicode-aware
  definition of whitespace, and refuses bidirectional override and isolate characters outright.
- The database-view guard now sees a view whichever role runs it, not only a role that owns the
  guarded tables.
- The chain secret is now excluded from `/actuator/env` and `/actuator/configprops` by name.
- The default schema-initialisation step no longer requires schema-owner privileges once the
  schema is already fully migrated.
- An unset document hash reads back as an empty string, not sixty-four padding spaces.
- **An allocation now joins the caller's transaction.** Called inside a Spring-managed transaction,
  the counter increment and the issuance row commit and roll back with that transaction; called
  outside one, it opens and commits its own connection exactly as before. The reads join it too, so
  a caller that allocates and then reads inside one transaction sees its own row. A caller-owned
  connection in auto-commit mode and a read-only caller transaction are refused with their own
  error codes (`DEI-118`, `DEI-119`) instead of being half-executed, and a failure after this
  module's first statement marks the caller's transaction rollback-only so a swallowed refusal
  cannot be committed.
- The lock invariant is now the ordering rather than the exclusion: a caller may hold the series row
  lock and then the chain's advisory lock in one transaction, and the reverse order is refused
  (`DEI-120`) rather than left to deadlock.
- The lock ledger is now unbound when a Spring transaction is suspended and rebound when it resumes,
  so a `REQUIRES_NEW` call no longer inherits the outer transaction's lock records and is no longer
  refused for locks it does not hold.
- A refusal raised after only a locking read (`SELECT ... FOR UPDATE`), and before any write, no
  longer marks the caller's transaction rollback-only. The two refusals a host is meant to catch and
  carry on from - an unknown invoice on a void, and the illegal-transition refusal - no longer turn
  a caught, handled refusal into an `UnexpectedRollbackException` at commit. "Written" is classified
  at the point each statement is created, from its own SQL text, and tracked on the same
  transaction-scoped ledger as the two locks, so a refusal in one call still poisons the caller's
  commit when an earlier call in the same transaction already wrote.
- A caller-owned unit of work that fails after writing, and refuses every further call on that unit
  (`DEI-121`) rather than attempting to roll back a connection it does not own, is now covered by a
  test.
- The claim (P1) and the issue (P4) phases of the issuance unit of work now record a state and a
  code on every stop, like every other phase already did. A refusal from either used to leave the
  inbound row `MAPPED` with no code and no recorded attempt, so the sweeper re-picked it forever and
  the retry ceiling was never reached. The loser of a claim race - `invoice.finalized` and
  `invoice.paid` arriving together, the normal case - is now recorded `COMPLETED` with the winner's
  number, re-read rather than guessed at, instead of landing in that state.
- An application with `einvoice.stripe.webhook-secrets` configured (or `einvoice.issuance.enabled`
  set explicitly) and no `DocumentRenderer` or `DocumentValidator` bean now fails startup by name,
  naming the missing bean and the property that turns the pipeline off. It used to start with no
  endpoint, no sweeper and no signal at any log level. A host with neither signal present still
  starts quietly, with one WARN.
- The sweeper now re-picks a `REFUSED_VERSION_SKEW` row once the currently configured API version
  pin equals what that row recorded, matching what the README, the docs page and the security notes
  already promised. It previously never re-picked a refused row at all; `REFUSED_MODE` and
  `REFUSED_ACCOUNT` are still never re-picked automatically, since neither is cured by an upgrade.
- An unexpected exception from a host-supplied `DocumentRenderer`, `DocumentValidator` or
  `ArchiveStore` is now recorded as a terminal, non-retryable failure with a stable generic code
  instead of leaving the inbound row `MAPPED` forever with no code and no recorded attempt. The
  cause is logged server-side only, never returned to a caller.
- An explicit `einvoice.issuance.enabled=false` is now honoured even when a webhook secret is
  otherwise configured, so the fail-fast startup refusal's own remedy - setting that property -
  actually works.
- The screening function and the canonical XML writer now refuse the XML 1.0 non-characters
  (U+FFFE, U+FFFF, U+FDD0..U+FDEF and the last two code points of every other plane) in the same
  pass as C0 controls and unpaired surrogates. A buyer-controlled field carrying one of these
  ordinary-looking code points used to survive both checks and produce bytes no XML 1.0 parser
  could read, discovered only after this module's own schema stage refused to re-read what it had
  just written; nothing unparseable can leave this module now. Legal C1 controls continue to pass.
  The refusal still happens after a legal number has been allocated, so it costs one number with a
  recorded, chained failed disposition and an operator void, exactly like every other data-dependent
  mapping refusal this module already accepts - see SECURITY-NOTES.md's residual risks. Closing that
  before the allocator runs is planned as its own change (QUESTIONS 22).
- An application whose configured validator can never actually run at all (no XSLT 2.0 processor
  on the classpath, in this module's own implementation) now refuses before the allocator and, when
  the issuance path is wired, at startup - rather than discovering the same application-wide fact
  once per invoice, after a legal number is already spent.
- `NOTICE` now names the third-party artefacts vendored under this module's own resources (the
  OASIS UBL 2.1 schema set and the CEN EN 16931, XRechnung and Peppol schematron stylesheets),
  each with its publisher, release and licence, alongside a build-time check that a newly vendored
  artefact cannot ship without one.

### Changed

- The sample smoke check (start the shipped application from a clean clone, time the first
  response) moved into `tools/run-sample-smoke.sh` and now runs on **every pull request** as well as
  on a tag. It ran on the tag path only, which is why the sample's startup defect survived seven
  green pull requests. It also refuses to measure an application it did not start, and asserts the
  startup line that says the sample has no Stripe intake.

- `DocumentRenderer.render` is still handed a `DocumentInput`, but a `DocumentInput` is now the
  `MappingInput` the preflight approved plus the legal number, rather than five separate
  components. The render input therefore *is* the preflight input plus the number, so the two
  passes cannot be given different invoices, series or issue dates. Hosts that build a
  `DocumentInput` themselves change one constructor call; nothing is released yet.
- The inbound `MAPPED` state now means the mapping ran and produced a document model. It used to be
  written as soon as the totals agreed, while the mapping itself was first attempted after a number
  had been allocated.
- A probe that runs an inner build or an external command now prints the last 30 lines of that
  command's output when it reports WEAK, and a probe that could not run at all says why on its own
  line. A swallowed transient failure and a real weakness used to look identical.
- The licence gate's own self-test runs in CI, not only when someone remembers to run it by hand.

- **EN 16931 documents: XRechnung 3.0 and Peppol BIS Billing 3.0, in UBL.** A semantic model in the
  domain package with zero third-party imports, a canonical UBL 2.1 writer, and a mapper from the
  authoritative Stripe invoice. Every EN 16931 balance rule is an invariant of the model, so an
  unbalanced document cannot be constructed, let alone written or archived.
- **The official validator suites, vendored and run before anything is archived.** The CEN
  EN 16931 schematron, the CIUS XRechnung 3.0.2 rules and the Peppol 2026.5 rules, each with a
  recorded SHA-256 and a provenance note, nothing downloaded at build or run time, and every
  checksum recomputed before a stylesheet is compiled. The only conformance claim this project makes
  is that its output is accepted by these, and they run in CI on every build over every fixture.
- **Parser and stylesheet hardening**, with a probe per control that is shown to be capable of going
  red: DOCTYPE refused outright, external DTD, schema and stylesheet access blanked, extension
  functions off, resolvers that throw rather than return empty, a bounded report and a wall-clock
  timeout. A validation that could not run reports `NOT_EVALUATED`, which refuses the archive write;
  there is no path that reports a pass without having executed the rules.
- **One screening function between every free-text value and the bytes**, with a reflection test
  over the model's own string components so a field added later is caught on the commit that adds
  it. Markup is escaped; a control character, an unpaired surrogate, a bidi override or a value that
  collapses to blank is refused.
- **An explicit currency exponent table**, Stripe's minor unit beside ISO 4217's presentation
  exponent, with the zero-decimal and three-decimal currencies listed by name. There is no default
  of 2 anywhere; an unlisted currency is refused at mapping time.
- **A versioned tax rule pack** that derives the EN 16931 category and its VATEX reason from the
  upstream's own taxability reason, checks it against the parties' countries, and refuses on a
  conflict rather than recategorising. Its id and version are recorded with the issuance.
- **Identifier checking for both parties, the configured seller included**: the French VAT key and
  the SIREN Luhn, a GLN's GS1 check digit, an IBAN's mod-97, ISO 3166 countries, and the Peppol
  electronic address scheme list. A mistyped seller identifier fails startup by property name.
- **Seller profile and document configuration** (`einvoice.seller.*`, `einvoice.documents.*`),
  validated at startup against the chosen profile's own rules: the buyer reference XRechnung
  requires, the contact and payment instructions it requires, the electronic addresses Peppol
  requires. Each refusal names the property rather than the value.
- **Golden files for five fixtures** - German, French, Belgian, reverse charge and a credit note -
  compared byte for byte, and rendered again under another locale and time zone to prove the same
  input produces the same bytes anywhere.

- The sample no longer ships a placeholder writer. It receives a signed Stripe test-mode event and
  archives a validator-clean Peppol BIS UBL invoice, then renders and validates an XRechnung for the
  same invoice beside it.
- `SourceInvoice` carries a line quantity and the upstream's per-rate tax treatment, both of which a
  document needs and neither of which could be derived from what was there.

- **The issuance unit of work.** One Stripe event is driven to one archived document or to none,
  with a record either way: a durable inbound record written before any routing decision, an
  authoritative re-fetch of every field a document carries, a totals recomputation compared against
  the upstream's own scalars, the legal number, the exact bytes, a write-once archive write and a
  chained disposition - in four phases whose crash behaviour is stated and tested one test per row.
- **A Stripe webhook endpoint.** The signature is verified over the exact bytes received against a
  keyring, the body is capped by counting as it is read rather than by believing a header, and the
  response contract splits on whether the request is provably from Stripe: 400 for what cannot be
  attributed, 200 for everything recorded - including the refusals - and 503 only when the record
  could not be made durable.
- **An intake state machine** with signature-valid refusals as terminal states that keep their
  bodies and replay once an API version pin is updated.
- **Write-once archiving**, content-addressed, on a filesystem or an S3-compatible store, with the
  store's capability probed at startup by a real conditional write rather than taken on trust.
- **A reconciliation sweep and a compliance findings list**, both in the free core: a finalised
  sale with no document is found and re-enqueued through the same idempotent path, an archived
  document that is missing or no longer hashes to its record is reported, and every finding is
  acknowledgeable with a recorded reason and never deleted.
- **An operational health contributor** in a group of its own, outside `readiness` and `liveness`.
- **A retention for the raw webhook bodies**: nulled the moment an event can no longer run, purged
  at the configured ceiling.
- **`einvoice.numbering.closed-year-cutoff`**, deferred from the previous change: when it is set, a
  late invoice from a fiscal year that closed longer ago is refused and reported rather than
  numbered into a period already declared.
- **The legal numbering series.** Per-seller, per-series, per-fiscal-year, per-mode, with row-lock
  allocation (`UPDATE ... RETURNING`) inside the transaction that inserts the issuance row. One
  Stripe invoice maps to one number for all time, enforced by a unique constraint rather than by
  hoping a redelivery is idempotent upstream.
- **A recorded, chained disposition for every allocated number.** `VOID_UNUSED` with a mandatory,
  screened reason and the failing validation rule id, written to an append-only, hash-chained
  disposition log with an anchor row.
- **The series report**: every allocated number, its disposition, the open count, and both the
  invoice date and the allocation timestamp, so a late invoice from a closing year is explained on
  the page where the question is asked.
- **Database-level protection**: triggers refusing DELETE, TRUNCATE, an UPDATE of any immutable
  column, a second write of a write-once column, an undeclared state transition, and a series
  counter that does not advance by exactly one.
- **A persistence-mapping guard**: startup refuses a host entity, secondary table, join table,
  collection table, `@Subselect` or database view that reaches this module's tables, over every
  `EntityManagerFactory` in the context including lazily created ones.
- **A sample application** with an end-to-end test against PostgreSQL.

### Added

- **An operator can re-run one refused event after correcting the configuration that refused it.**
  A mapping refusal stays final for the pipeline and for the sweeper; what is new is an explicit,
  privileged `IssuanceReprocess` bean the host calls from its own admin action, for one event at a
  time, only from `FAILED_MAPPING`, and never for an invoice that already carries a number. It
  re-opens the row and then runs the ordinary pipeline - intake, routing, the authoritative
  re-fetch, the preflight, the allocator - so it cannot skip a screen. An incomplete seller profile
  is a defect of the application, not of the invoice, and every invoice refused while it was wrong
  would otherwise have needed a new upstream event the seller cannot cause. There is **no HTTP
  endpoint and no actuator operation** for it, by the same reasoning as the void.
- **Every privileged re-open is recorded in a new append-only table, `einvoice_reprocess_request`.**
  A `REQUESTED` row with the operator's actor and reason - separate columns, screened, refused
  rather than shortened - written in the same transaction as the re-open, and a `CONCLUDED` row with
  the outcome or the escaping error code. No update path: a later call appends, it never overwrites.
  A request with no conclusion means a dead process and raises the new finding `DEI-276`. The
  runtime role's `INSERT` also lets a host application append a forged `CONCLUDED` row and so
  suppress `DEI-276` for a request that never finished: that is a detection, not a prevention, and
  it is stated rather than implied. The table
  is append-only against the application role and is **not** hash-chained, which the docs say in
  those words. The earlier plan to record this as an acknowledged compliance finding is dropped with
  it: **`DEI-265` no longer exists**, because an upsert keyed on the subject cannot hold two
  operators' decisions.
- **A legal number burned by a validation or render refusal is now explained inside the hash
  chain.** `FAILED_VALIDATION` appends a chained event carrying the failing rule id or the refusal
  code, in the same transaction as the state change, so the justification for a gap in the issued
  sequence no longer lives only on a mutable row. The chain verifier's cross-check covers those
  rows too, so a burned number invented out of band is reported `BROKEN`. `FAILED_ARCHIVE` is
  deliberately not chained: it is retryable rather than a disposition, and its eventual fate -
  issued or voided - is chained.

- **An invoice this module cannot document is refused before it consumes a legal number.** The
  mapping that screens every buyer-controlled field now runs as a pre-allocation preflight on the
  renderer port (`DocumentRenderer.preflight`), over the render path's own body rather than a second
  list of checks, so a hostile or unmappable field ends the event in `FAILED_MAPPING` with no
  issuance row, no chain entry, no archived object and no advance of the series counter. What still
  costs a number - a rule only the numbered document can be checked against, a renderer or writer
  fault, an archive failure, an upstream void after issuance - is listed in `SECURITY-NOTES.md`. A
  mapping refusal is final; the remedy is a corrected upstream invoice, which arrives as a new event.
- **The profile's own mandatory terms are checked before the number too.** XRechnung's and
  Peppol's presence rules - the buyer's and seller's electronic address, the buyer reference, the
  payment instruction, the seller contact, the buyer's street, city and post code, and both
  parties' VAT identifiers on a reverse-charge supply - used to be enforced only in the writer,
  which runs after the allocator. Two of them are decided by the buyer's own frozen data, so a
  buyer with no VAT identifier passed the preflight and then cost a legal number. They now run in
  the mapper, in the same body both passes share; the writer keeps its copy for a host that calls
  it directly.
- **A number consumed by a renderer or writer fault says so on its row.** It used to be left in
  `NUMBERED` with no recorded reason until the reconciliation sweep's stuck check noticed a count;
  it now records the same disposition a validation refusal does, with the render code where the
  rule id goes, so an operator can find it and void it.
- **A renderer without a preflight is announced rather than assumed.** The port's default answers
  `NOT_SUPPORTED` so that a renderer written before the method existed keeps working; that
  application is told at startup, in a compliance finding raised once per start (`DEI-263`), and on
  every outcome. Two new error codes: `DEI-262` for a preflight that threw, `DEI-263` for a renderer
  that implements none.

- **An XSLT 2.0 processor ships with the module, so a default install validates.** Saxon-HE is a
  runtime dependency of the core module; nothing to add, and no configuration to read first. It is
  MPL-2.0, admitted by the licence gate for that one coordinate and that one licence family, with
  the reasoning and the invariant recorded in `SECURITY-NOTES.md`; a second MPL dependency still
  fails the build. The class-name lookup stays as the "is a processor present" question
  `DocumentValidator.canValidate()` answers and as the seam for substituting another processor.
- **A keyless CVE gate on both sides of a merge.** Every pull request runs OSV-Scanner over the
  transitively resolved dependency graph as a required check, and every release scans the artifacts
  it is about to sign, and their runtime dependencies, with Grype before the signing step. One
  threshold for both: HIGH and above fails, MEDIUM and below is recorded for a decision in the
  release notes, an unrated advisory counts at the threshold, and an unreadable report fails. The
  scanners are pinned binaries verified against recorded checksums, and a report that lists no
  packages at all is a failure, not a clean tree - the pull-request scan states how many packages it
  looked at (380 on this tree today). OWASP Dependency-Check remains
  an optional weekly deep scan that skips loudly when no NVD key is configured.
- **`docs/mandates.md`** - which country requires a structured invoice from when, every date with a
  source URL and the date it was retrieved, and "not confirmed" where an official source could not
  be reached. The per-country table in `docs/documents.md` pointed to the same facts without either,
  which is how such a table goes stale.

### Notes

- PostgreSQL only, and the refusal probes the server rather than a configured dialect string.
- There is no `SEQUENCE`, no `nextval` and no `@GeneratedValue` on any numbering column, asserted by
  a test over the sources and the schema.
- The only HTTP endpoint this module auto-configures is the Stripe webhook controller
  (`POST /webhooks/stripe` by default, `einvoice.webhook.path` to change it), mapped only when the
  intake is wired: a `DocumentRenderer`, a `DocumentValidator` and a `StripeInvoiceSource` are all
  present and `einvoice.issuance.enabled` is not explicitly `false`. It authenticates the caller by
  Stripe signature, not by host security configuration. The privileged reprocess and the numbering
  API open no route of their own; both stay beans the host calls.
- Transaction participation is explicit: the module exposes a `JdbcUnitOfWork` port with a
  caller-supplied-connection factory and a Spring implementation in the starter. There is no
  ambient "current connection" registry, and no application-wide switch that turns joining off.
- JTA and XA are out of scope: a single JDBC `DataSource` is the supported deployment, and a JTA
  transaction manager in the context WARNs at every startup.
