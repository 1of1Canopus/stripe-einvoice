# Security review — release candidate 0.1.0

Whole-module adversarial pass, required by the release rules before the first tag. Separate from
the per-pull-request passes: the subject here is every mechanism interacting on the merged tree,
checked against the threat model written at spec time.

---

## 2026-09-22 — release-candidate pass on `main` at `253759f`

**Verdict: NOT RELEASABLE.** One HIGH, one MEDIUM, two LOW. Do not tag 0.1.0.

Reviewed as a detached worktree of `origin/main` at `253759f`, Docker up, `CIPHER_PROBE_MAVEN=1`.

### Numbers

| What | Result |
|---|---|
| `./mvnw verify`, merged tree, full test run | exit 0, 532 tests, 0 failures (core 410, starter 118, sample 4) |
| Existing `CipherProbe*` classes re-run unchanged | 26 classes, all green |
| `tools/cipher-probe-release-pipeline.sh` | 74 probes, 0 weak, 0 failed |
| `tools/check-private-references.sh --tree` / `--self-test` | tree clean; all self-test cases correct |
| Unpinned GitHub Actions | none (every `uses:` is a 40-hex digest) |
| New probes written this pass | 2, both RED on this tree |

### Threat model, line by line

The assets and boundaries are the spec's own (`SPEC.md`, "Cipher spec review (2026-09-15)"). Each
spec finding is listed with the mechanism that holds it on the merged tree and the test that proves
it. "verified" means a test was run in this pass or in the full `verify` above; "scope" means the
line was cut from 0.1.0 by the founder's decision 7 and is out of scope for this tag.

| Spec finding | Mechanism on the merged tree | Proof | Holds |
|---|---|---|---|
| D-01 test/live and account routing | `mode` in the series primary key; `IssuanceUnitOfWork.route` refuses on `livemode` and `stripeAccountId`; a second refusal on the re-fetched object | `CipherProbeIssuanceTest`, `CipherProbeNumberingTest` | yes |
| D-02 payload is not the invoice | body yields `(event id, type, api version, livemode, account, object id)` only; re-fetch with pagination to exhaustion; pin refusal, no `deserializeUnsafe` | `StripeApiInvoiceSourceTest`, `CipherProbePr5Test` | yes |
| D-03 numbering allocator | `UPDATE einvoice_series … RETURNING` under a row lock in the issuance transaction, no `SEQUENCE`; resume-by-source first; two unique constraints | `NumberAllocatorTest`, `ConcurrentAllocationTest`, `CipherProbePr1Test` | yes |
| D-04 unit of work across two stores | four recorded phases, content-addressed write-once archive, row predicts the object | `IssuanceCrashTest` (5 kill points), `CipherProbeIssuanceTest` | **partly — RC-01** |
| D-05 amounts and currency exponents | `Money`, explicit exponent table, refusal on an unlisted currency | `MoneyTest`, `ModelInvariantTest` | yes |
| D-06 screened text everywhere | one `ScreenedText` path, coverage test over the model's string fields | `ScreenedTextTest`, `ScreenedFieldCoverageTest`, `HostileStringTest` | yes |
| D-07 XXE and XSLT extension surface | hardened factories, throwing resolvers, secure processing, output bound and timeout, checksum before compile | `CipherProbeDocumentsTest`, `OfficialValidatorTest` | yes |
| D-08 event ordering | `InboundState` + `IssuanceState`, arrival order decides nothing, redelivery re-reads and hash-checks | `InboundStateTest`, `IssuanceStateTest`, `CipherProbeIssuanceTest` | **partly — RC-01** |
| D-09 200/500 contract, reconciliation, health | record-first, 200, worker, bounded retry, `ReconciliationSweep` both directions | `ReconciliationSweepTest`, `CipherProbeActuatorTest` | **partly — RC-01** |
| D-10 no tenant context at the endpoint | seller from configuration and `event.account` only; metadata selects nothing | `CipherProbeStarterTest`, `StripeWebhookEndpointTest` | yes |
| D-11 secrets, keyring, telemetry | `webhook-secrets.<id>` keyring, format check, named sanitiser on `/env` and `/configprops` | `CipherProbeStarterPr1Test`, `CipherProbeActuatorTest` | yes |
| D-12 totals recomputed and compared | `Totals.reconcile`, per-bucket, refusal naming the field, values only on a debug line | `TotalsTest`, `CipherProbePr5Test` | yes |
| D-13 credit notes and sign conventions | `credit_note.created` deliberately not subscribed; upstream void before issue drops, after issue is a finding | `CreditNoteValidationTest`, `CipherProbeIssuanceTest` | yes |
| D-14 tax treatment and rule pack | rates from the re-fetched Stripe objects, checked not overridden; pack version on the row and in the hashed material | `StripeInvoiceMapperTest`, `TaxTreatmentRules` tests | yes |
| D-15 dates, zones, byte determinism | BT-2 in the seller's tax zone; no `systemDefault`/`Locale.getDefault`; two-zone two-locale byte comparison | `CipherProbePr7bTest`, `CipherProbePr7bDeterminismTest`, `ArchitectureTest` | yes |
| D-16 foreign currency BT-111 | out of 0.1.0 scope: foreign-currency invoices refused rather than issued without a rate | `StripeInvoiceMapperTest` | scope |
| D-17 personal data, retention, logging | ids/codes/hashes only in logs, findings and metrics; body nulled at a state that cannot run again; purge at the retention | `InboundEventStoreTest`, `SourceAssertionsTest` | yes |
| D-18 delivery, SSRF, double filing | Pro; nothing in this edition submits anywhere | — | scope |
| D-19 raw bytes, size, charset, tolerance | `byte[]`, counted as read, content type refused, bounded tolerance, HMAC computed here not by the SDK helper | `StripeWebhookEndpointTest`, `WebhookSignatureTest` | yes |
| D-20 the chain, reused verbatim | keyed HMAC over the disposition log, key id in the hashed material from row 1, anchor with a monotonic trigger, full status set | `IssuanceChainVerifierTest`, `IssuanceChainTest`, `LedgerTriggerTest` | **partly — RC-02** |
| D-21 golden files and local paths | reference guard over tree and jars, `--text`, fixed producer strings, synthetic parties | `check-private-references.sh`, `GoldenDocumentTest` | yes |
| D-22 stable codes, no values, no frames | `ErrorCodes`, messages from constants and field names, host-port exceptions caught and given a generic code | `CipherProbePass2Test`, `SourceAssertionsTest` | yes |
| D-23 the free core's claim | chain and triggers are in the free core; the README says "recorded, chained disposition", never "gap-free" | README, `SECURITY-NOTES.md` | yes |
| D-24 supply chain, vendored artefacts | vendored with `CHECKSUMS.txt`, recomputed before compile at run time; licence gate with allowlist and denylist; coordinate-scoped Saxon carve-out | `VendoredArtefactAttributionTest`, `check-third-party-licences.sh --self-test`, 74 pipeline probes | yes |
| D-25 domain purity | ArchUnit; domain is JDK-only | `ArchitectureTest` | yes |

**Threats with no mechanism on this tree: none.** Every line of the threat model is answered by a
named mechanism. Three lines are answered only partly, and that is RC-01 and RC-02 below.

### End-to-end scenarios run

1. **Hostile webhook through intake → preflight → numbering → render → archive.** No path found
   that consumes a number without a document or produces a document without a number. Every
   data-dependent refusal is taken by the preflight before the allocator, including the profile
   rules that used to live only in the writer.
2. **Concurrent issuance for one seller.** `ConcurrentAllocationTest` and `LockOrderingTest` green
   unchanged: one number per invoice, no duplicate, no deadlock between the series row lock and the
   chain advisory lock.
3. **Crash at every transaction boundary.** The five kill points in `IssuanceCrashTest` and the
   two in `CipherProbeReprocessRecordTest` (between the re-open and the record; a throwing pipeline
   that still concludes) are green. The boundary that is **not** covered is a disposition that has
   since been voided — RC-01.
4. **Verifier against a tampered chain, a tampered burned row, a tampered reprocess record.**
   Tampered chain rows and out-of-band disposed rows are reported `BROKEN`. A tampered *burn
   justification* on the row is not — RC-02.
5. **The sample application as shipped.** `SampleEndToEndTest` green; the smoke-check probe
   (`probe_sample_smoke_rejects_a_404`) green. Verified through those tests, not by a manual run of
   the README quick start on a runner — recorded here so the difference is not implied away.
6. **Supply chain.** Every action pinned by digest; validator artefacts vendored with checksums
   recomputed before compile; the Saxon MPL carve-out is coordinate-scoped in both halves of the
   gate and four self-test cases hold each line; 74 pipeline probes green.
7. **Public claims against the code.** Claims-register rulings R9–R13 and D1 are applied: no
   "gap-free", no conformance claim, "library not issuer" stated, roles not names anywhere in the
   public text. One claim is nevertheless false on this tree — RC-03.

---

### RC-01 · HIGH · the prescribed remedy for a burned number puts the pipeline into a permanent loop

**What.** A validation or render refusal burns a legal number: the issuance row lands on
`FAILED_VALIDATION`. The module's own documented remedy is an operator void, which moves the row to
`VOID_UNUSED`. From that moment, any further event for the same Stripe invoice destroys the run.

`IssuanceUnitOfWork.process` has explicit guards for an existing issuance in `ISSUED` and in
`FAILED_VALIDATION`, and none for `VOID_UNUSED`. The run therefore falls through, the preflight
passes (the refusal was never a mapping refusal), the row is committed to `MAPPED`, and
`JdbcIssuanceStore.allocate` resumes onto the voided row — "a row in any state returns its existing
number". Every state write from there is an illegal transition out of a terminal:
`VOID_UNUSED.allowedNext()` is empty, so `markFailed` and `markArchiving` both throw. The
`markFailed` call that handles a validation refusal is not wrapped, and the one inside the archive
`catch` block throws *from within the catch*, so in both shapes an `EInvoiceException` escapes
`process`.

**What it leaves.** The inbound row stays `MAPPED` with `last_code` empty and `next_attempt_at`
null. `JdbcInboundEventStore.DUE` re-picks `state IN ('RECEIVED','FETCHED','MAPPED','PARKED') AND
(next_attempt_at IS NULL OR …)`, so the row is due **on every sweep, for ever**, each time paying a
Stripe API re-fetch, a full render and a full validation. `IssuanceWorker` catches and logs, so
there is no crash, no compliance finding, no code on the row and no health signal. The
reconciliation sweep makes it worse rather than better: it sees a finalised invoice with no `ISSUED`
issuance, raises `RECON_MISSING_ISSUANCE` and **re-enqueues it** into the same loop.

**How it is reached in normal operation.** `invoice.paid` for a net-30 invoice arrives days after
`invoice.finalized`; the void is taken in between. No attacker is needed. A hostile party who can
cause a validation refusal (a buyer field that the target profile rejects) can cause it on demand
once an operator follows the documented procedure.

**Repro.** `CipherProbeRcScenariosTest#probe_an_event_after_a_void_neither_loops_nor_escapes`,
`stripe-einvoice-core`. Observed on `253759f`:

```
escaped = EInvoiceException[DEI-113] an issuance in state VOID_UNUSED cannot move to FAILED_VALIDATION
state   = MAPPED        due = true        lastCode = ""
```

**Severity.** HIGH. It is a silent, permanent, unbounded loop reached by the module's own
prescribed operator action; it falsifies the README's "a crash leaves something a retry can finish"
and "re-enqueues anything with no issued document, through the same idempotent path"; and the sale
ends with no document and nothing on the row saying why.

**Fix — DESIGN STOP.** Do not patch this in a fix list. What is missing is not a guard clause, it
is a declared answer to "what does this module do with a Stripe invoice whose only number has been
voided", and that answer has to hold on four paths at once: a later Stripe event for the same
object, a Stripe redelivery, the reconciliation sweep's re-enqueue, and a privileged reprocess.
Thor writes a one-page design, reviewed before any code, establishing:

- **Property.** After a number is voided, no path re-enters the issuance pipeline for that Stripe
  invoice; every such path terminates on a recorded state with a stable code and a compliance
  finding; and no path throws out of `process`.
- **The second question the design must answer explicitly, not by omission.** Whether that invoice
  may ever be documented again. Decision 2 says one Stripe invoice maps to one number for all time,
  which reads as "never" — if so, the sweep must stop re-enqueueing it and must stop raising
  `RECON_MISSING_ISSUANCE` on it once a void explains it, or the operator is handed a finding they
  can never clear. If it is "yes, under a second number", that is a change to the uniqueness
  constraint and to the chain's canonical form, and it is a bigger change than 0.1.0 should carry.
- **Paths it must cover**, each with its own test: a second subscribed event type on a voided
  invoice; `process` re-run on the voided invoice's own event row; the sweep's `enqueue`;
  `IssuanceReprocess` (today safe by `REFUSED_NUMBERED`, and that must stay proven rather than
  inherited); and a void taken concurrently with a run already past the preflight.
- **A hard constraint from this review.** No state write inside a `catch` block may be able to
  throw a second, different exception in place of the first. `markFailed` in the archive `catch` is
  the instance; whatever the design chooses, that shape does not survive it.
- Probe to flip green: `probe_an_event_after_a_void_neither_loops_nor_escapes`, plus one test per
  path above.

---

### RC-02 · MEDIUM · the burn justification is rewritable by the runtime role and the verifier says INTACT

**What.** QUESTIONS 27 was closed by chaining the `FAILED_VALIDATION` disposition so that "the
justification for a gap in the issued sequence lives in the tamper-evident record and not only on a
row that can legitimately change". On the merged tree the justification still lives on the row for
every reader, and the chain is never compared against it.

Two gaps compose:

1. `einvoice_issuance_guard` makes `document_sha256`, `archive_key` and `void_reason` write-once,
   and leaves `void_rule_id` freely rewritable. `void_reason` is write-once only when the old value
   is non-empty — and a `FAILED_VALIDATION` burn leaves it empty, so a reason that was never
   chained can be *planted* on a burned row.
2. `JdbcIssuanceStore.disposedIssuances` — the verifier's cross-check — selects
   `seller_id, mode, series, fiscal_year, stripe_invoice_id, legal_number, state` and nothing else.
   Neither `void_reason` nor `void_rule_id` is compared with what the chained event carries.

`SERIES_LINES`, which builds the auditor-facing series report, reads `void_reason` and
`void_rule_id` **from the mutable row**. So the explanation an auditor is shown for a hole in the
series can be changed, and the module's own verifier reports `INTACT`.

The grants this needs are the ones `docs/schema-grants.sql` gives the runtime role. No ownership,
no `DISABLE TRIGGER`, no privilege the documented deployment withholds.

**Repro.** `CipherProbeRcScenariosTest#probe_a_rewritten_burn_reason_is_not_reported_by_the_verifier`.
A single `UPDATE einvoice_issuance SET void_rule_id = 'BR-CL-01', void_reason = 'buyer asked us to
cancel'` on a `FAILED_VALIDATION` row is accepted, and `IssuanceChainVerifier.verify()` returns
`INTACT`.

**Severity.** MEDIUM. The true values survive in the chain, so this is a detection gap rather than
a loss of evidence — but detecting exactly this is what the cross-check exists for, and the
README's "a rewrite by a role that outranks the triggers is still detectable … a cross-check that a
disposed issuance row has a chained event agreeing with it" is read as covering it.

**Fix (Thor, the run that built the chained burn).**
- `einvoice_issuance_guard`: make `void_rule_id` write-once on the same clause shape as
  `archive_key`, and make `void_reason` refuse any change once the row's state is terminal, not
  only once the column is non-empty.
- `JdbcIssuanceStore.disposedIssuances` and `IssuanceChainVerifier`: add `void_reason` and
  `void_rule_id` to the `DisposedIssuance` record and to the comparison against the latest chained
  event for that number, so a disagreement is `BROKEN` exactly as an identity disagreement is.
- `docs/schema-grants.sql` line 17–19 and `SECURITY-NOTES.md` ("What holds the property"): say
  which columns an `UPDATE` may change, correctly — the current comment omits `void_rule_id`.
- Probe to flip green: `probe_a_rewritten_burn_reason_is_not_reported_by_the_verifier`, plus a
  mutation run showing it RED with either half of the fix removed.

---

### RC-03 · LOW · a README claim is false on this tree

`README.md`, "What the issuance unit of work does": *"A sale with no document is noticed — A
reconciliation sweep lists what Stripe finalised and re-enqueues anything with no issued document,
through the same idempotent path."* On a voided invoice that path is not idempotent; it throws and
re-enters on every sweep (RC-01). The adjacent row, *"A crash leaves something a retry can finish"*,
is false in the same case.

**Fix (Isis, after RC-01's design lands).** Reword both rows to what the fixed code does, and add
the voided-invoice outcome to `SECURITY-NOTES.md`'s "What still costs a number, and needs an
operator void" section, which today stops at the void and never says what happens to a later event
for the same invoice. No claim is to be written before the RC-01 design is reviewed.

---

### RC-04 · LOW · the residual-risk list is out of date as of this pass

`SECURITY-NOTES.md` is the most honest document in this repository and is worth keeping that way.
Two conditions that exist on this tree are absent from it: the post-void re-entry (RC-01) and the
rewritable burn justification (RC-02). Both are findings to fix rather than residuals to record, so
the list is not *wrong* — but it must be re-read after the two fixes land and before the tag, and
the sentence "a burned number is also chained … so the justification for a gap lives in the
tamper-evident record" must not ship until the cross-check actually reads it (RC-02).

**Fix (Isis, last, after RC-01 and RC-02).** Re-read the residual list against the fixed code;
adjust the D7-03 paragraph so it claims the comparison that exists.

---

### Rulings asked for

**QUESTIONS 27 — closed correctly, with a caveat.** Chaining the `FAILED_VALIDATION` disposition
rather than inventing a `NUMBER_ABANDONED` state was the right call: it is the same evidence
without an enum value, a successor edge, a trigger clause and a migration. The caveat is RC-02 —
the chain carries the justification and nothing compares it to what the auditor is shown. The
question stays closed; the comparison is a finding, not a re-opening.

**QUESTIONS 28 — accepted for 0.1.0, as worded.** The reprocess is recorded append-only against the
runtime role with the actor and the reason in their own columns, two rows per call, one conclusion
per request enforced by a partial unique index, and an unconcluded request raised as `DEI-276`. Not
chaining it is defensible: a reprocess touches no legal number by definition, and a second chain
with its own anchor, keying and verifier is a mechanism, not a line. The stated consequence — that
a repeated reprocess overwrites the first *acknowledgement's* reason, while both *records* survive
— is accurate; I checked both halves. Decide the operator log in the Pro edition's audit export,
not by widening the issuance chain. **One wording correction before the tag:** the sentence in
`SECURITY-NOTES.md` that this table "is append-only against the application role" is true, and the
paragraph should also say that the runtime role's `INSERT` lets a host application append a forged
`CONCLUDED` row and so suppress `DEI-276` for a request that never finished. That is a detection,
not a prevention, and this module says so everywhere else.

**Residual-risk list completeness and honesty.** Honest and unusually complete for a 0.1.0 —
the unkeyed chain, the owning role, raw host JDBC, JTA/XA, backups, a lying validator, a
non-atomic store, an unpartitioned intake table and a third-party renderer without a preflight are
all named with their consequence rather than their mitigation. Complete after RC-01 to RC-04 are
applied and the list is re-read; not before.

### What to do next

1. Thor: the RC-01 design page, reviewed before any code.
2. Thor: RC-02, in the run that built the chained burn.
3. Isis: RC-03 and RC-04, last, after both.
4. A second release-candidate pass on the fixed tree before the tag. The two probes in
   `CipherProbeRcScenariosTest` must flip green with a mutation run each.

Nobody tags 0.1.0 while a HIGH is open.
