#!/usr/bin/env python3
"""Suite-scoped Docker budget; shell entry points and assertions remain intact."""
import collections
import contextlib
import json
import math
import os
import pathlib
import select
import shlex
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import uuid

MIB = 1024 * 1024
GIB = 1024 * MIB
ADMISSION_TIMEOUT = 120
JOB_TIMEOUT = 1200


def memory():
    values = {}
    for line in pathlib.Path('/proc/meminfo').read_text().splitlines():
        key, value = line.split(':', 1)
        if key in ('MemTotal', 'MemAvailable'):
            values[key] = int(value.split()[0]) * 1024
    total, available = values['MemTotal'], values['MemAvailable']
    # Walk cgroup v2 ancestors: a parent may impose the effective limit.
    group = next((line.split(':', 2)[2] for line in
                  pathlib.Path('/proc/self/cgroup').read_text().splitlines()
                  if line.startswith('0::')), '/')
    path = pathlib.Path('/sys/fs/cgroup') / group.lstrip('/')
    while path.is_relative_to('/sys/fs/cgroup'):
        try:
            ceiling = (path / 'memory.max').read_text().strip()
            if ceiling != 'max':
                total = min(total, int(ceiling))
                available = min(available, int(ceiling) - int((path / 'memory.current').read_text()))
        except (OSError, ValueError):
            pass
        path = path.parent
    return total, max(0, available)


def cpu_count():
    count = len(os.sched_getaffinity(0))
    group = next((line.split(':', 2)[2] for line in
                  pathlib.Path('/proc/self/cgroup').read_text().splitlines()
                  if line.startswith('0::')), '/')
    path = pathlib.Path('/sys/fs/cgroup') / group.lstrip('/')
    while path.is_relative_to('/sys/fs/cgroup'):
        try:
            quota, period = (path / 'cpu.max').read_text().split()
            if quota != 'max':
                count = min(count, int(quota) / int(period))
        except (OSError, ValueError):
            pass
        path = path.parent
    return count


def positive(name, default):
    value = int(os.environ.get(name, default))
    if value < 1:
        raise ValueError(f'{name} must be a positive integer')
    return value


class Budget:
    def __init__(self, directory, docker):
        self.docker = docker
        self.suite = uuid.uuid4().hex
        self.socket_path = str(directory / 'budget.sock')
        self.memory_mib = positive('PP_PAL_TEST_MEMORY_MIB', 1024)
        self.cpus = min(positive('PP_PAL_TEST_CPUS', 2), max(1, math.floor(cpu_count() / 2)))
        self.pids = positive('PP_PAL_TEST_PIDS', 256)
        requested = positive('PP_PAL_TEST_JOBS', 2)
        total, available = memory()
        self.reserve = max(GIB, total // 4)
        memory_jobs = (available - self.reserve) // (self.memory_mib * MIB)
        cpu_jobs = max(1, math.floor(cpu_count() / 2 / self.cpus))
        self.limit = min(requested, memory_jobs, cpu_jobs)
        if self.limit < 1:
            raise ValueError('Insufficient available memory for one test job and the host reserve')
        self.condition = threading.Condition()
        self.queue = collections.deque()
        self.used = self.peak = self.jobs = 0
        self.min_available = available
        self.stopped = False
        self.closed = False
        self.handlers = []
        self.clients = {}
        self.listener = socket.socket(socket.AF_UNIX)
        self.listener.bind(self.socket_path)
        self.listener.listen()
        self.listener.settimeout(0.2)
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()
        print(f'TEST BUDGET requested={requested} jobs={self.limit} '
              f'memory/job={self.memory_mib}MiB cpus/job={self.cpus} '
              f'pids/job={self.pids} reserve={self.reserve // MIB}MiB', flush=True)

    def serve(self):
        while not self.stopped:
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            thread = threading.Thread(target=self.lease, args=(connection,), daemon=True)
            self.handlers.append(thread)
            thread.start()

    def lease(self, connection):
        ticket = object()
        admitted = False
        completed = False
        name = None
        weight = 0
        pid, _, _ = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        try:
            pidfd = os.pidfd_open(pid)
        except ProcessLookupError:
            connection.close()
            return
        with self.condition:
            self.clients[pid] = pidfd
        try:
            with connection.makefile('rb') as stream:
                request = json.loads(stream.readline(4096))
            name = request['name']
            if not name.startswith('pp-pal-test-' + self.suite + '-'):
                raise ValueError('Container does not belong to this suite')
            weight = self.limit if request['build'] else 1
            pressure_since = None
            with self.condition:
                self.queue.append(ticket)
                while True:
                    if self.stopped:
                        raise RuntimeError('Test suite stopped')
                    _, available = memory()
                    self.min_available = min(self.min_available, available)
                    # Active jobs may grow to their hard ceilings. Reserve
                    # their remaining headroom before admitting another job.
                    enough_memory = available >= self.reserve + (self.used + weight) * self.memory_mib * MIB
                    eligible = self.queue[0] is ticket and self.used + weight <= self.limit
                    if eligible and enough_memory:
                        self.queue.popleft()
                        self.used += weight
                        self.jobs += 1
                        self.peak = max(self.peak, self.used)
                        admitted = True
                        break
                    if eligible and not enough_memory:
                        if pressure_since is None:
                            pressure_since = time.monotonic()
                        if time.monotonic() - pressure_since > ADMISSION_TIMEOUT:
                            raise RuntimeError('Test resource admission timed out under memory pressure')
                    else:
                        pressure_since = None
                    if select.select([connection], [], [], 0)[0] and not connection.recv(1, socket.MSG_PEEK):
                        return
                    self.condition.wait(0.2)
            connection.sendall(json.dumps({'memory': self.memory_mib,
                'cpus': self.cpus, 'pids': self.pids}).encode() + b'\n')
            # Socket lifetime owns the lease, including abrupt client death.
            completion = b''
            while chunk := connection.recv(1024):
                completion += chunk
            completed = completion == b'complete\n'
        except (OSError, ValueError, KeyError, RuntimeError) as failure:
            with contextlib.suppress(OSError):
                connection.sendall(json.dumps({'error': str(failure)}).encode() + b'\n')
        finally:
            connection.close()
            if admitted and not completed:
                # Removing an already --rm'ed container is harmless. On client
                # death this stops the container before releasing its slot.
                try:
                    subprocess.run([self.docker, 'rm', '-f', name], stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, timeout=30)
                except subprocess.TimeoutExpired:
                    self.stopped = True
            with self.condition:
                self.clients.pop(pid, None)
                os.close(pidfd)
                if ticket in self.queue:
                    self.queue.remove(ticket)
                if admitted:
                    self.used -= weight
                self.condition.notify_all()

    def close(self):
        with self.condition:
            if self.closed:
                return
            self.closed = True
            self.stopped = True
            for pidfd in self.clients.values():
                with contextlib.suppress(ProcessLookupError):
                    signal.pidfd_send_signal(pidfd, signal.SIGTERM)
            self.condition.notify_all()
        self.thread.join()
        self.listener.close()
        # Kill only this suite's containers, including clients killed with SIGKILL.
        result = subprocess.run([self.docker, 'ps', '-aq', '--filter',
                                 f'label=pp-pal-test-suite={self.suite}'],
                                text=True, capture_output=True, timeout=30)
        if result.returncode == 0 and result.stdout.split():
            subprocess.run([self.docker, 'rm', '-f', *result.stdout.split()],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        for handler in self.handlers:
            handler.join(timeout=2)
        print(f'TEST RESOURCE SUMMARY jobs={self.jobs} peak_slots={self.peak}/{self.limit} '
              f'min_available={self.min_available // MIB}MiB', flush=True)


def docker_command(args):
    docker = os.environ['PP_PAL_TEST_DOCKER']
    if not args or args[0] not in ('run', 'build'):
        os.execv(docker, [docker, *args])
    if args[0] == 'run':
        controlled = {'--memory', '--memory-swap', '--cpus', '--cpu-quota', '--cpu-period',
                      '--pids-limit', '--name', '--detach', '-d', '-m'}
        values = {'--network', '--user', '-u', '--workdir', '-w', '--env', '-e', '--env-file',
                  '--entrypoint', '--volume', '-v', '--mount', '--label', '-l', '--label-file',
                  '--hostname', '-h', '--device', '--gpus', '--platform', '--pull', '--dns',
                  '--add-host', '--tmpfs', '--security-opt', '--cap-add', '--cap-drop'}
        switches = {'--rm', '--read-only', '--init', '--privileged', '--tty', '-t',
                    '--interactive', '-i', '-it', '--sig-proxy', '--help'}
        index = 1
        while index < len(args) and args[index].startswith('-'):
            arg = args[index]
            flag = arg.split('=', 1)[0]
            if flag in controlled or arg.startswith('-m'):
                raise ValueError('Test containers must use the shared resource limits and foreground execution')
            if flag not in values and flag not in switches:
                raise ValueError('Unsupported test Docker option (declare its arity in tests/run.py): ' + flag)
            index += 2 if flag in values and '=' not in arg else 1
        # Stop at the image: javac's -d and application arguments are unrelated
        # to Docker detachment or resource flags.
    name = 'pp-pal-test-' + os.environ['PP_PAL_TEST_SUITE'] + '-' + uuid.uuid4().hex
    with socket.socket(socket.AF_UNIX) as connection:
        connection.connect(os.environ['PP_PAL_TEST_BUDGET'])
        connection.sendall(json.dumps({'name': name, 'build': args[0] == 'build'}).encode() + b'\n')
        with connection.makefile('rb') as response:
            limits = json.loads(response.readline())
        if 'error' in limits:
            raise RuntimeError(limits['error'])
        command = [docker, *args]
        if args[0] == 'run':
            command = [docker, 'run', '--name', name, '--label',
                       'pp-pal-test-suite=' + os.environ['PP_PAL_TEST_SUITE'],
                       '--memory', str(limits['memory']) + 'm',
                       '--memory-swap', str(limits['memory']) + 'm',
                       '--cpus', str(limits['cpus']), '--pids-limit', str(limits['pids']),
                       *args[1:]]
        # BuildKit ignores run-container limits. Builds own the entire budget
        # and are serialized; do not claim a hard build-memory ceiling.
        with subprocess.Popen(command, start_new_session=True) as process:
            def interrupt(signum, frame):
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGTERM)
            signal.signal(signal.SIGTERM, interrupt)
            signal.signal(signal.SIGINT, interrupt)
            try:
                status = process.wait(timeout=JOB_TIMEOUT)
                # Foreground --rm removes its container before Docker returns.
                # A completion receipt avoids a second Docker CLI per normal job.
                if status >= 0 and ('--rm' in args or args[0] == 'build'):
                    connection.sendall(b'complete\n')
                return status
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                raise RuntimeError('Docker test job exceeded 1200 seconds')


@contextlib.contextmanager
def test_environment():
    if os.environ.get('PP_PAL_TEST_BUDGET'):
        yield dict(os.environ)
        return
    docker = shutil.which('docker')
    if docker is None:
        raise ValueError('Docker is required for container tests')
    with tempfile.TemporaryDirectory(prefix='pp-pal-test-budget-') as tmp:
        directory = pathlib.Path(tmp)
        budget = Budget(directory, docker)
        previous_handlers = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
        def interrupt(signum, frame):
            budget.close()
            raise InterruptedError('Test qualification interrupted')
        for sig in previous_handlers:
            signal.signal(sig, interrupt)
        try:
            bin_dir = directory / 'bin'
            bin_dir.mkdir()
            shim = bin_dir / 'docker'
            shim.write_text('#!/bin/sh\nexec ' + shlex.join(
                [sys.executable, str(pathlib.Path(__file__).resolve()), '--docker']) + ' "$@"\n')
            shim.chmod(0o755)
            environment = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'],
                               PYTHONDONTWRITEBYTECODE='1',
                               PP_PAL_TEST_BUDGET=budget.socket_path,
                               PP_PAL_TEST_SUITE=budget.suite, PP_PAL_TEST_DOCKER=docker,
                               PP_PAL_TEST_WORKERS=str(budget.limit))
            yield environment
        finally:
            for sig, handler in previous_handlers.items():
                signal.signal(sig, handler)
            budget.close()


def run(command):
    with test_environment() as environment:
        with subprocess.Popen(command, env=environment, start_new_session=True) as process:
            def interrupt(signum, frame):
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGTERM)
            signal.signal(signal.SIGTERM, interrupt)
            signal.signal(signal.SIGINT, interrupt)
            return process.wait()


if __name__ == '__main__':
    try:
        if sys.argv[1] == '--docker':
            status = docker_command(sys.argv[2:])
        elif sys.argv[1] == '--':
            status = run(sys.argv[2:])
        else:
            raise ValueError('Usage: tests/run.py -- COMMAND [ARGS...]')
        sys.exit(status if status >= 0 else 128 - status)
    except (ValueError, OSError, RuntimeError) as failure:
        print('TEST RUNNER ERROR: ' + str(failure), file=sys.stderr)
        sys.exit(1)
