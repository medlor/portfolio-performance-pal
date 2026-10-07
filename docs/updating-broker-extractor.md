# Updating the upstream broker extractor

The Java backend directly uses Portfolio Performance PDF extraction. The [update workflow](upgrading-version-support.md) fetches and validates upstream/wrapper releases automatically; new fixture expectations, layouts, and adapter fixes still require maintenance. The pinned synthetic Baader EUR purchase profiles are described in [the fixture contract](../fixtures/v1/pdf-purchase/README.md) and [CLI coverage rules](cli.md#pdf-purchase-preview). They do not establish support for every current owner statement.

1. Record the current upstream revision, image identity, and tested layouts. Choose and pin the target revision; select the importer by document bank identifiers and layout. Smartbroker branding alone does not establish whether DAB, Baader, or another importer applies.
2. Review importer, shared parser, PDF-to-text, dependency, and upstream fixture/test changes. Check transaction types, identifiers, quantities, dates/times, booked/gross amounts, fees, taxes, currencies, rates, and broker references.
3. Obtain anonymized representative PDFs and extracted-text fixtures preserving whitespace/line structure. Keep previous supported layouts as regressions. Respect licensing and attribution; existing samples are not automatically anonymized fixtures.
4. Build the pinned image and verify headless PDF conversion and extraction end to end. Upstream text tests alone do not prove the container's PDF-to-text path works. Adapt the Java integration where needed, preserving authoritative booked amounts and rejecting missing or contradictory information.
5. Test preview/application, supplementation and fallback times, purchase/sale/dividend and FX values, multi-transaction atomicity, duplicates, unsupported layouts, and failure without modification. Load successful outputs with the supported pinned core and inspect financial records and account links.
6. Record only passing layouts plus the exact revision/image, commands, and limitations. Check file compatibility separately using [the version-support guide](upgrading-version-support.md); an importer update does not authorize a model version or migration.

Whole-document coverage requires independent member boundaries and page counts.
The pinned extractor's trade blocks begin at broker references, so an unsupported
or missing-reference later member can be silently omitted without an extraction
error. A successful first item is insufficient. Recognize the source-scoped bank,
heading/reference and page grammar independently, reject uncovered lines/pages,
extract each verified member separately, and compare every exact source value.
Missing time, explicit midnight, references and rates are member-scoped facts.
Present-but-ignored amounts/rates must reject rather than become supplements.

Locate contributor instructions, importer implementations, and extraction tests in the chosen upstream checkout. Record revision-specific source links in concrete update records rather than using mutable `master` as evidence.
