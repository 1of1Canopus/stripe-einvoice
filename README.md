# stripe-einvoice

**Legal EN 16931 e-invoices from Stripe Billing, for Spring Boot applications.**

France (small firms issuing from 1 September 2027, reception since 1 September 2026), Germany
(2027-28) and Belgium (since January 2026) require invoices as structured data. Stripe Invoicing
does not produce it, and Stripe's own documentation tells you to install a marketplace app or write
the mapping yourself. Java shops on Stripe have had nothing.

> **Status: under construction.** This repository is pre-release and nothing is published yet. Two
> pieces are in place: the legal numbering series, and the issuance unit of work that drives one
> Stripe event to one archived document or to none. The EN 16931 mapping and the XRechnung / Peppol
> UBL writers follow, and until they do an application supplies its own document writer - the
> starter refuses to wire the issuance path without one rather than archiving something it made up.

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

```java
@Bean DocumentRenderer renderer() { ... }   // the EN 16931 writers ship in the next increment
@Bean DocumentValidator validator() { ... } // a verdict that is not PASSED refuses the archive write
@Bean StripeInvoiceSource source() { ... }  // or set einvoice.stripe.api-key and take the SDK
```

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

The sample ships a **placeholder** document writer whose root element is `PlaceholderDocument`, so
that nothing it produces can be mistaken for an EN 16931 invoice. It exists to demonstrate the
whole path end to end - signature, record, re-fetch, totals, number, archive, chain - until the real
writers land.

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
- `SECURITY-NOTES.md` - what is protected, by what, and what is not.
- `docs/schema-grants.sql` - the database role this module should run as.
- `CHANGELOG.md`

## Licence

FSL-1.1-ALv2 (see `LICENSE` and `NOTICE`): source-available, and it converts to Apache-2.0 two
years after each version is published.

**What this software is, legally.** It is a library. Your application is the issuer of the invoices
it produces; we produce and validate documents. Have your accountant check the first invoices a new
configuration issues.
