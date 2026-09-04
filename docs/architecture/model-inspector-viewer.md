# Gaia Model Inspector diagnostic viewer

Gate 17.75D design is controller-approved; execution evidence belongs to the Gate D
implementation notes. This document describes the approved ownership, not a claim
that interactive acceptance has already passed.

## Data and process ownership

The viewer is a standalone development JVM and one GLFW window. It is not Gaia
runtime GLB support and is not an asset approval authority. It neither edits nor
saves models. Gate B remains the sole semantic validator.

An explicit local GLB is validated headlessly before window creation. A successful
`ValidatedModelSnapshot` is projected into packed viewer vertices and draw data.
No original JSON, JglTF DTOs, raw accessors or encoded image containers enter the
renderer. There is no ImageIO path in the viewer. Materials and images are bounded
immutable document data, not another Gaia asset/material/item registry.

The `modelViewer` source set consumes `modelInspector` output and existing engine
Window/MainThreadGuard/ShaderProgram infrastructure. It does not inherit ordinary
`tools.main`, whose game dependency is intentionally excluded. The inspector
itself remains fully headless without engine/LWJGL dependencies. No new dependency
version, UI framework, game session, world, inventory or Blender integration.

## Diagnostic rendering

OpenGL 4.1 / GLSL 410; POSITION/NORMAL/UV0 with indexed triangles. Snapshot node
world transforms remain unchanged. Camera coordinates are independent. A
double-precision model-view composition avoids gratuitous large-translation
rounding before shader upload. Float GPU representability is a candidate upload
constraint, not a reinterpretation of HAND_TOOL_V0 validity.

Inspector preview lighting is diagnostic and is not Gaia runtime render parity.
Base color and validated texture are modulated by simple normal-based lighting.
Metallic/roughness remain inspection metadata; there is no full PBR, IBL, shadow,
normal map, bloom or artistic production approval.

One canonical RGBA8 image index owns one GPU sRGB texture, even when referenced
by multiple texture definitions. Sampler objects preserve each texture's explicit
filter/wrap configuration. Unspecified min/mag filters use the viewer's documented
LINEAR_MIPMAP_LINEAR / LINEAR defaults. Required mip storage is counted. Canonical
top-to-bottom image bytes map to glTF UV0 with no additional vertical flip. OPAQUE
material alpha never silently enables blending. Texture sRGB is decoded once;
lighting is linear and shader output is encoded to sRGB with framebuffer-sRGB
conversion explicitly disabled.

Grid/axes/bounds are finite diagnostic lines, not model geometry. Actual validated
bounds produce twelve AABB edges; declared accessor min/max are never consulted.
Wireframe changes polygon mode only. No UI framework or copied gameplay renderer.

## Reload and destruction

One R pressed edge coalesces into one pending request. Validation and candidate
construction happen synchronously on the context thread. Old current data stays
alive until a complete GPU candidate is ready; publication precedes old resource
destruction. Validation, CPU packing and partial GPU failure preserve the old
model and its source SHA. Failure identity is separate and may be unavailable if
Gate A rejected before hashing. There is no watcher, retry queue, async loader,
history or pool.

All GL create/use/delete calls are context-owner guarded. Partial construction
rolls back acquired handles. Normal close explicitly destroys current/candidate,
helper geometry, buffers/images/samplers, shader and finally Window. GPU handle
and byte counters provide bounded evidence for repeated reload, not Task Manager
inference. The required Gate D tests and Windows x20 reload evidence must verify
the steady state; the design alone is not a leak-freedom claim.

## Controls and platform scope

LMB orbit; MMB or Shift+LMB pan; wheel zoom; F frame; R manual reload; G grid;
A axes; B bounds; W wireframe; Escape close. Camera target is actual bounds center,
not a model rewrite. Frame math handles aspect and valid near/far ranges. Focus
loss clears drag deltas and pressed-edge eligibility. Framebuffer resize and
logical input dimensions remain distinct.

Windows interactive acceptance is required. Linux/macOS/real JDK17 remain NOT RUN
unless actual evidence is recorded. Compilation with release17 is not execution
on JDK17. Gate E, runtime asset admission, Blender authoring changes and Phase18/19
remain separately authorized future work.
