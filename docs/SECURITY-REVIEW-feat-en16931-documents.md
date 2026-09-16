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
