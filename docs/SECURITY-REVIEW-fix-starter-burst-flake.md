# Security review: fix/starter-burst-flake (PR 58)

## 2026-11-02, pass 1 on 28a0715

**Verdict: MERGE WITH FIXES.** One LOW finding (D-PR58-1). No production defect.

The change touches one test file. It closes the CI flake D-PR57-CI-1 in the I-10 burst test
(I-10: back-pressure never loses an event). The cause the builder gives is right: a worker run
stamps its row (next attempt = clock now + backoff) before it reads Stripe, so a row inside a
run is RECEIVED but not due at the test's fixed time. The new wait removes that race. But the
test can still pass while it checks nothing (D-PR58-1).

### Build

`./mvnw -q verify -pl stripe-einvoice-spring-boot-starter -am`, Docker up, run once on 28a0715.

| Module | Tests | Failed | Errors | Skipped |
|---|---|---|---|---|
| core | 1144 | 0 | 0 | 3 |
| starter | 164 | 0 | 0 | 0 |

The three skips are the known Linux-only filename tests:
`FilesystemArchiveListingTest.probe_two_undecodable_names_stop_the_walk_for_ever`,
`FilesystemArchiveListingTest.two_names_that_decode_alike_are_two_orphans_and_the_walk_moves_on`,
`CipherProbePr57Test.probe_an_undecodable_name_longer_than_the_cursor_bound_stops_the_walk_for_ever`.
A skipped test is not a passing test; they run on the Linux CI runner.

### Question 1: is the race test-only?

Yes. Probe `check_a_sweep_pick_of_a_held_row_does_not_run_it_twice` (scratch, kept by the
review): one event, a Stripe source that blocks inside its read, retry backoff set to 1 ms so
the held row is past its own stamp, concurrency 2 so a second run can start.

- While the first run is held, a second pick through `IssuanceUnitOfWork.process` answers
  `DEI-217` (run in progress) and writes nothing.
- A second `IssuanceWorker.submit` of the same event while held also ends as busy: the Stripe
  read count for the invoice stays 1.
- After release the row exists and ends in its normal state.

So a sweeper pick of a row in flight cannot run the invoice twice: the run lock refuses it. The
stamp is the time a successor may start; it is not a lock and it does not need to be one.

### Question 2: does the wait hide a dropped event?

No. Mutation M1: `IssuanceWorker.submit` returns `true` for the 10th event without queuing it
and without counting it as refused. The burst test goes red after the 30 s bound:
`[refused plus finished runs, out of 50 submitted] expected: 50L`. The wait is on a counter
the worker owns, so an event the worker forgets keeps the count short.

Note: a run that throws a `RuntimeException` is not counted as processed either, so it also
ends as a 30 s timeout. That is red, which is right; the message just names the count, not
the exception.

### Question 3: is it still a control for I-10? (D-PR58-1, LOW)

Partly. The last assertion is `due.containsAll(rejectedOf(events))`, and `rejectedOf` keeps
only the rows that are still RECEIVED. If the refused rows leave RECEIVED, the list is empty and
the assertion passes on nothing. The test never checks that the number of refused rows it
looks at equals the number the worker refused.

Repro (mutation M2): after the wait, call `IssuanceSweeper.sweepOnce()` once, which is what the
scheduled sweep does. The rows were received at the test's fixed date in January, so at the
real clock the sweep expires them as stranded. Result on 28a0715: **green**, with 0 of 48
refused rows checked. Any change that moves refused rows to a state the sweeper does not pick
(the real shape of "losing an event") passes the same way.

The schedule makes this reachable without a mutation: the burst test does not pin the sweep
interval, so the default (first sweep 1 minute after the context starts) applies. On a slow
runner a sweep inside the test turns the control into a no-op without any red.

Fix (test only, file `CipherProbeIssuanceStarterTest`, test
`probe_a_burst_past_the_queue_capacity_loses_no_event`):

1. Add `"einvoice.issuance.sweep-interval=24h"` to the test's properties, so the worker is the
   only thing that moves rows during the test.
2. Before the `containsAll`, assert
   `assertThat(rejectedOf(events, inbound)).hasSize((int) worker.rejectedCount());`
   so every refused event is still RECEIVED, and then that each one is due.

Checked on the review copy: with both changes, mutation M2 is red
(`Expected size: 48 but was: 0`), and without the mutation the test is green 5 of 5.

### Question 4: timing left that CI load can break

- The 300 ms Stripe read only widens the window; every wait after it is on an event (a latch,
  then a counter), not a sleep. On a slow runner the settle takes longer; it does not change
  the result. A normal settle is about 1 s (two or three 300 ms reads), far under 30 s.
- `rejectedCount() > 0` right after the 50 submits: one thread and a queue of one, with a first
  read of 300 ms, so at least 47 refusals unless 50 `execute` calls take 15 s. Not reachable.
- The only timing left is the scheduled sweep, covered by D-PR58-1 fix 1.

### Findings

| Id | Severity | What | Fix | Probe |
|---|---|---|---|---|
| D-PR58-1 | LOW | Burst test passes on an empty set when refused rows leave RECEIVED (scheduled sweep in the window, or a regression); sweep interval not pinned | Pin `sweep-interval=24h`; assert refused rows still RECEIVED equal `rejectedCount()` | Mutation M2 (one `sweepOnce()` after the wait): green on 28a0715, must be red after the fix |

Re-verification: the fix pass shows M2 red and the test green in a loop of 20 in the PR body.
