#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/upstream-reuse.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
launcher=${PP_PAL_BEHAVIOR_LAUNCHER:-${PP_PAL_LAUNCHER:-$root/tools/run-container}}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
python3 - "$root" "$work" "$launcher" "$PP_PAL_IMAGE" <<'PY'
import os,pathlib,subprocess,sys
ROOT,BASE=map(pathlib.Path,sys.argv[1:3]); LAUNCHER,IMAGE=sys.argv[3:]
sys.path.insert(0, str(ROOT/'tools'))
from test_jobs import parallel_cases
SOURCE=(ROOT/'fixtures/v1/portfolio.xml').read_bytes()
def expect(r,code,text):
    assert r.returncode==code and text in r.stdout+r.stderr, f'{r.returncode}: {r.stdout}\n{r.stderr}'
def command(w,pdf,*extra):
    return subprocess.run([LAUNCHER,'import','--file',str(w/'portfolio.xml'),'--pdf',str(pdf),'--cash-account','Synthetic Cash A','--securities-account','Synthetic Securities A',*extra],cwd=w,text=True,capture_output=True)
def oracle(w,pdf,date,saved=False):
    r=subprocess.run(['python3',str(ROOT/'tests/oracle.py'),'run','--rm','--network','none','--user',f'{os.getuid()}:{os.getgid()}', '-v',f'{ROOT}/runtime/src/test/java:/tests:ro','-v',f'{ROOT}/fixtures:/fixtures:ro','-v',f'{w}:/work:ro','--entrypoint','java',IMAGE,'-cp','/opt/pp/classes:/opt/pp/lib/*','/tests/pal/UpstreamPdfAuthorityOracle.java','/work/before.xml','/fixtures/'+str(pdf.relative_to(ROOT/'fixtures')),date,*(['/work/portfolio.xml'] if saved else [])],text=True,capture_output=True)
    expect(r,0,'PASS PP-authoritative')
cases=[
 ('pdf-purchase/contradictory-gross','2025-01-03T09:30:00'),
 ('pdf-purchase/unknown-trailing','2025-01-03T09:30:00'),
 ('pdf-purchase/repeated-fee','2025-01-03T09:30:00'),
 ('pdf-purchase/midnight','2025-01-03T00:00:00'),
 ('pdf-sale/wrong-direction','2025-03-01T09:30:00'),
 ('pdf-sale/contradictory','2025-03-01T09:30:00'),
 ('pdf-dividend/contradictory','2025-03-01T11:00:00'),
 ('pdf-dividend/repeated-tax','2025-03-01T11:00:00'),
 ('pdf-dividend/bad-time','2025-03-01T11:00:00'),
 ('pdf-dividend/unknown-line','2025-03-01T11:00:00'),
 ('pp-extraction/purchase-footer','2025-01-03T09:30:00'),
 ('pp-extraction/purchase-two-pages','2025-01-03T09:30:00'),
 ('pp-extraction/sale-multiple-costs','2025-03-01T09:30:00'),
 ('pp-extraction/dividend-fund-no-reference','2025-03-01T11:00:00'),
 ('pp-extraction/dividend-zero-shares','2025-03-01T11:00:00'),
 ('pp-extraction/purchase-fx','2025-01-03T09:30:00'),
 ('pp-extraction/dividend-fx','2025-03-01T11:00:00'),
]
def check_document(case):
    n,(name,date) = case
    w=BASE/str(n); w.mkdir(); (w/'portfolio.xml').write_bytes(SOURCE); (w/'before.xml').write_bytes(SOURCE)
    pdf=ROOT/'fixtures/v1'/f'{name}.pdf'
    expect(command(w,pdf),0,'PREVIEW PDF ')
    assert (w/'portfolio.xml').read_bytes()==SOURCE and not (w/'portfolio.xml.pp-pal-imports.properties').exists()
    oracle(w,pdf,date)
    if name.startswith('pp-extraction/'):
        expect(command(w,pdf,'--apply','--desktop-closed','--allow-suspected-duplicates'),0,'APPLIED PDF ')
        oracle(w,pdf,date,True)
        saved=(w/'portfolio.xml').read_bytes(); metadata=(w/'portfolio.xml.pp-pal-imports.properties').read_bytes()
        expect(command(w,pdf,'--apply','--desktop-closed'),0,'SKIPPED PDF ')
        assert (w/'portfolio.xml').read_bytes()==saved and (w/'portfolio.xml.pp-pal-imports.properties').read_bytes()==metadata
        if name=='pp-extraction/dividend-fund-no-reference':
            renamed=w/'renamed.pdf'; renamed.write_bytes(pdf.read_bytes())
            expect(command(w,renamed,'--apply','--desktop-closed'),0,'SKIPPED PDF ')
            changed=w/'different-encoding.pdf'; changed.write_bytes(pdf.read_bytes()+b'\n% synthetic fixture encoding change\n')
            expect(command(w,changed),0,'SUSPECTED DUPLICATE')
            expect(command(w,changed,'--apply','--desktop-closed'),2,'--allow-suspected-duplicates')
            expect(command(w,pdf,'--time','12:00:00'),2,'confirmed document identity')
            assert (w/'portfolio.xml').read_bytes()==saved and (w/'portfolio.xml.pp-pal-imports.properties').read_bytes()==metadata
parallel_cases(check_document, enumerate(cases))
print('PASS direct PP oracle: ignored fields, midnight, footers, complete continuations, summed costs, no-reference identity and FX units survive saved imports/repeats',flush=True)
w=BASE/'rejects';w.mkdir();(w/'portfolio.xml').write_bytes(SOURCE)
for name,extra in [
 ('pp-extraction/dividend-cancelled',()),
 ('pp-extraction/dividend-zero-cost',('--fees','1')),
 ('pp-extraction/dividend-zero-cost',('--taxes','1')),
 ('pdf-purchase/missing-booked',('--total','51')),
 ('pdf-purchase/missing-quantity-price',('--quantity','2','--price','25')),
 ('pdf-dividend/missing-booked',('--total','23.50','--date','2025-03-01')),
 ('pdf-purchase/midnight',('--time','11:00:00')),
 ('pdf-dividend/continuation',()),
 ('pdf-dividend/later-unsupported',()),
]:
    for apply in ((),('--apply','--desktop-closed','--allow-suspected-duplicates')):
        expect(command(w,ROOT/'fixtures/v1'/f'{name}.pdf',*extra,*apply),2,'ERROR:')
        assert (w/'portfolio.xml').read_bytes()==SOURCE and not list(w.glob('*.????-??-??.bak')) and not (w/'portfolio.xml.pp-pal-imports.properties').exists()
print('PASS PP failures, extracted zero costs, conflicts, mandatory fields and incomplete envelopes reject atomically',flush=True)
# Fractional CLI times are preserved consistently with the manual CLI; invented
# payment-time text is ignored by PP and must not be claimed as extracted time.
r=command(w,ROOT/'fixtures/v1/pdf-dividend/explicit.pdf');expect(r,0,'fallback time 11:00:00')
r=command(w,ROOT/'fixtures/v1/pdf-dividend/basic.pdf','--time','11:00:00.1');expect(r,0,'2025-03-01T11:00:00.1')
PY
python3 "$root/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/PdfTextOracle.java /fixtures/v1/pp-extraction
echo 'PASS PP reuse gate'
