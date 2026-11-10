# Security review: build/stripe-java-34 (PR 60)

## 2026-11-10, pass 1 on 7ba0829

**Verdict: MERGE WITH FIXES.** No HIGH. One MEDIUM (D-PR60-6) and five LOW (D-PR60-1 to
D-PR60-5). D-PR60-2 needs its own design and its own PR, and blocks the next release tag.

The change: stripe-java 33.4.2 to 34.0.0. That moves the SDK's Stripe API version from
`2026-08-26.dahlia` to `2026-09-30.endive`. It also moves test fakes to `Stripe.API_VERSION`,
adds two webhook tests, and adds a CHANGELOG upgrade note.

### Build and probes

`./mvnw -B verify` on a fresh clone, Docker up, run once on 7ba0829: BUILD SUCCESS, coverage
gates met, licence gate clean.

| Module | Tests | Failed | Errors | Skipped |
|---|---|---|---|---|
| core | 1144 | 0 | 0 | 3 |
| starter | 164 | 0 | 0 | 0 |
| sample | 9 | 0 | 0 | 0 |

The three skipped tests are the known Linux-only filename tests
(`FilesystemArchiveListingTest` x2, `CipherProbePr57Test` x1). They run on the Linux CI runner.
A skipped test is not a passing test.

`CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh`: 131 fixed, 0 still weak, exit 0.

### Mutations

| # | Change | Expected | Result |
|---|---|---|---|
| M1 | `IssuanceUnitOfWork.route`: version check off (`if (false && ...)`) | skew tests red | Red. Both starter skew tests, plus 13 core tests (`IssuanceUnitOfWorkTest`, `CipherProbeIssuanceTest`, `OneRunPerEventTest`, `SweeperRetryEdgeTableTest`) |
| M2 | starter `IssuanceTestApp.PINNED_VERSION` = literal `2026-03-31.clover` | - | Red, one test: `an_event_carrying_the_sdk_api_version_is_accepted_and_issued` (REFUSED_VERSION_SKEW) |
| M3 | core `FakeStripeSource.PINNED` = literal `2026-03-31.clover` | nothing red | Green, 1144 |
| M4 | sample `FakeStripe.PINNED` = literal `2026-08-26.dahlia` | nothing red | Green, 9 |
| M5 | stripe-java 34.1.0 (moves the pin to `2026-10-28.endive`) | the two new tests red | Both green. See D-PR60-3 |

### The question from the fix pass: was a control unproven on main?

No. On main, most issuance tests used a fake pin (`2026-03-31.clover`), not the SDK's. No
control depended on that value:

- The route check compares two strings. It does not care which pin. On main it was proven both
  ways: refused (`a_version_skew_event_is_recorded_and_answered_200` and the core tests in M1)
  and accepted (every endpoint test at the fake pin).
- That the pin comes from the source port, not from somewhere else, was proven by the same
  tests. An event at the fake pin was accepted.
- That the real source pins the SDK version was proven in `StripeApiInvoiceSourceTest`. It
  checks the request header and the refusal of another pin at construction.

M3 and M4 stay green because those fakes stand in for the source port. The code under test never
sees the value. That is the expected result, not a gap. In the starter, M2 is red: the new
accepted test ties that fake to the SDK. No register row.

One thing the fake pin did hide is the case where the module moves ahead of the webhook
endpoint. Nothing tested it. That is D-PR60-1 and D-PR60-2.

### D-PR60-1 (LOW, docs vs code): the upgrade note gives a recovery path that does not exist

Probe `probe_a_row_refused_at_the_previous_pin_replays_once_the_endpoint_is_re_pinned`: red.
The row stays `REFUSED_VERSION_SKEW` through 15 s of sweeps.

The note says the refused events "replay once the versions match". They do not. A row keeps the
version it arrived with (`2026-08-26.dahlia`). On 34.0.0 the pin can only be
`2026-09-30.endive`. The due query re-picks only rows whose version equals the pin, so these
rows never match. The README row the note points to says "Update the pin to match, restart".
On this upgrade that is refused at start-up, which the note itself says. "Re-pinned" is also
not a Stripe action. Stripe sets an endpoint's version when the endpoint is created, so the
operator creates a new endpoint.

What does save the invoices today: reconciliation (on by default, 2-day window) re-enqueues
every finalised invoice that has no document, at the current pin. Check
`check_reconciliation_re_enqueues_the_invoice_a_previous_pin_row_left_unissued`: green. The
`recon-` row is at the SDK pin and gets past the route.

Fix (text only):

1. CHANGELOG note. Say what to do, in this order:
   - Create a new webhook endpoint at the new version.
   - Add its signing secret to `einvoice.stripe.webhook-secrets` next to the old one.
   - Disable the old endpoint.
   - Deploy this version.

   Events that arrive in between are held as `DEI-201` with the new version. The sweeper
   replays them after the deploy. Rows recorded at `2026-08-26.dahlia` are never replayed.
   Reconciliation re-enqueues their invoices only if it is on and the invoice is inside its
   window. Remove "replay once the versions match" and "re-pinned". Replace the last sentence
   (the one about the tests) with plain words, or drop it.
2. README, "Operating it", row "Stripe moved its API version": split it into two cases. If
   Stripe is ahead of the module, upgrade the module. If the module is ahead of the endpoint,
   follow the order above.
3. `docs/index.md`, "Replaying a refused event": add the second case and the order.

### D-PR60-2 (LOW, boundary validation): a copy at the new version of an already refused event is dropped (DESIGN STOP)

Probe `probe_the_sdk_version_copy_of_an_event_refused_at_the_previous_pin_is_processed`: red.
The row stays `REFUSED_VERSION_SKEW`.

During a change of endpoint, both endpoints receive the same event. We assume the same event id
reaches both endpoints. This comes from Stripe's documentation and was not tried against Stripe.
If the old-version copy lands first, it is recorded as `DEI-201`. The new-version copy is then
a duplicate (`ON CONFLICT (event_id) DO NOTHING`), so it is answered 200 and dropped. The event
is never processed. Only reconciliation can still issue that invoice.

This is on main already. This PR is what makes it reachable for users of the published version.

Fix: a new mechanism. It decides what recording an existing event id at a different API version
does. The rule that must hold: a refused-for-skew row is cured by a signed copy at the current
pin, with the body replaced, the change recorded, and never a second run of a processed row.
Paths it must cover: the webhook insert, the sweeper's due query, reprocess, and the purge. A
one-page design from the builder, reviewed before code, in its own PR. It must be merged before
the next release tag. It is not a fix-list item for this PR.

### D-PR60-3 (LOW, false completion): the "pin watch" tests cannot see a pin move

Repro M5: the pom set to stripe-java 34.1.0, which moves `Stripe.API_VERSION` to
`2026-10-28.endive`. Both new tests stay green, and CI would be green too. Both tests are
written in terms of the SDK constant. The previous-pin test checks only that the SDK value is
not `dahlia`. So the comment "When a stripe-java bump moves Stripe.API_VERSION, this and the
next test show it in CI" is not true. The CHANGELOG note would then name a version the module
no longer accepts. Dependabot's `minor-and-patch` group would bring 34.1.0 inside a six-update
PR.

Fix:

1. In `StripeWebhookEndpointTest`, add `the_sdk_pin_is_the_one_the_upgrade_note_names`. It
   asserts `Stripe.API_VERSION` equals a literal kept in one constant. The failure message
   says: write the upgrade note, then update the literal. It must be red under M5.
2. Correct the comment.
3. In `.github/dependabot.yml`, add `exclude-patterns: ["com.stripe:stripe-java"]` to the
   `minor-and-patch` group, so every stripe-java bump arrives alone.

### D-PR60-4 (LOW, supply chain): the PR lands a superseded pin

stripe-java 34.1.0 came out on 2026-10-29 and pins `2026-10-28.endive`. It is on Central and
outside the 7-day cooldown. The module accepts exactly one version string. With 34.0.0, the next
release asks operators to create endpoints at `2026-09-30.endive`, a version that is already
behind. The next bump then makes them change endpoints a second time.

Repro M5: the whole build on 34.1.0 is green except one core test. That failure is a stale
compiled class, not 34.1.0 (see D-PR60-6). The PR body says "not 34.1.0" and gives no reason.

Fix: bump to 34.1.0 in this PR. Write `2026-10-28.endive` in the note, the test literal, and
`CipherProbeActuatorTest`. Read 34.1.0's changes for the invoice, line-item and tax-rate
fields the adapter reads, as was done for 34.0.0.

### D-PR60-5 (LOW, boundary validation): reconciliation off is silent at start-up

Probe `probe_switching_reconciliation_off_is_silent_at_start_up`: red. Nothing in the output
names `einvoice.reconcile.enabled=false`.

After a pin move, reconciliation is the only path that issues invoices whose events were
refused at the previous pin (D-PR60-1, D-PR60-2). It also finds missing sales in general.
Switching it off is a weaker mode and must WARN at every start-up.

Fix: `IssuanceSweeper.afterPropertiesSet`. When `!properties.getReconcile().isEnabled()`, log a
WARN that names the property and what is lost: sales with no document are no longer found, and
events refused at a previous API version are no longer re-enqueued. Turn the probe into a test
in the starter.

### D-PR60-6 (MEDIUM, false completion): the pin is the SDK the module was compiled against, not the SDK the host runs

Probes, core, `CipherProbePr60SdkTest`. Compile at 34.0.0, then run `surefire:test` with
`-Dstripe-java.version=34.1.0`. Both probes are red:

- `probe_the_pin_is_the_compiled_sdk_version_not_the_one_on_the_classpath`: the module says
  `2026-09-30.endive`, the SDK on the classpath says `2026-10-28.endive`.
- `probe_a_source_whose_sdk_speaks_another_version_is_built_rather_than_refused`: the source is
  built, with no refusal.

`Stripe.API_VERSION` is a compile-time constant. So `StripeApiInvoiceSource.SDK_API_VERSION`,
the default in `StripeClientFactory`, and the construction check all hold the version the module
was built with. In core and in the starter, stripe-java is `optional`. So the host must declare
stripe-java itself, at a version it picks, and the README names none. A host on any other
version gets a module that claims one API version while the SDK sends and parses another. No
refusal happens. The construction check exists to stop exactly that. This is on main since
0.1.0.

Fix:

1. In `StripeApiInvoiceSource`, add a package-private static
   `requireClasspathSdk(String compiled, String runtime)`. It throws `EInvoiceException`
   `ErrorCodes.CONFIG` when the two differ, with a message that names both versions and says to
   align stripe-java. Call it first in the constructor. Pass `SDK_API_VERSION` and the runtime
   value, read with `Stripe.class.getField("API_VERSION").get(null)`. A failed read is also a
   refusal.
2. Add tests in `StripeApiInvoiceSourceTest`:
   - `a_classpath_sdk_that_speaks_another_version_is_refused_at_construction`: two
     different strings give `ErrorCodes.CONFIG`.
   - `the_classpath_sdk_is_the_compiled_one`: the reflective value equals `SDK_API_VERSION` on
     the build classpath.
3. In the README install section, name the exact stripe-java version each release is built for.
   Say that any other version is refused at start-up.

No weaker mode is prescribed. If the builder would rather have the pin follow the runtime SDK,
that is a design change: a one-page design first.

### Public text

The CHANGELOG note has no names and no paths. Its language and its instructions are covered
by D-PR60-1. The reference guard (`tools/check-private-references.sh --tree`) passes on this
document.

### Not tried in this pass

- Stripe itself: whether the same event id reaches two endpoints at two versions (D-PR60-2
  assumes it), and whether a Dashboard resend comes at the endpoint's current version.
- A host on stripe-java 33.x or 35.x with this module: only 34.1.0 was run (D-PR60-6).
- The 34.1.0 changes to the invoice, line-item and tax-rate fields: build and tests only, no
  diff of the models.
- The webhook-secret hardening in 34.1.0 (whitespace-only secrets refused). The module checks
  signatures with its own code, which was not re-attacked here.

## 2026-11-10, pass 2 on f3f5a1a

**Verdict: MERGE WITH FIXES.** No HIGH. The six findings of pass 1 are closed on this branch,
except D-PR60-2, which is in its own PR (design stop). Four new LOW findings (D-PR60-7 to
D-PR60-10) must be fixed on this branch before merge. Their probes are listed below. D-PR60-2
must merge in its own PR before the next release tag.

This is the last pass under the two-pass cap. The fix pass adds the pass 2 probes first, then
fixes. The verifier's numbers on the probes flipping green close the four findings. No third
security pass is needed unless a fix adds a mechanism.

### Build and probes

`./mvnw -B clean verify` on f3f5a1a, Docker up, run once: BUILD SUCCESS, coverage gates met.

| Module | Tests | Failed | Errors | Skipped |
|---|---|---|---|---|
| core | 1148 | 0 | 0 | 3 |
| starter | 167 | 0 | 0 | 0 |
| sample | 9 | 0 | 0 | 0 |

The three skipped tests are the known Linux-only filename tests. They run on the Linux CI
runner. A skipped test is not a passing test.

`CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh`: 131 fixed, 0 still weak, exit 0.

### Pass 1 findings

| Id | Sev | Fix | Verified by | Status |
|---|---|---|---|---|
| D-PR60-1 | LOW | df803db, text | CHANGELOG, README case 2 and `docs/index.md` read against the due query. The safe order is right: rows recorded at the new version during the change are re-picked after the deploy (core `probe_a_version_skewed_event_is_re_picked_by_the_sweeper_after_the_pin_moves`, green). README case 1 is now wrong: D-PR60-7 | closed |
| D-PR60-2 | LOW | design reviewed (D-SKEW-1 to 4), in build on its own branch | not in this PR | open, release-blocking, own PR |
| D-PR60-3 | LOW | 7082f90 | Mutant M5b: pom at 34.0.0 with a clean recompile. `the_sdk_pin_is_the_one_the_upgrade_note_names` red, expected `2026-10-28.endive`, was `2026-09-30.endive`. Dependabot exclude checked below | closed |
| D-PR60-4 | LOW | 937d564 | `stripe-java-34.1.0` resolved in the build. No `2026-09-30` left outside probe comments. Model signatures of 34.0.0 and 34.1.0 for the classes the adapter reads compared with `javap`: identical | closed |
| D-PR60-5 | LOW | 817b8cf | `probe_switching_reconciliation_off_is_silent_at_start_up` green. Mutant MD (WARN branch off): red | closed. A window that covers nothing bypasses it: D-PR60-10 |
| D-PR60-6 | MEDIUM | 746b8d7 | Mismatch run MA: compiled at 34.1.0, `surefire:test -Dstripe-java.version=34.0.0`. `probe_a_source_whose_sdk_speaks_another_version_is_built_rather_than_refused` green: CONFIG, message names both versions. The ten source tests that build a source go red, as they must | closed. The wiring has no test on the CI classpath: D-PR60-9 |

### Rulings on the fix pass deviations

1. **D-PR60-1 replay probe not added.** Accepted. That probe asserted the replay the old note
   promised. The fix makes the text say that replay does not happen, so the probe could never
   turn green. The finding closes on the text.
2. **D-PR60-2 probe not added.** Accepted. It belongs to the design stop and lands with that PR.
3. **Second core probe rewritten to pass in both classpath states.** Accepted. In the mismatch
   run it takes the refusal branch and is green. The first probe stays red in the mismatch
   state. That is correct: it records that an inlined constant is the build's. The fix does not
   change that fact. It refuses the mismatch.
4. **Forced recompile.** Confirmed. `Stripe.API_VERSION` and `Stripe.VERSION` are inlined. A
   mutant that only overrides the pom version keeps the stale classes. M5b used `clean`. Pass 1
   M5 had the same flaw, which explains its one stale-class failure.
5. **f3f5a1a.** Whitespace, line wrapping and import order only (`git diff -w`).

### Dependabot exclude

`exclude-patterns: ["com.stripe:stripe-java"]` matches. Dependabot names this artifact
`com.stripe:stripe-java` (its own PR 30 title). That is the `groupId:artifactId` form the docs
give for Maven, and the form this file already uses in `ignore`. The group has no `patterns`, so
it still takes every other minor and patch update. GitHub's docs say an outdated dependency that
matches no group "is updated in individual pull requests". The file is read from the default
branch, so the effect can only be observed after merge. Not observed here.

What the exclude does not cover: a stripe-java patch that keeps the API version passes every
test, so it auto-merges under the studio rule. The README would then name the old release.
That is part of D-PR60-8.

### Attacks on the reflection read

A harness built a source with `new StripeApiInvoiceSource(null, pin)`. The check runs before the
null check, so "NullPointerException: client" means the check passed. A fake `com.stripe.Stripe`
was put first on the classpath, with the real jar behind it.

| Shape of `Stripe.API_VERSION` | Result |
|---|---|
| no field | refused, `unreadable (NoSuchFieldException)` |
| `Integer` field | refused, `unreadable (ClassCastException)` |
| instance field, right value | refused, `unreadable (NullPointerException)` |
| private static field | refused, `unreadable (NoSuchFieldException)` |
| `null` | refused, `speaks null` |
| static initialiser throws | refused, `unreadable (ExceptionInInitializerError)` |
| right value plus a trailing space | refused |
| another version | refused |
| right value, not final | passed (correct) |
| no stripe-java on the classpath | `NoClassDefFoundError` while loading the source class: nothing is built |
| core and stripe-java 34.1.0 as named automatic modules on the JDK 21 module path | passed |
| same, stripe-java 34.0.0 | refused, CONFIG |

Every unreadable shape fails closed. JDK 21 has no SecurityManager by default, and an automatic
module exports every package, so the read crosses the module boundary. A stripe-java relocated
by a shading host without relocating this module is the "no stripe-java" row. A host that shades
both together relocates both references the same way; the field name is not a class name and is
not rewritten. That case was reasoned, not run.

A two-jar classpath (34.0.0 before 34.1.0) is read correctly: the read and the SDK use the same
loaded class.

### D-PR60-7 (LOW, docs vs code): the README says skew rows stay refused when the module catches up

Probe, starter, `CipherProbePr60PassTwoTest`:
`probe_the_readme_says_skew_rows_stay_refused_after_the_module_catches_up`. Red.

df803db wrote, for "Stripe is ahead of the module": "The refused rows stay refused;
reconciliation issues their invoices". The due query re-picks a `REFUSED_VERSION_SKEW` row whose
recorded version equals the pin (`JdbcInboundEventStore`, the `D2-03` clause). After the upgrade
the pin is the rows' version, so they replay. Core
`probe_a_version_skewed_event_is_re_picked_by_the_sweeper_after_the_pin_moves` proves it, green.
`docs/index.md` says the same as the code. An operator who believes the README may reprocess
these rows by hand, or wait for a reconciliation window that has already passed.

Fix (text): README, "Operating it", row "Stripe moved its API version", first case. Replace
"The refused rows stay refused; reconciliation issues their invoices if it is on and they are
inside its window" with: "The sweeper then re-picks the refused rows whose recorded version
equals the new pin and replays them."

### D-PR60-8 (LOW, docs vs code): "any other version is refused" is not what the check does

Probe, starter, `CipherProbePr60PassTwoTest`:
`probe_the_install_text_promises_a_refusal_for_any_other_stripe_java_release`. Red.

The README says "Declare exactly stripe-java `34.1.0` ... refuses to start with any other
version". The CHANGELOG says "any other version is refused at start-up". The check compares
Stripe API versions, not releases. stripe-java 33.4.0, 33.4.1 and 33.4.2 all speak
`2026-08-26.dahlia` (read with `javap` from Central). So a later 34.1.x patch at
`2026-10-28.endive` starts. The harness row "right value, not final" shows it. Comparing the API
version is the right design: an exact release match would refuse stripe-java security patches.
The text is what is wrong.

Second part: a stripe-java patch that keeps the API version passes every test, so it can
auto-merge. The README then names the old release.

Fix:

1. README install section and CHANGELOG note. Say: this release is built and tested with
   stripe-java `34.1.0`. The module refuses, at start-up, a stripe-java that speaks another Stripe
   API version than `2026-10-28.endive`.
2. Add the check from the probe file as a starter test:
   `the_readme_names_the_stripe_java_release_the_build_resolves`. It asserts the README's
   stripe-java dependency snippet carries `com.stripe.Stripe.VERSION`. Green now. Red under any
   stripe-java bump until the README is updated, so no stripe-java bump auto-merges.

### D-PR60-9 (LOW, false completion): the classpath check is wired with no test on the CI classpath

Probes, core, `CipherProbePr60PassTwoSdkTest`. Both red (`NoSuchMethodException`: the seams do
not exist yet).

- Mutant MB: the `requireClasspathSdk(...)` call removed from the constructor. Full core suite:
  1148 run, 0 failed. Only the mismatch run sees it, and CI never runs that.
- Mutant MC: the catch returns `SDK_API_VERSION` (an unreadable SDK accepted). The source tests
  and both PR 60 probes: 20 run, 0 failed. No other test reaches that path.

The tests pass 1 prescribed test the static method alone. That prescription was incomplete.

Fix, `StripeApiInvoiceSource`:

1. Replace `runtimeSdkApiVersion()` with package-private static
   `String sdkApiVersionOf(Class<?> stripeClass)`. Same body, the class as a parameter.
2. Add a package-private constructor
   `StripeApiInvoiceSource(StripeClient, String, Duration, String runtimeSdkApiVersion)` that
   calls `requireClasspathSdk(SDK_API_VERSION, runtimeSdkApiVersion)` first. The public
   three-argument constructor delegates with `sdkApiVersionOf(Stripe.class)`.
3. Tests in `StripeApiInvoiceSourceTest`:
   `a_source_over_another_classpath_sdk_is_refused_at_construction` (through the new
   constructor) and `an_unreadable_classpath_sdk_is_refused` (no field, `Integer` field,
   instance field, `null`, each through `sdkApiVersionOf` then `requireClasspathSdk`). MB and MC
   must each turn one of them red.

### D-PR60-10 (LOW, boundary validation): a reconciliation window that covers nothing starts silently

Probe, starter, `CipherProbePr60PassTwoWindowTest`:
`probe_a_reconciliation_window_shorter_than_its_interval_starts`. Red on the first case,
`einvoice.reconcile.window=0s`.

Each pass lists invoices finalised in `[now - grace - window, now - grace]`. The real source
filters on both ends (`StripeApiInvoiceSource.finalisedInvoiceIds`). A zero or negative window
covers nothing. A window shorter than the interval leaves gaps between passes, and the schedule
is fixed-delay, so each gap also grows by the pass time. `ReconciliationSweep.Settings` accepts
any window. That is reconciliation switched off without the WARN that D-PR60-5 added. It is on
main. It is reported here because it bypasses the new control.

Fix:

1. `ReconciliationSweep.Settings`: refuse a zero or negative window with `ErrorCodes.CONFIG`,
   for users of core without the starter.
2. `IssuanceSweeper.afterPropertiesSet`: when reconciliation is on, refuse with
   `ErrorCodes.CONFIG` a window shorter than twice the interval. The message names
   `einvoice.reconcile.window` and `einvoice.reconcile.interval` and says why: invoices finalised
   between two passes would never be checked. Defaults (2d, 15m) pass.
3. The probe becomes a starter test. `docs/index.md`, the reconcile row of the property table:
   add the rule.

### Public text

The CHANGELOG, README and `docs/index.md` changes carry no names and no paths. The reference
guard (`tools/check-private-references.sh --tree`) passes on this document.

### Not tried in this pass

- Stripe itself: two endpoints at two versions and the event id they carry; a Dashboard resend.
- A shading host that relocates both this module and stripe-java: reasoned, not run.
- Dependabot's handling of the exclude: only observable once the file is on the default branch.
- stripe-java 33.x or 35.x as the host SDK: covered by the check in principle. Only 34.0.0 and
  fake classes were run.
- The 34.1.0 changes inside the models (field names in annotations, deserialisers): signatures
  only.
- The webhook signature code against 34.1.0's secret hardening: not re-attacked.
