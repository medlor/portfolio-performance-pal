#!/usr/bin/env python3
"""Run existing Java oracles with optional once-per-suite compilation."""
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def compile_classes(image, destination):
    destination = pathlib.Path(destination).absolute()
    destination.mkdir(parents=True, exist_ok=False)
    sources = sorted((ROOT / 'runtime/src/test/java/pal').glob('*.java'))
    subprocess.run([
        'docker', 'run', '--rm', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}',
        '-v', f'{ROOT}/runtime/src/test/java:/tests:ro', '-v', f'{destination}:/classes:rw',
        '--entrypoint', 'javac', image, '-encoding', 'UTF-8', '--release', '21',
        '-cp', '/opt/pp/classes:/opt/pp/lib/*', '-d', '/classes',
        *['/tests/pal/' + path.name for path in sources],
    ], check=True)


if __name__ == '__main__':
    if not os.environ.get('PP_PAL_TEST_BUDGET'):
        os.execv(sys.executable, [sys.executable, str(ROOT/'tests/run.py'), '--',
                                 sys.executable, str(pathlib.Path(__file__).resolve()), *sys.argv[1:]])
    if sys.argv[1] == '--compile':
        compile_classes(sys.argv[2], sys.argv[3])
    else:
        args = sys.argv[1:]
        classes = os.environ.get('PP_PAL_TEST_CLASSES')
        if classes:
            for index, arg in enumerate(args):
                if arg.endswith('/runtime/src/test/java:/tests:ro'):
                    args[index] = classes + ':/tests:ro'
                elif arg.startswith('/tests/pal/') and arg.endswith('.java'):
                    args[index] = 'pal.' + pathlib.Path(arg).stem
                elif arg == '-cp':
                    args[index + 1] = '/tests:' + args[index + 1]
        os.execvp('docker', ['docker', *args])
