# Design Discussion

This document records requirements from the interview about [the original idea](idea.md). The interview stopped at the owner's request. It is a design record, not yet a confirmed implementation plan.

## Settled requirements

- “Add stocks” means record a purchase; “remove stocks” means record a sale of some or all held shares.
- Purchases and sales must preserve transaction history.
- Do not rebuild FIFO lot matching or profit calculations in the CLI. Record the transaction data and derive held quantities for queries; Portfolio Performance remains in use and computes FIFO/profit results itself.
- The initial audience is the portfolio owner; a limited initial feature set is acceptable.
- Normal CLI operations must run independently of Portfolio Performance. Compatibility tests may use its upstream Java code.
- PDF imports must support both review before applying and unattended application. Validation and ambiguity handling remain to be decided.
- Initially support XML with ID references. Binary support is a later phase; its precise representation remains to be defined.
- Support current broker document layouts only. Provide a maintenance guideline for updating the extractor when upstream Portfolio Performance changes.
- Queries cover current holdings and transaction history, with the filters, ordering, and output fields described below.
- Create a security definition when needed for a purchase. Transaction/security deletion is not an initial user-facing requirement; the user permits it only if required by the file model, which must be verified.
- Apply changes by replacing the original file, with a daily backup as detailed below.
- Detect repeated imports automatically where possible, distinguishing confirmed duplicates from suspected matches as detailed below.
- Query output is simple text. JSON and a separately formatted table are not required.
- Manual inputs accept quantity and price, gross value, or booked total together with fees/taxes; derive missing monetary fields using decimal arithmetic and reject inconsistent supplied values beyond supported rounding. PDF imports treat the statement's booked amount as authoritative.
- Support foreign-currency transactions with an explicit or PDF-extracted exchange rate; automatic exchange-rate lookup is deferred.
- Automatically discover the configuration file in the current working directory unless `--config` is supplied. Configuration remembers the portfolio XML path and default securities-account/cash-account pair. `--file` overrides the XML path; command-line account parameters override the configured accounts. Stop if configured accounts cannot be identified unambiguously; dividends use the cash account.
- The default portfolio filename is `portfolio_performance.xml`. This interprets the owner's XML filename response as the portfolio filename rather than a TOML configuration filename; the configuration filename remains unspecified.
- Resolve configured relative paths from the configuration file's directory. Resolve an explicit `--file` relative to the current working directory.
- Before the first successful change of a Europe/Berlin calendar day, preserve the original alongside it as `<filename>.YYYY-MM-DD.bak` if that backup does not exist. Never overwrite the daily backup; backup creation failure prevents writing.
- Use atomic replacement and reject writes if the input changed since preview. Portfolio Performance must be closed while applying changes.
- Skip confirmed re-imports using document identity or broker reference. Amount/price/date matches are suspected duplicates requiring an explicit override; unattended application stops on uncertainty.
- Support one PDF per import; batch import is not required.
- Provide a manual extractor maintenance guide covering the pinned upstream revision, relevant importer changes, extractor/fixture updates, extraction and compatibility checks, and supported layouts. Automatic synchronization is not required.
- Only tested Portfolio Performance versions and their supported ID-reference XML representations may be edited. Reject untested versions before writing and preserve unrelated content in supported files.
- Provide a practical guide for upgrading support to newer Portfolio Performance versions, covering persistence/model changes, fixture updates, compatibility validation, and updating the declared support matrix.
- Mutation commands preview by default. `--apply` writes without an additional interactive prompt after successful validation.
- Holdings queries filter by security identifier/name and securities account; sort by name, quantity, or last updated. Last updated means the latest quantity-changing transaction for that security in that securities account, excluding dividends and security metadata edits. By default omit zero holdings and show newest updates first.
- History queries additionally filter by transaction type and date range; sort by date. Output includes held quantities and recorded transaction amounts, excluding current market valuation and calculated profit.
- Both query commands provide `--limit`, defaulting to 15; `--limit 0` means unlimited. History defaults to newest transactions first. Ordering is stable when timestamps match.
- Validation covers linked transactions, references, monetary consistency, precision, duplicate behavior, and preservation of unrelated data. Compatibility tests load generated files with upstream Java and inspect their transactions. Include purchase, partial/full sale, dividend, and failure-without-modification cases.
- Allow backdated transactions, but reject a sale that causes a negative historical quantity at its date or later in the selected securities account. This validates quantity history without implementing FIFO/profit calculations.
- Command-line parameters may supply missing PDF information, with those values shown in preview. Missing required fields prevent application; conflicting extracted financial values produce an error.
- Keep imported document hashes/broker references and their transaction IDs in an adjacent metadata file, separate from Portfolio Performance XML. If metadata is missing, use suspected-duplicate checks rather than assuming an import is new.
- Retain the three most recent daily backup dates per calendar month, for the current Europe/Berlin calendar month and the preceding 23 months. Prune only this tool's backups after a successful write, never during preview or a failed operation.
- If a single PDF contains multiple recognized transactions, validate and apply all of them atomically. Any unsupported, ambiguous, or invalid transaction prevents the entire import from changing the XML.
- Accept date-only inputs or PDF dates and use `11:00:00` as the fallback time. Show the fallback in preview and preserve an explicitly supplied time.

## Verified upstream facts

- FIFO lot allocations, realized gains, and current quantities are derived from transaction history rather than persisted trade results. The writer must preserve trade primitives and cash/securities consistency, but need not write FIFO results. Sources: [Transaction](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/model/Transaction.java), [CostCalculation](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/security/CostCalculation.java), [CapitalGainsCalculation](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/security/CapitalGainsCalculation.java), [SecurityPosition](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/SecurityPosition.java).
- A normal sale, including closing a holding, does not require deleting purchases or the security definition. Zero holdings result from the transaction quantities. Security deletion is a separate operation. Sources: [SecurityPosition](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/SecurityPosition.java), [Client](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/model/Client.java).
- A purchase or sale links a securities-account transaction with a cash-account transaction. Preserving history does not mean simply appending an XML element: both histories and their references must remain consistent. Source: [BuySellEntry](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/model/BuySellEntry.java).
- Persistence supports XML with XPath or ID references, and compressed/encrypted containers that can contain XML or protobuf. File representation and supported versions remain scope decisions. Source: [ClientFactory](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/model/ClientFactory.java).
- Upstream exposes file-loading APIs and documents core tests without the GUI. Using them for compatibility checks is a possible approach, not an executed validation. Sources: [ClientFactory](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/model/ClientFactory.java), [contributor guide](https://github.com/portfolio-performance/portfolio/blob/master/CONTRIBUTING.md).

## Remaining details

- Select and record the initial tested Portfolio Performance release/source revision and XML model version when establishing fixtures.
- The exact representation and scope of later binary support have not been specified.
- The configuration filename has not been specified; the final filename answer referred to an XML file.

## Documentation

- [Glossary](../GLOSSARY.md)
- [Import bookkeeping ADR](adr/0001-separate-import-bookkeeping.md)
- [Upgrading version support](upgrading-version-support.md)
- [Updating the broker extractor](updating-broker-extractor.md)
