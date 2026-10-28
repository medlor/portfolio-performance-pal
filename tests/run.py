#!/usr/bin/env python3
"""Suite-scoped JVM budget; shell entry points and assertions remain intact."""
import collections
import contextlib
import json
import math
import os
import pathlib
import select
import shlex
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
# A build holds every slot so it runs alone, but it is a single JVM (javac of
# the upstream core); admit it against this much free memory, not one job share
# per slot.
BUILD_MEMORY = 2 * GIB
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
    def __init__(self, directory):
        self.suite = uuid.uuid4().hex
        self.socket_path = str(directory / 'budget.sock')
        self.memory_mib = positive('PP_PAL_TEST_MEMORY_MIB', 1024)
        # Default: half of the effective logical CPUs (affinity and cgroup quota
        # applied), one processor per JVM. Short JVM runs gain nothing from more.
        half = max(1, math.floor(cpu_count() / 2))
        self.cpus = min(positive('PP_PAL_TEST_CPUS', 1), half)
        requested = positive('PP_PAL_TEST_JOBS', half)
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
        self.groups = {}
        self.listener = socket.socket(socket.AF_UNIX)
        self.listener.bind(self.socket_path)
        self.listener.listen()
        self.listener.settimeout(0.2)
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()
        print(f'TEST BUDGET requested={requested} jobs={self.limit} '
              f'memory/job={self.memory_mib}MiB cpus/job={self.cpus} '
              f'reserve={self.reserve // MIB}MiB', flush=True)

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
        stream = connection.makefile('rb')
        try:
            request = json.loads(stream.readline(4096))
            name = request['name']
            if not name.startswith('pp-pal-test-' + self.suite + '-'):
                raise ValueError('Job does not belong to this suite')
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
                    # An exclusive build is admitted only when nothing else runs.
                    demand = BUILD_MEMORY if request['build'] else (self.used + weight) * self.memory_mib * MIB
                    enough_memory = available >= self.reserve + demand
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
                'cpus': self.cpus}).encode() + b'\n')
            # Socket lifetime owns the lease, including abrupt client death.
            # The client reports its JVM process group, then a completion receipt.
            for line in stream:
                if line.startswith(b'pgid '):
                    with self.condition:
                        self.groups[name] = int(line[5:])
                elif line == b'complete\n':
                    completed = True
        except (OSError, ValueError, KeyError, RuntimeError) as failure:
            with contextlib.suppress(OSError):
                connection.sendall(json.dumps({'error': str(failure)}).encode() + b'\n')
        finally:
            stream.close()
            connection.close()
            with self.condition:
                group = self.groups.pop(name, None) if name else None
            if admitted and not completed and group:
                # On client death, stop its JVM before releasing the slot.
                if not stop_group(group):
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
            groups = list(self.groups.values())
            self.condition.notify_all()
        self.thread.join()
        self.listener.close()
        # Stop only this suite's JVMs, including those of clients killed with SIGKILL.
        for group in groups:
            stop_group(group)
        for handler in self.handlers:
            handler.join(timeout=2)
        print(f'TEST RESOURCE SUMMARY jobs={self.jobs} peak_slots={self.peak}/{self.limit} '
              f'min_available={self.min_available // MIB}MiB', flush=True)


def stop_group(group, grace=5):
    """Terminate a job's process group; escalate to SIGKILL after the grace period."""
    for signum in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(group, signum)
        except ProcessLookupError:
            return True
        deadline = time.monotonic() + grace
        while time.monotonic() < deadline:
            try:
                os.killpg(group, 0)
            except ProcessLookupError:
                return True
            time.sleep(0.05)
    return False


# JVM options that would override the shared limits. javac takes them as -J<option>.
LIMIT_OPTIONS = ('-Xmx', '-XX:MaxRAM', '-XX:ActiveProcessorCount', '-XX:MaxHeapSize')
# java options whose value is the next argument; anything else not starting
# with '-' is the main class/source file, after which arguments belong to the application.
JAVA_VALUES = {'-cp', '-classpath', '--class-path', '-p', '--module-path', '--add-modules',
               '--add-opens', '--add-exports', '-jar'}


def check_limits(tool, args):
    javac = pathlib.Path(tool).name == 'javac'
    index = 0
    while index < len(args):
        arg = args[index]
        option = arg[2:] if javac and arg.startswith('-J') else arg
        if option.startswith(LIMIT_OPTIONS):
            raise ValueError('Test JVMs must use the shared resource limits')
        if not javac:
            if not arg.startswith('-') or arg == '-jar':
                return
            if arg in JAVA_VALUES:
                index += 1
        index += 1


def java_command(args):
    build = args[:1] == ['--build']
    if build:
        args = args[1:]
    if not args:
        raise ValueError('Usage: tests/run.py --java [--build] TOOL [ARGS...]')
    tool, rest = args[0], args[1:]
    check_limits(tool, rest)
    name = 'pp-pal-test-' + os.environ['PP_PAL_TEST_SUITE'] + '-' + uuid.uuid4().hex
    with socket.socket(socket.AF_UNIX) as connection:
        connection.connect(os.environ['PP_PAL_TEST_BUDGET'])
        connection.sendall(json.dumps({'name': name, 'build': build}).encode() + b'\n')
        with connection.makefile('rb') as response:
            limits = json.loads(response.readline())
        if 'error' in limits:
            raise RuntimeError(limits['error'])
        command = [tool]
        if not build:
            # A heap ceiling below the job's memory share leaves room for
            # metaspace, threads and code cache.
            heap = limits['memory'] * 3 // 4
            processors = math.ceil(limits['cpus'])
            prefix = '-J' if pathlib.Path(tool).name == 'javac' else ''
            command += [f'{prefix}-Xmx{heap}m', f'{prefix}-XX:ActiveProcessorCount={processors}']
        # Builds own the entire budget and are serialized; they get no heap ceiling.
        command += rest
        with subprocess.Popen(command, start_new_session=True) as process:
            connection.sendall(f'pgid {process.pid}\n'.encode())
            def interrupt(signum, frame):
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGTERM)
            signal.signal(signal.SIGTERM, interrupt)
            signal.signal(signal.SIGINT, interrupt)
            try:
                status = process.wait(timeout=JOB_TIMEOUT)
                if status >= 0:
                    connection.sendall(b'complete\n')
                return status
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                raise RuntimeError('Test JVM job exceeded 1200 seconds')


@contextlib.contextmanager
def test_environment():
    if os.environ.get('PP_PAL_TEST_BUDGET'):
        yield dict(os.environ)
        return
    with tempfile.TemporaryDirectory(prefix='pp-pal-test-budget-') as tmp:
        directory = pathlib.Path(tmp)
        budget = Budget(directory)
        previous_handlers = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
        def interrupt(signum, frame):
            budget.close()
            raise InterruptedError('Test qualification interrupted')
        for sig in previous_handlers:
            signal.signal(sig, interrupt)
        try:
            bin_dir = directory / 'bin'
            bin_dir.mkdir()
            shim = bin_dir / 'pp-pal-java'
            shim.write_text('#!/bin/sh\nexec ' + shlex.join(
                [sys.executable, str(pathlib.Path(__file__).resolve()), '--java']) + ' "$@"\n')
            shim.chmod(0o755)
            environment = dict(os.environ, PYTHONDONTWRITEBYTECODE='1',
                               PP_PAL_JAVA_WRAPPER=str(shim),
                               PP_PAL_TEST_BUDGET=budget.socket_path,
                               PP_PAL_TEST_SUITE=budget.suite,
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
        if sys.argv[1] == '--java':
            status = java_command(sys.argv[2:])
        elif sys.argv[1] == '--':
            status = run(sys.argv[2:])
        else:
            raise ValueError('Usage: tests/run.py -- COMMAND [ARGS...]')
        sys.exit(status if status >= 0 else 128 - status)
    except (ValueError, OSError, RuntimeError) as failure:
        print('TEST RUNNER ERROR: ' + str(failure), file=sys.stderr)
        sys.exit(1)
