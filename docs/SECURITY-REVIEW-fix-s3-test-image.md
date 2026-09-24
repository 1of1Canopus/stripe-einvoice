# Security review - fix/s3-test-image (module D, PR 16)

Reviewer: security review. Scope: test infrastructure and supply chain. Head: 21f552f.
Date: 2026-09-24. Docker up, `CIPHER_PROBE_MAVEN=1`, own worktree.

The change replaces the archive suite's S3-compatible server (a MinIO image whose registry
started demanding authentication) with Adobe's S3Mock, pinned by digest, and drops the
`testcontainers-minio` dependency. Production code is untouched.

## Numbers

| What | Result |
|---|---|
| `S3ArchiveStoreTest` on the new image | 7/7 green |
| Review probes (`CipherProbePr16Test`) | 2/2 green on the branch |
| Mutation (digest replaced by tag `5.2.3`) | suite still green (7/7 + 4/4), only the review probe red |
| Anonymous manifest fetch of the pinned digest | HTTP 200 with an anonymous pull token, 2026-09-24 |
| Tag `5.2.3` -> digest today | `sha256:ab01a6...fca92`, matches the pin |

## D16-01 - the swap does not weaken what the archive test proves. Not a finding.

Repro attempt: a probe issues, against the new server, an unconditional `PutObject` twice on the
same key (must overwrite: a server that refused here would make the conditional test pass for the
wrong reason) and then the same second write with `If-None-Match: *` (must be refused with 412).
Both hold: the 412 the adapter depends on is caused by the header, not by a server that refuses
every second write. The three archive tests that assert `CREATED` / `ALREADY_IDENTICAL` /
content-conflict therefore still exercise the conditional path end to end.

The dual branch in `the_startup_probe_does_not_lie_about_this_server` takes the "server honours
If-None-Match" arm on this image, as it did on the previous one. The other arm is not dead cover:
the not-atomic refusal (`DEI-242`) is asserted at unit level in `ArchiveCapabilityProbeTest`
against a store that overwrites. No second provider and no extra unit test is required. Closed
without a code change.

## D16-02 - LOW - nothing in the suite refuses a tag-only container image

The digest pin is a convention, not a guard. Repro: replace the digest in the archive suite's
container support with `adobe/s3mock:5.2.3` and run the suite - `S3ArchiveStoreTest` 7/7 and
`SourceAssertionsTest` 4/4 stay green, so a future edit (or a merge conflict resolved the easy way)
silently returns the module's S3 behaviour measurement to a moving tag. Both images in the tree are
pinned today; only the review probe notices when one is not.

Fix: add a source assertion, in `SourceAssertionsTest` (it already asserts source-level properties
and must be widened to scan `src/test/java` as well as `src/main/java`), named
`every_container_image_reference_is_pinned_by_digest`: every `DockerImageName.parse(...)` argument
in the tree contains `@sha256:`, concatenated string literals joined before the check. The review
probe `CipherProbePr16Test#probe_every_container_image_reference_is_pinned_by_digest` is the
reference implementation and goes red on the mutation above.

## D16-03 - LOW - the test surface now uses an undeclared dependency

The new container support uses `GenericContainer` and `Wait` from `org.testcontainers:testcontainers`,
which no module declares: it arrives transitively through `testcontainers-postgresql` and
`testcontainers-junit-jupiter`. The removed `testcontainers-minio` was a declared coordinate; its
replacement is not. There is no `maven-dependency-plugin` analyze step in the build, so a later
version bump that reshapes those transitive trees breaks test compilation for a reason nothing in
the pom explains, and the module's test-scope supply chain is no longer fully stated.

Fix: declare `org.testcontainers:testcontainers`, `<scope>test</scope>`, in
`stripe-einvoice-core/pom.xml` next to the other Testcontainers coordinates (version from the
managed BOM, as the siblings are).

## D16-04 - INFO - the image's licence and provenance are recorded only in a Javadoc

The rationale, licence (Apache-2.0), registry and digest of the new image live in one Javadoc
comment on the container support class; the database image beside it carries no such record at all,
and the third-party licence gate covers Maven coordinates, not container images. Nothing outside
that comment tells a future release which registries the build must be able to reach anonymously.

Verified while attacking this: a pull failure is a refusal, not a skip. There is no
`assumeTrue`, no `@Disabled`, no `@EnabledIf` and no `continue-on-error` on any path, so a registry
401 or a `429 toomanyrequests` from an anonymous, rate-limited shared runner fails the second,
tests-running build of the reproducibility check, which fails the release job before anything is
signed or uploaded. That is the correct failure mode and no change is required to it.

Fix: a short "test container images" table in the module's internal release document - image,
digest, tag at pin time, registry, licence, and the line that the release requires anonymous pull
access to those registries, with the decision recorded for what to do on a rate-limit refusal
(re-run the release; the pipeline must never be made to authenticate to a registry with a secret
that the reproducibility build does not otherwise need).

## D16-05 - the deleted MinIO support leaves no references. Not a finding.

Full-tree search (sources, README, CHANGELOG, docs, workflows, tools, scripts, sample, security
notes): the only remaining mention is one sentence of Javadoc in the capability probe naming MinIO
as an example of a deployment whose conditional-write behaviour varies. It is a true statement
about deployments, not a reference to the deleted test support. No compose file references the old
image. Closed without a code change.

## Verdict

**MERGE WITH FIXES** - D16-02 and D16-03 (LOW) and D16-04 (INFO). No HIGH, no MEDIUM. The
production adapter is untouched and the property the archive suite exists to prove still holds
against the replacement server.
