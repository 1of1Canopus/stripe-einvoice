# Security review - `feat/reprocess-refused`

Adversarial review of the privileged reprocess of one refused event, and of the chained failure
disposition that closes the prior D7-03. Roles, not names: "the design" is the builder's design
page for this mechanism, held in the module's internal folder; "the build" is the work on this
branch. The design had no prior review, so the design and the code are reviewed together here.

---

## Pass 1 - 2026-09-21

**HEAD reviewed:** `50d1cb3`
**Build:** full `verify` on a clean worktree of `feat/reprocess-refused`, Docker up, Testcontainers
real, licence gate and reference guard included. Exit 0.
**Surefire total: 510 tests run, 0 failures, 0 errors, 0 skipped** (core 391, starter 115, sample 4).
**Probes added by this review:** `CipherProbePr9Test`, 5 probes, **4 red, 1 green**.

### Verdict

**NOT MERGEABLE** - 1 HIGH, 2 MEDIUM, 1 LOW, 1 INFO.

The mechanism's shape is right and most of it survives attack. Eligibility really is one state; the
re-open really is one conditional statement, and its loser really does run nothing; the numbered
guard really does refuse; there is no HTTP surface, no actuator operation and no scheduled caller,
and a mapping refusal is genuinely unreachable from the sweeper's `DUE` query. The number-burning
oracle does not exist: every run that ends back at `FAILED_MAPPING` allocated nothing, and every run
that allocated ends in a state this path refuses, so one event can never consume two numbers.

What does not hold is the one control the mechanism exists for. "Who asked and why is recorded
durably" is the sentence the design, the changelog, the security notes and the docs page all make,
and it is defeated by an operator reason the caller chooses - deterministically, with no race -
leaving the event re-opened, due, and finished by the unattended sweeper into a real legal number
with no actor and no reason anywhere in the database. That is D9-01 and it is why this is not
mergeable. D9-02 is a regression the D7-03 change introduced on the burn path. D9-03 is the
durability of the record itself, which the design defers to an open question; on a no-allowance
branch a deferral is not a closure, and the fix is a mechanism, so it is a design stop.

### Findings

| Id | Severity | One line |
|----|----------|----------|
| D9-01 | HIGH | An operator reason the acknowledgement refuses re-opens the event and then throws, and the sweeper issues a legal number with no record of who asked. |
| D9-02 | MEDIUM | A rule id from a host validator that is not an identifier throws inside the burn transaction, out of `process()`, stranding a consumed number on a row that is due for ever. |
| D9-03 | MEDIUM (design stop) | The whole durable record of a privileged reprocess is one mutable row the next call overwrites, and the `DEI-264` marker is erased by the successful run. |
| D9-04 | LOW | After D7-03 the verifier demands a chained event for `FAILED_VALIDATION` while `IssuanceState.disposed()` answers false for it: two definitions of "settled" in the layer whose point is one transition table. |
| D9-05 | INFO | `actor` accepts `:`, so the one stored line `actor + ": " + reason` cannot be split back into who and why without ambiguity. |

---

#### D9-01 - HIGH - a chosen reason turns the privileged path into an unrecorded one

`IssuanceReprocess.reprocess` commits the re-open first and records afterwards, in two further
transactions of its own:

```
inbound.reopenForReprocess(...)   // committed: the row is RECEIVED, attempts 0, next_attempt_at NULL
findings.record(...)              // own transaction
findings.acknowledge(..., request.justification(), ...)   // own transaction, screens again
unitOfWork.process(eventId)
```

`ReprocessRequest` screens `reason` at 500 characters. What is stored is not `reason`, it is
`justification()` - `actor + ": " + reason`, then `substring(0, 500)`. The truncation cuts inside
the reason, and `JdbcFindingStore.acknowledge` screens the composed line again with the same screen.
A reason that is entirely valid on its own (a paired surrogate near the bound) becomes an unpaired
surrogate after truncation, `ScreenedText.screen` refuses it, and the exception is thrown *after*
the row is already re-opened and committed.

**Repro** (`probe_a_crafted_reason_leaves_the_event_reopened_and_numbered_with_no_record`, red):
actor `ops-jane`, reason `"a".repeat(489) + U+1F600 + "b".repeat(9)` - 500 characters, accepted by
the request's own screen. After the call: the event sits in `RECEIVED`, the finding row exists with
no acknowledgement, and `acknowledged(eventId)` is zero. A re-opened row has `next_attempt_at NULL`
and `RECEIVED` is the first branch of `DUE`, so it is due immediately: running the due list, as the
sweeper does unattended, allocates a legal number and issues the document. Probe output on this
HEAD: *"a legal number was consumed with no record of who re-opened the event"*.

Nothing exotic is needed for the same outcome: any failure of `record` or `acknowledge`, or a crash
between the re-open and the acknowledgement, leaves exactly the same state. The design's own
sentence - "recorded before the run, so a crash in the middle still leaves who asked and why" - is
not true of a crash between the re-open and the record, which is the window this order creates.

**Exact fix** (the builder, in the run that built this mechanism - it is inside the mechanism, not a
correction for the fix pass):

1. `ReprocessRequest` - validate the value that will actually be stored, at the boundary, before
   anything is written. Compose the justification in the compact constructor and screen *that*
   string with `ScreenedText.screen` at `MAX_REASON_CHARS`; refuse the request when the composed
   line does not fit, rather than truncating it. A silent `substring` on an operator's stated reason
   is itself a record that says something the operator did not.
2. `IssuanceReprocess.reprocess` - the re-open and the record must commit together or not at all.
   The re-open, `findings.record` and `findings.acknowledge` go into one unit of work, with the
   re-open last inside it, so a refusal to record cannot leave a re-opened row; `process` stays
   outside it, as it is today.
3. Probe names to make green: `probe_a_crafted_reason_leaves_the_event_reopened_and_numbered_with_no_record`,
   plus a new one of the builder's own that makes `FindingStore.record` throw and asserts the row
   is still `FAILED_MAPPING` and not in `due()`.

---

#### D9-02 - MEDIUM - the D7-03 chained burn turns a host validator's rule id into an unbounded loop

New on this branch: `IssuanceEvent.failed` calls `Identifiers.validate("failure rule id", ruleId,
64)` - the identifier charset, `[A-Za-z0-9-_.:+]` - and it is called from inside `markFailed`'s
transaction. Until this branch `markFailed` ignored `ruleId` entirely, so no value of it could fail.

`DocumentValidator.Report.ruleId` is free text from a port the module itself documents as "always a
host's own code in 0.1.0", and the site that burns the number is the one call in `issue()` that is
not wrapped:

```java
writer.markFailed(..., IssuanceState.FAILED_VALIDATION, report.ruleId());
inbound.transition(event.eventId(), InboundState.FAILED_ISSUANCE, code, report.ruleId(), ...);
```

A host validator reporting `BR-DE-15 (fatal)` - a space and parentheses, the shape this module's own
`En16931DocumentValidator` builds for its findings list one line above where it takes the rule id -
throws `EInvoiceException` `DEI-101` out of `markFailed`, out of `issue()`, out of `process()`. The
number is allocated, the issuance row stays `NUMBERED`, no chained event exists, and the inbound row
stays `MAPPED` with `next_attempt_at NULL`, which the `DUE` query re-picks immediately and for ever:
the D2-01 pathology this module already fixed once, restored on the burn path.

**Repro** (`probe_a_hostile_rule_id_from_a_host_validator_strands_a_burned_number`, red):
`IssuanceTestHarness.createWith(TestValidators.failing("BR-DE-15 (fatal)"))`, one finalised invoice,
one `process`. Today: throws out of `process`. Expected: `FAILED_ISSUANCE` on the event,
`FAILED_VALIDATION` on the number, and not due.

**Exact fix** (the builder). `IssuanceEvent.failed` must not refuse a value it can record safely: keep the
bound and the screening, drop the *charset* refusal for this field by screening the rule id the way
free text is screened elsewhere (`ScreenedText.screen("failure rule id", ruleId, 64)`), and when
even that refuses, fall back to the module's own stable code for the disposition rather than
throwing - a chained event with a generic code is a record; an exception is not. Either way
`markFailed` must not be able to throw a new exception type at a call site that has no catch: add
the same wrapping `issue()` gives the renderer and the validator, or state in `IssuanceWriter` that
`markFailed` never throws on the rule id and prove it. Probe to make green:
`probe_a_hostile_rule_id_from_a_host_validator_strands_a_burned_number`.

---

#### D9-03 - MEDIUM - DESIGN STOP - what the record proves to an auditor

The design's section 5 makes the findings row the durable record and defers the chained operator log
to an open question. Two consequences, both reproduced:

- **The next call erases the previous one.** `JdbcFindingStore.record` upserts on
  `(seller, mode, code, subject)` and `acknowledge` overwrites `ack_reason`. Two privileged calls on
  one event - which the mechanism explicitly allows, since a run that refuses again leaves the event
  eligible - leave one row carrying only the last operator's line. Probe output: stored value is
  `"ops-bob: ..."`; `ops-alice` and their stated reason are gone.
- **The `DEI-264` marker does not survive the run it describes.** The successful `process` transitions
  the row to `COMPLETED` with an empty code, so the public claim "the re-opened row carries
  `DEI-264`, so an event running again says why rather than looking like a retry" is true only while
  the call is in flight. Afterwards nothing on the event row distinguishes a privileged re-run from
  an ordinary delivery.

Add that `einvoice_finding` is a mutable table by necessity, while the issuance chain is
trigger-protected: the sole record of the one action that can turn a recorded refusal into a legal
document is the least protected row in the schema.

**Repro:** `probe_the_record_of_a_privileged_reprocess_is_erased_and_overwritten`, red on both
assertions.

**Ruling: DESIGN STOP.** I am not prescribing the mechanism in a fix list. The property that must
hold: *every privileged reprocess call that re-opens a row leaves a record of the actor, the reason
and the time that no later call of the same kind can overwrite, and that survives the run it
describes.* The paths it must cover: the successful run, the run that refuses again and re-opens a
second time, the run that throws mid-pipeline, and the process that dies between the re-open and the
commit. The builder writes a one-page design (append-only operator record, or the reprocess joining the
existing chain, or a second chain - the builder's call, with the lock order against `appendEvent`
stated), reviewed before any code. QUESTIONS 28 is not a closure on a no-allowance branch: either the
design lands in this PR or the mechanism ships with its record loud in the public text as
best-effort, which is a public claim change and goes past the claims register first. My preference is the former.

---

#### D9-04 - LOW - two definitions of a settled disposition

`IssuanceEventReader.disposedIssuances()` now selects `ISSUED, VOID_UNUSED, FAILED_VALIDATION`, and
the verifier reports `BROKEN` for a `FAILED_VALIDATION` row with no agreeing chained event.
`IssuanceState.disposed()`, documented as "the number's disposition is settled and recorded in the
chain", still answers `false` for `FAILED_VALIDATION`. The predicate has no main-source caller today,
which is exactly why it will drift: the next state that becomes chained will be added in one place
and not the other, and the one that goes stale is the one with the SQL in it.

**Repro:** `probe_the_domain_and_the_verifier_disagree_on_a_burned_number`, red - the row is in
`disposedIssuances()` and `IssuanceState.FAILED_VALIDATION.disposed()` is false.

**Exact fix** (the fix pass). `IssuanceState.disposed()` returns true for `FAILED_VALIDATION` and its javadoc
says which states are chained and why `FAILED_ARCHIVE` is not; `JdbcIssuanceStore.disposedIssuances`
builds its `IN` list from the enum (`Arrays.stream(IssuanceState.values()).filter(IssuanceState::disposed)`)
instead of a literal, so the two cannot disagree again. `IssuanceStateTest` gains the
`FAILED_VALIDATION` assertion; probe to make green: `probe_the_domain_and_the_verifier_disagree_on_a_burned_number`.
Check `open()` stays false for it (it is, and the reconciliation stuck check depends on that).

---

#### D9-05 - INFO - the stored line cannot be split back into who and why

`Identifiers.validate` allows `:`, so an actor may be `ops:jane`, and the record is the single line
`actor + ": " + reason`. A reader of the findings table cannot recover the actor unambiguously, and
an actor who wants to can make the line read as though someone else asked. No probe: the ambiguity
is in the format, not in a behaviour that can be asserted without asserting the format itself.
**Exact fix:** carried by the D9-03 design - the record holds the actor and the reason as two values,
not one concatenation. If D9-03's design lands with separate fields, this closes with it.

### Attempted and not a finding

- **Reprocess as a number-burning oracle.** Not reproducible. One event cannot consume two numbers:
  every outcome that allocates leaves a state this path refuses (`COMPLETED`, `FAILED_ISSUANCE`),
  and every outcome that stays eligible (`FAILED_MAPPING`) allocated nothing, which the builder's own
  probe 1 asserts against the series counter. Repeated calls on a still-refusing event cost Stripe
  fetches, not numbers.
- **Number burned while refusing.** Not reproducible. Both refusal branches return before
  `reopenForReprocess` and write nothing at all.
- **The re-open race, and the sweeper inside the same event.** Held. The conditional `UPDATE`
  gives one winner; the loser reads the row again and runs nothing. A re-opened row *is* due, which
  the design states deliberately, so I ran the case the builder did not: re-open, then two
  concurrent `process` calls on the same event id, started on a barrier
  (`probe_a_reopened_row_run_twice_at_once_still_numbers_once`, **green**) - both reach `COMPLETED`,
  one number, one chained event, one archived object.
- **Reachability from a schedule.** Enforced, not promised. `FAILED_MAPPING` appears in neither
  branch of the `DUE` SQL; no main source outside the auto-configuration mentions `IssuanceReprocess`;
  the starter has no `@WriteOperation` or `@DeleteOperation` anywhere, and the only operator endpoint
  is the read-only findings endpoint. The bean is callable by any bean in the host context, which is
  the void's posture and is stated in the public text.
- **A forged burn satisfying the widened cross-check.** Not reproducible. The cross-check key is the
  full row identity (seller, mode, series, fiscal year, source object id, legal number, state), so an
  out-of-band `FAILED_VALIDATION` row cannot borrow another row's chained event; the builder's own
  `probe_a_burned_row_written_out_of_band_is_reported_broken` covers it and stays green. The rule id
  is inside the canonical form (`voidRuleId` field) and therefore inside the hash - `IssuanceEvent.failed`
  copies `base.hash()` into an intermediate value, but `appendEvent` links through `chain.link`, which
  recomputes it after the rule id is set.
- **`FAILED_ARCHIVE` deliberately unchained.** The design's word "retryable" is imprecise - only
  `ARCHIVE_UNAVAILABLE` gets a backoff, so a permission denied or a missing bucket parks the event in
  `FAILED_ISSUANCE` with no next attempt and the number sits in `FAILED_ARCHIVE` indefinitely - but the
  gap is not silent: `ReconciliationSweep` raises `RECON_STUCK_ISSUANCE` for any `open()` row past its
  window, and the operator's void is chained. Not a finding; the notes would read truer as "retryable,
  and reported as stuck when it is not retried".
- **Second call after success under a changed seller profile.** No difference: eligibility is decided
  by the inbound state, which is `COMPLETED`, so the correction cannot re-run a passed event.
  Builder's probe 2 covers it; I re-ran it unchanged.

### Checked again and still clean

- All prior `CipherProbe*` tests re-run unchanged on this HEAD: green inside the 510.
- Public text on the branch carries roles only; no agent or person name in CHANGELOG, README,
  SECURITY-NOTES or `docs/`. The internal design page is not referenced by name.
- `domain` imports remain JDK-only; `IssuanceReprocess` is in `application` and takes ports only.
- Licence gate, reference guard and the release-pipeline probes pass in the full `verify`.
