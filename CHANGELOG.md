# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

### Notes

- PostgreSQL only, and the refusal probes the server rather than a configured dialect string.
- There is no `SEQUENCE`, no `nextval` and no `@GeneratedValue` on any numbering column, asserted by
  a test over the sources and the schema.
- This module auto-configures no HTTP endpoint at all.
- A security review pass on this branch left one finding open, tracked internally: the allocator
  does not yet join a caller-managed transaction that was opened outside this module. It needs a
  small design addition rather than a one-line fix and is not resolved in this release.
