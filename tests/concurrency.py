#!/usr/bin/env python3
"""Scheduling regressions using real child processes and a disposable Docker stub."""
import concurrent.futures
import importlib.util
import json
import os
import pathlib
import signal
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from test_jobs import parallel_cases

spec = importlib.util.spec_from_file_location('test_runner', ROOT / 'tests/run.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

STUB = '''#!/usr/bin/env python3
import json, os, pathlib, signal, sys, time
args = sys.argv[1:]
directory = pathlib.Path(__file__).parent
def event(kind):
    with (directory/'events').open('a') as output:
        output.write(json.dumps([kind,args,time.monotonic()])+'\\n')
if args[0] in ('run','build'):
    name = args[args.index('--name')+1] if '--name' in args else 'build'
    (directory/name).write_text(str(os.getpid()))
    event('start')
    try:
        time.sleep(float(args[-1]))
    finally:
        event('end')
        (directory/name).unlink(missing_ok=True)
elif args[0] == 'rm':
    for name in args[2:]:
        target = directory/name
        if target.exists():
            try: os.kill(int(target.read_text()),signal.SIGTERM)
            except ProcessLookupError: pass
            target.unlink(missing_ok=True)
    event('cleanup')
'''


class SchedulingTests(unittest.TestCase):
    def test_helper_import_preserves_immutable_qualification_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root/'tools').mkdir()
            (root/'tests').mkdir()
            for name in ('tools/update.py', 'tools/test_jobs.py', 'tests/run.py'):
                shutil.copyfile(ROOT/name, root/name)
            original = sorted(str(path.relative_to(root)) for path in root.rglob('*'))
            code = '''import importlib.util, runpy, sys
sys.path.insert(0, sys.argv[1]+'/tools')
runpy.run_path(sys.argv[1]+'/tools/update.py',run_name='qualification_import')
spec=importlib.util.spec_from_file_location('trusted_runner',sys.argv[1]+'/tests/run.py')
spec.loader.exec_module(importlib.util.module_from_spec(spec))
'''
            environment = dict(os.environ)
            environment.pop('PYTHONDONTWRITEBYTECODE', None)
            subprocess.run([sys.executable, '-c', code, str(root)], env=environment, check=True)
            self.assertEqual(original, sorted(str(path.relative_to(root)) for path in root.rglob('*')))

    def test_rolling_queue_and_failure_stop(self):
        started = []
        release = threading.Event()
        short_done = threading.Event()
        def task(value):
            started.append(value)
            if value == 'slow':
                self.assertTrue(release.wait(5))
            elif value == 'replacement':
                short_done.set()
        with patch.dict(os.environ, PP_PAL_TEST_WORKERS='2'):
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                result = pool.submit(parallel_cases, task, ['slow', 'short', 'replacement'])
                try:
                    self.assertTrue(short_done.wait(5), 'Replacement waited for the slow partner')
                finally:
                    release.set()
                result.result()
        self.assertCountEqual(started, ['slow', 'short', 'replacement'])
        started.clear()
        def fail(value):
            started.append(value)
            raise ValueError('expected failure')
        with patch.dict(os.environ, PP_PAL_TEST_WORKERS='1'):
            with self.assertRaisesRegex(ValueError, 'expected failure'):
                parallel_cases(fail, range(10))
        self.assertEqual(started, [0])

    def test_parent_cgroup_memory_and_cpu_limits_are_respected(self):
        files = {
            '/proc/meminfo': 'MemTotal: 16777216 kB\nMemAvailable: 12582912 kB\n',
            '/proc/self/cgroup': '0::/parent/leaf\n',
            '/sys/fs/cgroup/parent/leaf/memory.max': str(8*runner.GIB),
            '/sys/fs/cgroup/parent/leaf/memory.current': str(2*runner.GIB),
            '/sys/fs/cgroup/parent/memory.max': str(4*runner.GIB),
            '/sys/fs/cgroup/parent/memory.current': str(runner.GIB),
            '/sys/fs/cgroup/parent/leaf/cpu.max': '400000 100000',
            '/sys/fs/cgroup/parent/cpu.max': '200000 100000',
        }
        def read(path):
            if str(path) not in files:
                raise FileNotFoundError(str(path))
            return files[str(path)]
        with patch.object(pathlib.Path, 'read_text', read), patch.object(os, 'sched_getaffinity', return_value=set(range(16))):
            self.assertEqual(runner.memory(), (4*runner.GIB, 3*runner.GIB))
            self.assertEqual(runner.cpu_count(), 2)


class BudgetTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='pp-pal-budget-test-')
        self.directory = pathlib.Path(self.temporary.name)
        self.docker = self.directory / 'docker'
        self.docker.write_text(STUB)
        self.docker.chmod(0o755)
        self.patches = [patch.object(runner, 'memory', return_value=(16*runner.GIB, 12*runner.GIB)),
                        patch.object(runner, 'cpu_count', return_value=16),
                        patch.dict(os.environ, PP_PAL_TEST_JOBS='2', PP_PAL_TEST_MEMORY_MIB='1024',
                                   PP_PAL_TEST_CPUS='2', PP_PAL_TEST_PIDS='256')]
        for item in self.patches:
            item.start()
        self.budget = runner.Budget(self.directory, str(self.docker))
        self.environment = dict(os.environ, PP_PAL_TEST_DOCKER=str(self.docker),
                                PP_PAL_TEST_BUDGET=self.budget.socket_path,
                                PP_PAL_TEST_SUITE=self.budget.suite)
        self.processes = []

    def tearDown(self):
        self.budget.close()
        for process in self.processes:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=5)
        for item in reversed(self.patches):
            item.stop()
        self.temporary.cleanup()

    def start(self, kind='run', seconds='0.15'):
        process = subprocess.Popen([sys.executable, str(ROOT/'tests/run.py'), '--docker', kind, seconds],
                                   env=self.environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.processes.append(process)
        return process

    def events(self):
        path = self.directory/'events'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def await_starts(self, count):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if sum(event[0] == 'start' for event in self.events()) >= count:
                return
            time.sleep(0.02)
        self.fail('Docker stub did not start')

    def test_shared_limit_and_container_caps(self):
        processes = [self.start() for _ in range(8)]
        for process in processes:
            _, errors = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 0, errors.decode())
        active = peak = 0
        for kind, args, _ in self.events():
            if kind == 'start':
                active += 1
                peak = max(peak, active)
                for flag, expected in [('--memory','1024m'), ('--memory-swap','1024m'),
                                       ('--cpus','2'), ('--pids-limit','256')]:
                    self.assertEqual(args[args.index(flag)+1], expected)
            elif kind == 'end':
                active -= 1
        self.assertEqual(peak, 2)
        self.assertEqual(active, 0)

    def test_build_waits_for_running_containers_and_runs_exclusively(self):
        first = self.start(seconds='0.4')
        self.await_starts(1)
        build = self.start('build', '0.3')
        time.sleep(0.1)
        last = self.start()
        for process in [first, build, last]:
            _, errors = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 0, errors.decode())
        events = [(kind,args[0]) for kind,args,_ in self.events() if kind != 'cleanup']
        self.assertEqual(events, [('start','run'),('end','run'),('start','build'),
                                  ('end','build'),('start','run'),('end','run')])

    def test_killed_client_cleans_container_before_reusing_slot(self):
        self.budget.limit = 1
        first = self.start(seconds='30')
        self.await_starts(1)
        first.kill()
        first.communicate(timeout=5)
        next_job = self.start()
        _, errors = next_job.communicate(timeout=5)
        self.assertEqual(next_job.returncode, 0, errors.decode())
        deadline = time.monotonic() + 5
        while self.budget.used and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual(self.budget.used, 0)
        self.assertFalse(list(self.directory.glob('pp-pal-test-*')))
        relevant = [kind for kind, _, _ in self.events()]
        self.assertLess(relevant.index('cleanup'), relevant.index('start', 1))

    def test_host_pressure_blocks_admission_until_memory_recovers(self):
        with patch.object(runner, 'memory', return_value=(16*runner.GIB, 4*runner.GIB)):
            process = self.start()
            time.sleep(0.3)
            self.assertEqual(self.events(), [])
        _, errors = process.communicate(timeout=5)
        self.assertEqual(process.returncode, 0, errors.decode())

    def test_foreground_auto_remove_receipt_avoids_redundant_cleanup(self):
        process = subprocess.Popen([sys.executable, str(ROOT/'tests/run.py'), '--docker',
                                    'run', '--rm', '0.1'], env=self.environment,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.processes.append(process)
        _, errors = process.communicate(timeout=5)
        self.assertEqual(process.returncode, 0, errors.decode())
        deadline = time.monotonic() + 5
        while self.budget.used and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual([kind for kind, _, _ in self.events()], ['start', 'end'])

    def test_pressure_timeout_reports_failure_without_starting_container(self):
        with patch.object(runner, 'ADMISSION_TIMEOUT', 0.1), patch.object(
                runner, 'memory', return_value=(16*runner.GIB, 4*runner.GIB)):
            process = self.start()
            _, errors = process.communicate(timeout=5)
            self.assertNotEqual(process.returncode, 0)
            self.assertIn('memory pressure', errors.decode())
            self.assertEqual(self.events(), [])

    def test_container_cannot_override_budget_limits_or_detach(self):
        for flags in (['--memory=8g'], ['-m8g'], ['--cpus','16'], ['--pids-limit=-1'], ['--detach']):
            result = subprocess.run([sys.executable, str(ROOT/'tests/run.py'), '--docker',
                                     'run', *flags, '0.1'], env=self.environment,
                                    text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('shared resource limits', result.stderr)
        self.assertEqual(self.events(), [])

    def test_java_compiler_output_flag_is_not_docker_detachment(self):
        result = subprocess.run([sys.executable, str(ROOT/'tests/run.py'), '--docker',
                                 'run', '--rm', '--entrypoint', 'javac', 'test-image',
                                 '-d', '/classes', '0.1'], env=self.environment,
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_configuration_clamps_requested_workers(self):
        self.assertEqual(self.budget.limit, 2)
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, PP_PAL_TEST_JOBS='100'):
                budget = runner.Budget(pathlib.Path(tmp), str(self.docker))
                try:
                    self.assertEqual(budget.limit, 4)  # Half of 16 CPUs, two per job.
                finally:
                    budget.close()

    def test_low_memory_refuses_startup(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(runner, 'memory', return_value=(16*runner.GIB, 4*runner.GIB)):
                with self.assertRaisesRegex(ValueError, 'Insufficient available memory'):
                    runner.Budget(pathlib.Path(tmp), str(self.docker))

    def test_serial_limit_and_shutdown_stop_active_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, PP_PAL_TEST_JOBS='1'):
                budget = runner.Budget(pathlib.Path(tmp), str(self.docker))
            environment = dict(self.environment, PP_PAL_TEST_BUDGET=budget.socket_path,
                               PP_PAL_TEST_SUITE=budget.suite)
            process = subprocess.Popen([sys.executable, str(ROOT/'tests/run.py'), '--docker', 'run', '30'],
                                       env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.processes.append(process)
            try:
                self.await_starts(1)
                self.assertEqual(budget.peak, 1)
            finally:
                budget.close()
            process.communicate(timeout=5)
            self.assertNotEqual(process.returncode, 0)
            self.assertFalse(list(self.directory.glob('pp-pal-test-*')))

    def test_direct_qualification_interruption_cleans_shared_budget(self):
        code = '''import importlib.util, subprocess, sys, time
spec=importlib.util.spec_from_file_location('runner',sys.argv[1])
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
runner.memory=lambda:(16*runner.GIB,12*runner.GIB)
runner.cpu_count=lambda:16
with runner.test_environment() as environment:
    subprocess.Popen(['docker','run','--rm','30'],env=environment)
    time.sleep(30)
'''
        environment = {key:value for key,value in os.environ.items() if not key.startswith('PP_PAL_TEST_')}
        environment['PATH'] = str(self.directory) + os.pathsep + environment['PATH']
        process = subprocess.Popen([sys.executable,'-c',code,str(ROOT/'tests/run.py')],
                                   env=environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.processes.append(process)
        self.await_starts(1)
        process.terminate()
        _, errors = process.communicate(timeout=5)
        self.assertNotEqual(process.returncode, 0)
        self.assertIn('interrupted', errors.decode())
        self.assertFalse(list(self.directory.glob('pp-pal-test-*')))


if __name__ == '__main__':
    unittest.main()
