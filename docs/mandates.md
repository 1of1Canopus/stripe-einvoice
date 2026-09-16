# Where a structured invoice is required, and from when

This page exists because a deadline is the first thing a reader wants and the easiest thing to get
wrong. **A date appears below only with a source URL and the date this project last retrieved it.**
Where the official source could not be retrieved, the row says **not confirmed** and gives the legal
instrument to check - it does not carry a date this project cannot stand behind.

Nothing here is legal advice, and none of it changes what the library does: this module writes
EN 16931 documents (XRechnung 3.0 UBL, Peppol BIS Billing 3.0 UBL) and validates them against the
official rules. Whether you are obliged to send one, and through which network, is between you and
your tax authority.

Last review of this page: **2026-09-16**.

| Country | Receiving | Issuing | Format in practice | Source (retrieved) |
|---|---|---|---|---|
| **Germany** | Since **1 January 2025** - *inferred*, see the note below | **1 January 2027** for an issuer whose previous calendar year's total turnover exceeded EUR 800 000; **1 January 2028** for everyone else. EDI stays permissible to 31 December 2027 | EN 16931; XRechnung 3.x for public-sector buyers, which also require a Leitweg-ID in BT-10 | § 14 and § 27 (38) UStG, [gesetze-im-internet.de/ustg_1980/__14.html](https://www.gesetze-im-internet.de/ustg_1980/__14.html) and [/__27.html](https://www.gesetze-im-internet.de/ustg_1980/__27.html) (retrieved 2026-09-16) |
| **Belgium** | **1 January 2026** | **1 January 2026** | Peppol BIS Billing 3.0 over the Peppol network; an alternative format only by mutual agreement and only if it converts to EN 16931 | European Commission, *eInvoicing in Belgium*, [ec.europa.eu](https://ec.europa.eu/digital-building-blocks/sites/spaces/DIGITAL/pages/467108877/eInvoicing+in+Belgium) (retrieved 2026-09-16), citing the federal law of 6 February 2024 amending the VAT code |
| **France** | **not confirmed** | **not confirmed** | EN 16931 through an approved platform (*plateforme agréée*, formerly PDP) | Article 289 bis CGI, and the government's own programme page [economie.gouv.fr](https://www.economie.gouv.fr/tout-savoir-sur-la-facturation-electronique-pour-les-entreprises). Neither could be retrieved on 2026-09-16 (HTTP 403 / 404 from this network), so no date is published here |

### The two notes the table cannot hold

**Germany, receiving.** § 27 (38) UStG grants transitional relief to the party *issuing* an invoice
- it may still send paper, or a format that is not EN 16931 - and grants no equivalent relief to the
party receiving one. The widely repeated "reception mandatory since 1 January 2025" follows from
that asymmetry rather than from a sentence that says so. It is recorded here as an inference, not as
a quotation, and it is the one line on this page a reader should verify against a Federal Ministry
of Finance letter before relying on it.

**France.** The timetable has been amended more than once, and what this project can retrieve from a
build machine is not the authority on it. The library is unaffected either way: France's approved
platforms consume EN 16931, which is what this module writes.

### Why no other country is listed

A row here is a claim, and a claim needs a source someone maintains. Italy (FatturaPA), Poland
(KSeF), Spain (Verifactu/FACe) and the Nordics each use a national format or a national network
this module does not implement in 0.1.0, so a date for them would be a deadline for something this
library does not do. They are absent on purpose rather than by oversight.

### How to correct a row

Open a pull request changing the row, the source URL and the retrieval date together. A date without
the other two is not merged - that is the rule this page is built on, and the reason the France row
is empty rather than plausible.
