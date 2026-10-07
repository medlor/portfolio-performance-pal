#!/usr/bin/env bash
set -euo pipefail
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
root=$(cd "$(dirname "$0")/.." && pwd)
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/working directory"
cp "$root/fixtures/v1/portfolio.xml" "$work/working directory/portfolio_performance.xml"
cd "$work/working directory"
"$launcher" holdings --limit 0 > "$work/actual"
cat > "$work/expected" <<'EXPECTED'
SECURITIES ACCOUNT | SECURITY | QUANTITY | HOLDING LAST UPDATED
Synthetic Securities A | Synthetic Euro Instrument | 6 | 2025-02-02T11:00:01
EXPECTED
diff -u "$work/expected" "$work/actual"
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
mkdir -p "$work/config location" "$work/portfolio location"
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio location/selected portfolio.xml"
cat > "$work/config location/external.properties" <<'CONFIG'
file=../portfolio location/selected portfolio.xml
securities-account=Synthetic Securities B
cash-account=Synthetic Cash B
CONFIG
cp "$work/config location/external.properties" "$work/config-before"
"$launcher" holdings --config "$work/config location/external.properties" --include-zero --limit 0 > "$work/actual"
cat > "$work/expected" <<'EXPECTED'
SECURITIES ACCOUNT | SECURITY | QUANTITY | HOLDING LAST UPDATED
Synthetic Securities B | Synthetic Dollar Instrument | 0 | 2025-02-03T09:30:01
EXPECTED
diff -u "$work/expected" "$work/actual"
cmp "$work/config-before" "$work/config location/external.properties"
"$launcher" holdings --config "$work/config location/external.properties" --file portfolio_performance.xml --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' > "$work/actual"
test "$(sed -n '2p' "$work/actual")" = 'Synthetic Securities A | Synthetic Euro Instrument | 6 | 2025-02-02T11:00:01'
"$launcher" configure --config "$work/config location/created.properties" --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' > "$work/config-result"
test "$(stat -c %u "$work/config location/created.properties")" = "$(id -u)"
rg -q '^securities-account=00000000-0000-4000-8000-000000000005$' "$work/config location/created.properties"
rg -q '^cash-account=00000000-0000-4000-8000-000000000003$' "$work/config location/created.properties"
cp "$work/config location/created.properties" pp-pal.properties
"$launcher" holdings > "$work/actual"
test "$(sed -n '2p' "$work/actual")" = 'Synthetic Securities A | Synthetic Euro Instrument | 6 | 2025-02-02T11:00:01'
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
mv pp-pal.properties "$work/discovered-config"
cp "$root/fixtures/v1/query-portfolio.xml" query.xml
printf 'untouched import metadata\n' > query.xml.pp-pal.json
cp query.xml.pp-pal.json "$work/metadata-before"
query() { "$launcher" holdings --file query.xml "$@"; }
query > "$work/actual"
test "$(wc -l < "$work/actual")" = 16
cat > "$work/expected-prefix" <<'EXPECTED'
SECURITIES ACCOUNT | SECURITY | QUANTITY | HOLDING LAST UPDATED
Other Depot | Movement Instrument | 4 | 2025-01-18T08:00:00
Twin Depot | Movement Instrument | 2 | 2025-01-16T08:00:00
Other Depot | Alpha Instrument | 2 | 2025-01-14T08:00:00
Twin Depot | Alpha Instrument | 6 | 2025-01-12T09:00:00
Twin Depot | Alpha Instrument | 3 | 2025-01-11T08:00:00
EXPECTED
head -n 6 "$work/actual" > "$work/actual-prefix"
diff -u "$work/expected-prefix" "$work/actual-prefix"
test "$(tail -n 1 "$work/actual")" = 'Other Depot | Tie 10 | 1 | 2024-01-01T08:00:00'
query --limit 0 > "$work/actual"
test "$(wc -l < "$work/actual")" = 22
test "$(tail -n 1 "$work/actual")" = 'Other Depot | Tie 16 | 1 | 2024-01-01T08:00:00'
query --include-zero --limit 0 > "$work/actual"
test "$(wc -l < "$work/actual")" = 23
test "$(sed -n '5p' "$work/actual")" = 'Twin Depot | Beta Instrument | 0 | 2025-01-13T08:00:00'
for selector in Alpha DE0000000001 TEST01 ALPHA 10000000-0000-4000-8000-000000000001; do
  query --security "$selector" --sort name --ascending --limit 0 > "$work/actual"
  cat > "$work/expected" <<'EXPECTED'
SECURITIES ACCOUNT | SECURITY | QUANTITY | HOLDING LAST UPDATED
Twin Depot | Alpha Instrument | 6 | 2025-01-12T09:00:00
Twin Depot | Alpha Instrument | 3 | 2025-01-11T08:00:00
Other Depot | Alpha Instrument | 2 | 2025-01-14T08:00:00
EXPECTED
  diff -u "$work/expected" "$work/actual"
done
query --sort quantity --limit 5 > "$work/actual"
cat > "$work/expected" <<'EXPECTED'
SECURITIES ACCOUNT | SECURITY | QUANTITY | HOLDING LAST UPDATED
Twin Depot | Alpha Instrument | 6 | 2025-01-12T09:00:00
Other Depot | Movement Instrument | 4 | 2025-01-18T08:00:00
Twin Depot | Alpha Instrument | 3 | 2025-01-11T08:00:00
Other Depot | Alpha Instrument | 2 | 2025-01-14T08:00:00
Twin Depot | Movement Instrument | 2 | 2025-01-16T08:00:00
EXPECTED
diff -u "$work/expected" "$work/actual"
query --sort updated --ascending --limit 2 > "$work/actual"
test "$(sed -n '2p' "$work/actual")" = 'Other Depot | Tie 01 | 1 | 2024-01-01T08:00:00'
test "$(sed -n '3p' "$work/actual")" = 'Other Depot | Tie 02 | 1 | 2024-01-01T08:00:00'
query --sort name --limit 2 > "$work/actual"
test "$(sed -n '2p' "$work/actual")" = 'Other Depot | Tie 16 | 1 | 2024-01-01T08:00:00'
test "$(sed -n '3p' "$work/actual")" = 'Other Depot | Tie 15 | 1 | 2024-01-01T08:00:00'
query --securities-account Other\ Depot --limit 0 > "$work/actual"
test "$(wc -l < "$work/actual")" = 19
query --securities-account 30000000-0000-4000-8000-000000000001 --limit 0 > "$work/actual"
test "$(wc -l < "$work/actual")" = 3
reject() {
  local rc=0
  "$launcher" "$@" > "$work/rejection" 2>&1 || rc=$?
  test "$rc" = 2 || { cat "$work/rejection" >&2; echo "Expected input rejection2, got $rc" >&2; exit 1; }
}
reject holdings --file query.xml --securities-account 'Twin Depot'
reject holdings --file query.xml --securities-account missing
reject holdings --file query.xml --cash-account missing
reject holdings --file query.xml --cash-account ''
reject holdings --file query.xml --limit -1
reject holdings --file query.xml --limit 1.5
reject holdings --file query.xml --sort invalid
reject holdings --file query.xml --unknown value
reject holdings --file query.xml --limit
reject holdings --config "$work/absent.properties"
reject --file query.xml configure --securities-account 'Other Depot'
sed 's/<version>70<\/version>/<version>69<\/version>/' query.xml > unsupported.xml
reject holdings --file unsupported.xml
sed 's/Other Cash/Shared Cash/' query.xml > ambiguous-cash.xml
reject holdings --file ambiguous-cash.xml --cash-account 'Shared Cash'
cp query.xml unreadable.xml
chmod 000 unreadable.xml
reject holdings --file unreadable.xml
chmod 600 unreadable.xml
chmod 444 query.xml
query --sort quantity --ascending --limit 1 > "$work/actual"
test "$(sed -n '2p' "$work/actual")" = 'Other Depot | Tie 01 | 1 | 2024-01-01T08:00:00'
test "$(stat -c %a query.xml)" = 444
ln -s "$work/config location" "$work/config alias"
"$launcher" holdings --config "$work/config alias/external.properties" --include-zero > "$work/actual"
test "$(sed -n '2p' "$work/actual")" = 'Synthetic Securities B | Synthetic Dollar Instrument | 0 | 2025-02-03T09:30:01'
ln -s "$work/config location/external.properties" pp-pal.properties
"$launcher" holdings --include-zero > "$work/actual"
test "$(sed -n '2p' "$work/actual")" = 'Synthetic Securities B | Synthetic Dollar Instrument | 0 | 2025-02-03T09:30:01'
mv pp-pal.properties "$work/discovered-alias"
cmp query.xml "$root/fixtures/v1/query-portfolio.xml"
cmp query.xml.pp-pal.json "$work/metadata-before"
test ! -e pp-pal.properties
mkdir "$work/help-bin"
for tool in bash cat dirname; do ln -s "$(command -v "$tool")" "$work/help-bin/$tool"; done
PATH="$work/help-bin" "$launcher" --config /absent --help > "$work/help"
rg -q '^Usage: pp-pal COMMAND' "$work/help"
echo 'PASS holdings controls, stable ties, transfer/delivery, configuration paths/IDs, host ownership, immutability, errors, Docker-free help'
