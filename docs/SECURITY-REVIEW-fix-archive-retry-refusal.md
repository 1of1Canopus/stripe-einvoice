# Security review: fix/archive-retry-refusal (PR 42)

## Pass 1 (2026-10-19)

Head reviewed: `4a7bd08` (lock commit `fc0ee16`). Base: main `0d66ca8`. PR 41 (filesystem store
fixes) is not merged, so this pass reviews the archive contract, not the filesystem store.
Mechanism under review: what a resumed issuance row may conclude (QUESTIONS 38, D-DD-B1), with
the archive pre-read (D-RTR-1), the burn clause (D-RTR-2), the extra burn paths (D-RTR-3), the
WARN with both hashes (D-RTR-6), and the session row lock (D-RTR-7, rulings R1 to R7).

**Verdict: MERGE WITH FIXES.** No HIGH, no MEDIUM. Three LOW, two INFO (D-PR42-1 to D-PR42-5).
Every prior finding on this branch is confirmed: its probe is green on the head and its mutation
is red. The new findings are all in the lock's edges (pool use, the pooler check, the lock key
scope, one log code) and in one README section. None of them lets a number be burned next to a
stored document. D-PR42-2 needs a new mechanism: **DESIGN STOP**, a design page from the builder,
reviewed before code.

### Numbers

| check | result |
|---|---|
| `./mvnw -B clean verify`, Docker up | BUILD SUCCESS; core 675, starter 139, sample 9; 0 failures, 0 skipped (Surefire XML); coverage gate passed |
| the 9 design probes (10 methods) and the race probe | green |
| mutations 1 to 7 and L1 to L5, re-run by the reviewer, each alone, restored by `git checkout` | 12 of 12 RED, on the tests the PR body names |
| new review probes (`CipherProbePr42Test`, `CipherProbePr42BouncerTest`) | 6 probes, 6 RED on the head; 1 control, green |

Mutation results, measured:

| # | mutation | RED |
|---|---|---|
| 1 | edge removed from the trigger only | probes 1, 2, 7a, 7b, 8; `LedgerTriggerTest.a_resumed_row_can_be_burned_by_the_runtime_role` |
| 2 | edge removed from the enum only | probes 1, 2, 7a, 7b, 8; `IssuanceStateTest.a_resumed_row_that_is_refused_can_burn_its_number` |
| 3 | `ARCHIVING` edge removed in both places | probes 2, 7b; the `LedgerTriggerTest` burn test |
| 4 | pre-read removed | probes 3, 4, 5 |
| 5 | read failure mapped to "absent" | probe 5 |
| 6 | D-RTR-2 clause disabled | probe 6; `LedgerTriggerTest.a_burn_writes_only_the_rule_id` |
| 7 | `SET search_path` dropped from the guard | probe 9 and the two other trigger probes (install refused) |
| L1 | lock skipped | race probe; held-lock retry; try-only; collision; released-lock; terminated holder |
| L2 | unlock skipped | released-lock; free-lock path; terminated holder; eviction; joined; collision |
| L3 | session check disabled | `transaction_pooling_is_refused_before_a_number_is_taken` |
| L4 | decide from the allocator's read | `the_row_is_read_again_under_the_lock` |
| L5 | eviction skipped | `an_evicted_connection_never_comes_back_from_the_pool_holding_the_lock` |

### Attacks and results

1. **Key collision, rows 5 and 5 + 2^32.** Not a finding. The key is `(EINA, (int) id)` and the
   pg_locks pre-check compares `id & 0xFFFFFFFF`, so both forms agree. The builder's
   `two_rows_two_to_the_32_apart_share_a_key_and_only_retry` is green and goes red under L1 and
   L2. Example: row 5 holds the lock; row 4294967301 gets `DEI-245` and nothing is read.
2. **Pool checkout and checkin.** Not a finding. Hikari does not reset session state on checkin,
   so a lock that is not released would go back to the pool. The code does not let that happen:
   a failed release runs `pg_terminate_backend` on its own session. Measured in the probe log:
   Hikari marks the connection broken on SQLSTATE 57P01 and drops it. L5 is red. Example: pool of
   one, eviction, the next borrow has a new backend pid and no lock.
3. **Crash between the lock and the ARCHIVING mark.** Not a finding. The lock dies with the
   connection. The row is still `NUMBERED` with no hash, and the PUT comes after the mark, so no
   object can exist. The next run renders as a first run. A half-open TCP connection keeps the
   lock longer; that only retries (README names the keepalive settings).
4. **Two invoices on one connection in sequence.** Not a finding. L2 turns the released-lock and
   path tests red, and the eviction tests cover a failed release. Example: row A then row B on a
   pool of one; after A, `pg_locks` shows no `EINA` lock.
5. **PgBouncer check with the first two connections on one server, and pool mode changes.**
   Finding D-PR42-2: the check passes a transaction pooler. Session mode passes, re-run: the
   builder's `session_pooling_issues` is green, and the review control
   `session_pooling_with_round_robin_still_passes` is green.
6. **Re-read under the lock vs an operator void.** Not a finding. L4 is red. A void that lands
   between the re-read and the burn makes the burn illegal (`a_void_during_the_retry_wins`,
   `DEI-122`). A void during a PUT stays outside the lock by ruling R3.4 and is D-RTR-4's window.
7. **Chained event hash input.** Not a finding. `IssuanceEvent.failed` copies the row's hash and
   key and the normalised rule id; the canonical form hashes all three.
   `the_verifier_reads_a_burned_retry_as_intact` is green, and a changed rule id reads `BROKEN`.
8. **Empty mount point.** Agreed: it is a design stop (register D-RTR-13), its own design page
   and PR, before the 0.2.0 tag. The interim README text is too thin: D-PR42-5.
9. **Lock across databases and schemas.** Finding D-PR42-3.
10. **Joined unit of work and the first check.** Finding D-PR42-1.

### Findings

#### D-PR42-1 (LOW): the first session check starves a joined caller

Repro: `CipherProbePr42Test.probe_a_joined_caller_on_a_pool_of_two_is_not_starved_by_the_session_check`.
Pool of two. A host transaction holds one connection (joined unit of work). The first
`whileHeld` runs the R5 check, which opens two more connections at once. Only one is free, so the
second waits for the pool and times out. Measured: `DEI-102`, after the pool timeout. The check is
not cached on failure, so the same thing happens on every retry: with a pool of two and a host
transaction, nothing is ever issued. Ruling R3.6 required "no worker waits for a second pooled
connection while it holds one, joined case included". The builder's joined test passes only
because it calls `verifySessionPinning()` first, on a free pool. The README says "the pool needs
at least two connections", which is false for a joined host.

Example: a Spring host with `@Transactional` on its webhook handler and
`spring.datasource.hikari.maximum-pool-size=2` restarts. Every event fails `DEI-102` until the
pool is resized.

Fix (fix pass, a correction, no new mechanism):
* `EInvoiceIssuanceAutoConfiguration`: run `JdbcIssuanceStore.verifySessionPinning()` once at
  startup, outside any transaction (for example a `SmartInitializingSingleton` bean). `DEI-104`
  fails the startup. `DEI-102` (database not reachable yet) logs a WARN and leaves the lazy check.
* README "Database connections": "the check needs two free connections besides the caller's own
  the first time; the starter runs it at startup".
* `IssuanceRowLockStoreTest.a_joined_unit_of_work_holds_the_lock_on_the_callers_connection`: remove
  the `own.verifySessionPinning()` line, or keep it and add a separate test for the starter path.
* Probe above green through the starter path: a starter test with a pool of two, a host
  transaction, and one issuance that concludes `COMPLETED`.

#### D-PR42-2 (LOW): the pooler check passes a transaction pooler; statement mode answers DEI-102

Repro: `CipherProbePr42BouncerTest.probe_a_round_robin_transaction_pooler_is_refused` and
`probe_statement_pooling_is_refused_as_not_pinned`. Real PgBouncer 1.26 (same pinned image as the
builder's test), `pool_mode = transaction`, `server_round_robin = 1`, `default_pool_size = 2`,
two idle server connections. Measured: `verifySessionPinning` returns normally. Connection one
uses server S1 and sets the probe setting. Connection two gets S2. Connection one's next statement
gets S1 back, with the same pid and the setting still on it. Every condition of the check holds.
The same order happens under the default LIFO mode when another client takes S1 between the two
connections and gives it back (suspected, not measured: it needs concurrent load). In statement
mode the check's own transaction is refused by the pooler and comes out as `DEI-102`
(unavailable, retried) instead of `DEI-104`.

Why LOW and not higher: the TRY statement checks `pg_locks` for any holder, so mutual exclusion
still holds across server connections and no burn follows. What breaks is the release: the
unlock can land on another server, return false, and evict that server connection, while the
lock stays on S1. The row then answers `DEI-245` until the pooler closes S1 (default
`server_lifetime` one hour), and the README promise "a pooler in transaction mode is refused" is
false. (The leak is reasoned from the measured false pass, not measured itself.)

Example: a host fronts PostgreSQL with PgBouncer in transaction mode and round robin. The startup
check passes. Number `F-2026-0042` stays `ARCHIVING` with `DEI-245` for an hour after one release
lands on the wrong server.

**DESIGN STOP.** The check is a heuristic on how the pooler picks servers, and a stronger one is a
new mechanism. Property that must hold: *no issuance gets past the lock unless every statement of
the holder, from the TRY to the UNLOCK, ran on the backend that took the lock; when that cannot be
shown, the run refuses with `DEI-104` before the archive read, the PUT, or the burn.* Paths the
design must cover: PgBouncer transaction mode in LIFO and in round robin, under concurrent load;
statement mode (must answer `DEI-104`); `server_reset_query_always = 1`; a pooler mode changed by a
reload while the application runs (the check result is cached for the life of the data source);
other transaction poolers (Supavisor, Odyssey, pgcat). One page from the builder, reviewed before
code. Hint, not a prescription: the TRY already returns the holder pid, and the holder's later
statements could return theirs.

#### D-PR42-3 (LOW): the pg_locks pre-check ignores the database

Repro: `CipherProbePr42Test.probe_a_row_lock_in_another_database_does_not_block_this_one`. Advisory
locks belong to one database. The TRY statement's `pg_locks` pre-check has no `database` filter,
so it sees locks of every database in the cluster. Measured: row 4242 held in database A, then
`tryLock(4242)` in database B returns empty, although `pg_try_advisory_lock` in B would succeed.
R2 accepted collisions only for ids 2^32 apart, as rare. This makes them routine: two databases
on one cluster (staging and production, or one database per seller) start their ids at 1, so the
same row ids collide during every PUT window.

Example: production row 42 is mid-PUT. Staging row 42 on the same cluster gets `DEI-245` and
waits a backoff.

Fix (fix pass, a correction): in `IssuanceRowLocks.TRY`, add
`AND l.database = (SELECT d.oid FROM pg_catalog.pg_database d WHERE d.datname = pg_catalog.current_database())`.
Under a pooler all server connections reach one database, so the D-RTR-7 defence is unchanged.
The probe above goes green; L1 and the collision test stay as they are.

#### D-PR42-4 (INFO): an eviction is logged with the pooler refusal code

Repro: `CipherProbePr42Test.probe_an_eviction_is_not_logged_as_a_pooler_refusal`. Measured: the
ERROR line of `IssuanceRowLocks.evict` ends with `(DEI-104)`. A failed release usually means the
backend died (failover, `pg_terminate_backend`, network). An alert on `DEI-104` then sends the
operator to the pooler configuration for a connection loss.

Example: a failover kills the holder's backend. The log says `DEI-104`. The operator checks
PgBouncer, which is not there.

Fix (fix pass): a new constant in `ErrorCodes`, for example `ROW_LOCK_RELEASE_FAILED = "DEI-246"`,
with javadoc; use it in `IssuanceRowLocks.evict`; add it to the README error table.

#### D-PR42-5 (INFO): the README mount-point note names neither the harm nor a check

Repro: `CipherProbePr42Test.probe_the_readme_names_the_mount_point_harm_and_a_concrete_check`. The
section says an empty directory "looks like a new, empty archive". It does not say what follows
(a resumed row can burn a number whose document is on the unmounted volume; new documents are
written to the local disk). Its example ("a Kubernetes volume with no fallback directory") is the
case where the problem cannot happen, and it gives no check for the cases where it can.

Fix (fix pass), replace the paragraph with text of this shape:
"Until the archive marker lands (before 0.2.0), an empty directory where the volume should be
looks like a new, empty archive. Then a resumed row can burn a number whose document is on the
real volume, and new documents are written to the local disk. Make the start fail when the
volume is not mounted: with systemd, `RequiresMountsFor=<archive root>` on the service; in a
container entrypoint, `mountpoint -q <archive root> || exit 1`. A Kubernetes volume in the pod
spec is mounted or the pod does not start."

### Prior findings on this branch

| id | result |
|---|---|
| D-DD-B1 | closed: probes 1 and 2 green; mutations 1 and 2 red |
| D-RTR-1 (MEDIUM) | closed: probes 3, 4, 5 green; mutations 4 and 5 red |
| D-RTR-2 (LOW) | closed: probe 6 green; mutation 6 red |
| D-RTR-3 (LOW) | closed: probes 7a and 7b green; mutations 1 to 3 red on them |
| D-RTR-6 (INFO) | closed: WARN carries both hashes and no content; javadoc and README text read |
| D-RTR-7 (MEDIUM) | closed for the race: race probe green, L1 red; the lock's edges are D-PR42-1 to D-PR42-4 |
| D-RTR-B1 (INFO) | confirmed: `a_reprocess_never_resumes_a_numbered_row` green |
| D-RTR-B2 (MEDIUM) | confirmed: `probe_an_unavailable_archive_is_retried_on_resume_and_on_redelivery` green |
| D-RTR-B3 (LOW) | confirmed: L5 red on the new eviction test |

### Fix list

1. D-PR42-1: startup check in the starter, README wording, joined test without the pre-check.
2. D-PR42-2: DESIGN STOP, one page from the builder, reviewed before code.
3. D-PR42-3: `database` filter in `IssuanceRowLocks.TRY`.
4. D-PR42-4: own error code for the eviction log line.
5. D-PR42-5: README mount-point paragraph.

The six probes are in `CipherProbePr42Test` and `CipherProbePr42BouncerTest` (package
`com.housedevinci.einvoice.adapter.jdbc`, core tests), handed over with the fix list. The fix pass
adds them to the suite before it touches production code. Each must go green with its fix, and
the full `./mvnw -B clean verify` must stay at 0 skipped.

## Pass 2 (2026-10-20)

Head `3a4b6b5` on `fix/archive-retry-refusal-v2` (PR 43). The token proof mechanism is `35a43e1`.
`main` is not merged into the branch, as instructed.

**Verdict: MERGE WITH FIXES.** No HIGH, no MEDIUM. Two LOW and one INFO (D-PR43-1 to D-PR43-3).
D-PR43-1 needs a new mechanism: **DESIGN STOP**. The CI check Build & test is still red
(D-PR42-7, the `CipherProbePr40Test` failure that also fails on `main`). It blocks the merge on its
own until `main` is merged with its fix.

### Numbers

| check | result |
|---|---|
| `./mvnw -B clean verify`, Docker up, Surefire XML | BUILD SUCCESS; core 755, starter 143, sample 9; 0 failures, 0 skipped |
| pass 1 probes (4 in `CipherProbePr42Test`, 2 in `CipherProbePr42BouncerTest`) | 6 of 6 green |
| C4 probes (`probe_foreign_unlock_after_the_pre_read_refuses_the_burn`, `probe_foreign_unlock_during_the_put_refuses_mark_issued`), real PgBouncer transaction mode | 2 of 2 green |
| mutations T1 to T5 and L1 to L5, re-run by the reviewer, each alone, restored by `git checkout` | 10 of 10 RED on the tests the PR body names |
| mutations 1 to 7 (trigger, enum, pre-read) | not re-run: the schema, `IssuanceState` and the pre-read code are unchanged since pass 1 (`git diff 8bda84a..3a4b6b5` touches none of them); the pass 1 results stand |
| new review probes (internal `CipherProbePr43Test`, `CipherProbePr43StartupTest`) | 2 probes, 2 RED on the head |

Mutation results, measured:

| # | mutation | RED |
|---|---|---|
| T1 | proof skipped on held transactions | both C4 probes. The reset probe stays green (D-PR43-3) |
| T2 | token not compared | `probe_release_frees_another_holders_lock`, `probe_a_second_key_on_one_backend_never_passes_the_first_proof`, `a_new_session_holding_the_key_without_the_token_fails_the_proof` |
| T3 | release without the proof | `probe_release_frees_another_holders_lock`, `probe_a_second_key_on_one_backend_never_passes_the_first_proof` |
| T4 | `08P01` not mapped | `probe_statement_pooling_is_refused_as_not_pinned` |
| T5 | TRY not committed when autocommit is off | `probe_a_rolled_back_held_unit_keeps_the_token_on_a_pool_without_autocommit` |
| L1 | TRY answers "got it" for a held key | race probe and 14 path and store tests |
| L2 | unlock skipped | released-lock, free-lock path, terminated holder, eviction, collision, C1 probe |
| L3 | session check disabled | `transaction_pooling_is_refused_before_a_number_is_taken`, both pooler probes |
| L4 | decide from the allocator's read | `the_row_is_read_again_under_the_lock` |
| L5 | eviction skipped | `an_evicted_connection_never_comes_back_from_the_pool_holding_the_lock` |

### The builder's deviations

1. **A release whose proof fails answers DEI-104 even after the work succeeded.** Correct. Keep it.
   The retry does not do the work twice. It reads the row again under a fresh lock. An `ISSUED` row
   takes the redelivery path: one archive read and a hash compare, no render, no PUT, no number. A
   burned row ends terminal. An `ARCHIVING` row whose object exists resumes from the archive with no
   PUT. Example: `F-2026-0042` is marked `ISSUED`, then a foreign `pg_advisory_unlock_all()` frees
   the lock before the release. The run answers DEI-104. The next attempt finds `ISSUED` and
   completes with the same number. Evidence: the C4 PUT probe (row stays `ARCHIVING`, retried) and
   the pass 1 resume probes 3 and 4, green. One wording defect remains: the release refusal says
   "nothing was written in this transaction", which is false at the release. It is part of
   D-PR43-1.
2. **Joined mode runs no per-transaction proof, only the release proof.** Correct. In joined mode
   the TRY, every held statement and the release are one caller transaction. Any pooler keeps one
   transaction on one server connection. No foreign statement can run on that backend while the
   transaction is open, so nobody else can free the lock. A host transaction that spans two of our
   units takes two locks in turn: each unit runs its own TRY and its own release, and the second
   unit cannot run without its own TRY. A caller connection in autocommit is refused with
   `HOST_AUTOCOMMIT` before the first read. Spring joins only an actual transaction. The design
   page's "Order" line still says the proofs run in joined mode; the build notes correct it.
   The one gap found is not a lost lock. It is an aborted caller transaction: D-PR43-1.
3. **`tryLock` returns the token.** Accepted. The internal copy of `CipherProbePr42Test` is
   updated to `Optional<String>`.
4. **The reset probe stays green under T1.** Measured. That is a weak probe: D-PR43-3.

### Attacks with no finding

- **The token in logs at DEBUG.** This module never logs the token. The two messages name the row
  id only (C6, asserted). The JDBC driver can log bound values at its most verbose level, which I
  did not reproduce. Not a finding either way: the token is not a secret. The design review
  measured that any client on the same backend can read it with `current_setting`. It proves
  "this acquisition", and it grants nothing to someone who reads it. Closed without a code change.
- **The round-robin refusal on a plain pool.** The four extra statements run on connection one,
  which the pool never moves while it is checked out. A plain pool cannot fail them. Every database
  test in the suite runs the check on a plain pool and passes, and
  `session_pooling_with_round_robin_still_passes` is green. The one false refusal is a pool of one
  connection, and it is not a DEI-104: D-PR43-2.

### Findings

#### D-PR43-1 (LOW): an aborted caller transaction turns the outcome into DEI-104 and aborts the caller's connection

Repro: internal `CipherProbePr43Test.probe_an_aborted_joined_transaction_keeps_its_outcome_and_the_callers_connection`.
Joined mode. A held write (`markFailed`) times out on the issuance row lock (`lock_timeout`). The
run handles that refusal, as `concludeIssuance` does. PostgreSQL has now aborted the caller's
transaction, so the release statement cannot run. Measured: `whileHeld` throws `DEI-104` "the row
lock of issuance row 1 is no longer held by this run on its database session; nothing was written
in this transaction". The lock was never lost. The real code is gone. By code reading, the second
release in `finally` fails the same way, and `evict` then calls `abort()` on the connection the
caller's transaction manager still owns. That is the joined eviction of D-RTR-7, now also hit by a
plain lock timeout. Example: a host batch job holds a row of `F-2026-0042`; the issuance in the
host's transaction reports "pooler in transaction mode", and the host's rollback then fails on a
closed connection.

**DESIGN STOP.** The property, for the builder's one-page design: in joined mode, (a) a release
that cannot run because the caller's transaction is aborted keeps the run's own outcome and code;
(b) the row lock is still released by the end of the caller's transaction, whatever its result;
(c) this module never aborts or closes a connection that a caller's transaction owns. Paths:
`CallerConnectionUnitOfWork`, `SpringManagedUnitOfWork` (commit, rollback, rollback-only), and the
own-connection path unchanged. Also in this item: the release refusal gets its own message, which
says the run's committed writes stand and the next attempt converges, and never "nothing was
written".

#### D-PR43-2 (LOW): a pool of one connection only WARNs at startup and stalls every issuance

Repro: internal `CipherProbePr43StartupTest.probe_a_pool_of_one_fails_the_startup_as_a_configuration_error`.
Hikari with `maximumPoolSize = 1`. The check holds the only connection while it waits for a
second one. After the pool's connection timeout it ends `DEI-102`. The starter logs "it runs before
the first issuance row lock instead", which is never true for this pool. Every issuance then holds
the only connection for the whole timeout (30 s by default), starving the host, and ends DEI-102.
The README says the check needs two free connections, but nothing enforces it.
Fix (fix pass): in `EInvoiceIssuanceAutoConfiguration.einvoiceSessionPinningCheck`, when the data
source unwraps to `HikariDataSource` (or `HikariConfigMXBean`) with a maximum pool size below 2,
fail the startup with `ErrorCodes.CONFIG` and a message naming the pool size and the minimum. Any
other data source keeps today's path. README: one sentence. Test: the probe above, asserting
`CONFIG`.

#### D-PR43-3 (INFO): the reset probe does not pin the per-transaction proof

Repro: mutation T1 (proof skipped in `HeldConnectionUnitOfWork.beforeTransaction`) leaves
`probe_reset_always_pid_match_is_not_proof` green. Measured. The work is one read; the read runs
without a proof, and only the release refuses. The design asked for this probe to be red under T1.
Fix (fix pass): the work sets a flag after its held read returns; assert that the flag was never set
(the first held transaction refused, not the release). T1 must then turn it red.

### Prior findings on this branch

| id | status |
|---|---|
| D-PR42-1 (LOW) | closed: probe green, F1 and F1b red (pass 1 fix) |
| D-PR42-2 (LOW) | closed by Revision 3: both pooler probes green, T1 to T4 red |
| D-PR42-3 (LOW) | closed: probe green |
| D-PR42-4 (INFO) | closed: probe green, `DEI-247` |
| D-PR42-5 (INFO) | closed: probe green |
| D-PR42-6 (MEDIUM) | closed by Revision 3: case 1 and 2 probes green, T2 and T3 red |
| D-PR42-7 (LOW) | open: Build & test red on CI on `3a4b6b5`; waits for the `main` merge |
| D-PR42-8 (LOW, C1) | closed: C1 probe green, T5 red |
| D-PR42-9 (INFO, C2) | closed: C2 probe green, T2 and T3 red, text present |
| D-PR42-10 (INFO, C3 to C6) | closed: `IS TRUE`, the C4 probes green under T1 red, `08P01` only (T4 red), C6 text and message check present |
| D-RTR-7 (MEDIUM) | closed for the race (L1 red); its joined release edge is D-PR43-1 |

### Fix list

1. D-PR43-1: DESIGN STOP, one page from the builder, reviewed before code.
2. D-PR43-2: pool-size refusal in the starter, README sentence, probe asserting `CONFIG`.
3. D-PR43-3: the reset probe's flag assertion; show T1 red.
4. D-PR42-7: merge `main` with the flaky-probe fix; Build & test green on CI.

## Pass 3, limited re-verification (2026-10-21)

Head `0327481` on `fix/archive-retry-refusal-v2` (PR 43). Scope: D-PR43-1 candidate B as built,
D-PR43-6, and the two deviations the builder declared. Closed findings are not re-opened.

**Verdict: NOT MERGE.** One MEDIUM, one LOW, one INFO (D-PR43-7 to D-PR43-9). The held section is
right. The gap is the joined path outside it: a joined allocation skips the new autosave check and
the new DEI-248 mapping.

### Numbers

| check | result |
|---|---|
| `./mvnw -q verify` (reactor root), Docker up, Surefire XML | BUILD SUCCESS; core 781, starter 147, sample 9; 0 failures, 0 skipped |
| existing review probes (`CipherProbe*`, unchanged) | all green in that run |
| builder tests `JoinedModeLockTest` (11) | 11 of 11 green |
| new review probes (internal `probes/CipherProbePr43Pass3Test.java`, 6) | 4 green, 2 RED |

Mutations J1 to J8 were not re-run (budget). The probes below cover the same controls.

### Item 1: D-PR43-1 candidate B as built

Green, measured with new probes:

* `probe_a_swallowed_unit_failure_then_a_good_write_still_commits_nothing`. The work catches a
  failed unit, reads again and returns normally. The run still throws `DEI-102` (the failed unit's
  code). A suppressed `SQLException` has SQLState `DE248`. `pg_locks` shows 0 row locks before the
  commit. The connection is open, `autoCommit` false. The commit keeps 0 rows and the counter does
  not move.
* `probe_a_held_entry_on_an_aborted_transaction_is_dei_248_with_no_key`. The caller runs
  `SELECT 1/0`, then calls `whileHeld`. Answer `DEI-248`, 0 row locks, connection open.

Reading of `heldJoined`: release, poison, rollback-only each in its own `try`; the run's own
exception is thrown, the poison's exception only suppressed; no `abort()` or `close()` on a caller
connection. Matches the ruling.

### Item 2: D-PR43-6

Green: `probe_own_connections_set_never_and_restore_the_pools_mode`. `autosaveNever` turns an
`ALWAYS` Hikari connection to `NEVER` and `restoreAutosave` puts `ALWAYS` back. After an
own-connection `whileHeld` on an `autosave=always` pool of 2, both pooled connections answer
`ALWAYS`.

The held section refuses `ALWAYS` (the builder's probe, green). The joined path outside the held
section does not. That is D-PR43-7.

### Deviation rulings

**J5 mutation: acceptable, with one added probe (D-PR43-9).** The builder is right that
rollback-only does not stop a plain JDBC `commit()`. But for a plain JDBC caller, rollback-only
sets `CallerConnectionUnitOfWork.poisoned`, which refuses the module's next call. So the briefed
mutation is observable: make one more module call before the commit.
`probe_a_release_failure_alone_leaves_the_next_call_usable` does that and is green on the head.
Under the briefed mutation it must turn red. The new mutation (poison on a release failure alone)
stays too; both are R2 violations.

**`CipherProbePr1Test` alteration: acceptable.** The refusal comes after the same transaction wrote
(an allocation and a void). Point 3 of the approved design now aborts that transaction on purpose.
The probe's property was "the module's `SET LOCAL lock_timeout` never reaches the caller's later
statements". It now holds more strongly: no later statement can run (25P02). The check after the
rollback still turns red if the module ever used a session `SET` instead of `SET LOCAL`. Nothing a
regression could hide behind.

### New findings

**D-PR43-7 (MEDIUM). A joined allocation is not refused on `autosave=always`.** The DEI-249 check
runs only in `JdbcIssuanceStore.heldJoined`. `AbstractJdbcUnitOfWork.runJoined` (every joined
allocation, void and write outside the lock) never calls it. Under `autosave=always` the driver
undoes both the failed statement and the point-3 poison.

Example (measured): pool URL `autosave=always`. A plain JDBC caller allocates `in_p3a1`; the
issuance insert fails (test trigger) after the counter moved. The caller catches and commits.
`next_number` goes from 2 to 3 and no issuance row exists (T-03: a consumed number with no row).
The module logs "could not be confirmed aborted", then throws `DEI-102`, never `DEI-249`.

Probe: `probe_a_joined_allocation_on_autosave_always_is_refused_before_any_number` (RED: expected
2, was 3).

Fix (fix pass, a check not a mechanism): in `AbstractJdbcUnitOfWork.runJoined`, call
`IssuanceRowLocks.refuseAutosaveAlways(connection)` first, before the auto-commit check, for
read and write units. Keep the call in `heldJoined`. CHANGELOG and README: "joined mode" means
every joined call, not only the row lock. Mutation to show red: the new call removed.

**D-PR43-8 (LOW). A joined allocation on an aborted caller transaction answers DEI-102.** The
public javadoc of `DEI-248` says "SQLState 25P02 at the first statement of a joined call". In
`runJoined` the first statement is `SHOW transaction_read_only` (`refuseReadOnlyTransaction`). Its
25P02 falls to `JdbcSupport.unavailable`, an outage code.

Example (measured): caller runs `SELECT 1/0`, then `allocate`. Answer `DEI-102`, expected `DEI-248`.

Probe: `probe_a_joined_allocation_on_an_aborted_transaction_is_dei_248` (RED).

Fix (fix pass): in `runJoined`, map a 25P02 from `refuseReadOnlyTransaction` to
`HOST_TRANSACTION_ABORTED` (the same message as `IssuanceRowLocks.abortedOrUnavailable`; do not
take its 08P01 branch, which is the pooler refusal). Suspected, not run: a joined read unit
(`inReadUnit`) has no entry statement, so its first statement inside the work also answers
`DEI-102`; cover it with a 25P02 mapping in the `runJoined` catch when this call wrote nothing, and
a test `a_joined_read_on_an_aborted_transaction_is_dei_248`.

**D-PR43-9 (INFO). The briefed J5 mutation has no red test.** See the deviation ruling. Fix: add
`probe_a_release_failure_alone_leaves_the_next_call_usable` to the suite; show "rollback-only on a
release failure alone" red in the PR body as J5b.

### Prior findings on this branch

| id | status |
|---|---|
| D-PR43-1 (LOW) | closed: held-section probes green (builder 11, review 2) |
| D-PR43-4 (HIGH) | closed for the held section: half-written probe green with autosave NEVER and CONSERVATIVE; the same property outside the lock on autosave ALWAYS is D-PR43-7 |
| D-PR43-5 (LOW) | closed: R2 test green, review R2 probe green, R3 entry probe green |
| D-PR43-6 (MEDIUM) | closed for the held section and own connections; the joined path outside the lock is D-PR43-7 |

### Fix list

1. D-PR43-7: `refuseAutosaveAlways` at the top of `runJoined`; probe green; mutation red.
2. D-PR43-8: 25P02 at a joined entry is `DEI-248` on every joined call; probe green.
3. D-PR43-9: add the R2 next-call probe; J5b red in the PR body.
4. Add all six `CipherProbePr43Pass3Test` probes to the suite before the production change.

## Pass 3 addendum, D-PR9-F2 ruling (2026-10-21)

Head `7858963`. **Ruling: A.** The try-only row lock stays (it never waits). A run that finds the
other run holding the row may answer `FAILED_ISSUANCE` with `DEI-245`. That answer writes nothing
over the event: the event still ends `COMPLETED` with one number, one chained event, one archive
object. `CipherProbePr9Test.probe_a_reopened_row_run_twice_at_once_still_numbers_once` now accepts,
per run, `COMPLETED` or `FAILED_ISSUANCE` with `DEI-245`, and asserts the event ends `COMPLETED`.
B (polling for up to 30 s) is refused: a new mechanism that holds threads, for an answer the
caller already gets on the next delivery.

| check | result |
|---|---|
| `CipherProbePr9Test`, 10 runs | 10 of 10 green (5 tests each) |
| `CipherProbePr43Pass3Test` at `7858963` | 6 of 6 green |

D-PR43-7, D-PR43-8, D-PR43-9: closed at `432d4b0`. Probes green by name:
`probe_a_joined_allocation_on_autosave_always_is_refused_before_any_number`,
`probe_a_joined_allocation_on_an_aborted_transaction_is_dei_248`,
`probe_a_release_failure_alone_leaves_the_next_call_usable` (J5 as briefed, red in the PR body).

**Verdict: MERGE**, for the pass 3 scope. The compare-and-set in `7858963` (D-PR9-F2) was not
given an adversarial pass here.
