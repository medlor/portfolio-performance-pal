# Test execution performance

## Shared concurrency budget

Shell entry points remain available. `tests/run.py` supplies one JVM budget
for the entire invocation, including nested modules, oracle compilation,
selected-file validation, and runtime builds. Every JDK process started through
`tools/engine` or `tools/build-runtime` is routed through the budget by the
`PP_PAL_JAVA_WRAPPER` shim that `tests/run.py` installs. Fixture qualification
started by the owner launcher creates the same shared budget if one is not inherited.

```sh
R=~/.local/state/pp-pal/runtimes/dev
bash tests/all.sh "$R"                       # default: half the logical CPUs, one per job
PP_PAL_TEST_JOBS=1 bash tests/all.sh "$R"    # serial modules and cases
PP_PAL_TEST_JOBS=3 bash tests/all.sh "$R"    # request at most three jobs
```

By default the budget runs as many jobs as half the effective logical CPUs,
with one processor per JVM. On an unrestricted 16-thread machine that is 8
jobs; inside a 4-CPU cgroup quota it is 2. "Effective" means CPU affinity and
cgroup v2 quotas (including ancestors) are applied, because starting more JVMs
than a quota allows only gets them throttled. Any requested maximum is clamped
to that CPU limit and to available memory, after reserving at least 1 GiB or 25%
of effective RAM, whichever is larger. Admission pauses under host memory pressure and fails after 120
seconds if resources remain unavailable. Each active job's full memory share
is reserved conservatively before another job starts.

| Variable | Default | Meaning |
|---|---:|---|
| `PP_PAL_TEST_JOBS` | half the effective logical CPUs | Shared maximum expensive jobs across modules and cases |
| `PP_PAL_TEST_MEMORY_MIB` | 1024 | Memory share per test JVM; the heap is capped at 75% of it (`-Xmx`) |
| `PP_PAL_TEST_CPUS` | 1 | Processors per test JVM (`-XX:ActiveProcessorCount`); measured: more gives no gain for these short runs |

Limits are JVM flags, not kernel ceilings. Rootless processes have no per-job
cgroup, so native memory outside the heap is not hard-capped and there is no
process/thread limit (the former per-container pids limit has no rootless
equivalent). Callers cannot pass their own `-Xmx`, `-XX:MaxRAM*` or
`-XX:ActiveProcessorCount` (`-J…` for `javac`). Application arguments after
the main class or source file are not inspected.

JVM jobs time out after 20 minutes. Each job runs in its own process group. An
abruptly killed client loses its socket lease, and its process group is stopped
(SIGTERM, then SIGKILL after 5 seconds) before the slot becomes available again.
Interruption stops only this invocation's process groups. Builds hold all slots,
run alone, and get no heap ceiling. Because a build is a single JVM, it is admitted
once 2 GiB beyond the host reserve is free, not one job share per slot.

Holdings remains the prerequisite candidate gate. Other modules use a rolling
queue, replacing finished workers immediately. Independent case groups in
purchase rounding, sale application, PDF sale/dividend, and upstream PDF reuse
also use bounded queues. Each case keeps its own mutable files and performs
dependent steps sequentially. Failures stop admission and drain active work.
Updater scenarios that intentionally modify shared release/state remain serial.

`python3 tests/concurrency.py` checks scheduling, nested-process accounting,
exclusive builds, resource caps, parent cgroup limits, low-memory refusal,
serial mode, killed-worker cleanup, and shutdown with a stub JDK tool instead of real JVMs.
The cumulative suite includes these checks before the financial gates.

## JVM start-up

Most of a command's half second is class loading from the roughly one
thousand upstream jars. Each runtime therefore ships `classes.jar`, whose
manifest `Class-Path` lists every jar in a fixed order, and an AppCDS archive
`app.jsa` trained at build time (`tools/build-runtime` step 11). Measured on
2026-10-27 under load: holdings dropped from about 730 to 545 ms, and PDF
conversion from about 800 to 530 ms. `-XX:+UseSerialGC`, `-XX:TieredStopAtLevel=1`
and larger `-XX:ActiveProcessorCount` values gave no gain. Pruning jars by
static analysis broke PDF conversion and is not used.

## Shared-budget benchmark (2026-10-11, historical Docker runtime)

Both cumulative runs passed against the same local `pp-pal:0.88.0` image on a
16-logical-CPU Linux host with approximately 15.2 GiB RAM. The unmodified run
used commit `b28ca4b`; the new run used `PP_PAL_TEST_JOBS=2`. No additional
`JOBS=1` comparison was run.

| Cumulative suite | Elapsed |
|---|---:|
| Unmodified scheduling | 1909 seconds (31m 49s) |
| Shared budget, two jobs | 1642 seconds (27m 22s) |
| Saved | 267 seconds (4m 27s), 14.0% |

The new run completed 2158 expensive jobs, with a maximum of **two shared slots**
across all modules and cases. Kernel accounting verified the test-container
limits: 1 GiB RAM, two CPUs, 256 processes/threads, and zero swap. Sampling every
100 ms observed at most two such containers, 181.8 MiB peak memory per container,
326.5 MiB combined current memory, and 13 processes/threads per container. Host
available memory stayed above 9.05 GiB. These are sampled observations, not
guaranteed workload peaks; BuildKit builds are excluded from the capped-container
measurements and remain exclusive without hard memory/CPU caps.

All four baseline gates and all 16 registered candidate gates passed. The
registry, fixtures, and Java oracle sources are unchanged. Existing shell/Python
assertion/case expressions increased from 700 to 704; this counts source
expressions, not dynamically executed cases. Fifteen new infrastructure tests
also passed in the cumulative run. Focused checks of the changed case groups
passed first (379.82 seconds), and shell/Python syntax and whitespace checks
passed. The full suite also verified scheduling-helper tamper refusal.

These are successive local runs, not cold-cache or isolated-load benchmarks;
Docker cache and host load may differ. In particular, per-gate timings include
time waiting for the shared budget and should not be summed as wall time.

## Historical benchmark (2026-10-08)

Validated on 2026-10-08 with `bash tests/all.sh pp-pal:issue26` (exit 0).
The complete run took **880 seconds (14 minutes 40 seconds)**. The supplied
issue #26 report recorded approximately 88 minutes: an observed reduction of
about 83%. These runs were not a controlled isolated benchmark; load and Docker
cache conditions can differ.

### Execution changes

Broad financial cases use the real container launcher after representative owner
preview/apply integrations. Release and qualification refusal cases still use
the owner launcher. Independent selected-copy validation and stale-input checks
run before fixture qualification; holdings runs first. Remaining gates execute
in bounded pairs with private state, locks, sockets, and identical release copies.
Trusted test oracle sources compile once per baseline/candidate qualification.
Progress and elapsed times stream through both capture layers.

The cumulative updater test runs the original four baseline gates once and all
nine candidate fixture gates once for successful activation. Failed selected-file
attacks execute no fixture gates. Both wrong-quantity retries stopped at holdings
in 2.21/2.22 seconds, before expensive gates.

### Independent selected-file validation

The candidate generates disposable output with readonly input and no trusted
validator/artifact mounts. One baseline JVM then performs both canonical
roundtrip and relationship comparisons using trusted source. This reduces five
containers to two. A focused unchanged-pair probe took **3.27 seconds**, compared
with the supplied report's 7.26 seconds. Separate actual-container probes verified
refusal when candidate output changes an unrelated persisted setting or security
metadata. All original stale-input and release checks remain mandatory.

### Coverage and checks

A comparison against the pre-change commit retained every registered gate and
all **454 existing shell/Python assertion/case expressions**, including embedded
Python. Five assertion/case expressions were added. Loop-based scenarios and
existing Java oracle sources were retained; the expression count is not a count
of dynamically executed cases. Baseline coverage was relocated, rather than
removed. The complete suite additionally confirmed activation, financial
preview/application, exact qualification identity, cache reuse, readonly-input
isolation, changed archives/bootstrap, tampering, safe retries, stale inputs,
retained engines, and interrupted updates. Python compilation, syntax checks
for every shell script, and `git diff --check` passed.

### Successful candidate gate durations

Two gates can overlap, so these durations should not be added to obtain wall time.

| Gate | Seconds |
|---|---:|
| `tests/holdings.sh` | 75.37 |
| `tests/runtime.sh` | 15.01 |
| `tests/history.sh` | 66.37 |
| `tests/backups.sh` | 48.93 |
| `tests/purchase.sh` | 132.51 |
| `tests/security-create.sh` | 67.31 |
| `tests/purchase-apply.sh` | 101.19 |
| `tests/pdf-purchase.sh` | 94.50 |
| `tests/sale.sh` | 127.56 |
