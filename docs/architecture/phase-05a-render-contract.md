# Phase 5A Render Contract

## Scope

Phase 5A replaces the inline world shader and direct draw path with a
resource-backed, explicitly state-owned render pipeline. It preserves the
approved Phase 4 image, the Phase 2 resource/material identity system, and the
Phase 3 independent-Chunk GPU lifecycle. This contract does not claim
lighting, ambient-occlusion evaluation, fog, sky gradients, gamma correction,
frustum culling, transparent mesh sections, transparent sorting, shadows,
deferred rendering, PBR, or SSAO.

## Vertex format

`VoxelVertexFormat` is the single source of truth for stride, attributes,
face IDs, and face/light encoding. `ChunkMeshBuilder`, `ChunkMeshData`, and
`Mesh` must not retain a second hard-coded layout.

| Location | Field | GLSL type | Components | Float offset | Byte offset |
| ---: | --- | --- | ---: | ---: | ---: |
| 0 | position | `vec3` | 3 | 0 | 0 |
| 1 | UV | `vec2` | 2 | 3 | 12 |
| 2 | normal | `vec3` | 3 | 5 | 20 |
| 3 | face/light | `float` | 1 | 8 | 32 |
| 4 | ambient occlusion | `float` | 1 | 9 | 36 |

The interleaved stride is exactly ten floats / 40 bytes. Face IDs are stable
explicit values, not enum ordinals:

```text
NORTH=0, SOUTH=1, UP=2, DOWN=3, WEST=4, EAST=5
```

Encoding is:

```text
encodedFaceLight = faceId * 16 + lightLevel
```

`lightLevel` is an integer in `[0, 15]`. Phase 5A emits real unit normals,
light level `15`, and ambient occlusion `1.0`. The current shader consumes
position and UV only; normal, face/light, and AO are locked Phase 5B inputs
and are not permission to change the stride or attribute locations.

Chunk-local vertex coordinates, model translations, bounds, independent mesh
ownership, and neighbor-face occlusion remain unchanged.

## Shader resource and uniform contract

The engine owns the world shaders as JAR-safe resources:

```text
overlord:shaders/world.vert
  -> assets/overlord/shaders/world.vert
overlord:shaders/world.frag
  -> assets/overlord/shaders/world.frag
```

Both shaders declare `#version 410 core`. The vertex shader declares locations
0 through 4 from the vertex table. The exact required uniform set is:

```text
projection
view
model
textureAtlas
```

`ShaderResourceLoader` reads both sources only through
`AssetManager.readUtf8(ResourceLocation)`, preserving Phase 2 diagnostics for
missing, ambiguous, or unreadable resources. `ShaderProgram` compiles and
links on the context-owning main thread, resolves every required uniform once,
caches its location, and treats `-1` as a construction failure that names the
program label, missing uniform, vertex resource, and fragment resource. Compile
diagnostics retain program label, stage, exact resource identity, and compiler
log. Link diagnostics retain the program label, both resource identities, and
linker log. Temporary or partially created shaders/programs are deleted before
failure escapes. The primary shader failure remains authoritative; only a
distinct cleanup failure is retained as suppressed, and a cleanup backend
throwing the same failure instance cannot trigger self-suppression.

## Material and GPU ownership

`RenderAssets` carries the atlas image, selected Phase 2
`MaterialDefinition`, and both shader `ResourceLocation` values. Gaia
composition selects the existing `gaia:opaque` definition. The engine fallback
uses explicit `overlord:missing` identities; neither path creates a second
asset or material registry.

Runtime `Material` is an immutable triple of `MaterialDefinition`,
`ShaderBinding`, and `TextureBinding`. It does not create, close, or destroy
the shader program or texture. `Renderer` owns the single shared
`ShaderProgram` and atlas `Texture`, cleans the texture before the program,
and makes both initialization-failure and normal cleanup idempotent.

## Queue and pass contract

`RenderQueue` owns separate opaque and transparent lists. OPAQUE and CUTOUT
submissions enter the opaque list; TRANSPARENT submissions enter the
transparent list. Both preserve insertion order and expose immutable
snapshots.

Production pass order is immutable:

1. `SkyRenderPass` (`sky`);
2. `WorldRenderPass` (`world`);
3. `DebugRenderPass` (`debug`).

`RenderPipeline` rejects duplicate pass IDs, stops after a thrown pass, and
clears the queue in `finally`. `Renderer.renderFrame` also clears before
collection and in an outer `finally`, so invalid collection entries and pass
failures cannot retain stale or unloaded Chunk references.

| Pass/category | Depth test | Depth write | Blend | Cull | Phase 5A action |
| --- | --- | --- | --- | --- | --- |
| Sky | off | on | off | off | clear color and depth using the existing flat clear color |
| World opaque/CUTOUT | on | on | off | off | bind material, upload matrices/atlas unit, draw in insertion order |
| World transparent API | on | off | source alpha / one-minus-source-alpha, add | off | draw in insertion order |
| Debug | unchanged | unchanged | unchanged | unchanged | disabled; no draw or state mutation |

The transparent list is an API boundary only. Phase 5A does not split
`ChunkMeshData` or `ChunkRenderObject` by material, produce transparent Chunk
sections, sort back-to-front, or provide an end-to-end transparent world path.

## Exact OpenGL state scope

Every active pass category uses `RenderStateScope`. The incoming
`RenderStateSnapshot` captures and restores:

- depth-test enablement;
- depth write mask;
- blend enablement;
- blend RGB source and destination factors;
- blend alpha source and destination factors;
- blend RGB and alpha equations;
- cull-face enablement;
- current shader program;
- active texture unit;
- texture unit 0 `GL_TEXTURE_2D` binding.

Capture temporarily activates texture unit 0 only to query its 2D binding and
restores the original active unit before returning. Scope close restores the
exact incoming snapshot once, including normal and exceptional pass exits. If
applying requested pass state fails during `open`, the incoming snapshot is
restored before the original apply failure escapes. A restore failure is
suppressed on the apply failure and never replaces it. All production state
functions use OpenGL 4.1-compatible calls exposed by LWJGL `GL30C`.

## Thread and lifecycle boundaries

The context-owning main thread, enforced through `MainThreadGuard`, owns:

- renderer initialization, frame collection/pass execution, resize, and
  cleanup;
- shader compile/link/use/uniform upload/program cleanup;
- texture create/bind/cleanup;
- VAO/VBO create/upload/draw/release;
- state capture/apply/restore and color/depth clear;
- `ChunkMeshManager` completion draining, current-result validation, upload,
  installed-object replacement, unload release, and close.

CPU generation and immutable-snapshot meshing may run on workers. Workers may
produce `ChunkMeshData` using `VoxelVertexFormat`, but may not create or
manipulate `Mesh`, `ShaderProgram`, `Texture`, runtime GPU bindings, render
passes, `RenderStateScope`, GLFW, LWJGL, or OpenGL.

`ChunkRepository` remains the only loaded-Chunk directory and revision/dirty
authority. `ChunkMeshManager` remains the only installed render-object
authority. Repository revision checks, stale-result rejection, upload
validation, install-after-success, previous-object release, unload cleanup,
empty-mesh behavior, and one independent `ChunkRenderObject` per loaded Chunk
remain authoritative. Phase 5A does not create a combined world mesh or a
second lifecycle map.

## Phase 5B compatibility boundary

Phase 5B may deliberately add lighting, AO evaluation, sky/fog/gamma, and
frustum culling only while preserving:

- the 40-byte vertex layout, stable face IDs, and face/light encoding;
- OpenGL 4.1 / GLSL 410 compatibility with no compute shader or SSBO;
- Phase 2 `ResourceLocation`, material definitions, and asset diagnostics as
  the only identity/diagnostic system;
- cached required uniforms or an explicitly reviewed compatible extension;
- non-owning runtime materials and single renderer ownership of shared GPU
  resources;
- immutable pass order changes made only through explicit architecture review;
- exact state capture/restoration and queue clearing on every failure path;
- main/context-thread OpenGL and GPU ownership;
- Phase 3 Chunk revision, dirty, stale, upload, replacement, unload, and
  independent-object contracts;
- no world-generation, physics, player, gameplay-mutation, or UI coupling.

Cull remains off until winding and visual acceptance tests explicitly approve
enabling it. Transparent sorting and material-split Chunk geometry require a
separate lifecycle design; the existence of `transparentItems()` alone is not
approval to add them.
