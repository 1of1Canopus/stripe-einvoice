# Security review - branch `feat/numbering-allocator`

Adversarial review of pull request 1 (scaffold and legal numbering allocator), commit `71ce8d6`.
Pass 1 of at most 2. Every finding below was reproduced with a test that fails on this commit;
nothing here is a reading.

## 2026-09-15 - pass 1

### Verdict

**NOT MERGEABLE.** One HIGH: for one seller and one series, the second fiscal year re-issues the
first year's legal numbers, so two legal documents carry one number under the default configuration
and the configuration the README prints.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 1 | D1-01 |
| MEDIUM | 6 | D1-02, D1-03, D1-04, D1-05, D1-07, D1-10 |
| LOW | 2 | D1-06, D1-08 |
| INFO | 1 | D1-09 |

No allowance: all ten are fixed before merge.

### What was run

| Check | Result |
|---|---|
| `./mvnw -B verify` (Testcontainers PostgreSQL 16) | BUILD SUCCESS, 99 tests, 0 failures (74 core, 23 starter, 2 sample) |
| `./mvnw -B clean verify -Prelease -Dgpg.skip=true` | BUILD SUCCESS, tests run |
| `CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh` | 63 fixed, 0 weak, exit 0 |
| `tools/check-private-references.sh --tree` and `--jars` | clean, both published modules, main jars included |
| `tools/check-third-party-licences.sh --check-unused` | clean, every carve-out needed |
| Sources jar contents after the full release build | sources, the module's own `.sql` resource, `META-INF/LICENSE`, `META-INF/NOTICE`, Maven metadata; nothing else |
| Advisory lock constants against the two sibling modules' actual literals | distinct, two-argument form, and the four recorded sibling values are accurate |

Release hygiene lines 1, 3 and 10 pass. Lines 22, 23, 26, 27, 34, 36, 40, 42, 43, 56, 60 and 62 of
the builder checklist were re-checked against the diff and hold. Lines 1-6 are correctly not
applicable: no `BigDecimal`, `double` or `float` appears in any main source yet.

### Examined and closed without a finding

- 200 virtual threads on one series, the year-boundary burst, the killed backend, the rollback and
  the two-threads-one-invoice race: re-run unchanged, all green, and the rollback and kill probes
  are real detectors (they are shown red against a real PostgreSQL sequence first).
- The "no transaction holds both locks" assertion records real statements through an intercepting
  data source and asserts both halves were exercised. It is a probe, not a grep.
- The append-only triggers and the documented grant set: tested as the runtime role and as the
  owner, so a grant cannot stand in for a trigger.
- `VOID_UNUSED`: mandatory screened reason, refused from `ISSUED` in both the enum and the trigger,
  no reuse (the source id stays unique), no HTTP endpoint auto-configured, asserted by a probe that
  scans the module for web annotations.
- The chain's canonical form is length-prefixed and keyed from row one with the key id inside the
  material; the anchor carries `keyed` immutably and the verifier keeps `EMPTY`, `INTACT`,
  `INTACT_UNKEYED`, `BROKEN`, `ANCHOR_MISMATCH` and `NO_ANCHOR` distinct.
- The chain secret is base64-only with a decoded length floor, never logged, and absent from every
  exception message. Startup refuses a keyed and an unkeyed configuration set together.
- The unscoped reads (the log, the anchor) are named and argued in their own test rather than
  hidden behind the seller-predicate rule. Not a finding.
- The residual "an out-of-band row left open is not chain-detectable" is stated in the public
  security notes in the same breath as what is protected. Honest.

---

### D1-01 - HIGH - the second fiscal year re-issues the first year's legal numbers

A rendered legal number is `prefix || zeroPad(counter, width)`. The prefix is one static property
and the counter restarts at 1 in each new fiscal year, and nothing anywhere binds the fiscal year to
the rendered number: not the renderer, not the series row, not a startup check. The uniqueness
constraint carries `fiscal_year`, so the database accepts it. The README's own quick start prints
`prefix: INV-2026-`, and no line of public text tells an operator that the prefix must be re-edited
every January - which an unattended service cannot do anyway, since the rollover was deliberately
made to work without a restart.

The result under the default configuration: the first invoice of 2027 is numbered `INV-2026-000001`,
which is the number of the first invoice of 2026. Two legal documents, one number, and the wrong
year printed on the second. This is the asset the module exists to protect.

It also falsifies two public claims: the security notes' "No duplicate number" row, and the branch's
own property statement that every allocated number has a *recorded, chained* disposition - the
verifier cross-check that backs it is keyed on the number (see D1-02), so the duplicate makes the
detection wrong as well as the number.

**Repro.** `probe_a_new_fiscal_year_does_not_reissue_the_previous_years_legal_number` - allocate for
2026, allocate for 2027 on the same seller and series, compare the rendered values. Fails: both are
`INV-2026-000001`. The existing year-boundary concurrency test asserts counters only, which is why
it is green beside this.

**Required change.** The invariant is: *for one (seller, series, mode), no two issued documents ever
carry the same rendered number, across fiscal years included.* It must hold on both paths that open
a series row - the startup seed and the rollover inside the allocation transaction - and it must be
a property of the row, not of a property file (see D1-03). Concretely, one of:

- the fiscal year is part of the rendered number, taken from the series key, with the prefix
  carrying an explicit year placeholder; a series configured to reset per fiscal year whose prefix
  has no year placeholder is refused at startup, by name; or
- the series does not reset per year (the existing continuous mode), which is already a supported
  configuration and needs no change.

Whichever is chosen, the maximum-width probe and the exhaustion bound must be recomputed against the
rendered form, the public claims updated, and the test above plus
`a_series_that_resets_per_year_without_a_year_in_the_number_is_refused_at_startup` added.

---

### D1-02 - MEDIUM - one chained event vouches for any row that shares a number and a state

The verifier's cross-check - the control the security notes name for the one path no scan and no
grant can close, the host's own raw JDBC - keys a disposition on
`sellerId | mode | legalNumber | state`. That is not a row identity. It omits the series, the fiscal
year and the source object id, so a single legitimate chained event covers every issuance row that
happens to share those four values.

With D1-01 present that is not hypothetical: a forged, already-disposed row inserted out of band in
the next fiscal year carries the same legal number and the same state as a legitimately voided row,
and the verifier reports `INTACT`.

**Repro.** `probe_the_disposition_cross_check_is_keyed_per_row_not_per_number` - on a fresh database,
allocate and void one invoice, then insert an out-of-band `VOID_UNUSED` row with the same legal
number in the following fiscal year. Expected `BROKEN`, observed `INTACT`.

**Required change.** Key the cross-check on the row's identity on both sides: seller, mode, series,
fiscal year, source object id, legal number and state. The disposed-rows query selects those
columns; the event side builds the same tuple. Fix it independently of D1-01 - it is the defence in
depth that must survive the next numbering change.

---

### D1-03 - MEDIUM - the rendered format follows today's properties, not the series row

The series row carries `prefix` and `width`, and a trigger declares both immutable "once the series
exists" because both are part of a legal number's format. The allocator never reads them: it renders
from the configured definition and only reads the row on the error path. So the trigger guards a
value nothing uses, and an edit to `einvoice.numbering.prefix` or `.width` plus a restart changes the
format of a live series silently - the exact defect the width check was added to prevent, reached by
an easier route than outgrowing the width.

**Repro.** `probe_the_rendered_number_follows_the_series_row_not_todays_properties` - allocate with
`INV-2026-`/6, rebuild the store with `ZZZ-`/8, allocate again. Expected `INV-2026-000002`, observed
`ZZZ-00000002`.

**Required change.** Render from the series row: read `prefix` and `width` in the same statement or
the same transaction as the counter, and refuse at startup when the configured definition disagrees
with an existing row for that series key, naming both values. The exhaustion bound and the
maximum-width probe follow the row too.

---

### D1-04 - MEDIUM - the allocation does not join the caller's transaction, so a host rollback keeps the number

The design says the adapter joins the host's transaction through the framework's data-source
utilities. The implementation opens its own connection from the `DataSource` and commits it. A host
that calls the allocator inside its own transaction and then rolls back keeps a committed, open
`NUMBERED` row and an advanced counter: a number consumed with no document, recoverable only by an
operator void. The public claim "a rollback or a crash never consumes a number" holds only for calls
made outside a host transaction, and the direct-call path is a documented, supported path.

**Repro.** `probe_a_host_transaction_rollback_does_not_leave_an_allocated_number` - open a connection,
turn off auto-commit, allocate through the store, roll back, look the invoice up. The row is there.

**Required change.** Participate in the caller's transaction when one is active (the framework's
data-source utilities, in the adapter that the starter wires), so the allocation commits and rolls
back with the unit of work that asked for it. This is a correction to match the reviewed design, not
a new mechanism. Add `an_allocation_inside_a_host_transaction_rolls_back_with_it` and keep a test for
the no-active-transaction case.

---

### D1-05 - MEDIUM - the allocation timeout property is documented and never applied

`einvoice.numbering.allocation-timeout` is declared, validated and published in the property table as
a lock timeout with a default of five seconds. It is read nowhere: no `lock_timeout`, no
`statement_timeout`, no `setQueryTimeout`. An allocation that meets a session holding the series row
waits forever, and under load every issuance thread joins the queue with no typed refusal and no
signal.

**Repro.** `probe_a_blocked_allocation_gives_up_after_the_configured_timeout` - hold the series row
with `SELECT ... FOR UPDATE` on a second connection and allocate. The call does not return within
twenty seconds.

**Required change.** Apply the property as `SET LOCAL lock_timeout` at the head of the allocation
transaction, map SQLState `55P03` to a typed, stable code distinct from "store unavailable", bound
the property above with a WARN past the bound, and test both the refusal and its code. A property
that names a control the code does not have is checklist line 63 in configuration form.

---

### D1-06 - LOW - the collapse-then-check refuses ASCII blanks only

The screening function is the module's single free-text path and already reaches a chained row and an
auditor-facing report through the void reason. Its collapse step uses the regex class `\s`, which in
Java is ASCII-only: a value made entirely of U+00A0, U+2007 or U+200B is not blank to the check and
is blank to every reader of the report and, later, of a document. That is precisely the defect the
collapse step was added for. Bidirectional overrides and zero-width joiners are likewise carried
through unchanged into a report an auditor reads.

**Repro.** `probe_a_value_of_unicode_whitespace_only_is_refused_by_the_screening_function` -
`screen("void reason", "  ​", 100)` returns three characters instead of refusing.

**Required change.** Collapse and check over Unicode whitespace and format characters, and refuse a
value that collapses to nothing under that wider definition; refuse the bidirectional overrides and
isolates outright in a screened value. One test per code point class.

---

### D1-07 - MEDIUM - the database view guard is blind in the deployment the grants document

The view guard closes the one path the metamodel scan cannot see, and it asks
`information_schema.view_table_usage`. That view filters its rows with `pg_has_role(owner, 'USAGE')`
on the *table's* owner. The module's own grant documentation tells the operator to run as a role that
does **not** own these tables - which is the right advice, since an owner can disable the triggers.
In that deployment the query returns nothing, the guard reports clean, and startup proceeds with a
host view reading the ledger. The existing test passes because it runs as the owner.

**Repro.** `probe_the_view_guard_sees_a_view_when_it_runs_as_the_runtime_role` - create the runtime
role with the documented grants, create a view over the issuance table as the owner, run the guard on
a connection opened as the runtime role. No refusal.

**Required change.** Ask the catalog instead: `pg_depend` joined to `pg_rewrite` and `pg_class` for
relations of kind `v` (and `m`) depending on our tables, which every role can read. Keep the
fail-closed behaviour on any SQL error. Keep the owner-side test and add the non-owner one above; a
guard that only works for the role the documentation tells you not to use is not a guard.

---

### D1-08 - LOW - `char(64) DEFAULT ''` gives an unset hash two spellings

`document_sha256` is `char(64)`, so an unset value reads back as sixty-four spaces while the same
logical value is `""` in memory. The public reader returns the padded form today, and in the next
pull request the two spellings meet inside hashed material, where the chain will read `BROKEN` on a
good row or, worse, agree by accident.

**Repro.** `probe_an_empty_document_hash_reads_back_as_an_empty_string` - allocate and read back;
`documentSha256()` is sixty-four spaces.

**Required change.** One representation. Make the column `varchar(64)` in both tables, or trim on
read at the single point that builds the value object, and assert an unset hash is empty.

---

### D1-09 - INFO - the chain secret is a getter on a configuration-properties bean with no exclusion assertion

Checklist line 44 asks for the secret to be *explicitly* excluded from the environment, the
configuration-properties and the heap-dump endpoints, proven by an assertion, rather than protected by
the framework's name sanitisation. The branch marks line 44 not applicable on the grounds that there
is no actuator surface yet, but the secret property itself exists now and is reachable through a
public getter on a bean in the host's context. The framework's current default sanitises values, so
this is an absent control rather than a demonstrated leak - which is exactly what line 44 refuses to
rely on.

**Required change.** A test with the actuator endpoints enabled and value display turned on that
asserts the secret never appears, or take the secret from the environment without holding it on a
bean. Then tick line 44 rather than marking it not applicable.

---

### D1-10 - MEDIUM - the default schema step cannot run as the role the grants document

`einvoice.initialize-schema` defaults to `true` and the bundled script runs `CREATE OR REPLACE
FUNCTION` unconditionally at every start. The documented runtime role has no DDL and no `CREATE` on
the schema, by design, so the default configuration and the documented secure deployment cannot both
be used. The failure is a generic store-unavailable error that names neither the property nor the
cause, so the cheapest way out of it is to run the application as the table owner - the one thing the
triggers cannot survive.

**Repro.** `probe_the_default_schema_step_works_as_the_documented_runtime_role` - create the role with
exactly the documented grants and run the schema step on a connection opened as that role: SQLState
`42501`, "permission denied for schema public", surfaced as `DEI-102 the e-invoice store is
unavailable`.

**Required change.** Make the step read before it writes: check `to_regclass`, `pg_proc` and
`pg_trigger` for what is already there and issue DDL only for what is missing, so a fully migrated
database needs no privilege at all - which is also what the integration table means by re-asserting
the triggers rather than assuming them. On SQLState `42501`, raise a typed configuration error that
names `einvoice.initialize-schema` and says the schema is installed by an owner role. Document the
two-role deployment in the quick start rather than only in the grants file.

---

### Fix list for pass 2

D1-01, D1-02, D1-03, D1-04, D1-05, D1-06, D1-07, D1-08, D1-09, D1-10 - all of them, each with the
named test. Pass 2 is the last pass on this branch.

---

## 2026-09-15 - pass 2 (final)

Commit `7f05c2e`. Pass 2 of 2: no third pass. Everything below was run, not read.

### Verdict

**NOT MERGEABLE**, with a three-item fix list that closes without another review pass. No HIGH and no
finding in the ten from pass 1: all ten are fixed and their probes are green. The three open items are
new surfaces created by the transaction-participation work itself, all three reproduced, all three
with a correction small enough to apply blind.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | — |
| MEDIUM | 2 | D1-11, D1-12 |
| LOW | 1 | D1-13 |
| INFO | 0 | — |

### What was run

| Check | Result |
|---|---|
| `./mvnw -B verify` | BUILD SUCCESS, 123 tests, 0 failures (87 core, 34 starter, 2 sample), coverage gates met |
| `./mvnw -B clean verify -Prelease -Dgpg.skip=true` | BUILD SUCCESS, tests run |
| `CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh` | 63 fixed, 0 weak, exit 0 |
| Reference guard, tree and final jars | clean; licence carve-out check clean |
| Sources jars after the release build | sources, the module's `.sql` resource, the starter's imports file, `META-INF/LICENSE`, `META-INF/NOTICE`, Maven metadata; nothing else. LICENSE and NOTICE in both main jars |
| Four mutations of my own | one passed, three failed; one of the three was my assertion shape, two are the findings below |

Release hygiene lines 1, 3 and 10 pass on the final artifacts.

### The ten pass-1 findings, re-verified

Each by its own probe, green, with the mechanism checked rather than the probe alone.

| Id | Probe | Mechanism I checked |
|---|---|---|
| D1-01 | `probe_a_new_fiscal_year_does_not_reissue_the_previous_years_legal_number` | The fiscal year is now inside the rendered number through an explicit placeholder; a resetting series whose prefix does not carry it is refused at startup; the resolved prefix is written into the series row once, at creation. Two different years cannot collide, because the year occupies fixed positions in a fixed-width rendering |
| D1-02 | `probe_the_disposition_cross_check_is_keyed_per_row_not_per_number` | The cross-check key is now the row's identity: seller, mode, series, fiscal year, source object id, number, state, on both sides |
| D1-03 | `probe_the_rendered_number_follows_the_series_row_not_todays_properties` | The allocation statement returns the row's own prefix and width and renders from them; the exhaustion bound is computed from the row's width in the same statement |
| D1-04 | `probe_a_host_transaction_rollback_does_not_leave_an_allocated_number` plus the transaction-template and `@Transactional` probes | Closed; see the section below |
| D1-05 | `probe_a_blocked_allocation_gives_up_after_the_configured_timeout` | The property is applied as a lock timeout, the timeout SQL state maps to its own code, and the caller's previous value is restored on the failing path too |
| D1-06 | `probe_a_value_of_unicode_whitespace_only_is_refused_by_the_screening_function` | Collapse now covers Unicode space separators and the invisible format characters; bidirectional overrides and isolates are refused outright |
| D1-07 | `probe_the_view_guard_sees_a_view_when_it_runs_as_the_runtime_role` | The guard reads the catalog dependency records rather than the owner-filtered information-schema view, so it sees a view as the non-owner runtime role |
| D1-08 | `probe_an_empty_document_hash_reads_back_as_an_empty_string` | One spelling, produced at the single point that builds the value object, and used by both the row reader and the event reader |
| D1-09 | `probe_the_chain_secret_is_excluded_by_name` | The secret and every retired key id are excluded by name, not by the framework's word-matching luck |
| D1-10 | `probe_the_default_schema_step_works_as_the_documented_runtime_role` | The step reads the catalog before it writes, so a fully installed schema needs no privilege; a permission failure on a real bootstrap is reported by name |

### The design review's seven items, verified

T-01 (reads join the unit of work, startup checks deliberately do not), T-02a (the ledger is a
transaction-bound resource), T-02b (the order is enforced, the exclusion is not, and the test is
renamed to the property it holds), T-03 (the "already touched the database" fact is observed by the
unit of work, not declared by each method), T-04 (the application-wide weaker mode is gone; the
per-call escape hatch is documented and pinned), T-05 (restore on both paths), T-06 (the per-call
assertion is now an enum for a log line), T-07 (both documentation lines) are all present and probed.

Two checks of my own beyond the thirteen:

- **The transaction manager a real host has.** The probes all wire a data-source transaction manager.
  The common deployment is a JPA one. I built a context with an entity-manager factory and a JPA
  transaction manager and rolled a host transaction back around an allocation:
  `probe_an_allocation_joins_a_jpa_transaction_managers_transaction` **passes** — the number and the
  counter both go back. This was the finding that would have made the whole fix silently ineffective
  for most hosts; it is not there.
- **Two units of work in one host transaction sharing the ledger.** Allocate, dispose, allocate again
  in one host transaction is refused with the lock-order code, which is the designed and correct
  answer for the deadlock-forming order.

### The two questions put to this review

**1. "Already touched the database" counts a locking read.** The conservative direction is right in
principle and I am not asking for it to be loosened generally — but as implemented it is wrong for the
two refusals a host is *supposed* to catch. See D1-12: it is fixable at the one place statements are
created, without going back to a flag each method must remember.

**2. A caller-owned connection cannot be rolled back by us, so the unit poisons itself.** Correct,
and closed with no finding. A module that rolled back a connection it does not own would destroy the
caller's unrelated work; refusing every further call on that unit, with a message that tells the
caller to roll back, is the honest maximum. The one thing missing is a test — D1-13.

---

#### D1-11 · MEDIUM · the lock ledger outlives the transaction it belongs to, and refuses a correct program

The ledger is bound as a transaction resource, which is the right scope, but nothing unbinds it when
the framework *suspends* that transaction. A nested new transaction therefore inherits the suspended
transaction's lock records: it is told it already holds the chain lock when it holds nothing at all,
and an allocation inside it is refused with the lock-order code and a message that is simply untrue of
that transaction. The refusal is a runtime failure on a correct host program, and the suspended
transaction's records are still there afterwards.

**Repro.** A host transaction disposes of a number (taking the chain lock), then calls a second bean
whose method requires a new transaction and allocates. Expected: success. Observed: the lock-order
refusal, raised from the inner transaction.

**Required change.** The synchronization that unbinds the ledger on completion also implements the
suspend and resume callbacks the framework already calls: unbind on suspend, rebind on resume. That is
the documented contract for a transaction-scoped resource and it is one method pair inside the
synchronization that already exists. Probe:
`probe_a_requires_new_allocation_does_not_inherit_the_outer_lock_ledger`, asserting the inner
allocation succeeds and its number is present.

---

#### D1-12 · MEDIUM · a refusal raised after a locking read makes the caller's own work uncommittable

The unit of work marks the caller's transaction rollback-only whenever any statement has executed,
and a `SELECT ... FOR UPDATE` counts. Two of this module's typed refusals are raised after exactly
that and before any write: "no number is allocated for that Stripe invoice" and the illegal-transition
refusal when a host tries to void a number that is already issued. Both are refusals a host is meant
to catch and handle. Today, catching one and carrying on ends in a rollback exception at commit: the
host's own unrelated work in that transaction is destroyed by a read.

Nothing was written in either case, so this is not fail-closed prudence; it is an inaccurate
predicate. The accurate one is available where the tracking already sits, and it stays *observed*
rather than declared, which is the property that made the tracking wrapper the right shape in the
first place.

**Repro.** A host transaction asks to void an unknown invoice, catches the typed refusal, and returns
normally. Observed: the commit fails with a rollback exception.

**Required change.** In the statement-tracking wrapper, classify at creation: a statement whose SQL
begins (after trimming) with `SELECT` or `SHOW` is a read and does not set the flag; everything else,
including any dynamically executed SQL, sets it. The three write statements of this module are
unaffected, including the two that use a returning clause and are executed as queries, because they do
not begin with `SELECT`. Keep the comment explaining why a locking read is not a write, and why a
future read-with-side-effects would have to be declared. The existing swallow-after-a-write probe must
stay green — it writes before it refuses. Probe:
`probe_a_pre_write_refusal_does_not_poison_the_host_transaction`.

While you are in the allocation path: record the series-row lock in the ledger as the *first* thing
the unit of work does, before the resume read and before the series-row insert, rather than just
before the counter statement. The lock-order refusal is then raised before anything at all has run,
which is the cheapest way to make it non-poisoning under any predicate.

---

#### D1-13 · LOW · the self-poisoning refusal of a caller-owned unit of work is never exercised

The behaviour this review approves in answer 2 above — a caller-owned unit of work that failed after
writing refuses every further call, with its own code, rather than pretending it can roll back a
connection it does not own — has no test. It is the one behaviour in the port a host drives by hand,
and it is the one that is unproven.

**Required change.** A probe that takes a caller-owned connection, drives it into the write-then-fail
state, and asserts that the next call on the same unit of work is refused with that code and that the
caller's connection is still the caller's to roll back:
`probe_a_caller_owned_unit_that_failed_after_writing_refuses_further_work`.

### Fix list

Three items, in order, each with its probe. No further review pass: run the full build, the probe
suite with the Maven probes enabled, and the reference guard, and merge when they are green.

1. **D1-11** — suspend and resume callbacks on the ledger synchronization; probe
   `probe_a_requires_new_allocation_does_not_inherit_the_outer_lock_ledger`.
2. **D1-12** — classify reads at statement creation in the tracking wrapper; move the series-row
   ledger entry to the head of the allocation unit of work; probe
   `probe_a_pre_write_refusal_does_not_poison_the_host_transaction`, with the existing
   swallow-after-a-write probe still green.
3. **D1-13** — probe `probe_a_caller_owned_unit_that_failed_after_writing_refuses_further_work`.
