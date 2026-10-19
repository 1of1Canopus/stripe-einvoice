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
