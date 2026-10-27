#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/pdf-fx.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_RUNTIME=$1; fi
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio.xml"
"$launcher" import --file "$work/portfolio.xml" --pdf "$root/fixtures/v1/pdf-fx/missing-rate.pdf" --cash-account 'Synthetic Cash A' --securities-account 'Synthetic Securities A' --exchange-rate 0.8 --forex-gross 10 > "$work/preview"
rg -Fq 'SUPPLEMENT' "$work/preview"
cmp "$work/portfolio.xml" "$root/fixtures/v1/portfolio.xml"
"$launcher" import --file "$work/portfolio.xml" --pdf "$root/fixtures/v1/pdf-fx/missing-rate.pdf" --cash-account 'Synthetic Cash A' --securities-account 'Synthetic Securities A' --exchange-rate 0.8 --forex-gross 10 --apply --desktop-closed --allow-suspected-duplicates > "$work/applied"
rg -Fq 'APPLIED PDF PURCHASE DOCUMENT' "$work/applied"
launcher=${PP_PAL_BEHAVIOR_LAUNCHER:-${PP_PAL_LAUNCHER:-$root/tools/run-engine}}
python3 -u - "$root" "$work" "$launcher" "$PP_PAL_RUNTIME" <<'PY'
import os,pathlib,subprocess,sys,socket,threading
ROOT,BASE=map(pathlib.Path,sys.argv[1:3]); LAUNCHER,RUNTIME=sys.argv[3:]
sys.path.insert(0,str(ROOT/'tools'))
from test_jobs import parallel_cases
SOURCE=(ROOT/'fixtures/v1/portfolio.xml').read_bytes()
def expect(r,code,text):
    assert r.returncode==code and text in r.stdout+r.stderr, f'{r.returncode}: {r.stdout}\n{r.stderr}'
def fresh(name,source=SOURCE):
    w=BASE/name;w.mkdir();(w/'portfolio.xml').write_bytes(source);(w/'before.xml').write_bytes(source);return w
def run(w,name='mixed',apply=False,override=False,extra=(),observer=False):
    args=[LAUNCHER,'import','--file',str(w/'portfolio.xml'),'--pdf',str(ROOT/f'fixtures/v1/pdf-fx/{name}.pdf'),'--cash-account','Synthetic Cash A','--securities-account','Synthetic Securities A',*extra]
    if apply:args+=['--apply','--desktop-closed']
    if override:args+=['--allow-suspected-duplicates']
    env=dict(os.environ,PP_PAL_NOW='2026-10-11T12:00:00Z')
    if observer:env['PP_PAL_WRITE_OBSERVER']=str(w/'observer.sock')
    return subprocess.run(args,cwd=w,env=env,text=True,capture_output=True)
def oracle(w,name):
    r=subprocess.run(['python3',str(ROOT/'tests/oracle.py'),os.environ['PP_PAL_RUNTIME'],'PdfFxAppliedOracle',f'{w}/before.xml',f'{w}/portfolio.xml',name],text=True,capture_output=True)
    expect(r,0,'PASS saved FX PDF')
def success(name):
    # A single sale uses the same funded batch first, preserving it as baseline.
    w=fresh(name)
    if name=='sale':
        expect(run(w,'purchase',True,True),0,'APPLIED');(w/'before.xml').write_bytes((w/'portfolio.xml').read_bytes())
    extra=('--exchange-rate','0.8','--forex-gross','10') if name=='missing-rate' else ()
    expect(run(w,name,extra=extra),0,'PREVIEW PDF ')
    before=(w/'portfolio.xml').read_bytes()
    expect(run(w,name,True,True,extra=extra),0,'APPLIED PDF ');oracle(w,name)
    saved=(w/'portfolio.xml').read_bytes();meta=(w/'portfolio.xml.pp-pal-imports.properties').read_bytes()
    for override in (False,True):
        expect(run(w,name,True,override,extra),0,'SKIPPED PDF ')
        assert (w/'portfolio.xml').read_bytes()==saved and (w/'portfolio.xml.pp-pal-imports.properties').read_bytes()==meta
    assert saved!=before
w=fresh('derived-rate');r=run(w,'missing-rate',extra=('--exchange-rate','0.8'))
expect(r,0,'derived from booked, costs and supplied rate')
expect(run(w,'missing-rate',True,True,('--exchange-rate','0.8')),0,'APPLIED');oracle(w,'missing-rate')
w=fresh('matching-rate');expect(run(w,'purchase',extra=('--exchange-rate','0.8','--forex-gross','10')),0,'Portfolio Performance conversion')
parallel_cases(success,('purchase','sale','dividend','mixed','missing-rate'))
print('PASS actual FX PDF conversion, three transaction types, USD costs, authoritative booked amounts, supplied missing conversion, mixed timestamp ties and immutable repeats',flush=True)
w=fresh('refusals')
for name,extra in [('missing-rate',()),('missing-rate',('--exchange-rate','0')),('missing-rate',('--exchange-rate','0.8','--forex-gross','11')),('purchase',('--exchange-rate','0.9')),('purchase',('--forex-gross','11')),('purchase',('--total','10')),('mixed',('--exchange-rate','0.8')),('later-negative',()),('later-contradictory',()),('later-missing-rate',()),('repeated-reference',()),('later-invalid',()),('later-unsupported',())]:
    for apply in (False,True):
        expect(run(w,name,apply,True,extra),2,'ERROR:')
        assert (w/'portfolio.xml').read_bytes()==SOURCE and not (w/'portfolio.xml.pp-pal-imports.properties').exists() and not list(w.glob('*.????-??-??.bak'))
print('PASS conflicting rates/gross/booked totals, invalid rates, missing conversion and invalid later members reject complete documents unchanged',flush=True)
w=fresh('uncertain');expect(run(w,apply=True,override=True),0,'APPLIED');oracle(w,'mixed')
meta=w/'portfolio.xml.pp-pal-imports.properties';meta.unlink()
saved=(w/'portfolio.xml').read_bytes()
expect(run(w),0,'SUSPECTED DUPLICATE');expect(run(w,apply=True),2,'--allow-suspected-duplicates')
assert (w/'portfolio.xml').read_bytes()==saved and not meta.exists()
expect(run(w,apply=True,override=True),0,'APPLIED');expect(run(w,apply=True),0,'SKIPPED')
w=fresh('partial-confirmation');expect(run(w,'purchase',True,True),0,'APPLIED')
expect(run(w,apply=True,override=True),0,'APPLIED');oracle(w,'mixed');expect(run(w,apply=True),0,'SKIPPED')
print('PASS document-wide duplicate override after lost evidence and confirmed repeat protection',flush=True)
w=fresh('migration',(ROOT/'fixtures/v1/migrations/model69.xml').read_bytes())
expect(run(w,apply=True,override=True),0,'APPLIED');oracle(w,'mixed')
backups=list(w.glob('portfolio.xml.pp-pal.migration.*.bak'));assert len(backups)==1 and backups[0].read_bytes()==(w/'before.xml').read_bytes()
w=fresh('recovery');(w/'.pp-pal-disposable-fixture').write_text('pp-pal-write-observer-v1\n')
server=socket.socket(socket.AF_UNIX);server.bind(str(w/'observer.sock'));server.listen(1);server.settimeout(30);errors=[]
def observe():
    try:
        while True:
            c,_=server.accept()
            with c:
                msg=b''
                while msg.count(b'\n')<3:msg+=c.recv(4096)
                phase=msg.decode().splitlines()[0]
                if phase=='committed-before-follow-up':(w/'portfolio.xml.pp-pal-imports.properties').mkdir()
                c.sendall(('ACK '+phase+'\n').encode())
                if phase=='committed-before-follow-up':return
    except BaseException as e:errors.append(e)
thread=threading.Thread(target=observe,daemon=True);thread.start()
expect(run(w,apply=True,override=True,observer=True),4,'financial change succeeded; do not repeat apply');server.close();thread.join(timeout=1);assert not errors,errors
oracle(w,'mixed');saved=(w/'portfolio.xml').read_bytes()
expect(run(w,apply=True),4,'Previous financial replacement already succeeded')
(w/'portfolio.xml.pp-pal-imports.properties').rmdir()
expect(subprocess.run([LAUNCHER,'import','--file',str(w/'portfolio.xml'),'--recover-write','--desktop-closed'],cwd=w,text=True,capture_output=True),0,'RECOVERED')
expect(run(w,apply=True),0,'SKIPPED');assert (w/'portfolio.xml').read_bytes()==saved
print('PASS exact model69 migration protection and whole mixed FX metadata recovery without financial replay',flush=True)
PY
python3 "$root/tests/oracle.py" "$PP_PAL_RUNTIME" PdfTextOracle "$root/fixtures"/v1/pdf-fx
echo 'PASS FX/mixed PDF gate'
