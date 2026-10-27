#!/usr/bin/env python3
"""Run existing Java oracles on a runtime's JDK, with optional once-per-suite compilation.

  oracle.py --compile RUNTIME DEST     compile every trusted oracle into DEST
  oracle.py RUNTIME ORACLE [ARGS...]   run pal.ORACLE with host-path ARGS
"""
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ENGINE = ROOT / 'tools/engine'


def engine(runtime, *args):
    return [str(ENGINE), *args], dict(os.environ, PP_PAL_RUNTIME=str(runtime))


def runtime_classpath(runtime):
    command, environment = engine(runtime, 'classpath')
    return subprocess.run(command, env=environment, check=True, text=True, capture_output=True).stdout.strip()


def compile_classes(runtime, destination):
    destination = pathlib.Path(destination).absolute()
    destination.mkdir(parents=True, exist_ok=False)
    sources = sorted((ROOT / 'runtime/src/test/java/pal').glob('*.java'))
    command, environment = engine(runtime, 'javac', '-encoding', 'UTF-8', '--release', '21',
                                  '-cp', runtime_classpath(runtime), '-d', str(destination),
                                  *map(str, sources))
    subprocess.run(command, env=environment, check=True)


def run_oracle(runtime, oracle, args):
    classpath = runtime_classpath(runtime)
    classes = os.environ.get('PP_PAL_TEST_CLASSES')
    if classes:
        target = ['-cp', classes + ':' + classpath, 'pal.' + oracle]
    else:
        # Focused scripts fall back to Java source mode.
        target = ['-cp', classpath, str(ROOT / 'runtime/src/test/java/pal' / (oracle + '.java'))]
    command, environment = engine(runtime, 'java', *target, *args)
    os.execve(command[0], command, environment)


if __name__ == '__main__':
    if not os.environ.get('PP_PAL_TEST_BUDGET'):
        os.execv(sys.executable, [sys.executable, str(ROOT/'tests/run.py'), '--',
                                 sys.executable, str(pathlib.Path(__file__).resolve()), *sys.argv[1:]])
    if sys.argv[1] == '--compile':
        compile_classes(sys.argv[2], sys.argv[3])
    elif len(sys.argv) >= 3:
        run_oracle(sys.argv[1], sys.argv[2], sys.argv[3:])
    else:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
