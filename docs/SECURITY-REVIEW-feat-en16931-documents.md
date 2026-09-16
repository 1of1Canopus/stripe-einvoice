# Security review - branch `feat/en16931-documents`

Adversarial review of pull request 3 (the EN 16931 mapper, the UBL writers and the official
validators), commit `639cf22`. Pass 1 of at most 2. Every finding was reproduced with a test that
fails on this commit.

## 2026-09-16 - pass 1

### Verdict

**MERGE WITH FIXES** - three findings, no HIGH, none of them a mechanism finding and so none a design
stop. The hardening work here is the strongest in the module so far: the XSLT and parser surface is
closed at every factory and probed with detectors that are shown to go red, the vendored artefacts are
read from the classpath under a checksum that is recomputed on every build, the amount type carries
the exponent split the spec review asked for, and the writer escapes rather than templates. The three
findings are: one buyer-controlled input class the screening function does not know about, one
configuration fact that costs a legal number per invoice instead of refusing before the allocator, and
the attribution of 56,000 lines of third-party material that is shipped in the jar and named nowhere
in it.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | - |
| MEDIUM | 3 | D3-01, D3-02, D3-03 |
| LOW | 0 | - |
| INFO | 0 | - |

### What was run

| Check | Result |
|---|---|
| `./mvnw -B verify` | BUILD SUCCESS, 449 tests (340 core, 105 starter, 4 sample), coverage gates met |
| `./mvnw -B clean verify -Prelease -Dgpg.skip=true` | BUILD SUCCESS, tests run |
| Reference guard, tree and release jars; licence carve-out check | clean |
| Sources jar after the release build | sources, the module's own resources (the schema, the 31 vendored reference files, the checksum list and the provenance page), `LICENSE`, `NOTICE`, Maven metadata; nothing else |
| Three mutations of my own | all three failed; they are the findings below |

Release hygiene lines 1, 3 and 10 pass structurally. Line 3's *content* is D3-03.

### Verified, and closed without a finding

- **The XML surface.** Every factory - document builder, SAX, schema, transformer - sets secure
  processing, refuses a DOCTYPE outright rather than merely not resolving it, blanks external DTD,
  schema and stylesheet access, disables XInclude and entity reference expansion, and installs
  resolvers that *throw* on any resolution attempt. Because the DOCTYPE is refused at the parser, the
  entity-expansion family (billion laughs and its variants) is closed by construction rather than by
  a limit someone has to tune. The existing probes cover an external entity, an external stylesheet,
  extension functions and a stylesheet that calls `document()`, and one of them is a detector that is
  shown to read the file when the hardening is removed - which is the only kind of hardening test
  worth having.
- **The vendored artefacts.** Read from the classpath through one accessor that refuses a path not in
  the checksum list; the checksums are recomputed on every build; a resource this module does not
  vendor cannot be opened, and a file whose namespace is not the one asked for is refused. Nothing is
  fetched at build or run time. The provenance page records source URL, release, publisher, licence
  and the reason each artefact was taken from where it was taken - it is the best document of its kind
  in this repository.
- **Amounts.** The money type refuses a negative scale by name (so exponent notation cannot enter),
  bounds scale and integer digits, and refuses an unsupported currency; the Stripe minor-unit exponent
  and the ISO presentation exponent are separate tables with the zero-decimal and three-decimal
  currencies enumerated and the four Stripe-specific overrides listed. The totals reconciliation from
  the previous change still runs before the allocator.
- **Nothing buyer-controlled selects anything.** No Stripe `metadata` and no checkout custom field is
  read anywhere in the mapper; the one business term that tempted it is taken from the seller profile
  and the code says so. Buyer strings are inputs to specific screened fields and nothing else.
- **Determinism and the goldens.** The same fixture renders the same bytes under another locale and
  time zone; the golden files carry no local path and no machine identity; the fixtures match them
  byte for byte.
- **The informational finding is shown, not hidden**, and a report is never `PASSED` without having
  executed: a chain that cannot run, or runs out of time, is `NOT_EVALUATED` with its own rule id, and
  the unit of work treats that as a refusal.
- **The mapping table.** Ten business terms spot-checked between the published table and the writer;
  they agree.

---

#### D3-01 · MEDIUM · a buyer can put a character in their own name that no XML parser will read

The screening function refuses C0 controls, unpaired surrogates, bidirectional overrides and values
that collapse to blank, and the writer refuses the same set again and escapes the five markup
characters. None of them knows about the characters that are **not characters in XML 1.0 at all**:
U+FFFE and U+FFFF, and the other non-characters. They are perfectly ordinary Java string content,
they survive both checks, and the writer emits them raw.

The document that comes out cannot be parsed by anything. In this module's own pipeline that is
discovered by the schema stage - which is the right place for a *rendering* defect, but this is not
one: it is an input the buyer controls. The sequence is: the invoice maps, the totals reconcile, the
number is allocated, the bytes are rendered, the parse fails, the issuance goes to
`FAILED_VALIDATION` with a consumed number that an operator now has to void with a reason. A buyer who
puts U+FFFF in their company name does that on every invoice, at will.

**Repro.** `probe_a_buyer_name_no_xml_parser_can_read_is_refused_before_the_bytes` - parameterised
over U+FFFF, U+FFFE and U+0091; the first two produce bytes that the module's own hardened parser
refuses with "invalid XML character", the third (a C1 control, legal in XML 1.0) correctly passes.

**Required change.** The screening function refuses the XML 1.0 non-characters - U+FFFE, U+FFFF, and
the U+FDD0..U+FDEF block, plus the same pair at the end of every plane - in the same place it already
refuses C0 controls, so the refusal happens at mapping time, before the allocator, with a typed code.
The writer's own validity check gains the same clause as its second line of defence. Tests: the probe
above, plus one asserting that such an invoice consumes no number.

---

#### D3-02 · MEDIUM · an application that can never validate anything still consumes a number per invoice

With no XSLT 2.0 processor on the classpath the rules cannot run, every validation is
`NOT_EVALUATED`, and the unit of work refuses - correctly, since an unevaluated rule is not a passed
rule. But the refusal happens in phase 2, *after* the allocator has run in phase 1. So each invoice
consumes a legal number, records `FAILED_VALIDATION`, and waits for an operator to void it with a
reason. A day of invoices is a day of numbers to void by hand.

This is not a fact about a document. It is a fact about the application, known before it serves a
single request, and the startup log already prints it in capitals. It therefore belongs where every
other data-independent refusal in this module already is: before the number.

It is also not a corner case. The processor is test scope, for a licence reason that is still open,
so **every host that has not supplied its own processor is in this state**, which is every host today.

**Repro.** `probe_a_configuration_that_can_never_validate_consumes_no_number` - a validator that
reports `NOT_EVALUATED` with the no-processor rule id; the series counter advances by one.

**Required change.** Make the module's ability to validate a startup condition, not a per-invoice
discovery. The cleanest form, and the one consistent with how this module already treats a missing
renderer: when the issuance path is wired and no XSLT 2.0 processor is present, **fail startup** with
the message that is currently a WARN - it names the property and the remedy already. If the coordinator
prefers to keep such an application running (the numbering API is still useful), then the unit of work
asks the validator whether it can run *before* phase 1 and refuses there with a typed code, consuming
nothing. Either is acceptable; silently burning a number per invoice is not. Tests: the probe above,
and a wiring test for whichever branch is chosen.

---

#### D3-03 · MEDIUM · the jar ships 56,000 lines of third-party licensed material and its NOTICE names none of it

This change vendors 31 files into the published core jar: the OASIS UBL 2.1 schema set, the CEN
EN 16931 schematron as compiled by the German coordination office, the XRechnung CIUS stylesheet, and
the two OpenPeppol stylesheets. The provenance page records each one's publisher and licence -
Apache-2.0 for the three schematron sets, the OASIS IPR policy for the schemas - and that page is
itself shipped in the jar, which is good practice and not the same thing as attribution.

`NOTICE`, the file the release checklist requires in every jar and the file a redistributor's
attribution obligation actually attaches to, contains only this project's own FSL notice. Apache-2.0
section 4(d) requires the attribution notices of redistributed work to be carried in the redistributing
work's own NOTICE; the OASIS material carries its notice per file, which the vendoring preserves, but
a reader of the jar has no single place that says what is in it and under what terms.

The licence gate cannot catch this, and that is the part worth fixing properly: it reads resolved
Maven dependencies, and a vendored file is not one. The gate is not wrong - it simply has a blind spot
that opened the moment this change landed, and the next vendored file will walk through it.

**Repro.** The release-profile build, then `unzip -p stripe-einvoice-core/target/*.jar META-INF/NOTICE`
- four paragraphs, all about this project, none about the 31 files beside them in the same jar. No
test in the suite asserts otherwise.

**Required change.**
- A third-party section in `NOTICE` naming each vendored artefact set, its publisher, its version and
  its licence. The text already exists on the provenance page; it needs to be in the file that
  travels as the notice.
- A check that closes the blind spot rather than a one-time edit: every path in the checksum list has
  a licence recorded in the provenance page **and** an entry in `NOTICE`, asserted by a test or by the
  existing licence tool, so the next vendored file cannot ship unattributed. Name it in the same place
  the checksum test lives.

### Open questions 17-21

Ruling only on what is mine to rule on. **17** - I do not rule on the licence choice, as instructed;
the runtime behaviour without a processor is a loud refusal and never a silent pass, which is what I
was asked to verify, and it is correct - but see D3-02 for what that refusal costs where it currently
happens. **18** - verified: nothing buyer-controlled reaches a document field without an explicit
allowlist, and no metadata is read at all. **19** (unit price as line total over quantity) and **20**
are mapping decisions with their reasoning recorded and no security content. **21** - credit-note
wiring is out of scope by the brief; the writer and the goldens for it exist and are validated, which
is the right order.

### Fix list

Three corrections, each with its probe. Pass 2 is the last pass on this branch.

1. **D3-01** - refuse the XML 1.0 non-characters in the screening function, and again in the writer's
   validity check. Probe `probe_a_buyer_name_no_xml_parser_can_read_is_refused_before_the_bytes`,
   plus one asserting no number is consumed.
2. **D3-02** - make "this application can validate" a startup condition, or ask it before phase 1.
   Probe `probe_a_configuration_that_can_never_validate_consumes_no_number`.
3. **D3-03** - attribute the vendored artefacts in `NOTICE`, and add the check that keeps the next one
   from shipping unattributed.

---

## 2026-09-16 - pass 2 (final)

Commit `5d9694b`. Pass 2 of 2: no third pass.

### Verdict

**NOT MERGEABLE**, with a two-item list applied without another review pass. Both are LOW and both are
a few lines. D3-02 and D3-03 are closed, D3-01's byte defect is closed precisely, and the deferral of
D3-01's second half is **accepted** on one condition, which is the second item below.

| Severity | Count | Ids |
|---|---|---|
| HIGH | 0 | - |
| MEDIUM | 0 | - |
| LOW | 2 | D3-04, D3-05 |
| INFO | 0 | - |

### What was run

| Check | Result |
|---|---|
| `./mvnw -B verify` | BUILD SUCCESS, 455 tests (345 core, 106 starter, 4 sample), coverage gates met |
| `./mvnw -B clean verify -Prelease -Dgpg.skip=true` | BUILD SUCCESS; the third-party attribution is in `META-INF/NOTICE` inside the published jar |
| Reference guard, tree and release jars | clean |
| Fifteen assertions of my own on the screening fix, one on the issuance refusal, two mutations of the artefact guards | the screening and issuance ones pass; the artefact mutation is D3-04 |

### The three pass-1 findings, re-verified

- **D3-01(a) - closed, and exactly scoped.** I checked the refusal over the whole class rather than
  the two code points I reported: U+FFFE, U+FFFF, U+FDD0, U+FDEF and the plane-end pairs of the
  supplementary planes (U+1FFFE, U+1FFFF, U+10FFFE, U+10FFFF) are all refused, including the
  supplementary ones, which need the surrogate pair to be decoded before the test - and they are.
  Just as important, the check does not over-refuse: U+0091 (a legal C1 control), U+FDCF and U+FDF0
  (the code points either side of the reserved block), U+FFFD, U+1FFFD and U+20AC are all still
  accepted. An over-broad rule here would refuse a legitimate buyer's name, which is the same defect
  facing the other way. `probe_every_xml_non_character_is_refused` and
  `probe_a_legal_character_beside_the_non_characters_is_still_accepted`, 14 cases, all green.
- **D3-02 - closed.** The port now asks itself a capability question, in the same shape the archive
  store already had, and the unit of work refuses before the allocator with the processor-missing
  code and no backoff. The implementation cannot be satisfied by a name that does not load: it builds
  the factory and answers from that, and it answers "no" when the factory exists but cannot be
  configured securely, which is the right direction. The starter refuses startup when the intake is
  configured and the validator says it can never run.
  `probe_a_validator_that_can_never_validate_costs_no_number_and_says_why`: zero numbered rows, state
  `FAILED_ISSUANCE`, code `DEI-XSLT-processor-missing`.
- **D3-03 - closed for content, see D3-04 for coverage.** `NOTICE` now carries a third-party section
  naming the four artefact groups, their publishers, releases and licences, and it travels inside the
  published jar.

### Ruling on the deferral of D3-01's second half

**Accepted for this pull request.** Three reasons, in the order I weighed them.

1. **The half that is closed is the half that mattered.** The finding was that a buyer-controlled
   string could produce bytes no XML parser can read, archived and filed with a tax authority. Nothing
   unparseable can leave this module now, and I verified the boundary rather than the example.
2. **What remains is the exception this module already declared and defended.** A hostile field now
   costs one number, spent on an issuance that reaches a recorded, chained failed disposition with its
   rule id, visible in the series report and closable by an operator void. That is the same residue as
   the validation refusals the numbering design ruled on when it chose byte determinism over absolute
   gap-freeness - it is bounded (one number per invoice the buyer's own purchase causes), not silent,
   and not unbounded in time.
3. **Closing it needs a mechanism, and mechanisms are not built inside fix lists.** Screening before
   the allocator means a pre-check port called before phase 1, because the mapper needs a number to
   build its input. That is a new hook other code will depend on. My own rule says such a thing gets a
   one-page design reviewed before code, and requiring it inside this branch's fix list would break
   the rule I hold the builder to.

The condition is D3-05: the residue has to be visible where this module states what it does and does
not protect, not only in an internal note and a next-pull-request plan. An exercised weakness that is
recorded only where users will not read it is the defect checklist lines 63 and 65 exist for.

---

#### D3-04 · LOW · the artefact guards read the manifest, not the tree, so a new vendored file evades both

The attribution check added for D3-03 and the checksum probe both iterate the entries of
`CHECKSUMS.txt`. Neither one walks the vendored resource directory. A file added under the reference
resources and therefore packaged into the jar, but not added to the manifest, is invisible to both:
no checksum, no attribution, both checks green.

That is precisely the scenario the D3-03 fix was written for - "so a newly vendored artefact cannot
ship without one" is the changelog's own wording - and the next person to vendor a file is the person
it was written for. The cap on severity is that the module refuses to *load* a path outside the
manifest at runtime, so an unlisted file can ship but cannot execute.

**Repro.** Write `<fake/>` to a new file under the vendored `ubl` directory, then run the attribution
test and the checksum probe: both pass, and the file would be packaged.

**Required change.** Both checks start from the shipped tree, not from the manifest: enumerate the
files under the vendored resource root and assert every one of them appears in `CHECKSUMS.txt` (and
therefore, through the existing assertion, in the provenance page and in `NOTICE`). Keep the existing
direction too - a manifest entry with no file is also wrong. Probe:
`a_vendored_file_missing_from_the_manifest_fails_the_build`, planted and then removed, in the same
class.

---

#### D3-05 · LOW · the public text reads as though the buyer-controlled cost is gone

The changelog entry for D3-01 describes the fix in the past tense - a field carrying one of these code
points "used to survive both checks and produce bytes no XML 1.0 parser could read". A reader
concludes there is nothing left. What is left is that the refusal still happens after the number is
allocated, so one number is spent and an operator has to void it, on every invoice for a buyer whose
stored name carries such a character.

The security notes carry a residual-risks list for exactly this kind of statement, and this residue is
not in it.

**Required change.** One entry in that list: a buyer-controlled field that fails screening is refused
after the allocator has run, so it costs one number with a recorded, chained failed disposition and an
operator void; closing that is the next change, and the note says so. Adjust the changelog sentence so
it does not read as a complete fix. No test needed; this is public text matching the mechanism.

### Fix list

Two items, applied without a further review pass; merge when the build and the guards are green.

1. **D3-04** - both artefact checks enumerate the shipped tree and assert manifest membership; probe
   `a_vendored_file_missing_from_the_manifest_fails_the_build`.
2. **D3-05** - the residual-risks list gains the buyer-field entry, and the changelog sentence stops
   reading as a complete fix.
