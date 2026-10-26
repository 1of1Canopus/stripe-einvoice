# Security review: fix/one-run-per-event

## Pass 1 (2026-10-26)

Head `4a53ae6`, built from main `31197f1`. Subject: D-PR45-1 (MEDIUM, a 0.2.0 tag blocker), one
run per Stripe invoice under a session run lock (a PostgreSQL lock tied to one database connection).
Read against the approved design "one run per event", Revision 2.

**Verdict: MERGE WITH FIXES.** Two LOW findings, D-PR49-1 and D-PR49-2. The mechanism is right and
D-PR45-1 is fixed for the worker, sweeper and reprocess paths outside a host transaction.

### Numbers

| Check | Result |
|---|---|
| `CIPHER_PROBE_MAVEN=1 ./mvnw clean verify`, Docker up | core 811, starter 152, sample 9; 0 failed, 0 skipped; coverage gates met |
| Earlier review probes (`CipherProbe*`) | all green inside that run, unchanged |
| `CipherProbePr45Test` | 3 of 3 green; 3 of 3 red under mutation M1 |
| Mutations re-run by the review | M1, M3, M4, plus a revert of each builder fix (B1, B2): all red as stated |
| New probes | 3, all red on `4a53ae6` |

Mutations re-run here (break, run `OneRunPerEventTest` and `CipherProbePr45Test`, restore):

| Mutation | Red |
|---|---|
| M1 `runLocked` runs the work with no lock | `OneRunPerEventTest` 9 failures (T1, T4, T6, T7, T7b, T11, T13, T16, T17) and 1 error; `CipherProbePr45Test` 3 of 3 |
| M3 no proof before P3 | T16 only (T8 green, as the PR says) |
| M4 no stamp | T7b and T17 (T7 green, as the PR says) |
| B1 revert: the FETCHED/MAPPED steps write `next_attempt_at = NULL` | T7 |
| B2 revert: a failure on a dead session keeps its own code | T7, T7b, T8, T16 end `DEI-102`, not `DEI-104` |

### The builder's deviations

| # | Deviation | Ruling |
|---|---|---|
| a | `CipherProbePr45Test` outcome lines changed | Accepted. The escape checks are unchanged. The new lines are stronger: the second run must answer `DEI-217` and leave the row as it was. Without the lock all three tests go red (M1). The probe still proves D-PR45-1. |
| b | Built on main; rows 16 to 18 and T22 deferred | Accepted, with a tag condition: see QUESTIONS 51 and 53 below. |
| c | T13 is a recording-store test, not an ArchUnit rule | Accepted. ArchUnit cannot tell the plain store from the held one, because both are `InboundEventStore`. The recording store sees zero writes over five run shapes, and M1 turns it red. |
| d | D-ORPE-B1 and D-ORPE-B2 found and fixed | Both verified by reverting each fix (B1, B2 above). |
| e | Joined mode "like the PR 43 row lock" | Not accepted as built: D-PR49-1. |
| f | Eight race tests use a test-only lock that always grants | Accepted. `IssuanceTestHarness.withoutRunLock` lives under `src/test`. `JdbcInboundRunLock` is the only production `InboundRunLock`. The starter bean is not `@ConditionalOnMissingBean`. The compare-and-set, allocator and row-lock layers keep their own tests. |
| g | M3 leaves T8 green; M4 leaves T7 green | No test to add. Each mutation is caught by another test (T16; T7b and T17). T8 stays green because the PR 43 row lock refuses P3 too. The design keeps that lock on purpose (D-ORPE-6c). |

### The design's threat list

| Control | Status |
|---|---|
| Key = first 4 bytes of SHA-256 of the stored `object_id`; class `EINR`; no mode, nothing from settings | Verified by reading. `object_id` is `NOT NULL`, validated non-empty, and never updated. T19 covers it. |
| `DEI-217` only for "never acquired" | Holds for own connections. In joined mode a leaked lock gives a false `DEI-217`: D-PR49-1. |
| Permits shared by every caller; a host thread without a permit gets `DEI-102` | T18 verified. |
| Pool reserve refused at startup | Not reached in a started application: D-PR49-2. |
| Section 5 paths, one test each | Rows 1 to 15 tested. Rows 16 to 18 are not on this branch (QUESTIONS 51, 53). A path the page does not list is a run inside a host transaction. It is untested and defective: D-PR49-1. |
| PgBouncer session mode, server keepalive, idle-drop networks | Documented in README and `docs/index.md`. The text matches the design and the code. |
| Reserved codes (D-ORPE-8) | Fixed: `ReservedErrorCodes` and `ErrorCodesUniquenessTest`, probe green. |

### D-PR49-1, LOW (lock/window). A failed run in a host transaction leaves its run lock behind

A run can join a caller's transaction. Example: a host calls `IssuanceReprocess` from a
`@Transactional` admin action. Suppose one held write fails after the run has already written (a
statement timeout, a constraint, a full disk). `AbstractJdbcUnitOfWork.runJoined` then aborts the
caller's transaction at once (the "poison"). Next, `JdbcInboundRunLock.heldJoined` tries its
release inside that aborted transaction. The release fails with SQLState 25P02 and logs
`DEI-247`. The lock stays on the caller's connection, and that connection goes back to the pool.

Effect: every later run of that invoice answers `DEI-217` "another run is live", but no run is
live. This lasts until the pool retires the connection: 30 minutes with Hikari's default
`maxLifetime`, and never when it is 0. The PR 43 row lock does not have this defect.
`JdbcIssuanceStore.heldJoined` runs each unit under a savepoint, releases first, and poisons last.
QUESTIONS 50 says the run lock does the same; it does not.

Repro: `CipherProbePr49Test.probe_a_failed_joined_run_leaves_its_run_lock_on_the_callers_connection`.
A trigger refuses the `FETCHED` write. The run stamps the row, then its `FETCHED` write fails. The
caller rolls back. Result: the next run of the invoice on the same pool is busy, and one run lock
is still granted.

Fix (recommended): refuse joined mode in `JdbcInboundRunLock.whileHeld`, before the TRY, with a new
typed code from the free range. Add the code to `ErrorCodes`, check it against
`ReservedErrorCodes`, and record nothing. A run never joins a host transaction. Two reasons: it
fetches from Stripe, renders and archives, so it would hold that transaction open for minutes;
and its writes become visible only at the host's commit, after the lock has already been released.
`IssuanceReprocess` javadoc and `docs/index.md` then say: call it outside a transaction. Tests:
the probe above, rewritten to expect the refusal and no lock left; and a starter test where
`IssuanceReprocess` inside `@Transactional` refuses and records no ledger row.

Keeping joined runs instead would need a deferred-poison scope shared by the run lock and the row
lock. That is a new mechanism, so it needs a design page, and the founder decides (QUESTIONS 50).

### D-PR49-2, LOW (false completion). The pool rule never runs in a started application

`einvoiceRunPoolRule` carries `@ConditionalOnBean(IssuanceUnitOfWork.class)`. It is declared in the
same configuration class before `einvoiceIssuanceUnitOfWork`. Spring evaluates the condition when it
registers the rule, and at that moment the unit of work's definition does not exist yet. So the
condition is false, and the rule is never created.

Example: concurrency 3 on a pool of 8 needs 11 connections, yet the application starts. The tests
did not catch this: `RunPoolRuleTest` (T12, T21) calls the bean method by hand. README and
`docs/index.md` promise a startup refusal that does not happen. D-ORPE-4 and D-ORPE-7b are
therefore closed in the tests only, not in a running application.

Repro: `CipherProbePr49StarterTest.probe_the_pool_rule_exists_in_a_started_context` (no bean named
`einvoiceRunPoolRule`) and `probe_concurrency_three_on_a_pool_of_eight_refuses_a_started_context`
(the context starts).

Fix: in `EInvoiceIssuanceAutoConfiguration.einvoiceRunPoolRule`, drop the `@ConditionalOnBean`.
Take `ObjectProvider<IssuanceUnitOfWork>` and run the rule when a unit of work is available; that
includes one the host builds. Add both probes to the starter suite. `RunPoolRuleTest` stays for the
arithmetic.

### Rulings on QUESTIONS 50 to 53 (recommendations; the founder decides)

- **50 (joined mode):** do not accept as built (D-PR49-1). Recommend: refuse joined mode for the
  run lock. The alternative is a design page.
- **51 (rows 16, 17, T22 not here):** accept. PR 46 rebases on this branch and adds rows 16 and 17
  and T22, with the purge's pattern. The 0.2.0 tag waits until they are green at PR 46's review.
- **52 (race tests without the lock):** accept. The helper is test-only and cannot reach production
  (deviation f above).
- **53 (page 2 order):** accept. Row 18's argument holds on this tree: the stamp and every step
  write a next attempt, and reverting B1 turns T7 red. The page 2 review re-checks row 18 with its
  re-arm test.

### Probes

`CipherProbePr49Test` (core, package `adapter.jdbc`) and `CipherProbePr49StarterTest` (starter,
package `autoconfigure`). They are kept with the review's internal probe files. The fix pass adds
them to the suite red before it changes production code.

## Pass 2 (2026-10-26, security review, head cc16f17)

Limited pass: the two pass 1 findings, the verify numbers, and the DEI-246 note. Verdict: **MERGE**.

### Numbers

Full `verify` with `CIPHER_PROBE_MAVEN=1`, Docker up: core 815, starter 157, sample 9; 0 failed, 0
skipped. The fix pass reported 814 / 156 / 9; the extra test in core and in starter is the pass 2
probe below.

### D-PR49-1: verified

The run lock refuses a run inside a caller's transaction with `DEI-218 RUN_IN_HOST_TRANSACTION`
before the lock is tried. `DEI-218` is in `ReservedErrorCodes`; T15 is green.

Mutation F1 (the joined check disabled), run by the reviewer: the pass 1 probe stays green, as the
fix pass said. The fall-through runs the own-connection path on the caller's connection, and that
path releases its lock. So the pass 1 probe alone does not pin the guard.

New probe `probe_a_host_rollback_after_a_run_leaves_nothing_committed`. Example: the host inserts a
row in its transaction, calls a run that would stamp the event, then rolls back. Under F1 the host's
row stays committed (expected 0, was 1): the run's commits publish the host's write. With the guard
the call fails with `DEI-218`, and nothing stays committed: not the host row, not the stamp, no run
lock. `JoinedRunRefusalTest` and `JoinedRunRefusalSpringTest` are also red under F1.

### D-PR49-2: verified

The pool rule now runs after every singleton exists, through `ObjectProvider<IssuanceUnitOfWork>`.
In a started context, concurrency 3 on the shared pool of 8 fails startup. New probe
`probe_the_refusal_is_the_pool_rule_and_the_boundary_starts` checks that the failure is the rule's
own `DEI-100` naming 11 connections (not any startup error), and that concurrency 2 (needs exactly
8) starts. Three mutations, each red: F2a unit-of-work check inverted, F2b comparison inverted
(`need < max`), F2c off by one (`need >= max`). Changing `IssuanceOperationsTest` from concurrency
3 to 1 is correct: 3 needs 11 connections and the test pool has 8.

### DEI-246 note: new row D-PR49-3, LOW, not on this branch

The note understates it. The approved archive marker design page reserves **two** codes:
`DEI-246 ARCHIVE_UNMARKED` and `DEI-247 ARCHIVE_FOREIGN`. On main, `DEI-247` is already
`ROW_LOCK_RELEASE_FAILED`. Example of the harm: built as designed, `DEI-247` would mean both "a
lock could not be released, the outcome stands" and "wrong archive volume, this never heals". A
handler keyed on the code would log and carry on. Neither code is in `ReservedErrorCodes`, so T15
cannot catch it. This exists on main before PR 49 and does not block it.

Fix, own small PR: add `DEI-246 ARCHIVE_UNMARKED` to `ReservedErrorCodes`; pick a free code for
`ARCHIVE_FOREIGN` (DEI-248 and DEI-249 appear in another design page, so check the next free one),
reserve it, and update the design page; extend `ErrorCodesUniquenessTest` with a case like the
`DEI-218` one.

### Verdict

**MERGE.** D-PR49-1 and D-PR49-2 are closed with probes that fail on their own mutations. D-PR49-3
goes to its own PR before the archive marker is built.
