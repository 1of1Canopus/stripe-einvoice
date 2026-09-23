# Security review - fix/release-preflight-and-sample

Adversarial review of the branch that repairs what the first release run broke: the preflight job
that ran repository scripts without a checkout, and a shipped sample application that could not
start. One pass, release surface.

## 2026-09-23 - first pass (HEAD 3540289)

Build: `./mvnw -B clean verify` on a clean worktree, Docker up. BUILD SUCCESS. Starter 119 tests,
sample 6 tests, 0 failures, 0 skipped. Every existing release-pipeline probe
(`tools/cipher-probe-release-pipeline.sh`, `CIPHER_PROBE_MAVEN=1`) re-run unchanged: all FIXED, no
regression. `tools/run-sample-smoke.sh` executed locally against the repackaged jar: first response
in 10s, the `numbering API only, as configured` startup line present.

**Verdict: MERGE WITH FIXES** (two MEDIUM, three LOW, one INFO; no HIGH).

### D14-01 (LOW) - the shipped `intake` profile was loaded by nothing

`stripe-einvoice-sample/src/main/resources/application-intake.yml` is new, the README tells a
reader to run the sample with it, and no test activated it: the end-to-end test sets the same three
properties by hand and never mentions the profile, and the shipped-configuration test covers the
profile being off. That is the defect this branch exists to fix, moved one file across.

Probe: `CipherProbePr14IntakeProfileTest.probe_the_intake_profile_the_readme_documents_is_never_started_by_any_test`
(sample module) - starts the context with `@ActiveProfiles("intake")` and only the environment
values the README names, with well-formed fake secrets.

Repro outcome: **the profile is correct today** - the probe is green on the first run, the context
starts and the Stripe reader is wired. The finding is the absent guard, not a broken file. Fix:
keep the probe (added in this review commit). No production change.

### D14-02 (MEDIUM) - `einvoice.issuance.enabled=false` does not turn intake off

This branch promotes `einvoice.issuance.enabled=false` to the shipped sample's default, to the
README ("run the numbering API only"), and to the remedy printed in the intake wiring check's own
refusal message. On a host that has the three intake beans and a webhook signing secret, that
property turns off the sweeper and nothing else: the unauthenticated webhook controller is still
mapped, the worker and the inbound event store are still built. Such a host accepts Stripe events,
records them durably, answers 200 so Stripe never retries and no alert fires, and issues nothing -
the exact failure the wiring check exists to prevent, reached through the property the module
recommends. The wiring check is also silent in this state: it returns at the "all three beans
present" branch before reaching the `numbering API only, as configured` line, so the one signal a
reader is told to look for is absent.

Repro: `CipherProbePr14Test.probe_disabled_issuance_still_maps_the_unauthenticated_webhook_endpoint`
(starter) - RED on this HEAD, `einvoiceWebhookController` present with
`einvoice.issuance.enabled=false`.

Fix (builder, not a correction pass - it moves the bean graph): in
`EInvoiceIssuanceAutoConfiguration`, make the property gate the thing it names rather than the
sweeper alone; the smallest honest placement is
`@ConditionalOnProperty(prefix = "einvoice.issuance", name = "enabled", matchIfMissing = true)` on
`einvoiceIssuanceUnitOfWork`, which every intake bean is already conditional on transitively - and
enumerate the beans that survive it (health indicator, findings endpoint, reprocess, inbound store)
with one test each before the change. In `IssuanceIntakeWiringCheck.afterPropertiesSet`, evaluate
the explicit-`false` branch before the "all three beans present" early return, so the numbering-only
state always says so at startup. Tests: extend `IssuanceWiringTest` with the all-ports-present,
secret-configured, explicitly-disabled case; the probe above flips green.

### D14-03 (MEDIUM) - the placeholder secrets this repository publishes are accepted as real ones

`StripeSecrets.PLACEHOLDERS` refuses `whsec_changeme`, `whsec_test`, `rk_test_xxx` and friends
because a sample value must never end up guarding the endpoint. The two values the public README and
`application-intake.yml` print as the ones to paste - `whsec_from_your_stripe_dashboard` and
`rk_test_your_restricted_read_scoped_key` - are in neither list, are well formed by every check the
module applies, and start an application in `live` mode whose unauthenticated webhook endpoint is
authenticated by a string anyone can read in the repository.

Repro: `CipherProbePr14Test.probe_the_readme_placeholder_secrets_are_accepted_as_real_secrets`
(starter) - RED, startup failure is null.

Fix (correction pass): add both documented literals to `StripeSecrets.PLACEHOLDERS`, and add a test
that reads the `whsec_`/`rk_`/`sk_` literals out of `README.md` and
`stripe-einvoice-sample/src/main/resources/application-intake.yml` and asserts
`StripeSecrets.requireWebhookSecrets` / `requireApiKey` refuses each one, so a future documented
placeholder cannot be added without being refused.

### D14-04 (LOW) - the preflight job runs code from a tree no gate has accepted

The checkout added to `preflight` is correct and the job stays secret-free and token-free
(`persist-credentials: false`, no `environment:`, no `secrets.`). But the job now executes two
repository scripts from the tree the tag points at, and the two gates that establish that the tree
is the reviewed one - `git merge-base --is-ancestor $GITHUB_SHA origin/main` and the
`RELEASE_SIGNING_KEY_ID`-bound `git verify-tag` - live downstream, in the publish job. Anyone who
can push a `v*` tag therefore gets arbitrary code execution inside the release workflow before any
verification. Rated LOW, not HIGH, and the reasons are on the record: the job holds no secret and no
deployment environment, its token is read-only, and there is no cache, artifact or output by which
it can reach the signing job - the escalation over what `ci.yml` already grants a pull request is
nil. It is still the release checklist's own line ("ancestry check on every path") unmet.

Repro: `probe_preflight_runs_unverified_tree_code` in `tools/cipher-probe-release-pipeline.sh` -
executes the scanner step's body against a tampered copy of `tools/install-scanner.sh` and asserts
the tampering ran, then asserts the job verified nothing first. WEAK on this HEAD.

Fix (correction pass): in the `preflight` job, `fetch-depth: 0` on the checkout, then the publish
job's "Verify the released commit is on main" step and its "Verify the tag signature" step verbatim,
both before the scanner step.

### D14-05 (LOW) - the new pull-request smoke check is not a required check

Moving the smoke body into `tools/run-sample-smoke.sh` and calling it from both workflows is the
right shape and removes the drift risk. But `Sample app from a clean clone` is not in the list of
contexts the preflight job requires main to carry (`Build & test`, `DCO sign-off`, `Cipher probes`,
`Reference guard`), which is this repository's only machine-readable statement of what must be green
before a merge. A check that may be red on a merged pull request is advice, and "a check nothing had
to pass" is the defect this branch exists to close.

Repro: `probe_sample_smoke_is_not_a_required_check_on_main` in the probe suite - WEAK on this HEAD.

Fix (correction pass): add `Sample app from a clean clone` to the `for want in ...` list in the
preflight step, and to the branch ruleset and the release runbook (internal). Expected consequence,
and intended: the first release refuses until the ruleset lists it.

### D14-06 (INFO) - the 0.1.0 changelog section has two `Fixed` headings

`CHANGELOG.md` now carries `### Fixed`, `### Changed`, then a second `### Fixed` inside the same
0.1.0 section. The changelog is the public artifact of a re-cut release. Fix: one `Fixed` and one
`Changed` section under 0.1.0.

## Rulings

### QUESTIONS 31 - why the release-candidate scenario "the sample as shipped" did not catch it

It did not catch it because I accepted two things in place of the one thing the scenario named.
The scenario was "the sample as shipped, from the README quick start, against the smoke check". I
marked it verified on the strength of an end-to-end test that supplies its own Stripe secrets and
its own fake Stripe - so it never loads the shipped configuration as shipped, it overrides exactly
the file under test - and of a probe that read the smoke step's text out of `release.yml`. A grep
over a workflow is not the workflow, and a test context that sets the properties the shipped file
sets is not that file. Both were green on an application that could not start.

The probe I owed was the one that ran today: build the sample, launch
`stripe-einvoice-sample/target/stripe-einvoice-sample-*.jar` with only the environment values the
README names, poll the open endpoint, and assert the startup line. That is
`tools/run-sample-smoke.sh`, it is now on the pull-request path, and it was executed locally in this
pass (first response in 10s, startup line present). Two rules carried forward into every later
review: when a scenario says "as shipped", the probe runs the shipped artifact and nothing else;
and a workflow step is only verified by executing its body, never by matching its text.

### QUESTIONS 32 - re-cut v0.1.0, or move to 0.1.1

Re-cutting v0.1.0 is correct on this evidence, and I checked the evidence rather than the argument:
the failed run refused in `preflight` and in `sample-smoke`, both upstream of the signing job;
nothing was signed, no bundle was uploaded, no Portal deployment carries this project's deployment
name prefix at this version, and no artifact for the coordinates resolves on repo1. A signed tag
that produced no artifact is an internal pointer, not a release, and moving it before any
publication is a correction rather than a rewrite. The changelog entries belong under 0.1.0.

I refuse the re-cut, and require 0.1.1, if any one of these holds: an artifact for these coordinates
at 0.1.0 resolves anywhere public; a Portal deployment with this project's prefix exists at this
version in any state, validated and unpublished included, because a validated deployment can still
be published; the tag has been consumed outside the repository (release notes, an advisory or
scanner reference, a downstream pin, a public announcement); or the re-cut tag is not itself signed
by the release key and an ancestor of main. Conditions on doing it: delete the remote tag before
creating the new one, so no clone can hold two different `v0.1.0` objects, and leave the replay
check and its deployment-name prefix untouched.

## Probes added by this review

- `CipherProbePr14Test.java` (D14-02, D14-03; both RED) and `CipherProbePr14IntakeProfileTest.java`
  (D14-01; green guard), held in the internal probes folder for this module, as every red probe is:
  the fix pass copies them into
  `stripe-einvoice-spring-boot-starter/src/test/java/com/housedevinci/einvoice/autoconfigure/` and
  `stripe-einvoice-sample/src/test/java/com/housedevinci/einvoice/sample/` before touching any
  production code, and they are public tests from then on.
- `probe_preflight_runs_unverified_tree_code`, `probe_sample_smoke_is_not_a_required_check_on_main` in `tools/cipher-probe-release-pipeline.sh` (D14-04, D14-05; both WEAK)

## Pass 2 (2026-09-23) - re-verification and new surface (HEAD 3978889)

Second and last pass on this branch. Worktree from `origin/fix/release-preflight-and-sample`,
Docker up, `./mvnw -B clean verify`: **BUILD SUCCESS, 569 tests** (core 435, starter 126, sample 8),
0 failures, 0 skipped. Probe suite with `CIPHER_PROBE_MAVEN=1` re-run unchanged before any edit of
mine: **78 fixed, 0 weak**.

**Verdict: MERGE WITH FIXES** - three LOW, no MEDIUM, no HIGH. The fixes are one line of YAML, one
sentence of changelog, and one refusal branch in a class that already holds the bean factory; none
of them is a mechanism, so they go to a correction pass and **no third review pass is required**.

### Closures verified, by probe

| Finding | Claim | Evidence |
| --- | --- | --- |
| D14-01 | intake profile probe adopted | `CipherProbePr14IntakeProfileTest` green in the sample module (1 test) |
| D14-02 | the property gates the intake | `CipherProbePr14Test` and `DisabledIntakeEndpointTest` green: signed event -> 404, 0 inbound rows |
| D14-03 | documented placeholders refused | `CipherProbePr14Test`, `DocumentedPlaceholderSecretsTest`, `IntakeProfilePlaceholderRefusalTest` green |
| D14-04 | gates before tree code in preflight | `probe_preflight_runs_unverified_tree_code` FIXED; step order in the job is checkout, ancestry, verify-tag, API gates, scanner |
| D14-05 | smoke is on the required list | `probe_sample_smoke_not_required_on_main` FIXED |
| D14-06 | one `Fixed`, one `Changed` | 0.1.0 now carries `Fixed`, `Changed`, `Added`, `Notes` |

Mutations re-run by me, not taken on report:

- D14-02: both `@ConditionalOnProperty` annotations removed from `einvoiceInboundEventStore` and
  `einvoiceIssuanceUnitOfWork` -> 3 failures (`DisabledIntakeEndpointTest`, `CipherProbePr14Test`,
  `IssuanceWiringTest`). Restored, green again.
- D14-03: the two documented literals removed from `StripeSecrets.PLACEHOLDERS` -> 2 failures
  (`DocumentedPlaceholderSecretsTest`, `CipherProbePr14Test`). Restored, green again. The sample's
  `IntakeProfilePlaceholderRefusalTest` was run against an already-installed starter, so it is not
  independent mutation evidence; the starter tests are.

### Attacked, and not a finding

- **A wrong secret format with intake on.** `StripeSecrets.requireWebhookSecrets` names the exact
  property (`einvoice.stripe.webhook-secrets.<id>`), states the shape, and says "The value is not
  printed here on purpose"; the placeholder branch says "is a placeholder from a sample file". No
  secret, no substring of one, and no stack of the configuration source reaches the message.
- **Where preflight's public key comes from, now that the job holds no secret.**
  `vars.RELEASE_SIGNING_KEY_ID` - a repository variable, not a file in the tree - is checked for a
  full 40-character fingerprint before use, the key is fetched from a keyserver, and the match is on
  `VALIDSIG ... <fingerprint>`, so a substituted key fails. A pull request cannot change a
  repository variable. A tag pusher does control `release.yml` itself and could delete these steps,
  which is why the control that matters is unchanged and downstream: `publish` keeps its own copies
  of both gates, `needs: preflight`, and runs in the reviewer-gated `release` environment. Refused
  by design, not by this branch.
- **A host that sets `einvoice.issuance.enabled=false` and declares its own `StripeInvoiceSource`.**
  The unit of work carries the property itself, so the source alone wires nothing. Covered by
  `IssuanceWiringTest`.

### D14-07 (LOW) - a host-supplied bean defeats the new gate, and the startup line says otherwise

The D14-02 gate sits on two beans that are both `@ConditionalOnMissingBean`, which is this module's
documented "bring your own" contract. A host that supplies its own `InboundEventStore` and its own
`IssuanceUnitOfWork` - both public types with public constructors - and also sets
`einvoice.issuance.enabled=false` gets `einvoiceWebhookController` mapped again, with the worker
behind it. Worse than the wiring, the new numbering-only line answers that context with "No Stripe
intake is wired: no webhook endpoint, no inbound event store, no issuance worker", which the check
never asked the bean factory about. The line is the only evidence an operator is given for
"numbering API only", and here it is false.

Repro: `CipherProbePr14bTest.probe_a_host_supplied_unit_of_work_reopens_the_webhook_endpoint_with_intake_off`
and `probe_the_numbering_only_line_claims_absences_it_never_checked` (internal probes folder, both
RED on this HEAD: `["einvoiceWebhookController"]` present with the property false).

Rated LOW because the host wrote those beans on purpose and the endpoint still verifies signatures;
it is the property not being authoritative, and a startup claim that is not checked.

Fix (correction pass): in `IssuanceIntakeWiringCheck.afterPropertiesSet`, inside the
`explicitlyDisabled()` branch, ask `beanFactory` for `IssuanceUnitOfWork`, `InboundEventStore`,
`IssuanceWorker` and `StripeWebhookController`; if any is present, throw
`EInvoiceException(ErrorCodes.CONFIG, ...)` naming the bean names found and the two ways out (drop
the bean, or set `einvoice.issuance.enabled=true`). The property wins: a contradiction is refused,
never logged over. Only when none is present may the existing line be printed. Tests: adopt both
probes into
`stripe-einvoice-spring-boot-starter/src/test/java/com/housedevinci/einvoice/autoconfigure/`, and
extend `IssuanceWiringTest` with a host-supplied-unit-of-work-with-intake-off case that asserts the
context failed with the CONFIG code.

### D14-08 (LOW) - the ancestry gate moved into preflight and left `sample-smoke` behind

`sample-smoke` in `release.yml` has no `needs:`. It checks out the tag's tree and runs
`tools/run-sample-smoke.sh` from it, in parallel with `preflight`, so a `v*` tag still gets
repository code executed inside the release workflow with nothing established about the tree - the
same defect D14-04 closed one job to the left. Same reasons for LOW as D14-04: no secret, no
environment, read-only token, and `publish` carries no Maven cache (M3), so there is no channel from
this job to the signing job. The checklist line ("ancestry check on every path") is still the one
unmet.

Repro: `probe_sample_smoke_runs_unverified_tree_code` in `tools/cipher-probe-release-pipeline.sh`
(added by this review) - executes the job's step body against a tampered `tools/run-sample-smoke.sh`,
shows the tampering ran, then reads the job graph. **WEAK** on this HEAD; suite now 78 fixed, 1 weak.

Fix (correction pass): `needs: preflight` on the `sample-smoke` job in `release.yml`. The `ci.yml`
copy is on the pull-request path and is unaffected.

### D14-09 (LOW) - the changelog denies the endpoint this module auto-configures

`CHANGELOG.md`, 0.1.0 `Notes`: "This module auto-configures no HTTP endpoint at all". The module
auto-configures `einvoiceWebhookController`, a POST endpoint that no host authentication stands in
front of (it authenticates the sender by Stripe signature), whenever the intake is wired. The
builder flagged this and asked for a ruling: **it is a finding, LOW, fixed in this branch.** A
changelog is the public statement of what a version ships, and understating the attack surface is
the kind of error a reader cannot catch.

Repro: `CipherProbePr14bTest.probe_the_changelog_denies_the_endpoint_this_module_auto_configures` -
RED: a wired context maps the controller while the file carries the sentence.

Fix (correction pass): replace the sentence with what is true - the only auto-configured HTTP
endpoint is the Stripe webhook controller, mapped only when the intake is wired (renderer, validator
and Stripe source present, `einvoice.issuance.enabled` not false, `einvoice.webhook.enabled` not
false); the privileged reprocess and the numbering API open no route of their own. Adopt the probe
with the fix.

### Probes added by this pass

- `CipherProbePr14bTest.java` (D14-07 x2, D14-09; all three RED), in the internal probes folder for
  this module; the correction pass copies it into the starter's test tree before touching
  production code.
- `probe_sample_smoke_runs_unverified_tree_code` in `tools/cipher-probe-release-pipeline.sh`
  (D14-08; WEAK), committed with this review as the pass-1 probes were.
