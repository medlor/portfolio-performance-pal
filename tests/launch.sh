#!/usr/bin/env bash
# Native launcher contract: pinned JDK only, path handling, runtime selection
# errors and engine identity in previews.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/launch.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_RUNTIME=$1; fi
runtime=$(realpath -- "${PP_PAL_RUNTIME:?Select the runtime under test}")
identity=$(python3 "$root/tools/runtime_dir.py" identity "$runtime")
work=$(mktemp -d)
trap 'chmod -R u+w "$work" 2>/dev/null; rm -rf "$work"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
launch() { PP_PAL_STATE_DIR="$work/state" PP_PAL_NOW=2026-10-07T12:00:00Z "$root/tools/run-engine" "$@"; }
expect_status() { # expect_status STATUS LABEL COMMAND...
  local want=$1 label=$2 status=0
  shift 2
  "$@" > "$work/out" 2> "$work/err" || status=$?
  [[ $status == "$want" ]] || { cat "$work/err" >&2; fail "$label: exit $status, expected $want"; }
}

# (a) A host java first on PATH is never used; colon and space paths work.
mkdir -p "$work/fake" "$work/a:b c"
printf '#!/bin/sh\necho HOST-JAVA-USED >&2\nexit 99\n' > "$work/fake/java"
chmod +x "$work/fake/java"
cp "$root/fixtures/v1/portfolio.xml" "$work/a:b c/portfolio.xml"
cd "$work/a:b c"
expect_status 0 'colon/space path with fake host java' env PATH="$work/fake:$PATH" PP_PAL_RUNTIME="$runtime" \
  PP_PAL_STATE_DIR="$work/state" PP_PAL_NOW=2026-10-07T12:00:00Z "$root/tools/run-engine" holdings --file "$work/a:b c/portfolio.xml"
! rg -q HOST-JAVA-USED "$work/err" || fail 'host java was used'
[[ -s $work/out ]] || fail 'holdings printed nothing'

# (b) A newline in the portfolio path is refused.
newline_dir="$work/new"$'\n'"line"
mkdir "$newline_dir"
cp "$root/fixtures/v1/portfolio.xml" "$newline_dir/portfolio.xml"
expect_status 2 'newline path' launch holdings --file "$newline_dir/portfolio.xml"

# (c) A missing runtime names the build command.
expect_status 3 'missing runtime' env PP_PAL_RUNTIME="$work/missing" "$root/tools/run-engine" holdings --file "$work/a:b c/portfolio.xml"
rg -q 'build it with tools/build-runtime' "$work/err" || fail 'missing runtime message'

# (d) A renamed runtime (identity differs from its directory name) is refused.
mkdir "$work/renamed"
cp -r "$runtime" "$work/renamed/other"
expect_status 3 'renamed runtime' env PP_PAL_RUNTIME="$work/renamed/other" "$root/tools/run-engine" holdings --file "$work/a:b c/portfolio.xml"

# (e) Previews identify the engine runtime, not an image.
expect_status 0 'purchase preview' launch purchase --file "$work/a:b c/portfolio.xml" \
  --create-security --name 'Engine identity probe' --currency EUR --isin DE0000000001 \
  --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 1 --price 10
rg -qx "ENGINE RUNTIME $identity" "$work/out" || { cat "$work/out" >&2; fail 'preview lacks ENGINE RUNTIME identity'; }
! rg -q 'ENGINE IMAGE' "$work/out" || fail 'preview still prints ENGINE IMAGE'

# (g) A copied runtime cannot map its class-data-sharing archive; it must still
# run, with no JVM sharing warnings in the command's output.
mkdir "$work/copied"
cp -r "$runtime" "$work/copied/$identity"
expect_status 0 'copied runtime' env PP_PAL_RUNTIME="$work/copied/$identity" PP_PAL_STATE_DIR="$work/state" \
  PP_PAL_NOW=2026-10-07T12:00:00Z "$root/tools/run-engine" holdings --file "$work/a:b c/portfolio.xml"
! rg -qi 'warning|shared archive|cds' "$work/err" || { cat "$work/err" >&2; fail 'copied runtime printed JVM sharing warnings'; }

# (f) Help needs neither a runtime nor a portfolio.
expect_status 0 'help without runtime' env PP_PAL_RUNTIME="$work/missing" "$root/tools/run-engine" --help
cmp -s "$root/runtime/help.txt" "$work/out" || fail 'help output differs'
echo PASS
