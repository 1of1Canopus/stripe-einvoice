# Security review: fix/sweeper-retry-edges

## Pass 1 (2026-10-26)

Head `e33170e`. Reviewed: the three commits above `ba30b0f` (`2a67800` red tests, `d01932b` fix,
`e33170e` docs). Subject: D-PR45-B1 (MEDIUM, a 0.2.0 tag blocker). A retried row whose run ended
before `FETCHED` wrote an illegal edge; `DEI-210` (illegal inbound transition) escaped, and nothing
was recorded. Read against the approved design "sweeper retry edges", Revision 2, with D-SRE-6 to
D-SRE-9 to be closed in the build.

**Verdict: MERGE WITH FIXES.** Two LOW findings on this branch, D-PR50-1 and D-PR50-2. D-PR45-B1 is
fixed: every start state and every pre-`FETCHED` conclusion is recorded, with a backoff or a stop.
The builder's own finding D-PR50-B1 is re-graded LOW. It pre-exists on main and goes to its own
small pull request before the tag.

### Numbers

| Check | Result |
|---|---|
| `CIPHER_PROBE_MAVEN=1 ./mvnw -B verify`, Docker up | core 937, starter 157, sample 9; 0 failed, 0 skipped; coverage gates met |
| Earlier review probes (`CipherProbe*`, core) | 262 green, unchanged |
| `CipherProbePr45B1Test` | 3 of 3 green |
| Builder mutations re-run by the review | 3 of 14, all red as stated (table below) |
| New review probes | 3 red on `e33170e`; 2 guards green |

| Mutation | Red |
|---|---|
| 2: map nothing (write the target) | `CipherProbePr45B1Test` probes 1 and 2; `SweeperRetryEdgeTableTest` 18 failures and 1 error |
| D-SRE-7: drop the purge guard | `probe_the_purge_deletes_a_row_whose_number_is_still_open` |
| D-SRE-9: set the refused flag before the write | `a_success_racing_a_refusal_still_re_arms_the_refused_row` |

### The edge table

The review's own guard, `guard_every_cell_records_a_backoff_or_a_stop_and_never_escapes`, is
independent of the builder's table test. It runs 120 cells: 10 start states, 6 conclusions (the
five pre-`FETCHED` ones and the refused key), with and without a number. All green. In every cell:

- nothing escapes `process`, and `DEI-210` is never recorded;
- the attempt is counted;
- the row is never due again at once, so a sweep tick cannot loop on it;
- a refused key (`DEI-219`) is never retried;
- a retryable failure always carries a backoff;
- a start that may hold a number (`FETCHED`, `MAPPED`, `FAILED_ISSUANCE`) never ends in a
  pre-`FETCHED` state; the number is unchanged, and no number is taken.

By reading: every pre-`FETCHED` write in `IssuanceUnitOfWork` goes through `concludeBeforeFetch`
(the four route refusals, the fetch failure, and the fetched object's mode). The one other
`DROPPED` write is the upstream void after `FETCHED`. The design keeps that one on purpose.

A note on the guard. A second event of a numbered invoice that never got going (for example an
`invoice.paid` in `PARKED`) does end in a refusal. That is correct by design section 3: that row
never held the number; the first event's row does. The purge keeps both rows (D-SRE-7).

### The builder's deviations

| # | Deviation | Ruling |
|---|---|---|
| a | Refused-key code `DEI-219`, not `DEI-218` | Accepted. `DEI-218` is `RUN_IN_HOST_TRANSACTION` and is reserved. `ErrorCodesUniquenessTest` is green. README and CHANGELOG use `DEI-219`. |
| b | Reprocess takes a numbered row that is still due | Accepted. What a host can do with it: run the row now, under the same run lock, with a ledger entry. The backoff restarts from its base. The host cannot take a second number (the race guard below), cannot re-open the row to `RECEIVED` (the statement refuses), and cannot touch an issued, voided or burned number. This is the sweeper's next run taken earlier, with a name on it. No new surface. |
| c | The number check reads issuance states under any seller and mode | Accepted; isolation verified by reading. It returns states only: no number, no buyer field, no seller. It decides three things: the `FETCHED` read, reprocess eligibility, and in place versus `RECEIVED`. Each one fails closed: a foreign row can only make a row look numbered. No result or log carries the foreign row. A refused reprocess returns the seller-scoped read. A collision needs two sellers or modes with the same Stripe invoice id; Stripe ids are global. `SourceAssertionsTest` pins this statement as the only unscoped issuance read. |
| d | One line of `CipherProbePr45B1Test` probe 1 changed | Accepted. The original line counted fetches over three direct `process` calls. Nothing in production calls `process` again on a redelivery: the webhook hands an event to the worker only the first time it is recorded. So the direct calls are the first delivery, the worker and the reprocess. The probe's real claim, "every sweep tick calls Stripe", is now asserted on the sweep's input (not due during the backoff). The per-sweep count is in `StripeAccessRefusedTest`. Mutation 2 turns the probe red. |
| e | Reprocess probe 7 and archive-retry S7 reversed on purpose | Accepted. New guard `guard_a_reprocess_racing_the_sweeper_on_one_numbered_row_takes_no_second_number`: 8 numbered rows; for each, a reprocess and a worker run start together. Green: the series counter is unchanged, every row is `ISSUED` on its own first number. With the builder's `two_reprocess_calls_on_one_numbered_row_record_one_entry_and_run_once`, no second number under concurrency. |
| f | Three test classes moved to unique invoice ids | A test artefact, not a production cross-read. The suite reused `in_1` and `in_test_1` across sellers in one database. Production invoice ids are unique, and a collision would only fail closed. |
| g | D-PR50-B1, the stuck check's year window | Re-graded INFO to LOW, wider than stated. See below. |

### D-PR50-1, LOW (copy/leak). A burned number keeps the buyer's raw event past retention

The purge keeps a row whose invoice has an issuance row "not issued and not voided". A burned
number (`FAILED_VALIDATION`, a validation refusal) matches that test. So its inbound row stays, with
the buyer's raw body, past `inbound.retention`, until an operator voids the number. Nothing needs
the row: the reprocess refuses a burned number, and the stuck check ignores it. D-SRE-7 asked to
keep the rows of an **open** number only.

Example. A validation rule burns `INV-2026-000031` in March. Nobody voids it. In April the purge
should delete the event with the buyer's name and address; it keeps it, with no end date.

Repro: `CipherProbePr50Test.probe_a_burned_number_keeps_its_inbound_row_past_retention`. The row
remains with `bodyPresent=true`.

Fix. `JdbcInboundEventStore.OPEN_NUMBER_KEEPS_ROW` lists the open states positively:
`i.state IN ('NUMBERED','ARCHIVING','FAILED_ARCHIVE')`, built from the `IssuanceState` values whose
`open()` is true. `IssuanceReprocess.classifyOpen` becomes `IssuanceState::open`, so there is one
definition. The `purgeOlderThan` javadoc, the CHANGELOG and the retention text in `docs/index.md`
say "an open number (numbered, archiving or failed archive)". Add the probe to the suite.

### D-PR50-2, LOW (false completion). An unnumbered `FAILED_ISSUANCE` row loses its way back

`concludeBeforeFetch` treats every `FAILED_ISSUANCE` and `MAPPED` start as numbered, without
reading the issuance table. Both can hold no number. The allocator may refuse before the claim
(`DEI-132` clock behind, a store outage, a timeout). Or a run may crash between the `MAPPED` write
and the claim. The mapping then rewrites the code. The reprocess accepts `FAILED_ISSUANCE` only with
`DEI-132`, or with an open number. So the row has no operator path. Past the `DUE` ceiling it is
not retried either, and no finding names it: there is no issuance row for `DEI-273` to see. This is
the gap D-SRE-4 closed for `FETCHED`. Before this branch the same run escaped with `DEI-210`. So it
is a residue of the fix, not a regression.

Example. The allocator refuses `in_9` with `DEI-132` and schedules a retry. At the retry Stripe is
down: the row becomes `FAILED_ISSUANCE DEI-211`. Stripe stays down past the 72-hour ceiling. The
clock is fixed; the operator calls reprocess; it answers `REFUSED_NOT_ELIGIBLE`.

Repro: `CipherProbePr50Test.probe_an_unnumbered_failed_issuance_row_loses_its_reprocess_path_after_an_outage`.

Fix. `IssuanceReprocess.eligible` accepts `FAILED_ISSUANCE` with any code.
`JdbcInboundEventStore.REOPEN_FOR_REPROCESS` drops the `last_code = 'DEI-132'` condition and keeps
its `NOT EXISTS` number guard. That guard is what makes any code safe: a row whose invoice holds a
number anywhere is never re-opened to `RECEIVED`. Remove `ELIGIBLE_ISSUANCE_CODE`, or document it as
history. Update the reprocess eligibility text in `docs/index.md` and the CHANGELOG. Add the probe,
and one test for an unnumbered `MAPPED` row mapped by a refusal, reprocessed to `COMPLETED`.

### D-PR50-B1, LOW (false completion), re-graded. A stuck number can go unnamed for good

The builder found that the stuck check (`DEI-273`) scans only the fiscal years its two-day window
touches. Reproduced, and wider:

- the pass throws at the Stripe list call (a refused key, an outage) **before** the stuck check
  runs;
- `DEI-270` (missing issuance) names the invoice only while its finalisation date is inside the
  window;
- the stuck check reads only the newest page of each year's series.

So an open number is never named when no pass completes in the two days after finalisation, and
then the window has left its year (or more than one page of newer numbers came first). D-SRE-2,
D-SRE-5b and D-SRE-7 rely on `DEI-273`.

Example. A key is revoked on 30 December. A number from that morning stays open. The key is fixed
on 3 January. No pass ever names the number.

Repro: `CipherProbePr50Test.probe_a_stuck_number_is_never_named_when_the_list_fails_until_the_year_turns`.

It pre-exists on main, and this branch does not change it. Own small pull request; it blocks the
0.2.0 tag. Fix: `IssuanceReader.openOlderThan(seller, mode, before, limit)`, with state in the
`open()` states, oldest `allocated_at` first, any year. `ReconciliationSweep` runs it before the
Stripe list call, as it already does for the `DEI-219` names.

### Out of scope, for the release-candidate pass

D-PR50-3, INFO, suspected, not reproduced. The `DUE` query and the reprocess read inbound rows with
no seller filter. If two sellers share one database, one instance runs the other's rows. This
pre-exists and is unchanged here. It depends on whether that deployment is supported; the docs do
not say.

### Rulings on QUESTIONS 54 to 57 (recommendations; the founder decides)

- **54:** accept `DEI-219`.
- **55:** accept: same run, earlier, under the same lock, with a ledger entry; no second number.
- **56:** accept the changed line; the probe keeps its claim and goes red under mutation 2.
- **57:** LOW, wider than stated (D-PR50-B1); own small PR before the 0.2.0 tag.

### Probes

`CipherProbePr50Test` (core, package `application`), kept with the review's internal probe files.
The fix pass adds it to the suite before it changes production code. The two `guard_` methods are
green now and stay as regression guards. The B1 probe goes in with its own pull request.

## Pass 2 (2026-10-26)

Head `9974dc2` (PR 49 branch merged at `5c0acfd`). Limited pass: the two fixes, their side
effects, the skipped probe. Last pass on this PR.

**Verdict: MERGE WITH FIXES.** One INFO, documentation only (D-PR50-4). Both pass 1 findings are
verified. No third pass is needed: the fix pass applies D-PR50-4 and the verifier reports the
numbers.

### Numbers

Full `clean verify` on `9974dc2` plus the pass 2 guard file, Docker up.

| Module | Tests | Skipped | Failures |
|---|---|---|---|
| core | 946 (943 of the branch + 3 pass 2 guards) | 1 | 0 |
| starter | 157 | 0 | 0 |
| sample | 9 | 0 | 0 |

The one skipped test is `probe_a_stuck_number_is_never_named_when_the_list_fails_until_the_year_turns`.

### D-PR50-1, verified

The purge guard now keeps a row only while its number is open: `NUMBERED`, `ARCHIVING`,
`FAILED_ARCHIVE`. The list comes from `IssuanceState.open()`, the same predicate the reprocess
uses. The pass 1 probe is green.

Adversarial variant. A voided number (`VOID_UNUSED`) has nothing to resume, so its row must leave
at retention. It must not leave earlier. Example: the row arrived on 10 June. A purge with cutoff
10 June (same instant) or 1 June keeps it. A purge with cutoff 10 June plus one microsecond removes
it. Green: `guard_a_voided_number_row_is_purged_on_schedule_not_early`.

Boundary. Each of the three open states still keeps its row past retention:
`guard_every_open_state_keeps_its_row_past_retention`.

### D-PR50-2, verified

The reprocess accepts `FAILED_ISSUANCE` with any code. The re-open to `RECEIVED` keeps its
`NOT EXISTS` number guard. The pass 1 probe is green.

Side effect. A reprocess of a voided number now answers `REFUSED_NUMBERED`, not
`REFUSED_NOT_ELIGIBLE`. Checked from three starts: `FAILED_ISSUANCE DEI-211`,
`FAILED_ISSUANCE DEI-132`, `FAILED_MAPPING DEI-221`. Each one: no Stripe fetch, no reprocess
record, no series counter move, no new numbered row, no chained event, inbound row unchanged
(state, code, attempts), issuance still `VOID_UNUSED`. Green:
`guard_a_voided_number_reprocess_runs_nothing_records_nothing_takes_no_number`. The new answer is
correct and matches the `Disposition.REFUSED_NUMBERED` javadoc. No code in core, starter or sample
branches on either value. But the public text does not say it: see D-PR50-4.

### D-PR50-4, INFO (documentation). The new answer for a closed number is not in the public text

In 0.1.0 a reprocess of an event whose invoice holds a voided number answered
`REFUSED_NOT_ELIGIBLE` (the old `VoidTerminalDispositionTest` asserted it). It now answers
`REFUSED_NUMBERED`. An operator script that branches on the answer sees a new value. The public
text says otherwise:

- `docs/index.md`, reprocess list: "Every other state ... is answered `REFUSED_NOT_ELIGIBLE`". It
  never says what a `FAILED_ISSUANCE` or `FAILED_MAPPING` row with an issued, burned or voided
  number gets.
- `CHANGELOG.md`, D-SRE-2 entry: "`REFUSED_NUMBERED` now means a number appeared between the read
  and that statement". That is one of two cases; the main one is missing.

Example. An operator voids `INV-2026-000012`, then by mistake calls reprocess on its event. The
docs promise `REFUSED_NOT_ELIGIBLE`; the call returns `REFUSED_NUMBERED` with code
`DEI-111`. Nothing runs either way.

Repro: `CipherProbePr50Pass2Test.guard_a_voided_number_reprocess_runs_nothing_records_nothing_takes_no_number`
(green, asserts `REFUSED_NUMBERED`) against `docs/index.md` lines 209 to 213.

Fix (Isis, docs only). `docs/index.md`, reprocess list: add one bullet: a `FAILED_ISSUANCE` or
`FAILED_MAPPING` row whose invoice holds an issued, burned or voided number is answered
`REFUSED_NUMBERED` with `DEI-111`; nothing runs, nothing is recorded, no
number is taken. `CHANGELOG.md`: under the D-PR50-2 entry, state the change from 0.1.0
(`REFUSED_NOT_ELIGIBLE` to `REFUSED_NUMBERED` for a closed number), and correct the D-SRE-2 line to
name both cases. No code change.

### D-PR50-B1, handling accepted

The probe is in the suite, `@Disabled`, with a message that names QUESTIONS 58 and the commit where
it was red. QUESTIONS 58 puts the fix in its own small PR, with D-PR49-3, before the 0.2.0 tag. The
finding is pre-existing on main and not touched by this branch. Accepted. It stays a tag blocker:
that PR removes the annotation and the probe must be green there.

### Mutations

| Mutation | Result |
|---|---|
| `OPEN_NUMBER_KEEPS_ROW` filter narrowed from `open()` to `NUMBERED` | red: `guard_every_open_state_keeps_its_row_past_retention` at `ARCHIVING` |
| numbered gate in `IssuanceReprocess.reprocessPermitted` removed | red: the pass 2 reprocess guard and `VoidTerminalDispositionTest.a_privileged_reprocess_of_a_voided_invoice_is_still_refused`; the second gate still refused (`REFUSED_NOT_ELIGIBLE`), nothing ran |

Both restored; tree clean before the full run. JaCoCo line gate met in core and starter.

### New surfaces

None in code beyond D-PR50-4. The fix removes a constant (`ELIGIBLE_ISSUANCE_CODE`) and a private copy of `open()`.
The purge SQL is built from enum names, not from input.

### Probes

`CipherProbePr50Pass2Test` (core, package `application`): three guards, green on `9974dc2`. Added
to the suite on this branch and kept with the review's internal probe files.
