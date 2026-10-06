# Portfolio Performance Pal

A design baseline for a CLI around Portfolio Performance's Java core: query holdings and transaction history, record purchases, sales, and dividends, and import Smartbroker PDFs into an existing portfolio.

Each command will run in a short-lived Docker container. The Java backend will directly reuse upstream persistence, financial models, and PDF extraction. An optional thin Python CLI may improve invocation; it must not reimplement those components. There is no application, Dockerfile, build setup, or validated compatibility matrix yet. Headless build and runtime feasibility must be established first.

- [Current requirements and architecture](docs/spec.md)
- [Domain glossary](GLOSSARY.md) and [import bookkeeping decision](docs/adr/0001-separate-import-bookkeeping.md)
- [Version-support maintenance](docs/upgrading-version-support.md)
- [Broker-extractor maintenance](docs/updating-broker-extractor.md)

The previous Python design and published-ticket record are preserved on the local `python-design` branch. Existing Python-focused GitHub tickets are historical; they are not the implementation plan for this Java baseline.

Files in `samples/` remain reference inputs, not proof of tested support or anonymization. Never commit the owner's real portfolio or publish financial contents. Portfolio Performance must be closed when applying changes.
