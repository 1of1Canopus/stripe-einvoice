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
