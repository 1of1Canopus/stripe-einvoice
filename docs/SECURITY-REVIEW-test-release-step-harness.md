# Security review - branch `test/release-step-harness`

Adversarial review of pull request 17 (five executing probes for release-only step bodies, plus one
bash-3 compatibility change in `release.yml`), head `eb5f65b`. Pass 1 of at most 2. Scope: the new
probe harness, the five step bodies it executes, and the one changed line of `release.yml`.

## 2026-09-26 - pass 1

### Verdict

**MERGE WITH FIXES** - nine findings, none above MEDIUM; seven carry a probe.

The five probes do what the pull request says they do. I ran each one in isolation and broke each
control the way the body claims: every semantic mutation flips the probe to WEAK, in **both** copies
of the two duplicated gates, and no probe takes its FIXED verdict from a grep of the YAML - each one
runs the extracted body and asserts both directions. The `tr` replacement is equivalent to `${x^^}`
on every input that can reach it.

What is left is the harness's own boundary: the step it finds is found by a *substring* match on the
*first* occurrence in the job, and "switched off" is recognised only as the bare literal `false`. Both
gaps are reachable, both let a release gate be turned off while the probe still reads FIXED, and both
contradict a property the pull request body states. One finding is in `release.yml` itself: the step
named "Confirm the bundle contains exactly the three published coordinates" does not enforce that.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | - |
| MEDIUM | 4 | D17-01, D17-03, D17-04, D17-06 |
| LOW | 3 | D17-02, D17-05, D17-07 |
| INFO | 2 | D17-08, D17-09 |

Probes: `internal/D-stripe-einvoice/probes/cipher-probe-step-harness-round1.sh`, seven probes, all
WEAK on `eb5f65b`. Five of them flip to FIXED against a candidate exact-match + robust-disable
detector I built in a scratch copy, which also keeps the four fast new probes green - so they are
reproductions, not tautologies. D17-06 and D17-07 need the changes prescribed below.

### Numbers

| What | Result |
|---|---|
| Probe suite on this head (runner record, `Cipher probes` job of run 36238873575) | **still weak: 0, fixed: 92** |
| The five new probes on the runner | all FIXED |
| The five new probes locally, run in isolation (`CIPHER_PROBE_MAVEN=1`) | all FIXED |
| Checks on the pull request | 7 of 7 green on `eb5f65b` |
| Full `verify` | not re-run locally; taken from the runner's `Build & test` (pass, 2m34s) |
| This review's probes on `eb5f65b` | still weak: 7, fixed: 0 |

### Mutation matrix I reproduced myself

Each mutation was applied to a scratch copy of `release.yml` and the single probe was run against it,
then the workflow restored and the probe re-run (FIXED every time).

| Probe | rename (name replaced) | commented out | `if: false` | semantic mutation | both job copies |
|---|---|---|---|---|---|
| `probe_ancestry_gate_is_never_executed` | WEAK | WEAK | WEAK | check replaced by `true`: WEAK | preflight and publish, each alone: WEAK |
| `probe_tag_signature_gate_is_never_executed` | WEAK | WEAK | WEAK | VALIDSIG match reduced to any key: WEAK | preflight and publish, each alone: WEAK |
| `probe_wrapper_removal_is_never_executed` | WEAK | WEAK | WEAK | `rm -rf ~/.m2`: WEAK | single copy |
| `probe_version_set_step_is_never_executed` | WEAK | WEAK | WEAK | version regex removed: WEAK; backup poms re-enabled: WEAK | single copy |
| `probe_bundle_coordinate_check_is_never_executed` | WEAK | WEAK | WEAK | sample refusal removed: WEAK | single copy |

Two claims in the pull request body do not survive the same treatment, and are D17-04/D17-05 below:
a rename that *appends* to the step name is not detected, and `if:` written in any form other than
the bare literal is not detected.

### `tr` equivalence (the one production change)

`printf '%s' "$X" | tr '[:lower:]' '[:upper:]'` was compared against a reference uppercase over 207
inputs matching `^[0-9A-Fa-f]{40}$` (all-zero, all-`a`, all-`f`, all-`A`, mixed case, 200 random) in
three locales (`C`, `en_US.UTF-8`, `tr_TR.UTF-8`): **0 mismatches**. The hex class contains no
character whose case mapping is locale-dependent, so the Turkish dotless-i problem cannot arise.

The refusal order was verified by executing the real body with stub `gpg` and `git` on `PATH` that
record being called: 39 hex, 41 hex, a non-hex character, a 16-character short key id, the empty
string, `40 hex + "\nmalicious=1"`, `$(touch ...)`, a trailing space and 40 fullwidth `A` are each
refused by the `=~` test with **zero** calls to `gpg` or `git` and no file created. Only a well-formed
40-hex value reaches `tr`, and a lowercase one is normalised (the error line names the uppercase
form). The regex is therefore already the whole input filter; `tr` is a convenience, not a control.

### Findings

#### D17-01 (MEDIUM) - a step switched off with `if: ${{ false }}` reads as enabled, for all five probes

`_step_is_disabled` matches only `^\s*if:\s*(false|'false'|"false")\s*$`. GitHub Actions skips a step
whose `if` is any always-false expression, and `${{ false }}` is the obvious one.

Repro (`probe_step_disabled_by_an_expression_is_not_detected`, and end to end): insert
`if: ${{ false }}` under the `- name:` line of the bundle step, the publish ancestry step, or the
wrapper step; run the matching probe. Each reads **FIXED** while the step never executes on the
runner. Same result for all five steps.

Fix: `tools/cipher-probe-release-pipeline.sh`, `_step_is_disabled` - refuse the step unless its `if:`
value is one this repository's own steps use (`success()`, `failure()`, `always()`, the two
`github.event_name` tests). An allowlist, not a denylist of false spellings: any `if:` value the
harness does not recognise must read WEAK, because the harness cannot evaluate an expression and
"unverifiable is not clean" is already the rule everywhere else in this suite. Probe to flip:
`probe_step_disabled_by_an_expression_is_not_detected`.

#### D17-02 (LOW) - `if: false  # comment` reads as enabled

Same helper, same consequence; the trailing-comment form is a separate spelling the regex's `$`
anchor rejects. Repro: `probe_step_disabled_with_a_trailing_comment_is_not_detected`. The allowlist
in D17-01 closes this as a side effect; it is listed separately so the fix is asserted for it.

#### D17-03 (MEDIUM) - for the single-line `run:` form, an `if:` after `run:` is invisible

YAML mapping keys have no order. Written as

```yaml
      - name: Remove any pre-existing Maven wrapper distribution
        run: rm -rf ~/.m2/wrapper/dists
        if: false
```

the step is skipped on the runner, `_step_is_disabled` stops scanning at the `run:` line and never
sees the `if:`, and `_step_command_text` hands the command back to be executed. Reproduced: the probe
reads **FIXED** with the wrapper removal switched off - the one step whose whole purpose is that the
key-holding job starts with no unverified Maven distribution on disk.

The four block-scalar steps read WEAK in the same shape, but by accident: the trailing `if: false`
line is swept into the extracted body and makes `bash` fail. That is D17-08.

Fix: `_step_is_disabled` must scan the whole step block (every line up to the next `- name:` or the
next job key), not stop at `run:`. Probe to flip: `probe_an_if_after_the_run_key_is_not_detected`.

#### D17-04 (MEDIUM) - the step is found by substring on the first match, so a decoy name is what gets executed

`_step_body_text`, `_step_command_text` and `_step_is_disabled` all locate the step with
`index($0, "- name: <name>")`. Any step whose name *contains* the gate's name matches, the `index`
rule fires before the exit rules, and every matching step's script is **concatenated** into one body.
A correct fragment anywhere in that concatenation satisfies the probe.

Repro, end to end against the suite itself: in the `publish` job, put a step named
`Verify the released commit is on main (legacy copy, kept for reference)` with `if: ${{ false }}` and
the correct `merge-base` check, and reduce the live gate underneath it to
`|| echo "::warning::not an ancestor, releasing anyway"`.
`probe_ancestry_gate_is_never_executed` reads **FIXED**: the ancestry gate of the key-holding job now
waves through a commit that was never on main, and the assertion is green.
Standalone: `probe_a_shadowing_step_name_is_executed_instead_of_the_gate`.

Fix: `tools/cipher-probe-release-pipeline.sh` - match the step name exactly
(`$0 == "      - name: " want`) in all three extractors, and read WEAK when the name occurs more than
once in the job: two steps with one name means the harness cannot say which body the runner runs.
Probe to flip: `probe_a_shadowing_step_name_is_executed_instead_of_the_gate`.

#### D17-05 (LOW) - a renamed step is not detected when the rename appends

The pull request body states every probe reads WEAK when its step is renamed. A rename that replaces
the name does (verified, all five). A rename that appends - `Verify the tag signature (advisory)` -
does not, for the same substring reason: the probe extracts and runs the body and reads FIXED.
Harmless on its own (the control still runs) but it is the mechanism behind D17-04, and the claim as
written is not true. Repro: `probe_a_renamed_step_is_still_found`. Closed by the exact match in
D17-04; listed separately so the pull request body is corrected with it.

#### D17-06 (MEDIUM) - "exactly the three published coordinates" does not check that

`.github/workflows/release.yml`, step `Confirm the bundle contains exactly the three published
coordinates`: the body denies one literal artifact name (`stripe-einvoice-sample`) and asserts the
three expected coordinates are present. It never asserts that nothing else is present.

Repro (`probe_the_bundle_check_accepts_a_fourth_coordinate`), executing the real body against
synthetic bundles at the real path: three coordinates plus `com/housedevinci/stripe-einvoice-pro/`
exits **0**; plus `com/housedevinci/evil-lib/` exits **0**; plus a renamed sample (`demo-app`) exits
**0**. This step is the last line of defence behind `excludeArtifacts`, which is also keyed on that
one name, so both halves of the control fail to the same rename - and a `-pro` module is on this
module's roadmap.

The new probe asserts only the three directions the step already handles (the three, plus the sample,
minus one, no bundle), so it certifies the step against a property weaker than the step's own name.

Fix, two parts: (1) `release.yml` - derive the coordinate set from the listing
(`com/housedevinci/<artifactId>/`), sort it, and refuse unless it equals exactly the three expected
values, so any extra coordinate fails whatever it is called; (2) add the fourth-coordinate direction
to `probe_bundle_coordinate_check_is_never_executed`. Probe to flip:
`probe_the_bundle_check_accepts_a_fourth_coordinate`.

#### D17-07 (LOW) - the one line this branch changes has no probe direction

`probe_tag_signature_gate_is_never_executed` never configures a lowercase fingerprint, so the `tr`
normalisation is unasserted in both directions. Reproduced: with
`fingerprint="$RELEASE_SIGNING_KEY_ID"` substituted for the whole `tr` pipeline in the publish copy,
the probe still reads **FIXED**. Impact is fail-closed (a maintainer who pastes a lowercase
fingerprint gets a refused release, never an accepted one), hence LOW - but a branch whose single
production change is unasserted by the probe it ships is exactly the shape this branch exists to end.

Fix: add a sixth case to that probe - `RELEASE_SIGNING_KEY_ID` set to the configured key's
fingerprint in lowercase must be **accepted** for the tag signed by that key. Probe to flip:
`probe_the_fingerprint_normalisation_is_unasserted`.

#### D17-08 (INFO) - `_step_body_text` has no terminator for a sibling step key after `run:`

The extractor prints every line after `run: |` until the next `- name:` or the next job key, so a
sibling key placed after the block scalar (`shell: bash`, `env:`, `working-directory:`,
`timeout-minutes:`, `if:`) is executed as the last line of the body. That is what makes the four
block-scalar probes read WEAK in the D17-03 shape - for the wrong reason - and it will produce a
false WEAK the first time a step is written with `shell: bash` after its `run:`. A WEAK line that is
not a finding is the thing this branch's own commit message argues against.

Fix: stop at any line whose indentation equals the step-key indentation and which is not part of the
block scalar (`/^        [a-z-]+:/` outside the scalar's deeper indentation). No new probe: D17-03's
probe covers the reachable half.

#### D17-09 (INFO) - the pull request body's suite counts are stale

The body says "87 probes, still weak 0, fixed 87" and cites run 36052568814. On this head, after the
back-merge of main, the runner record is **still weak: 0, fixed: 92** (run 36238873575). The body
also states every probe reads WEAK on a rename and on `if: false`, which D17-04/D17-05 and
D17-01/D17-02 contradict.

Fix: update the counts, the run id and those two sentences in the pull request body when the fixes
above land. Public text: the diff carries no person or agent name and no host path (checked); the
`Reference guard` check is green.

### Not findings, checked and closed

- **Step names still match this head.** Both copies of the two duplicated gates are found in
  `preflight` and `publish` (all five probes FIXED, locally and on the runner). PR 14 and PR 18
  renamed steps elsewhere; none of the five names moved.
- **No probe takes its FIXED verdict from a grep.** Each of the five extracts the body and executes
  it; the grep-based detection is used only on the WEAK side (which is where D17-01 to D17-05 live).
  The suite's older static probes still grep, but they are a different block and out of this scope.
- **A commented-out copy of a step before the live one** fails safe: the commented `run: |` sets the
  extractor's state, the body becomes comment lines, and every probe reads WEAK. Verified.
- **`origin/main` in the runner checkout.** The ancestry probe supplies the ref itself, so it does
  not prove the checkout provides it; `fetch-depth: 0` is present in both jobs and asserted by two
  pre-existing probes. Verified by design, not re-tested here.
- **`_job_block` on a renamed or removed job** yields an empty block and every probe reads WEAK with
  a stated reason. Verified for both job ids.
- **Probe hygiene.** The five leave no fixture behind (throwaway git repo, `GNUPGHOME`, `HOME`,
  reactor, bundle, all removed) and the `gpg` wrapper answers `--recv-keys` locally, so no probe run
  contacts a keyserver. Verified.
- **`/tmp/verify-tag.out`** is a predictable path and `tee` follows a pre-existing symlink (verified
  locally: the symlink target was overwritten). On a GitHub runner `/tmp` is single-tenant, so this
  is not reachable there; the probe now executes the body on maintainer machines, where it is a local
  hygiene matter rather than a release control. Reclassified as not-a-finding for this branch;
  `mktemp` instead of the fixed path would close it and costs one line.

### What I did not review

The Portal upload itself, the reproducibility and replay steps (covered by their own probes on
earlier branches), and everything outside the release path.
