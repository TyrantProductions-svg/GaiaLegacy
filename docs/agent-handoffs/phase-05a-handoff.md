# Phase 5A Handoff: Render Pipeline Core

Implementation HEAD: `0ea3fa7b45162d6fb4fd48953fb61b49bb780c3f`

Branch: `feat/render-pipeline-core`

Base: `origin/main` at
`647d91d5fcab15a0acdd60e7898729e35182f71e`

Handoff status: **APPROVED**. The earlier Engine-owner and Game/shared-owner
re-reviews were approved. A later final branch review reported two Minor
findings; both were fixed, and the final branch re-review approved the result
with no remaining Critical, Important, or Minor finding. Native macOS
verification is **NOT RUN**.

## Completed work

- Moved the GLSL 410 world vertex and fragment shaders into engine-owned,
  JAR-safe resources loaded through the Phase 2 `AssetManager` and
  `ResourceLocation` diagnostic path.
- Replaced the old inline `Shader` with guarded `ShaderProgram` compilation,
  linking, required-uniform caching, failure diagnostics, partial-resource
  cleanup, and a deterministic fake backend for tests.
- Migrated voxel meshes from five floats to one ten-float / 40-byte
  `VoxelVertexFormat` containing position, UV, normal, face/light, and AO.
- Added a non-owning runtime `Material` backed by the existing
  `MaterialDefinition` identity and one renderer-owned program/texture pair.
- Added stable opaque/transparent queue boundaries and immutable pass order:
  sky, world, debug.
- Added exact OpenGL state capture/restoration across normal and exceptional
  pass exits.
- Routed `Renderer`, `Engine`, `GameBootstrap`, and `GameLoop` through one
  `renderFrame` path without changing Phase 3 independent-Chunk ownership.
- Added architecture guards and engine-JAR/installDist-JAR shader verification
  tasks.
- Created the normative Phase 5A render contract and updated the current
  architecture baseline.
- Ran the final Windows clean build and post-fix packaging/resource gates at
  implementation HEAD `0ea3fa7`.
- Recorded valid serial Windows development and installDist launcher
  acceptance, both with exit code 0.
- Resolved two owner findings with focused failure-path regressions:
  `0fe593b` restores captured render state when pass-state application fails,
  and `e603946` names both shader resources in missing-uniform diagnostics.
- Committed the original Task 9 documentation as `e400f6f` and completed its
  immediate post-commit clean-status, HEAD, and branch-diff verification.
- Resolved final branch review Minor 1 in `0ea3fa7`: ShaderProgram cleanup now
  preserves the primary failure when the backend throws the same instance.
- Resolved final branch review Minor 2 in this documentation correction:
  stale pre-commit wording and all completed baseline/Tasks 1-8 checkboxes now
  reflect actual history.

This final-review documentation correction changes no production or test code.
This worker ran no GUI command and performed no stage, commit, push, pull
request, merge, force-push, or modification of `main`. The existing `e400f6f`
documentation and `0ea3fa7` production commits were completed before this
correction.

## Unfinished work and platform truth

- Native macOS `./gradlew clean test build`:
  **NOT RUN**.
- Native macOS `./gradlew :game`, GLSL 410 compilation, Retina framebuffer,
  resize/focus/F1/Escape acceptance:
  **NOT RUN**.
- Phase 5B lighting, ambient-occlusion evaluation, sky/fog/gamma, and
  deliberate culling remain deferred.
- Production transparent Chunk sections, material splitting, and distance
  sorting remain deferred.

Windows automation or prior Phase 4 manual evidence is not used to infer the
native macOS results.

## Windows interactive acceptance

### Development run

The controller ran a valid serial:

```powershell
.\gradlew.bat :game --console=plain --no-daemon
```

- Rendered the current world and grass/dirt/stone atlas.
- Plains/tree, rolling-hills, and rocky highland/outcrop directions remained
  recognizable.
- No black screen or inline-shader regression was observed.
- Movement with W was accepted after viewport focus; Space jump input and
  mouse-look were accepted.
- F1 cursor release and recapture worked.
- Maximizing the window updated the full framebuffer without black bars.
- Focus loss/restore was observed during a preliminary correctly rendering
  run.
- Escape closed the window.
- The valid serial log ended `BUILD SUCCESSFUL`; `dev-game.exit=0`;
  duration 3 minutes 39 seconds.

An earlier preliminary run rendered correctly but was discarded because a
parallel infrastructure `gradlew --stop` terminated it with exit code 1. That
discarded run is not a product failure and is not the acceptance result.

The cave entrance was not re-navigated under the user's earlier explicit
waiver: the entrance had already been found and did not require another agent
inspection.

### installDist launcher

The controller launched:

```powershell
.\game\build\install\game\bin\game.bat
```

- The installed distribution rendered the same hills, trees, and materials.
- The log recorded `Engine initialized`.
- Mouse-look worked.
- Escape closed the window.
- `install-game.exit=0`.

Screenshots from both acceptances were inspected in-app only and were not
committed.

The later fixes affect failure rollback/diagnostics, same-instance cleanup
failure preservation, and documentation state only. They do not change shader
sources, normal pass state, vertex data, world generation, rendering output,
or input behavior. The valid development and installDist exit-0 results
therefore remain the manual acceptance evidence; no later GUI rerun was
required or performed.

## Core architecture decisions

### Vertex data

`VoxelVertexFormat` is the only stride/attribute authority:

| Location | Field | Components | Float offset | Byte offset |
| ---: | --- | ---: | ---: | ---: |
| 0 | position | 3 | 0 | 0 |
| 1 | UV | 2 | 3 | 12 |
| 2 | normal | 3 | 5 | 20 |
| 3 | face/light | 1 | 8 | 32 |
| 4 | ambient occlusion | 1 | 9 | 36 |

The stride is ten floats / 40 bytes. Stable face IDs are NORTH=0, SOUTH=1,
UP=2, DOWN=3, WEST=4, EAST=5, and encoding is
`faceId * 16 + lightLevel`. Phase 5A emits light 15 and AO 1.0.

### Resources, materials, and cleanup

- Shader identities are `overlord:shaders/world.vert` and
  `overlord:shaders/world.frag`.
- Required uniforms are `projection`, `view`, `model`, and `textureAtlas`.
- Runtime `Material` does not own or clean its program/texture.
- `Renderer` owns the shared atlas texture and shader program and cleans them
  in that order. Initialization failures clean all completed GPU resources
  before escaping.
- Gaia reuses `gaia:opaque`; no second asset/material registry exists.

### Queue, passes, and state

- Pass order is `sky`, `world`, `debug`.
- Sky uses depth-test off, depth-write on, blend off, cull off, and clears
  color/depth.
- Opaque/CUTOUT uses depth-test/write on, blend off, cull off.
- The transparent API uses depth-test on, depth-write off, source-alpha
  blending, cull off, and stable insertion order.
- Debug is disabled and performs no draw or state mutation.
- Every active scope captures/restores depth enable/write, blend enable plus
  RGB/alpha factors/equations, cull enable, current program, active texture
  unit, and texture-unit-0 2D binding.
- Queue cleanup is protected by both pipeline and renderer `finally` blocks.

### Thread and Chunk ownership

- Renderer lifecycle, shader/texture/VAO/VBO lifecycle, uniform upload, draw,
  resize, state mutation, pass execution, and cleanup remain on the captured
  main/context thread through `MainThreadGuard`.
- CPU generation and snapshot meshing may run on workers and may create only
  CPU `ChunkMeshData`.
- `ChunkRepository` remains revision/dirty/publication authority.
  `ChunkMeshManager` remains current-result/upload/install/release authority.
  Revision checks, stale rejection, unload cleanup, and one independent
  `ChunkRenderObject` per loaded Chunk remain intact.

The full normative boundary is
`docs/architecture/phase-05a-render-contract.md`.

## Exact modified files relative to `origin/main`

The documentation-preparation working tree changes these exact 69 tracked
paths relative to `origin/main`.

### Shared build, design, plan, architecture, and handoff

- `docs/agent-handoffs/phase-05a-handoff.md`
- `docs/architecture/current-baseline.md`
- `docs/architecture/phase-05a-render-contract.md`
- `docs/superpowers/plans/2026-07-25-phase-5a-render-pipeline-core.md`
- `docs/superpowers/specs/2026-07-25-phase-5a-render-pipeline-core-design.md`
- `engine/build.gradle`
- `game/build.gradle`

### Engine production and resources

- `engine/src/main/java/com/overlord/Main.java`
- `engine/src/main/java/com/overlord/core/Engine.java`
- `engine/src/main/java/com/overlord/renderer/Mesh.java`
- `engine/src/main/java/com/overlord/renderer/RenderAssets.java`
- `engine/src/main/java/com/overlord/renderer/Renderer.java`
- `engine/src/main/java/com/overlord/renderer/Shader.java` (deleted)
- `engine/src/main/java/com/overlord/renderer/Texture.java`
- `engine/src/main/java/com/overlord/renderer/TextureBinding.java`
- `engine/src/main/java/com/overlord/renderer/material/Material.java`
- `engine/src/main/java/com/overlord/renderer/pass/DebugRenderPass.java`
- `engine/src/main/java/com/overlord/renderer/pass/RenderContext.java`
- `engine/src/main/java/com/overlord/renderer/pass/RenderPass.java`
- `engine/src/main/java/com/overlord/renderer/pass/RenderPipeline.java`
- `engine/src/main/java/com/overlord/renderer/pass/SkyRenderPass.java`
- `engine/src/main/java/com/overlord/renderer/pass/WorldRenderPass.java`
- `engine/src/main/java/com/overlord/renderer/queue/RenderItem.java`
- `engine/src/main/java/com/overlord/renderer/queue/RenderQueue.java`
- `engine/src/main/java/com/overlord/renderer/shader/OpenGlShaderBackend.java`
- `engine/src/main/java/com/overlord/renderer/shader/ShaderBackend.java`
- `engine/src/main/java/com/overlord/renderer/shader/ShaderBinding.java`
- `engine/src/main/java/com/overlord/renderer/shader/ShaderProgram.java`
- `engine/src/main/java/com/overlord/renderer/shader/ShaderProgramException.java`
- `engine/src/main/java/com/overlord/renderer/shader/ShaderResourceLoader.java`
- `engine/src/main/java/com/overlord/renderer/shader/ShaderSourceSet.java`
- `engine/src/main/java/com/overlord/renderer/shader/ShaderStage.java`
- `engine/src/main/java/com/overlord/renderer/state/BlendMode.java`
- `engine/src/main/java/com/overlord/renderer/state/OpenGlRenderStateBackend.java`
- `engine/src/main/java/com/overlord/renderer/state/RenderStateBackend.java`
- `engine/src/main/java/com/overlord/renderer/state/RenderStateScope.java`
- `engine/src/main/java/com/overlord/renderer/state/RenderStateSnapshot.java`
- `engine/src/main/java/com/overlord/renderer/state/RenderStateSpec.java`
- `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java`
- `engine/src/main/java/com/overlord/voxel/ChunkMeshData.java`
- `engine/src/main/java/com/overlord/voxel/VoxelVertexAttribute.java`
- `engine/src/main/java/com/overlord/voxel/VoxelVertexFormat.java`
- `engine/src/main/resources/assets/overlord/shaders/world.frag`
- `engine/src/main/resources/assets/overlord/shaders/world.vert`

### Engine tests

- `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`
- `engine/src/test/java/com/overlord/renderer/ChunkRenderBackendTest.java`
- `engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java`
- `engine/src/test/java/com/overlord/renderer/RenderPipelineArchitectureTest.java`
- `engine/src/test/java/com/overlord/renderer/RendererStructureTest.java`
- `engine/src/test/java/com/overlord/renderer/material/MaterialTest.java`
- `engine/src/test/java/com/overlord/renderer/pass/RenderPipelineTest.java`
- `engine/src/test/java/com/overlord/renderer/pass/WorldRenderPassTest.java`
- `engine/src/test/java/com/overlord/renderer/queue/RenderQueueTest.java`
- `engine/src/test/java/com/overlord/renderer/shader/ShaderProgramTest.java`
- `engine/src/test/java/com/overlord/renderer/shader/ShaderResourceLoaderTest.java`
- `engine/src/test/java/com/overlord/renderer/state/RenderStateScopeTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshDataTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshLifecycleStructureTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`
- `engine/src/test/java/com/overlord/voxel/VoxelVertexFormatTest.java`

### Game production and tests

- `game/src/main/java/com/gaia/GameBootstrap.java`
- `game/src/main/java/com/gaia/GameLoop.java`
- `game/src/main/java/com/gaia/assets/GaiaResourceLoader.java`
- `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- `game/src/test/java/com/gaia/RenderArchitectureTest.java`
- `game/src/test/java/com/gaia/assets/GaiaProductionAssetsTest.java`
- `game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java`

Ignored `.superpowers/sdd` coordination reports are not tracked branch
deliverables.

## Windows automated verification

All results below are final post-fix evidence at implementation HEAD
`0ea3fa7`.

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

- **PASSED** after the `0ea3fa7` fix task.
- 22 actionable tasks, 22 executed.
- Engine XML: 60 suites, 574 tests, 0 failures, 0 errors, 0 skipped.
- Game XML: 29 suites, 249 tests, 0 failures, 0 errors, 0 skipped.
- Total: 89 suites, 823 tests, 0 failures, 0 errors, 0 skipped.

```powershell
.\gradlew.bat :game:verifyPackagedResources `
  --rerun-tasks --console=plain --no-daemon
```

- **PASSED**: `BUILD SUCCESSFUL` in 10 seconds.
- 5 actionable tasks, 5 executed.

```powershell
.\gradlew.bat :engine:verifyPackagedShaderResources `
  --rerun-tasks --console=plain --no-daemon
```

- **PASSED**: `BUILD SUCCESSFUL` in 8 seconds.
- 4 actionable tasks, 4 executed.

```powershell
.\gradlew.bat :game:verifyInstalledShaderResources `
  --rerun-tasks --console=plain --no-daemon
```

- **PASSED**: `BUILD SUCCESSFUL` in 11 seconds.
- 9 actionable tasks, 9 executed.

Both `engine/build/libs/engine-0.1.0.jar` and
`game/build/install/game/lib/engine-0.1.0.jar` contain exactly the required
entries:

```text
assets/overlord/shaders/world.frag
assets/overlord/shaders/world.vert
```

## Owner-review findings and resolutions

### Earlier owner-review cycle

Two owner findings are resolved in production and focused tests:

- `0fe593b` (`fix(rendering): restore state when pass setup fails`) fixes the
  state-scope construction gap. If `RenderStateBackend.apply` fails after
  capture, `RenderStateScope.open` now restores the incoming snapshot before
  rethrowing the same apply failure. A rollback failure is attached as
  suppressed, including self-suppression protection. Three focused regression
  tests cover Error propagation, rollback failure, and shared-failure identity.
- `e603946` (`fix(rendering): identify shader resources in uniform errors`)
  fixes incomplete missing-uniform diagnostics. The failure now names the
  program, first missing uniform, vertex `ResourceLocation`, and fragment
  `ResourceLocation`; lookup still stops at the first missing required
  uniform and partial GPU resources are cleaned.

Both fixes passed the complete post-fix gate and all hygiene/resource checks.

That owner re-review verdict was **APPROVED**.

- Engine owner: **APPROVED**, with no remaining Critical, Important, or Minor
  finding. The re-review explicitly confirmed that the `0fe593b` state-apply
  rollback finding and the `e603946` missing-uniform resource-diagnostic
  finding are closed.
- Game/shared owner: **APPROVED**, with no remaining Critical, Important, or
  Minor finding. The re-review explicitly confirmed that the documentation
  Minor referring to deleted `Renderer.renderChunks` is closed by the accurate
  `Renderer.renderFrame` guard statement.

### Later final branch review

The later final branch review found two additional Minor issues:

- Minor 1: `ShaderProgram` attempted to self-suppress when cleanup threw the
  exact same failure instance as the primary shader failure. Commit `0ea3fa7`
  preserves the primary failure, suppresses only distinct cleanup failures,
  and adds the focused RED/GREEN regression. Verification passed the focused
  shader suite 15/15, Task 7 integration, and final Windows clean build 22/22.
- Minor 2: the handoff still contained pre-`e400f6f` commit wording and the
  implementation plan left 60 completed baseline/Tasks 1-8 checkboxes
  unchecked. This documentation correction removes the stale statements and
  records every completed checkbox as `[x]`.

Both later Minors are fixed. Final branch re-review is **APPROVED**, with no
remaining Critical, Important, or Minor finding.

## Hygiene and scope audit

Fresh Task 9 checks reported:

- `git diff --check origin/main`: no whitespace error;
- `git status --short` before Task 9 edits: empty;
- `git ls-files --others --exclude-standard` before Task 9 edits: empty;
- tracked generated/crash/screenshot/IDE/cache scan: 0 matches;
- `gradle.properties` absolute-JDK scan: 0 matches;
- game production OpenGL import/call scan: 0 matches;
- GLSL-above-410 / OpenGL-above-4.1 scan: 0 matches;
- compute-shader / SSBO symbol scan: 0 matches;
- engine-to-game production dependency scan: 0 matches;
- worldgen, physics, player, gameplay mutation, `ChunkRepository`, and
  production `ChunkMeshManager` diff scan: 0 matches.

The only game production diffs are the planned composition/frame paths:

```text
game/src/main/java/com/gaia/GameBootstrap.java
game/src/main/java/com/gaia/GameLoop.java
game/src/main/java/com/gaia/assets/GaiaResourceLoader.java
```

Final hygiene, status, inventory, and diff checks are rerun directly against
the `0ea3fa7` branch plus this documentation correction and recorded below.

## Known risks

- The transparent queue preserves insertion order only. There is no
  camera-distance sorting, material-split Chunk mesh, or production
  transparent block path.
- Normal, face/light, and AO are emitted but not yet consumed for visual
  lighting. Fullbright cave/terrain depth limitations remain until Phase 5B.
- Cull remains disabled; enabling it without winding and visual tests can
  remove valid faces.
- Architecture/source scans are strong regression guards but cannot prove the
  absence of semantically hidden coupling.
- Automated fake-backend tests cannot replace live-driver shader compilation,
  exact window/input behavior, or native macOS verification.
- The earlier Engine-owner and Game/shared-owner re-reviews were approved.
  The two later final-branch Minors are fixed, and final branch re-review is
  also approved with no remaining finding.

## Interfaces Phase 5B must not break

- Preserve Java 17, the checked-in Wrapper, engine-to-game dependency
  direction, OpenGL 4.1, and GLSL 410.
- Preserve the 40-byte vertex layout, stable face IDs, and
  `faceId * 16 + lightLevel` encoding.
- Preserve `ResourceLocation`, `AssetManager`, `MaterialDefinition`, and
  `RenderType` as the only resource/material identity system.
- Preserve cached required uniforms or make any extension explicit and
  backward-compatible.
- Preserve runtime material GPU non-ownership and renderer ownership of the
  shared texture/program.
- Preserve queue cleanup and exact OpenGL state restoration on every normal
  and exceptional exit.
- Preserve main/context-thread ownership for every GLFW/OpenGL/GPU operation.
- Preserve `ChunkRepository` revision/dirty/publication authority and
  `ChunkMeshManager` upload/install/release authority.
- Preserve per-Chunk objects, local transforms/bounds, stale rejection,
  successful-replacement ordering, unload cleanup, and empty-mesh behavior.
- Do not couple rendering to world generation, player physics, gameplay
  mutation, inventory/world items, or UI.
- Treat lighting/AO/sky/fog/gamma/culling and transparent material splitting/
  sorting as deliberate reviewed extensions, not implicit Phase 5A behavior.

## Final phase report

Task 9 documentation commit `e400f6f` and its immediately verified branch
stat:

```text
69 files changed, 6642 insertions(+), 331 deletions(-)
```

The post-commit status was clean, HEAD resolved to `e400f6f`, and the
post-commit status/HEAD/diff verification passed before the later code fix.

Final committed branch `git diff --stat origin/main...HEAD` at `0ea3fa7`:

```text
69 files changed, 6686 insertions(+), 331 deletions(-)
```

Current final-review documentation correction relative to `origin/main`:

```text
69 files changed, 6755 insertions(+), 331 deletions(-)
```

Suggested final implementation commit:

```text
refactor(rendering): establish render passes materials and shader resources
```

Completed Task 9 documentation commit:

```text
e400f6f docs(rendering): record Phase 5A render contracts
```

Suggested pull request title:

```text
refactor(rendering): add cross-platform render pipeline core
```

Suggested pull request description:

```markdown
## Summary

- move the GLSL 410 world program into engine-owned JAR resources with guarded
  diagnostics and cleanup
- migrate Chunk meshes to one 40-byte position/UV/normal/face-light/AO vertex
  contract
- add non-owning materials, stable render queues, ordered sky/world/debug
  passes, and exact OpenGL state restoration
- preserve Phase 3 revision, stale-result, unload, and independent-Chunk GPU
  ownership
- add architecture guards plus engine and installDist shader-resource checks

## Verification

- Windows `clean test build`: 89 suites / 823 tests, zero
  failure/error/skip
- standalone packaged-resource verification passed
- standalone engine-JAR shader verification passed
- standalone installed-engine-JAR shader verification passed
- hygiene, JDK, OpenGL/GLSL, module-boundary, and protected-scope scans passed

## Manual/platform follow-up

- Windows development and installDist interactive acceptance: passed, exit 0
- earlier Engine-owner and Game/shared-owner reviews: APPROVED, no findings
- later final branch review: two Minor findings fixed; re-review APPROVED
- native macOS clean build and interactive smoke: NOT RUN
```

Task 9 documentation commit `e400f6f` and its post-commit verification are
complete. The later final-review implementation fix is `0ea3fa7`; this
closeout records the final approved branch re-review.
