#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
bash tests/runtime.sh
bash tests/holdings.sh
bash tests/history.sh
bash tests/backups.sh
