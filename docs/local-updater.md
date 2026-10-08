# Local released-pair updates

`pp-pal --update [--file PATH] [--config PATH]` checks the latest stable official
Portfolio Performance and wrapper releases, builds changed pairs locally, validates
them, and activates a passing pair automatically. It does not require `--apply`.
Queries use the activated released `tools/run-container` implementation and image.
The installed bootstrap dispatcher and independent validator remain the trusted
distribution code; they do not replace themselves from a candidate. Protocol 1
requires candidate `pp-pal` and `tools/update.py` to match that bootstrap exactly.
Changed host orchestration stops with a manual trusted-bootstrap maintenance error;
released Java code and `tools/run-container` can update automatically. Purchase, sale, dividend and import dispatch run
this same preparation before any preview or write. Same-currency manual purchases,
sales and dividends support preview/application; PDF import supports the validated
purchase preview profiles. Other statement layouts and PDF application remain pending.

Linux amd64, Bash, Python 3.10 or newer, Docker and standard Linux core utilities
are required. Python handles HTTP, JSON, locks, cache directories and opaque file
snapshots. Configuration, backup contents, XML models and financial comparisons
use the source-built Java core in Docker. Nothing is published to a registry.

## First release and installation

There is currently no usable released wrapper package on GitHub. Production
`--update` fails with the release prerequisite until a maintainer publishes a
stable release containing this implementation. An unreleased worktree is not a
production release. No tag, release or image publication is performed by these
tools.

The bootstrap distribution is the source archive of an explicit stable wrapper
release tag resolved to its immutable commit. It must include `pp-pal`, `tools/`,
`Dockerfile`, `runtime/`, `tests/` and reviewed `fixtures/`. Install this package in
a separate directory, build its pinned initial image with
`docker build -t pp-pal:0.88.0 .`, then use its `pp-pal` entry point (or a symlink to
that file). Record the wrapper commit and initial image identity with the package;
its immutable checkout and reviewed validation files are the initial trust root.
The initial recipe pins upstream 0.88.0, Java 21.0.11+10 and their digests.

The updater copies the reviewed bootstrap checks and fixture expectations into a
separate local validation bundle on first use. Candidate releases cannot replace
this bundle. Each subsequent feature must extend `runtime/candidate-gates.json`
and independent fixtures before the bootstrap package claims that feature. A new
support or migration policy requires an explicitly reviewed bootstrap validation
package; the updater does not invent one.

## Selection, protection and failures

Path/configuration precedence matches queries. Java resolves configuration before
updating; `--file` selects the actual portfolio validated and backed up. At launch,
a due Europe/Berlin monthly snapshot protects the selected file. A changed pair
also gets a distinct exact pre-attempt snapshot. An unchanged validated release
pair reuses its image and creates no extra update snapshot. Failed discovery,
build, fixed checks, selected-copy checks or stale input stops the operation and
retains the previous active pair. Financial XML and import metadata are never
replaced during updates.

State defaults to `${XDG_STATE_HOME:-~/.local/state}/pp-pal`; `PP_PAL_STATE_DIR`
selects another local state directory. Keep it private and backed up. It holds the
update lock, immutable wrapper snapshots, trusted validation bundle, public source
archives, candidate contexts, retained images' identities, reports and `active.json`.
An atomic manifest selects the released container launcher implementation and immutable Docker image
as one pair. Each processing invocation retains its selected pair even if another
update later activates a different pair. Old wrapper snapshots and permanent image-ID-derived Docker tags
are retained, including baseline and in-flight engines before any rebuild; no automatic garbage collection is implemented.

Release tags are peeled through annotated tag objects to exact commits, rather
than using a release's moving `target_commitish`. Linux dependency archives must
have a unique official SHA-256 digest. The archive-input Docker recipe verifies
source and dependency bytes and uses the unchanged reviewed, source-hash-guarded
headless patch. An unfamiliar patch target requires adapter maintenance.

Only released runtime code, the fixed build recipe and public upstream archives
enter the Docker build context. The original portfolio, PDFs, adjacent metadata
and backups are excluded. First a disposable selected-file copy is mounted
read-only for the candidate, which can write only a separate disposable output
directory. The trusted canonical comparison artifacts are never mounted in
the candidate container. Its output is inspected/serialized again by the previous
trusted engine in one JVM running the trusted `SelectedPortfolioOracle.java`
entry point. Both original/output canonical roundtrips and financial relationships
must agree. Candidate generation and trusted evaluation use two isolated
containers. After this check and an original-input identity check pass, all fixed
fixture gates remain mandatory. Holdings runs first, then gates run in batches
of two with private state, qualification sockets, and identical release copies.
Trusted test oracles compile once per qualification; candidate containers mount
those test classes readonly. Gate output streams with elapsed times. All selected-file containers have no network;
private inspection output is captured, compared and discarded without reporting
its contents. The current policy permits only model 70 ID-reference XML to model
70, with no migrations, and the previously proved synthetic Baader purchase layout.

Reports record immutable wrapper/upstream revisions, archive digests, image ID,
retention tag, platform/toolchain/recipe, trusted bundle integrity, support/layout
policy and executed gates. Successful compilation or candidate-only reloading is
never sufficient. The report is installed before a file-and-directory-fsynced
atomic active-manifest replacement; the previous pair remains available after a
pre-activation failure or interruption.

`PP_PAL_RELEASE_API` is a test transport boundary restricted to the official GitHub
API or a loopback HTTP fixture server. The integration gate serves actual tagged
Git snapshots and pinned public upstream archives through this boundary; it does
not bypass building, independent gates, selected-copy validation or activation.

Purchase preview always calls `prepare_processing` before constructing its financial
plan. Compatibility checks need to exercise that same boundary on a candidate
which is still under validation. A private 0600 Unix socket owned by the live
trusted updater qualifies only its descendant check processes. The client verifies
the peer PID, ancestry and exact trusted bootstrap bytes; reuse additionally checks
the wrapper/upstream tags and revisions, image identity, trusted suite, candidate
tree, official dependency digest and the previously downloaded archives. Release
discovery, disposable selected-file validation and stale checks still run. The
qualification lasts only for fixed checks and cannot activate the pair. Generic
owner environment injection, expired/fake parents or any mismatch fail closed.

The reviewed release package now declares purchase preview in its feature policy,
and its trusted gate registry includes `tests/purchase.sh`. A wrapper changing the
feature policy or host validator still requires reviewed bootstrap maintenance.
Financial application and successful-write backup retention remain separate work.
