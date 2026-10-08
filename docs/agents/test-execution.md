# Test execution

## Choose the test boundary

Run broad financial behavior cases (amounts, historical balances, timestamps,
selectors, and persistence) through `tools/run-container`. In candidate gates,
use `PP_PAL_BEHAVIOR_LAUNCHER` to exercise the exact released container launcher.
Keep representative successful preview/apply cases through `PP_PAL_LAUNCHER`
(the owner `pp-pal` launcher), and keep release discovery, qualification identity,
stale-input, cache, and refusal cases in `tests/updater.py` through that launcher.
Keep the qualification-refusal section of `tests/purchase.sh` on the owner
launcher as well.
Follow the existing purchase, sale, security-creation, and PDF scripts when
combining these boundaries. Preserve all existing assertions and financial oracles.

## Qualification and cumulative coverage

Register fixture gates in `runtime/candidate-gates.json`. The updater runs
independent selected-copy validation before fixture qualification and checks the
original again before activation. Keep all gates mandatory; order cheap checks
first, with holdings before expensive PDF/security-creation gates. Preserve
readonly candidate input, isolated output, independent baseline evaluation,
and live-parent/exact-pair qualification checks. `SelectedPortfolioOracle.java`
compares both canonical roundtrips and financial relationships in one baseline
JVM after isolated candidate output generation (two containers total). Never add
a skip-update switch.

`bash tests/all.sh IMAGE` delegates cumulative execution to the updater suite:
its fresh run checks the baseline once and qualifies the released candidate once.
Standalone scripts remain available for focused development. Avoid adding gates
to `all.sh` that qualification already runs; account for baseline and candidate
coverage separately. The updater tests assert that each registered gate runs once
on successful qualification and that known attacks fail before expensive gates.

Invoke saved-model Java oracles through `tests/oracle.py` with their existing
Docker arguments. Cumulative baseline and candidate runs compile trusted oracle
sources once into temporary test-only classes, then reuse them through
`PP_PAL_TEST_CLASSES`. Focused scripts fall back to Java source mode. Candidate
qualification creates its own classes from the trusted bundle; candidate-owned
classes must never serve as the selected-portfolio oracle.

Keep gate start/end/elapsed output streaming through nested capture layers and
retain diagnostics for assertions and failures. Run the full cumulative suite
before committing execution changes, report elapsed time, and compare the gate
registry and existing assertion coverage to verify that no tests were removed.
Qualification runs the first cheap gate alone, then bounded pairs with two
workers. Each gate owns its state, update lock, qualification socket, and an
identical private release copy (qualification attacks mutate its contents);
trusted classes are shared readonly. Keep this isolation when adding parallel
work, and stop scheduling later gates after a failed batch.
