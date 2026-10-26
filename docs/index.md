# The legal numbering series

The number this module puts on a document is the legal one (BT-1). Stripe's own `invoice.number` is
stored beside it and printed as a reference, and it is never the legal number: it is prefixed per
customer, holed by drafts, failed finalisations and voids, and its format is Stripe's to change.

## The property, and the word that is not used

For one `(seller, document type, series, fiscal year, mode)`:

- no two issued documents share a number;
- **every allocated number has a recorded, chained disposition** - issued, or voided with a reason;
- one Stripe invoice maps to one number for all time, on every path.

The phrase "gap-free" is not used, and the reason is worth a paragraph. Validating the exact bytes
that get archived is a property this module can prove; never leaving an unused number is one it
cannot, because a document that fails schematron after a number was allocated cannot be re-rendered
under a different number without breaking the first property. So the residue is handled instead of
hidden: an operator voids the number with a mandatory reason and the failing rule id, the event is
hash-chained, and the series report shows it. A hole with a chained reason and a hole with no
explanation must never look alike to the person auditing the series.

## How allocation works

One transaction, in this order:

1. `SELECT ... FOR UPDATE` on `(seller, mode, stripe invoice id)`. A row in any state returns its
   existing number, with the dates it was given - **resume, never re-allocate**.
2. A first read of `pg_catalog.clock_timestamp()` picks the fiscal year of a resetting series.
3. `INSERT INTO einvoice_series ... ON CONFLICT DO NOTHING`, which opens a new fiscal year's row if
   this is the first invoice of the year. The start value is 1 and is not a free parameter, so
   concurrent creators agree by construction.
4. `SELECT next_number, last_issue_date, prefix, width ... FOR UPDATE` on the series row: the row
   lock, held until commit, so a second transaction blocks here and then reads the committed value.
5. A second read of `pg_catalog.clock_timestamp()`, **after** the lock. Java turns it into a date
   once, in the seller's tax zone: that date is the issue date (BT-2). See "The issue date is the
   numbering day" below.
6. `UPDATE einvoice_series SET next_number = next_number + 1, last_issue_date =
   GREATEST(last_issue_date, <BT-2>) ... AND next_number <= <max for the width> AND
   (last_issue_date IS NULL AND next_number = 1 OR last_issue_date <= <BT-2>) RETURNING
   next_number - 1`.
7. `INSERT INTO einvoice_issuance`, state `NUMBERED`, with the issue date, the supply date and the
   previous number. The unique constraint is the backstop when two callers missed step 1 together:
   the loser's counter increment rolls back with its insert, so there is no gap, and it re-reads
   the winner's number.

A rollback, a crash, or a killed backend all leave the counter where it was - the counter is
ordinary row state, not a sequence. A PostgreSQL `SEQUENCE` is deliberately non-transactional, which
is exactly why one is not used here and why a test asserts none appears.

## The issue date is the numbering day

From 0.2.0 the issue date (BT-2) of an invoice is the day its number is given: the database clock,
read after the series row lock, turned into a date in the seller profile's **required**
`tax-zone`. The day of the sale travels beside it as the **supply date**: the delivery date
(BT-72, `cac:Delivery/cbc:ActualDeliveryDate`) is the day of Stripe's
`status_transitions.finalized_at`, or, when every line of the invoice names the same Stripe service
period, the invoicing period (BG-14, `cac:InvoicePeriod`) is written instead. The delivery date is
a proxy: it is the day Stripe finalised the invoice, which for most sales is the day of the sale.

Why: numbers follow the order in which work reaches the series lock, and that order is not the
order of finalisation. A retry, a park, a reprocess, the reconciliation sweep or a late webhook
could give an invoice finalised on 3 March a number after an invoice finalised on 10 March. Up to
0.1.x that invoice carried 3 March, so the dates of one series went backwards. Dated on the day its
number is given, a later number can no longer carry an earlier date. Example: an invoice finalised
on 10 March, parked, and numbered on 12 March is dated 12 March, carries 10 March as its delivery
date, and records the cause `PARK`.

Every invoice records, on its row and on its chained events: Stripe's `finalized_at`, the
allocation instant, the issue date, the supply date or period, the previous number of the series
and its issue date, and a cause for any day between supply and issue - `NONE`, `REPROCESS`,
`SWEEP`, `PARK`, `RETRY`, `LATE_WEBHOOK`, or `UNRECORDED` when no stored history explains it (a
worker queue past midnight, or a host that allocated directly). The cause is derived from stored
history only; no caller can pass one. More than one day between supply and issue raises the
finding `DEI-137`, which names the number; it is not a refusal. Lateness is counted from the day
Stripe finalised the invoice, also when an invoicing period is written: Stripe bills a
subscription at the start of its period, so an invoice for 1 January to 1 February finalised on
1 January and numbered on 20 January is 19 days late.

### When the clock cannot be trusted

The only refusals left are about the clock. None gives a number, and none can be released by hand:

- `DEI-132`: the database clock is behind the series' latest issue date (it stepped back), behind
  the invoice's own finalisation time, or more than `einvoice.numbering.max-clock-regression`
  (default 5 minutes, at most 1 hour) apart from the application clock. Two independent clocks must
  agree before a legal date is written. A small gap is retried with the normal backoff; a gap of
  more than `max-clock-regression` raises a finding that names the series; a gap of more than one
  day from the application clock, or from Stripe's `finalized_at`, stops the event in
  `FAILED_ISSUANCE` with no retry. More than one day behind the series is `DEI-130`, below. Remedy: fix the clock (or the tax zone),
  then run the event again through `IssuanceReprocess`, which accepts this one `FAILED_ISSUANCE`
  code for that purpose. The re-run goes through the same checks.
- `DEI-130`: a series' latest issue date is more than one day after today on the database clock,
  in the tax zone. Two causes put it there: both clocks stepped back together (one wrong time
  source for the database and application hosts, which the cross-check cannot see), or a hand edit
  or a forward jump of the clock. Refused at startup and before every allocation, with a finding
  that names the series. Remedy, in this order: first check the database and application clocks
  against an external time source (NTP); if they are wrong, fix them and retry, and do not touch
  the series - lowering its date while the clocks are wrong would date new invoices before ones
  already issued. Only if both clocks are right, look in the issuance chain for who moved the
  series row, then correct `last_issue_date` as the schema owner with a recorded reason. The
  runtime role cannot do it; the series guard refuses a backward move.
- `DEI-131`: the fiscal year changed between the read that picked the series and the read under its
  lock (a few seconds once a year). The worker retries in a new transaction, which picks the new
  year's series. A host that allocates inside its own transaction gets `DEI-131` as a typed
  exception: roll back and retry in a new transaction.

A series with numbered rows and no latest issue date refuses startup and allocation with `DEI-134`.

The production statement is `SELECT pg_catalog.clock_timestamp()`, schema-qualified and with no
wrapper function: a role that may create a function earlier on the search path cannot redefine an
invoice's legal date. It is never `now()`, which is the start of the transaction.

## Fiscal years, and the invoice that arrives late

The fiscal year of a resetting series is the year of the issue date, so of the numbering day. An
invoice finalised on 31 December and numbered on 2 January is dated 2 January, takes a number from
the **new** year's series, and carries 31 December as its supply date. The 0.1.x behaviour, where
that invoice took a number from the closing year's series after new-year numbers existed, no longer
happens.

Whether to refuse a late invoice from a closed year after a cut-off is the seller's accounting
policy, not this module's. `einvoice.numbering.closed-year-cutoff` applies to the **supply** date:
a sale from a year that closed longer ago than the cut-off is still refused (`DEI-207`).

## Document types: one series per type

Every numbered row belongs to exactly one **typed** series: the document type (BT-3, `380` for an
invoice, `381` for a credit note) is part of the series key, bound when the series row is created,
and immutable after that. One series name serves one type for a seller and mode, in every fiscal
year, and a legal number is unique per seller and mode across all series.

The binding of a name to its type lives in its own table, `einvoice_series_name`, one row per
seller, mode and series name. Every series row points at it through a foreign key that includes the
type. So two writers that create one name at the same instant, one as an invoice series and one as
a credit-note series, cannot both commit, whatever their isolation level. The loser gets `DEI-100`
naming the series at READ COMMITTED, and `DEI-117` (retryable) at REPEATABLE READ or SERIALIZABLE,
where PostgreSQL aborts its transaction. If your own transaction joined the allocator, retry the
whole transaction on `DEI-117`.

This edition issues invoices only. The allocator refuses any other type with `DEI-123`, before it
locks anything, because a credit-note number with no credit note behind it would be a hole in a
legal series. The format is ready for credit notes so that adding them later changes no schema.

Credit notes are numbered in their own series, each series continuous in itself. The VAT rules of
France, Germany and Belgium all allow numbering in one or more series (Directive 2006/112/EC art.
226(2); CGI annexe II art. 242 nonies A, 7°; UStG § 14(4) Nr. 4; AR n° 1 art. 5, § 1er, 2°). We
separate by document type because it is what auditors and accounting packages expect, not because
a text requires it.

A credit-note row must name the invoice it corrects (`corrects_issuance_id`), and an invoice row
names none: a `CHECK` constraint says so. The database refuses a target that is not an invoice of
the same seller and mode that this module issued, or whose stored document hash no longer matches
its chained issue event (`DEI-128` on the allocator's own path). The link cannot change after the
row is written, and the chained event of a credit note records the corrected invoice's seller,
mode, series, fiscal year and legal number, not only its row id.

### The latest issue date of a series

Each series row records the latest BT-2 it numbered (`last_issue_date`). It moves only in the
statement that moves the counter, and never backwards. A credit note dated before it is refused
(`DEI-127`). An invoice cannot be dated before it: its issue date is the numbering day, read under
the same lock (see "The issue date is the numbering day").

## The series report

Per `(seller, series, fiscal year, mode)`, every allocated number with:

- its disposition: `ISSUED`, `VOID_UNUSED` with its reason and rule id, `FAILED_VALIDATION` with
  its rule id, or still open (`NUMBERED`, `ARCHIVING`, `FAILED_ARCHIVE`);
- the issue date (BT-2), the supply date or period, the allocation timestamp, and the cause of any
  day between supply and issue;
- the Stripe invoice id it is bound to.

Plus the open count and the next number the series will hand out. `contiguous()` says whether the
enumeration itself has no hole, which is a different question from whether every number carries a
document, and both belong on the page.

## Voiding a number

`IssuanceVoidService.voidUnused(stripeInvoiceId, reason, ruleId)`.

- **Privileged, and with no HTTP endpoint anywhere in this module.** Expose it yourself, behind your
  own authorization, or not at all.
- Legal only from `NUMBERED`, `ARCHIVING`, `FAILED_ARCHIVE` or `FAILED_VALIDATION`. From `ISSUED` it is refused twice:
  by the state enum and by the database trigger.
- The reason is mandatory, screened (NFC, no control characters beyond tab/newline/carriage return,
  no unpaired surrogates, not blank after collapsing, bounded at 500 characters) and length-bounded.
- Nothing is deleted and the number is never re-allocated.
- The void is chained. So is a number burned by a validation or render refusal
  (`FAILED_VALIDATION`), which appends a chained event carrying the failing rule id or the refusal
  code: the gap it leaves in the issued sequence is explained inside the tamper-evident record and
  not only on the issuance row. `FAILED_ARCHIVE` is not chained - it is retryable, and its eventual
  disposition is.

## Reprocessing a refused event

`IssuanceReprocess.reprocess(new ReprocessRequest(eventId, actor, reason))`.

A mapping refusal is final for the pipeline: a finalised invoice's fields are frozen, so re-running
cannot change the verdict, and the sweeper never re-picks one. An *incomplete seller profile* or a
wrong rule pack is a different thing - a defect of your application, not of the invoice - and the
invoices refused while it was wrong would otherwise need new upstream events your seller cannot
cause. So the remedy is this explicit, privileged call, for one event at a time.

- **Privileged, and with no HTTP endpoint and no actuator operation anywhere in this module**, for
  the same reason the void has none. Call it from your own admin action, behind your own
  authorization.
- Eligible only from `FAILED_MAPPING`. Every other state, including a passed event and a second
  call after a successful reprocess, is answered `REFUSED_NOT_ELIGIBLE` with the recorded state and
  number, and nothing runs.
- Refused outright if a legal number already exists for that invoice: this path never touches a
  numbered invoice.
- It re-opens the row and then runs the ordinary pipeline. It cannot skip the preflight, and a
  re-run that refuses again costs nothing, exactly as the first run did.
- Two simultaneous calls on one event produce one run and one number.
- It takes one of the shared run permits first, and refuses at once with `DEI-102` when all of them
  are in use: it runs on your thread, so it never waits. It then takes the invoice's run lock
  **before** the re-open. While another run of the same invoice is live it answers
  `REFUSED_RUN_IN_PROGRESS` (`DEI-217`) and records nothing; call again later.
- Call it **outside a transaction**. Inside one (a `@Transactional` method, a `TransactionTemplate`,
  a connection you hand over) it is refused with `DEI-218 RUN_IN_HOST_TRANSACTION` before the run
  lock is tried, and records nothing. A run never joins a caller's transaction.
- `actor` and `reason` are mandatory, screened and **refused rather than shortened** if they are
  over their bound (64 and 500 characters). They are never concatenated: the record holds them as
  two columns.
- Every call writes to `einvoice_reprocess_request`: a `REQUESTED` row **in the same transaction as
  the re-open itself**, so an event running again is always attributed, and a `CONCLUDED` row when
  the run ends - with the outcome, or with the code of an exception that escaped. A `REQUESTED`
  with no `CONCLUDED` therefore means one thing: the process died mid-run, and the reconciliation
  sweep raises `DEI-276` for it.
- **What that table is, exactly.** It is append-only against the application role: `SELECT` and
  `INSERT` only, with triggers refusing `UPDATE`, `DELETE` and `TRUNCATE`, one conclusion per
  request enforced by a partial unique index, and the two length bounds enforced by `CHECK`
  constraints beside the in-code screen. It is **not hash-chained**: a role that owns the schema can
  disable the trigger and alter a row, and no verifier in this module will report that. The
  issuance chain is the tamper-evident record; this table is not.

## Properties

| Property | Default | Weaker mode |
|---|---|---|
| `einvoice.mode` | `live` | `test` - WARNs at every startup, separate series row |
| `einvoice.initialize-schema` | `true` | `false` when the host owns the migrations |
| `einvoice.seller.id` | required | none |
| `einvoice.seller.tax-zone` | required, no default | none |
| `einvoice.numbering.series` | `DEFAULT` | an unconfigured series is a refusal, never auto-created |
| `einvoice.numbering.prefix` | required, `[A-Z0-9-]{1,32}`, optionally with the literal `{fiscalYear}` placeholder | none; a resetting series without the placeholder is refused at startup |
| `einvoice.numbering.width` | `6` | 4..12; outside that the application refuses to start |
| `einvoice.numbering.fiscal-year-reset` | `true` | `false` - WARNs at every startup |
| `einvoice.numbering.allocation-timeout` | `5s` | applied as `SET LOCAL lock_timeout`; a blocked allocation past this bound is a typed `DEI-117` refusal |
| `einvoice.numbering.max-clock-regression` | `5m` | above zero and at most `1h`, refused at startup outside; how far the database clock may run behind a series or apart from the application clock before a refused allocation raises a finding (`DEI-132`). A value above `5m` WARNs at every startup: whoever controls only the database clock can then move an issue date by up to that much |
| `einvoice.chain.hmac-secret` / `.hmac-key-id` | required, env only, base64 of >= 32 bytes | none |
| `einvoice.chain.hmac-keys.<id>` | empty | retired ids the verifier still needs during a rotation |
| `einvoice.chain.unkeyed` | `false` | `true` - WARNs at every startup, verifier reports `INTACT_UNKEYED` |

No property in this module disables a uniqueness constraint, a trigger, the resume-by-source-id
behaviour or the chain.

## The database

Four tables: `einvoice_series` (the counter), `einvoice_issuance` (the mapping row, append plus a
state column), `einvoice_issuance_event` (the append-only chained disposition log) and
`einvoice_issuance_anchor`. A fifth, `einvoice_schema`, holds one row: the schema format version.
The starter reads it at every boot. Only "the table does not exist" means the schema is absent;
any other error stops startup with `DEI-133`, and a newer format stops it with `DEI-135`. The
runtime role gets `SELECT` on it and nothing else.

### Upgrading a 0.1.x database to format 2

0.2.0 brings format 2: the document type, the corrected-invoice link, the latest issue date, the
version row and the new constraints. The bundled `schema-postgresql.sql` upgrades a 0.1.x schema
in place. It rewrites no number, no hash and no archived byte.

1. **Stop every instance of 0.1.x.** Mixed versions are not supported. A 0.1.x binary cannot write
   a number against format 2 (its inserts fail on the new `NOT NULL` column, inside the allocation
   transaction, so its counter rolls back). Its chain verifier would report BROKEN on rows written
   in the new form. That verdict is meaningless; never act on it. Findings and inbound purges of a
   0.1.x binary are not refused, because those tables gain no column.
2. **Run the script once as the schema owner**, either by starting 0.2.0 with
   `einvoice.initialize-schema=true` and an owner-capable role, or by hand with this one command:

   ```
   PGOPTIONS='-c einvoice.tax_zone=Europe/Paris' psql -X -v ON_ERROR_STOP=1 --single-transaction \
     -f schema-postgresql.sql
   ```

   Put your own region id in place of `Europe/Paris`. It must be `UTC` or a region id with a slash
   that PostgreSQL lists (`Europe/Paris`, `America/New_York`). An offset such as `+02:00` is
   refused, because PostgreSQL reads its sign the other way round. A legacy name such as `CET` is
   refused too, because PostgreSQL and the JVM read it differently. The upgrade records each series' latest issue date as a date
   in that zone, and it refuses (`DEI-134`) rather than guess when the zone is missing. The starter
   sets it from `einvoice.seller.tax-zone`. Why each flag: `-X` skips your `~/.psqlrc`, which could
   change the session. `ON_ERROR_STOP=1` stops at the first error; without it psql prints the error
   and carries on. `--single-transaction` makes the run all or nothing, so a refusal changes nothing.
   The last step of the script also checks every format-2 condition before it stamps the version and
   raises `DEI-134` if one is missing, so a run that went wrong in some other way is still not
   accepted as format 2.

   PostgreSQL and the JVM ship separate time-zone data. If your zone changed its rules between the
   two versions, a series' backfilled date can be one day off for an invoice near midnight.
   Example: `America/Vancouver`, an invoice at 2026-11-05T07:30Z. A JVM with tzdb 2025b says
   4 November. PostgreSQL with tzdata 2026b keeps summer time all year and says 5 November. To
   check, compare `SELECT (max(issued_at) AT TIME ZONE 'America/Vancouver')::date FROM
   einvoice_issuance WHERE series = '<series>'` with the date on that series' last document.
3. **Expect a short stop.** The upgrade adds columns, rebuilds the series primary key and builds
   unique indexes in one transaction. Every issuance waits until it commits. It runs under the
   module's advisory lock, so two instances starting together run it one after the other, and an
   interrupted run leaves the 0.1.x schema unchanged.
4. **Then start 0.2.0 as the runtime role** with the grants in `docs/schema-grants.sql` (it now
   includes `SELECT` on `einvoice_schema`). With `einvoice.initialize-schema=false`, a schema that
   was not upgraded stops startup with `DEI-136`.

The upgrade refuses, by name and with no change, when existing rows break a format-2 rule: two rows
of one seller and mode with the same legal number (`EIU01`), or one series name bound to two
document types (`EIU04`). Both surface as `DEI-134`, with a count and no identifiers. A 0.1.x
database cannot hit `EIU04`, because every row there is an invoice. If you see it, the database was
written by an unreleased build and binds a series name to two document types; rebuild it, or restore
its 0.1.x copy, then start again.

Run the application as a role that does **not** own them - `docs/schema-grants.sql` has the grants.
The triggers refuse the runtime role; nothing refuses a table owner, who can disable them, so the
grant separation is the first line and the chain is what detects a rewrite afterwards.

This module maps none of these tables with JPA, deliberately: with no entity there is no `find`, no
derived query, no JPQL, no projection, no lazy attribute, no first- or second-level cache and no
dirty checking to reason about. A startup guard keeps that true by refusing to start if a host
entity, secondary table, join table, collection table, `@Subselect` or database view reaches them.

---

# The issuance unit of work

One Stripe event becomes one archived document, or none, and there is a record either way.

## The path, and where each refusal lands

| Step | What it does | What a refusal leaves |
|---|---|---|
| Intake | Content type, body cap counted as it is read, HMAC over the exact bytes against the keyring, identity read with a strict reader | 400 and nothing written: we cannot attribute it |
| Record | One row in `einvoice_inbound_event` with the raw body, before any decision | 503 only when the database is unreachable |
| Route | API version against the pin, `livemode` against `einvoice.mode`, `account` against the configured seller | A terminal `REFUSED_*` state, answered 200, replayable |
| Fetch | `GET /v1/invoices/{id}`, lines paginated to exhaustion, tax rates by id | `FAILED_FETCH`, retried by the sweeper up to the ceiling |
| Map | Totals recomputed and compared, issue date derived in the seller's tax zone, closed-year cut-off | `FAILED_MAPPING` or `FAILED_TOTALS`, final: they need a human |
| Preflight | The render path's own mapping, run with no number in existence: every screened field, every required term, every balance rule | `FAILED_MAPPING` with the mapper's code, and no issuance row, chain entry, archived object or advance of the counter. Final for the pipeline and the sweeper: a finalised invoice's fields are frozen, so the remedy is a corrected invoice and therefore a new event - or, when the defect was your configuration, the privileged `IssuanceReprocess` |
| P1 claim | The legal number, in one transaction with the issuance row | Nothing: every data refusal already happened |
| P2 render | The exact bytes, then validation of those exact bytes | `FAILED_VALIDATION` on the issuance row and a chained event carrying the rule id, for the operator's void |
| P3 write | `ARCHIVING` with the hash and key, then the write-once PUT | `FAILED_ARCHIVE`; an outage is retried, a content conflict is not |
| Resume | A row in `ARCHIVING` or `FAILED_ARCHIVE` with a recorded hash reads its own key before it renders | Same bytes: `ISSUED`, no render. Other bytes: `DEI-240`, not retried. Read failure: `FAILED_ARCHIVE`, retried. No object: P2 again, and a refusal is `FAILED_VALIDATION` with the first hash kept on the row |
| P4 issue | `ISSUED` and the chained disposition, one transaction | Back to P3 on the retry, byte-identical, no second number |

## Replaying a refused event

An API version skew is Stripe's move, not yours, and it applies to every event on the account at
once. The refused events are on `einvoice_inbound_event` with their bodies intact and
`last_code = DEI-201`.

1. Upgrade the module (the pin is the SDK's own version and is refused if configuration names
   another).
2. Restart. The sweeper's due query re-picks exactly the `REFUSED_VERSION_SKEW` rows whose own
   recorded `api_version` now equals the pin this application starts under, and drives them through
   the same path. `REFUSED_MODE` and `REFUSED_ACCOUNT` are never re-picked this way - neither is
   cured by an upgrade, and re-running either silently would be worse than leaving it for an
   operator to look at.
3. Watch the findings list empty out.

## Reconciliation, health and findings

The sweep runs every `einvoice.reconcile.interval` and closes four directions: a finalised Stripe
invoice with no issued document (re-enqueued through the same idempotent path, never issued
inline), an `ISSUED` row whose object is missing, an archived document that no longer hashes to its
record, an archive object no row predicted, and a number open past `einvoice.issuance.alert-after`.
It **alerts and never repairs**.

Two signals, and the difference matters:

- **Health** (`/actuator/health/einvoice`) carries operational conditions only - a silent sweeper, a
  stale reconciliation, a chain that does not verify. It is in a group of its own, outside
  `readiness` and `liveness`, because a business condition three weeks old must never take your
  application out of the load balancer.
- **Findings** (`/actuator/einvoicefindings`) carry the business conditions: counts, codes and
  subjects. Each is acknowledgeable through `IssuanceFindingService` with a reason that is recorded
  and never deletes the finding.

## Properties

| Property | Default | Weaker mode |
|---|---|---|
| `einvoice.stripe.webhook-secrets.<id>` | required, env only | none |
| `einvoice.stripe.api-key` | required for the bundled source, env only | none |
| `einvoice.stripe.require-restricted-key` | `true` | `false` - WARNs at every startup |
| `einvoice.stripe.telemetry` | `false` | `true` - WARNs at every startup |
| `einvoice.stripe.api-version` | the SDK's own | none; naming another is refused at startup |
| `einvoice.webhook.path` | `/webhooks/stripe` | - |
| `einvoice.webhook.max-body-bytes` | `1048576` | up to 8 MiB |
| `einvoice.webhook.tolerance` | `300s` | bounded above at 10 minutes; larger is refused |
| `einvoice.archive.type` / `.root` | `filesystem`, root required | supply your own `ArchiveStore` |
| `einvoice.archive.allow-non-atomic-store` | `false` | `true` - WARNs at every startup, read-back only |
| `einvoice.issuance.concurrency` / `.queue-capacity` | `2` / `1000` | - ; the pool must hold `3 x concurrency + 2` connections, refused at startup otherwise |
| `einvoice.issuance.retry-ceiling` | `72h` | - |
| `einvoice.issuance.alert-after` | `6h` | - |
| `einvoice.inbound.retention` | `30d` | longer is allowed and is a PII retention decision |
| `einvoice.reconcile.interval` / `.window` / `.grace` | `15m` / `2d` / `15m` | `enabled=false` removes the only watcher there is |
| `einvoice.reconcile.drift-sample` | `10` | `0` stops re-hashing archived documents |
| `einvoice.numbering.closed-year-cutoff` | unset | setting it refuses invoices whose supply date falls in a year closed longer ago than this |
| `einvoice.rule-pack-version` | `unversioned` | - |

Nothing here disables signature verification, validation before archiving, the totals comparison or
write-once archiving.

## The database, continued

### One run per invoice: what the server needs

Each live run holds a session advisory lock on its invoice (`einvoice.run_lock`) on one connection
for its whole length. Three server settings decide how fast a dead run's lock goes:

- `tcp_keepalives_idle = 60`, `tcp_keepalives_interval = 10`, `tcp_keepalives_count = 6`. A node
  that loses power cannot close its sessions; only the server's keepalive ends them. Example: a
  node dies at 10:00 while it runs `in_1`. With operating-system defaults `in_1` is busy until about
  12:11; with these settings the 10:03 sweep runs it.
- PgBouncer in session mode: keep `server_reset_query = DISCARD ALL` (its default). Emptied, a lock
  outlives its client on the server connection, and the invoice stays busy until `server_lifetime`.
- A network that drops connections idle for a few minutes ends a long run's session; the run stops
  with `DEI-104` at WARN and is retried after its backoff. The keepalive settings above keep the
  connection from looking idle.

Two more tables: `einvoice_inbound_event` (the durable intake record - purgeable, not append-only,
and the one table the runtime role may delete from) and `einvoice_finding` (the compliance findings
list). `docs/schema-grants.sql` has both, with the reason the intake table is treated differently.
