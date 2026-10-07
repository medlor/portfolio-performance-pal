#!/usr/bin/env python3
"""Host-only release/download/cache orchestration. Java owns financial semantics."""
import fcntl
import contextlib
import datetime
import hashlib
import json
import os
import pathlib
import platform
import re
import shutil
import socket
import struct
import subprocess
import sys
import tarfile
import tempfile
import threading
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
WRAPPER = 'medlor/portfolio-performance-pal'
UPSTREAM = 'portfolio-performance/portfolio'
SHA = re.compile(r'[a-f0-9]{40}')
IMAGE = re.compile(r'sha256:[a-f0-9]{64}')
DIGEST = re.compile(r'[a-f0-9]{64}')

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
    try:
        return json.loads(fetch(api_base() + path))
    except (ValueError, TypeError) as failure:
        raise UpdateError('Release service returned invalid JSON') from failure

def release(repository, label):
    try:
        data = request_json('/repos/' + repository + '/releases/latest')
    except UpdateError as failure:
        if 'HTTP 404' in str(failure):
            raise UpdateError(f'No stable released {label} is available; publish the documented bootstrap release package before updating') from failure
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

def execute(command, environment=None, quiet=False, input_errors=False):
    result = subprocess.run(command, text=True, capture_output=True, env=environment)
    if result.returncode:
        if input_errors and result.returncode == 2:
            raise InputError('Selected file/configuration rejected: ' + result.stderr.strip())
        detail = '' if quiet else ': ' + result.stderr[-4000:]
        raise UpdateError(f'Local check failed ({result.returncode}){detail}')
    return result.stdout.strip()

def image_identity(value):
    identity = execute(['docker', 'image', 'inspect', value, '--format', '{{.Id}}'])
    if not IMAGE.fullmatch(identity):
        raise UpdateError('Docker did not provide an immutable image identity')
    info = json.loads(execute(['docker', 'image', 'inspect', identity]))[0]
    if info.get('Os') != 'linux' or info.get('Architecture') != 'amd64':
        raise UpdateError('Only the fixture-validated Linux amd64 image platform is supported')
    execute(['docker', 'tag', identity, 'pp-pal:retained-' + identity[7:]])
    return identity

def tree_digest(directory):
    digest = hashlib.sha256()
    for path in sorted(directory.rglob('*')):
        if path.is_symlink():
            raise UpdateError('Cached release/validation bundle contains a symlink')
        if path.is_file():
            digest.update(str(path.relative_to(directory)).encode() + b'\0' + str(path.stat().st_mode & 0o777).encode() + b'\0' + path.read_bytes())
    return digest.hexdigest()

def trusted_bundle(state):
    target = state / 'trusted-validation'
    marker = state / 'trusted-validation.json'
    if not target.exists():
        if marker.exists():
            raise UpdateError('Trusted validation bundle is missing')
        temporary = pathlib.Path(tempfile.mkdtemp(prefix='.trusted-', dir=state))
        try:
            registry = json.loads((ROOT / 'runtime/candidate-gates.json').read_text())
            for name in ('fixtures', 'runtime/src/test/java'):
                shutil.copytree(ROOT / name, temporary / name)
            for name in [*registry['checks'], 'runtime/candidate-gates.json', 'runtime/release-package.json']:
                destination = temporary / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / name, destination)
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
        if data['schema'] != 1 or not IMAGE.fullmatch(data['image']) or not SHA.fullmatch(data['wrapper']['revision']):
            raise ValueError()
        if package.parent != state / 'wrappers' or package.name != data['wrapper']['revision'] or not (package / 'pp-pal').is_file():
            raise ValueError()
        if tree_digest(package) != data['wrapper']['treeSha256']:
            raise UpdateError('Activated wrapper content changed; active pair cannot be dispatched')
        trusted, digest = trusted_bundle(state)
        if digest != data['trustedSuiteSha256']:
            raise UpdateError('Activated trusted validation bundle does not match its manifest')
        return data
    except (ValueError, KeyError, TypeError):
        raise UpdateError('Invalid active wrapper/image manifest')

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

def launch_environment(image):
    environment = dict(os.environ, PP_PAL_IMAGE=image)
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

def download(state, url, expected=None):
    data = fetch(url)
    digest = hash_bytes(data)
    if expected is not None and digest != expected:
        raise UpdateError('Official release archive digest does not match release metadata')
    cache = state / 'downloads'
    cache.mkdir(exist_ok=True)
    path = cache / (digest + '.tar.gz')
    if path.exists():
        if path.is_symlink() or hash_bytes(path.read_bytes()) != digest:
            raise UpdateError('Download cache content changed')
    else:
        with path.open('xb') as stream:
            stream.write(data)
    return path, digest

def extract_wrapper(archive, directory, trusted_root):
    directory.mkdir()
    allowed = ('runtime/', 'tools/', 'pp-pal', 'Dockerfile', '.dockerignore')
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
                    target.chmod(0o755 if name in ('pp-pal', 'tools/run-container') else 0o644)
    except (tarfile.TarError, OSError) as failure:
        raise UpdateError('Cannot unpack released wrapper snapshot') from failure
    for name in ('pp-pal', 'tools/update.py', 'tools/run-container', 'Dockerfile', 'runtime/release-package.json'):
        if not (directory / name).is_file():
            raise UpdateError('Released wrapper lacks the required bootstrap package: ' + name)
    for name in ('pp-pal', 'tools/update.py'):
        if (directory / name).read_bytes() != (ROOT / name).read_bytes():
            raise UpdateError('Released host bootstrap/validator changed; manual trusted-bootstrap maintenance required')
    policy = json.loads((directory / 'runtime/release-package.json').read_text())
    trusted = json.loads((trusted_root / 'runtime/release-package.json').read_text())
    if policy.get('schema') != 1 or policy.get('buildRecipe') != 1 or policy.get('bootstrapProtocol') != 1 or policy.get('platform') != 'linux/amd64':
        raise UpdateError('Unknown released wrapper build recipe/platform; maintenance required')
    if any(policy.get(key) != trusted.get(key) for key in ('input', 'output', 'migrations', 'baseImage', 'jdk', 'layouts', 'features')):
        raise UpdateError('Unknown support or migration policy; maintenance required')
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

def fixed_checks(package, image, registry, trusted_root, qualification):
    with tempfile.TemporaryDirectory(prefix='pp-pal-gate-state-') as temporary:
        state = pathlib.Path(temporary)
        shutil.copytree(trusted_root, state / 'trusted-validation')
        atomic_json(state / 'trusted-validation.json', {'schema': 1, 'sha256': qualification['trustedSuiteSha256']})
        environment = launch_environment(image)
        environment.update(PP_PAL_LAUNCHER=str(package / 'pp-pal'), PP_PAL_STATE_DIR=temporary, PP_PAL_GATE_BASELINE_IMAGE=qualification['baselineImage'])
        with qualifying_validator(temporary, qualification) as address:
            environment['PP_PAL_GATE_SOCKET'] = address
            for check in registry['checks']:
                print('CHECK ' + check, flush=True)
                execute(['bash', str(trusted_root / check)], environment)

def validate_selected(state, original, baseline_image, candidate_image):
    # Opaque snapshot bytes stay local. All model/persistence operations are Java.
    # The candidate cannot mount either trusted canonical artifacts or writable input.
    with tempfile.TemporaryDirectory(prefix='selected-validation-', dir=state) as temporary:
        work = pathlib.Path(temporary)
        source, trusted, output = (work / name for name in ('input', 'trusted', 'output'))
        for directory in (source, trusted, output):
            directory.mkdir()
        (source / 'input.xml').write_bytes(original)
        base = ['docker', 'run', '--rm', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}']
        def run(image, mounts, command, *args):
            bindings = []
            for directory, target, mode in mounts:
                bindings += ['-v', str(directory) + ':' + target + ':' + mode]
            return execute(base + bindings + [image, command, *args], quiet=True)
        trusted_mounts = [(source, '/input', 'ro'), (trusted, '/trusted', 'rw')]
        run(baseline_image, trusted_mounts, 'roundtrip', '/input/input.xml', '/trusted/before.xml')
        candidate_mounts = [(source, '/input', 'ro'), (output, '/output', 'rw')]
        run(candidate_image, candidate_mounts, 'roundtrip', '/input/input.xml', '/output/candidate.xml')
        candidate = output / 'candidate.xml'
        if candidate.is_symlink() or not candidate.is_file():
            raise UpdateError('Candidate did not produce a regular disposable output')
        if (source / 'input.xml').read_bytes() != original:
            raise UpdateError('Candidate changed the readonly selected validation input')
        after_mounts = [(output, '/output', 'ro'), (trusted, '/trusted', 'rw')]
        run(baseline_image, after_mounts, 'roundtrip', '/output/candidate.xml', '/trusted/after.xml')
        if (trusted / 'before.xml').read_bytes() != (trusted / 'after.xml').read_bytes():
            raise UpdateError('Candidate changes selected portfolio persisted semantics; active pair retained')
        if run(baseline_image, [(source, '/input', 'ro')], 'inspect', '/input/input.xml') != run(baseline_image, [(output, '/output', 'ro')], 'inspect', '/output/candidate.xml'):
            raise UpdateError('Candidate changes selected portfolio financial relationships; active pair retained')

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

def verify_candidate_image(image, wrapper, upstream, policy):
    info = json.loads(execute(['docker', 'image', 'inspect', image]))[0]
    labels = info['Config'].get('Labels', {})
    if labels.get('pal.wrapper.revision') != wrapper['revision'] or labels.get('pal.upstream.revision') != upstream['revision'] or labels.get('pal.xml.model') != str(policy['output']['modelVersion']):
        raise UpdateError('Candidate image revision/model labels disagree with the pinned pair')
    result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--entrypoint', 'java', image, '-version'], text=True, capture_output=True)
    if result.returncode or '21.0.11' not in result.stderr or 'Temurin-21.0.11+10' not in result.stderr:
        raise UpdateError('Candidate Java runtime is not the reviewed Temurin 21.0.11+10 dependency')
    return result.stderr.strip()

def perform_update(arguments):
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
        baseline_image = image_identity(active['image'] if active else os.environ.get('PP_PAL_IMAGE', 'pp-pal:0.88.0'))
        launcher = str(pathlib.Path(active['launcher']).parent / 'tools/run-container') if active else str(ROOT / 'tools/run-container')
        environment = launch_environment(baseline_image)
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
            if (qualification['image'] != baseline_image or qualification['trustedSuiteSha256'] != suite_hash
                    or qualification['wrapper']['revision'] != wrapper['revision'] or qualification['wrapper']['tag'] != wrapper['tag']
                    or qualification['upstream']['revision'] != upstream['revision'] or qualification['upstream']['tag'] != upstream['tag']
                    or qualification['upstream']['dependencyArchiveSha256'] != official_dependency_hash
                    or qualification['wrapper']['treeSha256'] != tree_digest(ROOT)):
                raise UpdateError('Candidate qualification pair/revision/digest/suite/image mismatch')
            for archive in qualification['archives']:
                path = pathlib.Path(archive['path'])
                if path.is_symlink() or hash_bytes(path.read_bytes()) != archive['sha256']:
                    raise UpdateError('Candidate qualification source/dependency cache changed')
            verify_candidate_image(baseline_image, wrapper, upstream, qualification['support'])
            validate_selected(state, original, baseline_image, baseline_image)
            unchanged(file, original, identity)
            print('QUALIFIED exact fixed-check released pair; reused ' + baseline_image)
            return qualification
        if active and active['wrapper']['revision'] == wrapper['revision'] and active['upstream']['revision'] == upstream['revision'] and active['upstream']['dependencyArchiveSha256'] == official_dependency_hash and active['wrapper']['tag'] == wrapper['tag'] and active['upstream']['tag'] == upstream['tag'] and active['trustedSuiteSha256'] == suite_hash:
            validate_selected(state, original, baseline_image, baseline_image)
            unchanged(file, original, identity)
            print('UNCHANGED validated released pair; reused ' + baseline_image)
            return active
        unchanged(file, original, identity)
        execute([launcher, 'backup-before-update', *selected_args], environment)
        print('BUILD released wrapper ' + wrapper['tag'] + ' and upstream ' + upstream['tag'], flush=True)
        wrapper_archive, wrapper_hash = download(state, api_base() + '/repos/' + WRAPPER + '/tarball/' + wrapper['revision'])
        source_archive, source_hash = download(state, api_base() + '/repos/' + UPSTREAM + '/tarball/' + upstream['revision'])
        dependency_archive, dependency_hash = download(state, asset_url, official_dependency_hash)
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
        # The Docker context contains only released runtime/code and public upstream archives.
        build_root = state / 'builds'
        build_root.mkdir(exist_ok=True)
        context = pathlib.Path(tempfile.mkdtemp(prefix=wrapper['revision'] + '-', dir=build_root))
        shutil.copytree(package / 'runtime', context / 'runtime')
        shutil.copyfile(package / 'Dockerfile', context / 'Dockerfile')
        (context / '.dockerignore').write_text('*\n!Dockerfile\n!runtime/\n!runtime/**\n')
        inputs = context / 'runtime/build-inputs'
        inputs.mkdir()
        shutil.copyfile(source_archive, inputs / 'upstream-source.tar.gz')
        shutil.copyfile(dependency_archive, inputs / 'upstream-release.tar.gz')
        key_data = {'wrapper': wrapper['revision'], 'wrapperArchive': wrapper_hash, 'upstream': upstream['revision'], 'source': source_hash, 'dependencies': dependency_hash, 'recipe': hash_bytes((package / 'Dockerfile').read_bytes()), 'platform': 'linux/amd64', 'trustedSuite': suite_hash, 'wrapperTag': wrapper['tag'], 'upstreamTag': upstream['tag']}
        cache_key = hash_bytes(json.dumps(key_data, sort_keys=True).encode())
        tag = 'pp-pal:validated-pair-' + cache_key
        build_args = {'INPUT_SOURCE': 'supplied', 'UPSTREAM_SOURCE_SHA256': source_hash, 'UPSTREAM_RELEASE_SHA256': dependency_hash, 'UPSTREAM_REVISION': upstream['revision'], 'UPSTREAM_RELEASE': upstream['tag'], 'WRAPPER_REVISION': wrapper['revision']}
        command = ['docker', 'build', '--platform', 'linux/amd64', '-t', tag]
        for key, value in build_args.items():
            command += ['--build-arg', key + '=' + value]
        execute(command + [str(context)])
        image = image_identity(tag)
        java_version = verify_candidate_image(image, wrapper, upstream, policy)
        qualification = {'schema': 1, 'launcher': str(package / 'pp-pal'), 'image': image, 'baselineImage': baseline_image,
            'wrapper': {'tag': wrapper['tag'], 'revision': wrapper['revision'], 'treeSha256': tree_digest(package)},
            'upstream': {'tag': upstream['tag'], 'revision': upstream['revision'], 'dependencyArchiveSha256': dependency_hash},
            'trustedSuiteSha256': suite_hash, 'support': policy,
            'archives': [{'path': str(path), 'sha256': digest} for path, digest in
                ((wrapper_archive, wrapper_hash), (source_archive, source_hash), (dependency_archive, dependency_hash))]}
        fixed_checks(package, image, registry, trusted_root, qualification)
        validate_selected(state, original, baseline_image, image)
        unchanged(file, original, identity)
        manifest = {'schema': 1, 'validatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'wrapper': {'tag': wrapper['tag'], 'revision': wrapper['revision'], 'sourceArchiveSha256': wrapper_hash, 'treeSha256': tree_digest(package)}, 'upstream': {'tag': upstream['tag'], 'revision': upstream['revision'], 'sourceArchiveSha256': source_hash, 'dependencyArchiveSha256': dependency_hash}, 'image': image, 'retentionTag': 'pp-pal:retained-' + image[7:], 'cacheTag': tag, 'baselineImage': baseline_image, 'baselineRetentionTag': 'pp-pal:retained-' + baseline_image[7:], 'launcher': str(package / 'pp-pal'), 'cacheKey': cache_key, 'trustedSuiteSha256': suite_hash, 'support': policy, 'checks': registry['checks'] + ['selected-portfolio-cross-engine-semantic-roundtrip', 'selected-portfolio-original-stale-check'], 'dependencies': {'host': ['Linux', 'Bash', 'Python 3.10+', 'Docker'], 'build': policy['baseImage'], 'recipeSha256': key_data['recipe'], 'actualJavaVersion': java_version, 'actualPlatform': 'linux/amd64', 'actualHost': platform.platform()}, 'previous': active}
        reports = state / 'reports'
        reports.mkdir(exist_ok=True)
        atomic_json(reports / (cache_key + '.json'), manifest)
        if active:
            atomic_json(state / 'previous.json', active)
        atomic_json(state / 'active.json', manifest)
        print('ACTIVATED ' + wrapper['revision'] + ' / ' + upstream['revision'] + ' / ' + image)
        return manifest

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
        return perform_update(options)

def dispatch(arguments):
    state = state_directory()
    active = active_manifest(state)
    if arguments and arguments[0] in ('purchase', 'sale', 'dividend', 'import'):
        active = prepare_processing(arguments)
    if active:
        engine = pathlib.Path(active['launcher']).parent / 'tools/run-container'
        image = image_identity(active['image'])
    else:
        engine = ROOT / 'tools/run-container'
        image = image_identity(os.environ.get('PP_PAL_IMAGE', 'pp-pal:0.88.0'))
    environment = launch_environment(image)
    environment.pop('PP_PAL_RESOLVE_ONLY', None)
    os.execve(str(engine), [str(engine), *arguments], environment)

def main():
    os.umask(0o077)
    if sys.platform != 'linux' or platform.machine() != 'x86_64':
        raise UpdateError('Only the tested Linux amd64 host platform is supported')
    if sys.argv[1:2] == ['--dispatch']:
        dispatch(sys.argv[2:])
    else:
        perform_update(sys.argv[1:])

if __name__ == '__main__':
    try:
        main()
    except InputError as failure:
        print('ERROR: ' + str(failure), file=sys.stderr)
        sys.exit(2)
    except (UpdateError, OSError, ValueError, KeyError, TypeError) as failure:
        print('ERROR: ' + str(failure), file=sys.stderr)
        sys.exit(3)
