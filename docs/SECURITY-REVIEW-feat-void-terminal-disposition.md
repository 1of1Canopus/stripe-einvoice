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
