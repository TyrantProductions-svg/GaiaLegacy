# Phase 9B Interaction Feedback Design

Status: approved conversational design, written for repository review

Date: 2026-07-28

Branch: `feat/block-interaction-feedback`

Base: `origin/main` at `51cb3f23b7ebf9a8999451ac2cf3defb9eec2ceb`

## Goal

Connect Phase 9A's read-only interaction state and committed mutation facts to
the Phase 5B render pipeline. Phase 9B adds a centered crosshair, a ten-stage
block-damage overlay, restrained CPU particles, and visible logical world
items. It does not change targeting, mutation, inventory, world-item, Chunk,
physics, or fixed-step gameplay semantics.

The implementation remains Java 17 compatible, uses JDK 21 only as a build
runtime, and is restricted to OpenGL 4.1 and GLSL 410. Every OpenGL resource
operation remains on the context-owning main thread.

## Verified repository baseline

The startup audit was performed after `git fetch --prune origin` and found:

- `feat/block-interaction-feedback`, local `main`, and `origin/main` all at
  `51cb3f23b7ebf9a8999451ac2cf3defb9eec2ceb`;
- branch divergence from `origin/main` is `0/0`;
- the worktree and index are clean;
- Phase 5B is present on `origin/main` through squash commit `34c8d3f`;
- Phase 9A is present on `origin/main` through squash commit `51cb3f2`;
- engine and game compile for Java 17;
- the active build runtime is JDK 21;
- production shaders use `#version 410 core`;
- production code contains no OpenGL 4.2+ dependency, compute shader, SSBO,
  engine-to-game import, or platform-specific JDK path.

The status prose in `current-baseline.md` and the Phase 9A handoff is a
historical pre-merge snapshot. Git tree identity is authoritative for the
startup gate.

## Scope boundaries

### Required

- a static white crosshair centered in framebuffer pixels;
- a ten-stage alpha-cutout damage atlas and one-target cube overlay;
- low-frequency temporary break particles;
- a 24-particle burst produced only from a committed break fact;
- stable-ID visual instances derived from immutable world-item snapshots;
- complete GL state restoration on normal and exceptional pass exit;
- explicit main-thread GPU creation, streaming, draw, and cleanup;
- JAR and installDist resource verification;
- focused RED/GREEN tests and complete regression verification.

### Explicit non-goals

- any change to Phase 9A raycast, state machines, fixed timing, modes,
  reservation protocols, mutation ordering, conservation, or dirty tracking;
- formal HUD, inventory UI, text, counts, mode labels, or adaptive crosshair;
- Chunk texture mutation or damage-triggered mesh rebuilds;
- GPU compute particles, collision particles, particle lighting, or complex
  transparency sorting;
- world-item gravity, bounce, pickup, merging, lifetime, persistence, or
  PhysicsBody integration;
- new ItemStack, item registry, world-item model, or authoritative store;
- OpenGL above 4.1, GLSL above 410, compute shaders, or SSBOs.

## Selected architecture

`BlockInteractionViewModel` is game-owned while `Renderer` is engine-owned.
Directly passing the game type into Renderer would create a forbidden
engine-to-game dependency. Phase 9B therefore uses a game-side presentation
adapter:

```text
BlockInteractionViewModel ----+
WorldItemSnapshot list -------+--> InteractionFeedbackCoordinator (game)
BlockChangedEvent ------------+                  |
                                                  v
                                immutable InteractionFeedbackFrame (engine)
                                                  |
                                                  v
                                      RenderFrameInput -> Renderer
```

The coordinator is a transient presentation component. It is not a gameplay
service and is not an authority for blocks, items, inventory, or interaction.
It reads the Phase 9A view model, receives committed post-write facts, accepts
immutable world-item snapshots, advances CPU visual particles, and produces a
defensive immutable frame value.

The engine owns the generic presentation records and all GPU-backed render
passes. The game maps Gaia-specific block and item identities through the
existing `BlockRegistry` and `ItemFormDefinition` into engine render regions.
Renderer never receives `WorldItemService`, `WorldMutationService`, Inventory,
Raycast, or InteractionController.

## Frame presentation contract

`InteractionFeedbackFrame` is an engine-owned immutable aggregate containing:

- lifecycle flags: running, cursor captured, focused, interaction blocked;
- an optional immutable block-damage visual;
- an immutable ordered world-item visual list;
- an immutable particle render batch.

`RenderFrameInput` carries this aggregate beside the existing Chunk list,
frame delta, and mesh-queue depth. Record constructors defensively copy all
collections and mutable math values. No public accessor exposes a mutable
collection or mutable JOML object.

The frame is created on the main thread after fixed interaction updates and
before rendering. Renderer reads it once for that frame.

Focus is read through a new main-thread, read-only
`InputManager.isWindowFocused()` accessor over the existing GLFW callback
state; it does not create a second focus or input authority. Blocking UI is
read through an injected game-side `InteractionBlockState` interface whose
Phase 9B production binding is the constant unblocked state. Phase 10 may
replace that binding without changing Renderer or the frame contract.

## Render-pass order

The approved order is:

1. `SkyRenderPass`
2. `WorldRenderPass`
3. `BlockDamageOverlayPass`
4. `WorldItemVisualPass`
5. `ParticleRenderPass`
6. `DebugRenderPass`
7. `CrosshairRenderPass`

The overlay follows opaque world geometry and retains depth testing. World
items and particles then read the completed world depth. Debug world content
remains below the screen-space crosshair. The crosshair is last and cannot be
occluded by world geometry. Existing opaque/transparent Chunk queue ownership
is unchanged.

## Crosshair contract

The crosshair is four pure-white screen-space quads and uses no texture.

- total horizontal and vertical span: 16 framebuffer pixels;
- thickness: 2 framebuffer pixels;
- center gap: 4 framebuffer pixels;
- arm length: 6 framebuffer pixels;
- center: `(framebufferWidth / 2.0, framebufferHeight / 2.0)`;
- horizontal arms: `[cx - 8, cx - 2]` and `[cx + 2, cx + 8]` with
  `[cy - 1, cy + 1]` thickness;
- vertical arms use the same construction with x and y exchanged.

Floating-point pixel coordinates preserve the exact geometric center for both
odd and even framebuffer dimensions. Pixel coordinates are converted to NDC
using the current framebuffer dimensions every drawable frame. Logical window
dimensions are never used for crosshair placement or size.

The crosshair is visible only when all of the following are true:

- game state is RUNNING;
- gameplay cursor is captured;
- the window is focused;
- no blocking interaction UI is active;
- framebuffer width and height are positive.

F1 release, focus loss, loading, shutdown, or a blocking UI state hides it in
the current render frame. Recapture or focus regain may show the crosshair but
does not restore any prior interaction session.

The pass disables depth test, depth writes, blending, and culling, sets the
current full-framebuffer viewport, draws the four quads, and restores every
touched state.

## Damage atlas and overlay

### Resource

The damage resource is:

`assets/gaia/textures/effects/block_damage.png`

It is a horizontal `160 x 16` RGBA image containing ten `16 x 16` stages. It
does not alter or rearrange the shared block atlas. Sampling is nearest-only,
level zero only, and clamp-to-edge. Each stage uses a half-texel inset.

The atlas is generated specifically for GaiaLegacy by the checked-in,
pure-Java `tools/src/main/java/com/gaia/tools/BlockDamageAtlasGenerator.java`.
The generator has no third-party dependency, uses a fixed seed plus fixed
pixel-segment definitions, and writes the exact resource path above. It is run
explicitly with the active JDK and is not invoked by normal game startup or by
the ordinary build. The generated PNG, command, dimensions, and SHA-256 are
recorded in the architecture and handoff documents.

Missing or invalid damage data logs the requested ResourceLocation and cause,
then uses a deterministic black/magenta fallback crack texture. The fallback
is visually conspicuous and never silently becomes an uncracked block.

### Stage selection

The Phase 9A `crackStage()` value is authoritative when a valid Survival break
session is present. A pure generic fallback mapper is defined for tests and
future resource-count changes:

`stage = min(stageCount - 1, floor(progress * stageCount))`

The mapper accepts stage counts from 8 through 10. Progress at or below zero
does not produce a visible overlay. Progress exactly one maps to the final
stage. The shipping resource uses ten stages.

The overlay is visible only for a current Survival BREAKING view with a target
and positive progress. Target change uses the new target immediately. Session
cancellation, zero progress, mode switch, unload, out-of-range loss, cursor
release, focus loss, loading, or interaction blocking produces no overlay.
Creative instant breaking has no sustained overlay.

### Geometry and depth

One shared unit-cube overlay mesh is transformed to the target block. The
Chunk mesh, shared block atlas, Chunk revision, dirty tracker, and mesh manager
are never touched.

The sole Z-fighting strategy is polygon offset:

- `GL_POLYGON_OFFSET_FILL` enabled;
- factor `-1.0f`;
- units `-1.0f`;
- no model expansion.

The pass enables depth testing with `LEQUAL`, disables depth writes, blending,
and culling, and discards fragments with alpha below `0.1`. It does not rely on
transparent sorting.

## Particle system

The engine provides a reusable CPU `ParticleSystem`. Game code submits generic
immutable emission requests; it does not manipulate GPU buffers.

Each particle contains:

- position;
- velocity;
- age and lifetime;
- size;
- resolved texture region or fallback region;
- visual category (`BREAK_CONTINUOUS` or `BREAK_COMMITTED`);
- monotonically increasing spawn sequence for deterministic ordering.

The system uses fixed `1/60` updates and a hard cap of 512 particles.

- active Survival breaking emits one small particle every ten fixed steps,
  approximately six per second;
- each committed break emits exactly 24 burst particles before cap handling;
- lifetime is deterministically distributed from 0.35 through 0.75 seconds;
- position advances as `position += velocity * fixedDelta`;
- no collision, gravity simulation, or PhysicsBody is introduced;
- expired particles are removed completely;
- when full, the oldest spawn sequence is overwritten.

Emission direction, size, and lifetime derive from a deterministic hash of the
event or target coordinates, tick, category, and local particle index. No
shared `Random` or scheduling-dependent state is used.

Continuous particles derive material identity from the current target in the
view model and stop immediately on cancellation, target loss, F1, focus loss,
mode switch, loading, or blocking UI. They are temporary feedback and do not
claim a mutation occurred.

Completion bursts are produced only by an accepted committed visual event.
Before cancellation, mutation rejection, reservation failure, and ordinary
session cancellation cannot produce that event. Existing completion particles
continue to age naturally while interaction is blocked.

`ParticleRenderBatch` is a defensive immutable snapshot. The render pass
streams expanded small textured-cube vertices to one dynamic VBO on the GL
owner thread. It uses the existing block atlas and resolved regions, not a new
material registry. Initialization failure and shutdown release its VAO/VBO
exactly once.

## Committed break visual events

The repository has no separate `BlockBrokenCommitted` type. The existing
post-write `BlockChangedEvent` is the equivalent committed fact because it is
published only after the compare-and-set mutation reports APPLIED.

The game-side adapter accepts a completion event only when:

- `request.context().action()` is PRIMARY;
- the previous block is not air;
- the current block is air.

Coordinates come from the request. The old material identity comes from
`previousBlock`; the adapter never queries the mutated World to reconstruct
old state. `BlockRegistry` resolves the immutable visual region from that
identity.

The current committed event contract provides no stable event ID, so Phase 9B
does not invent a cross-call deduplication guarantee. One synchronous committed
delivery creates one burst. A later legitimate committed mutation at the same
position creates another burst.

The visual subscriber records every failure through a diagnostic sink. A
recoverable `RuntimeException` is contained so presentation failure does not
turn an otherwise successful interaction into a gameplay notification error.
A fatal `Error` is diagnosed and rethrown; the existing mutation service then
marks the dispatch failure as `mutationApplied=true`, so Phase 9A still commits
its guaranteed reservations and never rolls the block back. No visual failure
is automatically retried. The Phase 9A transaction implementation and event
order are unchanged.

## World-item visuals

`LogicalWorldItemService.snapshots()` is called by game composition on the
main thread. The resulting immutable list is adapted before rendering.
Renderer never receives or calls the service.

The presentation cache is a `LinkedHashMap<WorldItemId,
WorldItemVisualInstance>`. It is not an authoritative world-item store. Each
instance contains only:

- the canonical stable ID;
- source revision;
- immutable render transform values;
- resolved texture region or fallback region.

It does not contain a mutable stack, reservation state, pickup rules, or a new
logical position. Incoming snapshots drive a diff:

- a new stable ID creates one instance;
- an existing ID with changed revision updates that instance;
- an absent ID removes the instance;
- changed input ordering does not change identity or create duplicates.

The cube has an edge length of 0.25 block. Its position exactly matches the
logical snapshot. Phase 9B adds no rotation, hover offset, gravity, bounce,
pickup, merge, expiry, persistence, or PhysicsBody. Item material resolution
uses canonical `ItemStack.itemId()`, the existing `ItemFormDefinition`, and
`BlockRegistry`. Unsupported items use the explicit missing region. Phase 11
may update the same stable ID's logical position and attach physics without
creating a replacement ID.

## Complete GL state ownership

The existing Phase 5 state scope is extended because the new passes touch more
state than its current snapshot records. Capture and restoration cover:

- current program;
- vertex-array binding;
- array-buffer binding;
- element-array-buffer binding;
- active texture unit;
- the texture-2D binding on each texture unit touched by a pass;
- depth-test enable;
- depth function;
- depth write mask;
- blend enable, factors, and equations;
- cull enable;
- polygon-offset-fill enable, factor, and units;
- viewport x, y, width, and height.

New passes do not change cull mode, front-face winding, scissor, framebuffer,
stencil, color mask, sampler objects, or framebuffer-sRGB state. If later code
needs to change one of these values, it must first add it to the scope contract.

Every pass opens its scope before applying state and closes it from
try-with-resources. Apply failure attempts restoration and suppresses a
secondary restoration failure onto the primary exception. Draw failure also
restores state before propagation. Tests use a recording backend that asserts
the complete snapshot, not only call occurrence.

All shader, texture, VAO, VBO, and EBO creation, update, draw, and cleanup is
guarded by `MainThreadGuard`. Renderer remains the owning cleanup boundary.
Partial initialization cleans successful resources in reverse order. Repeated
cleanup cannot release an object twice.

## Lifecycle integration

Interaction fixed updates remain before rendering. The frame sequence is:

1. poll window/input and process lifecycle invalidation;
2. run zero or more fixed gameplay updates;
3. receive committed visual facts synchronously during mutations;
4. advance fixed-step presentation state for the steps that ran;
5. snapshot current interaction and world-item presentation state;
6. render the immutable frame;
7. swap buffers.

F1 and focus invalidation update presentation lifecycle flags even when a
frame has zero fixed steps. This immediately hides crosshair and overlay and
stops continuous emission eligibility. It does not add another mouse state
machine and does not alter Phase 9A suppression/re-arm behavior.

Loading supplies an empty interaction/world-item frame with crosshair hidden.
Shutdown stops producing frames, clears CPU presentation state, and releases
GPU resources through the existing reverse-order coordinator.

## Failure semantics

- Shader absence or compilation/link failure reports the shader
  ResourceLocation and diagnostic text, then reverses partial initialization.
- A missing or invalid damage atlas reports its ResourceLocation and uses the
  explicit fallback.
- Unsupported item or block visuals use the existing missing region and emit a
  diagnostic without changing gameplay data.
- A committed visual-event consumer failure is diagnosed; recoverable runtime
  failures are contained, fatal errors remain observable, and neither case
  rolls back or automatically retries gameplay.
- A render-pass exception restores GL state and remains observable to the
  existing runtime failure path.
- A zero-sized framebuffer skips drawing without using stale logical-window
  dimensions.
- Particle overflow deterministically replaces oldest visuals and never
  changes committed item counts.

## TDD and verification gates

### Gate 9B.1: presentation frame and crosshair

RED tests cover 1024x768 geometry, odd/even framebuffer dimensions, logical
versus framebuffer sizes, resize, F1, focus loss/regain, recapture, loading,
blocking UI, complete normal/exception state restoration, and forbidden
gameplay dependencies.

### Gate 9B.2: damage overlay

RED tests cover 8/9/10-stage mapping, zero/completion boundaries, target
change, cancellation, mode switch, unload, lifecycle clearing, atlas fallback,
full normal/exception state restoration, and zero calls to mutation, Chunk
revision, dirty, or mesh rebuild APIs.

### Gate 9B.3: particles

RED tests cover fixed-step emission, 24-particle committed burst, committed
exactly-once delivery, cancellation and failure exclusion, 512 cap, oldest
replacement, deterministic lifetime, expiry, material snapshot use, immutable
batching, GL-thread upload, complete state restoration, and cleanup exactly
once.

### Gate 9B.4: world-item visuals

RED tests cover stable-ID add/update/remove/reorder, one instance per ID,
fallback resolution, defensive snapshots, exact logical position, no mutation
dependency, no second store/model, complete state restoration, and cleanup.

### Gate 9B.5: integration

RED tests cover complete pass order, fixed-update-before-render, committed
fact conversion, F1/focus/mode/loading clearing, zero-fixed-step lifecycle
handling, shutdown, and normal/exception state restoration.

Every production change follows a recorded RED failure, the smallest
architecture-consistent implementation, focused GREEN, and the related
inventory/interaction/render regression suite before the next gate.

Final verification runs engine tests, game tests, clean test build, all three
packaged shader/resource tasks, diff/hygiene scans, Windows development and
installDist interactive checks, Engine-owner review, Game/render-owner review,
and a final branch-wide read-only review.

The current environment has no macOS host. macOS native launch and Retina
acceptance will therefore be recorded as `NOT RUN`, never as passing.

## Approved decisions

- ten-stage horizontal damage atlas with deterministic project-owned source;
- polygon offset only, factor and units both `-1.0f`;
- fixed-step CPU particles, cap 512, burst 24, six-per-second continuous
  emission, 0.35-0.75 second lifetime, oldest replacement;
- 0.25-block textured cube world-item visuals;
- 16/2/4 framebuffer-pixel crosshair geometry;
- game-side immutable presentation adapter and engine-owned render payload;
- recommended seven-pass order;
- existing `BlockChangedEvent` as the equivalent committed break fact without
  transaction modification.

## Rejected approaches

- Renderer directly depending on game-owned view models;
- game-owned OpenGL passes;
- texture arrays or ten separately bound damage textures;
- model expansion combined with polygon offset;
- frame-delta or mixed-step particle simulation;
- billboard world items;
- line-width-based crosshair geometry;
- cooldowns, debounce, mutation failure, or raycast changes as visual control;
- querying World after mutation to guess the destroyed material;
- a second world-item store, item stack, registry, or stable-ID namespace.
