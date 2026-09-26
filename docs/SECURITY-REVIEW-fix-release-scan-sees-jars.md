# Security review - branch `fix/release-scan-sees-jars`

Adversarial review of pull request 18 (the pre-sign vulnerability scan: coverage read from the
CycloneDX document of the scanning run, `--sbom`, `--min-artifacts`, `--expect-digests`), commit
`e7fda4f`. Pass 1 of at most 2. Scope: the release gate and the supply-chain path only.

## 2026-09-25 - pass 1

### Verdict

**MERGE WITH FIXES** - three findings, none above LOW. The defect this branch was opened for is
really fixed, and I proved it with the pinned scanner rather than by reading: the real grype writes
the catalog the gate now reads, the digest arm refuses a staged jar the scanner never opened, and a
published CRITICAL in a staged jar is named and refused. What is left is the other half of the same
sentence the branch is about - the gate now vouches for its COVERAGE and still does not vouch for
the document its VERDICT comes from.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | - |
| MEDIUM | 0 | - |
| LOW | 2 | D18-01, D18-02 |
| INFO | 1 | D18-03 |

Probes: `internal/D-stripe-einvoice/probes/cipher-probe-pr18.sh`, four probes, all RED on `e7fda4f`
(the D18-01 repro runs twice: once on crafted documents, once on the real pinned scanner).

### Numbers reproduced on this machine

| What | Result |
|---|---|
| `tools/cipher-probe-release-pipeline.sh` with `CIPHER_PROBE_MAVEN=1` | 84 fixed, 0 weak, 0 unverifiable |
| D-SCAN-02, real pinned scanner over the real staged set | 98 packages cataloged for 98 staged jars |
| D-SCAN-03, real scanner and real gate on a jar with CVE-2022-42889 | named and refused; empty directory refused |
| `tools/check-vulnerability-report.py --self-test` | all cases correct |
| This review's own probes on `e7fda4f` | 4 weak (D18-01 twice, D18-02, D18-03) |

### What held

Attacked and held, each by execution on this machine with grype 0.118.0 installed by
`tools/install-scanner.sh` (checksum verified):

- **One invocation, two documents.** The step runs the scanner once with `-o json=...` and
  `-o cyclonedx-json=...`. The catalog and the matches come from the same walk.
- **The digest arm is what it claims.** A file component with a SHA-256 appears only for an archive
  the cataloger actually opened: a 5 kB non-zip file named `*.jar`, staged alongside two real jars,
  produced neither a library nor a file component, so a staged artifact the scanner could not open
  is exit 2, not a pass. Matching is on content, so a rename cannot satisfy it without the bytes
  having been walked; an unreadable digest file, an empty digest file and a digest absent from the
  catalog are all exit 2.
- **The coverage floor cannot be starved in this pipeline.** Two byte-identical jars under different
  names produce two library components (no deduplication), and a jar with no Maven metadata still
  yields one filename-derived package, so "packages cataloged >= jars staged" holds for a staged set
  produced by `dependency:copy-dependencies`. A floor satisfied by unrelated entries is possible in
  principle; on the release path `--expect-digests` closes it, which is why D18-02 below is about
  that flag being optional rather than about the floor being weak.
- **The healthy case is a pass and the empty case is a refusal.** Zero matches with a populated
  catalog is exit 0; zero matches with an empty catalog, a catalog without a `components` section,
  or the report handed over in place of the catalog, are all exit 2.
- **Severity semantics unchanged.** A crafted CRITICAL refuses (exit 1) and is named; a crafted
  MEDIUM passes and is written to the below-threshold file with a coordinate and no path.
- **Exit codes under the new flags.** The scanner still exits 0 when it completes with findings
  (so the step's `rc != 0` test remains a scanner-failure test) and exits 1 on a target that does
  not exist, so a wrong scan path fails the step before the gate is reached.
- **`| tee` under `set -euo pipefail`.** The gate's exit 1 and exit 2 propagate through the pipe; the
  refusal is not swallowed.
- **The pull-request and weekly arms.** Both call the OSV arm only, which this branch does not touch;
  the D4-01 refusals (no packages, no `results` section) still return 2. No `--sbom` is needed there.
- **Summary output.** The lines written to the job summary are two counts and the below-threshold
  findings by coordinate: no filesystem path, no secret, no token.

### D18-01 (LOW) - the verdict document is bound to nothing

`run()` in `tools/check-vulnerability-report.py`, grype arm. The catalog is bound to the staged bytes
by `--expect-digests`, and the matches are read from a second file that nothing ties to that catalog.
Hand the gate the JSON report of one scan and the CycloneDX document of another and every coverage
check passes on the second while the verdict is computed from the first.

Repro (real scanner, in the probe file as D18-01): scan A is a directory holding one clean jar; scan
B is the staged set holding a jar that declares `org.apache.commons:commons-text:1.9`. Report A plus
catalog B plus the digests of B:

```
vulnerability gate: 1 staged artifact(s) matched by digest in the scan
vulnerability gate: 1 package(s) were scanned
vulnerability gate (grype report, threshold HIGH): 0 finding(s)
exit 0
```

The same catalog with its own report is exit 1 and names `GHSA-599f-7c49-w659`. Today nothing on the
release path feeds mismatched documents; a stale file left in `RUNNER_TEMP`, a path typo in a later
edit of the step, or a future caller is all it takes, and the failure mode is silent success - the
exact shape of the defect this branch exists to remove, seen from the other side.

Fix (Isis): in `run()`, after the catalog loads, refuse unless the two documents describe the same
scan. Both carry the target: the report has `source.target`, the CycloneDX document has
`metadata.component.name` (verified on 0.118.0: both were `/tmp/.../scan` for the same run). Exit 2
on a mismatch and exit 2 when either field is absent - an unverifiable pairing is not a pairing.
Compare the tool version as well (`descriptor.version` against `metadata.tools.components[].version`
where the component is named `grype`) so two runs of two different scanner versions cannot be mixed.
Probe to add to `tools/cipher-probe-release-pipeline.sh` as D-SCAN-04, with the real pinned scanner:
two scans, report A with catalog B, refused; report B with catalog B, still exit 1.

### D18-02 (LOW) - the control that binds the scan is optional at the boundary

`main()` in the same file makes `--sbom` mandatory for `--format grype`, with the right reason in the
comment: a caller without it would be asking for a verdict on unknown coverage. `--expect-digests` is
the control that actually binds the scan to the bytes, and `--min-artifacts` is the floor, and both
are optional - `--min-artifacts` defaults to `1`. A caller that passes only `--sbom` gets exit 0 from
a catalog holding a single filename-derived package and no digest bound to anything:

```
check-vulnerability-report.py --format grype --report report.json --sbom one-component.json
vulnerability gate: 1 package(s) were scanned
exit 0
```

Only the convention inside one workflow step keeps the release on the strong path. House rule: the
secure mode is the default, a weaker one is an explicit choice that says so.

Fix (Isis): for `--format grype`, `parser.error` unless `--expect-digests` is given, exactly as for
`--sbom`; make `--min-artifacts` required for that format and remove the default of `1`. Update the
crafted grype probes in the suite and the empty-directory mutation inside D-SCAN-03 to pass both
flags. No caller outside the release step uses the grype arm, so nothing else moves.

### D18-03 (INFO) - the job summary can kill a step the gate already passed

`.github/workflows/release.yml`, step "Scan the artifacts this release is about to sign". The summary
block runs `grep -E 'were scanned|matched by digest' ... | sed ...` under `set -euo pipefail`. A miss
is exit 1 on the pipeline, so the step fails after the gate has passed and the summary is truncated
at the heading. Repro: run the block against a gate transcript with no coverage line - exit 1, and
the text after the grep never reaches the summary. Release-hygiene checklist RP-6: a lookup miss
reports that there is no record and runs to the end; it never kills the step under `pipefail`.

Fix (Isis): `{ grep -E 'were scanned|matched by digest' "$f" || echo "the gate printed no coverage
line"; } | sed 's/^/- /'`.

### Not findings

- *The coverage floor can be met by entries that are not the staged jars.* True of
  `--min-artifacts` alone, and closed on the release path by `--expect-digests`; reclassified into
  D18-02, which is about that flag being optional rather than about the floor.
- *A `.jar` the scanner cannot open blocks the release.* Correct behaviour, and the scan set is
  already bound to the recorded build one step earlier.
- *Two jars with the same coordinate could deduplicate and starve the floor.* Attempted with two
  byte-identical jars under different names: two library components, no deduplication, gate passes.
