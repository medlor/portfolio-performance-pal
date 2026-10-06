# Upgrading Portfolio Performance support

The wrapper reuses upstream Java persistence and models. This does not establish rewrite safety or promise stable exported APIs. No combinations or executable checks are validated yet; establish the following evidence before enabling writes.

1. Declare application release, exact upstream revision, XML model version/representation, container image identity, and reproducible build/JDK dependencies separately. Pin the target revision/image and verify that core persistence, models, and PDF extraction build and run headlessly.
2. Review persistence/model changes, including `ClientFactory`, `Client`, `Transaction`, `AccountTransaction`, `PortfolioTransaction`, `BuySellEntry`, and reference relationships. Inspect scaled numbers, currencies, fees, taxes, new fields, and loader/save migrations. Adapt the Java integration; do not add a separate Python serializer.
3. Create anonymized ID-reference XML fixtures for proposed supported combinations. Include multiple account pairs, purchases, partial/full sales, dividends, FX, and unrelated settings/classifications. Keep previously supported fixtures. Do not read or commit the real portfolio to establish fixtures.
4. Document accepted input versions and whether loading/saving migrates them. Define allowed resulting versions and semantics per support-matrix entry. Reject untested representations/versions or unvalidated migrations before modifying the original.
5. Test no-op load/save round trips and intended changes with the pinned core. Save to temporary files and compare financial semantics and unrelated data before atomic replacement. Inspect quantities, dates/times, amounts, fees, taxes, currencies/rates, references, and linked accounts; loading alone is insufficient. Verify older still-supported combinations remain correct.
6. Run command/container checks for preview/apply, duplicates and metadata recovery, quantity history, atomic/stale writes, failure preservation, backups/retention, and host mounts/permissions. Record actual commands and results when they exist.
7. Add only passing combinations to the explicit support matrix, with revision/image identities, migration behavior, limitations, and whether earlier entries remain supported. No release becomes supported automatically.
8. Review extraction separately using [the broker-extractor guide](updating-broker-extractor.md). File compatibility and statement recognition are separate contracts.

Use the selected upstream checkout's contributor instructions and persistence/model tests as starting points. Concrete upgrade records must link to pinned revisions and include reproducible validation commands.
