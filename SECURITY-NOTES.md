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
| Append-only ledger | Database triggers: no DELETE, no TRUNCATE, no UPDATE of an immutable column, write-once `document_sha256` and `archive_key`, declared state transitions only |
| Tamper evidence above the triggers | HMAC-SHA-256 chain over the disposition log, length-prefixed canonical form, key id inside the hashed material from row 1, separate anchor row with a monotonic trigger |
| Out-of-band writes | The verifier cross-checks disposed issuance rows against the chained log and reports `BROKEN` |
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

## Personal data, and the erasure module

Nothing in this pull request stores a buyer field: the issuance row holds ids, a number, timestamps,
hashes and an operator's void reason. When the Stripe intake and the EN 16931 mapping arrive, that
changes, and the sentence below has to be true before both modules are sold to the same customer:

> **Issued invoices are outside erasure scope.** A seller must keep them for the legal retention
> period of its jurisdiction, so a data subject's erasure request does not, and must not, remove
> them. This module is an exporter of personal data into a long-lived archive, and that is a
> deliberate, documented consequence of tax law rather than an oversight.

## Reporting

`security@housedevinci.com`. See `SECURITY.md`.
