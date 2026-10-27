# Docker-free runtime and agent-driven upgrades

Date: 2026-10-27. Branch: `feature/native-runtime`.

## Intent

The owner wants to run every pp-pal operation without Docker. Docker is
removed entirely rather than kept as an option, because a second execution
path would double the launch, updater and test code for no owner benefit.

Separately, an update check that discovers a Portfolio Performance (PP)
release without tested support must stop the operation, and hand off to an
agent. The agent follows `UPGRADE.md` and does the following:
- adds verified support,
- activates the locally tested pair,
- opens a PR for the owner to approve,
- resumes the operation that failed.

## Goals

- All operations (queries, configure, backups, `--update`, manual
  purchase/sale/dividend preview and apply, PDF import) run as plain
  user processes with no Docker and no root.
- The JDK stays pinned exactly: Temurin 21.0.11+10, Linux x64, with its
  SHA-256 checked. The host's own `java` (here 25) is never used for the engine.
- A human-readable, annotated build script replaces the Dockerfile.
- The update check stops processing when the latest stable PP release isn't
  covered by tested support. It then prints an agent prompt.
- The agent performs `UPGRADE.md` unattended and activates the local pair
  once validated. It then re-runs the original command exactly, including
  `--apply --desktop-closed`.
- A PR with all verified changes is opened against `origin`. The owner
  approves and merges it.

## Non-goals

- Keeping Docker as an alternative engine.
- Sandboxing (no network, read-only binds, hidden trusted files). The owner
  accepted that candidate code runs unsandboxed. Integrity checks and
  trusted gates remain.
- Agent self-approval of the PR. GitHub forbids self-approval, and the
  owner approves the PR.
- New platforms other than Linux amd64.

## Anti-goals

- Weakening any existing financial assertion, oracle or gate to make the
  migration pass. Assertions that observed Docker-specific mechanics (bind
  modes, `docker` traces, image labels) are replaced by assertions on the
  native equivalent, never just deleted.
- Letting an agent change already-approved fixtures or expectations, and then
  activate the result unattended.
- Any operation that uses root, `sudo` or system package installation.

## Constraints

- **ADR 0002** says that Docker packages the Java reuse. This is superseded by
  **ADR 0005, native runtime**. The Java reuse decision itself stands.
- **ADR 0004** allows activation only from released wrapper tags, and requires
  a trusted validation bundle that candidates cannot replace. The local
  upgrade exception is recorded in **ADR 0006, agent local upgrades**.
- Bootstrap protocol: `extract_wrapper` refuses released wrappers whose
  `pp-pal` or `tools/update.py` differ from the bootstrap. This change
  rewrites both, so it is a new bootstrap (`bootstrapProtocol: 2`). No
  wrapper release exists yet, so no installed base needs migration.
- Network is needed for the first build, for these downloads:
  - Adoptium JDK,
  - the PP source tarball,
  - the PP release tarball.

  All downloads are cached in the state directory, and checked by SHA-256.

## Approaches considered

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | pp-pal downloads and pins its own Temurin JDK into the runtime directory. The engine identity is the runtime directory's tree digest. | One extra cached download (about 190 MB). It keeps one execution path and the exact JDK pin that the support matrix relies on. |
| B | Require a host Java 21 on PATH. | Least code. But the owner must install it, the exact pin is lost, and OS updates can change the engine. |
| C | A plus an optional rootless `bubblewrap` sandbox. | Recovers most of the isolation, but reintroduces two execution paths. It helps only where `bwrap` exists. |

The owner picked A.

## Design: Part 1, native runtime

### Runtime directory and identity

`tools/build-runtime` produces a self-contained runtime directory:

```
<runtime>/
  jdk/              Temurin 21.0.11+10 (unpacked, checksum-verified)
  classes/          compiled PP core + pdfbox3 adapter + pal wrapper, resources, help.txt
  lib/              upstream release plugin jars minus core/ui/pdfbox3 jars
  attribution/      upstream LICENSE, MANIFEST, source tarball, patches
  runtime.json      labels: wrapper revision, upstream revision/release, xml model,
                    jdk, platform (replaces Docker image labels)
```

- **Identity:** `sha256:<tree digest>`, computed over the whole directory with
  the existing `tree_digest` rules. This keeps the `IMAGE` regex and the
  manifest shapes.
- **Read-only:** after the build, the directory is made read-only
  (`chmod -R a-w`).
- **Verification:** the identity is recomputed whenever a runtime is selected.
  A mismatch refuses to run, which replaces `docker image inspect`.
- **Location:** runtimes live in `$STATE/runtimes/<identity-hex>/`.
- **Naming:** `pp-pal:validated-pair-*` and `pp-pal:retained-*` tags are
  replaced as follows:
  - `cacheTag` becomes a cache-key symlink-free index file,
    `$STATE/runtimes/by-key/<cache-key>`, which contains the identity.
  - Retention means the active and previous runtime directories are never
    deleted.
- **Naming, user-visible:** `ENGINE IMAGE`, `PP_PAL_ENGINE_IMAGE` and
  `PP_PAL_IMAGE` are renamed to `ENGINE RUNTIME`, `PP_PAL_ENGINE_RUNTIME`
  and `PP_PAL_RUNTIME`. `PP_PAL_RUNTIME` holds a runtime directory path.
  The write receipt's `engine` property records the identity.

### Build script (`tools/build-runtime`)

- **Language:** Bash, annotated step by step for a human reader. Each step
  says what it does and why, mirroring today's Dockerfile.
- **Steps:**
  1. Fetch, or take from `--inputs DIR`, the PP source tarball, the PP Linux
     release tarball and the Temurin tarball. Verify the SHA-256 of each.
  2. Unpack the JDK.
  3. Apply `runtime/patches/HeadlessPatch.java` using the pinned JDK.
  4. Compile with `javac --release 21`.
  5. Copy the resources and assemble `lib/`.
  6. Write `attribution/` and `runtime.json`.
  7. Make the result read-only and print the identity.
- **Arguments:**
  - `--output DIR` (required). The script builds into a temporary sibling
    directory and atomically renames it into place.
  - `--inputs DIR` for supplied archives (the updater).
  - Pins: wrapper revision and upstream revision/release, with the defaults
    from `runtime/release-package.json`.
- **Pin source:** the pinned digests and URLs move out of the Dockerfile into
  `runtime/release-package.json`, which then has `jdk` and `jdkArchive`
  (URL and SHA-256) in place of `baseImage`, plus `upstream` (tag, revision,
  source and release SHA-256). The script reads these through `python3 -c`.
  Python is already a host requirement.
- **Rootless:** user-owned paths only. It works with `umask 077`.
- `Dockerfile` and `.dockerignore` are deleted.

### Launch (`tools/run-container` → `tools/run-engine`)

- **Same contract:** the reserved options, the `--config` and `--pdf`
  handling, the resolve-first then run sequence, `PP_PAL_RESOLVE_ONLY`, and
  the exit-status mapping are all kept.
- **Invocation:** each step runs
  `<runtime>/jdk/bin/java -Djava.awt.headless=true -cp '<runtime>/classes:<runtime>/lib/*' pal.Main …`
  in the working directory.
- **Environment:** `DISPLAY` is unset and the environment is pruned to the
  variables already passed today.
- **Removed:** the mount bookkeeping and the colon/newline bind-path limits.
  Newline limits on resolved paths stay.
- **Selection:** `pp-pal` with no active manifest uses `PP_PAL_RUNTIME`, or
  the default dev runtime `$STATE/runtimes/dev` built by
  `tools/build-runtime --output "$STATE/runtimes/dev"`.

### Updater (`tools/update.py`)

- `image_identity`, `verify_candidate_image` and the `docker build` and
  `docker run` calls are replaced with these runtime-directory equivalents:
  - verify the tree digest,
  - check that the `runtime.json` labels match the pair,
  - check that `<runtime>/jdk/bin/java -version` reports Temurin-21.0.11+10,
  - build with `tools/build-runtime --inputs`.
- `validate_selected` keeps its structure:
  - the candidate writes only into a fresh output directory, from a copy of
    the input;
  - afterwards, the input is checked to be byte-identical;
  - the trusted oracle runs on the baseline runtime's JDK.

  Read-only protection is done with `chmod`, not binds.
- The manifest `dependencies.host` becomes `Linux, Bash, Python 3.10+,
  coreutils, tar`.

### Tests

- `tests/run.py`: the budget model is kept. Containers are replaced by JVM
  child processes.
  - The budget slots wrap `java` launches instead of `docker run`.
  - Per-container memory limits become `-Xmx` (and `-XX:ActiveProcessorCount`)
    values derived from the same budget.
  - Cleanup kills the process groups it labelled, instead of `docker rm -f`.
  - The `docker` shim becomes a `java` shim on PATH, which the runtime
    launcher honours through `PP_PAL_JAVA`. Only the tests set this.
- `tests/oracle.py`, `tests/runtime.sh`, `tests/migrations.sh` and
  `tests/purchase-apply.sh`: direct `docker run` calls become the runtime
  JDK with the same arguments.
- `tests/pdf-purchase.sh`: the docker trace of read-only PDF binds becomes a
  `java`-shim trace. It asserts the argument vector, and asserts that the PDF,
  portfolio and metadata bytes are unchanged.
- `tests/updater.py`: image-retention and label assertions become runtime
  directory retention, read-only state and digest assertions.
- `tests/concurrency.py` exercises the new shim.
- The `tests/*.sh IMAGE` argument becomes `RUNTIME` (a directory).
- `tests/all.sh RUNTIME` remains the cumulative suite.

### Docs

The following are updated for the new build command, requirements and
identities:
- `README.md`,
- `docs/headless-runtime.md`,
- `docs/local-updater.md`,
- `docs/agents/test-execution.md`,
- `docs/test-suite-performance.md`,
- the support matrix notes.

The historical image evidence stays as history. A new matrix entry records
the native runtime identity once the suite passes.

## Design: Part 2, unsupported releases and agent upgrades

### Detecting "unsupported"

- `runtime/release-package.json` gains `upstream: {tag, revision}`, which is
  the PP release this wrapper is qualified for.
- During the update check, after discovering the latest stable PP and wrapper
  releases, the candidate pair counts as supported if either of these holds:
  - the latest wrapper's policy names that exact upstream tag and revision;
  - a validated local upgrade pair in `active.json` names it.
- Otherwise the operation stops before any financial plan. It uses exit 3 and
  writes `UPGRADE REQUIRED` to stderr, followed by the agent prompt.

### The prompt

It is printed verbatim and is ready to paste into an agent:

```
UPGRADE REQUIRED: Portfolio Performance <tag> (<revision>) has no tested pp-pal support.
Agent prompt:
  Read <package>/UPGRADE.md and perform the pp-pal upgrade to Portfolio
  Performance <tag> (<revision>). Origin: https://github.com/medlor/portfolio-performance-pal.
  When finished, resume this operation exactly, in directory <cwd>:
  <shell-quoted original argv>
```

The same text is written to `$STATE/upgrades/<tag>/request.json`, so it
isn't lost.

### `UPGRADE.md` (repo root)

It is written for an agent and is readable by a human. It covers these steps:

1. **Work area:** clone `origin` into `$STATE/upgrades/<tag>/checkout` (or use
   an existing checkout). Create `upgrade/pp-<tag>`.
2. **Pins:** update the upstream pins in `runtime/release-package.json`, with
   digests computed from the official assets.
3. **Build:** run `tools/build-runtime`. Adapt the wrapper Java code
   following `docs/upgrading-version-support.md`. The headless patch must
   still apply exactly, or the upgrade stops for adapter maintenance.
4. **Fixtures:** add fixtures and gates for new behaviour (for example a new
   model version or migration). Never edit existing fixtures or expectations.
5. **Suite:** run the focused checks, then `bash tests/all.sh`. Record the
   evidence in the support matrix and docs.
6. **Commit and PR:** commit, push, and open a PR against `main` with the
   evidence. Ask the owner to approve and merge it. Do not attempt approval.
7. **Activation:** activate locally with
   `pp-pal --update --local-upgrade <checkout> [--file/--config …]`.
8. **Resume:** re-run the original command from `request.json` exactly,
   including `--apply --desktop-closed` when present, and report the outcome.
9. **Stop conditions:** these stop the upgrade and are reported to the owner:
   - any existing trusted file would need to change,
   - the patch fails,
   - a gate fails,
   - the owner's GitHub permission is missing.

   Nothing is activated in these cases.

### `--update --local-upgrade CHECKOUT` (ADR 0006)

1. Require a clean git checkout at a commit, and snapshot its tree into
   `$STATE/wrappers/local-<commit>`.
2. **Trusted-bundle rule, append-only:** every file in the existing trusted
   validation bundle must be byte-identical in the checkout. New gates,
   fixtures and oracles may be added.
3. Build the runtime from the snapshot with the cached, verified archives.
4. Run all old and new gates, plus the selected-copy validation, using the
   same qualification machinery as a release.
5. On success, extend the trusted bundle with the new files and record its
   new digest. Then activate atomically with `source: local-upgrade`, the
   commit, and the upstream tag and revision. The previous pair is retained.
6. **Later releases:** normal release discovery continues on every
   operation. A stable wrapper release that supports the same or newer
   upstream, and qualifies, replaces the local pair. Until then the local
   pair is used for all portfolio operations.

## Left to the builder

- Exact Temurin archive URL and SHA-256 (from Adoptium's API, then pinned).
- How to map budget-to-JVM-flag values, while keeping the host-memory
  reserve.
- Internal helper structure in `update.py` and `run.py`.
- Wording of docs, provided they stay consistent with the glossary.

## Calls I made (owner to check)

1. The launcher is renamed to `tools/run-engine` (from `tools/run-container`),
   and image naming changes to "runtime".
2. The engine identity keeps the `sha256:` form, as a tree digest.
3. A local upgrade may only *add* trusted files, so changed expectations
   block unattended activation.
4. The upgrade work area defaults to `$STATE/upgrades/<tag>/checkout`.
5. Bootstrap protocol moves to 2. No migration is needed, since no release
   exists yet.
