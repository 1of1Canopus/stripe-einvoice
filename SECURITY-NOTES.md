# Security notes

What this module protects, with what, and - the part that matters more - what it does not.

## The asset

The numbering series. A continuous per-seller series is the first thing an auditor checks, and a
number that is duplicated, or consumed with no document and no explanation, is an accounting defect
that cannot be repaired retroactively. Everything below exists for that one sentence.

## The claim, stated exactly

> Every number allocated in a series has a recorded, chained disposition: it is on an issued
> document, or it is voided with a reason.

This is deliberately **not** "gap-free numbering". Absolute gap-freeness and byte-determinism cannot
both hold: rendering a document with a placeholder number and patching it afterwards would mean the
bytes that passed the validator are not the bytes that were archived, which is a worse defect than a
recorded, explained hole. So a validation failure after a number is allocated leaves the number
allocated, and an operator voids it with a reason. The rate of those voids is a measurable quantity,
the failing rule id is recorded on each one, and a recurring one is a bug in our own model-level
validation rather than a fact of life.

Do not write "gap-free" in a README, a docs page, a release note or a sales page for this module.

## What holds the property

| Control | Where |
|---|---|
| Row-lock allocation in the issuance transaction | `UPDATE einvoice_series ... RETURNING`, no `SEQUENCE`, no `nextval`, no `@GeneratedValue` |
| The allocation belongs to the caller's unit of work | Called inside a Spring transaction, it joins that transaction: the caller's rollback takes the number and the counter with it. Called outside one, it opens and commits its own. A caller-owned connection in auto-commit mode, and a read-only caller transaction, are refused by name rather than half-executed |
| A caller cannot commit what we half-wrote | A failure after this module's first statement marks the caller's transaction rollback-only, so catching a typed refusal and committing anyway raises instead of recording a counter with no issuance row |
| The two locks cannot deadlock | Nothing takes the chain's advisory lock before the series counter's row lock; a transaction that already holds the chain lock is refused the series row lock, by a ledger scoped to that transaction |
| One Stripe invoice, one number | `UNIQUE (seller_id, mode, stripe_invoice_id)`, with a resume-by-source read first and the constraint as the backstop |
| No duplicate number, including across a fiscal-year boundary | `UNIQUE (seller_id, series, fiscal_year, mode, legal_number)` within one year, **and** a required `{fiscalYear}` placeholder in a resetting series' prefix, resolved once at the row's creation, so two different years' rows never render the same string in the first place |
| Test events out of the live series | `mode` in the series primary key, resolved from Stripe's own `livemode`, never from metadata |
| No silent format change | Allocation refuses at the last number the configured width renders |
| Append-only ledger | Database triggers: no DELETE, no TRUNCATE, no UPDATE of an immutable column, write-once `document_sha256` and `archive_key`, a void reason and rule id that change only in the statement recording the disposition, declared state transitions only |
| Tamper evidence above the triggers | HMAC-SHA-256 chain over the disposition log, length-prefixed canonical form, key id inside the hashed material from row 1, separate anchor row with a monotonic trigger |
| Out-of-band writes | The verifier cross-checks disposed issuance rows against the chained log - identity, state **and** the void reason and rule id - and reports `BROKEN` |
| No JPA path to these tables | No entity of ours, and a startup guard that refuses a host entity, secondary/join/collection table, `@Subselect` or database view over them |

## What it does not protect against, said plainly

- **A database role that owns these tables.** It can `ALTER TABLE ... DISABLE TRIGGER` and walk past
  every refusal above. Run with the grants in `docs/schema-grants.sql`; the startup check WARNs at
  every boot when the runtime role owns them. What still catches a rewrite afterwards is the chain,
  not the triggers.
- **A host application's own raw JDBC.** No scan can see a `JdbcTemplate`, and the runtime role must
  keep INSERT for this module to work at all. An out-of-band row that reaches a disposition has no
  chained event agreeing with it, and the verifier reports `BROKEN`. An out-of-band row that stays
  open shows up in the series report as an open allocation, which is a compliance finding rather
  than a cryptographic one. Both are detections, not preventions.
- **An unkeyed chain.** `einvoice.chain.unkeyed=true` exists, WARNs at every startup, and makes the
  verifier report `INTACT_UNKEYED` - never `INTACT`. Anyone who can write a row can then recompute
  every hash after it.
- **A caller that asks for the number to survive its own rollback.**
  `@Transactional(propagation = REQUIRES_NEW)` around an allocation suspends the caller's
  transaction, so the number is kept when the caller rolls back. That is the only supported way to
  ask for it, it is per call rather than application-wide, and the result is an allocated, open
  number that the series report lists as open until it is disposed of.
- **JTA and XA.** Out of scope and untested: a single JDBC `DataSource` is the supported
  deployment. A JTA transaction manager in the context WARNs at every startup.
- **Backups and replicas.** Nothing here reaches them.

## Secrets

`einvoice.chain.hmac-secret` is required, environment-only, base64 of at least 32 bytes, and has no
default. It is **not** the Stripe webhook secret: one proves a request came from Stripe, the other
proves our own record was not rewritten afterwards, and one value for both means one leak costs both
properties. A value that is not base64 is refused by name at startup rather than guessed at - a
32-character passphrase is also valid base64 and would silently become 24 different bytes of key.

It is also excluded from `/actuator/env` and `/actuator/configprops` **by name**: a
`SanitizingFunction` names `einvoice.chain.hmac-secret` and every `einvoice.chain.hmac-keys.*`
retired id explicitly, rather than resting on the framework's default sanitiser, which happens to
catch a property whose name contains "secret" or "key" today - a fact about English words, not a
control that survives a rename.

## Voiding is privileged, and has no endpoint

This module auto-configures no HTTP endpoint at all, and a void endpoint least of all. It is a
library and cannot authenticate anyone; an endpoint it configured for you would be an unauthenticated
way to burn a numbering series one number at a time. `IssuanceVoidService` is a service method,
documented as privileged. The host application exposes it, or does not, behind its own authorization.

**A void is irreversible, and unrecoverable inside this module.** One Stripe invoice maps to one
number for all time, so voiding that number ends this module's involvement with that sale: every
later event for the invoice is recorded terminally with `DEI-122`, the reconciliation sweep reports
it under `DEI-278` instead of re-enqueueing it, and no path allocates a second number. An operator
who voids by mistake cannot undo it here. The remedy is upstream - correct the data, void the Stripe
invoice, raise a new one - and when the sale is already paid that remedy needs a **credit note**,
which this edition does not produce. Treat the void as what it is: a decision about a legal
numbering series, taken once.

## Personal data, and the erasure module

The issuance row still stores no buyer field: ids, a number, timestamps, hashes and an operator's
void reason. Two other places now hold personal data, and both are set out below - the raw webhook
bodies on the intake path, which are bounded and purged, and the archived documents, which are not.
The sentence below has to be true before both modules are sold to the same customer:

> **Issued invoices are outside erasure scope.** A seller must keep them for the legal retention
> period of its jurisdiction, so a data subject's erasure request does not, and must not, remove
> them. This module is an exporter of personal data into a long-lived archive, and that is a
> deliberate, documented consequence of tax law rather than an oversight.

## The webhook endpoint, and why it is open

`POST /webhooks/stripe` is the one endpoint this module auto-configures and the one path your
security configuration must leave unauthenticated. Its authentication is an HMAC-SHA256 over the
**exact bytes received**, against a keyring, inside a bounded time window - computed here rather
than through the SDK helper, which takes the payload as a decoded `String` and a single secret.

What the endpoint refuses before anything durable happens: a content type that is not JSON, a body
past `einvoice.webhook.max-body-bytes` counted as it is read, a missing or invalid signature, a
timestamp outside the tolerance, and a body whose identity fields do not parse under a reader that
refuses duplicate keys, malformed UTF-8, unpaired surrogates and nesting past a depth bound. All of
those answer 400 and write nothing, because we cannot attribute them.

Everything with a valid signature is recorded first and answered 200, including the refusals we can
attribute: an API version off the pin, a test-mode event in a live application, an account that
resolves to no seller. That is deliberate. Stripe treats a 400 as a failed delivery and disables
endpoints that keep failing, and a version skew hits every event on the account at once, so a
refusal that answered 400 could take the whole intake offline and lose the events that would have
worked. Those rows keep their bodies, and the sweeper's due query re-picks exactly the
`REFUSED_VERSION_SKEW` rows whose own recorded API version now equals the pin this application
starts under, driving them through the same path (D2-03). `REFUSED_MODE` and `REFUSED_ACCOUNT` are
never re-picked automatically: an upgrade does not cure either, and re-running one silently would
be worse than leaving it for an operator.

## Personal data on the intake path

`einvoice_inbound_event` holds the raw signed webhook bodies: a full invoice payload per row, with
the buyer's name, address, email, tax id and line descriptions. It is the largest store of personal
data in this module, and it is treated differently from the ledger on purpose.

- It carries **no append-only trigger** and the runtime role **may delete from it**. A table nobody
  can ever delete from would make a retention promise impossible to keep.
- The raw body is **nulled as soon as the event reaches a state it can never run from again**, and
  a SHA-256 of it is kept so what arrived stays provable.
- Anything that never reached such a state is purged at `einvoice.inbound.retention`.
- Health details, findings, metrics and log lines carry ids, codes, counts and hashes. No buyer
  field, no amount, no acknowledgement text that could carry one.

The issued documents themselves are a different matter, and the paragraph below on the erasure
module is where that is set out.

## Residual risks on the issuance path

- **A configured intake with a missing renderer or validator fails startup, by name (D2-02).** The
  unit of work, the worker, the sweeper, the webhook controller and the health indicator are all
  conditional, transitively, on a `DocumentRenderer` and a `DocumentValidator` bean. Silently not
  wiring any of them was the earlier behaviour, and it meant an application with
  `einvoice.stripe.webhook-secrets` and an archive root configured - one that plainly expects to
  receive events - could start with no endpoint, no sweeper and no signal at any log level: Stripe
  then posts to a path that answers 404, retries for three days, and disables the endpoint with
  nothing having ever noticed. "Configured" is `einvoice.stripe.webhook-secrets` non-empty, or
  `einvoice.issuance.enabled` set explicitly (checked against the environment, never the property's
  own default, which is `true`). With neither, this is a numbering-only host and it starts with one
  WARN naming what is missing, never a failure.
- **An archive store that lies about write-once.** The capability is probed at startup with a real
  conditional write, and a store that overwrites is refused. With
  `einvoice.archive.allow-non-atomic-store=true` the application starts anyway, WARNs at every
  startup, and every write does a read-back comparison - which narrows the window between two
  concurrent writers and **does not close it**. Two processes writing the same key at the same
  moment can still leave one document's bytes behind another's. The reconciliation sweep re-hashes
  a bounded sample of the newest documents, which is what makes the condition detectable rather
  than theoretical.
- **The intake table is not seller-partitioned in this version.** Its rows are keyed on Stripe's
  own event id, which is unique per account, and this edition issues for one configured seller
  (the column exists and carries one value). The sweeper's own queries are therefore not
  seller-scoped: a row that has not been routed yet has no seller to scope by, and filtering on one
  would hide exactly the rows the sweeper exists to find. Multi-seller scoping arrives with Connect
  support.
- **A host-supplied `ArchiveStore`, `DocumentRenderer` or `DocumentValidator`** is code we do not
  review. The contracts state what they must guarantee - write-once and byte-identity, the same
  bytes for the same input, a verdict that is not PASSED unless every rule ran - and the
  reconciliation sweep detects a store that breaks the first. A renderer that is not deterministic
  is detected as an archive content conflict rather than silently issuing twice.
- **A validator that reports PASSED without running.** This module refuses `NOT_EVALUATED` and
  treats it as a refusal, but it cannot tell a lying validator from an honest one. The rule pack id
  and version are recorded on the issuance and inside the hashed material so a re-validation years
  later can be compared against what was claimed at the time.
- **An unexpected exception from a host-supplied renderer, validator or archive store** is caught,
  logged server-side only, and recorded as a terminal `FAILED_ISSUANCE` with a stable generic code
  - never left `MAPPED` with no code and no next attempt (D2-04). It is not retried automatically:
  a bug in a host's own port does not improve by running again, and a stack trace never reaches a
  caller. This is deliberately narrower than "the process crashed": an `Error` still propagates
  uncaught, exactly as before.
- **A mapping refusal costs no legal number; five kinds of post-allocation refusal still do
  (D3-01, D3-05).** Every refusal that depends on the invoice's own data - a buyer-controlled field
  no XML 1.0 parser could read, an unsupported currency, a tax rate no EN 16931 category can be
  established for, a missing required term, an incomplete seller profile, a document that does not
  balance, and every term the target profile requires beyond EN 16931 itself (BT-49 and BT-34 for
  Peppol, BR-DE-1, BR-DE-2, BR-DE-8, BR-DE-9, BR-DE-10 and BR-DE-15 for XRechnung, and both
  parties' VAT identifiers on a reverse-charge supply) - is taken **before** the allocator, by
  running the render path's own mapping with no number in existence. Those profile rules used to
  live only in the writer, which the preflight does not run, so a buyer with no VAT identifier
  passed the preflight and cost a number at the writer; they now run in the mapper, with the
  writer's copy kept for a host that calls the writer directly. The event ends `FAILED_MAPPING` with the mapper's own code and leaves no
  issuance row, no chain entry, no archived object and no advance of the series counter. That
  refusal is final: a finalised invoice's fields are frozen, so the same run would reach the same
  verdict; the remedy is a corrected upstream invoice, which arrives as a new event. The sweeper
  never re-picks the row.

  After that void, the Stripe invoice is terminal here: later events are recorded with `DEI-122`,
  the sweep reports `DEI-278` rather than re-enqueueing, and nothing allocates a second number. See
  "Voiding is privileged" above for what an operator has to do upstream instead.

  What still costs a number, and needs an operator void: a rule only the complete numbered document
  can be checked against; a renderer or writer fault that is not a mapping decision; archive
  unavailability, a write-once conflict or a read-back mismatch; a store failure after the bytes
  exist; and an upstream void after issuance, which is a credit-note case rather than a refusal.
  Each of those needs the number to exist or is an infrastructure fault, and none is buyer-
  controlled input.

  Every one of them puts the number's fate on the row: the issuance row moves out of `NUMBERED` to
  a failure disposition, and the inbound event row carries the code - and, where there is one, the
  failing rule id. A renderer or writer fault used to be the exception, leaving the number in
  `NUMBERED` with nothing recorded until the reconciliation sweep's stuck check reported a count
  hours later; it now records the same disposition a validation refusal does, with the render code
  where the rule id goes. **A burned number is also chained**: the `FAILED_VALIDATION` disposition
  appends a chained event, in the same transaction as the state change, carrying the failing
  validation rule id or the render refusal's code - so the justification for a gap in the issued
  sequence lives in the tamper-evident record and not only on a row that can legitimately change,
  and the verifier's cross-check reports a burned row written out of band as `BROKEN`. The
  justification itself is part of that comparison: the rule id is written on the row in the same
  statement that burns the number, the reason and the rule id change only in the statement that
  records the disposition - a bare `UPDATE` of either is refused by the trigger - and the verifier
  compares both against the chained event, so a rewrite by a role that outranks the triggers is
  reported as `BROKEN` rather than shown to an auditor as the explanation for the hole.
  `FAILED_ARCHIVE` is not chained: it is not a settled disposition, and its eventual fate - issued
  or voided unused - is. "Retryable" is narrower than it sounds, and the narrow reading is the
  right one: only an `ARCHIVE_UNAVAILABLE` outage schedules another attempt. A write-once content
  conflict, a read-back mismatch and a host store's own unexpected exception record the same state
  with no next attempt, so they wait for an operator exactly as a validation refusal does.

  **A mapping refusal can be re-run only by an explicit, privileged operator call.** The pipeline
  and the sweeper still treat it as final. The module ships a service the host calls from its own
  admin action, behind its own authorization - no HTTP endpoint and no actuator operation, for the
  same reason the void has none - which accepts one event at a time, refuses any state other than a
  mapping refusal, refuses outright if a legal number already exists for that invoice, re-opens the
  row and then runs the ordinary pipeline, preflight included. Two simultaneous calls on one event
  produce one run and one number.

  **Who asked, when and why is recorded in `einvoice_reprocess_request`**: a `REQUESTED` row written
  in the same transaction as the re-open - so no failure leaves an event running again with nobody
  recorded as having asked - and a `CONCLUDED` row when the run ends, including when it ended by
  throwing. The actor and the reason are separate screened columns, refused rather than shortened
  when they exceed their bounds, and one request can have at most one conclusion. A request with no
  conclusion means the process died, and the reconciliation sweep raises `DEI-276`.

  **That table is append-only against the application role, and it is not hash-chained.** The
  runtime role holds `SELECT` and `INSERT`, triggers refuse `UPDATE`, `DELETE` and `TRUNCATE`, and
  `CHECK` constraints repeat the length bounds. Two things those controls do not stop, and nothing
  in this module reports afterwards: a role that owns the schema can disable the triggers and alter
  a row; and the runtime role's own `INSERT`, the one grant it needs, is enough to append a forged
  `CONCLUDED` row for a request that never finished, which silences `DEI-276` for it. The table
  records who asked; it does not prove the answer was honest. The issuance
  chain is the tamper-evident record of what was issued; this table is a record of who asked, with
  the protection stated rather than implied.

  **A third-party renderer that does not implement the preflight keeps the old cost.** The port's
  default answers `NOT_SUPPORTED` rather than refusing, so an implementation written before the
  method existed goes on issuing exactly as it did; allocation then still precedes mapping for that
  application. It is never silent: the renderer is named in a startup warning, a compliance finding
  is raised once per application start, and every outcome carries the verdict. The renderer this
  module ships always implements it.

## Reporting

`security@housedevinci.com`. See `SECURITY.md`.

---

## The document writers and the validators

**Third-party stylesheets are run as code.** The EN 16931, XRechnung and Peppol rules are
schematron, and schematron is executed as XSLT. This module compiles and runs four stylesheets it
did not write, over XML, in the same JVM as the host application. Everything about how they are
handled follows from that:

- they are **vendored**, never downloaded, with a SHA-256 each in
  `stripe-einvoice-core/src/main/resources/com/housedevinci/einvoice/reference/CHECKSUMS.txt` and a
  provenance note per file;
- the checksum is recomputed **before a stylesheet is compiled at run time**, not only by a test. A
  test proves the repository is intact; this proves the jar that is running is;
- extension functions are disabled both portably (`FEATURE_SECURE_PROCESSING`) and by the
  processor's own switch, so a stylesheet cannot reach Java;
- external DTD, schema and stylesheet access is blanked, `document()` resolution throws, and the
  transform runs under an output bound and a wall-clock timeout on a bounded pool.

**Every XML input is untrusted**, including our own output: the bytes that are validated are the
bytes that will be archived, and an archived document re-read for a re-validation is read through
the same hardened parser. A DOCTYPE is a refusal, not an unresolved reference.

**The validation findings this module exposes carry rule identifiers and severities, not text.** A
handful of XRechnung rules interpolate values out of the document being judged into their own
assertion text, and those findings are persisted, rendered and logged. The full text is reachable
only through `En16931DocumentValidator.validateInDetail`, documented as carrying document content,
for a caller that has decided it may see it.

**The XSLT 2.0 processor ships with the module, under one carved-out licence.** Saxon-HE is a
runtime dependency of the core module, so a default install validates and therefore issues; before
that, every host had to add a processor itself or issue nothing. Saxon-HE is MPL-2.0 and the licence
gate denies MPL, so the exception is coordinate-scoped in both halves of the gate and the invariant
that makes it safe is this: **MPL-2.0 is file-level copyleft**, its obligations attach to Saxon's own
files, those files are used unmodified and are not redistributed here, and nothing in this project
imports a Saxon type - the processor is reached through JAXP by class name. A second MPL dependency
fails the build, Saxon under any other denied licence fails the build, and a look-alike coordinate
fails the build; four self-test cases and two probes hold each of those lines. **Do not copy this
exemption to a dependency whose licence is reciprocal at the work level** (GPL, AGPL, SSPL): there
the same shape of carve-out would relicense the product, and the invariant above does not hold.

Saxon brings `org.xmlresolver:xmlresolver` (Apache-2.0) with it. It is a resolver, which is exactly
the component the hardening above neutralises: DOCTYPE is refused at the parser, external DTD,
schema and stylesheet access is blanked, and the resolvers installed throw on any resolution
attempt.

**Remove the processor and this module still issues nothing.** The class-name lookup remains, and it
is what `DocumentValidator.canValidate()` answers; with no processor every validation reports
`NOT_EVALUATED`, the issuance unit of work refuses **before a number is allocated**, and an
application whose intake is configured refuses to start. An unevaluated rule is not a passed rule,
and archiving a document no rule ever read would be worse than issuing none.

**Known vulnerabilities in dependencies are gated, keylessly, and the gate has edges.** Every pull
request scans the transitively resolved dependency graph with OSV-Scanner, and every release scans
the jars it is about to sign, and their resolved runtime dependencies, with Grype before the signing
step. One threshold applies to both (`tools/check-vulnerability-report.py`): HIGH and above fails,
MEDIUM and below is written down for a decision in the release notes, an advisory with no severity
counts at the threshold rather than below it, and an empty or unreadable report is a failure - a
scan that did not run is never a clean scan. What this does **not** cover, said plainly:
- test-scope dependencies are outside the Maven graph OSV-Scanner resolves; what ships is what is
  gated;
- there is no offline mode and no cached vulnerability database. Both scanners need the network, and
  a network failure fails the check rather than passing it quietly;
- a vulnerability with no advisory is invisible to both scanners, as it is to every scanner;
- OWASP Dependency-Check is kept only as an optional weekly deep scan. The NVD API key it needs was
  applied for and not issued, so it skips - loudly, with the reason in the run's own summary, never
  silently.

**What the documents contain, and for how long.** An issued invoice carries the buyer's name,
postal address and VAT identifier, by law, and it lives in the customer's own archive for the
retention their tax authority requires. `customer_email` is deliberately not written to the
document: it is buyer data that no business term here needs. Issued invoices are **outside erasure
scope** - a legal retention obligation is what they exist under - so an erasure request does not and
must not remove them. This module is, by design, an exporter of personal data into a long-lived
archive, and that is a consequence of tax law rather than an oversight.

**Conformance is not claimed.** What is claimed is that the output is accepted by the reference
validators, that those validators are the official artefacts at the versions recorded, and that they
run on every build. The host application is the issuer of the invoices; this is a library.
