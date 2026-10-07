# Command conventions and holdings

Use `pp-pal COMMAND [OPTIONS]`, with the command first. Options can appear in any
order after it. Each value option requires a separate value argument; repeated or
unknown options fail. Names and paths with spaces must be quoted. `--help` is
handled by the host launcher before Docker, configuration or portfolio lookup.
The Java dispatcher uses the same `runtime/help.txt` text.

Current owner commands are `holdings` and `configure`. Developer diagnostics
`inspect`, `roundtrip`, `prove` and `pdf` remain available through the **container**
entry point for the runtime fixture gates; the host launcher exposes owner commands.
History, transactions, PDF import, updates and launch-time backups remain pending.

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

## Linux launcher and maintenance seams

The Bash launcher requires Docker and standard Linux core utilities. It resolves
the selected **local** image once to an immutable identity before either container
runs. It does not pull a missing wrapper image. `PP_PAL_IMAGE` selects a candidate
local tag or image identity for development and compatibility gates; the default
is `pp-pal:0.88.0`.

A read-only Java invocation resolves configuration using the same parser used by
the command. A second invocation mounts the selected portfolio parent and required
configuration/working directories at their canonical host absolute paths, preserving
relative-path semantics and symlinks. Queries mount them read-only; `configure`
allows writing its configuration directory. Both run without network/display and
with the numeric host UID/GID. Mount arguments use shell arrays, including paths
with spaces. Bind paths containing a colon or newline are rejected explicitly
because they cannot be represented in this launcher's Docker volume syntax.

Shared public Java interfaces are `CommandLine.parse/allow/value/flag/limit`,
`Configuration.load/portfolio/selection/path/save`, `Accounts.securities/cash`, and
`Holdings.query/text`. `Main.run` centralizes command dispatch and exit handling.
Later commands extend the dispatch/allowed option sets and use these account/path
interfaces; new boolean flags are registered in `CommandLine`. Launcher-only
resolved-path options are reserved and cannot be supplied by an owner invocation.

`tests/all.sh [IMAGE]` is the cumulative compatibility gate. It currently runs
real core/PDF runtime tests and the owner-facing holdings/configuration tests.
Each new feature must append its independent fixture-backed gate there so an
update candidate cannot validate only the earlier core smoke test. The updater
must use checked-in expectations, never regenerate them from the candidate.

The enlarged core/holdings/configuration boundary passed on 2026-10-07 using local
image `sha256:e7457b460368fb12241d229c75a82d8ea5a6756eb7f0da7f23920890dd4e213c`,
recorded separately from the original core-only proof in `runtime/support-matrix.json`.
The cumulative suite checks actual default/unlimited limits, stable ties and every
promised sort/filter, all upstream quantity directions, shared/ambiguous accounts,
explicit and discovered external/symlinked configuration paths, overrides, exact
portfolio/sidecar/configuration immutability, read-only files and host ownership.
