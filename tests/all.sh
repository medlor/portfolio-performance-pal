#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
state=$(mktemp -d)
trap 'rm -rf "$state"' EXIT
export PP_PAL_STATE_DIR="$state"
bash tests/runtime.sh
bash tests/holdings.sh
bash tests/history.sh
bash tests/backups.sh
bash tests/updater.sh
