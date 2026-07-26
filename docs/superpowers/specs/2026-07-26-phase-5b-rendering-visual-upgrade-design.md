# Phase 5B Rendering Visual Upgrade Design

Status: approved conversational design, written for repository review

Date: 2026-07-26

Branch: `feat/rendering-visual-upgrade`
Base: `origin/main` at `438859d722efb58349ada6d2100fc84f1556780c`

## Goal

Add Milestone 1 visual depth, texture stability, conservative Chunk frustum
culling, immutable render metrics, and complete render-surface propagation on
top of the approved Phase 5A pipeline. The work must preserve deterministic
world bytes, gameplay mutation semantics, independent Chunk GPU ownership,
worker/main-thread separation, Java 17, OpenGL 4.1, and GLSL 410.

Phase 5B does not implement a HUD. Metrics are exposed as immutable snapshots
and may be observed by an explicitly enabled console reporter only.

## Repository baseline

Phase 5A is merged through PR #12 at `438859d`. It provides:

- engine-owned JAR-safe world shaders;
- a ten-float / 40-byte voxel vertex format;
- non-owning runtime materials;
- `sky`, `world`, `debug` pass order;
- exact pass-state capture/restoration;
- one independent `ChunkRenderObject` per non-empty renderable Chunk;
- main-thread guarded shader, texture, mesh, pass, upload, draw, and cleanup;
- center plus four-cardinal immutable CPU meshing snapshots;
- repository-issued revisions and stale-result rejection.

The repository does not contain a README file at this baseline. `AGENTS.md`,
all current architecture documents, the Phase 3 handoff, the Phase 5A render
contract, the Phase 5A handoff, and the latest fifteen `origin/main` commits
were inspected before this design was written.

## Scope boundaries

### Required

- directional light, ambient light, and face-direction shading;
- standard three-sample vertex ambient occlusion;
- immutable center-plus-eight-neighbor horizontal meshing input;
- diagonal Chunk invalidation and stale-work rejection;
- distance fog and a real sky gradient;
- one explicit manual sRGB decode/linear-light/encode path;
- nearest-only, level-zero-only atlas sampling with half-texel UV inset;
- conservative world-space AABB frustum culling;
- immutable `RenderMetricsSnapshot` values;
- logical size, framebuffer size, and content-scale propagation;
- packaged world and sky shader verification;
- focused automated tests and Windows interactive comparison evidence.

### Explicit non-goals

- `DebugHud`, text rendering, crosshair, inventory UI, or any other HUD;
- dynamic shadows, shadow maps, PBR, deferred rendering, SSAO, or GPU
  particles;
- transparent section generation or sorting;
- mipmap generation or sampling;
- build-time padded-atlas generation;
- LOD, distance streaming, or automatic Chunk unload;
- changes to world generation, block definitions, mutation transactions,
  physics, player behavior, inventory, world items, or interaction gameplay.

## Selected implementation approach

Use a controlled vertical upgrade of the existing Phase 5A architecture.
Small immutable CPU values are added for visual settings, frame input, surface
metrics, frustum planes, and metrics snapshots. Existing passes gain only the
dependencies needed for their work. The repository remains the sole Chunk
revision/dirty authority and the mesh manager remains the sole CPU/GPU mesh
lifecycle authority.

Rejected alternatives:

- shader-only hard-coded behavior cannot correctly supply diagonal AO,
  culling, surface propagation, or testable metrics;
- a new render graph, dynamic material system, or padded-atlas builder would
  exceed Milestone 1 scope;
- a sky box adds assets and geometry that are unnecessary for the approved
  gradient;
- framebuffer sRGB would create a second gamma path and is forbidden;
- bounding-sphere culling is less precise than the already available Chunk
  AABBs.

## Immutable visual settings

`RenderVisualSettings` is an immutable engine value injected into `Renderer`.
It is not registered in `ServiceLocator` and is not stored in
`MaterialDefinition`. It owns defensive scalar/color copies for:

- normalized direction from the surface toward the sun;
- ambient-light strength;
- directional-light strength;
- linear-space sky-top color;
- linear-space sky-horizon color;
- linear-space fog color;
- fog start distance;
- fog end distance;
- the explicit shader sRGB policy.

`LinearColor` is an immutable three-component value. Components must be finite
and in `[0, 1]`. Light strengths must be finite and non-negative. Fog distances
must be finite, non-negative, and satisfy `fogEnd > fogStart`. The light
direction must be finite and non-zero before normalization.

`RenderVisualSettings.milestoneOneDefaults()` starts with:

- surface-to-sun direction `(-0.45, 0.85, -0.30)`, normalized;
- ambient strength `0.38`;
- directional strength `0.72`;
- fog start `64.0` blocks;
- fog end `160.0` blocks;
- linear sky-top color `(0.035, 0.160, 0.470)`;
- linear sky-horizon color `(0.350, 0.570, 0.780)`;
- linear fog color `(0.350, 0.570, 0.780)`.

The exact default colors may be tuned during the Phase 5B Windows visual
review without changing any architecture or ownership boundary. The final
values and review coordinates must be recorded in the handoff.

## Shader and pass architecture

### Resources

The existing world shader identities remain:

```text
overlord:shaders/world.vert
overlord:shaders/world.frag
```

Phase 5B adds engine-owned JAR-safe sky resources:

```text
overlord:shaders/sky.vert
overlord:shaders/sky.frag
```

`RenderAssets` carries both sky identities with engine defaults. All four
resources are loaded through `ShaderResourceLoader` and `AssetManager`.
Compile, link, uniform, and cleanup failures retain program labels, stages,
resource identities, primary failures, distinct suppressed cleanup failures,
and self-suppression protection from Phase 5A.

`ShaderBinding`, `ShaderBackend`, `OpenGlShaderBackend`, and `ShaderProgram`
gain only the finite scalar and three-component vector uniform operations
needed by the approved settings. Required uniforms remain resolved and cached
at link time.

### World shader

The 40-byte vertex layout and locations stay unchanged. The vertex shader:

- transforms position using `projection * view * model`;
- transforms the existing unit normal for the translation-only Chunk model;
- decodes the low four-bit light level from `aFaceLight`;
- passes AO, normal, UV, and view-space distance to the fragment shader.

The fragment shader performs exactly this order:

1. sample atlas RGBA;
2. decode sampled sRGB RGB to linear RGB with the standard piecewise sRGB
   transfer function;
3. compute `ambient + directional * max(dot(normal, sunDirection), 0)`;
4. multiply linear RGB by clamped lighting, decoded light level, and AO;
5. compute smooth distance fog between `fogStart` and `fogEnd`;
6. mix with the linear fog color;
7. encode the final linear RGB to sRGB with the standard piecewise output
   transfer function;
8. preserve sampled alpha.

Face-direction shading comes from the existing face normal. Stable face IDs
and `faceId * 16 + lightLevel` encoding remain unchanged. Phase 5B still emits
light level 15 because no block-light propagation system exists in this
milestone.

### Sky pass

`SkyRenderPass` remains first. It clears color and depth, then draws one
fullscreen triangle using a GLSL 410 vertex shader based on `gl_VertexID` and
one Renderer-owned empty VAO. No VBO is needed.

The sky state is depth-test off, depth-write off, blend off, and cull off.
The fragment shader interpolates linear horizon and top colors by normalized
screen Y and applies the same linear-to-sRGB output function as the world
shader. The sky draw is one draw call and one triangle in RenderMetrics.

`DebugRenderPass` remains disabled. Pass order remains `sky`, `world`,
`debug`. All pass states continue to use the exact Phase 5A scope and restore
the incoming state on normal, exceptional, and apply-failure paths.

### Gamma invariant

The atlas uses a linear OpenGL internal format and is treated as sRGB content
only by the shader. Renderer initialization explicitly disables
`GL_FRAMEBUFFER_SRGB`. No production path may enable it. Automated structure
and shader tests must prove that the only active path is shader decode plus
shader encode; double gamma is a stopping condition.

## Texture sampling and atlas stability

The existing atlas PNG and JSON are preserved. No pixels or third-party assets
are added.

Texture upload uses:

- `GL_NEAREST` minification;
- `GL_NEAREST` magnification;
- `GL_TEXTURE_BASE_LEVEL = 0`;
- `GL_TEXTURE_MAX_LEVEL = 0`;
- existing clamp-to-edge wrapping;
- no `glGenerateMipmap` call.

A small injectable texture backend/policy boundary makes these calls
observable without a live context. Production OpenGL calls remain guarded on
the main thread.

`TextureRegion` returns inset sampling bounds:

```text
uMin = (x + 0.5) / atlasWidth
uMax = (x + width - 0.5) / atlasWidth
vMin = (y + 0.5) / atlasHeight
vMax = (y + height - 0.5) / atlasHeight
```

A one-pixel region collapses to its pixel center and remains valid. Metadata
continues to validate positive dimensions and in-atlas bounds. Mipmaps remain
forbidden until a separate approved build-time padded-atlas migration verifies
every mip level.

## Immutable 3x3 Chunk meshing input

`ChunkMeshInput` becomes a fixed immutable nine-snapshot value:

```text
northWest  north  northEast
west       center east
southWest  south  southEast
```

Construction validates:

- non-null center;
- exact expected key for every supplied neighbor;
- equal world height across all nine snapshots;
- immutable empty snapshots with the expected key for missing neighbors.

`getBlock(localX, y, localZ)` routes center, cardinal, and diagonal reads.
Horizontal coordinates may extend at most one block beyond the center range,
which is the complete AO sampling requirement. A larger horizontal offset is
a programming error. Vertical coordinates outside the world return air.

`ChunkRepository.claimMeshing` captures all nine snapshots without holding
entry locks during CPU mesh construction. It then rechecks the center entry,
state, and claimed revision before publishing the input. Workers consume only
`ChunkMeshInput`, `BlockRenderResolver`, and CPU values; they never read
`World`, `ChunkRepository`, mutable `Chunk`, GLFW, LWJGL, OpenGL, or GPU
resources.

## Ambient-occlusion algorithm

For each vertex of each visible face, the mesher samples three cells one block
outside that face:

- the first tangent-side neighbor;
- the second tangent-side neighbor;
- their diagonal corner.

Only renderable non-transparent blocks occlude. If both side neighbors
occlude, the vertex uses the darkest level independent of the corner.
Otherwise the AO level is `3 - side1 - side2 - corner`.

Levels map to the existing float attribute as:

| AO level | Vertex multiplier |
| ---: | ---: |
| 0 | 0.45 |
| 1 | 0.65 |
| 2 | 0.82 |
| 3 | 1.00 |

The existing triangle split and winding remain unchanged. AO does not change
the vertex stride, attribute locations, block bytes, texture identity, face
visibility rule, or Chunk bounds.

## Dirty propagation and stale-result ownership

`ChunkRepository` remains the only revision and dirty authority. The
repository's existing `ChunkDirtyTracker` is extended rather than duplicated.

Rules:

- an interior block change dirties only its target Chunk;
- an edge block change also dirties the loaded cardinal neighbor across that
  edge;
- a corner block change also dirties the loaded diagonal neighbor across that
  corner;
- initial generation publication and unload dirty all currently loaded eight
  horizontal meshing neighbors;
- rebuild compares the four edge planes for cardinal invalidation and the
  four vertical corner columns for diagonal invalidation;
- no missing neighbor is allocated merely to dirty it;
- every actual dirty transition receives a repository-issued revision;
- `ChunkMutationOutcome` and subsequent Phase 7 events report the exact
  repository outcomes, including a diagonal revision when applicable.

A cardinal or diagonal change that can alter target AO advances the target
revision. Existing checks after snapshot capture, CPU completion, before
upload budget accounting, immediately before upload, and after upload reject
stale work. Phase 5B creates no second invalidation map and does not bypass
`ChunkMeshManager` replacement or unload cleanup.

## Frustum culling

`Frustum` is an immutable pure-CPU value containing six normalized
`FrustumPlane` values extracted from `projection * view`:

- left;
- right;
- bottom;
- top;
- near;
- far.

Planes reject non-finite or non-normalizable coefficients. AABB testing uses
the positive vertex for each plane. A bound touching a plane, intersecting a
plane, or lying within a `0.01` world-unit conservative epsilon remains
visible.

Each visible frame builds the frustum from the current projection and Camera
view, filters `ChunkRenderObject.worldBounds()`, and submits only visible
objects to `RenderQueue`. Culling never calls unload, changes a Chunk state,
alters a revision, removes an installed object, or affects mesh scheduling.

## Render metrics

The public read boundary is:

```java
public interface RenderMetrics {
    RenderMetricsSnapshot snapshot();
}
```

`RenderMetricsSnapshot` is an immutable record containing:

```text
double framesPerSecond
double frameTimeMilliseconds
int visibleChunks
int drawCalls
long triangles
int meshQueueDepth
```

All values are finite and non-negative. The first frame, whose FrameClock
delta is zero, reports zero FPS and frame time. FPS is instantaneous
`1 / frameDeltaSeconds`; no smoothing or second metric is added.

`RenderFrameInput` defensively copies the submitted Chunk collection and
carries the full GameLoop frame delta plus the mesh queue depth. Every caller
migrates to the single `renderFrame(RenderFrameInput)` path.

Per-frame accounting:

- reset visible, draw, and triangle counters at frame start;
- set visible count after frustum filtering;
- count one draw and one triangle only after a successful sky draw;
- count one draw and `vertexCount / 3` only after each successful Chunk draw;
- publish actual completed counts in `finally`, including exceptional frames;
- expose only the last immutable snapshot.

`ChunkMeshManager.meshQueueDepth()` is a main-thread read-only snapshot of:

- claimed/in-flight CPU mesh work;
- completed CPU results waiting for main-thread drain;
- mesh data waiting for GPU upload;
- failed upload payloads retained for explicit retry.

The count is observational only and cannot schedule, retry, upload, unload, or
change repository state.

## Optional console reporting

`RenderMetricsConsoleReporter` is a separate observer, not part of Renderer
and not a UI. `GameBootstrap` enables it only when the explicit JVM property
`gaia.renderMetrics=true` is present. `GameLoop` offers the last snapshot after
each rendered frame. The reporter emits at most once per second using an
injected monotonic clock and output sink, and is independently testable.

The console line records FPS, frame milliseconds, visible chunks, draw calls,
triangles, and mesh queue depth. Disabled reporting produces no output and no
per-frame allocation beyond the metrics snapshot already required.

## Render-surface propagation

`RenderSurfaceMetrics` is an immutable value containing:

```text
int logicalWidth
int logicalHeight
int framebufferWidth
int framebufferHeight
float contentScaleX
float contentScaleY
```

Dimensions are non-negative. Content scales are finite and positive.

`Window` reads initial logical size, framebuffer size, and content scale after
the OpenGL context is current. `WindowMetrics` coalesces window-size,
framebuffer-size, and content-scale callbacks into the latest complete
snapshot. After one `pollEvents`, GameLoop and the engine demo consume at most
one snapshot and pass it to Renderer.

Renderer uses only framebuffer width/height for `glViewport` and perspective
aspect ratio. Logical dimensions and content scale are retained as explicit
layout inputs for metrics and future UI but do not alter the world projection.

A zero framebuffer dimension is the explicit minimized/non-drawable state:

- do not call `glViewport` with a drawable projection update;
- retain the last valid projection;
- skip sky/world/debug execution;
- publish a zero-visible, zero-draw metrics frame;
- keep polling and processing CPU/main-thread lifecycle work.

The next positive framebuffer snapshot atomically restores viewport,
projection, and rendering with the new content-scale values.

## Frame data flow

For every GameLoop iteration:

1. `FrameClock.tick()` produces the clamped full-frame delta.
2. GLFW events are polled on the main thread.
3. The latest coalesced `RenderSurfaceMetrics`, if any, is passed to Renderer.
4. Input, loading, fixed updates, and mesh lifecycle work retain their existing
   ordering.
5. `ChunkMeshManager.meshQueueDepth()` and the current independent render
   objects are copied into `RenderFrameInput`.
6. Renderer starts metrics, handles zero-framebuffer pause, constructs the
   current frustum, and submits visible objects only.
7. Pipeline executes `sky`, `world`, `debug` with exact state scopes.
8. Renderer publishes the immutable metrics snapshot in `finally`.
9. The optional reporter observes the snapshot.
10. Window buffers are swapped and queue references are already cleared.

## Thread and lifecycle rules

The context-owning main thread continues to own:

- GLFW callbacks, event polling, surface-snapshot consumption, and swap;
- Renderer initialization, resize, frame execution, and cleanup;
- world and sky shader compile/link/use/uniform upload/delete;
- atlas create/parameter/upload/bind/delete;
- fullscreen VAO create/draw/delete;
- Chunk VAO/VBO upload/draw/release;
- state capture/apply/restore and framebuffer-sRGB disable;
- frustum submission, metrics publication, and mesh-queue observation.

Workers may capture no live world reference and may perform only CPU mesh
construction from immutable nine-Chunk input. `RenderMetricsSnapshot`,
`RenderVisualSettings`, `RenderSurfaceMetrics`, and frustum values are
immutable, but that does not grant workers permission to invoke Renderer.

Renderer owns the world program, sky program, atlas texture, and fullscreen
geometry. Partial initialization and normal cleanup release completed GPU
resources in reverse ownership order. The first failure remains primary;
distinct later cleanup failures are suppressed and same-instance failures are
not self-suppressed.

## Error handling

- Invalid settings, colors, planes, frame deltas, queue depths, dimensions,
  or scales fail at their value boundary with a field-specific message.
- Missing or invalid sky/world shader resources use the Phase 5A diagnostic
  path and identify the exact `ResourceLocation`.
- A null Chunk in `RenderFrameInput` is rejected while queue and metrics
  cleanup remain protected by `finally`.
- Frustum construction never silently disables culling after invalid math.
- A render/pass/OpenGL failure is propagated; metrics publish only work that
  actually completed before the failure.
- A zero framebuffer is not an error and does not destroy GPU resources.
- Culling and metrics do not catch or conceal mesh lifecycle failures.

## Automated test contract

### Chunk snapshot, AO, and lifecycle

- nine expected keys, equal height, immutable snapshots, missing-neighbor air;
- cardinal and diagonal lookup at positive and negative Chunk coordinates;
- reject reads farther than the one-block AO halo;
- all four AO levels and the two-side darkest rule;
- AO on every face orientation;
- diagonal AO at each Chunk corner;
- corner mutation dirties target, two cardinals, and one diagonal;
- non-corner edge mutation does not dirty a diagonal;
- initial publication and unload invalidate loaded eight-neighbor meshes;
- rebuild diagonal invalidation occurs only for changed corner columns;
- diagonal mutation/unload during CPU work makes the target result stale;
- no mesher production dependency on `World` or mutable Chunk storage.

### Visual pipeline and texture

- settings validation and defensive values;
- GLSL version exactly 410 for all four resources;
- required world and sky uniform diagnostics;
- standard sRGB decode and encode functions occur on the intended paths;
- production never enables `GL_FRAMEBUFFER_SRGB`;
- sky pass clears, draws once, counts once, and restores state;
- world pass uploads settings and counts only successful draws;
- texture fake backend observes nearest filters, level-zero bounds, and no
  mipmap generation;
- half-texel coordinates for 1x1, normal, and edge atlas regions;
- engine JAR, game JAR, and installDist contain all shader/atlas resources.

### Frustum, metrics, and surface

- normalized plane construction and invalid plane rejection;
- AABBs inside, outside, intersecting, touching, and inside epsilon;
- camera rotation changes the visible set without changing installed objects;
- immutable metrics snapshot and finite/non-negative validation;
- counters reset every frame and exception frames publish actual completed
  work;
- delta zero and positive delta FPS/frame-time behavior;
- mesh queue depth tracks deterministic in-flight/upload/retry transitions;
- callback coalescing preserves logical, framebuffer, and scale values;
- projection uses framebuffer aspect only;
- zero framebuffer skips draws and positive restore resumes rendering;
- Game and worker architecture guards prohibit direct OpenGL and mutable-world
  meshing.

## Manual acceptance

Windows development and installDist runs use seed `12345` and the approved
Phase 4 version-2 coordinates:

- plains tree `(-33, 33)`;
- rolling-hills tree `(0, 12)`;
- rocky outcrop `(-50, 8)`;
- cave entrances `(-22, 24, 3)` and `(-6, 23, -21)`;
- deep cave `(67, 2, -64)`;
- cross-Chunk tunnel `(48, 57, -45)`.

Check:

- sky top/horizon gradient and fog merge;
- top/side/down face hierarchy and cave/interior AO depth;
- no atlas bleeding at grass, stone, log, and leaf edges;
- camera rotation changes visible Chunk count without nearby false removal;
- no unload or remesh occurs merely because an object is culled;
- safe spawn, movement, jump, collision, F1, resize, focus loss/restore,
  Alt+Tab, and Escape remain intact;
- Windows 100% and 150% display scaling where available;
- development and installDist exit codes are captured.

Record Phase 5A and Phase 5B at the same seed, position, orientation, window
size, and display scale. Phase 5A FPS may be collected with a temporary,
uncommitted console probe before implementation; it must be removed and the
clean baseline restored before production work. Phase 5A draw calls may also
be derived from the exact number of submitted independent render objects,
because Phase 5A performs no sky geometry draw and no culling. Phase 5B values
come from the approved immutable metrics snapshot/reporter.

Native macOS must run `./gradlew clean test build` and `./gradlew :game` when a
native environment is available, including GLSL compilation and Retina
resize. Otherwise the handoff must state `NOT RUN` and must not infer success
from Windows.

## Stopping conditions

Stop and request design revision if:

- AO needs a mutable `World` read or a second dirty/revision authority;
- any diagonal mutation, generation, rebuild, or unload path can leave an
  accepted stale mesh;
- mipmap generation/sampling is introduced without a separately approved
  padded-atlas migration;
- shader gamma and framebuffer sRGB are simultaneously active;
- culling unloads Chunks or removes nearby valid geometry;
- a required shader needs GLSL above 410 or OpenGL above 4.1;
- a worker or Game production class gains direct OpenGL/GPU behavior;
- the feature requires Renderer changes to world generation, gameplay
  mutation, physics, inventory, or UI.

## Deliverables

- this Phase 5B design;
- a TDD implementation plan;
- engine visual/culling/metrics/surface code and focused tests;
- game composition and optional console-reporter integration tests;
- packaged shader/resource verification;
- `docs/agent-handoffs/phase-05b-handoff.md`;
- same-seed/same-position manual metrics and visual acceptance record;
- final owner reviews, full Gradle verification, diff/hygiene checks, suggested
  commit, and suggested PR text;
- no push, pull request, or merge without a later explicit instruction.
