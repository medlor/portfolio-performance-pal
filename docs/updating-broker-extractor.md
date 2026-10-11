# Updating the upstream broker extractor

The Java backend directly uses Portfolio Performance PDF extraction. The [update workflow](upgrading-version-support.md) fetches and validates upstream/wrapper releases automatically; new fixture expectations, layouts, and adapter fixes still require maintenance. The pinned synthetic Baader EUR purchase/sale profiles are described in [the fixture contract](../fixtures/v1/pdf-purchase/README.md) and [CLI coverage rules](cli.md#pdf-purchase-preview). They do not establish support for every current owner statement.

1. Record the current upstream revision, image identity, and tested layouts. Choose and pin the target revision; select the importer by document bank identifiers and layout. Smartbroker branding alone does not establish whether DAB, Baader, or another importer applies.
2. Review importer, shared parser, PDF-to-text, dependency, and upstream fixture/test changes. Check transaction types, identifiers, quantities, dates/times, booked/gross amounts, fees, taxes, currencies, rates, and broker references.
3. Obtain anonymized representative PDFs and extracted-text fixtures preserving whitespace/line structure. Keep previous supported layouts as regressions. Respect licensing and attribution; existing samples are not automatically anonymized fixtures.
4. Build the pinned image and verify headless PDF conversion and extraction end to end. Upstream text tests alone do not prove the container's PDF-to-text path works. Adapt the Java integration where needed, preserving authoritative booked amounts and rejecting missing or contradictory information.
5. Test preview/application, supplementation and fallback times, purchase/sale/dividend and FX values, multi-transaction atomicity, duplicates, unsupported layouts, and failure without modification. Load successful outputs with the supported pinned core and inspect financial records and account links.
6. Record only passing layouts plus the exact revision/image, commands, and limitations. Check file compatibility separately using [the version-support guide](upgrading-version-support.md); an importer update does not authorize a model version or migration.

Use the [PP reuse boundary](upstream-reuse.md): bank grammar and extracted financial
records belong upstream. Do not validate extraction by implementing another bank
parser or adding invented supplemental text. Observe PP's existing hooks for
explicit time, identifiers, cost presence and numeric representability. Validate
records with PP import actions and insert them with `InsertAction`.

Physical page completeness is wrapper policy. Group complete actual-page sequences,
convert each statement with PP and reject a whole PDF if any statement fails,
skips, contains unsupported additional items or has incomplete page coverage.
PP intentionally ignores some printed fields and content; do not promise exact
coverage of every source line. Test that the wrapper preserves PP's result directly,
with separate fixed financial/model-preservation expectations.

Locate contributor instructions, importer implementations, and extraction tests in the chosen upstream checkout. Record revision-specific source links in concrete update records rather than using mutable `master` as evidence.

Purchase application now retains these same fixtures and adds the mandatory
`tests/pdf-apply.sh` gate for complete-document writes, current-model duplicate
evidence, overrides, stale inputs, migration protection and metadata recovery.
No new broker grammar or statement layout is inferred from application support.

Sale grammar and independent literals are in [the sale fixture contract](../fixtures/v1/pdf-sale/README.md).
`tests/pdf-sale.sh` is mandatory cumulative qualification and retains all prior
purchase gates. Verify PP transaction types/gross/cost units, explicit/fallback times,
whole-history admission after all new members, confirmed skip before insertion,
and metadata failure recovery before widening sale layout support. Invented
purchase/sale mixed members cover atomic ordering. Synthetic PP EUR/USD purchase/dividend
regressions preserve extracted units; the FX gate below adds sales and complete mixed batches.


Dividend grammar and independent literals are in the
[dividend fixture contract](../fixtures/v1/pdf-dividend/README.md).
`tests/pdf-dividend.sh` is mandatory cumulative qualification. Keep cash-only
selection, authoritative credits, distribution shares, explicit/fallback times,
closed-holding invariance/relevance, mixed-member atomicity and cash-record
identity/recovery checks when changing extraction. Bind the existing security to PP-produced records, then apply PP import checks and
the disclosed timestamp policy. `tests/upstream-reuse.sh` compares original PP
extraction with plans and saved outputs; no second bank parser is permitted. All earlier layout gates stay
mandatory. Synthetic rendered PDFs prove conversion and their exact grammar;
complete representative current-layout evidence is still required before claiming
real Smartbroker dividend support. The supplied local CHF reference has one physical page marked `Seite 1/2`; it
cannot qualify a complete current layout. A missing broker reference alone is no
longer a rejection: exact document identity handles that case. Pinned extraction recognizes its dividend, but wrapper refusal preserves
financial bytes; do not treat extraction success as rewrite or layout-coverage proof.


Foreign-currency/mixed changes must pass `tests/pdf-fx.sh` as well as all retained
PDF gates. The [FX fixture contract](../fixtures/v1/pdf-fx/README.md) records the
invented EUR/USD profiles, cost conversions and independent literal oracle.
Keep printed-rate direction, foreign costs, authoritative cash totals, same-time
sale-before-buy page order, invalid later members, uncertainty override and
post-replacement recovery coverage. The wrapper supplements only absent model
conversion, never edits statement text or replaces extracted financial units.
Use the declared source revision/model/image evidence in the support matrix;
synthetic regression success does not establish complete current owner-layout support.
