"""Bounded Gate C export helpers. Gate B alone decides semantic conformance."""
import copy
import hashlib
import json
from pathlib import Path
import re

WORKSPACE = "GAIA_ASSET_WORKSPACE"
OWNER = "GAIA_GATE_C_V0"
PROFILE = "GAIA_GLB_HAND_TOOL_V0"
STAGING_ROOT = Path(__file__).resolve().parents[1] / "build/model-inspector/staging/gate-c"

# Explicit values audited from Blender 5.1.2 / official glTF exporter 5.1.20 RNA.
# Disabled-feature subordinate settings are also pinned, never loaded from the UI.
_SETTINGS = json.loads(r'''{
  "check_existing": false,
  "export_import_convert_lighting_mode": "SPEC",
  "gltf_export_id": "",
  "export_use_gltfpack": false,
  "export_gltfpack_tc": false,
  "export_gltfpack_tq": 8,
  "export_gltfpack_si": 1,
  "export_gltfpack_sa": false,
  "export_gltfpack_slb": false,
  "export_gltfpack_vp": 14,
  "export_gltfpack_vt": 12,
  "export_gltfpack_vn": 8,
  "export_gltfpack_vc": 8,
  "export_gltfpack_vpi": "Integer",
  "export_gltfpack_noq": true,
  "export_gltfpack_kn": false,
  "export_format": "GLB",
  "ui_tab": "GENERAL",
  "export_copyright": "Project-owned GaiaLegacy Gate C synthetic fixture",
  "export_image_format": "AUTO",
  "export_image_add_webp": false,
  "export_image_webp_fallback": false,
  "export_texture_dir": "",
  "export_jpeg_quality": 75,
  "export_image_quality": 75,
  "export_keep_originals": false,
  "export_texcoords": true,
  "export_normals": true,
  "export_gn_mesh": false,
  "export_draco_mesh_compression_enable": false,
  "export_draco_mesh_compression_level": 6,
  "export_draco_position_quantization": 14,
  "export_draco_normal_quantization": 10,
  "export_draco_texcoord_quantization": 12,
  "export_draco_color_quantization": 10,
  "export_draco_generic_quantization": 12,
  "export_tangents": false,
  "export_materials": "EXPORT",
  "export_unused_images": false,
  "export_unused_textures": false,
  "export_vertex_color": "NONE",
  "export_vertex_color_name": "Color",
  "export_all_vertex_colors": false,
  "export_active_vertex_color_when_no_material": false,
  "export_attributes": false,
  "use_mesh_edges": false,
  "use_mesh_vertices": false,
  "export_cameras": false,
  "use_selection": false,
  "use_visible": false,
  "use_renderable": false,
  "use_active_collection_with_nested": true,
  "use_active_collection": false,
  "use_active_scene": true,
  "collection": "GAIA_ASSET_WORKSPACE",
  "at_collection_center": false,
  "export_extras": false,
  "export_yup": true,
  "export_apply": false,
  "export_shared_accessors": false,
  "export_animations": false,
  "export_frame_range": false,
  "export_frame_step": 1,
  "export_force_sampling": false,
  "export_sampling_interpolation_fallback": "LINEAR",
  "export_pointer_animation": false,
  "export_animation_mode": "ACTIONS",
  "export_nla_strips_merged_animation_name": "Animation",
  "export_def_bones": false,
  "export_hierarchy_flatten_bones": false,
  "export_hierarchy_flatten_objs": false,
  "export_armature_object_remove": false,
  "export_leaf_bone": false,
  "export_optimize_animation_size": false,
  "export_optimize_animation_keep_anim_armature": false,
  "export_optimize_animation_keep_anim_object": false,
  "export_optimize_disable_viewport": false,
  "export_negative_frame": "SLIDE",
  "export_anim_slide_to_zero": false,
  "export_bake_animation": false,
  "export_merge_animation": "ACTION",
  "export_anim_single_armature": false,
  "export_reset_pose_bones": false,
  "export_current_frame": true,
  "export_rest_position_armature": false,
  "export_anim_scene_split_object": true,
  "export_skins": false,
  "export_influence_nb": 4,
  "export_all_influences": false,
  "export_morph": false,
  "export_morph_normal": false,
  "export_morph_tangent": false,
  "export_morph_animation": false,
  "export_morph_reset_sk_data": false,
  "export_lights": false,
  "export_try_sparse_sk": false,
  "export_try_omit_sparse_sk": false,
  "export_gpu_instances": false,
  "export_action_filter": false,
  "export_convert_animation_pointer": false,
  "export_nla_strips": false,
  "export_original_specular": false,
  "will_save_settings": false,
  "export_hierarchy_full_collections": false,
  "export_extra_animations": false,
  "export_loglevel": -1,
  "filter_glob": "*.glb"
}''')


def canonical_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False).encode("utf-8")


def settings():
    return dict(_SETTINGS)


def _no_links(path):
    for item in (path, *path.parents):
        if item.is_symlink() or item.is_junction():
            raise ValueError("Linked staging paths are not supported")


def safe_output(run_name, file_name):
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", run_name):
        raise ValueError("Expected one portable run name")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}\.glb", file_name):
        raise ValueError("Expected one portable GLB filename")
    root = STAGING_ROOT.absolute()
    _no_links(root)
    directory = root / run_name
    _no_links(directory)
    directory.mkdir(parents=True, exist_ok=True)
    output = directory / file_name
    _no_links(output)
    if output.exists() or output.with_suffix(".export.json").exists():
        raise FileExistsError("Staging output already exists")
    return output


def bind_validation(export_receipt, glb_bytes, report_bytes, exit_code):
    digest = hashlib.sha256(glb_bytes).hexdigest()
    if (export_receipt.get("schema") != "GAIA_BLENDER_EXPORT_V0"
            or export_receipt.get("profile") != PROFILE
            or export_receipt.get("profile_version") != 0
            or export_receipt.get("source_mode") != "GENERATED_EPHEMERAL_BLENDER_WORKSPACE"
            or export_receipt.get("glb_sha256") != digest
            or export_receipt.get("glb_bytes") != len(glb_bytes)):
        raise ValueError("Export receipt does not identify these exact bytes")
    report = json.loads(report_bytes)
    outcome = report.get("outcome")
    if (report.get("profile") != PROFILE or report.get("profileVersion") != 0
            or report.get("sourceSha256") != digest
            or outcome not in ("PASS", "PASS_WITH_WARNINGS", "FAIL")
            or exit_code != (1 if outcome == "FAIL" else 0)):
        raise ValueError("Validator report/exit does not bind to this export")
    receipt = copy.deepcopy(export_receipt)
    receipt["validation"] = {"outcome": outcome,
                             "report_sha256": hashlib.sha256(report_bytes).hexdigest()}
    receipt["asset_approval"] = "NOT_GRANTED"
    return receipt


def _owned_workspace(scene, workspace):
    import bpy
    if (scene is None or workspace is None or scene == bpy.context.scene
            or workspace.name != WORKSPACE
            or scene.get('gaia_owner') != OWNER or workspace.get('gaia_owner') != OWNER
            or scene.library or workspace.library
            or tuple(scene.collection.children) != (workspace,)
            or scene.collection.objects or workspace.children
            or scene.unit_settings.system != 'METRIC'
            or scene.unit_settings.scale_length != 1.0):
        raise ValueError('Expected isolated owned meter-scale workspace')
    if (sum(workspace in s.collection.children.values() for s in bpy.data.scenes) != 1
            or any(workspace in c.children.values() for c in bpy.data.collections)):
        raise ValueError('Shared workspace is not supported')
    objects = sorted(workspace.objects, key=lambda o: o.name)
    if not 1 <= len(objects) <= 64:
        raise ValueError('Expected bounded fixture membership')
    for obj in objects:
        if (obj.get('gaia_owner') != OWNER or obj.library or obj.override_library
                or tuple(obj.users_collection) != (workspace,) or obj.type not in ('MESH', 'EMPTY')
                or (obj.parent and obj.parent not in objects)
                or any(child not in objects for child in obj.children)
                or obj.constraints or obj.modifiers or obj.animation_data
                or obj.instance_type != 'NONE'):
            raise ValueError('Unsupported or shared owned object')
        if obj.type == 'MESH':
            mesh = obj.data
            if any(slot.link != 'DATA' for slot in obj.material_slots):
                raise ValueError('Object material overrides are outside the owned fixture workflow')
            if (mesh.get('gaia_owner') != OWNER or mesh.library or mesh.users != 1
                    or mesh.shape_keys or len(mesh.uv_layers) > 1 or mesh.color_attributes):
                raise ValueError('Unsupported or shared fixture mesh')
            for material in mesh.materials:
                if (not material or material.get('gaia_owner') != OWNER or material.library
                        or material.users != 1 or material.animation_data
                        or not material.use_nodes
                        or any(n.bl_idname not in ('ShaderNodeBsdfPrincipled', 'ShaderNodeOutputMaterial')
                               for n in material.node_tree.nodes)):
                    raise ValueError('Only owned untextured fixture materials are admitted by this workflow')
    return objects


def _exporter_identity():
    import bpy
    import io_scene_gltf2
    import sys
    if tuple(bpy.app.version) != (5, 1, 2) or tuple(io_scene_gltf2.bl_info['version']) != (5, 1, 20):
        raise ValueError('Exporter version requires a new capability audit')
    for addon in bpy.context.preferences.addons:
        module = sys.modules.get(addon.module)
        if module and any(hasattr(module, name) for name in
                          ('glTF2ExportUserExtension', 'glTF2ExportUserExtensions',
                           'glTF2_pre_export_callback', 'glTF2_post_export_callback')):
            raise ValueError('Custom glTF hooks require a separate safety audit')
    properties = bpy.ops.export_scene.gltf.get_rna_type().properties
    if any(key not in properties for key in _SETTINGS):
        raise ValueError('Exporter RNA does not match pinned configuration')
    return {'blender_version': bpy.app.version_string, 'module': 'io_scene_gltf2',
            'version': list(io_scene_gltf2.bl_info['version']),
            'entrypoint_sha256': hashlib.sha256(Path(io_scene_gltf2.__file__).read_bytes()).hexdigest()}


def _invoke_export(options):
    import bpy
    return bpy.ops.export_scene.gltf('EXEC_DEFAULT', **options)


def export_workspace(scene, workspace, run_name, file_name):
    """Export only ephemeral owned data. A receipt remains PENDING until Gate B."""
    import bpy
    if bpy.context.mode != 'OBJECT' or bpy.context.window is None:
        raise ValueError('Requires an Object-mode window, never changes user mode')
    objects = _owned_workspace(scene, workspace)
    identity = _exporter_identity()
    output = safe_output(run_name, file_name)
    options = settings()
    options['filepath'] = str(output)
    window = bpy.context.window
    original_scene, original_layer = window.scene, window.view_layer
    original_active = original_layer.objects.active
    original_selected = tuple(o for o in original_layer.objects if o.select_get(view_layer=original_layer))
    try:
        window.scene = scene
        window.view_layer = scene.view_layers[0]
        outcome = _invoke_export(options)
        if outcome != {'FINISHED'}:
            raise RuntimeError('Exporter did not finish; staging output is not admitted')
    finally:
        window.scene = original_scene
        window.view_layer = original_layer
        # Switching scenes does not alter user selection. Detect rather than silently repair it.
        if (original_layer.objects.active != original_active
                or tuple(o for o in original_layer.objects if o.select_get(view_layer=original_layer))
                != original_selected or bpy.context.mode != 'OBJECT'):
            raise RuntimeError('Unrelated UI state changed; controller review required')
    _no_links(output)
    data = output.read_bytes()
    receipt = {'schema': 'GAIA_BLENDER_EXPORT_V0', 'profile': PROFILE, 'profile_version': 0,
               'source_mode': 'GENERATED_EPHEMERAL_BLENDER_WORKSPACE',
               'exporter': identity, 'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               'settings': settings(), 'settings_sha256': hashlib.sha256(canonical_bytes(settings())).hexdigest(),
               'workspace': WORKSPACE, 'collections': [workspace.name],
               'objects': [o.name for o in objects], 'texture_sha256': [],
               'glb_sha256': hashlib.sha256(data).hexdigest(), 'glb_bytes': len(data),
               'validation': 'PENDING', 'asset_approval': 'NOT_GRANTED'}
    receipt_path = output.with_suffix('.export.json')
    _no_links(receipt_path)
    with receipt_path.open('xb') as stream:
        stream.write(canonical_bytes(receipt))
    return receipt
