# Security review - branch `feat/release-wiring`

Adversarial review of pull request 4 (release wiring: the licence exception, the vulnerability gates,
the shipped XSLT processor and four startup defects), commit `8001a53`. Pass 1 of at most 2.

## 2026-09-20 - pass 1

### Verdict

**MERGE WITH FIXES** - one finding, LOW, four lines to fix. This is the cleanest branch of the four so
far: every control I attacked held, including the two I expected to break. No mechanism finding, no
design stop.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | - |
| MEDIUM | 0 | - |
| LOW | 1 | D4-01 |
| INFO | 0 | - |

### What was run

| Check | Result |
|---|---|
| `./mvnw -B verify` | BUILD SUCCESS, 459 tests (346 core, 109 starter, 4 sample), coverage gates met |
| `./mvnw -B clean verify -Prelease -Dgpg.skip=true` | BUILD SUCCESS |
| `CIPHER_PROBE_MAVEN=1 tools/cipher-probe-release-pipeline.sh`, in the tree | **73 fixed, 0 weak** |
| Reference guard, tree and release jars; licence carve-out check | clean |
| Sources jars | sources, module resources, the vendored reference set, `LICENSE`, `NOTICE`, Maven metadata; nothing else. The `NOTICE` inside the published core jar carries the Saxon attribution |
| Eight mutations of my own | seven held; one is D4-01 |

Release hygiene lines 1, 3 and 10 pass on the release artifacts.

### The licence exception cannot widen (priority 1)

I did not take the four self-test cases on trust: I copied the gate, added four cases of my own to its
own harness and ran the real `check_notices_file` against them.

| Case | Result |
|---|---|
| Saxon-HE, "Mozilla Public License Version 2.0" | accepted - the decision, and only it |
| Saxon-HE, "MPL-2.0" (the other spelling) | accepted - the family list is doing its stated job |
| A **second** MPL dependency, different group (`org.mozilla:rhino`) | **refused** |
| An MPL dependency under the **same groupId**, different artifactId (`net.sf.saxon:Saxon-HE-x`) | **refused** |
| An MPL dependency under another coordinate, spelled "MPL 2.0" | **refused** |
| Saxon-HE under GPL-3.0, and Saxon-EE under MPL | **refused** (the branch's own cases, re-run) |

The exception is bound to one coordinate and one licence family, the coordinate match is exact rather
than a pattern, and `--check-unused` reports the entry dead if Saxon leaves the tree. The comment
above it states the invariant that makes it safe - MPL-2.0 is file-level copyleft on an unmodified
binary dependency - and warns against copying the shape to a work-level reciprocal licence. That is
the right control and the right explanation of it.

### The vulnerability gates (priority 2)

| Attack | Result |
|---|---|
| A scanner binary whose pinned checksum does not match | **refused**: the installer aborts with exit 1, prints expected and actual, and leaves the destination empty |
| An OSV report that scanned zero packages | **refused**, exit 2, with the reason |
| A Grype report carrying a HIGH finding | **refused**, exit 1 |
| A Grype report that scanned nothing | **accepted**, exit 0 - D4-01 |
| `--data-source=native` degrading to no data | closed at the workflow: any scanner exit other than 0 or 1 fails the job by name, and a report that parsed but lists nothing is the exit-2 path above |
| The release scan running after signing | it does not: the scan step sits between the reproducibility check and "Verify, licence check, sign, upload", and a later step re-ties the uploaded bundle to the reproducibility build |
| A scanner that cannot run discovered inside the key-holding job | closed: the preflight job, which holds no secret, installs both scanners, verifies both checksums and executes both before any key exists |
| The weekly sweep failing the week on a missing NVD key | it cannot: the deep scan is skipped with a warning annotation and a job-summary paragraph that says a skipped scan is not a clean scan; the gate is the OSV job beside it |

The bundle question is answered honestly in the workflow's own comment: `central-bundle.zip` is
assembled during `deploy`, after signing, so it cannot be the scan input; what is scanned is the jars
the reproducibility check just built - the same bytes that are signed - plus the resolved runtime
dependency set, which is where a library's vulnerabilities actually are.

### The shipped XSLT processor (priority 5)

Saxon-HE now ships at runtime scope, so a default install validates instead of refusing. I wrote three
hostile stylesheets and ran them through the factory the module discovers by class name:

- `probe_a_stylesheet_cannot_call_a_java_extension_function` - `java:java.lang.System` reaching
  `getProperty`: **refused**.
- `probe_a_stylesheet_cannot_read_a_file_through_document_or_unparsed_text` - both `document()` and
  `unparsed-text()` on `file:///etc/passwd`: **refused**, and nothing of the file reaches the output.
- `probe_a_stylesheet_cannot_fetch_over_the_network` - `document()` on an http URL: **refused**.

That also answers the xmlresolver question: the catalog resolver arrives with Saxon, and the paths it
could serve are shut at the factory - external DTD and stylesheet access blanked, secure processing
on, and a URI resolver that throws rather than returning empty.

### Weak-probe output (priority 4)

I planted two probes that report WEAK with captured inner output and ran the suite: both printed
`--- last 30 lines of this probe's inner command output ---` followed by their lines, and a probe with
no captured output says so explicitly rather than printing nothing. Checklist line 71 holds.

### The four startup defects (priority 6)

Two reverted, one at a time, against the full starter suite:

- blank Stripe API key treated as a present one - `a_blank_api_key_is_no_api_key_and_does_not_fail_a_numbering_only_host` goes **red**;
- the intake check refusing regardless of whether the intake is configured - **five** tests go red, including the numbering-only host.

Each defect has a test that fails when the fix is removed.

---

#### D4-01 · LOW · the report gate refuses a scan of nothing for one scanner and accepts it for the other

`check-vulnerability-report.py` has two arms. The OSV arm counts packages and, at zero, exits 2 with
"this is not evidence of a clean tree" - which is the whole point of the change that added it. The
Grype arm checks only that a `matches` key exists. A Grype report with `"matches": []` and
`"artifacts": []` - the shape produced by scanning a path that holds nothing, a renamed directory, an
empty copy target - returns **exit 0, zero findings, gate passed**.

Today nothing rides on it: the release workflow counts the jars it copied and refuses at zero before
Grype is invoked, so the empty scan cannot reach the gate on the one path that uses it. The finding is
that the *script's* contract differs between its two formats, and the script is the shared component -
the weekly sweep, a future bundle scan, a maintainer running it by hand, all get the OSV guarantee and
not the Grype one. A gate whose strictness depends on which scanner produced the report is a gate
somebody will read the wrong way.

**Repro.** `printf '{"matches":[],"source":{"type":"directory","target":"/nonexistent"},"artifacts":[]}'`
through `--format grype --fail-on high`: exit 0. The same emptiness through `--format osv`: exit 2.

**Required change.** Mirror the OSV arm: when the Grype report's `artifacts` array is absent or empty,
print the same kind of message and return 2. Add the case to the script's own `--self-test`, beside the
OSV zero-package case that is already there, so the two arms are held to one contract.

### Fix list

One correction, with its probe. Pass 2 is the last pass on this branch.

1. **D4-01** - the Grype arm refuses a report that scanned nothing, exit 2, with a self-test case
   beside the OSV one. Probe: `a_grype_report_that_scanned_nothing_is_refused`.
