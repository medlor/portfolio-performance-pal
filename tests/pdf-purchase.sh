#!/usr/bin/env bash
set -euo pipefail
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
root=$(cd "$(dirname "$0")/.." && pwd)
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio_performance.xml"
chmod 600 "$work/portfolio_performance.xml"
printf 'opaque PDF metadata sentinel\n' > "$work/portfolio_performance.xml.pp-pal.json"
cp "$work/portfolio_performance.xml.pp-pal.json" "$work/metadata-before"
printf 'preserved daily backup\n' > "$work/portfolio_performance.xml.2020-01-01.bak"
printf 'preserved migration backup\n' > "$work/portfolio_performance.xml.pp-pal.migration.2020.bak"
cp "$work/portfolio_performance.xml.2020-01-01.bak" "$work/daily-before"
cp "$work/portfolio_performance.xml.pp-pal.migration.2020.bak" "$work/migration-before"
mkdir "$work/input directory"
cp "$root/fixtures/v1/baader-buy.pdf" "$work/input directory/purchase statement.pdf"
cd "$work"
mkdir trace-bin
export PP_PAL_REAL_DOCKER=$(command -v docker) PP_PAL_DOCKER_TRACE="$work/docker-trace.jsonl"
cat > trace-bin/docker <<'PY'
#!/usr/bin/env python3
import json, os, sys
with open(os.environ['PP_PAL_DOCKER_TRACE'],'a') as stream:
    stream.write(json.dumps(sys.argv[1:])+'\n')
os.execv(os.environ['PP_PAL_REAL_DOCKER'],['docker',*sys.argv[1:]])
PY
chmod +x trace-bin/docker
export PATH="$work/trace-bin:$PATH"
"$launcher" import --pdf 'input directory/purchase statement.pdf' --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' > preview
rg -q '^PREVIEW PDF PURCHASE DOCUMENT' preview
rg -Fq 'BOOKED DEBIT EUR 51.00 (PDF)' preview
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
cmp 'input directory/purchase statement.pdf' "$root/fixtures/v1/baader-buy.pdf"
python3 - "$work" <<'PY'
import json, os, pathlib, sys
work=pathlib.Path(sys.argv[1]); path=str(work/'input directory/purchase statement.pdf')
commands=[json.loads(line) for line in (work/'docker-trace.jsonl').read_text().splitlines()]
runs=[command for command in commands if command[0]=='run']
assert runs and all(command[command.index('--user')+1]==f'{os.getuid()}:{os.getgid()}' for command in runs)
assert any(path+':'+path+':ro' in command for command in runs), 'PDF needs an explicit individual read-only bind despite writable portfolio parent'
assert not any(path+':'+path+':rw' in command for command in runs)
PY
echo 'PASS owner PDF purchase preview converts the actual statement and preserves inputs'
cp "$root/fixtures/v1/pdf-purchase/missing-fee.pdf" 'input directory/missing fee.pdf'
"$launcher" import --pdf 'input directory/missing fee.pdf' --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --fees 1 > supplemented-preview
rg -Fq 'FEES EUR 1.00 (SUPPLEMENT)' supplemented-preview
rg -Fq 'BOOKED DEBIT EUR 51.00 (PDF)' supplemented-preview
set +e
"$launcher" import --pdf 'input directory/purchase statement.pdf' --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --time 25:00 > rejected 2> error
status=$?
set -e
[[ $status == 2 ]]
! rg -q '^PREVIEW PDF PURCHASE DOCUMENT' rejected
echo 'PASS missing costs retain supplement origins and invalid supplemental time is input rejection'
cp "$root/fixtures/v1/pdf-purchase/paged.pdf" 'input directory/paged purchase.pdf'
"$launcher" import --pdf 'input directory/paged purchase.pdf' --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' > paged-preview
rg -Fq 'BOOKED DEBIT EUR 51.00 (PDF)' paged-preview
rg -Fq 'DATE 2025-01-03T09:30:00 (PDF date and time)' paged-preview
rg -Fq 'QUANTITY 2 (PDF)' paged-preview
rg -Fq 'PRICE EUR 25 (PDF)' paged-preview
rg -Fq 'GROSS EUR 50.00 (PDF)' paged-preview
rg -Fq 'FEES EUR 1.00 (PDF)' paged-preview
rg -Fq 'TAXES EUR 0.00 (default zero)' paged-preview
rg -Fq 'SECURITY Synthetic Euro Instrument | 00000000-0000-4000-8000-000000000001 | EUR (existing)' paged-preview
rg -Fq 'Vorgangs-Nr.: SYNTHETIC-PAGED-0001' paged-preview
echo 'PASS source-scoped paged Baader purchase preserves explicit statement time and booked debit'
preview_pdf() {
  local fixture=$1; shift
  "$launcher" import --pdf "$root/fixtures/v1/pdf-purchase/$fixture.pdf" --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' "$@" > current-preview
}
reject_pdf() {
  local fixture=$1 rc=0; shift
  preview_pdf "$fixture" "$@" > rejected 2> error || rc=$?
  [[ $rc == 2 ]] || { cat error >&2; echo "Expected PDF rejection2: $fixture, got $rc" >&2; exit 1; }
  ! rg -q '^PREVIEW PDF PURCHASE DOCUMENT' current-preview
}
preview_pdf missing-time
rg -Fq 'DATE 2025-01-06T11:00:00 (PDF date; fallback time 11:00:00)' current-preview
preview_pdf midnight
rg -Fq 'DATE 2025-01-03T00:00:00 (PDF date and time)' current-preview
preview_pdf missing-time --time 00:00:00
rg -Fq 'DATE 2025-01-06T00:00:00 (PDF date; SUPPLEMENT time)' current-preview
preview_pdf missing-quantity-price --quantity 2 --price 25
rg -Fq 'QUANTITY 2 (SUPPLEMENT)' current-preview
rg -Fq 'PRICE EUR 25 (SUPPLEMENT)' current-preview
preview_pdf missing-booked --total 51
rg -Fq 'BOOKED DEBIT EUR 51.00 (SUPPLEMENT)' current-preview
preview_pdf two-purchases
rg -Fq 'PREVIEW PDF PURCHASE DOCUMENT (2 members)' current-preview
rg -Fq 'BOOKED DEBIT EUR 51.00 (PDF)' current-preview
rg -Fq 'BOOKED DEBIT EUR 76.00 (PDF)' current-preview
rg -Fq 'DATE 2025-01-04T09:30:00 (PDF date and time)' current-preview
for fixture in missing-fee missing-booked missing-quantity-price contradictory-gross zero-quantity excess-quantity-precision conflicting-identity unsupported-sale unsupported-bank unknown-trailing repeated-fee bad-time unsupported-continuation page-marker-on-wrong-page later-missing-reference later-unsupported-sale later-unknown-line; do reject_pdf "$fixture"; done
reject_pdf paged --fees 2
reject_pdf paged --isin US0000000002
reject_pdf two-purchases --fees 1
cp "$root/fixtures/v1/ambiguous-purchase.xml" ambiguous.xml
reject_pdf paged --file ambiguous.xml
preview_pdf paged --file ambiguous.xml --security-id 00000000-0000-4000-8000-000000000001
rg -Fq '| 00000000-0000-4000-8000-000000000001 |' current-preview
cmp ambiguous.xml "$root/fixtures/v1/ambiguous-purchase.xml"
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
cmp portfolio_performance.xml.pp-pal.json metadata-before
cmp portfolio_performance.xml.2020-01-01.bak daily-before
cmp portfolio_performance.xml.pp-pal.migration.2020.bak migration-before
[[ $(stat -c %a portfolio_performance.xml) == 600 ]]
[[ $(find . -name '*.????-??-??.bak' | wc -l) == 1 ]]
[[ $(find . -name '*.pp-pal.migration.*.bak' | wc -l) == 1 ]]
echo 'PASS section-scoped times, missing-only supplements, all-member coverage, exact monetary evidence and joint security identity'
docker run --rm --network none --user "$(id -u):$(id -g)" -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/PdfTextOracle.java /fixtures/v1/pdf-purchase
docker run --rm --network none --user "$(id -u):$(id -g)" -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/PdfPurchaseOracle.java /fixtures/v1/portfolio.xml /fixtures/v1/pdf-purchase/two-purchases.pdf
