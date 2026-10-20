# Security review - branch `fix/running-row-time-limit`

Adversarial review of pull request 46 (QUESTIONS 29: a time limit for inbound rows that stay
running), head `860f25c`, built on `main` `dcc64bf`. `main` is now `5b8981e` (pull request 45,
compare-and-set writes before the claim). Pass 1 of at most 2.

## 2026-10-20 - pass 1

### Verdict

**MERGE WITH FIXES** - three findings, none above LOW. Fix list below.

The mechanism is sound. The clock restarts on progress. The migration gives an upgraded backlog
the upgrade instant, not the arrival. The due and stranded queries meet at one cutoff with no gap
and no overlap. The expiry is one compare-and-set on state and clock, with the finding in the same
transaction. Bad limits are refused at binding. Every probe of the design page and of the design
ruling is present and green.

The three findings are all about the edges of a live run and one gap that the builder found and
reported (QUESTIONS 49). Two of the three are closed by work that is already approved: the
rebase on pull request 45 and the run lock design.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | - |
| MEDIUM | 0 | - |
| LOW | 3 | D-PR46-1, D-PR46-2, D-Q29-3 (QUESTIONS 49) |
| INFO | 0 | - |

Probes: `internal/D-stripe-einvoice/probes/CipherProbePr46Test.java` (package
`com.housedevinci.einvoice.application`, core test tree). Four tests. The three `probe_*` tests are
RED on `860f25c`. The `not_a_finding_*` test is green and stays as a regression.

### Numbers

| What | Result |
|---|---|
| `./mvnw -B clean verify` on `860f25c` (Docker up) | SUCCESS, core 724 / starter 144 / sample 9, 0 failures, 0 errors, 0 skipped |
| Existing `CipherProbe*` Java tests, unchanged | 48 classes, 269 tests, all green |
| `tools/cipher-probe-release-pipeline.sh` | 122 FIXED, 9 not run (they need `CIPHER_PROBE_MAVEN=1`; this branch touches no release, CI or tool file). Not counted as passing. |
| New probes, `CipherProbePr46Test` | 4 run, 3 RED, 1 green |

### Findings

#### D-PR46-1 (LOW) - an expiry inside a live run throws DEI-210 out of `process`

Example. A draft is parked on day 0. On day 89 and 23 hours the sweep re-checks it and Stripe now
says "finalised". The run writes `PARKED -> FETCHED`. That move keeps the park's clock (QUESTIONS
46), so the row is still read against the 90-day limit. The run is in the preflight when a sweep on
another node passes the 90-day mark and expires the row. The run's next write, `FETCHED -> MAPPED`,
finds `EXPIRED` and throws `DEI-210 an inbound event in state EXPIRED cannot move to MAPPED`.

No number is taken: the write that throws comes before the claim. But pull request 45 states that
no write before the claim throws DEI-210 for a row another run moved. This branch predates it, so
its writes are the old unconditional ones. That is the `TODO(PR 45)` marker.

Probe: `probe_an_expiry_inside_a_live_run_throws_out_of_process`. RED on `860f25c`.

Ruling on the marker. Do **not** route the expiry through `transitionFrom`. `transitionFrom`
compares the state only. The expiry compares the state **and** the clock, and the clock is what
stops it from expiring a row that moved and came back to the same state with a fresh clock (test
`a_row_that_moved_and_came_back_has_a_new_clock_and_is_not_expired_on_the_old_read`). Replacing it
would weaken the control.

Fix (builder, this pull request):
1. Merge `main` (`5b8981e` or later) into the branch. `JdbcInboundEventStore` conflicts. Carry the
   two `due_since` bindings (`RunningLimit.restartsClock(current, resolved, false/true)` and the
   instant) into `main`'s single statement `TRANSITION_FROM`, so `transition` and `transitionFrom`
   restart the clock by the same rule.
2. Keep `EXPIRE` as it is (state and clock compare). Delete the `TODO(PR 45)` comment and say in
   one line why the expiry keeps its own compare.
3. Add one store test that moves a row with `transitionFrom` (`RECEIVED -> FETCHED`) and asserts
   that the clock restarted.
4. The probe must then be green with no other change: the run's `MAPPED` step is `main`'s
   compare-and-set, which answers from the stored `EXPIRED` row.

#### D-PR46-2 (LOW) - an expiry after the claim expires a row whose run holds a number

Example. A finalised invoice is received. The run writes `MAPPED`, claims `INV-2026-000001`, and
then waits on a host archive store that has no timeout. 73 hours later the sweep finds a `MAPPED`
row with a 73-hour-old clock and expires it, with `DEI-282 RECON_STUCK_INBOUND`. The archive call
then returns. The run marks the invoice `ISSUED` and its `COMPLETED` write throws
`DEI-210 ... EXPIRED cannot move to COMPLETED`.

End state: a legal document exists and is `ISSUED`. Its inbound row says `EXPIRED`, and the
finding says "stuck". A reprocess refuses the row because it holds a number. The report is false,
and an operator who trusts it may void a number that was issued.

Probe: `probe_an_expiry_after_the_claim_expires_an_issuing_row`. RED on `860f25c`.

This is the case the approved run lock design already names: its paths table row 16 ("QUESTIONS 29
expiry write: per row `pg_try_advisory_xact_lock` on the run key, then the compare-and-set; a held
row is skipped this pass", test T22). The builder's own build note 11 says the same.

Fix: no new mechanism. The run lock lands first. This branch then merges it and the expiry takes
the run lock exactly as row 16 says, in the same transaction as `EXPIRE`, skipping a held row
without a finding. T22 and this probe go green in this pull request. Ordering: this pull request
does not merge before the run lock pull request. That is a sequencing decision for the
coordinator, not a deferral: the finding is closed in this pull request.

#### D-Q29-3 / QUESTIONS 49 (LOW) - a parked row that meets one Stripe error is silent, and the
#### proposed fix does not close it

Confirmed. Example. A draft is parked on day 0. On day 10 the re-check meets a Stripe 503. The row
is `FAILED_FETCH`. Two days later it is in neither `due()` nor `stranded()`: never re-checked,
never expired, never reported.

The builder recommends measuring the retryable branch from `due_since`. That is not enough. The
probe reads the clock after the 503: it is still the park's clock from day 0, because the park
re-check edges keep it (QUESTIONS 46, by design). Measured from that clock against the 72-hour
limit, the row is still out of the due query.

Severity LOW, not a tag blocker of its own class. The invoice is normally documented by another
row: its own `invoice.finalized` event, and the reconciliation's re-enqueue of a finalised invoice
with no issuance (`DEI-270`, on by default). What is lost is the parked row's promise of 90 days.
Under the no-allowance rule it is fixed before the tag anyway.

Probe: `probe_a_parked_row_that_meets_one_stripe_error_is_silent`. RED on `860f25c`.

Owner and place: the builder, in **this** pull request. It is a rule of the clock this pull request
defines, in the `DUE` statement this pull request edits. It is not a second mechanism.

Fix: in `JdbcInboundEventStore.DUE`, the retryable branch reads a `FAILED_FETCH` row with
`parked_at IS NOT NULL` against the parked limit on its clock (`due_since >= parkedCutoff`)
instead of `received_at > now - retry-ceiling`. Every other retryable row keeps today's rule. At
the end of the parked limit the row has concluded with its `last_code`, the reading the design
ruling accepted for F-4. Add the probe, and one boundary test at exactly the parked cutoff.

### Rulings on the deviations and questions

* **QUESTIONS 46 (park re-check keeps the clock).** Accepted. The literal F-1 rule would switch the
  parked limit off; the probe for 90 days of re-checks proves the built rule works. The cost (a
  crash loop on an ever-parked `FETCHED` row is bounded by 90 days, not 72 hours) is bounded and
  stated. It opens the window of D-PR46-1, which the rebase closes.
* **Expiry in the issuance sweep, not the reconciliation.** Accepted. The reconciliation can be
  switched off, and the due query would then hide rows with nothing to report them.
* **Codes DEI-266 and DEI-282.** Free. Checked on `main`, on every remote branch of the repository,
  and in every design page of the module.
* **QUESTIONS 47 (a second expiry clears the acknowledgement).** Accepted. The second expiry is
  news. The re-open between the two is in the append-only reprocess ledger with actor, reason and
  time.
* **QUESTIONS 48 (a numbered `EXPIRED` row has no resume).** Not a finding of this pull request.
  Before it, the same row stayed due for ever with no report. Now it is reported once by DEI-282.
  The resume belongs to the sweeper-edges design, which already lists `EXPIRED` as number-capable.
  Condition: that pull request's paths table carries `EXPIRED` with a number as a row with a test.
* **`IssuanceOperationsTest` fixture on the application clock.** Not a weakening. The old fixture
  recorded the row months before the context clock. Under the new limit that row is correctly
  expired, not due. The test's subject is a fresh row.
* **D-Q29-4 (the builder's `install`).** Process slip, already registered. Every build in this
  pass ran the full reactor, so the local SNAPSHOT did not enter it. The coordinator removes the
  SNAPSHOT of `stripe-einvoice-core` from the local Maven repository before the next sibling build.

### Attacks that held

* **Upgraded backlog.** `probe_an_upgraded_backlog_is_mass_expired` builds a format 1 table with
  thirty running rows aged 30 days, initialises the schema, and finds zero stranded and thirty due,
  each clock at the upgrade instant. A second initialisation does not move the clock. Green.
* **Clock skew between the application and the database.** Every write binds the application's
  instant. The database's `now()` is used only for the one-time backfill and for the column default
  that a 0.1.0 writer hits during a rolling upgrade. A database clock behind by less than the
  running limit cannot expire a backfilled row on the first sweep. Not a finding.
* **Reprocess of an `EXPIRED` row, twice.** `not_a_finding_a_second_reprocess_of_an_expired_row_is_refused`:
  the second request finds a running row, re-opens nothing and records no second request. Green.
* **Expiry racing a conclusion, and one failing row in a page.** Covered by the builder's
  `probe_a_concurrently_completed_row_throws_the_sweep` and
  `a_row_whose_write_fails_does_not_stop_the_rest_of_the_page`. Green.
* **Too-short or zero limits.** Refused at binding, both orderings. Above 7 or 365 days, a WARN at
  every start. Green.
* **Domain boundary.** `RunningLimit` and the `InboundState` changes import the JDK only.

### Fix list

| Id | Severity | Who | Where | Probe |
|---|---|---|---|---|
| D-PR46-1 | LOW | builder | merge `main`; `TRANSITION_FROM` carries the clock bindings; `EXPIRE` keeps state and clock compare; delete `TODO(PR 45)`; one `transitionFrom` clock test | `probe_an_expiry_inside_a_live_run_throws_out_of_process` |
| D-PR46-2 | LOW | builder | after the run lock lands: `JdbcInboundEventStore.expire` takes `pg_try_advisory_xact_lock` on the run key in the `EXPIRE` transaction, skips a held row (run lock design row 16, T22) | `probe_an_expiry_after_the_claim_expires_an_issuing_row` |
| D-Q29-3 | LOW | builder | `JdbcInboundEventStore.DUE`, retryable branch: ever-parked `FAILED_FETCH` against `due_since >= parkedCutoff` | `probe_a_parked_row_that_meets_one_stripe_error_is_silent` |

The fixer adds `CipherProbePr46Test` to the core test tree first, shows the three probes RED, then
fixes. Pass 2 confirms each probe green and attacks the merge of pull request 45 and the run lock.
