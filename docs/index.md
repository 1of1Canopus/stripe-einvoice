# The legal numbering series

The number this module puts on a document is the legal one (BT-1). Stripe's own `invoice.number` is
stored beside it and printed as a reference, and it is never the legal number: it is prefixed per
customer, holed by drafts, failed finalisations and voids, and its format is Stripe's to change.

## The property, and the word that is not used

For one `(seller, series, fiscal year, mode)`:

- no two issued documents share a number;
- **every allocated number has a recorded, chained disposition** - issued, or voided with a reason;
- one Stripe invoice maps to one number for all time, on every path.

The phrase "gap-free" is not used, and the reason is worth a paragraph. Validating the exact bytes
that get archived is a property this module can prove; never leaving an unused number is one it
cannot, because a document that fails schematron after a number was allocated cannot be re-rendered
under a different number without breaking the first property. So the residue is handled instead of
hidden: an operator voids the number with a mandatory reason and the failing rule id, the event is
hash-chained, and the series report shows it. A hole with a chained reason and a hole with no
explanation must never look alike to the person auditing the series.

## How allocation works

One transaction, in this order:

1. `SELECT ... FOR UPDATE` on `(seller, mode, stripe invoice id)`. A row in any state returns its
   existing number - **resume, never re-allocate**.
2. `INSERT INTO einvoice_series ... ON CONFLICT DO NOTHING`, which opens a new fiscal year's row if
   this is the first invoice of the year. The start value is 1 and is not a free parameter, so
   concurrent creators agree by construction.
3. `UPDATE einvoice_series SET next_number = next_number + 1 ... AND next_number <= <max for the
   width> RETURNING next_number - 1`. The `UPDATE` takes a row lock held until commit, so a second
   transaction blocks and then reads the committed value.
4. `INSERT INTO einvoice_issuance`, state `NUMBERED`. The unique constraint is the backstop when two
   callers missed step 1 together: the loser's counter increment rolls back with its insert, so
   there is no gap, and it re-reads the winner's number.

A rollback, a crash, or a killed backend all leave the counter where it was - the counter is
ordinary row state, not a sequence. A PostgreSQL `SEQUENCE` is deliberately non-transactional, which
is exactly why one is not used here and why a test asserts none appears.

## Fiscal years, and the invoice that arrives late

The fiscal year comes from BT-2 - Stripe's `status_transitions.finalized_at`, converted in the
seller profile's **required** `tax-zone`, never in the JVM's default zone. An invoice finalised at
23:30 UTC on 31 December belongs to the year its seller's tax authority says it does.

One consequence to have an answer ready for: an invoice finalised on 31 December and processed on
2 January takes a number from the **closing** year's series, and is therefore issued after numbers
from the new year already exist. That is correct and unavoidable, and on a date-ordered list it
looks like tampering. The series report shows both the invoice date and the allocation timestamp on
every line for that reason. Whether to refuse a late invoice from a closed year after a cut-off is
the seller's accounting policy, not this module's.

## The series report

Per `(seller, series, fiscal year, mode)`, every allocated number with:

- its disposition: `ISSUED`, `VOID_UNUSED` with its reason and rule id, or still open (`NUMBERED`,
  `ARCHIVING`, `FAILED_ARCHIVE`);
- the invoice date (BT-2) and the allocation timestamp;
- the Stripe invoice id it is bound to.

Plus the open count and the next number the series will hand out. `contiguous()` says whether the
enumeration itself has no hole, which is a different question from whether every number carries a
document, and both belong on the page.

## Voiding a number

`IssuanceVoidService.voidUnused(stripeInvoiceId, reason, ruleId)`.

- **Privileged, and with no HTTP endpoint anywhere in this module.** Expose it yourself, behind your
  own authorization, or not at all.
- Legal only from `NUMBERED`, `ARCHIVING` or `FAILED_VALIDATION`. From `ISSUED` it is refused twice:
  by the state enum and by the database trigger.
- The reason is mandatory, screened (NFC, no control characters beyond tab/newline/carriage return,
  no unpaired surrogates, not blank after collapsing, bounded at 500 characters) and length-bounded.
- Nothing is deleted and the number is never re-allocated.

## Properties

| Property | Default | Weaker mode |
|---|---|---|
| `einvoice.mode` | `live` | `test` - WARNs at every startup, separate series row |
| `einvoice.initialize-schema` | `true` | `false` when the host owns the migrations |
| `einvoice.seller.id` | required | none |
| `einvoice.seller.tax-zone` | required, no default | none |
| `einvoice.numbering.series` | `DEFAULT` | an unconfigured series is a refusal, never auto-created |
| `einvoice.numbering.prefix` | required, `[A-Z0-9-]{1,32}`, optionally with the literal `{fiscalYear}` placeholder | none; a resetting series without the placeholder is refused at startup |
| `einvoice.numbering.width` | `6` | 4..12; outside that the application refuses to start |
| `einvoice.numbering.fiscal-year-reset` | `true` | `false` - WARNs at every startup |
| `einvoice.numbering.allocation-timeout` | `5s` | applied as `SET LOCAL lock_timeout`; a blocked allocation past this bound is a typed `DEI-117` refusal |
| `einvoice.chain.hmac-secret` / `.hmac-key-id` | required, env only, base64 of >= 32 bytes | none |
| `einvoice.chain.hmac-keys.<id>` | empty | retired ids the verifier still needs during a rotation |
| `einvoice.chain.unkeyed` | `false` | `true` - WARNs at every startup, verifier reports `INTACT_UNKEYED` |

No property in this module disables a uniqueness constraint, a trigger, the resume-by-source-id
behaviour or the chain.

## The database

Four tables: `einvoice_series` (the counter), `einvoice_issuance` (the mapping row, append plus a
state column), `einvoice_issuance_event` (the append-only chained disposition log) and
`einvoice_issuance_anchor`.

Run the application as a role that does **not** own them - `docs/schema-grants.sql` has the grants.
The triggers refuse the runtime role; nothing refuses a table owner, who can disable them, so the
grant separation is the first line and the chain is what detects a rewrite afterwards.

This module maps none of these tables with JPA, deliberately: with no entity there is no `find`, no
derived query, no JPQL, no projection, no lazy attribute, no first- or second-level cache and no
dirty checking to reason about. A startup guard keeps that true by refusing to start if a host
entity, secondary table, join table, collection table, `@Subselect` or database view reaches them.
