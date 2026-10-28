#!/usr/bin/env python3
"""Scheduling regressions using real child processes and a disposable JDK tool stub."""
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
kind = 'build' if '--build-marker' in args else 'run'
def event(name):
    with (directory/'events').open('a') as output:
        output.write(json.dumps([name,[kind,*args],time.monotonic()])+'\\n')
def stop(signum, frame):
    event('cleanup')
    sys.exit(143)
signal.signal(signal.SIGTERM, stop)
job = directory/('pp-pal-test-job-'+str(os.getpid()))
job.write_text(str(os.getpid()))
event('start')
try:
    time.sleep(float(args[-1]))
finally:
    event('end')
    job.unlink(missing_ok=True)
'''


class SchedulingTests(unittest.TestCase):
    def test_helper_import_preserves_immutable_qualification_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root/'tools').mkdir()
            (root/'tests').mkdir()
            for name in ('tools/update.py', 'tools/test_jobs.py', 'tools/runtime_dir.py', 'tests/run.py'):
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
        self.java = self.directory / 'java'
        self.java.write_text(STUB)
        self.java.chmod(0o755)
        self.javac = self.directory / 'javac'
        self.javac.write_text(STUB)
        self.javac.chmod(0o755)
        self.patches = [patch.object(runner, 'memory', return_value=(16*runner.GIB, 12*runner.GIB)),
                        patch.object(runner, 'cpu_count', return_value=16),
                        patch.dict(os.environ, PP_PAL_TEST_JOBS='2', PP_PAL_TEST_MEMORY_MIB='1024',
                                   PP_PAL_TEST_CPUS='2')]
        for item in self.patches:
            item.start()
        self.budget = runner.Budget(self.directory)
        self.environment = dict(os.environ, PP_PAL_TEST_BUDGET=self.budget.socket_path,
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

    def command(self, *args, build=False, tool=None):
        return [sys.executable, str(ROOT/'tests/run.py'), '--java', *(['--build'] if build else []),
                str(tool or self.java), *(['--build-marker'] if build else []), *args]

    def start(self, kind='run', seconds='0.15'):
        process = subprocess.Popen(self.command(seconds, build=kind == 'build'),
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
        self.fail('JDK tool stub did not start')

    def test_shared_limit_and_jvm_caps(self):
        processes = [self.start() for _ in range(8)]
        for process in processes:
            _, errors = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 0, errors.decode())
        active = peak = 0
        for kind, args, _ in self.events():
            if kind == 'start':
                active += 1
                peak = max(peak, active)
                self.assertEqual(args[1:3], ['-Xmx768m', '-XX:ActiveProcessorCount=2'])
            elif kind == 'end':
                active -= 1
        self.assertEqual(peak, 2)
        self.assertEqual(active, 0)

    def test_build_waits_for_running_jobs_and_runs_exclusively(self):
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

    def test_killed_client_stops_its_jvm_before_reusing_slot(self):
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

    def test_exclusive_build_needs_build_memory_not_one_share_per_slot(self):
        # A build holds every slot to run alone, but it is one JVM: with 8 slots it
        # must not need the reserve plus 8 job shares of free memory.
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, PP_PAL_TEST_JOBS='8', PP_PAL_TEST_CPUS='1'), \
                patch.object(runner, 'ADMISSION_TIMEOUT', 0.5):
            budget = runner.Budget(pathlib.Path(tmp))
            try:
                self.assertEqual(budget.limit, 8)
                environment = dict(self.environment, PP_PAL_TEST_BUDGET=budget.socket_path, PP_PAL_TEST_SUITE=budget.suite)
                # 4 GiB reserve + 3 GiB: enough for a 2 GiB build, far below 8 job shares.
                with patch.object(runner, 'memory', return_value=(16*runner.GIB, 7*runner.GIB)):
                    process = subprocess.Popen(self.command('0.1', build=True), env=environment,
                                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    self.processes.append(process)
                    _, errors = process.communicate(timeout=10)
                self.assertEqual(process.returncode, 0, errors.decode())
            finally:
                budget.close()

    def test_host_pressure_blocks_admission_until_memory_recovers(self):
        with patch.object(runner, 'memory', return_value=(16*runner.GIB, 4*runner.GIB)):
            process = self.start()
            time.sleep(0.3)
            self.assertEqual(self.events(), [])
        _, errors = process.communicate(timeout=5)
        self.assertEqual(process.returncode, 0, errors.decode())

    def test_completion_receipt_avoids_redundant_cleanup(self):
        process = subprocess.Popen(self.command('0.1'), env=self.environment,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.processes.append(process)
        _, errors = process.communicate(timeout=5)
        self.assertEqual(process.returncode, 0, errors.decode())
        deadline = time.monotonic() + 5
        while self.budget.used and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual([kind for kind, _, _ in self.events()], ['start', 'end'])

    def test_pressure_timeout_reports_failure_without_starting_jvm(self):
        with patch.object(runner, 'ADMISSION_TIMEOUT', 0.1), patch.object(
                runner, 'memory', return_value=(16*runner.GIB, 4*runner.GIB)):
            process = self.start()
            _, errors = process.communicate(timeout=5)
            self.assertNotEqual(process.returncode, 0)
            self.assertIn('memory pressure', errors.decode())
            self.assertEqual(self.events(), [])

    def test_jvm_cannot_override_budget_limits(self):
        cases = [(self.java, ['-Xmx8g']), (self.java, ['-XX:MaxRAM=8g']),
                 (self.java, ['-XX:MaxRAMPercentage=90']), (self.java, ['-XX:ActiveProcessorCount=16']),
                 (self.java, ['-cp', 'x', '-Xmx1g']), (self.javac, ['-J-Xmx8g']),
                 (self.javac, ['-d', 'out', '-J-XX:ActiveProcessorCount=16'])]
        for tool, flags in cases:
            result = subprocess.run(self.command(*flags, '0.1', tool=tool), env=self.environment,
                                    text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0, flags)
            self.assertIn('shared resource limits', result.stderr)
        self.assertEqual(self.events(), [])

    def test_application_arguments_are_not_jvm_limits(self):
        result = subprocess.run(self.command('-cp', str(self.directory / 'classes'), 'Oracle.java', '-Xmx99g', '0.1'),
                                env=self.environment, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = subprocess.run(self.command('-d', str(self.directory / 'classes'), 'A.java', '0.1', tool=self.javac),
                                env=self.environment, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        javac = [args for kind, args, _ in self.events() if kind == 'start'][-1]
        self.assertEqual(javac[1:3], ['-J-Xmx768m', '-J-XX:ActiveProcessorCount=2'])

    def test_configuration_clamps_requested_workers(self):
        self.assertEqual(self.budget.limit, 2)
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, PP_PAL_TEST_JOBS='100'):
                budget = runner.Budget(pathlib.Path(tmp))
                try:
                    self.assertEqual(budget.limit, 4)  # Half of 16 CPUs, two per job.
                finally:
                    budget.close()

    def test_default_budget_uses_half_the_logical_cpus_one_per_job(self):
        # Without overrides: half of the effective logical CPUs (cgroup quota and
        # affinity already applied by cpu_count), one processor per JVM.
        for cpus, jobs in ((16, 8), (4, 2), (2, 1), (1, 1)):
            with tempfile.TemporaryDirectory() as tmp, patch.object(runner, 'cpu_count', return_value=cpus):
                environment = {key: value for key, value in os.environ.items()
                               if key not in ('PP_PAL_TEST_JOBS', 'PP_PAL_TEST_CPUS')}
                with patch.dict(os.environ, environment, clear=True):
                    budget = runner.Budget(pathlib.Path(tmp))
                    try:
                        self.assertEqual((budget.limit, budget.cpus), (jobs, 1), f'{cpus} CPUs')
                    finally:
                        budget.close()

    def test_low_memory_refuses_startup(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(runner, 'memory', return_value=(16*runner.GIB, 4*runner.GIB)):
                with self.assertRaisesRegex(ValueError, 'Insufficient available memory'):
                    runner.Budget(pathlib.Path(tmp))

    def test_serial_limit_and_shutdown_stop_active_jvm(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, PP_PAL_TEST_JOBS='1'):
                budget = runner.Budget(pathlib.Path(tmp))
            environment = dict(self.environment, PP_PAL_TEST_BUDGET=budget.socket_path,
                               PP_PAL_TEST_SUITE=budget.suite)
            process = subprocess.Popen(self.command('30'),
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
    subprocess.Popen([environment['PP_PAL_JAVA_WRAPPER'],sys.argv[2],'30'],env=environment)
    time.sleep(30)
'''
        environment = {key:value for key,value in os.environ.items() if not key.startswith('PP_PAL_TEST_')}
        process = subprocess.Popen([sys.executable,'-c',code,str(ROOT/'tests/run.py'),str(self.java)],
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
