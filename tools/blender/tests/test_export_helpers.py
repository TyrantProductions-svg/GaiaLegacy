"""Pure receipt/output tests; never imports bpy or executes Blender."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import gaia_export_glb as exporter


class ExportHelpersTest(unittest.TestCase):
    def test_canonical_receipt_is_order_independent_utf8_without_timestamp(self):
        self.assertEqual(exporter.canonical_bytes({'b': 1, 'a': '高原'}),
                         '{"a":"高原","b":1}'.encode('utf-8'))
        with self.assertRaises(ValueError):
            exporter.canonical_bytes({'x': float('nan')})

    def test_scope_and_unsupported_feature_settings_are_explicit(self):
        settings = exporter.settings()
        self.assertEqual(settings['export_format'], 'GLB')
        self.assertEqual(settings['collection'], 'GAIA_ASSET_WORKSPACE')
        self.assertEqual(settings['export_vertex_color'], 'NONE')
        for key in ('use_selection', 'use_visible', 'use_renderable',
                    'use_active_collection', 'export_animations', 'export_skins',
                    'export_morph', 'export_tangents', 'export_attributes',
                    'export_lights', 'export_cameras', 'export_extras',
                    'export_use_gltfpack', 'export_draco_mesh_compression_enable',
                    'export_gpu_instances', 'will_save_settings', 'export_apply'):
            self.assertIs(settings[key], False, key)
        self.assertIs(settings['use_active_scene'], True)
        self.assertIs(settings['export_normals'], True)
        self.assertIs(settings['export_texcoords'], True)
        self.assertIs(settings['export_yup'], True)
        settings['export_format'] = 'BROKEN'
        self.assertEqual(exporter.settings()['export_format'], 'GLB')

    def test_output_rejects_traversal_absolute_names_and_non_glb(self):
        for run, file in [('../outside', 'x.glb'), ('ok', '../x.glb'),
                          ('D:/outside', 'x.glb'), ('ok', 'x.blend'),
                          ('ok', 'x.glb:stream'), ('', 'x.glb')]:
            with self.subTest(run=run, file=file), self.assertRaises(ValueError):
                exporter.safe_output(run, file)

    def test_output_is_contained_and_existing_asset_is_never_overwritten(self):
        exporter.STAGING_ROOT.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='test-', dir=exporter.STAGING_ROOT) as d:
            output = exporter.safe_output(Path(d).name, 'probe.glb')
            self.assertEqual(output.parent, Path(d))
            output.write_bytes(b'preserve')
            with self.assertRaises(FileExistsError):
                exporter.safe_output(Path(d).name, 'probe.glb')
            self.assertEqual(output.read_bytes(), b'preserve')

    def test_linked_output_directory_is_rejected(self):
        exporter.STAGING_ROOT.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='test-', dir=exporter.STAGING_ROOT) as d:
            link = Path(d) / 'link'
            try:
                link.symlink_to(Path(d), target_is_directory=True)
            except OSError:
                self.skipTest('OS does not permit local symlink creation')
            with self.assertRaises(ValueError):
                exporter._no_links(link)

    def test_valid_run_name_reaches_link_and_junction_guards(self):
        linked = exporter.STAGING_ROOT / 'linked-run'
        for method in ('is_symlink', 'is_junction'):
            with self.subTest(method=method), patch.object(
                    Path, method, lambda path: path == linked), self.assertRaises(ValueError):
                exporter.safe_output('linked-run', 'probe.glb')

    def receipt(self, data):
        return {'schema': 'GAIA_BLENDER_EXPORT_V0', 'profile': 'GAIA_GLB_HAND_TOOL_V0',
                'profile_version': 0, 'source_mode': 'GENERATED_EPHEMERAL_BLENDER_WORKSPACE',
                'glb_sha256': hashlib.sha256(data).hexdigest(), 'glb_bytes': len(data),
                'validation': 'PENDING'}

    def report(self, data, outcome='PASS'):
        return json.dumps({'profile': 'GAIA_GLB_HAND_TOOL_V0', 'profileVersion': 0,
                           'sourceSha256': hashlib.sha256(data).hexdigest(),
                           'outcome': outcome, 'diagnostics': []}).encode()

    def test_exact_file_report_receipt_binding_and_no_artistic_approval(self):
        data = b'exact exported bytes'
        report = self.report(data)
        result = exporter.bind_validation(self.receipt(data), data, report, 0)
        self.assertEqual(result['validation']['outcome'], 'PASS')
        self.assertEqual(result['validation']['report_sha256'], hashlib.sha256(report).hexdigest())
        self.assertEqual(result['asset_approval'], 'NOT_GRANTED')
        self.assertNotIn('blend_sha256', result)

    def test_modified_asset_wrong_report_and_bad_exit_cannot_claim_pass(self):
        data = b'asset'
        for actual, report, code in [(b'changed', self.report(data), 0),
                                     (data, self.report(b'other'), 0),
                                     (data, self.report(data), 1),
                                     (data, b'{}', 0)]:
            with self.subTest(actual=actual, code=code), self.assertRaises(ValueError):
                exporter.bind_validation(self.receipt(data), actual, report, code)

    def test_validator_fail_is_recorded_as_fail_not_approved(self):
        data = b'asset'
        result = exporter.bind_validation(self.receipt(data), data, self.report(data, 'FAIL'), 1)
        self.assertEqual(result['validation']['outcome'], 'FAIL')
        self.assertEqual(result['asset_approval'], 'NOT_GRANTED')


if __name__ == '__main__':
    unittest.main()
