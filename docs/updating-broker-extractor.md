# Updating the Broker Extractor

Initially support the owner's current broker document layouts for purchases, sales, and dividends. Extractor updates are manual; they must preserve extraction correctness and independently pass the supported XML compatibility checks.

## Procedure

1. Record the exact upstream revision used as the reference for the current extractor and the document layouts covered by local fixtures.
2. Select and pin the new upstream revision. Identify the importer matching the document's bank identifiers and layout; do not select solely from the broker's marketing name. Confirm the appropriate DAB/Baader or other importer against representative documents.
3. Review changes to that importer, shared parsing helpers, and upstream fixtures/tests. Check document detection, purchase/sale/dividend recognition, date/time, identifiers, quantities, booked/gross amounts, fees, taxes, currencies, exchange rates, and transaction references.
4. Obtain anonymized representative PDFs and their extracted text for each newly supported layout. Preserve whitespace and line structure in text fixtures. Keep the previously supported layouts as regression fixtures. Upstream text fixtures validate extraction logic but cannot alone validate our PDF-to-text step.
5. Adapt the Python extractor and expected results. Preserve the statement's booked amount as authoritative; reject missing required or contradictory information rather than guessing. Respect applicable upstream licensing and attribution requirements when adapting code or fixtures.
6. Test PDF-to-text conversion and extracted transaction values separately, then test preview and application end to end. Include duplicate imports, suspected duplicate matches, unsupported layouts, and ambiguous/incomplete documents.
7. Verify that successful imports produce correct linked transactions and that rejected imports leave the portfolio unchanged. Load generated fixtures with the pinned, supported Portfolio Performance Java reader and inspect the resulting model.
8. Record the new reference revision and supported layouts. Document limitations and the actual local test commands once those commands exist. A changed importer alone does not authorize support for a new XML model version.

## Upstream starting points

- [PDF extraction and fixture guidance](https://github.com/portfolio-performance/portfolio/blob/master/CONTRIBUTING.md)
- [Baader Bank extractor](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/datatransfer/pdf/BaaderBankPDFExtractor.java)
- [DAB extractor](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/datatransfer/pdf/DABPDFExtractor.java)
- [Baader extractor tests](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio.tests/src/name/abuchen/portfolio/datatransfer/pdf/baaderbank/BaaderBankPDFExtractorTest.java)

Use pinned source links in a concrete update record. This guide does not assume that every Smartbroker document uses the same importer.
