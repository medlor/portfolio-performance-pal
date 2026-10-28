#!/usr/bin/env python3
"""Runtime directory checks shared by the launcher, updater and tests."""
import json
import os
import pathlib
import shutil
import sys

KEYS = ('schema', 'identity', 'wrapperRevision', 'upstreamRevision', 'upstreamRelease', 'xmlModel', 'jdk', 'platform')


def read_runtime(path):
    """Return runtime.json labels; refuse missing, partial or renamed runtimes."""
    path = pathlib.Path(path)
    unavailable = ValueError('Selected runtime is unavailable or incomplete; build it with tools/build-runtime: ' + str(path))
    try:
        data = json.loads((path / 'runtime.json').read_text())
    except (OSError, ValueError) as failure:
        raise unavailable from failure
    if not isinstance(data, dict) or data.get('schema') != 1 or any(key not in data for key in KEYS):
        raise unavailable
    if data['identity'] != path.name or not (path / 'jdk/bin/java').is_file():
        raise unavailable
    return data


def remove_runtime(path):
    """Delete a read-only runtime: restore owner write access first."""
    path = pathlib.Path(path)
    if not path.exists():
        return
    for directory, _, _ in os.walk(path):
        os.chmod(directory, 0o700)
    shutil.rmtree(path)


if __name__ == '__main__':
    if sys.argv[1:2] == ['identity'] and len(sys.argv) == 3:
        try:
            print(read_runtime(sys.argv[2])['identity'])
        except ValueError as failure:
            print('ERROR: ' + str(failure), file=sys.stderr)
            sys.exit(3)
    else:
        print('Usage: runtime_dir.py identity PATH', file=sys.stderr)
        sys.exit(2)
