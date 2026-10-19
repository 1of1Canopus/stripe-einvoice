# Security review - fix/archive-unreadable-is-not-absent (module D, PR 41)

## Re-verification pass (2026-10-19)

Reviewer: security review. Head: 243a30f. Own worktree, Docker up, JDK 21, macOS (APFS).
Finding under re-verification: D-RTR-8 (MEDIUM). Production code was not edited by the review;
the mutation below was applied to a scratch copy and restored with `git checkout`.

### Numbers

| What | Result |
|---|---|
| `./mvnw -B clean verify` on 243a30f | BUILD SUCCESS: 628 / 139 / 9 tests, 0 failures, 0 skipped, coverage gates met |
| Existing review probes (`CipherProbe*`, unchanged) | all green inside the 628 |
| `FilesystemArchiveStoreUnreadableTest` | 8/8 green |
| Mutation: the whole production diff reverted | 5/8 of the fix's tests red (matches the fix pass) |
| New review probes (`CipherProbePr41Test`, 11 tests) | 6 `probe_*` red on 243a30f; 5 `confirm_*` green |
| Same 11 under the mutation | 9 red: the `confirm_*` tests are real guards, not tautologies |
| Full verify with the probe class present | 639 in core, 6 failures, all six are the new probes |

The probe class is kept in the internal probe folder, not on this branch, so the branch stays
green. The fix pass adds it to `stripe-einvoice-core/src/test/java/com/housedevinci/einvoice/adapter/file/`
before touching production code.

### D-RTR-8 - verified fixed

`get` no longer asks `Files.exists`. It reads, and only `NoSuchFileException` (the kernel said
ENOENT) is absence. `list` reads the prefix attributes and maps the walk's
`UncheckedIOException` to `ARCHIVE_UNAVAILABLE`. The fix's tests go red when the diff is
reverted. The S3 store was re-read: only `NoSuchKeyException` is empty, a 403 is
`ARCHIVE_UNAVAILABLE`. Closed.

### Attacks that hold (confirmed, not findings)

| Attack | Observed | Probe |
|---|---|---|
| Directory at the key path | `IOException` "Is a directory" -> `ARCHIVE_UNAVAILABLE` | `confirm_get_of_a_directory_at_the_key_is_unavailable` |
| A parent of the key is a file | ENOTDIR, `FileSystemException` -> `ARCHIVE_UNAVAILABLE` | `confirm_get_under_a_parent_that_is_a_file_is_unavailable` |
| Last component of 300 chars (ENAMETOOLONG) | `FileSystemException` -> `ARCHIVE_UNAVAILABLE`. With a missing parent the kernel answers ENOENT first, which is correct absence | `confirm_get_of_a_name_too_long_is_unavailable` |
| Directory with `r--` (listable, not searchable) | `list` and `get` both `ARCHIVE_UNAVAILABLE` | `confirm_list_of_a_readable_but_not_searchable_directory_is_unavailable` |
| `..` in a key or a prefix | `ArchiveKey` refuses with `INVALID`; `resolve` checks again; `list("../")` is empty, never outside the root | `confirm_traversal_keys_are_refused_before_the_store` |
| EMFILE (too many open files) | measured in a JVM under `ulimit -n 64`: `FileSystemException`, never `NoSuchFileException`, so `ARCHIVE_UNAVAILABLE` | scratch run, no unit test (a JVM cannot lower its own fd limit) |

### D-RTR-9 (MEDIUM) - a missing archive root reads as "no object"

The fix trusts ENOENT. But ENOENT is also what the kernel says when the archive root itself is
gone: the volume was unmounted, or the directory was moved while the process runs. Then every
`get` returns empty and every `list` returns an empty list. That is the D-RTR-8 class again:
"cannot tell" answered as "nothing there".

Example: the archive volume drops at 10:00. At 10:05 a redelivery arrives for an invoice issued in
January. `integrityFailure` gets an empty answer and the event concludes
`ARCHIVED_DOCUMENT_TAMPERED`, terminal, no retry. The document is intact on the volume. The
sweep at 10:10 lists nothing and reports zero orphans. The S3 store does not do this: a missing
bucket is `NoSuchBucket`, not `NoSuchKey`, so it is already `ARCHIVE_UNAVAILABLE`.

Probes: `probe_get_reports_absent_when_the_archive_root_itself_is_gone`,
`probe_list_reports_empty_when_the_archive_root_itself_is_gone`.

Fix (correction, same two methods): in `FilesystemArchiveStore`, add a private
`requireRoot()` that reads `Files.readAttributes(root, BasicFileAttributes.class)`. A
`NoSuchFileException`, any other `IOException`, or `!isDirectory()` throws `unavailable(e)`.
Call it in `get`'s `NoSuchFileException` branch and in both absent branches of `list`, before
answering empty. Only then is ENOENT absence: the root is there and the object is not.

### D-RTR-10 (LOW) - `get` opens whatever sits at the key

`get` (and `identicalOrConflict`, which `putIfAbsent` uses) calls `Files.readAllBytes`. It
follows symbolic links and opens any file type. Pre-existing, not introduced by this PR, but it
is the method the PR rewrote. Precondition: write access to the archive volume.

Example 1: a FIFO (named pipe) is created at a key. `get` blocks in `open(2)` forever, with no
timeout. The thread never returns; the issuance or the sweep that called it hangs with it.
Measured: still blocked after 5 s, the probe times out.

Example 2: a symbolic link at a key points to `/etc/hosts`. `get` returns the bytes of a file
outside the archive root. The `resolve` check is lexical only, so it does not see links.

Probes: `probe_get_blocks_forever_on_a_fifo_at_the_key`,
`probe_get_follows_a_symbolic_link_out_of_the_archive_root`.

Fix (correction): one private `readObject(Path)` used by `get` and `identicalOrConflict`.
First `Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)`.
`NoSuchFileException` goes to the D-RTR-9 root check, then empty (in `identicalOrConflict` it is
`ARCHIVE_UNAVAILABLE`: the name existed a moment ago). `!isRegularFile()` (link, FIFO,
device, directory) throws `ARCHIVE_UNAVAILABLE`. Then read with
`Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)` and `readAllBytes()`. The residual
race (a regular file swapped for a FIFO between the stat and the open) needs write access to the
volume and is stated in the class javadoc.

### D-RTR-11 (LOW) - `list` still turns "cannot tell" into "nothing here"

Two paths remain. (1) The walk filters with `Files::isRegularFile`, which follows links and
answers false on any error. An entry it cannot classify (a link loop, a dangling link, a FIFO)
is dropped from the list without a word. (2) A prefix that exists but is a file returns an empty
list. In both cases the store can tell something is wrong and says nothing.

Example: someone leaves a dangling link `live/acme/2026/INV-000002-...xml` in the archive. The
reconciliation sweep lists the prefix to find objects nobody can explain. The entry is not
listed, so no `RECON_ORPHAN_OBJECT` is recorded. The one alarm built for "unexplained object"
is silent about it.

Probes: `probe_list_silently_drops_an_entry_it_cannot_stat` (accepts either outcome: listed, or
`ARCHIVE_UNAVAILABLE`), `probe_list_reports_empty_when_the_prefix_is_a_file`.

Fix (correction): replace `Files.walk(start)` plus `filter(Files::isRegularFile)` with
`Files.find(start, Integer.MAX_VALUE, (path, attrs) -> !attrs.isDirectory())`. The walk's
attributes are read without following links, so a link, FIFO or device is listed as a key and
the sweep reports it as an orphan. Keep the `.einvoice-` part-file filter. In the prefix check,
an existing path that is not a directory throws `ARCHIVE_UNAVAILABLE` instead of returning empty.

### Contract this fix must hold for the retry PR (D-RTR-1)

The retry code is not on this branch, so this is stated, not probed:

1. The store answers empty only when the root is present and the kernel said ENOENT for the
   object (after D-RTR-9). Every other read fault is `ARCHIVE_UNAVAILABLE`.
2. `ARCHIVE_UNAVAILABLE` from `get` on the resume and redelivery paths is a retryable fault. It
   must never conclude `ARCHIVED_DOCUMENT_TAMPERED`, never re-render, and never allocate a new
   number. Today it propagates out of `redelivery`; the retry PR must pin where it lands.
3. The retry PR ships a probe that injects `ARCHIVE_UNAVAILABLE` on `get` in each resume state
   and asserts the number counter is unchanged and the event is re-picked after backoff.
4. Suspected, not reproduced as a burn: a mount point that is present but empty (volume not
   mounted underneath) is indistinguishable from a new archive at this layer. The retry PR's
   design page must say what the pre-read does when the database says "archived" and the store
   says "absent". If that answer needs a marker object or another mechanism, it is a design stop
   and its own PR.

### Verdict

**MERGE WITH FIXES**: D-RTR-9 (MEDIUM), D-RTR-10 (LOW), D-RTR-11 (LOW). All three are
corrections inside `get`, `list` and `identicalOrConflict`; no new mechanism. The fix pass adds
`CipherProbePr41Test` first, then applies the fixes; all 11 tests must be green, with the six
`probe_*` tests flipping.
