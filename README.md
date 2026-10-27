# Portfolio Performance Pal

A Linux CLI around Portfolio Performance's Java core. Holdings, transaction history, explicit account configuration and monthly/pre-upgrade backups and validated local release updates are implemented. Same-currency manual purchase preview/application, including explicit security creation or identifier-based reuse, is implemented with mandatory release validation, atomic replacement, daily protection and verified cleanup recovery. Same-currency manual sales validate the complete affected holding history, retain definitions and transactions after full disposal, and use the same protected writer. Same-currency dividends record security-associated cash credits with fees/taxes, including after a holding closes, without changing quantity or holding last updated. Manual foreign-currency purchases, sales and dividends use explicit exchange rates and preserve upstream gross FX units. Fixture-scoped Baader EUR purchase/sale PDFs support atomic batches, duplicate-safe bookkeeping and recovery; additional Smartbroker layouts remain pending.

Build the pinned local Java/Docker runtime and run the cumulative fixture checks:

```sh
docker build --platform linux/amd64 -t pp-pal:0.88.0 .
bash tests/all.sh
./pp-pal --help
./pp-pal holdings --file '/path/to/portfolio.xml'
./pp-pal holdings --security 'Instrument name' --sort quantity --limit 0
./pp-pal history --file '/path/to/portfolio.xml' --type sale --from 2025-01-01 --limit 0
```

Tests share a resource budget across modules and cases; set `PP_PAL_TEST_JOBS=1`
for serial execution. See [test concurrency and resource limits](docs/test-suite-performance.md).

## Network access

Builds run as your user and fetch three archives. Each archive is verified
against the SHA-256 pinned in `runtime/release-package.json` and then
cached by digest. The cache is `${PP_PAL_ARCHIVE_CACHE:-~/.local/state/pp-pal/downloads}`,
so later builds and tests download nothing. The updater also discovers
releases over HTTPS. The following addresses must be reachable:

| Purpose | URL |
|---|---|
| Temurin 21.0.11+10 JDK | `https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.11_10.tar.gz` |
| PP source at the pinned revision | `https://api.github.com/repos/portfolio-performance/portfolio/tarball/<revision>` |
| PP Linux release (dependency jars) | `https://github.com/portfolio-performance/portfolio/releases/download/<tag>/PortfolioPerformance-<tag>-linux.gtk.x86_64.tar.gz` |
| PP release discovery (updater) | `https://api.github.com/repos/portfolio-performance/portfolio/releases/latest`, `…/git/ref/tags/…`, `…/git/tags/…` |
| Wrapper release discovery and tarballs (updater) | `https://api.github.com/repos/medlor/portfolio-performance-pal/releases/latest`, `…/git/…`, `…/tarball/<revision>` |
| Redirect targets of the GitHub downloads | `codeload.github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com` |
| Pin lookup by maintainers only | `api.adoptium.net` |

To build offline, put the three files in one directory as
`jdk.tar.gz`, `upstream-source.tar.gz` and `upstream-release.tar.gz`, and
run `tools/build-runtime --inputs DIR --output …`. Alternatively, place
them in the cache directory, each named `<sha256>.tar.gz`. The digests are
checked either way.

The launcher fixes the selected local image identity for each invocation. A read-only Java container resolves configuration, then a short-lived command container reads the selected portfolio with the host UID/GID. Java reuses upstream persistence, financial models and PDF extraction. Images stay local; no wrapper image is published. [Headless build evidence and limits](docs/headless-runtime.md) and the [support matrix](runtime/support-matrix.json) record the tested upstream 0.88.0, ID-reference XML model 70, Linux amd64 combination.

The default portfolio is `portfolio_performance.xml` in the working directory. Discover `pp-pal.properties` there or select another with `--config`. Its UTF-8 Java Properties keys are `file`, `securities-account`, and `cash-account`; configured relative file paths resolve from the configuration directory, while explicit `--file` paths resolve from the working directory. Account selectors accept a stable ID or exact unique name. Save resolved account IDs explicitly:

```sh
./pp-pal configure --file '/path/to/portfolio.xml' \
  --securities-account 'My securities account' --cash-account 'My cash account'
```

Queries preserve the portfolio, import metadata and configuration. Holdings omit zero quantities by default, sort newest quantity updates first and show 15 rows; `--include-zero`, `--ascending`, and `--limit 0` adjust those controls. History retains closed purchases/sales and shows relevant dividends once, with their original cash owner and any ambiguous securities-account attribution. History defaults to newest dates and 15 rows; date bounds are inclusive. [Command conventions and reusable interfaces](docs/cli.md) describe exact filters, ties, exit codes and path behavior. `--help` requires neither Docker nor a portfolio.

Launches create a due Europe/Berlin monthly snapshot of the current selected file
alongside it, using the host UID/GID and mode 0600. The selected parent directory
must allow required backup creation even for queries. `./pp-pal backup-before-update`
preserves an additional exact snapshot for each actual upgrade attempt; it does
not discover or activate updates. Launches never prune recovery files. Two-year
additional-backup pruning will be wired only after successful financial writes.

`./pp-pal --update --file '/path/to/portfolio.xml'` builds and validates changed upstream/wrapper release pairs locally before activation, including a disposable selected-file cross-engine comparison. It requires a stable wrapper release containing this implementation; no usable wrapper release currently exists, so production discovery fails clearly until that bootstrap package is released. The dispatcher runs this preparation before purchase/sale/dividend preview and application and PDF preview/application. Purchase, sale and dividend use `--apply --desktop-closed` to persist financial changes. [Local updater and bootstrap packaging](docs/local-updater.md) describes Python 3, retained pair manifests, independent gates and recovery. Desktop updates remain the owner's responsibility.

Manual sales accept the existing-security purchase selectors and decimal options.
For sales, `--total` means cash credit after fees/taxes. Missing sale times use the
disclosed `11:00:01` fallback; explicit times are preserved. Backdated sales reject
if any balance in the selected security/securities-account history would become
negative. Equal timestamps process increases first, then decreases in persisted
account-list order, with the proposed sale last among decreases. See
[manual sale commands](docs/cli.md#manual-sales) for an example and validation rules.

- [Current requirements and architecture](docs/spec.md)
- [Domain glossary](GLOSSARY.md) and architectural decisions: [import bookkeeping](docs/adr/0001-separate-import-bookkeeping.md), [Java reuse](docs/adr/0002-reuse-portfolio-performance-java-core.md), [tested migrations](docs/adr/0003-allow-tested-migrations-with-transactions.md), [validated local updates](docs/adr/0004-validate-local-updates-before-processing.md)
- [Version-support maintenance](docs/upgrading-version-support.md)
- [Broker-extractor maintenance](docs/updating-broker-extractor.md) and [PP reuse audit](docs/upstream-reuse.md)
- [Update automation research](docs/update-automation-research.md)

The previous Python design and published-ticket record are preserved on the local `python-design` branch. Existing Python-focused GitHub tickets are historical; they are not the implementation plan for this Java baseline.

Files in `samples/` remain reference inputs, not proof of tested support or anonymization. Never commit the owner's real portfolio or publish financial contents. Portfolio Performance must be closed when applying changes.


Dividend PDF imports now share duplicate-safe atomic application and recovery.
The [fixture-tested dividend profile](fixtures/v1/pdf-dividend/README.md) accepts
invented German EUR Baader page-1-of-1 members and mixed purchase/dividend
batches; dividend-only requests need a cash account, with no securities account.
PP is authoritative for all three PDF transaction paths. No parallel financial
parser or manufactured supplemental text remains. Real current Smartbroker layouts
still require complete representative qualification. See [CLI details](docs/cli.md#pdf-dividend-import).
