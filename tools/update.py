#!/usr/bin/env python3
"""Host-only release/download/cache orchestration. Java owns financial semantics."""
import fcntl
import contextlib
import datetime
import hashlib
import http.server
import importlib.util
import json
import os
import pathlib
import platform
import re
import shlex
import shutil
import socket
import struct
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

# Released/trusted trees are immutable qualification inputs. Importing the
# scheduling helper must not add bytecode files and invalidate their digests.
sys.dont_write_bytecode = True
from test_jobs import parallel_cases
import runtime_dir

ROOT = pathlib.Path(__file__).resolve().parents[1]
WRAPPER = 'medlor/portfolio-performance-pal'
UPSTREAM = 'portfolio-performance/portfolio'
SHA = re.compile(r'[a-f0-9]{40}')
IDENTITY = re.compile(r'dev|[0-9A-Za-z._-]+-[a-f0-9]{12}')
DIGEST = re.compile(r'[a-f0-9]{64}')
# Release metadata resolved by this process, replayed to its gates (release_view).
RELEASE_VIEW = {}

class UpdateError(Exception):
    pass

class InputError(UpdateError):
    pass

def state_directory():
    default = pathlib.Path(os.environ.get('XDG_STATE_HOME', str(pathlib.Path.home() / '.local/state'))) / 'pp-pal'
    path = pathlib.Path(os.environ.get('PP_PAL_STATE_DIR', str(default))).expanduser().absolute()
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink() or not path.is_dir():
        raise UpdateError('Updater state must be a regular local directory')
    return path

def api_base():
    base = os.environ.get('PP_PAL_RELEASE_API', 'https://api.github.com').rstrip('/')
    parsed = urllib.parse.urlparse(base)
    if base != 'https://api.github.com' and not (parsed.scheme == 'http' and parsed.hostname == '127.0.0.1' and not parsed.username):
        raise UpdateError('Release transport must be official GitHub or the local fixture HTTP boundary')
    return base

def fetch(url):
    request = urllib.request.Request(url, headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'pp-pal-local-updater'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read()
    except urllib.error.HTTPError as failure:
        raise UpdateError(f'Release request failed (HTTP {failure.code}): {url}') from failure
    except OSError as failure:
        raise UpdateError(f'Release request failed: {failure}') from failure

def request_json(path):
    data = fetch(api_base() + path)
    try:
        value = json.loads(data)
    except (ValueError, TypeError) as failure:
        raise UpdateError('Release service returned invalid JSON') from failure
    RELEASE_VIEW[path] = data
    return value

def release(repository, label):
    try:
        data = request_json('/repos/' + repository + '/releases/latest')
    except UpdateError as failure:
        if 'HTTP 404' in str(failure):
            raise UpdateError(f'No stable released {label} is available; publish the documented bootstrap release package before updating'
                              + (' (tag it exactly like the Portfolio Performance release it supports, e.g. 0.88.0)' if repository == WRAPPER else '')) from failure
        raise UpdateError(f'{label} release discovery failed: {failure}') from failure
    if not isinstance(data, dict):
        raise UpdateError('Release service returned an invalid release object')
    if data.get('draft') or data.get('prerelease') or not isinstance(data.get('tag_name'), str) or not data['tag_name']:
        raise UpdateError(f'No usable stable released {label} was returned')
    tag = data['tag_name']
    obj = request_json('/repos/' + repository + '/git/ref/tags/' + urllib.parse.quote(tag, safe=''))['object']
    if not isinstance(obj, dict):
        raise UpdateError('Release tag reference is not an object')
    seen = set()
    while obj.get('type') == 'tag':
        value = obj.get('sha', '')
        if not SHA.fullmatch(value) or value in seen:
            raise UpdateError('Invalid or cyclic annotated release tag')
        seen.add(value)
        obj = request_json('/repos/' + repository + '/git/tags/' + value)['object']
        if not isinstance(obj, dict):
            raise UpdateError('Annotated release tag is not an object')
    revision = obj.get('sha', '')
    if obj.get('type') != 'commit' or not SHA.fullmatch(revision):
        raise UpdateError('Release tag does not resolve to an immutable source commit')
    return {'tag': tag, 'revision': revision, 'assets': data.get('assets', [])}

def hash_bytes(data):
    return hashlib.sha256(data).hexdigest()

def atomic_json(path, data):
    descriptor, name = tempfile.mkstemp(prefix='.manifest-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            json.dump(data, stream, indent=2, sort_keys=True)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
        descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if os.path.exists(name):
            os.unlink(name)

def execute(command, environment=None, quiet=False, input_errors=False, stream=False):
    if stream:
        # Gate output contains public fixture diagnostics; retain it for failure context.
        with subprocess.Popen(command, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, env=environment, bufsize=1) as process:
            lines = []
            for line in process.stdout:
                print(line, end='', flush=True)
                lines.append(line)
            result = subprocess.CompletedProcess(command, process.wait(), ''.join(lines), ''.join(lines))
    else:
        result = subprocess.run(command, text=True, capture_output=True, env=environment)
    if result.returncode:
        if input_errors and result.returncode == 2:
            raise InputError('Selected file/configuration rejected: ' + result.stderr.strip())
        detail = '' if quiet else ': ' + result.stderr[-4000:]
        raise UpdateError(f'Local check failed ({result.returncode}){detail}')
    return result.stdout.strip()

def runtimes(state):
    path = state / 'runtimes'
    path.mkdir(exist_ok=True)
    return path

def default_runtime(state):
    return pathlib.Path(os.environ.get('PP_PAL_RUNTIME', str(state / 'runtimes/dev'))).expanduser().absolute()

def runtime_identity(path):
    """Validate a runtime directory (see tools/build-runtime) and return its identity."""
    try:
        data = runtime_dir.read_runtime(path)
    except ValueError as failure:
        raise UpdateError(str(failure)) from failure
    if not IDENTITY.fullmatch(data['identity']) or data['platform'] != 'linux/amd64':
        raise UpdateError('Only the fixture-validated Linux amd64 runtime platform is supported')
    return data['identity']

def tree_digest(directory):
    digest = hashlib.sha256()
    for path in sorted(directory.rglob('*')):
        if path.is_symlink():
            raise UpdateError('Cached release/validation bundle contains a symlink')
        if path.is_file():
            digest.update(str(path.relative_to(directory)).encode() + b'\0' + str(path.stat().st_mode & 0o777).encode() + b'\0' + path.read_bytes())
    return digest.hexdigest()

# Trusted harness files copied with the gates. Gates run JDK tools through the
# trusted tools/engine against the runtime under test.
TRUSTED_FILES = ('runtime/candidate-gates.json', 'runtime/release-package.json', 'tests/oracle.py', 'tests/run.py',
                 'tools/test_jobs.py', 'tools/engine', 'tools/runtime_dir.py')

def build_trusted_bundle(source, destination):
    """Copy the independent validation files (gates, fixtures, oracles, harness) from SOURCE."""
    registry = json.loads((source / 'runtime/candidate-gates.json').read_text())
    for name in ('fixtures', 'runtime/src/test/java'):
        shutil.copytree(source / name, destination / name)
    for name in [*registry['checks'], *TRUSTED_FILES]:
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(source / name, target)  # keeps the executable bit of tools/engine

def trusted_changes(old_root, new_root):
    """Relative trusted paths added, changed or removed between two bundles."""
    def files(root):
        return {str(path.relative_to(root)): path for path in root.rglob('*') if path.is_file()}
    old, new = files(old_root), files(new_root)
    return {'added': sorted(set(new) - set(old)), 'removed': sorted(set(old) - set(new)),
            'changed': sorted(name for name in set(old) & set(new)
                              if old[name].read_bytes() != new[name].read_bytes()
                              or old[name].stat().st_mode != new[name].stat().st_mode)}

def trusted_bundle(state):
    target = state / 'trusted-validation'
    marker = state / 'trusted-validation.json'
    if not target.exists():
        if marker.exists():
            raise UpdateError('Trusted validation bundle is missing')
        temporary = pathlib.Path(tempfile.mkdtemp(prefix='.trusted-', dir=state))
        try:
            build_trusted_bundle(ROOT, temporary)
            digest = tree_digest(temporary)
            os.replace(temporary, target)
            atomic_json(marker, {'schema': 1, 'sha256': digest})
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)
    if target.is_symlink() or marker.is_symlink():
        raise UpdateError('Trusted validation paths must not be symlinks')
    metadata = json.loads(marker.read_text())
    digest = tree_digest(target)
    if metadata.get('schema') != 1 or metadata.get('sha256') != digest:
        raise UpdateError('Trusted validation bundle changed; maintenance required')
    return target, digest

def active_manifest(state):
    path = state / 'active.json'
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_file():
        raise UpdateError('Active manifest must be a regular file')
    try:
        data = json.loads(path.read_text())
        package = pathlib.Path(data['launcher']).parent
        if data['schema'] != 1 or not IDENTITY.fullmatch(data['identity']) or not SHA.fullmatch(data['wrapper']['revision']):
            raise ValueError()
        if pathlib.Path(data['runtime']).parent != state / 'runtimes' or runtime_identity(pathlib.Path(data['runtime'])) != data['identity']:
            raise UpdateError('Activated runtime is missing or changed; active pair cannot be dispatched')
        expected = ('local-' if data.get('source') == 'local-upgrade' else '') + data['wrapper']['revision']
        if package.parent != state / 'wrappers' or package.name != expected or not (package / 'pp-pal').is_file():
            raise ValueError()
        if tree_digest(package) != data['wrapper']['treeSha256']:
            raise UpdateError('Activated wrapper content changed; active pair cannot be dispatched')
        trusted, digest = trusted_bundle(state)
        if digest != data['trustedSuiteSha256']:
            raise UpdateError('Activated trusted validation bundle does not match its manifest')
        return data
    except (ValueError, KeyError, TypeError):
        raise UpdateError('Invalid active wrapper/runtime manifest')

def selection(arguments):
    if not arguments or arguments[0] != '--update':
        raise InputError('Put --update first; accepted options are --file PATH and --config PATH')
    result = []
    seen = {'--update'}
    index = 1
    while index < len(arguments):
        key = arguments[index]
        if key not in ('--file', '--config'):
            raise InputError('Unknown update option: ' + key)
        if key in seen:
            raise InputError('Repeated option: ' + key)
        if index + 1 == len(arguments) or not arguments[index + 1] or arguments[index + 1].startswith('--'):
            raise InputError('Missing value for ' + key)
        seen.add(key)
        result.extend(arguments[index:index + 2])
        index += 2
    return result

def launch_environment(runtime):
    environment = dict(os.environ, PP_PAL_RUNTIME=str(runtime))
    # Update protection itself cannot recursively discover/build releases.
    return environment

def snapshot(path):
    before = path.stat()
    data = path.read_bytes()
    after = path.stat()
    identity = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
    if identity != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
        raise UpdateError('Selected portfolio changed while taking its validation snapshot')
    return data, identity

def unchanged(path, expected, identity):
    actual, current = snapshot(path)
    if identity != current or hash_bytes(actual) != hash_bytes(expected):
        raise UpdateError('Selected portfolio changed during update; active pair retained')

def archive_cache(state):
    cache = pathlib.Path(os.environ.get('PP_PAL_ARCHIVE_CACHE', str(state / 'downloads'))).expanduser().absolute()
    cache.mkdir(parents=True, exist_ok=True)
    return cache

def download(state, url, expected=None):
    cache = archive_cache(state)
    if expected is not None:
        # Content-addressed cache: a verified archive with the expected digest is reused.
        cached = cache / (expected + '.tar.gz')
        if cached.is_file() and not cached.is_symlink() and hash_bytes(cached.read_bytes()) == expected:
            return cached, expected
    data = fetch(url)
    digest = hash_bytes(data)
    if expected is not None and digest != expected:
        raise UpdateError('Official release archive digest does not match release metadata')
    path = cache / (digest + '.tar.gz')
    if path.exists():
        if path.is_symlink() or hash_bytes(path.read_bytes()) != digest:
            raise UpdateError('Download cache content changed')
    else:
        with path.open('xb') as stream:
            stream.write(data)
    return path, digest

EXECUTABLES = ('pp-pal', 'tools/run-engine', 'tools/engine', 'tools/build-runtime')

def check_policy(policy, trusted_root):
    """A released candidate may only claim the support policy of the trusted bundle."""
    trusted = json.loads((trusted_root / 'runtime/release-package.json').read_text())
    if any(policy.get(key) != trusted.get(key) for key in ('input', 'output', 'migrations', 'jdk', 'jdkArchive', 'layouts', 'features')):
        raise UpdateError('Unknown support or migration policy; maintenance required')

def extract_wrapper(archive, directory, trusted_root):
    directory.mkdir()
    allowed = ('runtime/', 'tools/', 'pp-pal', 'UPGRADE.md')
    try:
        with tarfile.open(archive, 'r:gz') as source:
            for member in source.getmembers():
                parts = pathlib.PurePosixPath(member.name).parts
                if len(parts) < 2:
                    continue
                relative = pathlib.PurePosixPath(*parts[1:])
                if relative.is_absolute() or '..' in relative.parts or member.issym() or member.islnk() or not (member.isdir() or member.isfile()):
                    raise UpdateError('Unsafe released wrapper archive member')
                name = str(relative)
                if name not in allowed and not name.startswith(('runtime/', 'tools/')):
                    continue
                target = directory / name
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.extractfile(member) as stream, target.open('xb') as output:
                        shutil.copyfileobj(stream, output)
                    target.chmod(0o755 if name in EXECUTABLES else 0o644)
    except (tarfile.TarError, OSError) as failure:
        raise UpdateError('Cannot unpack released wrapper snapshot') from failure
    for name in ('pp-pal', 'tools/update.py', 'tools/test_jobs.py', *EXECUTABLES[1:], 'tools/runtime_dir.py', 'runtime/release-package.json'):
        if not (directory / name).is_file():
            raise UpdateError('Released wrapper lacks the required bootstrap package: ' + name)
    for name in ('pp-pal', 'tools/update.py', 'tools/test_jobs.py'):
        if (directory / name).read_bytes() != (ROOT / name).read_bytes():
            raise UpdateError('Released host bootstrap/validator changed; manual trusted-bootstrap maintenance required')
    policy = json.loads((directory / 'runtime/release-package.json').read_text())
    if policy.get('schema') != 1 or policy.get('buildRecipe') != 2 or policy.get('bootstrapProtocol') != 2 or policy.get('platform') != 'linux/amd64':
        raise UpdateError('Unknown released wrapper build recipe/platform; maintenance required')
    return policy

def trusted_suite(root):
    registry = json.loads((root / 'runtime/candidate-gates.json').read_text())
    if registry.get('schema') != 1 or not registry.get('checks'):
        raise UpdateError('Invalid trusted candidate gate registry')
    for check in registry['checks']:
        if not re.fullmatch(r'tests/[a-z0-9-]+\.sh', check) or not (root / check).is_file():
            raise UpdateError('Invalid trusted candidate gate')
    return registry

def descendant(pid, ancestor):
    for _ in range(128):
        if pid == ancestor:
            return True
        if pid <= 1:
            return False
        try:
            fields = pathlib.Path(f'/proc/{pid}/status').read_text().splitlines()
            pid = int(next(line for line in fields if line.startswith('PPid:')).split()[1])
        except (OSError, StopIteration, ValueError):
            return False
    return False

def candidate_qualification():
    """Only descendants of a live, identical trusted updater can reuse its built pair."""
    address = os.environ.get('PP_PAL_GATE_SOCKET')
    if address is None:
        return None
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
            connection.settimeout(5)
            connection.connect(address)
            pid, uid, _ = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            command = pathlib.Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')
            if uid != os.getuid() or pid == os.getpid() or not descendant(os.getpid(), pid):
                raise UpdateError('Candidate qualification is not from a live parent validator')
            if len(command) < 3 or command[2] not in (b'--update', b'--dispatch'):
                raise UpdateError('Candidate qualification parent is not the trusted updater')
            bootstrap = pathlib.Path(os.fsdecode(command[1]))
            if bootstrap.read_bytes() != pathlib.Path(__file__).read_bytes():
                raise UpdateError('Candidate qualification bootstrap differs from trusted updater')
            with connection.makefile('rb') as stream:
                data = json.loads(stream.read(1024 * 1024))
        if data.get('schema') != 1 or pathlib.Path(data['launcher']).parent != ROOT:
            raise UpdateError('Candidate qualification does not match this released launcher')
        return data
    except (OSError, ValueError, KeyError, TypeError) as failure:
        raise UpdateError('Invalid or expired candidate qualification') from failure

@contextlib.contextmanager
def qualifying_validator(temporary, qualification):
    address = str(pathlib.Path(temporary) / 'qualification.sock')
    stop = threading.Event()
    payload = json.dumps(qualification).encode()
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
        listener.bind(address)
        os.chmod(address, 0o600)
        listener.listen()
        listener.settimeout(0.1)
        def serve():
            while not stop.is_set():
                try:
                    connection, _ = listener.accept()
                except socket.timeout:
                    continue
                with connection:
                    pid, uid, _ = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                    if uid == os.getuid() and pid != os.getpid() and descendant(pid, os.getpid()):
                        try:
                            connection.sendall(payload)
                        except OSError:
                            pass
        thread = threading.Thread(target=serve, daemon=True)
        thread.start()
        try:
            yield address
        finally:
            stop.set()
            thread.join(timeout=2)

@contextlib.contextmanager
def release_view():
    """Serve the release metadata this update resolved, frozen, on loopback.

    Every owner-launcher command in a gate re-resolves both releases (about five API
    requests each). Against the live GitHub API a qualification would exceed the
    unauthenticated rate limit and could see a release published mid-run. Gates get
    this frozen view instead; unknown paths are 404. A loopback fixture base inside
    asset URLs is rebased so upstream_asset() still accepts them."""
    outer = api_base()
    responses = dict(RELEASE_VIEW)
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            data = responses.get(self.path)
            if data is None:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data)
            except BrokenPipeError:
                pass
        def log_message(self, *args):
            pass
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    base = f'http://127.0.0.1:{server.server_port}'
    if outer != 'https://api.github.com':
        responses = {path: data.replace(outer.encode() + b'/', base.encode() + b'/') for path, data in responses.items()}
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield base
    finally:
        server.shutdown()
        server.server_close()
        thread.join()

def fixed_checks(package, runtime, registry, trusted_root, qualification):
    # Import only the independent trusted runner, never a candidate-owned copy.
    spec = importlib.util.spec_from_file_location('pp_pal_test_runner', trusted_root / 'tests/run.py')
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    with runner.test_environment() as test_environment, release_view() as release_api:
        test_environment = dict(test_environment, PP_PAL_RELEASE_API=release_api)
        return fixture_checks(package, runtime, registry, trusted_root, qualification, test_environment)


def fixture_checks(package, runtime, registry, trusted_root, qualification, test_environment):
    with tempfile.TemporaryDirectory(prefix='pp-pal-gate-oracles-') as temporary:
        classes = pathlib.Path(temporary) / 'classes'
        execute([sys.executable, str(trusted_root / 'tests/oracle.py'), '--compile',
                 qualification['baselineRuntime'], str(classes)], test_environment)
        def gate(check):
            # Each gate owns its state/lock/socket; trusted test classes are mounted readonly.
            with tempfile.TemporaryDirectory(prefix='pp-pal-gate-state-') as gate_directory:
                state = pathlib.Path(gate_directory)
                shutil.copytree(trusted_root, state / 'trusted-validation')
                atomic_json(state / 'trusted-validation.json', {'schema': 1, 'sha256': qualification['trustedSuiteSha256']})
                # Qualification attacks deliberately mutate released help/source.
                # Give each gate an identical private package so those attacks cannot
                # interfere with another gate's mandatory tree identity checks.
                gate_package = state / 'released'
                shutil.copytree(package, gate_package)
                if tree_digest(gate_package) != qualification['wrapper']['treeSha256']:
                    raise UpdateError('Candidate gate package differs from qualified release')
                gate_qualification = dict(qualification, launcher=str(gate_package / 'pp-pal'))
                environment = dict(test_environment, PP_PAL_RUNTIME=str(runtime))
                environment.update(PP_PAL_LAUNCHER=str(gate_package / 'pp-pal'),
                                   PP_PAL_BEHAVIOR_LAUNCHER=str(gate_package / 'tools/run-engine'),
                                   PP_PAL_TEST_CLASSES=str(classes), PP_PAL_STATE_DIR=gate_directory,
                                   PP_PAL_GATE_BASELINE_RUNTIME=qualification['baselineRuntime'])
                with qualifying_validator(gate_directory, gate_qualification) as address:
                    environment['PP_PAL_GATE_SOCKET'] = address
                    started = time.monotonic()
                    print('CHECK START ' + check, flush=True)
                    try:
                        execute(['bash', str(trusted_root / check)], environment, stream=True)
                    finally:
                        elapsed = time.monotonic() - started
                        print(f'CHECK END {check} elapsed={elapsed:.2f}s', flush=True)
                    return elapsed, check
        checks = registry['checks']
        # Holdings remains the prerequisite; replace fixed pairs with a rolling
        # queue that drains active gates and stops admission after a failure.
        durations = [gate(checks[0])]
        durations.extend(parallel_cases(gate, checks[1:]))
        print('CHECK SUMMARY slowest: ' + ', '.join(
            f'{check}={elapsed:.2f}s' for elapsed, check in sorted(durations, reverse=True)[:3]), flush=True)

def validate_selected(state, original, baseline_runtime, candidate_runtime):
    # Opaque snapshot bytes stay local. All model/persistence operations are Java.
    # The candidate gets a read-only copy and a fresh output directory; the
    # original selected file is never passed to it.
    engine = str(ROOT / 'tools/engine')
    with tempfile.TemporaryDirectory(prefix='selected-validation-', dir=state) as temporary:
        work = pathlib.Path(temporary)
        source, trusted, output = (work / name for name in ('input', 'trusted', 'output'))
        for directory in (source, trusted, output):
            directory.mkdir()
        (source / 'input.xml').write_bytes(original)
        (source / 'input.xml').chmod(0o400)
        source.chmod(0o500)
        try:
            execute([engine, 'main', 'roundtrip', str(source / 'input.xml'), str(output / 'candidate.xml')],
                    launch_environment(candidate_runtime), quiet=True)
            candidate = output / 'candidate.xml'
            if candidate.is_symlink() or not candidate.is_file():
                raise UpdateError('Candidate did not produce a regular disposable output')
            if sorted(path.name for path in source.iterdir()) != ['input.xml'] or (source / 'input.xml').read_bytes() != original:
                raise UpdateError('Candidate changed the readonly selected validation input')
            # A single independent baseline JVM checks both canonical roundtrips and
            # relationship inspections, using the trusted oracle source.
            oracle = state / 'trusted-validation/runtime/src/test/java/pal/SelectedPortfolioOracle.java'
            environment = launch_environment(baseline_runtime)
            classpath = execute([engine, 'classpath'], environment)
            execute([engine, 'java', '-cp', classpath, str(oracle), str(source / 'input.xml'),
                     str(output / 'candidate.xml'), str(trusted)], environment, quiet=True)
        finally:
            source.chmod(0o700)
            (source / 'input.xml').chmod(0o600)

def upstream_asset(upstream):
    name = 'PortfolioPerformance-' + upstream['tag'] + '-linux.gtk.x86_64.tar.gz'
    assets = [a for a in upstream['assets'] if a.get('name') == name]
    if len(assets) != 1 or not re.fullmatch(r'sha256:[a-f0-9]{64}', assets[0].get('digest', '')):
        raise UpdateError('Official Linux dependency release lacks a unique SHA-256 asset digest')
    url = assets[0].get('browser_download_url', '')
    official = 'https://github.com/' + UPSTREAM + '/releases/download/' + urllib.parse.quote(upstream['tag'], safe='') + '/'
    if not url.startswith(official) and not (api_base().startswith('http://127.0.0.1:') and url.startswith(api_base() + '/')):
        raise UpdateError('Dependency asset is not from the official upstream release')
    return url, assets[0]['digest'][7:]

def verify_candidate_runtime(runtime, wrapper, upstream, policy):
    runtime_identity(runtime)
    labels = runtime_dir.read_runtime(runtime)
    if labels['wrapperRevision'] != wrapper['revision'] or labels['upstreamRevision'] != upstream['revision'] or labels['xmlModel'] != str(policy['output']['modelVersion']):
        raise UpdateError('Candidate runtime revision/model labels disagree with the pinned pair')
    result = subprocess.run([str(runtime / 'jdk/bin/java'), '-version'], text=True, capture_output=True)
    if result.returncode or '21.0.11' not in result.stderr or 'Temurin-21.0.11+10' not in result.stderr:
        raise UpdateError('Candidate Java runtime is not the reviewed Temurin 21.0.11+10 dependency')
    return result.stderr.strip()

def build_runtime(package, inputs, wrapper_revision, state, digests):
    """Build (or reuse an identical) runtime with the released wrapper's own recipe."""
    upstream_tag = json.loads((package / 'runtime/release-package.json').read_text())['upstream']['tag']
    target = runtimes(state) / (upstream_tag + '-' + wrapper_revision[:12])
    if target.exists():
        # The identity fixes the wrapper revision and therefore every pinned input.
        # Different recorded inputs mean corruption or tampering; the directory may
        # be the active runtime, so never delete it here.
        try:
            labels = runtime_dir.read_runtime(target)
        except ValueError as failure:
            raise UpdateError('Runtime ' + target.name + ' exists but is incomplete; remove it manually after checking it is not active') from failure
        if labels.get('inputs') == digests and labels['wrapperRevision'] == wrapper_revision:
            return target
        raise UpdateError('Runtime ' + target.name + ' exists with different inputs; refusing to replace it')
    execute([str(package / 'tools/build-runtime'), '--inputs', str(inputs), '--output', str(target),
             '--wrapper-revision', wrapper_revision])
    return target

class UpgradeRequired(UpdateError):
    pass

def wrapper_tag_matches(tag, upstream_tag):
    """Wrapper releases are tagged exactly like the Portfolio Performance release they
    support (e.g. 0.88.0); later wrapper fixes for that release add -2, -3, ..."""
    return tag == upstream_tag or re.fullmatch(re.escape(upstream_tag) + r'-([2-9]|[1-9][0-9]+)', tag) is not None

def supported(policy, active, upstream):
    """Tested support: the wrapper policy, or a validated local upgrade, names this exact release."""
    def same(declared):
        return isinstance(declared, dict) and declared.get('tag') == upstream['tag'] and declared.get('revision') == upstream['revision']
    if active is not None:
        return active.get('source') == 'local-upgrade' and same(active.get('upstream'))
    return same(policy.get('upstream'))

def upgrade_prompt(upstream, argv, cwd):
    return (f"UPGRADE REQUIRED: Portfolio Performance {upstream['tag']} ({upstream['revision']}) has no tested pp-pal support.\n"
            'Agent prompt:\n'
            f"  Read {ROOT / 'UPGRADE.md'} and perform the pp-pal upgrade to Portfolio\n"
            f"  Performance {upstream['tag']} ({upstream['revision']}). Origin: https://github.com/{WRAPPER}.\n"
            f'  When finished, resume this operation exactly, in directory {cwd}:\n'
            f"  {shlex.join([str(ROOT / 'pp-pal'), *argv])}")

def upgrade_required(state, upstream, argv):
    """Record the request for the agent (UPGRADE.md) and return the stopping error."""
    cwd = os.getcwd()
    prompt = upgrade_prompt(upstream, argv, cwd)
    if not re.fullmatch(r'[0-9A-Za-z._-]+', upstream['tag']):
        raise UpdateError('Unsupported upstream release tag cannot name an upgrade request')
    directory = state / 'upgrades' / upstream['tag']
    directory.mkdir(parents=True, exist_ok=True)
    atomic_json(directory / 'request.json', {'schema': 1, 'upstream': {'tag': upstream['tag'], 'revision': upstream['revision']},
                                             'argv': list(argv), 'cwd': cwd, 'prompt': prompt})
    return UpgradeRequired(prompt)

def perform_update(arguments, original_argv=None):
    original_argv = list(arguments if original_argv is None else original_argv)
    selected_args = selection(arguments)
    qualification = candidate_qualification()
    state = state_directory()
    lock_path = state / 'update.lock'
    descriptor = os.open(lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'r+') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as failure:
            raise UpdateError('Another wrapper update is active') from failure
        trusted_root, suite_hash = trusted_bundle(state)
        active = active_manifest(state)
        baseline_runtime = pathlib.Path(active['runtime']) if active else default_runtime(state)
        runtime_identity(baseline_runtime)
        launcher = str(pathlib.Path(active['launcher']).parent / 'tools/run-engine') if active else str(ROOT / 'tools/run-engine')
        environment = launch_environment(baseline_runtime)
        environment['PP_PAL_RESOLVE_ONLY'] = '1'
        file = pathlib.Path(execute([launcher, 'backup-monthly', *selected_args], environment, input_errors=True))
        original, identity = snapshot(file)
        environment.pop('PP_PAL_RESOLVE_ONLY')
        execute([launcher, 'backup-monthly', *selected_args], environment)
        wrapper = release(WRAPPER, 'wrapper')
        upstream = release(UPSTREAM, 'Portfolio Performance')
        registry = trusted_suite(trusted_root)
        asset_url, official_dependency_hash = upstream_asset(upstream)
        if qualification is not None:
            # A local upgrade is qualified against the stable wrapper release seen when it started.
            released = qualification.get('releasedWrapper', qualification['wrapper'])
            if (qualification['runtime'] != str(baseline_runtime) or qualification['trustedSuiteSha256'] != suite_hash
                    or released['revision'] != wrapper['revision'] or released['tag'] != wrapper['tag']
                    or qualification['upstream']['revision'] != upstream['revision'] or qualification['upstream']['tag'] != upstream['tag']
                    or qualification['upstream']['dependencyArchiveSha256'] != official_dependency_hash
                    or qualification['wrapper']['treeSha256'] != tree_digest(ROOT)):
                raise UpdateError('Candidate qualification pair/revision/digest/suite/runtime mismatch')
            for archive in qualification['archives']:
                path = pathlib.Path(archive['path'])
                if path.is_symlink() or hash_bytes(path.read_bytes()) != archive['sha256']:
                    raise UpdateError('Candidate qualification source/dependency cache changed')
            verify_candidate_runtime(baseline_runtime, qualification['wrapper'], upstream, qualification['support'])
            validate_selected(state, original, baseline_runtime, baseline_runtime)
            unchanged(file, original, identity)
            print('QUALIFIED exact fixed-check released pair; reused ' + qualification['identity'])
            return qualification
        if active and active['wrapper']['revision'] == wrapper['revision'] and active['upstream']['revision'] == upstream['revision'] and active['upstream']['dependencyArchiveSha256'] == official_dependency_hash and active['wrapper']['tag'] == wrapper['tag'] and active['upstream']['tag'] == upstream['tag'] and active['trustedSuiteSha256'] == suite_hash:
            validate_selected(state, original, baseline_runtime, baseline_runtime)
            unchanged(file, original, identity)
            print('UNCHANGED validated released pair; reused ' + active['identity'])
            return active
        # The released wrapper's support policy decides whether this upstream
        # release may be built at all, before any backup, source download or build.
        wrapper_archive, wrapper_hash = download(state, api_base() + '/repos/' + WRAPPER + '/tarball/' + wrapper['revision'])
        wrappers = state / 'wrappers'
        wrappers.mkdir(exist_ok=True)
        package = wrappers / wrapper['revision']
        snapshot_index = wrappers / (wrapper['revision'] + '.json')
        if package.exists():
            saved = json.loads(snapshot_index.read_text())
            if saved['archiveSha256'] != wrapper_hash or saved['treeSha256'] != tree_digest(package):
                raise UpdateError('Cached released wrapper content changed')
            policy = json.loads((package / 'runtime/release-package.json').read_text())
        else:
            try:
                policy = extract_wrapper(wrapper_archive, package, trusted_root)
                atomic_json(snapshot_index, {'archiveSha256': wrapper_hash, 'treeSha256': tree_digest(package)})
            except Exception:
                if package.exists():
                    shutil.rmtree(package)
                raise
        declared = policy.get('upstream', {}).get('tag', '')
        if not wrapper_tag_matches(wrapper['tag'], declared):
            raise UpdateError(f"Wrapper release tag {wrapper['tag']} does not match its supported Portfolio Performance release {declared}; "
                              'wrapper releases are tagged exactly like the Portfolio Performance tag they support (fixes: <tag>-2, <tag>-3, ...)')
        if not supported(policy, None, upstream):
            if supported(policy, active, upstream):
                # A validated local upgrade covers this upstream; the released wrapper does not yet.
                validate_selected(state, original, baseline_runtime, baseline_runtime)
                unchanged(file, original, identity)
                print('UNCHANGED validated local-upgrade pair; reused ' + active['identity'])
                return active
            raise upgrade_required(state, upstream, original_argv)
        check_policy(policy, trusted_root)
        unchanged(file, original, identity)
        execute([launcher, 'backup-before-update', *selected_args], environment)
        print('BUILD released wrapper ' + wrapper['tag'] + ' and upstream ' + upstream['tag'], flush=True)
        source_archive, source_hash = download(state, api_base() + '/repos/' + UPSTREAM + '/tarball/' + upstream['revision'])
        dependency_archive, dependency_hash = download(state, asset_url, official_dependency_hash)
        # The build inputs contain only public upstream archives; the released
        # wrapper's own tools/build-runtime builds from its released tree.
        jdk_archive, jdk_hash = download(state, policy['jdkArchive']['url'], policy['jdkArchive']['sha256'])
        build_root = state / 'builds'
        build_root.mkdir(exist_ok=True)
        inputs = pathlib.Path(tempfile.mkdtemp(prefix=wrapper['revision'] + '-', dir=build_root))
        for source, name in ((source_archive, 'upstream-source.tar.gz'), (dependency_archive, 'upstream-release.tar.gz'), (jdk_archive, 'jdk.tar.gz')):
            shutil.copyfile(source, inputs / name)
        key_data = {'wrapper': wrapper['revision'], 'wrapperArchive': wrapper_hash, 'upstream': upstream['revision'], 'source': source_hash, 'dependencies': dependency_hash, 'jdk': jdk_hash, 'recipe': hash_bytes((package / 'tools/build-runtime').read_bytes()), 'platform': 'linux/amd64', 'trustedSuite': suite_hash, 'wrapperTag': wrapper['tag'], 'upstreamTag': upstream['tag']}
        cache_key = hash_bytes(json.dumps(key_data, sort_keys=True).encode())
        try:
            runtime = build_runtime(package, inputs, wrapper['revision'], state,
                                    {'sourceSha256': source_hash, 'releaseSha256': dependency_hash, 'jdkSha256': jdk_hash})
        finally:
            shutil.rmtree(inputs)
        runtime_id = runtime_identity(runtime)
        java_version = verify_candidate_runtime(runtime, wrapper, upstream, policy)
        qualification = {'schema': 1, 'launcher': str(package / 'pp-pal'), 'runtime': str(runtime), 'identity': runtime_id, 'baselineRuntime': str(baseline_runtime),
            'wrapper': {'tag': wrapper['tag'], 'revision': wrapper['revision'], 'treeSha256': tree_digest(package)},
            'upstream': {'tag': upstream['tag'], 'revision': upstream['revision'], 'dependencyArchiveSha256': dependency_hash},
            'trustedSuiteSha256': suite_hash, 'support': policy,
            'archives': [{'path': str(path), 'sha256': digest} for path, digest in
                ((wrapper_archive, wrapper_hash), (source_archive, source_hash), (dependency_archive, dependency_hash), (jdk_archive, jdk_hash))]}
        validate_selected(state, original, baseline_runtime, runtime)
        unchanged(file, original, identity)
        fixed_checks(package, runtime, registry, trusted_root, qualification)
        unchanged(file, original, identity)
        manifest = {'schema': 1, 'validatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'wrapper': {'tag': wrapper['tag'], 'revision': wrapper['revision'], 'sourceArchiveSha256': wrapper_hash, 'treeSha256': tree_digest(package)}, 'upstream': {'tag': upstream['tag'], 'revision': upstream['revision'], 'sourceArchiveSha256': source_hash, 'dependencyArchiveSha256': dependency_hash}, 'runtime': str(runtime), 'identity': runtime_id, 'baselineRuntime': str(baseline_runtime), 'launcher': str(package / 'pp-pal'), 'cacheKey': cache_key, 'trustedSuiteSha256': suite_hash, 'support': policy, 'checks': registry['checks'] + ['selected-portfolio-cross-engine-semantic-roundtrip', 'selected-portfolio-original-stale-check'], 'dependencies': {'host': ['Linux', 'Bash', 'Python 3.10+', 'coreutils', 'tar'], 'build': policy['jdk'], 'jdkArchiveSha256': jdk_hash, 'recipeSha256': key_data['recipe'], 'actualJavaVersion': java_version, 'actualPlatform': 'linux/amd64', 'actualHost': platform.platform()}, 'previous': active}
        reports = state / 'reports'
        reports.mkdir(exist_ok=True)
        atomic_json(reports / (cache_key + '.json'), manifest)
        if active:
            atomic_json(state / 'previous.json', active)
        atomic_json(state / 'active.json', manifest)
        print('ACTIVATED ' + wrapper['revision'] + ' / ' + upstream['revision'] + ' / ' + runtime_id)
        return manifest

def perform_local_upgrade(checkout, selected_args):
    """Qualify and activate an agent upgrade checkout (UPGRADE.md, ADR 0006)."""
    checkout = pathlib.Path(checkout).expanduser().absolute()
    try:
        status = subprocess.run(['git', '-C', str(checkout), 'status', '--porcelain'], text=True, capture_output=True)
        commit = subprocess.run(['git', '-C', str(checkout), 'rev-parse', 'HEAD'], text=True, capture_output=True).stdout.strip()
    except OSError as failure:
        raise InputError('Local upgrade checkout must be a git checkout: ' + str(failure)) from failure
    if status.returncode or status.stdout.strip() or not SHA.fullmatch(commit):
        raise InputError('Local upgrade checkout must be clean and committed: ' + str(checkout))
    state = state_directory()
    descriptor = os.open(state / 'update.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'r+') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as failure:
            raise UpdateError('Another wrapper update is active') from failure
        trusted_root, suite_hash = trusted_bundle(state)
        active = active_manifest(state)
        baseline_runtime = pathlib.Path(active['runtime']) if active else default_runtime(state)
        runtime_identity(baseline_runtime)
        launcher = str(pathlib.Path(active['launcher']).parent / 'tools/run-engine') if active else str(ROOT / 'tools/run-engine')
        environment = launch_environment(baseline_runtime)
        environment['PP_PAL_RESOLVE_ONLY'] = '1'
        file = pathlib.Path(execute([launcher, 'backup-monthly', *selected_args], environment, input_errors=True))
        original, identity = snapshot(file)
        environment.pop('PP_PAL_RESOLVE_ONLY')
        execute([launcher, 'backup-monthly', *selected_args], environment)
        wrapper = release(WRAPPER, 'wrapper')
        upstream = release(UPSTREAM, 'Portfolio Performance')
        asset_url, official_dependency_hash = upstream_asset(upstream)
        archive = subprocess.run(['git', '-C', str(checkout), 'archive', '--format=tar.gz', '--prefix=upgrade/', commit], capture_output=True)
        if archive.returncode:
            raise UpdateError('Cannot archive local upgrade checkout: ' + archive.stderr.decode(errors='replace')[-2000:])
        with tempfile.TemporaryDirectory(prefix='local-upgrade-', dir=state) as temporary:
            work = pathlib.Path(temporary)
            (work / 'upgrade.tar.gz').write_bytes(archive.stdout)
            # Same allowlist and bootstrap-identity rule as a released wrapper; its
            # support policy may change (that is the point of the upgrade).
            staged = work / 'package'
            policy = extract_wrapper(work / 'upgrade.tar.gz', staged, trusted_root)
            declared = policy.get('upstream', {})
            if declared.get('tag') != upstream['tag'] or declared.get('revision') != upstream['revision']:
                raise UpdateError('Local upgrade does not target the latest upstream release ' + upstream['tag'])
            if declared.get('releaseSha256') != official_dependency_hash:
                raise UpdateError('Local upgrade dependency digest does not match the official release asset')
            new_bundle = work / 'trusted'
            new_bundle.mkdir()
            build_trusted_bundle(checkout, new_bundle)
            old_gates = trusted_suite(trusted_root)['checks']
            registry = trusted_suite(new_bundle)
            for gate in old_gates:
                if gate not in registry['checks']:
                    raise UpdateError('Local upgrade removes trusted gate ' + gate)
            changes = trusted_changes(trusted_root, new_bundle)
            new_hash = tree_digest(new_bundle)
            unchanged(file, original, identity)
            execute([launcher, 'backup-before-update', *selected_args], environment)
            print('BUILD local upgrade ' + commit + ' for upstream ' + upstream['tag'], flush=True)
            wrappers = state / 'wrappers'
            wrappers.mkdir(exist_ok=True)
            package = wrappers / ('local-' + commit)
            if package.exists():
                shutil.rmtree(package)
            shutil.copytree(staged, package)
            source_archive, source_hash = download(state, api_base() + '/repos/' + UPSTREAM + '/tarball/' + upstream['revision'], declared.get('sourceSha256'))
            dependency_archive, dependency_hash = download(state, asset_url, official_dependency_hash)
            jdk_archive, jdk_hash = download(state, policy['jdkArchive']['url'], policy['jdkArchive']['sha256'])
            inputs = work / 'inputs'
            inputs.mkdir()
            for source, name in ((source_archive, 'upstream-source.tar.gz'), (dependency_archive, 'upstream-release.tar.gz'), (jdk_archive, 'jdk.tar.gz')):
                shutil.copyfile(source, inputs / name)
            runtime = build_runtime(package, inputs, commit, state,
                                    {'sourceSha256': source_hash, 'releaseSha256': dependency_hash, 'jdkSha256': jdk_hash})
            runtime_id = runtime_identity(runtime)
            local_wrapper = {'tag': 'local-' + commit[:12], 'revision': commit, 'treeSha256': tree_digest(package)}
            java_version = verify_candidate_runtime(runtime, local_wrapper, upstream, policy)
            qualification = {'schema': 1, 'source': 'local-upgrade', 'launcher': str(package / 'pp-pal'), 'runtime': str(runtime),
                'identity': runtime_id, 'baselineRuntime': str(baseline_runtime), 'wrapper': local_wrapper,
                'releasedWrapper': {'tag': wrapper['tag'], 'revision': wrapper['revision']},
                'upstream': {'tag': upstream['tag'], 'revision': upstream['revision'], 'dependencyArchiveSha256': dependency_hash},
                'trustedSuiteSha256': new_hash, 'support': policy,
                'archives': [{'path': str(path), 'sha256': digest} for path, digest in
                    ((source_archive, source_hash), (dependency_archive, dependency_hash), (jdk_archive, jdk_hash))]}
            # The owner's portfolio is checked by the PREVIOUS trusted oracle on the
            # PREVIOUS runtime, independently of the agent's adapted tests.
            validate_selected(state, original, baseline_runtime, runtime)
            unchanged(file, original, identity)
            fixed_checks(package, runtime, registry, new_bundle, qualification)
            unchanged(file, original, identity)
            # Activation: retain the previous bundle, install the new one, then switch the manifest.
            retained = state / ('trusted-validation.previous-' + commit)
            if retained.exists():
                shutil.rmtree(retained)
            os.replace(trusted_root, retained)
            shutil.copytree(new_bundle, state / 'trusted-validation')
            atomic_json(state / 'trusted-validation.json', {'schema': 1, 'sha256': new_hash})
        cache_key = hash_bytes(json.dumps({'local': commit, 'upstream': upstream['revision'], 'trustedSuite': new_hash}, sort_keys=True).encode())
        manifest = {'schema': 1, 'source': 'local-upgrade', 'validatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'wrapper': local_wrapper, 'releasedWrapper': qualification['releasedWrapper'],
            'upstream': {'tag': upstream['tag'], 'revision': upstream['revision'], 'sourceArchiveSha256': source_hash, 'dependencyArchiveSha256': dependency_hash},
            'runtime': str(runtime), 'identity': runtime_id, 'baselineRuntime': str(baseline_runtime), 'launcher': str(package / 'pp-pal'),
            'cacheKey': cache_key, 'trustedSuiteSha256': new_hash, 'trustedChanges': changes, 'support': policy,
            'checks': registry['checks'] + ['selected-portfolio-cross-engine-semantic-roundtrip', 'selected-portfolio-original-stale-check'],
            'dependencies': {'host': ['Linux', 'Bash', 'Python 3.10+', 'coreutils', 'tar', 'git'], 'build': policy['jdk'], 'jdkArchiveSha256': jdk_hash,
                             'actualJavaVersion': java_version, 'actualPlatform': 'linux/amd64', 'actualHost': platform.platform()},
            'previous': active}
        reports = state / 'reports'
        reports.mkdir(exist_ok=True)
        atomic_json(reports / (cache_key + '.json'), manifest)
        if active:
            atomic_json(state / 'previous.json', active)
        atomic_json(state / 'active.json', manifest)
        print('TRUSTED CHANGES')
        for kind in ('added', 'changed', 'removed'):
            for name in changes[kind]:
                print(kind + ' ' + name)
        print('ACTIVATED local-upgrade ' + runtime_id)
        return manifest

def local_upgrade_arguments(arguments):
    """--update --local-upgrade PATH [--file F] [--config C] -> (PATH, selection)."""
    if arguments[:2] != ['--update', '--local-upgrade'] or len(arguments) < 3 or not arguments[2] or arguments[2].startswith('--'):
        raise InputError('Usage: pp-pal --update --local-upgrade CHECKOUT [--file PATH] [--config PATH]')
    return arguments[2], selection(['--update', *arguments[3:]])

def prepare_processing(arguments):
    """Automatic update boundary for every preview/import, before any financial plan."""
    options = ['--update']
    index = 1
    while index < len(arguments):
        if arguments[index] in ('--file', '--config'):
            if index + 1 == len(arguments) or arguments[index + 1].startswith('--'):
                raise InputError('Missing value for ' + arguments[index])
            options.extend(arguments[index:index + 2])
            index += 2
        else:
            index += 1
    with contextlib.redirect_stdout(sys.stderr):
        return perform_update(options, arguments)

def dispatch(arguments):
    state = state_directory()
    active = active_manifest(state)
    if arguments and arguments[0] in ('purchase', 'sale', 'dividend', 'import'):
        active = prepare_processing(arguments)
    if active:
        engine = pathlib.Path(active['launcher']).parent / 'tools/run-engine'
        runtime = pathlib.Path(active['runtime'])
    else:
        engine = ROOT / 'tools/run-engine'
        runtime = default_runtime(state)
    runtime_identity(runtime)
    environment = launch_environment(runtime)
    environment.pop('PP_PAL_RESOLVE_ONLY', None)
    os.execve(str(engine), [str(engine), *arguments], environment)

def main():
    os.umask(0o077)
    if sys.platform != 'linux' or platform.machine() != 'x86_64':
        raise UpdateError('Only the tested Linux amd64 host platform is supported')
    if sys.argv[1:2] == ['--dispatch']:
        dispatch(sys.argv[2:])
    elif sys.argv[2:3] == ['--local-upgrade']:
        perform_local_upgrade(*local_upgrade_arguments(sys.argv[1:]))
    else:
        perform_update(sys.argv[1:])

if __name__ == '__main__':
    try:
        main()
    except UpgradeRequired as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(3)
    except InputError as failure:
        print('ERROR: ' + str(failure), file=sys.stderr)
        sys.exit(2)
    except (UpdateError, OSError, ValueError, KeyError, TypeError) as failure:
        print('ERROR: ' + str(failure), file=sys.stderr)
        sys.exit(3)
