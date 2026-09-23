# Security review — `feat/void-terminal-disposition`

## Pass 1 — 2026-09-22 (HEAD `cfb27ec`)

**Verdict: MERGE WITH FIXES.** Two MEDIUM, two INFO. No HIGH. RC-01 and RC-02 are closed: both
release-candidate probes flip green on this branch and the mechanisms behind them hold under
independent attack. The two MEDIUM findings are not RC-01 reopening — they are new surfaces the fix
itself introduced, both in the failure handling that was added to satisfy the "no second failure in
place of the first" constraint.

Numbers, measured on this branch with a real PostgreSQL and `CIPHER_PROBE_MAVEN=1`:

| | |
|---|---|
| `mvn verify` | exit 0 |
| Tests | 552 run, 0 failures, 0 errors, 0 skipped |
| Probe classes / probe tests | 26 / 143, all green |
| Reviewer probes added this pass | 5 — 3 green, 2 red (D11-01, D11-02) |

### The acceptance gate

| Probe | Result |
|---|---|
| `probe_an_event_after_a_void_neither_loops_nor_escapes` | green |
| `probe_a_rewritten_burn_reason_is_not_reported_by_the_verifier` | green |

Both are now in the module's own test tree and run on every build.

### Ruling on the stated deviation — the void justification is not write-once

**Accepted.** The builder's argument is correct and the implementation is narrower than the
argument needs. A strict write-once clause on `void_rule_id` would refuse the documented remedy for
a burned number, which is precisely the void, so write-once would forbid the only sanctioned path
out of a burn.

What makes the weaker clause sound is not the clause, it is the transition set. The clause permits a
justification write only on an **edge** into `VOID_UNUSED` or `FAILED_VALIDATION`
(`NEW.state <> OLD.state AND NEW.state IN (...)`), so it sees the state change and not the state
value: a no-op self-update opens no window. The transition set — asserted twice, in the domain enum
and again in the trigger — makes `VOID_UNUSED` a sink with no outbound edge, and gives
`FAILED_VALIDATION` exactly one inbound edge, from `NUMBERED`. A row can therefore take a
justification-writing edge at most twice in its whole life: once at the burn, which writes a rule id
and never a reason, and once at the void. Both writes append a chained event in the same
transaction. I attacked both directions — a second `voidUnused`, and a raw `UPDATE` back to
`FAILED_VALIDATION` from `VOID_UNUSED` with a planted reason — and both are refused
(`probe_a_disposed_row_cannot_enter_a_disposition_a_second_time`, green).

What the clause leaves open is a justification planted in the same statement that burns a live
number. That is prevention the branch does not claim; detection is, and it holds: the cross-check
key now carries both void columns, so a justification with no chained event agrees with nothing and
the verifier reports `BROKEN`
(`probe_a_forged_disposition_that_plants_a_justification_is_reported_broken`, green). This is the
same line-one/line-two split the module already states for every other trigger.

Chain form is untouched: `IssuanceChain` is not modified on this branch, canonical fields 16 and 17
already carried the reason and the rule id, no `chain_version` bump, no migration. Confirmed by
diff, not by claim.

### Findings

#### D11-01 · MEDIUM · a read inside the failure handler escapes `process`

`IssuanceUnitOfWork.concludeIssuance` answers the constraint for the **write** and then calls
`voidedSince`, an unguarded `reader.findBySource`, from inside the same `catch`. The same call is
made in the `markIssued` catch before the original code is used. When the store is the thing that is
failing — the ordinary reason the state write failed at all — that read throws and the exception
leaves `process`, which is exactly the shape the constraint forbids and the shape RC-01 was.

*Repro.* `probe_a_failing_read_inside_the_archive_catch_does_not_escape_process`: an archive that
throws `DEI-241`, a writer that throws `DEI-102` on `markFailed(FAILED_ARCHIVE)`, a reader that
throws `DEI-102` from its second call. `EInvoiceException[DEI-102]` escapes `process`.

*Fix.* In `IssuanceUnitOfWork`, make `voidedSince` total: wrap its `findBySource` and return
`false` when the read fails, logging at WARN. Apply it at both call sites (`concludeIssuance`'s
catch and the `markIssued` catch). A handler that cannot read must fall back to the failure it
already has, never to a new one. Test name: `a_failing_read_in_the_failure_handler_never_escapes`.

#### D11-02 · MEDIUM · a transient store failure while burning is recorded as a permanent terminal

`concludeIssuance` returns `e.code()` for **every** failed state write, and each caller turns that
into `fail(event, FAILED_ISSUANCE, code, null)` — a null next attempt. The due query excludes that
shape by design, so a `DEI-102 STORE_UNAVAILABLE` blip while recording a validation refusal
concludes the event permanently: it is never due again, and the issuance row is left `NUMBERED`.
That is a silent terminal — a row nothing will run again, reached through a transient fault — which
is the defect class RC-01 belongs to, one layer down. Before this pull request the write threw and
the row stayed `MAPPED`, i.e. retryable. Recovery exists only while the sale is inside the
reconciliation window.

*Repro.* `probe_a_transient_store_failure_while_burning_is_not_made_permanent`: recorded code
`DEI-102`, event absent from `due()`.

*Fix.* In `IssuanceUnitOfWork`, `concludeIssuance` must distinguish the two reasons a state write
fails. A refusal that means "the row moved under us" — the illegal-transition code, or
`voidedSince` — concludes terminally with `NUMBER_VOIDED` as today. Anything else is a store
fault: return it to the caller as retryable, so the caller passes `backoffFor(event)` rather than
`null`. The class already names the transient codes in
`retryableAllocationFailure`; widen or reuse that rather than starting a second list. Test names:
`a_transient_failure_to_record_a_burn_stays_retryable` and
`a_void_landing_mid_run_is_still_terminal`.

#### D11-03 · INFO · a stray comment line corrupts the published javadoc of the V-04 limitation

`NumberVoider.java` line 22 is a bare ` * *`, which renders a literal asterisk immediately above the
"A void is irreversible" paragraph — the paragraph whose whole purpose is to be read by whoever
wires the admin action. *Fix.* Delete the line. Checked: `IssuanceVoidService` does not have it.

#### D11-04 · INFO · one half of the reader-8 assertion is missing

The design ruling required readers 8 and 9 to be asserted rather than assumed. Reader 9 is
(`acknowledging_the_finding_restarts_nothing`); reader 8 is not — nothing on the branch asserts that
the findings actuator endpoint exposes no write operation. I verified by reading that
`IssuanceFindingsEndpoint` carries only `@ReadOperation`, so this is a missing assertion and not an
exposure, but an annotation added later is exactly what a missing assertion fails to catch.
*Fix.* In the starter module, add
`the_findings_endpoint_exposes_no_write_operation`: reflect over `IssuanceFindingsEndpoint`'s
declared methods and assert none is annotated `@WriteOperation` or `@DeleteOperation`.

### Readers that can restart work — re-verified on the built code

All eleven from the design ruling hold. Ten are asserted by tests on the branch: the `process`
switch, a second subscribed event type, the invoice's own event run again, the due query, the sweep
classification, a `recon-` row queued before the void, the privileged reprocess, `acknowledge`, the
claim-race loser, and a void landing while a run is archiving; plus a table-driven walk over every
`IssuanceState`. Reader 8 is D11-04. The stuck check at `alert-after` remains out of scope with the
retry ceiling.

### Sweep report codes

`RECON_VOIDED_NO_DOCUMENT` and `RECON_BURNED_NO_DOCUMENT` classify correctly, enqueue nothing, and
are counted in `total()`. They do not crowd the findings page: the finding store upserts on
`(seller, mode, code, subject)` and an acknowledged row is not reopened by the `last_seen` bump
(`probe_a_voided_sale_does_not_grow_the_findings_page_every_sweep`, green). One adjacent case is
unchanged and remains correct: a `NUMBERED` row whose Stripe invoice was voided upstream is still
enqueued once and dropped once, and the stable `recon-<invoiceId>` event id keeps it to one row.

### Public text

The void's irreversibility is stated in the three required places: the README row "A number was
voided", the `SECURITY-NOTES.md` residual section under "Voiding is privileged", and the javadoc of
both `NumberVoider` and `IssuanceVoidService` (subject to D11-03). The credit-note limitation is
stated in all three. Roles-only: no personal or agent name appears in the changed public text.

### Open questions and the 0.1.0 tag

- **Retry ceiling and the stranded `PARKED` query.** Correctly deferred to its own pull request. No
  void path depends on it. But D11-02 adds a *new* non-retryable shape that the ceiling pull request
  would not cover, because the ceiling bounds running states and this row is `FAILED_ISSUANCE` with
  a null next attempt. With D11-02 fixed, the residual is the one already documented: a long-lived
  `PARKED` row is not bounded, and is visible only through the reconciliation sweep.
- **A successor number inside one Stripe id.** Deferred, correctly, and now stated in the README and
  in `SECURITY-NOTES.md` as a limitation rather than a note. Releasable as written.

0.1.0 is tag-able on this axis once D11-01 to D11-04 are applied, subject to the whole-module
release-candidate pass that was already ruled for before the first tag.

---

## Pass 2 — 2026-09-23 (HEAD `4877ac3`)

**Verdict: MERGE.** All four pass-1 findings are closed, each confirmed by its probe flipping green
and by a mutation that turns it red again. No new finding. Nothing outstanding at any severity.

| | |
|---|---|
| `mvn verify` | exit 0 |
| Tests | 558 run, 0 failures, 0 errors, 0 skipped |
| Probe tests in `CipherProbe*` classes | 149, all green |
| Release-pipeline shell probes | 74 fixed, 0 still weak |
| Reviewer probes | 6, all green (5 from pass 1 plus one new-surface probe) |

### Closures

| Id | Closed by | Mutation re-run by the reviewer |
|---|---|---|
| D11-01 | `voidedSince` wrapped, `EInvoiceException` and `RuntimeException` both caught, WARN, falls back to "not voided"; both call sites go through it | guard removed → `probe_a_failing_read_inside_the_archive_catch_does_not_escape_process` RED (`DEI-102` escapes `process`) |
| D11-02 | `WriteRefusal(code, retryable)`: a void landing mid-run stays terminal on `NUMBER_VOIDED`, a store fault keeps `backoffFor(event)`; the transient list is `retryableAllocationFailure`, named once | `transientStoreFailure` forced false → `probe_a_transient_store_failure_while_burning_is_not_made_permanent` RED at "must schedule another attempt, not conclude with none" |
| D11-03 | stray javadoc line removed from `NumberVoider` | n/a |
| D11-04 | `the_findings_endpoint_exposes_no_write_operation` reflects over the endpoint's declared methods for `@WriteOperation` and `@DeleteOperation` | n/a |

### Ruling on the probe change — due-ness at t0

**Accepted, and the stricter form is the correct one.** My pass-1 probe asserted the event was in
`due()` immediately. That is not the property; it is an accident of how I wrote the assertion. This
module schedules every recoverable failure through `backoffFor`, which is exponential with a cap,
and t0 due-ness could only be obtained by making the backoff zero — which would turn a store outage
into a hot loop and delete the back-pressure D2-01 exists for. I will not require a backoff-policy
change to satisfy an assertion.

The replacement asserts the property directly and more tightly: a next attempt must exist at all —
a null there *is* the silent terminal D11-02 was about — and the row must return to the due set once
that attempt has come. The mutation confirms it: with the transient classification forced false the
probe fails on the first of those two assertions, not the second, so the null-next-attempt condition
is what the probe is actually pinned to.

### The surface the D11-01 fix opens, probed

Making `voidedSince` total means that when the re-read fails it answers "not voided" — so a void
that really did land mid-run is misclassified as a store fault and scheduled for another attempt.
That is the safe direction only if the retry converges. It does:
`probe_a_void_missed_by_a_failed_reread_converges_on_the_void` (new this pass, green) drives a real
void, blinds the re-read, asserts nothing escapes and a next attempt is scheduled, then runs the
retry with the store back and asserts the event concludes on `NUMBER_VOIDED`, is never due again,
and the issuance row is still `VOID_UNUSED`. The fallback loses no void; it costs one retry.

The precedence inside `concludeIssuance` is the right way round: `voidedSince` is consulted before
the transient classification, so a readable void is terminal immediately and only an unreadable one
pays the retry.

### Residual, unchanged and still deferred

A store that is down for good now retries the burn path with an exponentially capped backoff and no
attempt ceiling. That is not a regression — before this pull request the same fault threw out of
`process` and left the row `MAPPED`, which the due query re-picked just as indefinitely — and it is
the retry ceiling deferred to its own pull request (QUESTIONS 29). Recorded here so the ceiling's
design covers this path explicitly.
