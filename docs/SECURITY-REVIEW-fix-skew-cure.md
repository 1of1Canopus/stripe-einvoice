# Security review: fix/skew-cure (PR 62)

## 2026-11-11, pass 1

On 9661643.

**Verdict: MERGE WITH FIXES.** No HIGH and no MEDIUM. Two LOW (D-PR62-1, D-PR62-3) and two INFO
(D-PR62-2, D-PR62-4).

Summary: the cure works as designed. One statement cures a skew row, two copies at once give one
cure, and a run that read the row before a cure loses its write. Each change the design review
required (D-SKEW-1 to D-SKEW-10) is built. Two things are wrong. The issuance can record a false
reason for a late number after a cure. A blank pin makes the endpoint answer 400 instead of
refusing to start.

### Build and probe suite

`./mvnw -B verify`, Docker up, on 9661643. The first run failed with 753 errors because the
Docker VM disk had gone read-only. Docker was restarted and the full run repeated. Numbers are
from the second run.

| Module | Tests | Failed | Errors | Skipped |
|---|---|---|---|---|
| core | 1192 | 0 | 0 | 3 |
| starter | 167 | 0 | 0 | 0 |
| sample | 9 | 0 | 0 | 0 |

The three skips are the known Linux-only filename tests. A skipped test is not a passing test;
they run on the Linux CI runner. Every existing `CipherProbe*` test ran inside this verify,
unchanged, and passed.

`CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh`: 79 FIXED, 0 WEAK.

### The ten required changes

| Change | Built | Evidence |
|---|---|---|
| D-SKEW-1, the safety argument and P15 | yes | `mayHoldNumber` answers false for both curable states; probe 15 green |
| D-SKEW-2, cure generation in every compare | yes | `TRANSITION_FROM`, `HELD_TRANSITION_FROM` (derived from it), `STAMP_ATTEMPT`, and the Java compare in `run()`; mutation runs below |
| D-SKEW-3, reconciliation is a path | yes | `ReconciliationSweep.enqueue` goes through `InboundIntake`, exhaustive switch; probe 16 green. Its mismatch answer has no test (D-PR62-4) |
| D-SKEW-4, retry once on 40001 | yes | `record` retries when the store owns the connection; probe 18 green at all three levels |
| D-SKEW-5, mismatch is a store answer | yes | `Recorded.IDENTITY_MISMATCH`, read in the same transaction, finding raised by `InboundIntake`; probe 7 green. Endpoint path untested (D-PR62-4) |
| D-SKEW-6, every port change named, no defaults | yes | probe 22 green; CHANGELOG entry names the three methods; QUESTIONS 66 |
| D-SKEW-7, no `xmax` | yes | `RETURNING cure_generation` |
| D-SKEW-8, rule B as a full table | partly | probe 17 green for the writers. A second cure loses the first arrival (D-PR62-2). A reader of a reset column was not checked (D-PR62-1) |
| D-SKEW-9, INFO for the losing run | yes | `storedOutcome(read, stored)` compares generations; probe 24 green |
| D-SKEW-10, the pin | partly | `InboundIntake` passes `configuration.pinnedApiVersion()`; `record` refuses a blank pin before SQL. The refusal comes per request as a 400, not at startup (D-PR62-3) |

### Mutation runs (review's own, on a detached copy, restored)

| Mutation | Result |
|---|---|
| MA: `run()` compares the state only, not the generation | red: `a_run_on_the_snapshot_before_a_cure_loses_its_write`, `a_run_that_read_before_two_cures_loses_its_write` |
| MB: `STAMP_ATTEMPT` drops `AND cure_generation = ?` (parameter kept) | red: the same two tests |
| Fix check: the two SQL fixes prescribed for D-PR62-1 and D-PR62-2 | both probes green; `JdbcInboundEventStoreCureTest`, `InvoiceDateOrderPathsTest` and `SkewCurePathsTest` stay green |

### Findings

#### D-PR62-1 (LOW, false completion): a cured event is recorded as a late webhook

Probe: `CipherProbePr62Test.probe_a_cured_row_is_recorded_as_a_late_webhook`, red.
Control: `guard_a_skew_row_replayed_by_a_pin_move_is_not_a_late_webhook`, green.

A cure resets `received_at` (rule B: the retry ceiling and retention restart). But
`JdbcDateGapCauses.HISTORY` also reads it: `min(received_at) AS first_received`. When an invoice
is numbered on a later day than its supply day, that value decides the cause written on the
issuance. A first arrival after the supply day gives `LATE_WEBHOOK`.

Repro (harness, tax zone Europe/Paris):

1. Invoice finalised on 3 January. Its webhook arrives on 3 January at the old version.
2. The run refuses it for skew (`DEI-201`).
3. On 10 January a copy at the pin cures the row. The row runs and is numbered.
4. The issuance records `LATE_WEBHOOK`.

The webhook came on 3 January. The module refused it. The same history repaired by a pin move
(the D2-03 path, no cure) records `UNRECORDED`, which is honest. So the cure writes a false
statement into the record that explains why the issue date is after the supply date. The design
review checked the writers of every column (probe 17) but not the readers of the columns rule B
resets.

Fix (builder or fix pass):

- `JdbcDateGapCauses.HISTORY`: read the first arrival,
  `min(coalesce(cured_from_received_at, received_at)) AS first_received`.
- This depends on D-PR62-2, so that `cured_from_received_at` is the first arrival after any
  number of cures.
- Check the other readers of the three reset clocks and of `seller_id`, and list them in the
  design page section 9, change 8 (the retry ceiling in `DUE`, retention in `PURGE_CANDIDATES`,
  the date-gap history, `ACCESS_REFUSED_ROWS` order). Each one either wants the cure time or the
  first arrival; say which.
- Promote both tests above into the suite (the probe renamed
  `a_cured_row_is_not_recorded_as_a_late_webhook`).

#### D-PR62-2 (INFO, false completion): a second cure loses the first arrival

Probe: `CipherProbePr62Test.probe_a_second_cure_loses_the_first_arrival`, red
(`expected: 2026-09-15T10:00:00Z but was: 2026-09-16T10:00:00Z`).

Repro: record at OLD at T0, refuse for skew, cure at NEW at T1, then a copy at OLD on a node
pinned to OLD cures it back at T2. This is the two-pins case D-SKEW-2 was raised for.
`cured_from_received_at` is then T1. D-SKEW-8 required the first arrival to be kept, and the
CHANGELOG upgrade note says "the first arrival" is kept.

Fix: in `JdbcInboundEventStore.INSERT`, `cured_from_received_at =
coalesce(e.cured_from_received_at, e.received_at)`. `cured_from_api_version` and
`cured_from_body_sha256` stay "the copy just replaced"; the docs page and the CHANGELOG say so.
Promote the probe as `a_second_cure_keeps_the_first_arrival`.

#### D-PR62-3 (LOW, boundary validation): a blank pin answers every signed event with 400

Probes: `CipherProbePr62Test.probe_a_blank_pin_is_accepted_when_the_configuration_is_built`, red
for `""`, `" "`, tab and null. `CipherProbePr62StarterTest.probe_a_blank_pin_answers_a_signed_event_with_400`,
red (`400 BAD_REQUEST`).

`IssuanceUnitOfWork.Configuration` accepts a null or blank `pinnedApiVersion`. The starter takes
it from the `InvoiceSource` bean, so a host source that answers `""` reaches it. The bundled
Stripe source refuses null but not every host does. The application then starts. Each signed
event reaches `JdbcInboundEventStore.record`, which refuses with `DEI-100` (built for
D-SKEW-10). `StripeWebhookController` maps every code other than `DEI-102` to 400. So a correctly
signed event is answered 400 and recorded nowhere. Stripe retries and may then disable the
endpoint, which is what the controller's own comment says a 4xx must never cause. Before this PR
the same host recorded every event and refused it as skew, replayable after the fix.

Fix: the compact constructor of `IssuanceUnitOfWork.Configuration` refuses a null or blank
`pinnedApiVersion` with `EInvoiceException(ErrorCodes.CONFIG, ...)`, so the context fails to
start. Keep the store's own check. Promote the core probe as
`a_blank_pin_is_refused_when_the_configuration_is_built`. The starter probe then cannot build its
controller; replace it with a context-runner test that a host `InvoiceSource` with a blank pin
fails the context with `DEI-100`.

#### D-PR62-4 (INFO, integration surface): the mismatch answer has no test at two callers

Guard: `CipherProbePr62StarterTest.guard_an_identity_mismatch_at_the_endpoint_is_200_with_a_finding_and_no_run`,
green.

`Recorded` has four answers and two callers outside the store: the endpoint and
`ReconciliationSweep.enqueue`. The branch tests `IDENTITY_MISMATCH` at the store and the intake
(probe 7) only. The guard shows the endpoint is right today: 200, no hand-over to the worker,
one `DEI-223` finding on the event id. Nothing holds it.

Fix: promote the guard into the starter suite. Add one core test in `SkewCurePathsTest`: a
`recon-<invoice>` row stored with another account or object, then a pass; the row is untouched,
one `DEI-223` finding is raised, and the pass carries on.

### Attacks tried that are not findings

- **A cure racing a run.** Read the run path end to end. Before `FETCHED` every write is a compare
  that binds the generation (`stampAttempt`, `step`, `failFrom`, `applyFrom`). The unconditional
  `fail` and `apply` run only after `FETCHED`, a state rule A never matches. `bindSeller` is
  unconditional but writes the configured seller, which the cured run writes again. Mutation runs
  below.
- **A cure on a row past the route, and a second document.** A row in `REFUSED_VERSION_SKEW`
  never holds a number: `concludeBeforeFetch` writes `FAILED_ISSUANCE` when `mayHoldNumber`. Probe
  10 covers every other state. Probes 13 to 15 issue once.
- **Expiry, purge, re-arm and reprocess.** `EXPIRE` binds `due_since`, which the cure rewrites.
  `PURGE_ONE` repeats `received_at < cutoff`. The re-arm and both re-open statements name states
  a cure never meets.
- **Identity comparison.** All four identity columns are `NOT NULL`, and `InboundEvent` turns a
  null account into `''`, so `=` in rule A never meets NULL.
- **Hexagonal boundary.** `domain` gains one record component and one check; no import.
  `InboundIntake` is in `application`.

### Not tried

- Stripe's two-endpoint behaviour and the Dashboard resend version, live.
- A serialisation failure at commit (not at the statement) under SERIALIZABLE. The retry catches
  `40001` from the statement only; a commit-time `40001` would give `DEI-102` and 503 with no
  retry. Not reproduced.
- A host that joins `record` to its own transaction at REPEATABLE READ (stated residual: 503).
- Column-level grants narrower than `docs/schema-grants.sql`.
- The rolling window with two releases that both have the cure and different pins, with traffic
  on both: walked through on paper only.
- A parked row at the old pin when the copy at the pin arrives: answered as a duplicate by design
  (P8). It is cured only after its next run refuses it for skew and a second resend comes.
  The docs' repair text speaks of rows "stuck" for skew, which is right; not tried end to end.

### Probe files

Internal probe folder, `D-PR62-CipherProbePr62Test.java.txt` (core, package
`com.housedevinci.einvoice.adapter.jdbc`) and `D-PR62-CipherProbePr62StarterTest.java.txt`
(starter, package `com.housedevinci.einvoice.autoconfigure`). The fix pass adds them to the suite
before touching production code.
