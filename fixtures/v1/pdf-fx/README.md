# FX and complete mixed-document regressions

These invented, anonymized Baader-grammar PDF/text fixtures qualify EUR cash / USD
security purchases, sales and dividends. They contain no owner financial input.
Their source layouts follow the pinned PP Baader extractor's EUR/USD gross and
foreign-cost sections; they are regression statements, not evidence that every
current Smartbroker layout is supported. Other currency pairs remain unqualified.

Pinned source: Portfolio Performance 0.88.0,
`8de1fe41d71a2c1b74a6bfbe913f885899aaf123`; Temurin 21.0.11+10, Linux amd64,
ID-reference XML model70 and protected transaction-only model69-to70 migration.
Exact qualified development/released images appear in the support matrix after
cumulative qualification.

Independent expected facts in `PdfFxAppliedOracle.java`:

| Member | Shares | Booked EUR | Gross EUR | Gross USD | Fee USD / EUR | Tax USD / EUR | Timestamp |
| --- | ---: | ---: | ---: | ---: | --- | --- | --- |
| Purchase | 2 | 9.20 debit | 8.00 | 10.00 | 1.00 / 0.80 | 0.50 / 0.40 | 2025-01-03 09:30:00 |
| Sale | 1 | 2.80 credit | 4.00 | 5.00 | 1.00 / 0.80 | 0.50 / 0.40 | 2025-01-03 09:30:00 |
| Dividend | 10 informational | 18.80 credit | 20.00 | 25.00 | 1.00 / 0.80 | 0.50 / 0.40 | 2025-03-01 11:00:00 fallback |

Printed EUR/USD 1.25 is stored as cash-per-security EUR/USD 0.8. The mixed PDF
puts the sale page first: admission must consider the equal-time purchase first,
without moving either timestamp. Dividend shares never add a securities record.
The missing-rate statement extracts the purchase and EUR costs without any PP
conversion; supplied rate 0.8 and optional foreign gross 10.00 complete that
conversion while preserving booked cash. Preview must disclose supplementation
and derivation. Matching extracted values are assertions, conflicting values fail.

Negative/contradictory/missing-conversion/unsupported later members and repeated
broker references reject the complete document. `tests/pdf-fx.sh` checks actual
PDF-to-text conversion, preview immutability, saved quantities/currencies/rates,
foreign costs, reciprocal trade links, full unrelated-state preservation,
confirmed repeats, partial confirmation, lost-evidence uncertainty, document-wide
overrides, exact migration protection and post-replacement metadata recovery.
It remains a mandatory updater gate alongside all previous gates.

Reproduce fixtures with Python 3 and ReportLab 4.4.7:

```sh
python3 scripts/generate-pdf-purchase-fixtures.py pdf-fx
cd fixtures/v1/pdf-fx
sha256sum *.pdf *.txt > SHA256SUMS
```

Focused check from the repository root:

```sh
PP_PAL_LAUNCHER="$PWD/tools/run-container" bash tests/pdf-fx.sh pp-pal:issue34
```

Complete qualification: `bash tests/all.sh pp-pal:issue34` (also exercises the
owner launcher and released immutable pair). Keep all retained domestic and PP
extraction profiles when upgrading; no independent financial expectations may be
changed to accommodate a candidate.
