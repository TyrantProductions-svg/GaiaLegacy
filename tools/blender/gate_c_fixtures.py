"""Project-owned ephemeral dimensional probes, not production tool artwork."""
import math

OWNER = 'GAIA_GATE_C_V0'
WORKSPACE = 'GAIA_ASSET_WORKSPACE'
SCENE = 'GAIA_GATE_C_SCENE'


def create_fixture(kind):
    import bpy
    if kind not in ('ruler', 'tool'):
        raise ValueError('Unknown synthetic fixture')
    if bpy.context.mode != 'OBJECT':
        raise ValueError('Leave unrelated edit/sculpt mode before authoring')
    names = ['GAIA_ASSET_ROOT', 'GAIA_C_Ruler', 'GAIA_C_Grip', 'GAIA_C_Tip']
    if (SCENE in bpy.data.scenes or WORKSPACE in bpy.data.collections
            or any(n in bpy.data.objects or n in bpy.data.meshes or n in bpy.data.materials for n in names)):
        raise ValueError('Reserved fixture names already exist; never repurpose them')
    handle = {'scene': None, 'workspace': None, 'root': None,
              'objects': [], 'meshes': [], 'materials': [], 'kind': kind}
    try:
        scene = bpy.data.scenes.new(SCENE)
        scene['gaia_owner'] = OWNER
        handle['scene'] = scene
        workspace = bpy.data.collections.new(WORKSPACE)
        workspace['gaia_owner'] = OWNER
        handle['workspace'] = workspace
        scene.collection.children.link(workspace)
        scene.unit_settings.system = 'METRIC'
        scene.unit_settings.scale_length = 1.0
        scene.unit_settings.length_unit = 'METERS'
        root = bpy.data.objects.new('GAIA_ASSET_ROOT', None)
        root['gaia_owner'] = OWNER
        handle['root'] = root
        handle['objects'].append(root)
        workspace.objects.link(root)
        if kind == 'ruler':
            root.location = (.25, .5, .75)
            _box(handle, 'GAIA_C_Ruler', (1.0, .02, .02), (0, 0, 0), (.12, .42, .62, 1), 0.0)
        else:
            _box(handle, 'GAIA_C_Grip', (.04, .025, .16), (0, 0, 0), (.08, .045, .02, 1), 0.0)
            tip = _box(handle, 'GAIA_C_Tip', (.018, .02, .08), (0, 0, .12), (.55, .58, .62, 1), .7)
            tip.rotation_euler.z = math.pi / 2
        scene.view_layers[0].update()
        return handle
    except Exception:
        release_fixture(handle)
        raise


def _box(handle, name, size, location, color, metallic):
    import bpy
    x, y, z = (d / 2 for d in size)
    vertices = [(-x,-y,-z),(x,-y,-z),(x,y,-z),(-x,y,-z),
                (-x,-y,z),(x,-y,z),(x,y,z),(-x,y,z)]
    faces = [(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]
    mesh = bpy.data.meshes.new(name)
    mesh['gaia_owner'] = OWNER
    handle['meshes'].append(mesh)
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    uv = mesh.uv_layers.new(name='UV0')
    for face in mesh.polygons:
        for loop, point in zip(face.loop_indices, [(0,0),(1,0),(1,1),(0,1)]):
            uv.data[loop].uv = point
    material = bpy.data.materials.new(name)
    material['gaia_owner'] = OWNER
    handle['materials'].append(material)
    material.use_nodes = True
    material.use_backface_culling = True
    shader = next(n for n in material.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    shader.inputs['Base Color'].default_value = color
    shader.inputs['Metallic'].default_value = metallic
    shader.inputs['Roughness'].default_value = .65
    mesh.materials.append(material)
    obj = bpy.data.objects.new(name, mesh)
    obj['gaia_owner'] = OWNER
    handle['objects'].append(obj)
    handle['workspace'].objects.link(obj)
    obj.parent = handle['root']
    obj.location = location
    return obj


def release_fixture(handle):
    """Remove only captured newly-owned references; never search/purge user data."""
    import bpy
    workspace, scene = handle['workspace'], handle['scene']
    for obj in handle['objects']:
        if obj.get('gaia_owner') != OWNER or any(c != workspace for c in obj.users_collection):
            raise RuntimeError('Ownership changed; cleanup requires controller review')
    if workspace and (workspace.get('gaia_owner') != OWNER or workspace.children
                      or any(o not in handle['objects'] for o in workspace.objects)):
        raise RuntimeError('Workspace ownership changed; do not delete')
    if scene and (scene == bpy.context.scene or scene.get('gaia_owner') != OWNER
                  or any(c != workspace for c in scene.collection.children)):
        raise RuntimeError('Scene ownership changed; do not delete')
    for obj in reversed(handle['objects']):
        bpy.data.objects.remove(obj, do_unlink=True)
    for mesh in handle['meshes']:
        if mesh.users or mesh.get('gaia_owner') != OWNER:
            raise RuntimeError('Shared fixture mesh; do not delete')
        bpy.data.meshes.remove(mesh)
    for material in handle['materials']:
        if material.users or material.get('gaia_owner') != OWNER:
            raise RuntimeError('Shared fixture material; do not delete')
        bpy.data.materials.remove(material)
    if workspace:
        bpy.data.collections.remove(workspace)
    if scene:
        bpy.data.scenes.remove(scene)
