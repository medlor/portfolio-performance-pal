#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/migrations.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_RUNTIME=$1; fi
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/migrations/model69.xml" "$work/portfolio.xml"
cd "$work"
"$launcher" purchase --file portfolio.xml --security DE0000000001 \
  --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' \
  --date 2025-03-01 --quantity 2.5 --price 10 --fees 1 --taxes .5 > preview
rg -Fq 'OUTPUT MODEL 70 (input 69; migration 69 -> 70)' preview
cmp portfolio.xml "$root/fixtures/v1/migrations/model69.xml"
test -z "$(find . -name '*.migration.*.bak' -o -name '*.????-??-??.bak')"
echo 'PASS model69 purchase preview discloses migration without persistence or financial backups'
# A pre-existing daily backup cannot replace exact operation-input protection.
printf 'older daily snapshot\n' > portfolio.xml.2026-10-08.bak
cp portfolio.xml before.xml
PP_PAL_NOW=2026-10-08T12:00:00Z "$launcher" purchase --file portfolio.xml --security DE0000000001 \
  --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' \
  --date 2025-03-01 --quantity 2.5 --price 10 --fees 1 --taxes .5 --apply --desktop-closed > applied
rg -q '^INPUT MODEL 69$' applied
rg -q '^OUTPUT MODEL 70$' applied
rg -q '^MIGRATION BACKUP ' applied
cmp before.xml portfolio.xml.pp-pal.migration.*.bak
test "$(cat portfolio.xml.2026-10-08.bak)" = 'older daily snapshot'
python3 "$root/tests/oracle.py" "$PP_PAL_RUNTIME" PurchaseAppliedOracle "$work"/before.xml "$work"/portfolio.xml 1
echo 'PASS migration application, exact separate backup, preserved daily backup and unrelated financial state'
# Update validation compares migration output under an independent baseline JVM.
mkdir trusted
python3 "$root/tests/oracle.py" "$PP_PAL_RUNTIME" SelectedPortfolioOracle "$work"/before.xml "$root"/fixtures/v1/portfolio.xml "$work"/trusted
python3 -u - "$root" <<'PY'
#!/usr/bin/env python3
"""Migration behavior at the CLI and Linux filesystem boundaries."""
import hashlib
import os
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading

ROOT = pathlib.Path(sys.argv[1])
RUNTIME = os.environ['PP_PAL_RUNTIME']
LAUNCHER = os.environ.get('PP_PAL_BEHAVIOR_LAUNCHER', str(ROOT/'tools/run-engine'))
SOURCE = (ROOT/'fixtures/v1/migrations/model69.xml').read_bytes()
NOW = '2026-10-08T12:00:00Z'


def run(work, command='purchase', apply=False, env=None, changes=None):
    options = {'--file': str(work/'portfolio.xml'), '--security': 'DE0000000001',
               '--cash-account': 'Synthetic Cash A', '--date': '2025-03-01', '--fees': '1', '--taxes': '.5'}
    if command == 'dividend':
        options['--gross'] = '25'
    elif command in ('purchase', 'sale'):
        options.update({'--securities-account': 'Synthetic Securities A', '--quantity': '2.5', '--price': '10'})
    else:
        options = {'--file': str(work/'portfolio.xml')}
    options.update(changes or {})
    args = [LAUNCHER, command] + [x for k, v in options.items() for x in (k, v)]
    if apply:
        args += ['--apply', '--desktop-closed']
    return subprocess.run(args, cwd=work, text=True, capture_output=True,
                          env=dict(os.environ, PP_PAL_NOW=NOW, **(env or {})))


def expect(result, status, text):
    assert result.returncode == status and text in result.stdout+result.stderr, result.stdout+result.stderr


def fixture(base, name):
    work = base/name
    work.mkdir()
    (work/'portfolio.xml').write_bytes(SOURCE)
    (work/'portfolio.xml.pp-pal.json').write_bytes(b'opaque migration metadata\n')
    return work


def unchanged(work, source=SOURCE):
    assert (work/'portfolio.xml').read_bytes() == source
    assert (work/'portfolio.xml.pp-pal.json').read_bytes() == b'opaque migration metadata\n'


def host(work, path):
    # Former container paths: /work is the case directory, /fixtures the fixture tree.
    for prefix, target in (('/work', str(work)), ('/fixtures', str(ROOT / 'fixtures'))):
        if path == prefix or path.startswith(prefix + '/'):
            return target + path[len(prefix):]
    return path

def oracle(work, name, *args):
    subprocess.run(['python3', str(ROOT/'tests/oracle.py'), RUNTIME, name,
                    *(host(work, arg) for arg in args)], check=True)


def observe(work, action):
    (work/'.pp-pal-disposable-fixture').write_text('pp-pal-write-observer-v1\n')
    observer = work/'observer.sock'
    server = socket.socket(socket.AF_UNIX)
    server.bind(str(observer))
    server.listen(2)
    errors = []

    def serve():
        try:
            while True:
                server.settimeout(15)
                connection, _ = server.accept()
                with connection:
                    message = b''
                    while message.count(b'\n') < 3:
                        part = connection.recv(4096)
                        assert part, 'observer disconnected'
                        message += part
                    phase = message.decode().splitlines()[0]
                    action(phase)
                    connection.sendall(('ACK '+phase+'\n').encode())
                if phase == 'committed-before-follow-up':
                    return
        except (TimeoutError, OSError):
            pass
        except BaseException as error:
            errors.append(error)

    thread = threading.Thread(target=serve, daemon=True)
    thread.start()
    result = run(work, apply=True, env={'PP_PAL_WRITE_OBSERVER': str(observer)})
    server.close()
    thread.join(timeout=16)
    observer.unlink()
    assert not thread.is_alive() and not errors, errors
    return result


with tempfile.TemporaryDirectory(prefix='pp-pal-migrations-') as temporary:
    base = pathlib.Path(temporary)
    work = fixture(base, 'readonly')
    for command in ('holdings', 'history', 'purchase', 'sale', 'dividend'):
        expect(run(work, command), 0, 'OUTPUT MODEL 70 (input 69; migration 69 -> 70)' if command in ('purchase', 'sale', 'dividend') else 'Synthetic')
        unchanged(work)
    assert not list(work.glob('*.????-??-??.bak')) and not list(work.glob('*.migration.*.bak'))
    subprocess.run([str(ROOT/'tools/engine'), 'main', 'roundtrip', str(work/'portfolio.xml'), str(work/'disposable.xml')],
                   env=dict(os.environ, PP_PAL_RUNTIME=RUNTIME), check=True)
    oracle(work, 'MigrationOracle', '/work/portfolio.xml', '/fixtures/v1/portfolio.xml', '/work/disposable.xml')
    oracle(work, 'FixtureOracle', '/work/portfolio.xml')
    oracle(work, 'FixtureOracle', '/work/disposable.xml')
    # This trusted oracle must also work on the previously supported wrapper runtime.
    oracle(work, 'SelectedPortfolioOracle', '/work/portfolio.xml', '/work/disposable.xml', '/work')
    unchanged(work)
    print('PASS queries, all financial previews and disposable migration preserve original/import state', flush=True)

    for command in ('sale', 'dividend'):
        work = fixture(base, command)
        (work/'before.xml').write_bytes(SOURCE)
        expect(run(work, command, True), 0, 'INPUT MODEL 69\nOUTPUT MODEL 70')
        backups = list(work.glob('*.migration.*.bak'))
        assert len(backups) == 1 and backups[0].read_bytes() == SOURCE
        assert (work/'portfolio.xml.2026-10-08.bak').read_bytes() == SOURCE
        assert b'<version>70</version>' in (work/'portfolio.xml').read_bytes()
        if command == 'sale':
            oracle(work, 'SaleAppliedOracle', '/work/before.xml', '/work/portfolio.xml', '2.5', '2025-03-01T11:00:01')
        else:
            oracle(work, 'DividendAppliedOracle', '/work/before.xml', '/work/portfolio.xml',
                   '00000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000001',
                   '2025-03-01T11:00:00', '00000000-0000-4000-8000-000000000005')
    print('PASS sale/dividend migrations preserve unrelated state and exact daily/additional snapshots', flush=True)

    work = fixture(base, 'distinct')
    daily = work/'portfolio.xml.2026-10-08.bak'
    daily.write_bytes(b'earlier daily protection\n')
    expect(run(work, apply=True), 0, 'MIGRATION BACKUP')
    first = next(work.glob('*.migration.*.bak'))
    changed = SOURCE.replace(b'preserve-me', b'new-original-setting')
    (work/'portfolio.xml').write_bytes(changed)
    expect(run(work, apply=True), 0, 'MIGRATION BACKUP')
    backups = list(work.glob('*.migration.*.bak'))
    assert len(backups) == 2 and first.read_bytes() == SOURCE
    assert next(p for p in backups if p != first).read_bytes() == changed
    assert daily.read_bytes() == b'earlier daily protection\n'
    # A later same-version financial change has no new migration backup.
    expect(run(work, apply=True), 0, 'INPUT MODEL 70\nOUTPUT MODEL 70')
    assert len(list(work.glob('*.migration.*.bak'))) == 2
    print('PASS every migration gets a distinct exact operation-input snapshot; model70 writes add none', flush=True)

    work = fixture(base, 'backup-failure')
    expect(run(work, 'backup-monthly'), 0, '')
    (work/'portfolio.xml.2026-10-08.bak').write_bytes(b'existing daily\n')
    index = work/'portfolio.xml.pp-pal-backups.properties'
    index.chmod(0o400)
    expect(run(work, apply=True), 3, 'index is not writable')
    unchanged(work)
    assert not list(work.glob('*.migration.*.bak'))
    index.chmod(0o600)
    for version in (68, 71):
        invalid = SOURCE.replace(b'<version>69</version>', f'<version>{version}</version>'.encode())
        (work/'portfolio.xml').write_bytes(invalid)
        expect(run(work, apply=True), 3, 'Unsupported input')
        unchanged(work, invalid)
    (work/'portfolio.xml').write_bytes(SOURCE)
    expect(run(work, apply=True, changes={'--quantity':'0'}), 2, 'quantity')
    unchanged(work)
    assert not list(work.glob('*.migration.*.bak'))
    print('PASS required migration backup failure, unsupported paths and financial rejection leave input unchanged', flush=True)

    work = fixture(base, 'stale')
    def stale(phase):
        if phase == 'before-final-check':
            (work/'portfolio.xml').write_bytes(SOURCE+b'\n')
    expect(observe(work, stale), 3, 'changed')
    unchanged(work, SOURCE+b'\n')
    assert next(work.glob('*.migration.*.bak')).read_bytes() == SOURCE
    assert (work/'portfolio.xml.2026-10-08.bak').read_bytes() == SOURCE
    print('PASS stale input refused after protection; migration snapshot retains validated original', flush=True)

    work = fixture(base, 'retention-recovery')
    expect(run(work, 'backup-monthly'), 0, '')
    index = work/'portfolio.xml.pp-pal-backups.properties'
    seeded = []
    with index.open('a') as records:
        for label, created in [('old','2024-10-08T11:59:59Z'), ('boundary','2024-10-08T12:00:00Z'),
                               ('new','2024-10-08T12:00:01Z')]:
            name = f'portfolio.xml.pp-pal.migration.{label}.bak'
            content = ('independent '+label+' snapshot\n').encode()
            (work/name).write_bytes(content)
            records.write(f'{name}.kind=migration\n{name}.created={created}\n{name}.sha256={hashlib.sha256(content).hexdigest()}\n')
            seeded.append(work/name)
    expect(run(work), 0, 'migration 69 -> 70')
    assert all(p.exists() for p in seeded), 'preview pruned migration backups'
    def fail_cleanup(phase):
        if phase == 'committed-before-follow-up':
            index.chmod(0o400)
    expect(observe(work, fail_cleanup), 4, 'financial change succeeded; do not repeat apply')
    assert b'<version>70</version>' in (work/'portfolio.xml').read_bytes()
    committed = (work/'portfolio.xml').read_bytes()
    assert all(p.exists() for p in seeded), 'failed cleanup lost recovery intent'
    index.chmod(0o600)
    result = subprocess.run([LAUNCHER, 'purchase', '--file', str(work/'portfolio.xml'), '--recover-write', '--desktop-closed'],
                            cwd=work, env=dict(os.environ, PP_PAL_NOW=NOW), text=True, capture_output=True)
    expect(result, 0, 'INPUT MODEL 69\nOUTPUT MODEL 70')
    assert (work/'portfolio.xml').read_bytes() == committed
    assert not seeded[0].exists() and seeded[1].exists() and seeded[2].exists()
    assert len(list(work.glob('*.migration.*.bak'))) == 3
    unchanged_metadata = (work/'portfolio.xml.pp-pal.json').read_bytes()
    assert unchanged_metadata == b'opaque migration metadata\n'
    print('PASS two-year Berlin retention boundary and committed migration recovery without financial replay', flush=True)

PY
