# Current Architecture Baseline

## Snapshot

This document describes `origin/main` at commit `1f260e1` (`fix: resolve ECS EntityManager generic type compilation errors`), fetched on 2026-07-23 before the Phase 0 branch was created.

The repository is a two-module Gradle build:

- `engine` is a Java library containing runtime, rendering, physics, ECS, event, scheduling, and voxel infrastructure.
- `game` is an application containing Gaia-specific blocks, resources, world generation, and the `GaiaMain` composition root.

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

`World` stores chunks in a `HashMap<String, Chunk>` keyed as `"x,z"`. Both `getChunk` and block reads lazily create absent chunks. World coordinates use `floorDiv`/`floorMod` to map correctly across negative chunk coordinates.

`Chunk` divides a configurable vertical range into lazily allocated `SubChunk` instances. `SubChunk` stores block IDs in a flat byte array and tracks a dirty flag. Setting the last non-air block to air removes an empty subchunk.

Current boundaries and risks:

- The world, chunk maps, subchunk maps, arrays, and dirty flags are mutable and not thread-safe.
- Reads can mutate world structure by allocating missing chunks.
- Chunks are addressed by allocated strings rather than a value key.
- Dirty state is not yet connected to a remesh pipeline.
- Persistence, streaming, unloading, block update events, and synchronization are absent.

The current block-coordinate behavior, 16-by-16 chunk footprint, sparse vertical allocation, and `byte` block IDs are active interfaces for game generation, physics, and meshing.

## Renderer

`Window` initializes GLFW, requests an OpenGL 4.1 core forward-compatible context, makes it current, then creates LWJGL OpenGL capabilities. `Renderer` enables depth testing, compiles embedded GLSL 410 shaders, loads the texture atlas, and creates a fallback mesh. `Mesh`, `Shader`, and `Texture` directly own OpenGL object IDs and delete them in `cleanup()`.

The current render path is a single textured mesh with projection, view, and model uniforms. `GaiaMain` performs CPU terrain meshing on a worker and transfers only `float[]` mesh data through an `AtomicReference`; it creates/uploads the `Mesh` and renders it on the main loop thread.

Current boundaries and risks:

- There is no runtime assertion that OpenGL calls occur on the context-owning thread.
- Resource ownership is manual and can double-delete: the application cleans its chunk mesh, then `Renderer.cleanup()` cleans the currently assigned mesh again.
- Replacing the renderer mesh does not define ownership of the previous mesh.
- Resize handling, render queues, batching, chunk-level resources, and diagnostics are absent.

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

`GaiaMain` is the composition root and currently combines registry initialization, engine startup, asynchronous terrain generation, spawn selection, player/physics construction, input callback installation, FPS counting, GPU mesh replacement, rendering, and shutdown.

This concentration is a Phase 1 design concern, not a Phase 0 change. A later split must retain the safe part of the current threading model: worker threads may generate world and CPU mesh data, while the main/context-owning thread alone performs GLFW interaction and OpenGL resource lifecycle work.
