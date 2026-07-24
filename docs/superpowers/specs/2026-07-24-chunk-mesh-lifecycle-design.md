# Independent Chunk Mesh Lifecycle Design

## Status

- Phase: 3
- Branch: `refactor/chunk-mesh-lifecycle`
- Base: `origin/main` at `11cc981`
- Approved design date: 2026-07-24
- Java compatibility: source and target 17
- Graphics compatibility: OpenGL 4.1 and GLSL 410

## Purpose

Phase 3 replaces the single combined terrain mesh with independently generated,
uploaded, rendered, rebuilt, and unloaded chunk meshes.

The design must support later block breaking, block placement, streaming, and
culling without implementing those gameplay or streaming policies now. A
change to one block rebuilds only the chunks whose visible faces may have
changed.

The phase preserves current terrain generation, block definitions, materials,
texture atlas contents, player behavior, and visual output.

## Scope

Phase 3 implements:

- an explicit chunk lifecycle state machine;
- value-based chunk coordinates;
- a repository that owns chunk state and revisions;
- immutable snapshots for CPU meshing;
- independent CPU mesh results and GPU render objects;
- dirty propagation across horizontal chunk boundaries;
- a revision-aware CPU mesh queue;
- a main-thread upload queue with a default budget of two uploads per frame;
- explicit chunk unload and GPU cleanup;
- independent chunk rendering with world transforms and bounds;
- focused lifecycle, concurrency, upload, replacement, and unload tests;
- the Phase 3 handoff.

Phase 3 does not implement:

- distance-driven automatic streaming;
- a frustum-culling algorithm;
- LOD;
- transparent sorting;
- new terrain generation;
- new visual effects;
- block breaking or placement UI;
- vertical chunk columns separate from the existing full-height `Chunk`.

## Existing Constraints

The design preserves these repository boundaries:

- `engine` remains independent of `game`;
- Gaia-specific names and generation remain in `game`;
- CPU generation and meshing may run on worker threads;
- GLFW, OpenGL, and all GPU resource lifecycle operations remain on the
  context-owning main thread;
- dependencies are passed by constructors or explicit context objects;
- no new `ServiceLocator` usage is introduced;
- no OpenGL 4.3+, compute shader, or platform-specific graphics API is used.

## Architectural Overview

### Engine-owned types

The reusable lifecycle infrastructure belongs in `engine`:

- `ChunkKey`
- `ChunkState`
- `ChunkRepository`
- `ChunkSnapshot`
- `ChunkMeshData`
- `ChunkRenderObject`
- `ChunkRenderBackend`
- `ChunkMeshManager`
- `ChunkDirtyTracker`
- a small immutable axis-aligned bounds value

`Chunk` and `SubChunk` continue to store voxel data. They do not own lifecycle
state, executors, render objects, or GPU resources.

### Game-owned composition

`game` remains responsible for:

- Gaia terrain generation;
- loading the initial chunk set;
- constructing the repository, mesh manager, executors, and renderer backend;
- pumping chunk scheduling and main-thread uploads from `GameLoop`;
- choosing the initial load radius;
- shutdown registration and ordering.

## Chunk Coordinates

`ChunkKey` is an immutable record:

```java
public record ChunkKey(int x, int z)
```

It provides helpers for:

- converting a world X/Z coordinate to a chunk key with `Math.floorDiv`;
- converting a world X/Z coordinate to a local coordinate with
  `Math.floorMod`;
- computing north, south, west, and east neighbors;
- computing the world-space X/Z origin of the chunk.

Negative coordinates retain the existing floor-based behavior.

The key is the only repository and render-map key. String keys such as
`"x,z"` are removed.

## Lifecycle State Machine

`ChunkState` contains:

```text
EMPTY
GENERATING
GENERATED
MESHING
READY_FOR_UPLOAD
RENDERABLE
DIRTY
UNLOADING
```

The normal transitions are:

```text
EMPTY -> GENERATING -> GENERATED -> MESHING
EMPTY -- explicit non-air mutation --> DIRTY
DIRTY ---------------------------> MESHING
MESHING -> READY_FOR_UPLOAD -> RENDERABLE
MESHING -- stale revision ------> DIRTY
READY_FOR_UPLOAD -- stale ------> DIRTY
any active state -> UNLOADING -> repository removal
```

An empty mesh completes the same lifecycle and enters `RENDERABLE`, but has no
`ChunkRenderObject`.

State transitions occur only through `ChunkRepository`. An invalid transition
throws an `IllegalStateException` containing the `ChunkKey`, current state,
and requested state.

## Repository Entries and Revisions

`ChunkRepository` is the sole directory of loaded chunks. Each private entry
owns:

- a `Chunk`;
- a `ChunkState`;
- a monotonically increasing mesh revision;
- a short-duration synchronization lock;
- whether a mesh task is currently claimed;
- the latest lifecycle failure, when present.

The repository does not own GPU resources.

The mesh revision is an invalidation token, not only a voxel-data version. It
increments whenever the target chunk mesh may have changed:

- any block in the chunk changes;
- a horizontal neighbor edge changes;
- an adjacent chunk completes generation;
- an adjacent chunk begins unloading.

Repeated dirty events coalesce. They may increment the revision, but they do
not create duplicate tasks for the same revision.

Repository reads do not create chunks. A missing chunk or block reads as air.
Creation is explicit through generation or mutation APIs. Setting air in a
missing chunk is a no-op; setting a non-air block explicitly creates an entry
and moves it from `EMPTY` to `DIRTY`.

## Generation and Runtime Mutation

### Batched generation

Terrain generation uses one exclusive mutation callback:

```java
repository.generate(
        key,
        mutableChunk -> generator.fill(mutableChunk));
```

The entry moves from `EMPTY` to `GENERATING`, the generator fills the chunk
under the entry lock, and completion performs one revision increment before
moving to `GENERATED`.

This avoids emitting thousands of dirty events during initial generation.

Completion also dirties already-present horizontal neighbors because their
boundary faces may change from exposed to hidden.

### Runtime block changes

Normal world mutation:

1. resolves `ChunkKey` and local coordinates;
2. mutates the target entry under its short lock;
3. increments the target revision;
4. marks the target `DIRTY`;
5. asks `ChunkDirtyTracker` for affected horizontal neighbors;
6. increments and dirties those present neighbors.

The dirty set rules are:

- every change includes the target key;
- local `x == 0` includes west;
- local `x == SIZE - 1` includes east;
- local `z == 0` includes north;
- local `z == SIZE - 1` includes south;
- a corner includes two orthogonal neighbors;
- a diagonal neighbor is never included;
- Y boundaries do not select another chunk because chunks retain full world
  height.

## Immutable Snapshot Model

CPU meshing never reads a mutable `Chunk`, `SubChunk`, or `World`.

`ChunkSnapshot` contains:

- `ChunkKey`;
- captured mesh revision;
- world height;
- an owned immutable copy of block IDs.

A mesh input contains the center snapshot and the four horizontal neighbor
boundary snapshots needed for face visibility.

Snapshot copying holds each entry lock only while copying its bytes. No lock is
held during CPU meshing.

The manager uses the following race protection:

1. claim the target state and revision;
2. copy center and neighbor data;
3. recheck the target revision after capture;
4. build the mesh from immutable data;
5. recheck the target revision when CPU work completes;
6. recheck again immediately before GPU upload.

Any mismatch discards the CPU result and returns the target to `DIRTY`. Edge
mutations increment the affected neighbor's target revision, so a stale
boundary snapshot cannot become permanently renderable.

## CPU Mesh Data

`ChunkMeshData` is a CPU-only immutable value containing:

- `ChunkKey`;
- target revision;
- owned vertex data;
- vertex count;
- local-space bounds;
- whether the result is empty.

It contains no OpenGL IDs and performs no GPU calls.

`ChunkMeshBuilder` accepts immutable snapshot input instead of reading
`World`. Existing face winding, UV selection, block render resolution, and
texture behavior remain unchanged.

Vertices are local to the chunk. World placement is supplied by the render
object's model transform.

## Chunk Mesh Manager

`ChunkMeshManager` is the central coordinator. It owns:

- references to `ChunkRepository`, `ChunkMeshBuilder`, worker executor, and
  `ChunkRenderBackend`;
- completed CPU result and failure queues;
- an upload-ready queue;
- a main-thread map of `ChunkKey` to `ChunkRenderObject`;
- a configurable upload budget, defaulting to two per frame;
- idempotent close state.

It does not own terrain generation or block definitions.

### Scheduling

Each frame, the game asks the manager to schedule eligible `GENERATED` and
`DIRTY` entries. Claiming the entry changes it to `MESHING`, preventing
duplicate submission. A `DIRTY` entry with an unresolved lifecycle failure is
not eligible until an explicit retry request or a newer mutation clears that
failure.

Worker tasks:

- capture immutable input;
- build only CPU data;
- enqueue success or failure;
- never call Renderer, Mesh, Shader, Texture, GLFW, or OpenGL.

### Completion

The main loop drains CPU completions:

- stale or unloaded results are discarded;
- current results move to `READY_FOR_UPLOAD`;
- failures return the entry to `DIRTY` and enter a failure queue;
- failures are surfaced on the main loop and are not retried automatically.

### Upload budget

The main thread processes at most two successful uploads per frame by default.
The budget is constructor-configurable for tests.

Remaining items stay queued for later frames.

An empty result consumes one queue item, removes any previous GPU object, and
sets the entry to `RENDERABLE` without creating a zero-vertex Mesh.

## GPU Backend and Render Objects

`ChunkRenderBackend` isolates the OpenGL boundary so lifecycle tests can use a
fake backend without a context.

The production implementation is provided by `Renderer`. Every production
backend operation asserts `MainThreadGuard` before touching GPU state:

- create/upload;
- replace;
- draw;
- release.

`ChunkRenderObject` contains:

- `ChunkKey`;
- Mesh ownership;
- source revision;
- a model matrix translated by
  `(key.x() * Chunk.SIZE, 0, key.z() * Chunk.SIZE)`;
- a world-space axis-aligned bounding box.

Phase 3 exposes the bounds for later culling but renders all current objects.

### Replacement safety

Replacement order is:

1. create the new Mesh successfully;
2. replace the manager's map entry;
3. release the previous Mesh exactly once.

If creation fails, the previous render object remains installed and
renderable. The failed result is removed from the automatic upload queue and
remains reportable for an explicit retry or a newer revision.

Continuous dirty/rebuild cycles therefore keep at most one installed render
object per key and release every replaced VAO/VBO.

## Renderer Changes

Renderer stops owning one global terrain Mesh and removes the terrain
`replaceMesh(float[])` path.

It retains shader, atlas, projection, and camera ownership. Rendering iterates
the supplied `ChunkRenderObject` collection:

1. bind shader and atlas;
2. set projection and view once;
3. set each object's model matrix;
4. draw its Mesh.

The existing fallback cube is removed from the terrain path. Loading continues
to clear and swap buffers without drawing terrain until chunk objects become
available.

## Initial Load and Game Loop

`WorldLoader` performs terrain generation and spawn selection only. Its result
contains:

- the initial immutable set of `ChunkKey` values;
- the spawn position.

It no longer combines chunk vertex arrays or returns one `float[]`.

After the generation future completes:

1. `GameLoop` requests meshing for the initial key set;
2. each frame schedules dirty chunks;
3. each frame drains completions and processes at most two uploads;
4. loading remains clear-only while independent GPU objects accumulate;
5. the loop enters `RUNNING` and draws the chunk set after every initial key is
   `RENDERABLE`.

The fixed update and input ordering from Phase 1 do not change.

## Explicit Unload

Phase 3 exposes explicit unload by key but adds no distance policy.

```text
unload(key)
  -> repository state UNLOADING
  -> revision increment invalidates pending work
  -> main-thread release of installed render object
  -> repository entry removal
```

Unload is idempotent. Late worker results for a missing or unloading entry are
discarded and cannot recreate it.

Neighbor entries are dirtied when an unloaded boundary changes their visible
faces.

## Shutdown

Shutdown order is:

1. cancel the world-load future;
2. stop world generation;
3. stop the mesh executor and wait for termination;
4. close `ChunkMeshManager` on the context-owning main thread;
5. release all remaining `ChunkRenderObject` instances;
6. shut down Engine, Renderer, and the GLFW window.

Registration order in `ShutdownCoordinator` is chosen so reverse cleanup
produces this sequence.

Manager close and per-key unload are idempotent.

## Error Handling

- Invalid repository transitions fail immediately with key and state context.
- Worker exceptions are transferred to the main thread; they are not printed
  and ignored.
- A failed mesh entry returns to `DIRTY` but is not automatically resubmitted
  until the caller handles the failure.
- Executor rejection during normal operation is a lifecycle error.
- Executor rejection after the entry is unloading is ignored.
- GPU upload failure preserves the old render object.
- GPU cleanup failures follow the existing shutdown aggregation rules.
- Stale results do not perform any GPU action.

## Test Strategy

### Engine tests

- positive and negative world/chunk/local coordinate conversion;
- legal and illegal lifecycle transitions;
- repository reads do not create chunks;
- generated chunk transition and one revision bump;
- snapshot immutability after later mutation;
- snapshot meshing never reads mutable world state;
- interior, edge, and corner dirty propagation;
- adjacent generation and unload dirty propagation;
- duplicate dirty events coalesce;
- stale CPU result rejection;
- stale upload result rejection;
- empty chunk reaches `RENDERABLE` with no GPU object;
- upload budget leaves excess work queued;
- worker tasks do not call the GPU backend;
- world transform and bounds;
- repeated replacement releases each previous object once;
- upload failure preserves the previous object;
- unload cleanup is idempotent;
- late unload results do not recreate entries;
- manager close releases every installed object once;
- main-thread guard rejects production backend use before OpenGL calls.

### Game tests

- Gaia terrain block layers remain unchanged;
- `WorldLoader` returns independent keys and spawn data;
- no combined mesh allocation remains;
- initial loading requests per-key meshing;
- the game remains in loading until initial entries are renderable;
- the game loop enforces the upload budget;
- Bootstrap constructs and shuts down repository/manager/executors in the
  required order;
- no game worker path references GPU resource types.

### Final verification

Windows:

```powershell
.\gradlew.bat clean test build
.\gradlew.bat :game
```

macOS:

```bash
./gradlew clean test build
./gradlew :game
```

The interactive smoke test verifies unchanged terrain and materials, no
boundary cracks in the initial set, normal movement, and clean shutdown.

Repository checks include:

- `git diff --check`;
- no tracked generated files;
- no absolute JDK path;
- no combined world mesh API or `replaceMesh(float[])`;
- no OpenGL or GPU object calls from worker paths;
- GLSL remains 410 and no compute shader API is introduced.

## Interfaces the Phase Must Preserve

- Java 17 source and target compatibility;
- `engine` must not depend on `game`;
- byte block IDs and Phase 2 `BlockRenderResolver` behavior;
- data-driven face UVs and missing-texture behavior;
- `MainThreadGuard` before every GPU operation;
- Phase 1 fixed-step update and input ordering;
- existing terrain seed, noise parameters, height rules, and block layering;
- atlas pixels and material appearance.

## Handoff

The phase creates `docs/agent-handoffs/phase-03-handoff.md` with:

- completed and incomplete work;
- lifecycle and threading decisions;
- modified files;
- automated and interactive verification;
- known risks;
- interfaces Phase 4 must not break;
- `git diff --stat`;
- suggested commit and pull request text.

Because the phase changes both `engine` and `game`, final review must explicitly
cover both ownership perspectives.
