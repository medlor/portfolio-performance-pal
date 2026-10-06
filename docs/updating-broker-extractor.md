# Updating the upstream broker extractor

The Java backend directly uses Portfolio Performance PDF extraction. Updates are manual changes to the pinned upstream dependency/image, not ports into Python. No extractor revision or layout has been validated yet.

1. Record the current upstream revision, image identity, and tested layouts. Choose and pin the target revision; select the importer by document bank identifiers and layout. Smartbroker branding alone does not establish whether DAB, Baader, or another importer applies.
2. Review importer, shared parser, PDF-to-text, dependency, and upstream fixture/test changes. Check transaction types, identifiers, quantities, dates/times, booked/gross amounts, fees, taxes, currencies, rates, and broker references.
3. Obtain anonymized representative PDFs and extracted-text fixtures preserving whitespace/line structure. Keep previous supported layouts as regressions. Respect licensing and attribution; existing samples are not automatically anonymized fixtures.
4. Build the pinned image and verify headless PDF conversion and extraction end to end. Upstream text tests alone do not prove the container's PDF-to-text path works. Adapt the Java integration where needed, preserving authoritative booked amounts and rejecting missing or contradictory information.
5. Test preview/application, supplementation and fallback times, purchase/sale/dividend and FX values, multi-transaction atomicity, duplicates, unsupported layouts, and failure without modification. Load successful outputs with the supported pinned core and inspect financial records and account links.
6. Record only passing layouts plus the exact revision/image, commands, and limitations. Check file compatibility separately using [the version-support guide](upgrading-version-support.md); an importer update does not authorize a model version or migration.

Locate contributor instructions, importer implementations, and extraction tests in the chosen upstream checkout. Record revision-specific source links in concrete update records rather than using mutable `master` as evidence.
