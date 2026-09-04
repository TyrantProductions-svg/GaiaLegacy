"""Run explicitly via Blender MCP; only Gate-C-owned temporary data is created."""
import importlib.util
import hashlib
import json
from pathlib import Path
import bpy


def load(name):
    path = Path(__file__).resolve().parents[1] / (name + '.py')
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def structural_state():
    assert len(bpy.data.objects) <= 256 and len(bpy.data.scenes) <= 16
    return {
        'file': {'path': bpy.data.filepath, 'saved': bpy.data.is_saved},
        'scenes': [{'name': s.name, 'collections': sorted(c.name for c in s.collection.children),
                    'objects': sorted(o.name for o in s.objects),
                    'camera': s.camera.name if s.camera else None, 'frame': s.frame_current,
                    'engine': s.render.engine,
                    'units': [s.unit_settings.system, s.unit_settings.scale_length, s.unit_settings.length_unit],
                    'view': [s.view_settings.view_transform, s.view_settings.look,
                             s.view_settings.exposure, s.view_settings.gamma]}
                   for s in sorted(bpy.data.scenes, key=lambda x: x.name)],
        'collections': [{'name': c.name, 'children': sorted(x.name for x in c.children),
                         'objects': sorted(x.name for x in c.objects),
                         'hide': [c.hide_viewport, c.hide_render]}
                        for c in sorted(bpy.data.collections, key=lambda x: x.name)],
        'objects': [{'name': o.name, 'type': o.type, 'data': o.data.name if o.data else None,
                     'parent': o.parent.name if o.parent else None,
                     'matrix': [list(row) for row in o.matrix_world],
                     'memberships': sorted(c.name for c in o.users_collection),
                     'materials': [s.material.name if s.material else None for s in o.material_slots],
                     'modifiers': [(m.name, m.type) for m in o.modifiers],
                     'hide': [o.hide_viewport, o.hide_render, o.hide_get()]}
                    for o in sorted(bpy.data.objects, key=lambda x: x.name)],
        'meshes': [{'name': m.name, 'counts': [len(m.vertices), len(m.edges), len(m.loops), len(m.polygons)],
                    'materials': [x.name if x else None for x in m.materials],
                    'uv': list(m.uv_layers.keys()),
                    'sample_positions': [list(m.vertices[i].co) for i in range(min(32, len(m.vertices)))],
                    'sample_faces': [list(m.polygons[i].vertices) for i in range(min(32, len(m.polygons)))]}
                   for m in sorted(bpy.data.meshes, key=lambda x: x.name)],
        'materials': [{'name': m.name, 'nodes': m.use_nodes, 'color': list(m.diffuse_color),
                       'metallic': m.metallic, 'roughness': m.roughness,
                       'node_types': sorted(n.bl_idname for n in m.node_tree.nodes) if m.node_tree else []}
                      for m in sorted(bpy.data.materials, key=lambda x: x.name)],
        'ui': {'scene': bpy.context.scene.name,
               'active': bpy.context.view_layer.objects.active.name if bpy.context.view_layer.objects.active else None,
               'selection': sorted(o.name for o in bpy.context.selected_objects), 'mode': bpy.context.mode},
        'counts': {k: len(getattr(bpy.data, k)) for k in
                   ['scenes', 'collections', 'objects', 'meshes', 'materials', 'images', 'cameras', 'lights']}}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                    ensure_ascii=False).encode()).hexdigest()


def fixture_checks():
    fixtures = load('gate_c_fixtures')
    before = structural_state()
    checks = []
    for kind, mesh_count in [('ruler', 1), ('tool', 2)]:
        handle = fixtures.create_fixture(kind)
        try:
            assert handle['workspace'].name == 'GAIA_ASSET_WORKSPACE'
            assert len(handle['meshes']) == mesh_count
            assert len(handle['workspace'].children) == 0
            assert handle['root'].name == 'GAIA_ASSET_ROOT'
            assert handle['scene'] != bpy.context.scene
            for obj in handle['objects']:
                assert tuple(obj.users_collection) == (handle['workspace'],)
                assert tuple(obj.scale) == (1.0, 1.0, 1.0)
                if obj.type == 'MESH':
                    assert len(obj.data.uv_layers) == 1
                    assert len(obj.data.vertices) == 8
            if kind == 'ruler':
                positions = [v.co for v in handle['meshes'][0].vertices]
                actual = [max(v[i] for v in positions)-min(v[i] for v in positions) for i in range(3)]
                assert all(abs(a-b)<1e-6 for a, b in zip(actual, (1.0, .02, .02)))
            checks.append(kind + ': authored geometry/ownership')
        finally:
            fixtures.release_fixture(handle)
        assert structural_state() == before, 'Unrelated Blender state changed'
    return {'checks': checks, 'before': before, 'after': structural_state(),
            'before_sha256': digest(before), 'after_sha256': digest(structural_state())}


def transaction_checks(run_name):
    fixtures, exporter = load('gate_c_fixtures'), load('gaia_export_glb')
    before = structural_state()
    checks = []
    handle = fixtures.create_fixture('ruler')
    try:
        export = exporter.export_workspace
        for scene, workspace, run, filename in [
                (None, None, run_name, 'missing.glb'),
                (handle['scene'], handle['workspace'], '../escape', 'bad.glb')]:
            try:
                export(scene, workspace, run, filename)
                raise AssertionError('Expected authoring/output rejection')
            except ValueError:
                checks.append('missing workspace / output boundary rejected')
        root = handle['root']
        del root['gaia_owner']
        try:
            try:
                export(handle['scene'], handle['workspace'], run_name, 'foreign.glb')
                raise AssertionError('Expected ownership rejection')
            except ValueError:
                checks.append('unowned member rejected')
        finally:
            root['gaia_owner'] = fixtures.OWNER
        original = exporter._invoke_export
        def fail(settings):
            assert bpy.context.scene == handle['scene']
            raise RuntimeError('injected export failure')
        exporter._invoke_export = fail
        try:
            try:
                export(handle['scene'], handle['workspace'], run_name, 'injected.glb')
                raise AssertionError('Expected exporter exception')
            except RuntimeError as error:
                assert str(error) == 'injected export failure'
                assert bpy.context.scene.name == before['ui']['scene']
                assert not (exporter.STAGING_ROOT / run_name / 'injected.export.json').exists()
                checks.append('exporter failure restores state / no receipt')
        finally:
            exporter._invoke_export = original
    finally:
        fixtures.release_fixture(handle)
    assert structural_state() == before, 'Unrelated Blender state changed'
    return {'checks': checks, 'before_sha256': digest(before),
            'after_sha256': digest(structural_state())}


def repeated_exports(run_name):
    fixtures, exporter = load('gate_c_fixtures'), load('gaia_export_glb')
    before = structural_state()
    exports = {}
    for kind in ('ruler', 'tool'):
        handle = fixtures.create_fixture(kind)
        try:
            receipts = []
            for index in (1, 2):
                # Change only temporary selection, proving it does not choose export membership.
                layer = handle['scene'].view_layers[0]
                for obj in handle['objects']:
                    obj.select_set(index == 2, view_layer=layer)
                layer.objects.active = handle['root'] if index == 2 else None
                receipt = exporter.export_workspace(handle['scene'], handle['workspace'],
                                                    run_name, kind + '-' + str(index) + '.glb')
                assert receipt['objects'] == sorted(o.name for o in handle['objects'])
                assert receipt['validation'] == 'PENDING'
                receipts.append(receipt)
            assert receipts[0] == receipts[1], 'Same-environment export bytes/receipt differ'
            exports[kind] = receipts
        finally:
            fixtures.release_fixture(handle)
        assert structural_state() == before, 'Unrelated Blender state changed'
    return {'exports': exports, 'before': before, 'after': structural_state(),
            'before_sha256': digest(before), 'after_sha256': digest(structural_state()),
            'saved': bpy.data.is_saved, 'dirty': bpy.data.is_dirty}


def rejected_semantic_export(run_name):
    fixtures, exporter = load('gate_c_fixtures'), load('gaia_export_glb')
    before = structural_state()
    handle = fixtures.create_fixture('ruler')
    try:
        # Deliberately invalid owned authoring: Gate B, not Blender, decides FAIL.
        handle['root'].scale = (2, 2, 2)
        handle['scene'].view_layers[0].update()
        receipt = exporter.export_workspace(handle['scene'], handle['workspace'], run_name, 'scaled.glb')
        assert receipt['validation'] == 'PENDING'
    finally:
        fixtures.release_fixture(handle)
    assert structural_state() == before
    return {'receipt': receipt, 'before_sha256': digest(before),
            'after_sha256': digest(structural_state())}


def material_override_rejection(run_name):
    fixtures, exporter = load('gate_c_fixtures'), load('gaia_export_glb')
    before = structural_state()
    handle = fixtures.create_fixture('ruler')
    invoked = []
    try:
        obj = next(o for o in handle['objects'] if o.type == 'MESH')
        slot = obj.material_slots[0]
        data_material = slot.material
        alternate = data_material.copy()
        handle['materials'].append(alternate)
        # Only new fixture data participates: never borrow a user's material.
        slot.link = 'OBJECT'
        slot.material = alternate
        def unexpected(options):
            invoked.append(True)
            raise AssertionError('Object material override reached exporter')
        exporter._invoke_export = unexpected
        try:
            try:
                exporter.export_workspace(handle['scene'], handle['workspace'], run_name, 'override.glb')
                raise AssertionError('Object material override accepted')
            except ValueError:
                assert not invoked
        finally:
            slot.material = data_material
            slot.link = 'DATA'
    finally:
        fixtures.release_fixture(handle)
    assert structural_state() == before
    return {'checks': ['OBJECT material override rejected before invocation'],
            'before_sha256': digest(before), 'after_sha256': digest(structural_state())}


if globals().get('family') == 'material-override':
    result = material_override_rejection(globals()['run_name'])
elif globals().get('family') == 'semantic-fail':
    result = rejected_semantic_export(globals()['run_name'])
elif globals().get('family') == 'export':
    result = repeated_exports(globals()['run_name'])
elif 'run_name' in globals():
    result = transaction_checks(globals()['run_name'])
else:
    result = fixture_checks()
