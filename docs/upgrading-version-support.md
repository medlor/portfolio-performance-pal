# Upgrading Portfolio Performance support

The wrapper reuses upstream Java persistence and models. This does not establish rewrite safety or promise stable exported APIs. Exact validated images and financial capabilities are recorded in `runtime/support-matrix.json`; retain the following evidence requirements when extending support.

Normal commands use a pinned Portfolio Performance version and locally built image. Provide a documented upgrade script through the shell launcher's `--update` option, targeting the latest stable upstream release. Build the candidate locally and activate it only after compatibility checks pass. If building or validation fails, keep the previous working image active and leave the portfolio untouched; report adapter maintenance required when upstream changes prevent an upgrade. The command is implemented; [local updater and bootstrap packaging](local-updater.md) documents its release prerequisite and gates. The workflow must not publish the wrapper image to an external registry.

Also fetch released wrapper updates through explicit tags resolved to immutable revisions. Build in isolated checkouts without altering the owner's repository or generating code/test fixes. Validate and activate the wrapper/upstream pair together, recording both revisions and retaining the previous pair on failure.

The launcher automatically checks for updates before every preview/import and rebuilds when an upstream or wrapper release changes; `--update` also invokes this workflow explicitly. Failed release checks, builds, or validation stop the requested command while preserving the previous validated image. Updating the wrapper leaves the selected portfolio unchanged. Financial commands support the tested ID-reference XML model 69 -> 70 migration, explicitly shown in preview and covered by a separate backup of the exact operation input, following [ADR 0003](adr/0003-allow-tested-migrations-with-transactions.md). No earlier migration is declared. The owner updates the desktop application independently; the wrapper does not gate writes against a configured desktop release.

Create a portfolio backup before each actual upgrade attempt; unchanged-release checks do not create update backups. Check for a due monthly backup at launch, with no Linux timer. After inactivity, back up the current state on the next launch; do not fabricate missed monthly backups. Retain update, monthly, and pre-migration backups for two years, separately from the existing daily-backup policy. Choose distinct, collision-safe names and tool-ownership markers during implementation.

1. Declare application release, exact upstream revision, XML model version/representation, container image identity, and reproducible build/JDK dependencies separately. Pin the target revision/image and verify that core persistence, models, and PDF extraction build and run headlessly.
2. Review persistence/model changes, including `ClientFactory`, `Client`, `Transaction`, `AccountTransaction`, `PortfolioTransaction`, `BuySellEntry`, and reference relationships. Inspect scaled numbers, currencies, fees, taxes, new fields, and loader/save migrations. Adapt the Java integration; do not add a separate Python serializer.
3. Create anonymized ID-reference XML fixtures for proposed supported combinations. Include multiple account pairs, purchases, partial/full sales, dividends, FX, and unrelated settings/classifications. Keep previously supported fixtures. Do not read or commit the real portfolio to establish fixtures.
4. Document accepted input versions and whether loading/saving migrates them. Define allowed resulting versions and semantics per support-matrix entry. Reject untested representations/versions or unvalidated migrations before modifying the original.
5. Test no-op load/save round trips and intended changes with the pinned core. Save to temporary files and compare financial semantics and unrelated data before atomic replacement. Inspect quantities, dates/times, amounts, fees, taxes, currencies/rates, references, and linked accounts; loading alone is insufficient. Verify older still-supported combinations remain correct.
6. Run command/container checks for preview/apply, duplicates and metadata recovery, quantity history, atomic/stale writes, failure preservation, backups/retention, and host mounts/permissions. Record actual commands and results when they exist.
7. Add only passing combinations to the explicit support matrix, with revision/image identities, migration behavior, limitations, and whether earlier entries remain supported. The updater may generate entries from passing checks under established validation rules; release discovery alone never establishes support. New fixture expectations, adapter fixes, and migration rules require maintenance rather than automatic rewriting.
8. Review extraction separately using [the broker-extractor guide](updating-broker-extractor.md). File compatibility and statement recognition are separate contracts.

## Automated update design

Automate the mechanical procedure while keeping validation rules and expected fixture results independent of the candidate release. The implemented updater follows this procedure; [ADR 0004](adr/0004-validate-local-updates-before-processing.md) records its trade-off.

[Upstream research notes](update-automation-research.md) document release discovery, immutable source resolution, build dependencies, and the headless packaging questions still to prove.

1. Acquire an update lock, perform any due monthly backup, and discover the latest stable official Portfolio Performance and wrapper releases. Resolve their tags to exact source revisions. A release-discovery failure stops processing; an unchanged, already validated pair reuses the current image.
2. Before an actual upgrade attempt, create the required update backup. Fetch the pinned candidate into a separate build area and reuse dependency/build caches. Build the upstream core and adapter with the documented toolchain in Docker, without mounting the owner's financial files into the build environment.
3. Test the candidate headlessly against fixed anonymized fixtures and independently specified expected records. Cover PDF conversion/import, manual entries, migrations, unrelated data preservation, and the command/filesystem contracts. Compiling or reloading with the candidate's own reader alone is insufficient.
   After these checks pass, validate a disposable local copy of the selected portfolio, including an XML supplied through `--file`. Compare financial semantics and unrelated data under the allowed migration policy. Keep private input out of build contexts and network uploads; never replace the original or change its metadata during update validation.
4. Produce a validation report and candidate manifest recording the release, source revision, image identity, supported models/migrations/layouts, and checks run. Unknown migrations, required adapter changes, or failed checks stop the upgrade; automation must not silently change tests or financial expectations to obtain a passing result.
5. Activate only a passing candidate through an atomic change to the active manifest selecting the released wrapper implementation and image together, retaining the previous validated pair. Interruptions before activation leave the previous manifest usable. Updating does not modify the portfolio or import bookkeeping.
6. Start the requested preview/import with the selected image fixed for the invocation. Report update failures and stop the requested operation rather than silently continuing with the older image.

`--update` activates a passing candidate automatically; it does not require `--apply`. Reserve `--apply` for recording financial changes. Update-only invocations leave the original portfolio unchanged. The source-built core recipe is proved for upstream 0.88.0; candidates use its reviewed archive-input variant with immutable revisions and verified digests. Unknown adapter changes stop the update.

Use the selected upstream checkout's contributor instructions and persistence/model tests as starting points. Concrete upgrade records must link to pinned revisions and include reproducible validation commands.

Sale PDF qualification uses the same pinned Portfolio Performance 0.88.0 source
revision `8de1fe41d71a2c1b74a6bfbe913f885899aaf123`, Temurin 21.0.11+10 and
Linux amd64 runtime. It retains ID-reference model70 roundtrips and protected
transaction-only model69-to-70 migration. `tests/pdf-sale.sh` is the fourteenth
mandatory candidate gate; all thirteen previous gates and independent expectations
remain. The complete final-policy run, `bash tests/all.sh pp-pal:issue32-final`,
passed in 1667 seconds. Exact development/released image identities and reproduction
commands are recorded in [the support matrix](../runtime/support-matrix.json).
Qualified sale grammar is fixture-scoped; the retained two-page current-layout
sample remains unverified, so release qualification does not authorize that layout
or additional migrations.


Dividend PDF qualification retains the same upstream revision/JDK/platform and
model70 plus protected model69-to-70 combinations. `tests/pdf-dividend.sh` is the
fifteenth mandatory candidate gate; all fourteen previous gates and assertions
remain. `bash tests/all.sh pp-pal:issue33-final` passed in 1764 seconds (29 minutes
24 seconds), including independent baseline checks, all fifteen gates exactly
once on successful released qualification, selected-copy validation and retained
updater failure/attack checks. Runtime, help, release policy, gates and executable
test inputs stayed frozen; only documentation and support evidence changed after
success. Exact development/released pair identities are in the
[support matrix](../runtime/support-matrix.json). The qualified dividend profile
is synthetic German EUR Baader page-1-of-1; the supplied local CHF reference has
unproved page coverage and no readable broker reference and remains unsupported.
