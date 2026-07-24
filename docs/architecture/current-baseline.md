# Current Architecture Baseline

## Snapshot

This document describes the final Phase 3 architecture on
`refactor/chunk-mesh-lifecycle`, based on `origin/main` at commit `11cc981`
(`feat(assets): add data-driven block and material resource system (#7)`).
The Phase 3 implementation was verified at commit `66dca43` before its handoff
documentation commit. This includes the whitespace-only cleanup at `fcccf86`
and the final stale-revision hardening at `66dca43`.

The repository is a two-module Gradle build:

- `engine` is a Java library containing runtime, rendering, physics, ECS, event, scheduling, and voxel infrastructure.
- `game` is an application containing Gaia-specific blocks, resources, world generation, and the `GameBootstrap` composition root.

Both modules target Java 17. The checked-in Gradle 8.5 Wrapper can run on JDK 21. LWJGL native selection in current main is based on operating system and CPU architecture.

## Engine

`com.overlord.core.Engine` is the top-level runtime owner. Its constructor:

- clamps its scheduler size to one through four logical cores;
- creates `TaskScheduler`;
- registers itself, `EventBus`, `ModuleManager`, and the scheduler in the global `ServiceLocator`.

`Engine.init()` creates `Window`, `Camera`, `Renderer`, and `World`, initializes the renderer, registers those services, starts scheduling, and marks the engine as running. `Engine.shutdown()` stops modules and scheduling, cleans the renderer, destroys the window, and clears global registries.

Current boundaries and risks:

- `Engine.init()` creates the GLFW window/OpenGL context and GPU-backed renderer resources on its caller thread. Rendering and shutdown must remain on that same main/context-owning thread.
- Lifecycle ownership is implicit. There are no guards against repeated initialization or shutdown and no `try/finally` composition at the application boundary.
- The engine exposes concrete subsystem getters. These are the practical integration surface for current game code.
- The global `ServiceLocator` and singleton managers hide dependencies. Later work should prefer constructor injection or an explicit context and must not expand locator use.

## ECS

`com.overlord.ecs` currently provides:

- `Entity`: integer identity plus an active flag;
- `Component`: an entity back-reference and runtime component type;
- `EntityManager`: entity creation/destruction and per-entity component maps;
- `ComponentPool`: reflection-created, fixed-size component arrays;
- `System`: enabled state and lifecycle hooks;
- `ParallelSystem`: fixed-thread-pool processing of entity list slices.

The ECS is a standalone prototype and is not wired into `Engine`, `World`, rendering, or physics. It has no queries, deterministic system ordering, serialization, or thread-safety contract. `EntityManager` stores mutable `HashMap`/`HashSet` state. `ParallelSystem` waits for submitted slices but only prints worker failures.

`ComponentPool` is not yet a production allocator: expansion replaces the existing array instead of preserving checked-out state, and normal `EntityManager.addComponent` accepts externally constructed components rather than acquiring them from the pool.

Interfaces to preserve until deliberately migrated:

- integer entity identity and `Entity.equals`/`hashCode`;
- `EntityManager` CRUD/component lookup behavior;
- `System.update(float)` and lifecycle hooks.

## EventBus

`EventBus` is a process-wide singleton. Producers enqueue `Event` instances in a `ConcurrentLinkedQueue`; the caller of `processAll()` drains events and invokes exact-class handlers in subscription order. Handlers can cancel an event to stop later handlers.

Current boundaries and risks:

- Delivery occurs on the thread that calls `processAll()`, not on the publishing thread.
- Polymorphic event delivery is not supported.
- The handler map is concurrent, but each handler list is an `ArrayList`; concurrent subscribe/unsubscribe/process operations are not fully safe.
- Handler exceptions are not isolated.
- `clear()` removes both handlers and queued events during engine shutdown.

Later phases should preserve queued, explicit-pump delivery unless an architecture decision and migration tests intentionally replace it.

## TaskScheduler

`TaskScheduler` owns one single-thread executor per configured core plus one dispatcher thread per executor. Tasks enter a global `PriorityBlockingQueue`, ordered only by `HIGH`, `NORMAL`, or `LOW`.

The current implementation does not honor the requested `targetCore`: every dispatcher competes for the same global queue and submits whichever task it takes to that dispatcher's executor. Equal-priority tasks also have no explicit sequence tie-breaker. Submission returns no completion handle, failure channel, or backpressure signal.

`Engine.submitToCore(...)` is already used by `GaiaMain` for world generation and player updates, so it is an existing call surface. Phase 1 may repair its semantics, but should add deterministic tests and avoid scheduling any OpenGL/GLFW or GPU-resource work onto worker executors.

## World

`World` delegates all voxel access to a `ChunkRepository`. Repository entries
are keyed by immutable `ChunkKey(int x, int z)` values and own the mutable
`Chunk`, lifecycle `ChunkState`, mesh revision token, and latest failure under
an entry-local monitor. A repository-scoped `AtomicLong` supplies unique,
monotonically increasing tokens whenever a revision advances. Missing chunk
and block reads return air and do not allocate entries. World coordinates
continue to use `floorDiv`/`floorMod` across negative coordinates.

`Chunk` still divides its configurable vertical range into lazily allocated
`SubChunk` instances, and subchunks still store `byte` block IDs sparsely. The
repository is now the only directory and mutation boundary for chunks.
Generation is an exclusive batched repository mutation; normal block changes
mark the target dirty and invalidate present horizontal neighbors when an edge
may have changed.

CPU meshing claims a target key and revision, copies the center and four
cardinal neighbors into immutable `ChunkSnapshot` values, releases all entry
locks, and builds `ChunkMeshData` from those copies. Target revisions are
checked after capture, after CPU completion, and before upload. A center change
or affected neighbor-edge change increments the target revision, so stale work
is rejected without replacing newer work.

Current boundaries and risks:

- Entry-local synchronization protects repository-owned state and snapshot
  copying, but callers must still use `World`/`ChunkRepository`; mutable
  `Chunk`/`SubChunk` storage is not a general concurrent API.
- Explicit unload advances the repository-wide revision sequence. A later
  reload receives a fresh token, so late work from an earlier incarnation
  cannot be accepted without retaining a per-key tombstone map.
- Explicit unload is implemented and idempotent, including neighbor
  invalidation and late-result rejection. There is no automatic
  distance-driven streaming policy, persistence, block update event stream, or
  vertical column split.

The current block-coordinate behavior, 16-by-16 chunk footprint, sparse vertical allocation, and `byte` block IDs are active interfaces for game generation, physics, and meshing.

## Renderer

`Window` initializes GLFW, requests an OpenGL 4.1 core forward-compatible
context, makes it current, then creates LWJGL OpenGL capabilities. `Renderer`
enables depth testing, compiles GLSL 410 shaders, loads the texture atlas, and
implements the `ChunkRenderBackend` GPU boundary. `Mesh`, `Shader`, and
`Texture` directly own OpenGL object IDs and delete them in `cleanup()`.

Terrain rendering uses independent `ChunkRenderObject` instances. Each object
binds a `ChunkKey`, source revision, owned GPU mesh, chunk-local model
translation, and world-space bounds. `Renderer.upload`, `release`, and
`renderChunks` assert the captured `MainThreadGuard`; the renderer no longer
owns or replaces one combined terrain mesh. `ChunkMeshManager` owns the
main-thread map of installed objects and releases the previous object only
after a replacement upload succeeds. Empty mesh data reaches `RENDERABLE`
without allocating a zero-vertex GPU object.

Current boundaries and risks:

- CPU generation and immutable-snapshot meshing run on dedicated workers.
  Completion draining, at most two uploads per frame, rendering, replacement,
  explicit unload, and manager close run on the main/context-owning thread.
- Failed uploads preserve the installed render object and remain explicit
  failures; stale and unloaded results perform no GPU action.
- The manager checks `READY_FOR_UPLOAD` state and revision before charging the
  normal upload budget and again immediately before the backend call. A narrow
  concurrent-mutation window between those checks can consume one frame-budget
  slot without performing a GPU upload; correctness and resource ownership are
  preserved.
- Phase 3 exposes bounds but does not implement frustum culling, batching, LOD,
  transparent sorting, or automatic streaming.

All future renderer work must remain compatible with OpenGL 4.1 / GLSL 410 and must keep every OpenGL call and GPU resource create/upload/destroy action on the main/context-owning thread.

## Physics

`PhysicsManager` is constructed explicitly with `Camera` and `World`. It treats the camera position as the player body, applies gravity and terminal velocity, resolves vertical voxel contact, performs horizontal AABB overlap checks, supports a one-block step-up, and exposes grounded jump state.

Current boundaries and risks:

- Physics is player-specific rather than an engine-wide simulation and is not integrated with ECS.
- Collision and movement update mutable camera/world state directly.
- Broad-phase acceleration, swept collision, fixed-step accumulation, entity collision, and tests are absent.
- Correct behavior depends on the caller supplying coherent delta time and horizontal movement values.

The constructor injection boundary is preferable to global lookup and should be preserved. Any future fixed-step loop must keep physics CPU-only and coordinate world access under an explicit threading policy.

## Current application flow

`GameBootstrap` is the composition root. It loads data-driven assets, starts
the engine, installs input/player/physics state, creates one world-loading
executor and two named chunk-meshing workers, constructs `ChunkMeshManager`
with an upload budget of two, and registers a shutdown barrier around worker,
GPU-manager, and engine cleanup.

`WorldLoader` generates the fixed initial 4-by-4 key set and spawn position
without building combined mesh data. During `LOADING`, `GameLoop` schedules
eligible per-key CPU work, drains completions, and processes up to two uploads
per frame while continuing clear/swap. It enters `RUNNING` only after every
initial key is `RENDERABLE`, then renders the manager's independent object
collection. The fixed-step input, player, module, and event ordering is
unchanged.

Shutdown cancels world loading, confirms the world and mesh executors have
terminated, closes the mesh manager on the main thread to release installed
objects, and only then tears down Engine/OpenGL. Explicit per-key unload uses
the same main-thread manager boundary. Phase 3 deliberately adds no automatic
streaming or culling policy.
