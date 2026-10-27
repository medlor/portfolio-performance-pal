#!/usr/bin/env python3
"""Focused updater unit checks that need no release server or build."""
import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('pp_pal_update', ROOT / 'tools/update.py')
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)


class BuildRuntimeReuse(unittest.TestCase):
    def test_mismatched_existing_runtime_is_refused_not_deleted(self):
        # The existing directory may be the active runtime; a failed rebuild must not leave no engine.
        with tempfile.TemporaryDirectory() as temporary:
            state = pathlib.Path(temporary)
            package = state / 'package'
            (package / 'runtime').mkdir(parents=True)
            (package / 'runtime/release-package.json').write_text(json.dumps({'upstream': {'tag': '0.88.0'}}))
            revision = 'a' * 40
            existing = state / 'runtimes' / ('0.88.0-' + revision[:12])
            (existing / 'jdk/bin').mkdir(parents=True)
            (existing / 'jdk/bin/java').write_text('')
            (existing / 'runtime.json').write_text(json.dumps({
                'schema': 1, 'identity': existing.name, 'wrapperRevision': revision, 'upstreamRevision': 'b' * 40,
                'upstreamRelease': '0.88.0', 'xmlModel': '70', 'jdk': 'Temurin-21.0.11+10', 'platform': 'linux/amd64',
                'inputs': {'sourceSha256': '1' * 64, 'releaseSha256': '2' * 64, 'jdkSha256': '3' * 64}}))
            digests = {'sourceSha256': '4' * 64, 'releaseSha256': '2' * 64, 'jdkSha256': '3' * 64}
            with self.assertRaisesRegex(update.UpdateError, 'different inputs'):
                update.build_runtime(package, state / 'inputs', revision, state, digests)
            self.assertTrue((existing / 'runtime.json').is_file(), 'Existing runtime was deleted')

    def test_matching_existing_runtime_is_reused(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = pathlib.Path(temporary)
            package = state / 'package'
            (package / 'runtime').mkdir(parents=True)
            (package / 'runtime/release-package.json').write_text(json.dumps({'upstream': {'tag': '0.88.0'}}))
            revision = 'a' * 40
            digests = {'sourceSha256': '1' * 64, 'releaseSha256': '2' * 64, 'jdkSha256': '3' * 64}
            existing = state / 'runtimes' / ('0.88.0-' + revision[:12])
            (existing / 'jdk/bin').mkdir(parents=True)
            (existing / 'jdk/bin/java').write_text('')
            (existing / 'runtime.json').write_text(json.dumps({
                'schema': 1, 'identity': existing.name, 'wrapperRevision': revision, 'upstreamRevision': 'b' * 40,
                'upstreamRelease': '0.88.0', 'xmlModel': '70', 'jdk': 'Temurin-21.0.11+10', 'platform': 'linux/amd64',
                'inputs': digests}))
            self.assertEqual(update.build_runtime(package, state / 'inputs', revision, state, digests), existing)


if __name__ == '__main__':
    unittest.main()
