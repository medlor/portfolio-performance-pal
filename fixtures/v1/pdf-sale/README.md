# Invented Baader EUR sale fixtures

All names, references, dates, accounts and amounts are invented. No owner portfolio was read. Private statement identities and financial contents
were not copied. Grammar is derived from the pinned
[Baader extractor](https://github.com/portfolio-performance/portfolio/blob/8de1fe41d71a2c1b74a6bfbe913f885899aaf123/name.abuchen.portfolio/src/name/abuchen/portfolio/datatransfer/pdf/BaaderBankPDFExtractor.java)
(EPL 1.0). These fixtures establish their exact synthetic German EUR profile,
not support for real current Smartbroker statements. Other issuers, continuations,
FX remains unsupported. Dividend support is defined separately by the
[dividend fixture contract](../pdf-dividend/README.md).

The sale heading is `Wertpapierabrechnung: Verkauf`, the booked credit is
`Zu Gunsten Konto ... Valuta: ... EUR ...`, and authoritative credit equals gross
minus fees/taxes. Flat single-page or repeated Baader `Seite 1/1` physical members
are admitted. Purchases and sales may coexist; every member is checked independently
before financial application. All existing purchase regressions remain mandatory.

`expected.json` contains independently authored literal saved-model facts.
`tests/pdf-sale.sh` checks actual PDF conversion against paired text with
`PdfTextOracle`, then linked records and unrelated state with
`PdfSaleAppliedOracle`. It covers partial/full and backdated sales, future deficits,
account isolation, explicit/missing times, missing-only supplements, atomic batches,
same-time purchase funding a sale even when physically later, confirmed/missing
metadata decisions, protected model69 migration, and post-replacement recovery.
An invalid later member prevents every member from applying.

Reproduce with ReportLab 4.4.7 and Python 3:

```sh
python3 scripts/generate-pdf-purchase-fixtures.py pdf-sale
cd fixtures/v1/pdf-sale
sha256sum *.pdf *.txt expected.json > SHA256SUMS
```

Review text and expected literals independently of extraction. The pinned runtime
is Portfolio Performance 0.88.0, revision
`8de1fe41d71a2c1b74a6bfbe913f885899aaf123`, PDFBox 3.0.8, Temurin 21.0.11+10,
Linux amd64, ID-reference XML model70 and protected model69-to-70 migration.
Exact passing image identities and cumulative commands belong in
`runtime/support-matrix.json`. The mandatory updater gate uses the released owner
launcher for representative preview/apply and the released container launcher for
broad behavior; neither fixture expectations nor upstream model assertions come
from candidate output.

The retained `samples/Smartbroker_Sell.pdf` was inspected locally through pinned
PDF conversion during review. It uses a different two-page continuation profile
and has no readable `Vorgangs-Nr.` member reference. Its private identities and
financial contents were not copied into these fixtures or validation evidence.
It remains unsupported: this release does not establish that retained layout's
current-statement compatibility. A complete anonymized representative specimen
is still required before claiming support for that layout.
