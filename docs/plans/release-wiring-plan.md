# Plan — validate out of the box, and gate releases on known vulnerabilities

Branch `feat/release-wiring`, on top of the documents change. Four tasks, one commit each, each
with its own probe. Nothing here touches the issuance path's behaviour.

## 1. Saxon-HE as a shipped runtime dependency

Today the schematron validator asks for an XSLT 2.0 processor by class name and reports
`NOT_EVALUATED` when none is present, because the only practical processor is MPL-2.0 and the
licence gate denies MPL for anything this project ships. The maintainer has decided MPL-2.0 is
acceptable for that one coordinate.

- `net.sf.saxon:Saxon-HE` moves from `test` to `runtime` scope in `stripe-einvoice-core`, where the
  validator lives; the starter and the sample inherit it and drop their own test-scope declarations.
- The plugin allowlist (`<includedLicenses>`, an any-of *permission* check) gains `MPL-2.0` with its
  merge spellings. That half is licence-wide by construction — the plugin cannot scope a licence to
  a coordinate.
- The scoping lives in the denial pass, which already works by coordinate:
  `ALLOWED_COORDINATE_LICENCES=( "net.sf.saxon:Saxon-HE|mpl20" )`. The coordinate is excused for
  **that denied pattern only**. A second MPL artefact still fails; Saxon under a different denied
  licence still fails; `--check-unused` drives the entry out if Saxon ever leaves the tree.
- `NOTICE` gains a Saxon-HE entry with the licence and its URL. `README` and `docs/documents.md`
  say the module validates out of the box.
- The class-name lookup stays, as the "is a processor present" check `canValidate()` is built on and
  as the seam a host uses to substitute its own processor. It is no longer the reason a default
  install cannot validate, and the text says which of the two it is.

## 2. Keyless CVE gates

- **Pull requests:** OSV-Scanner as its own required check, failing on HIGH/CRITICAL, action pinned
  by SHA, scanning the resolved runtime dependency set rather than the source tree.
- **Release:** Grype over the artifacts that are about to be signed — the module jars and the
  resolved runtime jars — before the signing/upload step, same threshold; MEDIUM findings are
  written to the step output for the release notes.
- **Fail closed:** a scanner that cannot run (no database, no network, a non-zero exit that is not a
  finding) fails the job. "We could not scan" is never "clean". The release preflight job proves
  both scanners are runnable before any key-holding job is scheduled.
- **Weekly:** Dependency-Check stays as an optional deep scan and must not fail the week when no NVD
  key is configured — it skips **loudly**, with the reason in the step summary.

## 3. Checklist line 71 in the probe suite

Every probe that shells out to an inner build or external command captures that output and prints
its last 30 lines when it reports WEAK, with the reason when the command could not run at all.

## 4. Sample and documentation

- The sample takes a test-mode fixture event and writes a validated XRechnung and a validated Peppol
  UBL document, from a clean clone, inside five minutes (the spec's acceptance check).
- The README quickstart is re-run from a fresh clone and corrected where it drifted.
- A documentation page with the per-country mandate dates: a date only with a source URL and an
  access date, otherwise "not confirmed".

## Risks

- Shipping Saxon enlarges the dependency surface of every host: it is the single largest artifact
  the starter now pulls, and it is exactly the kind of dependency a CVE gate exists for. Tasks 1 and
  2 land together for that reason.
- A required OSV check means a newly published advisory on a transitive dependency turns every
  pull request red until it is upgraded or triaged. That is the intended cost and is stated in
  CONTRIBUTING.

## Not in this change

The pre-allocation preflight mechanism (its own design, its own change), credit-note wiring
(a numbering decision is open), Factur-X, delivery, and multi-account support.
