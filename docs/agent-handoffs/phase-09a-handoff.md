# Phase 9A Handoff — Block Interaction Core

## Baseline

- Branch: `feat/block-interaction-core`
- Base and current uncommitted HEAD: `origin/main@078067e`
- Phase 8 merge verified with divergence `0/0` before implementation
- Java compatibility: source/target 17; verification runtime JDK 21
- Rendering baseline: Phase 5A/5B unchanged

## Completed work

- Added Survival/Creative `GameMode`, edge-driven F4 switching, committed
  `GameModeChanged`, and Creative selection independent from body inventory.
- Added GLFW mouse-button capture to immutable fixed-step input snapshots. Holds
  survive catch-up; press edges run once; focus loss clears all button state.
- Adapted the unique Phase 6 shape-aware `BlockRaycast` to Phase 7 resource hits.
  Player targeting uses authoritative PhysicsBody position plus eye height and
  Camera direction, rejecting unloaded targets.
- Added fixed 1/60 `BlockBreakSession` and tracker with hardness timing, instant
  hardness-zero/Creative behavior, crack stage, and every required cancellation
  path. Hit-point movement on the same block face does not reset progress.
- Added one production `LogicalWorldItemService` for Q drops and block drops. It
  owns stable IDs, canonical stacks, immutable snapshots, pickup timing, existing
  item reservations, and future-spawn capacity reservations. Pickup timing
  saturates at `Long.MAX_VALUE`, so an accepted reservation cannot later fail from
  tick overflow.
- Added two-phase Survival break transactions. Inventory insertion and exact
  world-item remainder capacity are reserved before air mutation; rejection rolls
  back, while an applied mutation commits with exact count conservation.
- Added placement validation for loaded/replaceable destination, registry-backed
  block item, available Survival quantity, and authoritative player AABB. Survival
  extraction is reserved before mutation; Creative consumes nothing.
- Added `GaiaBlockWorldAccess` over repository compare-and-set. Revision, dirty
  propagation, boundary neighbor invalidation, and stale mesh rejection remain
  Phase 3 responsibilities.
- Preserved strict mutation order: Before, repository write/dirty, BlockChanged,
  ChunkDirty, then item reservation commits. Before cancellation has no side
  effects. Applied post-write failures never roll back or trigger blind retry.
- Added immutable `BlockInteractionViewModel` extension with target, face,
  progress, crack stage, interaction mode, game mode, active item, and failure.
- Wired left-click breaking, right-click placement, F4, Q logical drop, fixed
  updates, and cursor-release blocking into `GameBootstrap`/`GameLoop` without
  renderer, mesh-manager, worldgen, or PlayerController changes. The game-layer
  `MouseInteractionLifecycle` cancels interaction synchronously at F1/focus
  boundaries, including zero-fixed-step frames.
- Corrected Creative left-click to read the existing press edge rather than held
  state. A catch-up batch can now break only its original target once; Survival
  still accumulates while held, and both placement modes remain right-button
  press-edge-only.
- `InputManager` is the unique physical mouse-state/release authority for F1 and
  focus boundaries. It masks destructive buttons that were held before state
  clearing until their GLFW release callbacks arrive. `BlockInteractionController`
  retains only the already-consumed F4 batch mask; old held input cannot cross into
  a new mode, a later catch-up step, or a recaptured/focused session.

## Not completed / deliberately deferred

- Crack overlay, particles, audio, hand animation, and world-item rendering
  (Phase 9B).
- Formal three-slot HUD, icons, text, quantities, progress bar, and mode feedback
  (Phase 10).
- PhysicsBody world-item drops, pickup, merging, lifetime, and persistence
  (Phase 11).
- Tool tiers, loot tables, non-cube/oriented placement, additional replaceable
  materials, multiplayer permission, and saves.
- Native macOS/Retina interactive acceptance: NOT RUN.

## Core architecture decisions

1. Phase 7 `ItemStack`, `ResourceLocation`, `InventoryService`,
   `InventoryReservation`, and `WorldItemService` signatures are unchanged.
2. `WorldItemSpawnReservations` is a capability on the same unique
   `LogicalWorldItemService` object, not a second service/store/ID namespace.
3. Gameplay writes use `DefaultWorldMutationService`; only
   `GaiaBlockWorldAccess` translates resource IDs to the repository CAS outcome.
4. Break identity excludes hit point/distance and includes block position, face,
   expected block identity, and Chunk revision.
5. `mutationApplied=true` is committed world history. Item holds commit, the
   failure remains observable, and the mutation is never retried automatically.
6. The protected Phase 7 `InteractionViewModel` method set remains unchanged;
   Phase 9A adds a read-only game-layer extension.

## Modified files

### Engine production

- `engine/src/main/java/com/overlord/config/GameConfig.java`
- `engine/src/main/java/com/overlord/core/input/InputManager.java`
- `engine/src/main/java/com/overlord/core/input/InputSnapshot.java`
- `engine/src/main/java/com/overlord/interaction/SynchronousBlockChangeEventPublisher.java`
- `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemRuntimeSnapshot.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemSpawnCommitResult.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemSpawnReservation.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemSpawnReservationId.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemSpawnReservations.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemSpawnReserveResult.java`

### Game production

- `game/src/main/java/com/gaia/GameBootstrap.java`
- `game/src/main/java/com/gaia/GameContext.java`
- `game/src/main/java/com/gaia/GameLoop.java`
- `game/src/main/java/com/gaia/blocks/BlockRegistry.java`
- `game/src/main/java/com/gaia/interaction/MouseInteractionLifecycle.java`
- `game/src/main/java/com/gaia/interaction/**` (Phase 9A domain,
  targeting, transactions, adapters, controller, and view model)

### Tests

- `engine/src/test/java/com/overlord/core/input/InputManagerTest.java`
- `engine/src/test/java/com/overlord/core/input/InputSnapshotTest.java`
- `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemServiceTest.java`
- `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- `game/src/test/java/com/gaia/blocks/BlockRegistryTest.java`
- `game/src/test/java/com/gaia/interaction/**`
- `game/src/test/java/com/gaia/interaction/MouseInteractionLifecycleTest.java`

### Documentation

- `README.md`
- `docs/architecture/block-interaction-core.md`
- `docs/architecture/current-baseline.md`
- `docs/architecture/interaction-inventory-contract.md`
- `docs/superpowers/specs/2026-07-27-phase-9a-block-interaction-core-design.md`
- `docs/superpowers/plans/2026-07-27-phase-9a-block-interaction-core.md`
- `docs/agent-handoffs/phase-09a-handoff.md`

## Automated verification

- Pre-change baseline: `clean test build` — PASS, 938 tests.
- Focused RED/GREEN evidence was captured for mouse input, mode/target/view,
  fixed break timing, cancellation, logical world items, break transactions,
  placement transactions, repository event order, boundary dirty, and controller
  composition.
- RED: `creativePrimaryPressBreaksOnlyFrontTargetDuringOneCatchUpBatch` and
  `creativePrimaryHoldRequiresReleaseAndNewPressBeforeSecondBreak` each failed
  before the production correction with `expected: <1> but was: <3>` mutations.
- GREEN focused checks: complete `com.gaia.interaction.*` suite and both
  `InputManagerTest` / `InputSnapshotTest` suites passed.
- Lifecycle follow-up RED: `creativeToSurvivalModeSwitchRequiresPrimaryReleaseBeforeSurvivalCanBreak`
  and `disabledInteractionRequiresPrimaryReleaseBeforeAnExistingHoldCanBreakAgain`
  failed before the suppression gate, exposing non-zero progress after the
  lifecycle boundary. The first complete lifecycle test compile also failed as
  expected because `MouseInteractionLifecycle` and
  `cancelAndSuppressMouseInteraction()` did not yet exist.
- Lifecycle follow-up GREEN: the focused controller/lifecycle tests, the complete
  `com.gaia.interaction.*` suite with `GameLoopStructureTest`, and the focused
  `InputManagerTest` / `InputSnapshotTest` suite all passed. The follow-up
  `clean test build` passed in 42 seconds with 1,002 tests: Engine 664 and Game
  338, zero failures, errors, or skips. The three resource checks were rerun
  with `--rerun-tasks` and passed.
- Final `clean test build`: PASS on Windows/JDK 21 in 44 seconds, 22/22
  actionable tasks. XML totals: Engine 663 tests, Game 331 tests, 994 total,
  zero failures/errors/skips.
- Post-correction packaged-resource checks: PASS for separately rerun
  `:game:verifyPackagedResources`, `:engine:verifyPackagedShaderResources`, and
  `:game:verifyInstalledShaderResources` with `--rerun-tasks`.
- Packaged resource checks: PASS for `:game:verifyPackagedResources`,
  `:engine:verifyPackagedShaderResources`, and
  `:game:verifyInstalledShaderResources` with `--rerun-tasks`.
- `git diff --check`: PASS. No tracked `build/`, `bin/`, `.class`, crash dump,
  IDE cache, or absolute Gradle JDK path was found. Protected Phase 7 API files
  are unchanged; Engine has no `com.gaia` dependency; Phase 9A code has no
  OpenGL call; Renderer, RenderPass, ChunkMeshManager, PlayerController, and
  world-generation files are outside the diff.
- Complete local candidate inventory relative to `origin/main`: 64 files,
  approximately 4,766 insertions and 35 deletions (14 tracked modifications plus
  50 intentional untracked Phase 9A files). Tracked-only `git diff --stat`
  reports 14 files, 345 insertions, and 35 deletions.
- Branch/HEAD: `feat/block-interaction-core` at
  `078067e7d1c1c80880c10e5a4cc11cccdd910308`, matching `origin/main` before the
  uncommitted Phase 9A work (`0/0` commit divergence).

## Manual Windows acceptance

- `\.\gradlew.bat :game --console=plain --no-daemon` loaded and rendered the
  deterministic world on Windows/JDK 21.
- Forward movement and jump input changed the observed scene without collision or
  loop failure. F1 release/recapture, F4 mode switching, Creative instant break,
  Creative dirt placement, body-slot key/wheel input, and the empty-inventory Q
  rejection path remained responsive.
- Framebuffer resize from 1026-by-607 to 2048-by-1104 rendered without black-screen
  failure. Alt+Tab focus loss and explicit reactivation remained responsive.
- The desktop-control API cannot sustain a mouse-button hold, so Survival timed
  breaking/drop and Survival placement consumption were not manually completed;
  their fixed-step, reservation, failure, and count-conservation paths are covered
  by focused automated tests, including 10/60/144/240 FPS timing.
- Escape closed the Gaia Legacy window and a follow-up process check found no Gaia
  window process. The launch harness itself had already returned timeout code 124,
  so an exact Gradle/game exit code was **NOT CAPTURED** and is not inferred.
- Lifecycle follow-up manual smoke: the Windows game was launched again after the
  final build. F1 release/recapture, F4, focus loss/restoration, and a left mouse
  interaction all left the game responsive; the left click produced one visible
  block removal. Escape closed the window and the desktop window list confirmed
  no remaining `Gaia Legacy` window. The desktop automation cannot sustain a
  physical mouse hold, so continuous held-button behavior remains automated-test
  coverage rather than a second manual hold test.
- Native macOS/Retina acceptance: **NOT RUN**.

## Review history

- Engine-owner review found and corrected one accepted-reservation tick-overflow
  path in `LogicalWorldItemService`; its focused test failed RED with
  `ArithmeticException` and passed GREEN after saturated pickup-tick arithmetic.
- The earlier game/shared-owner review corrected stale mouse-button state across
  F1 capture transitions.
- A later independent review found two lifecycle gaps: Creative-to-Survival could
  inherit a held left button across catch-up, and F1/focus loss could preserve a
  break session in a zero-fixed-step frame. A subsequent re-review found that an
  empty snapshot could still be mistaken for physical release after F1/focus state
  clearing. The final follow-up keeps F4 batch masking in the controller, moves
  physical-release re-arming to the GLFW-owning `InputManager`, and preserves
  synchronous frame-boundary cancellation. No commit, push, PR, or merge was
  performed.

## Known risks

- Phase 9A has no on-screen feedback. Interaction state must currently be inferred
  from world changes and optional diagnostics until Phase 9B/10.
- Logical world items persist and are queryable but are intentionally invisible and
  non-physical, so Q/block drops cannot yet be seen or picked up.
- Only air is replaceable in the current placement policy.
- Runtime mutation publisher is synchronously composed with no subscribers; future
  gameplay listeners must be registered directly rather than assuming queued Before
  cancellation through the global EventBus.
- macOS remains architecture/test-covered but not natively accepted in this phase.

## Interfaces the next phase must not break

- Canonical `ItemStack(ResourceLocation, positive count)` and Phase 2
  `BlockRegistry` / `ItemFormDefinition` identity rules.
- Phase 7 `InventoryService` and `WorldItemService` signatures and reservation
  terminal/idempotent guarantees.
- Same-instance relationship between `WorldItemService` and
  `WorldItemSpawnReservations`; no parallel item store or stable-ID sequence.
- `BodyInventoryService` as the only body-inventory mutation boundary.
- Phase 6 `BlockRaycast` as the only shape-aware voxel traversal; authoritative
  body origin plus Camera direction.
- `WorldMutationService` as the only gameplay write path and
  `ChunkRepository` ownership of dirty/revision/boundary invalidation.
- 1/60 fixed-step interaction/input ownership and first-step press-edge semantics.
- `BlockInteractionViewModel` remains read-only; UI/renderer cannot write gameplay.
- No renderer, OpenGL, GPU, Chunk mesh, worldgen, or physics-body work from workers.

## Suggested delivery

- Commit: `feat(interaction): add mode-aware breaking placement and two-phase item transactions`
- PR title: `feat(interaction): implement block interaction gameplay core`
- PR description: implement fixed-step Survival/Creative breaking and placement,
  one logical world-item backend, reservation-before-mutation item conservation,
  repository-owned dirty propagation, immutable interaction state, and focused
  regression coverage without adding Phase 9B visuals or Phase 10 UI.
