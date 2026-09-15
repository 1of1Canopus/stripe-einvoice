# stripe-einvoice

**Legal EN 16931 e-invoices from Stripe Billing, for Spring Boot applications.**

France (small firms issuing from 1 September 2027, reception since 1 September 2026), Germany
(2027-28) and Belgium (since January 2026) require invoices as structured data. Stripe Invoicing
does not produce it, and Stripe's own documentation tells you to install a marketplace app or write
the mapping yourself. Java shops on Stripe have had nothing.

> **Status: under construction.** This repository is pre-release and nothing is published yet. The
> first piece is in place: the legal numbering series. The Stripe intake, the EN 16931 mapping and
> the XRechnung / Peppol UBL writers follow.

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
| A test-mode event never touches the live series | The mode is part of the series key, not a filter |
| Invoicing does not stop on 1 January | A new fiscal year's series row is opened inside the allocation transaction, so an application last restarted in November keeps working |
| The series cannot silently change format | The counter is refused at the last number the configured width can render |
| The ledger cannot be rewritten by the application that writes it | Database triggers refuse DELETE, TRUNCATE, an UPDATE of any immutable column, a write-once column's second write, and any undeclared state transition |
| A rewrite by a role that outranks the triggers is still detectable | A keyed (HMAC) hash chain over the disposition log, with an anchor row, and a cross-check that a disposed issuance row has a chained event agreeing with it |
| No JPA mapping of ours, and none of the host's over our tables | The module ships no entity; a startup guard refuses a host entity, secondary table, join table, collection table, `@Subselect` or database view that reaches these tables |

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

Run the sample:

```bash
cd stripe-einvoice-sample
docker compose up -d
EINVOICE_CHAIN_SECRET=$(head -c 32 /dev/urandom | base64) ../mvnw spring-boot:run
```

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
