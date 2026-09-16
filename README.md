# stripe-einvoice

**Legal EN 16931 e-invoices from Stripe Billing, for Spring Boot applications.**

France (small firms issuing from 1 September 2027, reception since 1 September 2026), Germany
(2027-28) and Belgium (since January 2026) require invoices as structured data. Stripe Invoicing
does not produce it, and Stripe's own documentation tells you to install a marketplace app or write
the mapping yourself. Java shops on Stripe have had nothing.

> **Status: under construction.** This repository is pre-release and nothing is published yet.
> Three pieces are in place: the legal numbering series, the issuance unit of work that drives one
> Stripe event to one archived document or to none, and the EN 16931 documents themselves -
> XRechnung 3.0 and Peppol BIS Billing 3.0 in UBL, judged by the official validator artefacts.

## What the documents are

One Stripe invoice becomes one EN 16931 document, written by this module and **judged by the
official rules before it is archived**.

| | XRechnung 3.0 (UBL) | Peppol BIS Billing 3.0 (UBL) |
|---|---|---|
| `einvoice.documents.profile` | `xrechnung-ubl` | `peppol-bis-ubl` |
| Specification identifier (BT-24) | `urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0` | `urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0` |
| Rules run | CEN EN 16931 schematron, then the CIUS XRechnung rules (`BR-DE-*`) | CEN EN 16931 schematron as OpenPeppol compiles it, then `PEPPOL-EN16931-*` including the code lists |
| Artefacts | KoSIT validator configuration for XRechnung, release `v2026-08-31` | OpenPeppol release `2026.5` |

The artefacts are **vendored** with a SHA-256 each, recorded in
`stripe-einvoice-core/src/main/resources/com/housedevinci/einvoice/reference/CHECKSUMS.txt`, with a
provenance note per file.
Nothing is downloaded at build time or at run time, every checksum is recomputed on every build,
and the checksum of a stylesheet is recomputed again before it is compiled at run time. A
stylesheet is executable code, and these are run over documents that go to a tax authority.

What the writer guarantees:

| Property | How |
|---|---|
| The bytes that were validated are the bytes that are archived | One byte array: rendered, validated, hashed, stored. Never a re-serialisation of a model |
| The same invoice renders the same bytes anywhere | Canonical XML 1.1 written directly, no clock, no default zone, no default locale. The determinism test renders each fixture again under a Thai-digit locale and a time zone across the date line and compares bytes |
| A buyer's string cannot become markup | One canonical writer that escapes, and one screening function that refuses what escaping cannot make safe: C0 controls, unpaired surrogates, bidi overrides, a value that collapses to blank, anything past the business term's own length bound |
| A wrong number cannot hide behind a valid document | Every EN 16931 balance rule (`BR-CO-10`, `-13`, `-14`, `-15`, `-16`) is an invariant of the model, so an unbalanced document cannot be constructed, let alone written |
| An identifier is checked, not assumed | The French VAT key and the SIREN's Luhn, a GLN's GS1 check digit, an IBAN's mod-97, ISO 3166 for countries, the Peppol EAS list for electronic addresses - for **both** parties, the configured seller included |
| A tax position is never inferred | The category comes from Stripe's own taxability reason through a versioned rule pack, which checks and never overrides; a zero rate with no VATEX reason, or a reverse charge whose parties contradict it, is a refusal naming the Stripe field |
| An exponent is never assumed | An explicit currency table with Stripe's minor-unit exponent beside ISO 4217's presentation exponent. `JPY 10000` stays ten thousand; `HUF 1000` is charged as an integer and written with two decimals; an unlisted currency is refused |

### The XSLT processor

The EN 16931, XRechnung and Peppol schematron are **XSLT 2.0**, and the JDK ships an XSLT 1.0
processor only. This module therefore needs one, and asks for it **by class name** rather than
depending on it: the only practical XSLT 2.0 processor for the JVM is MPL-2.0, and this project's
licence gate denies MPL for anything it ships.

Add one to your application:

```xml
<dependency>
  <groupId>net.sf.saxon</groupId>
  <artifactId>Saxon-HE</artifactId>
  <version>13.0</version>
</dependency>
```

**Without a processor this module issues nothing.** Every validation reports `NOT_EVALUATED`, which
the issuance unit of work treats as a refusal, and the starter says so at every startup with a WARN
naming the class it looked for. An unevaluated rule is not a passed rule, and archiving a document
no rule ever read would be worse than issuing none.

## What the numbering series does

A per-seller legal numbering series in which **every allocated number has a recorded, chained
disposition** - it is either on an issued document, or it is voided with a reason that is written
down and hash-chained.

That is deliberately not the phrase "gap-free". Gap-freeness and byte-determinism cannot both be
absolute, and this module chooses the property it can prove: the bytes that pass the validator are
exactly the bytes that are archived, so a document that fails validation after a number was
allocated leaves the number recorded and voided rather than quietly re-used under different bytes.
An unexplained hole and a hole with a chained reason must never look alike to an auditor, and the
series report is written for that reader.

What it guarantees today:

| Property | How |
|---|---|
| One Stripe invoice, one number, for all time | A unique constraint on `(seller, mode, stripe invoice id)`; a retry resumes onto the existing number instead of allocating a second one |
| A rollback or a crash never consumes a number | Row-lock allocation (`UPDATE ... RETURNING`) inside the transaction that inserts the issuance row. No `SEQUENCE`, no `nextval`, no `@GeneratedValue` - those are non-transactional by design |
| Your rollback is our rollback | An allocation made inside your `@Transactional` method or `TransactionTemplate` joins that transaction and is rolled back with it; reads join it too, so you can read back the number you just allocated. Outside a transaction, the allocation commits on its own, as before. To keep a number across your own rollback, allocate in a `@Transactional(propagation = REQUIRES_NEW)` method - that is the only way to ask for it |
| A test-mode event never touches the live series | The mode is part of the series key, not a filter |
| Invoicing does not stop on 1 January | A new fiscal year's series row is opened inside the allocation transaction, so an application last restarted in November keeps working |
| The series cannot silently change format | The counter is refused at the last number the configured width can render |
| The ledger cannot be rewritten by the application that writes it | Database triggers refuse DELETE, TRUNCATE, an UPDATE of any immutable column, a write-once column's second write, and any undeclared state transition |
| A rewrite by a role that outranks the triggers is still detectable | A keyed (HMAC) hash chain over the disposition log, with an anchor row, and a cross-check that a disposed issuance row has a chained event agreeing with it |
| No JPA mapping of ours, and none of the host's over our tables | The module ships no entity; a startup guard refuses a host entity, secondary table, join table, collection table, `@Subselect` or database view that reaches these tables |

## What the issuance unit of work does

One sale becomes one legal document, or none, and there is a record either way.

```
Stripe  ->  POST /webhooks/stripe  ->  durable record  ->  worker  ->  document in the archive
            (signature over the                              |
             exact bytes)                                    +-> a refusal, with its reason, on the row
```

The order is the design. The verified event is **recorded before any decision is taken about it**,
and the endpoint answers 200. A refusal that is nevertheless provably from Stripe - an API version
skew, a test-mode event in a live application, an account that resolves to no configured seller -
is a terminal state on that row with its own error code, not a 400. Stripe treats a 400 as a failed
delivery and disables endpoints that keep failing, and a version skew applies to every event on the
account at once: a control that refuses one bad event must not be able to stop the whole intake.
Once the pin is updated to match what an event actually carried, the sweeper re-picks that
recorded row and it replays through the same path; `REFUSED_MODE` and `REFUSED_ACCOUNT` are never
re-picked automatically, because neither is cured by an upgrade.

| Property | How |
|---|---|
| The webhook payload never reaches a document | Only `(event id, type, api version, livemode, account, object id)` are read from the body. Every field a document carries is re-fetched from the Stripe API, with the lines paginated to exhaustion; a residual "more pages" is a refusal, never a partial document |
| A document carries the buyer as at the issue date | Only the fields Stripe froze onto the invoice at finalisation are mapped. The live customer object is neither read nor expanded, so a buyer who corrects their address in March does not change what a January invoice says |
| The totals on the document are ours, and they agree with Stripe's | Every line sum and every per-rate tax bucket is recomputed and compared. A difference of one cent in one bucket is a refusal that names the field, never an adjustment |
| A refusal consumes no number | Every refusal that depends on data happens before the allocator is called |
| One number never names two documents | The archive key carries the SHA-256 of the exact bytes and the store is write-once; the same input renders the same bytes under any time zone and any locale |
| A crash leaves something a retry can finish | Four phases, each with a recorded state: claim, render, write (the row predicts the object before the PUT), issue (state and chained disposition in one transaction) |
| A store that quietly overwrites is refused | At startup, with a real conditional write against a scratch key - not on the strength of a capability flag |
| A sale with no document is noticed | A reconciliation sweep lists what Stripe finalised and re-enqueues anything with no issued document, through the same idempotent path. It alerts and never repairs |
| A redelivery returns the stored document | After checking the archived bytes against the recorded hash. A tampered archive is refused; a legitimate upstream edit is not |
| Back-pressure costs latency, never an event | A bounded queue and a concurrency limit below your connection pool; past capacity the event stays `RECEIVED` and the sweeper takes it |
| Buyer data has a retention | The raw signed bodies are nulled the moment an event can no longer run, and purged at `einvoice.inbound.retention` |

### What it needs from you

The writer and the validator are the starter's, built from `einvoice.seller.*` and
`einvoice.documents.*` as soon as `einvoice.seller.name` is set. What is left for you:

```java
@Bean StripeInvoiceSource source() { ... }  // or set einvoice.stripe.api-key and take the SDK
```

You may still supply your own `DocumentRenderer` or `DocumentValidator` bean and ours backs off -
a custom format is a paid service on top of this, and the ports are public so you are not blocked
waiting for one.

With neither ours nor yours, this whole half of the module does not start: allocating a number with
no way to produce a validated document would consume a legal series and archive nothing. **How
loudly depends on whether you plainly meant to receive events.** With
`einvoice.stripe.webhook-secrets` configured, or `einvoice.issuance.enabled` set explicitly, the
missing bean fails startup by name - the usual cause being that `einvoice.seller.name` is unset.
With neither, this is a numbering-only host and it starts with one WARN naming what is missing, not
a failure - set `einvoice.issuance.enabled=false` to say so on purpose and stop the WARN repeating.

and, in your security configuration, the webhook path left unauthenticated - the HMAC over the
exact bytes received is its authentication:

```java
.requestMatchers(HttpMethod.POST, "/webhooks/stripe").permitAll()
```

## Quick start

```xml
<dependency>
  <groupId>com.housedevinci</groupId>
  <artifactId>stripe-einvoice-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```yaml
einvoice:
  mode: live                    # 'test' WARNs at every startup and uses a separate series
  seller:
    id: acme-fr
    tax-zone: Europe/Paris      # required, no default: it decides the invoice date and fiscal year
    name: Atelier Riviere SAS   # BT-27. Setting it is what turns the document writer on
    vat-id: FR25900000019       # checked at startup, check digit included
    electronic-address: FR25900000019
    electronic-address-scheme: "9957"   # Peppol EAS; mandatory for the Peppol profile
    address:
      line1: 12 rue des Lilas
      city: Lyon
      postal-code: "69003"
      country: FR
    contact:                    # mandatory for XRechnung (BR-DE-2, BR-DE-6, BR-DE-7)
      name: Comptabilite
      telephone: "+33 4 72 00 00 00"
      email: factures@atelier-riviere.invalid
    payment:                    # mandatory for XRechnung (BR-DE-1)
      means-code: "58"
      account-id: FR7630006000011234567890189
  documents:
    profile: peppol-bis-ubl     # or xrechnung-ubl
    buyer-reference: PO-9912    # BT-10; mandatory for XRechnung (BR-DE-15), a Leitweg-ID for B2G
  numbering:
    prefix: "INV-{fiscalYear}-" # required, no default; {fiscalYear} is required when the series
                                # resets each year (below) - never a static year, or the second
                                # January reuses the first year's numbers
    width: 6
    fiscal-year-reset: true
  chain:
    hmac-secret: ${EINVOICE_CHAIN_SECRET}   # base64 of >= 32 bytes, from the environment
    hmac-key-id: k1
  stripe:
    webhook-secrets:            # a keyring: Stripe's own rotation leaves two secrets live
      primary: ${EINVOICE_WEBHOOK_SECRET}
    api-key: ${EINVOICE_STRIPE_KEY}   # restricted, read-scoped; this module never writes to Stripe
  archive:
    type: filesystem            # or supply your own ArchiveStore bean (an S3 one ships in core)
    root: /var/lib/einvoice/archive
```

```java
@Service
class Invoicing {

  private final IssuanceNumberingService numbering;   // from the starter

  void onInvoiceFinalized(String stripeInvoiceId, String stripeNumber, Instant finalizedAt) {
    Issuance issuance = numbering.allocate(stripeInvoiceId, "", stripeNumber, finalizedAt);
    // issuance.legalNumber().value() is BT-1. Stripe's own number is stored as a reference.
  }
}
```

Voiding an allocated number is a **service method and never an HTTP endpoint**: this is a library,
it cannot authenticate anyone, and an auto-configured void endpoint would hand an unauthenticated
caller a way to burn a series one number at a time. If you expose it, put it behind your own
authorization.

### Operating it

| Question | Where the answer is |
|---|---|
| Is anything wrong right now? | `GET /actuator/health/einvoice` - operational conditions only: a silent sweeper, a stale reconciliation, a chain that does not verify. It is deliberately outside `readiness` and `liveness`, so a business condition can never take your application out of the load balancer |
| What needs a human? | `GET /actuator/einvoicefindings` - open compliance findings with their codes and subjects. Acknowledge one through `IssuanceFindingService`, with a reason that is recorded; the finding is never deleted |
| Stripe moved its API version | The refused events are on `einvoice_inbound_event` with their bodies. Update the pin to match, restart, and the sweeper's due query re-picks exactly the rows whose recorded version now equals it, and replays them through the same path |
| A document failed validation | The number stays allocated with the failing rule id recorded beside the event. An operator voids it through `IssuanceVoidService` with a reason, and the series report explains the hole |

Run the sample:

```bash
cd stripe-einvoice-sample
docker compose up -d
EINVOICE_CHAIN_SECRET=$(head -c 32 /dev/urandom | base64) \
EINVOICE_WEBHOOK_SECRET=whsec_from_your_stripe_dashboard \
../mvnw spring-boot:run
```

The sample receives a signed Stripe test-mode event and writes a **validator-clean Peppol BIS
Billing 3.0 UBL invoice** to its archive, then renders and validates an XRechnung for the same
invoice beside it. One profile per application means one legal original per invoice; the second is
a call on the renderer, not a second archived document under the same number.

## Requirements

- Java 21, Spring Boot 4
- PostgreSQL. The allocator's row-lock `UPDATE ... RETURNING`, the append-only triggers and the
  advisory locks have no portable equivalent, and the application refuses to start against any
  other server rather than degrading a legal numbering series quietly.
- A database role that does **not** own these tables - see `docs/schema-grants.sql`. The triggers
  refuse the runtime role; only the grant separation keeps a table owner from disabling them, and
  the startup check says so out loud when the role owns them.

## Documentation

- `docs/index.md` - how the series works, every property, the series report, and the one thing to
  tell an auditor about a late invoice from a closing year.
- `docs/documents.md` - the BT-to-Stripe mapping table field by field, the per-country notes with
  their sources, the validator artefacts and their versions, and what is deliberately refused.
- `SECURITY-NOTES.md` - what is protected, by what, and what is not.
- `docs/schema-grants.sql` - the database role this module should run as.
- `CHANGELOG.md`

## Licence

FSL-1.1-ALv2 (see `LICENSE` and `NOTICE`): source-available, and it converts to Apache-2.0 two
years after each version is published.

**What this software is, legally.** It is a library. Your application is the issuer of the invoices
it produces; we produce and validate documents. The claim this project makes about conformance is
exactly this and no more: **it produces output that the reference validators accept**, and those
validators run in CI on every build over every fixture. Have your accountant check the first
invoices a new configuration issues.
