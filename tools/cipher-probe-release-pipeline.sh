#!/usr/bin/env bash
#
# Cipher probes for the Maven Central release pipeline (branch feat/release-pipeline).
#
# Every probe below asserts a WEAKNESS. Each one PASSES while its finding is open and must
# FLIP TO FAILING once the matching finding from the security review is fixed. A probe
# that starts failing is the signal that the item is closed; delete it in the same PR that
# fixes it.
#
# The first block (M-, L-, I-ids) is the first pass, on 645397d: all FIXED at 30aec6f
# except the one reclassified as not-a-finding; see the note on
# probe_release_job_can_exec_an_unverified_maven_distribution below, which replaces it.
# The second block (N-ids) is the re-verification of 30aec6f: all WEAK there, all FIXED at
# 1da507e.
# The third block (F-ids) is the final verification of 1da507e, after the FSL-1.1-ALv2
# licence switch: all WEAK there.
#
# A probe for a step that runs a scanner uses the REAL pinned scanner, never a stub: a stub
# writes the report shape the gate expects, so the one thing that can be wrong - the gate and
# the scanner disagreeing about the document - is the one thing the probe cannot see (release
# run 36048639931, D-SCAN-02).
#
#   tools/cipher-probe-release-pipeline.sh            static probes only (seconds)
#   CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh   + the two build probes
#
# Exit code is 0 once every probe has flipped to FIXED, 1 while any weakness is still
# there (`[ "$pass" -eq 0 ]` on the last line). The header of this file used to claim the
# opposite; the code was always right and the sentence was wrong. Since the N12 fix this
# suite IS a CI gate: the `cipher-probes` job in .github/workflows/ci.yml runs it
# unconditionally on every push and pull request, and three probes below assert that that
# job cannot be disabled by a comment, by `if: false`, or by a rename.
#
set -uo pipefail
cd "$(dirname "$0")/.."

WF=.github/workflows/release.yml
pass=0; flipped=0

# Checklist line 71. A probe that shells out to an inner build, a script or any external
# command writes that command's output HERE - `>>"$PROBE_CAPTURE" 2>&1`, never
# `>>"$PROBE_CAPTURE" 2>&1` - and the reporter below prints the last 30 lines of it whenever the
# probe reads WEAK. A transient runner failure inside an inner Maven build used to be
# indistinguishable from the weakness the probe is looking for, because the output that said
# which one it was had been thrown away.
#
# A probe that cannot run at all (no Docker, no `zip`, CIPHER_PROBE_MAVEN unset) sets
# PROBE_SKIP_REASON and returns 0: unverifiable counts as WEAK, and now says why on the same
# line rather than in a stray stderr echo.
PROBE_CAPTURE=""
PROBE_SKIP_REASON=""
export PROBE_CAPTURE PROBE_SKIP_REASON

probe() { # probe <name> <"still weak" message>; body returns 0 when the weakness is present
  local name="$1" msg="$2"; shift 2
  PROBE_CAPTURE="$(mktemp)"
  PROBE_SKIP_REASON=""
  if "$@"; then
    printf 'WEAK    %-52s %s\n' "$name" "$msg"; pass=$((pass + 1))
    if [ -n "$PROBE_SKIP_REASON" ]; then
      printf '        unverifiable: %s\n' "$PROBE_SKIP_REASON"
    fi
    if [ -s "$PROBE_CAPTURE" ]; then
      printf '        --- last 30 lines of this probe%s inner command output ---\n' "'s"
      tail -n 30 "$PROBE_CAPTURE" | sed 's/^/        | /'
      printf '        --- end of inner command output ---\n'
    elif [ -z "$PROBE_SKIP_REASON" ]; then
      printf '        (no inner command output was captured for this probe)\n'
    fi
  else
    printf 'FIXED   %-52s\n' "$name"; flipped=$((flipped + 1))
  fi
  rm -f "$PROBE_CAPTURE"
  PROBE_CAPTURE=""
}

# ---------------------------------------------------------------------------
# M4 - the workflow_dispatch version input is validated with a line-oriented grep,
#      so a value containing a newline passes and injects extra lines into
#      $GITHUB_OUTPUT. Extracts the actual "Derive the release version" step body from
#      release.yml and runs it for real against the malicious input, so this probe tests
#      the workflow's own current logic and not a frozen copy of the old snippet.
# ---------------------------------------------------------------------------
probe_multiline_version_accepted() {
  local step out rc
  step=$(awk '
    /- name: Derive the release version/ { infield=0; instep=1 }
    instep && /run: \|/ { inrun=1; next }
    instep && !inrun && /^      - name:/ && !/Derive the release version/ { exit }
    inrun && /^      - name:/ { exit }
    inrun { print }
  ' "$WF")
  [ -n "$step" ] || return 0   # step vanished: cannot prove the fix, count as still weak
  out="$(mktemp)"
  GITHUB_EVENT_NAME=workflow_dispatch \
  INPUT_VERSION="$(printf '0.1.0\nmalicious=1')" \
  GITHUB_OUTPUT="$out" \
  bash -c "$step" >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  # Weak: the step exited 0 (accepted the input) and the injected extra line landed in
  # $GITHUB_OUTPUT. Fixed: the step rejected the multiline input (non-zero exit).
  if [ "$rc" -eq 0 ] && grep -q '^malicious=1$' "$out" 2>/dev/null; then
    rm -f "$out"; return 0
  fi
  rm -f "$out"; return 1
}

# ---------------------------------------------------------------------------
# M3 - the release job restores the setup-java maven cache, which covers
#      ~/.m2/wrapper/dists. mvnw execs an existing distribution without ever
#      re-checking distributionSha256Sum, so a poisoned cache entry runs
#      arbitrary Maven in the job that holds the signing key.
# ---------------------------------------------------------------------------
probe_release_job_restores_maven_cache() {
  local job
  job="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  publish:$/ {f=1} f' "$WF")"
  grep -q 'cache: maven' <<<"$job"
}

# Re-verification of 30aec6f. The probe that used to sit here,
# probe_mvnw_skips_checksum_for_existing_distribution, grepped the vendored `mvnw` for a
# checksum re-check inside its "found existing MAVEN_HOME, exec it" branch. That probe was
# refused: no Maven Wrapper script re-checks an unpacked distribution
# (distributionSha256Sum is only ever compared against the freshly downloaded zip), and the
# only marker that would satisfy the text match - a sidecar checksum file written at install
# time - is writable by exactly the attacker M3 describes, in the same write that plants the
# poisoned distribution. It would have turned the probe green without adding a control.
# Reclassified as NOT A FINDING: the property is not ours to hold.
#
# It is replaced, not dropped, by the probe below, which asserts the operational property
# that does close M3 and that this repository does control: the signing job restores no
# Maven cache, and it removes ~/.m2/wrapper/dists BEFORE the first mvnw invocation, so
# `[ -d "$MAVEN_HOME" ]` is always false there and mvnw always takes the download-and-verify
# path. Weak if the cache comes back, if the removal step disappears, or if it drifts below
# the first mvnw call.
probe_release_job_can_exec_an_unverified_maven_distribution() {
  local job first_mvnw rm_dists
  job=$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  publish:$/ {f=1} f' "$WF")
  grep -q 'cache: maven' <<<"$job" && return 0          # cache back: weak
  rm_dists=$(printf '%s\n' "$job" | grep -n 'rm -rf ~/\.m2/wrapper/dists' | head -1 | cut -d: -f1)
  [ -n "$rm_dists" ] || return 0                                    # no removal step: weak
  first_mvnw=$(printf '%s\n' "$job" | grep -n '\./mvnw' | head -1 | cut -d: -f1)
  [ -n "$first_mvnw" ] || return 0                                  # no mvnw at all: cannot prove it
  [ "$rm_dists" -lt "$first_mvnw" ] && return 1                     # removed before any mvnw: FIXED
  return 0
}

# ---------------------------------------------------------------------------
# M5 - any ref can release: no environment gate, no check that the tag's commit
#      is an ancestor of main, no requirement that the tag be signed.
# ---------------------------------------------------------------------------
probe_release_job_has_no_environment_gate() { ! grep -q '^\s*environment:' "$WF"; }
probe_release_does_not_check_tag_ancestry()  { ! grep -q 'merge-base' "$WF"; }
probe_release_does_not_verify_tag_signature() { ! grep -qE 'verify-tag|--verify-signatures' "$WF"; }

# ---------------------------------------------------------------------------
# M7 - Apache-2.0 is declared in the POM and there is no licence text anywhere.
# ---------------------------------------------------------------------------
probe_no_licence_file_in_the_repository() {
  # Not `git ls-files | grep -q`: this file runs under `set -o pipefail`, `grep -q` exits as
  # soon as it matches, and `git ls-files` then dies of SIGPIPE with status 141, which
  # pipefail promotes to the pipeline's status. The probe then reads WEAK on a tree that does
  # have a LICENSE - observed here, intermittently, on the first matching line of a long
  # listing. Materialise the listing first, so the exit status compared is grep's own.
  local tracked
  tracked="$(git ls-files)"
  ! grep -qiE '^(LICENSE|LICENCE|NOTICE)(\..*)?$' <<<"$tracked"
}

# ---------------------------------------------------------------------------
# L1 - the jars that are signed and uploaded come from the `clean deploy` build,
#      a third build that is never compared against the two the reproducibility
#      script checks.
# ---------------------------------------------------------------------------
probe_published_jars_are_never_checksum_compared() {
  ! grep -qE 'sha256|shasum' "$WF"
}

# ---------------------------------------------------------------------------
# L2 - concurrency is keyed on the ref, so a tag push and a workflow_dispatch for
#      the same version can release at the same time.
# ---------------------------------------------------------------------------
probe_concurrency_group_is_ref_scoped_not_version_scoped() {
  grep -q 'group: release-\${{ github.ref }}' "$WF"
}

# ---------------------------------------------------------------------------
# L3 - checkout leaves the GITHUB_TOKEN in .git/config for every Maven plugin
#      that runs in the release job.
# ---------------------------------------------------------------------------
probe_checkout_persists_credentials() { ! grep -q 'persist-credentials' "$WF"; }

# ---------------------------------------------------------------------------
# L4 - signed jars and .asc files of a FAILED release are uploaded anyway. Scoped to the
#      single step that uploads them: a whole-file grep for both strings anywhere false-
#      positives once the workflow legitimately has an unrelated `if: always()` step
#      (e.g. a `docker compose down` teardown) that has nothing to do with the evidence
#      upload.
# ---------------------------------------------------------------------------
probe_evidence_uploaded_even_when_the_release_failed() {
  awk '
    /^      - name:.*[Rr]elease evidence/ { instep=1; found=0 }
    instep && /if: always\(\)/ { found=1 }
    instep && /\*\.asc/ { has_asc=1 }
    instep && /^      - name:/ && !/[Rr]elease evidence/ { instep=0 }
    END { exit !(found && has_asc) }
  ' "$WF"
}

# ---------------------------------------------------------------------------
# I4 - `-X` makes Maven print env.CENTRAL_TOKEN and env.MAVEN_GPG_PASSPHRASE in
#      clear. The workflow does not use it today; nothing stops it being added.
# ---------------------------------------------------------------------------
probe_nothing_forbids_maven_debug_in_the_release_job() {
  ! grep -qE 'refusing.*(-X|--debug)|forbid.*debug' "$WF"
}

# ---------------------------------------------------------------------------
# M1 - licence gate: a dependency passes when ANY one of its declared licences is
#      on the allowlist, so `Apache-2.0 OR GPL-3.0` slips through. Runs a real build
#      against a synthetic dependency in a throwaway clone, through `verify` so the
#      tools/check-third-party-licences.sh denial pass (M1/M2 fix) actually runs -
#      the plugin's own allowlist stops at `package` and would report FIXED for the
#      wrong reason.
# ---------------------------------------------------------------------------
probe_licence_gate_accepts_a_dual_apache_or_gpl_dependency() {
  [ "${CIPHER_PROBE_MAVEN:-0}" = "1" ] || { PROBE_SKIP_REASON="this probe runs an inner Maven build; set CIPHER_PROBE_MAVEN=1 to run it"; return 0; }
  local work rc; work=$(mktemp -d)
  cat > "$work/syn.pom" <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>example.synthetic</groupId><artifactId>syn-dual</artifactId><version>1.0</version><packaging>jar</packaging>
<name>syn-dual</name><licenses>
  <license><name>Apache-2.0</name><url>http://example.invalid</url></license>
  <license><name>GPL-3.0</name><url>http://example.invalid</url></license>
</licenses></project>
EOF
  : > "$work/empty.txt"; (cd "$work" && jar cf syn.jar empty.txt)
  git clone -q --no-hardlinks . "$work/tree" || return 1
  (
    cd "$work/tree" || exit 1
    ./mvnw -B -q org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
      -Dfile="$work/syn.jar" -DpomFile="$work/syn.pom" >>"$PROBE_CAPTURE" 2>&1 || exit 1
    perl -0pi -e 's{<dependencies>}{<dependencies>\n    <dependency><groupId>example.synthetic</groupId><artifactId>syn-dual</artifactId><version>1.0</version></dependency>}' \
      stripe-einvoice-core/pom.xml
    ./mvnw -B -pl stripe-einvoice-core -am verify \
      -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true -Denforcer.skip=true >>"$PROBE_CAPTURE" 2>&1
  )
  rc=$?
  rm -rf "$work"
  # exit 0 from the build == the GPL-3.0 half was ignored == the weakness is present
  return "$rc"
}

# ===========================================================================
# Re-verification of 30aec6f: probes for the findings the fixes introduced.
# Every one of these asserts a weakness that is present at 30aec6f and must flip
# to FIXED when the matching N-finding from the security review's "Re-verification
# (30aec6f)" pass is closed.
# ===========================================================================

# Runs tools/check-third-party-licences.sh against ONE synthetic dependency line, in a
# throwaway tree, so the repository's own target/ files cannot mask the result.
# Echoes the script's exit code: 0 = the dependency passed the gate.
licence_verdict() { # licence_verdict <notices line>
  local work rc
  work=$(mktemp -d)
  mkdir -p "$work/tools" "$work/mod/target"
  cp tools/check-third-party-licences.sh "$work/tools/"
  printf '\nLists of 1 third-party dependencies.\n%s\n' "$1" > "$work/mod/target/THIRD-PARTY-NOTICES.txt"
  # N1: the script now takes the module build directory and packaging as arguments (it
  # checks one module's own notices, not a tree-wide `find`); jar is the packaging exercised
  # by every synthetic case below.
  "$work/tools/check-third-party-licences.sh" "$work/mod/target" jar >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$work"
  return "$rc"
}

# ---------------------------------------------------------------------------
# N2 - the denial pass matches hyphenated SPDX ids and the literal substring "gpl".
#      Real POMs declare licences in prose. "GNU General Public License v3" contains no
#      "gpl" at all, and "Mozilla Public License, Version 2.0" is not the string "mpl-2.0",
#      so both pass the all-of denial pass on their permissive half - the exact M1 hole,
#      respelled. The script's own comment claims it catches "GNU General Public License v3".
# ---------------------------------------------------------------------------
probe_denial_pass_misses_prose_licence_names() {
  licence_verdict '     (Apache-2.0) (GNU General Public License v3) x (c.s:syn:1.0 - no url defined)' &&
  licence_verdict '     (Apache-2.0) (Mozilla Public License, Version 2.0) x (c.s:syn:1.0 - no url defined)'
}

# ---------------------------------------------------------------------------
# N3 - the coordinate the denial pass matches is taken from the FIRST "(g:a:v - " group on
#      the line, and a dependency's own <name> is attacker-controlled text that lands on
#      that line ahead of its real coordinate. A dependency named
#      "evil (ch.qos.logback:logback-core:1.5.6 - http://x)" is read as logback-core, which
#      is on the coordinate allowlist, so every licence it declares is skipped. Second half:
#      a line whose version carries a character outside [\w.-] matches no coordinate at all
#      and is silently skipped rather than failing the build - a fail-open parser.
# ---------------------------------------------------------------------------
probe_denial_pass_coordinate_can_be_forged_by_the_dependency_name() {
  licence_verdict '     (GPL-3.0) evil (ch.qos.logback:logback-core:1.5.6 - http://x) (c.s:evil:1.0 - no url defined)' &&
  licence_verdict '     (GPL-3.0) x (c.s:syn:1.0+build - no url defined)'
}

# ---------------------------------------------------------------------------
# N10 - a licence line carrying an empty "()" token makes the script die on
#       "tok_list[@]: unbound variable" under set -u, abandoning every file and line it had
#       not reached yet. It exits 1, so it fails closed, but on a shell error rather than a
#       verdict, and it stops scanning.
# ---------------------------------------------------------------------------
probe_denial_pass_crashes_on_an_empty_licence_token() {
  local work out
  work=$(mktemp -d); mkdir -p "$work/tools" "$work/mod/target"
  cp tools/check-third-party-licences.sh "$work/tools/"
  printf '\nLists of 1 third-party dependencies.\n     () x (c.s:syn:1.0 - no url defined)\n' \
    > "$work/mod/target/THIRD-PARTY-NOTICES.txt"
  out=$("$work/tools/check-third-party-licences.sh" "$work/mod/target" jar 2>&1)
  rm -rf "$work"
  grep -q 'unbound variable' <<<"$out"
}

# ---------------------------------------------------------------------------
# N1 - the exec:exec denial pass is inherited by every module, so it runs on
#      stripe-einvoice-parent FIRST, before any module has produced a THIRD-PARTY-NOTICES.txt.
#      The script fails closed when it finds none, so `./mvnw verify` fails on any clean
#      checkout - which is what ci.yml and the release runner do. It only passes in a
#      working tree because the PREVIOUS build's notices files are still on disk when the
#      parent's verify runs (each module is cleaned when its own turn comes), which also
#      means the parent's pass is reading stale evidence.
# ---------------------------------------------------------------------------
probe_verify_fails_on_a_clean_checkout() {
  [ "${CIPHER_PROBE_MAVEN:-0}" = "1" ] || { PROBE_SKIP_REASON="this probe runs an inner Maven build; set CIPHER_PROBE_MAVEN=1 to run it"; return 0; }
  local work rc
  work=$(mktemp -d)
  git clone -q --no-hardlinks . "$work/tree" || { rm -rf "$work"; return 1; }
  ( cd "$work/tree" && ./mvnw -B verify \
      -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true >>"$PROBE_CAPTURE" 2>&1 )
  rc=$?
  rm -rf "$work"
  [ "$rc" -ne 0 ]   # non-zero on a clean checkout == the weakness is present
}

# ---------------------------------------------------------------------------
# Post-release finding (2026-09-10, run 34419387032) - scripts/verify-reproducible.sh always
# builds with -DskipTests, so its two builds never produce target/surefire-reports/. license-
# maven-plugin's add-third-party execution (addOutputDirectoryAsResourceDir defaults to true,
# includes "**/*.txt") registers outputDirectory (target/) as a live project resource at
# package phase; maven-source-plugin's jar-no-fork execution, bound to the same phase, reads
# project.getResources() at the moment IT runs and archives every target/*.txt it finds into
# the sources jar - THIRD-PARTY-NOTICES.txt deterministically, but also
# target/surefire-reports/*.txt whenever tests actually ran before package, which is never
# byte-identical run to run. A real `clean deploy -Prelease` (what the release job runs) DOES
# run tests, so its sources jars differed from what verify-reproducible.sh had already
# checksummed for the same tree: stripe-einvoice-core-0.1.0-sources.jar and
# stripe-einvoice-spring-boot-starter-0.1.0-sources.jar both failed "Confirm the deployed jars
# match the reproducibility check", while the main jars, javadoc jars and POMs matched.
#
# Behavioural probe: clones HEAD, runs the real scripts/verify-reproducible.sh to get its
# recorded sha256 for each sources jar, then runs a real `clean verify -Prelease
# -Dgpg.skip=true` (tests running, same outputTimestamp) and compares the sources jars it
# produces against those checksums. Weak while either sources jar differs.
# ---------------------------------------------------------------------------
probe_sources_jar_differs_from_a_build_that_actually_ran_tests() {
  [ "${CIPHER_PROBE_MAVEN:-0}" = "1" ] || { PROBE_SKIP_REASON="this probe runs an inner Maven build; set CIPHER_PROBE_MAVEN=1 to run it"; return 0; }
  local work rc
  work=$(mktemp -d)
  git clone -q --no-hardlinks . "$work/tree" || { rm -rf "$work"; return 1; }
  (
    cd "$work/tree" &&
    ts="$(scripts/git-commit-timestamp.sh)" &&
    REPRODUCIBLE_SHA_FILE="$PWD/repro-sha.txt" scripts/verify-reproducible.sh &&
    ./mvnw -B -q clean verify -Prelease -Dgpg.skip=true -Dproject.build.outputTimestamp="$ts" &&
    for jar in stripe-einvoice-core/target/*-sources.jar stripe-einvoice-spring-boot-starter/target/*-sources.jar; do
      name="$(basename "$jar")" &&
      actual="$(shasum -a 256 "$jar" | cut -d' ' -f1)" &&
      expected="$(awk -v n="$name" '$2==n{print $1}' repro-sha.txt)" &&
      [ -n "$expected" ] && [ "$actual" = "$expected" ] || exit 1
    done
  ) >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$work"
  [ "$rc" -ne 0 ]   # a mismatch, a missing jar, or a build failure: weakness present (WEAK)
}

# ---------------------------------------------------------------------------
# N4 - the L5 bundle assertion looks for the bundle in stripe-einvoice-sample/target/. The
#      plugin writes it to the TOP-LEVEL project's target/ (reproduced: a real
#      `deploy -Prelease` on a 0.1.0 checkout produced ./target/central-publishing/
#      central-bundle.zip, 45 files, no stripe-einvoice-sample). The step therefore always
#      fails with "was not created": the bundle is never inspected, and the two steps
#      after it - L1's checksum comparison and L4's evidence upload, both `if: success()` -
#      never run.
# ---------------------------------------------------------------------------
probe_bundle_assertion_points_at_the_wrong_path() {
  grep -q 'stripe-einvoice-sample/target/central-publishing/central-bundle.zip' "$WF"
}

# ---------------------------------------------------------------------------
# N5 - the tag-signature check is skipped, with a warning and exit 0, whenever
#      vars.RELEASE_SIGNING_KEY_ID is unset: a security control whose default is off.
#      Second half: even when it is set, `git verify-tag` only asserts that SOME key in the
#      keyring made a good signature, never that it was that key id (reproduced with two
#      throwaway keys: a tag signed by the second one verifies with exit 0). The binding
#      needs `git verify-tag --raw` and a VALIDSIG match on the full 40-hex fingerprint.
# ---------------------------------------------------------------------------
probe_tag_signature_check_is_optional_and_unbound() {
  local step
  step=$(sed -n '/- name: Verify the tag signature/,/^      - name:/p' "$WF")
  grep -q '::warning::' <<<"$step" && return 0        # skips when unset: weak
  grep -q 'VALIDSIG' <<<"$step" || return 0           # no key binding: weak
  return 1
}

# ---------------------------------------------------------------------------
# N6 - the I4 debug guard matches three literal flags. `--errors` (the long form of -e) is
#      not one of them, and neither is -Dorg.slf4j.simpleLogger.defaultLogLevel=debug in
#      MAVEN_OPTS, which turns on the identical DEBUG stream. Reproduced against this
#      repository: both a plain -X run and a MAVEN_OPTS slf4j-debug run print
#      "[DEBUG] env.CENTRAL_TOKEN: <value>" and "[DEBUG] env.MAVEN_GPG_PASSPHRASE: <value>"
#      in clear.
# ---------------------------------------------------------------------------
probe_debug_guard_misses_the_slf4j_log_level() {
  local guard
  guard=$(awk '/- name: Refuse Maven debug output in this job/{i=1} i&&/run: \|/{r=1;next} r&&/^      - name:/{exit} r{print}' "$WF")
  [ -n "$guard" ] || return 0
  MAVEN_ARGS='' MAVEN_OPTS='-Dorg.slf4j.simpleLogger.defaultLogLevel=debug' \
    bash -c "$guard" >>"$PROBE_CAPTURE" 2>&1 && return 0    # guard passed a debug setting: weak
  MAVEN_ARGS='' MAVEN_OPTS='--errors' bash -c "$guard" >>"$PROBE_CAPTURE" 2>&1 && return 0
  return 1
}

# ---------------------------------------------------------------------------
# N6 (run 34389977548, tag v0.1.0): the first version of the guard matched the bare words
#      `simpleLogger` and `defaultLogLevel` unconditionally, so it refused the workflow's
#      OWN job-level MAVEN_OPTS pin (`-Dorg.slf4j.simpleLogger.defaultLogLevel=info`) on
#      every run, before anything was uploaded - the guard's synthetic env cases above
#      never exercised the real job env, so this hole shipped past them. This probe reads
#      the workflow's actual declared MAVEN_ARGS/MAVEN_OPTS out of its own "env:" block
#      (not a hardcoded copy) and runs the real guard step body against exactly that env:
#      weak if the guard refuses its own declared pin.
# ---------------------------------------------------------------------------
probe_debug_guard_refuses_its_own_maven_opts_pin() {
  local guard maven_args maven_opts rc
  guard=$(awk '/- name: Refuse Maven debug output in this job/{i=1} i&&/run: \|/{r=1;next} r&&/^      - name:/{exit} r{print}' "$WF")
  [ -n "$guard" ] || return 0
  maven_args=$(awk -F'"' '/^  MAVEN_ARGS:/{print $2; exit}' "$WF")
  maven_opts=$(awk -F'"' '/^  MAVEN_OPTS:/{print $2; exit}' "$WF")
  [ -n "$maven_opts" ] || return 0   # env pin vanished: cannot prove the fix, count as weak
  MAVEN_ARGS="$maven_args" MAVEN_OPTS="$maven_opts" bash -c "$guard" >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  [ "$rc" -ne 0 ]   # guard refused the workflow's own declared MAVEN_ARGS/MAVEN_OPTS: weak
}

# ---------------------------------------------------------------------------
# N8 - the ancestry check is `if: github.event_name == 'push'`, so a workflow_dispatch run
#      on any branch skips it entirely and releases whatever is on that ref. Same set of
#      people can do either, so it is not a narrower privilege.
# ---------------------------------------------------------------------------
probe_ancestry_check_skips_the_dispatch_path() {
  local step
  step="$(sed -n '/- name: Verify the released commit is on main/,/^      - name:/p' "$WF")"
  grep -q "event_name == 'push'" <<<"$step"
}

# ---------------------------------------------------------------------------
# N7 - RETIRED. Verified FIXED (the release runbook's scratch-keyring sanity check now
#      runs in a subshell, so its `trap ... EXIT` fires on any step failure, not only on
#      shell exit). The release runbook that this probe read is maintained privately and
#      is out of scope for this public probe suite; it is no longer checked here.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# N11 - I1 from the first pass was never closed: the release profile still passes
#       --pinentry-mode loopback that maven-gpg-plugin 3.2.8 adds by itself whenever a
#       passphrase is supplied, and the comment still credits the flag as "required
#       because there is no tty".
# ---------------------------------------------------------------------------
probe_gpg_arguments_comment_still_credits_the_wrong_actor() {
  grep -q 'required because there is no tty' pom.xml
}

# ---------------------------------------------------------------------------
# N12 - the probe suite is not run by anything: `grep -rl 'cipher-probe' .github/` returns
#       zero files. This is the mechanical reason N6 reached a tagged release. Returns 0
#       (WEAK) unless some workflow under .github/workflows/ invokes this suite
#       unconditionally - a `run:` line naming the script, on a step with no
#       `continue-on-error: true` anywhere in that step.
# ---------------------------------------------------------------------------
probe_probe_suite_is_not_run_by_ci() {
  local wf
  for wf in .github/workflows/*.yml; do
    [ -f "$wf" ] || continue
    if _probe_suite_wired_unconditionally_in "$wf"; then
      return 1   # a workflow runs the suite unconditionally: FIXED
    fi
  done
  return 0   # no such job anywhere: WEAK
}

# N13: strip full-line comments first, same standard as the release guard's own static
# check (.github/workflows/release.yml, `grep -vE '^\s*#'`) - a single `#` used to be enough
# to disable the suite while the probe still reported it FIXED. Then split the file into
# per-JOB blocks (not per-step), because GitHub counts a job skipped by a job-level `if:` as
# satisfying a required status check: a step-scoped check alone cannot see that. A job block
# is the fix only when it invokes the script in a `run:` line AND carries no `if:` and no
# `continue-on-error:` anywhere in that block - job-level or step-level, `true` or any other
# value, since a skipped/soft-failed job is exactly as blind as a deleted one.
#
# N14: a substring/regex match on the `run:` line is still fooled by a trailing comment -
# `run: true # CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh` runs `true` and
# reports the probe suite FIXED. Same belt as the MAVEN_OPTS guard elsewhere in this file:
# stop pattern-matching, require the trimmed value of the `run:` line to be string-equal to
# the exact command. No YAML parser - this is still line-oriented - but equality instead of
# substring match refuses any decoration (comment, prefix, substitution) without needing one.
_probe_suite_wired_unconditionally_in() {
  local wf="$1"
  local want='CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh'
  grep -vE '^[[:space:]]*#' "$wf" | awk -v RS='\n  [A-Za-z0-9_.-]+:[[:space:]]*\n' -v want="$want" '
      {
        n = split($0, lines, "\n")
        matched = 0
        guarded = 0
        for (i = 1; i <= n; i++) {
          line = lines[i]
          if (match(line, /^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]*/)) {
            val = substr(line, RLENGTH + 1)
            gsub(/^[[:space:]]+/, "", val)
            gsub(/[[:space:]]+$/, "", val)
            if (val == want) { matched = 1 }
          }
          if (line ~ /^[[:space:]]*(-[[:space:]]+)?(if|continue-on-error):/) { guarded = 1 }
        }
        if (matched && !guarded) { found = 1 }
      }
      END { exit(found ? 0 : 1) }
    '
}

# ---------------------------------------------------------------------------
# N13 - the N12 probe above split ci.yml into per-STEP blocks and only refused
#       `continue-on-error: true` in the same step as the `run:` line. It reported FIXED for
#       a job-level `if: false`, a job-level `continue-on-error: true`, a step-level `if:
#       false`, and the `run:` line commented out with a single `#` - four ways to disable
#       the job while the probe that is supposed to guard it still passes. Applies each
#       mutation to a scratch copy of ci.yml and asserts probe_probe_suite_is_not_run_by_ci
#       reports WEAK (returns 0) for every one. WEAK before the fix above, FIXED after.
# ---------------------------------------------------------------------------
probe_suite_probe_accepts_a_disabled_probes_job() {
  local base d rc overall
  base="$(mktemp -d)"
  mkdir -p "$base/.github/workflows"
  cp .github/workflows/ci.yml "$base/.github/workflows/ci.yml"
  overall=0

  # a: job-level `if: false` on cipher-probes
  d="$base/a"; mkdir -p "$d/.github/workflows"
  sed 's/^  cipher-probes:$/  cipher-probes:\n    if: false/' \
    "$base/.github/workflows/ci.yml" > "$d/.github/workflows/ci.yml"

  # b: job-level `continue-on-error: true` on cipher-probes
  d="$base/b"; mkdir -p "$d/.github/workflows"
  sed 's/^  cipher-probes:$/  cipher-probes:\n    continue-on-error: true/' \
    "$base/.github/workflows/ci.yml" > "$d/.github/workflows/ci.yml"

  # c: step-level `if: false` on the step running the probe suite
  d="$base/c"; mkdir -p "$d/.github/workflows"
  sed 's/^\([[:space:]]*\)run: CIPHER_PROBE_MAVEN=1 tools\/cipher-probe-release-pipeline\.sh$/\1if: false\n&/' \
    "$base/.github/workflows/ci.yml" > "$d/.github/workflows/ci.yml"

  # d: the `run:` line commented out
  d="$base/d"; mkdir -p "$d/.github/workflows"
  sed 's/^\([[:space:]]*\)run: CIPHER_PROBE_MAVEN=1 tools\/cipher-probe-release-pipeline\.sh$/\1# run: CIPHER_PROBE_MAVEN=1 tools\/cipher-probe-release-pipeline.sh/' \
    "$base/.github/workflows/ci.yml" > "$d/.github/workflows/ci.yml"

  for d in a b c d; do
    ( cd "$base/$d" && probe_probe_suite_is_not_run_by_ci )
    rc=$?
    # rc 1 ("FIXED") on a disabled-job mutation means probe_probe_suite_is_not_run_by_ci was
    # fooled into believing the suite still runs unconditionally: the N13 weakness is present.
    [ "$rc" -eq 0 ] || overall=1
  done

  rm -rf "$base"
  [ "$overall" -ne 0 ]   # a mutation slipped past: weakness present (WEAK)
}

# ---------------------------------------------------------------------------
# N14 - the N13 fix still matched the `run:` line by substring, so a trailing comment after
#       the command (`run: true # CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh`)
#       runs `true` and still reads as wired. Also cover the `run: |` multi-line form, where
#       the command appears on a line of the block after another command has already run -
#       that must be refused too, since the `run:` line itself is just `|`. Applies both
#       mutations to a scratch copy of ci.yml and asserts probe_probe_suite_is_not_run_by_ci
#       reports WEAK (returns 0) for both. WEAK before the fix above, FIXED after.
# ---------------------------------------------------------------------------
probe_suite_probe_accepts_a_trailing_comment_disable() {
  local base d rc overall
  base="$(mktemp -d)"
  mkdir -p "$base/.github/workflows"
  cp .github/workflows/ci.yml "$base/.github/workflows/ci.yml"
  overall=0

  # a: the command hidden after a trailing comment on a `run: true` line
  d="$base/a"; mkdir -p "$d/.github/workflows"
  sed 's/^\([[:space:]]*\)run: CIPHER_PROBE_MAVEN=1 tools\/cipher-probe-release-pipeline\.sh$/\1run: true # CIPHER_PROBE_MAVEN=1 tools\/cipher-probe-release-pipeline.sh/' \
    "$base/.github/workflows/ci.yml" > "$d/.github/workflows/ci.yml"

  # b: the command hidden inside a `run: |` multi-line block, after another command
  d="$base/b"; mkdir -p "$d/.github/workflows"
  sed 's/^\([[:space:]]*\)run: CIPHER_PROBE_MAVEN=1 tools\/cipher-probe-release-pipeline\.sh$/\1run: |\n\1  true\n\1  CIPHER_PROBE_MAVEN=1 tools\/cipher-probe-release-pipeline.sh/' \
    "$base/.github/workflows/ci.yml" > "$d/.github/workflows/ci.yml"

  for d in a b; do
    ( cd "$base/$d" && probe_probe_suite_is_not_run_by_ci )
    rc=$?
    # rc 1 ("FIXED") on a disabled-job mutation means probe_probe_suite_is_not_run_by_ci was
    # fooled into believing the suite still runs unconditionally: the N14 weakness is present.
    [ "$rc" -eq 0 ] || overall=1
  done

  rm -rf "$base"
  [ "$overall" -ne 0 ]   # a mutation slipped past: weakness present (WEAK)
}


# ===========================================================================
# Final verification of 1da507e (F-ids). Everything below asserts a weakness
# introduced or left open by the licence switch and the N-fix pass.
# ===========================================================================

# Sources the helper functions of tools/check-third-party-licences.sh without running its
# argument dispatch (which would `exit 1` on no args and kill the probe run).
_source_licence_lib() {
  local lib
  lib="$(mktemp)"
  sed '/^if \[ "\${1:-}" = "--self-test" \]/,$d' tools/check-third-party-licences.sh >"$lib"
  # shellcheck disable=SC1090
  source "$lib"
  rm -f "$lib"
}

# ---------------------------------------------------------------------------
# F1 - <excludedGroups>com\.housedevinci</excludedGroups> is not anchored. license-maven-plugin
#      wraps a group pattern as "[^:]*(" + pattern + ")[^:]*:[^:]+" and matches it against
#      "groupId:artifactId" with Matcher.matches(), so the pattern is a SUBSTRING test on the
#      groupId: com.housedevinci-evil and xcom.housedevinci are excluded too, and an excluded
#      dependency is checked by neither gate (it never reaches includedLicenses and never
#      appears in THIRD-PARTY-NOTICES.txt for the denial pass to read).
#      Weak while the pattern in pom.xml matches a lookalike groupId under that wrapping.
# ---------------------------------------------------------------------------
probe_excluded_groups_pattern_also_excludes_lookalike_groups() {
  local pat
  pat=$(grep -o '<excludedGroups>[^<]*</excludedGroups>' pom.xml | sed 's/<[^>]*>//g')
  [ -n "$pat" ] || return 1
  perl -e '
    my ($pat, @ga) = @ARGV;
    my $re = qr/^[^:]*($pat)[^:]*:[^:]+$/;
    for my $ga (@ga) { exit 0 if $ga =~ $re; }   # a lookalike matched: still weak
    exit 1;
  ' "$pat" "com.housedevinci-evil:evil-dep" "xcom.housedevinci:evil-dep"
}

# ---------------------------------------------------------------------------
# F2 - N3 respelled through the dependency's URL. parse_notices collects "(...)"" groups with
#      [^()]*, which cannot span a nested pair, so a dependency whose own <url> contains
#      "(ch.qos.logback:logback-core:1.5.6 - x)" makes THAT the last paren group on the line.
#      The real coordinate is dropped, the forged one is on ALLOWED_COORDINATES, and the
#      dependency's GPL-3.0 half is never checked. The plugin's count header still matches,
#      so nothing else catches it.
#      Weak while the script reports a clean notices file for such a line.
# ---------------------------------------------------------------------------
probe_denial_pass_coordinate_can_be_forged_by_the_dependency_url() {
  local d rc
  d="$(mktemp -d)"
  cat >"$d/THIRD-PARTY-NOTICES.txt" <<'EOF'
Lists of 1 third-party dependencies.
     (Apache-2.0) (GPL-3.0) evil-url (example.synth:evil-b:1.0 - http://x/(ch.qos.logback:logback-core:1.5.6 - y))
EOF
  tools/check-third-party-licences.sh "$d" jar >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$d"
  [ "$rc" -eq 0 ]   # exit 0 on a GPL-3.0 line: still weak
}

# ---------------------------------------------------------------------------
# F3 - `VALIDSIG <fpr> ...` names the key that MADE the signature. On a key with a signing
#      subkey (git tag -s uses it even when -u names the primary) that is the SUBKEY
#      fingerprint; the PRIMARY fingerprint - the one the release runbook tells the
#      maintainer to put in RELEASE_SIGNING_KEY_ID, via `gpg --fingerprint` - is the LAST field of the same
#      line. Reproduced: with a primary+signing-subkey key the step's grep does not match and
#      the release is refused with "is not signed by <fpr>".
#      Weak while the grep anchors the fingerprint to field 1 instead of the primary-key field.
# ---------------------------------------------------------------------------
probe_tag_signature_binds_the_signing_subkey_not_the_primary_key() {
  local step
  step="$(sed -n '/- name: Verify the tag signature/,/^      - name:/p' "$WF")"
  grep -q 'VALIDSIG \${fingerprint} ' <<<"$step"
}

# ---------------------------------------------------------------------------
# F4 - the deny list carries the bare pattern "mpl" for Mozilla. Normalisation strips
#      punctuation, so "mpl" is a substring of "example", "simplified", "template",
#      "compliance": "Simplified BSD License" and any licence URL on example.com are DENIED.
#      Fail-closed, but it breaks the build on a permissive dependency the allowlist accepts.
#      Weak while a permissive name containing the letters m-p-l is denied.
# ---------------------------------------------------------------------------
probe_denial_list_denies_permissive_names_containing_mpl() {
  ( _source_licence_lib
    is_denied_token "Simplified BSD License" || is_denied_token "https://example.com/LICENSE" )
}

# ---------------------------------------------------------------------------
# F5 - the copyleft patterns are version-pinned where the id is not: "eupl12" misses
#      "EUPL v1.1" and "EUPL-1.1" (copyleft), "sspl10" misses a bare "SSPL" and "SSPL-2.0",
#      and OSL-3.0 / CPAL are absent entirely. Each only matters in the cumulative-dual case
#      the denial pass exists for (permissive half passes includedLicenses, copyleft half
#      must be caught here) - which is exactly N2's scenario.
#      Weak while any of them is allowed.
# ---------------------------------------------------------------------------
probe_denial_list_misses_eupl_1_1_bare_sspl_and_osl() {
  ( _source_licence_lib
    ! is_denied_token "EUPL v1.1" || ! is_denied_token "SSPL" || ! is_denied_token "OSL-3.0" )
}

# ---------------------------------------------------------------------------
# F6 - pom.xml's licence-allowlist comment still reads "Apache-2.0    the licence of this
#      project". Since 1da507e the project is FSL-1.1-ALv2. A stale Apache-2.0 claim about
#      our own code, in the file that is published to Maven Central, is the one place a
#      licensee would look to contradict LICENSE.
#      Weak while the comment survives.
# ---------------------------------------------------------------------------
probe_pom_comment_still_calls_apache_the_licence_of_this_project() {
  grep -q 'the licence of this project' pom.xml
}

# ---------------------------------------------------------------------------
# F7 - CONTRIBUTING.md states no inbound licence terms. Under Apache-2.0 the inbound grant
#      was conventional (ASF SS5); FSL-1.1-ALv2 has no contribution clause at all, and this
#      repository is about to be made public with a paid Pro edition beside it. Without a DCO
#      or an explicit grant, a merged outside PR arrives with no licence to relicense it.
#      Weak while the file says nothing about the licence of a contribution.
# ---------------------------------------------------------------------------
probe_contributing_states_no_inbound_licence_terms() {
  ! grep -qiE 'licen[cs]e|developer certificate of origin|\bDCO\b|copyright' CONTRIBUTING.md
}

# ---------------------------------------------------------------------------
# F8 - RETIRED. Verified FIXED (the release runbook's release-signing-key instructions
#      and Central Portal namespace organisation both now spell the licensor
#      "HouseDevinci", matching LICENSE/NOTICE/the POM). The release runbook that this
#      probe read is maintained privately and is out of scope for this public probe
#      suite; it is no longer checked here.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# F9 - SampleEndToEndTest asserts that the 4th tool call in the minute is BUDGET_EXCEEDED,
#      but BudgetLimit windows are epoch-aligned and TUMBLING (windowStart =
#      floorDiv(now, size) * size), so the four calls reset the counter whenever a window
#      boundary falls between them. Observed for real: one ./mvnw -B verify on a clean tree
#      failed at SampleEndToEndTest:155, three re-runs were green. The auto-configuration
#      already offers the seam - agentGuardClock is @ConditionalOnMissingBean(name = ...) -
#      so the test can supply a clock it controls.
#      Weak while the test defines no clock of its own.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# G1 - F2 respelled FORWARD. F2 closed the case where a URL's nested "(...)" splits the
#      coordinate group; the depth-aware scan fixed that. But the scan still trusts "the
#      LAST top-level group", and a dependency's own <url> is free text that can simply
#      CLOSE its own coordinate group and open a fresh, allowlisted one after it:
#        <url>http://x) (ch.qos.logback:logback-core:1.5.6 - http://y</url>
#      renders as two top-level groups, the last of which is on ALLOWED_COORDINATES, so the
#      dependency is skipped and its GPL-3.0 declaration is never checked. The plugin's own
#      count header still matches (one line in, one line parsed), so the count backstop that
#      catches a silently-dropped line does not fire here either.
#      Weak while the script reports a clean notices file for such a line.
# ---------------------------------------------------------------------------
# F9 (the sample end-to-end test taking its timestamps from the wall clock instead of the
# application's injected clock) has no probe here: this product's sample end-to-end test makes
# no assertion about a timestamp at all, so there is no wall-clock dependency for a probe to
# detect. The clock IS injected in this product (the starter builds the erasure service with a
# Clock bean) and the starter's own tests fix it; that is a different mechanism with its own
# tests, not this probe's target.

probe_denial_pass_coordinate_can_be_forged_by_a_trailing_group() {
  local d rc
  d="$(mktemp -d)"
  cat >"$d/THIRD-PARTY-NOTICES.txt" <<'EOF'
Lists of 1 third-party dependencies.
     (GPL-3.0) evil-trailing (example.synth:evil-c:1.0 - http://x) (ch.qos.logback:logback-core:1.5.6 - http://y)
EOF
  tools/check-third-party-licences.sh "$d" jar >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$d"
  [ "$rc" -eq 0 ]   # exit 0 on a GPL-3.0 line: still weak
}

# ---------------------------------------------------------------------------
# G2 - ci.yml's `dco` job decides a commit is a merge commit, and therefore exempt from the
#      sign-off requirement, by matching its SUBJECT against "Merge branch"* /
#      "Merge remote-tracking"*. A subject is free text chosen by the committer, so an
#      ORDINARY single-parent commit titled `Merge branch 'x' into y` is exempted from the
#      DCO check with no sign-off at all. Whether a commit is a merge is decided by its
#      parent count (%P), which the committer cannot forge.
#      Weak while the job branches on the subject instead of the parent count.
# ---------------------------------------------------------------------------
probe_dco_check_is_skipped_by_a_forged_merge_subject() {
  local w=.github/workflows/ci.yml
  [ -f "$w" ] || return 0
  grep -q '"Merge branch"\*' "$w"
}

# ---------------------------------------------------------------------------
# G3 - the parent-count exemption that closed G2 is broader than "a real merge". ANY
#      commit with two or more parents is exempt, and a merge commit's tree is not
#      constrained by its parents: an "evil merge" can carry content that exists in NO
#      parent. So an octopus merge (three parents), or a two-parent commit whose second
#      parent is an unrelated branch rather than the base, ships unsigned-off content
#      while the gate reports "all commits are signed off".
#
#      Behavioural probe: extracts the real dco step body from ci.yml and runs it against
#      a synthetic repository containing an octopus merge that adds a file present in none
#      of its three parents. Returns 0 (WEAK) while the gate passes that repository.
# ---------------------------------------------------------------------------
probe_dco_exempts_an_octopus_merge_carrying_unsigned_content() {
  local w=.github/workflows/ci.yml body repo rc
  [ -f "$w" ] || return 0
  body="$(awk '
    /name: Verify every commit in this pull request is signed off/ { instep=1 }
    instep && /run: \|/ { inrun=1; next }
    inrun && /^  [a-z-]+:$/ { exit }
    inrun { sub(/^          /, ""); print }
  ' "$w")"
  [ -n "$body" ] || return 0   # step vanished: cannot prove the fix, count as still weak

  repo="$(mktemp -d)"
  (
    cd "$repo" || exit 1
    git init -q -b main . && git config user.name t && git config user.email t@e.com
    echo v1 > base.txt && git add -A
    git commit -q -m "$(printf 'feat: base\n\nSigned-off-by: T <t@e.com>')"
    git checkout -q -b a && echo a > a.txt && git add -A
    git commit -q -m "$(printf 'feat: a\n\nSigned-off-by: T <t@e.com>')"
    git checkout -q main && git checkout -q -b b && echo b > b.txt && git add -A
    git commit -q -m "$(printf 'feat: b\n\nSigned-off-by: T <t@e.com>')"
    git checkout -q main
    git merge -q --no-commit --no-ff a b >>"$PROBE_CAPTURE" 2>&1 || true
    # content present in NO parent, and no Signed-off-by trailer anywhere
    echo BACKDOOR > evil.txt && git add -A
    git commit -q -m "Merge branches 'a' and 'b'"
  ) >>"$PROBE_CAPTURE" 2>&1 || { rm -rf "$repo"; return 0; }

  local base head
  base="$(git -C "$repo" rev-list --max-parents=0 HEAD)"
  head="$(git -C "$repo" rev-parse HEAD)"
  ( cd "$repo" && BASE_SHA="$base" HEAD_SHA="$head" \
      bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$repo"
  # exit 0 means the gate accepted an octopus merge carrying unsigned-off content: WEAK.
  [ "$rc" -eq 0 ]
}


# ---------------------------------------------------------------------------
# G4 - the G3 fix reads `auto="$(git merge-tree --write-tree "$1" "$2" 2>/dev/null | head -1)"`.
#      git merge-tree exits 1 when the merge it computes CONFLICTS. It still writes a tree,
#      so head -1 succeeds - but the step runs under `set -euo pipefail`, so pipefail hands
#      the pipeline git's 1, the assignment takes that status, and set -e kills the whole
#      step there: mid-loop, before the sign-off check, before any ::error:: annotation,
#      with 2>/dev/null hiding git's message. A legitimate, fully SIGNED-OFF back-merge
#      that resolved a conflict is rejected with no output at all.
#
#      Behavioural probe: extracts the real dco step body from ci.yml and runs it against a
#      synthetic repository whose back-merge resolved a real conflict and IS signed off.
#      That range is compliant and must pass. Returns 0 (WEAK) while the step exits
#      non-zero.
# ---------------------------------------------------------------------------
probe_dco_step_aborts_silently_on_a_conflicted_back_merge() {
  local w=.github/workflows/ci.yml body repo rc
  [ -f "$w" ] || return 0
  body="$(awk '
    /name: Verify every commit in this pull request is signed off/ { instep=1 }
    instep && /run: \|/ { inrun=1; next }
    inrun && /^  [a-z-]+:$/ { exit }
    inrun { sub(/^          /, ""); print }
  ' "$w")"
  [ -n "$body" ] || return 0   # step vanished: cannot prove the fix, count as still weak

  repo="$(mktemp -d)"
  (
    cd "$repo" || exit 1
    git init -q -b main . && git config user.name t && git config user.email t@e.com
    printf 'line\n' > c.txt && git add -A
    git commit -q -m "$(printf 'feat: base\n\nSigned-off-by: T <t@e.com>')"
    git checkout -q -b feature && printf 'feature-side\n' > c.txt && git add -A
    git commit -q -m "$(printf 'feat: f\n\nSigned-off-by: T <t@e.com>')"
    git checkout -q main && printf 'main-side\n' > c.txt && git add -A
    git commit -q -m "$(printf 'feat: base moves on\n\nSigned-off-by: T <t@e.com>')"
    git rev-parse HEAD > .base
    git checkout -q feature
    # both sides edited the same line: the automatic merge conflicts, git merge-tree exits 1
    git merge --no-commit --no-ff main >>"$PROBE_CAPTURE" 2>&1 || true
    printf 'resolved\n' > c.txt && git add c.txt
    git commit -q -m "$(printf "Merge branch 'main' into feature\n\nSigned-off-by: T <t@e.com>")"
  ) >>"$PROBE_CAPTURE" 2>&1 || { rm -rf "$repo"; return 0; }

  local base head
  base="$(cat "$repo/.base")"
  head="$(git -C "$repo" rev-parse HEAD)"
  ( cd "$repo" && BASE_SHA="$base" HEAD_SHA="$head" \
      bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$repo"
  # every commit in this range carries a Signed-off-by. Non-zero means the step aborted
  # on the conflicted merge-tree instead of checking them: WEAK.
  [ "$rc" -ne 0 ]
}


# ===========================================================================
# Design-stop build (2026-09-14): probes for the mechanisms the amended release-pipeline
# design adds. Same rule as every block above - each asserts a WEAKNESS, reads WEAK while
# the mechanism is missing or wrong, and flips to FIXED when it is built.
# ===========================================================================

CI=.github/workflows/ci.yml

# Extracts the body of a `run: |` block for a named step, from a named workflow file.
step_body() { # step_body <workflow> <step name>
  awk -v want="- name: $2" '
    index($0, want) { instep=1; next }
    instep && /run: \|/ { inrun=1; next }
    instep && !inrun && /^      - name:/ { exit }
    inrun && /^      - name:/ { exit }
    # A run block also ends at the next job header (two-space indent): the last step of a job
    # is followed by "  <next-job>:", not by another "- name:". Without this the extracted
    # body ran on into the next job and failed for a reason that has nothing to do with the
    # step under test.
    inrun && /^  [a-z][a-z0-9-]*:/ { exit }
    inrun { print }
  ' "$1"
}

# Prints the 1-based line number of a named step in a workflow, or nothing.
step_line() { # step_line <workflow> <step name>
  grep -n -- "- name: $2" "$1" | head -1 | cut -d: -f1
}

# ---------------------------------------------------------------------------
# The environment check must be its OWN job that the key-holding job depends on. As the
# first step of the publish job it is worthless: the environment's secrets are bound to a
# job when it is scheduled, so the refusal lands after the signing key and the Central
# token are already in the runner.
# ---------------------------------------------------------------------------
probe_preflight_is_not_its_own_job() {
  grep -q '^  preflight:$' "$WF" || return 0                      # no such job: weak
  local pub
  pub="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  publish:$/ {f=1} f' "$WF")"
  grep -q 'needs: preflight' <<<"$pub" || return 0                # publish does not wait: weak
  # The preflight job must hold nothing worth stealing: no environment, no secret.
  local pre
  pre="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  preflight:$/ {f=1} f' "$WF")"
  grep -q 'environment:' <<<"$pre" && return 0         # it holds the environment: weak
  grep -q 'secrets\.' <<<"$pre" && return 0            # it can see a secret: weak
  return 1
}

# Runs the real preflight step body against a stubbed `gh`, so the probe tests the
# workflow's own current logic rather than a frozen copy of it.
_preflight_verdict() { # _preflight_verdict <gh stub body>; echoes the step's exit code
  local body stub rc
  body="$(step_body "$WF" "Require a reviewer-gated \`release\` environment and a protected main")"
  [ -n "$body" ] || { echo 0; return; }      # step vanished: cannot prove the fix
  stub="$(mktemp -d)"
  printf '#!/usr/bin/env bash\n%s\n' "$1" > "$stub/gh"
  chmod +x "$stub/gh"
  ( PATH="$stub:$PATH" GITHUB_REPOSITORY=1of1Canopus/stripe-einvoice bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$stub"
  echo "$rc"
}

# ---------------------------------------------------------------------------
# "We could not read the gate" must be a refusal. A private repository on this plan answers
# nothing for environments and rulesets, and `environment: release` does not fail there -
# GitHub creates the environment implicitly, with no protection rules at all.
# ---------------------------------------------------------------------------
probe_preflight_passes_when_the_gates_cannot_be_read() {
  [ "$(_preflight_verdict 'exit 1')" -eq 0 ]
}

# ---------------------------------------------------------------------------
# An environment that exists but requires no reviewer is not a gate.
# ---------------------------------------------------------------------------
probe_preflight_accepts_an_environment_with_no_reviewer() {
  local stub_body
  stub_body='case "$*" in
  *environments/release*) echo "{\"name\":\"release\",\"protection_rules\":[{\"type\":\"wait_timer\",\"wait_timer\":0}]}" ;;
  *rules/branches/main*) echo "[{\"type\":\"required_status_checks\",\"parameters\":{\"required_status_checks\":[{\"context\":\"Build & test\"},{\"context\":\"DCO sign-off\"},{\"context\":\"Cipher probes\"},{\"context\":\"Reference guard\"},{\"context\":\"Sample app from a clean clone\"}]}}]" ;;
  *) exit 1 ;;
esac'
  [ "$(_preflight_verdict "$stub_body")" -eq 0 ]
}

# ---------------------------------------------------------------------------
# A ruleset that does not require every check is not a gate either. Same stub, with a
# reviewer present and one required check missing.
# ---------------------------------------------------------------------------
probe_preflight_accepts_main_without_the_required_checks() {
  local stub_body
  stub_body='case "$*" in
  *environments/release*) echo "{\"name\":\"release\",\"protection_rules\":[{\"type\":\"required_reviewers\",\"reviewers\":[{\"type\":\"User\"}]}]}" ;;
  *rules/branches/main*) echo "[{\"type\":\"required_status_checks\",\"parameters\":{\"required_status_checks\":[{\"context\":\"Build & test\"}]}}]" ;;
  *) exit 1 ;;
esac'
  [ "$(_preflight_verdict "$stub_body")" -eq 0 ]
}

# Control: with both gates real, preflight must PASS. A gate that refuses everything is not
# a gate, it is a broken workflow, and it would be "fixed" by deleting it.
probe_preflight_refuses_even_when_both_gates_are_real() {
  local stub_body
  stub_body='case "$*" in
  *environments/release*) echo "{\"name\":\"release\",\"protection_rules\":[{\"type\":\"required_reviewers\",\"reviewers\":[{\"type\":\"User\",\"reviewer\":{\"login\":\"someone\"}}]}]}" ;;
  *rules/branches/main*) echo "[{\"type\":\"required_status_checks\",\"parameters\":{\"required_status_checks\":[{\"context\":\"Build & test\"},{\"context\":\"DCO sign-off\"},{\"context\":\"Cipher probes\"},{\"context\":\"Reference guard\"},{\"context\":\"Sample app from a clean clone\"}]}}]" ;;
  *) exit 1 ;;
esac'
  [ "$(_preflight_verdict "$stub_body")" -ne 0 ]
}

# ---------------------------------------------------------------------------
# Release run 35899901341 - the preflight job runs two scripts out of the repository
# (tools/check-vulnerability-report.py, tools/install-scanner.sh) and has no checkout step,
# so the runner's working directory is empty and both exit 127. The whole release refused
# for a reason that had nothing to do with a gate.
#
# This probe does not grep for "checkout": it EXECUTES the scanner step's own body twice,
# once in an empty directory (no checkout) and once in a directory holding a real checkout
# of tools/ taken from git, and only then asks the workflow whether the job it belongs to
# gets one. Weak while the job has no checkout, or while the two runs are indistinguishable
# (a step that passes with no working tree proves nothing about the scanners).
# ---------------------------------------------------------------------------
probe_preflight_runs_repository_tools_without_a_checkout() {
  local body absent present rc_absent rc_present pre
  body="$(step_body "$WF" 'Both vulnerability scanners must be runnable, and the severity gate sound')"
  [ -n "$body" ] || return 0                            # step vanished: cannot prove it, weak

  # 1. No checkout: the runner's working directory is empty. This must fail.
  absent="$(mktemp -d)"
  mkdir -p "$absent/t"
  ( cd "$absent" && RUNNER_TEMP="$absent/t" bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1
  rc_absent=$?
  rm -rf "$absent"
  if [ "$rc_absent" -eq 0 ]; then
    PROBE_SKIP_REASON="the scanner step passed with no working tree at all, so it no longer proves the repository's own scanners run"
    return 0
  fi

  # 2. With a checkout of the same commit's tools/, the network-free half of the step body
  #    must succeed: the severity gate's self-test, and the installer being found and able
  #    to answer. This is the baseline the missing checkout destroys - executed, not assumed.
  present="$(mktemp -d)"
  ( git archive HEAD tools | tar -x -C "$present" \
      && cd "$present" \
      && tools/check-vulnerability-report.py --self-test \
      && tools/install-scanner.sh --print-versions ) >>"$PROBE_CAPTURE" 2>&1
  rc_present=$?
  rm -rf "$present"
  if [ "$rc_present" -ne 0 ]; then
    PROBE_SKIP_REASON="the same commands failed WITH a checkout too, so the difference this probe measures cannot be established"
    return 0
  fi

  # 3. The difference is proved. Does the preflight job actually get that checkout?
  pre="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  preflight:$/ {f=1} f' "$WF")"
  [ -n "$pre" ] || return 0                                        # no such job: weak
  grep -q 'uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1' <<<"$pre" || return 0
  grep -q 'persist-credentials: false' <<<"$pre" || return 0       # keeps the token out: else weak
  grep -q 'environment:' <<<"$pre" && return 0                     # must stay secret-free
  grep -q 'secrets\.' <<<"$pre" && return 0
  return 1
}

# ---------------------------------------------------------------------------
# D14-04 (PR 14 security review). The checkout added to `preflight` makes that job execute
# two scripts out of the tree the tag points at, and nothing in that job has checked yet
# that the tag is signed by the release key or that its commit is an ancestor of main -
# those two steps live in `publish`, downstream. So the first repository code the release
# workflow runs is code from a tree no gate has accepted.
#
# Executed, not grepped: the step body is run against a tampered copy of tools/, and the
# probe asserts the tampering executed. Then it asks whether the preflight job verifies
# anything before it gets there.
# ---------------------------------------------------------------------------
probe_preflight_executes_unverified_repository_code() {
  local body work marker pre
  body="$(step_body "$WF" 'Both vulnerability scanners must be runnable, and the severity gate sound')"
  [ -n "$body" ] || return 0                                   # step vanished: cannot prove it

  work="$(mktemp -d)"
  marker="$work/executed-attacker-code"
  git archive HEAD tools | tar -x -C "$work"
  printf '\n#!/bin/sh\ntouch "%s"\nexit 0\n' "$marker" > "$work/tools/install-scanner.sh"
  chmod +x "$work/tools/install-scanner.sh"
  mkdir -p "$work/t"
  ( cd "$work" && RUNNER_TEMP="$work/t" bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1 || true
  if [ ! -e "$marker" ]; then
    PROBE_SKIP_REASON="the step body did not execute tools/install-scanner.sh from the working tree, so this probe cannot show code from the checkout running"
    rm -rf "$work"
    return 0
  fi
  rm -rf "$work"

  # It runs tree code. Does the job establish first that the tree is the reviewed one?
  pre="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  preflight:$/ {f=1} f' "$WF")"
  [ -n "$pre" ] || return 0
  # Weakness present (return 0) when this job accepts the tree without establishing that the
  # tag is signed by the release key AND its commit is an ancestor of main.
  if grep -q 'merge-base --is-ancestor' <<<"$pre" && grep -q 'verify-tag' <<<"$pre"; then
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# D14-08: the ancestry gate moved into `preflight`, and `sample-smoke` was left behind. That
# job checks out the tag's tree and runs tools/run-sample-smoke.sh from it, in parallel with
# preflight, so a `v*` tag still gets repository code executed inside the release workflow
# with nothing established about the tree. Same exposure as D14-04 (no secret, read-only
# token) and the same checklist line unmet ("ancestry check on every path"). The step body is
# executed against a tampered script, as D14-04's is; the ordering half is read off the job
# graph, which is the only place ordering exists.
# ---------------------------------------------------------------------------
probe_sample_smoke_runs_unverified_tree_code() {
  local body work marker job
  body="$(step_body "$WF" 'Start PostgreSQL, build, run, time to first response')"
  [ -n "$body" ] || return 0                                   # step vanished: cannot prove it

  work="$(mktemp -d)"
  marker="$work/executed-attacker-code"
  git archive HEAD tools | tar -x -C "$work"
  printf '\n#!/bin/sh\ntouch "%s"\nexit 0\n' "$marker" > "$work/tools/run-sample-smoke.sh"
  chmod +x "$work/tools/run-sample-smoke.sh"
  ( cd "$work" && bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1 || true
  if [ ! -e "$marker" ]; then
    PROBE_SKIP_REASON="the step body did not execute tools/run-sample-smoke.sh from the working tree"
    rm -rf "$work"
    return 0
  fi
  rm -rf "$work"

  job="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  sample-smoke:$/ {f=1} f' "$WF")"
  [ -n "$job" ] || return 0
  # Fixed when the job cannot start before the gates: either it needs the gating job, or it
  # carries the two checks itself.
  if grep -qE 'needs:.*preflight' <<<"$job"; then
    return 1
  fi
  if grep -q 'merge-base --is-ancestor' <<<"$job" && grep -q 'verify-tag' <<<"$job"; then
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# A re-run of a green run, or a second tag for a version already cut, would sign and upload
# a second bundle for a version a human has already seen.
# ---------------------------------------------------------------------------
probe_release_does_not_refuse_a_replay() {
  local body
  body="$(step_body "$WF" "Refuse a replay of a version that already exists")"
  [ -n "$body" ] || return 0                                      # no such step: weak
  grep -q 'repo1.maven.org' <<<"$body" || return 0     # does not ask Maven Central: weak
  grep -q 'central.sonatype.com/api/v1/publisher' <<<"$body" || return 0
  return 1
}

probe_replay_check_runs_after_the_upload() {
  local replay deploy
  replay="$(step_line "$WF" 'Refuse a replay of a version that already exists')"
  deploy="$(step_line "$WF" 'Verify, licence check, sign, upload')"
  [ -n "$replay" ] && [ -n "$deploy" ] || return 0                # missing: weak
  [ "$replay" -lt "$deploy" ] && return 1                         # before the upload: FIXED
  return 0
}

# Runs the real replay-check step body against a stubbed `curl`, so the probe tests the
# workflow's own current logic rather than a frozen copy of it. Central and the Portal's
# published-check both answer clean (part 1 and 2 of the step must pass so the probe
# exercises part 3, the deployment list).
_replay_verdict() { # _replay_verdict <curl stub body>; echoes the step's exit code
  local body stub rc
  body="$(step_body "$WF" 'Refuse a replay of a version that already exists')"
  [ -n "$body" ] || { echo 0; return; }      # step vanished: cannot prove the fix
  stub="$(mktemp -d)"
  printf '#!/usr/bin/env bash\n%s\n' "$1" > "$stub/curl"
  chmod +x "$stub/curl"
  ( PATH="$stub:$PATH" CENTRAL_USERNAME=probe CENTRAL_TOKEN=probe VERSION=9.9.9-cipher-probe \
      bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$stub"
  echo "$rc"
}

# ---------------------------------------------------------------------------
# RP-4 - the replay check only ever asked for page 0 of the Portal deployment list. A
# VALIDATED-but-not-yet-published deployment for this version sitting behind a hundred
# newer ones on page 1 was invisible to it. Page 0 here answers with 100 unrelated
# deployments (a full page - the signal to keep paging); page 1 carries the clash. Weak if
# the step stops after page 0 and reports no clash; fixed if it pages on and refuses.
# ---------------------------------------------------------------------------
probe_replay_check_reads_only_the_first_page() {
  local stub_body rc
  stub_body='
case "$*" in
  *repo1.maven.org*) printf "404" ;;
  *api/v1/publisher/published*) echo "{\"published\":false}" ;;
  *api/v1/publisher/deployments*page=0*) jq -cn "{deployments: [range(100) | {deploymentName: (\"other-\" + (. | tostring)), deploymentState: \"PUBLISHED\"}]}" ;;
  *api/v1/publisher/deployments*page=1*) echo "{\"deployments\":[{\"deploymentName\":\"stripe-einvoice 9.9.9-cipher-probe (deadbeef)\",\"deploymentState\":\"VALIDATED\"}]}" ;;
  *api/v1/publisher/deployments*) echo "{\"deployments\":[]}" ;;
  *) exit 1 ;;
esac'
  rc="$(_replay_verdict "$stub_body")"
  [ "$rc" -eq 0 ]   # weak: the run 1 page over exhausted the clash and still passed
}

# ---------------------------------------------------------------------------
# The v0.1.0 tag run of 2026-09-15 refused itself because the clash filter matched any
# deployment whose name merely contained the version, and agent-guard 0.1.0 is PUBLISHED
# under the same Portal account. The filter must match this project's own deployment name
# and nothing else. Weak if a sibling project's published deployment of the same version
# makes the step refuse.
# ---------------------------------------------------------------------------
probe_replay_check_refuses_on_a_sibling_projects_deployment() {
  local stub_body rc
  stub_body='
case "$*" in
  *repo1.maven.org*) printf "404" ;;
  *api/v1/publisher/published*) echo "{\"published\":false}" ;;
  *api/v1/publisher/deployments*page=0*) echo "{\"deployments\":[{\"deploymentName\":\"agent-guard 9.9.9-cipher-probe\",\"deploymentState\":\"PUBLISHED\"},{\"deploymentName\":\"stripe-einvoice 9.9.9-cipher-probe-rc1 (cafe)\",\"deploymentState\":\"PUBLISHED\"}]}" ;;
  *api/v1/publisher/deployments*) echo "{\"deployments\":[]}" ;;
  *) exit 1 ;;
esac'
  rc="$(_replay_verdict "$stub_body")"
  [ "$rc" -ne 0 ]   # weak: a sibling project (or a different version with this prefix) made it refuse
}

# The sample smoke check must accept 404 from the open read endpoint, which is what a
# fresh database answers for the probe customer. Weak if only 200/401 are accepted. The
# check's body now lives in tools/run-sample-smoke.sh (one definition for the tag path and
# the pull-request path), so that is where this reads it from; weak, too, if the release
# job stopped calling it.
probe_sample_smoke_rejects_a_404_from_the_open_endpoint() {
  local body
  body="$(step_body "$WF" 'Start PostgreSQL, build, run, time to first response')"
  [ -n "$body" ] || return 0
  grep -q 'tools/run-sample-smoke.sh' <<<"$body" || return 0
  [ -x tools/run-sample-smoke.sh ] || return 0
  grep -q '"\$code" = "404"' tools/run-sample-smoke.sh && return 1
  return 0
}

# ---------------------------------------------------------------------------
# Release run 35899901341 - the smoke check was a step body inside release.yml and nowhere
# else, so the only thing that ever started the shipped sample application ran on a tag.
# Seven pull requests went green over a sample that could not start at all. The check has
# to run on every pull request, from the same script the release runs.
#
# Executed, not grepped: the probe runs the script with a working directory that has no
# stripe-einvoice-sample in it, and requires it to fail rather than report a green smoke
# run - a script that cannot tell "nothing to start" from "started fine" is worth nothing
# on either path. Then it asks whether ci.yml runs it on pull requests.
# ---------------------------------------------------------------------------
probe_sample_smoke_runs_on_the_tag_path_only() {
  local empty rc ci job
  [ -x tools/run-sample-smoke.sh ] || return 0                      # no shared script: weak

  empty="$(mktemp -d)"
  mkdir -p "$empty/tools"
  cp tools/run-sample-smoke.sh "$empty/tools/"
  ( cd "$empty" && SMOKE_BUDGET_SECONDS=1 tools/run-sample-smoke.sh ) >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$empty"
  if [ "$rc" -eq 0 ]; then
    PROBE_SKIP_REASON="the smoke script reported success from a tree with no sample in it, so a green run of it proves nothing"
    return 0
  fi

  ci=.github/workflows/ci.yml
  grep -q '^  sample-smoke:$' "$ci" || return 0                     # not a job on the PR path: weak
  job="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  sample-smoke:$/ {f=1} f' "$ci")"
  grep -q 'tools/run-sample-smoke.sh' <<<"$job" || return 0         # a second, drifting copy: weak
  grep -qE '^\s+pull_request:' "$ci" || return 0                    # ci does not run on PRs: weak
  return 1
}

# ---------------------------------------------------------------------------
# D14-05 (PR 14 security review). The smoke check now runs on every pull request, but the
# list of contexts main is required to carry - asserted by the preflight job, and the only
# machine-readable statement of what must be green before a merge - still names four checks
# and not this one. A check that can be red on a merged pull request is advice, and the
# defect this branch exists to fix is precisely a check that nothing had to pass.
# ---------------------------------------------------------------------------
probe_sample_smoke_is_not_a_required_check_on_main() {
  local ci job name
  ci=.github/workflows/ci.yml
  grep -q '^  sample-smoke:$' "$ci" || return 0                 # no such job: nothing to require
  job="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  sample-smoke:$/ {f=1} f' "$ci")"
  name="$(sed -n 's/^    name: //p' <<<"$job" | head -1)"
  [ -n "$name" ] || return 0
  grep -q "\"$name\"" "$WF" && return 1                         # preflight requires it: fixed
  return 0
}

# ---------------------------------------------------------------------------
# Two deployments of the same version are indistinguishable on the Portal unless the name
# says which commit each was built from.
# ---------------------------------------------------------------------------
probe_deployment_name_does_not_name_the_released_commit() {
  local body
  body="$(step_body "$WF" 'Verify, licence check, sign, upload')"
  grep -q 'deployment.name=' <<<"$body" || return 0
  grep -q 'GITHUB_SHA' <<<"$body" || return 0
  grep -q '<deploymentName>${deployment.name}</deploymentName>' pom.xml || return 0
  return 1
}

# ---------------------------------------------------------------------------
# The bytes that must be compared are the ones that went over the wire. The jars left in
# each module's target/ are a sibling of the bundle, not the bundle.
# ---------------------------------------------------------------------------
probe_bundle_comparison_reads_the_build_directory_not_the_bundle() {
  local body
  body="$(step_body "$WF" 'Confirm the uploaded bundle matches the reproducibility check')"
  [ -n "$body" ] || return 0                                      # no such step: weak
  grep -q 'central-bundle.zip' <<<"$body" || return 0  # not reading the bundle: weak
  grep -q 'unzip' <<<"$body" || return 0
  grep -qE '^\s*for jar in stripe-einvoice-core/target' <<<"$body" && return 0
  return 1
}

# A jar that was proved reproducible and then never uploaded is a silent hole in the other
# direction: the bundle would be missing an artifact and every entry it does contain matches.
probe_bundle_comparison_misses_a_jar_absent_from_the_bundle() {
  local body
  body="$(step_body "$WF" 'Confirm the uploaded bundle matches the reproducibility check')"
  [ -n "$body" ] || return 0
  grep -q 'MISSING FROM BUNDLE' <<<"$body" || return 0
  return 1
}

# ---------------------------------------------------------------------------
# RP-5 - poms in the bundle were never compared, only jars. Central consumes the pom
# bytes, and the window this whole comparison exists to close (proved build vs. what was
# uploaded) applies to them identically. Two places have to record and compare *.pom:
# scripts/verify-reproducible.sh's collect()/comparison, and the bundle-comparison step's
# find, which must walk *.pom alongside *.jar.
# ---------------------------------------------------------------------------
probe_reproducibility_check_never_records_poms() {
  grep -q '\*\.pom' scripts/verify-reproducible.sh || return 0
  return 1
}

probe_bundle_comparison_never_reads_poms() {
  local body
  body="$(step_body "$WF" 'Confirm the uploaded bundle matches the reproducibility check')"
  [ -n "$body" ] || return 0
  grep -qE "find \"\\\$work\" .*'\\*\\.pom'" <<<"$body" || return 0
  return 1
}

# Runs the real bundle-comparison step body for real, against a synthetic bundle and
# checksum file, so the probe tests the workflow's own current logic rather than a static
# grep of it (RP-6: the static probe above reads FIXED whether or not the lookup mechanism
# actually reaches the entries after the first unrecorded one). `${{ runner.temp }}` is a
# GitHub Actions context expression that resolves, at runtime, to the same path the
# `RUNNER_TEMP` environment variable already names - substituting the text and exporting
# that variable reproduces the real runner's environment closely enough to run this step
# body directly.
_bundle_comparison_run() { # _bundle_comparison_run <project dir with target/central-publishing/central-bundle.zip and reproducible-sha256.txt> <output file>
  local body project_dir="$1" out="$2" rc
  body="$(step_body "$WF" 'Confirm the uploaded bundle matches the reproducibility check')"
  [ -n "$body" ] || { echo 0 > "$out.rc"; return; }
  body="$(printf '%s' "$body" | sed 's/\${{ runner\.temp }}/$RUNNER_TEMP/g')"
  mkdir -p "$project_dir/.runner-temp"
  cp "$project_dir/reproducible-sha256.txt" "$project_dir/.runner-temp/reproducible-sha256.txt"
  : > "$project_dir/.runner-temp/summary.md"
  (
    cd "$project_dir" &&
    RUNNER_TEMP="$project_dir/.runner-temp" \
    GITHUB_STEP_SUMMARY="$project_dir/.runner-temp/summary.md" \
    bash -c "$body"
  ) >"$out" 2>&1
  echo $? > "$out.rc"
  # The NO RECORD/matches/MISMATCH verdicts land in the step summary table, not on
  # stdout/stderr - append it so a probe reading only "$out" sees them too.
  cat "$project_dir/.runner-temp/summary.md" >> "$out" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# RP-6 - an entry with no recorded checksum (the parent pom, before the collect() half of
# this fix) killed the step at the lookup (`grep -F ... | awk ...` under
# `set -euo pipefail`: grep's exit 1 was promoted by pipefail, `set -e` killed the
# assignment) with no `::error::` line at all, and `find | sort` puts
# `stripe-einvoice-parent/` before `stripe-einvoice-spring-boot-starter/`, so the starter's
# jars and pom were never reached. Reproduces the shape exactly: a bundle with an unrecorded
# parent pom (sorts first) and a starter jar that DOES have a record, deliberately wrong, so
# a MISMATCH row for it is only possible if the loop survived the unrecorded entry before
# it. Weak if the run dies with no NO RECORD line, or reaches no verdict for the entry after
# it; fixed if both are present.
# ---------------------------------------------------------------------------
probe_bundle_comparison_dies_on_an_unrecorded_entry() {
  command -v zip >>"$PROBE_CAPTURE" 2>&1 || { PROBE_SKIP_REASON="zip is not installed, so the synthetic jar this probe needs cannot be built"; return 0; }
  local work layout out rc_file rc weak=1
  work="$(mktemp -d)"
  layout="$work/bundle-src"
  mkdir -p "$layout/com/housedevinci/stripe-einvoice-parent/0.1.0" \
           "$layout/com/housedevinci/stripe-einvoice-spring-boot-starter/0.1.0" \
           "$work/target/central-publishing"
  echo "parent pom bytes, no record" \
    > "$layout/com/housedevinci/stripe-einvoice-parent/0.1.0/stripe-einvoice-parent-0.1.0.pom"
  echo "starter jar bytes" \
    > "$layout/com/housedevinci/stripe-einvoice-spring-boot-starter/0.1.0/stripe-einvoice-spring-boot-starter-0.1.0.jar"
  ( cd "$layout" && zip -q -r "$work/target/central-publishing/central-bundle.zip" . )
  # A record for the starter jar only - deliberately the wrong checksum - and none for the
  # parent pom, the exact asymmetry collect() produced before this fix.
  printf '%s  stripe-einvoice-spring-boot-starter-0.1.0.jar\n' \
    'deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef' \
    > "$work/reproducible-sha256.txt"

  out="$work/step-output.txt"
  _bundle_comparison_run "$work" "$out"
  rc="$(cat "$out.rc" 2>/dev/null || echo 0)"

  if grep -q 'NO RECORD' "$out" 2>/dev/null && grep -q 'MISMATCH' "$out" 2>/dev/null; then
    weak=0   # both the missing-record entry and the entry after it were reported: fixed
  fi
  rm -rf "$work"
  [ "$weak" -eq 1 ]
}

# ---------------------------------------------------------------------------
# The reference guard: one definition, its own job, the self-test run by CI, the jar loop
# covering the main jar.
# ---------------------------------------------------------------------------
probe_reference_guard_pattern_is_defined_more_than_once() {
  local files n
  files="$(git ls-files)"
  n="$(grep -lE "QUESTIONS\\\\\.md\|STATUS\\\\\.md" <<<"$files" >>"$PROBE_CAPTURE" 2>&1; grep -c . /dev/null)"
  # Count the tracked files that carry a pattern definition line.
  n=0
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    grep -q "docs\[/\]plans" "$f" 2>/dev/null && n=$((n + 1))
  done <<<"$files"
  [ "$n" -eq 1 ] && return 1     # exactly one definition: FIXED
  return 0
}

probe_reference_guard_is_not_its_own_job() {
  grep -q '^  reference-guard:$' "$CI" || return 0
  local job
  job="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  reference-guard:$/ {f=1} f' "$CI")"
  grep -q 'check-private-references.sh --tree' <<<"$job" || return 0
  grep -q 'check-private-references.sh --self-test' <<<"$job" || return 0
  return 1
}

probe_jar_guard_skips_the_main_jar() {
  grep -q 'for jar in "\$module"/target/\*\.jar' tools/check-private-references.sh || return 0
  return 1
}

# ---------------------------------------------------------------------------
# R-3 - the dead internal/** exemption protected nothing (this repository has no
# internal/ directory and by design never will), was wrong for the model (a top-level
# internal/ is world-readable in a public repository), and had no self-test case. Weak
# while the pathspec is still there.
# ---------------------------------------------------------------------------
probe_guard_still_exempts_internal_directory() {
  # Looks for the actual pathspec, not any mention of the word: a historical comment
  # explaining why the carve-out was removed is the right residue and must not re-trip
  # this probe (same rule as the DCO grandfather-exemption comment elsewhere).
  grep -q -- ":!internal/\*\*" tools/check-private-references.sh && return 0
  return 1
}

# ---------------------------------------------------------------------------
# The licence gate: the denial pass has to be wired into the build (without it the pom's
# includedLicenses string is the licence-name pattern N9 forbids as the only gate), and a
# carve-out must not be able to outlive the dependency it was written for.
# ---------------------------------------------------------------------------
probe_denial_pass_is_not_wired_into_the_build() {
  grep -q 'check-third-party-licences.sh' pom.xml || return 0
  grep -q '<artifactId>exec-maven-plugin</artifactId>' pom.xml || return 0
  return 1
}

probe_licence_carve_out_can_outlive_its_dependency() {
  grep -q -- '--check-unused' tools/check-third-party-licences.sh || return 0
  grep -q -- 'check-third-party-licences.sh --check-unused' "$CI" || return 0
  return 1
}

# ---------------------------------------------------------------------------
# The dry run's jar assertions are only worth anything against the jar a release produces,
# and a release runs the tests.
# ---------------------------------------------------------------------------
probe_release_dryrun_skips_the_tests() {
  local job
  job="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  release-dryrun:$/ {f=1} f' "$CI")"
  [ -n "$job" ] || return 0
  grep -q 'mvnw .*-DskipTests.*-Prelease' <<<"$job" && return 0
  return 1
}

probe_sources_jar_contents_are_never_asserted() {
  local job
  job="$(awk 'f && /^  [a-z][a-z-]*:$/ {exit} /^  release-dryrun:$/ {f=1} f' "$CI")"
  grep -q 'unzip -Z1' <<<"$job" || return 0
  return 1
}

# ---------------------------------------------------------------------------
# RP-3 - the sources-jar assertion allowlists extensions, not paths, so a build-output path
# riding in on an allowlisted extension (target/classes/META-INF/spring-configuration-
# metadata.json is exactly this shape, generated on every build) sweeps straight through.
# Extracts the real step body from ci.yml and runs it, for real, against a synthetic
# sources jar that is otherwise clean and carries exactly that one entry.
# ---------------------------------------------------------------------------
probe_sources_jar_assertion_is_extension_only_not_path_based() {
  command -v zip >>"$PROBE_CAPTURE" 2>&1 || { PROBE_SKIP_REASON="zip is not installed, so the synthetic jar this probe needs cannot be built"; return 0; }
  local body work rc
  body="$(step_body "$CI" 'Confirm each sources jar holds sources, resources and the licence texts only')"
  [ -n "$body" ] || return 0
  work="$(mktemp -d)"
  mkdir -p "$work/stripe-einvoice-core/target" "$work/stripe-einvoice-spring-boot-starter/target" \
           "$work/src/com/housedevinci/einvoice" "$work/src/META-INF" "$work/src/classes/META-INF"
  : > "$work/src/com/housedevinci/einvoice/Foo.java"
  : > "$work/src/META-INF/LICENSE"
  : > "$work/src/META-INF/NOTICE"
  : > "$work/src/classes/META-INF/spring-configuration-metadata.json"
  ( cd "$work/src" && zip -q -r "$work/stripe-einvoice-core/target/stripe-einvoice-core-0.1.0-sources.jar" . ) >>"$PROBE_CAPTURE" 2>&1
  cp "$work/stripe-einvoice-core/target/stripe-einvoice-core-0.1.0-sources.jar" \
     "$work/stripe-einvoice-spring-boot-starter/target/stripe-einvoice-spring-boot-starter-0.1.0-sources.jar"
  ( cd "$work" && bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$work"
  # Weak: the step passed (exit 0) despite the build-output path. Fixed: it refused (non-zero).
  [ "$rc" -eq 0 ]
}


# ===========================================================================
# PR 4 (release wiring). Same rule as every block above: each probe asserts a
# WEAKNESS and reads WEAK while that weakness is present.
# ===========================================================================

# ---------------------------------------------------------------------------
# S1 - the published artifacts carry no XSLT 2.0 processor, so a default install
#      reports NOT_EVALUATED on every document, refuses every issuance, and the library
#      only works for a host that read the README and added a dependency itself. Asks
#      Maven for the module's real runtime dependency set - the pom text is not the
#      question, the resolved scope is.
#      Weak while no XSLT 2.0 processor is on the runtime classpath of the core module.
# ---------------------------------------------------------------------------
probe_published_artifacts_ship_no_xslt2_processor() {
  [ "${CIPHER_PROBE_MAVEN:-0}" = "1" ] || { PROBE_SKIP_REASON="this probe runs an inner Maven build; set CIPHER_PROBE_MAVEN=1 to run it"; return 0; }
  local list
  ./mvnw -B dependency:list -DincludeScope=runtime -pl stripe-einvoice-core >>"$PROBE_CAPTURE" 2>&1 || return 0
  list="$(tail -n 400 "$PROBE_CAPTURE")"
  # Saxon-HE, or any other processor a maintainer substitutes, at runtime or compile scope.
  grep -qE 'Saxon-HE:jar:[^:]+:(runtime|compile)' <<<"$list" && return 1
  return 0
}

# ---------------------------------------------------------------------------
# S2 - the MPL carve-out is not scoped to one coordinate: a SECOND dependency under the
#      same licence walks through the gate on the back of the decision that was made about
#      Saxon-HE alone. Runs the real gate over a synthetic notices file.
#      Weak while a foreign coordinate declaring MPL-2.0 passes.
# ---------------------------------------------------------------------------
probe_mpl_carve_out_is_not_scoped_to_one_coordinate() {
  local d rc
  d="$(mktemp -d)"
  cat >"$d/THIRD-PARTY-NOTICES.txt" <<'NOTICES'
Lists of 1 third-party dependencies.
     (MPL-2.0) some-other-mpl-library (example.synth:other-mpl:1.0 - http://x)
NOTICES
  tools/check-third-party-licences.sh "$d" jar >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$d"
  [ "$rc" -eq 0 ]   # the gate accepted a second MPL dependency: weak
}

# ---------------------------------------------------------------------------
# S3 - the carve-out is scoped to the coordinate but not to the LICENCE, so the accepted
#      dependency becomes a hole for every denied licence: a future Saxon-HE release (or a
#      repository that serves a forged pom for that coordinate) declaring GPL-3.0 would pass.
#      Weak while Saxon-HE under a denied licence other than MPL passes.
# ---------------------------------------------------------------------------
probe_mpl_carve_out_admits_any_denied_licence_on_that_coordinate() {
  local d rc
  d="$(mktemp -d)"
  cat >"$d/THIRD-PARTY-NOTICES.txt" <<'NOTICES'
Lists of 1 third-party dependencies.
     (GPL-3.0) Saxon-HE (net.sf.saxon:Saxon-HE:13.0 - http://www.saxonica.com/)
NOTICES
  tools/check-third-party-licences.sh "$d" jar >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$d"
  [ "$rc" -eq 0 ]   # the gate accepted a GPL-3.0 declaration on the carved-out coordinate: weak
}

# ---------------------------------------------------------------------------
# S4 - the licence gate has a --self-test (the N2/F2/G1 table, and since this change the four
#      coordinate+licence cases) that nothing in .github/ runs. A self-test only a human runs
#      by hand is the N12 shape: it is evidence, not a gate.
#      Weak while no CI job invokes it.
# ---------------------------------------------------------------------------
probe_licence_gate_self_test_is_not_run_by_ci() {
  grep -rq 'check-third-party-licences.sh --self-test' .github/workflows/ && return 1
  return 0
}

# ---------------------------------------------------------------------------
# S5 - nothing stops a pull request that adds a dependency with a known HIGH or CRITICAL
#      vulnerability. Runs the REAL severity step from ci.yml against a synthetic report
#      carrying one HIGH finding (a CVSS 7.5 vector, computed from the vector exactly as
#      the gate does), rather than asserting that some job name appears in the YAML.
#      Weak while that step exits 0 on a HIGH finding.
# ---------------------------------------------------------------------------
probe_a_high_severity_dependency_passes_the_pull_request_gate() {
  local body work rc
  body="$(step_body "$CI" 'Fail on a known HIGH or CRITICAL vulnerability')"
  [ -n "$body" ] || return 0   # no such step: nothing gates a pull request
  work="$(mktemp -d)"
  cat >"$work/osv.json" <<'REPORT'
{"results":[{"source":{"path":"pom.xml"},"packages":[{
  "package":{"name":"org.example:vulnerable","version":"1.0","ecosystem":"Maven"},
  "vulnerabilities":[{"id":"GHSA-synthetic-high","severity":[
    {"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"}]}]}]}]}
REPORT
  RUNNER_TEMP="$work" bash -c "$body" >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$work"
  [ "$rc" -eq 0 ]   # the gate accepted a HIGH finding: weak
}

# ---------------------------------------------------------------------------
# S6 - a release is signed and uploaded without anyone having looked for known
#      vulnerabilities in what is being signed. Two halves, both required: the artifacts
#      must be scanned BEFORE the step that signs (position in the job, which is a property
#      of the file and can only be read there), and the decision the scan feeds must
#      actually refuse. The second half runs the shared gate against a synthetic Grype
#      report with a CRITICAL finding - the same binary the release step invokes.
#      Weak while either half is missing.
# ---------------------------------------------------------------------------
probe_the_release_signs_artifacts_it_never_scanned() {
  local scan_line sign_line work rc
  scan_line="$(grep -n 'name: Scan the artifacts this release is about to sign' "$WF" | head -1 | cut -d: -f1)"
  sign_line="$(grep -n 'name: Verify, licence check, sign, upload' "$WF" | head -1 | cut -d: -f1)"
  [ -n "$scan_line" ] && [ -n "$sign_line" ] || return 0        # no scan step at all: weak
  [ "$scan_line" -lt "$sign_line" ] || return 0                  # scan after the signature: weak
  work="$(mktemp -d)"
  cat >"$work/grype.json" <<'REPORT'
{"matches":[{"vulnerability":{"id":"CVE-synthetic-critical","severity":"Critical"},
             "artifact":{"name":"vulnerable","version":"1.0","type":"java-archive"}}],
 "source":{"type":"directory","target":"/probe/scan"},
 "descriptor":{"name":"grype","version":"0.118.0"}}
REPORT
  # The coverage document grype writes beside the report (-o cyclonedx-json); the gate needs
  # both, because a grype report does not say what it scanned. metadata.component.name and the
  # grype tool version match the report above so this probe still exercises the CRITICAL path
  # rather than tripping the D18-01 same-scan check.
  cat >"$work/sbom.json" <<'SBOM'
{"metadata":{"component":{"type":"file","name":"/probe/scan"},
             "tools":{"components":[{"name":"grype","version":"0.118.0"}]}},
 "components":[{"type":"library","name":"vulnerable","version":"1.0",
                "purl":"pkg:maven/org.example/vulnerable@1.0"},
               {"type":"file","name":"/probe/scan/vulnerable-1.0.jar",
                "hashes":[{"alg":"SHA-256","content":"aa"}]}]}
SBOM
  printf 'aa  vulnerable-1.0.jar\n' > "$work/staged.sha256"
  tools/check-vulnerability-report.py --format grype --report "$work/grype.json" \
    --sbom "$work/sbom.json" --min-artifacts 1 --expect-digests "$work/staged.sha256" \
    --fail-on high >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$work"
  [ "$rc" -eq 0 ]   # a CRITICAL finding did not stop the release: weak
}

# ---------------------------------------------------------------------------
# S7 - a scanner that could not run is indistinguishable from a clean scan. Three shapes of
#      "no answer" - an empty file, a truncated one, and a syntactically valid report with
#      no result section - each run through the real gate.
#      Weak while any of them exits 0.
# ---------------------------------------------------------------------------
probe_an_unreadable_scan_report_counts_as_clean() {
  local work weak=1
  work="$(mktemp -d)"
  : > "$work/empty.json"
  printf '{"results": [' > "$work/truncated.json"
  printf '{"scanner":"osv","version":"2"}' > "$work/no-results.json"
  # A syntactically perfect report of a scan that looked at nothing. Without --all-packages an
  # OSV report lists only VULNERABLE packages, so a resolution that silently produced nothing
  # renders exactly like a clean tree - the vacuous pass this whole script exists to refuse.
  printf '{"results":[{"source":{"path":"pom.xml"},"packages":[]}]}' > "$work/no-packages.json"
  local f
  for f in empty truncated no-results no-packages; do
    if tools/check-vulnerability-report.py --format osv --report "$work/$f.json" --fail-on high >>"$PROBE_CAPTURE" 2>&1; then
      echo "the gate accepted $f.json as a clean scan" >>"$PROBE_CAPTURE"
      weak=0
    fi
  done
  rm -rf "$work"
  [ "$weak" -eq 0 ]
}

# ---------------------------------------------------------------------------
# D4-01 - the same "scanned nothing renders as clean" defect S7 refuses for OSV, on the Grype
#      side: an empty "matches" array with an empty (or absent) "artifacts" array is a report of a
#      scan that looked at nothing, indistinguishable from a clean tree unless "artifacts" is
#      checked too.
#      Weak while the gate exits 0 on it.
# ---------------------------------------------------------------------------
a_grype_report_that_scanned_nothing_is_refused() {
  local work weak=1
  work="$(mktemp -d)"
  printf '{"matches":[],"source":{"type":"directory","target":"/nonexistent"}}' \
    > "$work/grype-empty.json"
  printf '{"components":[]}' > "$work/grype-empty-sbom.json"
  : > "$work/empty.sha256"
  # Two shapes of "this scan looked at nothing", both refused or the gate is weak:
  #  - a completed scan whose coverage document is empty;
  #  - a report handed over with NO coverage document at all, which is the shape release run
  #    36048639931 produced. Answering on coverage that was never supplied is the same defect
  #    seen from the other side.
  if tools/check-vulnerability-report.py --format grype --report "$work/grype-empty.json" \
       --sbom "$work/grype-empty-sbom.json" --min-artifacts 1 --expect-digests "$work/empty.sha256" \
       --fail-on high >>"$PROBE_CAPTURE" 2>&1; then
    echo "the gate accepted an empty coverage document as a clean scan" >>"$PROBE_CAPTURE"
    weak=0
  fi
  if tools/check-vulnerability-report.py --format grype --report "$work/grype-empty.json" \
       --fail-on high >>"$PROBE_CAPTURE" 2>&1; then
    echo "the gate answered without any coverage document at all" >>"$PROBE_CAPTURE"
    weak=0
  fi
  rm -rf "$work"
  [ "$weak" -eq 0 ]   # an empty scan reported as clean: weak
}

# ---------------------------------------------------------------------------
# S8 - a MEDIUM finding disappears: it does not fail the release (by decision) and nothing
#      writes it down either, so the release notes cannot carry a decision for it.
#      Weak while a MEDIUM finding leaves no line in the summary file.
# ---------------------------------------------------------------------------
probe_a_medium_finding_is_never_written_down() {
  local work rc
  work="$(mktemp -d)"
  cat >"$work/grype.json" <<'REPORT'
{"matches":[{"vulnerability":{"id":"CVE-synthetic-medium","severity":"Medium"},
             "artifact":{"name":"vulnerable","version":"1.0","type":"java-archive"}}],
 "source":{"type":"directory","target":"/probe/scan"},
 "descriptor":{"name":"grype","version":"0.118.0"}}
REPORT
  cat >"$work/sbom.json" <<'SBOM'
{"metadata":{"component":{"type":"file","name":"/probe/scan"},
             "tools":{"components":[{"name":"grype","version":"0.118.0"}]}},
 "components":[{"type":"library","name":"vulnerable","version":"1.0",
                "purl":"pkg:maven/org.example/vulnerable@1.0"},
               {"type":"file","name":"/probe/scan/vulnerable-1.0.jar",
                "hashes":[{"alg":"SHA-256","content":"aa"}]}]}
SBOM
  printf 'aa  vulnerable-1.0.jar\n' > "$work/staged.sha256"
  tools/check-vulnerability-report.py --format grype --report "$work/grype.json" \
    --sbom "$work/sbom.json" --min-artifacts 1 --expect-digests "$work/staged.sha256" \
    --fail-on high \
    --summary-file "$work/below.txt" >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  if [ "$rc" -ne 0 ]; then rm -rf "$work"; return 0; fi   # a MEDIUM failed the release: also wrong
  if [ -s "$work/below.txt" ]; then rm -rf "$work"; return 1; fi
  rm -rf "$work"
  return 0   # nothing written down: weak
}

# ---------------------------------------------------------------------------
# S9 - the weekly deep scan fails the week because no NVD key exists, so the scheduled run
#      is permanently red and the OSV findings beside it go unread; or it skips in silence,
#      which is worse. Runs the real step body with no key and requires BOTH: it does not
#      fail, and it says out loud that it skipped.
#      Weak while it fails, or while it is silent.
# ---------------------------------------------------------------------------
probe_the_weekly_deep_scan_is_red_or_silent_without_a_key() {
  local body work rc out
  body="$(step_body .github/workflows/security-scan.yml 'Say whether this deep scan can run at all')"
  [ -n "$body" ] || return 0
  work="$(mktemp -d)"
  : > "$work/out"; : > "$work/summary"
  ( cd "$work" && NVD_API_KEY="" GITHUB_OUTPUT="$work/out" GITHUB_STEP_SUMMARY="$work/summary" \
      bash -c "$body" ) >"$work/log" 2>&1
  rc=$?
  cat "$work/log" >>"$PROBE_CAPTURE" 2>/dev/null || true
  out="$(cat "$work/log" "$work/summary" 2>/dev/null)"
  if [ "$rc" -ne 0 ]; then rm -rf "$work"; return 0; fi                    # red for a known reason: weak
  grep -q 'available=false' "$work/out" 2>/dev/null || { rm -rf "$work"; return 0; }
  grep -qi 'skipped' <<<"$out" || { rm -rf "$work"; return 0; }            # silent skip: weak
  rm -rf "$work"
  return 1
}

# ---------------------------------------------------------------------------
# S10 - the pinned scanner binaries are fetched without being verified, so whatever the
#       release page serves on the day is what decides whether a release is safe to publish.
#       Runs the REAL installer against a local file:// URL (a copy of the script with the
#       download location redirected - the production script has no such switch, on purpose)
#       with the right checksum and then with a tampered payload.
#       Weak while a tampered payload installs.
# ---------------------------------------------------------------------------
probe_scanner_downloads_are_installed_without_verification() {
  local work script good_sha rc
  command -v curl >>"$PROBE_CAPTURE" 2>&1 || { PROBE_SKIP_REASON="curl is not installed, so the installer cannot be exercised"; return 0; }
  work="$(mktemp -d)"
  printf 'not really a scanner\n' > "$work/payload"
  if command -v sha256sum >/dev/null 2>&1; then good_sha="$(sha256sum "$work/payload" | cut -d" " -f1)"
  else good_sha="$(shasum -a 256 "$work/payload" | cut -d" " -f1)"; fi
  script="$work/install-scanner.sh"
  # Redirect the download to the local payload and make the "does it run" check a no-op:
  # this probe is about the checksum, not about whether a text file is a scanner.
  sed -e "s#url=\"https://github.com/google/osv-scanner/releases/download/[^\"]*\"#url=\"file://$work/payload\"#" \
      -e 's#"\$dest/osv-scanner" --version >/dev/null#true#' \
      tools/install-scanner.sh > "$script"
  chmod +x "$script"
  # 1. the honest case: the recorded checksum matches the payload, so it installs.
  sed -i.bak "s/^OSV_SHA256_linux_amd64=.*/OSV_SHA256_linux_amd64=\"$good_sha\"/;s/^OSV_SHA256_linux_arm64=.*/OSV_SHA256_linux_arm64=\"$good_sha\"/;s/^OSV_SHA256_darwin_arm64=.*/OSV_SHA256_darwin_arm64=\"$good_sha\"/" "$script"
  if ! "$script" osv-scanner "$work/bin" >>"$PROBE_CAPTURE" 2>&1; then
    echo "the installer refused a payload matching its own recorded checksum" >>"$PROBE_CAPTURE"
    rm -rf "$work"; return 0
  fi
  # 2. the tampered case: same recorded checksum, different bytes on the wire.
  printf 'tampered\n' > "$work/payload"
  "$script" osv-scanner "$work/bin" >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$work"
  [ "$rc" -eq 0 ]   # a tampered payload installed: weak
}

echo "the security review release-pipeline probes  (WEAK = finding still open)"
echo
probe probe_multiline_version_accepted                       "M4 newline in the version input passes validation"   probe_multiline_version_accepted
probe probe_release_job_restores_maven_cache                 "M3 release job restores the maven/wrapper cache"     probe_release_job_restores_maven_cache
probe probe_release_job_can_exec_unverified_maven_dist       "M3 the signing job can exec an unverified dist"      probe_release_job_can_exec_an_unverified_maven_distribution
probe probe_release_job_has_no_environment_gate              "M5 no environment / reviewer gate"                   probe_release_job_has_no_environment_gate
probe probe_release_does_not_check_tag_ancestry              "M5 a tag on any commit can release"                  probe_release_does_not_check_tag_ancestry
probe probe_release_does_not_verify_tag_signature            "M5 the tag is not required to be signed"             probe_release_does_not_verify_tag_signature
probe probe_no_licence_file_in_the_repository                "M7 Apache-2.0 declared, no LICENSE anywhere"         probe_no_licence_file_in_the_repository
probe probe_published_jars_are_never_checksum_compared       "L1 the deployed jars are never compared"             probe_published_jars_are_never_checksum_compared
probe probe_concurrency_group_is_ref_scoped                  "L2 concurrency keyed on the ref, not the version"    probe_concurrency_group_is_ref_scoped_not_version_scoped
probe probe_checkout_persists_credentials                    "L3 GITHUB_TOKEN left in .git/config"                 probe_checkout_persists_credentials
probe probe_evidence_uploaded_when_release_failed            "L4 signed jars of a failed release are uploaded"     probe_evidence_uploaded_even_when_the_release_failed
probe probe_nothing_forbids_maven_debug                      "I4 nothing stops -X being added to the release"      probe_nothing_forbids_maven_debug_in_the_release_job
probe probe_licence_gate_accepts_dual_apache_or_gpl          "M1 Apache-2.0 OR GPL-3.0 passes the gate"            probe_licence_gate_accepts_a_dual_apache_or_gpl_dependency
echo
probe probe_denial_pass_misses_prose_licence_names           "N2 prose GPL/MPL names pass the denial pass"         probe_denial_pass_misses_prose_licence_names
probe probe_denial_pass_coordinate_can_be_forged             "N3 the dependency <name> forges the coordinate"      probe_denial_pass_coordinate_can_be_forged_by_the_dependency_name
probe probe_denial_pass_crashes_on_empty_licence_token       "N10 empty () token kills the scan under set -u"      probe_denial_pass_crashes_on_an_empty_licence_token
probe probe_verify_fails_on_a_clean_checkout                 "N1 ./mvnw verify fails on a fresh clone"             probe_verify_fails_on_a_clean_checkout
probe probe_sources_jar_differs_from_a_test_run               "post-release: sources jar not reproducible with tests running" probe_sources_jar_differs_from_a_build_that_actually_ran_tests
probe probe_bundle_assertion_points_at_the_wrong_path        "N4 the L5 bundle path is not where it is written"    probe_bundle_assertion_points_at_the_wrong_path
probe probe_tag_signature_check_is_optional_and_unbound      "N5 tag signature check is off by default"            probe_tag_signature_check_is_optional_and_unbound
probe probe_debug_guard_misses_the_slf4j_log_level           "N6 --errors and slf4j debug walk past the guard"     probe_debug_guard_misses_the_slf4j_log_level
probe probe_debug_guard_refuses_its_own_maven_opts_pin       "N6 the guard refuses the workflow's own MAVEN_OPTS"  probe_debug_guard_refuses_its_own_maven_opts_pin
probe probe_ancestry_check_skips_the_dispatch_path           "N8 workflow_dispatch skips the ancestry check"       probe_ancestry_check_skips_the_dispatch_path
probe probe_gpg_arguments_comment_credits_the_wrong_actor    "N11 I1 was never closed"                             probe_gpg_arguments_comment_still_credits_the_wrong_actor
probe probe_probe_suite_is_not_run_by_ci                      "N12 nothing in .github/ runs this suite"             probe_probe_suite_is_not_run_by_ci
probe probe_suite_probe_accepts_a_disabled_probes_job          "N13 the N12 probe misses a disabled probes job"      probe_suite_probe_accepts_a_disabled_probes_job
probe probe_suite_probe_accepts_a_trailing_comment_disable      "N14 a trailing comment still hides a disabled job"   probe_suite_probe_accepts_a_trailing_comment_disable
echo
probe probe_excluded_groups_also_excludes_lookalike_groups   "F1 com.housedevinci-evil is excluded too"            probe_excluded_groups_pattern_also_excludes_lookalike_groups
probe probe_denial_pass_coordinate_forged_by_the_url         "F2 a URL with parens forges the coordinate"          probe_denial_pass_coordinate_can_be_forged_by_the_dependency_url
probe probe_tag_signature_binds_the_subkey_not_the_primary   "F3 VALIDSIG field 1 is the signing subkey"           probe_tag_signature_binds_the_signing_subkey_not_the_primary_key
probe probe_denial_list_denies_simplified_bsd                "F4 bare mpl denies example/simplified"               probe_denial_list_denies_permissive_names_containing_mpl
probe probe_denial_list_misses_eupl_1_1_and_bare_sspl        "F5 EUPL 1.1 / SSPL / OSL-3.0 are allowed"            probe_denial_list_misses_eupl_1_1_bare_sspl_and_osl
probe probe_pom_comment_calls_apache_the_project_licence     "F6 pom.xml still claims Apache-2.0 for us"           probe_pom_comment_still_calls_apache_the_licence_of_this_project
probe probe_contributing_has_no_inbound_licence_terms        "F7 no inbound licence terms for contributions"       probe_contributing_states_no_inbound_licence_terms

echo
probe probe_denial_pass_coordinate_forged_by_a_trailing_group "G1 a URL can append an allowlisted coordinate"      probe_denial_pass_coordinate_can_be_forged_by_a_trailing_group
probe probe_dco_check_skipped_by_a_forged_merge_subject      "G2 a forged 'Merge branch' subject skips the DCO"   probe_dco_check_is_skipped_by_a_forged_merge_subject
probe probe_dco_exempts_an_octopus_merge                    "G3 an octopus/evil merge skips the DCO entirely"    probe_dco_exempts_an_octopus_merge_carrying_unsigned_content
probe probe_dco_aborts_on_a_conflicted_merge                "G4 the dco step crashes with no output"             probe_dco_step_aborts_silently_on_a_conflicted_back_merge

echo
probe probe_preflight_is_not_its_own_job                     "preflight is not a job the key-holder needs"        probe_preflight_is_not_its_own_job
probe probe_preflight_passes_when_gates_unreadable           "an unreadable gate is treated as a pass"            probe_preflight_passes_when_the_gates_cannot_be_read
probe probe_preflight_accepts_no_required_reviewer           "an environment with no reviewer passes"             probe_preflight_accepts_an_environment_with_no_reviewer
probe probe_preflight_accepts_main_without_the_checks        "main without the required checks passes"                probe_preflight_accepts_main_without_the_required_checks
probe probe_preflight_refuses_when_both_gates_are_real       "preflight refuses even a correctly gated repo"      probe_preflight_refuses_even_when_both_gates_are_real
probe probe_preflight_has_no_checkout                        "run 35899901341: preflight runs tools it never checked out" probe_preflight_runs_repository_tools_without_a_checkout
probe probe_preflight_runs_unverified_tree_code              "D14-04 preflight runs tag-tree code before any ancestry check" probe_preflight_executes_unverified_repository_code
probe probe_sample_smoke_runs_unverified_tree_code           "D14-08 sample-smoke runs tag-tree code with no gate"        probe_sample_smoke_runs_unverified_tree_code
probe probe_release_does_not_refuse_a_replay                 "a re-run can upload a second bundle"                probe_release_does_not_refuse_a_replay
probe probe_replay_check_runs_after_the_upload               "the replay check lands after the upload"            probe_replay_check_runs_after_the_upload
probe probe_replay_check_first_page_only                     "RP-4 a clash on page 1 of deployments is missed"    probe_replay_check_reads_only_the_first_page
probe probe_deployment_name_omits_the_released_commit        "two deployments of a version look identical"        probe_deployment_name_does_not_name_the_released_commit
probe probe_replay_check_refuses_on_a_sibling_project     "v0.1.0 run: agent-guard 0.1.0 made the replay check refuse" probe_replay_check_refuses_on_a_sibling_projects_deployment
probe probe_sample_smoke_rejects_a_404                       "v0.1.0 run: open endpoint answers 404, smoke check waits"  probe_sample_smoke_rejects_a_404_from_the_open_endpoint
probe probe_sample_smoke_is_tag_only                        "run 35899901341: the shipped sample is only ever started on a tag" probe_sample_smoke_runs_on_the_tag_path_only
probe probe_sample_smoke_not_required_on_main                "D14-05 the sample smoke check is not a required check"      probe_sample_smoke_is_not_a_required_check_on_main
probe probe_bundle_comparison_reads_the_build_directory      "the comparison reads target/, not the bundle"       probe_bundle_comparison_reads_the_build_directory_not_the_bundle
probe probe_bundle_comparison_misses_an_absent_jar           "a jar missing from the bundle is not noticed"       probe_bundle_comparison_misses_a_jar_absent_from_the_bundle
probe probe_reproducibility_check_never_records_poms         "RP-5 verify-reproducible.sh never collects *.pom"   probe_reproducibility_check_never_records_poms
probe probe_bundle_comparison_never_reads_poms                "RP-5 the bundle comparison never reads *.pom"       probe_bundle_comparison_never_reads_poms
probe probe_bundle_comparison_dies_on_an_unrecorded_entry     "RP-6 an unrecorded entry silently kills the step"   probe_bundle_comparison_dies_on_an_unrecorded_entry
probe probe_reference_guard_pattern_defined_twice            "the guard pattern has more than one definition"     probe_reference_guard_pattern_is_defined_more_than_once
probe probe_reference_guard_is_not_its_own_job               "the guard is not a check of its own"                probe_reference_guard_is_not_its_own_job
probe probe_jar_guard_skips_the_main_jar                     "the jar scan skips the published main jar"          probe_jar_guard_skips_the_main_jar
probe probe_guard_exempts_internal_directory                 "R-3 the dead internal/** exemption is still there"  probe_guard_still_exempts_internal_directory
probe probe_denial_pass_is_not_wired_into_the_build          "the licence denial pass is not run by the build"    probe_denial_pass_is_not_wired_into_the_build
probe probe_licence_carve_out_can_outlive_its_dependency     "a dead licence carve-out can sit in the file"       probe_licence_carve_out_can_outlive_its_dependency
probe probe_release_dryrun_skips_the_tests                   "the dry run asserts on jars a release never makes"  probe_release_dryrun_skips_the_tests
probe probe_sources_jar_contents_are_never_asserted          "nothing asserts what a sources jar contains"        probe_sources_jar_contents_are_never_asserted
probe probe_sources_jar_allowlist_is_extension_only          "RP-3 the sources-jar check is extension-only"       probe_sources_jar_assertion_is_extension_only_not_path_based

echo
# ---------------------------------------------------------------------------
# D-SCAN-01 (release run 35922939487, third failed tag). The pre-sign Grype step staged the
# two published jars and then ran a BARE `dependency:copy-dependencies -pl core,starter` to
# stage their runtime classpath. The starter depends on com.housedevinci:stripe-einvoice-core
# at the release version, which at that moment exists in exactly one place - this reactor's
# target/ - and, because the replay refusal two steps earlier just proved it, nowhere else.
# With the job's empty local repository the only place Maven could look was Central, so the
# step died with "Could not find artifact com.housedevinci:stripe-einvoice-core:jar:0.1.0 in
# central" and the release stopped before signing. The shape is the one the checklist names:
# a step that exists only in release.yml and therefore had never executed for real.
#
# Executed, not grepped: this probe extracts the step body from release.yml, builds a
# synthetic reactor at a release version (versions:set, no -SNAPSHOT) with an EMPTY local
# Maven repository, stubs the Grype binary the step invokes with one that reports the
# artifacts it was handed, and runs the body. Weak while the body exits non-zero, or while
# the scan set it produced does not contain both published jars plus their resolved runtime
# dependencies.
# ---------------------------------------------------------------------------
# A synthetic reactor at a RELEASE version, built exactly the way the signing job builds it:
# versions:set to a version no repository has, an empty local Maven repository, and the real
# scripts/verify-reproducible.sh run over it so the checksum record the scan step binds to is
# the genuine article rather than a hand-written file. Built once, then handed to each probe
# as a private copy. The local repository is carried over the way it is in a release job
# (warm from the reproducibility check) - and it holds nothing of our own group, because
# those builds only ever `package`, which is precisely the condition that broke run
# 35922939487.
_SCAN_FIXTURE=""
_scan_step_fixture() {   # echoes a work dir holding tree/, record.txt, t/ (the runner temp)
  local base work
  if [ -z "$_SCAN_FIXTURE" ]; then
    base="$(mktemp -d)"
    if ! ( git clone -q --no-hardlinks . "$base/tree" &&
           cd "$base/tree" &&
           export MAVEN_OPTS="-Dmaven.repo.local=$base/m2" &&
           ./mvnw -B -q org.codehaus.mojo:versions-maven-plugin:2.21.0:set \
             -Dmaven.repo.local="$base/m2" -DnewVersion=99.99.99-probe \
             -DprocessAllModules=true -DgenerateBackupPoms=false &&
           REPRODUCIBLE_SHA_FILE="$base/record.txt" scripts/verify-reproducible.sh
         ) >>"$PROBE_CAPTURE" 2>&1; then
      rm -rf "$base"; return 1
    fi
    # The defect this fixture exists to reproduce needs our own coordinates to be absent.
    if [ -d "$base/m2/com/housedevinci" ]; then rm -rf "$base"; return 1; fi
    _SCAN_FIXTURE="$base"
  fi
  work="$(mktemp -d)"
  cp -a "$_SCAN_FIXTURE/tree" "$work/tree"
  mkdir -p "$work/t/bin"
  cp -a "$_SCAN_FIXTURE/m2" "$work/t/m2repo"
  cp "$_SCAN_FIXTURE/record.txt" "$work/t/reproducible-sha256.txt"
  # A stand-in for the pinned Grype: it asserts nothing about vulnerabilities, it records what
  # it was asked to scan, in the report shape the severity gate demands (an empty "artifacts"
  # list is refused by check-vulnerability-report.py, D4-01).
  cat > "$work/t/bin/grype" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
# Mimics the pinned grype's OUTPUT CONTRACT as of 0.118.0: one invocation, two documents -
# `-o json=<path>` (matches only; grype never lists what it scanned) and
# `-o cyclonedx-json=<path>` (the catalog: one library component per package, one file
# component with a SHA-256 per file walked). It asserts nothing about vulnerabilities.
# This stub can only ever agree with the gate about the shape, which is why D-SCAN-02 runs
# the real binary; see the header of the suite.
target=""; report=""; sbom=""
while [ $# -gt 0 ]; do
  case "$1" in
    dir:*) target="${1#dir:}" ;;
    -o) case "$2" in
          json=*) report="${2#json=}" ;;
          cyclonedx-json=*) sbom="${2#cyclonedx-json=}" ;;
        esac
        shift ;;
    --file) report="$2"; shift ;;
  esac
  shift
done
[ -n "$report" ] || { echo "stub grype: no json output path given" >&2; exit 1; }
[ -n "$sbom" ] || { echo "stub grype: no cyclonedx-json output path given" >&2; exit 1; }
printf '{"matches": [], "source": {"type": "directory", "target": "%s"}, "descriptor": {"name": "grype", "version": "stub"}}\n' "$target" > "$report"
{
  printf '{"metadata": {"component": {"type": "file", "name": "%s"}, "tools": {"components": [{"name": "grype", "version": "stub"}]}}, "components": [' "$target"
  sep=""
  while IFS= read -r jar; do
    printf '%s{"type": "library", "name": "%s", "version": "0", "purl": "pkg:maven/probe/%s@0"}' \
      "$sep" "$(basename "$jar")" "$(basename "$jar")"
    sep=", "
    printf '%s{"type": "file", "name": "%s", "hashes": [{"alg": "SHA-256", "content": "%s"}]}' \
      "$sep" "$jar" "$(shasum -a 256 "$jar" | cut -d' ' -f1)"
  done < <(find "$target" -name '*.jar' | sort)
  printf ']}\n'
} > "$sbom"
STUB
  chmod +x "$work/t/bin/grype"
  # D-SCAN-02: the same fixture, with the REAL pinned scanner in place of the stub. Installed
  # once into the shared fixture and copied per probe, so the download and its checksum check
  # happen at most once per suite run.
  if [ "${1:-}" = "real" ]; then
    if [ ! -x "$_SCAN_FIXTURE/realbin/grype" ]; then
      if ! tools/install-scanner.sh grype "$_SCAN_FIXTURE/realbin" >>"$PROBE_CAPTURE" 2>&1; then
        rm -rf "$work"; return 1
      fi
    fi
    cp "$_SCAN_FIXTURE/realbin/grype" "$work/t/bin/grype"
  fi
  printf '%s' "$work"
}

# Runs the real "Scan the artifacts this release is about to sign" body in a fixture copy and
# echoes its exit code. The runner substitutes ${{ ... }} before bash ever sees it; bash would
# read ${{ as a bad substitution.
_scan_step_exit() {   # _scan_step_exit <work dir>
  local work="$1" body rc ts
  body="$(step_body "$WF" 'Scan the artifacts this release is about to sign')"
  [ -n "$body" ] || { echo 127; return; }
  ts="$(cd "$work/tree" && scripts/git-commit-timestamp.sh)"
  body="${body//\$\{\{ runner.temp \}\}/\$RUNNER_TEMP}"
  body="${body//\$\{\{ steps.v.outputs.timestamp \}\}/\$PROBE_RELEASE_TIMESTAMP}"
  ( cd "$work/tree" && RUNNER_TEMP="$work/t" PROBE_RELEASE_TIMESTAMP="$ts" \
      GITHUB_STEP_SUMMARY="$work/summary.md" bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  echo "$rc"
}

# ---------------------------------------------------------------------------
# D-SCAN-01 (release run 35922939487, third failed tag). The pre-sign Grype step staged the
# published jars and then ran a BARE `dependency:copy-dependencies -pl core,starter` to stage
# their runtime classpath. The starter depends on com.housedevinci:stripe-einvoice-core at the
# release version, which at that moment exists in exactly one place - this reactor's target/ -
# and, because the replay refusal two steps earlier just proved it, nowhere else. With the
# job's local repository holding nothing of our group, the only place Maven could look was
# Central, so the step died with "Could not find artifact
# com.housedevinci:stripe-einvoice-core:jar:0.1.0 in central" and the release stopped before
# signing. The shape is the one the checklist names: a step that exists only in release.yml
# and had therefore never executed for real.
#
# Executed, not grepped: the step body is run against the synthetic reactor above. Weak while
# the body exits non-zero, or while the scan set it produced does not contain both published
# jars plus their resolved runtime dependencies.
# ---------------------------------------------------------------------------
probe_pre_sign_scan_cannot_resolve_the_reactors_own_modules() {
  [ "${CIPHER_PROBE_MAVEN:-0}" = "1" ] || { PROBE_SKIP_REASON="this probe builds a synthetic release reactor; set CIPHER_PROBE_MAVEN=1 to run it"; return 0; }
  local work rc scanned
  work="$(_scan_step_fixture)" || { PROBE_SKIP_REASON="the synthetic release reactor could not be built, so the step body was never exercised"; return 0; }
  rc="$(_scan_step_exit "$work")"
  if [ "$rc" -ne 0 ]; then rm -rf "$work"; return 0; fi      # the step failed: weakness present
  # What the scan CATALOGED lives in the CycloneDX document beside the report: a grype report
  # names only the vulnerable packages (release run 36048639931).
  scanned="$(cat "$work/t/grype-sbom.json" 2>/dev/null || true)"
  rm -rf "$work"
  grep -q 'stripe-einvoice-core-99.99.99-probe.jar' <<<"$scanned" || return 0
  grep -q 'stripe-einvoice-spring-boot-starter-99.99.99-probe.jar' <<<"$scanned" || return 0
  grep -q 'spring-' <<<"$scanned" || return 0        # the resolved runtime dependencies
  return 1
}

# ===========================================================================
# D-SCAN-02 / D-SCAN-03 block (release run 36048639931, FIFTH failed tag).
# Kept together and separate from the probes above on purpose: they are the answer to why
# D-SCAN-01 did not catch this one.
#
# What happened: the step staged 98 jars, grype exited 0, and the gate refused with "lists no
# artifacts at all". grype's JSON report has four top-level keys - matches, source, distro,
# descriptor - and names what it scanned in none of them. "artifacts" is SYFT's key. The scan
# had in fact cataloged every jar; the gate was reading a key the scanner does not write.
#
# Why no probe caught it: D-SCAN-01 stubs the scanner with a script that writes the report
# shape the gate expects. A stub cannot disagree with the gate about the format, so the one
# thing that was wrong was the one thing the probe could not see. Hence the rule now in the
# header of this file and in QUESTIONS: a probe for a scanner step runs the REAL pinned
# scanner.
#
# D-SCAN-02 runs the real step body with the real pinned grype over the real staged set.
#   Weak while the body exits non-zero, or while it does not report coverage of at least as
#   many packages as it staged jars.
# D-SCAN-03 runs the real pinned grype and the real gate over a synthetic scan set holding one
#   jar that declares a coordinate with a published CRITICAL advisory
#   (org.apache.commons:commons-text:1.9, CVE-2022-42889 / GHSA-599f-7c49-w659). No download:
#   the fixture is a jar carrying only META-INF/maven/.../pom.properties, which is what syft
#   reads. Weak unless the gate names that coordinate and refuses. The mutation is inside the
#   probe: the same scanner and gate over an EMPTY directory must refuse too (exit 2), so a
#   scan of nothing can never read as clean.
# ===========================================================================
probe_pre_sign_scan_reports_no_coverage_with_the_real_scanner() {
  [ "${CIPHER_PROBE_MAVEN:-0}" = "1" ] || { PROBE_SKIP_REASON="this probe builds a synthetic release reactor and runs the real pinned grype; set CIPHER_PROBE_MAVEN=1 to run it"; return 0; }
  local work rc staged covered
  work="$(_scan_step_fixture real)" || { PROBE_SKIP_REASON="the synthetic release reactor, or the pinned grype download, could not be prepared, so the step body was never exercised with a real scanner"; return 0; }
  rc="$(_scan_step_exit "$work")"
  if [ "$rc" -ne 0 ]; then rm -rf "$work"; return 0; fi      # the step failed: weakness present
  # The step prints "scanning N jar(s)" and the gate prints "M package(s) were scanned"; the
  # coverage claim is only worth something if M >= N.
  staged="$(grep -oE 'scanning [0-9]+ jar' "$PROBE_CAPTURE" | tail -1 | grep -oE '[0-9]+')"
  covered="$(grep -oE '[0-9]+ package\(s\) were scanned' "$PROBE_CAPTURE" | tail -1 | grep -oE '^[0-9]+')"
  rm -rf "$work"
  # Printed on every run, weak or not: the number is the evidence, and a FIXED probe prints
  # no capture.
  printf '        real pinned scanner: %s package(s) cataloged for %s staged jar(s)\n' \
    "${covered:-none}" "${staged:-none}"
  [ -n "$staged" ] && [ -n "$covered" ] || return 0          # no coverage reported at all: weak
  [ "$covered" -ge "$staged" ] || return 0                   # scanned fewer than it staged: weak
  return 1
}

probe_the_real_scanner_and_gate_miss_a_known_critical() {
  local work bin rc out
  work="$(mktemp -d)"
  if ! tools/install-scanner.sh grype "$work/bin" >>"$PROBE_CAPTURE" 2>&1; then
    rm -rf "$work"
    PROBE_SKIP_REASON="the pinned grype could not be installed, so the real scanner was never run"
    return 0
  fi
  bin="$work/bin/grype"
  mkdir -p "$work/build/META-INF/maven/org.apache.commons/commons-text" "$work/scan" "$work/empty"
  printf 'groupId=org.apache.commons\nartifactId=commons-text\nversion=1.9\n' \
    > "$work/build/META-INF/maven/org.apache.commons/commons-text/pom.properties"
  ( cd "$work/build" && jar cf "$work/scan/commons-text-1.9.jar" META-INF ) >>"$PROBE_CAPTURE" 2>&1 \
    || { rm -rf "$work"; PROBE_SKIP_REASON="no jar tool to build the vulnerable fixture"; return 0; }
  find "$work/scan" -name '*.jar' -print0 | xargs -0 shasum -a 256 > "$work/staged.sha256"
  if ! "$bin" "dir:$work/scan" -o json="$work/grype.json" -o cyclonedx-json="$work/sbom.json" >>"$PROBE_CAPTURE" 2>&1; then
    rm -rf "$work"
    PROBE_SKIP_REASON="the pinned grype could not complete a scan (no vulnerability database?)"
    return 0
  fi
  out="$(tools/check-vulnerability-report.py --format grype --report "$work/grype.json" \
          --sbom "$work/sbom.json" --min-artifacts 1 --expect-digests "$work/staged.sha256" \
          --fail-on high 2>&1)"
  rc=$?
  printf '%s\n' "$out" >>"$PROBE_CAPTURE"
  if [ "$rc" -ne 1 ]; then rm -rf "$work"; return 0; fi                    # did not refuse: weak
  grep -q 'commons-text@1.9' <<<"$out" || { rm -rf "$work"; return 0; }    # did not name it: weak
  # The mutation, run every time rather than remembered: the same scanner and the same gate
  # over a directory with nothing in it must refuse (exit 2), never report a clean scan.
  "$bin" "dir:$work/empty" -o json="$work/empty-grype.json" -o cyclonedx-json="$work/empty-sbom.json" >>"$PROBE_CAPTURE" 2>&1
  : > "$work/empty.sha256"
  tools/check-vulnerability-report.py --format grype --report "$work/empty-grype.json" \
    --sbom "$work/empty-sbom.json" --min-artifacts 1 --expect-digests "$work/empty.sha256" \
    --fail-on high >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$work"
  [ "$rc" -eq 2 ] || return 0      # a scan of nothing was not refused: weak
  return 1
}

# ---------------------------------------------------------------------------
# D15-01 / D15-02 (security review of this branch). The first version of the identity check
# compared the jar in the scan set against the jar in target/ - the same directory the step's
# own inner build writes to, so the reference was the file the step had just overwritten, and
# deleting the whole check changed no probe's verdict. The reference must be the checksum
# record the reproducibility check wrote, which is also what the post-deploy step compares the
# uploaded bundle against.
#
# Executed: the fixture's core jar is replaced in target/ AFTER the record was written, which
# is exactly the state a rewritten target/, or a same-version artifact arriving from a
# repository, would leave behind. Weak unless the step body refuses.
# ---------------------------------------------------------------------------
probe_pre_sign_scan_accepts_a_jar_that_is_not_the_recorded_build() {
  [ "${CIPHER_PROBE_MAVEN:-0}" = "1" ] || { PROBE_SKIP_REASON="this probe builds a synthetic release reactor; set CIPHER_PROBE_MAVEN=1 to run it"; return 0; }
  local work rc jar
  work="$(_scan_step_fixture)" || { PROBE_SKIP_REASON="the synthetic release reactor could not be built, so the step body was never exercised"; return 0; }
  jar="$work/tree/stripe-einvoice-core/target/stripe-einvoice-core-99.99.99-probe.jar"
  if [ ! -f "$jar" ]; then rm -rf "$work"; PROBE_SKIP_REASON="the fixture produced no core jar to tamper with"; return 0; fi
  # Appended, not replaced: the file stays a readable jar and stays newer than its inputs, so
  # the inner build leaves it alone and the scan set really does carry the tampered bytes.
  printf 'not the recorded build' >> "$jar"
  rc="$(_scan_step_exit "$work")"
  rm -rf "$work"
  [ "$rc" -eq 0 ]    # accepted a jar that is not the recorded artifact: weakness present
}

# ---------------------------------------------------------------------------
# D15-03 (same review). `[ -f "$resolved" ] || continue` made an absent jar a skipped
# iteration, and the only other guard - `count -gt 0` - is satisfied by the 88 dependency
# jars on their own, so a scan set that had silently stopped containing our own artifacts read
# as scanned and clean. The check must be over every recorded jar of ours, both modules, with
# absence a refusal.
#
# Executed: a jar the reproducibility check recorded is one the scan set cannot produce. Weak
# unless the step body refuses.
# ---------------------------------------------------------------------------
probe_pre_sign_scan_accepts_a_published_jar_missing_from_the_scan_set() {
  [ "${CIPHER_PROBE_MAVEN:-0}" = "1" ] || { PROBE_SKIP_REASON="this probe builds a synthetic release reactor; set CIPHER_PROBE_MAVEN=1 to run it"; return 0; }
  local work rc
  work="$(_scan_step_fixture)" || { PROBE_SKIP_REASON="the synthetic release reactor could not be built, so the step body was never exercised"; return 0; }
  printf '%s  %s\n' "0000000000000000000000000000000000000000000000000000000000000000" \
    "stripe-einvoice-core-99.99.99-probe-shaded.jar" >> "$work/t/reproducible-sha256.txt"
  rc="$(_scan_step_exit "$work")"
  rm -rf "$work"
  [ "$rc" -eq 0 ]    # a recorded jar absent from the scan set passed: weakness present
}

# ===========================================================================
# D18 block (security review of fix/release-scan-sees-jars, e7fda4f). PR 18 bound the coverage
# documents to the staged bytes and left the VERDICT document bound to nothing.
#
# D18-01: the grype JSON report of one scan, paired with the CycloneDX document of a
#   different scan, passes every coverage check while answering with matches nobody computed
#   over the staged set. Both documents name their run (report: source.target; CycloneDX:
#   metadata.component.name) and their tool version; the gate must refuse a mismatch.
# D18-02: --sbom is mandatory for --format grype; --expect-digests (the control that binds
#   the scan to the bytes) and --min-artifacts (the floor) must be too, with no default of 1.
# D18-03: the job-summary grep|sed pipeline under `set -euo pipefail` dies on a miss, killing
#   the step after the gate already passed. Checklist RP-6: a lookup miss says "no record" and
#   runs to the end.
# ===========================================================================
probe_verdict_document_accepted_from_a_different_scan() {
  local work rc
  work="$(mktemp -d)"
  cat >"$work/a-report.json" <<'J'
{"matches":[],"source":{"type":"directory","target":"/tmp/somewhere-else"},"descriptor":{"name":"grype","version":"0.118.0"}}
J
  cat >"$work/b-sbom.json" <<'J'
{"bomFormat":"CycloneDX","metadata":{"component":{"type":"file","name":"/tmp/release-scan"},
 "tools":{"components":[{"name":"grype","version":"0.118.0"}]}},
 "components":[{"type":"library","name":"commons-text","version":"1.9",
                "purl":"pkg:maven/org.apache.commons/commons-text@1.9"},
               {"type":"file","name":"/tmp/release-scan/commons-text-1.9.jar",
                "hashes":[{"alg":"SHA-256","content":"11"}]}]}
J
  printf '11  commons-text-1.9.jar\n' > "$work/staged.sha256"
  tools/check-vulnerability-report.py --format grype --report "$work/a-report.json" \
    --sbom "$work/b-sbom.json" --min-artifacts 1 --expect-digests "$work/staged.sha256" \
    --fail-on high >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$work"
  [ "$rc" -eq 0 ]   # accepted a report and an SBOM from two different scans: weak
}

probe_grype_verdict_without_a_digest_binding() {
  local work rc
  work="$(mktemp -d)"
  cat >"$work/report.json" <<'J'
{"matches":[],"source":{"type":"directory","target":"/tmp/release-scan"}}
J
  cat >"$work/sbom-one.json" <<'J'
{"components":[{"type":"library","name":"whatever","version":"1","purl":"pkg:maven/x/whatever@1"}]}
J
  tools/check-vulnerability-report.py --format grype --report "$work/report.json" \
    --sbom "$work/sbom-one.json" --fail-on high >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  rm -rf "$work"
  [ "$rc" -eq 0 ]   # --sbom alone, no --min-artifacts, no --expect-digests, and a pass: weak
}

probe_summary_block_dies_on_a_missing_coverage_line() {
  local line snippet work rc reached
  line="$(grep -nE "were scanned\|matched by digest" "$WF" | head -1 | cut -d: -f1)"
  [ -n "$line" ] || return 0   # no such line to extract at all: cannot prove it is safe
  snippet="$(sed -n "${line}p" "$WF")"
  work="$(mktemp -d)"
  # A gate transcript with no coverage line at all - the shape a refusal earlier in the step
  # (or a stale/empty file) would leave behind.
  printf 'vulnerability gate (grype report, threshold HIGH): 0 finding(s)\n' > "$work/vulnscan-gate.txt"
  RUNNER_TEMP="$work" bash -c "set -euo pipefail
    { echo START; $snippet; echo TAIL-REACHED; } >> \"$work/summary.txt\"" \
    >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  grep -q TAIL-REACHED "$work/summary.txt" 2>/dev/null; reached=$?
  rm -rf "$work"
  [ "$rc" -ne 0 ] && [ "$reached" -ne 0 ]   # the step died and the tail never printed: weak
}

# --- end of the D18 block ------------------------------------------------------------------

probe probe_artifacts_ship_no_xslt2_processor                "S1 a default install can validate nothing"          probe_published_artifacts_ship_no_xslt2_processor
probe probe_mpl_carve_out_is_not_coordinate_scoped           "S2 a second MPL dependency passes the gate"         probe_mpl_carve_out_is_not_scoped_to_one_coordinate
probe probe_mpl_carve_out_admits_any_licence_on_saxon        "S3 GPL on the carved-out coordinate passes"         probe_mpl_carve_out_admits_any_denied_licence_on_that_coordinate
probe probe_licence_gate_self_test_is_not_run_by_ci          "S4 nothing in CI runs the licence gate self-test"    probe_licence_gate_self_test_is_not_run_by_ci
probe probe_high_severity_passes_the_pull_request_gate       "S5 a HIGH advisory does not fail a pull request"    probe_a_high_severity_dependency_passes_the_pull_request_gate
probe probe_release_signs_artifacts_it_never_scanned         "S6 nothing scans what the release signs"            probe_the_release_signs_artifacts_it_never_scanned
probe probe_unreadable_scan_report_counts_as_clean           "S7 a scan that did not run reads as clean"          probe_an_unreadable_scan_report_counts_as_clean
probe a_grype_report_that_scanned_nothing_is_refused         "D4-01 an empty Grype scan reads as clean"           a_grype_report_that_scanned_nothing_is_refused
probe probe_medium_finding_is_never_written_down             "S8 a MEDIUM finding leaves no record"               probe_a_medium_finding_is_never_written_down
probe probe_weekly_deep_scan_red_or_silent_without_a_key     "S9 the weekly run is red, or skips in silence"      probe_the_weekly_deep_scan_is_red_or_silent_without_a_key
probe probe_scanner_downloads_are_unverified                 "S10 a tampered scanner binary installs"             probe_scanner_downloads_are_installed_without_verification
probe probe_pre_sign_scan_cannot_resolve_reactor_modules      "D-SCAN-01 the pre-sign scan resolves its own module from Central" probe_pre_sign_scan_cannot_resolve_the_reactors_own_modules
# --- D-SCAN-02 / D-SCAN-03 block (real pinned scanner) -------------------------------------
probe probe_pre_sign_scan_has_no_real_scanner_coverage       "D-SCAN-02 the real scanner reports no coverage of the staged jars" probe_pre_sign_scan_reports_no_coverage_with_the_real_scanner
probe probe_real_scanner_and_gate_miss_a_known_critical      "D-SCAN-03 a known CRITICAL in a staged jar is not caught"          probe_the_real_scanner_and_gate_miss_a_known_critical
# --- end of the D-SCAN-02 / D-SCAN-03 block ------------------------------------------------
# --- D18 block (PR 18 review, e7fda4f) ------------------------------------------------------
probe probe_verdict_document_accepted_from_a_different_scan   "D18-01 the verdict document is not bound to the coverage document" probe_verdict_document_accepted_from_a_different_scan
probe probe_grype_verdict_without_a_digest_binding             "D18-02 --min-artifacts/--expect-digests are optional at the boundary" probe_grype_verdict_without_a_digest_binding
probe probe_summary_block_dies_on_a_missing_coverage_line       "D18-03 the job summary can kill a step the gate already passed" probe_summary_block_dies_on_a_missing_coverage_line
# --- end of the D18 block --------------------------------------------------------------------
probe probe_pre_sign_scan_accepts_an_unrecorded_jar          "D15-01/02 the scan set is not bound to the recorded build" probe_pre_sign_scan_accepts_a_jar_that_is_not_the_recorded_build
probe probe_pre_sign_scan_accepts_a_missing_published_jar    "D15-03 a published jar absent from the scan set passes"    probe_pre_sign_scan_accepts_a_published_jar_missing_from_the_scan_set


# ===========================================================================
# D15-04 (security review of fix/release-scan-resolution). Five step bodies exist only in
# release.yml, so nothing in CI ever runs them, and until this block they were asserted by
# reading the YAML: the ancestry gate, the tag-signature gate, the wrapper-distribution
# removal, the version rewrite and the bundle-coordinate assertion. Three of the last four
# tag runs died inside a release-only body that had never executed once.
#
# Every probe below EXTRACTS the real body out of release.yml and RUNS it against synthetic
# inputs - a throwaway git repository, a throwaway GPG keyring, a throwaway HOME, a synthetic
# reactor, a synthetic bundle zip - and asserts both directions: the body accepts what it
# must accept and refuses what it must refuse. Same rule as every block above: the probe
# reads WEAK while the weakness (a body nothing executes, or one that behaves wrongly when
# executed) is present, and FIXED when the body behaves.
#
# Like N12-N14, each one reads WEAK when its step is renamed, commented out or switched off
# with `if: false`: a control that has been unhooked is not a control, and a probe that
# cannot find its step has proved nothing.
# ===========================================================================

# The text of one job of a workflow. The ancestry and signature steps exist TWICE, once in
# `preflight` and once in `publish` (deliberately: a gate that lives only in an upstream job
# is one a workflow edit can drop with nothing noticing), and step_body above stops at the
# first match, so each copy has to be sliced out of its own job before it can be run.
_job_block() { # _job_block <workflow> <job id>
  awk -v want="  $2:" '
    f && /^  [a-z][a-z0-9-]*:$/ && $0 != want { exit }
    $0 == want { f = 1 }
    f
  ' "$1"
}

# The body of a `run: |` block, read from workflow or job text on stdin.
_step_body_text() { # _step_body_text <step name>   (text on stdin)
  awk -v want="- name: $1" '
    index($0, want) { instep=1; next }
    instep && /run: \|/ { inrun=1; next }
    instep && !inrun && /^      - name:/ { exit }
    inrun && /^      - name:/ { exit }
    inrun && /^  [a-z][a-z0-9-]*:/ { exit }
    inrun { print }
  '
}

# The command of a single-line `run: <command>` step (the wrapper removal is one), read from
# workflow or job text on stdin. Prints nothing for a block-scalar step.
_step_command_text() { # _step_command_text <step name>   (text on stdin)
  awk -v want="- name: $1" '
    index($0, want) { instep=1; next }
    instep && /^      - name:/ { exit }
    instep && /^  [a-z][a-z0-9-]*:/ { exit }
    instep && /^[[:space:]]*run: / {
      line = $0
      sub(/^[[:space:]]*run: /, "", line)
      if (line == "|" || line == ">") exit
      print line
      exit
    }
  '
}

# True when the named step carries `if: false` (or a quoted false) - the N13 shape, applied
# to a step instead of a job.
_step_is_disabled() { # _step_is_disabled <step name>   (text on stdin)
  awk -v want="- name: $1" '
    index($0, want) { instep=1; next }
    instep && /^      - name:/ { exit }
    instep && /^[[:space:]]*run:/ { exit }
    instep { print }
  ' | grep -Eq "^[[:space:]]*if:[[:space:]]*(false|'false'|\"false\")[[:space:]]*$"
}

# ---------------------------------------------------------------------------
# D15-04 (1/5) - `Verify the released commit is on main`. The release's first gate, in both
# the preflight job and the key-holding job, and until now asserted by a grep for the word
# `merge-base`.
#
# Executed: a throwaway repository with a real `refs/remotes/origin/main`, a commit that IS
# an ancestor of it and a commit on a scratch branch that is NOT. Both copies of the body
# must accept the first and refuse the second. Weak if either copy is missing, disabled, or
# gets either direction wrong.
# ---------------------------------------------------------------------------
_ancestry_repo() {   # echoes a repo dir carrying .on-main and .off-main
  local repo
  repo="$(mktemp -d)"
  (
    set -e
    cd "$repo"
    git init -q -b main .
    git config user.email probe@example.invalid
    git config user.name probe
    git commit -q --allow-empty -m "root"
    git commit -q --allow-empty -m "reviewed, on main"
    git rev-parse HEAD > .on-main
    # The runner's checkout carries origin/main as a remote-tracking ref, not a local branch.
    git update-ref refs/remotes/origin/main HEAD
    git checkout -q --detach HEAD~1
    git commit -q --allow-empty -m "scratch branch, never merged"
    git rev-parse HEAD > .off-main
  ) >>"$PROBE_CAPTURE" 2>&1 || { rm -rf "$repo"; return 1; }
  printf '%s' "$repo"
}

probe_ancestry_gate_is_never_executed() {
  local repo on off job text body rc weak=0
  repo="$(_ancestry_repo)" || { PROBE_SKIP_REASON="the synthetic git repository could not be built, so the ancestry body was never exercised"; return 0; }
  on="$(cat "$repo/.on-main")"
  off="$(cat "$repo/.off-main")"
  for job in preflight publish; do
    text="$(_job_block "$WF" "$job")"
    if [ -z "$text" ]; then
      PROBE_SKIP_REASON="the '$job' job is gone, so its ancestry gate could not be run"
      weak=1; break
    fi
    if _step_is_disabled 'Verify the released commit is on main' <<<"$text"; then
      PROBE_SKIP_REASON="the ancestry gate is switched off with if: false in the '$job' job"
      weak=1; break
    fi
    body="$(_step_body_text 'Verify the released commit is on main' <<<"$text")"
    if [ -z "$body" ]; then
      PROBE_SKIP_REASON="no 'Verify the released commit is on main' body in the '$job' job (renamed or commented out)"
      weak=1; break
    fi
    ( cd "$repo" && GITHUB_SHA="$on" bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1
    rc=$?
    [ "$rc" -eq 0 ] || { weak=1; break; }        # refused a commit that IS on main
    ( cd "$repo" && GITHUB_SHA="$off" bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1
    rc=$?
    [ "$rc" -ne 0 ] || { weak=1; break; }        # accepted a commit that is NOT on main
  done
  rm -rf "$repo"
  [ "$weak" -eq 1 ]
}

# ---------------------------------------------------------------------------
# D15-04 (2/5) - `Verify the tag signature`. The other first gate, in both jobs, and the one
# F3 had to correct blind: every assertion about it so far has been a text match on the
# VALIDSIG pattern.
#
# Executed: two throwaway GPG keys in a temporary GNUPGHOME, a tag signed by each, and the
# real body run against them with RELEASE_SIGNING_KEY_ID set to the FIRST key's fingerprint.
# Five directions, all of them the body's own logic:
#   a tag signed by the configured key           -> accepted
#   a tag signed by a second key in the keyring  -> refused (the attack F3 describes)
#   RELEASE_SIGNING_KEY_ID unset                 -> refused
#   a short key id instead of a fingerprint      -> refused
#   the second key, configured                   -> accepted (control on the fixture)
# The body's `gpg --keyserver ... --recv-keys` is the one thing a probe cannot let happen: it
# is a network call to a public keyserver for a key that exists only in this temporary
# keyring. A `gpg` wrapper on PATH answers that one invocation and execs the real gpg for
# every other, including the one `git verify-tag` makes, so the logic under test is
# unmodified.
# ---------------------------------------------------------------------------
_tag_signature_fixture() {   # echoes a work dir carrying repo/, gnupg/, bin/, fp1, fp2
  local work real_gpg fp1 fp2 who
  real_gpg="$(command -v gpg)" || return 1
  work="$(mktemp -d)"
  mkdir -p "$work/gnupg" "$work/bin"
  chmod 700 "$work/gnupg"
  (
    set -e
    export GNUPGHOME="$work/gnupg"
    for who in one two; do
      gpg --batch --quiet --pinentry-mode loopback --passphrase '' \
        --quick-generate-key "Release Probe Key $who <$who@probe.invalid>" ed25519 sign never
    done
  ) >>"$PROBE_CAPTURE" 2>&1 || { rm -rf "$work"; return 1; }
  fp1="$(GNUPGHOME="$work/gnupg" gpg --batch --with-colons --fingerprint --list-secret-keys one@probe.invalid 2>/dev/null | awk -F: '/^fpr:/ { print $10; exit }')"
  fp2="$(GNUPGHOME="$work/gnupg" gpg --batch --with-colons --fingerprint --list-secret-keys two@probe.invalid 2>/dev/null | awk -F: '/^fpr:/ { print $10; exit }')"
  if [ -z "${fp1:-}" ] || [ -z "${fp2:-}" ] || [ "$fp1" = "$fp2" ]; then rm -rf "$work"; return 1; fi
  printf '%s' "$fp1" > "$work/fp1"
  printf '%s' "$fp2" > "$work/fp2"
  (
    set -e
    export GNUPGHOME="$work/gnupg"
    mkdir "$work/repo"
    cd "$work/repo"
    git init -q -b main .
    git config user.email probe@example.invalid
    git config user.name probe
    git config gpg.program gpg
    git commit -q --allow-empty -m "released commit"
    git -c user.signingkey="$fp1" tag -s -u "$fp1" v9.9.9 -m "signed by the configured key"
    git -c user.signingkey="$fp2" tag -s -u "$fp2" v9.9.9-other -m "signed by another key"
  ) >>"$PROBE_CAPTURE" 2>&1 || { rm -rf "$work"; return 1; }
  cat > "$work/bin/gpg" <<STUB
#!/usr/bin/env bash
# Answers the keyserver fetch the step body makes - the key under test is already in this
# throwaway keyring - and forwards everything else, unchanged, to the real gpg.
for arg in "\$@"; do
  if [ "\$arg" = "--recv-keys" ]; then
    echo "probe: --recv-keys answered locally, no keyserver was contacted"
    exit 0
  fi
done
exec "$real_gpg" "\$@"
STUB
  chmod +x "$work/bin/gpg"
  printf '%s' "$work"
}

_tag_signature_exit() {   # _tag_signature_exit <work> <body> <ref> <key id>; echoes rc
  local work="$1" body="$2" ref="$3" key="$4" rc
  (
    cd "$work/repo" &&
    PATH="$work/bin:$PATH" GNUPGHOME="$work/gnupg" \
    GITHUB_REF_NAME="$ref" RELEASE_SIGNING_KEY_ID="$key" \
    bash -c "$body"
  ) >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  echo "$rc"
}

probe_tag_signature_gate_is_never_executed() {
  command -v gpg >>"$PROBE_CAPTURE" 2>&1 || { PROBE_SKIP_REASON="gpg is not installed, so the tag-signature body was never exercised"; return 0; }
  local work fp1 fp2 job text body weak=0
  work="$(_tag_signature_fixture)" || { PROBE_SKIP_REASON="the throwaway keys and signed tags could not be created, so the tag-signature body was never exercised"; return 0; }
  fp1="$(cat "$work/fp1")"
  fp2="$(cat "$work/fp2")"
  for job in preflight publish; do
    text="$(_job_block "$WF" "$job")"
    if [ -z "$text" ]; then
      PROBE_SKIP_REASON="the '$job' job is gone, so its tag-signature gate could not be run"
      weak=1; break
    fi
    if _step_is_disabled 'Verify the tag signature' <<<"$text"; then
      PROBE_SKIP_REASON="the tag-signature gate is switched off with if: false in the '$job' job"
      weak=1; break
    fi
    body="$(_step_body_text 'Verify the tag signature' <<<"$text")"
    if [ -z "$body" ]; then
      PROBE_SKIP_REASON="no 'Verify the tag signature' body in the '$job' job (renamed or commented out)"
      weak=1; break
    fi
    [ "$(_tag_signature_exit "$work" "$body" v9.9.9 "$fp1")" -eq 0 ] || { weak=1; break; }
    [ "$(_tag_signature_exit "$work" "$body" v9.9.9-other "$fp1")" -ne 0 ] || { weak=1; break; }
    [ "$(_tag_signature_exit "$work" "$body" v9.9.9 "")" -ne 0 ] || { weak=1; break; }
    [ "$(_tag_signature_exit "$work" "$body" v9.9.9 "${fp1: -16}")" -ne 0 ] || { weak=1; break; }
    # Control on the fixture itself: the second key must really be able to produce a tag the
    # gate accepts when it IS the configured key, otherwise the refusal above could be an
    # artefact of a broken signature rather than of the identity check.
    [ "$(_tag_signature_exit "$work" "$body" v9.9.9-other "$fp2")" -eq 0 ] || { weak=1; break; }
  done
  rm -rf "$work"
  [ "$weak" -eq 1 ]
}

# ---------------------------------------------------------------------------
# D15-04 (3/5) - `Remove any pre-existing Maven wrapper distribution`. M3's operational half:
# mvnw execs an already-unpacked distribution with no checksum check at all, so the job that
# holds the signing key must start with none on disk. Nothing had ever run the line.
#
# Executed: a throwaway HOME carrying an unpacked distribution, a settings.xml (the file
# setup-java writes with the Central credentials) and a local repository. After the body the
# distribution must be gone and the other two must still be there - `rm -rf ~/.m2` would also
# satisfy "no distribution" and would take the release's credentials file with it.
# ---------------------------------------------------------------------------
probe_wrapper_removal_is_never_executed() {
  local text cmd home weak=0
  text="$(_job_block "$WF" publish)"
  [ -n "$text" ] || { PROBE_SKIP_REASON="the publish job is gone"; return 0; }
  if _step_is_disabled 'Remove any pre-existing Maven wrapper distribution' <<<"$text"; then
    PROBE_SKIP_REASON="the wrapper-removal step is switched off with if: false"
    return 0
  fi
  cmd="$(_step_command_text 'Remove any pre-existing Maven wrapper distribution' <<<"$text")"
  if [ -z "$cmd" ]; then
    cmd="$(_step_body_text 'Remove any pre-existing Maven wrapper distribution' <<<"$text")"
  fi
  if [ -z "$cmd" ]; then
    PROBE_SKIP_REASON="no 'Remove any pre-existing Maven wrapper distribution' step body (renamed or commented out)"
    return 0
  fi
  home="$(mktemp -d)"
  mkdir -p "$home/.m2/wrapper/dists/apache-maven-3.9.11-bin/2f1b3c/apache-maven-3.9.11/bin"
  printf 'poisoned distribution\n' > "$home/.m2/wrapper/dists/apache-maven-3.9.11-bin/2f1b3c/apache-maven-3.9.11/bin/mvn"
  printf '<settings/>\n' > "$home/.m2/settings.xml"
  mkdir -p "$home/.m2/repository/com/example"
  ( cd "$home" && HOME="$home" bash -c "$cmd" ) >>"$PROBE_CAPTURE" 2>&1 || weak=1
  [ -e "$home/.m2/wrapper/dists" ] && weak=1          # the unpacked distribution survived
  [ -f "$home/.m2/settings.xml" ] || weak=1           # took the Central credentials with it
  [ -d "$home/.m2/repository/com/example" ] || weak=1 # took the local repository with it
  rm -rf "$home"
  [ "$weak" -eq 1 ]
}

# ---------------------------------------------------------------------------
# D15-04 (4/5) - `Set the release version in the checkout`. The step that decides which
# version is signed and uploaded; never executed as a body, and the only thing standing
# between it and a hostile version string is the step two above it, whose output the runner
# substitutes into this one's command line.
#
# Executed as the runner composes it: the real `Derive the release version` body produces the
# version, that literal is substituted into the real `Set the release version in the checkout`
# body (${{ }} never reaches bash on a runner), and the pair runs against a synthetic
# aggregator with two modules.
#   a tag version           -> the parent and every module carry it afterwards, exit 0,
#                              and no versionsBackup pom is left in the tree
#   an embedded newline     -> the composition refuses and the rewrite never runs
#   shell metacharacters    -> same, and nothing is executed in the checkout
#   a SNAPSHOT              -> same
# The refusal deliberately belongs to the derivation step (M4's whole-string `=~`, which no
# newline, `$`, `(` or `;` can satisfy), which is why this probe runs the composition rather
# than the second body alone: asserting the refusal inside the rewrite step would be
# asserting a control this workflow does not have and does not need.
# ---------------------------------------------------------------------------
_derive_version() {   # _derive_version <ref name>; echoes "<rc> <version>"
  local body out rc version
  body="$(step_body "$WF" 'Derive the release version')"
  [ -n "$body" ] || { echo "127 "; return; }
  out="$(mktemp)"
  GITHUB_EVENT_NAME=push GITHUB_REF_NAME="$1" GITHUB_OUTPUT="$out" \
    bash -c "$body" >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  version="$(awk -F= '/^version=/ { print $2; exit }' "$out" 2>/dev/null)"
  rm -f "$out"
  echo "$rc ${version:-}"
}

_version_set_reactor() {   # echoes a work dir carrying tree/ (wrapper included) and t/
  local work module
  work="$(mktemp -d)"
  mkdir -p "$work/tree/probe-a" "$work/tree/probe-b" "$work/t"
  cp -a .mvn "$work/tree/.mvn" 2>/dev/null || { rm -rf "$work"; return 1; }
  cp mvnw "$work/tree/mvnw" 2>/dev/null || { rm -rf "$work"; return 1; }
  cat > "$work/tree/pom.xml" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.housedevinci.probe</groupId>
  <artifactId>version-set-probe-parent</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <packaging>pom</packaging>
  <modules>
    <module>probe-a</module>
    <module>probe-b</module>
  </modules>
</project>
POM
  for module in probe-a probe-b; do
    cat > "$work/tree/$module/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.housedevinci.probe</groupId>
    <artifactId>version-set-probe-parent</artifactId>
    <version>0.0.1-SNAPSHOT</version>
  </parent>
  <artifactId>$module</artifactId>
</project>
POM
  done
  # An empty local repository would make this probe download the versions plugin on every
  # run. The release job's own reason for an empty one (M3) is about the artifacts it signs,
  # which is not what is under test here, so reuse the caller's repository when there is one.
  if [ -d "$HOME/.m2/repository" ]; then
    ln -s "$HOME/.m2/repository" "$work/t/m2repo"
  fi
  printf '%s' "$work"
}

probe_version_set_step_is_never_executed() {
  [ "${CIPHER_PROBE_MAVEN:-0}" = "1" ] || { PROBE_SKIP_REASON="this probe runs a Maven plugin against a synthetic reactor; set CIPHER_PROBE_MAVEN=1 to run it"; return 0; }
  local text body run_body work tree rc derived version hostile module weak=0
  text="$(_job_block "$WF" publish)"
  [ -n "$text" ] || { PROBE_SKIP_REASON="the publish job is gone"; return 0; }
  if _step_is_disabled 'Set the release version in the checkout' <<<"$text"; then
    PROBE_SKIP_REASON="the version-set step is switched off with if: false"
    return 0
  fi
  body="$(_step_body_text 'Set the release version in the checkout' <<<"$text")"
  if [ -z "$body" ]; then
    PROBE_SKIP_REASON="no 'Set the release version in the checkout' body (renamed or commented out)"
    return 0
  fi

  # 1. The honest path: a real tag name through the real derivation, then the real rewrite.
  derived="$(_derive_version v7.8.9)"
  version="${derived#* }"
  if [ "${derived%% *}" -ne 0 ] || [ "$version" != "7.8.9" ]; then
    PROBE_SKIP_REASON="the derivation step did not yield a version for v7.8.9, so the rewrite was never composed"
    return 0
  fi
  work="$(_version_set_reactor)" || { PROBE_SKIP_REASON="the synthetic reactor could not be built, so the rewrite body was never exercised"; return 0; }
  tree="$work/tree"
  run_body="$body"
  run_body="${run_body//\$\{\{ runner.temp \}\}/$work/t}"
  run_body="${run_body//\$\{\{ steps.v.outputs.version \}\}/$version}"
  ( cd "$tree" && bash -c "$run_body" ) >>"$PROBE_CAPTURE" 2>&1
  rc=$?
  [ "$rc" -eq 0 ] || weak=1
  grep -q "<version>$version</version>" "$tree/pom.xml" || weak=1
  grep -q "<version>$version</version>" "$tree/probe-a/pom.xml" || weak=1
  grep -q "<version>$version</version>" "$tree/probe-b/pom.xml" || weak=1
  for module in probe-a probe-b; do
    grep -q '0.0.1-SNAPSHOT' "$tree/$module/pom.xml" && weak=1
  done
  grep -q '0.0.1-SNAPSHOT' "$tree/pom.xml" && weak=1
  # -DgenerateBackupPoms=false: a release must not carry a pom.xml.versionsBackup into the
  # tree it is about to build, sign and publish.
  [ -e "$tree/pom.xml.versionsBackup" ] && weak=1

  # 2. The hostile paths, through the same composition. Each must be refused before the
  #    rewrite body is reached, and must leave the checkout untouched.
  for hostile in "$(printf 'v7.8.9\nmalicious=1')" 'v7.8.9$(touch pwned)' 'v7.8.9; touch pwned' 'v7.8.9-SNAPSHOT'; do
    derived="$(_derive_version "$hostile")"
    if [ "${derived%% *}" -eq 0 ]; then
      weak=1                                    # a hostile version reached the rewrite step
      continue
    fi
    version="${derived#* }"
    [ -z "$version" ] || weak=1                 # it still wrote a version to $GITHUB_OUTPUT
  done
  [ -e pwned ] && { weak=1; rm -f pwned; }
  [ -e "$tree/pwned" ] && weak=1

  rm -rf "$work"
  [ "$weak" -eq 1 ]
}

# ---------------------------------------------------------------------------
# D15-04 (5/5) - `Confirm the bundle contains exactly the three published coordinates`. L5's
# only mechanism for keeping the sample out of what is uploaded, and N4 already caught it
# pointing at a path the plugin never writes - after the upload had happened. Executed once
# by hand during that fix, never by a gate.
#
# Executed: synthetic central-bundle.zip files at the real path.
#   exactly the three published coordinates -> accepted
#   plus the sample                         -> refused
#   one published coordinate missing        -> refused
#   no bundle at all                        -> refused (the N4 shape)
# ---------------------------------------------------------------------------
_make_bundle() {   # _make_bundle <work dir> <coordinate>...
  local work="$1"; shift
  local layout="$work/bundle-src" coord
  rm -rf "$layout" "$work/target/central-publishing/central-bundle.zip"
  mkdir -p "$layout" "$work/target/central-publishing"
  for coord in "$@"; do
    mkdir -p "$layout/com/housedevinci/$coord/0.1.0"
    printf 'pom bytes\n' > "$layout/com/housedevinci/$coord/0.1.0/$coord-0.1.0.pom"
    printf 'jar bytes\n' > "$layout/com/housedevinci/$coord/0.1.0/$coord-0.1.0.jar"
    printf 'signature\n' > "$layout/com/housedevinci/$coord/0.1.0/$coord-0.1.0.jar.asc"
  done
  ( cd "$layout" && zip -q -r "$work/target/central-publishing/central-bundle.zip" . ) >>"$PROBE_CAPTURE" 2>&1
}

probe_bundle_coordinate_check_is_never_executed() {
  command -v zip >>"$PROBE_CAPTURE" 2>&1 || { PROBE_SKIP_REASON="zip is not installed, so the synthetic bundle this probe needs cannot be built"; return 0; }
  local text body work weak=0
  text="$(_job_block "$WF" publish)"
  [ -n "$text" ] || { PROBE_SKIP_REASON="the publish job is gone"; return 0; }
  if _step_is_disabled 'Confirm the bundle contains exactly the three published coordinates' <<<"$text"; then
    PROBE_SKIP_REASON="the bundle-coordinate check is switched off with if: false"
    return 0
  fi
  body="$(_step_body_text 'Confirm the bundle contains exactly the three published coordinates' <<<"$text")"
  if [ -z "$body" ]; then
    PROBE_SKIP_REASON="no 'Confirm the bundle contains exactly the three published coordinates' body (renamed or commented out)"
    return 0
  fi
  work="$(mktemp -d)"

  # No bundle at all: the shape N4 found, where the step read a path nothing writes.
  mkdir -p "$work/target/central-publishing"
  ( cd "$work" && bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1 && weak=1

  _make_bundle "$work" stripe-einvoice-parent stripe-einvoice-core stripe-einvoice-spring-boot-starter
  ( cd "$work" && bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1 || weak=1   # must accept

  _make_bundle "$work" stripe-einvoice-parent stripe-einvoice-core stripe-einvoice-spring-boot-starter stripe-einvoice-sample
  ( cd "$work" && bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1 && weak=1   # must refuse

  _make_bundle "$work" stripe-einvoice-parent stripe-einvoice-spring-boot-starter
  ( cd "$work" && bash -c "$body" ) >>"$PROBE_CAPTURE" 2>&1 && weak=1   # must refuse

  rm -rf "$work"
  [ "$weak" -eq 1 ]
}

probe probe_ancestry_gate_is_never_executed                  "D15-04 the ancestry gate is read, never run"        probe_ancestry_gate_is_never_executed
probe probe_tag_signature_gate_is_never_executed             "D15-04 the tag-signature gate is read, never run"   probe_tag_signature_gate_is_never_executed
probe probe_wrapper_removal_is_never_executed                "D15-04 the wrapper removal has never executed"      probe_wrapper_removal_is_never_executed
probe probe_version_set_step_is_never_executed               "D15-04 the version rewrite has never executed"      probe_version_set_step_is_never_executed
probe probe_bundle_coordinate_check_is_never_executed        "D15-04 the bundle-coordinate check never runs"      probe_bundle_coordinate_check_is_never_executed
[ -z "$_SCAN_FIXTURE" ] || rm -rf "$_SCAN_FIXTURE"

echo
echo "still weak: $pass    fixed: $flipped"
[ "$pass" -eq 0 ]
