#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/all.sh" "$@"
fi
cd "$(dirname "$0")/.."
if [[ $# -gt 0 ]]; then export PP_PAL_RUNTIME=$1; fi
# Updater runs the distinct baseline checks and every registered candidate gate.
started=$SECONDS
python3 tests/concurrency.py
bash tests/updater.sh
printf 'SUITE PASS elapsed=%ss\n' "$((SECONDS-started))"
