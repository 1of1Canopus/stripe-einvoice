# Security review - fix/release-scan-resolution

Adversarial review of the branch that repairs the third failed tag run: the pre-sign vulnerability
scan asked Maven Central for a module that exists only in the release reactor. One pass, release
surface, small change (one workflow step, one probe).

## 2026-09-24 - first pass (HEAD 8cda261)

Build: `./mvnw -B clean verify` on a clean worktree, Docker up. BUILD SUCCESS - core 435 tests,
starter 130, sample 8, 0 failures, 0 errors, 0 skipped. Probe suite re-run unchanged with
`CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh`: 80 probes, **still weak: 0, fixed: 80**,
nothing skipped.

Environment: dedicated worktree, Docker up, `CIPHER_PROBE_MAVEN=1`. Every attack below was executed
against a synthetic reactor built from this HEAD at version `99.99.99-probe` with an empty local
Maven repository - the same conditions the signing job runs under.

**Verdict: MERGE WITH FIXES** (three LOW, one INFO; no HIGH, no MEDIUM).

The change does what it claims. The failure of run 35922939487 reproduces exactly on the old step
body and is gone on the new one. What the new assertion does *not* cover is what the findings are
about.

### Confirmed: the reported defect is fixed (D-SCAN-01)

Old body (`dependency:copy-dependencies` with no `package`, no `-am`), same synthetic reactor, empty
local repository:

```
[ERROR] Failed to execute goal on project stripe-einvoice-spring-boot-starter: Could not resolve
        dependencies for project com.housedevinci:stripe-einvoice-spring-boot-starter:jar:99.99.99-probe
[ERROR] dependency: com.housedevinci:stripe-einvoice-core:jar:99.99.99-probe (compile)
[ERROR]   Could not find artifact com.housedevinci:stripe-einvoice-core:jar:99.99.99-probe in central
```

New body, same conditions: exit 0, 94 jars staged, scan completed, severity gate ran.

Four of the five questions this pass was asked answer clean:

- **`-am` scope.** `-pl core,starter -am` builds the two published modules and the parent pom
  project only. `stripe-einvoice-sample` was not built and no sample jar reached the scan set
  (`ls $scan | grep sample` empty). The scan set holds exactly the six jars the bundle publishes
  (core and starter: main, sources, javadoc) plus 88 resolved runtime dependency jars. No published
  jar is missing from it.
- **Network for `com.housedevinci`.** After the step, `$m2repo/com/housedevinci` does not exist and
  the step log contains no line mentioning the group id. Nothing in this step or after it resolves
  our own coordinates remotely.
- **Replay refusal.** Unchanged by this branch and still upstream of the scan (workflow order:
  reproducibility check, replay refusal, Grype install, scan, deploy). All three of its questions -
  repo1, the Portal published-check, the paged Portal deployment list - are intact and still
  fail-closed.
- **The probe runs the real body.** It extracts the step through `step_body "$WF" 'Scan the
  artifacts this release is about to sign'` and executes it; it is not a paraphrase and not a grep.
  The scanner stub writes the report from `find "$target" -name '*.jar'`, so the assertion is on the
  set actually handed to the scanner, not on what was copied earlier in the step.

### D15-01 (LOW) - the new byte-identity assertion is mutation-silent

The assertion added by this commit is the only thing standing between the scan and a
repository-fetched same-version jar, and nothing in the suite fails when it is deleted. The new
probe asserts exit code 0 and the presence of three names in the scanner report; removing the whole
`for built in stripe-einvoice-core/target/...` loop changes none of those. A control the probe suite
cannot see removed is a comment.

Repro: delete the loop from the step body, run
`probe_pre_sign_scan_cannot_resolve_the_reactors_own_modules` - still FIXED.

The assertion itself does work when exercised directly: replacing `$scan/stripe-einvoice-core-*.jar`
with foreign bytes makes it exit 1 with its `::error::` line.

Fix (Isis): extend `probe_pre_sign_scan_cannot_resolve_the_reactors_own_modules` in
`tools/cipher-probe-release-pipeline.sh`, or add a second probe beside it, that runs the step body
against a scan directory where the staged core jar has been replaced after the copy, and is WEAK
unless the body exits non-zero with that error. Note the loop is at the end of the step, so a probe
can also assert its presence as a sanity floor, but the executed case is the one that counts.

### D15-02 (LOW) - the scan set is not bound to the bytes that get signed

Question (b), answered by experiment: **the scanned jar can be a sibling build.**

`scripts/verify-reproducible.sh` builds with `-Prelease -Dproject.build.outputTimestamp=$TS` (the
tag's timestamp) and records each artifact's sha256 in `$REPRODUCIBLE_SHA_FILE`; the post-deploy
step compares the uploaded `central-bundle.zip` against that file. That is the digest binding, and
it binds the record to the upload.

The scan step now runs `./mvnw -B -q -DskipTests package ...` inside the tree the reproducibility
check just built, with neither `-Prelease` nor `-Dproject.build.outputTimestamp`. It therefore
rebuilds into the same `target/` directories with the pom's default timestamp
(`2026-09-15T00:00:00Z`), not the tag's. Whether it actually rewrites the jar depends on
maven-jar-plugin's up-to-date check, which is not a control:

- Untouched tree: the jar plugin short-circuits, `target/` survives, the scan set carries the
  reproducibility jar `d398ff18...`. Assertion passes.
- One `touch` on a single class file in `stripe-einvoice-core/target/classes` (anything that makes
  the archive older than its contents): the jar is recreated as `d763f6fe...`, `target/` no longer
  holds the bytes the reproducibility check recorded, the scan set carries `d763f6fe...` - and the
  assertion still passes, because its reference is the `target/` jar the step itself just
  overwrote.

Two consequences. First, the pre-sign scan does not demonstrably describe the artifacts the release
signs; it describes a fourth build of them. Second, the step mutates `target/`, which is the
evidence directory of the previous step. The upload itself is unaffected (`clean deploy` rebuilds,
and the sha file lives outside `target/`), which is why this is LOW and not MEDIUM. It is still the
exact property the commit message claims and does not deliver.

The scan set is also internally inconsistent after the step: the core jar comes from
`copy-dependencies` (the scan-step build), the starter jar from the earlier `cp` loop (the
reproducibility build).

Fix (Isis), in the `Scan the artifacts this release is about to sign` step of
`.github/workflows/release.yml`:
1. Pass the release build's own coordinates to the inner invocation so it cannot perturb the
   recorded bytes: add `-Dproject.build.outputTimestamp=${{ steps.v.outputs.timestamp }}`.
2. Replace `target/` as the reference. Compare every one of our jars in `$scan` against the
   recorded digest in `${{ runner.temp }}/reproducible-sha256.txt` (format: `<sha256>  <name>`,
   written by `scripts/verify-reproducible.sh`), and refuse on any mismatch. That is the record
   that binds the upload, so a scan set that matches it is provably about the signed bytes.

### D15-03 (LOW) - the assertion is single-module and skips itself when the jar is absent

Two holes in the same loop:

```sh
for built in stripe-einvoice-core/target/stripe-einvoice-core-*.jar; do
  ...
  resolved="$scan/$(basename "$built")"
  [ -f "$resolved" ] || continue
```

- `|| continue`: if the core jar is not in the scan set at all, the assertion passes. Executed
  against an empty scan directory: exit 0. The only other guard is `count -gt 0`, which the 88
  dependency jars satisfy on their own. So a scan set that has silently stopped containing our own
  artifacts - a scope change, a `copy-dependencies` option change, an `outputDirectory` typo - is
  reported as scanned and clean.
- The loop covers `stripe-einvoice-core` only. The starter jar in the scan set is whatever the `cp`
  loop put there and is never verified against anything. The starter cannot be repository-resolved
  today (it is a selected module, not a dependency), which is why this is LOW rather than MEDIUM,
  but the asymmetry is not stated anywhere.

Fix (Isis): drop `|| continue` - a missing `$resolved` is a refusal, with its own error message -
and run the loop over both published modules. Folds naturally into the D15-02 fix, since comparing
against the recorded digest covers both modules and all six jars in one pass.

### D15-04 (INFO) - release-only step bodies that have still never been executed

Per the checklist line "every job that exists only in `release.yml` is either duplicated in
`ci.yml` or covered by a probe that executes its step body". After this branch, the bodies below
have never run - not in CI, not in a probe, not by hand:

| Step (release.yml only) | Coverage today |
| --- | --- |
| `Verify the released commit is on main` (preflight and publish) | grep-asserted only (`probe_ancestry_check_skips_the_dispatch_path` reads the text) |
| `Verify the tag signature` (preflight and publish) | grep-asserted only (two probes, both text matches on `VALIDSIG`) |
| `Remove any pre-existing Maven wrapper distribution` | none |
| `Set the release version in the checkout` | none on the body; the equivalent `versions:set` command is executed inside two probes |
| `Confirm the bundle contains exactly the three published coordinates` | none in the suite; executed once by hand during this branch's work, which is evidence, not a gate |
| The Portal upload itself (`clean deploy -Prelease` against the live endpoint) | never, and acceptably so |

Executed bodies, for contrast: the ruleset/environment preflight check, the scanner-runnability
preflight check, the debug guard, the version derivation, `scripts/verify-reproducible.sh`, the
replay refusal, the deploy invocation shape, the bundle-versus-reproducibility comparison, and now
the pre-sign scan.

The first two rows are the ones that matter: the ancestry check and the tag-signature check are the
release's strongest gates, they are the first things a tag run reaches, and both are asserted by
reading the YAML. Each is testable for real - a temporary git repository, an ephemeral GPG key, a
tag signed by it and a tag signed by another - in the same style as the probes that already build
synthetic reactors.

Fix (Thor, not Isis - these are new probe harnesses, not corrections): add executing probes for
`Verify the released commit is on main` and `Verify the tag signature` against a synthetic
repository, and one for `Confirm the bundle contains exactly the three published coordinates`
against a synthetic bundle zip. The two remaining rows (`Remove any pre-existing Maven wrapper
distribution`, `Set the release version in the checkout`) are single-command bodies whose failure is
loud and immediate; recorded here so the list is complete, no probe required.

### Runbook note carried from the builder

`-DskipPublishing=true` produces no `central-bundle.zip`, so a dry run with that flag cannot
exercise the two post-deploy comparison steps. Belongs in the private release runbook next to the
local-rehearsal instructions.

## Pass 2 (2026-09-24) - second and last pass (HEAD e1cd0e6)

Probe suite re-run unchanged, `CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh`:
**82 probes, still weak 0, fixed 82**, nothing skipped. No Java changed between the two passes
(`git diff feecd02..e1cd0e6` touches `.github/workflows/release.yml` and
`tools/cipher-probe-release-pipeline.sh` only), so pass 1's `./mvnw -B clean verify` - core 435,
starter 130, sample 8, 0 failures, 0 skipped - stands.

**Verdict: MERGE.** No new finding. D15-04 stays open as a named condition on the first tag, below.

### Closures, each verified by an executing probe

- **D15-01 closed.** The reference is now `${RUNNER_TEMP}/reproducible-sha256.txt`, and the control
  is no longer invisible to the suite. My own mutation - both
  `assert_scan_set_is_the_recorded_build` calls deleted from the step body, committed, whole suite
  re-run - turns `probe_pre_sign_scan_accepts_an_unrecorded_jar` and
  `probe_pre_sign_scan_accepts_a_missing_published_jar` WEAK (still weak: 2, fixed: 80) while
  `probe_pre_sign_scan_cannot_resolve_reactor_modules` stays FIXED, which is the right shape: the
  resolution fix and the binding are independently observable.
- **D15-02 closed.** The inner build now carries `-Prelease -Dgpg.skip=true -DskipTests` and
  `-Dproject.build.outputTimestamp=${{ steps.v.outputs.timestamp }}`. That timestamp and
  `verify-reproducible.sh`'s `TS` are the same expression, `scripts/git-commit-timestamp.sh`, so the
  inner build cannot rewrite `target/` with bytes the record does not know. The fixture proves the
  stronger property I could not assume: a module-selected `-pl core,starter -am` build reproduces
  the full-reactor recorded bytes exactly, otherwise probe one would be WEAK.
- **D15-03 closed.** The check runs over every recorded `*.jar` of both published modules, in both
  directions, with a recorded-but-absent jar a refusal and an unrecorded jar of our group a
  refusal, and it is called twice - after staging and after `copy-dependencies`, the path a
  repository-fetched same-version artifact would arrive by. `collect()` in
  `verify-reproducible.sh` records core and starter only, never the sample, so the forward
  direction demands exactly the six jars the staging loop copies: the gate cannot deadlock the
  release on an artifact it never stages.
- **D15-04 not closed, deferred by routing.** Recorded as QUESTIONS 34 for a follow-up PR. That is
  the correct routing - executing probes for the ancestry and tag-signature bodies are new
  harnesses, Thor's work, not a correction - but deferral is not closure. **The first tag must not
  be cut until those two bodies have executed at least once.** They are the first gates a tag run
  reaches and they have only ever been read, not run; the last three tag runs each died in a
  release-only body that had never executed.

### Attack on the new binding: can the record be regenerated inside the scan step?

No. Repro attempt, closed without a code change.

- One writer: the `Reproducibility check` step, through `REPRODUCIBLE_SHA_FILE:
  ${{ runner.temp }}/reproducible-sha256.txt` (release.yml:399). `scripts/verify-reproducible.sh`
  truncates with `: > "$sha_file"` before writing, so a stale file cannot accumulate a second,
  agreeing entry.
- Two readers: the scan step (release.yml:522) and the post-deploy bundle comparison
  (release.yml:704). Same literal path, so the scan and the upload check are bound to one record.
- The scan step has no write path to it. Its only inner command is `./mvnw ... package
  dependency:copy-dependencies`, which writes to `target/`, to `$scan` and to the job-local
  `m2repo`; it never invokes `verify-reproducible.sh`, and nothing in the `release` profile writes
  into `RUNNER_TEMP`. The steps between the record and the scan are the replay refusal (network
  reads only) and the Grype install (`RUNNER_TEMP/bin`).
- Fail-closed if the record is gone: `[ -f "$record" ]` refuses with "a scan that cannot be bound
  to the artifacts being signed is not evidence". An empty record does not pass either - the
  reverse direction then reports every staged jar of ours as unrecorded.

Three further attempts that closed without a finding, recorded so they are not re-opened: the
reverse-direction `awk -v n=" $name" index(...)` match cannot collide (matching a shorter name
inside a longer recorded one would need `" X.jar"` to appear inside `" X-sources.jar"`); javadoc
jars are warn-not-enforce, which mirrors the post-deploy rule and cannot carry a dependency
advisory; and the record being an unsigned file in `RUNNER_TEMP` is not a finding under this job's
model, where every step comes from main's workflow definition and the job holds
`permissions: contents: read`.
