# Gaia Model Inspector viewer

Gate 17.75D provides a standalone diagnostic viewer for one explicit GLB that
already passes `GAIA_GLB_HAND_TOOL_V0` validation. It is a development tool, not
Gaia runtime model loading and not artistic/production asset approval.

After Gate D acceptance, launch from the repository root with:

```powershell
.\gradlew.bat :tools:modelViewer --args='tools/build/model-inspector/staging/gate-c/acceptance-003/tool-1.glb'
```

Exactly one explicit local file is accepted. Gate A/B validation runs before any
window. Invalid input prints bounded diagnostics and creates no model window.
There is no file browser, directory scan, drag/drop, watcher or neighboring-file
resolution.

## Controls

- LMB drag: orbit
- MMB drag or Shift+LMB drag: pan
- wheel: zoom
- F: frame/reset model camera
- R: manual synchronous reload
- G: grid
- A: axes
- B: actual validated bounds
- W: wireframe
- Escape: close

Focus loss clears unfinished drag and pressed-edge state; held controls do not
replay on return. Window title distinguishes the current validated source SHA from
a failed reload candidate. A failed reload leaves the old model visible. Reload
has no retry, queue, watcher, background thread or history.

## Preview limits

Inspector preview lighting is diagnostic and is not Gaia runtime render parity.
The GLSL 410 renderer shows the validated base color, optional sRGB RGBA8 texture
and simple normal-based lighting. It intentionally omits full glTF PBR, IBL,
shadows, normal mapping, postprocessing and runtime held-item transforms. Grid,
axes and bounds are inspection references; they never alter validated model data.

The viewer consumes only `ValidatedModelSnapshot`. It never parses JglTF data,
reads JSON/accessors, decodes PNG/JPEG, changes HAND_TOOL_V0 legality or imports
anything into game resources. OpenGL resources are explicit context-owner objects
with transactional current/candidate replacement and deterministic cleanup.
