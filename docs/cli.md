# Command conventions and queries

Use `pp-pal COMMAND [OPTIONS]`, with the command first. Options can appear in any
order after it. Each value option requires a separate value argument; repeated or
unknown options fail. Names and paths with spaces must be quoted. `--help` is
handled by the host launcher before Docker, configuration or portfolio lookup.
The Java dispatcher uses the same `runtime/help.txt` text.

Current owner commands are `holdings`, `history`, `purchase`, `sale`, `dividend`, `import`, `configure`, `backup-before-update`,
and `--update`. Developer diagnostics
`inspect`, `roundtrip`, `prove` and `pdf` remain available through the **container**
entry point for the runtime fixture gates; the host launcher exposes owner commands.
Additional PDF layouts remain pending. Release discovery/validation/activation
is implemented, with the initial released bootstrap package still a prerequisite.

Exit 0 means a successful query/configuration/preview or financial operation. Exit 2 rejects input or validation,
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

## Purchase preview

`purchase` defaults to a same-currency purchase preview of an existing security;
`--apply --desktop-closed` commits a fresh plan as described below. Supply a required securities/cash account
pair explicitly or through configuration, an exact `--security` selector, ISO
`--date`, positive `--quantity`, and one or more of `--price`, `--gross`, or
`--total` (booked debit). Optional `--fees` and `--taxes`
default to zero. Security selection accepts an exact stable UUID, external
identifier, or exact name. `--security-id`, `--isin`, `--wkn`, and `--ticker`
add exact constraints; their intersection must select one existing definition.
An ambiguous name plus a stable UUID can resolve one security; contradictory
constraints reject. Fuzzy query name filtering does not select
financial records. Currency must agree between the security and selected cash
account. Linked proposed records use the upstream BuySellEntry and monetary units.

The launcher performs mandatory release discovery and selected-file validation
before constructing a plan, then fixes the selected immutable image for that
invocation. Preview shows that image, the input/output model version, accounts,
existing security, quantity, currencies, fee/tax/booked values and their origins.
Missing time defaults to `11:00:00` and is marked as a fallback. Explicit local
times, including midnight and fractional seconds, retain their supplied value.
Portfolio, import metadata and configuration remain byte-for-byte unchanged;
permitted launch/update protection is distinct from financial daily/migration
backups. No FIFO, profit, prices or rates are fetched or persisted.

Share quantities and supplied prices must be exactly representable at upstream
8-place scales; explicit fees/taxes are exact cents. Trailing zeros are harmless.
Price times quantity is multiplied exactly before derived gross is rounded
HALF_UP to cents; booked debit adds fees and taxes with checked integer arithmetic.
Gross/booked values are exact cents too. Supplied values must agree with rounded
quantity times price and gross plus costs. When price is absent, gross divided by
quantity gives a display value rounded HALF_EVEN to eight places, checked against
the upstream quote range. The derived quote is not persisted as a price record.
Values exceeding scaled integer range or containing nonzero excess precision
reject. Positive-quantity purchases can have zero booked value when their gross
rounds to zero; preview explicitly shows the zero and its derivation.

`Purchase.plan(Client, Portfolio, Account, CommandLine)` returns one detached
`Purchase.Plan` containing the actual linked upstream entry and its amount origins;
it does not insert into account lists or save anything. The subsequent writer can
consume that plan once against its invocation's snapshot; a PDF planner can share
`Amounts` and exact `Securities.existing` selection. Each invocation plans anew.

The fixed updater gates exercise the real released purchase launcher. During those
checks, only descendants of the live identical trusted updater can obtain a
temporary Unix-socket qualification for its exact built wrapper/upstream/image,
reviewed suite and checked archive bytes. They still discover releases, validate
the selected disposable portfolio and recheck staleness; they reuse the built pair
without recursively invoking its gates. Qualification cannot activate a production
pair. Owner-injected, expired, foreign-parent or mismatching qualifications fail
closed; there is no owner option to skip the update workflow.

## PDF purchase preview

```sh
pp-pal import --pdf 'statements/purchase statement.pdf' \
  --securities-account 'My Securities' --cash-account 'My Cash'
```

`import` reads one PDF, performs mandatory released-pair preparation, and previews
all supported purchase members together. No `--apply` option exists for PDF import.
The source file is individually mounted read-only, including when its parent is
also the writable portfolio directory. Paths with spaces, relative paths and
absolute external PDF paths follow the launcher rules. The Java planner snapshots
the actual bytes before conversion, converts those bytes through upstream
`PDFInputFile.convertPDFtoText()`, and rechecks source bytes before returning output.

Supported profiles are the independently invented German Baader EUR purchase
fixtures: one flat single-page member, or one or more one-page members with an
exact `Baader Bank AG` header immediately followed by `Seite 1/1`. The number of
proved page starts must match the physical PDF page count. Each member requires
its own unique broker reference, purchase heading and ISIN/WKN section. All
nonblank lines must match the reviewed grammar, including the security name
continuation. Other issuers, Smartbroker branding alone, unknown/trailing lines,
continuations, FX, sales and dividends reject the entire PDF before any preview.
This proves fixture-scoped grammar, not support for every current Baader layout.

Raw German decimal tokens are checked against the exact shared `Amounts` rules
before comparison with actual upstream extracted models. Booked debit is
authoritative; quantity, price, gross, fees and taxes must agree. Present ignored,
malformed, duplicated or contradictory values cannot be replaced by CLI options.
ISIN and WKN jointly resolve one existing security, with any CLI constraints also
intersected. Extraction uses an isolated security cache and then binds the detached
entry to that exact existing definition, preserving its currency/name/settings.
No definition is created or modified in the owner portfolio.

The existing purchase value options can supplement truly absent fields in a
single-member PDF. An absent quantity/price section requires both `--quantity` and
`--price`; an absent booked debit requires explicit `--total`. Missing fees/taxes
may be supplied, otherwise default to zero. A missing date needs `--date`; source
value date is used when trade date is absent, following upstream extraction.
Missing time defaults to 11:00:00, while explicit midnight stays midnight; an
absent time can be supplied at whole-second precision. Supplements are inserted
only into temporary extraction text and upstream extraction is rerun. Missing
identity, malformed partial sections and unsupported layouts conservatively reject.
Financial supplements for multiple members reject because no member selector has
been defined. Origins distinguish `PDF`, `SUPPLEMENT`, derivation, default zero and
fallback time. Statement values supplied identically through CLI retain `PDF` origin.

`PdfPurchase.plan(Client, Portfolio, Account, CommandLine)` returns a detached
`Document` with ordered `Member` plans, origins, broker references and an immutable
byte snapshot/hash. All members must validate before output. The upstream entry
retains legitimate bank/reference notes and source filename. Snapshot, hash,
coverage and origin bookkeeping stay in wrapper records, never financial notes or
XML. The plan does not insert records or save the portfolio. Later application
must use its own request snapshot and consume the complete batch once.

`tests/pdf-purchase.sh` is the first trusted candidate gate and uses the real
qualified release launcher, actual PDF conversion and independent literal values.
It also checks read-only PDF binds, host UID/GID, input/metadata immutability,
ambiguous identities and malformed later members. `PdfTextOracle.java` compares
all committed paired text to real upstream conversion. `tests/updater.sh IMAGE
--pdf-slice` runs every registered candidate gate, selected-copy validation,
activation and mandatory fixed-image owner PDF preview, then stops before unrelated
already-tested updater adversity cases. The complete registry remains intact.

## Linux launcher and maintenance seams

The host dispatcher requires Python 3.10+, Docker and standard Linux core utilities. It resolves
the selected **local** image once to an immutable identity before either container
runs. It does not pull a missing wrapper image. Without an active release pair,
`PP_PAL_IMAGE` selects a local tag or image identity for development and compatibility
gates; the default is `pp-pal:0.88.0`. Once activated, the integrity-checked manifest
selects the released launcher and immutable image together, taking precedence over
that development variable.

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
real core/PDF runtime tests and the owner-facing holdings/configuration/history/backup
and released-pair updater tests, including trusted purchase compatibility checks.
`tests/purchase.sh` runs within the real updater's candidate qualification so it
exercises mandatory preparation without a developer/owner bypass. Its expected
financial values and `PurchaseOracle.java` come from the trusted bundle, while
released candidate archives contain no fixtures.
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
candidate. The updater calls it only after detecting a changed
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

The stable host dispatcher now requires Python 3.10+ for commands and updates;
`--help` remains Bash-only. `--update [--file PATH] [--config PATH]` follows the
same separate-value, repeated-option and path conventions. The active manifest
selects a released `tools/run-container` implementation and immutable image together.
The bootstrap validator stays fixed and rejects changed candidate bootstrap files.
Its trusted candidate gates are registered in `runtime/candidate-gates.json`; add
each implemented command's fixed independent checks there as well as `tests/all.sh`.
`tools/update.py:prepare_processing` is the shared automatic preparation boundary
for purchase, sale, dividend and import dispatch. It runs before planning/processing
and has no preview/import bypass option. [Local updater](local-updater.md) describes
release/bootstrap prerequisites, cache/report integrity and retained recovery pairs.

On 2026-10-07, the full cumulative suite passed with image
`sha256:030e992423b2736c23baa50e07799ac35cb5e32c4787fb673f459a69c9c44736`,
retained as `pp-pal:validated-issue22-030e992423b2`. It includes actual released
snapshot builds, independent candidate gates, selected-copy isolation, unchanged
cache reuse, discovery and integrity failures, stale input, safe retry and
interrupted activation. The separate official GitHub prerequisite check confirmed
that absence of a stable wrapper release stops without activating or changing the
synthetic selected portfolio.

The purchase preview boundary passed on 2026-10-07 with retained local image
`sha256:1c10ae5ae30305afe1cfc441879bc0415a6cea036da1d0c2d9ab29618b383ffa`
(`pp-pal:validated-issue23-1c10ae5ae303`). Composite verification consists of the
runtime, holdings/configuration, history and backup suites passing in `tests/all.sh`,
then the complete `tests/updater.sh` exiting 0 on the same image and host code.
The original cumulative run stopped at a midnight assertion's regular-expression
typo; only that assertion was corrected before the scoped updater rerun. Its
trusted candidate gates repeat every feature suite, including exact purchase
amount/range/contradiction cases, required accounts and identifiers, configured
paths/overrides and installed symlinks, timestamps, preserved financial/metadata/
configuration and old daily/migration backups, and the public upstream model oracle.

The actual tagged released candidate
`sha256:6b2b9d014316c4af0e89fbd2c8d6e826c0cd44f52a0ca1de043cbbc3a4d97863`
passed activation and mandatory owner preview. Live fixed-check qualification
rejected changed images, candidate trees, trusted bundles, wrapper/upstream
revisions and dependency digests. Owner-forged/expired qualification, stale selected
input and failed release discovery stopped before preview. Full updater isolation,
retry and interruption checks also passed. The support matrix records these exact
commands and distinguishes their composite result from an `all.sh` exit-0 result.
Financial application and successful-write retention remain #24 work.

## Purchase application and recovery

`purchase --apply --desktop-closed` computes a fresh plan from this invocation's
exact input snapshot after mandatory released-pair validation. Close Portfolio
Performance before invoking it. `--desktop-closed` is your explicit declaration;
the GUI does not participate in the persistent per-file `.pp-pal-write.lock`.
Other wrapper writers fail rather than waiting or guessing. Preview is still the
default, and no persisted preview approval is required.

The shared Java `AtomicWriter` accepts a planner over an isolated pinned client
and explicit appended transaction IDs/owners and optional new security IDs.
It serializes in a private adjacent directory, reloads with the same pinned core,
compares the complete planned state and verifies the complete original state after
stripping exactly the declared additions. An undeclared reference to a new
security prevents its removal from this validation projection. It preserves host
ownership and permissions, forces staged bytes, checks exact input bytes, inode,
parent identity, timestamps and permissions immediately before replacement, and
uses [`SecureDirectoryStream.move`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/SecureDirectoryStream.html#move(T,java.nio.file.SecureDirectoryStream,T))
within one filesystem; the Java API specifies an atomic move, with existing-target
replacement determined by the provider. Linux acceptance tests
exercise real replacement and refusal; there is no copy fallback. Filesystem
permissions and explicit desktop closure remain prerequisites; an uncooperative
external writer cannot be coordinated through a wrapper-only lock.

Before replacement, `<filename>.YYYY-MM-DD.bak` protects the exact original input
for the Berlin day. A pre-existing readable regular file satisfies this existence
requirement and is never overwritten or adopted, even if its contents differ.
A symlink, special file, unreadable file or required backup failure stops the
write. Owned backups and receipts use mode 0600; the financial output retains the
input's restrictive permissions. Import metadata and configuration are unchanged.

Only a committed financial write prunes owned daily backups: the three most
recent dates per month in the current and preceding 23 Berlin calendar months,
or every date if fewer than three. Future dates and foreign lookalikes remain.
Monthly/update backups use their separate strict two-year creation-time cutoff.
The `pp-pal-backups-v1` ownership index records each basename's `kind`, `created`,
`sha256`, and `date` (daily) or `month` (monthly). Daily names must agree exactly
with their date. Cleanup validates candidate hashes before deleting and persists
`<basename>.prune=pending` first, so partial unlink/index failures can resume.
Launches, previews and failed plans never prune. Malformed/tampered ownership
refuses deletion and reports recovery after a committed write.

A private `.pp-pal-write.properties` receipt records exact input/output hashes,
new IDs, daily protection and `prepared`, `committed` or `complete` state. The
receipt is durable before rename. Once rename returns, every later failure,
including receipt, observer, retention or resource-close errors, reports the
financial change as committed with exit 4 and recovery information. **Do not
repeat that application.** Ordinary apply seeing a pending committed receipt
also returns 4 without inserting records.

`purchase --recover-write --desktop-closed` accepts only shared path/config
options and no financial inputs. Under the same lock, exact output hash and
present-once transaction identities permit cleanup only, with no replanning or
insertion. Exact unchanged input with a prepared intent permits aborting its
verified private staging, reporting `NO FINANCIAL CHANGE` without pruning.
Any other current state refuses automatic recovery. A complete receipt does not
block a later explicitly requested fresh manual purchase; it is not duplicate
import metadata.

`PP_PAL_WRITE_OBSERVER` is a test-only Unix socket inside an explicitly marked
disposable fixture directory (`.pp-pal-disposable-fixture` contains
`pp-pal-write-observer-v1` and a newline). The peer must share the container/host
UID. It acknowledges `before-final-check` and `committed-before-follow-up` within
ten seconds, with no operation bypass or executable hook. Wrong peers, replies
and timeouts fail closed; a failure after rename remains committed/exit 4.
`tests/purchase-apply.sh` exercises the real owner and disposable container
filesystem, including deterministic interruption, stale bytes/inodes, unsafe
staging, Linux permissions, calendar cutoffs and independent pinned model checks.

## Explicit security creation with a purchase

Use `purchase --create-security --name 'Instrument name' --currency EUR` with the
usual selected account pair, date and decimal amounts. External `--isin`, `--wkn`
and `--ticker` identifiers are optional. Creation is detached during preview;
`SECURITY ... (create)` shows the proposed new definition and `... (existing)`
shows reuse. `--apply --desktop-closed` adds the definition and both linked
purchase records together through the shared validated atomic writer. The normal
mandatory release check, fresh input snapshot, exact daily backup and verified
write recovery also apply. No additional migration is supported.

Every supplied identifier participates in selection. A uniquely identified
existing definition is reused without changing its name, currency or identifiers,
including when the requested name uses a different broker spelling. Conflicting,
unknown extra identifiers alongside a matched identifier, ambiguous matches,
missing names/currencies and unsupported currencies stop the command before
financial changes. An exact `--security-id` can disambiguate existing matches;
unknown stable IDs never request creation. Existing security/cash currencies
must agree with the requested currency; foreign-currency purchases still require
the separately validated FX path. `--name` and `--currency` require the explicit
creation flag. Names alone do not prove identity: creation without identifiers
intentionally creates a new definition, even if a name resembles an existing one.

`tests/security-create.sh` is a trusted candidate gate. It exercises identifier-free
creation, optional identifiers, exact existing reuse, ambiguous/conflicting and
invalid definitions, immutable previews, decimal failure and explicit midnight
through the real released launcher. Pinned-core oracles load the written portfolio,
check literal new-definition and linked purchase facts and compare the complete
unrelated model after removing only the proposed additions. `tests/updater.sh
IMAGE --security-slice` also verifies mandatory release discovery, fixed-image
owner preview/application and stale/release failure refusal.

## Manual sales

`sale` accepts the purchase command's existing-security selectors, account pair,
quantity, price/gross/total, fees/taxes and date/time options. It supports tested
same-currency entries only. `--total` is net cash credit (`gross - fees - taxes`);
shared exact decimal precision and rounding rules apply. A gross amount below
fees and taxes rejects. For example:

```sh
./pp-pal sale --security DE0000000001 --securities-account 'Synthetic Securities A' \
  --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 2.5 \
  --price 10 --fees 1 --taxes .5
```

Preview discloses the missing-time fallback `11:00:01`. Explicit times, including
midnight and fractional seconds, are preserved without adjustment. Before preview
or apply, validate the complete selected security/securities-account quantity
history, including all dates after a backdated sale. At identical timestamps,
quantity increases (purchases, inbound deliveries/transfers) precede decreases;
within each group preserve the persisted account transaction-list order, and place
the proposed sale after existing decreases. Any negative balance rejects with its
date and account/security IDs. Other accounts' holdings cannot fund a sale.

Use `--apply --desktop-closed` to record the linked securities sale and cash
credit with the shared atomic writer, backups and recovery. Security definitions,
past purchases/sales and unrelated settings remain after full disposal. This
command neither calculates nor persists FIFO or profit. Mandatory released-pair
qualification includes `tests/sale.sh`; run it directly against a local development
image with `bash tests/sale.sh pp-pal:issue26`.

## Manual dividends

`dividend` credits a selected cash account for an existing security. Select both
unambiguously by stable ID, exact unique name or security identifiers. A configured
cash-account ID can supply the default; a securities account is not required.

```sh
./pp-pal dividend --security DE0000000001 --cash-account 'Synthetic Cash A' \
  --date 2025-03-01 --gross 25 --fees 1 --taxes .5
```

The preview shows EUR 25.00 gross, EUR 1.00 fees, EUR 0.50 taxes and EUR 23.50
booked cash credit using upstream monetary units. `--total 23.50` can replace
`--gross 25`; gross is then derived from booked credit plus fees/taxes. Supplying
both requires exact agreement. All monetary inputs must be nonnegative exact
cents in the cash/security currency; negative credits, overflow and excess
precision reject. Omitted fees/taxes default to zero. No quantity or price input
is accepted. Foreign-currency dividends await the separately validated FX path.

`--date` is required. Missing time uses the disclosed `11:00:00` fallback; explicit
midnight and fractional seconds are preserved. Backdated dividends and dividends
after full disposal retain the security reference and leave securities history,
held quantity and holding last updated unchanged. No securities transaction or
securities-account ownership link is created. History uses the documented upstream
reference-cash-account/security relevance over the complete history: a closed
holding remains relevant, shared cash/security history can make attribution
ambiguous, and an unrelated cash dividend is excluded from securities history.

Use `--apply --desktop-closed` for a fresh validated write after mandatory release
checks. The same writer lock, stale-input checks, roundtrip/unrelated-state
validation, atomic replacement, exact daily backup, retention and verified
`--recover-write --desktop-closed` rules apply as for purchases. Preview does not
create daily/migration backups or alter portfolio/import metadata.

`tests/dividend.sh` is a mandatory candidate gate. It exercises owner preview/apply,
container financial cases and an independent pinned-core saved-model oracle for
fees/taxes, derivations, references and relevance, closed/shared holdings, unchanged
quantity/timestamps, exact times, ambiguous/missing selectors, configuration,
invalid decimals, backup failure and stale input. Run a local development image
with `PP_PAL_LAUNCHER="$PWD/tools/run-container" bash tests/dividend.sh pp-pal:issue27`;
`bash tests/all.sh IMAGE` qualifies the released owner launcher as well.

The final cumulative run on 2026-10-08 passed in 1023 seconds (17 minutes 3
seconds), qualifying all ten candidate gates once and the complete historical
updater adversarial suite. The validated dividend-capable released image is
`sha256:7ac4e8891b41d613759bc024481c5c29ce5ab3e7fcbec4551080604197b6816f`,
retained locally under `pp-pal:retained-7ac4e8891b41d613759bc024481c5c29ce5ab3e7fcbec4551080604197b6816f`.
The support matrix records the exact pair and tested scope. Production use still
requires the initial official stable wrapper bootstrap release.
