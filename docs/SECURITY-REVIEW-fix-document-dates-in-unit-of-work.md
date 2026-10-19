# Security review: fix/document-dates-in-unit-of-work (PR 39)

## Pass 1 (2026-10-19)

Head reviewed: `f6cba33`. Base: main `40581e3`. Finding under fix: D-PR36-6 (LOW, design stop): the
date rule EINVOICE-DATES lived only in the replaceable module validator, so a host validator let a
backdated invoice reach the archive. Design: the reviewed design page with its Revision 2 (one
rule, two callers; private array; foreign-reader WARN; reader errors refused).

**Verdict: MERGE WITH FIXES.** No HIGH, no MEDIUM, no LOW. One INFO, about the PR text only
(D-PR39-1). The mechanism holds on every path the design names. D-PR36-6 is closed by this branch.

### Numbers

| check | result |
|---|---|
| `./mvnw -B clean verify`, Docker up | BUILD SUCCESS; core 608, starter 139, sample 9; 0 failures, 0 skipped; coverage gate passed |
| P2, P17, P18, P19, P20 probes | green |
| builder mutations 1 to 11 and 8b, re-run by the reviewer | each RED on exactly the tests the PR body names, then restored |
| extra mutation 12 (reader gets the private array, no copy) | RED on `a_port_that_keeps_its_array_cannot_change_the_archived_or_hashed_bytes` |
| new review tests (this pass) | 12, all green on the head |

### Attacks and results

1. **Are the two callers able to disagree on the same bytes?** No. Both call the same
   `UblDocumentDates.read` and the same static `DocumentDatesRule.check` with `input.dating()`.
   Test `the_validator_and_the_unit_of_work_rule_agree_on_the_same_bytes` runs six variants of a
   real rendered invoice (true, backdated, padded, CDATA, comment inside the date, BT-7 added)
   through both. Whenever the validator says PASSED, the rule says PASSED. Example: CDATA
   `<![CDATA[2026-01-10]]>` passes both; a comment inside the date is NOT_EVALUATED in both.
2. **Is the private copy really what is archived?** Yes. Test
   `a_port_that_keeps_its_array_cannot_change_the_archived_or_hashed_bytes`: the renderer keeps
   the array it handed over, a colluding validator backdates that array and its own, and the
   reader blanks its own array after reading. The event completes; the row's hash equals the
   SHA-256 of the archived object, which equals the SHA-256 of the bytes as rendered. Mutation 6
   and mutation 12 each turn it RED.
3. **The reader.** Only direct children of the root are read, by namespace and local name.
   * A second `cbc:IssueDate` inside `ext:UBLExtensions` is ignored; the root one is read.
   * A second prefix bound to the cbc namespace is still the same element: AMBIGUOUS.
   * A `cbc` prefix rebound to another namespace is not BT-2: no issue date, refused.
   * The right date written only in a comment is not read: refused.
   * A comment or a processing instruction inside the date: UNREADABLE.
   * Newline, tab, no-break space, figure space around the date: refused (no trim).
   * A character reference that spells the right date reads as that date; the archived XML
     means that date, so this is correct.
   * UBL 2.1, 2.2 and 2.3 share the same namespace URIs; there is no variant to confuse.
   * BT-7 is only `/*/cbc:TaxPointDate`; BT-8 only `/*/cac:InvoicePeriod/cbc:DescriptionCode`;
     line-level copies are ignored (the builder's `a_line_period_and_a_line_delivery_are_not_read`).
   * A lower-case root `invoice` is UNREADABLE.
   * A 200 000-level deep element tree does not escape the reader.
4. **Can a host silence the foreign-reader WARN?** Only by the operator's own logging
   configuration, which is the operator's choice, not a module path. The check is the class
   name plus the code source; the class is final, so a subclass or a lambda is foreign
   (`a_subclass_or_a_namesake_is_not_the_module_reader`). Not a finding.
5. **The catch around `read`.** A RuntimeException, an `EInvoiceException` and a `null` answer
   each end `FAILED_ISSUANCE`, `VALIDATION_NOT_EVALUATED`, rule `EINVOICE-DATES`, row
   `FAILED_VALIDATION`, nothing archived; the cause is logged server-side only (P18 probe and
   `a_reader_that_answers_nothing_or_refuses_is_not_evaluated`). A thrown `Error` is not caught,
   the same as the validator call; not a finding.
6. **The archive-retry path (open-questions item 38).** Reproduced as the builder states: on the
   retry of a `FAILED_ARCHIVE` row, a date refusal ends the event `FAILED_ISSUANCE` with `DEI-113`
   and the row stays `FAILED_ARCHIVE` without the rule id. Example: the S3 write times out; the
   retry renders BT-2 3 January for a number of 10 January; nothing is archived, but the record
   says "illegal transition", not "dates refused". Judgement: fail-closed holds, the same
   behaviour already exists on main for a validator refusal (the state machine is not touched by
   this diff), and the fix is a data-model change (enum, trigger, schema script). It goes in its
   own PR with its own two passes, as proposed, and that PR must merge before the 0.2.0 tag.
   Severity stays as recorded by the builder (LOW). It does not block this PR.
7. **The R9 edits.** `CipherProbePr11Test` and `CipherProbePr14bTest` each gain exactly one
   argument, `new UblDocumentDates()`, and nothing else. The internal copies carry the same line.
8. **The changed assertions.** Four, not five (D-PR39-1). Each changes form only:
   `<Delivery>d</Delivery>` becomes
   `<cac:Delivery><ActualDeliveryDate>d</ActualDeliveryDate></cac:Delivery>` with the same date
   (2026-01-03, 2026-01-16, 2026-01-16, 2026-01-15).

### Findings

**D-PR39-1 (INFO). The PR body counts five changed assertions; the diff has four.**
Repro: `git diff origin/main...f6cba33 -- '*/src/test/*' | grep '^-.*assert\|^-.*contains'` lists
four removed `<Delivery>` assertions (one in `InvoiceDateOrderPathsTest`, three in
`IssuanceUnitOfWorkTest`). Fix: the PR body line under "Existing tests changed" says "Four
assertions" and names the two files. No code change.

### Closed by this branch

D-PR36-6, with the design-review items D-PR36-11 to D-PR36-21: each has its probe or test green
and its mutation RED.
