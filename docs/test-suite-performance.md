# Test execution performance

Validated on 2026-10-08 with `bash tests/all.sh pp-pal:issue26` (exit 0).
The complete run took **880 seconds (14 minutes 40 seconds)**. The supplied
issue #26 report recorded approximately 88 minutes: an observed reduction of
about 83%. These runs were not a controlled isolated benchmark; load and Docker
cache conditions can differ.

## Execution changes

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

## Independent selected-file validation

The candidate generates disposable output with readonly input and no trusted
validator/artifact mounts. One baseline JVM then performs both canonical
roundtrip and relationship comparisons using trusted source. This reduces five
containers to two. A focused unchanged-pair probe took **3.27 seconds**, compared
with the supplied report's 7.26 seconds. Separate actual-container probes verified
refusal when candidate output changes an unrelated persisted setting or security
metadata. All original stale-input and release checks remain mandatory.

## Coverage and checks

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

## Successful candidate gate durations

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
