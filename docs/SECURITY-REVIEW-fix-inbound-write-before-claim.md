# Security review - branch `fix/inbound-write-before-claim`

Adversarial review of pull request 45, head `10f628a`. Pass 1 of at most 2. Scope: the fix for
D-PR9-F1 (every inbound write before the number claim becomes a compare-and-set through the new
port method `InboundEventStore.transitionFrom`), the "carry on behind a run in flight" branch, the
port change, and the two open questions the fix pass raised (QUESTIONS 44 and 45).

## 2026-10-20 - pass 1

### Verdict

**MERGE WITH FIXES** - D-PR45-2 (LOW) and D-PR45-3 (INFO) are fixed in this pull request.
D-PR45-1 (MEDIUM, design stop) and D-PR45-B1 (MEDIUM) are pre-existing on `main`, are not caused
by this change, and each gets its own pull request. Both must land before the 0.2.0 tag.

D-PR9-F1 is **fixed** in its stated scope. Its probe is green. The three mutations in the pull
request body turn red exactly as claimed. The fix closes every inbound write before the claim.
The same symptom (`DEI-210` escaping `process` on one of two runs of one event) still exists
after the claim. That is D-PR45-1.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | - |
| MEDIUM | 2 | D-PR45-1 (design stop, own PR), D-PR45-B1 (own PR, re-graded from LOW) |
| LOW | 1 | D-PR45-2 |
| INFO | 1 | D-PR45-3 |

### Numbers

| Check | Result |
|---|---|
| `./mvnw -B clean verify`, Docker up, own worktree | BUILD SUCCESS; core 692, starter 140, sample 9; 0 failures, 0 skipped |
| `CipherProbePr9FlakeTest` | green |
| Mutation 1: compare removed in `JdbcInboundEventStore.move` | 5 red (Pr9Flake, store CAS case, 3 of `InboundWriteBeforeClaimTest`) |
| Mutation 2: re-read removed (`concludedElsewhere` and `step` answer from the stale view) | 5 red (Pr9Flake, Pr9 `run_twice_at_once`, 3 of `InboundWriteBeforeClaimTest`) |
| Mutation 3: never carry on behind a run in flight | 2 red (Pr9 `run_twice_at_once`, `does_not_drag_..._back_to_fetched`) |
| New probes (this pass) | 8, all red on `10f628a` |

The new probes are kept in the internal probe folder. The fix pass adds each one to the suite
before it touches production code.

### D-PR45-1 - MEDIUM - a second run of the same event concludes first, and the first run's post-claim failure write escapes as `DEI-210` (design stop)

Two runs of one event can both pass the number claim. There are two ways in:

- The QUESTIONS 44 path. A run first reads a row that a live run has already written `MAPPED`. Its
  `FETCHED` and `MAPPED` writes land, because `MAPPED -> FETCHED` is the restart edge.
- The new "carry on behind" branch. A run's `MAPPED` step finds `MAPPED` and carries on.

In both cases the second run reaches P1 (the number claim). The allocator hands it the winner's
`NUMBERED` row, so it resumes that number. It then renders, archives and writes `COMPLETED`.
Meanwhile the first run is still rendering. If the first run then fails, it writes
`FAILED_ISSUANCE` with the plain `transition`, with no compare. `COMPLETED -> FAILED_ISSUANCE` is
not an edge, so `DEI-210` escapes `process`. That is the D-PR9-F1 symptom, after the claim.

Example: worker A writes `MAPPED`, claims `INV-2026-000001` and starts rendering. The sweeper
re-picks the row (a `MAPPED` row with no `next_attempt_at` is due at once). The sweeper's run
resumes the same number, archives the document and writes `COMPLETED`. A's renderer then throws.
A's `FAILED_ISSUANCE` write fails with "an inbound event in state COMPLETED cannot move to
FAILED_ISSUANCE". The stored state is right (the issuance is `ISSUED`, the row is `COMPLETED`).
But one number was rendered twice at once, and the slower run ends in an error.

Repro: `CipherProbePr45Test`.

- `probe_a_winner_whose_render_fails_after_a_same_event_resume_escapes_dei_210` (QUESTIONS 44
  path): red with `DEI-210 COMPLETED cannot move to FAILED_ISSUANCE`.
- `probe_a_carry_on_run_completes_first_and_the_winner_failure_escapes_dei_210` (carry-on path):
  red with the same message.
- `probe_a_winner_that_succeeds_after_a_same_event_resume_concludes_without_an_exception` is green.
  It is the control: when A succeeds, both runs answer `COMPLETED`, with one number, one chain
  entry and one archive object.

Pre-existing: `main` reaches P1 the same way, through unconditional writes (read from the code;
the probes were not run on `main`).

**DESIGN STOP.** This needs a new mechanism, so there is no fix list here. The property that must
hold is this: **at most one run of one event is past the inbound row's first write at any time.**
A second run either waits or returns the stored outcome. It never resumes a number another live run
holds. The paths the design must cover:

1. the worker (webhook intake);
2. the sweeper's due query, for `RECEIVED`, `FETCHED`, `MAPPED` and `PARKED` with no backoff;
3. a privileged re-open followed by `process` (`IssuanceReprocess`);
4. a second worker in another JVM on the same database;
5. a run that crashed while it held the mark (the mark must not strand the row).

This is the QUESTIONS 44 liveness mark. The builder's suggestion to reuse the session row lock of
D-RTR-7 / D-PR42-2 on the inbound row is the right starting point. The builder writes a one-page
design. The security reviewer reviews it before any code.

**Ruling on QUESTIONS 44: rejected as "accept for 0.2.0".** Its harm statement says the live run's
later writes are legal from `FETCHED` and the claim numbers once. Both are true. But it misses
that the second run goes on to resume the live run's number, and that the live run's failure write
then escapes. QUESTIONS 44 and D-PR45-1 are one design, and it lands before the 0.2.0 tag.

### D-PR45-B1 - MEDIUM (re-graded from LOW) - a re-run whose first write is not an edge from the stored state escapes `DEI-210` and loops at every sweep

There is no race here. `process` can start from any state the due query selects, or from a
re-opened state. Some of its first conclusions are not edges from those states:

- `MAPPED` (a crashed run) to `FAILED_FETCH`: Stripe cannot be read.
- `FAILED_ISSUANCE` (a retry is due) to `FAILED_FETCH`.
- `PARKED` to `REFUSED_VERSION_SKEW`: the host moved its pinned API version while the event was
  parked. This one is not in QUESTIONS 45.

`transitionFrom` matches the expected state. It then refuses the edge and throws `DEI-210`.
Nothing is recorded: no attempt is counted and no backoff is set. A `MAPPED` row with a null
`next_attempt_at` is due at once. A `PARKED` row's `next_attempt_at` stays in the past. Neither
running state has a retry ceiling in the due query. So every sweep tick calls Stripe again and
logs `DEI-210` again, with no backoff and no end. A permanent failure never stops. Example: the host
revoked the restricted key, so every fetch fails.

Example: the archive was down, so the row is `FAILED_ISSUANCE` with a retry due. At the retry,
Stripe is down. The sweeper logs `einvoice: issuance of event evt_... stopped with DEI-210`. On a
`MAPPED` row it does this at every tick until Stripe is back.

Repro: `CipherProbePr45B1Test`.

- `probe_a_crashed_mapped_row_rerun_while_stripe_is_down_loops_on_dei_210`: red, `MAPPED cannot
  move to FAILED_FETCH`.
- `probe_a_failed_issuance_retry_while_stripe_is_down_escapes_dei_210`: red, `FAILED_ISSUANCE cannot
  move to FAILED_FETCH`.
- `probe_a_parked_row_rerun_after_a_pin_change_escapes_dei_210`: red, `PARKED cannot move to
  REFUSED_VERSION_SKEW`.

Why MEDIUM and not LOW: this is the D2-01 class. A row never reaches a state the retry ceiling can
govern. It costs one Stripe call per row per tick while Stripe is failing. The only signal is a
WARN line, not a finding.

**Ruling: it blocks the 0.2.0 tag.** It is fixed in its own pull request, because it changes the
inbound state machine. Some edges move a row that may hold a number (for example, a `MAPPED` row
left by a crash after P1). So the builder first writes a short edge table: for every state the
due query or a re-open can start `process` from, and every conclusion `process` can write before
`FETCHED`, either the edge or the reason the pair cannot happen. The security reviewer reviews the
table. Then the fix. Required test: a table-driven test over that product, plus the three probes
above.

### D-PR45-2 - LOW - at REPEATABLE READ or SERIALIZABLE a lost compare-and-set escapes as `DEI-102` instead of answering empty

The allocator already supports hosts whose pool runs REPEATABLE READ or SERIALIZABLE, and it maps
`40001` (D-PR40-1). `JdbcInboundEventStore.move` does not. Run B's `SELECT ... FOR UPDATE` waits on
run A's row lock. A moves the row and commits. At READ COMMITTED, B re-reads the row, sees the move
and answers empty. At REPEATABLE READ and SERIALIZABLE, PostgreSQL raises `40001` instead. It is
mapped to `DEI-102` (store unavailable) and escapes `process`. That is the lost race this pull
request promises to answer quietly.

Example: a host sets `spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ`.
The worker and the sweeper race on one event. The loser logs `stopped with DEI-102` instead of
returning the stored outcome.

Repro: `CipherProbePr45IsolationTest.probe_a_lost_compare_and_set_at_repeatable_read_escapes_as_store_unavailable`,
for both levels. It is red with `DEI-102 ... (SQLState 40001)`.

Fix (a correction inside the mechanism this pull request built, so it goes to the builder):
`JdbcInboundEventStore.move`. When the store owns the connection and the statement fails with
SQLState `40001`, run the whole move once more in a fresh transaction. The fresh snapshot sees the
committed move: the compare answers empty, or the move lands. A second `40001` stays `DEI-102`.
When the connection is the caller's own transaction, propagate unchanged. The caller's
transaction is already aborted and cannot be retried from inside. Javadoc on `transitionFrom`
states both cases. Test: the probe above, green at both levels, plus one case where the retry
lands (the row was moved to the expected state).

### D-PR45-3 - INFO - the port change is source-breaking for hosts, but neither the CHANGELOG nor the commit says so

`transitionFrom` is a new abstract method on a public port of a published module (0.1.0). A host
that supplies its own `InboundEventStore` stops compiling. The CHANGELOG entry says what to do, but
it is not marked as breaking. The earlier `DocumentDates` constructor change in the same
`[Unreleased]` section is marked `**BREAKING CHANGE: ...**`. The commit has no `BREAKING CHANGE:`
footer.

Example: a host that keeps its inbound events in its own table upgrades to 0.2.0. Its build fails
on a missing method. Nothing in a scan of the release notes for "BREAKING" warned it.

Fix: prefix the CHANGELOG entry with `**BREAKING CHANGE:` like the `DocumentDates` entry. Add a
`BREAKING CHANGE:` paragraph to the pull request body; it becomes the merge commit body. Do not
rewrite the existing commit.

### Attacked and not a finding

- **Two runs both carrying on behind each other, and a third run.** Each one carries on only when
  it sees `FETCHED` or `MAPPED`. Every run then reaches P1. The allocator gives one number for the
  invoice, and resumes it for every other run. A third run follows the same path. The numbering
  stays single (`numberedRows` 1, chain 1, archive 1 in the green control probe). What is left is
  concurrent work on one number. That is D-PR45-1.
- **The stored outcome for a row that finished `FAILED_*`.** The loser returns the stored state and
  the stored code. It does not re-process. Example: A wrote `FAILED_FETCH` while B's fetch worked.
  B returns `FAILED_FETCH` with A's code, writes nothing, and the sweeper retries after A's backoff.
  This is correct: the retryable state carries the backoff and the attempt count, and B adds no
  second attempt.
- **`bindSeller` in `route` is not a compare-and-set.** A stale run can rewrite `seller_id` and
  `updated_at` on a row another run has just concluded. The value is the same configuration
  value. `updated_at` is display-only (no query reads it). It is not a finding. It is noted so
  that the D-PR45-1 design covers it: the single-run mark makes this write unreachable.
- **The plain `transition` now checks the update count.** Under the row lock, the count cannot
  differ from 1 at READ COMMITTED. Under REPEATABLE READ, the locking read fails first
  (D-PR45-2). No new failure mode.
- **Sample app and starter.** The module ships the only implementation. Sample and starter build
  and pass unchanged (140 and 9). No test double in any module implements the port.
