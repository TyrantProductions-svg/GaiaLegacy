"""Thin process adapter tests; semantic rules remain in the Java validator."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import gaia_export_glb as exporter
import validate_export as adapter


class ValidationAdapterTest(unittest.TestCase):
    def setUp(self):
        exporter.STAGING_ROOT.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='adapter-', dir=exporter.STAGING_ROOT)
        self.addCleanup(self.temp.cleanup)
        self.model = Path(self.temp.name) / 'probe.glb'
        self.model.write_bytes(b'original')
        self.receipt = {'schema': 'GAIA_BLENDER_EXPORT_V0', 'profile': exporter.PROFILE,
                        'profile_version': 0, 'source_mode': 'GENERATED_EPHEMERAL_BLENDER_WORKSPACE',
                        'glb_sha256': hashlib.sha256(b'original').hexdigest(), 'glb_bytes': 8}
        self.model.with_suffix('.export.json').write_bytes(exporter.canonical_bytes(self.receipt))

    def completed(self, outcome='PASS'):
        report = exporter.canonical_bytes({'profile': exporter.PROFILE, 'profileVersion': 0,
                                           'sourceSha256': self.receipt['glb_sha256'], 'outcome': outcome}) + b'\n'
        return subprocess.CompletedProcess([], 1 if outcome == 'FAIL' else 0, report, b'')

    def test_explicit_headless_java_without_blender_and_exact_report_preservation(self):
        process = self.completed()
        with patch.object(adapter.subprocess, 'run', return_value=process) as run:
            result = adapter.validate_export(self.model, 'java', 'isolated-classpath')
        args, kwargs = run.call_args
        self.assertEqual(args[0][:5], ['java', '-Djava.awt.headless=true', '-cp', 'isolated-classpath',
                                      'com.gaia.tools.model.ModelInspectorMain'])
        self.assertEqual(args[0][-2:], ['--json', str(self.model)])
        self.assertFalse(kwargs['shell'])
        self.assertEqual(self.model.with_suffix('.report.json').read_bytes(), process.stdout)
        self.assertEqual(result['validation']['outcome'], 'PASS')
        with self.assertRaises(FileExistsError):
            adapter.validate_export(self.model, 'java', 'isolated-classpath')

    def test_validator_fail_is_not_relabelled_pass(self):
        with patch.object(adapter.subprocess, 'run', return_value=self.completed('FAIL')):
            result = adapter.validate_export(self.model, 'java', 'cp')
        self.assertEqual(result['validation']['outcome'], 'FAIL')
        self.assertEqual(result['asset_approval'], 'NOT_GRANTED')

    def test_process_error_or_timeout_never_publishes_validation_receipt(self):
        for behavior in (subprocess.CompletedProcess([], 2, b'', b'usage'),
                         subprocess.TimeoutExpired('java', 30)):
            with self.subTest(behavior=type(behavior).__name__):
                kwargs = {'side_effect': behavior} if isinstance(behavior, Exception) else {'return_value': behavior}
                with patch.object(adapter.subprocess, 'run', **kwargs), self.assertRaises(Exception):
                    adapter.validate_export(self.model, 'java', 'cp')
                self.assertFalse(self.model.with_suffix('.validated.json').exists())

    def test_changed_file_during_process_cannot_bind_report(self):
        def mutate(*args, **kwargs):
            self.model.write_bytes(b'changed')
            return self.completed()
        with patch.object(adapter.subprocess, 'run', side_effect=mutate), self.assertRaises(ValueError):
            adapter.validate_export(self.model, 'java', 'cp')
        self.assertFalse(self.model.with_suffix('.validated.json').exists())

    def test_ambiguous_dotdot_paths_fail_before_any_process_or_file_read(self):
        path = exporter.STAGING_ROOT / '..' / 'outside.glb'
        with patch.object(adapter.subprocess, 'run') as run, self.assertRaises(ValueError):
            adapter.validate_export(path, 'java', 'cp')
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
