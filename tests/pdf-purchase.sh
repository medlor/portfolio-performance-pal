#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/pdf-purchase.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_RUNTIME=$1; fi
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
# Trace every JDK process the launcher starts, then continue through the test budget.
export PP_PAL_JAVA_TRACE="$work/java-trace.jsonl" PP_PAL_TRACE_NEXT=${PP_PAL_JAVA_WRAPPER:-}
cat > trace-bin/java-trace <<'PY'
#!/usr/bin/env python3
import json, os, sys
with open(os.environ['PP_PAL_JAVA_TRACE'],'a') as stream:
    stream.write(json.dumps(sys.argv[1:])+'\n')
following = os.environ.get('PP_PAL_TRACE_NEXT')
command = [following, *sys.argv[1:]] if following else sys.argv[1:]
os.execv(command[0], command)
PY
chmod +x trace-bin/java-trace
export PP_PAL_JAVA_WRAPPER="$work/trace-bin/java-trace"
"$launcher" import --pdf 'input directory/purchase statement.pdf' --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' > preview
rg -q '^PREVIEW PDF PURCHASE DOCUMENT' preview
rg -Fq 'BOOKED DEBIT EUR 51.00 (PDF)' preview
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
cmp 'input directory/purchase statement.pdf' "$root/fixtures/v1/baader-buy.pdf"
cmp portfolio_performance.xml.pp-pal.json metadata-before
python3 - "$work" <<'PY'
import json, pathlib, sys
work=pathlib.Path(sys.argv[1]); path=str(work/'input directory/purchase statement.pdf')
commands=[json.loads(line) for line in (work/'java-trace.jsonl').read_text().splitlines()]
runs=[command for command in commands if 'pal.Main' in command and '--pdf' in command]
assert runs, 'PDF preview did not start the engine'
# The launcher hands the engine the canonical absolute statement path, never a relative one.
assert all(command[command.index('--pdf')+1]==path for command in runs), runs
PY
export PP_PAL_JAVA_WRAPPER=$PP_PAL_TRACE_NEXT
[[ -n $PP_PAL_JAVA_WRAPPER ]] || unset PP_PAL_JAVA_WRAPPER
echo 'PASS owner PDF purchase preview converts the actual statement and preserves inputs'
# Broad behavior uses the real container boundary after the owner integration case.
launcher=${PP_PAL_BEHAVIOR_LAUNCHER:-$root/tools/run-engine}
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
rg -Fq 'PRICE EUR 25 (PP derived from gross / quantity)' paged-preview
rg -Fq 'GROSS EUR 50.00 (PP derived from booked and costs)' paged-preview
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
reject_pdf missing-quantity-price --quantity 2 --price 25
reject_pdf missing-booked --total 51
preview_pdf two-purchases
rg -Fq 'PREVIEW PDF PURCHASE DOCUMENT (2 members)' current-preview
rg -Fq 'BOOKED DEBIT EUR 51.00 (PDF)' current-preview
rg -Fq 'BOOKED DEBIT EUR 76.00 (PDF)' current-preview
rg -Fq 'DATE 2025-01-04T09:30:00 (PDF date and time)' current-preview
for fixture in missing-booked missing-quantity-price zero-quantity excess-quantity-precision conflicting-identity unsupported-bank bad-time page-marker-on-wrong-page later-missing-reference; do reject_pdf "$fixture"; done
# PP intentionally ignores these fields/lines, or supports the complete continuation.
# Its authoritative results are checked against direct PP extraction in upstream-reuse.sh.
for fixture in missing-fee contradictory-gross unsupported-sale unknown-trailing repeated-fee unsupported-continuation later-unsupported-sale later-unknown-line; do preview_pdf "$fixture"; done
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
echo 'PASS section-scoped times, missing-only supplements, PP-authoritative amounts, complete page envelopes and joint security identity'
python3 "$root/tests/oracle.py" "$PP_PAL_RUNTIME" PdfTextOracle "$root/fixtures"/v1/pdf-purchase
python3 "$root/tests/oracle.py" "$PP_PAL_RUNTIME" PdfPurchaseOracle "$root/fixtures"/v1/portfolio.xml "$root/fixtures"/v1/pdf-purchase/two-purchases.pdf
