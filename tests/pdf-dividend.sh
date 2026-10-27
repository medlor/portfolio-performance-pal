#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/pdf-dividend.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_RUNTIME=$1; fi
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio.xml"
cd "$work"
"$launcher" import --file portfolio.xml --pdf "$root/fixtures/v1/pdf-dividend/basic.pdf" --cash-account 'Synthetic Cash A' > preview
rg -q '^PREVIEW PDF DIVIDEND DOCUMENT' preview
rg -Fq 'BOOKED CREDIT EUR 23.50 (PDF)' preview
rg -Fq 'fallback time 11:00:00' preview
cmp portfolio.xml "$root/fixtures/v1/portfolio.xml"
echo 'PASS actual dividend PDF preview and immutable portfolio'
"$launcher" import --file portfolio.xml --pdf "$root/fixtures/v1/pdf-dividend/basic.pdf" --cash-account 'Synthetic Cash A' --apply --desktop-closed > applied
rg -q '^APPLIED PDF DIVIDEND DOCUMENT$' applied
launcher=${PP_PAL_BEHAVIOR_LAUNCHER:-$root/tools/run-engine}
export PP_PAL_TEST_ROOT="$root" PP_PAL_DIVIDEND_LAUNCHER="$launcher"
python3 -u - <<'PY'
import sys
import json, os, pathlib, subprocess, tempfile, socket, threading
ROOT=pathlib.Path(os.environ['PP_PAL_TEST_ROOT']); LAUNCHER=os.environ['PP_PAL_DIVIDEND_LAUNCHER']; RUNTIME=os.environ['PP_PAL_RUNTIME']
sys.path.insert(0, str(ROOT/'tools'))
from test_jobs import parallel_cases
SOURCE=(ROOT/'fixtures/v1/portfolio.xml').read_bytes()
FACTS=json.loads((ROOT/'fixtures/v1/pdf-dividend/expected.json').read_text())
def expect(r,code,text):
    assert r.returncode==code and text in r.stdout+r.stderr, f'{r.returncode}: {r.stdout}\n{r.stderr}'
def run(w,name='basic',apply=False,override=False,extra=(),cash='Synthetic Cash A',observer=False):
    args=['import','--file',str(w/'portfolio.xml'),'--pdf',str(ROOT/f'fixtures/v1/pdf-dividend/{name}.pdf'),'--cash-account',cash]
    if name in ('mixed','later-invalid','later-negative'): args+=['--securities-account','Synthetic Securities A']
    if apply: args+=['--apply','--desktop-closed']
    if override: args+=['--allow-suspected-duplicates']
    env=dict(os.environ,PP_PAL_NOW='2026-10-10T12:00:00Z')
    if observer: env['PP_PAL_WRITE_OBSERVER']=str(w/'observer.sock')
    return subprocess.run([LAUNCHER,*args,*extra],cwd=w,env=env,text=True,capture_output=True)
def fresh(base,name,source=SOURCE):
    w=base/name; w.mkdir(); (w/'portfolio.xml').write_bytes(source); (w/'before.xml').write_bytes(source); return w
def oracle(w,name,cash_id=None):
    (w/'facts.tsv').write_text(''.join('\t'.join(str(f[k]) for k in ('reference','type','shares','total','gross','fees','taxes','date'))+'\n' for f in FACTS[name]))
    r=subprocess.run(['python3',str(ROOT/'tests/oracle.py'),os.environ['PP_PAL_RUNTIME'],'PdfDividendAppliedOracle',f'{w}/before.xml',f'{w}/portfolio.xml',f'{w}/facts.tsv',*([cash_id] if cash_id else [])],text=True,capture_output=True)
    expect(r,0,'PASS saved PDF dividend/mixed batch')
with tempfile.TemporaryDirectory(prefix='pp-pal-pdf-dividend-') as tmp:
    base=pathlib.Path(tmp)
    def check_document(name):
        w=fresh(base,name)
        expect(run(w,name),0,'PREVIEW PDF ')
        assert (w/'portfolio.xml').read_bytes()==SOURCE and not list(w.glob('*.????-??-??.bak')) and not (w/'portfolio.xml.pp-pal-imports.properties').exists()
        expect(run(w,name,True,True),0,'APPLIED PDF '); oracle(w,name)
        assert (w/'portfolio.xml.2026-10-10.bak').read_bytes()==SOURCE
        saved=(w/'portfolio.xml').read_bytes(); evidence=(w/'portfolio.xml.pp-pal-imports.properties').read_bytes()
        for override in (False,True):
            expect(run(w,name,True,override),0,'SKIPPED PDF ')
            assert (w/'portfolio.xml').read_bytes()==saved and (w/'portfolio.xml.pp-pal-imports.properties').read_bytes()==evidence
    parallel_cases(check_document, ('basic','missing-gross','two-dividends','mixed'))
    for name,time in [('explicit','09:30:00'),('midnight','00:00:00')]:
        w=fresh(base,name)
        expect(run(w,name,True,True,extra=('--time',time)),0,'APPLIED PDF ')
        oracle(w,name)
        saved=(w/'portfolio.xml').read_bytes()
        expect(run(w,name,True,extra=('--time',time)),0,'SKIPPED PDF ')
        assert (w/'portfolio.xml').read_bytes()==saved
    print('PASS dividends and mixed batches, statement shares, credit/fees/taxes, explicit/midnight/fallback times, preservation and confirmed repeats',flush=True)
    for name in ('later-negative','later-unsupported','foreign-currency','wrong-direction','conflicting-security','repeated-reference','continuation','quantity-precision'):
        w=fresh(base,name)
        for apply in (False,True):
            expect(run(w,name,apply,True),2,'ERROR:')
            assert (w/'portfolio.xml').read_bytes()==SOURCE and not (w/'portfolio.xml.pp-pal-imports.properties').exists() and not list(w.glob('*.????-??-??.bak'))
    for name in ('later-invalid','contradictory','repeated-tax','bad-time','unknown-line'):
        w=fresh(base,'pp-authority-'+name)
        expect(run(w,name),0,'PREVIEW PDF ')
        assert (w/'portfolio.xml').read_bytes()==SOURCE
    print('PASS unsupported/invalid later members, later-negative sale, precision and PP failures reject entire document without changes',flush=True)
    w=fresh(base,'supplements')
    for name,extra,text in [('missing-fee',('--fees','1'),'FEES EUR 1.00 (SUPPLEMENT)'),('missing-tax',('--taxes','0.50'),'TAXES EUR 0.50 (SUPPLEMENT)'),('basic',('--time','12:34:56'),'SUPPLEMENT time')]:
        expect(run(w,name,extra=extra),0,text)
        supplemented=fresh(base,'supplement-apply-'+name)
        expect(run(supplemented,name,True,extra=extra),0,'APPLIED PDF DIVIDEND')
        oracle(supplemented,'supplement-time' if name=='basic' else name)
        saved_supplement=(supplemented/'portfolio.xml').read_bytes()
        expect(run(supplemented,name,True,extra=extra),0,'SKIPPED PDF DIVIDEND')
        assert (supplemented/'portfolio.xml').read_bytes()==saved_supplement
    for apply in (False,True):
        expect(run(w,'missing-booked',apply,extra=('--total','23.50','--date','2025-03-01')),2,'Portfolio Performance extraction failed')
    for extra in [('--total','24'),('--gross','26'),('--fees','2'),('--taxes','0'),('--quantity','11'),('--price','3'),('--isin','DE0000000002')]:
        expect(run(w,extra=extra),2,'ERROR:')
    expect(run(w,'mixed',extra=('--fees','1')),2,'single-member')
    expect(run(w,cash='Synthetic Cash'),2,'ERROR:')
    expect(run(w,extra=('--security-id','00000000-0000-4000-8000-000000000002')),2,'ERROR:')
    w=fresh(base,'ambiguous-security',(ROOT/'fixtures/v1/ambiguous-purchase.xml').read_bytes())
    expect(run(w,apply=True),2,'Ambiguous')
    expect(run(w,apply=True,extra=('--security-id','00000000-0000-4000-8000-000000000001')),0,'APPLIED PDF DIVIDEND')
    ambiguous_cash=SOURCE.replace(b'Synthetic Cash B',b'Synthetic Cash A')
    w=fresh(base,'ambiguous-cash',ambiguous_cash)
    expect(run(w,apply=True),2,'Ambiguous'); assert (w/'portfolio.xml').read_bytes()==ambiguous_cash
    expect(run(w,apply=True,cash='00000000-0000-4000-8000-000000000003'),0,'APPLIED PDF DIVIDEND')
    print('PASS missing-only supplements, authoritative amount/identity conflicts, account/security selection and multi-member supplement refusal',flush=True)
    # The same security is closed in B. Its dividend neither needs a securities account nor reopens its holding.
    # B's closed fixture holding originally references the dollar security; use the tested EUR security.
    closed_source=SOURCE.replace(b'<security reference="3"/>',b'<security reference="2"/>')
    w=fresh(base,'closed',closed_source)
    before=subprocess.run([LAUNCHER,'holdings','--file',str(w/'portfolio.xml'),'--include-zero','--limit','0'],cwd=w,text=True,capture_output=True); expect(before,0,'Synthetic Euro Instrument')
    expect(run(w,cash='Synthetic Cash B',apply=True),0,'APPLIED PDF DIVIDEND'); oracle(w,'basic','00000000-0000-4000-8000-000000000010')
    after=subprocess.run([LAUNCHER,'holdings','--file',str(w/'portfolio.xml'),'--include-zero','--limit','0'],cwd=w,text=True,capture_output=True); assert before.stdout==after.stdout
    history=subprocess.run([LAUNCHER,'history','--file',str(w/'portfolio.xml'),'--securities-account','Synthetic Securities B','--type','dividend','--limit','0'],cwd=w,text=True,capture_output=True); expect(history,0,'23.50')
    print('PASS closed holding dividend preserves quantities/holding timestamps and appears in relevant history',flush=True)
    w=fresh(base,'uncertain')
    expect(run(w,apply=True),0,'APPLIED PDF DIVIDEND'); oracle(w,'basic')
    saved=(w/'portfolio.xml').read_bytes(); metadata=w/'portfolio.xml.pp-pal-imports.properties'; evidence=metadata.read_bytes()
    expect(run(w,apply=True,override=True,extra=('--time','12:00:00')),2,'confirmed broker reference')
    for mode in ('missing','stale','restored','external'):
        (w/'portfolio.xml').write_bytes(saved); metadata.write_bytes(evidence)
        if mode=='missing': metadata.unlink()
        elif mode=='stale': metadata.write_bytes(evidence.replace(b'SYNTHETIC-DIVIDEND',b'SYNTHETIC-STALE'))
        elif mode=='restored': (w/'portfolio.xml').write_bytes(SOURCE)
        else: (w/'portfolio.xml').write_bytes(saved+b'\n')
        expect(run(w),0,'SUSPECTED DUPLICATE'); expect(run(w,apply=True),2,'--allow-suspected-duplicates')
    (w/'portfolio.xml').write_bytes(saved); metadata.unlink(missing_ok=True)
    expect(run(w,'similar-shares'),0,'SUSPECTED DUPLICATE')
    expect(run(w,'similar-shares',True),2,'--allow-suspected-duplicates')
    assert (w/'portfolio.xml').read_bytes()==saved
    print('PASS cash evidence confirmation, changed confirmed values refuse override, and missing/stale/restored/externally edited evidence remains uncertain',flush=True)
    w=fresh(base,'partial-confirmation')
    expect(run(w,apply=True),0,'APPLIED PDF DIVIDEND')
    expect(run(w,'mixed',True,True),0,'APPLIED PDF TRADE'); oracle(w,'mixed')
    expect(run(w,'mixed',True),0,'SKIPPED PDF TRADE')
    migration=(ROOT/'fixtures/v1/migrations/model69.xml').read_bytes(); w=fresh(base,'migration',migration)
    expect(run(w,apply=True),0,'APPLIED PDF DIVIDEND'); oracle(w,'basic')
    assert len(list(w.glob('portfolio.xml.pp-pal.migration.*.bak')))==1 and list(w.glob('portfolio.xml.pp-pal.migration.*.bak'))[0].read_bytes()==migration
    w=fresh(base,'metadata-failure'); (w/'.pp-pal-disposable-fixture').write_text('pp-pal-write-observer-v1\n')
    server=socket.socket(socket.AF_UNIX); server.bind(str(w/'observer.sock')); server.listen(1); server.settimeout(30); errors=[]
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
    expect(run(w,apply=True,observer=True),4,'financial change succeeded; do not repeat apply')
    server.close(); thread.join(timeout=1); assert not errors,errors; oracle(w,'basic')
    expect(run(w,apply=True),4,'Previous financial replacement already succeeded')
    (w/'portfolio.xml.pp-pal-imports.properties').rmdir()
    r=subprocess.run([LAUNCHER,'import','--file',str(w/'portfolio.xml'),'--recover-write','--desktop-closed'],cwd=w,text=True,capture_output=True)
    expect(r,0,'RECOVERED'); oracle(w,'basic'); expect(run(w,apply=True),0,'SKIPPED PDF DIVIDEND')
    print('PASS partial confirmation across mixed types, exact migration protection and metadata recovery without financial replay',flush=True)
PY
python3 "$root/tests/oracle.py" "$PP_PAL_RUNTIME" PdfTextOracle "$root/fixtures"/v1/pdf-dividend
