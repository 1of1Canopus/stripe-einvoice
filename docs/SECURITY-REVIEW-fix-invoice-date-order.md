# Security review: fix/invoice-date-order (PR 36)

## Pass 1 (2026-10-17)

Head reviewed: `dfd249a`. Base: main `2936e82`. Finding under fix: D-PS1-27 (invoices numbered out
of date order). Design: the approved round-2 design of the date-order change.

**Verdict: MERGE WITH FIXES.** No HIGH. One MEDIUM, four LOW. The mechanism holds where it matters
most: the issue date is read from one database clock after the series lock, numbers and dates go
up together under concurrency, and the year change, the backward clock and the forward clock are
refused. The five findings are about what the record says, what the verifier compares, one
misleading remedy, the rendered bytes, and one test that does not kill its mutant.

### Numbers

| item | result |
|---|---|
| `./mvnw -B clean verify`, Docker up | BUILD SUCCESS, 688 tests (core 545, starter 135, sample 8), 0 failures, 0 skipped, JaCoCo gate met |
| existing Cipher probes | all green inside that run, unchanged |
| builder's 17 mutations, re-run by this review | 15 RED as claimed; M2 GREEN (equivalent, confirmed); M5 GREEN with the mutant of design item 5 (finding D-PR36-5) |
| new probes of this pass | 5 fail on `dfd249a`; 2 extra checks pass |

### Rulings on the ten build choices (QUESTIONS 37)

| | choice | ruling |
|---|---|---|
| (a) | test seam `IssueClock` instead of libfaketime | **Accept, with D-PR36-5.** The seam cannot be reached from the public API: the only production users are `JdbcIssuanceStore` and `JdbcSupport`; the starter calls the public constructor, which always passes `IssueClock.DATABASE`. Reaching the package-private constructor needs a class planted in the package by the host itself, which already holds the database credentials. No ArchUnit rule is needed for that. But the seam keeps only the difference between two database readings, so it hides a production statement that returns a wrong absolute instant. D-PR36-5 adds the one test that reads the real clock. |
| (b) | one tax zone per store | Accept for 0.2.0. A zone per seller (Pro) is its own PR. |
| (c) | `AllocationRequest` takes a `SeriesScope` | Accept. No caller can pass a year or a date for a 380. |
| (d) | backward-clock finding raised at once from the size of the gap | Accept. While the clock moves forward, a gap smaller than the bound closes before the bound has passed, so the result is the same, only earlier. A frozen clock is caught by the cross-check with the application clock. |
| (e) | seventh cause `UNRECORDED`; `LATE_WEBHOOK` from the first intake day | Accept. Changed by D-PR36-1 only in the day the gap is measured from. |
| (f) | `DEI-137` `ISSUED_LATE` | Accept. The unique-code test covers it. |
| (g) | database clock behind Stripe's `finalized_at` is `DEI-132` | Accept. Suspected, not reproduced (no Stripe account in this run): an invoice on a Stripe test clock can carry a `finalized_at` days ahead; it then stops for good in test mode. That fails closed. It is noted, not a finding. |
| (h) | BG-14 replaces BT-72 when all lines share one period | Accept. D-PR36-1 changes how lateness is measured for these invoices. |
| (i) | schema stays format 2 | Accept: unreleased branch. |
| (j) | credit notes keep a caller date | Accept. D-PS1-43 stays with the credit-note slice. |

### Builder's own rows

* **D-DO-B4 (M2 equivalent): closed, no code change.** Only two statements write
  `last_issue_date`, `ALLOCATE` and `ALLOCATE_IN_DATE_ORDER`. Both refuse a day before the stored
  one inside the same statement, so `GREATEST(last, day)` always equals `day`. Re-run here: 66
  tests over the date-order, document-series, PR 33 and concurrency suites stay green. `GREATEST`
  stays as a harmless third line; the predicate and the series guard trigger are the controls,
  and both are killed by tests.
* **D-DO-B5 (renderer without `carriesSupplyDate()`): closed by the branch.** The startup check
  `IssuanceIntakeWiringCheck` refuses with `DEI-280` when intake is configured; with intake off,
  every invoice fails closed with the same code.

### What was attacked and held

* **Clock read and lock (D-PS1-40).** The row lock is its own statement; the clock read is the
  next statement on the same connection; the counter statement re-checks the date inside itself.
  The lock is held until commit, so the next allocator reads the clock after the previous commit.
  Example: A reads 23:59:59.9 and commits at 00:00:00.1; A is dated day D; B waited for the lock,
  reads 00:00:00.2 and is dated D+1, number A+1. Monotonic. M3 and M4 go red.
* **Concurrency.** Extra check of this pass: eight threads, 64 invoices, one series, the real
  production clock statement (no seam). In counter order, allocation instants and issue dates
  never go down. Green.
* **Year change at midnight (D-PS1-41).** `DEI-131` on the worker path and on the joined host
  path; nothing numbered; the retry lands in the new year's series. M11 and M12 go red.
* **Two-clock cross-check (D-PS1-46).** The tolerance is `max-clock-regression`, default 5 minutes,
  refused at startup when zero, negative or above 1 hour. Someone who controls only the
  application clock cannot choose an issue date: the date comes from the database. Someone who
  controls only the database clock can move it by at most the tolerance, so only an invoice
  allocated within 5 minutes of midnight can change day. That is the accepted residual.
* **Search path (D-PS1-45).** `pg_catalog.clock_timestamp()` is schema-qualified; the probe that
  plants a `clock_timestamp()` earlier on the path stays green; unqualifying it goes red.
* **Retry after `DEI-132`.** The refusal is raised before the counter moves and rolls back; a
  retry resumes by Stripe id; the `UNIQUE (seller_id, mode, stripe_invoice_id)` constraint is the
  last line. One Stripe invoice never gets two numbers.
* **Reorder paths R1 to R10.** Each has a test: R1 and R10 in the store tests, R2 to R9 in the
  path tests over the real database harness, each asserting number, BT-2, supply day, cause and
  the written XML.

### Findings

#### D-PR36-1 (MEDIUM, false completion): an advance-billed subscription numbered weeks late is recorded as on time

The late-issue gap is measured from the END of the invoicing period
(`IssueDating.daysAfterSupply`). Stripe bills a licensed subscription at the START of its period,
so the period end is in the future. The gap is then zero: cause `NONE`, no `DEI-137` finding.

Example: invoice finalised by Stripe on 1 January for the period 1 January to 1 February. An
outage holds it; it is numbered on 20 January. The chain and the series report say `NONE`, and no
finding is raised, although it was issued 19 days after Stripe finalised it. The design asks for
a cause for every day between supply and issue, and a finding beyond one day.

Fix (fix pass): in `JdbcIssuanceStore.allocateInvoice`, measure the gap from the `finalized_at`
day (`supplyDay`, already computed) to the issue day, floor zero, and use that one value for both
`JdbcDateGapCauses.derive` and the `ISSUED_LATE` finding. Make `IssueDating.daysAfterSupply`
measure from `supplyStart()` or remove it if nothing else uses it. Rewrite the sentence "a
subscription billed after its month is not late" where it appears. An invoice billed in arrears
(finalised on the period's last day) stays at gap zero, because the reference is the
finalisation day. Probe: `probe_an_advance_billed_period_numbered_weeks_late_is_recorded_as_on_time`.

#### D-PR36-2 (LOW, false completion): the verifier does not compare the row's dates with the chain

The dates are chained on every event (good) and frozen on the row by the issuance guard (good).
But `IssuanceChainVerifier.countUnchained` compares only identity, state, void columns, type and
target. The row's `issue_date`, supply columns, previous number, cause and `allocated_at` are not
compared. Those row columns are what the series report shows and what a resumed run renders as
BT-2. The format already compares the type for the same actor (S-01, "a role that outranks the
triggers").

Example: a role that outranks the triggers sets `issue_date` from 12 March to 1 March on a
disposed row. A resumed allocation returns 1 March, which is what the render would print. The
verifier says `INTACT`.

Fix (builder, inside the date-order mechanism): `JdbcIssuanceStore.disposedIssuances` selects the
dating columns and `allocated_at`; `DisposedIssuance` carries them; `countUnchained` adds them to
both keys, from the row and from the event's `dating()` (empty markers for pre-0.2.0 events).
Probe: `probe_a_rewritten_issue_date_on_the_row_is_reported_by_the_verifier`.

#### D-PR36-3 (LOW, error classification): both clocks back more than a day are reported as a hand edit, and the remedy lowers the guard

The cross-check sees nothing when the database host and the application host step back together
(one wrong time source). The series' latest date, written by a real allocation, then reads as
"more than one day in the future", so the refusal is `DEI-130`. Its message and `docs/index.md`
tell the operator to find a hand edit and lower `last_issue_date` as the schema owner. Following
that remedy while the clocks are still wrong lets the next invoices be dated before ones already
issued, which is D-PS1-27 again. It also means the design 5.2 case "more than one day behind
the series stops in `FAILED_ISSUANCE` with `DEI-132`" never happens for the series comparison.
Past one day it is always `DEI-130`.

Example: invoice A numbered on 10 March. Both clocks jump to 7 March. Invoice B is refused with
"Find who moved the series row in the issuance chain, then correct last_issue_date as the schema
owner". The chain shows that nobody moved it.

Fix (fix pass): the `DEI-130` message in `refuseUntrustedClock` and in `checkSeriesDates`, and the
`DEI-130` paragraph of `docs/index.md`, name both causes. They put the clock check first: "check
the database and application clocks against an external time source (NTP); only if both are
right, look in the issuance chain for who moved the series row". Correct the `DEI-132` paragraph:
past one day behind the series the code is `DEI-130`. Probe:
`probe_both_clocks_back_three_days_is_not_sent_to_lowering_the_series_date`.

#### D-PR36-4 (LOW, boundary validation): the rendered dates are taken on the renderer's word

`DEI-280` trusts `carriesSupplyDate()`. Nothing checks the bytes. The module's own validator
(`En16931DocumentValidator`) parses the UBL but does not compare its dates with the allocation.
A document without BT-72 only raises `BR-DE-TMP-32` at information level. A document whose BT-2
is not the numbering day passes as well.

Example 1: a host renderer returns `true` and forgets `cac:Delivery`. The bytes are archivable.
The invoice is dated 20 January with no trace that the sale was on 3 January. Example 2: a
renderer ported from 0.1.x writes the sale day as BT-2. Archivable.

Fix (builder, inside the date-order mechanism): in `En16931DocumentValidator.validate(bytes,
input)`, after the schema and schematron steps, read `cbc:IssueDate`, and
`cac:Delivery/cbc:ActualDeliveryDate` or `cac:InvoicePeriod` from the same parsed document. Return
a non-archivable report with one stable module rule id when BT-2 differs from
`input.issueDate()`, or when the input's supply date or period is missing or different. Probes:
`probe_a_document_that_drops_its_supply_date_is_not_archivable`,
`probe_a_document_dated_other_than_its_numbering_day_is_not_archivable`.

#### D-PR36-5 (LOW, false completion): mutation M5 survives; no test reads the real issue clock

Every store test reads the clock through `TestIssueClock`. That clock adds the difference between
two database readings to a fixed test clock. A production statement that returns a wrong absolute
instant keeps every difference the same, so the tests stay green. The PR body lists M5 as RED. The
mutant of design item 5 (SQL converts in the session zone) survives here.

Example: `IssueClock.STATEMENT` changed to `SELECT pg_catalog.date_trunc('day',
pg_catalog.clock_timestamp())`. `the_session_time_zone_never_decides_the_issue_day` stays green.
In production the cross-check would refuse every invoice (fail closed). But the suite proves
nothing about the one statement that dates every invoice.

Fix (fix pass): add the probe to the suite; it is green on `dfd249a` and red on that mutant. It
reads `IssueClock.DATABASE` on sessions in UTC, `Pacific/Kiritimati` and `America/Adak` and checks
the value against the real time within two seconds. Correct the M5 row of the PR body to name
the mutant the suite kills. Probe:
`probe_the_production_issue_clock_is_the_real_instant_in_any_session_zone`.

### Fix list

| id | severity | who | probe |
|---|---|---|---|
| D-PR36-1 | MEDIUM | fix pass | `probe_an_advance_billed_period_numbered_weeks_late_is_recorded_as_on_time` |
| D-PR36-2 | LOW | builder | `probe_a_rewritten_issue_date_on_the_row_is_reported_by_the_verifier` |
| D-PR36-3 | LOW | fix pass | `probe_both_clocks_back_three_days_is_not_sent_to_lowering_the_series_date` |
| D-PR36-4 | LOW | builder | `probe_a_document_that_drops_its_supply_date_is_not_archivable`, `probe_a_document_dated_other_than_its_numbering_day_is_not_archivable` |
| D-PR36-5 | LOW | fix pass | `probe_the_production_issue_clock_is_the_real_instant_in_any_session_zone` |

The probes are in the probe file of this pass, kept with the internal review notes. The fixer
adds them to `CipherProbePr36Test` (package `adapter.jdbc`) and `CipherProbePr36DocumentTest`
(package `adapter.validation`) before touching production code. They are not committed red on
this branch, so the branch stays green.

D-PS1-27 stays open until the pass-2 run shows these probes green.
