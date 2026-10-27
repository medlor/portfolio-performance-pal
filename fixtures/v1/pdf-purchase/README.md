# Invented Baader purchase preview fixtures

The PP-authority refactor supersedes the strict wrapper-parser behavior described
in the historical fixture notes below. These original PDFs/text/literals remain
retained regression evidence. Missing mandatory PP sections now reject even with
CLI values; ignored printed gross/price, repeated or unknown fields follow PP;
complete continuations are supported. Fees/taxes and time may supplement absent
upstream-extracted values. The invented dividend payment-time extension is ignored;
its explicit/midnight saved-model literals are now exercised with CLI times.
See [the reuse boundary](../../../docs/upstream-reuse.md) and
[authority regressions](../pp-extraction/README.md) for the current contract.

Historical fixture notes (former strict-parser behavior):

Every identity, account, reference, date and amount is invented. No owner portfolio,
private statement or repository reference sample was read or copied. The starting
text is the existing public invented `../baader-buy.txt`. Grammar comes from the
[pinned upstream Baader extractor](https://github.com/portfolio-performance/portfolio/blob/8de1fe41d71a2c1b74a6bfbe913f885899aaf123/name.abuchen.portfolio/src/name/abuchen/portfolio/datatransfer/pdf/BaaderBankPDFExtractor.java),
EPL 1.0 reference material; upstream's private fixture contents were not copied.
The model is upstream Portfolio Performance 0.88.0, revision
`8de1fe41d71a2c1b74a6bfbe913f885899aaf123`; PDFBox 3.0.8 runs headlessly on Temurin
21.0.11+10, Linux amd64. Actual image/evidence belongs in the support matrix.

`expected.json` records independently authored literal facts. The test's financial
assertions use those reviewed values, never candidate-derived expectations. Paired
text preserves every line, including physical page starts. `PdfTextOracle.java`
requires exact equality with actual upstream conversion for every PDF. The renderer
does no financial modelling. It uses the repeated invented issuer header solely
as fixture page metadata; production classification uses actual physical PDF pages.
The wrong-page trap puts both statement markers on page 1 and leaves page 2 blank.
That blank physical page contributes one extra literal newline at the paired
text's end; the renderer consumes it as page formatting, not page-1 content.
Global counts alone would accept it; each physical page must have exactly one start.

Regenerate only PDFs with Python and ReportLab 4.4.7:

```sh
python3 scripts/generate-pdf-purchase-fixtures.py
cd fixtures/v1/pdf-purchase
sha256sum *.pdf *.txt expected.json > SHA256SUMS
```

Review committed text and expected literals separately from rendering. Do not
regenerate expectations from extraction. `tests/pdf-purchase.sh` runs through the
owner launcher in the actual updater's qualified released-candidate gates; it
checks the original flat fixture too. `tests/updater.sh IMAGE --pdf-slice` keeps
all registered gates and verifies activation/selected-copy validation plus the
fixed released-image PDF preview without rerunning unrelated updater adversity.

Only flat single-page and `Baader Bank AG` plus `Seite 1/1` German EUR purchase
profiles are supported. The synthetic profile's presence proves no real current
Baader/Smartbroker PDF layout. Continuations, other issuers, FX, sales/dividends,
unknown content and incomplete identity reject. Missing whole quantity/price or
booked sections can be completed only with explicit required values; a malformed
present section cannot be supplemented. Missing fee/time fixtures preserve
SUPPLEMENT/fallback origins, and explicit midnight stays explicit.

`later-missing-reference` and `later-unsupported-sale` expose upstream block-start
omission hazards. Each physical page is classified before all members complete
strict source/evidence checks; the document produces no preview until every member
has one valid upstream entry. `contradictory-gross` proves that an ignored domestic
Kurswert must reject rather than be treated as missing. Zero/overprecision, repeated
costs, malformed time and conflicting identifiers are hard failures. Existing
ambiguous-security fixtures test joint identifier resolution and stable-ID choice.
All previews and rejections preserve portfolio, document and opaque import metadata.

Current-layout evidence was added separately on 2026-10-11 in the
[current-layout contract](../smartbroker-current/README.md) and mandatory
`tests/smartbroker-current.sh` gate. These earlier fixture notes retain the scope
and limitations of their historical validation; they do not describe the new
complete purchase, sale and CHF-distribution specimens.
