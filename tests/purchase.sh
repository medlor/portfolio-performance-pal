#!/usr/bin/env bash
set -euo pipefail
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
root=$(cd "$(dirname "$0")/.." && pwd)
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio_performance.xml"
cd "$work"
chmod 600 portfolio_performance.xml
printf 'old daily protection\n' > portfolio_performance.xml.2020-01-01.bak
printf 'old migration protection\n' > portfolio_performance.xml.pp-pal.migration.2020.bak
cp portfolio_performance.xml.2020-01-01.bak daily-before
cp portfolio_performance.xml.pp-pal.migration.2020.bak migration-before
"$launcher" purchase --security DE0000000001 --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 2.5 --price 10 --fees 1 --taxes 0.50 > preview
rg -q '^PREVIEW PURCHASE$' preview
mkdir 'bin directory'
ln -s "$launcher" 'bin directory/pal'
'bin directory/pal' --help > symlink-help
rg -q '^Usage: pp-pal COMMAND' symlink-help
'bin directory/pal' purchase --security DE0000000001 --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 1 --price 10 > symlink-preview
rg -q '^PREVIEW PURCHASE$' symlink-preview
rg -q '^GROSS EUR 25.00 \(derived' preview
rg -q '^BOOKED DEBIT EUR 26.50 \(derived' preview
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
echo 'PASS exact linked same-currency purchase preview preserves the portfolio'
# Broad behavior uses the real container boundary after the owner integration case.
launcher=${PP_PAL_BEHAVIOR_LAUNCHER:-$root/tools/run-container}
purchase() {
  local options=("$@") defaults=() key value found arg
  for key in security securities-account cash-account date; do
    case "$key" in
      security) value=DE0000000001;; securities-account) value='Synthetic Securities A';;
      cash-account) value='Synthetic Cash A';; date) value=2025-03-01;;
    esac
    found=false
    for arg in "${options[@]}"; do if [[ "$arg" == "--$key" ]]; then found=true; fi; done
    if ! "$found"; then defaults+=("--$key" "$value"); fi
  done
  "$launcher" purchase "${defaults[@]}" "${options[@]}"
}
purchase --quantity 2.5 --gross 25 --fees 1 --taxes 0.50 > gross-preview
rg -q '^PRICE EUR 10 \(derived' gross-preview
rg -q '^GROSS EUR 25.00 \(supplied' gross-preview
rg -q '^BOOKED DEBIT EUR 26.50 \(derived' gross-preview
purchase --quantity 2.5 --total 26.50 --fees 1 --taxes 0.50 > total-preview
rg -q '^GROSS EUR 25.00 \(derived' total-preview
rg -q '^BOOKED DEBIT EUR 26.50 \(supplied' total-preview
echo 'PASS determined gross/booked inputs derive the same purchase without inventing an amount'
"$launcher" purchase --isin DE0000000001 --wkn TEST01 --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 1 --price 10 > identifiers-preview
rg -q '^SECURITY Synthetic Euro Instrument \|' identifiers-preview
reject() {
  local rc=0
  purchase "$@" > error 2>&1 || rc=$?
  test "$rc" = 2 || { cat error >&2; echo "Expected purchase rejection 2, got $rc" >&2; exit 1; }
  ! rg -q '^PREVIEW PURCHASE' error
}

missing() {
  local expected=$1 rc=0; shift
  "$launcher" purchase --file portfolio_performance.xml "$@" > missing-error 2>&1 || rc=$?
  test "$rc" = 2 || { cat missing-error >&2; exit 1; }
  rg -Fiq "$expected" missing-error
  ! rg -q '^PREVIEW PURCHASE' missing-error
}
missing 'existing security' --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 1 --price 10
missing 'required securities and cash accounts' --security DE0000000001 --date 2025-03-01 --quantity 1 --price 10
missing 'required securities and cash accounts' --security DE0000000001 --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 1 --price 10
missing 'required securities and cash accounts' --security DE0000000001 --securities-account 'Synthetic Securities A' --date 2025-03-01 --quantity 1 --price 10
missing 'Missing --date' --security DE0000000001 --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --quantity 1 --price 10

for value in 0 -1 0.000000001 92233720368.54775808; do reject --quantity "$value" --price 10; done
for value in 0 -1 0.000000001 92233720368.54775808; do reject --quantity 1 --price "$value"; done
reject --quantity 1 --price 10 --fees 0.001
reject --quantity 1 --gross 10.001
reject --quantity 1 --gross 92233720368547758.07 --fees 0.01
reject --quantity 0.00000001 --gross 100000000000
reject --quantity 2.5 --price 10 --gross 24.99
reject --quantity 2.5 --price 10 --fees 1 --taxes 0.5 --total 26.49
reject --quantity 1 --total 0.5 --fees 1
reject --quantity 1
reject --quantity 1 --price 10 --date 2025-02-30
reject --quantity 1 --price 10 --time 25:00
reject --quantity 1 --price 10 --security unknown
reject --quantity 1 --price 10 --security-id 00000000-0000-4000-8000-000000000002
reject --quantity 1 --price 10 --cash-account missing
reject --quantity 1 --price 10 --apply
for fact in '1 1.005 1.01' '1 1.00499999 1.00' '3 0.335 1.01' '3 0.33333333 1.00' '0.00000001 100000000 1.00' '1.23456789 12.34567890 15.24' '12345678.12345678 12.34567890 152415777.91' '92233720368.54775807 0.01 922337203.69' '1 0.00000001 0.00'; do
  read -r quantity price expected <<< "$fact"
  purchase --quantity "$quantity" --price "$price" > boundary-preview
  rg -Fq "GROSS EUR $expected (derived" boundary-preview
done
purchase --quantity 2.500000000 --price 10.000000000 --fees 1.000 --time 00:00:00 > midnight-preview
rg -Fq 'DATE 2025-03-01T00:00:00 (supplied date and time)' midnight-preview
purchase --quantity 1 --price 10 --time 12:34:56.123456789 > nanos-preview
rg -Fq '2025-03-01T12:34:56.123456789' nanos-preview
cp "$root/fixtures/v1/ambiguous-purchase.xml" ambiguous.xml
reject --file ambiguous.xml --quantity 1 --price 10
purchase --file ambiguous.xml --security 'Synthetic Euro Instrument' --security-id 00000000-0000-4000-8000-000000000001 --quantity 1 --price 10 > selected-preview
rg -Fq '| 00000000-0000-4000-8000-000000000001 |' selected-preview
cp "$root/fixtures/v1/query-portfolio.xml" query.xml
reject --file query.xml --security Alpha --securities-account 'Twin Depot' --quantity 1 --price 10
purchase --file query.xml --security ALPHA --securities-account 30000000-0000-4000-8000-000000000001 --cash-account 20000000-0000-4000-8000-000000000001 --quantity 1 --price 10 > ticker-preview
rg -q '^SECURITY Alpha Instrument' ticker-preview
reject --security US0000000002 --quantity 1 --price 10
printf 'opaque metadata\n' > portfolio_performance.xml.pp-pal.json
cp portfolio_performance.xml.pp-pal.json metadata-before
mkdir 'configured directory'
cp portfolio_performance.xml 'configured directory/input file.xml'
cat > 'configured directory/defaults.properties' <<'CONFIG'
file=input file.xml
securities-account=00000000-0000-4000-8000-000000000005
cash-account=00000000-0000-4000-8000-000000000003
CONFIG
cp 'configured directory/defaults.properties' config-before
# Explicit file/account selections override valid configured alternatives.
cp "$root/fixtures/v1/query-portfolio.xml" 'configured directory/other.xml'
cat > 'configured directory/overrides.properties' <<'CONFIG'
file=other.xml
securities-account=30000000-0000-4000-8000-000000000003
cash-account=20000000-0000-4000-8000-000000000002
CONFIG
cp 'configured directory/overrides.properties' overrides-before
purchase --config 'configured directory/overrides.properties' --file portfolio_performance.xml --quantity 1 --price 10 > override-preview
rg -Fq 'Synthetic Securities A | 00000000-0000-4000-8000-000000000005' override-preview
cmp 'configured directory/overrides.properties' overrides-before
cmp 'configured directory/other.xml' "$root/fixtures/v1/query-portfolio.xml"
"$launcher" purchase --config 'configured directory/defaults.properties' --security-id 00000000-0000-4000-8000-000000000001 --date 2025-03-01 --quantity 1 --gross 0 > configured-preview
rg -q '^BOOKED DEBIT EUR 0.00' configured-preview
cmp 'configured directory/defaults.properties' config-before
cmp 'configured directory/input file.xml' "$root/fixtures/v1/portfolio.xml"
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
cmp portfolio_performance.xml.pp-pal.json metadata-before
cmp ambiguous.xml "$root/fixtures/v1/ambiguous-purchase.xml"
cmp query.xml "$root/fixtures/v1/query-portfolio.xml"
cmp portfolio_performance.xml.2020-01-01.bak daily-before
cmp portfolio_performance.xml.pp-pal.migration.2020.bak migration-before
test "$(find . -name '*.????-??-??.bak' | wc -l)" = 1
test "$(find . -name '*.pp-pal.migration.*.bak' | wc -l)" = 1
test "$(stat -c %a portfolio_performance.xml)" = 600
python3 "$root/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/PurchaseOracle.java /fixtures/v1/portfolio.xml
echo 'PASS exact rounding/range/contradictions, selector intersections, accounts, explicit times, paths and immutable preview'
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
# Exercise qualification refusal through the same live candidate launcher. No
# fabricated qualification payload can grant reuse to an owner process.
python3 - "$launcher" "$work/portfolio_performance.xml" <<'PY'
import http.server, json, os, pathlib, subprocess, sys, threading, urllib.request
launcher, selected = sys.argv[1:]
args = [launcher, 'purchase', '--file', selected, '--security', 'DE0000000001', '--securities-account', 'Synthetic Securities A', '--cash-account', 'Synthetic Cash A', '--date', '2025-03-01', '--quantity', '1', '--price', '10']
def refused(environment, expected):
    result = subprocess.run(args, env=environment, text=True, capture_output=True)
    assert result.returncode == 3 and expected.lower() in result.stderr.lower() and 'PREVIEW PURCHASE' not in result.stdout, result.stderr + result.stdout
state = pathlib.Path(os.environ['PP_PAL_STATE_DIR'])
marker = state / 'trusted-validation.json'
original = marker.read_bytes()
try:
    data = json.loads(original); data['sha256'] = '0' * 64; marker.write_text(json.dumps(data))
    refused(dict(os.environ), 'Trusted validation bundle changed')
finally: marker.write_bytes(original)
package = pathlib.Path(launcher).parent
help_file = package / 'runtime/help.txt'; original = help_file.read_bytes()
try:
    help_file.write_bytes(original + b'\nchanged candidate help\n')
    refused(dict(os.environ), 'qualification pair/revision/digest/suite/image mismatch')
finally: help_file.write_bytes(original)
other = os.environ['PP_PAL_GATE_BASELINE_IMAGE']
assert other != os.environ['PP_PAL_IMAGE'], 'Released candidate must differ from the independently built baseline'
refused(dict(os.environ, PP_PAL_IMAGE=other), 'qualification pair/revision/digest/suite/image mismatch')
base = os.environ['PP_PAL_RELEASE_API']
mode = None
class Proxy(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with urllib.request.urlopen(base + self.path) as response: data = response.read()
        if '/git/tags/' in self.path and mode == 'wrapper-revision':
            value = json.loads(data); value['object']['sha'] = '1' * 40; data = json.dumps(value).encode()
        if '/portfolio/git/ref/tags/' in self.path and mode == 'upstream-revision':
            value = json.loads(data); value['object']['sha'] = '2' * 40; data = json.dumps(value).encode()
        if '/portfolio/releases/latest' in self.path:
            value = json.loads(data)
            value['assets'][0]['browser_download_url'] = value['assets'][0]['browser_download_url'].replace(base, f'http://127.0.0.1:{self.server.server_port}')
            if mode == 'dependency-digest': value['assets'][0]['digest'] = 'sha256:' + '3' * 64
            data = json.dumps(value).encode()
        self.send_response(200); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
    def log_message(self, *args): pass
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Proxy)
thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
try:
    for mode in ('wrapper-revision', 'upstream-revision', 'dependency-digest'):
        refused(dict(os.environ, PP_PAL_RELEASE_API=f'http://127.0.0.1:{server.server_port}'), 'qualification pair/revision/digest/suite/image mismatch')
finally: server.shutdown(); server.server_close(); thread.join()
print('PASS live fixed-check qualification rejects source tree, image, trusted suite, wrapper/upstream revisions and dependency digest mismatches')
PY
