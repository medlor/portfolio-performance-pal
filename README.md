# Portfolio Performance Pal

A Linux CLI around Portfolio Performance's Java core. Holdings, transaction history and explicit account configuration are implemented. Manual purchases/sales/dividends, updates, backups and Smartbroker import are still pending implementation.

Build the pinned local Java/Docker runtime and run the cumulative fixture checks:

```sh
docker build --platform linux/amd64 -t pp-pal:0.88.0 .
bash tests/all.sh
./pp-pal --help
./pp-pal holdings --file '/path/to/portfolio.xml'
./pp-pal holdings --security 'Instrument name' --sort quantity --limit 0
./pp-pal history --file '/path/to/portfolio.xml' --type sale --from 2025-01-01 --limit 0
```

The launcher fixes the selected local image identity for each invocation. A read-only Java container resolves configuration, then a short-lived command container reads the selected portfolio with the host UID/GID. Java reuses upstream persistence, financial models and PDF extraction. Images stay local; no wrapper image is published. [Headless build evidence and limits](docs/headless-runtime.md) and the [support matrix](runtime/support-matrix.json) record the tested upstream 0.88.0, ID-reference XML model 70, Linux amd64 combination.

The default portfolio is `portfolio_performance.xml` in the working directory. Discover `pp-pal.properties` there or select another with `--config`. Its UTF-8 Java Properties keys are `file`, `securities-account`, and `cash-account`; configured relative file paths resolve from the configuration directory, while explicit `--file` paths resolve from the working directory. Account selectors accept a stable ID or exact unique name. Save resolved account IDs explicitly:

```sh
./pp-pal configure --file '/path/to/portfolio.xml' \
  --securities-account 'My securities account' --cash-account 'My cash account'
```

Queries preserve the portfolio, import metadata and configuration. Holdings omit zero quantities by default, sort newest quantity updates first and show 15 rows; `--include-zero`, `--ascending`, and `--limit 0` adjust those controls. History retains closed purchases/sales and shows relevant dividends once, with their original cash owner and any ambiguous securities-account attribution. History defaults to newest dates and 15 rows; date bounds are inclusive. [Command conventions and reusable interfaces](docs/cli.md) describe exact filters, ties, exit codes and path behavior. `--help` requires neither Docker nor a portfolio.

The selected design will check for updates before preview/import. Planned `--update` builds and validates changed upstream/wrapper release pairs locally before activation, including checks against a disposable copy of the selected portfolio. Planned `--apply` records financial changes. These commands are not implemented yet. Desktop application updates remain the owner's responsibility.

- [Current requirements and architecture](docs/spec.md)
- [Domain glossary](GLOSSARY.md) and architectural decisions: [import bookkeeping](docs/adr/0001-separate-import-bookkeeping.md), [Java reuse](docs/adr/0002-reuse-portfolio-performance-java-core.md), [tested migrations](docs/adr/0003-allow-tested-migrations-with-transactions.md), [validated local updates](docs/adr/0004-validate-local-updates-before-processing.md)
- [Version-support maintenance](docs/upgrading-version-support.md)
- [Broker-extractor maintenance](docs/updating-broker-extractor.md)
- [Update automation research](docs/update-automation-research.md)

The previous Python design and published-ticket record are preserved on the local `python-design` branch. Existing Python-focused GitHub tickets are historical; they are not the implementation plan for this Java baseline.

Files in `samples/` remain reference inputs, not proof of tested support or anonymization. Never commit the owner's real portfolio or publish financial contents. Portfolio Performance must be closed when applying changes.
