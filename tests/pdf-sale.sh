#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/pdf-sale.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio.xml"
cd "$work"
"$launcher" import --file portfolio.xml --pdf "$root/fixtures/v1/pdf-sale/partial.pdf" --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' > preview
rg -q '^PREVIEW PDF SALE DOCUMENT' preview
rg -Fq 'BOOKED CREDIT EUR 23.50 (PDF)' preview
rg -Fq 'DATE 2025-03-01T09:30:00 (PDF date and time)' preview
cmp portfolio.xml "$root/fixtures/v1/portfolio.xml"
echo 'PASS actual sale PDF preview and immutable portfolio'
"$launcher" import --file portfolio.xml --pdf "$root/fixtures/v1/pdf-sale/partial.pdf" --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --apply --desktop-closed --allow-suspected-duplicates > applied
rg -q '^APPLIED PDF SALE DOCUMENT$' applied
launcher=${PP_PAL_BEHAVIOR_LAUNCHER:-$root/tools/run-container}
export PP_PAL_TEST_ROOT="$root" PP_PAL_SALE_LAUNCHER="$launcher"
python3 -u - <<'PY'
import sys
import json, os, pathlib, shutil, socket, subprocess, tempfile, threading
ROOT=pathlib.Path(os.environ['PP_PAL_TEST_ROOT'])
LAUNCHER=os.environ['PP_PAL_SALE_LAUNCHER']; IMAGE=os.environ['PP_PAL_IMAGE']
sys.path.insert(0, str(ROOT/'tools'))
from test_jobs import parallel_cases
SOURCE=(ROOT/'fixtures/v1/portfolio.xml').read_bytes()
FACTS=json.loads((ROOT/'fixtures/v1/pdf-sale/expected.json').read_text())
def expect(r,code,text):
    assert r.returncode==code and text in r.stdout+r.stderr, f'{r.returncode}: {r.stdout}\n{r.stderr}'
def run(w,name='partial',apply=False,override=False,extra=(),observer=False):
    args=['import','--file',str(w/'portfolio.xml'),'--pdf',str(ROOT/f'fixtures/v1/pdf-sale/{name}.pdf'),'--securities-account','Synthetic Securities A','--cash-account','Synthetic Cash A']
    if apply: args+=['--apply','--desktop-closed']
    if override: args+=['--allow-suspected-duplicates']
    env=dict(os.environ,PP_PAL_NOW='2026-10-10T12:00:00Z')
    if observer: env['PP_PAL_WRITE_OBSERVER']=str(w/'observer.sock')
    return subprocess.run([LAUNCHER,*args,*extra],cwd=w,env=env,text=True,capture_output=True)
def fresh(base,name,source=SOURCE):
    w=base/name; w.mkdir(); (w/'portfolio.xml').write_bytes(source); (w/'before.xml').write_bytes(source); return w
def oracle(w,name):
    (w/'facts.tsv').write_text(''.join('\t'.join(str(f[k]) for k in ('reference','type','shares','total','gross','fees','taxes','date'))+'\n' for fact in FACTS[name] for f in [{'type':'SELL',**fact}]))
    r=subprocess.run(['python3',str(ROOT/'tests/oracle.py'),'run','--rm','--network','none','--user',f'{os.getuid()}:{os.getgid()}', '-v',f'{ROOT}/runtime/src/test/java:/tests:ro','-v',f'{w}:/work:ro','--entrypoint','java',IMAGE,'-cp','/opt/pp/classes:/opt/pp/lib/*','/tests/pal/PdfSaleAppliedOracle.java','/work/before.xml','/work/portfolio.xml','/work/facts.tsv'],text=True,capture_output=True)
    expect(r,0,'PASS saved PDF sale batch')
with tempfile.TemporaryDirectory(prefix='pp-pal-pdf-sale-') as tmp:
    base=pathlib.Path(tmp)
    def check_document(name):
        w=fresh(base,name)
        r=run(w,name); expect(r,0,'PREVIEW PDF ')
        assert (w/'portfolio.xml').read_bytes()==SOURCE
        assert not list(w.glob('*.????-??-??.bak')) and not (w/'portfolio.xml.pp-pal-imports.properties').exists()
        if name=='missing-time': assert 'fallback time 11:00:01' in r.stdout
        r=run(w,name,True,True); expect(r,0,'APPLIED PDF '); oracle(w,name)
        assert (w/'portfolio.xml.2026-10-10.bak').read_bytes()==SOURCE
        saved=(w/'portfolio.xml').read_bytes(); evidence=(w/'portfolio.xml.pp-pal-imports.properties').read_bytes()
        for override in (False,True):
            expect(run(w,name,True,override),0,'SKIPPED PDF ')
            assert (w/'portfolio.xml').read_bytes()==saved and (w/'portfolio.xml.pp-pal-imports.properties').read_bytes()==evidence
    parallel_cases(check_document, ('partial','full','backdated','missing-time','midnight','tie','two-sales','funded-tie'))
    print('PASS partial/full/backdated sales, explicit/fallback times, same-time increases first, atomic batches and confirmed repeats',flush=True)
    for name in ('later-negative','before-purchase','unsupported-dividend','foreign-currency','later-invalid'):
        w=fresh(base,name)
        for apply in (False,True):
            expect(run(w,name,apply,True),2,'ERROR:')
            assert (w/'portfolio.xml').read_bytes()==SOURCE and not (w/'portfolio.xml.pp-pal-imports.properties').exists() and not list(w.glob('*.????-??-??.bak'))
    for name in ('wrong-direction','contradictory','later-unsupported'):
        w=fresh(base,'pp-authority-'+name)
        expect(run(w,name),0,'PREVIEW PDF ')
        assert (w/'portfolio.xml').read_bytes()==SOURCE
    print('PASS later-negative holdings, invalid/unsupported members, PP extraction errors preserve original bytes; ignored fields defer to PP',flush=True)
    w=fresh(base,'supplements')
    expect(run(w,'missing-fee',extra=('--fees','1')),0,'FEES EUR 1.00 (SUPPLEMENT)')
    expect(run(w,'missing-booked',extra=('--total','23.50')),2,'Portfolio Performance extraction failed')
    expect(run(w,extra=('--total','24')),2,'CLI conflicts')
    expect(run(w,apply=True),2,'--allow-suspected-duplicates')
    expect(run(w,apply=True,override=True),0,'APPLIED PDF SALE'); oracle(w,'partial')
    saved=(w/'portfolio.xml').read_bytes(); (w/'portfolio.xml.pp-pal-imports.properties').unlink()
    expect(run(w),0,'SUSPECTED DUPLICATE'); expect(run(w,apply=True),2,'--allow-suspected-duplicates')
    assert (w/'portfolio.xml').read_bytes()==saved
    print('PASS missing-only supplementation, authoritative credit and lost metadata uncertainty',flush=True)
    w=fresh(base,'partial-confirmation')
    expect(run(w,apply=True,override=True),0,'APPLIED PDF SALE')
    expect(run(w,'two-sales',True,True),0,'APPLIED PDF SALE'); oracle(w,'two-sales')
    expect(run(w,'two-sales',True),0,'SKIPPED PDF SALE')
    migration=(ROOT/'fixtures/v1/migrations/model69.xml').read_bytes()
    w=fresh(base,'migration',migration)
    expect(run(w,apply=True,override=True),0,'APPLIED PDF SALE'); oracle(w,'partial')
    backups=list(w.glob('portfolio.xml.pp-pal.migration.*.bak'))
    assert len(backups)==1 and backups[0].read_bytes()==migration
    w=fresh(base,'isolated-account')
    # Explicitly select B through a standalone request: its holding is closed.
    r=subprocess.run([LAUNCHER,'import','--file',str(w/'portfolio.xml'),'--pdf',str(ROOT/'fixtures/v1/pdf-sale/partial.pdf'),
        '--securities-account','Synthetic Securities B','--cash-account','Synthetic Cash B','--apply','--desktop-closed','--allow-suspected-duplicates'],cwd=w,text=True,capture_output=True)
    expect(r,2,'negative holding'); assert (w/'portfolio.xml').read_bytes()==SOURCE
    print('PASS partial confirmation inserts only remaining sale, protected migration and account-isolated admission',flush=True)
    w=fresh(base,'metadata-failure')
    (w/'.pp-pal-disposable-fixture').write_text('pp-pal-write-observer-v1\n')
    server=socket.socket(socket.AF_UNIX); server.bind(str(w/'observer.sock')); server.listen(1); server.settimeout(30)
    errors=[]
    def observe():
        try:
            while True:
                c,_=server.accept()
                with c:
                    message=b''
                    while message.count(b'\n')<3: message+=c.recv(4096)
                    phase=message.decode().splitlines()[0]
                    if phase=='committed-before-follow-up': (w/'portfolio.xml.pp-pal-imports.properties').mkdir()
                    c.sendall(('ACK '+phase+'\n').encode())
                    if phase=='committed-before-follow-up': return
        except BaseException as error: errors.append(error)
    thread=threading.Thread(target=observe,daemon=True); thread.start()
    expect(run(w,apply=True,override=True,observer=True),4,'financial change succeeded; do not repeat apply')
    server.close(); thread.join(timeout=1); assert not errors, errors
    oracle(w,'partial')
    expect(run(w,apply=True,override=True),4,'Previous financial replacement already succeeded')
    (w/'portfolio.xml.pp-pal-imports.properties').rmdir()
    r=subprocess.run([LAUNCHER,'import','--file',str(w/'portfolio.xml'),'--recover-write','--desktop-closed'],cwd=w,text=True,capture_output=True)
    expect(r,0,'RECOVERED'); oracle(w,'partial'); expect(run(w,apply=True),0,'SKIPPED PDF SALE')
    print('PASS sale metadata failure reports committed financial success and recovery never replays it',flush=True)
PY
python3 "$root/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/PdfTextOracle.java /fixtures/v1/pdf-sale
