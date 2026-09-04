# Gate 17.75C authoring provenance

All geometry/materials are project-owned synthetic code-authored probes. No
downloaded model/texture, external artwork, saved `.blend`, production chisel,
runtime resource or viewer is introduced. Source mode:
`GENERATED_EPHEMERAL_BLENDER_WORKSPACE`.

Sources: `tools/blender/gate_c_fixtures.py`, `gaia_export_glb.py`,
`validate_export.py`; operational commands: `tools/blender/README.md`.
The existing official Blender installation is used, not vendored or copied.
No new dependency or add-on. See existing Inspector dependency provenance for
the unchanged JglTF/Jackson Java graph and licenses.

## Actual environment and sources

- Windows Blender 5.1.2, Python 3.13.9, official exporter `io_scene_gltf2` 5.1.20.
- Gaia export script SHA-256:
  `f8361f4428f66383e6664a13fd6d5dd417f236e0f64aed61c69cbdd26e29f849`.
- Fixture source byte SHA-256:
  `b7f9eab31690e07ac8ccdcabb4cc425417d672925aab21433a04e8f392bf936c`.
- These are byte SHA-256 values, not Git blob hashes. The source is uncommitted;
  baseline is `d3a219767b2a16c6f4e5f7514019c56ca9cf6cf7`, not its content hash.
- Full explicit settings and exporter entrypoint byte hash live in each export
  receipt. Source paths/timestamps are deliberately absent from canonical bytes.

## Same-environment acceptance-003 (two independent export calls per fixture)

This final run follows the one bounded review closure (reject OBJECT material
overrides). Earlier acceptance-002 is retained as historical ignored evidence,
not falsely rebound to the new script hash. GLB/report bytes remain unchanged;
export/validated receipts changed because the exporter script changed.

| Fixture | GLB bytes | Exact GLB SHA-256 | Gate B each time |
|---|---:|---|---|
| ruler-1 / ruler-2 | 2192 | `359c1fba2c1411ab2874f560e85569fd29a80133ff101373b7ea6edf05ccdb4f` | PASS, no warnings |
| tool-1 / tool-2 | 3924 | `902e992711fec6d895ab7e1cc0a875a12f65164d33dbd11fb4960f8e1561d752` | PASS, no warnings |

Gate B raw report hashes (including stdout newline):

- Ruler: `ebdfd2a1faba44cd07aa43cb0dd7d326492691c9f6b992d09d278df6e8b12627`.
- Tool: `9aa40c40561ad492164f87e349d8801c174f1cf9f39b7326ac63f90e4cdb840b`.

Validated receipt byte hashes:

- Ruler: `895bf655a0c7212362a90ed88ed9c69678c24d60e799227d1dc88c2bb498f2b4`.
- Tool: `4760b9b24544f77512b387c73f7b7b280f975a6b00b768b82e1d59c01f86e703`.

Same-fixture GLB, report, export receipt and validated receipt are byte-identical
across both calls. This is NOT a cross-version or cross-platform guarantee.
No GLB normalization/repacking took place between export and validation.

## Dimensional and data coverage

Ruler: Blender mesh XYZ=(1,.02,.02)m, root translation=(.25,.5,.75)m.
Blender Z-up to glTF Y-up maps (x,y,z) to (x,z,-y). Expected glTF bounds:
min=(-.25,.74,-.51), max=(.75,.76,-.49). Actual values agree within 1e-6m;
actual dimensions=(1.0,0.019999999552965164,0.019999999552965164).
Unique/expanded counts: both 24 vertices, 12 triangles; 1 mesh, 2 nodes.

Tool: grip (.04,.025,.16)m and tip (.018,.02,.08)m centered .12m along Blender Z,
with tip Z-rotation pi/2. Expected glTF bounds min=(-.02,-.08,-.0125),
max=(.02,.16,.0125); actual dimensions=(0.03999999910593033,
0.23999999463558197,0.02500000037252903). Tolerance 1e-6m. Unique/expanded
48 vertices, 24 triangles; 2 meshes, 3 nodes. No universal tool working axis.

Both GLBs contain exactly POSITION/NORMAL/TEXCOORD_0, no images or extensions.
Tool uses two scalar metallic/roughness opaque single-sided materials. Gate B
PASS covers actual normals, winding, transforms, materials and actual bounds.
UV0 is retained without a texture; optional textured export was NOT exercised.

The deliberate scaled-ruler rejection-002 output is not an accepted asset:
`861cc31d077980034c6a149d109beabb8b9f3da4c2c6cfd40ed6487309d41634`.
Gate B exit1 / FAIL / SCENE_INVALID; receipt records FAIL, never approval.

## Evidence location and limitations

GLBs and raw/report/bound receipts: ignored
`tools/build/model-inspector/staging/gate-c/acceptance-003/`.
Failure output: sibling `rejection-002/`. Structural/RNA operational evidence:
sibling `evidence/`. Local evidence may contain the unsaved session path/context;
it is not portable production content and must not be staged.

Full closure workflow receipt: `evidence/blender-final-integration.json`; each of
fixture creation, failure transaction, material-override rejection, repeated export
and semantic-failure export records the same before/after structural fingerprint.
Pinned settings SHA-256:
`8de28c214af7d165409720215e59d5d06051f423541faf32cf5bc00f798f81f8`.
Installed official exporter entrypoint byte SHA-256:
`dddd7d2362b937f34a5d4a1978259317720a9bc647bb3b4dc113f9d5c38cbf56`.

Original and restored scene SHA-256:
`caef133ee26333bb28f0b7e3e44db753c3e9eb7b3245013d2d35035b32321008`.
Original unsaved Cube/Camera/Light session remained unsaved/clean, selected/active
Cube, Object mode. No save operation, user geometry change or preference change.

Windows real Blender and independent Windows JDK21 headless validation executed.
Real JDK17, Blender Linux/macOS, validator Linux/macOS: NOT RUN.
Authoring `.blend` persistence/source-LFS policy remains separately authorized.

## Gate E replay — gate-e-20260903

Gate E repeated both fixtures twice with the unchanged exporter/settings and a fresh
ephemeral workspace. Exact GLB bytes, report hashes and validated receipt hashes
match the acceptance evidence above. The scene fingerprint before and after was
`c5367e05e059cf92c26532bf7c86cb0b152a376ab58e677ad8121b825e49b52a`;
the source scene remained unsaved and no unrelated object/collection state changed.
Evidence is ignored under
`tools/build/model-inspector/staging/gate-c/gate-e-20260903/` and is not an
integration input.

The exact validated simple-tool GLB was then consumed by the Gate D viewer through
Gate B. Human inspection confirmed the expected model, navigation/toggles, rejected
invalid reload preserving CURRENT, valid recovery and clean close. This is pipeline
acceptance evidence only; it does not grant artistic or production asset approval,
and no production `.blend`, GLB or gameplay asset was created.
