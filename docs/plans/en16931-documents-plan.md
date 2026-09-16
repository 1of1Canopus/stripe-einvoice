# Plan - PR 3: EN 16931 semantic model, XRechnung and Peppol BIS UBL writers, official validators

Branch `feat/en16931-documents`, from `main` at 6776612. This is the third pull request of module D.
PR 1 built the numbering series; PR 2 built the issuance unit of work and left two ports with no
implementation - `DocumentRenderer` and `DocumentValidator`. This one fills them, and deletes the
sample's placeholder writer in the same change.

## 1. What it has to satisfy

The two ports are already written and already reviewed. Their contracts are the constraints, not a
starting point for negotiation:

- `DocumentRenderer.render(DocumentInput)` returns the **exact bytes that will be archived**. The
  same input renders the same bytes under any time zone and any locale (D-15). A renderer that does
  not have that property has a retry that writes a second document under one number.
- `DocumentValidator.validate(byte[], DocumentInput)` validates **those bytes**, never a
  re-serialisation of a model (checklist line 15). A validator that could not run reports
  `NOT_EVALUATED`, which the unit of work treats as a refusal, never as a pass (lines 18, 57, 65).
- `DocumentInput` carries the authoritative `SourceInvoice`, the series key, the legal number, the
  issue date already converted in the seller's tax zone, and the rule-pack version.

## 2. Module layout

Everything lands in `stripe-einvoice-core` plus wiring in the starter. No new Maven module.

```
domain/en16931/          JDK only. No Spring, no Jackson, no JAXB, no XML API. (D-25)
  Money                  BigDecimal + currency, bounded on integer digits and scale
  CurrencyExponents      explicit table: Stripe minor-unit exponent and ISO 4217 presentation exponent
  DocumentTypeCode       UNTDID 1001 subset: 380, 381, 384
  TaxCategory            UNTDID 5305 subset: S, Z, E, AE, K, G, O
  VatexCode              VATEX code list subset with its legal reference text
  PostalAddress, Party, PartyIdentifier, Contact, PaymentInstruction
  DocumentLine           BT-126..BT-155
  TaxSubtotal            BG-23
  EnInvoice              the document, with EN 16931's balance rules asserted in the constructor
  BusinessTerms          per-BT length bounds, one constant per field the writers screen against
  SellerProfile          the configured issuing party, validated where it is built

adapter/xml/
  CanonicalXmlWriter     Canonical XML 1.1, written directly. Reused from the Morocco generators.
  SecureXml              the one place a JAXP factory is created, hardened. Reused and extended.
  UblProfile             XRECHNUNG_UBL | PEPPOL_BIS_UBL: customization id, profile id, extra rules
  UblDocumentWriter      EnInvoice -> UBL 2.1 Invoice / CreditNote over the canonical writer

adapter/en16931/
  StripeInvoiceMapper    DocumentInput + SellerProfile -> EnInvoice
  En16931DocumentRenderer   implements DocumentRenderer

adapter/validation/
  VendoredArtefacts      classpath-only, SHA-256 manifest, a test recomputes every one
  XsdValidator           UBL 2.1 Invoice / CreditNote schema
  SchematronValidator    a vendored compiled XSLT, run under a hardened XSLT 2.0 factory, SVRL parsed
  En16931DocumentValidator  implements DocumentValidator: XSD, then the profile's schematron chain
```

## 3. Data model: the mapping, field by field

The full BT -> Stripe table goes into `docs/mapping.md` and is generated from nothing - it is
written by hand and each row cites where the value comes from. Summary of the decisions:

| BT | Element | Source |
|---|---|---|
| BT-1 invoice number | `cbc:ID` | our legal number, never Stripe's |
| BT-2 issue date | `cbc:IssueDate` | `DocumentInput.issueDate` (already in the seller's tax zone) |
| BT-3 type code | `cbc:InvoiceTypeCode` | 380, or 381 on a credit note |
| BT-5 currency | `cbc:DocumentCurrencyCode` | `invoice.currency` upper-cased, exponent from the table |
| BT-10 buyer reference | `cbc:BuyerReference` | seller profile (`einvoice.documents.buyer-reference`), required for XRechnung |
| BT-13 purchase order | omitted in 0.1.0 | - |
| BT-24 specification id | `cbc:CustomizationID` | per profile, a constant read from the validator artefact |
| BT-25/26 preceding invoice | `cac:BillingReference` | credit notes only |
| BT-27..BT-43 seller | `cac:AccountingSupplierParty` | seller profile, validated at startup |
| BT-44..BT-56 buyer | `cac:AccountingCustomerParty` | the invoice's **frozen** customer fields (I-02) |
| BT-81/84 payment means | `cac:PaymentMeans` | seller profile; required for XRechnung (BR-DE-1) |
| BT-106..BT-115 totals | `cac:LegalMonetaryTotal` | recomputed in minor units, then scaled once |
| BG-23 VAT breakdown | `cac:TaxTotal/cac:TaxSubtotal` | one per Stripe tax rate, category from the rate |
| BT-126..BT-155 lines | `cac:InvoiceLine` | one per Stripe line item |

Two mapping points that are decisions, not transcriptions:

1. **Quantity and unit price.** Stripe carries a line's `quantity` and its net `amount`. Writing
   BT-129 = quantity, BT-146 (item net price) = the line's net amount and BT-149 (base quantity) =
   quantity is exact by construction: EN 16931 defines BT-131 = BT-129 x BT-146 / BT-149, which
   reduces to the amount Stripe charged with no division and no rounding anywhere. The alternative -
   dividing the amount by the quantity - introduces a rounding difference on the first line where it
   does not divide, which is the one class of defect this module refuses to create.
   `SourceInvoice.SourceLine` gains one field, `quantity`, to carry it.
2. **Tax category.** Stripe gives a percentage and an inclusive/exclusive flag, not an EN 16931
   category. The category comes from the tax rate's own country and percentage through a small,
   explicit table, and a zero-rated or exempt category with no VATEX reason is a refusal (D-14). No
   inference beyond that table; a rate the table does not cover is refused by name.

## 4. Writers

One writer, `UblDocumentWriter`, over `CanonicalXmlWriter`, parameterised by `UblProfile`. There is
no string templating and no concatenation anywhere: every text value goes through
`ScreenedText.screen` (already in `domain` since PR 2) with its per-BT bound, and then through the
canonical writer, which escapes. A reflection test walks every `String` component of every record in
`domain.en16931` and fails if the writer reaches one without screening it (checklist line 9).

Byte determinism (D-15, line 17): the writer touches no clock, no default zone and no default
locale; amounts are rendered with `BigDecimal.toPlainString()` at a fixed scale; every collection
the writer iterates is a `List`, never a `Map`'s entry set. The determinism test renders the same
fixture under `Europe/Berlin`/`de-DE` and `Pacific/Kiritimati`/`th-TH-u-nu-thai` and compares bytes;
the Thai locale is chosen because its default number formatting uses Thai digits, so a single
`String.format` slipping into the writer turns the test red rather than leaving it green.

### Profile differences, measured rather than assumed

Both were established by running the official artefacts over a hand-written document before any code
was written:

| | XRechnung UBL | Peppol BIS Billing 3.0 UBL |
|---|---|---|
| BT-24 | `urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0` | `urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0` |
| BT-23 profile | `urn:fdc:peppol.eu:2017:poacc:billing:01:1.0` | same |
| BT-10 buyer reference | mandatory (BR-DE-15) | optional |
| Seller contact BG-6 | mandatory (BR-DE-2, -6, -7) | optional |
| Payment means BG-16 | mandatory (BR-DE-1) | optional |
| BT-34/BT-49 electronic address | optional | **mandatory**, and the scheme must be on the EAS code list (PEPPOL-EN16931-CL008). `EM` is not on the 2026.5 list; a VAT-based scheme (9930 DE, 9957 FR, 9925 BE) or a configured one is used |

## 5. Validators

Offline, vendored, checksummed. Four artefacts, all Apache-2.0, under
`stripe-einvoice-core/src/main/resources/reference/`:

| Artefact | Version | Source |
|---|---|---|
| `EN16931-UBL-validation.xsl` | XRechnung validator configuration 3.0.2 / 2026-08-31 | itplr-kosit/validator-configuration-xrechnung release v2026-08-31 (itself the CEN EN16931 1.3.x schematron) |
| `XRechnung-UBL-validation.xsl` | XRechnung 3.0.2 | same release |
| `CEN-EN16931-UBL.xslt` | openpeppol 2026.5 | phive-rules-peppol 4.5.6 |
| `PEPPOL-EN16931-UBL.xslt` | openpeppol 2026.5 | phive-rules-peppol 4.5.6 |

plus the UBL 2.1 XSD set (OASIS), carried over from the Morocco module's vendored copy.

`PROVENANCE.md` records, per file, where it came from, the release tag, the retrieval date and the
licence; `CHECKSUMS.txt` records the SHA-256; a test recomputes every one of them and fails the
build on a drift. Nothing is downloaded at build time or at run time (D-24).

### The XSLT engine, and why it is a test-scope dependency

These stylesheets are XSLT 2.0. The JDK ships XSLT 1.0 only, so an XSLT 2.0 processor is needed, and
in practice that means Saxon-HE, which is **MPL-2.0**. This repository's licence gate denies MPL for
anything it ships (`pom.xml` `includedLicenses`, `tools/check-third-party-licences.sh`
`DENIED_PATTERNS`), and weakening a licence gate is not a builder's decision. So:

- No compile-time dependency on Saxon anywhere. The schematron validator asks
  `TransformerFactory.newInstance(className, loader)` for a configured processor class name, which
  defaults to Saxon's, and reports `NOT_EVALUATED` with a reason when no XSLT 2.0 processor is on
  the classpath. `NOT_EVALUATED` is a refusal in the unit of work, so an application with no
  processor issues **nothing** rather than issuing something unvalidated. Secure by default.
- Saxon-HE is declared `test` scope in `stripe-einvoice-core`, the starter and the sample, so CI
  runs the real official suites on every build, and the licence gate (which excludes test scope)
  stays untouched.
- README and the docs page say, in the quickstart, that the host adds the processor, name the
  licence, and say what happens if it does not. QUESTIONS.md carries this as a numbered decision.

### Hardening (D-07, checklist lines 10-14)

Every factory comes from `SecureXml`, one test per factory:
`disallow-doctype-decl=true`, `FEATURE_SECURE_PROCESSING=true`, `ACCESS_EXTERNAL_DTD`,
`ACCESS_EXTERNAL_SCHEMA` and `ACCESS_EXTERNAL_STYLESHEET` blanked, XInclude off, entity expansion
bounded, Saxon's `allow-external-functions` off by feature URI (no compile dependency needed), an
`EntityResolver`/`URIResolver`/`XMLResolver` that **throws** on any resolution attempt, an output
bound on the SVRL result and a wall-clock timeout on the transform. Each control has a mutation
test: the probe is shown red with the control removed, in the PR body.

The schematron result is SVRL, parsed with the same hardened parser. `fatal`, `error` and `warning`
flags fail the document; `information` flags are recorded in the report and do not. That threshold
is stated in the docs with the one rule it currently affects (BR-DE-TMP-32, delivery date).

## 6. Credit notes (D-13)

The narrow slice the brief allows, and no mechanism: `DocumentTypeCode` carries 380, 381 and 384;
a credit note is written as a UBL `CreditNote` document with **positive** amounts and a
`cac:BillingReference` to the invoice it corrects; a negative total on a 380 is refused. PR 2's
`Totals.reconcile` already refuses a non-positive total, so no Stripe credit note reaches the writers
in 0.1.0 - the type code, the sign convention and the writer path exist and are tested from the
model downward, which is what D-13 asks for, and the `credit_note.created` intake stays out. If
closing that gap turns out to need a second numbering series or a second state machine, that is a
mechanism and it stops here with a report.

## 7. Test list

Domain, no Spring, no container:
1. `Money` refuses negative scale, `1E+2000`, exponent notation, a magnitude past the bound, an
   unknown currency; each with its own stable code (lines 1, 2, 4).
2. `CurrencyExponents` lists the zero-decimal and three-decimal currencies by name; an unlisted
   currency is a refusal and there is no default of 2 (line 3).
3. `EnInvoice` refuses a document whose lines do not sum to BT-106, whose breakdown does not sum to
   BT-110, or where BT-112 != BT-109 + BT-110 (line 5).
4. `TaxCategory`/`VatexCode`: a zero-rated or exempt category with no reason code is refused (D-14).
5. `DocumentTypeCode`: a negative total on 380 is refused; 381 with positive amounts is accepted.

Writer:
6. Golden file per fixture (FR B2B, DE B2B with Leitweg-ID, BE, reverse charge, credit note), bytes
   compared exactly.
7. Determinism: same fixture, two zones and two locales, identical bytes (line 17).
8. Every string field of the model is screened: a buyer name containing `</cbc:Name>`, a `]]>`, an
   `&entity;`, a C0 control, an unpaired surrogate, a bidi override - each refused or escaped, and
   the produced document still parses (lines 7, 8, 9).
9. Reflection test enumerating the model's string components against the screened set (line 9).
10. ArchUnit: no `Instant.now`, `LocalDate.now`, `ZoneId.systemDefault`, `Locale.getDefault`,
    `String.format` without `Locale.ROOT` in `adapter.xml` or `adapter.en16931` (line 16).

Validator:
11. Every fixture is validator-clean under its profile's full chain (XSD, EN 16931 schematron,
    profile schematron).
12. A document with a deliberately wrong tax breakdown is FAILED with the rule id recorded.
13. `NOT_EVALUATED` when no XSLT 2.0 processor is present, and the unit of work refuses on it.
14. XXE: a document with an external entity is refused, and the probe is shown resolving the entity
    with the hardening removed (line 13).
15. A stylesheet-side resolution attempt throws rather than returning empty (line 11).
16. Checksum manifest recomputation (D-24).
17. The validator is handed the archived bytes, not a re-serialisation (line 15) - asserted by
    mutating the bytes between render and validate and seeing the refusal.

Starter and sample:
18. Seller profile validation at startup: each required field refused by name when absent.
19. An XRechnung profile with no buyer reference fails at startup, naming the property (BR-DE-15).
20. The sample receives a Stripe test-mode fixture event and writes a validated XRechnung and,
    beside it, a validated Peppol BIS UBL for the same input.

Probes: `CipherProbeDocumentsTest` in core, `CipherProbeDocumentWiringTest` in the starter.

## 8. Risks

- **Schematron compile cost.** Each of the four stylesheets takes 0.6-1.1 s to compile. `Templates`
  are compiled lazily and cached per artefact; the cache is the bean's own field, never a static.
  A first issuance therefore pays about two seconds. Documented; a warm-up on startup is deliberately
  not added, because it would run before the operator has seen the startup log.
- **Repository size.** The four stylesheets are 2.3 MB. That is the price of "no download at build
  time"; the alternative is the supply-chain shape D-24 exists to refuse.
- **Peppol and XRechnung both move.** The versions are pinned, recorded with their retrieval date,
  and the checksum test makes a silent swap impossible. A version bump is a deliberate commit with
  new golden files.
- **Scope creep into a mechanism.** Reading BT-10 from Stripe metadata needs an allowlist mechanism
  that the spec does not describe. It is not built here; the value comes from configuration, and the
  gap is a numbered question.

## 9. What is deliberately not in this PR

Factur-X and PDF/A-3 (Pro, Decision 7); delivery; Connect; the `release.yml` CVE gate; reading BT-10
or any other BT from Stripe metadata; discounts and allowances (PR 2 already refuses an invoice whose
total is not subtotal + tax); foreign-currency BT-111 and the FX rate source (D-16), which needs a
rate provenance store and is a mechanism.
