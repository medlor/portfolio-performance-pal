#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/history.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_RUNTIME=$1; fi
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/history directory"
cd "$work/history directory"
cp "$root/fixtures/v1/query-portfolio.xml" portfolio_performance.xml
cp "$root/fixtures/v1/history-portfolio.xml" history-portfolio.xml
printf 'unchanged sidecar\n' > portfolio_performance.xml.pp-pal.json
cp portfolio_performance.xml.pp-pal.json "$work/metadata-before"
python3 "$root/tests/oracle.py" "$PP_PAL_RUNTIME" DividendRelevanceOracle "$root/fixtures"/v1/history-portfolio.xml
"$launcher" history --security Beta --limit 0 > "$work/actual"
cat > "$work/expected" <<'EXPECTED'
DATE/TIME | TYPE | SECURITY | QUANTITY | RECORDED AMOUNT | SECURITIES ACCOUNT | CASH ACCOUNT
2025-01-21T11:00:00 | DIVIDEND | Beta Instrument | 0 | EUR 1.00 | Twin Depot [30000000-0000-4000-8000-000000000001] | Shared Cash
2025-01-13T08:00:00 | SALE | Beta Instrument | 5 | EUR 50.00 | Twin Depot [30000000-0000-4000-8000-000000000001] | Shared Cash
2025-01-09T08:00:00 | PURCHASE | Beta Instrument | 5 | EUR 50.00 | Twin Depot [30000000-0000-4000-8000-000000000001] | Shared Cash
EXPECTED
diff -u "$work/expected" "$work/actual"
"$launcher" history --file history-portfolio.xml --security Beta --limit 0 > "$work/actual"
diff -u "$work/expected" "$work/actual"
"$launcher" history --file history-portfolio.xml --security Alpha --type dividend --limit 0 > "$work/actual"
test "$(rg -c '^2025-01-20T11:00:00' "$work/actual")" = 2
test "$(wc -l < "$work/actual")" = 4
query() { "$launcher" history "$@"; }
query --security Alpha --type dividend --limit 0 > "$work/actual"
cat > "$work/expected" <<'EXPECTED'
DATE/TIME | TYPE | SECURITY | QUANTITY | RECORDED AMOUNT | SECURITIES ACCOUNT | CASH ACCOUNT
2025-01-20T11:00:00 | DIVIDEND | Alpha Instrument | 0 | EUR 1.00 | AMBIGUOUS: Twin Depot [30000000-0000-4000-8000-000000000001]; Twin Depot [30000000-0000-4000-8000-000000000002] | Shared Cash
2025-01-15T11:00:00 | DIVIDEND | Alpha Instrument | 0 | EUR 1.00 | Other Depot [30000000-0000-4000-8000-000000000003] | Other Cash
EXPECTED
diff -u "$work/expected" "$work/actual"
query --security Alpha --type dividend --securities-account 30000000-0000-4000-8000-000000000002 --limit 0 > "$work/actual"
head -n 2 "$work/expected" > "$work/shared-dividend"
diff -u "$work/shared-dividend" "$work/actual"
query --security Beta --securities-account 30000000-0000-4000-8000-000000000002 --limit 0 > "$work/actual"
test "$(wc -l < "$work/actual")" = 1
query --securities-account 'Other Depot' --type dividend --limit 0 > "$work/actual"
test "$(wc -l < "$work/actual")" = 2
test "$(sed -n '2p' "$work/actual")" = '2025-01-15T11:00:00 | DIVIDEND | Alpha Instrument | 0 | EUR 1.00 | Other Depot [30000000-0000-4000-8000-000000000003] | Other Cash'
query --from 2025-01-12 --to 2025-01-13 --ascending --limit 0 > "$work/actual"
cat > "$work/expected" <<'EXPECTED'
DATE/TIME | TYPE | SECURITY | QUANTITY | RECORDED AMOUNT | SECURITIES ACCOUNT | CASH ACCOUNT
2025-01-12T09:00:00 | SALE | Alpha Instrument | 4 | EUR 40.00 | Twin Depot [30000000-0000-4000-8000-000000000001] | Shared Cash
2025-01-13T08:00:00 | SALE | Beta Instrument | 5 | EUR 50.00 | Twin Depot [30000000-0000-4000-8000-000000000001] | Shared Cash
EXPECTED
diff -u "$work/expected" "$work/actual"
query --from 2025-01-13 --to 2025-01-13 --type sale > "$work/actual"
test "$(wc -l < "$work/actual")" = 2
test "$(sed -n '2p' "$work/actual")" = '2025-01-13T08:00:00 | SALE | Beta Instrument | 5 | EUR 50.00 | Twin Depot [30000000-0000-4000-8000-000000000001] | Shared Cash'
query --type purchase --ascending --limit 0 > "$work/actual"
test "$(wc -l < "$work/actual")" = 4
test "$(sed -n '2p' "$work/actual")" = '2025-01-09T08:00:00 | PURCHASE | Beta Instrument | 5 | EUR 50.00 | Twin Depot [30000000-0000-4000-8000-000000000001] | Shared Cash'
query > "$work/actual"
test "$(wc -l < "$work/actual")" = 16
test "$(sed -n '2p' "$work/actual")" = '2025-01-21T11:00:00 | DIVIDEND | Beta Instrument | 0 | EUR 1.00 | Twin Depot [30000000-0000-4000-8000-000000000001] | Shared Cash'
test "$(tail -n 1 "$work/actual")" = '2024-01-01T08:00:00 | DELIVERY_INBOUND | Tie 01 | 1 | EUR 10.00 | Other Depot [30000000-0000-4000-8000-000000000003] | —'
query --limit 0 > "$work/actual"
test "$(wc -l < "$work/actual")" = 31
test "$(tail -n 1 "$work/actual")" = '2024-01-01T08:00:00 | DELIVERY_INBOUND | Tie 16 | 1 | EUR 10.00 | Other Depot [30000000-0000-4000-8000-000000000003] | —'
query --ascending --limit 2 > "$work/actual"
test "$(sed -n '2p' "$work/actual")" = '2024-01-01T08:00:00 | DELIVERY_INBOUND | Tie 01 | 1 | EUR 10.00 | Other Depot [30000000-0000-4000-8000-000000000003] | —'
test "$(sed -n '3p' "$work/actual")" = '2024-01-01T08:00:00 | DELIVERY_INBOUND | Tie 02 | 1 | EUR 10.00 | Other Depot [30000000-0000-4000-8000-000000000003] | —'
query --sort date --from 2024-01-01 --to 2024-01-01 --limit 2 > "$work/actual"
test "$(sed -n '2p' "$work/actual")" = '2024-01-01T08:00:00 | DELIVERY_INBOUND | Tie 01 | 1 | EUR 10.00 | Other Depot [30000000-0000-4000-8000-000000000003] | —'
test "$(sed -n '3p' "$work/actual")" = '2024-01-01T08:00:00 | DELIVERY_INBOUND | Tie 02 | 1 | EUR 10.00 | Other Depot [30000000-0000-4000-8000-000000000003] | —'
for selector in DE0000000001 TEST01 ALPHA 10000000-0000-4000-8000-000000000001; do
  query --security "$selector" --type sale --limit 0 > "$work/actual"
  test "$(wc -l < "$work/actual")" = 2
  test "$(sed -n '2p' "$work/actual")" = '2025-01-12T09:00:00 | SALE | Alpha Instrument | 4 | EUR 40.00 | Twin Depot [30000000-0000-4000-8000-000000000001] | Shared Cash'
done
reject() {
  local rc=0
  query "$@" > "$work/error" 2>&1 || rc=$?
  test "$rc" = 2 || { cat "$work/error" >&2; echo "Expected history rejection2, got $rc" >&2; exit 1; }
}
reject --securities-account 'Twin Depot'
reject --from 2025-01-21 --to 2025-01-20
reject --from 2025-02-30
reject --to invalid
reject --sort quantity
reject --type interest
reject --limit -1
reject --limit 0.5
cat > pp-pal.properties <<'CONFIG'
file=portfolio_performance.xml
securities-account=30000000-0000-4000-8000-000000000001
cash-account=20000000-0000-4000-8000-000000000001
CONFIG
cp pp-pal.properties "$work/config-before"
query --security Alpha --limit 0 > "$work/actual"
test "$(wc -l < "$work/actual")" = 4
cmp pp-pal.properties "$work/config-before"
cmp portfolio_performance.xml "$root/fixtures/v1/query-portfolio.xml"
cmp history-portfolio.xml "$root/fixtures/v1/history-portfolio.xml"
cmp portfolio_performance.xml.pp-pal.json "$work/metadata-before"
echo 'PASS closed history, dividend ownership/relevance/dedup/ambiguity, date/type/identifier/account filters, ties/limits and immutable query'
