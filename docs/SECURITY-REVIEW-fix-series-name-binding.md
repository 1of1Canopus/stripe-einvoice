# Security review - branch `fix/series-name-binding`

Adversarial review of pull request 40 (D-A1-C03: one series name binds one document type, with a
name table and a composite foreign key), head `c8636bd`. Pass 1 of at most 2. Scope: the schema
changes (name table, fill, `EIU04` pre-check, foreign key, final-block checks), the series stage of
the store (name step, `40001` mapping), the presence check, the grants file, and the edited probes.

## 2026-10-19 - pass 1

### Verdict

**MERGE WITH FIXES** - one finding, INFO, with a probe. No HIGH, no MEDIUM, no LOW.

The mechanism holds. Example: two transactions open series `RACE` at once, one as an invoice
(`380`) for 2026 and one as a credit note (`381`) for 2027. The second one waits on the name row's
primary key and fails, at every isolation level, and the committed state has one name row and one
type. I re-ran the build, the ten mutations, and six attacks of my own on a scratch PostgreSQL 16.14
(the pinned test image). None of the attacks committed a second type for one name.

The one finding is a message. At SERIALIZABLE, a serialization failure at the name step does not
always mean "someone created this series". The store says it does.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | |
| MEDIUM | 0 | |
| LOW | 0 | |
| INFO | 1 | D-PR40-1 |

### Numbers

| Check | Result |
|---|---|
| `./mvnw -B clean verify` at `c8636bd`, Docker up | BUILD SUCCESS: core 589, starter 137, sample 8; 0 failures, 0 errors, 0 skipped; coverage gate met in all three modules |
| Existing `CipherProbe*` tests | all green inside the run above |
| Mutations N1 to N8, N3b, N4b, re-run one at a time | see the mutation table below |
| New probe `CipherProbePr40Test` | RED on `c8636bd` (1 of 1) |

### Rulings asked for

**The restored probe and its one setup line (QUESTIONS 39, D-C03-B1): accepted, not a weakening.**
The assertion is unchanged ("one distinct type for `RACE`"). Without the line the first writer's
year row has no name row, so nothing commits, the count is 0, and the probe fails on every correct
design. With the line it is green. What the probe now proves is narrower than its name: the second
writer inserts only a year row, so it fails on the foreign key at once (`23503`) and never waits. It
is a "raw year row with no name row" check, not a race check. The mutation run shows it: under N2
(name key widened to include the type) the restored probe stays green and test 1b goes red. That is
the split the design page agreed in R2: test 1 states the property, test 1b proves the race is
closed. Both stay in the suite.

**The probe edits: none weakens a probe.**
- `CipherProbePr33Pass3Test`, planted operator: `EIS01` became `23503`. The read in the guard that
  the operator attacked is gone; the binding is the foreign key, whose check is PostgreSQL's own
  qualified query. The attack still fails, now on the control that replaced the old one.
- `CipherProbePr33Pass3Test`, other schema: the probe now inserts the `acct` name row, then expects
  `23503` for the `381` row and one committed row. Same property, same schema-qualification test.
- `CipherProbeDocumentSeriesTest`: the free-text "serves one type" became the constraint names
  (`einvoice_series_name_binding` for the same year, `einvoice_series_name_fk` for another year).
  Stricter: each case now pins which control refused.
- The two flip probes (`CipherProbeSchemaUpgradeTest`, `CipherProbeTypedChainTest`): the owner must
  now disable the name table's guard too and rewrite both tables in one statement, because the
  foreign key refuses the old one-table flip. The assertion (verifier says BROKEN) is unchanged. A
  flip that silently did nothing would leave the chain intact and fail the assertion, so neither
  probe can go green by accident.

**The owner's one-statement flip (QUESTIONS 40, D-C03-B2): accepted as outside the threat model.**
Example: as table owner, disable both guards, then `WITH n AS (UPDATE einvoice_series_name SET
document_type = '381' ...) UPDATE einvoice_series SET document_type = '381' ...`. The foreign key
passes because it is checked at the end of the statement. This needs the table owner disabling the
module's own guards, which is outside the model for every table of this module. The chain verifier
reports it as BROKEN (two probes measure that), and `SECURITY-NOTES.md` says so. No code change.

**The foreign-key trigger check in the final block: keep.** It costs one query. It is what makes
`convalidated` mean something after a superuser `DISABLE TRIGGER ALL`. One limit, for the record,
not a finding: it runs when the script runs. The presence check (`SCHEMA_FULLY_PRESENT`) only asks
whether the foreign key exists, so a superuser who disables its triggers after the stamp is not seen
at the next start. A superuser is outside the model, and the notes only claim the stamp refusal.

### Mutations

Each mutation applied alone to a clean tree, the named tests run, then the tree restored. Results:

| # | Mutation | Red (mine) | Green under it |
|---|---|---|---|
| N1 | no foreign key (creation, final check, presence clause) | 6 of 6: restored probe, runtime-role test, user-triggers-disabled test, test 1 at three levels | |
| N2 | name key on `(seller_id, mode, series, document_type)` | test 1b at three levels (3 of 3) | the restored probe (as ruled above) |
| N3 | `EIU04` pre-check off | `a_database_binding_one_name_to_two_types_refuses_the_upgrade_and_changes_nothing` | |
| N3b | fill without `HAVING count(DISTINCT document_type) = 1` | `a_by_hand_run_past_the_refusal_binds_no_type_and_is_not_stamped` | |
| N4 | no type comparison in the name step | test 2 at READ COMMITTED (1 of 3) | test 2 at RR and SER, as expected (they never reach the comparison) |
| N4b | no `40001` mapping | test 2 at RR and SER (2 of 3) | test 2 at READ COMMITTED, as expected |
| N5 | `UPDATE` granted on the name table | runtime-role test | |
| N6 | `SET LOCAL lock_timeout` moved after the series stage | bounded-wait test | |
| N7 | final block without the two name-table guards and the foreign-key trigger check | 3 of 3 | |
| N8 | presence check without the name table, its guards and the foreign key | `a_format_2_database_without_the_name_table_runs_the_script_again_and_is_refused` | |

All ten match the builder's table. My own first N7 patch turned off only the count clause and left
the `tgenabled` clause live (`false AND a OR b` is `b`), so the foreign-key trigger test stayed
green; with both clauses off it is red. That was my mutation, not the control (the same slip as the
builder's first N7).

### Attacks

**A1. Owner deletes or retypes the name row inside the allocator's window: holds.**
Example: the runtime role creates name row `W` as `380` with no year rows. A second session at each
level reads the name (`380`), sleeps, then inserts the `380` year row. In between, the owner
disables the guard and deletes the name row, or sets it to `381`. Six runs (three levels, two
actions):

| Level | Delete | Retype |
|---|---|---|
| READ COMMITTED | `23503`, no year row | `23503`, no year row |
| REPEATABLE READ | `40001`, no year row | `40001`, no year row |
| SERIALIZABLE | `40001`, no year row | `40001`, no year row |

The year row never commits without a matching name row. This also covers `ON CONFLICT DO NOTHING`
at REPEATABLE READ next to a concurrent delete: the store's read comes first, so the insert only
runs when the read saw no row, and then a deleted row cannot conflict. Outside the model anyway
(owner with the guard disabled).

**A2. Names in two cases, or with trailing spaces: not a finding.**
Example: `INV` as `380` and `inv` as `381` for one seller and mode both bind (measured as the runtime
role). Java and PostgreSQL agree that these are two names: `SeriesId` is an exact record key, the
column uses the default deterministic collation, and the fill groups by the exact string. Each has
its own prefix, and the legal number is rendered from the prefix, not from the series name. So the
type of a number is still readable from its series. Trailing spaces: `Identifiers.validate` refuses
any whitespace, so a configured name never has one; a raw `'CASE '` name row from the runtime role
is accepted by the database but no configuration can address it, so it binds nothing that numbers.

**A3. Charset and length: consistent.** `Identifiers.validate("series name", ..., 32)`: 1 to 32
bytes from `[A-Za-z0-9-_.:+]`. Both tables declare `series varchar(32)`, `seller_id varchar(64)`,
`mode varchar(4)`, so the foreign key's columns have identical types, and a name that passes the
Java check always fits.

**A4. Scope of the `40001` mapping: one finding (D-PR40-1).** The mapping covers the name step and
the year-row insert, as the design page says. A `40001` raised at commit is outside it and leaves as
`DEI-102`, which the pipeline also retries. But a `40001` at the name step is not always a
concurrent creation of this series: see D-PR40-1.

**A5. `EIU04` against a two-type name added after the fill: refused by the foreign key.** Measured
as the runtime role on a stamped schema: name row `CASE` as `380`, year row 2026 as `380` commits,
year row 2027 as `381` fails `23503`, a second name row `CASE` as `381` fails `23505` (also with
`ON CONFLICT` on the four-column key, which is not the arbiter). `UPDATE` and `DELETE` on the name
table fail `42501`. In a by-hand run, a two-type name written between the fill and the foreign key
makes `ADD CONSTRAINT` fail, so the final block refuses the stamp: it fails closed.

**A6. `lock_timeout` ordering (N6) in a host transaction: holds.** `SET LOCAL lock_timeout` runs
before the resume read, so the name step is bounded; N6 makes the bounded-wait test red. In a joined
transaction the caller's value is restored in `finally`. On `CONFIG` at READ COMMITTED the host's
transaction is still usable (the `ON CONFLICT` insert returned no row, no error) and gets its value
back. On `40001` or `55P03` the host's transaction is already aborted and the restore is logged, as
before. One note, not a finding of this PR: the start-up seed (`openSeries`) runs in autocommit
with no `lock_timeout`, so it can wait on another transaction's uncommitted first creation of the
same name. Its year-row insert already had this shape before the PR; the name step adds no new kind.

### Findings

#### D-PR40-1 (INFO) - a serialization failure is reported as "this series was created by a concurrent transaction"

**What goes wrong.** `JdbcIssuanceStore.createSeriesRowForNewFiscalYear` maps every `40001` from the
name step and the year-row insert to `DEI-117` with the text "series X was created by a concurrent
transaction under REPEATABLE READ or SERIALIZABLE isolation, and this transaction can no longer see
it". At SERIALIZABLE, PostgreSQL also raises `40001` at that insert for a read/write dependency
between two transactions that open two different series. Then nobody created X, and the message
sends an operator looking for a writer that does not exist. The code (`DEI-117`, retryable) is
right; only the stated cause is wrong.

**Example.** Host B starts a SERIALIZABLE transaction. Host A opens series `GAMMA` and commits. Host
B then opens series `DELTA` and is aborted at the name step. `DELTA` has no name row anywhere, and
the message says "series DELTA was created by a concurrent transaction".

**Repro.** `CipherProbePr40Test#probe_a_serialization_failure_is_not_reported_as_a_concurrent_creation_of_this_series`.
RED on `c8636bd`: code `DEI-117`, zero `DELTA` name rows, message contains "was created by a
concurrent transaction". Also measured in psql with the name table alone: the second transaction's
`INSERT ... ON CONFLICT DO NOTHING` fails with "could not serialize access due to read/write
dependencies among transactions".

**Fix (fix pass).**
- `JdbcIssuanceStore.createSeriesRowForNewFiscalYear`: keep `ALLOCATION_TIMEOUT` and the fixed text
  with no driver text, but state only what is known. For example: "series X could not be opened: a
  concurrent transaction conflicted with this one (serialization failure under REPEATABLE READ or
  SERIALIZABLE). Nothing was numbered; retry in a new transaction." Update the comment above it.
- `NumberAllocator` Javadoc: "When two transactions create one new series at once" becomes "when a
  concurrent transaction conflicts with the opening of a series".
- `SECURITY-NOTES.md`, the D-A1-C03 bullet: add that any serialization failure while a series is
  opened ends as `DEI-117`, not only a concurrent creation of the same series.
- Tests: the probe above goes green; test 2 keeps its `contains("concurrent")` assertion.

### Editorial note for the fix pass (not a finding)

`SECURITY-NOTES.md`, the D-A1-C03 bullet: "Run the application as a role that owns nothing. Under
REPEATABLE READ ..." sits on one line past the wrap width; rewrap it when the bullet is edited.

### Not reviewed

Release, CI and packaging files: not touched by this PR.

## Pass 2 (2026-10-19)

Re-verification at `2694520`. Scope: the D-PR40-1 fix (`c467097`), the merge of main (`a22b5df`),
and the builder's fix for D-C03-B6 (`cd41d8f` test, `2694520` fix): the name insert and the upgrade
fill now use `ON CONFLICT DO NOTHING` with no conflict target (no named index, so every unique index
of the table is checked).

### Verdict

**MERGE WITH FIXES.** No HIGH, no MEDIUM, no LOW. One new INFO, D-PR40-4. D-PR40-1 is closed.
D-C03-B6 is confirmed fixed.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | |
| MEDIUM | 0 | |
| LOW | 0 | |
| INFO | 1 | D-PR40-4 |

### Numbers

| Check | Result |
|---|---|
| `./mvnw -B clean verify` at `2694520`, Docker up, Maven idle before start | BUILD SUCCESS (with `-Dmaven.test.failure.ignore=true` so the new probe does not stop the reactor) |
| Tests | 741 run: core 596, starter 137, sample 8; 1 failure (the D-PR40-4 probe), 0 errors, 0 skipped |
| Tests without the new probe file | 737, as the builder reported |
| JaCoCo gate | met in all three modules |
| `CipherProbePr40Test` (D-PR40-1) | green at `2694520` |
| `SeriesNameRaceTest` at `cd41d8f` (the test, before the fix) | 1 of 16 red: the new deterministic test fails with `DEI-102 ... (SQLState 23505)` |
| `SeriesNameRaceTest` at `2694520` | 16 of 16 green |
| Mutation N9 (old target `(seller_id, mode, series)` restored on `2694520`) | the deterministic test red; reverted, tree clean |
| `ConcurrentAllocationTest`, five separate runs at `2694520` | 5 of 5 green |
| `CipherProbePr40Pass2Test` (new, 4 tests) | 3 green (confirmations), 1 red (D-PR40-4) |

### Prior findings

- **D-PR40-1 (INFO): closed.** The `40001` branch now says "a concurrent transaction conflicted with
  this one while series X was being opened". The probe is green. The `NumberAllocator` Javadoc and
  the `SECURITY-NOTES.md` bullet match. The over-long line from the editorial note is rewrapped.
- **D-C03-B6 (LOW, builder's bug): fixed.** Red before the fix, green after, red again under N9.
  One extra measurement: on `cd41d8f` the same interleaving at REPEATABLE READ and SERIALIZABLE also
  ended in `DEI-102 (23505)`. So the old target lost at every level, not only at READ COMMITTED.
  On `2694520` both levels end in `DEI-117` with cause `40001` (see A3).

### Rulings from pass 1 (QUESTIONS 39 and 40)

Both stand. Nothing in `c467097..2694520` touches the restored probe, its setup line, or the owner's
one-statement flip. QUESTIONS 39: the probe is still a "raw year row with no name row" check, and
test 1b is still the race proof (green at all three levels). QUESTIONS 40: still outside the
threat model; still no code change.

### Attacks

**A1. Does "no target" now swallow a conflict that should raise? One finding (D-PR40-4).** With no
target, PostgreSQL treats every unique index and every exclusion constraint of the table as an
arbiter. CHECK, NOT NULL and foreign keys are not arbiters and still raise. A deferrable unique
index makes every such insert fail at once, so it cannot hide anything. On the shipped schema the
table has two arbiters: the primary key `(seller_id, mode, series)` and the type key
`(seller_id, mode, series, document_type)`. The type key's columns contain all of the primary
key's, so any clash on it is also a clash on the primary key. Nothing that should raise is
swallowed today. `CipherProbePr40Pass2Test#confirm_the_name_table_has_only_indexes_the_primary_key_implies`
pins this from the catalog (exactly those two, not partial, no expression, each covering the
primary key): green.

The weakness is in the branch the insert falls into when it did nothing and the read by primary key
finds no row. That branch says "being created by a concurrent transaction, retry" (`DEI-117`). On
the shipped schema it is unreachable: name rows are append-only (trigger guard), READ COMMITTED
re-reads with a new snapshot and sees the committed winner, and REPEATABLE READ and SERIALIZABLE
raise `40001` instead of doing nothing (A3). The only way in is an index the primary key does not
imply. Then the allocator reports a race that does not exist, and the retry it asks for fails the
same way for ever. With the old target the same index gave `DEI-102 (23505)`: loud, if unclear. See
D-PR40-4.

**Rule for future indexes on `einvoice_series_name`.** Every unique index or exclusion constraint on
this table must be non-partial, have no expression, and contain all three primary-key columns. Any
other uniqueness rule belongs on another table or in a check that raises. The schema comment at the
type key and the catalog test (fix 2 of D-PR40-4) carry the rule.

**A2. The test-only index trick: test code only. Confirmed.** `c03b_pause` and `c03b_pause_first`
appear only in `SeriesNameRaceTest.java` (`git grep` over the tree at `2694520`). The schema script,
`JdbcSupport` and the starter have no trace of it. The helper builds its own database
(`freshDatabase`), so the shared test database never carries the index. The function is `IMMUTABLE`
while it takes a lock; that is a lie to the planner, acceptable in a disposable test database.

**A3. The read-after-insert window at REPEATABLE READ and SERIALIZABLE: closed, no finding.**
Question: writer A's snapshot is older than winner B's commit. Does A's insert "do nothing" and its
second read then see nothing? Measured with the same held-writer trick, A's pool set to each level:
A takes its snapshot at the first read, is held inside its insert, B opens the same name and type
and commits, A is released. At both levels PostgreSQL does not do nothing: the clash is with a row
A's snapshot cannot see, so it raises `40001`. The store maps it to `DEI-117`, A writes no year row,
one year row exists. `confirm_a_snapshot_older_than_the_winner_gets_40001_not_an_empty_read`, green
at `repeatable read` and `serializable`. On `cd41d8f` the same two cases end in `DEI-102 (23505)`.
At READ COMMITTED the second read takes a new snapshot and sees B's row; the race test proves both
the same-type case (goes on) and the other-type case (`CONFIG`, no `381` row).

**A4. Can the upgrade fill now skip a name it should insert? No finding.** The fill inserts one row
per name whose year rows carry exactly one type. It can only meet a row that already exists for the
name: a second run of the script, or a name row the allocator wrote. Because the type key implies
the primary key, every row skipped with no target is a row the old target `(seller_id, mode,
series)` also skipped. The set of skipped rows is identical. If the existing row carries the other
type, the foreign key cannot be added and the final block refuses the stamp, as in pass 1. The only
difference is a fill racing an allocator's uncommitted insert of another type: the old target could
raise `23505` from the type key (refused upgrade, retry); now it waits on the primary key and then
skips, and the foreign-key step decides. The script runs with every older instance stopped, so this
is not a reachable path in operation. No mutation was needed for the fill: the change cannot alter
its result.

**A5. Other `ON CONFLICT DO NOTHING` with no target in the store. Not in this PR, note only.** The
year-row insert (`CREATE_SERIES_ROW`) already had no target before this PR. Its table has an index
the primary key does not imply (`einvoice_series_name_binding`, no `document_type`). A clash there
means another type in the same year for the same name, which the name binding now refuses earlier
with `CONFIG`. Not reachable; no finding on this branch.

### Findings

#### D-PR40-4 (INFO) - an index the primary key does not imply turns the name insert into a false, endless retry

**What goes wrong.** `JdbcIssuanceStore.bindSeriesName`: when the no-target insert does nothing and
the second read by primary key finds no row, the store throws `ALLOCATION_TIMEOUT` (`DEI-117`,
retryable) with "series X is being created by a concurrent transaction". The comment says "only a
concurrent creation explains it". That is false. On the shipped schema nothing reaches this branch
(A1, A3). The only way in is a unique or exclusion index on `einvoice_series_name` that the primary
key does not imply. That is a permanent schema condition, not a race. The retry never succeeds.

**Example.** An operator adds `CREATE UNIQUE INDEX ... ON einvoice_series_name (seller_id, series)`
so a series name cannot be reused across modes. The seller already used `RACE` in test mode. The
first live invoice in `RACE` is refused with `DEI-117` "being created by a concurrent transaction;
retry". Every retry gets the same answer. Before `2694520` it got `DEI-102 (23505)`.

**Repro.** `CipherProbePr40Pass2Test#probe_a_foreign_unique_index_turns_the_name_insert_into_a_false_retry`.
RED on `2694520`: code `DEI-117`, message contains "concurrent". GREEN on `cd41d8f` (the old target
raised `23505`), which shows the change of behaviour comes from this PR.

**Fix (fix pass).**
1. `JdbcIssuanceStore.bindSeriesName`, the `bound.isEmpty()` branch after the insert: throw
   `ErrorCodes.SCHEMA_NOT_PREPARED`, not `ALLOCATION_TIMEOUT`, with fixed text and no driver text,
   for example: "the name row of series X was not written and no row for that name exists: the
   table einvoice_series_name carries a unique or exclusion index its primary key does not imply.
   Nothing was numbered. Remove that index; retrying will not help." Replace the comment: on the
   shipped schema this branch is unreachable (append-only rows; READ COMMITTED re-reads; REPEATABLE
   READ and SERIALIZABLE raise `40001`), so reaching it means a schema defect.
2. Move `CipherProbePr40Pass2Test#confirm_the_name_table_has_only_indexes_the_primary_key_implies`
   into `SeriesNameRaceTest` as
   `the_name_table_has_only_unique_indexes_its_primary_key_implies`, unchanged. It is the guard for
   the rule in A1.
3. `schema-postgresql.sql`, the comment at the type key: state the rule from A1 in one sentence
   ("any further unique or exclusion index on this table must contain seller_id, mode and series,
   and be neither partial nor on an expression").
4. Tests: the probe above goes green; the two race tests and `CipherProbePr40Test` stay green.

### Not reviewed

Release, CI and packaging files: not touched by this PR.
