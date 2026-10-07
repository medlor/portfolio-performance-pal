# Synthetic fixture contract v1

All names, account numbers, transaction references, identifiers, dates and amounts
are invented. No real portfolio or reference PDF was read to create these files.
These fixtures can be committed publicly. The instrument identifiers deliberately
serve as synthetic parser inputs rather than claims about existing instruments.

`portfolio.xml` is ID-reference model 70. Core construction in
`runtime/src/test/java/pal/FixtureBuilder.java` created its initial shape; UUIDs and
update timestamps were replaced with deterministic invented values for the
committed fixture. Regeneration must preserve/review this committed contract and
the independently written `FixtureOracle.java`, rather than regenerate expected
results from the candidate engine. The fixture has two cash/securities account
pairs, two retained instrument definitions and a taxonomy with both assignments.

Independent financial facts (amounts in EUR unless indicated):

| Account | Record | Date/time | Shares | Booked | Fee | Tax | FX gross |
|---|---|---|---:|---:|---:|---:|---|
| A | Purchase | 2025-01-02 11:00:00 | 10 | 102.00 | 1.00 | 1.00 | — |
| A | Partial sale | 2025-02-02 11:00:01 | 4 | 46.00 | 1.00 | 1.00 | — |
| A | Dividend | 2025-02-10 11:00:00 | 6 relevant | 8.50 | 0.50 | 1.00 | — |
| B | Purchase | 2025-01-03 09:30:00 | 2 | 51.00 | 1.00 | 0 | EUR 50.00 / USD 60.00 |
| B | Full sale | 2025-02-03 09:30:01 | 2 | 57.00 | 1.00 | 2.00 | EUR 60.00 / USD 72.00 |

Both FX gross units store EUR per USD `0.8333333333`. A retains 6 shares; B retains
zero shares and its closed history. The dividend is a cash transaction referencing
the security. The independent full-sale proof adds A's remaining 6 shares sold for
EUR 72.00 at 2025-03-01 11:00:01 with reciprocal linked cash/securities entries;
all other persisted fields must be identical. The custom client setting equals
`preserve-me`, and the 18 default bookmark settings must survive.

`baader-buy.txt` is an invented Baader purchase layout using patterns documented in
[the pinned extractor](https://github.com/portfolio-performance/portfolio/blob/8de1fe41d71a2c1b74a6bfbe913f885899aaf123/name.abuchen.portfolio/src/name/abuchen/portfolio/datatransfer/pdf/BaaderBankPDFExtractor.java).
Its independent expected result: BUY 2 shares of synthetic DE0000000001,
2025-01-03 09:30:00, booked EUR 51.00, gross EUR 50.00, fee EUR 1.00.
`baader-buy.expected` records stable container output. `baader-buy.pdf` contains
the same invented text drawn as an actual PDF. Generate it with ReportLab 4.4.7
and `scripts/generate-fixture-pdf.py`; normal runtime/tests require no Python
dependency because the PDF is committed. The public patterns are upstream EPL
reference material; no upstream private statement fixture was copied.
