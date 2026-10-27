# Security review: fix/reserved-codes-and-stuck-check

## Pass 1 (2026-10-27)

Head `c44435e`, built from main `7f08181`. Subject: D-PR49-3 (reserved codes) and D-PR50-B1
(stuck check), both LOW.

**Verdict: MERGE WITH FIXES.** Both findings are fixed and verified. Two new findings: D-PR51-1
(LOW) and D-PR51-2 (INFO).

### Numbers

| Check | Result |
|---|---|
| `CIPHER_PROBE_MAVEN=1 ./mvnw -B verify`, Docker up | core 955, starter 157, sample 9; 0 failed, 0 skipped; coverage gates met |
| Earlier review probes (`CipherProbe*`) | all green in that run, unchanged |
| `CipherProbePr50Test` stuck probe | green (no longer disabled) |
| Mutation M1: stuck read moved back after the Stripe list call | `a_number_stuck_while_the_stripe_list_fails_is_named_by_the_same_pass` red; review probe green, as the PR says |
| Mutation M2: `ARCHIVE_FOREIGN` back on `DEI-247` | 5 of 7 `ErrorCodesUniquenessTest` red |
| New probes (`CipherProbePr51eTest`, internal) | 2 red on `c44435e`, 1 guard green |

### The questions put to this pass

| # | Question | Ruling |
|---|---|---|
| a | The review's D-PR50-B1 probe stays green if the stuck check moves back after the Stripe call | Accepted as the builder states. The review probe pins the year gap; the builder's test `a_number_stuck_while_the_stripe_list_fails_is_named_by_the_same_pass` pins the order (M1 red). Together they cover the finding. No stronger review probe is needed. |
| b | The archive checks still read by year and run after the Stripe call | A new row: D-PR51-1. The year scope is by design (a bounded sample; the full re-hash is Pro). The order is not: see below. |
| c | `ReservedErrorCodes.RESERVED` changed type | Confirmed safe. The class is not in `v0.1.0`. No README, docs page or SECURITY-NOTES line names it. The CHANGELOG mentions it only under Unreleased. |
| d | `DEI-232` free, one meaning | Confirmed. Not in `ErrorCodes` on any remote branch, not in the Pro tree, not in the Pro slice 1 table. Every `DEI-` code in the internal design pages is now either defined or reserved. The Pro slice 1 names match the table entry by entry. `DEI-266` and `DEI-282` match the open running-limit branch. The `DEI-244`/`DEI-245` lines in the marker design are in its superseded first text. |
| e | Counts and mutations | Reproduced (table above). |

### D-PR51-1 (LOW): a refused Stripe key skips every check that does not need Stripe

PR 51 moves the stuck check before the Stripe list call. The other checks that need no Stripe call
still run after it: archive missing, archive drift (a stored object changed), orphan objects (an
object no row predicted), and unfinished reprocess runs. The list call throws on a refused key or
an outage. So for the whole outage, none of these checks runs.

Example: the operator revokes the restricted Stripe key on Friday. On Saturday an archived invoice
is deleted and a foreign object appears in the archive. No pass names either until the key is
fixed. The `DEI-274` stale alert fires, but it only says "the sweep is failing", not "an invoice
was deleted".

Repro: `probe_a_refused_stripe_key_skips_the_archive_checks_of_the_same_pass`. Red on `c44435e`:
no `DEI-271` and no `DEI-272` after the failing pass.

Property the fix must hold: every check runs in every pass. A failure in one check (Stripe list,
archive read) never skips another check. The pass still fails at the end with the first error, so
the sweeper still logs its WARN and `DEI-274` still fires.

Fix (builder of this PR, `ReconciliationSweep.sweep`): run the Stripe list block and the archive
block each so that its failure is kept, not thrown at once; run every other check; throw the kept
failure after the last check. Do not only move the Stripe block to the end: an archive outage would
then skip the missing-issuance check (`DEI-270`), whose window moves past the invoice. The guard
`guard_an_archive_outage_does_not_skip_the_missing_issuance_check` pins that (green now, must stay
green). A local rehearsal of this shape turned the probe green and kept `ReconciliationSweepTest`,
`CipherProbePr50Test` and `StripeAccessRefusedTest` green. Add both tests to the suite.

### D-PR51-2 (INFO): the new port method is not flagged for implementers

`IssuanceReader` is a public interface in `v0.1.0`. `openOlderThan` is a new abstract method with no
default. A host that implements the port no longer compiles; this branch had to add the method to
the test delegate in `CipherProbePr11Test`. Every earlier port method in the CHANGELOG says so
("Implementers must add it." or a BREAKING CHANGE bullet). This entry does not, and the commit has no
`BREAKING CHANGE:` footer.

Repro: `probe_the_changelog_does_not_tell_implementers_to_add_open_older_than`. Red on `c44435e`;
green with the sentence added.

Fix (fix pass, `CHANGELOG.md`, entry "The stuck check names every open number"): add "New port
method; implementers must add it." Put a `BREAKING CHANGE:` footer on the fix commit for D-PR51-1
or on its own docs commit. No history rewrite.

### Checked and not a finding

- Stuck page starvation. With more stuck numbers than one page, only the oldest are named and a WARN
  is logged. Findings are deduplicated, so the newer ones wait until older ones close. Read in the
  code and in the builder's page-of-one test, not run with two stuck numbers. Ruled not a
  finding: a page of stuck numbers already raises a page of findings and a WARN on every pass; the
  old newest-first read had the mirror gap.
- `OPEN_OLDER_THAN` builds its state list from the enum, not from input. Seller, mode, cutoff and
  limit are bound parameters. The limit is floored at 1.
- Tenancy: the read filters on seller and mode; the builder's test shows another seller and the other
  mode are not returned.
- `ReservedErrorCodes` stays JDK-only (`java.util`); ArchitectureTest green. The map is immutable.
