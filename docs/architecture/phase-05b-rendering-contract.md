# Phase 5B Visual Rendering Contract

## Status and scope

This is the normative Phase 5B contract for the Milestone 1 visual rendering
upgrade. It extends, rather than weakens, the Phase 5A render-pipeline,
resource, state-restoration, and independent-Chunk ownership contracts. All
OpenGL, GLFW, and GPU-resource operations remain main/context-thread work.

## Visual settings and shaders

`RenderVisualSettings.milestoneOneDefaults()` is the default visual contract:

- sun direction input `(-0.45, 0.85, -0.30)`, normalized on construction;
- ambient strength `0.38` and directional strength `0.72`;
- sky top linear color `(0.035, 0.160, 0.470)`;
- sky horizon and fog linear color `(0.350, 0.570, 0.780)`;
- fog start `64.0`, fog end `160.0`;
- `GammaPath.SHADER_SRGB_DECODE_ENCODE`.

All direction components and scalar settings must be finite; the direction
must be non-zero; intensities and fog distances must be non-negative; and fog
end must be strictly greater than fog start. The renderer owns the following
GLSL 410 resources:

- `overlord:shaders/world.vert` and `overlord:shaders/world.frag`;
- `overlord:shaders/sky.vert` and `overlord:shaders/sky.frag`.

The world program requires `projection`, `view`, `model`, `textureAtlas`,
`sunDirection`, `ambientStrength`, `directionalStrength`, `fogColor`,
`fogStart`, and `fogEnd`. The sky program requires `skyHorizon` and `skyTop`.
World shading decodes the atlas from sRGB to linear, multiplies linear albedo
by clamped ambient plus directional lighting, face light, and AO, mixes fog in
linear space, then encodes exactly once to sRGB. The sky gradient is also
encoded exactly once in the shader. `GL_FRAMEBUFFER_SRGB` stays disabled:
enabling it while retaining shader encoding would double-encode output.

## Voxel data, ambient occlusion, and invalidation

The Phase 5A 40-byte vertex contract remains authoritative. Phase 5B consumes
the existing normal, encoded face/light, and AO attributes. Face light is the
low nibble of `aFaceLight`, normalized by `15.0`.

AO is evaluated for each visible face corner using its two tangent-side samples
and diagonal corner sample in an immutable `ChunkMeshInput`. Non-air,
renderable, non-transparent blocks occlude. Both occupied side samples win
over the corner and return `0.45`; otherwise zero, one, or two occluders return
`1.00`, `0.82`, or `0.65`, respectively. Tangent signs are exactly `-1` or
`+1`.

Meshing input is a center Chunk snapshot plus all eight horizontal neighbors:
north, north-east, east, south-east, south, south-west, west, and north-west.
Missing neighbors are immutable air snapshots at the expected key and matching
height. The repository must invalidate a Chunk when its own bytes change and
when any direct or diagonal neighbor change can alter its one-block halo. The
repository remains the only revision, dirty-state, claim, and publication
authority; workers build CPU-only `ChunkMeshData` from snapshots and never
read a mutable live `World`.

## Texture sampling

The atlas is uploaded as RGBA8 level zero only. Both minification and
magnification filters are `GL_NEAREST`; base and maximum level are zero; wrap
is `GL_CLAMP_TO_EDGE`; mipmaps are neither generated nor sampled. Atlas regions
use a half-texel inset so nearest sampling cannot bleed into an adjacent tile.
This pixel-art policy is intentional and must be preserved unless an explicit
asset/sampling migration is reviewed.

## Culling and surface behavior

The frustum is extracted from `projection * view` as six normalized planes.
For an AABB, each plane uses the positive vertex in the plane-normal direction;
the bounds are outside only when signed distance is less than `-0.01`. Bounds
on or within that epsilon remain visible. Culling affects submission only, not
Chunk ownership, lifecycle, or generation.

`RenderSurfaceMetrics` accepts non-negative logical and framebuffer dimensions
and finite, positive content scales. A zero framebuffer is non-drawable:
`Renderer.renderFrame` still starts and finishes metrics and clears its queue,
but performs no frustum construction, pass, viewport, or draw work. A positive
framebuffer after zero is a resize/rebuild event even when it matches the last
positive size. Projection uses `max(1, width)` and `max(1, height)` defensively;
viewport and projection rebuild happen only for drawable surfaces.

## Metrics

`RenderMetricsCollector.beginFrame(delta, queueDepth)` validates finite,
non-negative values and resets visible chunks, draw calls, and triangles.
`recordDraw(triangles)` increments draw calls once per draw and adds the
non-negative triangle count exactly. `finishFrame` publishes frame-local
values: FPS is `0` for a zero delta, otherwise `1 / delta`; milliseconds are
`delta * 1000`. Overflowed derived values saturate at `Double.MAX_VALUE` so
every accepted delta produces a finite snapshot. A frame that throws after
beginning still clears its queue and publishes metrics in `finally`.

`meshQueueDepth` is the main-thread observed sum of in-flight CPU meshing,
completed and failed CPU queues, pending uploads, and retained failed uploads.
It is a work-depth metric, not a count of visible or GPU-resident Chunks.

## Thread, GPU, and stale-result lifecycle

`MainThreadGuard` protects initialization, window/surface updates, frame
rendering, state mutation, shader/texture/VAO/VBO creation and destruction,
uniform upload, draw, and renderer cleanup. The mesh executor may only consume
immutable snapshots and return CPU mesh data. `ChunkMeshManager` drains and
uploads on the main thread; it releases a rejected stale replacement and only
releases an installed predecessor after a current replacement is accepted.

Unload remains repository-driven. A pending unload prevents a late CPU result
from becoming renderable; queued CPU/upload/failure state is discarded, the
installed object is released on the main thread, and the repository completes
the unload. Renderer code does not initiate Chunk unload.

## Interfaces later work must preserve

- Java 17, Gradle Wrapper, engine-to-game dependency direction, OpenGL 4.1,
  and GLSL 410.
- Phase 5A vertex layout, face IDs, resource identities, material GPU
  non-ownership, render-pass ordering, and exact state restoration.
- `RenderVisualSettings`, `RenderFrameInput`, `RenderSurfaceMetrics`, metrics
  snapshot semantics, immutable nine-snapshot meshing input, frustum epsilon,
  and the CPU/GPU ownership split.
- `ChunkRepository` as revision/dirty/publication authority and
  `ChunkMeshManager` as main-thread upload/install/release authority.
- Deterministic world-generation bytes, Phase 6 physics, and Phase 7 gameplay
  mutation behavior. Phases 8, 9, and 10 must treat this rendering contract as
  an integration boundary, not a reason to couple their systems to OpenGL.
