# Current Architecture Baseline

## Snapshot

The current working snapshot is Phase 9A on `feat/block-interaction-core`,
based on `origin/main@078067e` after the Phase 8 body-inventory merge. Phase 9A
adds fixed-step breaking/placement, Survival/Creative policy, repository-backed
world mutation, and the single logical world-item backend without changing the
Phase 5 rendering or Phase 6 physics implementations.

The final local Phase 9A candidate passed the Windows/JDK 21 clean build with
663 Engine tests and 331 Game tests (994 total), plus all packaged-resource
checks. Final Engine and Game/shared owner review is approved with no remaining
Critical, Important, or Minor finding. Native macOS/Retina acceptance remains
not run.

The historical Phase 5A render-pipeline baseline was developed on
`feat/render-pipeline-core` from `origin/main`
`647d91d5fcab15a0acdd60e7898729e35182f71e`. Its final-review implementation
HEAD was `0ea3fa7b45162d6fb4fd48953fb61b49bb780c3f`; Task 9 documentation was
committed at `e400f6f` before the later `0ea3fa7` cleanup fix. Phase 5A
preserved the Phase 3 independent-Chunk
mesh lifecycle, approved Phase 4 deterministic world, Phase 6 fixed-step
physics foundation, and Phase 7 interaction/inventory contracts while
replacing inline shaders and direct world drawing with JAR-safe shader
resources, one ten-float voxel vertex format, non-owning runtime materials,
an ordered render pipeline, and exact OpenGL state scopes.

The repository is a two-module Gradle build:

- `engine` is a Java library containing runtime, rendering, physics, ECS, event, scheduling, and voxel infrastructure.
- `game` is an application containing Gaia-specific blocks, resources, world generation, and the `GameBootstrap` composition root.

Both modules target Java 17. The checked-in Gradle 8.5 Wrapper can run on JDK 21. LWJGL native selection in current main is based on operating system and CPU architecture.

## Engine

`com.overlord.core.Engine` is the top-level runtime owner. Its constructor:

- clamps its scheduler size to one through four logical cores;
- creates `TaskScheduler`;
- registers itself, `EventBus`, `ModuleManager`, and the scheduler in the global `ServiceLocator`.

`Engine` receives `RenderAssets` and an `AssetManager` through constructor
injection. `Engine.init()` creates `Window`, `Camera`, `Renderer`, and `World`,
initializes the renderer with those injected assets, registers the established
runtime services, starts scheduling, and marks the engine as running.
`Engine.shutdown()` stops modules and scheduling, cleans the renderer, destroys
the window, and clears global registries. Phase 5A does not register assets,
materials, queues, passes, or shader programs in `ServiceLocator`.

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
repository is the only directory, live mutation boundary, and generation
publication authority for chunks. A repository generation transaction issues
one repository-owned `ChunkGenerationTicket` per key in either `INITIAL` or
`REBUILD` mode. `commitGeneration` materializes detached
`ChunkGenerationData` and performs per-Chunk atomic publication under the
entry lifecycle; `failGeneration` retains the exact failure without exposing
partial generated bytes. `generationStatus` makes `IDLE`, `GENERATING`,
`COMMITTED`, and explicit `FAILED` failure state observable while the relevant
incarnation remains loaded or failed.

An `INITIAL` commit publishes a complete new Chunk and repository-issued
revision. A `REBUILD` ticket captures the stable loaded base revision; commit
conflicts if that revision or lifecycle changed, otherwise atomically replaces
the CPU Chunk, marks the target `DIRTY`, and invalidates only loaded neighbors
whose corresponding horizontal edge changed. Normal block changes continue to
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

## Phase 4 deterministic world generation

`WorldGenerationConfig` is an immutable deterministic seed/version/config
contract. The default fixed test seed is `12345L`, the algorithm version is
`1`, and every tuning field participates in
`canonicalFingerprintInput()`. `DeterministicCoordinateSampler` is stateless:
each sample is derived from seed, algorithm version, stage ID, absolute
coordinates, and salt. It owns no global `Random`, cached permutation table,
or call-order state.

`GaiaWorldGenerator.createDefault()` composes exactly six ordered Providers:

1. `ContinuousBiomeProvider`;
2. `BlendedHeightProvider`;
3. `DefaultStrataDensityProvider`;
4. `NoiseCaveProvider`;
5. `DefaultSurfaceProvider`;
6. `StoneOutcropDecorationProvider`.

The visually revised pipeline remains a non-normative candidate until explicit
approval. `GaiaWorldGenerator.createVisualRevisionCandidate()` also composes
exactly six ordered stages, but substitutes `BiomeShapedHeightProvider`,
`HybridCaveProvider`, and `CompositeDecorationProvider`. The composite
decoration stage rebuilds deterministic world-cell tree and outcrop
descriptors independently for every intersected Chunk. Oak log and opaque oak
leaf blocks are data-driven resources in the existing atlas; no second atlas
or item registry was introduced. `GameBootstrap` currently selects this
candidate for Windows visual review while `createDefault()` and the formal
version-1 snapshot contract remain unchanged.

`StagedWorldGenerator` creates one bounded detached `GenerationRegion` for a
key, runs those stages in declaration order, stops at the first returned or
thrown failure, rethrows `CancellationException` without converting it into a
failed Stage, and calls `freeze()` only after all six succeed. Generation is a
pure CPU Pipeline and does not read or mutate the live `World`. It has no
renderer, mesh, GPU, LWJGL, GLFW, EventBus, interaction, inventory, or
world-item dependency. Biome softmax normalization uses `StrictMath.exp`; the
correction from `Math.exp` did not change the locked version-1 block bytes or
hashes.

The default finite loader range is 81 Chunks: inclusive X/Z keys `[-4, 4]`,
ordered by ascending X and then Z. Initial loading is fail-fast and has
explicit `IDLE`, `RUNNING`, `SUCCEEDED`, `FAILED`, and `CANCELLED` states.
`WorldLoadFailure` preserves completed keys, optional failed key and Stage,
stable failure code, and exact cause. Success returns the immutable initial key
set, player feet coordinates, the configuration fingerprint, and aggregate
generation hash.

`SafeSpawnSelector` searches only committed keys within the configured block
radius. Candidates need non-air support and at least empty feet and head cells.
For each horizontal column it selects the highest valid support before ties
resolve by squared distance, X, Z, then feet Y. This prevents a hybrid cave
below the nearest surface from becoming the initial player position. No
fallback block is manufactured. If no valid candidate exists, initial loading
fails explicitly.

The debug rebuild lifecycle is programmatic only. `WorldLoader.rebuildRegion`
is a package-private orchestration helper behind public
`rebuildRegionAsync`. Both rebuild and initial `loadAsync` execute on one
constructor-injected `ExecutorService`; `GameBootstrap` owns one dedicated
`Gaia-World-Loader` executor, injects it into `WorldLoader`, and shuts it down
through the existing barrier. Each public `CompletableFuture` retains the
exact submitted `Future`; cancellation signals the generation operation and
propagates `cancel(true)` to the owned task. The loader checks that signal
before every initial publication and rebuild commit. Cancellation, each
repository commit, and successful loader-state/future completion share one
operation gate. A successful cancellation decision prevents every later
commit and `SUCCEEDED` transition. A commit or success action that wins the
gate makes the concurrently waiting cancellation attempt return `false`
without interrupting the owned task. Cancellation can still win between
rebuild keys, preserving already committed prior-key outcomes and terminally
failing only the active ticket.

`loadAsync` reserves the load lifecycle synchronously before submission.
Duplicate calls reject before enqueue, a rejected submission rolls the
reservation back to `IDLE`, queued cancellation becomes `CANCELLED` without
running a Provider, and running cancellation retains exclusive ownership until
the worker and live ticket are terminal. Rebuild snapshots, null-validates,
and sorts its requested keys before crossing the asynchronous boundary.
Rebuild then starts repository `REBUILD` tickets, continues across independent
keys, and returns per-key `COMMITTED`, `FAILED`, or `CONFLICT` outcomes. A
successful replacement remains `DIRTY`; Phase 3 revision/dirty/stale
authority rejects old CPU mesh or upload work and preserves the installed
render object until a current replacement succeeds.

Phase 7 gameplay mutation exclusion remains authoritative: detached generation
does not call `WorldMutationService`, publish `BeforeBlockChangedEvent` or
`BlockChangedEvent`, touch inventory/world-item state, or use live-world
`World.setBlock`. Gameplay writes continue through the Phase 7 mutation
contract; generation publication continues through repository generation
transactions.

The locked default-region aggregate SHA-256 is
`161f6c10773c8dfd84e6961183e8706d5a0ec00750e727e83c4a08afcfbd5ce8`.
Manual coordinates are plains `(29, -45)`, rolling hills/origin `(0, 0)`,
rocky highlands `(0, 44)`, cave and positive boundary `(16, 2, 0)`, and
negative coverage `(-1, -1)`. The byte contract, representative Chunk hashes,
and intentional-update protocol are normative in
`docs/architecture/phase-04-deterministic-snapshots.md`.

The approved visual revision is algorithm version `2`. Its locked
configuration fingerprint is
`56cb2f243319c7cf275ade89f480f9208ce5c1f85334eb225e6b56ed18e3012a`
and its seed-12345 81-Chunk aggregate hash is
`ec2c76a97f36d34b7360ae9abbb0be60fb8790f275fdaf5227a7daeae9754353`.
The preceding version-1 review candidate remains historical evidence only.
The user approved the terrain, decoration, and cave direction with deferred
rendering limitations and later confirmed that an entrance was found.

## Renderer

### Phase 5B visual rendering baseline

Phase 5B extends the existing renderer with shader-linear lighting, ambient
occlusion, sky/fog, conservative frustum culling, DPI-aware surface handling,
and frame-local observability. The normative details are in
`docs/architecture/phase-05b-rendering-contract.md`.

The current defaults are normalized sun input `(-0.45, 0.85, -0.30)`, ambient
`0.38`, directional `0.72`, linear sky top `(0.035, 0.160, 0.470)`, linear
horizon/fog `(0.350, 0.570, 0.780)`, and fog `64` through `160`. Atlas colors
are decoded in the world fragment shader, lit and fogged in linear space, then
encoded once. The sky shader encodes its linear gradient once. Framebuffer
sRGB remains disabled to avoid a second encoding.

Workers now mesh from a 3-by-3 immutable horizontal snapshot neighborhood,
including diagonals. AO uses two side samples and a corner sample per face
corner, with factors 1.00/0.82/0.65/0.45 and both sides taking precedence.
Chunk dirty propagation includes diagonal halo dependencies. GPU creation,
upload, replacement, release, rendering, resize, and cleanup remain on the
context-owning main thread; stale work is still rejected by repository
revision/lifecycle checks.

Frustum tests use normalized planes and a conservative 0.01 epsilon. A zero
framebuffer suppresses drawing but still completes frame cleanup and metrics;
a positive restoration rebuilds the viewport/projection. Metrics reset each
frame and report FPS, frame time, visible Chunks, draw calls, triangles, and
mesh queue depth without implying a profiling baseline.

Fresh Task 13 Windows automation at `c247ec1` passed `clean test build` and
all three standalone resource checks. XML totals are Engine 69 suites / 642
tests and Game 30 suites / 251 tests (99 suites / 893 tests total), with zero
failures, errors, and skips. Development and installDist acceptance both
exited `0`; metrics stabilized near 100 FPS, camera rotation changed visible
and draw counts, resize/input/focus behavior passed, and the installed shader
and texture resources rendered normally. Exact numeric player position and
orientation were not captured, so no same-view FPS comparison is claimed.
Both code-owner reviews approved the implementation contingent on this
documentation refresh; their documentation currency finding is resolved here.
Native macOS remains **NOT RUN**.

`Window` initializes GLFW, requests an OpenGL 4.1 core forward-compatible
context, makes it current, then creates LWJGL OpenGL capabilities. `Renderer`
remains the frame coordinator and `ChunkRenderBackend` GPU boundary, but no
longer embeds shader source or owns a direct `clear`/`renderChunks` path.
It loads `overlord:shaders/world.vert` and
`overlord:shaders/world.frag` through the injected Phase 2 `AssetManager`,
creates one guarded `ShaderProgram`, uploads one shared atlas `Texture`,
creates one non-owning runtime `Material`, and constructs one reusable
`RenderQueue` plus the immutable pass order `sky`, `world`, `debug`.

The shader resources are GLSL `#version 410 core` JAR entries. The required
uniform set is `projection`, `view`, `model`, and `textureAtlas`; locations are
resolved once at successful link and cached. Compile, link, missing-uniform,
and cleanup failures preserve the program/stage/resource context; a missing
uniform names both the vertex and fragment `ResourceLocation` values. Partial
shader/program resources are deleted before failure escapes. Cleanup retains
the primary shader failure, suppresses only distinct cleanup failures, and
avoids self-suppression when a backend throws the same instance. `ShaderProgram`,
`Mesh`, and `Texture` own their OpenGL object IDs. Runtime `Material` only
references the shared program and texture and has no cleanup/close ownership;
`Renderer` releases the texture and then the program exactly once.

`VoxelVertexFormat` is the only vertex-layout authority. Every vertex is ten
floats / 40 bytes: position `vec3` at location 0/offset 0, UV `vec2` at
location 1/offset 12, normal `vec3` at location 2/offset 20, encoded
face/light `float` at location 3/offset 32, and ambient occlusion `float` at
location 4/offset 36. Stable face IDs are NORTH=0, SOUTH=1, UP=2, DOWN=3,
WEST=4, EAST=5, and `encodedFaceLight = faceId * 16 + lightLevel`. Phase 5A
emits light level 15 and AO 1.0 while its shaders intentionally consume only
position and UV to preserve the approved Phase 4 visual result.

Each visible frame submits current independent `ChunkRenderObject` instances
to the queue, executes `SkyRenderPass`, `WorldRenderPass`, then the disabled
`DebugRenderPass`, and clears the queue in `finally`. Sky disables depth test,
keeps depth writes on, disables blend/cull, and clears color/depth. World
opaque enables depth test/writes and disables blend/cull. The transparent API
enables depth test, disables depth writes, uses source-alpha blending, and
keeps culling off. Every state scope captures and restores depth-test
enablement, depth write mask, blend enablement plus RGB/alpha factors and
equations, cull enablement, current program, active texture unit, and texture
unit 0's 2D binding, including exceptional paths. If applying requested pass
state fails after capture, scope construction immediately restores the
incoming snapshot before rethrowing the original failure; any rollback failure
is suppressed without replacing that primary failure.

Terrain rendering uses independent `ChunkRenderObject` instances. Each object
binds a `ChunkKey`, source revision, owned GPU mesh, chunk-local model
translation, and world-space bounds. `Renderer.upload`, `release`, and
`renderFrame` assert the captured `MainThreadGuard`; the renderer no longer
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
- `Renderer.init`, `renderFrame`, framebuffer resize, program/texture
  creation and cleanup, VAO/VBO upload/draw/release, state capture/apply/
  restore, and uniform upload all assert the captured `MainThreadGuard`.
  Workers may produce `ChunkMeshData`; they cannot create or manipulate GPU
  resources, materials, passes, or state scopes.
- Failed uploads preserve the installed render object and remain explicit
  failures; stale and unloaded results perform no GPU action.
- The manager checks `READY_FOR_UPLOAD` state and revision before charging the
  normal upload budget and again immediately before the backend call. A narrow
  concurrent-mutation window between those checks can consume one frame-budget
  slot without performing a GPU upload; correctness and resource ownership are
  preserved.
- Phase 3 exposes bounds but Phase 5A still does not implement frustum
  culling, batching, LOD, transparent sorting, or automatic streaming. The
  transparent queue API keeps stable insertion order, but production chunks
  are not split by material and there is no end-to-end transparent geometry
  path.

All future renderer work must remain compatible with OpenGL 4.1 / GLSL 410 and must keep every OpenGL call and GPU resource create/upload/destroy action on the main/context-owning thread.
Phase 5B may add deliberate lighting, AO evaluation, sky/fog/gamma, and
culling only without breaking the vertex layout, shader/material resource
identity, exact state restoration, pass/queue cleanup, or Phase 3
revision/dirty/stale ownership documented in
`docs/architecture/phase-05a-render-contract.md`.

## Phase 7 contracts

### Interaction, body inventory, and logical world items

- `ItemStack(ResourceLocation itemId, int count)` is the canonical immutable
  command value with a positive count. `ItemStackView` is only a read-only
  snapshot/projection; it is not a second stack domain type. There is no item
  registry or alternate item identity in this phase.
- `InventoryService` retains snapshot and optimistic slot replacement and now
  defines `reserve`, `commit`, and `rollback` for `INSERT` and `EXTRACT`.
  `InventoryReservation` protects the accepted amount from ordinary later
  state changes. Full, partial, remainder, explicit failure, terminal
  conflict, and idempotent repeat semantics are contract values.
- `WorldItemService` is the single source of truth for stable `WorldItemId`
  instances, spawn requests/results, revisioned snapshots, partial
  reservations, commit, and rollback. Q drop, block drops, pickup, and future
  Phase 11 physics drops must share it.
- `BodyInventoryService` is the production main-thread mutation boundary for
  LEFT_HAND, RIGHT_HAND, and MOUTH. It implements slot rules, atomic two-hand
  ownership, snapshots/events, insertion/extraction, and protected reservation
  commit/rollback. Number keys, wheel selection, Q drop, and opt-in debug tools
  are wired through the fixed-update input path.
- `LogicalWorldItemService` is the single production logical store. It owns Q
  drops and block-drop future spawns in one stable ID namespace, preserves
  canonical stacks and immutable snapshots, records pickup delay, and supports
  existing-item and future-spawn reservations. It has no renderer or PhysicsBody;
  physical drop motion and pickup remain Phase 11.
- `ChunkRepository.compareAndSetBlock` owns the atomic
  `ChunkMutationOutcome`, dirty propagation, and exact issued
  `DirtyChunkRevision` values. It reports only actually loaded boundary
  neighbors. `World` delegates, and the resource-level
  `BlockWorldMutationOutcome` preserves those facts. Phase 3 stale-result
  rejection and mesh lifecycle remain authoritative.
- Gameplay block writes use synchronous `WorldMutationService`.
  `DefaultWorldMutationService` now takes only `MainThreadGuard`,
  `BlockWorldAccess`, and `BlockChangeEventPublisher`; it has no
  `ChunkDirtyTracker` dependency and does not derive invalidation candidates.
  It publishes `ChunkDirtyEvent` only after an applied repository outcome,
  using exact committed revisions.
- Before-event reentrant mutation through the same service is prohibited for
  every target. Post-Before revalidation remains. A post-write subscriber
  failure cannot roll back; both post events are attempted, there is no
  automatic retry, and `mutationApplied() == true` prohibits blind caller
  retry.
- `GaiaBlockRaycastService` maps the unique Phase 6 raycast to resource IDs.
  `PlayerBlockTargeting` uses authoritative PhysicsBody position plus eye height
  and Camera direction. `GaiaBlockWorldAccess` is the resource adapter over the
  repository-issued compare-and-set outcome.
- `BlockInteractionController` runs on the 1/60 fixed path. Survival breaking
  uses held left-button hardness timing and reserves inventory/world-item capacity
  before air mutation; Creative is instant, press-edge-only, and produces no drops.
  Both placement modes are right-button press-edge-only. Placement validates a
  loaded air destination and player AABB before reserving one Survival
  extraction. F4 toggles mode on an edge, cancels, and stops processing for that
  step; it does not enable noclip.
- `BlockInteractionViewModel` extends the protected Phase 7 read-only view with
  crack stage and game mode. No Phase 9A rendering consumes it yet.
- Direct gameplay block writes do not call `World.setBlock`. Generation retains
  its separate bulk-generation API, while interaction uses only
  `WorldMutationService` and repository-owned dirty propagation.
- Breaking/placement visuals, physical world-item bodies, pickup, persistence,
  complex placement shapes, tools/loot, and formal HUD/UI remain deferred.

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

`GameBootstrap` is the composition root. It creates one `AssetManager`, uses
it to load Gaia's data-driven block atlas/material catalog and engine-owned
shader resources, injects the resulting `RenderAssets` and the same manager
into `Engine`, starts the engine, constructs the approved version-2 immutable
world-generation config and six-stage CPU generator, one shared default
block-shape resolver,
`CollisionWorld`, `BlockRaycast`, player body, `PlayerController`, and
`PhysicsWorld`, creates one world-loading executor and two named chunk-meshing
workers, constructs `ChunkMeshManager` with an upload budget of two, and
registers a shutdown barrier around worker, GPU-manager, and engine cleanup.

`WorldLoader.loadAsync` generates the default finite 81-key set on the
injected world executor through one detached CPU Pipeline execution and one
repository transaction for each Chunk. After all commits it selects explicit
safe player-feet coordinates and computes the canonical aggregate hash through
`WorldGenerationHasher.hashRegion`. During `LOADING`, `GameLoop` takes an
explicit failure branch if the loader fails; on success it teleports both
authoritative player transforms, requires collision-free recovery, schedules
eligible per-key CPU meshing, drains completions, and processes up to two
uploads per frame while continuing clear/swap. It enters `RUNNING` only after
every initial key is `RENDERABLE`, then renders the manager's independent
object collection.

`GameLoop` calls exactly one `Renderer.renderFrame` for each visible frame:
an empty collection while loading and the mesh manager's current independent
render objects while running. Mesh completion/upload pumping and camera
interpolation still precede rendering; buffer swap and input polling ownership
are unchanged.

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

The Game-owner review fixes at `349c81c`, async-contract follow-up at
`9f18cf6`, and atomic-gate follow-up at `72a08dd` added an end-to-end default
loader snapshot, strict biome math, Stage-cancellation propagation,
cancellable submitted-task bridging, synchronous lifecycle reservation,
active cancellation exclusivity, immutable rebuild-request coverage, and
atomic cancellation/commit/success winner semantics. The locked aggregate
remains
`161f6c10773c8dfd84e6961183e8706d5a0ec00750e727e83c4a08afcfbd5ce8`.
The 2026-07-25 pre-hardening Windows visual-candidate run completed
successfully. The revised safe-spawn selection produced an open grass-surface
start; plains, trees, rolling terrain, sparse rocky highlands, F1 cursor
release, framebuffer resize from 1026-by-607 to 2048-by-1104 and back, and
Escape shutdown were observed. Later deterministic edge hardening preserved
the candidate hash. The user subsequently approved the terrain, tree, outcrop,
entrance, chamber, and cross-Chunk tunnel direction with deferred rendering
limitations. The production visual revision now uses algorithm version `2`;
the deterministic sampler therefore generated a new canonical contract rather
than reusing candidate version-1 values. Its configuration fingerprint is
`56cb2f243319c7cf275ade89f480f9208ce5c1f85334eb225e6b56ed18e3012a`
and its 81-Chunk aggregate hash is
`ec2c76a97f36d34b7360ae9abbb0be60fb8790f275fdaf5227a7daeae9754353`.
The final Windows `clean test build` at implementation HEAD `0ea3fa7` passed
all 22 actionable tasks. Final XML contains 60 Engine suites / 574 tests and
29 Game suites / 249 tests (823 total), with zero failures, errors, or skips.
Standalone packaged-resource checks also passed after `0ea3fa7` and confirmed
both shader entries in the engine JAR and installed engine JAR.

Owner review found two failure-path gaps. Commit `0fe593b` now restores the
captured state when `RenderStateScope` pass-state application fails and
preserves rollback failure as suppressed. Commit `e603946` now includes both
world shader resource identities in a missing-uniform diagnostic. Both
findings have focused regression coverage and passed the complete gate above.
The earlier Engine-owner re-review confirmed both findings closed and returned
**APPROVED**, with no remaining Critical, Important, or Minor finding at that
review point.

A later final branch review found two additional Minor issues. Minor 1 was a
same-instance cleanup self-suppression path in `ShaderProgram`; `0ea3fa7`
preserves the primary failure, has a focused RED/GREEN regression (15/15), and
passed Task 7 integration plus the final 22/22 clean build. Minor 2 was stale
Task 9 documentation/checkbox state; the current documentation correction
records the completed `e400f6f` commit and final implementation HEAD. Both
Minors are fixed; final branch re-review is **APPROVED**, with no remaining
Critical, Important, or Minor finding.

The controller's valid serial Windows development run rendered the current
grass/dirt/stone atlas and approved plains/tree, rolling-hills, and rocky
highland/outcrop directions without a black-screen or inline-shader
regression. Movement, jump input, mouse-look, F1 release/recapture, maximize
resize, focus loss/restore, and Escape shutdown were observed; Gradle ended
`BUILD SUCCESSFUL` with exit code 0. The installDist launcher rendered the
same hills/trees/materials, accepted mouse-look, and closed through Escape
with exit code 0. The cave entrance was not re-navigated under the user's
earlier explicit waiver that the entrance had already been found. Screenshots
were inspected in-app only and were not committed.

The implementation fixes after manual acceptance affect failure handling,
diagnostics, and same-instance cleanup preservation only; Minor 2 is
documentation-only. They do not change normal render output, shader source,
vertex data, pass state, world generation, or input behavior, so the valid
serial Windows development and installDist exit-0 evidence was retained
without another GUI run. Final Game/shared-owner
re-review confirmed the `Renderer.renderFrame` documentation Minor closed and
returned **APPROVED**, with no remaining Critical, Important, or Minor finding
at that review point. The later final-branch two-Minor re-review is also
**APPROVED** with no remaining finding. Native macOS clean-build and interactive verification are
**NOT RUN**; no Windows result is used to infer macOS runtime success.
