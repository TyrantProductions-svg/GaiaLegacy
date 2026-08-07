# Phase 11.6 Gameplay Feel and Drop Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan inline task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do not dispatch subagents for this plan.

**Goal:** Correct Q and block-drop production behavior and add bounded committed-only first-person, camera, block-transition, and particle feedback.

**Architecture:** The existing logical world-item and inventory services remain sole authorities. Q and block breaking share `WorldItemSpawnReservations`; the existing `InteractionFeedbackCoordinator` receives committed facts and emits immutable renderer snapshots through bounded presentation controllers and the existing single `ParticleSystem`.

**Tech Stack:** Java 17, JUnit 5, Gradle Wrapper 8.5, JOML, LWJGL OpenGL 4.1, GLSL 410.

## Global Constraints

- Work on `feat/physical-world-items` in the existing unstaged Phase 11 tree.
- Do not spawn subagents, stage, commit, push, create a PR, merge, or rewrite history.
- Use RED/GREEN for every production behavior change and keep each internal gate green before continuing.
- Preserve `LogicalWorldItemService`, `BodyInventoryService`, and `WorldMutationService` as sole mutation authorities.
- Preserve one particle system and the limits 512 total, 384 LOW, 128 protected HIGH, 64 requests/step, and 32 particles/request.
- Keep Java 17, OpenGL 4.1, GLSL 410, immutable renderer input, and owner-thread GPU work.
- Do not add automatic pickup, attraction, expiry, pooling, persistence, networking, merging, body-body collision, or rotation.

---

### Task 1: Gate 11.6A Q amount, transaction, input lifecycle, and kinematics

**Files:**
- Create: `game/src/main/java/com/gaia/inventory/InventoryDropAmount.java`
- Create: `game/src/main/java/com/gaia/worlditem/WorldItemDropKinematics.java`
- Modify: `game/src/main/java/com/gaia/inventory/InventoryDropController.java`
- Modify: `game/src/main/java/com/gaia/inventory/InventoryDropResult.java`
- Modify: `game/src/main/java/com/gaia/inventory/BodyInventoryInputController.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `engine/src/main/java/com/overlord/config/GameConfig.java`
- Test: `game/src/test/java/com/gaia/inventory/InventoryDropControllerTest.java`
- Test: `game/src/test/java/com/gaia/inventory/BodyInventoryInputControllerTest.java`
- Test: `game/src/test/java/com/gaia/worlditem/WorldItemDropKinematicsTest.java`
- Test: `game/src/test/java/com/gaia/InventoryDropGameLoopTest.java`

**Interfaces:**
- `InventoryDropAmount { ONE, COMPLETE_STACK }` selects the exact canonical extraction.
- `InventoryDropController.drop(..., InventoryDropAmount amount, ..., long tick)` reserves exact inventory extraction and the matching `WorldItemSpawnReservations` request, then commits once.
- `BodyInventoryInputController.handleSelection(InputSnapshot)` remains non-destructive.
- `BodyInventoryInputController.handleDrop(InputSnapshot,long,Optional<InventoryDropLocation>,boolean)` performs Q only when gameplay actions are enabled.
- `WorldItemDropKinematics.qDrop(Vector3fc eye, Vector3fc forward, Vector3fc right, long eventIdentity)` returns finite immutable position/velocity.

- [ ] **Step 1: Write production-path Q RED tests**

```java
@Test void qPressDropsExactlyOneFromMultiCountActiveSlot() { /* assert 4 -> 3 + one world item */ }
@Test void leftControlQDropsEntireActiveStack() { /* assert one complete-stack world item */ }
@Test void rightControlQDropsEntireActiveStack() { /* same for right Ctrl */ }
@Test void heldQDoesNotRepeat() { /* press then heldOnly */ }
@Test void releaseAndRepressAllowsAnotherSingleDrop() { /* two edges -> two items */ }
@Test void catchUpStepsDoNotReplayQEdge() { /* FixedBatch first/full then heldOnly */ }
@Test void focusLossAndBlockingUiClearPendingQEdge() { /* destructive path disabled */ }
```

- [ ] **Step 2: Run Q tests and verify the expected RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.inventory.InventoryDropControllerTest" --tests "com.gaia.inventory.BodyInventoryInputControllerTest" --tests "com.gaia.InventoryDropGameLoopTest" --rerun-tasks --console=plain --no-daemon
```

Expected: the multi-count Q assertion observes the complete stack removed, Ctrl has no distinct path, and the requested new APIs are missing.

- [ ] **Step 3: Implement exact reservation and input semantics**

```java
ItemStack requested = amount == InventoryDropAmount.ONE
        ? new ItemStack(stack.itemId(), 1)
        : stack;
InventoryReserveResult inventoryHold = inventory.reserve(
        new InventoryReservationRequest(owner, slot, EXTRACT, requested));
WorldItemSpawnReserveResult worldHold = spawns.reserveSpawn(
        new WorldItemSpawnRequest(requested, x, y, z, vx, vy, vz,
                Optional.of(owner), tick));
```

Require complete reservations; reverse-roll back both before the barrier.
Commit the world spawn once, then the inventory extraction once. Treat an
applied inventory notification exception as committed with diagnostic payload.
Blind independent retry or allocation of a replacement stable ID is forbidden.

Audited completion of the SAME reservation with the SAME stable ID is allowed
and required when the reservation is proven PENDING and the commit barrier must
be completed.

- [ ] **Step 4: Write kinematics RED tests**

Assert 0.40 forward position, 4.5 forward speed plus 1.25 upward speed,
lateral magnitude <= 0.15, same-event equality, different-event bounded
variation, finite degenerate handling, and copied Camera vectors unchanged.

- [ ] **Step 5: Implement pure Q kinematics and 20-tick delay**

Use a local `mix64(long)` hash, normalize copied inputs, and project lateral
variation along the copied right vector. Set
`WORLD_ITEM_PICKUP_DELAY_TICKS = 20` and never mutate Camera vectors.

- [ ] **Step 6: Run Gate 11.6A Q-focused GREEN suites**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.inventory.*" --tests "com.gaia.worlditem.WorldItemDropKinematicsTest" --tests "com.gaia.InventoryDropGameLoopTest" --tests "com.gaia.GameLoopStructureTest" --rerun-tasks --console=plain --no-daemon
```

Expected: all selected tests pass with exact conservation and no edge replay.

---

### Task 2: Gate 11.6A canonical block-drop production wiring

**Files:**
- Modify: `game/src/main/java/com/gaia/interaction/BlockBreakTransaction.java`
- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionController.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Test: `game/src/test/java/com/gaia/interaction/BlockBreakTransactionTest.java`
- Test: `game/src/test/java/com/gaia/interaction/BlockInteractionControllerTest.java`
- Test: `game/src/test/java/com/gaia/worlditem/WorldItemDropIntegrationTest.java`

**Interfaces:**
- `WorldItemDropKinematics.blockDrop(BlockHitResult, Vector3fc playerPosition, long eventIdentity)` supplies the reserved request transform.
- `BlockBreakTransaction` reserves the complete canonical drop in `WorldItemSpawnReservations`; it never inserts break loot into inventory.
- Applied `BlockBreakResult.worldItemCommitted()` equals the complete canonical produced count.

- [ ] **Step 1: Write block production RED tests**

```java
@Test
void committedSurvivalBreakSpawnsCanonicalDropExactlyOnce() {
    Fixture fixture = fixtureWithEmptyInventoryCapacity();
    BlockBreakResult result = fixture.breakStoneInSurvival();
    assertEquals(BlockBreakResult.Status.APPLIED, result.status());
    assertEquals(1, result.worldItemCommitted());
    assertEquals(1, fixture.logical().snapshots().size());
    assertEquals(STONE, fixture.logical().snapshots().get(0).stack().itemId());
    assertEquals(1, fixture.logical().snapshots().get(0).stack().count());
}

@Test
void failedBreakMutationProducesNoDrop() {
    Fixture fixture = fixtureWithRejectedMutation();
    assertEquals(BlockBreakResult.Status.MUTATION_REJECTED,
            fixture.breakStoneInSurvival().status());
    assertTrue(fixture.logical().snapshots().isEmpty());
    assertEquals(1, fixture.spawnRollbacks());
}
```

In the same fixture, add separate methods for pre-mutation item identity,
Creative/no-item-form silence, applied notification failure without duplicate,
center/away kinematics, same-event determinism, projection/visual failure
isolation, and repeated reconciliation without another logical spawn.

- [ ] **Step 2: Run block tests and verify the expected RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.interaction.BlockBreakTransactionTest" --tests "com.gaia.interaction.BlockInteractionControllerTest" --tests "com.gaia.worlditem.WorldItemDropIntegrationTest" --rerun-tasks --console=plain --no-daemon
```

Expected: a normal inventory receives the drop and the logical world-item list
remains empty, demonstrating the confirmed root cause.

- [ ] **Step 3: Implement reserve-mutate-commit canonical spawn**

```java
WorldItemSpawnReserveResult hold = worldItemSpawns.reserveSpawn(spawnRequest);
if (hold.status() != RESERVED) return RESERVATION_REJECTED;
BlockChangeResult mutation = mutations.changeBlock(request);
if (mutation.status() != APPLIED) {
    worldItemSpawns.rollbackSpawn(hold.reservation().orElseThrow().id());
    return MUTATION_REJECTED;
}
WorldItemSpawnCommitResult committed =
        worldItemSpawns.commitSpawn(hold.reservation().orElseThrow().id());
```

The request uses the pre-mutation item form and deterministic block-drop
kinematics. The event-derived base/outward horizontal magnitude is
`1.25..1.75 blocks/s`; an independently derived orthogonal lateral component is
bounded by `+/-0.20 blocks/s`, so the total horizontal resultant cannot exceed
`sqrt(1.75^2 + 0.20^2)` (approximately `1.7614 blocks/s`). Applied notification
failure continues to the single spawn commit.

- [ ] **Step 4: Run the complete Gate 11.6A verification set**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.inventory.*" --tests "com.gaia.interaction.*" --tests "com.gaia.worlditem.*" --tests "com.gaia.GameLoopStructureTest" --rerun-tasks --console=plain --no-daemon
```

Expected: all Q/drop, inventory reservation, world-item, input lifecycle, and
Phase 9A interaction tests pass before Gate 11.6B begins.

---

### Task 3: Gate 11.6B immutable feedback contracts and action animator

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/feedback/VisualTransform.java`
- Create: `engine/src/main/java/com/overlord/renderer/feedback/FirstPersonItemVisual.java`
- Create: `game/src/main/java/com/gaia/interaction/feedback/FirstPersonActionAnimator.java`
- Modify: `engine/src/main/java/com/overlord/renderer/feedback/InteractionFeedbackFrame.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/InteractionFeedbackCoordinator.java`
- Modify: `game/src/main/java/com/gaia/interaction/BlockInteractionController.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Test: `game/src/test/java/com/gaia/interaction/feedback/FirstPersonActionAnimatorTest.java`
- Test: `game/src/test/java/com/gaia/interaction/feedback/CommittedGameplayFeedbackTest.java`

**Interfaces:**
- `FirstPersonActionAnimator.trigger(Action, WorldItemFaceRegions,long)` restarts deterministically.
- `advance(double renderDeltaSeconds)` returns exact identity after duration.
- `InteractionFeedbackCoordinator.onDrop/onPlacement/onBreak` accept immutable committed facts only.

- [ ] **Step 1: Write animator and commit-boundary RED tests**

Assert exact states/durations, identity before/after, bounded rapid restart,
failed-result silence, Camera/player immutability, and lifecycle clearing.

- [ ] **Step 2: Run RED and implement minimal animator**

Use piecewise eased scalar curves and immutable transforms. Clamp elapsed time,
validate all components, and snap to `VisualTransform.IDENTITY` at completion.

- [ ] **Step 3: Wire committed action facts after transaction return**

Call feedback only for `APPLIED`, `APPLIED_WITH_NOTIFICATION_FAILURE`, or a
committed Q result. Catch presentation RuntimeExceptions inside the coordinator,
report them, and never retry gameplay.

- [ ] **Step 4: Run animator/coordinator GREEN tests**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.FirstPersonActionAnimatorTest" --tests "com.gaia.interaction.feedback.CommittedGameplayFeedbackTest" --rerun-tasks --console=plain --no-daemon
```

---

### Task 4: Gate 11.6B render-only camera impulse and first-person pass

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/feedback/CameraImpulseVisual.java`
- Create: `engine/src/main/java/com/overlord/renderer/pass/FirstPersonVisualPass.java`
- Create: `game/src/main/java/com/gaia/interaction/feedback/CameraImpulseController.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/RenderContext.java`
- Modify: `engine/src/main/resources/assets/overlord/shaders/feedback/world_item.frag`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/WorldItemVisualPass.java`
- Test: `game/src/test/java/com/gaia/interaction/feedback/CameraImpulseControllerTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/pass/FirstPersonVisualPassTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/RendererCameraImpulseTest.java`

**Interfaces:**
- `CameraImpulseController` exposes immutable pitch/yaw/translation only.
- `Renderer.composeRenderView(Matrix4fc, CameraImpulseVisual)` returns a new matrix and never calls Camera setters.
- `FirstPersonVisualPass` uses identity view space and the existing atlas/unit cube.

- [ ] **Step 1: Write camera and pass RED tests**

Assert committed placement/break impulses, failed-action silence, identical
same-event output, +/-1 degree clamp, stable decay, exact zero, canonical
Camera forward byte-for-byte equality, and lifecycle clearing.

- [ ] **Step 2: Implement the bounded analytic/cubic envelope and immutable view composition**

Evaluate the deterministic analytic/cubic envelope, clamp all axes,
epsilon-snap exact zero, and use only copied Camera matrices/vectors.

- [ ] **Step 3: Implement the first-person cube pass**

Reuse the GLSL 410 six-face shader and shared cube. Add a validated
`visualAlpha` uniform; world items set 1.0. The first-person pass draws after
world-space feedback with depth disabled and restores exact incoming GL state.

- [ ] **Step 4: Run Gate 11.6B camera/first-person GREEN tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.*" --tests "com.overlord.renderer.pass.*" --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.CameraImpulseControllerTest" --rerun-tasks --console=plain --no-daemon
```

---

### Task 5: Gate 11.6B transient block proxy and bounded render mask

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/feedback/TransientBlockVisual.java`
- Create: `engine/src/main/java/com/overlord/renderer/pass/TransientBlockVisualPass.java`
- Create: `game/src/main/java/com/gaia/interaction/feedback/TransientBlockVisualSystem.java`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/WorldRenderPass.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/main/resources/assets/overlord/shaders/world.vert`
- Modify: `engine/src/main/resources/assets/overlord/shaders/world.frag`
- Test: `game/src/test/java/com/gaia/interaction/feedback/TransientBlockVisualSystemTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/pass/TransientBlockVisualPassTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/pass/WorldRenderPassTransientMaskTest.java`

**Interfaces:**
- `TransientBlockVisualSystem` caps active coordinates at 256, replaces by
  coordinate, evicts oldest sequence at capacity, advances on render time, and
  clears idempotently.
- `TransientBlockVisual` carries coordinate, faces, type, event identity,
  scale, alpha, downward offset, and exclusion flag.
- `WorldRenderPass` uploads only immutable masks for the current Chunk.

- [ ] **Step 1: Write transition lifecycle RED tests**

Assert placement 0.85 -> 1.00 over 0.14 s, break 1.00 -> 0.72 and alpha
1.00 -> 0.55 over 0.18 s, pre-mutation faces retained, one coordinate/one
transition, deterministic replacement/overflow, expiration cleanup, idempotent
close, and unchanged World/Chunk revision.

- [ ] **Step 2: Implement bounded transition state**

Use a `LinkedHashMap<BlockCoordinate, Transition>` plus monotonic presentation
sequence. All public snapshots are immutable and stable-order sorted.

- [ ] **Step 3: Write and implement renderer-mask RED/GREEN**

Add `worldPosition` from vertex to fragment shader and bounded uniforms:

```glsl
uniform int transientBlockCount;
uniform vec3 transientBlockCoords[256];
for (int i = 0; i < transientBlockCount; ++i) {
    vec3 p = worldPosition - transientBlockCoords[i];
    if (all(greaterThanEqual(p, vec3(0.0)))
            && all(lessThanEqual(p, vec3(1.0)))) discard;
}
```

Upload masks on the GL owner thread and render proxies afterward with the
existing atlas/cube. No World or mesh snapshot mutation is permitted.

- [ ] **Step 4: Run all Gate 11.6B focused suites**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.*" --tests "com.overlord.voxel.*" --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.*" --tests "com.gaia.interaction.BlockPlacementTransactionTest" --tests "com.gaia.interaction.BlockBreakTransactionTest" --rerun-tasks --console=plain --no-daemon
```

---

### Task 6: Gate 11.6C deterministic mixed particle generation

**Files:**
- Modify: `engine/src/main/java/com/overlord/renderer/particle/ParticleCategory.java`
- Modify: `engine/src/main/java/com/overlord/renderer/particle/ParticleEmission.java`
- Modify: `engine/src/main/java/com/overlord/renderer/particle/ParticleSystem.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/CommittedBreakVisualAdapter.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/CommittedPickupVisualAdapter.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/InteractionFeedbackCoordinator.java`
- Test: `engine/src/test/java/com/overlord/renderer/particle/ParticleSystemTest.java`
- Test: `game/src/test/java/com/gaia/interaction/feedback/GameplayParticleFeedbackTest.java`

**Interfaces:**
- `ParticleEmission` carries immutable six-face regions and a finite primary
  direction while retaining compatibility constructors for uniform regions.
- Categories distinguish break debris/astral, placement debris/astral,
  pickup convergence, and existing continuous feedback.

- [ ] **Step 1: Write exact split and distribution RED tests**

Assert 16/4 break, 6/2 place, 8 pickup, multiple horizontal quadrants,
several debris `deltaY <= 0`, average direction not world-up, same-seed exact
equality, finite values, deterministic face variation, placement face-tangent
spread, and pickup convergence.

- [ ] **Step 2: Implement stratified deterministic particle creation**

For break debris use golden-angle azimuth rotated by event hash and stratified
Y in approximately -0.65..+0.85. Select speed, size, lifetime, and one of six
faces from hashed local index. Apply -12 gravity and light drag each 1/60 step;
shrink the rendered size toward zero. Implement bounded category-specific
astral, placement, and pickup paths without shared RNG.

- [ ] **Step 3: Verify capacity and cleanup invariants**

Run existing and new tests for HIGH/LOW reserve, 512 hard cap, 64 request cap,
32 per-request cap, lifetime cleanup, and failed transaction silence.

- [ ] **Step 4: Run Gate 11.6C GREEN suites and resources**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.particle.ParticleSystemTest" --tests "com.overlord.renderer.pass.ParticleRenderPassTest" --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.*" --tests "com.gaia.worlditem.WorldItemPickupControllerTest" --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
```

---

### Task 7: Gate 11.6D production composition and integrated regressions

**Files:**
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/GameContext.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- Modify: `game/src/test/java/com/gaia/InteractionFeedbackGameLoopTest.java`
- Modify: `game/src/test/java/com/gaia/worlditem/WorldItemPickupControllerTest.java`
- Modify: `game/src/test/java/com/gaia/worlditem/WorldItemDropIntegrationTest.java`
- Create: `game/src/test/java/com/gaia/Phase116GameplayIntegrationTest.java`

**Interfaces:**
- One production `InteractionFeedbackCoordinator`, `ParticleSystem`,
  `LogicalWorldItemService`, and `PhysicalWorldItemSystem` are composed.
- Shutdown clears presentation resources before renderer/engine cleanup and
  never removes logical items.

- [ ] **Step 1: Write integrated RED scenarios**

Cover Q one-item -> projection -> visual -> manual pickup; Ctrl+Q one stable
ID; Survival break -> drop -> fall; Creative no drop; placement immediate
collision + transition; break collision absent + proxy + drop; alternating
same-coordinate actions with no leak; released rapid Q exact conservation;
right-click/Shift+right exclusivity; no proximity pickup; protected feedback
under particle saturation; and shutdown during active presentation without
canonical deletion.

- [ ] **Step 2: Wire production composition and lifecycle**

Pass the unique services/controllers through `GameContext`, preserve fixed
system order, advance presentation once per rendered frame, apply immutable
feedback to `RenderFrameInput`, and register idempotent cleanup in the existing
shutdown coordinator.

- [ ] **Step 3: Run all related engine/game tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.worlditem.*" --tests "com.overlord.physics.*" --tests "com.overlord.renderer.*" --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.Phase116GameplayIntegrationTest" --tests "com.gaia.worlditem.*" --tests "com.gaia.interaction.*" --tests "com.gaia.inventory.*" --rerun-tasks --console=plain --no-daemon
```

---

### Task 8: Gate 11.6D documentation, full verification, and self-review

**Files:**
- Modify: `docs/architecture/physical-world-items.md`
- Modify: `docs/agent-handoffs/phase-11-progress.md`
- Modify: `docs/agent-handoffs/phase-11-handoff.md`
- Modify: `docs/testing/phase-11-world-item-acceptance.md`
- Modify: `docs/architecture/current-baseline.md`

**Interfaces:**
- Documentation reports measured implementation evidence only.
- Windows Phase 11.6 interactive acceptance remains `NOT RUN`.

- [ ] **Step 1: Update architecture, controls, evidence, and risks**

Record exact Q/Ctrl behavior, both Ctrl keys, reservation/commit order,
kinematics, manual-only pickup, committed feedback, render-only camera,
transient mask/proxy rules, particle splits/distribution, caps, RED/GREEN
evidence, prior Windows defect discovery, and current manual status.

- [ ] **Step 2: Run exact final module and clean-build commands**

```powershell
.\gradlew.bat :engine:test --console=plain --no-daemon
.\gradlew.bat :game:test --console=plain --no-daemon
.\gradlew.bat :tools:test --console=plain --no-daemon
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources :engine:verifyPackagedShaderResources :game:verifyInstalledShaderResources :tools:verifyGeneratedUiAssets --rerun-tasks --console=plain --no-daemon
```

Record exact JUnit XML counts, failures, errors, and skips.

- [ ] **Step 3: Run final hygiene and forbidden-feature scans**

```powershell
git diff --check
git status -sb
git status --short --untracked-files=all
git diff --stat
git ls-files --others --exclude-standard
git diff --cached --name-only
git ls-files | rg "(^|/)(build|bin|out|target)/|\.class$"
rg -n "[A-Za-z]:\\\\" engine/src/main game/src/main --glob "!**/build/**"
rg -n "BlockDropEntity|BlockStack|new ParticleSystem|new LogicalWorldItemService" engine/src/main game/src/main
rg -n "proximity|automatic pickup|magnet|attraction" engine/src/main game/src/main
rg -n "#version (42[0-9]|4[2-9][0-9])|GL_COMPUTE_SHADER|GL_SHADER_STORAGE_BUFFER|SSBO" engine game --glob "!**/build/**"
```

Expected: zero staged files, no tracked generated artifacts, no absolute local
paths, no second stores/systems, no automatic pickup, no forbidden GL feature.

- [ ] **Step 4: Perform implementation-session read-only self-review**

Audit every item in the Phase 11.6 prompt: Q amount/modifier/edge/catch-up,
rollback and applied failures, block wiring/identity/duplication, feedback
commit boundary, Camera/raycast immutability, proxy isolation/leaks, particle
direction/determinism/caps, right-click/Shift+right regressions, shutdown, and
GL ownership. Classify Critical/Important/Minor and fix each finding through a
new focused RED/GREEN cycle.

- [ ] **Step 5: Produce the required final report without acceptance claims**

Use exactly:

```text
Phase 11.6 implementation candidate complete.
Independent code review and Windows interactive acceptance are required before
commit/PR preparation.
```

Do not stage, commit, push, create a PR, or merge.

## Plan self-review

- Gates are ordered and independently testable.
- Every approved behavior and forbidden scope item maps to a task or global constraint.
- Q and block break use one spawn-reservation authority and no second item store.
- Feedback receives committed immutable facts and cannot compensate gameplay.
- Camera impulse and transient block exclusion are render-only.
- Particle counts, deterministic direction, gravity, lifetime, and existing caps are explicit.
- All new controller, renderer snapshot, and transaction interfaces are defined consistently.
- Every named interface is introduced by a preceding task and used consistently.
