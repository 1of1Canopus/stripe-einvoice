# stripe-einvoice

**EN 16931 e-invoices from Stripe Billing, for Spring Boot applications.**

Germany (issuing from 1 January 2027 above an EUR 800 000 turnover, 2028 below it), Belgium (since
1 January 2026) and France (reception by all businesses and issuing by large firms and ETI since
1 September 2026, PME and micro-entreprises from 1 September 2027; see `docs/mandates.md`) require
invoices as structured data.
Stripe Invoicing does not produce it, and Stripe's own documentation tells you to install a
marketplace app or write the mapping yourself. Java shops on Stripe have had nothing.

Every date in that sentence is sourced and dated in [`docs/mandates.md`](docs/mandates.md); one that
could not be confirmed against an official source is marked "not confirmed" there rather than
repeated here.

> **Status: 0.1.0 is on Maven Central.** The API may still change before 1.0; see CHANGELOG.md.
> Three pieces are in place: the legal numbering series, the issuance unit of work that drives one
> Stripe event to one archived document or to none, and the EN 16931 documents themselves -
> XRechnung 3.0 and Peppol BIS Billing 3.0 in UBL, judged by the official validator artefacts.

## What the documents are

One Stripe invoice becomes one EN 16931 document, written by this module and **judged by the
official rules before it is archived**.

| | XRechnung 3.0 (UBL) | Peppol BIS Billing 3.0 (UBL) |
|---|---|---|
| `einvoice.documents.profile` | `xrechnung-ubl` | `peppol-bis-ubl` |
| Specification identifier (BT-24) | `urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0` | `urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0` |
| Rules run | CEN EN 16931 schematron, then the CIUS XRechnung rules (`BR-DE-*`) | CEN EN 16931 schematron as OpenPeppol compiles it, then `PEPPOL-EN16931-*` including the code lists |
| Artefacts | KoSIT validator configuration for XRechnung, release `v2026-08-31` | OpenPeppol release `2026.5` |

The artefacts are **vendored** with a SHA-256 each, recorded in
`stripe-einvoice-core/src/main/resources/com/housedevinci/einvoice/reference/CHECKSUMS.txt`, with a
provenance note per file.
Nothing is downloaded at build time or at run time, every checksum is recomputed on every build,
and the checksum of a stylesheet is recomputed again before it is compiled at run time. A
stylesheet is executable code, and these are run over documents that go to a tax authority.

What the writer holds to:

| Property | How |
|---|---|
| The bytes that were validated are the bytes that are archived | One byte array: rendered, validated, hashed, stored. Never a re-serialisation of a model |
| The same invoice renders the same bytes anywhere | Canonical XML 1.1 written directly, no clock, no default zone, no default locale. The determinism test renders each fixture again under a Thai-digit locale and a time zone across the date line and compares bytes |
| A buyer's string cannot become markup | One canonical writer that escapes, and one screening function that refuses what escaping cannot make safe: C0 controls, unpaired surrogates, bidi overrides, a value that collapses to blank, anything past the business term's own length bound |
| A wrong number cannot hide behind a valid document | Every EN 16931 balance rule (`BR-CO-10`, `-13`, `-14`, `-15`, `-16`) is an invariant of the model, so an unbalanced document cannot be constructed, let alone written |
| An identifier is checked, not assumed | The French VAT key and the SIREN's Luhn, a GLN's GS1 check digit, an IBAN's mod-97, ISO 3166 for countries, the Peppol EAS list for electronic addresses - for **both** parties, the configured seller included |
| A tax position is never inferred | The category comes from Stripe's own taxability reason through a versioned rule pack, which checks and never overrides; a zero rate with no VATEX reason, or a reverse charge whose parties contradict it, is a refusal naming the Stripe field |
| An exponent is never assumed | An explicit currency table with Stripe's minor-unit exponent beside ISO 4217's presentation exponent. `JPY 10000` stays ten thousand; `HUF 1000` is charged as an integer and written with two decimals; an unlisted currency is refused |

### The XSLT processor

The EN 16931, XRechnung and Peppol schematron are **XSLT 2.0** and the JDK ships an XSLT 1.0
processor only, so this module ships one: `net.sf.saxon:Saxon-HE`, a runtime dependency of the
core module. **You add nothing; validation works on a default install.**

Saxon-HE is MPL-2.0 - file-level copyleft over Saxon's own files, which are used unmodified and
are not redistributed here. It is the only dependency of this project under that licence, and the
licence gate admits it by coordinate, not by licence: a second MPL dependency fails the build.

The processor is still reached **by class name** through JAXP (`einvoice.documents.xslt-processor`,
default `net.sf.saxon.TransformerFactoryImpl`) and no source file imports a Saxon type, so you can
substitute another XSLT 2.0 processor by setting that property and excluding the dependency.

If you do exclude it and put nothing in its place, **this module issues nothing**: every validation
reports `NOT_EVALUATED`, which the issuance unit of work treats as a refusal before a legal number
is allocated, and an application configured to receive Stripe events refuses to start rather than
spend a number per invoice on a document nothing will read. An unevaluated rule is not a passed
rule, and archiving a document no rule ever read would be worse than issuing none.

## What the numbering series does

A per-seller legal numbering series in which **every allocated number has a recorded, chained
disposition** - it is either on an issued document, or it is voided with a reason that is written
down and hash-chained.

That is deliberately not the phrase "gap-free". Gap-freeness and byte-determinism cannot both be
absolute, and this module chooses the property it can prove: the bytes that pass the validator are
exactly the bytes that are archived, so a document that fails validation after a number was
allocated leaves the number recorded and voided rather than quietly re-used under different bytes.
An unexplained hole and a hole with a chained reason must never look alike to an auditor, and the
series report is written for that reader.

What the series holds to today:

| Property | How |
|---|---|
| One Stripe invoice, one number, for all time | A unique constraint on `(seller, mode, stripe invoice id)`; a retry resumes onto the existing number instead of allocating a second one |
| A rollback or a crash never consumes a number | Row-lock allocation (`UPDATE ... RETURNING`) inside the transaction that inserts the issuance row. No `SEQUENCE`, no `nextval`, no `@GeneratedValue` - those are non-transactional by design |
| Your rollback is our rollback | An allocation made inside your `@Transactional` method or `TransactionTemplate` joins that transaction and is rolled back with it; reads join it too, so you can read back the number you just allocated. Outside a transaction, the allocation commits on its own, as before. To keep a number across your own rollback, allocate in a `@Transactional(propagation = REQUIRES_NEW)` method - that is the only way to ask for it |
| A test-mode event never touches the live series | The mode is part of the series key, not a filter |
| Invoicing does not stop on 1 January | A new fiscal year's series row is opened inside the allocation transaction, so an application last restarted in November keeps working |
| The series cannot silently change format | The counter is refused at the last number the configured width can render |
| The ledger cannot be rewritten by the application that writes it | Database triggers refuse DELETE, TRUNCATE, an UPDATE of any immutable column, a write-once column's second write, and any undeclared state transition |
| A rewrite by a role that outranks the triggers is still detectable | A keyed (HMAC) hash chain over the disposition log, with an anchor row, and a cross-check that a disposed issuance row has a chained event agreeing with it |
| No JPA mapping of ours, and none of the host's over our tables | The module ships no entity; a startup guard refuses a host entity, secondary table, join table, collection table, `@Subselect` or database view that reaches these tables |

## What the issuance unit of work does

One sale becomes one legal document, or none, and there is a record either way.

```
Stripe  ->  POST /webhooks/stripe  ->  durable record  ->  worker  ->  document in the archive
            (signature over the                              |
             exact bytes)                                    +-> a refusal, with its reason, on the row
```

The order is the design. The verified event is **recorded before any decision is taken about it**,
and the endpoint answers 200. A refusal that is nevertheless provably from Stripe - an API version
skew, a test-mode event in a live application, an account that resolves to no configured seller -
is a terminal state on that row with its own error code, not a 400. Stripe treats a 400 as a failed
delivery and disables endpoints that keep failing, and a version skew applies to every event on the
account at once: a control that refuses one bad event must not be able to stop the whole intake.
Once the pin is updated to match what an event actually carried, the sweeper re-picks that
recorded row and it replays through the same path; `REFUSED_MODE` and `REFUSED_ACCOUNT` are never
re-picked automatically, because neither is cured by an upgrade.

| Property | How |
|---|---|
| The webhook payload never reaches a document | Only `(event id, type, api version, livemode, account, object id)` are read from the body. Every field a document carries is re-fetched from the Stripe API, with the lines paginated to exhaustion; a residual "more pages" is a refusal, never a partial document |
| A document carries the buyer as at the issue date | Only the fields Stripe froze onto the invoice at finalisation are mapped. The live customer object is neither read nor expanded, so a buyer who corrects their address in March does not change what a January invoice says |
| The totals on the document are ours, and they agree with Stripe's | Every line sum and every per-rate tax bucket is recomputed and compared. A difference of one cent in one bucket is a refusal that names the field, never an adjustment |
| A refusal consumes no number | Every refusal that depends on data happens before the allocator is called |
| One number never names two documents | The archive key carries the SHA-256 of the exact bytes and the store is write-once; the same input renders the same bytes under any time zone and any locale |
| A crash leaves something a retry can finish | Four phases, each with a recorded state: claim, render, write (the row predicts the object before the PUT), issue (state and chained disposition in one transaction). A disposition a retry cannot finish - a burned number, a voided one - is recorded with its code instead of being retried |
| A resumed run never burns a number whose document exists | A row that recorded its hash reads its own archive key before it renders again. Same bytes: the number is issued, with no render. Other bytes: a content conflict (`DEI-240`). The read fails: retried later. No object: the run renders, validates and checks dates again, and a refusal burns the number with its rule id. New bytes that pass but differ from the recorded hash or key: the number burns with `DEI-244`, nothing is archived |
| A store that quietly overwrites is refused | At startup, with a real conditional write against a scratch key - not on the strength of a capability flag |
| A sale with no document is noticed | A reconciliation sweep lists what Stripe finalised and re-enqueues anything with no issued document **and no recorded reason for it**, through the same idempotent path. A number that was burned or voided already has its reason, chained: it is reported under its own code and never re-enqueued. The sweep alerts and never repairs |
| A redelivery returns the stored document | After checking the archived bytes against the recorded hash. A tampered archive is refused; a legitimate upstream edit is not |
| Back-pressure costs latency, never an event | A bounded queue and a concurrency limit below your connection pool; past capacity the event stays `RECEIVED` and the sweeper takes it |
| Buyer data has a retention | The raw signed bodies are nulled the moment an event can no longer run, and purged at `einvoice.inbound.retention`. One exception: the row of an invoice whose number is still open (numbered, archiving or failed archive) is kept until the number resolves, because the reprocess needs it to resume that number |
| No event is due for ever | A row that stays `RECEIVED`, `FETCHED` or `MAPPED` for `einvoice.issuance.running-ceiling` (default 72 hours) without progress, or `PARKED` for `parked-ceiling` (default 90 days), becomes `EXPIRED` (`DEI-266`) with the finding `DEI-282` on its event id, once. It is no longer re-run. A zero, a negative, or a limit shorter than the retry backoff is refused at startup; a longer one WARNs at every start |

### Issuance states

| From | May move to |
|---|---|
| `NUMBERED` | `ARCHIVING`, `FAILED_ARCHIVE`, `FAILED_VALIDATION`, `VOID_UNUSED` |
| `ARCHIVING` | `ISSUED`, `FAILED_ARCHIVE`, `FAILED_VALIDATION`, `VOID_UNUSED` |
| `FAILED_ARCHIVE` | `ARCHIVING`, `FAILED_VALIDATION`, `VOID_UNUSED` |
| `FAILED_VALIDATION` | `VOID_UNUSED` |
| `ISSUED`, `VOID_UNUSED` | nothing |

The same table is enforced twice: by the Java enum and by the database trigger.

`FAILED_VALIDATION` is a burned number: the document was refused, the rule id is on the row and in
the chain, and an operator voids it. The unit of work takes it from `ARCHIVING` or
`FAILED_ARCHIVE` only when a resumed run found no object at the row's archive key (or the row had
no key yet). The row then keeps any hash it recorded the first time. **That hash names the first bytes, which passed and were never archived; the rule
id names the refusal of the later bytes.** The run logs both hashes at WARN, never the content.
With rule id `DEI-244`, the later bytes passed but differ from the recorded hash (or key); they were
never stored. A later event for a burned number ends on `DEI-250` and carries the burn's rule id,
so with `DEI-244` it says that nothing was refused.

Example. `F-2026-0042` is numbered on 10 January. The archive write times out and nothing lands.
The host upgrades; the retry renders BT-2 = 3 January; `EINVOICE-DATES` refuses. The event ends on
`DEI-250` with that rule id, the row is `FAILED_VALIDATION`, nothing is archived, and the sweep
reports `DEI-279` until an operator voids the number.

If the first PUT completes on the store after the burn (or after a void), the sweep reports
`DEI-281` on the invoice: the ledger says the number has no document, and the archive holds one.
Nothing is deleted. The operator keeps the object as evidence and notes it next to the number in
the series report. The finding repeats on every pass while the row is among the newest
`page-size` burned or voided numbers that recorded a key (500 by default), in any year. Issued
numbers do not push it out: only 500 burned or voided numbers with a key, numbered after it, do.

The check for archive objects that no row predicted (`DEI-272`) walks the seller folder a page at a
time and saves where it stopped, so every key is examined within one cycle, in any year. With the
defaults a pass examines 2,000 keys; `DEI-283` reports a cycle that takes longer than 7 days. See
the docs page, "Reconciliation, health and findings", for the cost on a filesystem and on S3.

### Database connections: one session per connection

Each issuance row is held under a session-level PostgreSQL advisory lock from the moment its
archive write is announced until its outcome is recorded. Two workers never write and refuse the
same number at once: the second one gets `DEI-245` and retries later.

- **A pooler in transaction or statement mode is refused** (`DEI-104`). The startup check is an
  early warning only: under low load a transaction pooler can pass it. What protects the number is
  the proof every locked transaction runs first (this session still holds the key with this run's
  random token); when it fails, the run stops with `DEI-104` before it reads, writes or burns. A
  false pass needs two foreign acts on the same database backend (something frees the lock without
  a reset, then something that is not this module takes the same key), which only code running as
  the module's own database role can do. The session setting `einvoice.row_lock` is reserved.
  Under such a pooler a session lock is no lock at all. PgBouncer in session mode, or a direct
  connection, works. There is no setting that turns this check off.
- A Hikari pool with a maximum size below 2 fails the startup with `DEI-100`: the check needs two
  connections at once.
- The check needs two free connections besides the caller's own, the first time. The starter runs
  it once at startup, outside any transaction (a refused pooler fails the startup). A host that
  wires the store by hand calls `JdbcIssuanceStore.verifySessionPinning()` at startup.
- A lock that cannot be released on its own session (the backend died: a failover, a terminated
  backend, a network cut) is logged at ERROR with `DEI-247`, and the connection is closed instead
  of pooled.
- If a database connection dies while it holds the lock, PostgreSQL releases the lock at once. A
  half-open TCP connection keeps it until the server notices: set `tcp_keepalives_idle`,
  `tcp_keepalives_interval` and `tcp_keepalives_count` on the server (and `tcpKeepAlive=true` on
  the JDBC URL) so that this takes seconds, not hours. Until then the row only retries; it is never
  burned.

### One run per invoice

A run is one pass of the issuance pipeline over one recorded event. At most one run of one Stripe
invoice is live at any time. Before it decides anything, a run takes a session advisory lock keyed
on the invoice (the first four bytes of SHA-256 of the Stripe object id; the session setting
`einvoice.run_lock` is reserved). The lock is tried, never waited for.

- **Busy is `DEI-217`.** A second run of the same invoice (the sweeper re-picking a row the worker
  is still running, or `invoice.paid` arriving while `invoice.finalized` runs) writes nothing and
  answers the stored state with `DEI-217`, logged at DEBUG. The sweeper picks the row again later.
  `IssuanceReprocess` answers `REFUSED_RUN_IN_PROGRESS` and records nothing.
- **A run never joins your transaction: `DEI-218`.** Call `IssuanceReprocess` (and
  `IssuanceUnitOfWork.process`) outside a transaction. Inside one, for example from a
  `@Transactional` admin action, the call is refused with `DEI-218 RUN_IN_HOST_TRANSACTION` before
  the run lock is tried: nothing is locked, read or recorded, and your transaction is untouched. A
  run fetches from Stripe, renders and archives, so it would hold your transaction open for minutes,
  and a failed write would leave its run lock on your pooled connection.
- **A refused Stripe key is `DEI-219`.** Stripe answering 401 or 403 means the key was revoked or
  lacks a permission. That does not improve with time, so the event stops with no retry. Each
  reconciliation pass names every stopped event once (a finding with code `DEI-219`). The first
  successful Stripe call after a refusal, and every startup, makes the stopped events due again.
  Example: the key is revoked by mistake at 09:00, three events stop; you rotate the key and
  restart at 09:20; the startup makes the three due and the next sweep finishes them.
- **A run never leaves a number behind a refusal.** A row that may hold a number (`MAPPED`,
  `FAILED_ISSUANCE`, or `FETCHED` when the issuance table has a number for the invoice) and meets a
  Stripe outage, a pin change, a mode or account mismatch or an unsubscribed type is recorded
  `FAILED_ISSUANCE` with that refusal's own code, keeping the number. It is retried with a backoff
  (no retry for `DEI-205` and `DEI-219`), and the reprocess resumes it once the cause is fixed.
- **A live run is never expired.** The time limit on running rows (`running-ceiling`,
  `parked-ceiling`) tries the invoice's run lock before it expires a row, and skips the row while a
  run holds it. Example: a run holds `INV-2026-000001` and waits 73 hours on a slow archive; every
  sweep in that time skips its `MAPPED` row; the run then issues the invoice and writes `COMPLETED`.
  A row whose run ended without progress is expired at the next sweep.
- **A lost lock is `DEI-104`, at WARN.** If the server or the network ends the run's session
  mid-run, the run's next write refuses, the run writes nothing more, and one WARN names the event.
  The connection is closed, not pooled.
- **One run is one attempt.** A run stamps the row as it starts: one attempt more, and a next
  attempt one backoff away. A run that dies (an out-of-memory kill, a pod eviction) is retried after
  that backoff, onto the same number, not at the next sweep tick.
- **Capacity.** A live run holds up to three connections: the run lock, the row lock during the
  archive write, and one short-lived one. Every caller of the pipeline shares
  `einvoice.issuance.concurrency` run permits: worker threads wait for one, a host thread calling
  `IssuanceReprocess` gets `DEI-102` at once when none is free. At startup the starter refuses
  (`DEI-100`) a Hikari pool smaller than `3 x concurrency + 2` (one for the module's jobs, which
  all run on one thread, and one for the host). The defaults (concurrency 2, pool 10) pass. A pool
  that does not unwrap to HikariCP is not checked, and a WARN says so at every startup.
- **A vanished host.** When a node loses power, nothing on that node closes its sessions. Only the
  database server's own TCP keepalive ends them, and every invoice that node was running stays busy
  until then: about 2 h 11 min with operating-system defaults. Set `tcp_keepalives_idle = 60`,
  `tcp_keepalives_interval = 10` and `tcp_keepalives_count = 6` on the server: about two minutes.
  The client's `tcpKeepAlive` does not help here; the client is gone.
- **PgBouncer in session mode** needs `server_reset_query = DISCARD ALL` (its default). Emptied,
  a lock outlives its client on the server connection and the invoice stays busy, fail-closed,
  until `server_lifetime` closes that connection.
- **Networks that drop idle connections** (a NAT or cloud load balancer that cuts a connection idle
  for four to six minutes) end the session of a run that holds its lock idle for longer, for example
  an invoice with many line pages. Each such run ends `DEI-104` at WARN. The server keepalive above
  keeps the connection from looking idle.

### Inside your own transaction (joined mode)

When the store joins your transaction (Spring's transaction manager, or a connection you hand
over), each locked step runs under its own savepoint, and the row lock is released before the call
returns.

- **Your PostgreSQL connection needs `autosave` NEVER (the default) or CONSERVATIVE.** `autosave
  ALWAYS` undoes a failed statement inside the driver, so a failed step could leave your commit
  keeping a half-written invoice. Every joined call, read or write, refuses it with `DEI-249`;
  there is no opt-out.
- **When a step fails after your transaction wrote through the store, the transaction is aborted on
  purpose** (SQLState `DE248`), so it cannot commit. The exception you catch is the signal. A plain
  JDBC `commit()` after a failed joined call returns normally and commits nothing; PostgreSQL
  answers ROLLBACK. The module cannot make that call throw. Spring callers also get
  `UnexpectedRollbackException`.
- **A call on a transaction that was already aborted** is refused with `DEI-248`; roll it back and
  call again. The module never aborts or closes your connection.
- A caller that rolls back to its own savepoint, taken between two calls to this module, keeps the
  first call's writes: PostgreSQL lets a rollback to an earlier savepoint undo any abort. Wrap the
  whole issuance in one savepoint and never split it.
- A lock attempt cancelled between taking the key and writing its token leaves a key with no token
  until the database session ends; the row answers `DEI-245`, never a burn.

### The filesystem archive and its mount point

Until the archive marker lands (before 0.2.0), an empty directory where the volume should be looks
like a new, empty archive. Then a resumed row can burn a number whose document is on the real
volume, and new documents are written to the local disk. Make the start fail when the volume is
not mounted: with systemd, `RequiresMountsFor=<archive root>` on the service; in a container
entrypoint, `mountpoint -q <archive root> || exit 1`. A Kubernetes volume in the pod spec is
mounted or the pod does not start.

### What it needs from you

The writer and the validator are the starter's, built from `einvoice.seller.*` and
`einvoice.documents.*` as soon as `einvoice.seller.name` is set. What is left for you:

```java
@Bean StripeInvoiceSource source() { ... }  // or set einvoice.stripe.api-key and take the SDK
```

You may still supply your own `DocumentRenderer` or `DocumentValidator` bean and ours backs off.
The date check does not: it is part of the unit of work, not of the validator, so no bean removes
it. Before anything is archived, the unit of work reads the document's issue date (BT-2), supply
date (BT-72), invoicing period (BG-14) and VAT point date (BT-7, BT-8), and compares them with the
allocation. Example: an invoice numbered on 10 January whose renderer writes 3 January is refused,
whatever your validator answers. The check reads UBL `Invoice` and `CreditNote` only; any other
syntax is refused until the module ships a reader for it. A custom format is a paid service, and
the date check applies to it. The ports are public, so you are not blocked waiting for one.

**Your renderer must be byte-stable across a retry.** Only the hash of the first attempt is
recorded. When a retry finds no object at that key and renders bytes that pass but differ, the
number is burned with `DEI-244` and nothing is archived: the hash and key are write-once, so the
new bytes can never take that number. Causes: a renderer upgrade, a seller profile change (the
seller's street name corrected in configuration between two attempts), a mapping change, a new rule
pack that changes the output, or a timestamp or random id in the bytes. Drain open numbers
(`NUMBERED`, `ARCHIVING`, `FAILED_ARCHIVE`) before such a change.

With neither ours nor yours, this whole half of the module does not start: allocating a number with
no way to produce a validated document would consume a legal series and archive nothing. **How
loudly depends on whether you plainly meant to receive events.** With
`einvoice.stripe.webhook-secrets` configured, or `einvoice.issuance.enabled` set explicitly, the
missing bean fails startup by name - the usual cause being that `einvoice.seller.name` is unset.
With neither, this is a numbering-only host and it starts with one WARN naming what is missing, not
a failure - set `einvoice.issuance.enabled=false` to say so on purpose and stop the WARN repeating.

and, in your security configuration, the webhook path left unauthenticated - the HMAC over the
exact bytes received is its authentication:

```java
.requestMatchers(HttpMethod.POST, "/webhooks/stripe").permitAll()
```

## Quick start

```xml
<dependency>
  <groupId>com.housedevinci</groupId>
  <artifactId>stripe-einvoice-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```yaml
einvoice:
  mode: live                    # 'test' WARNs at every startup and uses a separate series
  seller:
    id: acme-fr
    tax-zone: Europe/Paris      # required, no default: it decides the invoice date and fiscal year
    name: Atelier Riviere SAS   # BT-27. Setting it is what turns the document writer on
    vat-id: FR25900000019       # checked at startup, check digit included
    electronic-address: FR25900000019
    electronic-address-scheme: "9957"   # Peppol EAS; mandatory for the Peppol profile
    address:
      line1: 12 rue des Lilas
      city: Lyon
      postal-code: "69003"
      country: FR
    contact:                    # mandatory for XRechnung (BR-DE-2, BR-DE-6, BR-DE-7)
      name: Comptabilite
      telephone: "+33 4 72 00 00 00"
      email: factures@atelier-riviere.invalid
    payment:                    # mandatory for XRechnung (BR-DE-1)
      means-code: "58"
      account-id: FR7630006000011234567890189
  documents:
    profile: peppol-bis-ubl     # or xrechnung-ubl
    buyer-reference: PO-9912    # BT-10; mandatory for XRechnung (BR-DE-15), a Leitweg-ID for B2G
  numbering:
    prefix: "INV-{fiscalYear}-" # required, no default; {fiscalYear} is required when the series
                                # resets each year (below) - never a static year, or the second
                                # January reuses the first year's numbers
    width: 6
    fiscal-year-reset: true
  chain:
    hmac-secret: ${EINVOICE_CHAIN_SECRET}   # base64 of >= 32 bytes, from the environment
    hmac-key-id: k1
  stripe:
    webhook-secrets:            # a keyring: Stripe's own rotation leaves two secrets live
      primary: ${EINVOICE_WEBHOOK_SECRET}
    api-key: ${EINVOICE_STRIPE_KEY}   # restricted, read-scoped; this module never writes to Stripe
  archive:
    type: filesystem            # or supply your own ArchiveStore bean (an S3 one ships in core)
    root: /var/lib/einvoice/archive
```

```java
@Service
class Invoicing {

  private final IssuanceNumberingService numbering;   // from the starter

  void onInvoiceFinalized(String stripeInvoiceId, String stripeNumber, Instant finalizedAt) {
    Issuance issuance = numbering.allocate(stripeInvoiceId, "", stripeNumber, finalizedAt);
    // issuance.legalNumber().value() is BT-1. Stripe's own number is stored as a reference.
  }
}
```

Voiding an allocated number is a **service method and never an HTTP endpoint**: this is a library,
it cannot authenticate anyone, and an auto-configured void endpoint would hand an unauthenticated
caller a way to burn a series one number at a time. If you expose it, put it behind your own
authorization.

### Operating it

| Question | Where the answer is |
|---|---|
| Is anything wrong right now? | `GET /actuator/health/einvoice` - operational conditions only: a silent sweeper, a stale reconciliation, a chain that does not verify. It is deliberately outside `readiness` and `liveness`, so a business condition can never take your application out of the load balancer |
| What needs a human? | `GET /actuator/einvoicefindings` - open issuance findings with their codes and subjects. Acknowledge one through `IssuanceFindingService`, with a reason that is recorded; the finding is never deleted |
| Stripe moved its API version | The refused events are on `einvoice_inbound_event` with their bodies. Update the pin to match, restart, and the sweeper's due query re-picks exactly the rows whose recorded version now equals it, and replays them through the same path |
| A document failed validation | The number stays allocated with the failing rule id recorded on the row and in the hash chain, so the gap it leaves is explained by the tamper-evident record. An operator voids it through `IssuanceVoidService` with a reason, and the series report explains the hole |
| A number was voided | **The void is final, and it is final inside this module.** That Stripe invoice will never carry a document here: one invoice maps to one number for all time, so every later event for it is recorded with `DEI-122` and nothing is retried. To document the sale, correct the data upstream and raise a **new** Stripe invoice, which takes the next number through the ordinary path - and if the sale is already paid, that needs a credit note, which this edition does not produce |
| A finding `DEI-282` says an event expired | The event stopped being due after its time limit. Example: a Stripe draft was never finalised, so its `invoice.paid` event sat `PARKED` for 90 days. Look at the row's state before it expired (in the log line) and fix the cause, for example finalise or delete the draft in Stripe. Then call `IssuanceReprocess` for the event id: it re-fetches the invoice from Stripe, starts the clock again, and records who asked. An event whose invoice already holds a number is refused there; its number is reported by `DEI-273` and is resumed or voided like any other stuck number |
| A seller profile was incomplete and invoices were refused | Correct the profile, then call the privileged `IssuanceReprocess` for each refused event. It runs the ordinary pipeline, preflight included, never touches an invoice that already has a number, and records who asked and why |

### Run the sample

**The whole thing end to end, with no Stripe account and no keys.** A signed test-mode event becomes
a validator-clean **Peppol BIS Billing 3.0 UBL** invoice in the archive, with a validator-clean
**XRechnung** rendered for the same invoice beside it. Docker is the only prerequisite
(Testcontainers starts PostgreSQL):

```bash
git clone https://github.com/1of1Canopus/stripe-einvoice && cd stripe-einvoice
./mvnw -pl stripe-einvoice-sample -am test
```

Measured from a clean clone with an empty Maven cache on 2026-09-16: about two minutes, including
the downloads. One profile per application means one legal original per invoice; the XRechnung
beside it is a call on the renderer, not a second archived document under the same number.

**The application itself**, against your own PostgreSQL. As shipped it is the numbering and
rendering half: it allocates legal numbers and serves the series report, and it has **no Stripe
intake** - no webhook secret, no API key, no sweeper - because it has to start from a clean clone
with no Stripe account. It says so at startup (`numbering API only, as configured`), and
`einvoice.issuance.enabled: false` in its `application.yml` is where it is said:

```bash
./mvnw -q -DskipTests install          # the sample resolves its siblings from the local repository
cd stripe-einvoice-sample
docker compose up -d
EINVOICE_CHAIN_SECRET=$(head -c 32 /dev/urandom | base64) ../mvnw spring-boot:run
```

**With Stripe intake**, once you have an account, a webhook endpoint and a restricted key. The
`intake` profile (`application-intake.yml`) is the other half of the sample: it sets
`einvoice.issuance.enabled=true`, the webhook-secret keyring and the API key, all three from the
environment and none of them with a default:

```bash
EINVOICE_CHAIN_SECRET=$(head -c 32 /dev/urandom | base64) \
EINVOICE_WEBHOOK_SECRET=whsec_from_your_stripe_dashboard \
EINVOICE_STRIPE_KEY=rk_test_your_restricted_read_scoped_key \
../mvnw spring-boot:run -Dspring-boot.run.profiles=intake
```

The two Stripe values above are **placeholders and are refused as such**: replace them with your
own before running, or the application stops at startup with "is a placeholder from a sample file".
A signing secret printed in a public README is a public secret, and the module's job is to keep one
from ever guarding the unauthenticated webhook endpoint.

All three values are required together, and that is the point: an application configured to receive
Stripe events with no key to read them back would record events it could never turn into documents,
so it refuses to start and names the missing piece. Leaving the profile off is how you get the
numbering API only - a state the application states rather than falls into.

## Requirements

- Java 21, Spring Boot 4
- PostgreSQL. The allocator's row-lock `UPDATE ... RETURNING`, the append-only triggers and the
  advisory locks have no portable equivalent, and the application refuses to start against any
  other server rather than degrading a legal numbering series quietly.
- A database role that does **not** own these tables - see `docs/schema-grants.sql`. The triggers
  refuse the runtime role; only the grant separation keeps a table owner from disabling them, and
  the startup check says so out loud when the role owns them.

## Documentation

- `docs/index.md` - how the series works, every property, the series report, and why an
  invoice's issue date is the day its number is given, with the day of the sale beside it.
- `docs/documents.md` - the BT-to-Stripe mapping table field by field, the validator artefacts and
  their versions, and what is deliberately refused.
- `docs/mandates.md` - which country requires a structured invoice from when, each date with its
  source URL and retrieval date, and "not confirmed" where there is none.
- `SECURITY-NOTES.md` - what is protected, by what, and what is not.
- `docs/schema-grants.sql` - the database role this module should run as.
- `CHANGELOG.md`

## Licence

FSL-1.1-ALv2 (see `LICENSE` and `NOTICE`): source-available, and it converts to Apache-2.0 two
years after each version is published.

**What this software is, legally.** It is a library. Your application is the issuer of the invoices
it produces; we produce and validate documents. The claim this project makes about conformance is
exactly this and no more: **it produces output that the reference validators accept**, and those
validators run in CI on every build over every fixture. Have your accountant check the first
invoices a new configuration issues.
