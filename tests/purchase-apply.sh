#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/purchase-apply.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
export PP_PAL_NOW=2026-10-07T12:00:00Z
if [[ ${2:-} != --boundaries-only ]]; then
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio_performance.xml"
chmod 600 "$work/portfolio_performance.xml"
cd "$work"
printf 'opaque unchanged import metadata\n' > portfolio_performance.xml.pp-pal.json
cp portfolio_performance.xml.pp-pal.json metadata-before
apply() { "$launcher" purchase --security DE0000000001 --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 2.5 --price 10 --fees 1 --taxes 0.5 --apply "$@"; }
rc=0
apply > rejection 2>&1 || rc=$?
test "$rc" = 2
rg -Fq -- '--apply requires --desktop-closed' rejection
cmp portfolio_performance.xml "$root/fixtures/v1/portfolio.xml"
apply --desktop-closed > applied
rg -q '^APPLIED PURCHASE$' applied
rg -q '^TRANSACTIONS ' applied
cmp portfolio_performance.xml.2026-10-07.bak "$root/fixtures/v1/portfolio.xml"
cmp portfolio_performance.xml.pp-pal.json metadata-before
test "$(stat -c %a portfolio_performance.xml)" = 600
test "$(stat -c %u portfolio_performance.xml)" = "$(id -u)"
test "$(stat -c %g portfolio_performance.xml)" = "$(id -g)"
python3 "$root/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" -v "$root/fixtures:/fixtures:ro" -v "$root/runtime/src/test/java:/tests:ro" -v "$work:/work:ro" --entrypoint java "$PP_PAL_IMAGE" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/PurchaseAppliedOracle.java /fixtures/v1/portfolio.xml /work/portfolio_performance.xml 1
echo 'PASS declared desktop-closed fresh purchase commits linked entries with exact daily recovery and preserved complete unrelated model'
fi
export PP_PAL_TEST_ROOT="$root"
python3 -u - "$PP_PAL_IMAGE" <<'PY'
#!/usr/bin/env python3
"""Actual Linux container/filesystem cases; all financial oracles use pinned Java."""
import datetime, fcntl, hashlib, os, pathlib, shutil, signal, socket, struct, subprocess, sys, tempfile, threading
ROOT = pathlib.Path(os.environ["PP_PAL_TEST_ROOT"])
IMAGE = sys.argv[1]
NOW = '2026-10-07T12:00:00Z'
BUY = ['purchase', '--file', '/work/portfolio.xml', '--security', 'DE0000000001', '--securities-account', 'Synthetic Securities A', '--cash-account', 'Synthetic Cash A', '--date', '2025-03-01', '--quantity', '2.5', '--price', '10', '--fees', '1', '--taxes', '.5', '--apply', '--desktop-closed']
SOURCE = (ROOT / 'fixtures/v1/portfolio.xml').read_bytes()

def check(value, message):
    if not value: raise AssertionError(message)

def run(work, args=BUY, now=NOW, observer=False):
    command = ['docker', 'run', '--rm', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}', '-v', f'{work}:/work:rw', '--env', f'PP_PAL_NOW={now}']
    if observer: command += ['--env', 'PP_PAL_WRITE_OBSERVER=/work/observer.sock']
    return subprocess.run(command + [IMAGE] + args, text=True, capture_output=True)

def fixture(base, name):
    work = base / name; work.mkdir(); (work / 'portfolio.xml').write_bytes(SOURCE); (work / 'portfolio.xml').chmod(0o600)
    (work / 'portfolio.xml.pp-pal.json').write_bytes(b'opaque metadata sentinel\n')
    return work

def unchanged(work):
    check((work / 'portfolio.xml').read_bytes() == SOURCE, str(work) + ': original changed')
    check((work / 'portfolio.xml.pp-pal.json').read_bytes() == b'opaque metadata sentinel\n', 'metadata changed')

def expect(result, code, text):
    check(result.returncode == code and text in result.stdout + result.stderr, f'expected {code} {text}: {result.returncode}\n{result.stdout}\n{result.stderr}')

def oracle(work, count=1):
    result = subprocess.run(['python3', str(ROOT/'tests/oracle.py'), 'run', '--rm', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}', '-v', f'{ROOT}/fixtures:/fixtures:ro', '-v', f'{ROOT}/runtime/src/test/java:/tests:ro', '-v', f'{work}:/work:ro', '--entrypoint', 'java', IMAGE, '-cp', '/opt/pp/classes:/opt/pp/lib/*', '/tests/pal/PurchaseAppliedOracle.java', '/fixtures/v1/portfolio.xml', '/work/portfolio.xml', str(count)], text=True, capture_output=True)
    check(result.returncode == 0, result.stdout + result.stderr)

def observe(work, action):
    (work / '.pp-pal-disposable-fixture').write_text('pp-pal-write-observer-v1\n')
    server = socket.socket(socket.AF_UNIX); server.bind(str(work / 'observer.sock')); server.listen(2)
    errors = []
    def serve():
        try:
            while True:
                server.settimeout(20)
                connection, _ = server.accept()
                with connection:
                    message = b''
                    while message.count(b'\n') < 3:
                        part = connection.recv(4096)
                        if not part: raise AssertionError('observer client disconnected')
                        message += part
                    phase, _, staging = message.decode().splitlines()
                    pid, uid, gid = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                    check(uid == os.getuid(), 'fixture peer UID mismatch')
                    reply = action(phase, work / pathlib.Path(staging).parent.name / 'portfolio.xml' if staging else None, pid)
                    try: connection.sendall(((reply if isinstance(reply,str) else 'ACK ' + phase) + '\n').encode())
                    except BrokenPipeError: pass
                    if phase == 'committed-before-follow-up': return
        except (TimeoutError, OSError): pass
        except BaseException as error: errors.append(error)
    thread = threading.Thread(target=serve, daemon=True); thread.start()
    result = run(work, observer=True)
    server.close(); thread.join(timeout=1)
    check(not errors, str(errors))
    return result

def seed(work, daily=(), additional=()):
    # Literal public v1 ownership records. Expected survivor sets below are independent literals.
    index = work / 'portfolio.xml.pp-pal-backups.properties'
    records = ['format=pp-pal-backups-v1', 'portfolio=portfolio.xml']
    for date in daily:
        name = 'portfolio.xml.' + date + '.bak'
        content = ('independent daily fixture ' + date + '\n').encode()
        (work / name).write_bytes(content)
        records += [name+'.kind=daily', name+'.date='+date, name+'.created='+date+'T12:00:00Z', name+'.sha256='+hashlib.sha256(content).hexdigest()]
    for label, created in additional:
        kind = 'monthly' if label == 'monthly-old' else 'update'
        name = 'portfolio.xml.pp-pal.' + kind + '.' + label + '.bak'
        content = ('independent update fixture ' + label + '\n').encode(); (work / name).write_bytes(content)
        records += [name+'.kind='+kind, name+'.created='+created, name+'.sha256='+hashlib.sha256(content).hexdigest()]
        if kind == 'monthly': records.append(name+'.month=2024-10')
    index.write_text('\n'.join(records)+'\n'); index.chmod(0o600)

with tempfile.TemporaryDirectory(prefix='pp-pal-write-boundaries-') as temporary:
    base = pathlib.Path(temporary)
    for name, setup, text in [
        ('readonly-input', lambda w: (w/'portfolio.xml').chmod(0o400), 'must be writable'),
        ('readonly-directory', lambda w: w.chmod(0o500), 'pp-pal-backups.lock'),
        ('lock-symlink', lambda w: (w/'portfolio.xml.pp-pal-write.lock').symlink_to('portfolio.xml'), 'Unsafe nonregular writer'),
        ('daily-symlink', lambda w: (w/'portfolio.xml.2026-10-07.bak').symlink_to('portfolio.xml'), 'regular files'),
        ('daily-directory', lambda w: (w/'portfolio.xml.2026-10-07.bak').mkdir(), 'regular files'),
        ('receipt-symlink', lambda w: (w/'portfolio.xml.pp-pal-write.properties').symlink_to('portfolio.xml'), 'Unsafe financial receipt')]:
        work = fixture(base, name); setup(work)
        result = run(work); expect(result, 3, text); unchanged(work); work.chmod(0o700)
    work = fixture(base, 'locked')
    with (work/'portfolio.xml.pp-pal-write.lock').open('w') as held:
        fcntl.lockf(held, fcntl.LOCK_EX | fcntl.LOCK_NB)
        expect(run(work), 3, 'Another financial writer'); unchanged(work)
        other = fixture(base, 'independent-file'); expect(run(other), 0, 'APPLIED PURCHASE'); oracle(other)
    work = fixture(base, 'foreign-daily'); sentinel = b'foreign exact untouched bytes\n'; daily = work/'portfolio.xml.2026-10-07.bak'; daily.write_bytes(sentinel)
    expect(run(work), 0, 'APPLIED PURCHASE'); check(daily.read_bytes()==sentinel, 'foreign backup overwritten')
    check('portfolio.xml.2026-10-07.bak.kind' not in (work/'portfolio.xml.pp-pal-backups.properties').read_text(), 'foreign backup adopted'); oracle(work)
    expect(run(work), 0, 'APPLIED PURCHASE'); check(daily.read_bytes()==sentinel, 'second daily overwritten'); oracle(work, 2)
    work=fixture(base,'required-index-failure')
    expect(run(work,['backup-monthly','--file','/work/portfolio.xml']),0,'')
    (work/'portfolio.xml.pp-pal-backups.properties').chmod(0o400)
    expect(run(work),3,'index is not writable'); unchanged(work)
    check(not (work/'portfolio.xml.2026-10-07.bak').exists(),'required protection failure created daily')
    work=fixture(base,'unreadable-foreign-daily'); (work/'portfolio.xml.2026-10-07.bak').write_bytes(b'foreign'); (work/'portfolio.xml.2026-10-07.bak').chmod(0)
    expect(run(work),3,'unreadable'); unchanged(work)
    print('PASS actual permissions, nonregular paths, independent writer locks and existing daily nonoverwriting', flush=True)

    for name, action, text in [
        ('same-stat-edit', lambda phase,w,s,p: ((w/'portfolio.xml').write_bytes(SOURCE.replace(b'preserve-me', b'changed-me!')), os.utime(w/'portfolio.xml', ns=( (w/'portfolio.xml').stat().st_atime_ns, stamp[0]))) if phase=='before-final-check' else None, 'changed since'),
        ('inode-replacement', lambda phase,w,s,p: ( (w/'replacement').write_bytes(SOURCE), os.replace(w/'replacement', w/'portfolio.xml')) if phase=='before-final-check' else None, 'changed since'),
        ('stage-corrupt', lambda phase,w,s,p: s.write_bytes(b'invalid financial output') if phase=='before-final-check' else None, 'changed since'),
        ('stage-symlink', lambda phase,w,s,p: (s.unlink(), s.symlink_to('../portfolio.xml')) if phase=='before-final-check' else None, 'regular file'),
        ('rename-failure', lambda phase,w,s,p: w.chmod(0o500) if phase=='before-final-check' else None, 'portfolio.xml -> portfolio.xml')]:
        work = fixture(base, name); stamp=[(work/'portfolio.xml').stat().st_mtime_ns]
        result = observe(work, lambda phase,s,p: action(phase,work,s,p)); expect(result, 3, text); work.chmod(0o700)
        if name=='same-stat-edit': check((work/'portfolio.xml').read_bytes()==SOURCE.replace(b'preserve-me',b'changed-me!'), 'external edit overwritten')
        else: unchanged(work)
        check((work/'portfolio.xml.2026-10-07.bak').read_bytes()==SOURCE, 'required daily snapshot was not exact original')
    print('PASS changed bytes with restored timestamp, replaced inode, corrupt/symlink stage and failed atomic rename preserve external/original bytes', flush=True)

    for name, action in [('post-index-permission', lambda w: (w/'portfolio.xml.pp-pal-backups.properties').chmod(0o400)), ('post-directory-permission', lambda w: w.chmod(0o500)), ('post-receipt-permission', lambda w: (w/'portfolio.xml.pp-pal-write.properties').chmod(0o400))]:
        work = fixture(base, name); seed(work, daily=['2024-10-01'])
        expect(observe(work, lambda phase,s,p: action(work) if phase=='committed-before-follow-up' else None), 4, 'financial change succeeded; do not repeat apply')
        oracle(work); output=(work/'portfolio.xml').read_bytes()
        if name != 'post-receipt-permission': check((work/'portfolio.xml.2024-10-01.bak').exists(), 'failed cleanup pruned')
        work.chmod(0o700); (work/'portfolio.xml.pp-pal-backups.properties').chmod(0o600); (work/'portfolio.xml.pp-pal-write.properties').chmod(0o600)
        expect(run(work), 4, 'Previous financial replacement already succeeded'); check((work/'portfolio.xml').read_bytes()==output, 'pending repeat inserted')
        expect(run(work, ['purchase','--file','/work/portfolio.xml','--recover-write','--desktop-closed']), 0, 'no financial records inserted')
        check((work/'portfolio.xml').read_bytes()==output and not (work/'portfolio.xml.2024-10-01.bak').exists(), 'recovery changed financial state or failed cleanup')
    print('PASS postreplacement failures report committed exit4, block replay, and verified recovery only finishes cleanup', flush=True)

    work=fixture(base,'observer-refusal'); expect(run(work,observer=True),3,'explicitly marked disposable'); unchanged(work)
    work=fixture(base,'post-observer-refusal'); expect(observe(work,lambda phase,s,p: 'WRONG' if phase=='committed-before-follow-up' else None),4,'financial change succeeded'); oracle(work)
    for phase in ['before-final-check','committed-before-follow-up']:
        work=fixture(base, 'interrupt-'+phase)
        result=observe(work, lambda seen,s,p: os.kill(p, signal.SIGKILL) if seen==phase else None)
        check(result.returncode != 0, 'interrupted process reported success')
        if phase=='before-final-check': unchanged(work); text='NO FINANCIAL CHANGE'
        else: oracle(work); text='no financial records inserted'; expect(run(work),4,'Previous financial replacement already succeeded')
        output=(work/'portfolio.xml').read_bytes()
        expect(run(work,['purchase','--file','/work/portfolio.xml','--recover-write','--desktop-closed']),0,text)
        check((work/'portfolio.xml').read_bytes()==output, 'interruption recovery replayed')
    print('PASS deterministic process interruption before/after rename resolves exact input/output without replay', flush=True)

    work=fixture(base,'retention')
    dates=['2024-10-31','2024-11-01','2024-11-02','2024-11-03','2024-11-04','2025-01-01','2025-01-02','2026-10-01','2026-10-02','2026-10-03','2026-10-04','2026-11-01']
    seed(work, daily=dates, additional=[('monthly-old','2024-10-07T11:59:59Z'),('old','2024-10-07T11:59:59Z'),('cutoff','2024-10-07T12:00:00Z'),('new','2024-10-07T12:00:01Z'),('future','2027-10-07T12:00:00Z')])
    foreign=work/'portfolio.xml.2020-01-01.bak'; foreign.write_bytes(b'foreign old backup')
    before={p.name:p.read_bytes() for p in work.glob('*.bak')}
    expect(run(work,BUY[:-2]),0,'PREVIEW PURCHASE'); check(all((work/n).read_bytes()==v for n,v in before.items()),'preview pruned')
    expect(run(work,BUY+['--total','1']),2,'contradict'); check(all((work/n).read_bytes()==v for n,v in before.items()),'failed plan pruned')
    expect(run(work),0,'APPLIED PURCHASE'); oracle(work)
    expected={'2024-11-02','2024-11-03','2024-11-04','2025-01-01','2025-01-02','2026-10-03','2026-10-04','2026-10-07','2026-11-01','2020-01-01'}
    actual={p.name.removeprefix('portfolio.xml.').removesuffix('.bak') for p in work.glob('portfolio.xml.????-??-??.bak')}
    check(actual==expected,f'daily retention: {actual}')
    check(not (work/'portfolio.xml.pp-pal.update.old.bak').exists() and not (work/'portfolio.xml.pp-pal.monthly.monthly-old.bak').exists(), 'old monthly/update additional retained')
    for label in ['cutoff','new','future']: check((work/f'portfolio.xml.pp-pal.update.{label}.bak').read_bytes()==before[f'portfolio.xml.pp-pal.update.{label}.bak'],'additional boundary/future removed')
    check(foreign.read_bytes()==b'foreign old backup','foreign backup pruned')
    print('PASS literal current+23month top-three/fewer-than-three/future daily retention and strict two-year additional cutoff only after real apply',flush=True)

    for name, alter in [('tampered',lambda w: (w/'portfolio.xml.2024-10-01.bak').write_bytes(b'tampered')), ('malformed',lambda w: (w/'portfolio.xml.pp-pal-backups.properties').write_text((w/'portfolio.xml.pp-pal-backups.properties').read_text().replace('.date=2024-10-01','.date=not-a-date')))]:
        work=fixture(base,name); seed(work,daily=['2024-10-01']); alter(work)
        expect(run(work),4,'financial change succeeded'); oracle(work); check((work/'portfolio.xml.2024-10-01.bak').exists(),'unverified backup deleted')
    for instant,date in [('2025-12-31T22:59:59Z','2025-12-31'),('2025-12-31T23:00:00Z','2026-01-01'),('2026-03-28T23:30:00Z','2026-03-29'),('2026-03-29T01:30:00Z','2026-03-29'),('2026-10-24T22:30:00Z','2026-10-25'),('2026-10-25T01:30:00Z','2026-10-25')]:
        work=fixture(base,'calendar-'+instant.replace(':',''))
        expect(run(work,now=instant),0,'APPLIED PURCHASE'); check((work/f'portfolio.xml.{date}.bak').read_bytes()==SOURCE,'Berlin date mismatch')
    print('PASS malformed/tampered ownership refuses deletion after committed write; Berlin DST daily calendar boundaries',flush=True)

PY
