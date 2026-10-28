# Security review: fix/stray-archive-object-check

## Pass 1 (2026-10-27)

Head `b7a945a` (main after PR 51, merged in). Subject: D-RTR-4 (LOW), the check for an archive
object at the key of a burned or voided number (`DEI-281`). Built from the approved design page,
in the PR 51 shape of the reconciliation pass.

**Verdict: MERGE WITH FIXES.** The check does what the design says for the rows it reads. Two
findings on this PR: D-PR52-1 (LOW) and D-PR52-2 (INFO). One older finding, D-PR52-3 (MEDIUM), is
on main since 0.1.0 and gets its own PR before the 0.2.0 tag. D-PR51-5 is now reproduced.

### Numbers

| Check | Result |
|---|---|
| `CIPHER_PROBE_MAVEN=1 ./mvnw -B clean verify`, Docker up | core 983, starter 157, sample 9; 0 failed, 0 skipped; coverage gates met |
| Earlier review probes (`CipherProbe*`), unchanged | 67 classes, 332 tests, all green |
| Mutation M1: the check removed from the pass | 18 red in the four affected classes (the PR's 16, plus the review probes and the row-lock test) |
| Mutation M2: a lookup failure read as "absent" | 4 red, as the PR says |
| Mutation M4: `disposedUnverified` left out of `total()` | 1 red, as the PR says |
| New review probes (internal, `CipherProbePr52Test`) | 6 of 6 red on `b7a945a` |

### The questions put to this pass

| # | Question | Ruling |
|---|---|---|
| a | The design's paths against the code | O1, O2, O4 to O9 and O11 hold for rows in the series read. O3 waits for D-RTR-5, as stated. The read itself is too narrow: D-PR52-1. |
| b | The page-size gap: is a stray object past the newest page ever found? | Only by chance, as an orphan (`DEI-272`), when its key falls in the first list page. That page holds the oldest keys, so the orphan check is itself broken past one page: D-PR52-3. The O10 residual as written assumes a row is new when it is disposed. A void is not: D-PR52-1. |
| c | The two new `Result` counts as public API | Accepted. Added at the end, both in `total()`, the break is in the CHANGELOG. The starter's health reads `total()` only, so it sees both. |
| d | An object at the key of a number being issued right now | Not a finding. A disposed row never leaves the disposed states (`FAILED_VALIDATION` may only go to `VOID_UNUSED`). A row being issued is read as open and is skipped. The lock-loss case (another worker burns the row while the first PUT is in flight) is covered by the row-lock test, which now expects `DEI-281`. |
| e | A PUT that lands during the check | Not a finding. Seen as absent in this pass, named by the next. |
| f | The trailing-slash prefix (D-PR51-5) here | The new check reads one exact key, so it is not affected. The orphan check is: D-PR51-5 is now reproduced (probe below), and it belongs to the D-PR52-3 design. |

### D-PR52-1 (LOW): the check reads rows by allocation, but the risk starts at disposal

The check looks only at rows the series read returns: the newest `page-size` numbers of the series
years the window touches. A row is disposed when it is burned or voided, and that can be long after
it was numbered. Two cases where the row is never looked up, not even once:

- **Across a year end.** A 2026 number is stuck over the new year. The operator voids it on
  10 January 2027 while its PUT is on the store. The pass reads the 2027 series only. Probe:
  `probe_a_late_object_at_a_number_voided_after_its_series_year_is_not_reported` (expected 1
  finding, got 0).
- **On a busy series.** The stuck number is already older than one page of newer numbers when it
  is voided. Probe: `probe_a_late_object_at_an_old_number_voided_on_a_busy_series_is_not_reported`
  (page of 2, expected 1, got 0).

The README claim "about ten months" for a seller with 50 invoices a month is also wrong with a
yearly series: the row leaves the read when the window leaves its year.

The stuck check had the same shape and was fixed the same way in PR 51 (D-PR50-B1): "a number left
open is stuck whatever year it was allocated in and however many newer numbers came after it".

Property the fix must hold: every disposed row with a key, of this seller and mode, in any series
year, is looked up, whatever the number of newer rows; the bound is a page of disposed rows with a
key, not a page of all rows.

Fix (the builder of this PR; a read on an existing port, like `openOlderThan`, not a mechanism):
a reader method that returns the disposed rows with a key for a seller and mode, every year,
newest first, limited to `page-size`; a WARN when the page is full; the check reads it before any
archive call, next to the series read; its keys join the orphan check's known set. Treat the port
change as D-PR51-2 treated `openOlderThan`. Rewrite the O10 test to the new bound, fix the README
and SECURITY-NOTES lines, and add both probes to the suite.

### D-PR52-2 (INFO): "the cheap question first" downloads the object twice on S3

`S3ArchiveStore` does not override `exists`; the port default calls `get`. So on S3 the check
downloads an object it finds twice, and the javadoc ("a HEAD on S3") and the CHANGELOG ("an
existence check first") say what the code does not do. The branch where `exists` says yes and
`get` says no is also the only failure path with no WARN line. Probe:
`probe_a_found_object_is_downloaded_twice_on_a_store_without_a_head` (expected 1 read, got 2).

This reverses item 4 of the design review, which assumed a HEAD. A GET of a missing key costs the
same as a HEAD on S3 and on a filesystem.

Fix (fix pass): `ReconciliationSweep.checkDisposed` calls `archive.get(key)` once inside the try;
empty is `ABSENT`. Drop the `exists` call and the contradiction branch. Correct the javadoc and the
CHANGELOG line. Replace `the_cheap_question_is_asked_first` with a test that asserts one read per
disposed key, and `a_read_failure_after_the_existence_check_is_a_finding` with the plain read
failure.

### D-PR52-3 (MEDIUM, on main since 0.1.0): the orphan check compares two different pages

The orphan check builds its known keys from the newest `page-size` rows of the window's series
years. It then lists the first `page-size` keys of the whole seller prefix, in key order, which
means the oldest. Once a seller has more objects than one page, or a year has passed, the two pages
do not overlap:

- every older document is reported as an orphan (`DEI-272`) on every pass. Probe:
  `probe_last_years_issued_document_is_reported_as_an_orphan`, default settings, one invoice
  (expected 0, got 1);
- a real orphan written later sits past the list page and is never seen. Probe:
  `probe_a_real_orphan_past_the_first_list_page_is_never_seen`.

So past one page the control floods with false findings and misses the true ones. It also holds
D-PR51-5: the list prefix has no trailing slash, so another seller whose id starts with this
seller's id is listed under it. Probe:
`probe_a_seller_reports_a_longer_named_sellers_object_as_its_orphan` (expected 0, got 1). The
finding subject is then the other seller's legal number and document hash.

**DESIGN STOP.** This needs a new way to decide "no row predicted this key", not a correction. The
property: an object is an orphan only when no row of this seller and mode, in any year, has its
key; every key under `<mode>/<seller>/` (with the slash) is eventually examined, not only the first
page. Paths the design must cover: S3 and filesystem listing order, the year prefixes, the
continuous series, the scratch prefix, a seller id that is a prefix of another, the disposed keys
(O8). The builder writes a one-page design; the security review reads it before code. Own PR,
before the 0.2.0 tag. Not a blocker for this PR.

### Fix list for this PR

| Finding | Owner | Tests |
|---|---|---|
| D-PR52-1 | builder | both D-PR52-1 probes green; O10 test rewritten |
| D-PR52-2 | fix pass | D-PR52-2 probe green; two tests replaced |

The probes live in the internal probes folder as `CipherProbePr52Test`; the fixer adds the three
probes of this PR to the suite before changing code. The three D-PR52-3 and D-PR51-5 probes go to
the PR that implements that design.
