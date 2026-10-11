# Test execution performance

## Shared concurrency budget

Shell entry points remain available. `tests/run.py` supplies one Docker boundary
for the entire invocation, including nested modules, oracle compilation,
selected-file validation, and image builds. Fixture qualification started by
the owner launcher creates the same shared boundary if one is not inherited.

```sh
bash tests/all.sh pp-pal:0.88.0                       # conservative default: two jobs
PP_PAL_TEST_JOBS=1 bash tests/all.sh pp-pal:0.88.0    # serial modules and cases
PP_PAL_TEST_JOBS=3 bash tests/all.sh pp-pal:0.88.0    # request at most three jobs
```

The requested maximum is clamped to available memory after reserving at least
1 GiB or 25% of effective RAM, whichever is larger, and half the effective CPU
capacity. Linux cgroup v2 ancestor limits and CPU affinity participate in those
calculations. Admission pauses under host memory pressure and fails after 120
seconds if resources remain unavailable. Each active job's full memory ceiling
is reserved conservatively before another job starts.

| Variable | Default | Meaning |
|---|---:|---|
| `PP_PAL_TEST_JOBS` | 2 | Shared maximum expensive jobs across modules and cases |
| `PP_PAL_TEST_MEMORY_MIB` | 1024 | Hard memory limit per test container; swap disabled |
| `PP_PAL_TEST_CPUS` | 2 | CPU quota per test container, reduced on small hosts |
| `PP_PAL_TEST_PIDS` | 256 | Process/thread limit per test container |

Container jobs time out after 20 minutes. Suite-labelled containers are removed
on interruption; an abruptly killed Docker wrapper loses its socket lease, and
its container is removed before the slot becomes available again. Cleanup
targets only this invocation's containers. Builds hold all slots and run alone;
the existing BuildKit path has no hard build-memory/CPU cap. The Docker boundary
assumes the native local Docker daemon; host/cgroup measurements do not describe
a remote daemon or a separate Docker VM.

Holdings remains the prerequisite candidate gate. Other modules use a rolling
queue, replacing finished workers immediately. Independent case groups in
purchase rounding, sale application, PDF sale/dividend, and upstream PDF reuse
also use bounded queues. Each case keeps its own mutable files and performs
dependent steps sequentially. Failures stop admission and drain active work.
Updater scenarios that intentionally modify shared release/state remain serial.

`python3 tests/concurrency.py` checks scheduling, nested-process accounting,
exclusive builds, resource caps, parent cgroup limits, low-memory refusal,
serial mode, killed-worker cleanup, and shutdown without running real containers.
The cumulative suite includes these checks before the financial gates.

## Shared-budget benchmark (2026-10-11)

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
