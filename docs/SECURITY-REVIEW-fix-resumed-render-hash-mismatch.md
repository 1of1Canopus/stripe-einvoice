# Security review: fix/resumed-render-hash-mismatch

## Pass 1 (2026-10-27)

Head `2a90a00` (main `f581849` merged in). Subject: D-RTR-5 (LOW, a 0.2.0 tag item). A resumed run
whose new bytes pass validation and the date check, but differ in hash or key from what the row
recorded, now burns the number with `DEI-244`. Read against the approved design "resumed render
hash mismatch" (candidate A, spec review 2026-10-20).

**Verdict: MERGE WITH FIXES.** D-RTR-5 is fixed. One new finding, D-PR53-1 (INFO). It is the
limit the builder stated in the PR: a later event on a burned number answers `DEI-250` and
records no rule id.

### Numbers

| Check | Result |
|---|---|
| `CIPHER_PROBE_MAVEN=1 ./mvnw -B clean verify`, Docker up | core 979, starter 157, sample 9; 0 failed, 0 skipped; coverage gates met |
| Earlier review probes (`CipherProbe*`) | all green in that run, unchanged |
| `CipherProbeRenderMismatchTest` (the spec review's three probes) | 3 of 3 green |
| Builder mutations re-run by the review | 3 of 6, red as stated (table below) |
| New review probes (`CipherProbePr53eTest`, internal) | 1 red (D-PR53-1), 3 guards green |
| Merge with the stray-object PR (local, not pushed) | 2 review probes green; both PRs' path tests green |

| Mutation | Red in the builder's two classes | Also red in the review probes |
|---|---|---|
| comparison removed | 11, as stated | the reprocess and extension-case guards |
| comparison on the hash only | 2, as stated (extension test and probe) | the extension-case guard |
| burn replaced by `DEI-240` without a burn | 11, as stated | the reprocess and extension-case guards |

### The five review items of the design, against the code

| Item | Ruling |
|---|---|
| 1 compare the key, not only the hash | Done. `underLock` compares `documentSha256` and `archiveKey`. The hash-only mutation turns 2 builder tests red, and the review's extension-case guard too. |
| 2 the phase table claim | Done. P2 now says the re-render *must* be byte-identical and names `DEI-244`. README and `DocumentRenderer` list the causes, with the street name example. |
| 3 the `FAILED_VALIDATION` text | Done on `Issuance.documentHash()`, `IssuanceState.FAILED_VALIDATION` and the README state section. `the_series_report_shows_a_render_mismatch_burn` pins the report line. |
| 4 paths R8b and R15 | Both tested and green. R15: the void wins, one `VOID_UNUSED` event, nothing archived. |
| 5 mutation list | The six mutations are in the PR body. Three re-run here (above). |

Chained event: `renderNotReproducible` goes through `concludeIssuance` and `markFailed`, so the
event copies the row: rule id `DEI-244`, the first hash, the first key. The new hash is in the WARN
line only. The WARN carries the number, two hashes and two keys. A key holds only
`[A-Za-z0-9._/-]` (`ArchiveKey`), so no content can reach the log through it.

### The paths the brief named

| Path | Ruling | Evidence |
|---|---|---|
| Burn under the run lock (PR 49) | Safe. The comparison and the burn run in `underLock`, under the row lock, on the row re-read under that lock. The burn does not call `runProof.assertHeld()`; neither does the validation burn. A run that lost its run lock still holds the row lock, so a second run gets `DEI-245` (in flight) and writes nothing. | `probe_a_reprocess_during_a_render_mismatch_burn_is_refused` |
| Edge table (PR 50) | No new inbound state. The burn concludes `FAILED_ISSUANCE` with no backoff, as every burn does. `SweeperRetryEdgeTableTest` (87 cells) green. | full verify |
| Only the key differs | Burns. Same bytes with extension `pdf`, or `XML` in place of `xml`: the pre-read looks at the recorded key, finds nothing, and the comparison burns. The new key is never written. | builder's extension test; `probe_a_key_that_differs_only_by_extension_case_burns_and_writes_nothing` |
| Hash matches, recorded object gone from the archive | Issued. The pre-read finds nothing, the render gives the recorded hash and key, the same bytes are written again at the same key. No `DEI-244`, no second key. | `probe_a_matching_hash_with_the_object_gone_is_issued_at_the_recorded_key` |
| An object at the burned number's key (stray-object PR) | Reported there, not here. On a local merge with that branch: the first bytes landing late at the recorded key give `DEI-281` and `DEI-279`, no drift, no orphan, nothing re-enqueued. Other bytes give `DEI-281` and `DEI-275`, no orphan. On this branch alone the object is silent: that is R11, owned by the other PR. | `CipherProbePr53eAfterPr52Test` (2 green on the merge); added by whichever of the two PRs merges second |
| Concurrent reprocess during the burn | Refused with `REFUSED_RUN_IN_PROGRESS`. It renders nothing and writes nothing. The burn chains exactly one event. After the burn, the reprocess is refused as numbered. | `probe_a_reprocess_during_a_render_mismatch_burn_is_refused` |

Other attempts, no finding:

* The archive key layout has not changed since `v0.1.0` (`ArchiveKey.of`, `SeriesKey` key parts).
  So an upgrade from 0.1.x does not make a byte-stable renderer burn its open numbers.
* There is one render call site. No other path renders a row that has a hash.
* A renderer that returns an invalid extension fails inside `RenderedDocument`. That is in the
  render `try`, so it is a render refusal, not an escape.
* `DEI-244` is reserved under this name since PR 51. `ErrorCodesUniquenessTest` is green here and
  on the merge.

### D-PR53-1 (INFO): a later event on a `DEI-244` burn says "validation refused" and records no rule id

`terminalPending` answers every burned row with `DEI-250`, and `Pending.of` records an empty rule
id. `DEI-250` is documented as "the exact bytes did not pass validation. The failing rule id is
recorded." For a `DEI-244` burn both halves are false on the later event. Nothing was refused, and
no rule id is on that event row.

Example. `F-2026-0042` burns with `DEI-244` on the retry of `invoice.finalized`. `invoice.paid`
arrives an hour later. Its row ends `FAILED_ISSUANCE`, code `DEI-250`, rule id empty. An operator
who reads the newest event for the invoice is told the bytes were refused. The real cause, renderer
drift, is only on the issuance row and the first event.

The same empty rule id exists for every burn since before this PR. This PR makes the code itself
wrong, because `DEI-244` is the first burn whose bytes passed.

Repro: `probe_a_later_event_on_a_render_mismatch_burn_names_its_rule_id`. It fails at
`2a90a00`: expected `DEI-244`, found an empty string on the later event's `last_rule_id`.

Fix (fix pass, correction only):

1. `IssuanceUnitOfWork.terminalPending(Issuance row)`: for a `BURNED` row, return
   `new Pending(InboundState.FAILED_ISSUANCE, ErrorCodes.VALIDATION_REFUSED, row.voidRuleId(), null,
   row.legalNumber().value())`. The `VOIDED` branch stays as it is. Every later event on a burned
   number then records the number's rule id: `DEI-244`, a validator rule, or a render code.
2. `ErrorCodes.VALIDATION_REFUSED` javadoc and the README state section: one sentence. "A later
   event for a burned number concludes `DEI-250` too, with the number's rule id; with rule id
   `DEI-244` nothing was refused, the bytes were not reproducible."
3. Tests: the review probe above, and one in `ResumedRenderMismatchPathsTest` or
   `VoidTerminalDispositionTest` asserting that a later event on a validator burn records the
   validator's rule id.

### Fix list

| Id | Severity | Fix | Probe |
|---|---|---|---|
| D-PR53-1 | INFO | `terminalPending` carries the row's rule id for a burned row; one sentence on `DEI-250` (javadoc, README) | `probe_a_later_event_on_a_render_mismatch_burn_names_its_rule_id` |

The review probes go into the suite before the fix. The three guards must stay green.
