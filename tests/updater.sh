#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/updater.sh" "$@"
fi
cd "$(dirname "$0")/.."
mode=()
if [[ ${2:-} == --arguments-only || ${2:-} == --production-prerequisite || ${2:-} == --purchase-slice || ${2:-} == --apply-slice || ${2:-} == --security-slice || ${2:-} == --pdf-slice ]]; then mode+=("$2")
elif [[ $# -gt 1 ]]; then export PP_PAL_UPDATE_GATE_WORK=$2; fi
python3 -u tests/updater.py "${1:-${PP_PAL_IMAGE:-pp-pal:0.88.0}}" "${mode[@]}"
