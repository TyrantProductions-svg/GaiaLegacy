# Phase 5A Render Pipeline Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the inline-shader, five-float world renderer with a JAR-safe GLSL 410 resource pipeline, a locked ten-float voxel vertex format, and explicit material, queue, pass, and OpenGL state-restoration boundaries while preserving the current Chunk lifecycle and visual result.

**Architecture:** Keep `Renderer` as the frame coordinator and `ChunkRenderBackend`. Introduce CPU-testable resource, vertex, queue, and pass contracts; isolate LWJGL behind guarded shader and state backends; migrate `GameLoop` only after the new pipeline components are independently green.

**Tech Stack:** Java 17, Gradle 8.5 Wrapper, JUnit Jupiter 6.1.1, LWJGL 3.3.3 OpenGL 4.1 APIs, GLSL 410 core, JOML 1.10.5.

## Global Constraints

- Base work on `origin/main` commit `647d91d5fcab15a0acdd60e7898729e35182f71e`.
- Work only on `feat/render-pipeline-core`; never modify, push, or merge `main`.
- Do not push, create a pull request, or merge during implementation.
- Keep Java 17 source/target compatibility; JDK 21 may run Gradle.
- Use only the checked-in Gradle Wrapper and never write a platform-specific JDK path.
- Keep every shader at `#version 410 core`; do not use compute shaders, SSBOs, OpenGL 4.2+, or platform-specific APIs.
- Every OpenGL call and GPU create/upload/draw/state/release operation must assert `MainThreadGuard`.
- Preserve Phase 3 `ChunkRenderObject`, `ChunkMeshManager`, per-Chunk upload/release, revision, stale-result, and unload contracts.
- Preserve Phase 2 `AssetManager`, `ResourceLocation`, `MaterialDefinition`, and `RenderType` as the only asset/material identity system.
- Keep `engine` independent of `game`; `game` must not import or call `org.lwjgl.opengl`.
- Do not modify world generation, physics, player behavior, gameplay mutation, or UI.
- Do not add lighting, AO calculation, fog, sky gradients, gamma, frustum culling, shadows, PBR, deferred rendering, SSAO, or transparent sorting.
- Use constructor injection or explicit contexts; do not expand `ServiceLocator`.
- Do not copy third-party code or resources.
- Use TDD for every production change: observe the focused test fail for the expected reason before implementing.

## Baseline Gate

- [ ] Run `git status --short` and confirm no uncommitted or untracked files before Task 1.
- [ ] Run `.\gradlew.bat clean test build --console=plain --no-daemon`.
- [ ] Parse `engine/build/test-results/test/TEST-*.xml` and `game/build/test-results/test/TEST-*.xml`; record the exact baseline suite/test/failure/error/skip counts in the execution notes.
- [ ] Stop and request direction if the clean Phase 4 baseline does not pass.

---

### Task 1: JAR-safe shader resources and source loading

**Files:**
- Create: `engine/src/main/resources/assets/overlord/shaders/world.vert`
- Create: `engine/src/main/resources/assets/overlord/shaders/world.frag`
- Create: `engine/src/main/java/com/overlord/renderer/shader/ShaderSourceSet.java`
- Create: `engine/src/main/java/com/overlord/renderer/shader/ShaderResourceLoader.java`
- Create: `engine/src/test/java/com/overlord/renderer/shader/ShaderResourceLoaderTest.java`

**Interfaces:**
- Consumes: `AssetManager.readUtf8(ResourceLocation)` and Phase 2 `AssetLoadException`.
- Produces:

```java
public record ShaderSourceSet(
        String label,
        ResourceLocation vertexResource,
        String vertexSource,
        ResourceLocation fragmentResource,
        String fragmentSource) {}

public final class ShaderResourceLoader {
    public ShaderResourceLoader(AssetManager assets);

    public ShaderSourceSet load(
            String label,
            ResourceLocation vertexResource,
            ResourceLocation fragmentResource);
}
```

- [ ] **Step 1: Write failing resource-loader tests**

Add tests that load `overlord:shaders/world.vert` and
`overlord:shaders/world.frag` from the test runtime classpath, copy them into a
temporary JAR and load them through a `URLClassLoader`, and request
`overlord:shaders/missing.vert`.

```java
ShaderSourceSet sources =
        new ShaderResourceLoader(
                        new AssetManager(getClass().getClassLoader()))
                .load(
                        "world",
                        ResourceLocation.parse(
                                "overlord:shaders/world.vert"),
                        ResourceLocation.parse(
                                "overlord:shaders/world.frag"));

assertEquals("world", sources.label());
assertTrue(sources.vertexSource().startsWith("#version 410 core"));
assertTrue(sources.fragmentSource().startsWith("#version 410 core"));
```

The missing-resource assertion must inspect `AssetLoadException.report()` and
verify diagnostic code `ASSET_NOT_FOUND` plus the exact missing
`ResourceLocation`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.shader.ShaderResourceLoaderTest `
  --console=plain --no-daemon
```

Expected: compilation fails because `ShaderSourceSet` and
`ShaderResourceLoader` do not exist.

- [ ] **Step 3: Add visual-equivalent GLSL 410 resources**

The vertex shader declares locations 0 through 4 but uses only position and UV
for Phase 5A output:

```glsl
#version 410 core
layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec2 aUv;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in float aFaceLight;
layout (location = 4) in float aAmbientOcclusion;
uniform mat4 projection;
uniform mat4 view;
uniform mat4 model;
out vec2 texCoord;
void main() {
    gl_Position =
        projection * view * model * vec4(aPosition, 1.0);
    texCoord = aUv;
}
```

The fragment shader samples the existing atlas without applying lighting:

```glsl
#version 410 core
in vec2 texCoord;
out vec4 fragmentColor;
uniform sampler2D textureAtlas;
void main() {
    fragmentColor = texture(textureAtlas, texCoord);
}
```

- [ ] **Step 4: Implement immutable source loading**

Validate every record/constructor field with `Objects.requireNonNull`. Reject a
blank label with `IllegalArgumentException`. Implement `load` only through
`AssetManager.readUtf8`; do not use `ClassLoader.getResource`, `Path`, or
`File`.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the Task 1 command again. Expected: all
`ShaderResourceLoaderTest` tests pass.

- [ ] **Step 6: Commit Task 1**

```powershell
git add engine/src/main/resources/assets/overlord/shaders `
  engine/src/main/java/com/overlord/renderer/shader `
  engine/src/test/java/com/overlord/renderer/shader
git commit -m "feat(rendering): load GLSL 410 shaders from resources"
```

---

### Task 2: Guarded ShaderProgram with diagnostic fake backend

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/shader/ShaderStage.java`
- Create: `engine/src/main/java/com/overlord/renderer/shader/ShaderBackend.java`
- Create: `engine/src/main/java/com/overlord/renderer/shader/OpenGlShaderBackend.java`
- Create: `engine/src/main/java/com/overlord/renderer/shader/ShaderBinding.java`
- Create: `engine/src/main/java/com/overlord/renderer/shader/ShaderProgramException.java`
- Create: `engine/src/main/java/com/overlord/renderer/shader/ShaderProgram.java`
- Create: `engine/src/test/java/com/overlord/renderer/shader/ShaderProgramTest.java`
- Modify: `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`

**Interfaces:**
- Consumes: `ShaderSourceSet` from Task 1 and `MainThreadGuard`.
- Produces:

```java
public enum ShaderStage {
    VERTEX,
    FRAGMENT
}

interface ShaderBackend {
    int createShader(ShaderStage stage);
    void setSource(int shaderId, String source);
    void compile(int shaderId);
    boolean compileSucceeded(int shaderId);
    String shaderInfoLog(int shaderId);
    void deleteShader(int shaderId);
    int createProgram();
    void attach(int programId, int shaderId);
    void link(int programId);
    boolean linkSucceeded(int programId);
    String programInfoLog(int programId);
    int uniformLocation(int programId, String name);
    void useProgram(int programId);
    void uploadMatrix4(int location, float[] columnMajor);
    void uploadInt(int location, int value);
    void deleteProgram(int programId);
}

public interface ShaderBinding {
    int programId();
    void use();
    void setMatrix4(String uniform, Matrix4fc value);
    void setInt(String uniform, int value);
}

public final class ShaderProgram
        implements ShaderBinding, AutoCloseable {
    public ShaderProgram(
            MainThreadGuard guard,
            ShaderSourceSet sources,
            List<String> requiredUniforms);

    // package-private test seam
    ShaderProgram(
            MainThreadGuard guard,
            ShaderSourceSet sources,
            List<String> requiredUniforms,
            ShaderBackend backend);

    public void cleanup();

    @Override
    public void close();
}
```

- [ ] **Step 1: Write failing compile/link/uniform/cleanup tests**

Use a deterministic `FakeShaderBackend` with assigned IDs and call counters.
Cover:

```java
ShaderProgramException failure =
        assertThrows(
                ShaderProgramException.class,
                () ->
                        new ShaderProgram(
                                guard,
                                sources(),
                                List.of("projection"),
                                backendThatFailsVertexCompile(
                                        "line 4: syntax error")));

assertTrue(failure.getMessage().contains("world"));
assertTrue(failure.getMessage().contains("VERTEX"));
assertTrue(
        failure.getMessage()
                .contains("overlord:shaders/world.vert"));
assertTrue(failure.getMessage().contains("line 4: syntax error"));
assertEquals(List.of(101), backend.deletedShaders());
```

Add corresponding link-failure and missing-uniform tests. Verify successful
construction deletes both temporary shaders, caches locations exactly once,
uploads matrices/integers to cached locations, and deletes the program exactly
once across repeated `cleanup`.

- [ ] **Step 2: Add a worker-thread RED test**

Construct `ShaderProgram` on an executor using an owner-thread guard. Expected
cause: `IllegalStateException` before the fake backend records any call.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.shader.ShaderProgramTest `
  --tests com.overlord.core.thread.MainThreadGuardTest `
  --console=plain --no-daemon
```

Expected: compilation fails because the Task 2 types do not exist.

- [ ] **Step 4: Implement the semantic shader backend**

`OpenGlShaderBackend` is the only Task 2 class that imports LWJGL. Map
`ShaderStage.VERTEX` to `GL_VERTEX_SHADER` and
`ShaderStage.FRAGMENT` to `GL_FRAGMENT_SHADER`. Use only GL 4.1-compatible
functions exposed by `GL30C`.

`ShaderProgram` must:

- assert the guard before the first backend call;
- compile vertex then fragment;
- link one program;
- resolve `projection`, `view`, `model`, and `textureAtlas` when requested;
- reject duplicate/blank required uniform names;
- store an immutable uniform-location map;
- delete partial resources and attach cleanup failures as suppressed
  exceptions.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the Task 2 command again. Expected: all focused tests pass without an
OpenGL context because tests use the fake backend.

- [ ] **Step 6: Commit Task 2**

```powershell
git add engine/src/main/java/com/overlord/renderer/shader `
  engine/src/test/java/com/overlord/renderer/shader `
  engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java
git commit -m "feat(rendering): add guarded shader program diagnostics"
```

---

### Task 3: Unified ten-float voxel vertex format

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/VoxelVertexAttribute.java`
- Create: `engine/src/main/java/com/overlord/voxel/VoxelVertexFormat.java`
- Create: `engine/src/test/java/com/overlord/voxel/VoxelVertexFormatTest.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshData.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Mesh.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshDataTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/ChunkRenderBackendTest.java`

**Interfaces:**
- Consumes: `BlockFace`, `TextureRegion`, and existing Chunk-local mesh rules.
- Produces:

```java
public record VoxelVertexAttribute(
        int location,
        int componentCount,
        int floatOffset) {
    public int byteOffset() {
        return floatOffset * Float.BYTES;
    }
}

public final class VoxelVertexFormat {
    public static final int FLOATS_PER_VERTEX = 10;
    public static final int STRIDE_BYTES = 40;
    public static final int DEFAULT_LIGHT_LEVEL = 15;
    public static final float DEFAULT_AMBIENT_OCCLUSION = 1.0f;

    public static List<VoxelVertexAttribute> attributes();
    public static int faceId(BlockFace face);
    public static float encodeFaceLight(
            BlockFace face, int lightLevel);
}
```

- [ ] **Step 1: Write the failing format contract test**

Assert the exact locations, component counts, float offsets, byte offsets, and
stride:

```java
assertEquals(
        List.of(
                new VoxelVertexAttribute(0, 3, 0),
                new VoxelVertexAttribute(1, 2, 3),
                new VoxelVertexAttribute(2, 3, 5),
                new VoxelVertexAttribute(3, 1, 8),
                new VoxelVertexAttribute(4, 1, 9)),
        VoxelVertexFormat.attributes());
assertEquals(40, VoxelVertexFormat.STRIDE_BYTES);
assertEquals(
        47.0f,
        VoxelVertexFormat.encodeFaceLight(
                BlockFace.UP, 15));
```

Test every explicit face mapping and reject light values below 0 or above 15.

- [ ] **Step 2: Update mesh tests to the new expected layout and verify RED**

Change one-block length from 180 to 360 floats and five-face length from 150
to 300. Read U at `vertexOffset + 3`, normal at offsets 5..7, face/light at 8,
and AO at 9 with a stride of 10.

Add per-face assertions for:

```text
NORTH (0, 0, -1)
SOUTH (0, 0, 1)
UP    (0, 1, 0)
DOWN  (0, -1, 0)
WEST  (-1, 0, 0)
EAST  (1, 0, 0)
```

Run:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.VoxelVertexFormatTest `
  --tests com.overlord.voxel.ChunkMeshDataTest `
  --tests com.overlord.voxel.ChunkMeshBuilderTest `
  --tests com.overlord.renderer.ChunkRenderBackendTest `
  --console=plain --no-daemon
```

Expected: compilation failure for the missing format types or assertions still
observe the old five-float layout.

- [ ] **Step 3: Implement the pure vertex contract**

Return an immutable attribute list. Implement `faceId` with an exhaustive
`switch` over names, not `ordinal()`. Validate the light range before encoding.
Return `(float) (faceId(face) * 16 + lightLevel)` after validation; do not use
bit reinterpretation or enum ordinals.

- [ ] **Step 4: Migrate ChunkMeshData**

Replace the private five-float constant with
`VoxelVertexFormat.FLOATS_PER_VERTEX`. The validation message must report the
required ten-float layout. Bounds still read position offsets 0..2 and step by
the shared stride.

- [ ] **Step 5: Migrate ChunkMeshBuilder**

Replace five-float face arrays with an `addVertex` helper:

```java
private static void addVertex(
        List<Float> vertices,
        float x,
        float y,
        float z,
        float u,
        float v,
        BlockFace face,
        float normalX,
        float normalY,
        float normalZ) {
    vertices.add(x);
    vertices.add(y);
    vertices.add(z);
    vertices.add(u);
    vertices.add(v);
    vertices.add(normalX);
    vertices.add(normalY);
    vertices.add(normalZ);
    vertices.add(
            VoxelVertexFormat.encodeFaceLight(
                    face,
                    VoxelVertexFormat.DEFAULT_LIGHT_LEVEL));
    vertices.add(
            VoxelVertexFormat.DEFAULT_AMBIENT_OCCLUSION);
}
```

Keep the existing six-triangle vertex positions, UV orientation, neighbor
occlusion, Chunk-local coordinates, key, revision, and bounds unchanged.

- [ ] **Step 6: Migrate Mesh VAO configuration**

Calculate `vertexCount` with the shared float count. Loop through
`VoxelVertexFormat.attributes()` and call `glVertexAttribPointer` with
`GL_FLOAT`, `VoxelVertexFormat.STRIDE_BYTES`, and each byte offset. Keep upload,
draw, unbind, failure cleanup, and `MainThreadGuard` behavior unchanged.

- [ ] **Step 7: Run focused tests and verify GREEN**

Run the Task 3 command again. Expected: all selected tests pass.

- [ ] **Step 8: Commit Task 3**

```powershell
git add `
  engine/src/main/java/com/overlord/voxel/VoxelVertexAttribute.java `
  engine/src/main/java/com/overlord/voxel/VoxelVertexFormat.java `
  engine/src/main/java/com/overlord/voxel/ChunkMeshData.java `
  engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java `
  engine/src/main/java/com/overlord/renderer/Mesh.java `
  engine/src/test/java/com/overlord/voxel/VoxelVertexFormatTest.java `
  engine/src/test/java/com/overlord/voxel/ChunkMeshDataTest.java `
  engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java `
  engine/src/test/java/com/overlord/renderer/ChunkRenderBackendTest.java
git commit -m "refactor(rendering): migrate chunks to unified vertex format"
```

---

### Task 4: Runtime Material and RenderAssets composition

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/TextureBinding.java`
- Create: `engine/src/main/java/com/overlord/renderer/material/Material.java`
- Create: `engine/src/test/java/com/overlord/renderer/material/MaterialTest.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Texture.java`
- Modify: `engine/src/main/java/com/overlord/renderer/RenderAssets.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java`
- Modify: `game/src/main/java/com/gaia/assets/GaiaResourceLoader.java`
- Modify: `game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java`
- Modify: `game/src/test/java/com/gaia/assets/GaiaProductionAssetsTest.java`

**Interfaces:**
- Consumes: Task 2 `ShaderBinding`, existing `TextureImage`,
  `MaterialDefinition`, and `gaia:opaque`.
- Produces:

```java
public interface TextureBinding {
    void bind(int textureUnit);
}

public final class Texture implements TextureBinding {
    // existing API and ownership remain
}

public record Material(
        MaterialDefinition definition,
        ShaderBinding shader,
        TextureBinding texture) {}

public record RenderAssets(
        TextureImage blockAtlas,
        MaterialDefinition worldMaterial,
        ResourceLocation worldVertexShader,
        ResourceLocation worldFragmentShader) {
    public static final ResourceLocation DEFAULT_WORLD_VERTEX_SHADER =
            ResourceLocation.parse(
                    "overlord:shaders/world.vert");
    public static final ResourceLocation DEFAULT_WORLD_FRAGMENT_SHADER =
            ResourceLocation.parse(
                    "overlord:shaders/world.frag");
}
```

- [ ] **Step 1: Write failing Material ownership tests**

Use fake `ShaderBinding` and `TextureBinding`. Assert the exact Phase 2
definition is preserved and that `Material` exposes no `cleanup` or `close`
method:

```java
Material material =
        new Material(definition, fakeShader, fakeTexture);
assertSame(definition, material.definition());
assertSame(fakeShader, material.shader());
assertSame(fakeTexture, material.texture());
assertThrows(
        NoSuchMethodException.class,
        () -> Material.class.getMethod("cleanup"));
```

- [ ] **Step 2: Write failing RenderAssets and Gaia composition tests**

Assert `RenderAssets.missing()` has a non-null explicit missing
`MaterialDefinition` and the two default shader locations. Extend
`GaiaResourceLoaderTest` and `GaiaProductionAssetsTest` to assert:

```java
assertEquals(
        ResourceLocation.parse("gaia:opaque"),
        catalog.renderAssets().worldMaterial().id());
assertEquals(
        RenderAssets.DEFAULT_WORLD_VERTEX_SHADER,
        catalog.renderAssets().worldVertexShader());
```

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.material.MaterialTest `
  --tests com.overlord.renderer.RenderAssetsTest `
  --console=plain --no-daemon
.\gradlew.bat :game:test `
  --tests com.gaia.assets.GaiaResourceLoaderTest `
  --tests com.gaia.assets.GaiaProductionAssetsTest `
  --console=plain --no-daemon
```

Expected: compilation failure because `Material`, `TextureBinding`, and the
expanded `RenderAssets` fields do not exist.

- [ ] **Step 4: Implement non-owning Material and expanded RenderAssets**

Validate every constructor field. `RenderAssets.missing()` creates a
`MaterialDefinition` using:

```text
id=overlord:missing
atlas=overlord:missing
renderType=OPAQUE
alphaCutoff=0.5
missingRegion=overlord:missing
```

Do not add a registry or mutable material map.

- [ ] **Step 5: Select the existing Gaia opaque definition**

At the end of `GaiaResourceLoader.load`, retrieve `gaia:opaque` from the
already validated `materialById` map and pass it to `RenderAssets`. If it is
absent, add this exact diagnostic before the existing `throwIfErrors` path;
do not manufacture a second Gaia material:

```java
new AssetDiagnostic(
        AssetSeverity.ERROR,
        "RENDER_WORLD_MATERIAL_MISSING",
        "assets/gaia/resource-index.json",
        ResourceLocation.parse("gaia:opaque"),
        "materials",
        "Required world material gaia:opaque is not declared",
        null)
```

- [ ] **Step 6: Run focused tests and verify GREEN**

Run both Task 4 commands again. Expected: all selected tests pass.

- [ ] **Step 7: Commit Task 4**

```powershell
git add `
  engine/src/main/java/com/overlord/renderer/TextureBinding.java `
  engine/src/main/java/com/overlord/renderer/Texture.java `
  engine/src/main/java/com/overlord/renderer/RenderAssets.java `
  engine/src/main/java/com/overlord/renderer/material/Material.java `
  engine/src/test/java/com/overlord/renderer/material/MaterialTest.java `
  engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java `
  game/src/main/java/com/gaia/assets/GaiaResourceLoader.java `
  game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java `
  game/src/test/java/com/gaia/assets/GaiaProductionAssetsTest.java
git commit -m "feat(rendering): add runtime material bindings"
```

---

### Task 5: Exact OpenGL state capture and restoration

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/state/BlendMode.java`
- Create: `engine/src/main/java/com/overlord/renderer/state/RenderStateSpec.java`
- Create: `engine/src/main/java/com/overlord/renderer/state/RenderStateSnapshot.java`
- Create: `engine/src/main/java/com/overlord/renderer/state/RenderStateBackend.java`
- Create: `engine/src/main/java/com/overlord/renderer/state/OpenGlRenderStateBackend.java`
- Create: `engine/src/main/java/com/overlord/renderer/state/RenderStateScope.java`
- Create: `engine/src/test/java/com/overlord/renderer/state/RenderStateScopeTest.java`
- Modify: `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`

**Interfaces:**
- Consumes: `MainThreadGuard`; no game or voxel type.
- Produces:

```java
public enum BlendMode {
    DISABLED,
    ALPHA
}

public record RenderStateSpec(
        boolean depthTest,
        boolean depthWrite,
        BlendMode blendMode,
        boolean cullFace) {}

public record RenderStateSnapshot(
        boolean depthTest,
        boolean depthWrite,
        boolean blend,
        int blendSourceRgb,
        int blendDestinationRgb,
        int blendSourceAlpha,
        int blendDestinationAlpha,
        int blendEquationRgb,
        int blendEquationAlpha,
        boolean cullFace,
        int currentProgram,
        int activeTexture,
        int texture2dUnit0) {}

public interface RenderStateBackend {
    RenderStateSnapshot capture();
    void apply(RenderStateSpec state);
    void restore(RenderStateSnapshot snapshot);
    void clearColorAndDepth();
}

public final class RenderStateScope implements AutoCloseable {
    public static RenderStateScope open(
            RenderStateBackend backend,
            RenderStateSpec requested);
}
```

- [ ] **Step 1: Write failing scope tests with a fake backend**

Cover call order, normal restoration, exceptional restoration, and idempotent
close:

```java
try (RenderStateScope ignored =
        RenderStateScope.open(backend, WORLD_OPAQUE)) {
    assertEquals(
            List.of("capture", "apply:" + WORLD_OPAQUE),
            backend.calls());
}
assertEquals("restore:" + incoming, backend.lastCall());
```

Throw inside the try block and assert restoration happened before the same
exception escaped.

- [ ] **Step 2: Add worker rejection test and verify RED**

Construct `OpenGlRenderStateBackend` with an owner guard and call `capture`
from a worker. Assert `IllegalStateException` occurs before any OpenGL call can
be made.

Run:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.state.RenderStateScopeTest `
  --tests com.overlord.core.thread.MainThreadGuardTest `
  --console=plain --no-daemon
```

Expected: compilation failure for missing state types.

- [ ] **Step 3: Implement the scope and exact production snapshot**

Use `glIsEnabled`, `glGetBoolean`, and `glGetInteger` calls available in
OpenGL 4.1. To capture unit-0 texture binding:

1. record the active texture;
2. activate `GL_TEXTURE0`;
3. query `GL_TEXTURE_BINDING_2D`;
4. restore the original active texture before returning.

`restore` must restore blend factors/equations, enable flags, depth mask,
program, unit-0 texture binding, and original active texture. `ALPHA` applies
`GL_SRC_ALPHA`, `GL_ONE_MINUS_SRC_ALPHA`, and `GL_FUNC_ADD`.

- [ ] **Step 4: Implement guarded clear**

`clearColorAndDepth` asserts the main thread and calls only:

```java
glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
```

`Renderer.init` remains responsible for the existing clear color.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the Task 5 command again. Expected: all selected tests pass without a live
OpenGL context.

- [ ] **Step 6: Commit Task 5**

```powershell
git add engine/src/main/java/com/overlord/renderer/state `
  engine/src/test/java/com/overlord/renderer/state `
  engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java
git commit -m "feat(rendering): scope and restore OpenGL pass state"
```

---

### Task 6: RenderQueue and ordered pass pipeline

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/queue/RenderItem.java`
- Create: `engine/src/main/java/com/overlord/renderer/queue/RenderQueue.java`
- Create: `engine/src/main/java/com/overlord/renderer/pass/RenderContext.java`
- Create: `engine/src/main/java/com/overlord/renderer/pass/RenderPass.java`
- Create: `engine/src/main/java/com/overlord/renderer/pass/RenderPipeline.java`
- Create: `engine/src/main/java/com/overlord/renderer/pass/SkyRenderPass.java`
- Create: `engine/src/main/java/com/overlord/renderer/pass/WorldRenderPass.java`
- Create: `engine/src/main/java/com/overlord/renderer/pass/DebugRenderPass.java`
- Create: `engine/src/test/java/com/overlord/renderer/queue/RenderQueueTest.java`
- Create: `engine/src/test/java/com/overlord/renderer/pass/RenderPipelineTest.java`
- Create: `engine/src/test/java/com/overlord/renderer/pass/WorldRenderPassTest.java`

**Interfaces:**
- Consumes: Task 4 `Material`, `ChunkRenderObject`; Task 5 state types.
- Produces:

```java
public record RenderItem(
        ChunkRenderObject object,
        Material material) {}

public final class RenderQueue {
    public void submit(
            ChunkRenderObject object, Material material);
    public List<RenderItem> opaqueItems();
    public List<RenderItem> transparentItems();
    public void clear();
    public boolean isEmpty();
}

public final class RenderContext {
    public RenderContext(
            Matrix4fc projection,
            Matrix4fc view);

    public Matrix4f projection();
    public Matrix4f view();
}

public interface RenderPass {
    String id();
    void render(
            RenderContext context, RenderQueue queue);
}

public final class RenderPipeline {
    public RenderPipeline(List<RenderPass> passes);
    public void render(
            RenderContext context, RenderQueue queue);
    public List<String> passIds();
}
```

- [ ] **Step 1: Write failing queue tests**

Submit OPAQUE, CUTOUT, and TRANSPARENT fake materials. Assert OPAQUE/CUTOUT
retain stable order in `opaqueItems`, TRANSPARENT uses
`transparentItems`, returned lists are immutable, and `clear` empties both.

- [ ] **Step 2: Write failing pipeline ordering/cleanup tests**

Use three fake passes that append IDs. Assert:

```java
pipeline.render(context, queue);
assertEquals(
        List.of("sky", "world", "debug"),
        calls);
assertTrue(queue.isEmpty());
```

Make the world fake throw and assert debug is not called, the same exception
escapes, and the queue is still cleared in `finally`.

- [ ] **Step 3: Write failing WorldRenderPass tests**

Use fake shader/texture bindings, fake `ChunkGpuMesh`, and fake state backend.
Assert:

- opaque items draw before transparent items;
- opaque uses `depth=true`, `depthWrite=true`, `BlendMode.DISABLED`;
- transparent uses `depth=true`, `depthWrite=false`, `BlendMode.ALPHA`;
- projection/view/model uniforms and `textureAtlas=0` are uploaded;
- each material binds texture unit 0;
- a draw failure still restores the incoming state.

- [ ] **Step 4: Run focused tests and verify RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.queue.RenderQueueTest `
  --tests com.overlord.renderer.pass.RenderPipelineTest `
  --tests com.overlord.renderer.pass.WorldRenderPassTest `
  --console=plain --no-daemon
```

Expected: compilation failure because the queue/pass classes do not exist.

- [ ] **Step 5: Implement queue routing and defensive views**

Route `RenderType.TRANSPARENT` to transparent; route OPAQUE and CUTOUT to
opaque. Return `List.copyOf` snapshots, not mutable backing lists.

- [ ] **Step 6: Implement the three passes and pipeline**

Use these exact IDs:

```text
sky
world
debug
```

`SkyRenderPass` opens the sky state scope and calls
`clearColorAndDepth`. `WorldRenderPass` skips empty lists and uses one scope
per queue category. For every item it binds its non-owning material, uploads
projection/view/model/texture unit uniforms, and calls
`item.object().mesh().draw()`. `DebugRenderPass` returns immediately while
disabled.

Use these exact state specs:

```java
new RenderStateSpec(
        false, true, BlendMode.DISABLED, false); // sky
new RenderStateSpec(
        true, true, BlendMode.DISABLED, false);  // opaque
new RenderStateSpec(
        true, false, BlendMode.ALPHA, false);    // transparent
```

`RenderContext` copies both input matrices at construction and returns a new
`Matrix4f` from each accessor so later camera/projection mutation cannot alter
an in-flight pass context.

`RenderPipeline` copies the pass list, rejects duplicate IDs, executes in list
order, and clears the queue in `finally`.

- [ ] **Step 7: Run focused tests and verify GREEN**

Run the Task 6 command again. Expected: all selected tests pass.

- [ ] **Step 8: Commit Task 6**

```powershell
git add engine/src/main/java/com/overlord/renderer/queue `
  engine/src/main/java/com/overlord/renderer/pass `
  engine/src/test/java/com/overlord/renderer/queue `
  engine/src/test/java/com/overlord/renderer/pass
git commit -m "feat(rendering): add ordered render passes and queues"
```

---

### Task 7: Integrate Renderer, Engine, and GameLoop

**Files:**
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Delete: `engine/src/main/java/com/overlord/renderer/Shader.java`
- Modify: `engine/src/main/java/com/overlord/core/Engine.java`
- Modify: `engine/src/main/java/com/overlord/Main.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/ChunkRenderBackendTest.java`
- Create: `engine/src/test/java/com/overlord/renderer/RendererStructureTest.java`
- Modify: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- Modify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`

**Interfaces:**
- Consumes: Tasks 1 through 6.
- Produces:

```java
public final class Renderer implements ChunkRenderBackend {
    public Renderer(
            MainThreadGuard guard,
            RenderAssets assets);

    public Renderer(
            MainThreadGuard guard,
            RenderAssets assets,
            AssetManager assetManager);

    public void renderFrame(
            Collection<ChunkRenderObject> chunks);
}

public Engine(
        MainThreadGuard guard,
        RenderAssets assets,
        AssetManager assetManager);
```

Existing two-argument `Engine` and `Renderer` constructors remain and delegate
to an `AssetManager` using the engine classloader, preserving source
compatibility.

- [ ] **Step 1: Write failing integration structure tests**

Assert that `Renderer.java` contains no `#version`, no inline shader source,
and no `new Shader(`. Assert it contains:

```text
ShaderResourceLoader
ShaderProgram
RenderQueue
SkyRenderPass
WorldRenderPass
DebugRenderPass
RenderPipeline
renderFrame
```

Assert `Shader.java` is absent after GREEN.

- [ ] **Step 2: Update GameLoop/Bootstrap tests for the intended API and verify RED**

`GameBootstrapStructureTest` must require one reused `AssetManager` variable
for `GaiaResourceLoader` and the three-argument `Engine`.

`GameLoopStructureTest` must require:

```java
context.engine()
        .getRenderer()
        .renderFrame(
                state == State.RUNNING
                        ? context.chunkMeshes().renderObjects()
                        : List.of());
```

It must still prove mesh pumping and render-camera interpolation occur before
rendering and that no combined mesh or direct `new Mesh` path returns.

Run:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.RendererStructureTest `
  --tests com.overlord.renderer.RenderAssetsTest `
  --tests com.overlord.renderer.ChunkRenderBackendTest `
  --console=plain --no-daemon
.\gradlew.bat :game:test `
  --tests com.gaia.GameBootstrapStructureTest `
  --tests com.gaia.GameLoopStructureTest `
  --console=plain --no-daemon
```

Expected: assertions fail because the old `clear`/`renderChunks` and inline
`Shader` path still exist.

- [ ] **Step 3: Migrate Engine and application composition**

Store the injected `AssetManager` in `Engine`, pass it to `Renderer`, and
preserve init failure cleanup. In `GameBootstrap`, create one variable:

```java
AssetManager assetManager =
        new AssetManager(
                GameBootstrap.class.getClassLoader());
GaiaAssetCatalog catalog =
        new GaiaResourceLoader(assetManager).load();
Engine engine =
        new Engine(
                mainThreadGuard,
                catalog.renderAssets(),
                assetManager);
```

Do not register `AssetManager`, passes, queues, or materials in
`ServiceLocator`.

- [ ] **Step 4: Migrate Renderer initialization and cleanup**

Initialization order:

1. load `ShaderSourceSet`;
2. create `ShaderProgram` with
   `List.of("projection", "view", "model", "textureAtlas")`;
3. create atlas `Texture`;
4. create non-owning `Material`;
5. create `OpenGlRenderStateBackend`;
6. create sky/world/debug passes and `RenderPipeline`.

On failure, clean texture then program, attach cleanup failures as suppressed,
and leave every field null. Normal cleanup uses the same reverse GPU order and
is idempotent.

- [ ] **Step 5: Implement renderFrame and preserve Chunk backend behavior**

`renderFrame` asserts the main thread, clears the reusable queue before
collection, submits every current Chunk with the world material, builds a
defensive `RenderContext` from projection/view, and calls
`RenderPipeline.render`. Wrap collection plus pipeline execution in an outer
`try/finally` that clears the queue, so an invalid collection element cannot
retain earlier submissions. `upload` and `release` retain all current
validation, main-thread, local-bounds, partial-Mesh cleanup, and per-object
release behavior.

Delete old `clear` and `renderChunks` only after both application callers use
`renderFrame`.

- [ ] **Step 6: Update GameLoop and engine Main**

`GameLoop` calls one `renderFrame` every visible frame, with an empty list
during loading and installed render objects while running. `Main` calls
`renderFrame(List.of())`. Resize, polling, camera interpolation, and
swap-buffer order stay unchanged.

- [ ] **Step 7: Run integration tests and verify GREEN**

Run both Task 7 commands again. Expected: all selected tests pass.

- [ ] **Step 8: Run Chunk lifecycle regression tests**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.ChunkMeshManagerTest `
  --tests com.overlord.voxel.ChunkMeshLifecycleStructureTest `
  --tests com.overlord.renderer.ChunkRenderObjectTest `
  --tests com.overlord.renderer.ChunkRenderBackendTest `
  --console=plain --no-daemon
```

Expected: all tests pass; no independent-Chunk or cleanup contract changed.

- [ ] **Step 9: Commit Task 7**

```powershell
git add `
  engine/src/main/java/com/overlord/renderer/Renderer.java `
  engine/src/main/java/com/overlord/core/Engine.java `
  engine/src/main/java/com/overlord/Main.java `
  engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java `
  engine/src/test/java/com/overlord/renderer/ChunkRenderBackendTest.java `
  engine/src/test/java/com/overlord/renderer/RendererStructureTest.java `
  game/src/main/java/com/gaia/GameBootstrap.java `
  game/src/main/java/com/gaia/GameLoop.java `
  game/src/test/java/com/gaia/GameBootstrapStructureTest.java `
  game/src/test/java/com/gaia/GameLoopStructureTest.java
git rm engine/src/main/java/com/overlord/renderer/Shader.java
git commit -m "refactor(rendering): route frames through render pipeline"
```

---

### Task 8: Architecture guards and packaged shader verification

**Files:**
- Create: `engine/src/test/java/com/overlord/renderer/RenderPipelineArchitectureTest.java`
- Create: `game/src/test/java/com/gaia/RenderArchitectureTest.java`
- Modify: `engine/build.gradle`
- Modify: `game/build.gradle`
- Modify: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`

**Interfaces:**
- Consumes: final production packages from Tasks 1 through 7.
- Produces Gradle tasks:

```text
:engine:verifyPackagedShaderResources
:game:verifyInstalledShaderResources
```

Both tasks are dependencies of their module `check`.

- [ ] **Step 1: Write failing architecture tests**

`RenderPipelineArchitectureTest` reads engine source and asserts:

- `ChunkMeshBuilder`, `ChunkMeshData`, `ChunkMeshManager`, and generation
  packages do not import `org.lwjgl`;
- `OpenGlShaderBackend`, `OpenGlRenderStateBackend`, `Mesh`, `Texture`,
  `Renderer`, and `Window` remain under engine;
- `ShaderProgram` and state entry points store/use `MainThreadGuard`;
- no source contains `#version 420`, `#version 430`, `glDispatchCompute`, or
  `GL_SHADER_STORAGE_BUFFER`.

`game/RenderArchitectureTest` recursively reads `src/main/java` and rejects
`org.lwjgl.opengl`, `glUseProgram`, `glBindTexture`, `glBindVertexArray`, and
`glDraw`.

- [ ] **Step 2: Extend build-script structure assertions and verify RED**

Require both task names, both shader paths, `installDist`, and each module's
`check` dependency. Run:

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.RenderPipelineArchitectureTest `
  --console=plain --no-daemon
.\gradlew.bat :game:test `
  --tests com.gaia.RenderArchitectureTest `
  --tests com.gaia.GameBootstrapStructureTest `
  --console=plain --no-daemon
```

Expected: assertions fail because the verification tasks do not yet exist.

- [ ] **Step 3: Add engine JAR shader verification**

In `engine/build.gradle`, register
`verifyPackagedShaderResources`, depend on `jar`, open the archive with
`java.util.zip.ZipFile`, and require:

```text
assets/overlord/shaders/world.vert
assets/overlord/shaders/world.frag
```

Make `check` depend on this task.

- [ ] **Step 4: Add installDist shader verification**

In `game/build.gradle`, register `verifyInstalledShaderResources`, depend on
`installDist`, locate exactly one `engine-*.jar` under
`build/install/game/lib`, and require the same two entries with
`ZipFile`. Fail if zero or multiple engine JARs match. Make `check` depend on
this task.

- [ ] **Step 5: Run focused tests and resource tasks**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.renderer.RenderPipelineArchitectureTest `
  --console=plain --no-daemon
.\gradlew.bat :game:test `
  --tests com.gaia.RenderArchitectureTest `
  --tests com.gaia.GameBootstrapStructureTest `
  --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources `
  --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources `
  --rerun-tasks --console=plain --no-daemon
```

Expected: all tests and both resource tasks pass.

- [ ] **Step 6: Commit Task 8**

```powershell
git add engine/build.gradle game/build.gradle `
  engine/src/test/java/com/overlord/renderer/RenderPipelineArchitectureTest.java `
  game/src/test/java/com/gaia/RenderArchitectureTest.java `
  game/src/test/java/com/gaia/GameBootstrapStructureTest.java
git commit -m "test(rendering): guard pipeline and packaged shaders"
```

---

### Task 9: Architecture contract, handoff, full verification, and owner review

**Files:**
- Create: `docs/architecture/phase-05a-render-contract.md`
- Create: `docs/agent-handoffs/phase-05a-handoff.md`
- Modify: `docs/architecture/current-baseline.md`
- Modify: `docs/superpowers/plans/2026-07-25-phase-5a-render-pipeline-core.md`

**Interfaces:**
- Documents the final contracts of Tasks 1 through 8 without changing
  production behavior.
- Records exact Windows/macOS/manual status and interfaces Phase 5B must not
  break.

- [x] **Step 1: Write the render contract**

Record:

- the exact 40-byte vertex table and face/light encoding;
- pass order and per-pass state;
- captured/restored state fields;
- shader resource locations and required uniforms;
- material GPU non-ownership;
- main-thread/worker boundaries;
- Chunk lifecycle interfaces that remain authoritative;
- transparent-queue limitations;
- Phase 5B deferred lighting/AO/sky/fog/gamma/culling work.

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

Parse both module XML directories and record exact suite, test, failure,
error, and skip counts. Do not infer counts from `BUILD SUCCESSFUL`.

- [x] **Step 3: Run final hygiene and scope checks**

```powershell
git diff --check origin/main
git status --short
git ls-files --others --exclude-standard
git diff --stat origin/main
```

Also verify:

- no tracked `build/`, `bin/`, `.class`, crash dump, screenshot, IDE, or local
  cache file;
- no platform-specific absolute JDK path;
- no game OpenGL import;
- no GLSL version above 410;
- no compute/SSBO symbol;
- no worldgen, physics, player, gameplay mutation, `ChunkRepository`, or
  `ChunkMeshManager` production diff;
- both shader files occur in the engine JAR and installed engine JAR.

- [x] **Step 4: Run Windows development interactive acceptance**

Start:

```powershell
.\gradlew.bat :game --console=plain --no-daemon
```

Inspect the approved Phase 4 plains/tree, hills, highlands/outcrop, and cave
entrance directions. Exercise resize, focus loss/restore, F1 cursor release,
movement, jump, and Escape. Record the actual Gradle/game exit code.

Stop instead of changing worldgen or effects if visual equivalence fails for
an unexplained reason.

- [x] **Step 5: Run Windows installDist acceptance**

Build and launch:

```powershell
.\gradlew.bat :game:installDist --console=plain --no-daemon
.\game\build\install\game\bin\game.bat
```

Confirm the same world renders, then Escape and record the launcher exit code.
If the distribution path differs, inspect Gradle's reported install directory
and document the actual generated launcher path; do not hard-code a developer
machine path in source or build files.

- [x] **Step 6: Record native macOS status**

On native macOS run:

```bash
./gradlew clean test build
./gradlew :game
```

Confirm GLSL 410 compilation, Retina framebuffer rendering, resize, focus,
F1, and Escape. If no native macOS environment is available, write
`macOS NOT RUN` in the handoff; do not claim cross-platform runtime success.

- [x] **Step 7: Perform branch-wide owner reviews**

Request:

- Engine-owner review of all `engine/**` production/tests, main-thread GPU
  ownership, OpenGL 4.1/GLSL 410 compatibility, state restoration, shader
  cleanup, and Chunk lifecycle preservation;
- Game/shared-owner review of asset composition, GameLoop migration, packaged
  resources, docs, scope exclusions, and visual/manual evidence.

Resolve every Critical, Important, and Minor finding through a focused
RED/GREEN cycle or an evidence-based documentation correction. Re-run the
complete relevant gate after any production change.

- [x] **Step 8: Create the Phase 5A handoff**

Include:

- completed and unfinished work;
- core architecture decisions;
- exact modified files;
- test commands, XML counts, and results;
- Windows development/installDist results and exit codes;
- macOS result or `NOT RUN`;
- known risks and transparent-queue limitation;
- Phase 5B interfaces that must not break;
- owner-review verdicts;
- `git diff --stat origin/main...HEAD`;
- suggested final commit and PR text.

Suggested final implementation commit:

```text
refactor(rendering): establish render passes materials and shader resources
```

Suggested PR title:

```text
refactor(rendering): add cross-platform render pipeline core
```

- [x] **Step 9: Commit Task 9**

```powershell
git add docs/architecture/phase-05a-render-contract.md `
  docs/architecture/current-baseline.md `
  docs/agent-handoffs/phase-05a-handoff.md `
  docs/superpowers/plans/2026-07-25-phase-5a-render-pipeline-core.md
git diff --cached --check
git commit -m "docs(rendering): record Phase 5A render contracts"
```

- [x] **Step 10: Final post-commit verification**

```powershell
git status --short
git rev-parse HEAD
git diff --stat origin/main..HEAD
```

Expected: clean status, a final HEAD on `feat/render-pipeline-core`, and no
push, pull request, or merge.

#### Task 9 execution status

- Steps 1 through 6 and Step 8 are complete.
- Post-fix implementation HEAD:
  `e603946cbb00e42c0dba097796f21f745c4d5683`.
- Windows automated gate: 60 Engine suites / 573 tests and 29 Game suites /
  249 tests, totaling 89 suites / 822 tests with 0 failures, 0 errors, and
  0 skipped.
- All four required Gradle commands passed, including standalone packaged,
  engine-JAR shader, and installed-engine-JAR shader checks.
- Hygiene, JDK-path, game-OpenGL, OpenGL/GLSL-version, compute/SSBO,
  engine-to-game, and protected production-diff audits found no prohibited
  match.
- Valid serial Windows development acceptance passed with
  `dev-game.exit=0`; the installDist launcher passed with
  `install-game.exit=0`. Screenshots were inspected in-app only and not
  committed. Cave entrance re-navigation remained waived by the user.
- Owner finding `0fe593b` restores the incoming state when pass-state apply
  fails; owner finding `e603946` adds both shader resource identities to
  missing-uniform diagnostics. Both are failure-path-only fixes with focused
  regressions and a complete green post-fix gate. They do not change normal
  rendering/input behavior, so the valid GUI exit-0 evidence was retained
  without another GUI run.
- Native macOS automated and interactive acceptance is **NOT RUN**.
- Step 7 is complete. Engine-owner and Game/shared-owner final re-reviews are
  **APPROVED**, each with no remaining Critical, Important, or Minor finding.
  Engine confirmed the `0fe593b` and `e603946` findings closed; Game/shared
  confirmed the `Renderer.renderFrame` documentation Minor closed.
- Task 9 documentation is finalized for the controller's documentation commit.
- Steps 9 and 10 are completed by that commit and the immediately following
  clean-status, final-HEAD, and branch-diff verification recorded in the final
  delivery.
