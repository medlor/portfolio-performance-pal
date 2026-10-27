# Docker-free runtime and agent upgrades: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every pp-pal operation runs as a plain rootless user process,
using a pinned, self-provisioned Temurin JDK. Docker is removed entirely.
When a PP release has no tested support, the update check stops the
operation and hands off to an agent through `UPGRADE.md`. The agent:
- builds the upgrade,
- activates it locally with `--update --local-upgrade`,
- opens a PR,
- re-runs the original command.

**Architecture:**
- `tools/build-runtime` (annotated Bash) replaces the Dockerfile. It builds
  a read-only *runtime directory* that contains the JDK, classes, libs,
  attribution and `runtime.json`.
- `tools/engine` runs a JDK tool from a runtime.
- `tools/run-engine` replaces `tools/run-container` and keeps its contract.
- `tools/update.py` swaps images for runtime directories. It also gains
  unsupported-release detection and a local-upgrade path.
- The test budget in `tests/run.py` wraps JVM processes instead of
  containers.

**Tech stack:** Bash, Python 3.10+, Temurin 21.0.11+10 (downloaded), PP
0.88.0 sources and release jars, git, `gh` (agent side only).

**Spec:** `docs/superpowers/specs/2026-10-27-native-runtime-design.md`.
Read it first; this plan argues from it.

## Global constraints

- No Docker anywhere: no `docker` call, no `Dockerfile`, no
  `.dockerignore`, no image tags. `rg -i docker` may hit only history and
  evidence prose, ADRs and this plan.
- Rootless: no `sudo`, no root and no system package installation in any
  script. Everything is written to user-owned paths under `umask 077`.
- JDK pin: Temurin **21.0.11+10**, Linux x64 tarball, SHA-256 checked. The
  host `java` is never used for the engine, oracles or builds.
- Upstream pins are unchanged:
  - revision `8de1fe41d71a2c1b74a6bfbe913f885899aaf123`,
  - release `0.88.0`,
  - source SHA-256
    `ce6d10fe45ce5484f8c4ee9ae36eb1654890b03f6adb2fbbd3f54077d93cc656`,
  - release SHA-256
    `1770d95488a383f3b97e5e7a1b30f9fd122200a16f88078caa4823951e9aeac2`.
- Runtime identity is `<upstream tag>-<wrapper revision[:12]>`, or `dev`
  when the wrapper revision is `development`. It is the runtime
  directory's basename and the `identity` field of `runtime.json`.
- Environment and output renames:

  | Old | New |
  |---|---|
  | `PP_PAL_IMAGE` | `PP_PAL_RUNTIME` (runtime directory path) |
  | `PP_PAL_ENGINE_IMAGE` | `PP_PAL_ENGINE_RUNTIME` (identity) |
  | preview line `ENGINE IMAGE ` | `ENGINE RUNTIME ` |

- Default runtime: `$STATE/runtimes/dev`, where `$STATE` is resolved like
  `state_directory()`.
- `bootstrapProtocol` becomes **2**, and `runtime/release-package.json`
  loses `baseImage`.
- Preserve every existing financial assertion and oracle. A
  Docker-mechanics assertion is replaced by its native equivalent, never
  deleted.
- `UPGRADE REQUIRED` exits with status **3**.
- Platform is Linux x86_64 only.

## Review focus

These are the failure modes most likely to bite the owner. Each one is
pinned by a test in the task that owns it:

1. **Read-only runtime directories break cleanup.** `chmod -R a-w` makes
   `rm -rf` or `TemporaryDirectory` cleanup fail for the owner and tests.
   Every deletion must restore `u+w` first. Owned by Task 1 (helper) and
   Task 5 (updater).
2. **The host's Java 25 on PATH leaks into the engine.** A fake `java`
   that exits 99 placed first on PATH must not affect any command. Owned
   by Task 2.
3. **Portfolio or PDF paths containing `:` or spaces.** These were refused
   because of Docker binds and must now work. Newline is still refused.
   Owned by Task 2.
4. **A missing or half-built runtime.** An interrupted build must never
   leave a directory with the final name. A missing runtime gives exit 3
   and a message naming `tools/build-runtime`. Owned by Tasks 1 and 2.
5. **Original argv that is quoted or has spaces in the upgrade prompt.**
   `request.json` and the prompt must reproduce the argv exactly, through
   `shlex.join`, including `--apply --desktop-closed`. Owned by Task 6.

---

### Task 1: Pins and the annotated `tools/build-runtime`

**Files:**
- Modify: `runtime/release-package.json`
  - remove `baseImage`;
  - `bootstrapProtocol: 2`;
  - add `jdkArchive: {url, sha256}`;
  - add `upstream: {tag: "0.88.0", revision, sourceSha256, releaseSha256, releaseAsset: "PortfolioPerformance-0.88.0-linux.gtk.x86_64.tar.gz"}`.
- Create: `tools/build-runtime` (executable)
- Create: `tools/runtime_dir.py`, with small helpers that both
  `update.py` and the tests use.
- Modify: `README.md`. Add a "Network access" section listing every URL
  the build and the updater fetch, including the redirect hosts. It also
  says that `PP_PAL_ARCHIVE_CACHE` or `--inputs` allow offline builds
  from pre-fetched archives, verified by SHA-256. The section lists:
  - `https://github.com/adoptium/temurin21-binaries/releases/download/…`:
    the JDK.
  - `https://api.github.com/repos/portfolio-performance/portfolio/…`:
    release discovery and the source tarball.
  - `https://github.com/portfolio-performance/portfolio/releases/download/…`:
    the PP Linux release.
  - `https://api.github.com/repos/medlor/portfolio-performance-pal/…`:
    wrapper release discovery and tarballs.
  - Redirect targets: `codeload.github.com`,
    `objects.githubusercontent.com` and
    `release-assets.githubusercontent.com`.
  - Maintainers only, for pin lookup: `api.adoptium.net`.
- Test: `tests/build-runtime.sh`

**Interfaces:**
- Produces:
  - `tools/build-runtime --output DIR [--inputs DIR] [--wrapper-revision REV]`
    - `--inputs DIR` contains `upstream-source.tar.gz`,
      `upstream-release.tar.gz` and `jdk.tar.gz`.
    - Without `--inputs`, it downloads into
      `${PP_PAL_ARCHIVE_CACHE:-$STATE/downloads}/<sha256>`. The cache is
      reused when the digest matches.
    - On success, the identity is the last stdout line.
    - Exit codes: 2 for bad arguments or an existing output directory,
      3 for a checksum or build failure.
    - Pins are read from `runtime/release-package.json` next to the
      script.
  - Runtime layout: `jdk/`, `classes/` (including `help.txt` and the
    upstream resources), `lib/`, `attribution/`, `runtime.json`.
  - `runtime.json`:
    ```json
    {"schema":1, "identity":"…", "wrapperRevision":"…",
     "upstreamRevision":"…", "upstreamRelease":"…", "xmlModel":"70",
     "jdk":"Temurin-21.0.11+10", "platform":"linux/amd64"}
    ```
  - `tools/runtime_dir.py`:
    - `read_runtime(path: pathlib.Path) -> dict`: validates the schema, and
      that `identity == path.name`, and that `jdk/bin/java` exists. Raises
      `ValueError` with the message
      `Selected runtime is unavailable or incomplete; build it with tools/build-runtime: <path>`.
    - `remove_runtime(path: pathlib.Path) -> None`: runs `chmod -R u+w`,
      then `rmtree`.
    - CLI: `python3 tools/runtime_dir.py identity PATH` prints the identity
      or exits 3.

- [ ] **Step 1: Pin the Temurin archive.** Get the URL and SHA-256 of
  `OpenJDK21U-jdk_x64_linux_hotspot_21.0.11_10.tar.gz` from
  `https://api.adoptium.net/v3/assets/release_name/eclipse/jdk-21.0.11%2B10?os=linux&architecture=x64&image_type=jdk`.
  Cross-check the downloaded file's `sha256sum`. Write both values into
  `jdkArchive`.

- [ ] **Step 2: Write the failing test `tests/build-runtime.sh`.** It uses
  the standard `tests/run.py` re-exec header and makes these assertions:
  - (a) A run with `--inputs` holding a corrupted `jdk.tar.gz` exits 3 and
    prints `SHA-256 mismatch`. No `--output` directory exists afterwards,
    and no `.build-*` sibling is left over.
  - (b) A real build into `"$work/dev"` exits 0. The last stdout line is
    `dev`. `runtime.json` has `identity` `dev` and `xmlModel` `70`.
    `"$work/dev/jdk/bin/java" -version 2>&1` contains `Temurin-21.0.11+10`.
    `classes/help.txt` equals `runtime/help.txt`. There is no
    `lib/name.abuchen.portfolio_*.jar`.
  - (c) `find "$work/dev" -perm -u+w -type f` is empty.
  - (d) A build into an existing directory exits 2.
  - (e) `python3 tools/runtime_dir.py identity "$work/dev"` prints `dev`.
    `remove_runtime` deletes the directory.
  - (f) The script contains no `sudo`.

  Point (b) uses `PP_PAL_ARCHIVE_CACHE` from the environment when set, so
  CI and dev runs reuse downloads.

- [ ] **Step 3: Run it.** `bash tests/build-runtime.sh` fails with "No such
  file `tools/build-runtime`".

- [ ] **Step 4: Implement `tools/build-runtime`.**
  - Use numbered, commented sections that mirror the old Dockerfile line
    for line:
    1. resolve pins,
    2. fetch or verify archives,
    3. unpack the JDK,
    4. unpack the sources and release,
    5. apply `HeadlessPatch.java` with `jdk/bin/java`,
    6. compile with `javac -encoding UTF-8 --release 21 -cp 'release/portfolio/plugins/*'`,
    7. copy the non-Java resources,
    8. assemble `lib/` minus the core, ui and pdfbox3 jars,
    9. write attribution and `runtime.json`,
    10. run `chmod -R a-w` and atomically `mv` the build into `--output`.
  - Each section's comment says *what* it does and *why*.
  - Work in `mktemp -d "$(dirname OUT)/.build-XXXX"`, with a trap that
    removes it on failure, restoring `u+w` first.
  - When `PP_PAL_JAVA_WRAPPER` is set, prefix the `java` and `javac`
    invocations with `"$PP_PAL_JAVA_WRAPPER" --build`. Task 3 defines that
    wrapper.
  - Implement `tools/runtime_dir.py`.

- [ ] **Step 5: Run it.** `bash tests/build-runtime.sh` prints `PASS`.

- [ ] **Step 6: Document network access.** Write the README "Network
  access" section. Check each listed URL against the URLs that
  `tools/build-runtime`, `release-package.json` and `tools/update.py`
  actually use.

- [ ] **Step 7: Commit.** Message: `Add rootless annotated runtime build`.

### Task 2: Native launch (`tools/engine`, `tools/run-engine`) and Java renames

**Files:**
- Create: `tools/engine`
- Create: `tools/run-engine`, from `tools/run-container`, which is deleted
- Modify: `pp-pal`
- Modify: `runtime/src/main/java/pal/{Purchase,Dividend,WriteReceipt}.java`
- Modify: `runtime/help.txt:28`, which becomes `--help  Show this help
  without a runtime or a portfolio.`
- Test: `tests/runtime.sh` (migrated) and `tests/launch.sh` (new)

**Interfaces:**
- Consumes: the runtime layout and `tools/runtime_dir.py identity` from
  Task 1.
- Produces:
  - `tools/engine (main|java|javac) ARGS...`
    - Reads `PP_PAL_RUNTIME`. Its default is `$STATE/runtimes/dev`.
    - Validates the runtime through `runtime_dir.py`. Failure exits 3 with
      that module's message.
    - `main` executes `$R/jdk/bin/java -Djava.awt.headless=true -cp "$R/classes:$R/lib/*" pal.Main ARGS`.
    - `java` and `javac` execute that JDK tool with ARGS. They export
      `PP_PAL_RUNTIME_CP="$R/classes:$R/lib/*"` for callers.
    - `DISPLAY` is unset, and `PP_PAL_ENGINE_RUNTIME` is set to the
      identity.
    - If `PP_PAL_JAVA_WRAPPER` is set, `"$PP_PAL_JAVA_WRAPPER" TOOLPATH ARGS`
      is executed instead.
  - `tools/run-engine`: the same CLI contract as the old `run-container`.
    - Kept: reserved options, `--config`, a single `--pdf`,
      `PP_PAL_RESOLVE_ONLY`, `PP_PAL_NOW` and `PP_PAL_WRITE_OBSERVER`
      pass-through, and status mapping 0, 2, 3 and 4, with everything else
      mapped to 3 (`ERROR: Engine command failed (status N)`).
    - It runs `tools/engine main` twice: first resolve, then run.
    - Bind and mount logic is removed. The colon restriction is removed.
      The newline restriction is kept.
  - `pp-pal` is unchanged, apart from the comments and the Python dispatch
    path.

- [ ] **Step 1: Migrate `tests/runtime.sh`.**
  - Replace `run()` with `"$root/tools/engine" main "$@"`.
  - Use host paths instead of `/fixtures` and `/work`.
  - The script takes `RUNTIME`: `export PP_PAL_RUNTIME=$1`.
  - Leave oracle calls for Task 3. Temporarily call
    `tools/engine java -cp "$PP_PAL_RUNTIME_CP" runtime/src/test/java/pal/FixtureOracle.java …`.
  - Keep every existing assertion, including `MODEL 70 EUR`, the
    roundtrip `cmp`, the pdf diff and the unsupported model/xpath
    rejections.

- [ ] **Step 2: Write the new test `tests/launch.sh`.** With a dev runtime,
  it makes these assertions:
  - (a) `PATH="$fake:$PATH"`, where `$fake/java` is a script that exits
    99. `tools/run-engine holdings --file "$work/a:b c/portfolio.xml"`
    exits 0 and lists the fixture holdings. This also covers a path with
    a colon and a space.
  - (b) A portfolio path containing a newline exits 2.
  - (c) `PP_PAL_RUNTIME=$work/missing` exits 3, and stderr contains
    `build it with tools/build-runtime`.
  - (d) A `runtime.json` identity that doesn't match the directory name
    exits 3.
  - (e) A purchase preview prints `ENGINE RUNTIME dev`, and nothing prints
    `ENGINE IMAGE`.
  - (f) `--help` exits 0 when `PP_PAL_RUNTIME` points at a missing
    directory.

- [ ] **Step 3: Run both.** `bash tests/runtime.sh "$R"` and
  `bash tests/launch.sh "$R"` fail, because `tools/engine` is missing.

- [ ] **Step 4: Implement.**
  - Write `tools/engine` and `tools/run-engine`.
  - Rename the variables in the three Java files. The receipt property
    `engine` keeps its name and now holds the identity.
  - Rebuild the dev runtime, because the Java code changed.

- [ ] **Step 5: Run both.** `bash tests/runtime.sh "$R"` and
  `bash tests/launch.sh "$R"` print `PASS`.

- [ ] **Step 6: Commit.** Message: `Launch commands natively from a runtime
  directory`.

### Task 3: Native test budget and oracle interface

**Files:**
- Modify: `tests/run.py`. Replace `docker_command` with
  `java_command(args)`. The shim becomes `PP_PAL_JAVA_WRAPPER`.
- Modify: `tests/oracle.py`, with the new interface.
- Modify: `tests/concurrency.py`. A stub JDK tool replaces the stub
  docker.
- Modify: `tools/test_jobs.py`, only if it references docker.
- Modify: `docs/test-suite-performance.md`

**Interfaces:**
- Consumes: `tools/engine` and `PP_PAL_RUNTIME_CP` from Task 2.
- Produces:
  - `test_environment()` sets these variables:
    - `PP_PAL_JAVA_WRAPPER=<tmp>/bin/pp-pal-java`, which runs
      `exec python3 tests/run.py --java "$@"`;
    - `PP_PAL_TEST_BUDGET`, `PP_PAL_TEST_SUITE` and
      `PP_PAL_TEST_WORKERS`, which keep their meaning.

    Docker is no longer required.
  - `tests/run.py --java [--build] TOOL ARGS`:
    - acquires a slot, or the whole budget when `--build` is given;
    - starts TOOL in a new session, inserting `-Xmx<0.75·memory>m` and
      `-XX:ActiveProcessorCount=<ceil cpus>` right after TOOL. For
      `javac`, these are inserted as `-J-Xmx…` and
      `-J-XX:ActiveProcessorCount=…`;
    - refuses caller-supplied `-Xmx*`, `-XX:MaxRAM*` or
      `-XX:ActiveProcessorCount*`, with the message
      `Test JVMs must use the shared resource limits`;
    - kills the process group after `JOB_TIMEOUT`, and on SIGTERM or
      SIGINT;
    - sends `complete` to the budget socket on a normal exit.

    `Budget.close()` kills the remaining registered process groups,
    replacing `docker rm -f`. The pids limit is dropped and documented as
    having no rootless equivalent.
  - `python3 tests/oracle.py --compile RUNTIME DEST`: compiles every
    `runtime/src/test/java/pal/*.java` with `tools/engine javac`.
  - `python3 tests/oracle.py RUNTIME ORACLE [ARGS...]`:
    - `ORACLE` is a class name such as `FixtureOracle`. `ARGS` are host
      paths.
    - When `PP_PAL_TEST_CLASSES` is set, it runs
      `tools/engine java -cp "$PP_PAL_TEST_CLASSES:$CP" pal.ORACLE ARGS`.
    - Otherwise it runs in source mode:
      `tools/engine java -cp "$CP" runtime/src/test/java/pal/ORACLE.java ARGS`.
    - Outside a budget, it re-execs through `tests/run.py --`, as it does
      today.

- [ ] **Step 1: Port `tests/concurrency.py`.** Every existing scenario
  stays: admission, timeout, build exclusivity, interruption cleanup,
  refusing controlled flags, and "application `-d` is not a resource
  flag". Each becomes a fake `java`, a script that sleeps or records its
  argv, started through `tests/run.py --java`. Add these assertions:
  - the inserted `-Xmx` and `-XX:ActiveProcessorCount` are present;
  - a caller-supplied `-Xmx1g` is refused;
  - on SIGTERM, the fake process group is gone within 5 seconds.

- [ ] **Step 2: Run it.** `python3 tests/concurrency.py` fails, because
  `--java` is unknown.

- [ ] **Step 3: Implement** the `tests/run.py` and `tests/oracle.py`
  interfaces above. Switch the `tests/runtime.sh` oracle calls to
  `python3 tests/oracle.py "$PP_PAL_RUNTIME" FixtureOracle <host path>`.

- [ ] **Step 4: Run both.** `python3 tests/concurrency.py` and
  `bash tests/runtime.sh "$R"` print `PASS`.

- [ ] **Step 5: Commit.** Message: `Budget native JVM test jobs`.

### Task 4: Migrate the gate scripts

**Files:**
- Modify: all `runtime/candidate-gates.json` checks except
  `tests/runtime.sh` (Task 2). These are holdings, history, backups,
  purchase, purchase-apply, migrations, fx, security-create, pdf-purchase,
  pdf-apply, pdf-sale, pdf-dividend, upstream-reuse, pdf-fx,
  smartbroker-current, sale and dividend.
- Modify: `tests/all.sh`

**Interfaces:**
- Consumes:
  - `tools/engine` (Task 2);
  - `tests/oracle.py RUNTIME ORACLE ARGS` (Task 3);
  - `PP_PAL_BEHAVIOR_LAUNCHER`, which now defaults to
    `$root/tools/run-engine`.
- Produces: every script accepts `RUNTIME` as `$1`, and runs
  `export PP_PAL_RUNTIME=$1`.

Each conversion is mechanical:
- `docker run … IMAGE CMD /work/x` becomes
  `"$root/tools/engine" main CMD "$work/x"`;
- `oracle.py run … --entrypoint java IMAGE -cp … /tests/pal/X.java a b`
  becomes `oracle.py "$PP_PAL_RUNTIME" X <host a> <host b>`;
- `os.environ['PP_PAL_IMAGE']` and `PP_PAL_IMAGE=` become `PP_PAL_RUNTIME`;
- `ENGINE IMAGE` expectations become `ENGINE RUNTIME <identity>`.

Two special cases:
- `tests/pdf-purchase.sh`: the docker trace becomes a `PP_PAL_JAVA_WRAPPER`
  chain. The trace script appends its argv as JSON and execs the previous
  wrapper, or TOOL. Assert that:
  - each `pal.Main` invocation receives the PDF as its absolute host path;
  - the PDF, portfolio, configuration and metadata bytes are unchanged
    after every preview (the old read-only-bind intent).
- `tests/purchase-apply.sh:50`: `PP_PAL_NOW` is passed through the
  environment.

- [ ] **Step 1: Record the baseline.** Save the old scripts' assertion
  inventory as
  `rg -c 'check\(|assert|cmp |diff -u|test "' tests/*.sh > /tmp/assert-before`.
  Use the committed `main` versions.

- [ ] **Step 2: Convert the manual-entry scripts.** These are holdings,
  history, backups, purchase, purchase-apply, migrations, fx,
  security-create, sale and dividend. Run each one with
  `bash tests/<name>.sh "$R"` and expect `PASS`.

- [ ] **Step 3: Convert the PDF and reuse scripts.** These are
  pdf-purchase, pdf-apply, pdf-sale, pdf-dividend, pdf-fx,
  smartbroker-current and upstream-reuse. Run each one and expect `PASS`.

- [ ] **Step 4: Check that no assertion was lost.** Rerun the `rg -c` from
  Step 1 and diff it against `/tmp/assert-before`. Every count must be
  greater than or equal to its old value. `rg -n 'docker|/opt/pp|--entrypoint' tests`
  must return nothing.

- [ ] **Step 5: Commit.** Message: `Run all gate scripts against native
  runtimes`.

### Task 5: Native updater

**Files:**
- Modify: `tools/update.py`
- Modify: `tests/updater.py`
- Modify: `tests/updater.sh`

**Interfaces:**
- Consumes: Tasks 1 to 4.
- Produces, in `tools/update.py`:
  - `runtime_identity(path: pathlib.Path) -> str`, which wraps
    `runtime_dir.read_runtime`. It replaces `image_identity` and does no
    tagging.
  - `build_runtime(package: pathlib.Path, inputs: pathlib.Path, wrapper_revision: str, state: pathlib.Path) -> pathlib.Path`.
    - It runs `package/tools/build-runtime --inputs … --output $STATE/runtimes/<upstream tag>-<rev12>`.
    - If that directory already exists and `read_runtime` accepts it, the
      runtime is reused.
  - `verify_candidate_runtime(path, wrapper, upstream, policy) -> str`.
    - It checks that the `runtime.json` revisions match, along with the
      `xmlModel` and the output model.
    - `jdk/bin/java -version` must contain `21.0.11` and
      `Temurin-21.0.11+10`.
    - It returns the version text.
  - `download_jdk(state, policy) -> (path, sha)` uses `download()` with
    `policy['jdkArchive']`. The cache directory comes from
    `PP_PAL_ARCHIVE_CACHE` when set.
  - Manifest keys:

    | Old | New |
    |---|---|
    | `image` | `runtime` (absolute path) and `identity` |
    | `baselineImage` | `baselineRuntime` |
    | `retentionTag`, `cacheTag` | removed |

    `dependencies.host` becomes
    `['Linux', 'Bash', 'Python 3.10+', 'coreutils', 'tar']`, and `build`
    becomes `policy['jdk']`.
  - `validate_selected(state, original, baseline_runtime, candidate_runtime)`:
    - The candidate runs `tools/engine main roundtrip <input> <output>`
      with `PP_PAL_RUNTIME=candidate`.
    - Before that, the input copy is set to mode 0400 and the output
      directory is fresh.
    - The baseline oracle runs `SelectedPortfolioOracle.java` from the
      trusted bundle on the baseline runtime's JDK.
  - `extract_wrapper` allows `UPGRADE.md`. It drops `Dockerfile` and
    `.dockerignore` from the required and allowed sets, and requires
    `tools/build-runtime`, `tools/engine`, `tools/run-engine` and
    `tools/runtime_dir.py`. The policy comparison keys become `input`,
    `output`, `migrations`, `jdk`, `jdkArchive`, `layouts` and `features`.
  - `trusted_bundle` adds `tools/runtime_dir.py` to the bundle file list.
  - The gate environment sets `PP_PAL_RUNTIME`, plus
    `PP_PAL_BEHAVIOR_LAUNCHER=<package>/tools/run-engine`, plus
    `PP_PAL_GATE_BASELINE_RUNTIME`.
  - Every removal of a runtime directory goes through `remove_runtime`.

- [ ] **Step 1: Migrate `tests/updater.py`.**
  - `IMAGE` becomes `RUNTIME`, taken from argv or `PP_PAL_RUNTIME`.
  - `released_snapshot` no longer copies the Dockerfile.
  - The fixture server also serves the JDK archive. It is read from
    `PP_PAL_ARCHIVE_CACHE` or downloaded and pinned like `public_archive`.
  - Lines 470 and 704 to 707 change from docker oracle and image retention
    to these assertions:
    - the active and previous runtime directories exist, and
      `read_runtime` accepts them;
    - no file in them is writable;
    - the manifest `identity` equals the directory name.
  - `missing-owner-image` and `missing-development-image` become missing
    runtime paths, with the same expected errors, reworded to "runtime".
  - Add a test: when the gate work directory is cleaned up after
    qualification, `remove_runtime` deletes candidate runtimes without
    `PermissionError`.

- [ ] **Step 2: Run it.** `bash tests/updater.sh "$R" --arguments-only`
  fails on the removed docker calls.

- [ ] **Step 3: Implement** the `tools/update.py` interfaces above.

- [ ] **Step 4: Run the slices.** Run
  `bash tests/updater.sh "$R" --arguments-only`, then `--apply-slice`,
  then `--pdf-slice`. Each must print its existing success lines.

- [ ] **Step 5: Commit.** Message: `Build and qualify released pairs as
  native runtimes`.

### Task 6: Stop on unsupported upstream releases with an agent prompt

**Files:**
- Modify: `tools/update.py`
- Modify: `tests/updater.py`, adding the `--unsupported-slice` section,
  which also runs by default.
- Modify: `tests/updater.sh`, accepting the new slice flag.

**Interfaces:**
- Produces:
  - `class UpgradeRequired(UpdateError)`. `main()` prints its message to
    stderr and exits 3.
  - `supported(policy: dict, active: dict | None, upstream: dict) -> bool`:
    - True if `policy['upstream']` has the same `tag` and `revision` as
      `upstream`;
    - also true if `active` has `source == 'local-upgrade'` and
      `active['upstream']` has the same tag and revision.
  - `upgrade_prompt(upstream, argv: list[str], cwd: str) -> str` returns
    exactly this text:
    ```
    UPGRADE REQUIRED: Portfolio Performance {tag} ({revision}) has no tested pp-pal support.
    Agent prompt:
      Read {ROOT}/UPGRADE.md and perform the pp-pal upgrade to Portfolio
      Performance {tag} ({revision}). Origin: https://github.com/medlor/portfolio-performance-pal.
      When finished, resume this operation exactly, in directory {cwd}:
      {shlex.join([str(ROOT / 'pp-pal'), *argv])}
    ```
  - `$STATE/upgrades/<tag>/request.json` contains
    `{"schema":1, "upstream":{tag,revision}, "argv":[...], "cwd":"…", "prompt":"…"}`.
    It is written with `atomic_json`.
  - `perform_update(arguments, original_argv)` checks after the wrapper
    policy is known, which is after extraction or reading the cached
    package, and before any upstream source download, build, or update
    backup. When `active` is a local upgrade that covers the latest
    upstream, and the wrapper release doesn't, the active pair is reused.
    This uses the existing `UNCHANGED` path: it validates the selected copy
    and prints `UNCHANGED validated local-upgrade pair`.
  - `dispatch` passes the full original argv. An explicit `--update` passes
    `['--update', *rest]`.

- [ ] **Step 1: Write the failing test.** The fixture upstream's latest
  release is `0.88.1-fixture`, pointing at the same revision and asset
  bytes. The released wrapper's policy still says `0.88.0`. Run
  `pp-pal purchase … --apply --desktop-closed --file "$work/my 'quoted' portfolio.xml"`
  and assert:
  - exit 3;
  - stderr contains `UPGRADE REQUIRED: Portfolio Performance 0.88.1-fixture`;
  - the prompt's last line, after `shlex.split`, equals
    `[ROOT/pp-pal, 'purchase', …, '--apply', '--desktop-closed', '--file', "<path with space and quote>"]`;
  - `request.json`'s `argv` equals the original argv, and its `cwd`
    equals the test's working directory;
  - the portfolio and metadata bytes are unchanged;
  - no `*.update.*.bak` was created;
  - no build directory was created under `$STATE/builds`;
  - `active.json` is unchanged.

  Also assert that `--update` alone produces the same stop, with argv
  `['--update', …]`.

- [ ] **Step 2: Run it.** `bash tests/updater.sh "$R" --unsupported-slice`
  fails, because it currently proceeds to build.

- [ ] **Step 3: Implement** the interfaces above.

- [ ] **Step 4: Run it.** `bash tests/updater.sh "$R" --unsupported-slice`
  prints `PASS unsupported upstream`. The `--arguments-only` slice must
  still pass.

- [ ] **Step 5: Commit.** Message: `Stop unsupported upstream releases with
  an agent upgrade prompt`.

### Task 7: `--update --local-upgrade CHECKOUT`

**Files:**
- Modify: `tools/update.py`
- Modify: `runtime/help.txt`, documenting `--update --local-upgrade PATH`.
- Modify: `tests/updater.py`, adding the `--local-upgrade-slice` section,
  which also runs by default after the unsupported slice.
- Modify: `tests/updater.sh`

**Interfaces:**
- Consumes: Task 5 (qualification machinery) and Task 6 (`supported`,
  `request.json`).
- Produces:
  - `perform_local_upgrade(checkout: pathlib.Path, selected_args: list[str]) -> dict`.
    It takes the same update lock as `perform_update` and goes through
    these steps in order:
    1. `git -C checkout status --porcelain` must be empty. Otherwise it
       raises `InputError('Local upgrade checkout must be clean and committed')`.
       The commit is `rev-parse HEAD`.
    2. It discovers the latest upstream release. The checkout's
       `release-package.json` `upstream` must equal that release.
       Otherwise it raises
       `UpdateError('Local upgrade does not target the latest upstream release <tag>')`.
    3. It snapshots `git archive HEAD` into `$STATE/wrappers/local-<commit>`,
       using the same allowlist as `extract_wrapper`, including the
       bootstrap-identity rule for `pp-pal` and `tools/update.py`. The
       policy comparison against the trusted bundle is skipped, because
       the policy may change.
    4. It builds a candidate trusted bundle from the checkout, using the
       same file list as `trusted_bundle`, into a temporary directory. The
       result of `trusted_changes(old_root, new_root) -> {'added':[], 'changed':[], 'removed':[]}`
       uses sorted relative paths. If any old `candidate-gates.json` check
       is missing from the new registry, it raises
       `UpdateError('Local upgrade removes trusted gate <path>')`.
    5. It runs `backup-before-update`. It downloads the archives pinned in
       the checkout's policy (upstream URLs as in `upstream_asset`, and the
       JDK) and builds the runtime `<upstream tag>-<commit12>`.
    6. It runs `verify_candidate_runtime`, then `validate_selected` with
       the *current* trusted bundle and the baseline runtime, then
       `fixture_checks` using the *new* bundle's gates.
    7. It moves the current bundle to
       `$STATE/trusted-validation.previous-<commit>`, installs the new
       bundle, and rewrites `trusted-validation.json`.
    8. It writes the report, `previous.json` and `active.json`. The
       manifest has `source:'local-upgrade'`,
       `wrapper:{tag:'local-<commit12>', revision:commit, treeSha256}`,
       `upstream:{tag, revision, …}` and `trustedChanges`.
    9. It prints `TRUSTED CHANGES` with one `added|changed|removed <path>`
       line per change, then `ACTIVATED local-upgrade <identity>`.
  - On any failure, `active.json` and the trusted bundle are unchanged.
  - CLI: `main()` routes `--update --local-upgrade PATH [--file F] [--config C]`
    to this function.

- [ ] **Step 1: Write the failing tests.** These continue from the Task 6
  state:
  - (a) A dirty checkout exits 2 with
    `must be clean and committed`. `active.json` is unchanged.
  - (b) A checkout whose `candidate-gates.json` drops `tests/sale.sh`
    exits 3 with `removes trusted gate tests/sale.sh`, before any build
    starts.
  - (c) A valid checkout is the released snapshot plus two changes: the
    policy `upstream.tag` becomes `0.88.1-fixture`, and a comment is added
    to `tests/holdings.sh`. Running `--update --local-upgrade` against it
    must:
    - exit 0;
    - print `changed tests/holdings.sh` under `TRUSTED CHANGES`;
    - set `active.json` `source` to `local-upgrade`, with `identity`
      `0.88.1-fixture-<commit12>`;
    - leave `trusted-validation.previous-<commit>` in place.
  - (d) Re-running the exact argv from `request.json` exits 0 and applies
    the purchase. Its preview or receipt shows `ENGINE RUNTIME
    0.88.1-fixture-<commit12>`. The saved-model oracle confirms one new
    purchase.
  - (e) When a new fixture wrapper release `v0.2.0` declares
    `0.88.1-fixture` support, the next `holdings` run qualifies it and
    activates it. Its `previous` is the local-upgrade manifest.

- [ ] **Step 2: Run it.** `bash tests/updater.sh "$R" --local-upgrade-slice`
  fails, because the option is unknown.

- [ ] **Step 3: Implement** the interfaces above.

- [ ] **Step 4: Run it.** `bash tests/updater.sh "$R" --local-upgrade-slice`
  prints `PASS local upgrade`.

- [ ] **Step 5: Commit.** Message: `Activate validated agent local upgrades`.

### Task 8: `UPGRADE.md`, ADRs, docs and Docker removal

**Files:**
- Create: `UPGRADE.md`, which follows spec section "`UPGRADE.md`" steps 1
  to 9, as an agent runbook with exact commands.
- Create: `docs/adr/0005-native-runtime.md`, which supersedes the Docker
  clause of ADR 0002.
- Create: `docs/adr/0006-agent-local-upgrades.md`, covering:
  - the exception to ADR 0004;
  - that tests may be adapted unattended;
  - the safeguards from spec Part 2.
- Modify: `docs/adr/0002-…md`. Add the line
  `Packaging superseded by ADR 0005.`
- Modify: `docs/adr/0004-…md`. Add the line
  `Local agent upgrades: see ADR 0006.`
- Modify: `README.md`, `docs/headless-runtime.md`, `docs/local-updater.md`,
  `docs/agents/test-execution.md`, `docs/upgrading-version-support.md`,
  `docs/cli.md` and `AGENTS.md`, where they mention a launcher or images.
- Delete: `Dockerfile` and `.dockerignore`.
- Modify: `tests/all.sh`. Its argument is `RUNTIME`, and it adds
  `bash tests/build-runtime.sh` and `bash tests/launch.sh` before the
  updater.

`UPGRADE.md` must contain these exact commands:
- `git clone https://github.com/medlor/portfolio-performance-pal "$STATE/upgrades/<tag>/checkout"`;
- `git switch -c upgrade/pp-<tag>`;
- `tools/build-runtime --output "$STATE/runtimes/dev-<tag>"`;
- `bash tests/all.sh "$STATE/runtimes/dev-<tag>"`;
- `gh pr create --base main --title "Support Portfolio Performance <tag>" --body-file …`.
  The body includes the `TRUSTED CHANGES` list and the suite evidence.
- `pp-pal --update --local-upgrade <checkout> [--file …]`;
- the argv re-run from `request.json`.

It must also state these rules:
- never approve the PR;
- never delete a gate or weaken a financial assertion;
- the stop conditions;
- the behaviour when `gh` permission is missing: report it and keep the
  branch local.

It must tell the agent to read `docs/upgrading-version-support.md` and
`docs/updating-broker-extractor.md` before adapting code.

- [ ] **Step 1: Write the docs, ADRs and `UPGRADE.md`.** Delete the Docker
  files.

- [ ] **Step 2: Check for leftovers.** `rg -n -i 'docker' --glob '!docs/superpowers/**' --glob '!docs/adr/**' --glob '!runtime/support-matrix.json'`
  returns only historical-evidence prose in `docs/headless-runtime.md` and
  `docs/upgrading-version-support.md`, each marked as historical.
  `rg -n 'run-container|PP_PAL_IMAGE|ENGINE IMAGE'` returns nothing.

- [ ] **Step 3: Run the full suite** and report its elapsed time:
  `PP_PAL_TEST_JOBS=4 bash tests/all.sh "$STATE/runtimes/dev"`. It must
  print `SUITE PASS`. Compare the gate registry with `main`: it must be
  identical, and the assertion inventory from Task 4 Step 4 must still
  hold.

- [ ] **Step 4: Record the evidence.** Append a support-matrix entry with
  the native runtime identity, the JDK archive SHA-256 and the suite
  result. Earlier entries are kept unchanged.

- [ ] **Step 5: Commit.** Message: `Document native runtime and agent
  upgrades; remove Docker`.

## Execution risk

Network access is required to download the JDK and the PP archives, which
total about 400 MB, cached after the first run. In the current session,
`curl` to Adoptium was denied by the permission layer. If Python's
`urllib` is blocked too, Task 1 Step 1 and every real build need the owner
to allow network access, or to supply the three archives through
`--inputs` or `PP_PAL_ARCHIVE_CACHE`.
