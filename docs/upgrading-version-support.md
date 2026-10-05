# Upgrading Portfolio Performance Version Support

The CLI writes only tested Portfolio Performance versions using ID-reference XML. A successful XML parse does not establish compatibility: the saved object graph, linked transactions, amounts, and unrelated content must remain correct.

## Procedure

1. Record the currently supported Portfolio Performance release, exact source revision, and XML model version. These are distinct identifiers: do not infer XML compatibility from an application release number alone.
2. Select the target release and pin its exact source revision. Read its contributor instructions for the required Java/build environment and core test procedure.
3. Compare persistence and model code between the two pinned revisions, especially `ClientFactory`, `Client`, `Transaction`, `AccountTransaction`, `PortfolioTransaction`, `BuySellEntry`, and security/account references. Inspect migration logic, scaled numerical representations, currencies, fees, taxes, and newly introduced fields.
4. Generate anonymized ID-reference XML fixtures with the target release. Include purchases, partial/full sales, dividends, foreign currencies, multiple account pairs, and unrelated settings/classifications. Keep fixtures for previously supported versions.
5. Update format detection, parsing, writing, and validation where required. Preserve unrelated content and references. Do not bypass the version check merely because the new file parses.
6. Run regression checks for existing supported versions and the target version. Verify no-op preservation, expected transaction changes, monetary consistency, duplicate detection, and failure without modification. Check daily backup behavior as well.
7. Load modified fixtures with the target release's Java reader and inspect the resulting transactions and account links. Loading alone is insufficient: compare quantities, dates, amounts, fees, taxes, currencies, and preserved unrelated data against expectations.
8. Add the target release/revision/model-format combination to the declared support matrix only after these checks pass. State whether earlier combinations remain supported and record any limitations.
9. Review broker-extractor changes separately using [the extractor maintenance guide](updating-broker-extractor.md). File compatibility and PDF extraction are separate contracts.

Exact repository test commands and support-matrix location must be added when the implementation exists; this guide does not assume commands that have not been created.

## Upstream starting points

- [Persistence and format detection](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/model/ClientFactory.java)
- [Linked purchase/sale entries](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/model/BuySellEntry.java)
- [Contributor and core test instructions](https://github.com/portfolio-performance/portfolio/blob/master/CONTRIBUTING.md)

These links identify the relevant files. Use the pinned revisions when comparing or validating a release, rather than mutable `master`.
