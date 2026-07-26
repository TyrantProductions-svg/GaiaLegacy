# Phase 5B Rendering Visual Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add GLSL-410 voxel lighting, immutable 3x3-snapshot vertex AO, sky and fog with one gamma path, nearest/no-mipmap atlas stability, conservative Chunk frustum culling, immutable RenderMetrics, and complete render-surface propagation without adding UI or changing world data.

**Architecture:** Preserve the Phase 5A `sky -> world -> debug` pipeline, ten-float vertex contract, exact OpenGL state scopes, and Renderer-owned shared GPU resources. Extend Phase 3 snapshot/revision ownership to eight horizontal neighbors, perform all culling and metrics work on immutable CPU values, and keep every OpenGL/GLFW/GPU action on the captured main/context thread.

**Tech Stack:** Java 17, Gradle 8.5 Wrapper, JUnit 5, JOML, LWJGL OpenGL 4.1, GLSL 410, GLFW.

## Global Constraints

- Work only on `feat/rendering-visual-upgrade`, based on `origin/main` at `438859d722efb58349ada6d2100fc84f1556780c` plus the approved Phase 5B design commits.
- Do not push, create a pull request, merge, force-push, or modify `main`.
- Preserve Java 17 source compatibility and use only the checked-in Gradle Wrapper.
- Keep every OpenGL/GLFW/GPU create, upload, draw, resize, state, and destroy operation on the `MainThreadGuard` context-owning thread.
- Use only OpenGL 4.1 and GLSL `#version 410 core`; do not add compute shaders, SSBOs, or platform-specific APIs.
- `engine` must not depend on `game`; do not expand `ServiceLocator`.
- Do not change block definitions, world-generation bytes/configuration/hashes, physics, player behavior, gameplay mutations, inventory, world items, or UI.
- Preserve the Phase 5A 40-byte vertex layout, attribute locations, face IDs, `faceId * 16 + lightLevel` encoding, pass order, state restoration, queue cleanup, shader diagnostics, and material non-ownership.
- Preserve `ChunkRepository` as the only state/revision/dirty authority and `ChunkMeshManager` as the only CPU/GPU mesh lifecycle authority.
- Meshing workers may read only immutable `ChunkMeshInput`/`ChunkSnapshot` data and CPU render metadata; they must never read mutable `World` or invoke OpenGL.
- Default texture sampling is `GL_NEAREST`, level zero only, with no mipmap generation or sampling.
- Gamma is shader sRGB decode -> linear lighting/fog -> shader sRGB encode; production must explicitly disable and never enable `GL_FRAMEBUFFER_SRGB`.
- Use TDD for every production behavior: write the focused test, observe the intended RED, implement the minimum GREEN, rerun focused and affected regression suites, then commit.
- Never commit `build/`, `bin/`, `.class`, crash dumps, screenshots, IDE files, local logs, or `.superpowers/sdd` coordination files.

---

## File and responsibility map

### New Engine production values and services

- `engine/src/main/java/com/overlord/renderer/visual/LinearColor.java`: immutable finite linear RGB value.
- `engine/src/main/java/com/overlord/renderer/visual/GammaPath.java`: the single approved shader decode/encode policy.
- `engine/src/main/java/com/overlord/renderer/visual/RenderVisualSettings.java`: immutable lighting, sky, fog, and gamma settings.
- `engine/src/main/java/com/overlord/renderer/FullscreenTriangle.java`: main-thread-owned empty VAO for the sky draw.
- `engine/src/main/java/com/overlord/renderer/FullscreenTriangleBackend.java`: injectable empty-VAO draw/delete boundary.
- `engine/src/main/java/com/overlord/renderer/OpenGlFullscreenTriangleBackend.java`: guarded OpenGL 4.1 fullscreen backend.
- `engine/src/main/java/com/overlord/renderer/frustum/FrustumPlane.java`: normalized plane value and AABB outside test.
- `engine/src/main/java/com/overlord/renderer/frustum/Frustum.java`: six-plane extraction and conservative AABB visibility.
- `engine/src/main/java/com/overlord/renderer/metrics/RenderMetrics.java`: read-only metrics snapshot interface.
- `engine/src/main/java/com/overlord/renderer/metrics/RenderMetricsSnapshot.java`: immutable published frame values.
- `engine/src/main/java/com/overlord/renderer/metrics/RenderMetricsRecorder.java`: pass-facing draw counter boundary.
- `engine/src/main/java/com/overlord/renderer/metrics/RenderMetricsCollector.java`: Renderer-owned mutable per-frame accumulator.
- `engine/src/main/java/com/overlord/renderer/RenderFrameInput.java`: immutable chunks/delta/mesh-depth frame command.
- `engine/src/main/java/com/overlord/core/RenderSurfaceMetrics.java`: immutable logical/framebuffer/content-scale snapshot.
- `engine/src/main/java/com/overlord/renderer/texture/TextureBackend.java`: injectable texture upload/parameter boundary.
- `engine/src/main/java/com/overlord/renderer/texture/OpenGlTextureBackend.java`: guarded OpenGL 4.1 texture backend.
- `engine/src/main/java/com/overlord/voxel/VoxelAmbientOcclusion.java`: pure three-sample AO calculator.

### New Engine resources

- `engine/src/main/resources/assets/overlord/shaders/sky.vert`: fullscreen-triangle GLSL 410 vertex source.
- `engine/src/main/resources/assets/overlord/shaders/sky.frag`: linear gradient plus sRGB output source.

### New Game observer

- `game/src/main/java/com/gaia/rendering/RenderMetricsConsoleReporter.java`: explicitly enabled, once-per-second console snapshot observer.

### New focused tests

- `engine/src/test/java/com/overlord/renderer/visual/RenderVisualSettingsTest.java`
- `engine/src/test/java/com/overlord/renderer/FullscreenTriangleTest.java`
- `engine/src/test/java/com/overlord/renderer/frustum/FrustumTest.java`
- `engine/src/test/java/com/overlord/renderer/metrics/RenderMetricsCollectorTest.java`
- `engine/src/test/java/com/overlord/renderer/RenderFrameInputTest.java`
- `engine/src/test/java/com/overlord/renderer/texture/TextureTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshInputTest.java`
- `engine/src/test/java/com/overlord/voxel/VoxelAmbientOcclusionTest.java`
- `game/src/test/java/com/gaia/rendering/RenderMetricsConsoleReporterTest.java`

Existing tests named in each task are modified in place rather than creating parallel architecture-test suites.

---

### Task 0: Record the clean Phase 5A baseline and optional performance probe

**Files:**
- Read: `docs/agent-handoffs/phase-05a-handoff.md`
- Read: `docs/architecture/phase-05a-render-contract.md`
- Create ignored evidence only: `.superpowers/sdd/2026-07-26-phase-5b-rendering-visual-upgrade/baseline.md`
- Temporarily modify and then restore exactly: `game/src/main/java/com/gaia/GameLoop.java`

**Interfaces:**
- Consumes: Phase 5A `GameLoop`, `ChunkMeshManager.renderObjects()`, `FrameClock`.
- Produces: clean test count and optional same-seed Phase 5A FPS/draw-call evidence; no tracked production change.

- [ ] **Step 1: Verify the branch baseline**

Run:

```powershell
git status --short
git rev-parse HEAD
git merge-base --is-ancestor 438859d HEAD
.\gradlew.bat clean test build --console=plain --no-daemon
```

Expected: clean status; ancestor check exit `0`; build passes with at least the Phase 5A total of 823 tests.

- [ ] **Step 2: Record the structural Phase 5A draw baseline**

Document that Phase 5A submits every installed non-empty render object, performs one draw per submitted object, performs no sky geometry draw, and performs no frustum filtering. Record the loaded `renderObjects().size()` at the approved seed/position during the optional run.

- [ ] **Step 3: If interactive automation is available, add a temporary one-second probe**

Use `apply_patch` to add local counters around the Phase 5A render/swap path:

```java
long sampleStartNanos = System.nanoTime();
int sampledFrames = 0;
// after a successful render/swap:
sampledFrames++;
long sampleNanos = System.nanoTime() - sampleStartNanos;
if (sampleNanos >= 1_000_000_000L) {
    double fps = sampledFrames * 1_000_000_000.0 / sampleNanos;
    int drawCalls = state == State.RUNNING
            ? context.chunkMeshes().renderObjects().size()
            : 0;
    System.out.printf(
            java.util.Locale.ROOT,
            "PHASE5A fps=%.2f drawCalls=%d%n",
            fps,
            drawCalls);
    sampleStartNanos = System.nanoTime();
    sampledFrames = 0;
}
```

Run the same seed, position, orientation, window size, and display scale intended for final Phase 5B comparison. Capture the log outside tracked paths.

- [ ] **Step 4: Remove the temporary probe with an inverse `apply_patch`**

Run:

```powershell
git diff -- game/src/main/java/com/gaia/GameLoop.java
git status --short
```

Expected: no `GameLoop` diff and clean tracked status. Do not commit the probe. If GUI automation is unavailable, record `PHASE 5A FPS NOT CAPTURED` rather than inventing a result.

---

### Task 1: Add immutable visual settings and scalar/vector shader uniforms

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/visual/LinearColor.java`
- Create: `engine/src/main/java/com/overlord/renderer/visual/GammaPath.java`
- Create: `engine/src/main/java/com/overlord/renderer/visual/RenderVisualSettings.java`
- Modify: `engine/src/main/java/com/overlord/renderer/shader/ShaderBinding.java`
- Modify: `engine/src/main/java/com/overlord/renderer/shader/ShaderBackend.java`
- Modify: `engine/src/main/java/com/overlord/renderer/shader/OpenGlShaderBackend.java`
- Modify: `engine/src/main/java/com/overlord/renderer/shader/ShaderProgram.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/main/java/com/overlord/core/Engine.java`
- Create: `engine/src/test/java/com/overlord/renderer/visual/RenderVisualSettingsTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/shader/ShaderProgramTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java`

**Interfaces:**
- Consumes: Phase 5A cached required-uniform locations and `MainThreadGuard`.
- Produces: `LinearColor`, `GammaPath.SHADER_SRGB_DECODE_ENCODE`, `RenderVisualSettings.milestoneOneDefaults()`, `ShaderBinding.setFloat`, and `ShaderBinding.setVector3`.

- [ ] **Step 1: Write failing settings validation and immutability tests**

Test exact defaults and failures:

```java
@Test
void milestoneDefaultsUseApprovedLightingFogAndGamma() {
    RenderVisualSettings settings =
            RenderVisualSettings.milestoneOneDefaults();

    assertEquals(0.38f, settings.ambientStrength());
    assertEquals(0.72f, settings.directionalStrength());
    assertEquals(64.0f, settings.fogStart());
    assertEquals(160.0f, settings.fogEnd());
    assertEquals(
            GammaPath.SHADER_SRGB_DECODE_ENCODE,
            settings.gammaPath());
    assertEquals(1.0f, settings.sunDirection().length(), 1.0e-6f);
    assertEquals(new LinearColor(0.035f, 0.160f, 0.470f), settings.skyTop());
    assertEquals(new LinearColor(0.350f, 0.570f, 0.780f), settings.skyHorizon());
    assertEquals(settings.skyHorizon(), settings.fogColor());
}
```

Also reject NaN/infinity, zero sun vector, negative strengths, colors outside `[0,1]`, and `fogEnd <= fogStart`. Assert that mutating a returned `Vector3f` does not mutate the settings.

- [ ] **Step 2: Write failing shader upload tests**

Extend the fake backend to record:

```java
shader.setFloat("fogStart", 64.0f);
shader.setVector3("sunDirection", new Vector3f(-0.45f, 0.85f, -0.30f));
```

Assert main-thread enforcement, cached-location use, finite values, and backend calls `uploadFloat(location, value)` and `uploadVector3(location, x, y, z)`.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.visual.RenderVisualSettingsTest `
  --tests com.overlord.renderer.shader.ShaderProgramTest `
  --console=plain --no-daemon
```

Expected: compilation/test failure because the new values and methods do not exist.

- [ ] **Step 4: Implement the immutable values**

Use this public shape:

```java
public record LinearColor(float red, float green, float blue) {}

public enum GammaPath {
    SHADER_SRGB_DECODE_ENCODE
}

public final class RenderVisualSettings {
    public static RenderVisualSettings milestoneOneDefaults();
    public Vector3f sunDirection();
    public float ambientStrength();
    public float directionalStrength();
    public LinearColor skyTop();
    public LinearColor skyHorizon();
    public LinearColor fogColor();
    public float fogStart();
    public float fogEnd();
    public GammaPath gammaPath();
}
```

Normalize a defensive copy of the sun vector in the constructor and return a new vector from the accessor.

- [ ] **Step 5: Implement minimal shader uniform support**

Add:

```java
void setFloat(String uniform, float value);
void setVector3(String uniform, Vector3fc value);
```

and backend methods:

```java
void uploadFloat(int location, float value);
void uploadVector3(int location, float x, float y, float z);
```

Use `glUniform1f` and `glUniform3f` in `OpenGlShaderBackend`. Validate finite inputs in `ShaderProgram`, preserve cached-location diagnostics, and retain all Phase 5A cleanup behavior.

- [ ] **Step 6: Thread settings through constructor injection**

Add a `RenderVisualSettings` field to `Engine` and `Renderer`. Add exact full constructors:

```java
public Engine(
        MainThreadGuard guard,
        RenderAssets assets,
        AssetManager assetManager,
        RenderVisualSettings visualSettings)

public Renderer(
        MainThreadGuard guard,
        RenderAssets assets,
        AssetManager assetManager,
        RenderVisualSettings visualSettings)
```

Existing shorter constructors delegate to `RenderVisualSettings.milestoneOneDefaults()` for compatibility. `Engine.init()` passes its injected instance to Renderer. Do not register settings in `ServiceLocator`.

- [ ] **Step 7: Run GREEN and regressions**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.visual.RenderVisualSettingsTest `
  --tests com.overlord.renderer.shader.ShaderProgramTest `
  --tests com.overlord.renderer.RenderPipelineArchitectureTest `
  --tests com.overlord.renderer.RenderAssetsTest `
  --console=plain --no-daemon
git diff --check
```

- [ ] **Step 8: Commit Task 1**

```powershell
git add engine/src/main/java/com/overlord/renderer/visual `
  engine/src/main/java/com/overlord/renderer/shader `
  engine/src/main/java/com/overlord/renderer/Renderer.java `
  engine/src/main/java/com/overlord/core/Engine.java `
  engine/src/test/java/com/overlord/renderer/visual `
  engine/src/test/java/com/overlord/renderer/shader/ShaderProgramTest.java `
  engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java
git commit -m "feat(rendering): add immutable visual settings"
```

---

### Task 2: Migrate ChunkMeshInput to an immutable 3x3 neighborhood

**Files:**
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshInput.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java:485-558`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkMeshInputTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`

**Interfaces:**
- Consumes: immutable `ChunkSnapshot`, `ChunkKey` cardinal/diagonal key arithmetic, repository claim revision.
- Produces: nine-snapshot `ChunkMeshInput` and one-block-halo `getBlock` routing.

- [ ] **Step 1: Write failing nine-snapshot construction tests**

Construct center `(0,0)` with all eight neighbors and assert exact accessors:

```java
assertEquals(new ChunkKey(-1, -1), input.northWest().key());
assertEquals(new ChunkKey(0, -1), input.north().key());
assertEquals(new ChunkKey(1, -1), input.northEast().key());
assertEquals(new ChunkKey(-1, 0), input.west().key());
assertEquals(new ChunkKey(1, 0), input.east().key());
assertEquals(new ChunkKey(-1, 1), input.southWest().key());
assertEquals(new ChunkKey(0, 1), input.south().key());
assertEquals(new ChunkKey(1, 1), input.southEast().key());
```

Reject wrong keys and world heights in each position. Pass null for each neighbor and assert a correctly keyed empty snapshot.

- [ ] **Step 2: Write failing halo-routing tests**

Place distinct marker bytes at all cardinal and diagonal boundary cells. Assert:

```java
assertEquals(NORTH_WEST_MARKER, input.getBlock(-1, y, -1));
assertEquals(NORTH_EAST_MARKER, input.getBlock(CHUNK_SIZE, y, -1));
assertEquals(SOUTH_WEST_MARKER, input.getBlock(-1, y, CHUNK_SIZE));
assertEquals(SOUTH_EAST_MARKER, input.getBlock(CHUNK_SIZE, y, CHUNK_SIZE));
```

Repeat with a negative center key. Reject `-2` and `CHUNK_SIZE + 1` horizontal coordinates. Preserve vertical-out-of-range air.

- [ ] **Step 3: Write failing repository-capture test**

Generate a center and all eight neighbors, claim meshing, and assert every snapshot contains its marker. Assert the production input has no `World`, `Chunk`, or `ChunkRepository` field.

- [ ] **Step 4: Run RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.ChunkMeshInputTest `
  --tests com.overlord.voxel.ChunkRepositoryTest `
  --tests com.overlord.voxel.ChunkMeshBuilderTest `
  --tests com.overlord.voxel.ChunkMeshManagerTest `
  --console=plain --no-daemon
```

- [ ] **Step 5: Implement the fixed nine-snapshot record**

Use this field order:

```java
public record ChunkMeshInput(
        ChunkSnapshot center,
        ChunkSnapshot north,
        ChunkSnapshot northEast,
        ChunkSnapshot east,
        ChunkSnapshot southEast,
        ChunkSnapshot south,
        ChunkSnapshot southWest,
        ChunkSnapshot west,
        ChunkSnapshot northWest) {}
```

Route by horizontal offset pair `(-1|0|1, -1|0|1)` and convert boundary local coordinates with `floorMod`. Do not add a five-argument compatibility constructor.

- [ ] **Step 6: Capture all eight neighbors in claimMeshing**

Use `snapshot(key...)` with correctly keyed empty fallbacks, retain the existing center claim/recheck, and return the nine-snapshot input only while the center state/revision still matches.

- [ ] **Step 7: Migrate existing test fixtures and run GREEN**

Centralize test creation in a helper that supplies empty diagonals. Then run the Task 2 RED command plus:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.ChunkMeshLifecycleStructureTest `
  --console=plain --no-daemon
git diff --check
```

- [ ] **Step 8: Commit Task 2**

```powershell
git add engine/src/main/java/com/overlord/voxel/ChunkMeshInput.java `
  engine/src/main/java/com/overlord/voxel/ChunkRepository.java `
  engine/src/test/java/com/overlord/voxel
git commit -m "refactor(voxel): capture immutable 3x3 mesh neighborhoods"
```

---

### Task 3: Extend repository invalidation and stale rejection to diagonals

**Files:**
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkKey.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkDirtyTracker.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkDirtyTrackerTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryGenerationTransactionTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`
- Modify: `engine/src/test/java/com/overlord/interaction/InteractionArchitectureTest.java`

**Interfaces:**
- Consumes: Task 2 nine-snapshot input, repository-issued revisions, `ChunkMutationOutcome`.
- Produces: eight-neighbor meshing invalidation with exact cardinal/diagonal dirty revisions.

- [ ] **Step 1: Write failing dirty-tracker corner tests**

For every corner assert target, two cardinals, and exactly one diagonal. For example:

```java
assertEquals(
        Set.of(center, center.north(), center.west(), new ChunkKey(-1, -1)),
        tracker.affectedByBlock(center, 0, 0));
```

Assert an edge non-corner has no diagonal and an interior has only the target. Add `meshingNeighbors(center)` asserting exactly eight keys; preserve `horizontalNeighbors(center)` as the four-cardinal API if existing consumers/tests still require it.

- [ ] **Step 2: Write failing mutation outcome tests**

Load the target, both cardinal neighbors, and diagonal. Mutate a corner through `compareAndSetBlock` and assert four ordered `DirtyChunkRevision` entries with fresh, distinct repository revisions. Repeat with the diagonal absent and assert no entry allocation and no reported diagonal.

- [ ] **Step 3: Write failing generation/rebuild/unload tests**

Cover:

- initial generation dirties all eight loaded meshing neighbors;
- unload dirties all eight loaded meshing neighbors;
- rebuild with an interior-only change dirties no neighbor;
- rebuild with a north-edge non-corner change dirties north only;
- rebuild with a north-west corner-column change dirties north, west, and north-west;
- unchanged corner columns do not dirty diagonals.

- [ ] **Step 4: Write failing stale-result races**

Claim a target mesh, mutate or unload its diagonal neighbor before CPU completion, and assert the target revision advances and the old completion cannot become `READY_FOR_UPLOAD` or replace the installed object.

- [ ] **Step 5: Run RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.ChunkDirtyTrackerTest `
  --tests com.overlord.voxel.ChunkRepositoryTest `
  --tests com.overlord.voxel.ChunkRepositoryGenerationTransactionTest `
  --tests com.overlord.voxel.ChunkMeshManagerTest `
  --tests com.overlord.interaction.InteractionArchitectureTest `
  --console=plain --no-daemon
```

- [ ] **Step 6: Implement one authoritative invalidation expansion**

Add these exact deterministic helpers to `ChunkKey`:

```java
public ChunkKey northWest() { return new ChunkKey(x - 1, z - 1); }
public ChunkKey northEast() { return new ChunkKey(x + 1, z - 1); }
public ChunkKey southWest() { return new ChunkKey(x - 1, z + 1); }
public ChunkKey southEast() { return new ChunkKey(x + 1, z + 1); }
```

Extend `affectedByBlock`; use `meshingNeighbors` for initial generation and unload. Replace `changedHorizontalEdges` with a result that reports four cardinal edge changes and four corner-column changes without scanning twice.

Do not publish a second dirty event, tracker, or revision sequence. Keep `ChunkMutationOutcome` as the Phase 7 truth source.

- [ ] **Step 7: Run GREEN and Phase 7 regressions**

Run the RED command, then:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.interaction.DefaultWorldMutationServiceTest `
  --tests com.overlord.interaction.InteractionArchitectureTest `
  --console=plain --no-daemon
git diff --check
```

- [ ] **Step 8: Commit Task 3**

```powershell
git add engine/src/main/java/com/overlord/voxel `
  engine/src/test/java/com/overlord/voxel `
  engine/src/test/java/com/overlord/interaction/InteractionArchitectureTest.java
git commit -m "refactor(voxel): invalidate diagonal AO neighbors"
```

---

### Task 4: Compute four-level three-sample vertex AO

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/VoxelAmbientOcclusion.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java`
- Create: `engine/src/test/java/com/overlord/voxel/VoxelAmbientOcclusionTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/VoxelVertexFormatTest.java`

**Interfaces:**
- Consumes: Task 2 one-block 3x3 halo, `BlockRenderResolver`, existing face normals and AO float attribute.
- Produces: per-vertex AO multipliers `0.45`, `0.65`, `0.82`, `1.0` without changing vertex stride or winding.

- [ ] **Step 1: Write failing pure AO-level tests**

Use:

```java
float sample(
        ChunkMeshInput input,
        BlockRenderResolver resolver,
        int blockX,
        int blockY,
        int blockZ,
        BlockFace face,
        int tangentSignA,
        int tangentSignB)
```

Require each tangent sign to be `-1` or `1`. Assert:

```text
no occluders -> 1.00
corner only -> 0.82
one side + corner -> 0.65
both sides -> 0.45 regardless of corner
```

Assert air and transparent material do not occlude; renderable OPAQUE and CUTOUT do.

- [ ] **Step 2: Write failing orientation and diagonal-boundary tests**

Cover NORTH/SOUTH tangent axes X/Y, UP/DOWN axes X/Z, and WEST/EAST axes Z/Y. Put the corner sample in each of the four diagonal Chunk snapshots and assert the correct vertex darkens for positive and negative center keys.

- [ ] **Step 3: Write failing mesh-layout tests**

Build a single exposed cube. Assert every vertex remains ten floats, position/UV/normal/face-light offsets are unchanged, AO appears only at float offset 9, vertex count remains 36, and the existing triangle vertex positions/winding remain byte-for-byte equal except AO values.

- [ ] **Step 4: Run RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.VoxelAmbientOcclusionTest `
  --tests com.overlord.voxel.ChunkMeshBuilderTest `
  --tests com.overlord.voxel.VoxelVertexFormatTest `
  --console=plain --no-daemon
```

- [ ] **Step 5: Implement face-basis AO sampling**

Use exact tangent bases:

```text
NORTH/SOUTH: A=(1,0,0), B=(0,1,0)
UP/DOWN:     A=(1,0,0), B=(0,0,1)
WEST/EAST:   A=(0,0,1), B=(0,1,0)
```

Sample `normal + signA*tangentA`, `normal + signB*tangentB`, and their combined corner. Occlusion is `renderable && material.renderType() != TRANSPARENT`.

- [ ] **Step 6: Feed AO into existing face vertex order**

Calculate the four logical corner values once per face and pass the matching value to each duplicated triangle vertex. Keep the existing face geometry and UV order unchanged. Continue emitting light level 15.

- [ ] **Step 7: Run GREEN and lifecycle regression**

Run the RED command plus:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.ChunkMeshDataTest `
  --tests com.overlord.voxel.ChunkMeshLifecycleStructureTest `
  --console=plain --no-daemon
git diff --check
```

- [ ] **Step 8: Commit Task 4**

```powershell
git add engine/src/main/java/com/overlord/voxel `
  engine/src/test/java/com/overlord/voxel
git commit -m "feat(rendering): add snapshot-based voxel ambient occlusion"
```

---

### Task 5: Enforce nearest level-zero texture sampling and half-texel UVs

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/texture/TextureBackend.java`
- Create: `engine/src/main/java/com/overlord/renderer/texture/OpenGlTextureBackend.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Texture.java`
- Modify: `engine/src/main/java/com/overlord/renderer/texture/TextureRegion.java`
- Create: `engine/src/test/java/com/overlord/renderer/texture/TextureTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/texture/TextureAtlasMetadataTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`
- Modify: `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`

**Interfaces:**
- Consumes: Phase 5A Renderer texture ownership and current atlas pixels/metadata.
- Produces: testable OpenGL texture calls, no mipmap surface, and inset UV accessors.

- [ ] **Step 1: Write failing fake-backend parameter tests**

Create a package-visible Texture constructor that accepts `TextureBackend`. Assert exactly:

```text
MIN_FILTER = GL_NEAREST
MAG_FILTER = GL_NEAREST
WRAP_S = GL_CLAMP_TO_EDGE
WRAP_T = GL_CLAMP_TO_EDGE
BASE_LEVEL = 0
MAX_LEVEL = 0
one level-0 GL_RGBA8 upload
no mipmap operation exists or occurs
```

Assert partial upload failure deletes the generated texture once and preserves the primary failure.

- [ ] **Step 2: Write failing half-texel tests**

For `TextureRegion(id, 16, 0, 16, 16, 128, 64)` assert:

```java
assertEquals(16.5f / 128.0f, region.uMin());
assertEquals(31.5f / 128.0f, region.uMax());
assertEquals(0.5f / 64.0f, region.vMin());
assertEquals(15.5f / 64.0f, region.vMax());
```

For a 1x1 region assert min equals max at the pixel center. Cover the last atlas pixel and preserve bounds validation.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.texture.TextureTest `
  --tests com.overlord.renderer.texture.TextureAtlasMetadataTest `
  --tests com.overlord.voxel.ChunkMeshBuilderTest `
  --tests com.overlord.core.thread.MainThreadGuardTest `
  --console=plain --no-daemon
```

- [ ] **Step 4: Implement the backend and no-mipmap upload**

Use this package-visible backend shape:

```java
interface TextureBackend {
    int createTexture();
    void activateTextureUnit(int textureUnit);
    void bindTexture2d(int textureId);
    void setTextureParameter(int parameterName, int value);
    void uploadRgba8(int width, int height, ByteBuffer pixels);
    void deleteTexture(int textureId);
}
```

It deliberately exposes no mipmap method. `OpenGlTextureBackend` maps these methods to OpenGL 4.1-compatible calls and uploads level zero with internal format `GL_RGBA8`, external format `GL_RGBA`, and type `GL_UNSIGNED_BYTE`. Texture retains `MainThreadGuard` assertions for construction, bind, and cleanup.

- [ ] **Step 5: Implement inset UVs and migrate expected fixtures**

Use the exact approved formulas. Do not alter atlas JSON or PNG. Update only tests whose expected UVs intentionally change.

- [ ] **Step 6: Run GREEN and packaged-resource regression**

Run the RED command plus:

```powershell
.\gradlew.bat :game:verifyPackagedResources `
  --rerun-tasks --console=plain --no-daemon
git diff --check
```

- [ ] **Step 7: Commit Task 5**

```powershell
git add engine/src/main/java/com/overlord/renderer/Texture.java `
  engine/src/main/java/com/overlord/renderer/texture `
  engine/src/test/java/com/overlord/renderer/texture `
  engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java `
  engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java
git commit -m "fix(rendering): use stable nearest atlas sampling"
```

---

### Task 6: Add linear-space world lighting, fog, and the single gamma path

**Files:**
- Modify: `engine/src/main/resources/assets/overlord/shaders/world.vert`
- Modify: `engine/src/main/resources/assets/overlord/shaders/world.frag`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/RenderContext.java`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/WorldRenderPass.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/test/java/com/overlord/renderer/pass/WorldRenderPassTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RenderPipelineArchitectureTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RendererStructureTest.java`

**Interfaces:**
- Consumes: Task 1 settings/uniform API, Task 4 AO attribute, Task 5 linear atlas upload.
- Produces: GLSL 410 world visual path with exactly one manual decode/encode gamma route.

- [ ] **Step 1: Write failing shader-contract tests**

Load shader resources through `AssetManager` and assert:

- exact `#version 410 core`;
- vertex inputs remain locations 0..4;
- vertex shader consumes `aNormal`, `aFaceLight`, and `aAmbientOcclusion`;
- fragment shader declares standard piecewise `srgbToLinear` and `linearToSrgb` functions;
- sampling precedes decode, lighting/AO precede fog, and encode is the final RGB operation;
- no shader performs a second `pow` after output encoding;
- production source contains `glDisable(GL_FRAMEBUFFER_SRGB)` and no `glEnable(GL_FRAMEBUFFER_SRGB)`.

- [ ] **Step 2: Write failing WorldRenderPass uniform tests**

Require exact uniforms:

```text
projection, view, model, textureAtlas,
sunDirection, ambientStrength, directionalStrength,
fogColor, fogStart, fogEnd
```

Use a fake binding to assert every value comes from `RenderContext.visualSettings()` and each successful mesh draw remains once.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.pass.WorldRenderPassTest `
  --tests com.overlord.renderer.RenderPipelineArchitectureTest `
  --tests com.overlord.renderer.RendererStructureTest `
  --console=plain --no-daemon
```

- [ ] **Step 4: Implement the world shaders**

Decode light level as:

```glsl
float vertexLight = mod(aFaceLight, 16.0) / 15.0;
```

Use the standard thresholds `0.04045` for decode and `0.0031308` for encode. Clamp the combined light before applying it. Compute view distance from the transformed view-space position. Preserve alpha.

- [ ] **Step 5: Extend RenderContext and WorldRenderPass**

Add a defensive `RenderVisualSettings` reference to `RenderContext`. Upload the settings through the Task 1 API before draw. Update Renderer required-uniform lists and explicitly call:

```java
glDisable(GL_FRAMEBUFFER_SRGB);
```

on the guarded initialization path.

- [ ] **Step 6: Run GREEN and shader packaging check**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.pass.WorldRenderPassTest `
  --tests com.overlord.renderer.RenderPipelineArchitectureTest `
  --tests com.overlord.renderer.RendererStructureTest `
  --tests com.overlord.renderer.shader.ShaderResourceLoaderTest `
  --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources `
  --rerun-tasks --console=plain --no-daemon
git diff --check
```

- [ ] **Step 7: Commit Task 6**

```powershell
git add engine/src/main/resources/assets/overlord/shaders/world.* `
  engine/src/main/java/com/overlord/renderer/Renderer.java `
  engine/src/main/java/com/overlord/renderer/pass `
  engine/src/test/java/com/overlord/renderer
git commit -m "feat(rendering): add linear voxel lighting and fog"
```

---

### Task 7: Render the sky gradient with a guarded fullscreen triangle

**Files:**
- Create: `engine/src/main/resources/assets/overlord/shaders/sky.vert`
- Create: `engine/src/main/resources/assets/overlord/shaders/sky.frag`
- Create: `engine/src/main/java/com/overlord/renderer/FullscreenTriangle.java`
- Create: `engine/src/main/java/com/overlord/renderer/FullscreenTriangleBackend.java`
- Create: `engine/src/main/java/com/overlord/renderer/OpenGlFullscreenTriangleBackend.java`
- Modify: `engine/src/main/java/com/overlord/renderer/RenderAssets.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/SkyRenderPass.java`
- Create: `engine/src/test/java/com/overlord/renderer/FullscreenTriangleTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/pass/RenderPipelineTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RenderPipelineArchitectureTest.java`

**Interfaces:**
- Consumes: Task 1 settings/uniform API and Phase 5A state scope.
- Produces: engine-owned sky resources, empty-VAO geometry, and a real gradient in the existing sky pass.

- [ ] **Step 1: Write failing sky resource tests**

Assert both resources load through `AssetManager`, use GLSL 410, vertex source derives positions from `gl_VertexID`, fragment source interpolates `skyHorizon`/`skyTop`, and uses the same piecewise linear-to-sRGB function as world output.

- [ ] **Step 2: Write failing fullscreen geometry tests**

Use an injectable geometry backend to assert:

- construction asserts `MainThreadGuard`;
- one VAO is generated and no VBO is generated;
- draw binds the VAO and calls exactly `GL_TRIANGLES, 0, 3`;
- cleanup deletes the VAO exactly once;
- partial creation and same-instance cleanup failures preserve the primary failure.

Use this package-private backend shape:

```java
interface FullscreenTriangleBackend {
    int createVertexArray();
    void bindVertexArray(int vertexArrayId);
    void drawTriangles(int firstVertex, int vertexCount);
    void deleteVertexArray(int vertexArrayId);
}
```

`OpenGlFullscreenTriangleBackend` maps these calls to `glGenVertexArrays`, `glBindVertexArray`, `glDrawArrays(GL_TRIANGLES, first, count)`, and `glDeleteVertexArrays`.

- [ ] **Step 3: Write failing SkyRenderPass tests**

Assert state `depthTest=false`, `depthWrite=false`, `blend=disabled`, `cull=false`; clear happens before shader use; top/horizon uniforms are uploaded; geometry draws once; scope restores after success/failure.

- [ ] **Step 4: Run RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.FullscreenTriangleTest `
  --tests com.overlord.renderer.RenderAssetsTest `
  --tests com.overlord.renderer.pass.RenderPipelineTest `
  --tests com.overlord.renderer.RenderPipelineArchitectureTest `
  --console=plain --no-daemon
```

- [ ] **Step 5: Implement resources, geometry, and pass**

Add default identities:

```java
ResourceLocation.parse("overlord:shaders/sky.vert")
ResourceLocation.parse("overlord:shaders/sky.frag")
```

Renderer owns world program, sky program, texture, and fullscreen triangle; normal and failed initialization cleanup release in reverse creation order and suppress only distinct later failures.

- [ ] **Step 6: Run GREEN and lifecycle regressions**

Run the RED command plus:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.RendererStructureTest `
  --tests com.overlord.renderer.state.RenderStateScopeTest `
  --tests com.overlord.core.thread.MainThreadGuardTest `
  --console=plain --no-daemon
git diff --check
```

- [ ] **Step 7: Commit Task 7**

```powershell
git add engine/src/main/resources/assets/overlord/shaders/sky.* `
  engine/src/main/java/com/overlord/renderer `
  engine/src/test/java/com/overlord/renderer
git commit -m "feat(rendering): add gradient sky pass"
```

---

### Task 8: Add conservative Chunk AABB frustum culling

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/frustum/FrustumPlane.java`
- Create: `engine/src/main/java/com/overlord/renderer/frustum/Frustum.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Create: `engine/src/test/java/com/overlord/renderer/frustum/FrustumTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RendererStructureTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RenderPipelineArchitectureTest.java`

**Interfaces:**
- Consumes: current projection/view matrices and `ChunkRenderObject.worldBounds()`.
- Produces: immutable six-plane frustum and queue-only visibility filtering.

- [ ] **Step 1: Write failing normalized-plane tests**

Assert finite normalization and field-specific rejection for zero/non-finite normals. Test signed distance to known points.

- [ ] **Step 2: Write failing frustum visibility tests**

Build a perspective/view matrix and assert AABBs fully inside, outside each of six planes, intersecting, touching, and `0.005` inside the approved `0.01` epsilon. Assert an AABB farther than epsilon outside is rejected.

- [ ] **Step 3: Write failing camera-rotation and ownership tests**

Use pure `Frustum` values to show front/back Chunk bounds exchange visibility after a 180-degree view rotation. Structure-test Renderer to filter only before `RenderQueue.submit` and prohibit `unload`, repository mutation, or installed-map edits in the culling path.

- [ ] **Step 4: Run RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.frustum.FrustumTest `
  --tests com.overlord.renderer.RendererStructureTest `
  --tests com.overlord.renderer.RenderPipelineArchitectureTest `
  --console=plain --no-daemon
```

- [ ] **Step 5: Implement matrix extraction and AABB positive-vertex test**

Extract left/right/bottom/top/near/far from `projection * view`, normalize each plane, and retain epsilon `0.01f`. Keep the math in pure CPU classes with no renderer or OpenGL dependency.

- [ ] **Step 6: Filter only current-frame submissions**

Renderer creates one current frustum, null-validates each input Chunk, and submits only intersecting `worldBounds`. It does not remove or mutate input objects.

- [ ] **Step 7: Run GREEN and Chunk lifecycle regressions**

Run the RED command plus:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.ChunkRenderObjectTest `
  --tests com.overlord.voxel.ChunkMeshManagerTest `
  --console=plain --no-daemon
git diff --check
```

- [ ] **Step 8: Commit Task 8**

```powershell
git add engine/src/main/java/com/overlord/renderer/frustum `
  engine/src/main/java/com/overlord/renderer/Renderer.java `
  engine/src/test/java/com/overlord/renderer
git commit -m "feat(rendering): cull chunks against the camera frustum"
```

---

### Task 9: Publish immutable per-frame RenderMetrics

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/metrics/RenderMetrics.java`
- Create: `engine/src/main/java/com/overlord/renderer/metrics/RenderMetricsSnapshot.java`
- Create: `engine/src/main/java/com/overlord/renderer/metrics/RenderMetricsRecorder.java`
- Create: `engine/src/main/java/com/overlord/renderer/metrics/RenderMetricsCollector.java`
- Create: `engine/src/main/java/com/overlord/renderer/RenderFrameInput.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/RenderContext.java`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/SkyRenderPass.java`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/WorldRenderPass.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java`
- Modify: `engine/src/main/java/com/overlord/Main.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Create: `engine/src/test/java/com/overlord/renderer/metrics/RenderMetricsCollectorTest.java`
- Create: `engine/src/test/java/com/overlord/renderer/RenderFrameInputTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/pass/WorldRenderPassTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/pass/RenderPipelineTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`
- Modify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`

**Interfaces:**
- Consumes: Task 7 sky draw, Task 8 visible set, `ChunkGpuMesh.vertexCount()`, GameLoop frame delta.
- Produces: one `renderFrame(RenderFrameInput)` API and immutable last-frame metrics.

- [ ] **Step 1: Write failing value and frame-input tests**

Use exact public shapes:

```java
public interface RenderMetrics {
    RenderMetricsSnapshot snapshot();
}

public record RenderMetricsSnapshot(
        double framesPerSecond,
        double frameTimeMilliseconds,
        int visibleChunks,
        int drawCalls,
        long triangles,
        int meshQueueDepth) {}

public record RenderFrameInput(
        List<ChunkRenderObject> chunks,
        double frameDeltaSeconds,
        int meshQueueDepth) {}
```

Reject non-finite/negative values and null chunks; defensively copy the list.

- [ ] **Step 2: Write failing collector reset/exception tests**

Assert:

- zero delta publishes FPS/time zero;
- `0.02` seconds publishes `50 FPS` and `20 ms`;
- `beginFrame` resets visible/draw/triangles;
- successful sky draw records `1/1`;
- successful 36-vertex Chunk draw adds `1/12`;
- a failed draw is not counted;
- `finishFrame` in `finally` publishes completed work and mesh depth;
- a returned old snapshot never changes after later frames.

- [ ] **Step 3: Write failing mesh queue-depth transition tests**

Cover accepted in-flight CPU work, completed waiting drain, awaiting upload, failed upload retained for retry, stale result discard, unload discard, successful install, and close. Assert every count is non-negative and observational reads do not alter state.

- [ ] **Step 4: Run RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.metrics.RenderMetricsCollectorTest `
  --tests com.overlord.renderer.RenderFrameInputTest `
  --tests com.overlord.voxel.ChunkMeshManagerTest `
  --tests com.overlord.renderer.pass.RenderPipelineTest `
  --tests com.overlord.renderer.pass.WorldRenderPassTest `
  --console=plain --no-daemon
```

- [ ] **Step 5: Implement collector and pass recorder**

Use this pass-facing boundary:

```java
public interface RenderMetricsRecorder {
    void recordDraw(long triangles);
}
```

The mutable collector is Renderer-owned and package-internal and implements:

```java
void beginFrame(double frameDeltaSeconds, int meshQueueDepth);
void setVisibleChunks(int visibleChunks);
void recordDraw(long triangles);
void finishFrame();
RenderMetricsSnapshot snapshot();
```

`RenderContext` exposes only `RenderMetricsRecorder`; passes call `recordDraw(triangles)` only after the draw returns successfully. Renderer calls `beginFrame`, sets visible count, and calls `finishFrame` in `finally`.

- [ ] **Step 6: Implement meshQueueDepth**

Track accepted CPU claims until they produce a completion/failure handoff, and sum them with completed, awaiting-upload, and retained failed-upload work in a main-thread read. Prevent double decrement on rejection, close, stale completion, and unload.

- [ ] **Step 7: Migrate every renderFrame caller**

GameLoop calls:

```java
renderer.renderFrame(
        new RenderFrameInput(
                List.copyOf(renderObjects),
                frameDeltaSeconds,
                context.chunkMeshes().meshQueueDepth()));
```

Engine `Main` creates a `FrameClock(System::nanoTime, 0.25)` and passes `List.of()` plus queue depth zero. Remove the collection-only overload after all callers/tests migrate.

- [ ] **Step 8: Run GREEN and full affected tests**

Run the RED command plus:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.RendererStructureTest `
  --tests com.overlord.renderer.ChunkRenderBackendTest `
  --tests com.overlord.core.thread.MainThreadGuardTest `
  --console=plain --no-daemon
.\gradlew.bat :game:test `
  --tests com.gaia.GameLoopStructureTest `
  --console=plain --no-daemon
git diff --check
```

- [ ] **Step 9: Commit Task 9**

```powershell
git add engine/src/main/java/com/overlord/renderer `
  engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java `
  engine/src/main/java/com/overlord/Main.java `
  game/src/main/java/com/gaia/GameLoop.java `
  engine/src/test/java/com/overlord `
  game/src/test/java/com/gaia/GameLoopStructureTest.java
git commit -m "feat(rendering): expose immutable per-frame render metrics"
```

---

### Task 10: Coalesce logical, framebuffer, and content-scale metrics

**Files:**
- Create: `engine/src/main/java/com/overlord/core/RenderSurfaceMetrics.java`
- Modify: `engine/src/main/java/com/overlord/core/WindowMetrics.java`
- Modify: `engine/src/main/java/com/overlord/core/Window.java`
- Modify: `engine/src/main/java/com/overlord/core/Engine.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/main/java/com/overlord/Main.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `engine/src/test/java/com/overlord/core/WindowMetricsTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RendererStructureTest.java`
- Modify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`

**Interfaces:**
- Consumes: GLFW logical/framebuffer/content-scale callbacks and Renderer projection state.
- Produces: one coalesced `RenderSurfaceMetrics` update and explicit zero-framebuffer pause.

- [ ] **Step 1: Write failing value/coalescing tests**

Use:

```java
public record RenderSurfaceMetrics(
        int logicalWidth,
        int logicalHeight,
        int framebufferWidth,
        int framebufferHeight,
        float contentScaleX,
        float contentScaleY) {}
```

Reject negative dimensions and non-finite/non-positive scales. Update logical, framebuffer, and scale in any callback order, then assert one `consumeRenderSurfaceChange()` returns only the latest complete snapshot and the next consume is empty.

- [ ] **Step 2: Write failing Renderer surface tests**

Through an injectable viewport/projection seam or focused structure test, assert:

- framebuffer `1600x900`, logical `800x600`, scale `2x1.5` creates aspect `1600/900`, not `800/600`;
- zero framebuffer marks non-drawable, retains the last positive projection, and produces zero visible/draw metrics;
- the next positive framebuffer applies one viewport/projection update and resumes passes;
- logical-only or scale-only changes do not rebuild projection when framebuffer dimensions are unchanged.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.core.WindowMetricsTest `
  --tests com.overlord.renderer.RendererStructureTest `
  --console=plain --no-daemon
.\gradlew.bat :game:test `
  --tests com.gaia.GameLoopStructureTest `
  --console=plain --no-daemon
```

- [ ] **Step 4: Read and callback all initial metrics**

After context creation, call `glfwGetWindowSize`, `glfwGetFramebufferSize`, and `glfwGetWindowContentScale`. Install window-size, framebuffer-size, and content-scale callbacks. GLFW callbacks update only `WindowMetrics`; they do not invoke Renderer.

Expose an immutable current snapshot:

```java
public RenderSurfaceMetrics currentRenderSurfaceMetrics();
public Optional<RenderSurfaceMetrics> consumeRenderSurfaceChange();
```

Initialize `WindowMetrics` from one complete `RenderSurfaceMetrics` value.

- [ ] **Step 5: Replace framebuffer-only consumption**

Expose:

```java
Optional<RenderSurfaceMetrics> consumeRenderSurfaceChange();
```

GameLoop and engine Main consume it after `pollEvents` and call:

```java
renderer.updateSurface(surfaceMetrics);
```

Delete `consumeFramebufferResize` and `resizeFramebuffer` only after all callers/tests migrate.

Change Renderer initialization to:

```java
public void init(Camera camera, RenderSurfaceMetrics initialSurface);
```

`Engine.init()` passes `initializedWindow.currentRenderSurfaceMetrics()`; no caller reconstructs scale from width ratios.

- [ ] **Step 6: Implement zero-framebuffer behavior**

Renderer stores the latest surface, uses framebuffer dimensions only for viewport/projection, and checks `framebufferWidth > 0 && framebufferHeight > 0` before pass execution. It still begins/finishes metrics for minimized frames.

- [ ] **Step 7: Run GREEN and focus/resize regressions**

Run the RED command plus:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.core.thread.MainThreadGuardTest `
  --tests com.overlord.renderer.RenderAssetsTest `
  --tests com.overlord.renderer.RenderPipelineArchitectureTest `
  --console=plain --no-daemon
git diff --check
```

- [ ] **Step 8: Commit Task 10**

```powershell
git add engine/src/main/java/com/overlord/core `
  engine/src/main/java/com/overlord/renderer/Renderer.java `
  engine/src/main/java/com/overlord/Main.java `
  game/src/main/java/com/gaia/GameLoop.java `
  engine/src/test/java/com/overlord/core `
  engine/src/test/java/com/overlord/renderer/RendererStructureTest.java `
  game/src/test/java/com/gaia/GameLoopStructureTest.java
git commit -m "refactor(rendering): propagate complete render surface metrics"
```

---

### Task 11: Add optional console reporting and application composition

**Files:**
- Create: `game/src/main/java/com/gaia/rendering/RenderMetricsConsoleReporter.java`
- Modify: `game/src/main/java/com/gaia/GameContext.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Create: `game/src/test/java/com/gaia/rendering/RenderMetricsConsoleReporterTest.java`
- Modify: `game/src/test/java/com/gaia/GameBootstrapTest.java`
- Modify: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- Modify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`

**Interfaces:**
- Consumes: Task 9 `RenderMetrics`, immutable snapshot, and monotonic clock.
- Produces: disabled-by-default, once-per-second console observation with no UI or renderer mutation.

- [ ] **Step 1: Write failing reporter tests**

Construct with injected `LongSupplier` and `PrintStream`. Assert:

- disabled reporter emits nothing;
- enabled reporter emits nothing before one second;
- at one second it emits one Locale.ROOT line containing all six fields;
- repeated frames before the next second emit nothing;
- reporter never calls a Renderer mutation method and accepts only a snapshot.

- [ ] **Step 2: Write failing composition tests**

Assert `GameBootstrap` creates one `RenderVisualSettings.milestoneOneDefaults()` and passes it through the Task 1 four-argument `Engine` constructor. Also assert it reads `Boolean.getBoolean("gaia.renderMetrics")`, constructs the reporter explicitly, puts it in `GameContext`, and GameLoop invokes it only after `renderFrame` completes and before swap. Preserve all loading/fixed-update/input ordering.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.rendering.RenderMetricsConsoleReporterTest `
  --tests com.gaia.GameBootstrapTest `
  --tests com.gaia.GameBootstrapStructureTest `
  --tests com.gaia.GameLoopStructureTest `
  --console=plain --no-daemon
```

- [ ] **Step 4: Implement the separate observer**

Format exactly:

```text
RenderMetrics fps=%.2f frameMs=%.2f visibleChunks=%d drawCalls=%d triangles=%d meshQueue=%d
```

The reporter stores no OpenGL object, does not draw, does not mutate metrics, and emits at most once for each elapsed one-second interval.

- [ ] **Step 5: Run GREEN and full game regressions**

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.rendering.RenderMetricsConsoleReporterTest `
  --tests com.gaia.GameBootstrapTest `
  --tests com.gaia.GameBootstrapStructureTest `
  --tests com.gaia.GameLoopStructureTest `
  --tests com.gaia.RenderArchitectureTest `
  --console=plain --no-daemon
git diff --check
```

- [ ] **Step 6: Commit Task 11**

```powershell
git add game/src/main/java/com/gaia `
  game/src/test/java/com/gaia
git commit -m "feat(game): add optional render metrics reporting"
```

---

### Task 12: Harden architecture and packaged-resource gates

**Files:**
- Modify: `engine/build.gradle`
- Modify: `game/build.gradle`
- Modify: `engine/src/test/java/com/overlord/renderer/RenderPipelineArchitectureTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RendererStructureTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshLifecycleStructureTest.java`
- Modify: `game/src/test/java/com/gaia/RenderArchitectureTest.java`
- Modify: `game/src/test/java/com/gaia/world/WorldGenerationArchitectureTest.java`

**Interfaces:**
- Consumes: Tasks 1-11 final production structure and four shader resources.
- Produces: non-vacuous source/resource guards for every protected Phase 5B boundary.

- [ ] **Step 1: Write failing architecture assertions**

Require production source to prove:

- all `.vert`/`.frag` resources use exactly GLSL 410;
- no OpenGL symbol above 4.1, compute shader, or SSBO exists;
- no production `glEnable(GL_FRAMEBUFFER_SRGB)` exists and Renderer explicitly disables it;
- Texture has no mipmap generation call and sets nearest/base/max parameters;
- Game production has no OpenGL import/call;
- worker meshing has no World/Renderer/LWJGL/OpenGL dependency;
- Renderer frustum code has no unload/repository mutation call;
- `ChunkMeshInput` has all eight neighbors and no mutable Chunk field;
- AO diagonal invalidation is enforced in repository tests, not a second manager;
- no HUD/TextRenderer/UI class was added.

Use the existing comment/string-aware source scanning helpers so comments cannot satisfy or violate guards vacuously.

- [ ] **Step 2: Extend build resource tasks and verify RED**

Add exact required entries:

```text
assets/overlord/shaders/world.vert
assets/overlord/shaders/world.frag
assets/overlord/shaders/sky.vert
assets/overlord/shaders/sky.frag
```

Require them in the engine JAR and installed engine JAR. Before the build-script update, the strengthened structure test must fail for missing sky entries.

- [ ] **Step 3: Run focused GREEN**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.RenderPipelineArchitectureTest `
  --tests com.overlord.renderer.RendererStructureTest `
  --tests com.overlord.voxel.ChunkMeshLifecycleStructureTest `
  --console=plain --no-daemon
.\gradlew.bat :game:test `
  --tests com.gaia.RenderArchitectureTest `
  --tests com.gaia.world.WorldGenerationArchitectureTest `
  --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources `
  --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources `
  --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources `
  --rerun-tasks --console=plain --no-daemon
git diff --check
```

- [ ] **Step 4: Commit Task 12**

```powershell
git add engine/build.gradle game/build.gradle `
  engine/src/test/java/com/overlord `
  game/src/test/java/com/gaia
git commit -m "test(rendering): guard Phase 5B visual architecture"
```

---

### Task 13: Final verification, manual acceptance, reviews, and handoff

**Files:**
- Create: `docs/architecture/phase-05b-rendering-contract.md`
- Modify: `docs/architecture/current-baseline.md`
- Create: `docs/agent-handoffs/phase-05b-handoff.md`
- Modify: `docs/superpowers/plans/2026-07-26-phase-5b-rendering-visual-upgrade.md`
- Read only: all production/test files changed by Tasks 1-12.

**Interfaces:**
- Consumes: final Phase 5B implementation, fixed seed/coordinates, metrics reporter, owner reviews.
- Produces: normative contract, exact test/manual/platform evidence, final clean branch; no push/PR/merge.

- [x] **Step 1: Write the normative Phase 5B contract**

Record:

- exact `RenderVisualSettings` defaults;
- shader identities/uniforms and gamma invariant;
- AO formula and nine-snapshot/invalidation rules;
- texture level-zero and half-texel policy;
- frustum plane/epsilon semantics;
- metrics counting/reset semantics;
- render-surface and zero-framebuffer behavior;
- thread/GPU ownership and Phase 3 stale lifecycle;
- interfaces Phase 8/9/10 and later renderer work must not break.

- [x] **Step 2: Run the complete Windows automated gate**

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources `
  --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources `
  --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources `
  --rerun-tasks --console=plain --no-daemon
```

Parse every `TEST-*.xml` file and record Engine/Game suite, test, failure, error, and skip totals. Do not reuse Phase 5A's 823 count.

- [x] **Step 3: Run hygiene and scope checks**

```powershell
git diff --check origin/main
git status --short
git ls-files --others --exclude-standard
git diff --stat origin/main...HEAD
```

Also scan for tracked build/bin/class/crash/IDE output, absolute JDK paths, Game OpenGL, OpenGL above 4.1, GLSL above 410, compute/SSBO, engine-to-game imports, `GL_FRAMEBUFFER_SRGB` enable, mipmap generation, mutable-world meshing, renderer-driven unload, and protected worldgen/gameplay/physics diffs.

- [x] **Step 4: Run Windows development acceptance with metrics**

```powershell
$env:JAVA_TOOL_OPTIONS='-Dgaia.renderMetrics=true'
.\gradlew.bat :game --console=plain --no-daemon
```

At the approved seed and exact recorded position/orientation, capture at least three one-second metric samples after stabilization. Inspect sky gradient, fog, face light, AO, atlas edges, camera-rotation visible-count changes, nearby Chunk stability, safe spawn, movement, jump, collision, F1, resize, focus, Alt+Tab, and Escape. Capture the Gradle/game exit code. Do not commit screenshots or logs.

- [x] **Step 5: Run installDist acceptance**

Launch:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dgaia.renderMetrics=true'
.\game\build\install\game\bin\game.bat
```

Repeat the core visual, culling, metrics, resize, input, and Escape checks. Record the process exit code.

- [x] **Step 6: Record the Phase 5A/5B comparison truthfully**

Use the Task 0 baseline only if captured at the same seed, position, orientation, window, and scale. Otherwise state that Phase 5A FPS was not instrumented and compare structural Phase 5A draw calls against Phase 5B metrics without inventing FPS. Record visible chunks and draw calls before and after camera rotation.

- [x] **Step 7: Record native macOS status**

When available:

```bash
./gradlew clean test build
./gradlew :game
```

Check shader compilation, Retina logical/framebuffer/content scale, resize, lighting/fog/AO, input, and exit. If no native environment exists, write `macOS NOT RUN` and do not infer success.

- [x] **Step 8: Perform Engine-owner and Game/shared-owner reviews**

Engine owner reviews all `engine/**` changes, especially diagonal stale ownership, texture/shader GPU cleanup, frustum math, metrics exception semantics, and surface callbacks. Game/shared owner reviews composition, reporter, build scripts, documentation, unchanged worldgen bytes, and manual evidence.

Resolve every Critical, Important, and Minor finding through a focused RED/GREEN fix, rerun affected tests and the complete gate, then obtain explicit APPROVED verdicts.

- [x] **Step 9: Create the Phase 5B handoff**

`docs/agent-handoffs/phase-05b-handoff.md` must contain:

- completed and unfinished work;
- core architecture decisions;
- exact modified-file inventory;
- exact commands/results and current test counts;
- Windows development/installDist exit codes;
- same-seed metrics comparison and coordinates;
- macOS result or `NOT RUN`;
- owner-review verdicts;
- known risks;
- interfaces later phases must not break;
- full `git diff --stat`;
- suggested commit and PR title/description;
- explicit no-push/no-PR/no-merge status.

- [x] **Step 10: Commit Task 13 documentation**

```powershell
git add docs/architecture/phase-05b-rendering-contract.md `
  docs/architecture/current-baseline.md `
  docs/agent-handoffs/phase-05b-handoff.md `
  docs/superpowers/plans/2026-07-26-phase-5b-rendering-visual-upgrade.md
git diff --cached --check
git commit -m "docs(rendering): record Phase 5B visual contracts"
```

- [ ] **Step 11: Final post-commit verification**

```powershell
git status --short
git rev-parse HEAD
git branch --show-current
git diff --check origin/main..HEAD
git diff --stat origin/main..HEAD
git ls-files --others --exclude-standard
```

Expected: clean status, no untracked deliverables, final HEAD on `feat/rendering-visual-upgrade`, all review verdicts approved, and no push, pull request, or merge.

Task 13 status at the documentation commit: Steps 1–10 are complete. Windows
development and installDist acceptance both exited `0`; Phase 5A comparable
FPS and exact numeric player pose were not captured; macOS is `NOT RUN`. Both
code owners approved contingent on this documentation refresh, which resolves
their documentation findings. Step 11 remains controller post-commit
validation and must not be marked complete by this commit.

Suggested overall commit/squash message:

```text
feat(rendering): add voxel lighting fog culling and render metrics
```

Suggested PR title:

```text
feat(rendering): complete Milestone 1 visual rendering upgrade
```
