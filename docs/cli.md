# Command conventions and queries

Use `pp-pal COMMAND [OPTIONS]`, with the command first. Options can appear in any
order after it. Each value option requires a separate value argument; repeated or
unknown options fail. Names and paths with spaces must be quoted. `--help` is
handled by the host launcher before Docker, configuration or portfolio lookup.
The Java dispatcher uses the same `runtime/help.txt` text.

Current owner commands are `holdings`, `history`, `configure`, and `backup-before-update`. Developer diagnostics
`inspect`, `roundtrip`, `prove` and `pdf` remain available through the **container**
entry point for the runtime fixture gates; the host launcher exposes owner commands.
Transactions, PDF import and release discovery/activation remain pending.

Exit 0 means a successful query/configuration operation (later also preview,
successful write or confirmed re-import skip). Exit 2 rejects input or validation,
including unknown/ambiguous account selectors, invalid options and unsupported
portfolio representations. Exit 3 reports an operational failure, including an
unavailable local image or inability to write explicit configuration. Exit 4 is
reserved for a successful financial replacement with failed follow-up work.
Errors go to stderr with `ERROR:`; ordinary output goes to stdout. Upstream loading
may also print informational status lines to stderr. Successful empty queries show
the column heading and no records.

## Configuration and account choices

Configuration is UTF-8 Java Properties, `pp-pal.properties` discovered in the host
working directory, or a path supplied through `--config`. An absent discovered
file is normal; an absent explicit file is rejected for queries. `configure` may
create an explicitly selected configuration in an existing directory.

```properties
file=../portfolios/my portfolio.xml
securities-account=30000000-0000-4000-8000-000000000001
cash-account=20000000-0000-4000-8000-000000000001
```

Java Properties escaping and comments apply. Configured `file` paths resolve
relative to the physical configuration directory. Explicit `--file` paths resolve
relative to the original physical working directory and override configuration.
Without a configured `file`, the default is `portfolio_performance.xml` in the
working directory. Explicit account options override the corresponding configured
defaults. With no securities account selector, holdings span all securities
accounts. Query filters do not require selecting an account pair.

Account selection first matches a stable upstream UUID, then an exact unique
name. Duplicate names, nonexistent selections and empty selections are rejected;
the CLI never guesses. If a selector was supplied for the cash account it is also
validated, even though cash transactions do not change holdings.

`configure` loads the selected supported portfolio and requires a securities
account, resolving an explicit or existing default. It resolves a selected cash
account or uses that securities account's reference cash account. It saves the
selected absolute portfolio path and both resolved UUIDs, retaining other
properties. It uses a temporary file and atomic replacement in the configuration
directory, and refuses a non-writable existing configuration. It never saves the
financial portfolio. Queries never call configuration saving.

## Holdings controls

Output is simple text: securities account, security name, recorded quantity and
holding last updated. There are no live prices, valuations or calculated profits.

- Quantities are grouped by upstream security identity and securities account.
  Purchases, transfers in and deliveries inbound increase quantity; sales,
  transfers out and deliveries outbound decrease it. Closed holdings retain
  history and appear only with `--include-zero`.
- `--security` matches an exact UUID, ISIN, WKN or ticker (case-insensitive), or a
  case-insensitive name substring. These fuzzy query filters must not be reused
  to select a financial mutation's security.
- `--securities-account ID|NAME` selects one resolved securities account.
- `--sort updated|quantity|name` defaults to `updated`, descending. Name sorting
  uses case-insensitive names. `--ascending` reverses only the primary sort key.
- Every equal primary key uses ascending security UUID then securities-account
  UUID as the stable tie order. Direction changes do not reverse these ties.
- `--limit` defaults to 15, and 0 is unlimited. Negative, fractional, nonnumeric
  and out-of-range limits are rejected.
- Holding last updated is the latest date/time of a transaction changing quantity
  in that security/securities account. Dividends, prices, security metadata and
  object modification timestamps are excluded. Supplied transaction times are
  displayed with their recorded precision.

## History controls and dividend relevance

`pp-pal history` shows actual securities-account records and relevant original
cash-account dividends. Purchase/sale records remain after the holding closes;
security definitions and closed history are retained. Each linked purchase/sale
appears as its securities-account record, with the cash owner of its linked entry,
instead of displaying the companion cash entry as a second trade. Transfers and
deliveries retain their recorded upstream types. A transfer has one record in
each affected securities account. Records without a cash transaction show `—` in
the cash-owner column. Quantities and currency/booked amounts are recorded values;
there are no live valuations or calculated profits.

- `--security` uses the same identifier/name filter as holdings.
- `--securities-account ID|NAME` resolves one exact account. Configured account
  defaults and explicit overrides follow the shared configuration rules.
- `--type purchase|sale|dividend` selects the exact recorded type. Transfers and
  deliveries are distinct; their filters are `transfer-in`, `transfer-out`,
  `delivery-inbound`, and `delivery-outbound`.
- `--from YYYY-MM-DD` and `--to YYYY-MM-DD` are inclusive calendar-date bounds.
  Invalid dates or a start after the end are rejected. A bound includes every
  recorded time on that day; the query does not change timestamps.
- `--sort date` is the supported sort. Dates are newest first by default;
  `--ascending` reverses only dates. Equal timestamps always use ascending original
  transaction UUIDs, independent of list/loading order.
- `--limit` defaults to 15; 0 means unlimited. Filtering and sorting precede the
  limit. Empty results show the column heading without records.

For a dividend, a securities account is relevant when its **reference cash
account is the original cash owner** and its **complete transaction history
contains that security**. This follows the pinned upstream
[`PortfolioClientFilter` reference-cash/security rule](https://github.com/portfolio-performance/portfolio/blob/8de1fe41d71a2c1b74a6bfbe913f885899aaf123/name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/filter/PortfolioClientFilter.java).
Relevance is computed before the query's type/date filters and does not depend on
the current holding. Closed holdings and a delivery/transfer history can therefore
make a dividend relevant. With no selected securities account, the query includes
the union of dividends relevant to any securities account; truly unrelated cash
owners/security combinations are excluded.

Every original dividend is listed once, with its owning cash account. Distinct
transactions with identical dates, amounts and security remain distinct records.
When several securities accounts share that cash account and security history,
the row says `AMBIGUOUS:` and lists all relevant account names and UUIDs. A filter
selecting one of those accounts retains the ambiguity marker and complete relevant
list: relevance does not prove ownership. The query does not emit the upstream
filter's synthetic balancing deposit/removal records as real history.

`tests/history.sh` verifies independent fixture output through the launcher, and
`DividendRelevanceOracle.java` also checks independently specified 3/2/1 dividend
counts through the pinned upstream public filter. The edge fixture contains equal
valid records and irrelevant security/cash combinations; it is copied into a
temporary directory for owner CLI tests. Financial portfolio, import sidecar and
configuration bytes remain unchanged.

## Linux launcher and maintenance seams

The Bash launcher requires Docker and standard Linux core utilities. It resolves
the selected **local** image once to an immutable identity before either container
runs. It does not pull a missing wrapper image. `PP_PAL_IMAGE` selects a candidate
local tag or image identity for development and compatibility gates; the default
is `pp-pal:0.88.0`.

A read-only Java invocation resolves configuration using the same parser used by
the command. A second invocation mounts the selected portfolio parent and required
configuration/working directories at their canonical host absolute paths, preserving
relative-path semantics and symlinks. The selected portfolio parent is writable
to create required adjacent backups even for queries; other mounts are read-only
unless `configure` needs to write its configuration directory. Both run without network/display and
with the numeric host UID/GID. Mount arguments use shell arrays, including paths
with spaces. Bind paths containing a colon or newline are rejected explicitly
because they cannot be represented in this launcher's Docker volume syntax.

Shared public Java interfaces are `CommandLine.parse/allow/value/flag/limit`,
`Configuration.load/portfolio/selection/path/save`, `Accounts.securities/cash`, and
`Holdings.query/text`, and `History.query/date/text`. `Main.run` centralizes command dispatch and exit handling.
Later commands extend the dispatch/allowed option sets and use these account/path
interfaces; new boolean flags are registered in `CommandLine`. Launcher-only
resolved-path options are reserved and cannot be supplied by an owner invocation.

`tests/all.sh [IMAGE]` is the cumulative compatibility gate. It currently runs
real core/PDF runtime tests and the owner-facing holdings/configuration/history/backup tests.
Each new feature must append its independent fixture-backed gate there so an
update candidate cannot validate only the earlier core smoke test. The updater
must use checked-in expectations, never regenerate them from the candidate.

## Launch and pre-upgrade protection

After resolving a readable regular selected file, every owner launch creates one
due **Europe/Berlin** monthly snapshot alongside it. This copies exact bytes before
financial-model loading, so future unsupported input can still receive upgrade
protection. A query can subsequently reject its unsupported representation; a
raw backup does not declare that model financially supported. Help and path-only
resolution do not create backups. A symlinked selected portfolio is resolved by
the Linux launcher to its real file; backups live beside that target.

Monthly names are `<filename>.pp-pal.monthly.YYYY-MM.<uuid>.bak`. A launch after
inactivity captures the present state once for the current month; it never
fabricates missed monthly snapshots or runs a background timer. The ownership
index records the month and hash. Later launches in the same month verify the
existing indexed snapshot and leave its bytes and index unchanged.

`pp-pal backup-before-update [--file PATH] [--config PATH]` is the public maintenance
boundary for the updater. It creates any due monthly protection and then an exact
snapshot named `<filename>.pp-pal.update.<UTC-instant-without-colons>.<uuid>.bak`,
printing its absolute path. Every call is an **actual-attempt** operation, including
retries at the same instant. It does not discover releases, build or activate a
candidate. The following updater ticket must call it only after detecting a changed
pair; unchanged release checks must not call it and create no update backup.
Configured account selections are irrelevant to copying raw bytes.

Backups, the adjacent `<filename>.pp-pal-backups.properties` ownership index, and
its advisory `.pp-pal-backups.lock` file use host UID/GID and mode **0600**. Required
snapshots are exclusively created, flushed with their containing directory, and
never overwritten. UUID names isolate collisions, including same-instant retries.
Only index-listed names and hashes authorize tool ownership; unindexed lookalikes
remain unowned. Snapshot/index/lock symlinks, foreign index formats, altered or
missing required indexed monthly snapshots, unwritable paths, and a concurrent
backup lock stop the protected operation with operational exit 3. A failed index
installation can leave an unindexed protective snapshot; leave it intact and repair
the index deliberately instead of treating it as automatically owned.

Launch and backup-only operations never prune any backups, write the selected
portfolio, change import metadata, or create daily/migration backups. The shared
Java `BackupService(Path, Clock)` exposes `monthly()`, `beforeUpgrade()` and
`pruneAdditionalAfterSuccessfulWrite()`. The latter retains monthly/update backups
until two calendar years after their creation in Berlin (the exact cutoff is
retained), verifies owned snapshot hashes, and preserves unowned files. Only the
future safe financial writer calls it after successful replacement. It is not
wired to an owner command here; end-to-end successful-write retention and daily
backup gates belong to #24, and migration protection to #29. No passing retention
integration result is claimed yet.

The explicit clock boundary `PP_PAL_NOW` accepts an ISO UTC instant for reproducible
compatibility gates; when unset, the system UTC clock is converted to Berlin for
calendar decisions. The launcher passes it to both fixed-image invocations. It
changes backup timing only, never transaction dates. Keep it unset for ordinary
use. `tests/backups.sh IMAGE` exercises real launcher/container snapshots, spring
and autumn month boundaries, unchanged months and catch-up, repeated attempts,
selected paths, private permissions and ownership, old/unowned retention during
launch, raw future input, uncertainty and permission failures.

On 2026-10-07, `bash tests/all.sh
sha256:4f386e29190b6ccffab438721fd026e5f6dd7766dc18be9f3acfde47efe11573`
passed the cumulative core/PDF, holdings/configuration and launch/update-backup
gates. `runtime/support-matrix.json` records this image separately and explicitly
leaves successful-financial-write additional retention validation pending #24.

The enlarged core/holdings/configuration boundary passed on 2026-10-07 using local
image `sha256:e7457b460368fb12241d229c75a82d8ea5a6756eb7f0da7f23920890dd4e213c`,
recorded separately from the original core-only proof in `runtime/support-matrix.json`.
The cumulative suite checks actual default/unlimited limits, stable ties and every
promised sort/filter, all upstream quantity directions, shared/ambiguous accounts,
explicit and discovered external/symlinked configuration paths, overrides, exact
portfolio/sidecar/configuration immutability, read-only files and host ownership.

The history boundary and the full cumulative gate passed on 2026-10-07 using
image `sha256:ba64923a7d8958e1d96760692829262277d732e18052677321ee2cc23025e1b6`,
retained locally as `pp-pal:validated-issue20-ba64923a7d89`. History checks use
fixed original recorded amounts, dates and quantities; the public upstream
`PortfolioClientFilter` independently verifies dividend relevance for shared
reference cash accounts and closed securities histories.

The combined history and backup integration passed `bash tests/all.sh
sha256:6ef50141fe9448bf8da781d81539195bde2dc88dec6e5a560fc3307041ac28d4`
on 2026-10-07. This gate includes runtime/PDF, holdings/configuration, history
and launch/pre-upgrade protection, including competing backup writers. The image
is retained as `pp-pal:validated-integration20-21-6ef50141fe94`; the support matrix
keeps each earlier image's evidence separate from this combined result.
