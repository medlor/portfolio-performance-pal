#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/smartbroker-current.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
python3 "$root/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" \
  -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" -v "$work:/work:rw" \
  --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' \
  /tests/pal/CurrentLayoutOracle.java prepare /fixtures/v1/portfolio.xml /work/base.xml
python3 -u - "$root" "$work" "${PP_PAL_LAUNCHER:-$root/pp-pal}" "${PP_PAL_BEHAVIOR_LAUNCHER:-${PP_PAL_LAUNCHER:-$root/tools/run-container}}" "$PP_PAL_IMAGE" <<'PY'
import decimal, os, pathlib, re, subprocess, sys
ROOT, BASE = map(pathlib.Path, sys.argv[1:3])
OWNER, BEHAVIOR, IMAGE = sys.argv[3:]
sys.path.insert(0, str(ROOT/'tools'))
from test_jobs import parallel_cases
SOURCE = (BASE/'base.xml').read_bytes()
def expect(result, code, text):
    assert result.returncode == code and text in result.stdout + result.stderr, f'{result.returncode}: {result.stdout}\n{result.stderr}'
def run(w, name, apply=False, launcher=BEHAVIOR, extra=()):
    return subprocess.run([launcher, 'import', '--file', str(w/'portfolio.xml'), '--pdf', str(ROOT/f'fixtures/v1/smartbroker-current/{name}.pdf'),
        '--cash-account', 'Synthetic Cash A', '--securities-account', 'Synthetic Securities A', *extra,
        *(['--apply', '--desktop-closed'] if apply else [])], cwd=w, text=True, capture_output=True)
def oracle(w, name):
    result = subprocess.run(['python3', str(ROOT/'tests/oracle.py'), 'run', '--rm', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}',
        '-v', f'{ROOT}/runtime/src/test/java:/tests:ro', '-v', f'{w}:/work:ro', '--entrypoint', 'java', IMAGE,
        '-cp', '/opt/pp/classes:/opt/pp/lib/*', '/tests/pal/CurrentLayoutOracle.java', 'verify', '/work/before.xml', '/work/portfolio.xml', name], text=True, capture_output=True)
    expect(result, 0, 'PASS saved current-layout ' + name)
def success(name):
    w=BASE/name; w.mkdir()
    for filename in ('portfolio.xml', 'before.xml'): (w/filename).write_bytes(SOURCE)
    # The complete mixed statement exercises preview/apply through the checked owner pair.
    launcher = OWNER if name == 'mixed' else BEHAVIOR
    preview=run(w, name, launcher=launcher); expect(preview, 0, 'PREVIEW PDF ')
    if name in ('dividend-chf', 'mixed'):
        rate=re.search(r'EXCHANGE RATE ([0-9.]+) EUR per CHF', preview.stdout)
        assert rate and decimal.Decimal(rate[1]) == decimal.Decimal('0.8') and 'fallback time 11:00:00' in preview.stdout, preview.stdout
    if name in ('purchase', 'mixed'): assert '2025-03-01T09:30:00 (PDF date and time)' in preview.stdout, preview.stdout
    if name in ('sale', 'mixed'): assert '2025-03-02T09:30:01 (PDF date and time)' in preview.stdout, preview.stdout
    assert (w/'portfolio.xml').read_bytes() == SOURCE and not (w/'portfolio.xml.pp-pal-imports.properties').exists()
    extra=()
    if name == 'mixed':
        assert 'SUSPECTED DUPLICATE' in preview.stdout, preview.stdout
        expect(run(w, name, True, launcher), 2, '--allow-suspected-duplicates')
        assert (w/'portfolio.xml').read_bytes() == SOURCE and not (w/'portfolio.xml.pp-pal-imports.properties').exists()
        extra=('--allow-suspected-duplicates',)
    expect(run(w, name, True, launcher, extra), 0, 'APPLIED PDF '); oracle(w, name)
    saved=(w/'portfolio.xml').read_bytes(); metadata=(w/'portfolio.xml.pp-pal-imports.properties').read_bytes()
    for extra in ((), ('--allow-suspected-duplicates',)):
        expect(run(w, name, True, launcher, extra), 0, 'SKIPPED PDF ')
        assert (w/'portfolio.xml').read_bytes() == saved and (w/'portfolio.xml.pp-pal-imports.properties').read_bytes() == metadata
    assert len(list(w.glob('portfolio.xml.????-??-??.bak'))) == 1
parallel_cases(success, ('purchase', 'sale', 'dividend-chf', 'mixed'))
print('PASS current purchase, complete sale/CHF-dividend continuations, mixed whole-document preview/apply and confirmed repeats', flush=True)
w=BASE/'contradictory'; w.mkdir(); (w/'portfolio.xml').write_bytes(SOURCE)
for name, extra in [('purchase', ('--total', '51')), ('sale', ('--quantity', '3')), ('dividend-chf', ('--exchange-rate', '0.9')), ('incomplete-sale', ()), ('mixed-invalid', ())]:
    for apply in (False, True):
        expect(run(w, name, apply, extra=extra), 2, 'ERROR:')
        assert (w/'portfolio.xml').read_bytes() == SOURCE and not (w/'portfolio.xml.pp-pal-imports.properties').exists()
print('PASS current-layout contradictory amounts/shares/CHF rate, missing continuation and invalid later member refuse whole documents unchanged', flush=True)
PY
python3 "$root/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" \
  -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" \
  --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' \
  /tests/pal/PdfTextOracle.java /fixtures/v1/smartbroker-current
echo 'PASS current Smartbroker layout gate'
