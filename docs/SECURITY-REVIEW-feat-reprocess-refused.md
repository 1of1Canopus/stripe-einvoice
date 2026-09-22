# Security review - `feat/reprocess-refused`

Adversarial review of the privileged reprocess of one refused event, and of the chained failure
disposition that closes the prior D7-03. Roles, not names: "the design" is the builder's design
page for this mechanism, held in the module's internal folder; "the build" is the work on this
branch. The design had no prior review, so the design and the code are reviewed together here.

---

## Pass 1 - 2026-09-21

**HEAD reviewed:** `50d1cb3`
**Build:** full `verify` on a clean worktree of `feat/reprocess-refused`, Docker up, Testcontainers
real, licence gate and reference guard included. Exit 0.
**Surefire total: 510 tests run, 0 failures, 0 errors, 0 skipped** (core 391, starter 115, sample 4).
**Probes added by this review:** `CipherProbePr9Test`, 5 probes, **4 red, 1 green**.

### Verdict

**NOT MERGEABLE** - 1 HIGH, 2 MEDIUM, 1 LOW, 1 INFO.

The mechanism's shape is right and most of it survives attack. Eligibility really is one state; the
re-open really is one conditional statement, and its loser really does run nothing; the numbered
guard really does refuse; there is no HTTP surface, no actuator operation and no scheduled caller,
and a mapping refusal is genuinely unreachable from the sweeper's `DUE` query. The number-burning
oracle does not exist: every run that ends back at `FAILED_MAPPING` allocated nothing, and every run
that allocated ends in a state this path refuses, so one event can never consume two numbers.

What does not hold is the one control the mechanism exists for. "Who asked and why is recorded
durably" is the sentence the design, the changelog, the security notes and the docs page all make,
and it is defeated by an operator reason the caller chooses - deterministically, with no race -
leaving the event re-opened, due, and finished by the unattended sweeper into a real legal number
with no actor and no reason anywhere in the database. That is D9-01 and it is why this is not
mergeable. D9-02 is a regression the D7-03 change introduced on the burn path. D9-03 is the
durability of the record itself, which the design defers to an open question; on a no-allowance
branch a deferral is not a closure, and the fix is a mechanism, so it is a design stop.

### Findings

| Id | Severity | One line |
|----|----------|----------|
| D9-01 | HIGH | An operator reason the acknowledgement refuses re-opens the event and then throws, and the sweeper issues a legal number with no record of who asked. |
| D9-02 | MEDIUM | A rule id from a host validator that is not an identifier throws inside the burn transaction, out of `process()`, stranding a consumed number on a row that is due for ever. |
| D9-03 | MEDIUM (design stop) | The whole durable record of a privileged reprocess is one mutable row the next call overwrites, and the `DEI-264` marker is erased by the successful run. |
| D9-04 | LOW | After D7-03 the verifier demands a chained event for `FAILED_VALIDATION` while `IssuanceState.disposed()` answers false for it: two definitions of "settled" in the layer whose point is one transition table. |
| D9-05 | INFO | `actor` accepts `:`, so the one stored line `actor + ": " + reason` cannot be split back into who and why without ambiguity. |

---

#### D9-01 - HIGH - a chosen reason turns the privileged path into an unrecorded one

`IssuanceReprocess.reprocess` commits the re-open first and records afterwards, in two further
transactions of its own:

```
inbound.reopenForReprocess(...)   // committed: the row is RECEIVED, attempts 0, next_attempt_at NULL
findings.record(...)              // own transaction
findings.acknowledge(..., request.justification(), ...)   // own transaction, screens again
unitOfWork.process(eventId)
```

`ReprocessRequest` screens `reason` at 500 characters. What is stored is not `reason`, it is
`justification()` - `actor + ": " + reason`, then `substring(0, 500)`. The truncation cuts inside
the reason, and `JdbcFindingStore.acknowledge` screens the composed line again with the same screen.
A reason that is entirely valid on its own (a paired surrogate near the bound) becomes an unpaired
surrogate after truncation, `ScreenedText.screen` refuses it, and the exception is thrown *after*
the row is already re-opened and committed.

**Repro** (`probe_a_crafted_reason_leaves_the_event_reopened_and_numbered_with_no_record`, red):
actor `ops-jane`, reason `"a".repeat(489) + U+1F600 + "b".repeat(9)` - 500 characters, accepted by
the request's own screen. After the call: the event sits in `RECEIVED`, the finding row exists with
no acknowledgement, and `acknowledged(eventId)` is zero. A re-opened row has `next_attempt_at NULL`
and `RECEIVED` is the first branch of `DUE`, so it is due immediately: running the due list, as the
sweeper does unattended, allocates a legal number and issues the document. Probe output on this
HEAD: *"a legal number was consumed with no record of who re-opened the event"*.

Nothing exotic is needed for the same outcome: any failure of `record` or `acknowledge`, or a crash
between the re-open and the acknowledgement, leaves exactly the same state. The design's own
sentence - "recorded before the run, so a crash in the middle still leaves who asked and why" - is
not true of a crash between the re-open and the record, which is the window this order creates.

**Exact fix** (the builder, in the run that built this mechanism - it is inside the mechanism, not a
correction for the fix pass):

1. `ReprocessRequest` - validate the value that will actually be stored, at the boundary, before
   anything is written. Compose the justification in the compact constructor and screen *that*
   string with `ScreenedText.screen` at `MAX_REASON_CHARS`; refuse the request when the composed
   line does not fit, rather than truncating it. A silent `substring` on an operator's stated reason
   is itself a record that says something the operator did not.
2. `IssuanceReprocess.reprocess` - the re-open and the record must commit together or not at all.
   The re-open, `findings.record` and `findings.acknowledge` go into one unit of work, with the
   re-open last inside it, so a refusal to record cannot leave a re-opened row; `process` stays
   outside it, as it is today.
3. Probe names to make green: `probe_a_crafted_reason_leaves_the_event_reopened_and_numbered_with_no_record`,
   plus a new one of the builder's own that makes `FindingStore.record` throw and asserts the row
   is still `FAILED_MAPPING` and not in `due()`.

---

#### D9-02 - MEDIUM - the D7-03 chained burn turns a host validator's rule id into an unbounded loop

New on this branch: `IssuanceEvent.failed` calls `Identifiers.validate("failure rule id", ruleId,
64)` - the identifier charset, `[A-Za-z0-9-_.:+]` - and it is called from inside `markFailed`'s
transaction. Until this branch `markFailed` ignored `ruleId` entirely, so no value of it could fail.

`DocumentValidator.Report.ruleId` is free text from a port the module itself documents as "always a
host's own code in 0.1.0", and the site that burns the number is the one call in `issue()` that is
not wrapped:

```java
writer.markFailed(..., IssuanceState.FAILED_VALIDATION, report.ruleId());
inbound.transition(event.eventId(), InboundState.FAILED_ISSUANCE, code, report.ruleId(), ...);
```

A host validator reporting `BR-DE-15 (fatal)` - a space and parentheses, the shape this module's own
`En16931DocumentValidator` builds for its findings list one line above where it takes the rule id -
throws `EInvoiceException` `DEI-101` out of `markFailed`, out of `issue()`, out of `process()`. The
number is allocated, the issuance row stays `NUMBERED`, no chained event exists, and the inbound row
stays `MAPPED` with `next_attempt_at NULL`, which the `DUE` query re-picks immediately and for ever:
the D2-01 pathology this module already fixed once, restored on the burn path.

**Repro** (`probe_a_hostile_rule_id_from_a_host_validator_strands_a_burned_number`, red):
`IssuanceTestHarness.createWith(TestValidators.failing("BR-DE-15 (fatal)"))`, one finalised invoice,
one `process`. Today: throws out of `process`. Expected: `FAILED_ISSUANCE` on the event,
`FAILED_VALIDATION` on the number, and not due.

**Exact fix** (the builder). `IssuanceEvent.failed` must not refuse a value it can record safely: keep the
bound and the screening, drop the *charset* refusal for this field by screening the rule id the way
free text is screened elsewhere (`ScreenedText.screen("failure rule id", ruleId, 64)`), and when
even that refuses, fall back to the module's own stable code for the disposition rather than
throwing - a chained event with a generic code is a record; an exception is not. Either way
`markFailed` must not be able to throw a new exception type at a call site that has no catch: add
the same wrapping `issue()` gives the renderer and the validator, or state in `IssuanceWriter` that
`markFailed` never throws on the rule id and prove it. Probe to make green:
`probe_a_hostile_rule_id_from_a_host_validator_strands_a_burned_number`.

---

#### D9-03 - MEDIUM - DESIGN STOP - what the record proves to an auditor

The design's section 5 makes the findings row the durable record and defers the chained operator log
to an open question. Two consequences, both reproduced:

- **The next call erases the previous one.** `JdbcFindingStore.record` upserts on
  `(seller, mode, code, subject)` and `acknowledge` overwrites `ack_reason`. Two privileged calls on
  one event - which the mechanism explicitly allows, since a run that refuses again leaves the event
  eligible - leave one row carrying only the last operator's line. Probe output: stored value is
  `"ops-bob: ..."`; `ops-alice` and their stated reason are gone.
- **The `DEI-264` marker does not survive the run it describes.** The successful `process` transitions
  the row to `COMPLETED` with an empty code, so the public claim "the re-opened row carries
  `DEI-264`, so an event running again says why rather than looking like a retry" is true only while
  the call is in flight. Afterwards nothing on the event row distinguishes a privileged re-run from
  an ordinary delivery.

Add that `einvoice_finding` is a mutable table by necessity, while the issuance chain is
trigger-protected: the sole record of the one action that can turn a recorded refusal into a legal
document is the least protected row in the schema.

**Repro:** `probe_the_record_of_a_privileged_reprocess_is_erased_and_overwritten`, red on both
assertions.

**Ruling: DESIGN STOP.** I am not prescribing the mechanism in a fix list. The property that must
hold: *every privileged reprocess call that re-opens a row leaves a record of the actor, the reason
and the time that no later call of the same kind can overwrite, and that survives the run it
describes.* The paths it must cover: the successful run, the run that refuses again and re-opens a
second time, the run that throws mid-pipeline, and the process that dies between the re-open and the
commit. The builder writes a one-page design (append-only operator record, or the reprocess joining the
existing chain, or a second chain - the builder's call, with the lock order against `appendEvent`
stated), reviewed before any code. QUESTIONS 28 is not a closure on a no-allowance branch: either the
design lands in this PR or the mechanism ships with its record loud in the public text as
best-effort, which is a public claim change and goes past the claims register first. My preference is the former.

---

#### D9-04 - LOW - two definitions of a settled disposition

`IssuanceEventReader.disposedIssuances()` now selects `ISSUED, VOID_UNUSED, FAILED_VALIDATION`, and
the verifier reports `BROKEN` for a `FAILED_VALIDATION` row with no agreeing chained event.
`IssuanceState.disposed()`, documented as "the number's disposition is settled and recorded in the
chain", still answers `false` for `FAILED_VALIDATION`. The predicate has no main-source caller today,
which is exactly why it will drift: the next state that becomes chained will be added in one place
and not the other, and the one that goes stale is the one with the SQL in it.

**Repro:** `probe_the_domain_and_the_verifier_disagree_on_a_burned_number`, red - the row is in
`disposedIssuances()` and `IssuanceState.FAILED_VALIDATION.disposed()` is false.

**Exact fix** (the fix pass). `IssuanceState.disposed()` returns true for `FAILED_VALIDATION` and its javadoc
says which states are chained and why `FAILED_ARCHIVE` is not; `JdbcIssuanceStore.disposedIssuances`
builds its `IN` list from the enum (`Arrays.stream(IssuanceState.values()).filter(IssuanceState::disposed)`)
instead of a literal, so the two cannot disagree again. `IssuanceStateTest` gains the
`FAILED_VALIDATION` assertion; probe to make green: `probe_the_domain_and_the_verifier_disagree_on_a_burned_number`.
Check `open()` stays false for it (it is, and the reconciliation stuck check depends on that).

---

#### D9-05 - INFO - the stored line cannot be split back into who and why

`Identifiers.validate` allows `:`, so an actor may be `ops:jane`, and the record is the single line
`actor + ": " + reason`. A reader of the findings table cannot recover the actor unambiguously, and
an actor who wants to can make the line read as though someone else asked. No probe: the ambiguity
is in the format, not in a behaviour that can be asserted without asserting the format itself.
**Exact fix:** carried by the D9-03 design - the record holds the actor and the reason as two values,
not one concatenation. If D9-03's design lands with separate fields, this closes with it.

### Attempted and not a finding

- **Reprocess as a number-burning oracle.** Not reproducible. One event cannot consume two numbers:
  every outcome that allocates leaves a state this path refuses (`COMPLETED`, `FAILED_ISSUANCE`),
  and every outcome that stays eligible (`FAILED_MAPPING`) allocated nothing, which the builder's own
  probe 1 asserts against the series counter. Repeated calls on a still-refusing event cost Stripe
  fetches, not numbers.
- **Number burned while refusing.** Not reproducible. Both refusal branches return before
  `reopenForReprocess` and write nothing at all.
- **The re-open race, and the sweeper inside the same event.** Held. The conditional `UPDATE`
  gives one winner; the loser reads the row again and runs nothing. A re-opened row *is* due, which
  the design states deliberately, so I ran the case the builder did not: re-open, then two
  concurrent `process` calls on the same event id, started on a barrier
  (`probe_a_reopened_row_run_twice_at_once_still_numbers_once`, **green**) - both reach `COMPLETED`,
  one number, one chained event, one archived object.
- **Reachability from a schedule.** Enforced, not promised. `FAILED_MAPPING` appears in neither
  branch of the `DUE` SQL; no main source outside the auto-configuration mentions `IssuanceReprocess`;
  the starter has no `@WriteOperation` or `@DeleteOperation` anywhere, and the only operator endpoint
  is the read-only findings endpoint. The bean is callable by any bean in the host context, which is
  the void's posture and is stated in the public text.
- **A forged burn satisfying the widened cross-check.** Not reproducible. The cross-check key is the
  full row identity (seller, mode, series, fiscal year, source object id, legal number, state), so an
  out-of-band `FAILED_VALIDATION` row cannot borrow another row's chained event; the builder's own
  `probe_a_burned_row_written_out_of_band_is_reported_broken` covers it and stays green. The rule id
  is inside the canonical form (`voidRuleId` field) and therefore inside the hash - `IssuanceEvent.failed`
  copies `base.hash()` into an intermediate value, but `appendEvent` links through `chain.link`, which
  recomputes it after the rule id is set.
- **`FAILED_ARCHIVE` deliberately unchained.** The design's word "retryable" is imprecise - only
  `ARCHIVE_UNAVAILABLE` gets a backoff, so a permission denied or a missing bucket parks the event in
  `FAILED_ISSUANCE` with no next attempt and the number sits in `FAILED_ARCHIVE` indefinitely - but the
  gap is not silent: `ReconciliationSweep` raises `RECON_STUCK_ISSUANCE` for any `open()` row past its
  window, and the operator's void is chained. Not a finding; the notes would read truer as "retryable,
  and reported as stuck when it is not retried".
- **Second call after success under a changed seller profile.** No difference: eligibility is decided
  by the inbound state, which is `COMPLETED`, so the correction cannot re-run a passed event.
  Builder's probe 2 covers it; I re-ran it unchanged.

### Checked again and still clean

- All prior `CipherProbe*` tests re-run unchanged on this HEAD: green inside the 510.
- Public text on the branch carries roles only; no agent or person name in CHANGELOG, README,
  SECURITY-NOTES or `docs/`. The internal design page is not referenced by name.
- `domain` imports remain JDK-only; `IssuanceReprocess` is in `application` and takes ports only.
- Licence gate, reference guard and the release-pipeline probes pass in the full `verify`.

---

## Pass 2 - 2026-09-22

**HEAD reviewed:** `3efc0a0`
**Build:** full `verify` on a clean worktree of `feat/reprocess-refused`, Docker up, Testcontainers
real, licence gate and reference guard included. Exit 0.
**Surefire total: 524 tests run, 0 failures, 0 errors, 0 skipped** (core 405, starter 115, sample 4).
**Release-pipeline probe suite with the Maven probes enabled: 74 fixed, 0 weak.** The four probes
reported weak by the verification run are the four that need Maven on the path; with it they pass.
**Reference guard:** tree clean, self-test all cases correct.
**Probes added by this pass:** 8, in two files - 5 core, 3 starter - **5 green, 3 red**.

### Verdict

**MERGE WITH FIXES** - 2 MEDIUM, 1 LOW. No HIGH; nothing here blocks the design.

Every pass-1 finding is closed, each confirmed by its own probe flipping green and by breaking the
control again myself. The three new findings are all in the layer *around* the new record rather
than in it: the reconciliation read that was added for it has no tenant filter, the conclusion code
for an untyped failure says something that is not true, and the new table was never added to the one
list the starter's three schema guards read. All three are corrections, not mechanisms, so they go to
the fix pass and not to the builder, and each comes with a red probe that turns green - a mechanical
re-verification the verifier can run. I am not asking for a third adversarial pass.

### Pass-1 findings: closed

| Id | Probe that is now green | Mutation that turns it red again |
|----|-------------------------|----------------------------------|
| D9-01 | `probe_a_crafted_reason_leaves_the_event_reopened_and_numbered_with_no_record`, `probe_a_failure_between_the_reopen_and_the_record_leaves_neither`, `probe_a_bound_length_reason_is_stored_byte_identical`, `probe_an_over_bound_actor_or_reason_is_refused_before_any_write` | M1 |
| D9-02 | `probe_a_hostile_rule_id_from_a_host_validator_strands_a_burned_number`, `probe_every_hostile_rule_id_shape_still_burns_and_chains` | M2 |
| D9-03 | `probe_the_record_of_a_privileged_reprocess_is_erased_and_overwritten`, the nine `CipherProbeReprocessRecordTest` probes, `probe_the_reprocess_record_is_append_only_by_grant_and_by_trigger` | M1, M3 |
| D9-04 | `probe_the_domain_and_the_verifier_disagree_on_a_burned_number`, `probe_the_disposed_predicate_and_the_verifier_list_agree_for_every_state` | M4 |
| D9-05 | closed by construction: `justification()` is gone, `actor` and `reason` are separate columns, nothing is concatenated and nothing has to be split back | M1 |

R-01 verified directly: a 500-character reason with a paired surrogate at the bound is stored
byte-identical in its own column; 501 characters, or a 65-character actor, is refused with a typed
error and leaves zero rows in both tables with the event still `FAILED_MAPPING`. The screen bounds
`nfc.length()` in UTF-16 units while PostgreSQL's `char_length` counts code points, so the Java bound
is the stricter of the two and a bound-length reason can never overflow `varchar(500)` - checked, not
assumed. R-03 verified: an orphan past the window raises `DEI-276` exactly once per subject and a
matched pair raises nothing. R-04 verified: an injected upstream failure still leaves `REQUESTED` +
`CONCLUDED` and the exception still reaches the caller. R-05 verified: the partial unique index
refuses a second conclusion, the `CHECK` bounds are in the DDL, and a full sweep, reconcile, purge
and chain-verify cycle leaves the table's row count unchanged.

**Mutations: 5 of 5 re-run by me, each red, each restored.** M1 - the `REQUESTED` insert removed from
the re-open's transaction: `probe_a_crafted_reason_...` fails with *"a legal number was consumed with
no record of who re-opened the event"*, 2 failures and 7 errors across the record probes. M2 -
`RuleIds.normalise` replaced by `Identifiers.validate` in `IssuanceEvent.failed`: **green**, because
`IssuanceUnitOfWork` normalises first; breaking *both* points turns both rule-id probes red
(*"failure rule id is 2000 bytes, max 64"*). That is defence in depth working, and it is also why a
mutation of one site is not evidence about the control. M3 - the two guard rows removed from the
schema's trigger list: `probe_the_reprocess_record_is_append_only_by_grant_and_by_trigger` red. M4 -
`disposed()` back to two states: the D9-04 probe, my agreement probe and `IssuanceStateTest` all red.
M5 - the two `conclude` calls removed from the catch blocks: `probe_a_throwing_pipeline_still_
concludes_with_the_error_code` red.

### The trigger ruling (R-02)

The verification run reported no trigger in the `einvoice_reprocess_request` DDL. **That reading is
wrong and the promise is met.** The triggers are not written beside the `CREATE TABLE`; they are two
rows in the schema's single trigger-registration loop
(`einvoice_reprocess_append_only`, `BEFORE UPDATE OR DELETE`, and `einvoice_reprocess_no_truncate`,
`BEFORE TRUNCATE`, both bound to `einvoice_append_only()`), which is where every other guard on this
schema is declared. I did not settle it by reading: `probe_the_reprocess_record_is_append_only_by_
grant_and_by_trigger` creates a role holding exactly the two grants `docs/schema-grants.sql`
prescribes for this table (`SELECT, INSERT` on the table, `USAGE` on its sequence), appends as that
role, and then runs `UPDATE`, `DELETE` and `TRUNCATE` twice - once as that role, refused by the
grant, and once as the owner, where the trigger's own `append-only` message is what comes back. Both
halves hold, and M3 shows the probe is really watching the trigger. R-02's other half - the wording -
is in `SECURITY-NOTES.md` and `docs/index.md` as ruled, the words "audit trail" and "tamper-evident"
appear nowhere near this table, `DEI-265`'s removal is stated in the CHANGELOG, and claims-register
row D1 carries the sentence.

### New findings

| Id | Severity | One line |
|----|----------|----------|
| P2-01 | MEDIUM | The new reconciliation read is the only sweep query with no seller and no mode filter, so a sweep raises `DEI-276` under its own seller for another seller's event id. |
| P2-02 | LOW | A run that fails with an untyped exception is concluded in the record as `DEI-200`, "the inbound event could not be read", which is not what happened. |
| P2-03 | MEDIUM | `einvoice_reprocess_request` was never added to `EInvoiceTables.ALL`, so the persistence-mapping guard, the database-view guard and the schema-owner warning all skip the one table whose only protection is a trigger. |

---

#### P2-01 - MEDIUM - the sweep's new read crosses the tenant boundary

`ReprocessLedger.unfinished(Instant, int)` takes no seller and no mode, and
`JdbcReprocessLedger.UNFINISHED` filters on `kind`, `at` and the absence of a conclusion only. Every
other read `ReconciliationSweep` makes is scoped - `reader.findBySource(configuration.sellerId(),
configuration.mode(), ...)`, `inSeries(key, ...)` on a `SeriesKey` that carries both, `archive.list`
on a `mode/seller` prefix - and `record(...)` writes the finding under `configuration.sellerId()`.
So the row is read across tenants and the finding is written under the reader's own tenant.

**Repro** (`probe_an_unfinished_reprocess_of_another_seller_is_reported_under_this_one`, red):
seller A refuses an event, re-opens it through the ledger and never concludes (a dead process);
seller B sweeps a day later. `findingRows(A's event id)` under **B's** seller is 1, and
`result.unfinishedReprocesses()` is 1. Expected zero for both.

Three consequences, in order of how much they matter: B's operators read A's event ids through the
findings endpoint, which is a cross-tenant identifier disclosure on the one operator-facing HTTP
surface this module has; the finding is attributed to the wrong seller, so an auditor reading B's
findings sees a privileged action that never happened under B; and because the read is `LIMIT
pageSize` `ORDER BY seq`, a tenant with many old orphans crowds every other tenant's newer orphan out
of the page permanently - a missed alert, not only noise. The same three hold between `live` and
`test` of a single seller, which is a configuration this module's `mode` column exists to support.

**Exact fix** (the fix pass). `ReprocessLedger.unfinished(String sellerId, Mode mode, Instant
olderThan, int limit)`; `JdbcReprocessLedger.UNFINISHED` gains `AND r.seller_id = ? AND r.mode = ?`;
`ReconciliationSweep` passes `configuration.sellerId()` and `configuration.mode()`, as it does for
every other read it makes. `forEvent` may stay as it is - it is keyed on an event id, which is
already tenant-specific, and it has no caller that crosses a tenant. Probe to make green:
`probe_an_unfinished_reprocess_of_another_seller_is_reported_under_this_one`.

#### P2-02 - LOW - the record states a cause that did not happen

`IssuanceReprocess.reprocess` concludes a `RuntimeException` that is not an `EInvoiceException` with
`ErrorCodes.INBOUND_UNREADABLE` (`DEI-200`), which the codes table defines as "no inbound event is
recorded under that id" and which this very method throws for exactly that case, seven lines above.
The `CONCLUDED` row is the evidence of a privileged action; a code in it that names a different
failure is a wrong statement in the one record that is meant to outlive the run.

**Repro** (`probe_a_non_typed_failure_is_not_concluded_as_an_unreadable_inbound_event`, red): an
upstream client that throws `IllegalStateException` during the re-fetch leaves a `CONCLUDED` row with
`outcome_code = DEI-200` for an event that was read perfectly well.

**Exact fix** (the fix pass). Conclude an untyped failure with a code that means what happened - a
free `DEI-2xx` in the reprocess block of `ErrorCodes`, documented like its neighbours as "the
reprocess run failed with an error this module does not type" - and never with a code whose
documented meaning is a different failure. While there: the two `conclude` calls sit in `catch`
blocks, so a ledger that throws while concluding replaces the original exception on its way to the
caller; wrap them so the original is what propagates and the ledger failure is logged. Probe to make
green: `probe_a_non_typed_failure_is_not_concluded_as_an_unreadable_inbound_event`.

#### P2-03 - MEDIUM - the new table is outside every guard that exists for exactly this

`EInvoiceTables` says of itself: *"The tables this module owns, in one place, so no scan can be right
about a subset of them."* `einvoice_reprocess_request` is not in it. Three controls read that list
and therefore skip the new table:

- `PersistenceMappingGuard` - a host `@Entity` mapped over the reprocess record starts cleanly. The
  trigger still refuses its `UPDATE` and `DELETE`, but `INSERT` is granted, and an insert is enough:
  a `CONCLUDED` row appended for an orphaned `REQUESTED` silences `DEI-276` for ever, and the
  partial unique index guarantees it can be done exactly once, which is all an attacker needs.
- `DatabaseViewGuard` - a host view over the table is not refused, so every actor and reason is
  readable through a mapping this module refuses on all six of its other tables.
- `EInvoiceStartupCheck`'s owner warning - the operator is told at every start that the runtime role
  owns six tables and can disable their triggers, and is not told it about the seventh. That warning
  is the remedy `SECURITY-NOTES.md` points at for this exact table ("Run with a role that has only
  the grants in `docs/schema-grants.sql`"), so the one table whose entire protection is a trigger is
  the one table whose trigger nobody is warned about.

**Repro** (`CipherProbePr9Pass2StarterTest`, all three red):
`probe_the_reprocess_record_is_one_of_the_tables_this_module_guards`,
`probe_a_host_entity_mapped_over_the_reprocess_record_refuses_startup`,
`probe_a_database_view_over_the_reprocess_record_refuses_startup`.

**Exact fix** (the fix pass). Add `REPROCESS_REQUEST = "einvoice_reprocess_request"` to
`EInvoiceTables` and to `ALL`. Nothing else changes: the module maps no entity of its own, so the
mapping guard has nothing of ours to refuse, and the view guard and the owner warning pick the table
up from the same list. Probes to make green: the three above.

### Attempted and not a finding

- **Forging a conclusion through the granted `INSERT`.** Real, and it is the module's stated trust
  boundary rather than a defect of this change: anything holding the runtime role's credentials can
  append a row to any append-only table here, which is why the issuance chain is hashed and why the
  public text says this table is not. The part that *is* actionable is the missing mapping guard,
  filed as P2-03.
- **Truncation of the reason, in any form.** Gone. `justification()` is deleted, both values are
  screened at their own bound in the record's compact constructor, and the adapter stores exactly
  what the screen returned - asserted byte-identical at the bound, in NFC, in its own column.
- **Overflowing `varchar(500)` with astral characters.** Not reproducible; the Java bound counts
  UTF-16 units and PostgreSQL counts code points, so the screen is strictly the tighter of the two.
- **`RuleIds.normalise` producing something unsafe.** Not reproducible over five hostile shapes -
  blank, 2000 characters, `NUL`/`BEL`/`DEL`, a lone high surrogate followed by SQL, a bidi override
  pair. Every one is collapsed to the identifier charset, bounded at 64, stripped of trailing
  fillers, and what reaches the chained row equals `RuleIds.normalise` of the input. No shape throws
  out of `process()`, every one leaves `FAILED_ISSUANCE` on the event, `FAILED_VALIDATION` on the
  number, a chained event, and nothing due.
- **The chain hash over a normalised rule id.** Unchanged from pass 1: `appendEvent` links through
  `chain.link`, which recomputes the hash after the rule id is set, so the value inside the canonical
  form is the value that was stored.
- **`disposedStates()` as a SQL injection surface.** The list is built from `IssuanceState.values()`,
  a closed enum whose names are compiler-controlled. No external input reaches it.
- **A second `CONCLUDED` racing the first.** The partial unique index settles it at the database; the
  builder's probe covers it and it stays green.
- **The reprocess table in the retention purge.** Unchanged across a full sweep, reconcile, purge and
  chain-verify cycle; the purge addresses a different table and holds no grant to delete from this
  one.

### Checked again and still clean

- All prior `CipherProbe*` tests re-run unchanged on this HEAD: green inside the 524.
- Public text carries roles only; no agent or person name in CHANGELOG, README, SECURITY-NOTES,
  `docs/` or the PR body. Reference guard clean on the tree and on its own self-test.
- `domain` imports remain JDK-only; `RuleIds` is a JDK-only class in `domain`, `ReprocessLedger` is a
  port in `application`, `JdbcReprocessLedger` an adapter in `adapter.jdbc`.
- No new HTTP surface, no new actuator operation, no scheduled caller of `IssuanceReprocess`.
- Licence gate, reference guard and the release-pipeline probes pass in the full `verify`; the probe
  suite is 74 fixed / 0 weak with the Maven probes enabled.

### Routing

P2-01, P2-02 and P2-03 are corrections inside code that already exists and go to the fix pass, not to
the builder. Probe files for all eight pass-2 probes are in the module's internal folder, as on every
earlier pass; the fix pass adds them to the suite before touching production code. Re-verification is
three probe files turning green - no third adversarial pass.
