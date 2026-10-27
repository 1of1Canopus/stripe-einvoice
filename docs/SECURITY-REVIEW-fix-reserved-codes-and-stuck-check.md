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

## Pass 2 (2026-10-27)

Head `96af207`. Limited pass: the fix for D-PR51-1 and D-PR51-2, and the new surface it adds.

**Verdict: NOT MERGE.** D-PR51-1 and D-PR51-2 are verified. The fix adds two new findings:
D-PR51-3 (LOW) and D-PR51-4 (INFO). Under the no-allowance rule both are fixed before merge.

### Numbers

| Check | Result |
|---|---|
| `CIPHER_PROBE_MAVEN=1 ./mvnw -B verify`, Docker up | core 958, starter 157, sample 9; 0 failed, 0 skipped; coverage gates met |
| Pass 1 probes (`CipherProbePr51eTest`) | 3 of 3 green |
| Mutation "stop at the first failed check" (rethrow at once in `Failures.call`) | `probe_a_refused_stripe_key_skips_the_archive_checks_of_the_same_pass` red; restored |
| Pass 2 probes (`CipherProbePr51fTest`, internal) | 2 variants green, 2 probes red on `96af207` |

### D-PR51-1 (LOW): verified

The probe is green, the guard stays green, and the mutation turns the probe red. Two adversarial
variants, both green:

- `variant_archive_down_and_key_refused_every_other_check_still_runs`. Stripe refuses the key and
  every archive read fails, in the same pass. The key-refused, stuck, series-read and
  unfinished-reprocess checks all still run. The pass throws the Stripe failure (`DEI-219`) with the
  archive failure (`DEI-241`) attached as suppressed.
- `variant_series_read_failure_reports_no_orphan`. The series read fails while the archive holds a
  real document. No orphan finding (`DEI-272`) is written. The unfinished-reprocess check still
  runs. The pass fails.

### D-PR51-2 (INFO): verified

The CHANGELOG entry says "Implementers must add it." and the fix commit carries a
`BREAKING CHANGE:` footer naming `openOlderThan`. The probe is green.

### D-PR51-3 (LOW): one shared exception instance stops the rest of the pass

`Failures.call` keeps the first failure and calls `first.addSuppressed(e)` for each later one. If
two checks throw the same exception instance, that call is `first.addSuppressed(first)`. The JDK
answers with `IllegalArgumentException: Self-suppression not permitted`, thrown from inside the
catch block. It escapes the pass. The checks after it do not run, and the pass ends with an
exception that carries no `DEI` code.

Example: a host archive adapter behind a circuit breaker (a switch that stops calls to a failing
service) throws one preallocated "archive unavailable" exception. The archive is down. The
"us to archive" check throws it, then the "archive to us" check throws the same object. The
unfinished-reprocess check (`DEI-276`) never runs, which is the gap D-PR51-1 closed.

Repro: `probe_one_shared_exception_instance_aborts_the_rest_of_the_pass`. Red on `96af207`:
`IllegalArgumentException` at `ReconciliationSweep$Failures.call`, and the unfinished check is not
called.

Fix (builder of this PR, `ReconciliationSweep.Failures.call`): attach a later failure only when it
is not the same instance as the first and is not already among its suppressed. Do not change the
order of checks. The probe must turn green; `CipherProbePr51eTest` and `ReconciliationSweepTest`
stay green.

### D-PR51-4 (INFO): a failed series read names only one of the two skipped checks

When the series read fails, both archive checks are skipped. The WARN says only
"reconciliation check 'archive to us' not run". Nothing names "us to archive". An operator who asks
why `DEI-271` and `DEI-275` stopped finds no line for it.

Repro: `probe_a_failed_series_read_names_only_one_of_the_two_skipped_checks`. Red on `96af207`.

Fix (builder of this PR, `ReconciliationSweep.sweep`, the `else` branch after the series read): the
WARN names both checks, "us to archive" and "archive to us".

### Checked and not a finding

- A partial series read cannot feed the orphan check. `seriesRows` builds its list and returns it
  only when every series key was read. One failing key fails the whole read, so the orphan check
  never runs with half the known keys.
- The failure log carries the check name and the `DEI` code or the class name, never the message.
  A host adapter's message cannot reach the log through this path.
- The pass still throws, so the sweeper WARN and `DEI-274` keep their meaning. The first failure is
  the one thrown; a test can read the others as suppressed.
