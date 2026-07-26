# Phase 5B Handoff — Visual Rendering Upgrade

Final delivery HEAD:
`f05693f4bfbdcc2b2866131aa092f12ebc5bbcba`

Final branch status:
**APPROVED AND READY FOR PULL REQUEST**

The final documentation commit, post-commit verification, clean-status check,
and branch-wide owner reviews are complete.

## Status

Task 13 documentation, automated evidence, Windows manual acceptance, and
owner reviews are complete on `feat/rendering-visual-upgrade`, based on
`origin/main` `438859d` at implementation commit `c247ec1`. Both code-owner
verdicts are **APPROVED** contingent on this documentation refresh; the owner
documentation finding is resolved by these four documents. Native macOS is
**NOT RUN**. The documentation commit is being created with the required
message; no push, pull request, or merge has been performed.

## Completed work

- Added Phase 5B shader-linear terrain lighting, face light, AO, sky gradient,
  fog, conservative frustum culling, DPI/framebuffer handling, and render
  metrics without widening the engine-to-game dependency direction.
- Preserved Phase 5A resource/pass/state contracts and Phase 3 repository
  revision, stale-result, independent-Chunk, and GPU ownership contracts.
- Added test-only architecture guards for GLSL 410, single shader gamma path,
  texture sampling, nine immutable meshing snapshots, diagonal invalidation,
  thread ownership, stale lifecycle, and game-side OpenGL containment.
- Wrote the normative contract and refreshed the current architecture baseline.
- Ran fresh Windows automation and independent resource packaging checks.
- Completed Windows development and installDist acceptance with metrics,
  visual, culling, resize, input, focus, and clean-exit evidence.
- Resolved the only owner-review findings, which concerned documentation
  currency rather than production code.

## Unfinished work and unavailable evidence

- **Phase 5A FPS comparison: NOT CAPTURED.** Do not invent comparable FPS.
  No same-position, same-orientation, same-window, same-scale FPS comparison
  can be made.
- **Exact numeric player position/orientation: NOT CAPTURED.** The Windows
  acceptance exercised movement and rotation, but did not record numeric pose.
- **macOS: NOT RUN.** No native build, GLSL compilation, Retina,
  resize/focus/input, or exit inference is permitted.
- **Task 13 post-commit verification: PENDING controller validation.** After
  this documentation commit, the controller must confirm HEAD/status/stat and
  that no push, pull request, or merge occurred.

## Core architecture decisions

- `RenderVisualSettings` supplies normalized sun direction, linear sky/fog,
  ambient/directional strengths, fog bounds, and a shader sRGB path; exact
  values and validation are normative in the Phase 5B contract.
- World and sky shaders are GLSL 410 resources. Atlas decode, lighting, fog,
  and one encode happen in shader code; framebuffer sRGB is disabled.
- AO uses two tangent sides plus a diagonal sample against immutable center and
  eight-neighbor data. Direct and diagonal halo dependencies invalidate safely.
- Texture sampling is level-zero nearest and half-texel inset; no mipmaps.
- Six normalized frustum planes conservatively retain AABBs to epsilon 0.01.
- Metrics are per-frame resets and remain complete even if rendering exits by
  exception; a zero framebuffer is a non-drawable but cleanup-safe frame.
- All GLFW/OpenGL/GPU work remains context-main-thread work. Workers only
  build CPU mesh data; repository revision checks and manager main-thread
  upload/release prevent stale installation and renderer-driven unload.

## Fresh Windows automated verification

All commands were run from the repository root on 2026-07-26.

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

**PASSED** — 22 actionable tasks, 22 executed, `BUILD SUCCESSFUL` in 44s.

```powershell
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
```

**PASSED** — 5 actionable tasks, 5 executed, `BUILD SUCCESSFUL` in 10s.

```powershell
.\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
```

**PASSED** — 4 actionable tasks, 4 executed, `BUILD SUCCESSFUL` in 9s.

```powershell
.\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
```

**PASSED** — 9 actionable tasks, 9 executed, `BUILD SUCCESSFUL` in 11s.

Every current `TEST-*.xml` was parsed after the fresh build:

| Module | XML suites | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| Engine | 69 | 642 | 0 | 0 | 0 |
| Game | 30 | 251 | 0 | 0 | 0 |
| Total | 99 | 893 | 0 | 0 | 0 |

## Windows development acceptance

The development run used
`JAVA_TOOL_OPTIONS=-Dgaia.renderMetrics=true` with `gradlew :game` and exited
with code `0`. Three stabilized initial samples were:

| FPS | Frame ms | Visible Chunks | Draw calls | Triangles |
| ---: | ---: | ---: | ---: | ---: |
| 101.79 | 9.82 | 36 | 37 | 107591 |
| 103.51 | 9.66 | 36 | 37 | 107591 |
| 99.22 | 10.08 | 36 | 37 | 107591 |

A later camera state reported 28 visible Chunks, 29 draw calls, and 67045
triangles, confirming that the visible submission set changes with camera
orientation. Exact numeric player position and orientation were not captured.

Win32 resizing changed the captured window from 1026x607 to 787x494 after an
800x500 request, and rendering continued. F1 cursor capture, movement,
jump/landing, camera rotation, focus loss to VSCode, Alt+Tab recovery, and
Escape all passed. The sky gradient, fog, face-light/AO hierarchy, atlas edges,
and nearby Chunk stability were visually acceptable. No screenshots were
committed.

## Windows installDist acceptance

The installed launcher exited with code `0`. Three stabilized initial samples
were:

| FPS | Frame ms | Visible Chunks | Draw calls | Triangles |
| ---: | ---: | ---: | ---: | ---: |
| 100.55 | 9.95 | 36 | 37 | 107591 |
| 103.17 | 9.69 | 36 | 37 | 107591 |
| 95.95 | 10.42 | 36 | 37 | 107591 |

A later camera state reported 29 visible Chunks, 30 draw calls, and 92685
triangles. F1, camera rotation, resize from 1026x607 to 747x474 after a 760x480
request, and Escape passed. Installed shader and texture resources rendered
normally.

## Phase 5A comparison and platform status

Phase 5A comparable FPS was **NOT CAPTURED**. Its structural behavior submitted
all installed non-empty Chunk render objects, performed one draw for each, and
had no sky geometry draw or frustum filtering. Phase 5B development metrics
changed from 36 visible / 37 draws to 28 / 29 after camera movement; installDist
changed from 36 / 37 to 29 / 30. These observations demonstrate culling but are
not a controlled Phase 5A/5B performance comparison because exact numeric pose
was not recorded. Native macOS is **NOT RUN**.

## Owner-review verdicts

- **Engine owner: APPROVED.** No code findings. The only Minor finding was to
  refresh stale documentation fields; this handoff, baseline, contract, and
  plan update resolve it.
- **Game/shared owner: APPROVED contingent on documentation refresh.**
  Production composition, reporter, build scripts, and shared code were
  approved. The only Important finding was documentation currency; this
  refresh resolves it without production changes.

## Hygiene and scope audit

`git diff --check origin/main`, status, untracked-file, tracked-artifact,
absolute-JDK, Game-OpenGL, OpenGL-above-4.1, GLSL-above-410, compute/SSBO,
engine-to-game dependency, framebuffer-sRGB-enable, mipmap-generation,
renderer-unload, and protected worldgen/gameplay/physics-diff scans were run.
All reported zero matches or clean status. All four Phase 5B shader resources
declare `#version 410 core`. This source/scope audit is complemented by the
Windows live-driver acceptance and both owner approvals recorded above.

## Exact modified-file inventory relative to origin/main

The following tracked paths are the full current Phase 5B inventory. Ignored
`.superpowers/sdd` reports are intentionally excluded.

```text
docs/agent-handoffs/phase-05b-handoff.md
docs/architecture/current-baseline.md
docs/architecture/phase-05b-rendering-contract.md
docs/superpowers/plans/2026-07-26-phase-5b-rendering-visual-upgrade.md
docs/superpowers/specs/2026-07-26-phase-5b-rendering-visual-upgrade-design.md
engine/build.gradle
engine/src/main/java/com/overlord/Main.java
engine/src/main/java/com/overlord/core/Engine.java
engine/src/main/java/com/overlord/core/Window.java
engine/src/main/java/com/overlord/core/WindowMetrics.java
engine/src/main/java/com/overlord/renderer/FullscreenTriangle.java
engine/src/main/java/com/overlord/renderer/FullscreenTriangleBackend.java
engine/src/main/java/com/overlord/renderer/OpenGlFullscreenTriangleBackend.java
engine/src/main/java/com/overlord/renderer/RenderAssets.java
engine/src/main/java/com/overlord/renderer/RenderFrameInput.java
engine/src/main/java/com/overlord/renderer/RenderMetricsCollector.java
engine/src/main/java/com/overlord/renderer/RenderSurfaceController.java
engine/src/main/java/com/overlord/renderer/RenderSurfaceMetrics.java
engine/src/main/java/com/overlord/renderer/Renderer.java
engine/src/main/java/com/overlord/renderer/Texture.java
engine/src/main/java/com/overlord/renderer/frustum/Frustum.java
engine/src/main/java/com/overlord/renderer/frustum/FrustumPlane.java
engine/src/main/java/com/overlord/renderer/metrics/RenderMetrics.java
engine/src/main/java/com/overlord/renderer/metrics/RenderMetricsRecorder.java
engine/src/main/java/com/overlord/renderer/metrics/RenderMetricsSnapshot.java
engine/src/main/java/com/overlord/renderer/pass/RenderContext.java
engine/src/main/java/com/overlord/renderer/pass/SkyRenderPass.java
engine/src/main/java/com/overlord/renderer/pass/WorldRenderPass.java
engine/src/main/java/com/overlord/renderer/shader/OpenGlShaderBackend.java
engine/src/main/java/com/overlord/renderer/shader/ShaderBackend.java
engine/src/main/java/com/overlord/renderer/shader/ShaderBinding.java
engine/src/main/java/com/overlord/renderer/shader/ShaderProgram.java
engine/src/main/java/com/overlord/renderer/texture/OpenGlTextureBackend.java
engine/src/main/java/com/overlord/renderer/texture/TextureBackend.java
engine/src/main/java/com/overlord/renderer/texture/TextureBackends.java
engine/src/main/java/com/overlord/renderer/texture/TextureRegion.java
engine/src/main/java/com/overlord/renderer/visual/GammaPath.java
engine/src/main/java/com/overlord/renderer/visual/LinearColor.java
engine/src/main/java/com/overlord/renderer/visual/RenderVisualSettings.java
engine/src/main/java/com/overlord/voxel/ChunkDirtyTracker.java
engine/src/main/java/com/overlord/voxel/ChunkKey.java
engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java
engine/src/main/java/com/overlord/voxel/ChunkMeshInput.java
engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java
engine/src/main/java/com/overlord/voxel/ChunkRepository.java
engine/src/main/java/com/overlord/voxel/VoxelAmbientOcclusion.java
engine/src/main/resources/assets/overlord/shaders/sky.frag
engine/src/main/resources/assets/overlord/shaders/sky.vert
engine/src/main/resources/assets/overlord/shaders/world.frag
engine/src/main/resources/assets/overlord/shaders/world.vert
engine/src/test/java/com/overlord/core/WindowMetricsTest.java
engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java
engine/src/test/java/com/overlord/interaction/InteractionArchitectureTest.java
engine/src/test/java/com/overlord/renderer/ChunkRenderBackendTest.java
engine/src/test/java/com/overlord/renderer/FullscreenTriangleTest.java
engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java
engine/src/test/java/com/overlord/renderer/RenderFrameInputTest.java
engine/src/test/java/com/overlord/renderer/RenderPipelineArchitectureTest.java
engine/src/test/java/com/overlord/renderer/RenderSurfaceControllerTest.java
engine/src/test/java/com/overlord/renderer/RendererStructureTest.java
engine/src/test/java/com/overlord/renderer/frustum/FrustumTest.java
engine/src/test/java/com/overlord/renderer/material/MaterialTest.java
engine/src/test/java/com/overlord/renderer/metrics/RenderMetricsCollectorTest.java
engine/src/test/java/com/overlord/renderer/pass/RenderPipelineTest.java
engine/src/test/java/com/overlord/renderer/pass/WorldRenderPassTest.java
engine/src/test/java/com/overlord/renderer/queue/RenderQueueTest.java
engine/src/test/java/com/overlord/renderer/shader/ShaderProgramTest.java
engine/src/test/java/com/overlord/renderer/texture/TextureAtlasMetadataTest.java
engine/src/test/java/com/overlord/renderer/texture/TextureTest.java
engine/src/test/java/com/overlord/renderer/visual/RenderVisualSettingsTest.java
engine/src/test/java/com/overlord/voxel/ChunkDirtyTrackerTest.java
engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java
engine/src/test/java/com/overlord/voxel/ChunkMeshInputTest.java
engine/src/test/java/com/overlord/voxel/ChunkMeshLifecycleStructureTest.java
engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java
engine/src/test/java/com/overlord/voxel/ChunkRepositoryGenerationTransactionTest.java
engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java
engine/src/test/java/com/overlord/voxel/VoxelAmbientOcclusionTest.java
engine/src/test/java/com/overlord/voxel/VoxelVertexFormatTest.java
game/build.gradle
game/src/main/java/com/gaia/GameBootstrap.java
game/src/main/java/com/gaia/GameContext.java
game/src/main/java/com/gaia/GameLoop.java
game/src/main/java/com/gaia/RenderMetricsConsoleReporter.java
game/src/test/java/com/gaia/GameBootstrapStructureTest.java
game/src/test/java/com/gaia/GameLoopStructureTest.java
game/src/test/java/com/gaia/RenderArchitectureTest.java
game/src/test/java/com/gaia/RenderMetricsConsoleReporterTest.java
game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java
```

## Known risks and protected interfaces

Native macOS/Retina behavior and a controlled Phase 5A performance comparison
remain unverified. Source guards do not prove semantic absence of every
coupling. Keep Java 17, the Wrapper, OpenGL 4.1,
GLSL 410, engine-to-game direction, Phase 5A exact state restoration, the
40-byte vertex layout, asset/material identities, nine-snapshot meshing,
repository stale authority, manager GPU authority, deterministic world bytes,
physics, and gameplay mutation contracts intact.

## Current diff, suggested integration, and branch status

After staging, `git diff --cached --stat` reports `4 files changed, 493
insertions(+), 17 deletions(-)`; full-branch `git diff --stat origin/main`
reports `89 files changed, 7264 insertions(+), 378 deletions(-)`.
Suggested documentation commit:

```text
docs(rendering): record Phase 5B visual contracts
```

Suggested final squash message:

```text
feat(rendering): add voxel lighting fog culling and render metrics
```

Suggested pull request title: `feat(rendering): complete Milestone 1 visual
rendering upgrade`.

Suggested pull request description should summarize shader-linear lighting,
AO/diagonal snapshot safety, sky/fog/culling/surface/metrics behavior, fresh
893-test Windows automation, Windows development/installDist acceptance, both
owner approvals, and explicit macOS/comparable-Phase-5A limitations. Branch
status at handoff is **documentation commit only; no push, no pull request, and
no merge** for Task 13.
