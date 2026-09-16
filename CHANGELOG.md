# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
