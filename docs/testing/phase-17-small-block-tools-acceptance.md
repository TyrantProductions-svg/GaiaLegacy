# Phase 17 small-block tools acceptance

## Automated evidence

Gate-focused verification covers standalone items/resources, canonical route
and targeting, immutable preview/ghost, atomic mutations, Creative edits,
Survival reservation ordering and conservation, coarse hardness/output,
bounded HUD/feedback, production-session save/restore, repository unload/return,
and rapid pressed-edge edits.

The bounded rapid-edit fixture is:

```powershell
.\gradlew.bat :tools:profileDetailEdits --console=plain --no-daemon
```

The representative Gate 17D run attempted 120 canonical edges, applied 120,
rejected/staled zero, affected only `ChunkKey[0,0]`, ended at revision 121 with
zero occupied quarters, used about 9.14 ms total and about 5.23 ms maximum for
the coldest sample. These are local observations, not universal FPS limits.

Final proportional, packaged-resource, installed-resource, `installDist`, and
repository-wide results are recorded in the Phase 17 handoff and Gate 17D
implementation notes.

## Windows runtime matrix - PASS

The controller-accepted manual run used the real production GLFW/OpenGL path
with development shortcuts enabled. It reported every required scenario PASS:

1. Placeholder chisel, precision/coarse mode, selected material, Survival unit
   count, local target/preview, and R stone/dirt cycling were readable/correct.
2. Creative FULL sculpt, DETAIL remove/place, whole-parent coarse removal,
   rapid editing, and ghost refresh/no stale visual passed.
3. Survival remove recovered exactly one matching unit; placement consumed
   exactly one; inventory-full rejection left world/inventory unchanged;
   uniform 64/64 coarse output produced one FULL block; partial/mixed coarse
   removal produced no micro refund.
4. Real unload-radius departure/return restored DETAIL shape/material, raycast,
   collision, and mesh.
5. Save & Quit, complete process exit, and fresh-process reload restored DETAIL,
   chisel identity, unit counts, raycast, collision, and rendering.
6. Alt+Tab/resume produced no input replay and retargeting passed.
7. Manual resize preserved HUD, ghost/crosshair, targeting, held presentation,
   viewport, and shaders.
8. Ordinary FULL break/place, Q, Ctrl+Q, pickup, pause/resume, and clean exit
   passed.

Native Apple Silicon interactive status must be reported `NOT RUN` unless a
human executes the arm64 GLFW/OpenGL 4.1/Retina sequence.

## Intentional/deferred behavior

- Phase 17 chisel art is an `ACCEPTED PLACEHOLDER`. Canonical identity and
  capability are independent from its future Phase 18 Blender/GLB visual.
- Chisel gameplay sound is `DEFERRED`; no suitable bounded committed SFX seam
  exists and no new audio subsystem is introduced.
- Normal Survival chisel acquisition and FULL/64 conversion are Phase 18.
- Partial/mixed coarse removal intentionally yields no micro-unit output.
- Phase 19 natural DETAIL generation is not implemented.
- Full repository verification currently takes about 5h38m and the broad
  save/streaming matrix exceeds ten hours because of existing heavy scenarios.
  Future work may audit fast/integration/heavy/release Gradle tiers without
  weakening release coverage.
