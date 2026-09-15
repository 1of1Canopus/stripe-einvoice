# Security review - branch `feat/issuance-unit-of-work`

Adversarial review of pull request 2 (the issuance unit of work), commit `4035e92`. Pass 1 of at
most 2. Every finding was reproduced with a test that fails on this commit.

## 2026-09-16 - pass 1

### Verdict

**MERGE WITH FIXES** - three findings, no HIGH. The mechanism is right: the phase order, the
content-addressed write-once archive, the durable record before the routing decision, the totals
comparison in minor units before the allocator, and the crash table are all real and all tested. The
three findings are about what happens when something goes wrong *outside* the paths the design
enumerated - a phase that throws, a dependency that is absent, and a recovery path that is documented
but not implemented.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | - |
| MEDIUM | 3 | D2-01, D2-02, D2-03 |
| LOW | 0 | - |
| INFO | 0 | - |

No mechanism finding: all three are corrections. Nothing here needs a design stop.

### What was run

| Check | Result |
|---|---|
| `./mvnw -B verify` | BUILD SUCCESS, 326 tests (238 core, 85 starter, 3 sample), coverage gates met |
| `./mvnw -B clean verify -Prelease -Dgpg.skip=true` | BUILD SUCCESS, tests run |
| Reference guard, tree and release jars | clean; licence carve-out check clean |
| Sources jars after the release build | sources, the module's `.sql` resource, the starter's two `META-INF` registration files, `LICENSE`, `NOTICE`, Maven metadata; nothing else. `LICENSE` and `NOTICE` in both main jars |
| Four mutations of my own | all four failed; three are the findings below and one is D2-01's second face |

Release hygiene lines 1, 3 and 10 pass on the release artifacts.

### Verified, and closed without a finding

- **The edge.** The signature is an HMAC over the exact bytes against a keyring with a constant-time
  comparison and a bounded window, computed rather than delegated to a helper that takes a decoded
  string. The body cap counts as it reads and the declared length is an early reject only; the
  minimum accepted cap makes the degenerate buffer unreachable. Identity is parsed only after the
  signature verifies. Nothing at this boundary is logged: I grepped every log statement in both
  modules for a body, a header, a secret or a buyer field and there is none.
- **400 only for not-provably-Stripe.** The split holds, including the part that matters most - a
  version skew is recorded and answered 200, so a control meant to refuse one event cannot take the
  whole intake offline.
- **Amounts.** The comparison is integer arithmetic in the currency's own minor unit with checked
  addition, a per-bucket breakdown, an inclusive-tax path and a refusal of a non-positive total. The
  rate type bounds scale, integer digits and range, and refuses exponent and locale forms, so the
  division cannot leave the long range and no untyped arithmetic exception can be the observable
  result of a payload.
- **The archive.** Write-once is `O_EXCL` on the filesystem (a hard link, not a rename - the comment
  explaining why `ATOMIC_MOVE` is the wrong call is correct) and a conditional create on the object
  store, the capability is probed at startup with a real conditional write, the weaker mode WARNs and
  turns on a read-back that is documented as narrowing rather than closing the window, and the key is
  content-addressed, charset-checked and resolved a second time against the root.
- **Transaction participation.** The whole issuance runs through the port from PR 1, and the probes
  run under both a data-source and a JPA transaction manager, which is the point that mattered.
- **Redelivery.** The integrity check is against the archived bytes and the recorded hash, and it is
  deliberately not a re-render comparison; a buyer who corrects an address in March does not turn a
  January document into an alert.
- **The three checklist exceptions.** All three **accepted**, see below.

### Rulings on the three checklist exceptions

- **Line 15 (a refusal consumes no sequence value) - accepted as partial.** It follows from the
  numbering review's own ruling that byte determinism beats absolute gap-freeness: the number has to
  be inside the bytes that are validated. Every refusal that does not depend on the rendered bytes
  happens before the allocator, which is pinned by a probe, and a number consumed by a validation
  refusal keeps its failing rule id for the operator who voids it. The claim shipped matches.
- **Line 36 (append-only triggers) - accepted for both new tables.** A table holding raw webhook
  bodies must be purgeable; a retention promise and an append-only trigger cannot both be kept, and
  the personal-data section says which one wins and why. The findings table is operator working
  state, the ledger still holds the truth, and the security notes do not claim tamper evidence for
  either.
- **Line 48 (seller predicate) - accepted as partial.** A row that has not been routed has no seller,
  and scoping the sweeper on one would hide exactly the rows it exists to find. Every statement over
  the ledger and the findings table carries both predicates. Revisit at Connect, where the account is
  known at insert time.

### Open questions 10-16

None is blocking. 10 (minor-unit comparison instead of an exponent table) is better than what the
spec asked for on this path and the table is genuinely the mapper's; 11 and 12 (refuse rather than
guess) are the right default for a legal document; 13 is the line-48 ruling above; 14 (acknowledgement
as a service method, no endpoint) is consistent with the void operation and correct for a library;
15 is scoped to the next change; 16 (a tampered archive stays retryable) is right - a restored backup
must be able to finish.

---

#### D2-01 · MEDIUM · a phase that throws after the mapping records nothing, so the retry ceiling never arrives

The unit of work records a state and a code for every refusal it *expects* - a fetch failure, a
totals mismatch, a validation refusal, an archive conflict. It records nothing for an exception
thrown by the two phases that are not wrapped: the claim (P1) and the issue (P4). The exception
leaves the method, the worker logs it, and the row stays `MAPPED` with an empty code, an unchanged
attempt count and no next attempt.

Three consequences, in order of how long they take to notice:

1. The sweeper re-picks a `MAPPED` row with no next attempt *immediately*, so a permanent refusal -
   an unconfigured or exhausted series, an allocation timeout, a lock-order refusal - becomes an
   unbounded loop that re-fetches the invoice from Stripe on every sweep, for ever. The attempt
   counter never advances, so the retry ceiling that `retryableTerminal` exists to enforce is never
   reached.
2. The operator sees no code on the row. The only signal is the sweep's other direction (a finalised
   Stripe invoice with no issuance row), which is a different finding with a different remedy.
3. The loser of a real claim race - two events for one invoice, which is the normal case, since
   `invoice.finalized` and `invoice.paid` arrive together - is left in exactly this state. The suite's
   own concurrency probe catches the exception in the test body and asserts only that one document
   exists, so the row's state is unasserted.

**Repro.** `probe_every_event_that_stops_carries_a_recorded_state_and_code` - four events for one
invoice, processed concurrently: the losers are `MAPPED` with code `''`.
`probe_an_allocator_refusal_is_recorded_on_the_inbound_row` - a unit of work whose series is not
configured: the refusal is `DEI-110`, the row says `''`, attempts is 0.

**Required change.** Every phase that can throw is inside the same recording path as the phases that
already are. Concretely: wrap P1 and P4 (and any future phase) so that an `EInvoiceException` becomes
a `FAILED_ISSUANCE` transition carrying that code, with a backoff for the codes that can improve
(store unavailable, allocation timeout, the claim race) and none for those that cannot (series not
configured, series exhausted). The claim race deserves its own treatment: a loser is not a failure at
all, it is a duplicate, and recording it as `COMPLETED`-by-another-event or re-reading the winner's
row is more honest than a failure state. Tests: the two probes above, plus one asserting the attempt
counter advances on a repeated store failure so the ceiling is actually reachable.

---

#### D2-02 · MEDIUM · without a renderer the whole intake disappears silently, endpoint included

The decision not to wire the issuance path when no renderer or validator is present is right: an
application that can allocate a number and cannot produce a validated document would consume a legal
series and archive nothing. The problem is that the refusal is silent, and it takes more with it than
the pipeline.

The unit of work is conditional on the renderer and the validator; the worker is conditional on the
unit of work; **the webhook controller is conditional on the worker**; the sweeper and the health
indicator likewise. So an application with webhook secrets, an archive root and a webhook path
configured - an application that plainly expects to receive events - starts with no endpoint, no
sweeper and no health indicator, and says nothing at any log level. Stripe then posts to a path that
answers 404, retries for three days, and disables the endpoint. Every sale finalised in that window
has no invoice and nothing in the application ever noticed. That is precisely the failure the
durable-record-first design exists to prevent, reached by a configuration mistake rather than by a
crash.

It is also the *default* state of the free core until the writers land: every host without its own
renderer is in it.

**Repro.** `probe_a_configured_intake_without_a_renderer_is_refused_loudly_not_silently` - the
context starts, has no startup failure, and has no webhook controller bean.

**Required change.** Separate "this application does not use the issuance path" from "this
application uses it and is missing a part". When the intake is configured - a webhook secret is set,
or `einvoice.issuance.enabled` is explicitly true - a missing `DocumentRenderer` or
`DocumentValidator` fails startup with a message naming the missing bean type and the property that
turns the path off. When nothing about the intake is configured, log one WARN at every startup saying
the issuance path is not wired and why, so the numbering-only host still starts and still knows. A
test per branch: fails fast with secrets configured, starts with a WARN without them, and the
existing numbering-only assertion stays green.

---

#### D2-03 · MEDIUM · the documented recovery from an API version skew does not happen

Three public documents - the README's operating table, the docs page's "replaying a refused event",
and the security notes - all say that refused events keep their bodies and are replayed by the
sweeper once the pin is updated. The sweeper's query selects `RECEIVED`, `FETCHED`, `MAPPED`,
`PARKED` and the two retryable failure states. No `REFUSED_*` state is in it, and nothing else in the
module re-drives one.

A version skew applies to every event on the account at once, which is the reason the design
correctly chose to record and answer 200 rather than 400. But the recovery half is missing: those
events sit in `REFUSED_VERSION_SKEW` until the retention purge deletes their bodies, and the module
ships no service method, no endpoint and no sweep path that re-runs them. The operator's only route
is hand-written SQL against a table whose contract the docs do not describe. Meanwhile the documents
tell them the problem solves itself after a restart, so they will not look.

**Repro.** `probe_a_version_skewed_event_is_re_picked_by_the_sweeper_after_the_pin_moves` - a skewed
event is refused, and the sweeper's due list does not contain it.

**Required change.** Make the mechanism match the claim, in that order. Add to the sweeper's
selection the refused rows a configuration change has actually cured: a row in
`REFUSED_VERSION_SKEW` whose recorded API version now equals the configured pin. That is one more
predicate with the pin as a bind parameter, and it re-drives exactly the rows the documents promise
and none of the ones that are still refused. Leave `REFUSED_MODE` and `REFUSED_ACCOUNT` where they
are - those are not cured by an upgrade and re-running them silently would be worse. Then check the
three documents say what the code does. Test:
`a_version_skewed_event_is_re_picked_once_the_pin_matches_and_not_before`.

### Fix list

Three corrections, each with its probe. Pass 2 is the last pass on this branch.

1. **D2-01** - record every phase failure on the inbound row with its code and a backoff; treat the
   claim race as the duplicate it is. Probes
   `probe_every_event_that_stops_carries_a_recorded_state_and_code`,
   `probe_an_allocator_refusal_is_recorded_on_the_inbound_row`, plus an attempt-counter test.
2. **D2-02** - fail fast when the intake is configured and a renderer or validator is missing, WARN
   at every startup when it is not. Probe
   `probe_a_configured_intake_without_a_renderer_is_refused_loudly_not_silently`.
3. **D2-03** - re-drive cured version-skew rows from the sweeper, and align the three documents.
   Probe `probe_a_version_skewed_event_is_re_picked_by_the_sweeper_after_the_pin_moves`.
