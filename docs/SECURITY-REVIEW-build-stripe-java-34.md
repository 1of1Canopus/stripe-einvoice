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
