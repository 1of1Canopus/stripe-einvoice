# The documents: EN 16931, XRechnung and Peppol BIS Billing 3.0

What this module writes, where every value comes from, what it refuses, and which rules judge it.

The claim made here is narrow on purpose: **this module produces output that the reference
validators accept.** Those validators are vendored, run in CI on every build, and run again before
any document is archived. It is not a conformance certification, and nothing on this page should be
read as one.

---

## 1. The two profiles

| | XRechnung 3.0 (UBL) | Peppol BIS Billing 3.0 (UBL) |
|---|---|---|
| `einvoice.documents.profile` | `xrechnung-ubl` | `peppol-bis-ubl` |
| Syntax | OASIS UBL 2.1 `Invoice` / `CreditNote` | the same |
| BT-24 specification identifier | `urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0` | `urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0` |
| BT-23 business process | `urn:fdc:peppol.eu:2017:poacc:billing:01:1.0` | the same |
| Extra mandatory terms | BT-10 buyer reference; BG-6 seller contact with telephone and email; BG-16 payment instructions; the seller's street, city and post code; BT-31 or BT-32 | BT-34 and BT-49 electronic addresses, with schemes from the Peppol EAS code list |

Both identifiers are read out of the validator stylesheets themselves rather than out of a
specification somebody remembered - the XRechnung one out of the `XR-CIUS-ID` variable in
`XRechnung-UBL-validation.xsl` - so this module and the validator that judges it cannot disagree
about which version is being claimed.

One profile per application, and therefore one legal original per invoice. Rendering the other
profile for the same invoice is a call on the renderer; it is not a second archived document under
the same number, and inventing one would be a mechanism nobody has designed.

---

## 2. The validator artefacts

Vendored under the core module's own package root,
`stripe-einvoice-core/src/main/resources/com/housedevinci/einvoice/reference/` - not at the root of
the classpath, where a library's `/reference/` is one jar ordering away from resolving somebody
else's file of the same name. Every file has a SHA-256 in `CHECKSUMS.txt` and a provenance note in
`PROVENANCE.md`. **Nothing is downloaded at build time or
at run time.** Every checksum is recomputed on every build, and a stylesheet's checksum is
recomputed again before it is compiled at run time: a stylesheet is executable code, run over
documents that are filed with a tax authority, and a checksum nothing recomputes is a comment.

| Artefact | Version | Publisher | Licence |
|---|---|---|---|
| `ubl/2.1/**` | UBL 2.1 runtime schemas | OASIS, as distributed in the KoSIT validator configuration | OASIS IPR policy |
| `schematron/en16931/EN16931-UBL-validation.xsl` | XRechnung validator configuration `v2026-08-31` | KoSIT, compiled from `ConnectingEurope/eInvoicing-EN16931` | Apache-2.0 |
| `schematron/xrechnung/XRechnung-UBL-validation.xsl` | XRechnung **3.0.2**, release `v2026-08-31` | KoSIT / IT-Planungsrat | Apache-2.0 |
| `schematron/peppol/CEN-EN16931-UBL.xslt` | OpenPeppol **2026.5** | OpenPeppol, via `phive-rules-peppol` 4.5.6 | Apache-2.0 |
| `schematron/peppol/PEPPOL-EN16931-UBL.xslt` | OpenPeppol **2026.5** | as above | Apache-2.0 |

Retrieved 2026-09-16. Sources, release tags and the exact archive entries are in `PROVENANCE.md`.

### The chain, per profile

- **XRechnung:** UBL 2.1 schema, then `EN16931-UBL-validation.xsl`, then
  `XRechnung-UBL-validation.xsl`.
- **Peppol:** UBL 2.1 schema, then `CEN-EN16931-UBL.xslt`, then `PEPPOL-EN16931-UBL.xslt`.

The schema runs first and, when it fails, the schematron is deliberately **not** run: over a
document that is not structurally UBL, a schematron reports rules that never matched anything, and
a report of rules that did not match is not a report that they passed.

### Severity

`fatal`, `error` and `warning` make a document invalid. `information` does not; the finding is
recorded and reported, never filtered.

One informational finding is expected today and is not silenced:

> **BR-DE-TMP-32** - an invoice should state a delivery date (BT-72), an invoicing period (BG-14) or
> a per-line period (BG-26). This edition carries none of the three, because the authoritative
> invoice it reads does not yet carry a line period. The rule's own flag is `information`, so the
> document is valid; a test asserts that this finding, and only this finding, appears.

### The XSLT processor

The schematron is XSLT 2.0 and the JDK ships an XSLT 1.0 processor, so the core module carries
`net.sf.saxon:Saxon-HE` as a **runtime** dependency. Nothing to add: a default install validates.

The processor is still resolved **by class name** (`einvoice.documents.xslt-processor`, default
`net.sf.saxon.TransformerFactoryImpl`) through JAXP, and no class here imports a Saxon type. Two
reasons that seam stays:

1. it is what `DocumentValidator.canValidate()` asks - "is a processor of that name loadable, and
   can it be configured securely" - which is how an application that could never validate is
   refused **before** a legal number is allocated rather than once per invoice after one is spent;
2. it is how a host substitutes its own XSLT 2.0 processor: exclude the dependency, add another,
   set the property.

Exclude it and put nothing in its place and every validation reports `NOT_EVALUATED`, the issuance
unit of work treats that as a refusal, and **the application issues nothing** - an application whose
intake is configured refuses to start instead. An unevaluated rule is not a passed rule.

Saxon-HE is MPL-2.0, the only dependency of this project under that licence, admitted by the
licence gate for that coordinate alone (see `tools/check-third-party-licences.sh`). It brings
`org.xmlresolver:xmlresolver` (Apache-2.0) with it; the resolvers this module installs still throw
on every resolution attempt, so neither one can reach the file system or the network.

### How the stylesheets are run

They are third-party code, run over untrusted XML, so every one of these is set and each has its own
test:

- DOCTYPE **refused** (`disallow-doctype-decl`), not merely unresolved - no doctype means no entity
  declaration, and the billion-laughs and external-entity attacks are the same declaration twice.
- `FEATURE_SECURE_PROCESSING`, and the processor's own `allow-external-functions` switch set by
  feature URI, so extension functions cannot reach Java from a stylesheet.
- `ACCESS_EXTERNAL_DTD`, `ACCESS_EXTERNAL_SCHEMA` and `ACCESS_EXTERNAL_STYLESHEET` blanked; XInclude
  off.
- An entity resolver and a URI resolver that **throw** rather than returning empty, so a changed JDK
  default produces a refusal and never a silent fetch.
- The SVRL output is bounded from inside the transform, and the transform runs on a bounded pool
  with a wall-clock timeout. A timeout, an output bound or a saturated pool is `NOT_EVALUATED`.
- Schema imports resolve only to vendored files, selected by name **inside the checksum manifest**
  and then accepted only if the file's own `targetNamespace` is the namespace that was asked for.

### What the findings carry

The `DocumentValidator` port returns **rule identifiers and severities only**. A handful of
XRechnung rules interpolate values out of the document being judged into their own assertion text,
and those findings are persisted, rendered in an operator view, and read by whoever holds the logs.
The full text is reachable through `En16931DocumentValidator.validateInDetail`, which is documented
as carrying document content, for a caller that has decided it may look.

---

## 3. The mapping table, BT by BT

Everything below comes from the invoice as the **authoritative Stripe API** reports it, never from
the webhook payload, and from the fields Stripe **froze onto the invoice at finalisation**, never
from the live customer record. A field EN 16931 requires and Stripe does not have is a typed refusal
naming the Stripe field, never a blank element.

### The document

| BT | UBL | Source | Notes |
|---|---|---|---|
| BT-1 | `cbc:ID` | **this module's legal number** | Never Stripe's. Decision 2: we allocate, Stripe's own number is a reference |
| BT-2 | `cbc:IssueDate` | `status_transitions.finalized_at`, converted in `einvoice.seller.tax-zone` | Required property, no default. A 23:30 UTC invoice lands in the day its tax authority would put it in |
| BT-3 | `cbc:InvoiceTypeCode` / `cbc:CreditNoteTypeCode` | 380, or 381 on a credit note | 384 exists and is written only when a caller asks |
| BT-5 | `cbc:DocumentCurrencyCode` | `currency`, upper-cased | Refused if the exponent table does not list it |
| BT-9 | `cbc:DueDate` | not mapped in 0.1.0 | Omitted rather than guessed |
| BT-10 | `cbc:BuyerReference` | `einvoice.documents.buyer-reference` | Configuration only. See section 5 |
| BT-22 | `cbc:Note` | not mapped in 0.1.0 | `invoice.description` and `footer` are free text a dashboard user controls; they are on the list, not in this release |
| BT-23 | `cbc:ProfileID` | the profile constant | |
| BT-24 | `cbc:CustomizationID` | the profile constant | |
| BT-25/26 | `cac:BillingReference` | the corrected document's number and date | Credit notes and corrective invoices only, and mandatory for both |
| BT-122 | `cac:AdditionalDocumentReference` | `invoice.number` | Stripe's own number, carried as a reference so an auditor can tie the legal document back to the account |

### The seller (BG-4), from configuration

| BT | UBL | Property |
|---|---|---|
| BT-27 | `cac:PartyLegalEntity/cbc:RegistrationName` | `einvoice.seller.name` |
| BT-28 | `cac:PartyName/cbc:Name` | `einvoice.seller.trading-name`, falling back to the name |
| BT-29/30 | `cac:PartyIdentification/cbc:ID`, `cac:PartyLegalEntity/cbc:CompanyID` | `einvoice.seller.legal-id` + `.legal-id-scheme` (ISO 6523 ICD) |
| BT-31 | `cac:PartyTaxScheme/cbc:CompanyID` (scheme `VAT`) | `einvoice.seller.vat-id` |
| BT-32 | `cac:PartyTaxScheme/cbc:CompanyID` (scheme `FC`) | `einvoice.seller.tax-registration-id` |
| BT-34 | `cbc:EndpointID` | `einvoice.seller.electronic-address` + `.electronic-address-scheme`, or derived from BT-31 |
| BT-35..BT-40 | `cac:PostalAddress` | `einvoice.seller.address.*` |
| BT-41..BT-43 | `cac:Contact` | `einvoice.seller.contact.*` |

### The buyer (BG-7), from the frozen invoice fields

| BT | UBL | Stripe field |
|---|---|---|
| BT-44 | `cac:PartyLegalEntity/cbc:RegistrationName`, `cac:PartyName/cbc:Name` | `customer_name` |
| BT-48 | `cac:PartyTaxScheme/cbc:CompanyID` | `customer_tax_ids[0].value` |
| BT-49 | `cbc:EndpointID` | derived from `customer_tax_ids[0].value` with the country's Peppol EAS scheme, or `einvoice.documents.buyer-electronic-address` |
| BT-50/51 | `cbc:StreetName`, `cbc:AdditionalStreetName` | `customer_address.line1`, `.line2` |
| BT-52 | `cbc:CityName` | `customer_address.city` |
| BT-53 | `cbc:PostalZone` | `customer_address.postal_code` |
| BT-55 | `cac:Country/cbc:IdentificationCode` | `customer_address.country` |

`customer_email` is **not** written to the document. It is buyer PII with no business term that
needs it here, and every field that is written is one somebody asked for.

### The lines (BG-25)

| BT | UBL | Stripe field |
|---|---|---|
| BT-126 | `cbc:ID` | `lines.data.id`, or the line's ordinal |
| BT-129 | `cbc:InvoicedQuantity` | `lines.data.quantity` |
| BT-130 | `@unitCode` | `C62` ("one, piece"), UN/ECE Recommendation 20 |
| BT-131 | `cbc:LineExtensionAmount` | the line's taxable amount |
| BT-146 | `cac:Price/cbc:PriceAmount` | the line's taxable amount |
| BT-149 | `cac:Price/cbc:BaseQuantity` | `lines.data.quantity` |
| BT-151 | `cac:ClassifiedTaxCategory/cbc:ID` | derived from the tax rate - section 4 |
| BT-152 | `cac:ClassifiedTaxCategory/cbc:Percent` | `tax_rate.percentage`, zero on every zero-rate category |
| BT-153 | `cac:Item/cbc:Name` | `lines.data.description` |

**Why BT-146 is the line total and BT-149 is the quantity.** EN 16931 defines
BT-131 = BT-129 x BT-146 / BT-149. Setting BT-146 to the line's own net amount and BT-149 to the
same quantity as BT-129 makes that identity exact, for every line, with no division. The obvious
alternative - dividing the amount by the quantity to get a unit price - produces a rounding
difference on the first line where the division is not exact, and a document whose lines do not add
up to its own total is the single defect this module exists to refuse.

### The totals

| BT | UBL | Source |
|---|---|---|
| BT-106 | `cbc:LineExtensionAmount` | `subtotal`, after the recomputation agreed with it |
| BT-109 | `cbc:TaxExclusiveAmount` | the same |
| BT-110 | `cac:TaxTotal/cbc:TaxAmount` | `tax`, after the per-bucket recomputation agreed |
| BT-112 | `cbc:TaxInclusiveAmount` | `total` |
| BT-115 | `cbc:PayableAmount` | `total` |
| BG-23 | `cac:TaxSubtotal` | one per Stripe tax rate, with its taxable base summed from the lines |

Every one of these is recomputed in minor units and compared with Stripe's own scalars **and its own
per-rate breakdown** before anything is written; a difference of one cent in one bucket is a refusal
naming both values. And the four EN 16931 balance rules are invariants of the model, so an
unbalanced document cannot be constructed at all.

---

## 4. Tax categories and exemption reasons

The category comes from Stripe's own `taxability_reason`, through a versioned rule pack
(`en16931-eu-vat`, version `2026.09`) that **checks and never overrides**. Stripe computed and
charged the tax; deciding silently, on the seller's behalf, that it was wrong is not something a
library gets to do.

| Stripe `taxability_reason` | Rate | EN 16931 category (BT-151/BT-118) | VATEX reason (BT-121) |
|---|---|---|---|
| `standard_rated`, `proportionally_rated` | above zero | `S` | none |
| `reverse_charge` | zero | `AE` | `VATEX-EU-AE` |
| `customer_exempt` | zero | `E` | `VATEX-EU-132` |
| `zero_rated` | zero | `Z` | `VATEX-EU-Z` |
| `excluded_territory`, `not_subject_to_tax`, `not_collecting`, `not_supported` | zero | `O` | `VATEX-EU-O` |
| `portion_*` | zero | **refused** | one line split across treatments is several lines in EN 16931, and this edition does not split |
| anything else | zero | **refused** | "never infer": an exemption this module cannot cite is one it will not assert |

Two cross-checks, because the upstream's reason and the parties' countries are independent facts:

- a **reverse charge between parties in the same country** is refused; this module does not decide
  which of the two is wrong;
- a **reverse charge where one party is outside the EU VAT area** is refused, because that supply is
  an export (category `G`) under a different rule.

A zero-rate category with no reason code is refused wherever it appears: recipients reject a
zero-rated line that does not say which rule zero-rated it.

The pack's id and version are recorded on the issuance row and inside the hashed material, so a
re-validation years from now reads the pack that was in force rather than today's.

---

## 5. Amounts and currencies

Stripe amounts are integers in the currency's minor unit, and **there is no default exponent of 2
anywhere in this module**. Two exponents are listed per currency, because they are two numbers:

- **Stripe's minor-unit exponent**, which turns the integer into a decimal;
- **ISO 4217's presentation exponent**, which decides how the amount is written.

They differ for HUF, TWD, UGX and ISK, which Stripe charges as zero-decimal while ISO 4217 does not.
`JPY 10000` stays ten thousand yen; `HUF 1000` is charged as the integer 1000 and written
`1000.00`; a three-decimal amount (BHD, JOD, KWD, OMR, TND) that is not a multiple of ten did not
come from Stripe and is refused. A currency that is not on the list is refused at mapping time.

Every amount is a bounded `BigDecimal`: no negative scale, at most four decimal places, at most
twelve integer digits, and exponent notation refused at the text before `BigDecimal` sees it. An
amount that does not fit its currency's presentation exponent exactly is **refused, not rounded** -
rounding at the writer changes what the seller charged.

---

## 6. Per-country notes

**The dates live in one place, [`mandates.md`](mandates.md)**, where every one of them carries a
source URL and the date this project last retrieved it, and where a date that could not be confirmed
against an official source says "not confirmed" instead of guessing. They were repeated here, once,
with neither URLs nor retrieval dates, which is how a table goes quietly stale.

Two practical consequences this module enforces rather than documents:

- a German B2G invoice **must** carry BT-10, so `einvoice.documents.buyer-reference` is required by
  the XRechnung profile and the context refuses to start without it;
- a Peppol invoice **must** carry both electronic addresses under a scheme on the EAS code list.
  `EM` (an email address) was removed from that list, so an email is not an electronic address any
  more; the module derives one from the party's VAT identifier where it knows the country's scheme,
  and refuses rather than inventing one where it does not.

Deadlines move, which is why they are sourced and dated in `mandates.md` rather than asserted here.
None of it is legal advice: your accountant checks the first invoices a new configuration issues.

---

## 7. What is deliberately refused

Each of these is a typed refusal naming the Stripe field or the property, never a best-effort
document:

| Refused | Code | Why |
|---|---|---|
| An invoice whose total is not subtotal + tax | `DEI-220` | A discount, a coupon or a customer credit balance. This edition will not write a total it cannot account for line by line |
| A line with no tax rate | `DEI-221` | No EN 16931 category can be established, and an untaxed line is a VAT position nobody took deliberately |
| A zero rate with a reason this pack cannot name | `DEI-302` | "Never infer" |
| A currency with no recorded exponent | `DEI-300` | Never a default of 2 |
| A negative total on an invoice type | `DEI-306` | A refund is a credit note with positive amounts |
| An invoice in a currency other than the seller's accounting currency | `DEI-301` | BT-111 needs an exchange rate with a provenance record (D-16), which is not in this edition |
| A buyer name, address line or item name past its business term's length | `DEI-101` | Truncating changes what the seller said |
| A buyer value carrying a control character, an unpaired surrogate or a bidi override | `DEI-101` | Escaping preserves it, and it fails at the recipient or renders as something else |
| A seller identifier that fails its own check digit | `DEI-307` | At startup, naming the property |

## 8. What is not in this edition

Factur-X and PDF/A-3; delivery to a platform or a Peppol access point; Stripe Connect and multiple
sellers; document-level allowances and charges; the accounting-currency tax amount (BT-111) and the
exchange rate behind it; reading any business term out of Stripe metadata or a checkout custom
field. The last one is not an oversight: those are fields a **buyer** controls, and letting one
reach a business term needs an explicit allowlist with bounded values, which is a mechanism and not
a mapping.
