# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [0.1.0] - 2026-09-23

### Fixed

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

### Fixed

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

### Changed

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

### Changed

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

### Fixed

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

### Notes

- PostgreSQL only, and the refusal probes the server rather than a configured dialect string.
- There is no `SEQUENCE`, no `nextval` and no `@GeneratedValue` on any numbering column, asserted by
  a test over the sources and the schema.
- This module auto-configures no HTTP endpoint at all.
- Transaction participation is explicit: the module exposes a `JdbcUnitOfWork` port with a
  caller-supplied-connection factory and a Spring implementation in the starter. There is no
  ambient "current connection" registry, and no application-wide switch that turns joining off.
- JTA and XA are out of scope: a single JDBC `DataSource` is the supported deployment, and a JTA
  transaction manager in the context WARNs at every startup.
