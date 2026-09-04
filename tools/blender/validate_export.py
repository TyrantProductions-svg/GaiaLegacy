"""Standalone exact-byte receipt adapter. No Blender import or semantic validator."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

import gaia_export_glb as exporter


def validate_export(model, java, classpath):
    model = Path(model).absolute()
    if '..' in model.parts:
        raise ValueError('Ambiguous parent traversal is not a staged input')
    root = exporter.STAGING_ROOT.absolute()
    exporter._no_links(model)
    if not model.is_relative_to(root) or model.suffix != '.glb':
        raise ValueError('Expected an explicit staged GLB')
    source = model.with_suffix('.export.json')
    report_path, receipt_path = model.with_suffix('.report.json'), model.with_suffix('.validated.json')
    for path in (source, report_path, receipt_path):
        exporter._no_links(path)
    if report_path.exists() or receipt_path.exists():
        raise FileExistsError('Validation evidence already exists')
    original = model.read_bytes()
    source_bytes = source.read_bytes()
    receipt = json.loads(source_bytes)
    process = subprocess.run([java, '-Djava.awt.headless=true', '-cp', classpath,
                              'com.gaia.tools.model.ModelInspectorMain', '--json', str(model)],
                             shell=False, capture_output=True, timeout=30)
    if model.read_bytes() != original or source.read_bytes() != source_bytes:
        raise ValueError('Input changed during validation')
    bound = exporter.bind_validation(receipt, original, process.stdout, process.returncode)
    bound['export_receipt_sha256'] = hashlib.sha256(source_bytes).hexdigest()
    with report_path.open('xb') as stream:
        stream.write(process.stdout)
    with receipt_path.open('xb') as stream:
        stream.write(exporter.canonical_bytes(bound))
    return bound


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--classpath', required=True, help='Existing isolated modelInspector runtime classpath')
    parser.add_argument('model', type=Path)
    args = parser.parse_args()
    result = validate_export(args.model, args.java, args.classpath)
    print(exporter.canonical_bytes(result).decode('utf-8'))
    return 1 if result['validation']['outcome'] == 'FAIL' else 0


if __name__ == '__main__':
    raise SystemExit(main())
