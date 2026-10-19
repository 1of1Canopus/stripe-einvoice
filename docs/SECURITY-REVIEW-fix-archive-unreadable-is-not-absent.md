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

## Pass 2 (2026-10-19)

Head reviewed: `c25505b` (fix pass, main merged in). Re-verification of D-RTR-9 to D-RTR-11,
then an attack on the new surface.

### Numbers

| Item | Result |
|---|---|
| `./mvnw -B clean verify` | BUILD SUCCESS, 678 + 140 + 9 = 827 tests, 0 failures, 0 skipped, coverage gate met |
| Existing `CipherProbe*` suites | all green, unchanged |
| `CipherProbePr41Test` | 11 of 11 green |
| Mutations re-run (five) | each one turns its probes red; code restored after each |
| New probes `CipherProbePr41Pass2Test` | 3 red (`probe_*`), 4 green (`confirm_*`) |

### Prior findings

- D-RTR-9 (MEDIUM): fixed. `requireRoot()` runs before any empty answer. Mutation "no-op
  `requireRoot`" turns both root probes red.
- D-RTR-10 (LOW): fixed for the last name of the path. Mutation "follow links" turns the link
  probe red; mutation "skip the file-type check" turns the FIFO (named pipe) probe red. A linked
  folder further up the path is still open: see D-RTR-12.
- D-RTR-11 (LOW): fixed. Mutation "back to `Files.walk` plus `isRegularFile`" turns the
  dropped-entry probe red. The fix pass had no mutation for the file-prefix probe, so this pass
  ran one: `throw new IOException("archive prefix is not a folder")` replaced by
  `return List.of()`. `probe_list_reports_empty_when_the_prefix_is_a_file` turns red.

### D-RTR-12 (LOW) - a linked folder on the way still leaves the root

`NOFOLLOW_LINKS` (do not follow a symbolic link) applies to the last name of the path only. The
kernel still follows a link in any folder name before it. Precondition: write access to the
archive volume, the same as D-RTR-10. Pre-existing, but D-RTR-10's fix was meant to keep every
read inside the root, and it does not.

Example 1, read: `live/acme` is a link to `/srv/other`. `get` on
`live/acme/2026/INV-000001-<sha>.xml` returns the bytes of `/srv/other/2026/INV-000001-<sha>.xml`.
Example 2, write: `live` is a link to `/srv/other`. `putIfAbsent` creates `/srv/other/acme/2026/`
and writes the legal document there. The next run reads it back as archived.
Example 3, list: `live` is a link to `/srv/other`. `list("live/acme")` names files outside the
root as archive keys.

Probes (red on `c25505b`): `probe_get_follows_a_linked_folder_out_of_the_archive_root`,
`probe_put_writes_through_a_linked_folder_out_of_the_archive_root`,
`probe_list_walks_a_linked_folder_out_of_the_archive_root`, in
`stripe-einvoice-core/src/test-pending/java/.../adapter/file/CipherProbePr41Pass2Test.java`.
Run with `./mvnw -Pprobes-pending -pl stripe-einvoice-core test -Dtest=CipherProbePr41Pass2Test`.

Fix (correction, same class, no new mechanism): in `FilesystemArchiveStore`, a private
`requireFoldersBelowRoot(Path target)` reads each existing name from the root down to the
target's folder with `readAttributes(..., NOFOLLOW_LINKS)`. A name that exists and is not a plain
folder throws `ARCHIVE_UNAVAILABLE`. Call it in `readObject` before the stat (a missing name there
means absent, then `requireRoot` as today), in `putIfAbsent` before `createDirectories` and again
after it, and in `list` on `start` (the start itself included) before the walk. The root itself
stays followed: see "root is a link" below. Move the probe file into `src/test/java` when green.

### Attacks that did not break

- Root replaced by a link to another folder. `requireRoot` follows it. That is right: a root
  that is a link to a mounted volume is a normal deployment, and the root is operator
  configuration, not data. A dangling root link is `ARCHIVE_UNAVAILABLE` for `get` and `list`.
  Pinned by `confirm_a_root_that_is_a_link_to_a_folder_is_followed_by_design` and
  `confirm_a_root_link_that_dangles_is_unavailable`. Not a finding.
- Root disappears between `requireRoot` and the read. In `get` the root check runs after the
  empty answer, so a volume that drops (a one-way event) can never read as absent. The residual
  case needs the root to vanish and come back between two system calls; acceptable, stated.
  In `list` the check runs before the walk, so a drop in that window could return an empty list.
  Suspected only: 3000 rounds with a thread moving the root away at a random moment never
  produced an empty list. Not a finding. If the D-RTR-12 fix touches `list` anyway, calling
  `requireRoot` again before each empty return costs one line and closes it.
- A folder at the key. `putIfAbsent` gets "already exists" from the link, then `readObject`
  refuses a non-regular file: `ARCHIVE_UNAVAILABLE`, nothing overwritten. Pinned by
  `confirm_put_over_a_folder_at_the_key_is_unavailable`.
- A very deep tree. `Files.find` walks with a loop and a stack of open folders, not recursion,
  so there is no stack overflow. Depth is bounded by the path length limit of the OS. Running out
  of file handles surfaces as `UncheckedIOException`, which maps to `ARCHIVE_UNAVAILABLE`. A
  300-level tree lists in milliseconds (`confirm_list_on_a_deep_tree_finishes`). Note, not a
  finding of this PR: `list` sorts every entry before applying `limit`, so memory grows with the
  archive size. That was the same on main.

### Verdict

**MERGE WITH FIXES**: D-RTR-12 (LOW). Correction inside `FilesystemArchiveStore`; three probes in
`src/test-pending` must turn green and move to `src/test/java`.
