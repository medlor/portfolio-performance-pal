# Portfolio Performance Pal

A design baseline for a CLI around Portfolio Performance's Java core: query holdings and transaction history, record purchases, sales, and dividends, and import Smartbroker PDFs into an existing portfolio.

Each command will run in a short-lived Docker container. The Java backend will own the CLI and directly reuse upstream persistence, financial models, and PDF extraction, with a small shell launcher for invocation. Images will be built locally; no wrapper image will be uploaded to an external registry. There is no application, Dockerfile, build setup, or validated compatibility matrix yet. Headless build and runtime feasibility must be established first.

The selected design checks for updates before preview/import. `--update` builds and validates changed upstream/wrapper release pairs locally before activation, including checks against a disposable copy of the selected portfolio. `--apply` records financial changes. Linux is the initial supported host; desktop application updates remain the owner's responsibility.

- [Current requirements and architecture](docs/spec.md)
- [Domain glossary](GLOSSARY.md) and architectural decisions: [import bookkeeping](docs/adr/0001-separate-import-bookkeeping.md), [Java reuse](docs/adr/0002-reuse-portfolio-performance-java-core.md), [tested migrations](docs/adr/0003-allow-tested-migrations-with-transactions.md), [validated local updates](docs/adr/0004-validate-local-updates-before-processing.md)
- [Version-support maintenance](docs/upgrading-version-support.md)
- [Broker-extractor maintenance](docs/updating-broker-extractor.md)
- [Update automation research](docs/update-automation-research.md)

The previous Python design and published-ticket record are preserved on the local `python-design` branch. Existing Python-focused GitHub tickets are historical; they are not the implementation plan for this Java baseline.

Files in `samples/` remain reference inputs, not proof of tested support or anonymization. Never commit the owner's real portfolio or publish financial contents. Portfolio Performance must be closed when applying changes.
