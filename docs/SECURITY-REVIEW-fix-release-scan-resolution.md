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
