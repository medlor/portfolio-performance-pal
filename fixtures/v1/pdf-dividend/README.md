# Invented Baader EUR dividend fixtures

All financial values, securities, references and accounts are invented; no owner
portfolio or private dividend document was read. Grammar follows the pinned
[Baader extractor](https://github.com/portfolio-performance/portfolio/blob/8de1fe41d71a2c1b74a6bfbe913f885899aaf123/name.abuchen.portfolio/src/name/abuchen/portfolio/datatransfer/pdf/BaaderBankPDFExtractor.java)
(EPL 1.0). These rendered PDFs establish the exact synthetic German EUR
`Dividendenabrechnung` profile with a physical Baader `Seite 1/1` member.
They do not establish current owner Smartbroker layout support. The supplied local owner reference is a CHF fund distribution, with one physical
page labeled `Seite 1/2` and no readable broker reference. Pinned upstream extraction
recognizes a dividend and security definition without extraction errors; the wrapper
refuses the unproved page coverage and preserves the disposable portfolio bytes.
This reference cannot qualify the complete German EUR profile. Real current-layout
verification in #33 remains open; foreign-currency PDF imports belong to #34.
The original reference is kept local and is not a committed fixture.

Each member has a broker reference, ISIN/WKN, STK quantity and per-share
distribution, optional gross/fees/taxes, and authoritative booked credit/value date.
Distribution quantity and gross must agree after upstream HALF_UP cent rounding.
Upstream extracts informational distribution shares on the cash record; they do
not change held quantity or create a securities-account transaction. The security
must already exist. Only a cash-account selection is required for dividend-only
imports. Purchase members in mixed documents still require a securities account.

The optional invented `Zahlungsdatum Zahlungsuhrzeit` section covers explicit
whole-second times (including midnight). The wrapper checks all its contents;
upstream extracts the value-date midnight, independently verified before the
explicit time or disclosed 11:00:00 fallback is set. Payment and value dates must
agree. This synthetic extension is not claimed to be a real bank time layout.
Absent booked/date/fees/taxes may be supplied for a single member, with origins
shown. Conflicting financial or identity values reject. Unsupported continuation,
FX, unknown/repeated fields or invalid members reject the entire document.

`expected.json` holds independent literal model expectations.
`tests/pdf-dividend.sh` exercises PDF conversion, extraction, immutable preview,
fees/taxes, quantities/timestamps, closed-holding relevance, supplements,
confirmed/uncertain duplicate decisions, mixed batches with invalid later members,
model69 migration protection and committed metadata-failure recovery.
`PdfDividendAppliedOracle` loads saved records and checks literal amounts, cost
units, times, statement shares, links, security and complete unrelated state.
All older purchase and sale gates remain mandatory.

Reproduce with Python 3 and ReportLab 4.4.7:

```sh
python3 scripts/generate-pdf-purchase-fixtures.py pdf-dividend
cd fixtures/v1/pdf-dividend
sha256sum *.pdf *.txt expected.json > SHA256SUMS
```

Pinned runtime: upstream 0.88.0 revision
`8de1fe41d71a2c1b74a6bfbe913f885899aaf123`, PDFBox 3.0.8, Temurin 21.0.11+10,
Linux amd64, ID-reference model70 and protected transaction-only model69-to-70.
Exact image and cumulative evidence are recorded after validation in the support matrix.
