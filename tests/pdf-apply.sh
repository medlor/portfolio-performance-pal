#!/usr/bin/env bash
set -euo pipefail
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
root=$(cd "$(dirname "$0")/.." && pwd)
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export PP_PAL_NOW=2026-10-07T12:00:00Z
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio_performance.xml"
chmod 600 "$work/portfolio_performance.xml"
cd "$work"
"$launcher" import --pdf "$root/fixtures/v1/pdf-purchase/two-purchases.pdf" --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --apply --desktop-closed --allow-suspected-duplicates > applied
rg -q '^APPLIED PDF PURCHASE DOCUMENT$' applied
cmp portfolio_performance.xml.2026-10-07.bak "$root/fixtures/v1/portfolio.xml"
"$launcher" history --limit 0 > history
rg -Fq '2025-01-03T09:30' history
rg -Fq '2025-01-04T09:30' history
echo 'PASS complete PDF purchase batch through command boundary'
python3 "$root/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" -v "$work:/work:ro" --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/PdfAppliedOracle.java /fixtures/v1/portfolio.xml /work/portfolio_performance.xml 2
cp portfolio_performance.xml after
"$launcher" import --pdf "$root/fixtures/v1/pdf-purchase/two-purchases.pdf" --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --apply --desktop-closed > repeated
rg -q '^SKIPPED PDF PURCHASE DOCUMENT' repeated
cmp portfolio_performance.xml after
echo 'PASS confirmed repeat skips without financial changes'
export PP_PAL_TEST_ROOT="$root"
python3 -u - "$PP_PAL_IMAGE" <<'PY'
import os, pathlib, re, shutil, signal, socket, struct, subprocess, sys, tempfile, threading
ROOT = pathlib.Path(os.environ['PP_PAL_TEST_ROOT'])
IMAGE = sys.argv[1]
LAUNCHER = os.environ.get('PP_PAL_BEHAVIOR_LAUNCHER', str(ROOT/'tools/run-container'))
SOURCE = (ROOT/'fixtures/v1/portfolio.xml').read_bytes()
PDF = ROOT/'fixtures/v1/pdf-purchase/two-purchases.pdf'

def check(value, detail):
    if not value: raise AssertionError(detail)

def fixture(base, name, source=SOURCE):
    work = base/name; work.mkdir()
    (work/'portfolio.xml').write_bytes(source); (work/'portfolio.xml').chmod(0o600)
    shutil.copyfile(PDF, work/'document.pdf')
    return work

def command(work, apply=False, override=False, pdf=None, extra=(), observer=False):
    args = ['import', '--file', str(work/'portfolio.xml'), '--pdf', str(pdf or work/'document.pdf'), '--securities-account', 'Synthetic Securities A', '--cash-account', 'Synthetic Cash A']
    if apply: args += ['--apply', '--desktop-closed']
    if override: args += ['--allow-suspected-duplicates']
    args += list(extra)
    env = dict(os.environ, PP_PAL_IMAGE=IMAGE, PP_PAL_NOW='2026-10-07T12:00:00Z')
    if observer: env['PP_PAL_WRITE_OBSERVER'] = str(work/'observer.sock')
    return subprocess.run([LAUNCHER,*args], cwd=work, env=env, text=True, capture_output=True)

def expect(result, code, text):
    check(result.returncode == code and text in result.stdout+result.stderr, f'expected {code} {text}: {result.returncode}\n{result.stdout}\n{result.stderr}')

def metadata(work): return work/'portfolio.xml.pp-pal-imports.properties'

def oracle(work, count=2, original=None):
    before = work/'before.xml'; before.write_bytes(original or SOURCE)
    result = subprocess.run(['python3',str(ROOT/'tests/oracle.py'),'run','--rm','--network','none','--user',f'{os.getuid()}:{os.getgid()}', '-v',f'{ROOT}/runtime/src/test/java:/tests:ro','-v',f'{work}:/work:ro','--entrypoint','java',IMAGE,'-cp','/opt/pp/classes:/opt/pp/lib/*','/tests/pal/PdfAppliedOracle.java','/work/before.xml','/work/portfolio.xml',str(count)],text=True,capture_output=True)
    expect(result,0,'PASS saved PDF batch')

def observe(work, action, crash=None):
    (work/'.pp-pal-disposable-fixture').write_text('pp-pal-write-observer-v1\n')
    server=socket.socket(socket.AF_UNIX); server.bind(str(work/'observer.sock')); server.listen(2)
    errors=[]
    def serve():
        try:
            while True:
                server.settimeout(20); connection,_=server.accept()
                with connection:
                    message=b''
                    while message.count(b'\n') < 3:
                        part=connection.recv(4096)
                        if not part: raise AssertionError('observer disconnected')
                        message+=part
                    phase,_,_=message.decode().splitlines()
                    if phase==crash:
                        pid,uid,gid=struct.unpack('3i',connection.getsockopt(socket.SOL_SOCKET,socket.SO_PEERCRED,12))
                        check(uid==os.getuid(),'observer owner mismatch')
                        os.kill(pid,signal.SIGKILL); return
                    action(phase)
                    connection.sendall(('ACK '+phase+'\n').encode())
                    if phase=='committed-before-follow-up': return
        except (OSError,TimeoutError): pass
        except BaseException as error: errors.append(error)
    thread=threading.Thread(target=serve,daemon=True); thread.start()
    result=command(work,True,True,observer=True)
    server.close(); thread.join(timeout=1)
    check(not errors,str(errors)); return result

with tempfile.TemporaryDirectory(prefix='pp-pal-pdf-apply-') as temporary:
    base=pathlib.Path(temporary)
    work=fixture(base,'uncertainty')
    preview=command(work); expect(preview,0,'SUSPECTED DUPLICATE member 2')
    check('SUSPECTED DUPLICATE member 2' in preview.stdout,'price similarity not listed')
    check(not metadata(work).exists() and (work/'portfolio.xml').read_bytes()==SOURCE,'preview changed evidence or portfolio')
    expect(command(work,True),2,'--allow-suspected-duplicates')
    check(not list(work.glob('*.????-??-??.bak')) and (work/'portfolio.xml').read_bytes()==SOURCE,'uncertain apply created financial/daily changes')
    expect(command(work,True,True),0,'APPLIED PDF PURCHASE DOCUMENT'); oracle(work)
    check(metadata(work).stat().st_mode & 0o777 == 0o600,'private import metadata mode')
    before=(work/'portfolio.xml').read_bytes(); evidence=metadata(work).read_bytes(); receipt=(work/'portfolio.xml.pp-pal-write.properties').read_bytes()
    old=work/'portfolio.xml.2020-01-01.bak'; old.write_bytes(b'unowned untouched\n')
    for override in (False,True):
        expect(command(work,True,override),0,'SKIPPED PDF PURCHASE DOCUMENT')
        check((work/'portfolio.xml').read_bytes()==before and metadata(work).read_bytes()==evidence and (work/'portfolio.xml.pp-pal-write.properties').read_bytes()==receipt and old.read_bytes()==b'unowned untouched\n','confirmed skip touched state')
    # A different PDF byte identity with the same broker reference is still confirmed.
    expect(command(work,True,pdf=ROOT/'fixtures/v1/pdf-purchase/paged.pdf'),0,'SKIPPED PDF PURCHASE DOCUMENT')
    check((work/'portfolio.xml').read_bytes()==before,'broker-reference confirmation replayed purchase')
    print('PASS preview lists all suspects, apply refuses uncertainty, override imports whole batch and confirmed hashes/references never replay',flush=True)
    for name, alter in [
        ('missing',lambda w: metadata(w).unlink()),
        ('malformed',lambda w: metadata(w).write_text('broken evidence\n')),
        ('invalid-utf8',lambda w: metadata(w).write_bytes(b'\xff\xfe')),
        ('stale-id',lambda w: metadata(w).write_text(re.sub(r'(\.trade=)[^\n]+',r'\g<1>00000000-0000-4000-8000-999999999999',metadata(w).read_text()))),
        ('inconsistent-signature',lambda w: metadata(w).write_text(re.sub(r'(\.signature=)[^\n]+',r'\g<1>invalid',metadata(w).read_text()))),
        ('external-edit',lambda w: (w/'portfolio.xml').write_bytes((w/'portfolio.xml').read_bytes().replace(b'Synthetic Euro Instrument',b'Externally renamed instrument'))),
        ('restored-backup',lambda w: (w/'portfolio.xml').write_bytes(SOURCE))]:
        w=fixture(base,name); (w/'portfolio.xml').write_bytes(before); metadata(w).write_bytes(evidence); alter(w)
        current=(w/'portfolio.xml').read_bytes(); m=metadata(w).read_bytes() if metadata(w).exists() else None
        expect(command(w),0,'SUSPECTED DUPLICATE')
        expect(command(w,True),2,'--allow-suspected-duplicates')
        check((w/'portfolio.xml').read_bytes()==current and (metadata(w).read_bytes() if metadata(w).exists() else None)==m,'uncertain evidence rejection changed bytes')
    w=fixture(base,'override-missing'); (w/'portfolio.xml').write_bytes(before)
    expect(command(w,True,True),0,'APPLIED PDF PURCHASE DOCUMENT'); oracle(w,4)
    expect(command(w,True,True),0,'SKIPPED PDF PURCHASE DOCUMENT'); oracle(w,4)
    print('PASS missing, malformed, stale, inconsistent, external and restored evidence fall back to suspects; override authorizes uncertainty only',flush=True)
    w=fixture(base,'partial-confirmed')
    expect(command(w,True,True,pdf=ROOT/'fixtures/v1/pdf-purchase/paged.pdf'),0,'APPLIED PDF PURCHASE DOCUMENT'); oracle(w,1)
    expect(command(w,True),2,'--allow-suspected-duplicates')
    expect(command(w,True,True),0,'APPLIED PDF PURCHASE DOCUMENT'); oracle(w,2)
    expect(command(w,True,True),0,'SKIPPED PDF PURCHASE DOCUMENT')
    print('PASS mixed confirmed/new members apply only new purchases and never override confirmed identity',flush=True)
    w=fixture(base,'changed-supplement')
    missing=ROOT/'fixtures/v1/pdf-purchase/missing-time.pdf'
    expect(command(w,True,True,pdf=missing),0,'APPLIED PDF PURCHASE DOCUMENT')
    current=(w/'portfolio.xml').read_bytes()
    expect(command(w,True,True,pdf=missing,extra=('--time','12:00:00')),2,'confirmed broker reference')
    check((w/'portfolio.xml').read_bytes()==current,'supplement override replayed a confirmed document')
    print('PASS changed supplements cannot override established current broker identity',flush=True)
    for name in ('later-missing-reference','bad-time'):
        w=fixture(base,'invalid-'+name)
        expect(command(w,True,True,pdf=ROOT/f'fixtures/v1/pdf-purchase/{name}.pdf'),2,'ERROR:')
        check((w/'portfolio.xml').read_bytes()==SOURCE and not metadata(w).exists() and not list(w.glob('*.????-??-??.bak')),'invalid later member partially applied')
    print('PASS PP extraction errors reject entire financial document despite override',flush=True)
    for name,count in [('later-unsupported-sale',2),('later-unknown-line',2),('contradictory-gross',1)]:
        w=fixture(base,'pp-authority-'+name)
        expect(command(w,True,True,pdf=ROOT/f'fixtures/v1/pdf-purchase/{name}.pdf'),0,'APPLIED PDF ')
        # Independent literal facts: PP recognizes the sale title even though
        # the invented cash-direction line still says debit.
        (w/'before.xml').write_bytes(SOURCE)
        first='SYNTHETIC-0001' if count==1 else 'SYNTHETIC-PAGED-0001'
        facts=f'{first}\tBUY\t2\t5100\t5000\t100\t0\t2025-01-03T09:30:00\n'
        if count==2:
            kind='SELL' if name=='later-unsupported-sale' else 'BUY'
            gross=7700 if kind=='SELL' else 7500
            facts+=f'SYNTHETIC-PAGED-0002\t{kind}\t3\t7600\t{gross}\t100\t0\t2025-01-04T09:30:00\n'
        (w/'facts.tsv').write_text(facts)
        result=subprocess.run(['python3',str(ROOT/'tests/oracle.py'),'run','--rm','--network','none','--user',f'{os.getuid()}:{os.getgid()}', '-v',f'{ROOT}/runtime/src/test/java:/tests:ro','-v',f'{w}:/work:ro','--entrypoint','java',IMAGE,'-cp','/opt/pp/classes:/opt/pp/lib/*','/tests/pal/PdfSaleAppliedOracle.java','/work/before.xml','/work/portfolio.xml','/work/facts.tsv'],text=True,capture_output=True)
        expect(result,0,'PASS saved PDF sale batch')
        saved=(w/'portfolio.xml').read_bytes()
        expect(command(w,True,pdf=ROOT/f'fixtures/v1/pdf-purchase/{name}.pdf'),0,'SKIPPED PDF ')
        check((w/'portfolio.xml').read_bytes()==saved,'PP-authoritative confirmed document replayed')
    print('PASS PP-ignored content preserves authoritative saved purchase facts and confirmed repeats',flush=True)
    broken=SOURCE.replace(b'<shares>400000000</shares>',b'<shares>2000000000</shares>')
    w=fixture(base,'later-negative-history',broken)
    expect(command(w,True,True),2,'negative holding at 2025-02-02')
    check((w/'portfolio.xml').read_bytes()==broken and not metadata(w).exists(),'later-negative history partly applied')
    print('PASS duplicate override cannot bypass full later quantity-history validation',flush=True)
    for name, action, text in [
        ('stale-pdf',lambda w: (w/'document.pdf').write_bytes(b'changed PDF'), 'PDF changed'),
        ('stale-portfolio',lambda w: (w/'portfolio.xml').write_bytes(SOURCE+b'\n'), 'changed'),
        ('stale-metadata',lambda w: metadata(w).write_text('external metadata\n'), 'metadata changed')]:
        w=fixture(base,name)
        expect(observe(w,lambda phase: action(w) if phase=='before-final-check' else None),2 if name=='stale-pdf' else 3,text)
        check((w/'portfolio.xml').read_bytes() in (SOURCE,SOURCE+b'\n'),'stale input was replaced')
    print('PASS late PDF/portfolio/metadata changes refuse financial replacement',flush=True)
    w=fixture(base,'metadata-failure')
    expect(observe(w,lambda phase: metadata(w).mkdir() if phase=='committed-before-follow-up' else None),4,'financial change succeeded; do not repeat apply')
    oracle(w)
    expect(command(w,True,True),4,'Previous financial replacement already succeeded')
    metadata(w).rmdir()
    result=subprocess.run([LAUNCHER,'import','--file',str(w/'portfolio.xml'),'--recover-write','--desktop-closed'],cwd=w,env=dict(os.environ,PP_PAL_IMAGE=IMAGE),text=True,capture_output=True)
    expect(result,0,'RECOVERED pending cleanup'); oracle(w)
    expect(command(w,True),0,'SKIPPED PDF PURCHASE DOCUMENT')
    print('PASS post-replacement metadata failure reports financial success, blocks replay and recovers evidence without financial insertion',flush=True)
    for phase in ('before-final-check','committed-before-follow-up'):
        w=fixture(base,'crash-'+phase)
        expect(observe(w,lambda phase: None,crash=phase),3,'Docker command failed')
        check(not metadata(w).exists(),'crash wrote import evidence prematurely')
        result=subprocess.run([LAUNCHER,'import','--file',str(w/'portfolio.xml'),'--recover-write','--desktop-closed'],cwd=w,env=dict(os.environ,PP_PAL_IMAGE=IMAGE),text=True,capture_output=True)
        expect(result,0,'RECOVERED' if phase=='committed-before-follow-up' else 'NO FINANCIAL CHANGE')
        if phase=='before-final-check':
            check((w/'portfolio.xml').read_bytes()==SOURCE and not metadata(w).exists(),'uncommitted crash recovery wrote financial/import data')
        else:
            oracle(w); expect(command(w,True),0,'SKIPPED PDF PURCHASE DOCUMENT')
    print('PASS hard interruption before/after replacement recovers without financial replay or premature evidence',flush=True)
    migration=(ROOT/'fixtures/v1/migrations/model69.xml').read_bytes()
    w=fixture(base,'migration',migration)
    expect(command(w,True,True),0,'APPLIED PDF PURCHASE DOCUMENT'); oracle(w,2,migration)
    backups=list(w.glob('portfolio.xml.pp-pal.migration.*.bak'))
    check(len(backups)==1 and backups[0].read_bytes()==migration,'PDF migration exact input backup missing')
    print('PASS PDF batch uses shared protected model69 migration path',flush=True)
PY
