"""Explicit acceptance evidence checks, not a second semantic validator."""
import hashlib
import json
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import gaia_export_glb as exporter


def verify(run):
    results = {}
    expectations = {'ruler': ([-.25, .74, -.51], [.75, .76, -.49], 24, 12),
                    'tool': ([-.02, -.08, -.0125], [.02, .16, .0125], 48, 24)}
    for kind, (minimum, maximum, vertices, triangles) in expectations.items():
        repeated = []
        for number in (1, 2):
            path = run / (kind + '-' + str(number) + '.glb')
            glb = path.read_bytes()
            report_bytes = path.with_suffix('.report.json').read_bytes()
            source_bytes = path.with_suffix('.export.json').read_bytes()
            bound_bytes = path.with_suffix('.validated.json').read_bytes()
            receipt, report, bound = map(json.loads, (source_bytes, report_bytes, bound_bytes))
            expected = exporter.bind_validation(receipt, glb, report_bytes, 0)
            expected['export_receipt_sha256'] = hashlib.sha256(source_bytes).hexdigest()
            assert bound == expected
            assert receipt['script_sha256'] == hashlib.sha256(Path(exporter.__file__).read_bytes()).hexdigest()
            assert receipt['settings'] == exporter.settings()
            assert receipt['settings_sha256'] == hashlib.sha256(exporter.canonical_bytes(exporter.settings())).hexdigest()
            assert report['outcome'] == 'PASS' and report['diagnostics'] == []
            for field, wanted in [('min', minimum), ('max', maximum)]:
                assert all(abs(a-b) <= 1e-6 for a, b in zip(report['bounds'][field], wanted))
            for domain in ('unique', 'expanded'):
                assert report['statistics'][domain + 'VertexCount'] == vertices
                assert report['statistics'][domain + 'TriangleCount'] == triangles
            # Inspect the unchanged exporter JSON only to document what the Gate B PASS covers.
            length = struct.unpack_from('<I', glb, 12)[0]
            model = json.loads(glb[20:20+length])
            assert not model.get('images') and not model.get('extensionsUsed')
            for mesh in model['meshes']:
                for primitive in mesh['primitives']:
                    assert set(primitive['attributes']) == {'POSITION', 'NORMAL', 'TEXCOORD_0'}
            assert {node['name'] for node in model['nodes']} == set(receipt['objects'])
            assert all(material.get('doubleSided', False) is False for material in model['materials'])
            repeated.append((glb, report_bytes, source_bytes, bound_bytes))
        assert repeated[0] == repeated[1], 'Byte identity is separate from semantic PASS'
        results[kind] = {'glb_sha256': hashlib.sha256(repeated[0][0]).hexdigest(),
                         'report_sha256': hashlib.sha256(repeated[0][1]).hexdigest(),
                         'receipt_sha256': hashlib.sha256(repeated[0][3]).hexdigest(),
                         'bounds': report['bounds'], 'statistics': report['statistics'],
                         'byte_determinism': 'PASS', 'semantic_validation': 'PASS'}
    return results


if __name__ == '__main__':
    print(json.dumps(verify(Path(sys.argv[1])), sort_keys=True, indent=2))
