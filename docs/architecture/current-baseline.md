# Current Architecture Baseline

## Snapshot

This document describes the final Phase 6 architecture on
`refactor/physics-foundation`, based on `origin/main` at commit `ad02717`.
The Phase 6 implementation and review fixes were verified at commit `95d78d0`
before its handoff documentation commit. Phase 6 preserves the Phase 3 chunk
mesh lifecycle while replacing the former Camera-owned physics path with
fixed-step collision, player-controller, raycast, and minimal rigid-body
foundations.

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

Phase 6 does not add physics services to `ServiceLocator`. `GameBootstrap`
constructs and injects the shared collision resolver, `CollisionWorld`,
`BlockRaycast`, player `PhysicsBody`, `PlayerController`, and `PhysicsWorld`
through the explicit `GameContext`.

Current boundaries and risks:

- `Engine.init()` creates the GLFW window/OpenGL context and GPU-backed renderer resources on its caller thread. Rendering and shutdown must remain on that same main/context-owning thread.
- `GameBootstrap` owns the application `try/finally` boundary and closes the
  `ShutdownCoordinator` after success or failure. `Engine` itself still has no
  explicit guard against repeated initialization or shutdown, so callers must
  preserve the single-init/single-close ownership contract.
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

The ECS is a standalone prototype and is not wired into `Engine`, `World`,
rendering, or the Phase 6 `PhysicsWorld`. Physics bodies use a separate
insertion-ordered registry. The ECS has no queries, deterministic system
ordering, serialization, or thread-safety contract. `EntityManager` stores
mutable `HashMap`/`HashSet` state. `ParallelSystem` waits for submitted slices
but only prints worker failures.

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

Phase 6 keeps delivery on the main fixed-update thread. Each running fixed step
orders `PlayerManager.fixedUpdate`, `PhysicsWorld.step`,
`ModuleManager.updateAll`, then `EventBus.processAll`.

## TaskScheduler

`TaskScheduler` owns one single-thread executor per configured core plus one dispatcher thread per executor. Tasks enter a global `PriorityBlockingQueue`, ordered only by `HIGH`, `NORMAL`, or `LOW`.

The current implementation does not honor the requested `targetCore`: every dispatcher competes for the same global queue and submits whichever task it takes to that dispatcher's executor. Equal-priority tasks also have no explicit sequence tie-breaker. Submission returns no completion handle, failure channel, or backpressure signal.

`Engine.submitToCore(...)` remains a public legacy scheduling surface, but the
Gaia application no longer uses it for world generation or per-frame player
updates. `GameBootstrap` owns dedicated world-loading and chunk-meshing
executors, while the Phase 6 player controller and generic physics world run
synchronously in `GameLoop` on the main thread at the production fixed step.
No OpenGL/GLFW or GPU-resource work may be scheduled through `TaskScheduler`,
the physics path, or either dedicated worker pool.

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

Phase 6 collision and raycast queries read blocks exclusively through
`World.getBlock`, so negative and Chunk-boundary coordinates retain the
repository's `floorDiv`/`floorMod` behavior and missing reads remain
non-allocating air. One injected `BlockCollisionShapeResolver` maps stored byte
IDs to ordered local shapes; the current production resolver treats ID `0` as
empty and every other ID as a full cube.

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

Camera position is now one-way render output. Before rendering, `GameLoop`
copies the player body's interpolated previous/current feet position plus eye
height into Camera; `Camera.setPosition` copies the value into owned storage.
Physics and collision do not read Camera position and perform no renderer,
LWJGL, OpenGL, or GPU work.

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

The legacy `PhysicsManager` has been removed. Reusable physics now lives under
`com.overlord.physics` and remains independent of `game`, rendering, LWJGL,
OpenGL, and GPU resources.

`Aabb`, `SweepResult`, and `MotionResult` are immutable collision values.
`BlockCollisionShape` preserves ordered local sub-boxes, and
`BlockCollisionShapeResolver` is the one injected stored-ID-to-shape boundary.
`CollisionWorld` is the shared static-voxel kernel for continuous swept AABB,
bounded sweep-and-slide, strict overlap, and deterministic depenetration.
Equal-time sweep selection uses Y/X/Z axis priority, ascending block X/Y/Z,
then declared sub-shape order.

`BlockRaycast` uses the same `World` and shape resolver as collision. It
combines finite 3D DDA traversal with exact sub-shape slabs, returns immutable
hit/adjacent data, preserves negative and Chunk-boundary coordinate behavior,
uses checked adjacent-coordinate arithmetic, and caps synchronous casts at
4096 blocks.

`PhysicsBody` owns authoritative previous/current translational positions,
linear velocity, reserved angular velocity, validated mass/material state, and
one force/impulse/reserved-torque accumulator. Teleports synchronize both
positions; interpolation is a pure read. `PhysicsWorld` keeps generic bodies
in insertion order and integrates active, awake dynamic bodies against static
voxels once per supplied fixed step. Static, inactive, and sleeping bodies do
not integrate. Body-body collision, rotation, constraints, joints, and a full
solver remain deferred to Phase 11.

`PlayerController` is the sole integrator of the player body. It implements
gravity, terminal velocity, continuous collision, grounded/jump/ceiling state,
wall slide, one-block step-up, conditional one-block ground snap, bounded
spawn recovery, collision-safe noclip exit, and normalized noclip movement.
`PlayerManager` remains the input/view boundary: it derives normalized
world-space movement from Camera orientation, applies look, and implements the
15-fixed-step double-Space window without owning a collision loop.

Current boundaries and risks:

- Production physics is exactly `1.0 / 60.0` second, supplied by `GameLoop`;
  neither `PhysicsWorld` nor `PlayerController` owns a wall-clock accumulator.
- The player body must not also be registered in `PhysicsWorld`, or it would
  be integrated twice.
- The default shape resolver treats every non-air block as a full cube until
  block data gains collision-shape definitions.
- Broad-phase collision and overlap enumerate voxel ranges directly; very
  large displacements or colliders can be expensive.
- Angular velocity and torque are reserved and cleared but do not rotate a
  body in Phase 6.
- Physics remains separate from ECS.

## Current application flow

`GameBootstrap` is the composition root. It loads data-driven assets, starts
the engine, constructs one shared default block-shape resolver,
`CollisionWorld`, `BlockRaycast`, player body, `PlayerController`, and
`PhysicsWorld`, creates one world-loading executor and two named chunk-meshing
workers, constructs `ChunkMeshManager` with an upload budget of two, and
registers a shutdown barrier around worker, GPU-manager, and engine cleanup.

`WorldLoader` generates the fixed initial 4-by-4 key set and spawn position
as explicit player feet coordinates without building combined mesh data.
During `LOADING`, `GameLoop` teleports both authoritative player transforms,
requires collision-free recovery, schedules eligible per-key CPU work, drains
completions, and processes up to two uploads per frame while continuing
clear/swap. It enters `RUNNING` only after every initial key is `RENDERABLE`,
then renders the manager's independent object collection.

`RUNNING` uses an exact `1.0 / 60.0` fixed step with an eight-step catch-up
limit, sufficient for the required 10 FPS case while preserving the existing
0.25-second frame clamp. The first catch-up step receives the full input
snapshot and later steps receive held-only snapshots so pressed edges are not
replayed. Each step runs player intent/controller, generic physics, modules,
then events. Rendering receives only the body's interpolated output.

Shutdown cancels world loading, confirms the world and mesh executors have
terminated, closes the mesh manager on the main thread to release installed
objects, and only then tears down Engine/OpenGL. Explicit per-key unload uses
the same main-thread manager boundary. Phase 3 deliberately adds no automatic
streaming or culling policy.
