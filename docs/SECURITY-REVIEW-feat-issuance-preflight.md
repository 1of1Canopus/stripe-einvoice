# Security review - `feat/issuance-preflight`

Adversarial review of the pre-allocation issuance preflight. Roles, not names: "the design review"
is the review section at the bottom of the module's internal design page, "the build" is the work
on this branch. (The path this sentence first named is an internal document, which the repository's
reference guard refuses in public text; only the wording changed.)

---

## Pass 1 - 2026-09-21

**HEAD reviewed:** `44887b5`
**Build:** full `verify` on a clean worktree, Docker up, Testcontainers real.
**Surefire total: 475 tests run, 0 failures, 0 errors, 0 skipped.**

### Verdict

**NOT MERGEABLE** - 1 HIGH, 2 MEDIUM, 1 LOW.

The mechanism is built the way the design review approved it, and the two structural claims hold
under attack. `EnInvoice`'s canonical constructor cannot be reached without running
`UnnumberedInvoice`'s, so "the preflight screens less than the render does" is genuinely not
expressible; and the single `MappingInput`, constructed once at one site and carried into
`DocumentInput`, means the two passes cannot be handed different values. What does not hold is the
*coverage* claim on which the public text now rests: the preflight runs the mapper and skips the
writer, and the writer has refusals of its own that the mapper does not have. Two of them are
decided by the buyer's own data, which is exactly the class the notes now say costs no number.

### The three prior findings

| Id | Ruling |
|----|--------|
| P-01 - no durable record of a `NOT_SUPPORTED` renderer | **Closed.** A compliance finding (`DEI-263`) is raised once per application start, idempotent on `(seller, mode, code, subject)`, with the startup warning and the outcome flag kept. Verified green by `a_renderer_without_preflight_raises_a_compliance_finding_once`, including the once-not-per-invoice assertion. |
| P-02 - the reflection enumeration must be default-deny, recursive and independent | **Partially closed, reopened as D5-02.** Independent: yes, the walk takes nothing from the mapper. Recursive: into nested records and into record elements of lists, with the five deep paths asserted by name. Default-deny: for the strings the walk *reaches*. It is default-allow for the strings it does not reach, and the shapes it does not reach are not exotic. See D5-02. |
| P-03 - the replay sentence is not true against the code | **Closed.** The sentence is gone from the design page, the security notes, the changelog and the port javadoc; a grep over `*.md` and `*.java` finds no surviving claim that a mapping refusal can be re-run. Finality is *proven*, not asserted: terminal by enum, absent from the sweeper's `DUE` SQL (which lists `RECEIVED, FETCHED, MAPPED, PARKED` and the two retryable terminals - `FAILED_MAPPING` appears in neither branch), asserted at three elapsed windows, and with the Stripe fetch count unchanged after the refusal. The privileged reprocess is correctly deferred. |

### The added condition - one `MappingInput`, both passes

**Held.** Attacked and not broken:

- `MappingInput` and `DocumentInput` are constructed in exactly two places in main sources, both in
  `IssuanceUnitOfWork` (lines 264 and 346), from the same local.
- Every component is deeply immutable: `SourceInvoice` copies all three lists with `List.copyOf`,
  every nested type is a record of strings, primitives, `Instant` and `LocalDate`.
- There is no second construction path to `EnInvoice`: the canonical constructor builds an
  `UnnumberedInvoice` and copies every component back from it, so a value that skipped the shared
  body cannot survive construction. No builder, no setter, no marshaller annotation (the ArchUnit
  rule forbidding them in `domain.en16931` is unchanged and unsuppressed).
- The one thing `render` adds that `preflight` does not run is the writer. That is the subject of
  D5-01.

### The second deviation - `MAPPED` means "the mapping ran"

**Held.** Every edge checked against `InboundState.allowedNext()`:

- `FETCHED -> FAILED_ISSUANCE` (the `canValidate` refusal, now taken before `MAPPED`) is in
  `FETCHED`'s successor set already; no edge was widened for this change.
- `FETCHED -> FAILED_MAPPING` (the preflight refusal) likewise.
- Everything `issue()` can return after `MAPPED` is `COMPLETED` or `FAILED_ISSUANCE`, both in
  `MAPPED`'s successor set.
- A crash between the `FETCHED` transition and the preflight leaves the row `FETCHED`, which is in
  the `DUE` query's still-running branch, so the widened window is swept. Correct.
- Reconciliation still classifies these rows correctly: a preflight refusal creates no issuance row
  at all, so direction 1 re-enqueues it through the same idempotent path if Stripe still reports the
  invoice finalised, and directions 2-4 have nothing to look at. No misclassification found.

### Findings

---

#### D5-01 - HIGH - a buyer-controlled field the writer refuses still costs a legal number

The preflight runs `StripeInvoiceMapper.map` and deliberately skips `UblDocumentWriter`, on the
stated invariant that "every string reaching the writer arrived through a screened business term, so
a writer refusal implies a mapper refusal". The invariant is false. `UblDocumentWriter.write` calls
`requireProfileRequirements` before it writes a byte, and that method throws `DEI-221` for five
conditions **none of which the mapper checks**:

- the buyer's electronic address (BT-49) is absent under a profile that requires it;
- the seller's electronic address (BT-34) is absent under the same;
- a buyer reference (BT-10) is absent under a profile that requires it;
- payment instructions or a seller contact are absent under a profile that requires them, and the
  buyer's street, city or post code is absent (BR-DE-8, BR-DE-9, BR-DE-10);
- a reverse-charge or intra-Community document does not carry both parties' VAT identifiers.

Three of those are decided by the buyer's own frozen data. The buyer's electronic address is derived
from `customer_tax_ids` and is absent whenever the buyer has no VAT identifier, or has one whose
issuing country this module has no Peppol scheme for; the address detail is `customer_address`; the
reverse-charge rule is `customer_tax_ids` again. The mapper's own javadoc says so in as many words:
the electronic address is "absent when neither - **which the writer turns into a refusal** for the
Peppol profile".

So: preflight `PASSED`, allocator runs, number consumed, writer refuses, event ends
`FAILED_ISSUANCE` with a backoff. That is precisely the cost this branch exists to remove, still
paid, for a field the buyer controls.

The public text now states the opposite. `SECURITY-NOTES.md` lists what still costs a number and
closes with "none is buyer-controlled input"; the design's section 5 makes the same claim; the port
javadoc offers "a name no XML parser can read, a currency this module has no exponent for" as the
kind of thing that now costs nothing. A buyer with no VAT identifier is a more ordinary case than
any of them.

**Repro** (`probes/CipherProbePr5Test.java`, both red on `44887b5`):

- `probe_a_writer_profile_refusal_is_visible_to_the_preflight` - renderer level, no database. The
  French fixture with `taxId` set to `null` and everything else valid under Peppol, and the German
  fixture with the buyer's street, city and post code set to `null` under XRechnung. Output:

  ```
  peppol, buyer with no tax id (BT-49): preflight PASSED, render refused DEI-221
  xrechnung, buyer with no street (BR-DE-8): preflight PASSED, render refused DEI-221
  ```

- `probe_a_buyer_without_a_tax_id_consumes_no_number_under_peppol` - full pipeline against real
  PostgreSQL. Output: `outcome=FAILED_ISSUANCE DEI-221 preflight=Optional[PASSED] numbered=1
  counter=-1->2`. One legal number consumed.

The existing `preflight_and_render_agree_on_every_fixture` does not catch it because its one
near-miss fixture, `fr-no-buyer-country`, sets the buyer's country to `""` **and** the tax id to
`null` in the same call, so the mapper's country refusal fires first and masks the disagreement. The
property test `probe_a_writer_refusal_implies_a_mapper_refusal` does not catch it either: it varies
only the buyer's name, on one fixture, under one profile, and the writer's profile rules are
presence rules rather than content rules.

**Exact fix.** The profile-mandatory rules must run in the shared body, which is the only place a
rule can be in both passes by construction. `StripeInvoiceMapper` already holds both things they
need - the `UblProfile` and the configured buyer electronic address - and
`UnnumberedInvoice` already holds every term they read except BT-1. Move the body of
`UblDocumentWriter.requireProfileRequirements` and `requireBuyerAddressDetail` to
`StripeInvoiceMapper.map`, operating on the `UnnumberedInvoice` it is about to return; keep the
writer's copy as a second line of defence rather than deleting it, since a host may call the writer
directly. This is inside the mechanism the design review named, so it is the builder's fix, not a
correction pass.

Then make the two probes that missed it into probes:

1. `preflight_and_render_agree_on_every_fixture` gains one fixture per profile rule, each varying
   **one** field, so no refusal masks another: Peppol buyer with no tax id, Peppol seller with no
   electronic address, XRechnung seller with no buyer reference configured, XRechnung buyer with no
   street, XRechnung invoice with no payment instruction, XRechnung seller with no contact, a
   reverse-charge invoice with a buyer that has no VAT identifier.
2. `probe_a_writer_refusal_implies_a_mapper_refusal` is widened from the buyer's name to every
   screened string term and to both profiles, or it is renamed to say it covers one term.
3. `probe_every_screened_buyer_field_refuses_before_the_number` is run under **both** profiles; a
   value that is harmless under XRechnung and fatal under Peppol is invisible to a single-profile
   run.

Until that lands, the "none is buyer-controlled input" sentence in `SECURITY-NOTES.md` and section 5
of the design page are claims the code does not support and must not ship.

---

#### D5-02 - MEDIUM - the screen detector is default-allow for every container it does not recognise

P-02 required three properties and said any one of them missing collapses the probe. Independence
and recursion are there. Default-deny is there only for the strings the walk reaches, and the walk
recognises exactly three shapes: a `String` component, a record component, and a `List` whose
element type is a record. Everything else is skipped in silence - not tested, not excluded, not
printed, not a build failure.

The shapes that get skipped are the shapes a payload record grows into:

- `Optional<String>` - the module's own style rule is `Optional` over `null` for nullable returns,
  so an optional component is a likely next edit;
- `List<String>` - several notes, several references;
- `Map<String, String>` - Stripe metadata is literally this type, and the mapper's own javadoc names
  reading metadata as "the obvious next feature";
- `String[]`;
- any `String` nested below one of those.

P-02's whole point was that "a new field arriving without a screen fails the build". For these five
shapes it does not; the build stays green and the exclusion list stays short because the field never
appeared in it.

**Repro** - `probe_the_screen_detector_reaches_every_string_in_the_payload_shape` calls the branch's
own `stringPaths` by reflection on a record carrying one component of each shape. Output:

```
D5-02: the walk reached [Shaped.plain]
```

Five of six strings invisible, including one in a nested record reached only through an `Optional`.

**Exact fix.** In `CipherProbePreflightTest.stringPaths`, make the walk total: handle
`Optional<String>` and `Optional<SomeRecord>` through the generic argument, `List<String>` and
`Set<String>` as `path[]`, `Map<String, ?>` as key and value, and arrays through
`getComponentType()`. Then close it: any component whose type is not `String`, not a record, not one
of the handled containers and not a primitive, boxed type, `Instant`, `LocalDate` or enum must fail
the test by default with the component's declared type in the message, so the next unhandled shape
is a build failure rather than a silent skip. The rewriter `replace` needs the matching cases.
Assert the new paths by name the way the five existing ones are asserted.

---

#### D5-03 - MEDIUM - a number consumed by a render refusal carries no disposition

`SECURITY-NOTES.md` now says of the five residual cases: "What still costs a number, **and needs an
operator void**". For two of the five that is accurate - the validation refusal writes
`FAILED_VALIDATION` with the rule id, the archive failures write `FAILED_ARCHIVE`. For the renderer
or writer fault it is not. Both catch blocks around `renderer.render` in `IssuanceUnitOfWork.issue`
go straight to `fail(event, FAILED_ISSUANCE, ...)` with no `writer.markFailed`, so the issuance row
is left in `NUMBERED`: a legal number allocated, no document, no recorded reason, and nothing on the
row an operator can grep or void against. The only thing that ever notices is the reconciliation
sweep's stuck check, six hours later by default, and it reports a count rather than a cause.

This is a pre-existing code path; the claim that it is operator-voidable is new on this branch, and
D5-01 is what makes it reachable from ordinary buyer data rather than only from a host's bug.

**Repro** - `probe_a_number_consumed_by_a_render_refusal_carries_a_disposition`. Output:
`numbered=1 state=NUMBERED`.

**Exact fix**, the smaller of the two options: both render catch blocks in
`IssuanceUnitOfWork.issue` call
`writer.markFailed(sellerId, mode, invoice.id(), IssuanceState.FAILED_VALIDATION, code)` before
`fail(...)`, putting the render code where the rule id goes, and `SECURITY-NOTES.md` says that a
renderer or writer fault lands in the same needs-a-human disposition as a schematron refusal. That
destination is already `NUMBERED`'s legal successor in the enum *and* in the
`schema-postgresql.sql` trigger guard, and is already not `open()`, so the number stops counting as
stuck and starts counting as awaiting a void - which is what it is. A distinct `FAILED_RENDER`
state reads better but is an enum, an `allowedNext` edge, an `open()` case, the trigger guard and a
migration; if the builder wants it, it is its own change with its own note, not a line in a fix
list. Either way the number's fate is on its own row. Probe:
`probe_a_render_refusal_records_a_disposition_on_the_numbered_row`, asserting the row is not
`NUMBERED` and carries the code, plus the existing chained-disposition assertion.

If the choice is to leave it, the sentence in `SECURITY-NOTES.md` has to be corrected to say that a
renderer or writer fault leaves the number open with no disposition until the stuck sweep reports
it - but that is the weaker option and I am not recommending it.

---

#### D5-04 - LOW - the determinism rule names five methods and misses the overloads

The new ArchUnit rule `the_preflight_path_reads_no_clock_no_zone_and_no_locale` is the control the
"same input, same verdict, always" claim rests on. Its method list is `Instant.now()`,
`LocalDate.now()`, `Clock.systemDefaultZone()`, `ZoneId.systemDefault()`, `TimeZone.getDefault()`,
`Locale.getDefault()` - all no-argument. ArchUnit's `callMethod(owner, name)` matches the no-argument
signature, so every environment-reading overload and every sibling class passes:
`LocalDate.now(ZoneId)`, `ZonedDateTime.now()`, `OffsetDateTime.now()`, `System.currentTimeMillis()`,
`new java.util.Date()`, `Locale.getDefault(Locale.Category)`. The module-wide rule
`nothing_reads_the_time_the_zone_or_the_locale_from_the_environment` has the same gaps, so the new
rule is a narrower restatement rather than a tightening.

**Repro** - `probe_the_determinism_rule_refuses_every_environment_reader` applies the rule, method
list copied verbatim, to a single fixture class in `adapter.en16931` that calls all five. Output:
`D5-04: rule refused the fixture = false`. (Note for whoever re-runs it: import the fixture class by
name, not the package - importing the package pulls in test classes that legitimately call
`TimeZone.getDefault()` and turns the probe green for the wrong reason.)

**Exact fix.** Extend both rules' method lists with the overloads and the sibling classes above, and
add `callConstructor(java.util.Date.class)`. One line each. Probe: the fixture class above, kept in
test sources in the adapter package, with a test asserting the module-wide rule refuses it - which
also means `ArchitectureTest`'s importer keeps `DO_NOT_INCLUDE_TESTS` and the assertion runs against
a separate import.

### Checked and clean

- The compatibility path. `NOT_SUPPORTED` allocates as before, warns once naming the renderer class,
  raises `DEI-263` once per start, and is on the outcome. A preflight that throws is `DEI-262`,
  terminal, cause logged server-side only, no number consumed - verified.
- Preflight borrows no connection and opens no transaction, under both transaction managers.
- Determinism under two zones and two locales, over all seven consistency fixtures.
- Hexagonal direction: `application` owns the port and both inputs, `domain` stays JDK-only, the
  adapter implements. No new suppression.
- `PreflightReport` refuses a `REFUSED` verdict with an empty code, copies its findings list, and
  carries ids and codes only. `Outcome.preflight()` is an `Optional`, never null.
- Public text carries roles only. No agent or person name in any file on the branch.

---

## Pass 2 (2026-09-21)

Second and last pass on `feat/issuance-preflight`, HEAD `ed85a3e`. Full `verify` on a clean
worktree with the containers up: **486 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS
(the 496 in this worktree's surefire reports includes the ten pass-2 probe tests below, which are
not committed to the branch). Coverage, probe scripts, reference guard and the CI checks are as
reported by the verifier and were not re-measured here.

**Verdict: MERGE WITH FIXES.** The HIGH and both MEDIUMs are closed, each confirmed by running a
probe rather than by reading the diff. Two LOW and one INFO are new, all three found by a probe
that is red on this HEAD. None of them is a bypass and none needs a third pass: a fix pass that
lands them can go to merge on the verifier's numbers.

### Closure of the pass-1 findings

| Id | Closure | Evidence |
|----|---------|----------|
| D5-01 HIGH | **Closed.** | `UblProfileRequirements.require` is one body called by the mapper (before it returns the unnumbered document) and by the writer (as a second line of defence), with the same six arguments read off the same document in both places, and one `profile` field shared by the mapper and the writer inside `En16931DocumentRenderer`. `CipherProbePr5Test` green: `outcome=FAILED_MAPPING DEI-221 preflight=REFUSED numbered=0 counter=-1->-1` - the buyer with no tax id now costs nothing. |
| D5-02 MEDIUM | **Closed.** | The walk reaches `[plain, optional, strings[], metadata, metadata{}, array, nested.deep]`, and the unknown-shape branch is an `AssertionError`, not a skip. |
| D5-03 MEDIUM | **Closed in the code, not in the probe.** | Both catch paths now call `renderRefused`, which writes the disposition before it fails the event. See D7-01 for the probe. |
| D5-04 LOW | **Closed for what the rules name.** | The preflight rule now refuses the fixture, and it refuses it *as the rule*, not as a copy. See D7-02 for what the rules still do not name. |

### The one more buyer-controlled field, looked for and not found

`probe_every_buyer_controlled_term_agrees_between_the_passes` - 2000 iterations over the five
upstream terms the branch's own property test does not vary (buyer tax id BT-48/BT-49, buyer
country BT-55, buyer email, buyer line 1, upstream currency), each filled with strings drawn from
an alphabet of XML metacharacters, C0 controls, `U+0085`, `U+00A0`, `U+200B`, `U+2028`, lone
surrogates, `U+FFFE`, `U+FFFF` and `U+FEFF`, under both profiles. Census:
`construction=349 refused=1136 passed=515 leaks=0`. 515 values reached the writer after a `PASSED`
preflight and every one of them produced bytes. Not vacuous, and no disagreement. `attribute()`
runs the same `requireValidText` as `text()`, so the `schemeID` route is screened as well.

### D5-02, the shapes pass 1 did not have fixtures for

`CipherProbePr7bWalkTest`, four assertions, all green on this HEAD:

- a record inside a `Map` value is reached: `InMapValue.byKey{}.deep`;
- `Optional<List<Leaf>>` **fails the build** - `argument()` refuses a type argument that is not a
  plain class rather than skipping it, which is the default-deny P-02 asked for;
- a sealed interface component fails the build the same way;
- an enum component yields no string path and smuggles none: an enum constant's fields are not
  upstream data, so stopping there is a refusal by design, not a gap.

### D5-03, the residual: no chained event at a failure disposition - ruling

**Acceptable at 0.1.0, as a documented limitation, on condition that it becomes a dated QUESTIONS
item before the tag (D7-03).** It is not a bypass: the number's fate is now on the issuance row and
on the inbound event row, and the series report reads both. What it is, is an *evidence* boundary,
and the module sells the chain as the evidence. A number that was allocated and then burned leaves
a gap in the chained sequence whose only explanation lives on a mutable row; the chain itself
cannot tell an auditor that number 3 was burned by a render fault rather than removed. That is a
compliance-facing design decision with a date attached (the numbering rules require a gap to be
explainable), not a defect of this branch, and it is the kind of decision that must be written
down with an owner rather than left in a notes paragraph. Hence INFO, not "closed".

### D5-04, the changed probe - ruling

**The change is legitimate and is a strengthening, not a softening.** My probe carried a *copy* of
the rule's method list, so tightening the real rule could never turn it green; a control's test
that duplicates the control tests nothing. The builder replaced the copy with the rule itself:
`DeterminismRules.moduleWide()` and `DeterminismRules.preflightPath()` are defined once, applied by
`ArchitectureTest` to the module and by the probe to `CipherNonDeterministicFixture` - same rule
object, same fixture, same message. Verified: the probe prints `rule refused the fixture = true`
and the printed rule text contains the overloads I asked for (`LocalDate.now(ZoneId)`,
`ZonedDateTime.now()`, `OffsetDateTime.now()`, `LocalTime.now()`, `Year.now()`,
`Clock.systemUTC()`, `System.currentTimeMillis()`, `System.nanoTime()`,
`Locale.getDefault(Category)`, `Date.<init>()`). Accepted. The only thing I hold against the new
shape is that the two rules drifted apart, which is D7-02.

### New findings

---

#### D7-01 - LOW - the probe that guards the D5-03 fix passes with the fix removed

`probe_a_number_consumed_by_a_render_refusal_carries_a_disposition` uses the buyer-with-no-tax-id
fixture. That fixture is now refused at the preflight by the D5-01 fix, so no issuance row is ever
created and the assertion `state != "NUMBERED"` is satisfied by the absence of a row. Its own
printed output says so: `D5-03: numbered=0 state=<none>`. The probe no longer exercises the code
path it was written for; the two findings' fixtures collided.

**Repro** - mutation, in this worktree, restored afterwards. Both `renderRefused(...)` calls in
`IssuanceUnitOfWork.issue` reverted to the pre-fix
`fail(event, InboundState.FAILED_ISSUANCE, ...)`:

```
D5-03: numbered=0 state=<none>                     <- branch probe still GREEN
D7-03 unchecked=false ... numbered=1 state=NUMBERED <- new probe RED
D7-03 unchecked=true  ... numbered=1 state=NUMBERED <- new probe RED
```

With the fix in place the new probe prints
`numbered=1 state=FAILED_VALIDATION preflight=Optional[PASSED]` on both catch paths, with
`DEI-221` and `DEI-261` respectively on the event row. So the control is correct; it is unguarded.

**Exact fix** (fix pass). Add `CipherProbePr7bDispositionTest` (in
the review's own probe set) to
`stripe-einvoice-core/src/test/java/com/housedevinci/einvoice/application/`. It drives a renderer
whose `preflight` returns `PreflightReport.passed()` and whose `render` throws - once an
`EInvoiceException`, once an unchecked one - so the number is allocated first and both catch paths
are covered. Keep the existing D5-03 probe or delete it; it asserts nothing either way, and if it
is kept it must be renamed to say it covers the preflight refusal.

---

#### D7-02 - LOW - the two determinism rules disagree about `Clock.systemDefaultZone()`, and neither names the locale-dependent formatters

`preflightPath()` lists `Clock.systemDefaultZone()`. `moduleWide()` does not list it at all. So the
same call is fatal in `adapter.en16931` and `domain.en16931` and allowed everywhere else -
`application`, `adapter.jdbc`, the starter - which is where the issue date and the series clock
actually live. The rule that is supposed to be the wider of the two is the weaker one for that
call, and the asymmetry is invisible because each rule is only ever checked against material that
does not make the call.

Beyond it, neither rule names three environment readers that are as locale- and machine-dependent
as `LocalDate.now()`: `String.format(String, Object...)` without a `Locale` (the module formats
money; `Money.toString` already uses `Locale.ROOT`, so the convention exists and is unenforced),
`String.toUpperCase()` / `toLowerCase()` without a `Locale`, and `Calendar.getInstance()`.

**Repro** - `CipherProbePr7bDeterminismTest`, two assertions red on this HEAD:

```
D7-04 moduleWide refused = allowed
D7-04 Clock.systemDefaultZone(): moduleWide=allowed preflightPath(as written, other packages)=refused
```

**Exact fix** (fix pass, one line each). In `DeterminismRules.moduleWide()` add
`callMethod(java.time.Clock.class, "systemDefaultZone")`; in **both** rules add
`callMethod(String.class, "format", String.class, Object[].class)`,
`callMethod(String.class, "toUpperCase")`, `callMethod(String.class, "toLowerCase")` and
`callMethod(java.util.Calendar.class, "getInstance")`. Then extend
`CipherNonDeterministicFixture` with one method per added call so the existing probe covers them,
and keep the two lists in sync by construction if it is cheap - a shared `List` of conditions that
both rules apply is one edit and removes this class of drift.

*Attempted and not a finding:* a refusal message that differs by default locale.
`ScreenedText.screen` formats a code point with `String.format("%04X", ...)`, but the `X`
conversion does not localise and the index is concatenated, so the message is byte-identical under
`hi-IN-u-nu-deva` and `Locale.ROOT`
(`probe_a_refusal_message_does_not_depend_on_the_default_locale`, green). No current call is
locale-dependent; D7-02 is a guard gap, not an observed divergence, which is why it is LOW.

---

#### D7-03 - INFO - the chain-gap limitation needs a dated owner, not a notes paragraph

`SECURITY-NOTES.md` now states that no chained event is appended at a failure disposition. Correct
and welcome. What is missing is the decision: the chain is the module's tamper-evident record, and
the justification for a gap in the allocated sequence currently lives only on a mutable issuance
row. That is a choice with a compliance consequence and it should be owned in writing, on the internal open-questions list, before the 0.1.0 tag.

**Exact fix** (docs only). Add to the module's internal open-questions list: "A legal number burned by
a validation or render refusal leaves a gap in the chained sequence with no chained entry
explaining it; the disposition is on the issuance row only. Decide before 0.1.0 whether a
`NUMBER_ABANDONED` chain entry is required for the numbering-gap justification." Dated, with the
0.1.0 release-candidate pass named as the checkpoint. No code change on this branch.

### Checked again and still clean

- Both `MappingInput`/`DocumentInput` construction sites unchanged; the profile the mapper screens
  under and the profile the writer writes under are the same field of the same renderer.
- `renderRefused` is one store call plus one event transition outside any transaction of ours, the
  same shape the validation refusal already uses; the allocator's "never two locks in one
  transaction" rule is untouched.
- `FAILED_VALIDATION` is a legal successor of `NUMBERED` in the enum and in the
  `schema-postgresql.sql` trigger guard, and is not `open()`, so a burned number counts as awaiting
  a void rather than as stuck. Verified by the new probe reaching it through the real store.
- Public text on the branch carries roles only; no agent or person name.
