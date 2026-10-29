# Security review: fix/orphan-check-window

## Pass 1 (2026-10-29)

Head `1ef0995`. Subject: the orphan walk (D-PR52-3, MEDIUM, on main since 0.1.0) and the trailing
slash (D-PR51-5). Built from the design page "the orphan check examines every key, against every
row", Revision 3, approved with changes D1 to D7 at the second design review.

**Verdict: MERGE WITH FIXES.** No HIGH. The walk does what the page says for the cases the page
names. Every mutation re-run here goes red. Eight findings: four MEDIUM, two LOW, two INFO. Three
of the MEDIUM findings are the D1 class again, through doors the page did not name. A volume or
bucket writer stops the walk for ever with an unreadable folder, a deep tree, a long
undecodable name, or a control character in an S3 key. The fourth MEDIUM is the cycle alert
(`DEI-283`): after the first complete cycle it never fires again. None needs a new mechanism. Each
fix is a correction inside a class this PR built.

### Numbers

| Check | Result |
|---|---|
| `CIPHER_PROBE_MAVEN=1 ./mvnw -B clean verify`, Docker up (PostgreSQL by Testcontainers) | BUILD SUCCESS. core 1126 run, 0 failed, 2 skipped; starter 161, 0 failed; sample 9, 0 failed. Line coverage core 91.1%, starter 89.7%; branch 76.6% and 79.3% |
| The 2 skipped tests | the two Linux-only D1 tests (`probe_two_undecodable_names_stop_the_walk_for_ever`, `two_names_that_decode_alike_are_two_orphans_and_the_walk_moves_on`), skipped on macOS. Run by this review in a Temurin 21 Linux container: 18 of 18 `FilesystemArchiveListingTest` green. CI on Linux also green on this head |
| Earlier review probes (`CipherProbe*`), unchanged | 73 classes, 355 tests, all green |
| PR 52 probes (`CipherProbePr52Test`), enabled on this branch | 6 of 6 green on `1ef0995`. On `50344e1` (probes enabled, walk not yet built) the two window probes are red. The neighbour probe goes red when the trailing slash is removed (M6). The file differs from the internal original only in formatting and in the port method of its test double |
| New review probes (internal, `CipherProbePr57Test`, `CipherProbePr57S3Test`, `CipherProbePr57StarterTest`) | 12 probes. 9 red on `1ef0995` on macOS. 1 more red on Linux, in the container. 2 green on `1ef0995` and red on the mutation the suite misses (D-PR57-7, D-PR57-8) |
| CI on `1ef0995` | 7 of 7 checks green |

### Mutations re-run by this review

Each control was broken in the working tree, its tests run, then restored. The tree was clean
after each one.

| # | Mutation | Tests run | Red output |
|---|---|---|---|
| M1 | order guard removed (`compareUnsigned(current, previous) <= 0` made `false`) | T22 | 1 failure: "Expecting code to raise a throwable" (no `DEI-284`) |
| M2 | `archive_key <> ''` dropped from the lookup | `KnownKeysIndexTest` | 2 of 3 red, generic and custom plan: plan is "Bitmap Index Scan on einvoice_issuance_number_unique", not the partial index |
| M3 | cursor moved before the findings | T8c `a_failed_finding_write_does_not_move_the_cursor` | 1 failure: "Expecting empty but was" the stray key as the cursor |
| M4 | an invalid key looked up, raw subject | `probe_a_1000_byte_stray_key_stops_the_walk_for_ever` | 1 error: `DEI-102` (SQLState 22001), "value too long for type character varying(512)" |
| M5 | `contains("/.einvoice-")` back | `probe_a_file_under_an_einvoice_dot_folder_is_never_examined` | 1 failure |
| M6 | trailing slash removed in `ArchiveKey.sellerFolder` | PR 52 probes, T5 in two classes | 3 red, each "expected: 0" (the neighbour's object reported) |
| M7 | subject cut first, then escaped (D4) | `OrphanWalkPathsTest`, `FilesystemArchiveListingTest`, `CipherProbePr52Test`, `ReconciliationSweepTest` | **none red**: D-PR57-7 |
| M8 | case probe rethrows when its scratch file cannot be removed (D7) | `FilesystemArchiveListingTest`, `FilesystemArchiveStoreTest`, `OrphanWalkPathsTest` | **none red**: D-PR57-8 |
| M9 | `0 <=` bound on the temp file age dropped (D2) | `a_temporary_file_dated_in_the_future_is_an_orphan` | 1 failure |
| M10 | compare-and-set made a plain update (`revision = ?` replaced by a term always true) | T16 and `a_second_first_cursor_is_a_lost_compare_and_set` | 2 failures |
| M11 | the `DEI-283` check removed from the pass | T18 and `a_listing_that_always_fails_still_raises_the_cycle_alert` | 2 failures, "expected: 1" |

### The design items against the code

| Item | Evidence | Ruling |
|---|---|---|
| R1 one listing per pass, findings before cursor, insert race | M3, M10 red; T3 records one call per pass | holds |
| R2, D6 the index term, both plan modes | M2 red in both modes | holds |
| R3, D2 only the own temp file, one hour, never in the future | M5, M9 red | holds |
| R4, D3 lower-case rule, message pinned | T20, T21 green; message pinned in T20 | holds on the starter path with the bare store; not on a wrapped store or without the starter: D-PR57-6 |
| R5 seller `.` refused | `probe_seller_dot_lists_every_seller_of_the_mode` green | holds |
| R6, D4 bounded subject, escape then cut | M4 red; the 4-byte bound test the page requires is missing | D-PR57-7 |
| R7 order guard, `endOfFolder` from the store | M1 red; T23 green | holds for the store's own order; reachable through a name: D-PR57-3, D-PR57-5 |
| R8 when a cycle counts, empty mount, `DEI-283` | M11 red; T26 green | `DEI-283` dead after the first cycle: D-PR57-1. Empty mount in mid cycle completes: D-PR57-4 |
| R9 walk runs when the series read fails | T25 green; QUESTIONS 61 change checked | holds |
| R10 `listAfter` abstract | compile | holds |
| R11 C13 bucket prefix, C14 upgrade SQL | both T4 runs green; the SQL's columns match `einvoice_finding` | holds |
| R12 to R14 paths and mutations | 9 of the 16 PR mutations re-run here (M1 to M6, M9 to M11), all red where the PR says | holds |
| D1 a name never stops the walk | Linux run: 18 of 18 green | holds for two names that decode alike; not for a long one, a folder, or S3: D-PR57-2, D-PR57-3, D-PR57-5 |
| D5 both directory bucket suffixes | T24 green, both suffixes | holds |
| D7 cleanup never stops startup | code is right; no test holds it | D-PR57-8 |

### The questions put to this pass

| # | Question | Ruling |
|---|---|---|
| a | Names that do not decode | Two short ones: fixed (D1). One long one: the decoded key grows three bytes per raw byte and passes the 4,096-byte bound: D-PR57-3 |
| b | Case folding | Holds where the starter checks the bare store. A wrapped store or a host without the starter is not checked: D-PR57-6. A Windows trailing dot is suspected below, not counted |
| c | Temp files | Shape, last element, regular file, one hour, future time: all hold (M5, M9). A temp-shaped link or folder is listed |
| d | The per-pass guard | Checks the store's order as designed. Reachable through a name on the filesystem (D-PR57-3) and on S3 (D-PR57-5) |
| e | `DEI-283` | Fires for the first cycle only: D-PR57-1. That also makes residual 5 of SECURITY-NOTES ("then `DEI-283`") untrue |
| f | `DEI-284` | Raised by the guard and by the directory bucket refusal. Correct code, but a writer can trigger it for ever (D-PR57-3, D-PR57-5) |
| g | `DEI-285` | Message pinned (D3). Path coverage: D-PR57-6 |
| h | Second hops: what the round 2 fixes introduced | The `#inode:` key has no length bound (D-PR57-3). The case probe's cleanup is untested (D-PR57-8). The escape order is untested (D-PR57-7) |

### D-PR57-1 (MEDIUM): the cycle alert never fires after the first complete cycle

`OrphanCursor.completed(now)` sets `cycleStartedAt` and `lastCompleteCycleAt` to the same instant.
`OrphanCursor.stale` asks whether a completion is "not before" the start. After the first complete
cycle that is always true, until the store changes. So `DEI-283` never fires again. Two cases
stay silent for ever: a listing that fails on every pass (the `DEI-241` case C9 names), and a cycle
that takes months because the archive outgrew `orphan-pages`. Property 2 of the page says the bound
is "stated and observable". After one cycle it is not observable.

Repro: `probe_the_cycle_alert_never_fires_after_the_first_complete_cycle`. One document, one-hour
alert. The first pass completes a cycle. Then the listing fails for four hours. Expected one
`DEI-283`, got none. `probe_a_cursor_completed_once_is_never_stale_again` shows the rule alone:
completed, then 30 days later with a 7-day alert, `stale` is false.

Fix (builder): `OrphanCursor.stale` is the age of `cycleStartedAt` against the alert and nothing
else. `completed` and `restartedOn` already start the next cycle at their instant, so "no complete
cycle since the start" is implied. T18 gains a second cycle. SECURITY-NOTES residual 5 stays true
only with this fix.

### D-PR57-2 (MEDIUM): an unreadable folder, or a tree past the path limit, stops the filesystem walk for ever

`FilesystemArchiveStore.listAfter` throws from `visitFileFailed` and `postVisitDirectory` for any
failure but "no such file". So one entry the service cannot read, anywhere under the seller folder,
fails the whole listing. That happens on every pass (`DEI-241`). No key of the seller is examined
again until someone finds the entry. The message has no path, by design, so the operator sees "the
document archive is unavailable" while every read works. Two doors, both open to a writer of the
volume with no database access:

- A folder with mode `000`. Probe: `probe_an_unreadable_folder_stops_the_filesystem_walk_for_ever`
  (red on macOS and Linux, `AccessDeniedException`).
- A tree deeper than the path limit, built with relative `mkdir`. Probe:
  `probe_a_tree_deeper_than_the_path_limit_stops_the_filesystem_walk_for_ever` (red on macOS and
  Linux, "File name too long").

This is the D1 property applied to entries, not only to names. Whatever a writer creates can
produce a reported orphan, never a stopped walk. QUESTIONS 63 proposed the second door as a stated
residual. Refused: no `openat` is needed. The walk only has to report the entry it cannot enter
and carry on.

Fix (builder): in `listAfter`, a failure on an entry strictly below the walk's start folder is
not fatal. Any `IOException` other than `NoSuchFileException` counts, in `visitFileFailed` and in
`postVisitDirectory`. The entry becomes one listed key, `<decoded relative path>#unlistable`. That
key is invalid as an `ArchiveKey`, so the sweep reports it with no lookup. It is bounded as in
D-PR57-3, and the walk continues. A failure on the root or on the start folder stays fatal, and so
does the `requireRoot` after the walk. One WARN gives the count, never the path. Both probes go to
the suite. Strike residual 5 from SECURITY-NOTES and close QUESTIONS 63 with this ruling.

### D-PR57-3 (MEDIUM): a long undecodable name reaches the order guard

D1 lists a name that does not round-trip as `<decoded path>#inode:<file key>`. Decoding writes
U+FFFD, three UTF-8 bytes, for each bad byte. Five folders and a file, each named with 255 bytes
of `\xff`, make a path of about 1.5 KB, well under `PATH_MAX`. The key is 4,632 bytes. The guard
refuses any key over 4,096 bytes with `DEI-284`, on every pass. The revision says "No name can
reach it". This one does.

Repro (Linux, Temurin 21 container, `C.UTF-8`): the store returned `live/acme/2026/INV-a.xml` (24
bytes) and the decoded key (4,632 bytes, `...#inode:(dev=d0,ino=3407929)`). The guard refused both
passes: "returned a key longer than 4096 UTF-8 bytes". Probe:
`probe_an_undecodable_name_longer_than_the_cursor_bound_stops_the_walk_for_ever`, Linux only, red
in the container.

QUESTIONS 62(c) is accepted for the column bound and refused for its second sentence. A key over
the bound may be refused only if no store can make one from a name. The filesystem store can. A
system with a longer path limit (Windows long paths) may also make one with no bad byte at all;
that is not reproduced here.

Fix (builder): every key the filesystem store emits is at most 4,096 UTF-8 bytes. A longer one
is cut at a code point boundary, so that the suffix fits. It then ends in `#inode:<file key>`, or
in `#unreadable-name` when there is no file key. The suffix keeps it unique and invalid. State the
bound in the `listAfter` port contract, with one constant that `OrphanCursor.MAX_POSITION_BYTES`
uses too. The guard stays as it is.

### D-PR57-4 (LOW): an archive that empties in mid cycle completes the cycle

The empty-mount rule tests the cycle's count (`keysThisCycle == 0`). A volume that goes away in
mid cycle answers "no key, end of folder" after a position that is not empty. The cycle is
stamped complete, though the keys after the position were never examined. The next pass then
fails with `DEI-241`, which is right. But the false completion resets the clock that D-PR57-1
fixes. Probe: `probe_an_archive_that_goes_empty_mid_cycle_completes_the_cycle` (three documents,
one key a pass; after one pass the listing is empty; the cycle is complete).

The shipped stores report the end in the same answer as the last keys. An empty answer at a
position that is not empty means the keys were removed or the volume is gone. The module never
deletes an archive object.

Fix (builder): R8 gains a fourth condition. A cycle completes only when the listing that ends it
has at least one key, or when the cycle started at the empty position. Otherwise the cursor goes
back to the empty position with `cycleStartedAt` unchanged and `keysThisCycle` 0. No completion is
recorded. The next pass then meets the existing empty-mount rule, or walks again from the start.

### D-PR57-5 (MEDIUM): an S3 key that XML cannot carry stops the walk for ever

`S3ArchiveStore.listAfter` does not set `encoding-type=url`. The recorded request was
`list-type=2&max-keys=10&prefix=live%2Facme%2F`. AWS documents the risk: a `ListObjectsV2` "200 OK
response can contain valid or invalid XML". It also says "the XML 1.0 parser can't parse certain
characters, such as characters with an ASCII value from 0 to 10", and `encoding-type=url` is the
documented way to receive such keys. A bucket writer can do two things:

- Put a key with `\x01`. The SDK fails with "Could not parse XML response", so the listing fails
  with `DEI-241` on every pass. On the S3-compatible test server the PUT is accepted, and the
  listing answers HTTP 500 on every pass.
- Put `INV-\r.xml` and `INV-\n.xml`. XML end-of-line handling makes both `INV-\n.xml`, so the
  listing holds a duplicate and the guard refuses it with `DEI-284` on every pass. The local
  server answer shows the collapse: two keys parsed as one.

D1 says "S3 returns object keys as UTF-8 strings it has validated, so this path does not exist
there". That is true of decoding, and false of the XML transport.

Probes: `probe_a_control_character_in_an_s3_key_stops_the_walk_for_ever` (a local server that
answers as the AWS pages describe: raw characters, or percent-encoded when asked) and
`probe_an_s3_compatible_server_fails_the_listing_on_a_control_character_key` (the suite's S3
server). Both red.

Fix (builder): every `ListObjectsV2Request` in `S3ArchiveStore.listAfter` sets
`encodingType(EncodingType.URL)`. The SDK decodes `Key`, `Prefix` and `StartAfter` itself. This
review tried that one line in the working tree, then restored it: both probes green, and 6 of 6
`S3ArchiveListingTest` green. Correct the D1 sentence in the design page and in the docs.

### D-PR57-6 (LOW): the case rule runs only for the starter's bare store

`ArchiveStoreStartupProbe` checks `DEI-285` only when `store instanceof FilesystemArchiveStore`.
Two paths skip the check:

- A host that decorates the store (metrics, tracing, retry).
- A host that builds the store and the sweep without the starter, which the store's javadoc
  invites.

On a volume that ignores case, seller `Acme` then starts and shares a folder with `acme`.
Property 3 fails exactly as C3 described. Probes, on a case-insensitive volume (macOS default,
skipped elsewhere and named as skipped):
`probe_a_wrapped_filesystem_store_skips_the_seller_case_refusal` (starter; the bare store is
refused, the wrapped one starts) and
`probe_the_filesystem_store_walks_a_mixed_case_seller_on_a_case_insensitive_volume` (core).

Fix (builder): the rule moves into the store, the one place every path goes through.
`FilesystemArchiveStore.putIfAbsent` and `listAfter` refuse a key or prefix whose seller element
has an upper-case letter on a volume that ignores case (`DEI-285`, the D3 message). The volume
answer is probed once per store instance and kept. At startup, `ArchiveStoreStartupProbe` drops
the `instanceof` and calls `store.listAfter(ArchiveKey.sellerFolder(mode, sellerId), "", 1)`, so
the refusal still comes at startup, through any wrapper.

### D-PR57-7 (INFO): no test pins "escape, then cut"

R6 and D4 require "a 1,024-byte key made only of 4-byte characters" to pin the subject bound. The
suite has none: the long-key test uses ASCII `x`. Mutation M7 (cut first, then escape) passes the
whole orphan suite. With M7, 200 surrogate halves become 1,200 characters. The finding write then
fails on `varchar(512)` and the walk stops at that name. Probe:
`probe_a_key_of_four_byte_characters_keeps_its_subject_bound_and_the_walk_moves`. It is green on
`1ef0995` and red on M7 (`DEI-102`).

Fix (fix pass): add the probe to the suite.

### D-PR57-8 (INFO): no test holds D7

The case probe catches a failed delete of its scratch file and logs a WARN, as D7 says. No test
fails when that catch rethrows (mutation M8). Probe:
`probe_a_case_probe_that_cannot_remove_its_scratch_file_still_answers` (macOS; a user append-only
folder lets the create succeed and the delete fail). It is green on `1ef0995` and red on M8.

Fix (fix pass): add the probe to the suite. A Linux twin needs `chattr +a`, which needs root, so
macOS is the runner.

### Suspected, not counted

A seller id with a trailing dot (`acme.`) is valid and lower case. On Windows the file API strips a
trailing dot, so `live/acme./` and `live/acme/` may be one folder. That would be the C3 class with
two distinct lower-case ids. Attempt: not reproducible here (macOS and Linux keep the dot). No
probe, not a finding. The CI matrix has no Windows runner, so the claim "two distinct lower-case
ids never share a folder on any volume" is untested there. The builder should either refuse an
element that ends in `.` in `ArchiveKey`, or narrow the claim in the README.

### Rulings on the builder's questions

- **QUESTIONS 61:** accepted. Each changed line follows a ruled item. The `CipherProbePr51fTest`
  change is stricter (it now asserts the lookup ran). The other review probe changes are the port
  method of their test doubles only.
- **QUESTIONS 62:** (a) accepted, M10 red. (b) accepted. (c) accepted for the column, refused for
  "a longer key is refused as `DEI-284`": D-PR57-3. (d) accepted. (e) accepted: no clock means
  nothing hidden, the safe direction.
- **QUESTIONS 63:** refused as a residual: D-PR57-2.

### Fix list for this PR

| Finding | Severity | Owner | Done when |
|---|---|---|---|
| D-PR57-1 | MEDIUM | builder | both D-PR57-1 probes green; T18 covers a second cycle |
| D-PR57-2 | MEDIUM | builder | both D-PR57-2 probes green on macOS and Linux; SECURITY-NOTES residual 5 removed; QUESTIONS 63 closed |
| D-PR57-3 | MEDIUM | builder | the Linux probe green in CI; bound named in the port contract |
| D-PR57-4 | LOW | builder | probe green; R8 condition in the docs page |
| D-PR57-5 | MEDIUM | builder | both S3 probes green; D1 sentence corrected |
| D-PR57-6 | LOW | builder | both probes green on a case-insensitive volume; `instanceof` gone |
| D-PR57-7 | INFO | fix pass | probe in the suite |
| D-PR57-8 | INFO | fix pass | probe in the suite |

Design stop: none. Each fix stays inside a class this PR built and reuses its own form. D-PR57-2
and D-PR57-3 use the D1 key, D-PR57-5 is one request option, and D-PR57-6 moves an existing check.
The builder who built the walk applies D-PR57-1 to D-PR57-6. The probes are in the internal probes
folder as `CipherProbePr57Test` (core), `CipherProbePr57S3Test` (core, S3 package) and
`CipherProbePr57StarterTest` (starter). The fixer adds them to the suite before changing
production code. For each fix, the PR body shows the probe red, then green.
