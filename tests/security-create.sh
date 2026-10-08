#!/usr/bin/env bash
set -euo pipefail
export PP_PAL_IMAGE=${1:-${PP_PAL_IMAGE:-pp-pal:0.88.0}}
root=$(cd "$(dirname "$0")/.." && pwd)
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio.xml"
cd "$work"
export PP_PAL_NOW=2026-10-08T12:00:00Z
buy() { "$launcher" purchase --file portfolio.xml --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 2.5 --price 10 --fees 1 --taxes .5 "$@"; }
buy --create-security --name 'New Synthetic Instrument' --currency EUR > preview
rg -Fq ' (create)' preview
rg -Fq 'SECURITY New Synthetic Instrument |' preview
rg -Fq 'BOOKED DEBIT EUR 26.50' preview
cmp portfolio.xml "$root/fixtures/v1/portfolio.xml"
test "$(find . -name '*.????-??-??.bak' | wc -l)" = 0
echo 'PASS explicit identifier-free security creation preview preserves financial input'
buy --create-security --name 'New Synthetic Instrument' --currency EUR --apply --desktop-closed > applied
rg -q '^APPLIED PURCHASE$' applied
cmp portfolio.xml.2026-10-08.bak "$root/fixtures/v1/portfolio.xml"
python3 "$root/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" -v "$work:/work:ro" --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/SecurityCreatedOracle.java /fixtures/v1/portfolio.xml /work/portfolio.xml
echo 'PASS persisted identifier-free definition and linked purchase reload with pinned core'
# Broad behavior uses the real container boundary after the owner integration case.
launcher=${PP_PAL_BEHAVIOR_LAUNCHER:-$root/tools/run-container}
cp "$root/fixtures/v1/portfolio.xml" reuse.xml
"$launcher" purchase --file reuse.xml --create-security --name 'Requested broker spelling' --currency EUR --isin DE0000000001 --wkn TEST01 --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 2.5 --price 10 --fees 1 --taxes .5 > reused-preview
rg -Fq 'SECURITY Synthetic Euro Instrument | 00000000-0000-4000-8000-000000000001 | EUR (existing)' reused-preview
"$launcher" purchase --file reuse.xml --create-security --name 'Requested broker spelling' --currency EUR --isin DE0000000001 --wkn TEST01 --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 2.5 --price 10 --fees 1 --taxes .5 --apply --desktop-closed > reused
python3 "$root/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" -v "$work:/work:ro" --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/PurchaseAppliedOracle.java /fixtures/v1/portfolio.xml /work/reuse.xml 1
cp "$root/fixtures/v1/portfolio.xml" failed.xml
reject() {
  local rc=0
  "$launcher" purchase --file failed.xml --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 2.5 --price 10 --fees 1 --taxes .5 --apply --desktop-closed "$@" > error 2>&1 || rc=$?
  test "$rc" = 2 || { cat error >&2; echo "Expected creation rejection 2, got $rc" >&2; exit 1; }
  cmp failed.xml "$root/fixtures/v1/portfolio.xml"
  test ! -e failed.xml.2026-10-08.bak
}
reject --create-security --currency EUR
reject --create-security --name 'Missing currency'
reject --create-security --name ' ' --currency EUR
reject --create-security --name New --currency INVALID
reject --create-security --name New --currency USD
reject --create-security --name New --currency EUR --total 1
reject --create-security --name New --currency EUR --security-id missing
reject --name New --currency EUR
reject --create-security --name New --currency EUR --isin DE0000000001 --wkn NEW001
reject --create-security --name New --currency EUR --isin DE0000000001 --wkn TEST02
reject --create-security --name New --currency EUR --security unknown
reject --create-security --name New --currency EUR --ticker ' '
cp "$root/fixtures/v1/ambiguous-purchase.xml" ambiguous.xml
rc=0
"$launcher" purchase --file ambiguous.xml --create-security --name New --currency EUR --isin DE0000000001 --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 1 --price 10 --apply --desktop-closed > error 2>&1 || rc=$?
test "$rc" = 2
rg -Fq 'Ambiguous security' error
cmp ambiguous.xml "$root/fixtures/v1/ambiguous-purchase.xml"
echo 'PASS external identifiers reuse retained definitions; missing/conflicting/ambiguous definitions cannot write'

cp "$root/fixtures/v1/portfolio.xml" identifiers.xml
"$launcher" purchase --file identifiers.xml --create-security --name 'New Synthetic Instrument' --currency eur --isin de0000000099 --wkn new099 --ticker new.syn --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 2.5 --price 10 --fees 1 --taxes .5 --apply --desktop-closed > identifiers-applied
python3 "$root/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" -v "$work:/work:ro" --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/SecurityCreatedOracle.java /fixtures/v1/portfolio.xml /work/identifiers.xml identifiers
cp identifiers.xml identifiers-before
"$launcher" purchase --file identifiers.xml --create-security --name 'Requested broker spelling' --currency EUR --isin DE0000000099 --wkn NEW099 --ticker NEW.SYN --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 1 --price 10 > created-reuse
rg -Fq 'SECURITY New Synthetic Instrument |' created-reuse
rg -Fq ' (existing)' created-reuse
cmp identifiers.xml identifiers-before
"$launcher" purchase --file ambiguous.xml --create-security --name New --currency EUR --isin DE0000000001 --security-id 00000000-0000-4000-8000-000000000001 --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --time 00:00:00 --quantity 1 --price 10 > disambiguated
rg -Fq '| 00000000-0000-4000-8000-000000000001 | EUR (existing)' disambiguated
rg -Fq 'DATE 2025-03-01T00:00:00 (supplied date and time)' disambiguated
cmp ambiguous.xml "$root/fixtures/v1/ambiguous-purchase.xml"
echo 'PASS optional identifier definitions persist and can be reused; stable IDs disambiguate and explicit midnight survives'
