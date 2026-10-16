# Security review: `feat/document-series`

## Pass 1, 2026-10-17 (HEAD `3bb9dcc`)

**Verdict: MERGE WITH FIXES.** Two MEDIUM, four LOW, one INFO. No HIGH.

The format holds where the builder said it holds. The typed chain form, the pre-seam rule, the
series type cross-check and the corrected-invoice identity all resist the rewrites I tried. The
public allocator refuses a credit note before any lock. The two MEDIUM findings sit at the edges:
the by-hand upgrade path, and the two new triggers that read tables by short name.

Every fix below is a correction. One finding (D-A1-C03) needs a lock, so it is a design stop
(a short design page before code).

### Numbers

Measured on this branch, real PostgreSQL 16 in Testcontainers, Docker up, full `./mvnw verify`.

| | |
|---|---|
| `./mvnw verify` | exit 0, twice (before and after the mutation runs) |
| Tests | 622 run (core 482, starter 132, sample 8), 0 failures, 0 errors, 0 skipped |
| In-tree probe classes | 34, all green |
| Builder mutations re-run | 15 (17 runs): 14 red, M14 green as the builder stated |
| Reviewer mutations | 3: 2 red, 1 green (explained below) |
| Reviewer probes this pass | 10 test cases in one class, 9 red on this branch, 1 green (it guards a control no other probe guards) |

The reviewer probes are in the internal probe folder. The fix pass adds them to
`stripe-einvoice-core/src/test/java/com/housedevinci/einvoice/adapter/jdbc/` unchanged, before
it touches production code.

### Builder mutations, re-run one by one

Each mutation was applied alone, the named probe run, the tree restored.

| # | Result | Note |
|---|---|---|
| M1 | red, 2 failures | |
| M2 | red | |
| M3a, M3b, M3c | red, red, red | |
| M4 | red | |
| M5 | red | first run failed on a connection setup error, not on the probe; re-run red |
| M6, M7 | red, red | |
| M8 | red | the series guard refuses the backward date, so invoicing stops: the exact failure D-PS1-38 named |
| M9 to M13 | red | |
| M14 | green | the insert guard refuses, same code, counter rolls back. Two controls, as stated |
| M15 | red | |

### Reviewer mutations

| # | Mutation | Result |
|---|---|---|
| C1 | `IssuanceChain.verify` without its pre-seam clause (an `ei1`/`ei2h` row must read 380 and carry no target) | the builder's `probe_a_pre_seam_event_that_claims_a_credit_note_type_is_broken` stays **green**. My probe for D-A1-C07 turns **red** |
| C2 | verifier without "no older form after a newer one" | green. Not a finding: the version is the first hashed element, so a relabelled row breaks its own hash. I found no case where this rule is the only line. It stays as a second line |
| C3 | insert guard without its seller and mode clause | red (`probe_the_database_refuses_a_bad_target_for_every_writer`) |

### Findings

#### D-A1-C01 · MEDIUM · the by-hand upgrade stamps format 2 after it refused

**What goes wrong.** The upgrade note says: run the script "by hand with `psql`", and set the zone
first. `psql -f` with its defaults runs each statement in autocommit and goes on after an error.
So the script's own refusals do not stop it:

- Zone forgotten: the backfill raises `EIU02` ("nothing was changed"). psql goes on. The last
  statement stamps `einvoice_schema` at format 2. The series with rows keeps `last_issue_date`
  NULL.
- Duplicate legal numbers: the pre-check raises `EIU01`, the unique index fails, psql goes on, and
  format 2 is stamped. The schema runs without the legal-number uniqueness that format 2 promises.

Then the gate accepts it. `verifySchema` (with `initialize-schema=false`) checks only the version.
`initializeSchema` returns early because the version reads 2 and every trigger exists. So startup
vouches for a schema the upgrade never finished. The advisory lock at the top of the script is a
transaction lock, so by hand it also ends after the first statement.

**Repro.** A real `psql -f` against a 0.1.0 database, as the note says, both cases: version row
reads `2|2`, the series date is NULL, the unique index count is 0. Probes:
`probe_a_by_hand_upgrade_refused_for_the_zone_is_never_accepted_as_format_2`,
`probe_a_by_hand_upgrade_refused_for_duplicate_numbers_is_never_accepted_as_format_2` (they run
the script statement by statement, in autocommit, going on after errors, the way psql does).

**Fix (fix pass).**
1. `schema-postgresql.sql`: the version stamp moves into one `DO` block, at the end, that first
   checks every format-2 postcondition and raises a new `EIU03` (mapped to `DEI-134` with the
   other two) naming the first one missing: the index `einvoice_issuance_number_unique`, the
   series primary key on `(seller_id, document_type, series, fiscal_year, mode)`, the five `CHECK`
   constraints, the three new triggers, no default on the three `document_type` columns, and no
   series with rows and a NULL `last_issue_date`. The stamp is in the same block, so a refusal can
   never be followed by the stamp, whatever runs the script. `JdbcSupport.UPGRADE_REFUSALS` gains
   `EIU03`.
2. `docs/index.md`, step 2: give the one exact by-hand command, run once on a real psql by the fix
   pass: `PGOPTIONS='-c einvoice.tax_zone=Europe/Paris' psql -X -v ON_ERROR_STOP=1
   --single-transaction -f schema-postgresql.sql`. Say why each flag is there.
3. The `EIU01`/`EIU02` messages say "this run changed nothing" only when it is true. With fix 1
   and 2 it is true under the documented command; keep the words, and add "if run as documented".

#### D-A1-C02 · MEDIUM · the runtime role defeats both new insert guards with temporary tables

**What goes wrong.** `einvoice_issuance_insert_guard` and `einvoice_series_insert_guard` read
`einvoice_issuance`, `einvoice_issuance_event` and `einvoice_series` by short name. PostgreSQL
resolves a short table name through the caller's `search_path`, and `pg_temp` comes first. The
runtime role holds `TEMPORARY` on the database by default. So:

- Two temporary tables that say "ISSUED" make the database accept a credit note against a number
  that was never issued. The same works for another seller's invoice or a credit note. The
  D-CNS-S12 bound "for every writer" does not hold for the one role the triggers exist to bind.
  The verifier then reports nothing: it checks the link's identity, not the bound.
- One empty temporary `einvoice_series` hides the type a name already has, so the S-09 binding
  (one series name, one type) is bypassed.

This is pattern NR-1 (pin the search path). The older guards read only `NEW` and `OLD`, so they
are not exposed. These two are the first guards in this module that read a table.

**Repro.** As a role with exactly `docs/schema-grants.sql`: `CREATE TEMP TABLE einvoice_issuance
AS SELECT * FROM public.einvoice_issuance WHERE id = <target>`, set it `ISSUED` with a hash, a temp
`einvoice_issuance_event` with one matching `ISSUED` row, then `INSERT INTO public.einvoice_issuance
(... '381', <target>)`. Accepted; the target reads `NUMBERED`. Without the temp tables the same
insert is refused (`EIT01`). Probes:
`probe_the_runtime_role_cannot_shadow_the_target_bound_with_temporary_tables`,
`probe_the_runtime_role_cannot_shadow_the_series_name_binding_with_a_temporary_table`.

**Fix (fix pass).** In both functions, qualify every table read with the trigger's own schema:
`EXECUTE format('SELECT ... FROM %I.einvoice_issuance WHERE id = $1', TG_TABLE_SCHEMA) INTO t
USING NEW.corrects_issuance_id`, and the same for the event read and the series read. This also
keeps a non-default install schema right. Pinning each function's `search_path` to `pg_catalog,
<schema>, pg_temp` from the script is an accepted alternative; the probes decide. Add one sentence
under "Triggers" in the script: a guard that reads a table qualifies it with `TG_TABLE_SCHEMA`.

#### D-A1-C03 · LOW · the S-09 binding does not hold under concurrent inserts · DESIGN STOP

**What goes wrong.** The series insert guard checks "this name has no other type" with a plain
`EXISTS`. Under READ COMMITTED it cannot see another transaction's row that is not committed yet.
No index covers "same name, other year, other type". Two writers that create the name for two
fiscal years at once, with two types, both commit.

**Repro.** Transaction 1 inserts `RACE`/2026/`380` and waits. Transaction 2 inserts `RACE`/2027/
`381` and commits. Transaction 1 commits. Two types on one name. Probe:
`probe_one_series_name_never_binds_two_types_under_concurrent_inserts`.

**Why a design stop.** The fix needs a serialisation point: a lock or a new table. A lock must fit
the lock-ledger order (target, series, chain); a table is a new structure. Either one is a
mechanism other code depends on. The builder writes a short design page (one paragraph is
enough), and the security review reads it before code.

**The property.** No two `einvoice_series` rows of one `(seller_id, mode, series)` with different
`document_type` ever both commit, under any interleaving. **Paths:** the allocator's
new-fiscal-year row (`createSeriesRowForNewFiscalYear`), the starter's seed at startup, and a raw
`INSERT` by the runtime role. The page states where the new lock sits in the ledger order, or why
a table needs none.

#### D-A1-C04 · LOW · a far issue date from a direct caller moves the series date for good

**What goes wrong.** `AllocationRequest` takes `issueDate` from the caller and never compares it
with `issuedAt`. `last_issue_date` only goes forward, and the runtime role cannot lower it. One
direct call to `NumberAllocator.allocate` with `issuedAt` 2026-03-10 and `issueDate` 2099-01-01 is
numbered and moves the series date to 2099. After that, every invoice of the series raises an open
`DEI-127`, and in a credit-note series every credit note is refused until the series ends. No time
zone puts 2026-03-10T10:00Z on 1 January 2099.

**Repro.** Probe `probe_an_issue_date_no_zone_can_derive_from_issued_at_never_moves_the_series_date`.

**Fix (fix pass).** In the `AllocationRequest` compact constructor, refuse with `INVALID` when
`issueDate` is before `LocalDate.ofInstant(issuedAt, ZoneOffset.MIN)` or after
`LocalDate.ofInstant(issuedAt, ZoneOffset.MAX)`. The record still does not need the zone: any
real zone falls inside that window.

#### D-A1-C05 · LOW · offset tax zones are converted with the sign reversed in the backfill

**What goes wrong.** The starter accepts any `ZoneId` as `einvoice.seller.tax-zone`, for example
`+02:00` or `UTC+02:00`, and passes its id to the backfill. PostgreSQL reads these as POSIX zones,
where the sign means the opposite. An invoice at 23:00 UTC on 20 January is the 21st in Java and
on the document, and the 20th in the backfilled `last_issue_date`. The script also says "an unknown
zone fails here", but PostgreSQL accepts invented names such as `Nowhere+5`. Region ids
(`Europe/Paris`, `America/New_York`, `Australia/Lord_Howe`) agree between Java and PostgreSQL on
all 33 DST boundary instants I tried, so this is about offset ids and invented ids only.

**Repro.** Probe `probe_the_backfilled_date_is_the_date_the_starter_derives_in_the_same_zone`,
two cases (`+02:00`, `UTC+02:00`): the series reads 2026-01-20, Java derives 2026-01-21.

**Fix (fix pass).** In the backfill block, replace `PERFORM now() AT TIME ZONE zone` with a
refusal (`EIU02`, new message) when `zone` is not a `name` in `pg_catalog.pg_timezone_names`. The
offset forms are not listed there, and `Etc/GMT-2` (listed) means the same in both. The message
asks for a region id. Correct the script comment and question 36 (f) to say so.

#### D-A1-C06 · LOW · a corrected-invoice identity planted on an invoice event is neither hashed nor refused

**What goes wrong.** The event table's `CHECK` covers `corrects_issuance_id` only. The reader
builds a target only when that id is set. So the five other `corrects_*` columns of an invoice
event can carry a full corrected-invoice identity that nothing hashes, refuses or reads. Someone
who reads the table sees an invoice event that names a corrected invoice, and the verifier
reports INTACT. A credit-note event with the id set and another column NULL fails to decode, which
is right (BROKEN).

**Repro.** As the owner, set the five columns on an issued invoice's event. Verifier: INTACT.
Probe `probe_a_correction_identity_planted_on_an_invoice_event_is_refused_or_broken`.

**Fix (fix pass).** `einvoice_issuance_event_correction_shape` becomes: for `380`, all six
`corrects_*` columns are NULL; for `381`, all six are NOT NULL. `JdbcIssuanceStore.readEvent`
treats any `corrects_*` value on a row whose id is NULL as a decode error (an `EInvoiceException`,
which the verifier already reports as BROKEN).

#### D-A1-C07 · INFO · the pre-seam clause in `IssuanceChain.verify` has no probe that fails without it

**What goes wrong.** Mutation C1 removes the clause "an `ei1`/`ei2h` row must read 380 and carry
no target". The builder's probe stays green, because it rewrites the event only, and the row
cross-check catches that. When the event and the row are rewritten the same way and the series row
still reads 380, the clause is the only line: without it, a pre-seam invoice turned into a credit
note verifies INTACT.

**Repro.** Probe `probe_a_pre_seam_invoice_rewritten_into_a_credit_note_on_row_and_event_is_broken`:
green on this branch, red under C1.

**Fix (fix pass).** Add the probe. Add C1 to the mutation table in the pull-request body.

### Questions 36 (a) to (f)

| | Ruling |
|---|---|
| (a) `issueDate` required | accepted, with D-A1-C04 |
| (b) credit-note series properties in A2 | accepted; secure default: no default series name or prefix, startup refuses a 381 kind with no series |
| (c) archived bytes re-read in B2 | accepted. In A1 the row and chain check is enough because `ISSUED` is terminal and the checked columns are immutable (verified: the issuance guard has no edge out of `ISSUED`) |
| (d) commit subject | noted, nothing to rule |
| (e) `DEI-133` to `DEI-136` in section 4.3 | accepted, under D-PS1-39 (open, any build review) |
| (f) PostgreSQL zone data for the one-time backfill | accepted for region ids only, with D-A1-C05 |

### Is A1 safe on its own?

Yes, once the fixes are in. A free install has no path to a credit-note number through the public
API: `allocate` refuses any type but 380 before any lock (M12 red). `allocateTyped` is
package-private; reaching it means code placed in this module's own package on purpose, which is
the same as raw SQL. The database accepts a 381 row from the runtime role by design, because Pro
writes the same tables; a SQL-level refusal cannot tell free from Pro. That is why D-A1-C02 matters:
the database bound is the line that holds there. No target row lock is needed in A1, because no
check here can change after it passed. The 0.2.0 tag stays blocked by D-PS1-27 (the date-order
design), as ruled in the spec review.

### Attempted, not a finding

- **Version row.** As the documented role: `INSERT`, `UPDATE`, `DELETE` and `TRUNCATE` are refused
  by grant (42501); as the owner, the trigger refuses a lower version, a delete and a truncate. A
  second row is refused by `CHECK (id = 1)`. Drop and recreate needs the owner, who is outside the
  trigger model like for every table; startup then reads 42P01 and refuses with `DEI-136`, or the
  script re-creates format 2.
- **Bad targets through Java and the database.** Self-reference (not found in a `BEFORE INSERT`),
  a voided, burned or never-issued number, a credit note, another seller, another mode, a stored
  hash that left its chain: all refused twice, same code, no number consumed. What the trigger
  compares: the target row's stored `document_sha256` with the latest chained event of its number,
  which must be `ISSUED`. The archived bytes are B2.
- **Later owner rewrite of the target's series or number.** The target's own events stop agreeing
  with its row, and the credit note's hashed identity stops agreeing with the join: BROKEN twice.
- **Replay of a 0.1.0 entry.** A copied row is refused by the unique hash. A relabelled row breaks
  its own hash (C2 above). Mixed `ei2h`/`ei4h` trails verify, with per-form counts.
- **`DEI-127` reaching the operator.** Written in the allocation transaction, listed by the
  findings endpoint, never filtered by code. Acknowledging records a decision and deletes nothing.
- **Upgrade over JDBC.** One transaction; an interrupted run leaves 0.1.0; a third run changes no
  constraint; a host-named primary key is found. Two series with one name and two types cannot
  exist in 0.1.0 (no type column), so the upgrade never meets them.
- **Uniqueness per seller and mode.** A 0.1.0 database that renamed a series and kept its prefix
  has duplicates; the starter's path refuses with `DEI-134`, a count and no identifiers.
- **One tax zone for all sellers in the backfill.** The starter configures one seller, and the docs
  describe no shared schema between sellers. Not a supported deployment.
- **DST.** Covered under D-A1-C05: region ids agree.
- **`min_compatible_version`.** Startup refuses any version but 2, which is the strict reading.
