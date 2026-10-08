#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
# Updater runs the distinct baseline checks and every registered candidate gate.
started=$SECONDS
bash tests/updater.sh
printf 'SUITE PASS elapsed=%ss\n' "$((SECONDS-started))"
