# Gate C — ephemeral Blender authoring/export

This is offline tooling, NOT runtime GLB support or a second HAND_TOOL_V0 validator.
Gate B `GaiaGlbValidator` / `ModelInspectorMain` is the only semantic authority.
No `.blend` may be saved without separate approval. No viewer is implemented.

## Admission and ownership

Audited environment: Windows Blender **5.1.2**, bundled Python **3.13.9**, official
`io_scene_gltf2` exporter **5.1.20**. Other versions require a capability audit.
The scripts use Python 3.12+ pathlib junction support. No package/add-on install.

`gate_c_fixtures.create_fixture('ruler'|'tool')` creates a new dedicated scene
`GAIA_GATE_C_SCENE`, direct collection `GAIA_ASSET_WORKSPACE`, and root
`GAIA_ASSET_ROOT`. Existing reserved names reject; nothing is repurposed. Every
new reference is captured and tagged `GAIA_GATE_C_V0`. Cleanup removes only these
captured references after ownership checks. Nested/shared/linked collections and
shared mesh/material data are not supported by this bounded synthetic workflow.

`gaia_export_glb.export_workspace(scene, collection, run, filename)` derives the
export set from direct membership, never current selection/visibility. Object
mode is required; the script does not force the user's edit/sculpt mode to change.
It temporarily switches the window's scene for the official exporter, restores
the original scene/view layer in `finally`, and checks original selection/active
object/mode. It neither saves Blender data nor changes preferences.

No background user edits should run concurrently with this explicitly invoked
authoring operation. It is not a sandbox for hostile Blender Python/add-ons.
Enabled custom glTF export hooks reject. Output ancestors cannot be links or
junctions; portable run/file names cannot escape the fixed build staging root.
Existing outputs/receipts reject. Never override `STAGING_ROOT` for production
resource export. Incomplete outputs are unapproved staging evidence, not assets.

## Pinned settings and limits of the workflow

`gaia_export_glb.settings()` is the complete explicit audited RNA option set.
Important settings: GLB, exact named collection, active temporary scene, Y-up,
normals + UVs + materials, current frame, no modifier application, no selection/
visibility filtering, no cameras/lights/animations/skins/morphs/vertex colors/
tangents/custom attributes/extras/compression/instancing. `will_save_settings=false`;
use `EXEC_DEFAULT`, not a UI invoke which loads remembered options.

The audited exporter has a logging defect when a nonnegative `export_loglevel`
is supplied: it does not set `export_settings['loglevel']`. Explicit `-1` is its
working auto-logging path. This affects logging only, not an output setting.
No upstream exporter patch or Blender preference change was made.

The current authoring preconditions intentionally support only owned MESH/EMPTY
objects with DATA-linked simple untextured Principled/output materials (OBJECT
material overrides reject before exporter invocation), no modifiers,
constraints, animation, shape keys or extra UV/color layers. These are authoring
convenience restrictions, not a duplicate geometry/material validator. Numerical
rigidity, normals, winding, bounds and all HAND_TOOL_V0 rules remain Gate B's job.

## Reproduction (explicit local acceptance, not automatic CI Blender launch)

Read/audit the current user scene first. Set `sys.dont_write_bytecode=True` only
around the invocation and restore it afterwards, or use standalone Python `-B`.
From a trusted Blender Python context run `tests/blender_export_checks.py` using
`runpy.run_path` with an explicitly resolved checkout-relative script path:

- No init globals: create/check/clean ruler and tool, compare structural receipts.
- `{'run_name':'transaction-NEW'}`: missing/foreign/path/exception failure tests.
- `{'family':'export','run_name':'acceptance-NEW'}`: each fixture exported twice,
  with different temporary selections, then cleaned. Never reuse a run name.
- `{'family':'semantic-fail','run_name':'rejection-NEW'}`: export an owned scaled
  ruler, to prove Gate B rejection (no Blender-side semantic PASS claim).
- `{'family':'material-override','run_name':'closure-NEW'}`: verify an OBJECT
  material override using only temporary fixture data rejects before invocation.

All files stay in ignored `tools/build/model-inspector/staging/gate-c/<run>/`.
This does not save `.blend`, retain a production source asset, or grant art approval.

After Blender finishes, use a **separate normal process**. Build the existing
isolated modelInspector classes with the checked-in wrapper. Set
`$InspectorClasspath` to `tools/build/classes/java/modelInspector`,
`tools/build/resources/modelInspector` and the existing resolved six JARs:
JglTF model/impl-v1/impl-v2 3.0.1, Jackson core/databind 2.22.1, annotations 2.22.
Use the platform path separator; no engine/game/LWJGL classpath entries.
These are already admitted dependencies, not new downloads for Gate C.

```powershell
.\gradlew.bat :tools:modelInspectorClasses --console=plain --no-daemon
python -B tools/blender/validate_export.py --classpath $InspectorClasspath tools/build/model-inspector/staging/gate-c/acceptance-NEW/ruler-1.glb
```

The adapter executes this exact command shape with `shell=False` and a timeout:

```text
java -Djava.awt.headless=true -cp <isolated-classpath> com.gaia.tools.model.ModelInspectorMain --json <exact-staged.glb>
```

No bpy, MCP or live Blender process is used for validation. The user's Blender
session is not closed. Exit 0 means PASS/PASS_WITH_WARNINGS; exit 1 is semantic
FAIL; usage/error/timeout cannot produce a successful validation receipt.
Run the adapter once for each export, then:

```powershell
python -B tools/blender/tests/verify_staged_exports.py tools/build/model-inspector/staging/gate-c/acceptance-NEW
python -B -m unittest discover -s tools/blender/tests -p 'test_*.py' -v
.\gradlew.bat :tools:modelInspectorTest --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :tools:check --rerun-tasks --console=plain --no-daemon
```

Python pure tests and real Blender tests are explicit Gate C checks, not included
in Java test counts or automatically run on machines without Blender. Gate A/B
permanent Java tests remain included in normal tools:check.

## Receipts and authority

`<name>.export.json`: deterministic `GAIA_BLENDER_EXPORT_V0`, profile/version,
Blender/exporter identity and entrypoint hash, Gaia export script SHA-256, complete
settings/fingerprint, workspace/object IDs, generated ephemeral source mode,
exact output length/SHA-256, no textures, validation PENDING / NOT_GRANTED.

`<name>.report.json`: unchanged stdout bytes of Gate B, including final newline.
`<name>.validated.json`: binds original export receipt hash, GLB hash, Gate B
report hash/outcome and process exit. Never artistic/production approval.
Inputs are compared before/after the process to reject changed file evidence.
Receipts omit absolute paths, times, usernames, machine names and fictional
`.blend` hashes. Local before/after scene/RNA evidence is separate and ignored.

Exact same-environment GLB byte identity is reported separately from semantic
PASS. No cross-Blender-version, cross-OS or cross-JDK determinism claim.
