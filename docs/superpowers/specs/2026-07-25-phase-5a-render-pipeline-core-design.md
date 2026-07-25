# Phase 5A Render Pipeline Core Design

Date: 2026-07-25

Branch: `feat/render-pipeline-core`

Base: `origin/main` at
`647d91d5fcab15a0acdd60e7898729e35182f71e`

## Goal

Refactor the current direct world renderer into a reusable, explicitly
state-owned render pipeline while preserving the Phase 3 independent-Chunk
mesh lifecycle and the current Phase 4 visual result. Move shader source into
JAR-safe resources, migrate voxel meshes to one versioned vertex contract,
and establish minimal material, queue, and pass boundaries for later visual
work.

Phase 5A is an architecture and vertex-format migration. It does not add
lighting, ambient occlusion, fog, sky gradients, gamma correction, frustum
culling, UI, dynamic shadows, PBR, deferred rendering, or SSAO.

## Baseline and prerequisites

The repository has no `README.md`; repository guidance comes from
`AGENTS.md`, the current architecture baseline, and the Phase 4 handoff.
Phase 4 is merged into `origin/main`, and this branch starts at the same
commit.

The current renderer:

- embeds GLSL source in `Renderer`;
- uses one `Shader`, one texture atlas, and one five-float position/UV mesh;
- keeps one independent `ChunkRenderObject` per loaded Chunk;
- uploads and releases Chunk GPU meshes through `ChunkMeshManager` and
  `ChunkRenderBackend`;
- guards renderer, mesh, shader, texture, window, and GPU lifecycle operations
  with `MainThreadGuard`;
- renders the approved version-2 Phase 4 terrain and data-driven Gaia block
  resources.

The existing `AssetManager`, `ResourceLocation`, `MaterialDefinition`,
`RenderType`, texture atlas metadata, and asset diagnostics remain the only
resource and material identity system.

## Non-negotiable boundaries

- Keep Java 17 source and target compatibility; builds may run on JDK 21.
- Use the checked-in Gradle Wrapper without a platform-specific JDK path.
- Keep OpenGL compatible with macOS OpenGL 4.1 and GLSL `#version 410 core`.
- Do not use compute shaders, SSBOs, OpenGL 4.2+ functions, or
  platform-exclusive graphics APIs.
- Every OpenGL call and GPU create, upload, draw, state change, and release
  must run on the context-owning main thread through `MainThreadGuard`.
- Preserve `ChunkRenderObject`, `ChunkMeshManager`, repository revision
  checks, stale-result rejection, unload cleanup, and independent Chunk GPU
  ownership.
- Keep material and shader resource identity based on Phase 2
  `ResourceLocation`; do not create a second resource or material registry.
- Keep `engine` independent of `game`. `game` may compose engine rendering
  APIs but must not call OpenGL directly.
- Do not modify world generation, player physics, gameplay interaction, or UI.
- Do not copy code or resources from Terasology, Create, or another project.

## Selected migration approach

Use an incremental pipeline replacement around the existing `Renderer`.
`Renderer` remains the frame coordinator and `ChunkRenderBackend`; focused
classes take over shader compilation, runtime material binding, render queue
collection, pass execution, and state restoration.

This approach is preferred over a full renderer/frame-graph rewrite because
Phase 5A must preserve visual equivalence and the existing Chunk lifecycle. It
is also preferred over superficial pass wrappers because the vertex contract,
state isolation, diagnostics, and queue ownership need to be real,
independently testable boundaries before Phase 5B.

## Architecture and frame flow

`Renderer` initializes shared GPU resources from injected render assets on the
main thread:

1. load the engine-owned world shader sources through `AssetManager`;
2. compile and link one `ShaderProgram`;
3. upload one shared block-atlas `Texture`;
4. create one runtime world `Material` using the Phase 2 material identity;
5. create the ordered pass list and reusable frame `RenderQueue`.

The frame flow is:

```text
Renderer begins frame
  -> collect current ChunkRenderObjects into RenderQueue
  -> SkyRenderPass
  -> WorldRenderPass
       -> opaque queue in insertion order
       -> transparent queue in insertion order
  -> DebugRenderPass
  -> clear RenderQueue in a finally block
```

`SkyRenderPass` only clears color and depth with the existing flat background
color. `WorldRenderPass` applies camera and model transforms and draws current
Chunk meshes. `DebugRenderPass` is disabled and emits no draw calls in Phase
5A. The explicit pass objects are future extension boundaries, not permission
to add Phase 5B effects.

`Renderer.upload` and `Renderer.release` continue to implement
`ChunkRenderBackend`. `ChunkMeshManager` continues to decide when CPU results
are current, when upload may run, when a render object becomes installed, and
when an old GPU object is released.

## Vertex format contract

`VoxelVertexFormat` is the single source of truth for vertex stride,
attribute locations, offsets, component counts, and face/light encoding.

The interleaved layout is ten floats and 40 bytes per vertex:

| Location | Field | GLSL type | Float offset | Byte offset |
| ---: | --- | --- | ---: | ---: |
| 0 | position | `vec3` | 0 | 0 |
| 1 | UV | `vec2` | 3 | 12 |
| 2 | normal | `vec3` | 5 | 20 |
| 3 | face/light | `float` | 8 | 32 |
| 4 | ambient occlusion | `float` | 9 | 36 |

Face IDs are explicit stable constants and never use enum ordinals:

```text
NORTH=0, SOUTH=1, UP=2, DOWN=3, WEST=4, EAST=5
```

The scalar face/light encoding is:

```text
encodedFaceLight = faceId * 16 + lightLevel
```

Both operands are integral values exactly representable as a float. Phase 5A
uses real face IDs, face-normal unit vectors, `lightLevel = 15`, and
`ambientOcclusion = 1.0`. The Phase 5A shaders consume position and UV for
visual equivalence; the other fields establish a stable Phase 5B input
contract.

`ChunkMeshBuilder` emits the layout. `ChunkMeshData` validates vertex-array
length and computes vertex count and local bounds through
`VoxelVertexFormat`. `Mesh` configures its VAO from the same format. No class
retains a separate hard-coded stride.

The migration does not merge Chunk meshes, change Chunk-local positions,
change `ChunkRenderObject` transforms or bounds, or create GPU resources on
meshing workers.

## Shader resources and diagnostics

The world shaders are engine-owned JAR resources:

```text
engine/src/main/resources/assets/overlord/shaders/world.vert
engine/src/main/resources/assets/overlord/shaders/world.frag
```

Their resource identities are:

```text
overlord:shaders/world.vert
overlord:shaders/world.frag
```

`ShaderResourceLoader` calls the existing
`AssetManager.readUtf8(ResourceLocation)` and returns an immutable
`ShaderSourceSet` containing the program label, both resource locations, and
both source strings. Missing, ambiguous, or unreadable resources continue to
use the Phase 2 `AssetLoadException` and `AssetDiagnostic` model.

`ShaderProgram` replaces the old `Shader`; there is no parallel compatibility
API because the old class has no production consumer outside `Renderer`.
Creation, use, uniform upload, and cleanup assert the main thread.

Compilation failures report:

- program label;
- vertex or fragment stage;
- exact `ResourceLocation`;
- the OpenGL compiler log.

Link failures report the program label, both resource locations, and the
OpenGL linker log. Any partially created shader or program object is deleted
before the failure escapes.

At successful link time, `ShaderProgram` resolves and caches the required
world uniform set:

```text
projection
view
model
textureAtlas
```

A location of `-1` produces `ShaderProgramException` containing the program
label, resource paths, and missing uniform name. Per-frame uniform uploads use
the cached locations and do not call `glGetUniformLocation`.

A package-private shader GL backend permits deterministic compile, link,
uniform, cleanup, and thread-guard tests without constructing a platform
OpenGL context. The production backend is the only implementation that calls
LWJGL.

## Runtime material contract

`RenderAssets` carries:

- the block-atlas `TextureImage`;
- the selected Phase 2 `MaterialDefinition`;
- the two world shader `ResourceLocation` values.

Production Gaia composition selects the existing `gaia:opaque` material.
Missing/fallback engine construction uses an explicit engine-owned missing
material identity without registering a second material collection.

`Material` is an immutable runtime binding:

- its identity and render type come from `MaterialDefinition`;
- it references the shared `ShaderProgram`;
- it references the shared atlas `Texture`;
- it does not create or destroy either GPU resource.

`Renderer` owns the shared program and texture and releases them once, in
reverse initialization order. `Material` cannot independently clean them up.

The current production world uses one opaque Chunk mesh. Phase 5A does not
split `ChunkMeshData` or `ChunkRenderObject` by material because there is no
current transparent production block and doing so would expand the Phase 3
lifecycle migration. The queue API is ready for a later material-layer
extension without claiming that transparent geometry is already supported
end-to-end.

## Render queue and pass contract

`RenderItem` is an immutable pair of `ChunkRenderObject` and `Material`.
`RenderQueue` owns separate opaque and transparent lists and exposes read-only
snapshots to passes. Submission order is stable and is the only ordering rule
in Phase 5A.

Current Chunk objects are submitted with the selected opaque world material.
The transparent submission API exists independently but Phase 5A does not
generate transparent Chunk sections or perform camera-distance sorting.

`RenderPass` receives a read-only `RenderContext` and the current queue. The
production pass order is an immutable list:

1. `SkyRenderPass`;
2. `WorldRenderPass`;
3. `DebugRenderPass`.

`Renderer` clears the queue in a `finally` block after pass execution so a
failed pass cannot retain unloaded Chunk references into the next frame.

## OpenGL state scope

`OpenGlRenderStateBackend` is the production state boundary. It asserts the
main thread for every operation. `RenderStateSnapshot` captures:

- depth-test enablement;
- depth write mask;
- blend enablement;
- blend RGB/alpha source factors, destination factors, and equations;
- cull-face enablement;
- current shader program;
- active texture unit;
- texture unit 0 `GL_TEXTURE_2D` binding.

`RenderStateScope implements AutoCloseable`. It captures the incoming
snapshot, applies the pass state, and restores the exact incoming state once
on close. Pass execution always uses try-with-resources, including exceptional
paths.

The Phase 5A pass states are:

- Sky: depth off, depth writes on, blend off, cull off; clear color/depth
  only.
- World opaque: depth on, depth writes on, blend off, cull off, world program
  and atlas bound.
- World transparent API: depth on, depth writes off, blend on; stable
  insertion order and no distance sorting. It uses standard source-alpha /
  one-minus-source-alpha blending and restores the incoming factors and
  equations afterward.
- Debug: disabled, no draw and no state mutation.

Cull remains off because the current renderer does not enable it and Phase 5A
must preserve the existing terrain result. Phase 5B may deliberately enable
it only with winding tests and visual approval.

A fake state backend tests capture, apply, normal restoration, exceptional
restoration, and close idempotence without OpenGL.

## Thread and ownership rules

- `Renderer.init`, frame clear/pass execution, queue submission, VAO/VBO
  upload, texture/program creation, uniform upload, draw, state changes, and
  cleanup run on the captured main/context thread.
- `ChunkMeshBuilder` and detached world generation remain CPU-only and may run
  on workers.
- Workers may produce `ChunkMeshData` using `VoxelVertexFormat`; they cannot
  create `Mesh`, `ShaderProgram`, `Texture`, `Material` GPU bindings, or
  `RenderStateScope`.
- `game` composes assets and submits existing engine objects. It does not
  import or call `org.lwjgl.opengl`.
- No new `ServiceLocator` registration is introduced. Dependencies are
  constructor-injected through `Engine`, `Renderer`, `RenderAssets`, and pass
  contexts.

## Error handling and cleanup

- Shader asset errors preserve Phase 2 diagnostic codes and resource IDs.
- Shader compile, link, and uniform errors use `ShaderProgramException` with
  the program label, stage or uniform, resource IDs, and OpenGL log.
- Renderer initialization tracks completed resources and releases them in
  reverse order on failure.
- `RenderStateScope` restores state before propagating pass failures.
- `RenderQueue` clears in `finally` before a render failure escapes.
- Chunk upload failure cleans a partially created `Mesh` and does not replace
  the installed `ChunkRenderObject`.
- Cleanup is idempotent and remains on the main thread.

## Automated verification

Focused tests cover:

- shader resources loaded from normal classpaths and temporary JARs;
- missing shader resources and exact resource diagnostics;
- fake-backend shader compilation, link, uniform, cleanup, and
  `MainThreadGuard` failures;
- exact vertex stride, offsets, locations, normals, face/light encoding, AO
  default, vertex count, and bounds;
- `ChunkMeshBuilder` output compatibility across faces and Chunk boundaries;
- queue routing, stable order, immutable views, and frame clearing;
- pass order and normal/exceptional state restoration;
- Renderer/Chunk backend ownership and cleanup behavior;
- architecture guards forbidding direct OpenGL use from `game`, worker
  generation, and CPU meshing;
- shader entries in the engine JAR and in the engine JAR copied into
  `game installDist`.

Full Windows automation remains:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources `
  --rerun-tasks --console=plain --no-daemon
```

Resource verification additionally builds `installDist` and checks the
distributed engine JAR for both shader entries.

## Manual acceptance

Windows:

- run `.\gradlew.bat :game --console=plain --no-daemon`;
- run the `installDist` launcher;
- confirm the approved Phase 4 world remains visually equivalent;
- verify resize, focus restoration, F1 cursor release, and Escape shutdown;
- record both process exit codes.

macOS:

- run `./gradlew clean test build`;
- run `./gradlew :game`;
- confirm native GLSL 410 compilation and Retina framebuffer rendering;
- verify the world is not black and input/resize/shutdown remain functional.

If no native macOS environment is available, the handoff records macOS as
`NOT RUN`; Windows or automated results must not be used to infer it.

## Stop conditions

Stop and request direction if:

- visual equivalence fails for an unexplained reason;
- a shader requires GLSL above 410 or an OpenGL API above 4.1;
- implementation would require changing `ChunkRepository`,
  `ChunkMeshManager`, independent `ChunkRenderObject` ownership, world
  generation, player behavior, or gameplay mutation;
- shader or material loading would require a second registry;
- a new visual effect is required to make the architecture work.

## Deliverables

- this Phase 5A design;
- a detailed TDD implementation plan;
- engine-owned shader resources;
- the unified vertex format contract;
- shader/material/queue/pass/state-scope implementation and tests;
- packaged-resource verification for engine JAR and `installDist`;
- `docs/architecture/phase-05a-render-contract.md`;
- `docs/agent-handoffs/phase-05a-handoff.md`;
- final Engine-owner and Game/shared-owner reviews.

No push, pull request, or merge is part of the implementation task.
