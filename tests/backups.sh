#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/backups.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_RUNTIME=$1; fi
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fail_with() {
  local expected=$1; shift
  set +e
  "$launcher" "$@" > "$work/rejected-output" 2> "$work/rejected-error"
  local status=$?
  set -e
  test "$status" = "$expected"
  rg -q '^ERROR:' "$work/rejected-error"
}
mkdir -p "$work/working directory"
cp "$root/fixtures/v1/portfolio.xml" "$work/working directory/portfolio_performance.xml"
chmod 600 "$work/working directory/portfolio_performance.xml"
cd "$work/working directory"
export PP_PAL_NOW=2026-03-31T21:59:59Z
"$launcher" holdings > "$work/holdings"
mapfile -t snapshots < <(find . -maxdepth 1 -name 'portfolio_performance.xml.pp-pal.monthly.2026-03.*.bak')
test "${#snapshots[@]}" = 1
cmp "${snapshots[0]}" "$root/fixtures/v1/portfolio.xml"
test "$(stat -c %u "${snapshots[0]}")" = "$(id -u)"
test "$(stat -c %a "${snapshots[0]}")" = 600
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
printf 'unchanged adjacent metadata\n' > portfolio_performance.xml.pp-pal.json
cp portfolio_performance.xml.pp-pal.json "$work/metadata-before"
index=portfolio_performance.xml.pp-pal-backups.properties
cp "$index" "$work/index-before"
"$launcher" holdings > "$work/holdings"
cmp "$work/index-before" "$index"
test "$(find . -maxdepth 1 -name 'portfolio_performance.xml.pp-pal.monthly.*.bak' | wc -l)" = 1
export PP_PAL_NOW=2026-03-31T22:00:00Z
"$launcher" holdings > "$work/holdings"
test "$(find . -maxdepth 1 -name 'portfolio_performance.xml.pp-pal.monthly.2026-04.*.bak' | wc -l)" = 1
cmp "${snapshots[0]}" "$root/fixtures/v1/portfolio.xml"
echo 'PASS launch protects the selected portfolio with an exact Berlin-month snapshot'
"$launcher" backup-before-update > "$work/update-result"
mapfile -t updates < <(find . -maxdepth 1 -name 'portfolio_performance.xml.pp-pal.update.*.bak')
test "${#updates[@]}" = 1
cmp "${updates[0]}" "$root/fixtures/v1/portfolio.xml"
"$launcher" backup-before-update > "$work/update-result"
mapfile -t updates < <(find . -maxdepth 1 -name 'portfolio_performance.xml.pp-pal.update.*.bak')
test "${#updates[@]}" = 2
cmp "${updates[0]}" "${updates[1]}"
test "$(stat -c %a "${updates[0]}")" = 600
echo 'PASS each upgrade attempt preserves an exact, nonoverwriting snapshot'

# Old tool-owned snapshots survive launch and upgrade protection. Only a later
# successful financial write is permitted to call the two-year pruning hook.
export PP_PAL_NOW=2023-01-15T12:00:00Z
"$launcher" backup-before-update > "$work/old-update-result"
old_update=$(cat "$work/old-update-result")
mapfile -t old_monthly < <(find . -maxdepth 1 -name 'portfolio_performance.xml.pp-pal.monthly.2023-01.*.bak')
test "${#old_monthly[@]}" = 1
printf 'unowned lookalike\n' > portfolio_performance.xml.pp-pal.monthly.2023-02.external.bak
export PP_PAL_NOW=2026-10-31T22:59:59Z
"$launcher" holdings > "$work/holdings"
test "$(find . -maxdepth 1 -name 'portfolio_performance.xml.pp-pal.monthly.2026-10.*.bak' | wc -l)" = 1
test "$(find . -maxdepth 1 -name 'portfolio_performance.xml.pp-pal.monthly.2026-09.*.bak' | wc -l)" = 0
export PP_PAL_NOW=2026-10-31T23:00:00Z
"$launcher" backup-before-update > "$work/update-result"
test "$(find . -maxdepth 1 -name 'portfolio_performance.xml.pp-pal.monthly.2026-11.*.bak' | wc -l)" = 1
cmp "$old_update" "$root/fixtures/v1/portfolio.xml"
cmp "${old_monthly[0]}" "$root/fixtures/v1/portfolio.xml"
test "$(cat portfolio_performance.xml.pp-pal.monthly.2023-02.external.bak)" = 'unowned lookalike'
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
cmp portfolio_performance.xml.pp-pal.json "$work/metadata-before"
test "$(find . -maxdepth 1 -name 'portfolio_performance.xml.????-??-??.bak' | wc -l)" = 0
test "$(find . -maxdepth 1 -name 'portfolio_performance.xml.pp-pal.migration.*.bak' | wc -l)" = 0
echo 'PASS launch catches up only the current month and never prunes old or unowned backups'

# Selected configuration/file paths and symlinked paths keep host semantics.
mkdir -p "$work/config location" "$work/portfolio location"
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio location/selected portfolio.xml"
printf 'file=../portfolio location/selected portfolio.xml\n' > "$work/config location/external.properties"
cp "$work/config location/external.properties" "$work/config-before"
ln -s "$work/config location/external.properties" config-link.properties
"$launcher" backup-before-update --config config-link.properties > "$work/config-backup"
mapfile -t configured < <(find "$work/portfolio location" -maxdepth 1 -name 'selected portfolio.xml.pp-pal.update.*.bak')
test "${#configured[@]}" = 1
cmp "${configured[0]}" "$root/fixtures/v1/portfolio.xml"
cmp "$work/config-before" "$work/config location/external.properties"
cp "$root/fixtures/v1/portfolio.xml" override.xml
"$launcher" backup-before-update --config config-link.properties --file override.xml > "$work/override-backup"
mapfile -t overridden < <(find . -maxdepth 1 -name 'override.xml.pp-pal.update.*.bak')
test "${#overridden[@]}" = 1
cmp "${overridden[0]}" "$root/fixtures/v1/portfolio.xml"
echo 'PASS backup selection preserves external configuration paths and explicit file precedence'

# Backup raw input without requiring the active reader to support its model.
printf '<future-model>raw immutable bytes</future-model>\n' > future.xml
"$launcher" backup-before-update --file future.xml > "$work/future-backup"
cmp future.xml "$(cat "$work/future-backup")"
fail_with 2 holdings --file future.xml
cmp future.xml "$(cat "$work/future-backup")"
echo 'PASS required upgrade protection does not depend on active model compatibility'

# Symlinks and existing foreign index files are never followed or overwritten.
cp "$root/fixtures/v1/portfolio.xml" unsafe.xml
printf 'outside marker\n' > "$work/target"
ln -s "$work/target" unsafe.xml.pp-pal-backups.properties
fail_with 3 backup-before-update --file unsafe.xml
test "$(cat "$work/target")" = 'outside marker'
test -L unsafe.xml.pp-pal-backups.properties
cp "$root/fixtures/v1/portfolio.xml" foreign.xml
printf 'foreign ownership file\n' > foreign.xml.pp-pal-backups.properties
cp foreign.xml.pp-pal-backups.properties "$work/foreign-before"
fail_with 3 backup-before-update --file foreign.xml
cmp foreign.xml.pp-pal-backups.properties "$work/foreign-before"
cp "$root/fixtures/v1/portfolio.xml" lock-link.xml
ln -s "$work/target" lock-link.xml.pp-pal-backups.lock
fail_with 3 backup-before-update --file lock-link.xml
test "$(cat "$work/target")" = 'outside marker'
echo 'PASS unrecognized ownership markers and symlinked backup paths stop protected operations'

# A replaced or removed required monthly snapshot is uncertainty, not permission
# to overwrite it or to continue the protected command.
cp "$root/fixtures/v1/portfolio.xml" tampered.xml
"$launcher" holdings --file tampered.xml > "$work/holdings"
mapfile -t tampered < <(find . -maxdepth 1 -name 'tampered.xml.pp-pal.monthly.*.bak')
printf 'changed by another writer\n' > "${tampered[0]}"
fail_with 3 backup-before-update --file tampered.xml
test "$(cat "${tampered[0]}")" = 'changed by another writer'
cmp tampered.xml "$root/fixtures/v1/portfolio.xml"
echo 'PASS changed required snapshots are preserved and refuse upgrade protection'

mkdir "$work/read only directory"
cp "$root/fixtures/v1/portfolio.xml" "$work/read only directory/selected.xml"
chmod 500 "$work/read only directory"
fail_with 3 holdings --file "$work/read only directory/selected.xml"
cmp "$work/read only directory/selected.xml" "$root/fixtures/v1/portfolio.xml"
chmod 700 "$work/read only directory"
test "$(find "$work/read only directory" -maxdepth 1 -name '*.bak' | wc -l)" = 0
cp "$root/fixtures/v1/portfolio.xml" readonly-index.xml
"$launcher" holdings --file readonly-index.xml > "$work/holdings"
chmod 400 readonly-index.xml.pp-pal-backups.properties
fail_with 3 backup-before-update --file readonly-index.xml
cmp readonly-index.xml "$root/fixtures/v1/portfolio.xml"
test "$(find . -maxdepth 1 -name 'readonly-index.xml.pp-pal.update.*.bak' | wc -l)" = 0
echo 'PASS required backup permission failures preserve original financial bytes'

# Competing wrapper writers share the same host-side POSIX advisory lock.
python3 - "$PWD/portfolio_performance.xml.pp-pal-backups.lock" "$work/lock-ready" "$work/lock-release" <<'PY' &
import fcntl, pathlib, sys, time
with open(sys.argv[1], 'r+') as lock:
    fcntl.lockf(lock, fcntl.LOCK_EX)
    pathlib.Path(sys.argv[2]).touch()
    while not pathlib.Path(sys.argv[3]).exists():
        time.sleep(0.05)
PY
locker=$!
for ((attempt=0; attempt<100; attempt++)); do
  [[ -e "$work/lock-ready" ]] && break
  sleep 0.05
done
test -e "$work/lock-ready"
fail_with 3 backup-before-update
touch "$work/lock-release"
wait "$locker"
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
echo 'PASS competing backup writers stop before the protected operation'
