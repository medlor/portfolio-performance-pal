#!/usr/bin/env python3
"""Updater behavior through the owner launcher and a local release API boundary."""
import contextlib
import http.server
import hashlib
import fcntl
import io
import tarfile
import urllib.request
import json
import os
import pathlib
import shutil
import signal
import subprocess
import sys
import tempfile
import threading

ROOT = pathlib.Path(__file__).resolve().parents[1]
IMAGE = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('PP_PAL_IMAGE', 'pp-pal:0.88.0')

class ReleaseServer(http.server.ThreadingHTTPServer):
    def __init__(self):
        self.routes = {}
        self.requests = []
        self.before = {}
        super().__init__(('127.0.0.1', 0), ReleaseHandler)

class ReleaseHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.requests.append(self.path)
        callback = self.server.before.get(self.path)
        if callback: callback()
        value = self.server.routes.get(self.path)
        if value is None:
            self.send_error(404)
            return
        if isinstance(value, int):
            self.send_error(value)
            return
        data = value if isinstance(value, bytes) else json.dumps(value).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except BrokenPipeError:
            pass
    def log_message(self, *args):
        pass

@contextlib.contextmanager
def server():
    instance = ReleaseServer()
    thread = threading.Thread(target=instance.serve_forever, daemon=True)
    thread.start()
    try:
        yield instance
    finally:
        instance.shutdown()
        instance.server_close()
        thread.join()

def run(work, api, *args, **changes):
    environment = dict(os.environ, PP_PAL_IMAGE=IMAGE, PP_PAL_STATE_DIR=str(work / 'state'),
                       PP_PAL_RELEASE_API=f'http://127.0.0.1:{api.server_port}',
                       PP_PAL_NOW='2026-10-07T12:00:00Z')
    environment.update(changes)
    return subprocess.run([str(ROOT / 'pp-pal'), *args], cwd=work, env=environment,
                          text=True, capture_output=True)

def check(condition, detail):
    if not condition:
        raise AssertionError(detail)

if '--production-prerequisite' in sys.argv[2:]:
    with tempfile.TemporaryDirectory(prefix='pp-pal-production-prerequisite-') as temporary:
        work = pathlib.Path(temporary)
        selected = work / 'selected synthetic portfolio.xml'
        shutil.copyfile(ROOT / 'fixtures/v1/portfolio.xml', selected)
        original = selected.read_bytes()
        transport = type('ProductionTransport', (), {'server_port': 0})()
        result = run(work, transport, '--update', '--file', str(selected), PP_PAL_RELEASE_API='https://api.github.com')
        check(result.returncode == 3 and 'No stable released wrapper' in result.stderr, result.stderr + result.stdout)
        check(selected.read_bytes() == original and not (work / 'state/active.json').exists(), 'Production release prerequisite changed source/active state')
        print('PASS official production discovery reports absent stable wrapper bootstrap release and preserves synthetic input')
    sys.exit(0)

with tempfile.TemporaryDirectory(prefix='pp-pal-updater-') as temporary, server() as api:
    work = pathlib.Path(temporary)
    selected = work / 'selected portfolio.xml'
    shutil.copyfile(ROOT / 'fixtures/v1/portfolio.xml', selected)
    original = selected.read_bytes()
    result = run(work, api, '--update', '--file', str(selected))
    check(result.returncode == 3, f'Expected release prerequisite failure 3; got {result.returncode}: {result.stderr}')
    check('released wrapper' in result.stderr.lower(), result.stderr)
    check('/repos/medlor/portfolio-performance-pal/releases/latest' in api.requests, api.requests)
    check(selected.read_bytes() == original, 'Update changed selected portfolio')
    check(not (work / 'state/active.json').exists(), 'Failed discovery installed active pair')
    print('PASS missing released wrapper fails closed and preserves selected input/active state')
    for arguments in (
        ('--update', '--file', str(selected), '--file', str(selected)),
        ('--update', '--config', 'x', '--config', 'x'),
        ('--update', '--update'),
        ('--update', '--fi', str(selected)),
        ('--update', '--file=' + str(selected)),
        ('--update', '--file'),
        ('--update', '--file', ''),
        ('--update', '--apply'),
        ('--update', '--file', str(work / 'absent.xml')),
        ('--update', '--config', str(work / 'absent.properties')),
    ):
        result = run(work, api, *arguments)
        check(result.returncode == 2, f'Invalid update options must reject2: {arguments}: {result.stderr}')
    print('PASS update options enforce command-first separate values without repetitions or abbreviations')
    if '--arguments-only' in sys.argv[2:]:
        sys.exit(0)


def sha(data):
    return hashlib.sha256(data).hexdigest()

def released_snapshot(work, api, tag='v0.1.0', mutate=None):
    package = work / ('released-' + tag)
    existing = package.exists()
    if not existing: package.mkdir()
    for name in (() if existing else ('runtime', 'tools', 'tests', 'fixtures')):
        shutil.copytree(ROOT / name, package / name, ignore=shutil.ignore_patterns('__pycache__'))
    for name in (() if existing else ('pp-pal', 'Dockerfile', '.dockerignore')):
        shutil.copyfile(ROOT / name, package / name)
    (package / 'pp-pal').chmod(0o755)
    if mutate and not existing:
        mutate(package)
    if not existing:
        subprocess.run(['git', 'init', '-q', str(package)], check=True)
        subprocess.run(['git', '-C', str(package), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(package), '-c', 'user.name=Fixture Release', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'Synthetic released wrapper snapshot'], check=True)
        subprocess.run(['git', '-C', str(package), 'tag', '-a', tag, '-m', 'Synthetic stable release'], check=True, env=dict(os.environ, GIT_COMMITTER_NAME='Fixture Release', GIT_COMMITTER_EMAIL='fixture@example.invalid'))
    revision = subprocess.check_output(['git', '-C', str(package), 'rev-parse', 'HEAD'], text=True).strip()
    tag_object = subprocess.check_output(['git', '-C', str(package), 'rev-parse', tag], text=True).strip()
    archive = subprocess.check_output(['git', '-C', str(package), 'archive', '--format=tar.gz', '--prefix=release/', revision])
    repo = '/repos/medlor/portfolio-performance-pal'
    api.routes[repo + '/releases/latest'] = {'tag_name': tag, 'draft': False, 'prerelease': False}
    api.routes[repo + '/git/ref/tags/' + tag] = {'object': {'type': 'tag', 'sha': tag_object}}
    api.routes[repo + '/git/tags/' + tag_object] = {'object': {'type': 'commit', 'sha': revision}}
    api.routes[repo + '/tarball/' + revision] = archive
    return revision

def public_archive(name, url, digest, previous):
    cache = pathlib.Path(tempfile.gettempdir()) / 'pp-pal-test-public-archives'
    cache.mkdir(exist_ok=True)
    target = cache / name
    for candidate in (target, pathlib.Path(previous)):
        if candidate.is_file() and sha(candidate.read_bytes()) == digest:
            return candidate.read_bytes()
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    check(sha(data) == digest, 'Public fixture archive does not match pinned digest')
    target.write_bytes(data)
    return data

def upstream_release(api):
    revision = '8de1fe41d71a2c1b74a6bfbe913f885899aaf123'
    repo = '/repos/portfolio-performance/portfolio'
    asset = 'PortfolioPerformance-0.88.0-linux.gtk.x86_64.tar.gz'
    source = public_archive('source-8de1fe41.tar.gz', 'https://api.github.com/repos/portfolio-performance/portfolio/tarball/' + revision, 'ce6d10fe45ce5484f8c4ee9ae36eb1654890b03f6adb2fbbd3f54077d93cc656', '/tmp/pp-source.tar.gz')
    dependency = public_archive(asset, 'https://github.com/portfolio-performance/portfolio/releases/download/0.88.0/' + asset, '1770d95488a383f3b97e5e7a1b30f9fd122200a16f88078caa4823951e9aeac2', '/tmp/pp-0.88.0.tar.gz')
    check(sha(source) == 'ce6d10fe45ce5484f8c4ee9ae36eb1654890b03f6adb2fbbd3f54077d93cc656', 'Official upstream source cache digest differs')
    check(sha(dependency) == '1770d95488a383f3b97e5e7a1b30f9fd122200a16f88078caa4823951e9aeac2', 'Official upstream dependency cache digest differs')
    base = f'http://127.0.0.1:{api.server_port}'
    api.routes[repo + '/releases/latest'] = {'tag_name': '0.88.0', 'draft': False, 'prerelease': False,
        'assets': [{'name': asset, 'digest': 'sha256:' + sha(dependency), 'browser_download_url': base + '/assets/' + asset}]}
    api.routes[repo + '/git/ref/tags/0.88.0'] = {'object': {'type': 'commit', 'sha': revision}}
    api.routes[repo + '/tarball/' + revision] = source
    api.routes['/assets/' + asset] = dependency

@contextlib.contextmanager
def released_work():
    # Optional developer cache preserves an ACTUALLY validated fixture between slices.
    # The default cumulative gate always uses a fresh isolated release/state.
    fixed = os.environ.get('PP_PAL_UPDATE_GATE_WORK')
    if fixed:
        pathlib.Path(fixed).mkdir(parents=True, exist_ok=True)
        yield fixed
    else:
        with tempfile.TemporaryDirectory(prefix='pp-pal-updater-release-') as temporary:
            yield temporary

with released_work() as temporary, server() as api:
    work = pathlib.Path(temporary)
    selected = work / 'selected portfolio.xml'
    shutil.copyfile(ROOT / 'fixtures/v1/portfolio.xml', selected)
    original = selected.read_bytes().replace(b'preserve-me', b'owner-local-validation-marker-22')
    selected.write_bytes(original)
    metadata = work / 'selected portfolio.xml.pp-pal.json'
    metadata.write_bytes(b'opaque adjacent import metadata sentinel\n')
    def marker(package):
        target = package / 'tools/run-container'
        text = target.read_text()
        target.write_text(text.replace('set -euo pipefail', 'set -euo pipefail\necho RELEASED-LAUNCHER-FIXTURE >&2', 1))
    prior_attempts = len(list(work.glob('selected portfolio.xml.pp-pal.update.*.bak')))
    was_active = (work / 'state/active.json').exists()
    revision = released_snapshot(work, api, mutate=marker)
    upstream_release(api)
    result = run(work, api, '--update', '--file', str(selected))
    check(result.returncode == 0, f'Actual released pair failed: {result.stderr}\n{result.stdout}')
    active = json.loads((work / 'state/active.json').read_text())
    check(active['wrapper']['revision'] == revision, active)
    check(active['upstream']['revision'] == '8de1fe41d71a2c1b74a6bfbe913f885899aaf123', active)
    check(active['image'].startswith('sha256:'), active)
    check(selected.read_bytes() == original, 'Successful update replaced original')
    check(len(list(work.glob('selected portfolio.xml.pp-pal.update.*.bak'))) == prior_attempts + (0 if was_active else 1), 'Actual update attempt lacks exact snapshot')
    for context in (work / 'state/builds').iterdir():
        for path in context.rglob('*'):
            if path.is_file():
                check(b'owner-local-validation-marker-22' not in path.read_bytes(), 'Selected private bytes entered build context')
    report = (work / 'state/reports' / (active['cacheKey'] + '.json')).read_text()
    check('owner-local-validation-marker-22' not in report, 'Report disclosed selected contents')
    check(metadata.read_bytes() == b'opaque adjacent import metadata sentinel\n', 'Metadata changed')
    print('PASS released immutable pair builds, passes trusted gates, validates local selected copy and activates')

    purchase_args = ('purchase', '--file', str(selected), '--security', 'DE0000000001',
        '--securities-account', 'Synthetic Securities A', '--cash-account', 'Synthetic Cash A',
        '--date', '2025-03-01', '--quantity', '2.5', '--price', '10', '--fees', '1', '--taxes', '0.50')
    request_count = len(api.requests)
    result = run(work, api, *purchase_args)
    check(result.returncode == 0 and 'PREVIEW PURCHASE' in result.stdout and 'BOOKED DEBIT EUR 26.50 (derived' in result.stdout, result.stderr + result.stdout)
    check('UNCHANGED' in result.stderr and 'ENGINE IMAGE ' + active['image'] in result.stdout, 'Preview did not use the freshly checked fixed pair')
    check('/repos/medlor/portfolio-performance-pal/releases/latest' in api.requests[request_count:] and '/repos/portfolio-performance/portfolio/releases/latest' in api.requests[request_count:], 'Preview omitted mandatory releases')
    check(selected.read_bytes() == original and metadata.read_bytes() == b'opaque adjacent import metadata sentinel\n', 'Preview changed financial/import bytes')
    check(not list(work.glob('selected portfolio.xml.????-??-??.bak')) and not list(work.glob('selected portfolio.xml.pp-pal.migration.*.bak')), 'Preview created daily/migration backups')
    print('PASS mandatory-release fixed-engine purchase preview on released image ' + active['image'])
    # Owner supplied qualification signals never grant candidate reuse.
    result = run(work, api, *purchase_args, PP_PAL_GATE_SOCKET=str(work / 'expired.sock'))
    check(result.returncode == 3 and 'qualification' in result.stderr.lower() and 'PREVIEW PURCHASE' not in result.stdout, result.stderr)
    import socket
    fake = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    fake_path = str(work / 'fake.sock'); fake.bind(fake_path); fake.listen(1)
    def fake_qualifier():
        connection, _ = fake.accept()
        with connection:
            try: connection.sendall(json.dumps(active).encode())
            except BrokenPipeError: pass
    fake_thread = threading.Thread(target=fake_qualifier, daemon=True); fake_thread.start()
    try:
        result = run(work, api, *purchase_args, PP_PAL_GATE_SOCKET=fake_path)
        check(result.returncode == 3 and 'trusted updater' in result.stderr.lower() and 'PREVIEW PURCHASE' not in result.stdout, result.stderr)
    finally: fake.close(); fake_thread.join(timeout=5)
    # A generic development-image override cannot change the checked active pair.
    result = run(work, api, *purchase_args, PP_PAL_IMAGE='missing-owner-image')
    check(result.returncode == 0 and 'ENGINE IMAGE ' + active['image'] in result.stdout, result.stderr)
    wrapper_route = '/repos/medlor/portfolio-performance-pal/releases/latest'
    api.before[wrapper_route] = lambda: selected.write_bytes(original + b'\n')
    try:
        result = run(work, api, *purchase_args)
        check(result.returncode == 3 and 'changed' in result.stderr.lower() and 'PREVIEW PURCHASE' not in result.stdout, result.stderr)
    finally:
        api.before.pop(wrapper_route); selected.write_bytes(original)
    wrapper_release = api.routes.pop(wrapper_route)
    try:
        result = run(work, api, *purchase_args)
        check(result.returncode == 3 and 'released wrapper' in result.stderr.lower() and 'PREVIEW PURCHASE' not in result.stdout, result.stderr)
    finally: api.routes[wrapper_route] = wrapper_release
    check(selected.read_bytes() == original and metadata.read_bytes() == b'opaque adjacent import metadata sentinel\n', 'Failed preview protection changed financial bytes')
    print('PASS owner-injected/forged qualification refused, fixed active image, selected stale and release failure stop before preview')
    if '--apply-slice' in sys.argv[2:]:
        api.before[wrapper_route] = lambda: selected.write_bytes(original + b'\n')
        try:
            result = run(work, api, *purchase_args, '--apply', '--desktop-closed')
            check(result.returncode == 3 and 'changed' in result.stderr.lower() and 'APPLIED PURCHASE' not in result.stdout, result.stderr)
        finally:
            api.before.pop(wrapper_route); selected.write_bytes(original)
        wrapper_release = api.routes.pop(wrapper_route)
        try:
            result = run(work, api, *purchase_args, '--apply', '--desktop-closed')
            check(result.returncode == 3 and 'released wrapper' in result.stderr.lower() and 'APPLIED PURCHASE' not in result.stdout, result.stderr)
        finally: api.routes[wrapper_route] = wrapper_release
        check(selected.read_bytes() == original and not list(work.glob('selected portfolio.xml.????-??-??.bak')), 'Rejected apply created financial/daily changes')
        request_count = len(api.requests)
        result = run(work, api, *purchase_args, '--apply', '--desktop-closed')
        check(result.returncode == 0 and 'APPLIED PURCHASE' in result.stdout and 'ENGINE IMAGE ' + active['image'] in result.stdout, result.stderr + result.stdout)
        check('/repos/medlor/portfolio-performance-pal/releases/latest' in api.requests[request_count:] and '/repos/portfolio-performance/portfolio/releases/latest' in api.requests[request_count:], 'Apply omitted mandatory release checks')
        check((work / 'selected portfolio.xml.2026-10-07.bak').read_bytes() == original, 'Owner application daily backup not exact original')
        check(metadata.read_bytes() == b'opaque adjacent import metadata sentinel\n', 'Owner application changed metadata')
        output = selected.read_bytes()
        receipt = work / 'selected portfolio.xml.pp-pal-write.properties'
        check('state=complete' in receipt.read_text(), 'Owner application receipt not finalized')
        result = run(work, api, 'purchase', '--file', str(selected), '--recover-write', '--desktop-closed')
        check(result.returncode == 2 and 'No pending' in result.stderr and selected.read_bytes() == output, 'Completed receipt allowed replay/recovery')
        print('PASS actual released-owner apply uses mandatory fixed pair, exact original daily snapshot and completed nonreplay receipt on ' + active['image'])
        sys.exit(0)
    if '--purchase-slice' in sys.argv[2:]:
        sys.exit(0)

    expected_attempts = len(list(work.glob('selected portfolio.xml.pp-pal.update.*.bak')))
    before = (work / 'state/active.json').read_bytes()
    request_count = len(api.requests)
    result = run(work, api, '--update', '--file', str(selected))
    check(result.returncode == 0 and 'UNCHANGED' in result.stdout, result.stderr + result.stdout)
    check((work / 'state/active.json').read_bytes() == before, 'Unchanged pair rewrote active manifest')
    check(len(list(work.glob('selected portfolio.xml.pp-pal.update.*.bak'))) == expected_attempts, 'Unchanged pair created attempt snapshot')
    check(not any('/tarball/' in path or '/assets/' in path for path in api.requests[request_count:]), 'Unchanged pair downloaded/build sources again')
    result = run(work, api, 'holdings', '--file', str(selected), PP_PAL_IMAGE='missing-development-image')
    check(result.returncode == 0, result.stderr)
    check('RELEASED-LAUNCHER-FIXTURE' in result.stderr, 'Dispatch failed to select activated launcher implementation')
    check('Synthetic Securities A | Synthetic Euro Instrument | 6 | 2025-02-02T11:00:01' in result.stdout, result.stdout)
    check(selected.read_bytes() == original, 'Cache/dispatch changed selected portfolio')
    print('PASS unchanged released pair reuses image without attempt backup and dispatch pins activated launcher/image')

    # An asset may be replaced while its immutable source tag stays unchanged.
    # That is a changed pair, not permission to reuse old dependency validation.
    upstream_metadata = api.routes['/repos/portfolio-performance/portfolio/releases/latest']
    old_digest = upstream_metadata['assets'][0]['digest']
    upstream_metadata['assets'][0]['digest'] = 'sha256:' + '0' * 64
    result = run(work, api, '--update', '--file', str(selected))
    check(result.returncode == 3, 'Changed dependency digest silently reused old validation: ' + result.stdout + result.stderr)
    check('digest' in result.stderr.lower(), result.stderr)
    check((work / 'state/active.json').read_bytes() == before, 'Failed archive check replaced active pair')
    check(selected.read_bytes() == original, 'Failed archive check changed financial input')
    check(len(list(work.glob('selected portfolio.xml.pp-pal.update.*.bak'))) == expected_attempts + 1, 'Changed failed attempt lacks distinct protection')
    upstream_metadata['assets'][0]['digest'] = old_digest
    print('PASS changed official asset digest forces a protected attempt and cannot silently reuse old validation')

    wrapper_path = '/repos/medlor/portfolio-performance-pal/releases/latest'
    wrapper_metadata = api.routes.pop(wrapper_path)
    for command in ('--update', 'purchase'):
        result = run(work, api, command, '--file', str(selected))
        check(result.returncode == 3 and 'released wrapper' in result.stderr.lower(), result.stderr)
        check((work / 'state/active.json').read_bytes() == before, 'Discovery failure replaced activated pair')
        check(selected.read_bytes() == original, 'Discovery failure/preview wrote portfolio')
    api.routes[wrapper_path] = 500
    result = run(work, api, '--update', '--file', str(selected))
    check(result.returncode == 3 and 'discovery failed' in result.stderr.lower(), result.stderr)
    api.routes[wrapper_path] = wrapper_metadata
    print('PASS failed discovery stops update and preview instead of silently processing with the old engine')

    # Public unsupported selection must be checked even when the release pair is cached.
    selected.write_bytes(original.replace(b'<version>70</version>', b'<version>69</version>'))
    unsupported = selected.read_bytes()
    metadata = work / 'selected portfolio.xml.pp-pal.json'
    metadata.write_bytes(b'opaque adjacent import metadata sentinel\n')
    result = run(work, api, '--update', '--file', str(selected))
    check(result.returncode == 3, 'Cached update accepted unsupported selected model: ' + result.stdout)
    check(selected.read_bytes() == unsupported and metadata.read_bytes() == b'opaque adjacent import metadata sentinel\n', 'Selected rejection changed financial/import bytes')
    check((work / 'state/active.json').read_bytes() == before, 'Selected rejection replaced activated pair')
    selected.write_bytes(original)
    print('PASS cached update still validates selected --file and preserves unsupported XML/import metadata')

    requests_before = len(api.requests)
    with (work / 'state/update.lock').open('r+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        result = run(work, api, '--update', '--file', str(selected))
        check(result.returncode == 3 and 'Another wrapper update' in result.stderr, result.stderr)
    check(len(api.requests) == requests_before, 'Competing update performed network/processing')
    check((work / 'state/active.json').read_bytes() == before, 'Competing update changed active pair')
    print('PASS competing updater lock stops before release or financial processing')

    for target in (pathlib.Path(active['launcher']).parent / 'tools/run-container', work / 'state/trusted-validation/fixtures/v1/baader-buy.expected'):
        content = target.read_bytes()
        target.write_bytes(content + b'\nchanged fixture release content\n')
        result = run(work, api, 'holdings', '--file', str(selected))
        check(result.returncode == 3 and 'changed' in result.stderr.lower(), result.stderr)
        check((work / 'state/active.json').read_bytes() == before, 'Content tampering rewrote active manifest')
        target.write_bytes(content)
    print('PASS activated launcher and trusted expectation integrity are checked before dispatch/cache reuse')

    def tamper_selected_only(package):
        source = package / 'runtime/src/main/java/pal/Core.java'
        text = source.read_text()
        needle = 'case "roundtrip" -> { save(client, Path.of(args[2]));'
        replacement = '''case "roundtrip" -> {
                    if ("owner-local-validation-marker-22".equals(client.getProperty("fixture.setting"))) {
                        client.setProperty("fixture.setting", "candidate-changed-unrelated-state");
                        save(client, Path.of(args[1]));
                        save(client, Path.of(args[1]).resolveSibling("trusted-before.xml"));
                    }
                    save(client, Path.of(args[2]));'''
        check(needle in text, 'Synthetic candidate transformation no longer matches reviewed fixture source')
        source.write_text(text.replace(needle, replacement))
    attack_revision = released_snapshot(work, api, tag='v0.2.0', mutate=tamper_selected_only)
    result = run(work, api, '--update', '--file', str(selected))
    check(result.returncode == 3, 'Candidate overwrote independent selected-copy oracle and activated: ' + result.stdout)
    check((work / 'state/active.json').read_bytes() == before, 'Failed selected-copy oracle replaced old pair')
    check(selected.read_bytes() == original and metadata.read_bytes() == b'opaque adjacent import metadata sentinel\n', 'Selected oracle failure changed original/import data')
    print('PASS selected-copy oracle/input are isolated from candidate output and attempted semantic changes retain old pair')

    def changed_bootstrap(package):
        target = package / 'tools/update.py'
        target.write_text(target.read_text() + '\n# changed released host orchestration\n')
    released_snapshot(work, api, tag='v0.3.0', mutate=changed_bootstrap)
    result = run(work, api, '--update', '--file', str(selected))
    check(result.returncode == 3 and 'trusted-bootstrap maintenance' in result.stderr, result.stderr)
    check((work / 'state/active.json').read_bytes() == before, 'Unsupported host bootstrap change activated')
    check(selected.read_bytes() == original, 'Host bootstrap rejection changed portfolio')
    print('PASS changed host bootstrap is rejected explicitly while retaining the released engine pair')

    retained_id = subprocess.check_output(['docker', 'image', 'inspect', active['image'], '--format', '{{.Id}}'], text=True).strip()
    check(retained_id == active['image'], 'Earlier active immutable image vanished after newer builds')
    permanent = 'pp-pal:retained-' + active['image'][7:]
    check(subprocess.check_output(['docker', 'image', 'inspect', permanent, '--format', '{{.Id}}'], text=True).strip() == active['image'], 'Active engine lacks permanent image-derived retention root')
    print('PASS baseline/active images retain immutable identities after later candidate builds')

    # External edits remain intact; the updater must not activate from a stale snapshot.
    released_snapshot(work, api)  # reset latest metadata to the known valid pair
    api.before[wrapper_path] = lambda: selected.write_bytes(original + b'\n')
    result = run(work, api, '--update', '--file', str(selected))
    check(result.returncode == 3 and 'changed during update' in result.stderr.lower(), result.stderr)
    check(selected.read_bytes() == original + b'\n', 'Updater overwrote an external edit')
    check((work / 'state/active.json').read_bytes() == before, 'Stale selected input activated')
    api.before.pop(wrapper_path)
    selected.write_bytes(original)
    print('PASS stale selected file stops activation and preserves the external edit')

    def wrong_query_and_own_tests(package):
        target = package / 'runtime/src/main/java/pal/Holdings.java'
        text = target.read_text().replace('return BigDecimal.valueOf(shares, 8).stripTrailingZeros().toPlainString();', 'return "999";')
        target.write_text(text)
        (package / 'tests/all.sh').write_text('#!/usr/bin/env bash\nexit 0\n')
        (package / 'fixtures/v1/baader-buy.expected').write_text('candidate-controlled expectation\n')
    bad_revision = released_snapshot(work, api, tag='v0.4.0', mutate=wrong_query_and_own_tests)
    attempts = len(list(work.glob('selected portfolio.xml.pp-pal.update.*.bak')))
    for retry in range(2):
        result = run(work, api, '--update', '--file', str(selected))
        check(result.returncode == 3 and 'Local check failed' in result.stderr, result.stderr + result.stdout)
        check((work / 'state/active.json').read_bytes() == before, 'Candidate-only passing tests activated wrong quantities')
        check(selected.read_bytes() == original and metadata.read_bytes() == b'opaque adjacent import metadata sentinel\n', 'Fixed-gate failure changed portfolio/import bytes')
    check(len(list(work.glob('selected portfolio.xml.pp-pal.update.*.bak'))) == attempts + 2, 'Each failed retry must have distinct protection')
    print('PASS trusted fixed expectations reject wrong released behavior despite passing candidate tests, including safe retry')

    paused = threading.Event()
    resume = threading.Event()
    route = '/repos/medlor/portfolio-performance-pal/tarball/' + bad_revision
    def pause_download():
        paused.set()
        resume.wait(15)
    api.before[route] = pause_download
    environment = dict(os.environ, PP_PAL_IMAGE=IMAGE, PP_PAL_STATE_DIR=str(work / 'state'),
                       PP_PAL_RELEASE_API=f'http://127.0.0.1:{api.server_port}', PP_PAL_NOW='2026-10-07T12:00:00Z')
    process = subprocess.Popen([str(ROOT / 'pp-pal'), '--update', '--file', str(selected)], cwd=work, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        check(paused.wait(10), 'Interrupted update never reached the actual-attempt download boundary')
        process.terminate()
        stdout, stderr = process.communicate(timeout=10)
        check(process.returncode != 0, 'Interrupted update completed successfully')
    finally:
        resume.set()
        api.before.pop(route, None)
        if process.poll() is None:
            process.kill()
            process.wait()
    check((work / 'state/active.json').read_bytes() == before, 'Interrupted update activated')
    check(selected.read_bytes() == original, 'Interrupted update changed selected file')
    print('PASS interrupted protected update retains the previous activated implementation/image')
