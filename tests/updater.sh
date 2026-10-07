#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mode=()
if [[ ${2:-} == --arguments-only || ${2:-} == --production-prerequisite ]]; then mode+=("$2")
elif [[ $# -gt 1 ]]; then export PP_PAL_UPDATE_GATE_WORK=$2; fi
python3 -u tests/updater.py "${1:-${PP_PAL_IMAGE:-pp-pal:0.88.0}}" "${mode[@]}"
