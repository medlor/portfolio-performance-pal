# Local released-pair updates

`pp-pal --update [--file PATH] [--config PATH]` checks the latest stable official
Portfolio Performance and wrapper releases, builds changed pairs locally, validates
them, and activates a passing pair automatically. It does not require `--apply`.
Queries use the activated released `tools/run-engine` implementation and runtime.
The installed bootstrap dispatcher and independent validator remain the trusted
distribution code; they do not replace themselves from a candidate. Protocol 2
requires candidate `pp-pal`, `tools/update.py` and `tools/test_jobs.py` to match that bootstrap exactly.
Changed host orchestration stops with a manual trusted-bootstrap maintenance error;
released Java code, `tools/run-engine`, `tools/engine` and `tools/build-runtime` can update automatically. Purchase, sale, dividend and import dispatch run
this same preparation before any preview or write. Manual purchases,
sales and dividends, including explicit foreign-currency transactions, support
preview/application; PDF import supports the validated
purchase profiles with atomic application and duplicate-safe bookkeeping recovery. Other statement layouts remain pending.

Linux amd64, Bash, Python 3.10 or newer, standard Linux core utilities, `tar` and
`git` (local upgrades) are required; Docker and a host Java are not. Everything runs
rootless as the invoking user. Python handles HTTP, JSON, locks, cache directories
and opaque file snapshots. Configuration, backup contents, XML models and financial
comparisons use the source-built Java core on the runtime's pinned Temurin JDK
([ADR 0005](adr/0005-native-runtime.md)). Nothing is published. The README's
"Network access" section lists every URL the build and updater fetch.

## First release and installation

There is currently no usable released wrapper package on GitHub. Production
`--update` fails with the release prerequisite until a maintainer publishes a
stable release containing this implementation. An unreleased worktree is not a
production release. No tag or release publication is performed by these tools.

The bootstrap distribution is the source archive of an explicit stable wrapper
release tag resolved to its immutable commit. It must include `pp-pal`, `tools/`,
`runtime/`, `tests/`, reviewed `fixtures/` and `UPGRADE.md`. Install this package in
a separate directory, build its pinned initial runtime with
`tools/build-runtime --output ~/.local/state/pp-pal/runtimes/dev`, then use its
`pp-pal` entry point (or a symlink to that file). Record the wrapper commit with the
package; its immutable checkout and reviewed validation files are the initial trust root.
The initial recipe pins upstream 0.88.0, Java 21.0.11+10 and their digests.

### Release tags

A wrapper release is tagged **exactly like the Portfolio Performance release it
supports**: the tag equals `upstream.tag` in that release's
`runtime/release-package.json`, with no `v` prefix (PP tags its releases `0.88.0`).
A later wrapper fix for the same PP release appends `-2`, `-3`, and so on (for
example `0.88.0-2`). The updater refuses any release whose tag breaks this rule,
before backup or build. Tags are annotated and point at a commit on `main`.

Publish the release for the current pin after the change is merged:

```sh
git switch main && git pull --ff-only
python3 -c 'import json; print(json.load(open("runtime/release-package.json"))["upstream"]["tag"])'   # must print 0.88.0
git tag -a 0.88.0 -m "pp-pal for Portfolio Performance 0.88.0"
git push origin 0.88.0
gh release create 0.88.0 --verify-tag --title "pp-pal for Portfolio Performance 0.88.0" \
  --notes "Native runtime (ADR 0005); agent local upgrades (ADR 0006)."
```

The first `pp-pal --update --file …` afterwards builds and qualifies that release
(all gates; roughly 20–40 minutes), then activates it.

The updater copies the reviewed bootstrap checks and fixture expectations into a
separate local validation bundle on first use. Candidate releases cannot replace
this bundle. Each subsequent feature must extend `runtime/candidate-gates.json`
and independent fixtures before the bootstrap package claims that feature. A new
support or migration policy requires an explicitly reviewed bootstrap validation
package; the updater does not invent one. The one exception is an agent local
upgrade (below, and [ADR 0006](adr/0006-agent-local-upgrades.md)).

## Unsupported releases and agent local upgrades

The released wrapper's `runtime/release-package.json` names the exact upstream
release it is qualified for (`upstream.tag` and `upstream.revision`). After
discovery, the updater unpacks the latest wrapper release and compares. If neither
that wrapper nor an active local upgrade covers the latest stable upstream release,
the operation stops with exit 3. This happens before any update backup, upstream
download or build. The updater prints:

```
UPGRADE REQUIRED: Portfolio Performance <tag> (<revision>) has no tested pp-pal support.
Agent prompt:
  Read <package>/UPGRADE.md and perform the pp-pal upgrade to …
  When finished, resume this operation exactly, in directory <cwd>:
  <shell-quoted original command>
```

The same prompt, the original argv and the working directory are saved atomically
in `$STATE/upgrades/<tag>/request.json`.

`pp-pal --update --local-upgrade CHECKOUT [--file PATH] [--config PATH]` qualifies
an agent's committed, clean git checkout. Its pinned upstream must equal the latest
stable release, and none of the current trusted gates may be removed. The checkout's
gates, fixtures and oracles become a candidate trusted bundle; adapting existing
ones is allowed in unattended mode. The candidate runtime is built from the
checkout. The selected portfolio is validated by the *previous* trusted oracle on
the *previous* runtime, then every gate of the new bundle runs once. On success:
- the previous bundle is kept as `trusted-validation.previous-<commit>`;
- the new bundle is installed;
- `active.json` records `source: local-upgrade`, the stable wrapper release seen at
  that time (`releasedWrapper`) and `trustedChanges`;
- the output lists the `TRUSTED CHANGES`, then `ACTIVATED local-upgrade <identity>`.

Afterwards, later operations reuse the local pair while the stable wrapper still
lacks support (`UNCHANGED validated local-upgrade pair`). A stable wrapper release
that declares the upstream and qualifies replaces the local pair, which then
becomes `previous`.

## Selection, protection and failures

Path/configuration precedence matches queries. Java resolves configuration before
updating; `--file` selects the actual portfolio validated and backed up. At launch,
a due Europe/Berlin monthly snapshot protects the selected file. A changed pair
also gets a distinct exact pre-attempt snapshot. An unchanged validated release
pair reuses its runtime and creates no extra update snapshot. Failed discovery,
build, fixed checks, selected-copy checks or stale input stops the operation and
retains the previous active pair. Financial XML and import metadata are never
replaced during updates.

State defaults to `${XDG_STATE_HOME:-~/.local/state}/pp-pal`; `PP_PAL_STATE_DIR`
selects another local state directory. Keep it private and backed up. It holds the
update lock, immutable wrapper snapshots, the trusted validation bundle, the
content-addressed archive cache `downloads/` (override with `PP_PAL_ARCHIVE_CACHE`),
read-only runtime directories `runtimes/<identity>/`, upgrade requests, reports and
`active.json`. An atomic manifest selects the released `tools/run-engine` launcher
and its runtime directory as one pair. Each processing invocation retains its
selected pair even if another update later activates a different pair. Old wrapper
snapshots and runtime directories are retained, including baseline and in-flight
engines; no automatic garbage collection is implemented. A runtime is reused only
if its recorded input digests match. To delete a runtime by hand, restore write
permission first (`chmod -R u+w`).

Release tags are peeled through annotated tag objects to exact commits, rather
than using a release's moving `target_commitish`. Linux dependency archives must
have a unique official SHA-256 digest. The released wrapper's own `tools/build-runtime`
runs with `--inputs`: it verifies source, dependency and JDK bytes against the
pinned digests and uses the unchanged reviewed, source-hash-guarded headless patch. An unfamiliar patch target requires adapter maintenance.

Only public upstream archives enter the build inputs. The original portfolio,
PDFs, adjacent metadata and backups are excluded. First, the candidate runtime
round-trips a disposable selected-file copy. The copy is mode 0400 inside a 0500
directory, and the candidate writes only to a fresh output directory. Afterwards
that directory must still hold only the byte-identical copy. The output is
inspected and serialized again by the previous trusted runtime, in one JVM running
the trusted `SelectedPortfolioOracle.java`. Both original and output canonical
roundtrips and the financial relationships must agree. Candidate code is not
sandboxed: it has network access and can read the user's files
([ADR 0005](adr/0005-native-runtime.md)). Integrity rests on these copies, byte
comparisons and the independent trusted gates. After this check and an
original-input identity check pass, all fixed fixture gates remain mandatory.
Holdings runs first, then the other gates run in a bounded rolling queue with
private state, qualification sockets and identical release copies. Trusted test
oracles compile once per qualification. Gate output streams with elapsed times.
Private inspection output is captured, compared and discarded without reporting
its contents. The current policy permits only model 70 ID-reference XML to model
70, with no migrations, and the previously proved synthetic Baader purchase layout.

Reports record immutable wrapper/upstream revisions, archive digests (including the
JDK), runtime directory and identity, platform/toolchain/recipe, trusted bundle integrity, support/layout
policy and executed gates. Successful compilation or candidate-only reloading is
never sufficient. The report is installed before a file-and-directory-fsynced
atomic active-manifest replacement; the previous pair remains available after a
pre-activation failure or interruption.

`PP_PAL_RELEASE_API` is a test transport boundary restricted to the official GitHub
API or a loopback HTTP fixture server. The integration gate serves actual tagged
Git snapshots and pinned public upstream archives through this boundary; it does
not bypass building, independent gates, selected-copy validation or activation.
Qualification gates never use the outer API. The updater sets their
`PP_PAL_RELEASE_API` to a loopback replay of the release metadata it resolved
(`release_view` in `tools/update.py`). Each gate's owner-launcher commands re-resolve
both releases, and against GitHub a qualification would exceed the unauthenticated
rate limit.

Purchase preview always calls `prepare_processing` before constructing its financial
plan. Compatibility checks need to exercise that same boundary on a candidate
which is still under validation. A private 0600 Unix socket owned by the live
trusted updater qualifies only its descendant check processes. The client verifies
the peer PID, ancestry and exact trusted bootstrap bytes; reuse additionally checks
the wrapper/upstream tags and revisions, runtime directory, trusted suite, candidate
tree, official dependency digest and the previously downloaded archives. Release
discovery, disposable selected-file validation and stale checks still run. The
qualification lasts only for fixed checks and cannot activate the pair. Generic
owner environment injection, expired/fake parents or any mismatch fail closed.

The reviewed release package now declares purchase preview in its feature policy,
and its trusted gate registry includes `tests/purchase.sh`. A wrapper changing the
feature policy or host validator still requires reviewed bootstrap maintenance.
Financial application and successful-write backup retention remain separate work.
