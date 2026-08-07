# Phase 11 Physical World Items Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one-authority physical world items, Shift+right manual pickup, complete six-face block-drop visuals, and evidence-gated bounded particles without changing Phase 7 transaction identities.

**Architecture:** `LogicalWorldItemService` remains the canonical stable-ID, stack, lifecycle, position, and velocity owner. A game-owned `PhysicalWorldItemSystem` projects immutable logical snapshots into existing engine `PhysicsBody` instances, then writes motion back through revision-checked engine contracts; pickup coordinates existing inventory and world-item reservations inside one synchronous conservation barrier. Renderer and UI continue to consume immutable presentation values only.

**Tech Stack:** Java 17 source compatibility, JDK 21 build runtime, Gradle Wrapper, JUnit Jupiter 6.1.1, JOML, LWJGL 3.3.3, OpenGL 4.1, GLSL 410.

## Global Constraints

- Work only on `feat/physical-world-items` created from the latest `origin/main`; confirm initial divergence is `0/0` or explain approved prior Phase 11 work.
- Do not modify, stage, commit, push, create a PR, or merge without the user's explicit authorization for that action.
- Preserve canonical `ResourceLocation`, `ItemStack`, `InventoryService`, `InventoryReservation`, `WorldItemService`, `WorldItemReservation`, and spawn-reservation signatures.
- `BodyInventoryService` remains the only inventory mutation boundary.
- `LogicalWorldItemService` remains the only world-item store and stable-ID lifecycle authority.
- Renderer reads immutable presentation only and never calls world-item, inventory, physics, mutation, or targeting services.
- All fixed updates, projection registration, canonical motion writes, and reservation commits run on the main game thread.
- All OpenGL resource creation, upload, draw, and destruction run on the main context-owner thread.
- Keep Java 17 source/target compatibility, OpenGL 4.1 maximum, and GLSL 410 maximum.
- Do not use compute shaders, SSBOs, platform-exclusive APIs, system font paths, or platform-specific JDK paths.
- Do not create a second ItemStack, item registry, world-item store, BlockDropEntity, BlockStack, or parallel particle system.
- Do not implement automatic pickup, body-to-body collision, rotation, joints, stack solving, persistence, or infinite streaming.
- Use TDD for every production change: focused RED, minimal GREEN, related regression suite, then owner checkpoint.
- Do not pool canonical ItemStack, stable IDs, reservations, or public immutable snapshots.
- Preserve current terrain, Chunk revision/dirty ownership, Phase 9A transactions, Phase 9B feedback, and Phase 10 UI behavior.

---

## File and responsibility map

### Engine world-item authority

- `engine/src/main/java/com/overlord/worlditem/api/WorldItemPhysicalState.java` - live canonical physical-state enum.
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemPhysicalSnapshot.java` - immutable runtime, state, and extraction-lock view.
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemMotionUpdate.java` - expected-revision motion command.
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemMotionUpdateResult.java` - closed CAS result.
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemRuntimeAccess.java` - list/read/write runtime extension beside Phase 7 APIs.
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemCommitException.java` - explicit applied-state commit failure.
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemReservationAudit.java` - exceptional read-only reservation audit.
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemReservationAuditSnapshot.java` - immutable world reservation state.
- `engine/src/main/java/com/overlord/inventory/api/InventoryReservationAudit.java` - exceptional read-only inventory reservation audit.
- `engine/src/main/java/com/overlord/inventory/api/InventoryReservationAuditSnapshot.java` - immutable inventory reservation state.
- `engine/src/main/java/com/overlord/core/transaction/ReservationTerminalState.java` - shared `PENDING`, `COMMITTED`, `ROLLED_BACK` value.
- `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java` - implements canonical runtime and audit contracts.

### Game physical projection and pickup

- `game/src/main/java/com/gaia/worlditem/WorldItemPhysicsConfig.java` - approved physical constants.
- `game/src/main/java/com/gaia/worlditem/WorldItemPhysicsMetrics.java` - immutable counters.
- `game/src/main/java/com/gaia/worlditem/WorldItemPresentationSnapshot.java` - reconstructable previous/current render motion.
- `game/src/main/java/com/gaia/worlditem/PhysicalWorldItemSystem.java` - stable-ID projection, state machine, physics reconciliation, writeback, and cleanup.
- `game/src/main/java/com/gaia/worlditem/WorldItemTarget.java` - immutable targeting result.
- `game/src/main/java/com/gaia/worlditem/WorldItemTargetingService.java` - eye ray, AABB intersection, nearest/tie/occlusion policy.
- `game/src/main/java/com/gaia/worlditem/RoutedWorldInteractionInput.java` - immutable pickup/block input split.
- `game/src/main/java/com/gaia/worlditem/WorldInteractionInputRouter.java` - stateless Shift+right priority routing.
- `game/src/main/java/com/gaia/inventory/InventoryReservationBatch.java` - deterministic multi-slot reservation output.
- `game/src/main/java/com/gaia/inventory/BodyInventoryReservationPlanner.java` - active-slot-first insertion reservation acquisition.
- `game/src/main/java/com/gaia/worlditem/WorldItemPickupReceipt.java` - immutable applied pickup fact retained after terminal removal.
- `game/src/main/java/com/gaia/worlditem/WorldItemPickupResult.java` - closed result with exact counts and failures.
- `game/src/main/java/com/gaia/worlditem/WorldItemPickupTransaction.java` - reserve/commit/rollback conservation barrier.
- `game/src/main/java/com/gaia/worlditem/WorldItemPickupController.java` - one-edge targeting and transaction coordinator.

### Immutable visuals and bounded particles

- `engine/src/main/java/com/overlord/renderer/feedback/WorldItemFaceRegions.java` - six complete block-face regions.
- `engine/src/main/java/com/overlord/renderer/feedback/WorldItemVisual.java` - stable ID, interpolated center, and face regions.
- `game/src/main/java/com/gaia/interaction/feedback/GaiaWorldItemFaceResolver.java` - BlockRegistry-backed six-face resolver and fallback.
- `game/src/main/java/com/gaia/interaction/feedback/WorldItemVisualTracker.java` - stable-ID presentation reconciliation using physical presentation snapshots.
- `engine/src/main/java/com/overlord/renderer/particle/ParticlePriority.java` - LOW/HIGH priority.
- `engine/src/main/java/com/overlord/renderer/particle/ParticleEmissionResult.java` - admitted/rejected request result.
- `engine/src/main/java/com/overlord/renderer/particle/ParticleAllocationMetrics.java` - immutable cap/allocation counters.
- `engine/src/main/java/com/overlord/renderer/particle/ParticleSystem.java` - priority capacity and deterministic overflow.
- `game/src/main/java/com/gaia/interaction/feedback/CommittedPickupVisualAdapter.java` - committed-only six-particle feedback.

### Composition, profiling, and documentation

- `game/src/main/java/com/gaia/GameContext.java` - explicit Phase 11 dependencies.
- `game/src/main/java/com/gaia/GameBootstrap.java` - construct and register ownership.
- `game/src/main/java/com/gaia/GameLoop.java` - approved fixed-step order and immutable render capture.
- `tools/src/main/java/com/gaia/tools/WorldItemPerformanceFixture.java` - deterministic allocation/GC fixture.
- `tools/build.gradle` - `profileWorldItems` JavaExec task.
- `docs/architecture/physical-world-items.md` - final implemented contract and evidence.
- `docs/agent-handoffs/phase-11-progress.md` - command results, manual status, risks, and protected interfaces.

The subsystems stay in one ordered plan because motion revision, pickup
reservations, stable-ID visuals, and particle feedback share one terminal
lifecycle. Splitting them into independently mergeable plans would permit an
intermediate second authority or an unobservable committed pickup.

---

### Task 1: Canonical physical runtime and motion CAS

**Files:**
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPhysicalState.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPhysicalSnapshot.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemMotionUpdate.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemMotionUpdateResult.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemRuntimeAccess.java`
- Modify: `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- Create: `engine/src/test/java/com/overlord/worlditem/WorldItemRuntimeAccessTest.java`
- Modify: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemServiceTest.java`

**Interfaces:**
- Consumes: existing `WorldItemRuntimeSnapshot`, `WorldItemSnapshot`, `WorldItemId`, and the single logical service.
- Produces:

```java
public interface WorldItemRuntimeAccess {
    List<WorldItemPhysicalSnapshot> physicalSnapshots();
    Optional<WorldItemPhysicalSnapshot> physicalSnapshot(WorldItemId itemId);
    WorldItemMotionUpdateResult updateMotion(WorldItemMotionUpdate update);
}

public record WorldItemPhysicalSnapshot(
        WorldItemRuntimeSnapshot runtime,
        WorldItemPhysicalState state,
        boolean extractionReserved) {}

public record WorldItemMotionUpdate(
        WorldItemId itemId,
        long expectedRevision,
        double positionX,
        double positionY,
        double positionZ,
        double velocityX,
        double velocityY,
        double velocityZ,
        WorldItemPhysicalState state) {}
```

- [ ] **Step 1: Write failing contract tests**

Add tests that spawn two items and assert stable-ID order, immutable lists,
initial `ACTIVE` state, revision-checked motion, and non-finite rejection:

```java
@Test
void motionUpdateIsCanonicalRevisionCheckedAndFinite() {
    LogicalWorldItemService service = service(4, 10);
    WorldItemSnapshot spawned = service.spawn(request(DIRT, 1)).item().orElseThrow();

    WorldItemMotionUpdateResult applied = service.updateMotion(new WorldItemMotionUpdate(
            spawned.id(), spawned.revision(), 4.0, 5.0, 6.0, 1.0, -2.0, 3.0,
            WorldItemPhysicalState.GROUNDED));
    assertEquals(WorldItemMotionUpdateResult.Status.APPLIED, applied.status());
    WorldItemPhysicalSnapshot canonical = applied.snapshot().orElseThrow();
    assertEquals(1L, canonical.runtime().item().revision());
    assertEquals(4.0, canonical.runtime().item().positionX());
    assertEquals(WorldItemPhysicalState.GROUNDED, canonical.state());

    WorldItemMotionUpdateResult stale = service.updateMotion(new WorldItemMotionUpdate(
            spawned.id(), 0, 9.0, 9.0, 9.0, 0.0, 0.0, 0.0,
            WorldItemPhysicalState.ACTIVE));
    assertEquals(WorldItemMotionUpdateResult.Status.STALE_REVISION, stale.status());
    assertEquals(canonical, service.physicalSnapshot(spawned.id()).orElseThrow());

    WorldItemMotionUpdateResult invalid = service.updateMotion(new WorldItemMotionUpdate(
            spawned.id(), 1, Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0,
            WorldItemPhysicalState.ACTIVE));
    assertEquals(WorldItemMotionUpdateResult.Status.INVALID_MOTION, invalid.status());
    assertEquals(canonical, service.physicalSnapshot(spawned.id()).orElseThrow());
}
```

Also assert that an active extraction toggles `extractionReserved`, motion can
advance while count is reserved, partial extraction preserves the newest
motion, and final extraction makes both snapshot methods empty.

- [ ] **Step 2: Run the focused tests and record RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.worlditem.WorldItemRuntimeAccessTest" --tests "com.overlord.worlditem.LogicalWorldItemServiceTest" --console=plain --no-daemon
```

Expected: compilation fails because the five runtime contract types and
`LogicalWorldItemService.updateMotion` do not exist.

- [ ] **Step 3: Add the minimal immutable contracts**

Implement closed validation rules:

```java
public enum WorldItemPhysicalState {
    ACTIVE,
    GROUNDED,
    SLEEPING,
    FROZEN_UNLOADED
}

public record WorldItemMotionUpdateResult(
        Status status,
        Optional<WorldItemPhysicalSnapshot> snapshot) {
    public enum Status {
        APPLIED,
        STALE_REVISION,
        UNKNOWN_ITEM,
        INVALID_MOTION,
        REVISION_EXHAUSTED
    }
}
```

`WorldItemPhysicalSnapshot` must null-check the runtime/state fields.
`WorldItemMotionUpdate` must null-check ID/state and reject negative expected
revision while leaving finite validation to the service so `INVALID_MOTION` is
observable.

- [ ] **Step 4: Make LogicalWorldItemService the sole implementation**

Add physical state to its existing private `ItemState`, initialize committed
spawns as `ACTIVE`, return stable-ID-sorted immutable snapshots, and apply one
atomic replacement:

```java
OptionalLong advancedRevision = nextRevision(current.revision());
if (advancedRevision.isEmpty()) {
    return revisionExhausted(currentPhysicalSnapshot);
}
WorldItemSnapshot next = new WorldItemSnapshot(
        current.id(), current.stack(),
        update.positionX(), update.positionY(), update.positionZ(),
        update.velocityX(), update.velocityY(), update.velocityZ(),
        advancedRevision.getAsLong());
state.item = next;
state.physicalState = update.state();
return applied(state.physicalSnapshot(activeExtractions.containsKey(current.id())));
```

Check unknown ID, expected revision, all six finite components, and revision
availability before this assignment. Partial extraction commit uses the same
revision helper before terminalizing its reservation or releasing the active
lock. Existing spawn/extraction APIs must delegate to the same `ItemState`.

- [ ] **Step 5: Run focused GREEN and all world-item tests**

Run the focused command from Step 2, then:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.worlditem.*" --console=plain --no-daemon
```

Expected: all tests pass; existing Phase 7 reservation tests remain unchanged.

- [ ] **Step 6: Engine-owner checkpoint**

Review for signature preservation, stable ordering, shared revision ownership,
immutable values, main-thread assertions, and no engine-to-game dependency.

- [ ] **Step 7: Commit checkpoint after explicit authorization**

```powershell
git add engine/src/main/java/com/overlord/worlditem engine/src/test/java/com/overlord/worlditem
git commit -m "feat(world-items): add canonical physical runtime snapshots"
```

---

### Task 2: Applied-state failures and read-only reservation audit

**Files:**
- Create: `engine/src/main/java/com/overlord/core/transaction/ReservationTerminalState.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryReservationAudit.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryReservationAuditSnapshot.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemReservationAudit.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemReservationAuditSnapshot.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemCommitException.java`
- Modify: `game/src/main/java/com/gaia/inventory/BodyInventoryService.java`
- Modify: `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- Create: `engine/src/test/java/com/overlord/worlditem/WorldItemReservationAuditTest.java`
- Modify: `game/src/test/java/com/gaia/inventory/BodyInventoryServiceTest.java`

**Interfaces:**
- Consumes: Task 1 runtime authority and existing idempotent reservations.
- Produces read-only audit only for exceptional diagnosis:

```java
public interface InventoryReservationAudit {
    Optional<InventoryReservationAuditSnapshot> reservationAudit(
            InventoryReservationId reservationId);
}

public interface WorldItemReservationAudit {
    Optional<WorldItemReservationAuditSnapshot> reservationAudit(
            WorldItemReservationId reservationId);
}

public enum ReservationTerminalState {
    PENDING,
    COMMITTED,
    ROLLED_BACK
}
```

- [ ] **Step 1: Write RED tests for pending and terminal audit**

Assert reserve, commit, repeated commit, rollback, and unknown IDs without
calling a mutating method during audit:

```java
@Test
void worldReservationAuditReportsStateWithoutCompletingIt() {
    LogicalWorldItemService service = service(2, 0);
    WorldItemId item = service.spawn(request(DIRT, 2)).item().orElseThrow().id();
    WorldItemReservation reservation = service.reserve(item, 1).reservation().orElseThrow();

    assertEquals(ReservationTerminalState.PENDING,
            service.reservationAudit(reservation.id()).orElseThrow().state());
    assertEquals(2, service.snapshot(item).orElseThrow().stack().count());

    service.commit(reservation.id());
    assertEquals(ReservationTerminalState.COMMITTED,
            service.reservationAudit(reservation.id()).orElseThrow().state());
    assertEquals(1, service.snapshot(item).orElseThrow().stack().count());
}
```

Add the equivalent inventory test and verify event-sink `AssertionError` still
emerges as `InventoryEventDispatchException(stateChangeApplied=true)`.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.worlditem.WorldItemReservationAuditTest" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.inventory.BodyInventoryServiceTest" --console=plain --no-daemon
```

Expected: the audit interfaces and methods are missing.

- [ ] **Step 3: Implement immutable audit snapshots**

Each snapshot wraps the original reservation and one terminal enum. Return
`Optional.empty()` for an unknown ID. Audit must not unlock, apply, retry, or
publish events.

Define the explicit world failure:

```java
public final class WorldItemCommitException extends RuntimeException {
    private final WorldItemReservationId reservationId;
    private final boolean stateChangeApplied;

    public WorldItemCommitException(
            String message,
            Throwable cause,
            WorldItemReservationId reservationId,
            boolean stateChangeApplied) {
        super(message, cause);
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.stateChangeApplied = stateChangeApplied;
    }
}
```

Expose accessors and preserve the original cause.

- [ ] **Step 4: Make commit application ordering explicit**

In `LogicalWorldItemService`, compute the complete remainder or removal result
before changing terminal state. Apply the item map update and terminal state in
one main-thread method. If a post-application hook is introduced by a test fake,
it must report a typed `WorldItemCommitException(true)`; a pre-application
failure reports `false`.

In `BodyInventoryService`, implement `InventoryReservationAudit` over the
existing reservation map without changing mutation behavior.

- [ ] **Step 5: Run GREEN and related reservation suites**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.worlditem.*" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.inventory.*" --tests "com.gaia.interaction.BlockBreakTransactionTest" --tests "com.gaia.interaction.BlockPlacementTransactionTest" --console=plain --no-daemon
```

Expected: audit reads are side-effect free and all existing commit guarantees pass.

- [ ] **Step 6: Cross-owner checkpoint**

Verify audit is not used as normal control flow, no protected interface changed,
and raw post-apply failures cannot escape from built-in services.

- [ ] **Step 7: Commit checkpoint after explicit authorization**

```powershell
git add engine/src/main/java/com/overlord/core/transaction engine/src/main/java/com/overlord/inventory/api engine/src/main/java/com/overlord/worlditem game/src/main/java/com/gaia/inventory engine/src/test/java/com/overlord/worlditem game/src/test/java/com/gaia/inventory
git commit -m "feat(world-items): expose reservation outcome audit"
```

---

### Task 3: Stable-ID projection lifecycle and canonical writeback

**Files:**
- Create: `game/src/main/java/com/gaia/worlditem/WorldItemPhysicsConfig.java`
- Create: `game/src/main/java/com/gaia/worlditem/WorldItemPhysicsMetrics.java`
- Create: `game/src/main/java/com/gaia/worlditem/WorldItemPresentationSnapshot.java`
- Create: `game/src/main/java/com/gaia/worlditem/PhysicalWorldItemSystem.java`
- Create: `game/src/test/java/com/gaia/worlditem/PhysicalWorldItemSystemTest.java`

**Interfaces:**
- Consumes: `WorldItemRuntimeAccess`, `PhysicsWorld`, `CollisionWorld`,
  `ChunkRepository`, and `MainThreadGuard`.
- Produces:

```java
public final class PhysicalWorldItemSystem implements AutoCloseable {
    public void prepareStep(long tick);
    public void finishStep();
    public List<WorldItemPresentationSnapshot> presentationSnapshots();
    public WorldItemPhysicsMetrics metrics();
    public void close();
}
```

- [ ] **Step 1: Write RED lifecycle tests**

Use the real logical service and a real `PhysicsWorld` over an empty loaded
Chunk. Assert one body per stable ID, no stack field in projection-facing
snapshots, terminal removal, projection rebuild, and idempotent close:

```java
@Test
void stableIdOwnsAtMostOneProjectionAndLogicalAbsenceRemovesOnlyTheBody() {
    Fixture fixture = loadedFixture();
    WorldItemId id = fixture.spawn(DIRT, 1.5, 4.0, 1.5).id();

    fixture.system.prepareStep(1);
    fixture.system.prepareStep(1);
    assertEquals(1, fixture.physics.bodies().size());
    assertTrue(fixture.logical.snapshot(id).isPresent());

    WorldItemReservation reservation = fixture.logical.reserve(id, 1)
            .reservation().orElseThrow();
    fixture.logical.commit(reservation.id());
    fixture.system.prepareStep(2);

    assertTrue(fixture.logical.snapshot(id).isEmpty());
    assertTrue(fixture.physics.bodies().isEmpty());
    fixture.system.close();
    fixture.system.close();
}
```

Add a stale-write test where an external logical motion update occurs between
prepare and finish; assert the body is removed and the external snapshot wins.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.worlditem.PhysicalWorldItemSystemTest" --console=plain --no-daemon
```

Expected: the projection package and four public types do not exist.

- [ ] **Step 3: Add the approved immutable configuration**

```java
public record WorldItemPhysicsConfig(
        float edgeLength,
        float maximumFallSpeed,
        float restitution,
        float friction,
        float groundProbeDistance,
        float sleepSpeedThreshold,
        int sleepStableSteps,
        int depenetrationIterations,
        int worldHeight,
        float pickupReach) {
    public static WorldItemPhysicsConfig production() {
        return new WorldItemPhysicsConfig(
                0.50f, -30.0f, 0.12f, 0.25f, 0.02f, 0.05f,
                30, 8, GameConfig.Chunk.MAX_HEIGHT, 3.5f);
    }
}
```

Validate finite ranges, positive counts, `edgeLength == 0.50f` for production,
and `pickupReach > 0`. Keep the `1.00f` comparison fixture in tests only.

- [ ] **Step 4: Implement reconcile and writeback without collision refinements**

The private projection stores `WorldItemId`, `PhysicsBody`, last adopted
revision, previous canonical center, and sleep counter. It stores no stack.

Create a body with:

```java
float half = config.edgeLength() * 0.5f;
PhysicsBody body = new PhysicsBody(
        new Aabb(-half, -half, -half, half, half, half),
        MassProperties.dynamic(1.0f));
body.setGravityScale(1.0f);
body.setRestitution(config.restitution());
body.setFriction(config.friction());
body.teleport(center);
body.setLinearVelocity(velocity);
```

`finishStep` submits one `WorldItemMotionUpdate` per active body. Remove a body
after unknown/stale/invalid results. Adopt the returned snapshot only after
`APPLIED`.

- [ ] **Step 5: Implement immutable presentation and metrics**

`WorldItemPresentationSnapshot` contains the canonical item snapshot,
previous/current centers, and physical state. It validates alpha in `[0,1]`
when calculating render coordinates. `WorldItemPhysicsMetrics` reports live,
created, rebuilt, destroyed, lost, applied writes, stale rejections, and
capacity-skipped counts plus the latest skipped stable IDs as non-negative
immutable diagnostics. Capacity-limited IDs do not throw and are retried after
a slot frees.

- [ ] **Step 6: Run GREEN and determinism regression**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.worlditem.PhysicalWorldItemSystemTest" --console=plain --no-daemon
.\gradlew.bat :engine:test --tests "com.overlord.physics.*" --tests "com.overlord.worlditem.*" --console=plain --no-daemon
```

Expected: one projection per live loaded ID, canonical CAS ownership, and
idempotent cleanup all pass.

- [ ] **Step 7: Cross-owner checkpoint**

Verify the projection contains no ItemStack, no alternate position store, no
worker access, and body removal never invokes logical extraction.

- [ ] **Step 8: Commit checkpoint after explicit authorization**

```powershell
git add game/src/main/java/com/gaia/worlditem game/src/test/java/com/gaia/worlditem
git commit -m "feat(world-items): add stable physical projections"
```

---

### Task 4: Grounding, sleeping, bounds, and Chunk freeze

**Files:**
- Modify: `game/src/main/java/com/gaia/worlditem/PhysicalWorldItemSystem.java`
- Modify: `game/src/main/java/com/gaia/worlditem/WorldItemPhysicsMetrics.java`
- Create: `game/src/test/java/com/gaia/worlditem/PhysicalWorldItemCollisionTest.java`
- Modify: `game/src/test/java/com/gaia/worlditem/PhysicalWorldItemSystemTest.java`

**Interfaces:**
- Consumes: Task 3 projection lifecycle and existing `CollisionWorld`.
- Produces the complete ACTIVE/GROUNDED/SLEEPING/FROZEN_UNLOADED behavior.

- [x] **Step 1: Write RED physics-state tests**

Add deterministic tests for maximum fall speed, high-speed floor collision,
wall/corner slide, cross-Chunk floor, ground snap, 30-step sleep, support-loss
wake, unload freeze, reload rebuild, non-finite rejection, overlap recovery,
and lower-bound recovery.

The unload test must assert both authorities:

```java
@Test
void unloadFreezesCanonicalMotionAndReloadRebuildsTheSameStableId() {
    Fixture fixture = loadedFixture();
    WorldItemSnapshot spawned = fixture.spawn(DIRT, 15.75, 5.0, 8.0);
    fixture.step(4);
    WorldItemSnapshot beforeUnload = fixture.logical.snapshot(spawned.id()).orElseThrow();

    assertTrue(fixture.chunks.beginUnload(new ChunkKey(0, 0)));
    assertTrue(fixture.chunks.completeUnload(new ChunkKey(0, 0)));
    fixture.system.prepareStep(5);

    WorldItemPhysicalSnapshot frozen = fixture.logical
            .physicalSnapshot(spawned.id()).orElseThrow();
    assertEquals(WorldItemPhysicalState.FROZEN_UNLOADED, frozen.state());
    assertEquals(beforeUnload.positionX(), frozen.runtime().item().positionX());
    assertTrue(fixture.physics.bodies().isEmpty());

    fixture.loadEmptyChunk(new ChunkKey(0, 0));
    fixture.system.prepareStep(6);
    assertEquals(spawned.id(), fixture.system.presentationSnapshots()
            .get(0).item().id());
    assertEquals(1, fixture.physics.bodies().size());
}
```

- [x] **Step 2: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.worlditem.PhysicalWorldItemCollisionTest" --tests "com.gaia.worlditem.PhysicalWorldItemSystemTest" --console=plain --no-daemon
```

Expected: state remains ACTIVE, terminal velocity is unclamped, and unload has
no freeze transition.

- [x] **Step 3: Add pre-physics clamp, wake, and recovery**

Before `PhysicsWorld.step`, clamp `velocity.y` to at least `-30`. If the current
collider overlaps solid voxels, call `CollisionWorld.depenetrate` with eight
iterations. If it returns empty, scan center Y upward in exact `0.25f` steps
until `worldHeight + halfEdge` and use the first non-overlap position.

If center Y falls below `halfEdge`, start the same deterministic safe scan at
`halfEdge`. If the owning X/Z Chunk is absent, write `FROZEN_UNLOADED`, remove
the body after the write succeeds, and retain canonical velocity.

- [x] **Step 4: Add ground probe, snap, and sleep**

After physics, use one downward sweep of `groundProbeDistance`. A contact with
positive Y normal establishes support. Move only the reported safe downward
distance, zero a small downward velocity, and rely on the body's approved
friction/restitution for contact response.

Increment the stable counter only when grounded and full linear speed is at or
below `0.05`. At 30 steps, write `SLEEPING` and set the body sleeping flag.
Support loss, an external revision, explicit velocity, or reload resets the
counter and wakes to ACTIVE.

- [x] **Step 5: Run GREEN plus Phase 6 physics tests**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.worlditem.PhysicalWorldItem*" --console=plain --no-daemon
.\gradlew.bat :engine:test --tests "com.overlord.physics.*" --console=plain --no-daemon
```

Expected: every physical state and recovery test passes without modifying
player-controller behavior.

- [x] **Step 6: Owner checkpoint**

Review unloaded-Chunk reads, finite validation, collision iteration bounds,
sleep determinism, and absence of dynamic body collision.

- [ ] **Step 7: Commit checkpoint after explicit authorization**

```powershell
git add game/src/main/java/com/gaia/worlditem game/src/test/java/com/gaia/worlditem
git commit -m "feat(world-items): add deterministic dropped-item physics"
```

---

### Task 5: Independent world-item targeting

**Files:**
- Create: `game/src/main/java/com/gaia/worlditem/WorldItemTarget.java`
- Create: `game/src/main/java/com/gaia/worlditem/WorldItemTargetingService.java`
- Create: `game/src/test/java/com/gaia/worlditem/WorldItemTargetingServiceTest.java`

**Interfaces:**
- Consumes: immutable `WorldItemPhysicalSnapshot` values, player eye origin,
  Camera direction, and the Phase 6 `BlockRaycastService` for occlusion only.
- Produces:

```java
public record WorldItemTarget(
        WorldItemId itemId,
        WorldItemPhysicalSnapshot snapshot,
        float distance) {}

public Optional<WorldItemTarget> target(
        Vector3fc eye,
        Vector3fc direction,
        float maximumDistance,
        long tick,
        List<WorldItemPhysicalSnapshot> candidates);
```

- [ ] **Step 1: Write RED targeting tests**

Cover nearest hit, maximum-distance equality, AABB miss, stable-ID tie-break,
block occlusion, delay, extraction lock, frozen state, and immutable candidate
input. Use a normalized direction and centered 0.50 cube.

```java
@Test
void nearestVisibleEligibleItemWinsAndStableIdBreaksEqualDistances() {
    WorldItemPhysicalSnapshot higherId = physical(9, 0.0, 1.62, -3.0, 0, false);
    WorldItemPhysicalSnapshot lowerId = physical(4, 0.0, 1.62, -3.0, 0, false);
    WorldItemTargetingService service = targeting(Optional.empty());

    WorldItemTarget target = service.target(
            new Vector3f(0.0f, 1.62f, 0.0f),
            new Vector3f(0.0f, 0.0f, -1.0f),
            3.5f,
            10,
            List.of(higherId, lowerId)).orElseThrow();

    assertEquals(new WorldItemId(4), target.itemId());
    assertEquals(2.75f, target.distance(), 0.0001f);
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.worlditem.WorldItemTargetingServiceTest" --console=plain --no-daemon
```

Expected: targeting types do not exist.

- [ ] **Step 3: Implement deterministic slab intersection**

For each axis, intersect the ray with `[center-halfEdge, center+halfEdge]`.
Parallel axes must reject an origin outside the slab. Keep the largest entry
and smallest exit distances, reject `exit < max(entry, 0)`, and return the
non-negative entry. Normalize direction or reject zero/non-finite direction.

Call block raycast independently with the same eye/direction/reach. Its hit
distance is the exclusive visibility ceiling. Do not accept item hits behind
or at the opaque block surface.

- [ ] **Step 4: Filter canonical eligibility and sort**

Reject when:

```java
snapshot.state() == WorldItemPhysicalState.FROZEN_UNLOADED
        || snapshot.extractionReserved()
        || tick < snapshot.runtime().pickupAvailableTick();
```

Compare distance first and stable ID second. Do not modify or reorder the input
list supplied by the caller.

- [ ] **Step 5: Run GREEN and block-raycast regression**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.worlditem.WorldItemTargetingServiceTest" --tests "com.gaia.interaction.*Raycast*" --console=plain --no-daemon
.\gradlew.bat :engine:test --tests "com.overlord.physics.BlockRaycastTest" --console=plain --no-daemon
```

Expected: targeting is independent, occluded, deterministic, and renderer-free.

- [ ] **Step 6: Game-owner checkpoint**

Verify origin is supplied from authoritative player feet plus eye height,
direction comes from Camera, and no block hit is reused or mutated.

- [ ] **Step 7: Commit checkpoint after explicit authorization**

```powershell
git add game/src/main/java/com/gaia/worlditem game/src/test/java/com/gaia/worlditem
git commit -m "feat(world-items): add manual pickup targeting"
```

---

### Task 6: Multi-slot pickup conservation barrier

**Files:**
- Create: `game/src/main/java/com/gaia/inventory/InventoryReservationBatch.java`
- Create: `game/src/main/java/com/gaia/inventory/BodyInventoryReservationPlanner.java`
- Modify: `game/src/main/java/com/gaia/interaction/BlockBreakTransaction.java`
- Create: `game/src/main/java/com/gaia/worlditem/WorldItemPickupReceipt.java`
- Create: `game/src/main/java/com/gaia/worlditem/WorldItemPickupResult.java`
- Create: `game/src/main/java/com/gaia/worlditem/WorldItemPickupTransaction.java`
- Create: `game/src/test/java/com/gaia/inventory/BodyInventoryReservationPlannerTest.java`
- Create: `game/src/test/java/com/gaia/worlditem/WorldItemPickupTransactionTest.java`
- Modify: `game/src/test/java/com/gaia/interaction/BlockBreakTransactionTest.java`

**Interfaces:**
- Consumes: Task 2 audit/applied-state contracts, Task 5 target stable ID, the
  canonical inventory owner/active slot, and existing reservation guarantees.
- Produces:

```java
public record InventoryReservationBatch(
        List<InventoryReservation> reservations,
        int acceptedCount,
        Optional<ItemStack> remainder) {}

public InventoryReservationBatch reserveInsertion(
        EntityRef owner,
        BodySlot preferredSlot,
        ItemStack requested);

public record WorldItemPickupReceipt(
        WorldItemId itemId,
        ItemStack picked,
        double positionX,
        double positionY,
        double positionZ,
        long tick) {}
```

Every applied pickup result contains an optional immutable receipt captured
before terminal removal. The receipt is a committed fact for presentation; it
is not a store and cannot be used to retry or mutate either domain service.
`WorldItemPickupResult.committedReceipt()` returns
`Optional<WorldItemPickupReceipt>` and is empty for every non-applied status.

`WorldItemPickupResult.Status` is exactly:

```java
PICKED_ALL,
PICKED_PARTIAL,
PICKED_WITH_NOTIFICATION_FAILURE,
PICKUP_DELAYED,
UNKNOWN_ITEM,
WORLD_ITEM_BUSY,
INVENTORY_FULL,
INVENTORY_REJECTED,
WORLD_REJECTED,
COMMIT_GUARANTEE_BROKEN,
INDETERMINATE
```

- [ ] **Step 1: Write RED planner tests**

Assert active-slot-first order, merge before empty slot as enforced by the
inventory service, mouth rejection, two-handed atomic reservation, exact
accepted/remainder counts, and reverse rollback order.

Migrate `BlockBreakTransaction` to the planner only after recording its existing
strict trace and count assertions, so Phase 9A behavior cannot drift.

- [ ] **Step 2: Write RED transaction-table tests**

Create recording wrappers around the real inventory and logical world service.
Assert exact ordered traces and counts for every design-table row. The normal
partial case must assert:

```java
assertEquals(List.of(
        "inventory.reserve:RIGHT_HAND",
        "inventory.reserve:LEFT_HAND",
        "inventory.reserve:MOUTH",
        "world.reserve:2",
        "inventory.commit:0",
        "inventory.commit:1",
        "world.commit:0"), trace);
assertEquals(originalCount,
        result.inventoryCommittedCount() + result.remainingWorldCount());
assertEquals(stableId, world.snapshot(stableId).orElseThrow().id());
```

Add fakes for inventory notification RuntimeException, notification
`AssertionError`, world `WorldItemCommitException(true)`, world
`WorldItemCommitException(false)`, a non-COMMITTED fresh result, rollback
failure, duplicate commit, and untyped indeterminate failure.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.inventory.BodyInventoryReservationPlannerTest" --tests "com.gaia.worlditem.WorldItemPickupTransactionTest" --tests "com.gaia.interaction.BlockBreakTransactionTest" --console=plain --no-daemon
```

Expected: planner and pickup types are absent; the recorded block-break trace
still documents the pre-refactor baseline.

- [ ] **Step 4: Implement deterministic reservation acquisition**

Build a `LinkedHashSet` order with preferred slot first, followed by
`BodySlot.values()`. For each non-empty remainder, call
`InventoryService.reserve(INSERT)`, append any returned reservation, and carry
the returned remainder. Compute accepted count with `Math.addExact`.

Return immutable reservation lists. Put reverse rollback in one helper that
collects suppressed failures without replacing the primary failure.

- [ ] **Step 5: Implement pre-barrier pickup validation and reservation**

Re-read `WorldItemPhysicalSnapshot`, validate tick and state, reserve inventory,
then reserve exactly `acceptedCount` from the same stable ID. Accept world
status `RESERVED` or `PARTIALLY_RESERVED` only when the reservation count equals
the accepted count. A busy/rejected world result reverse-rolls back inventory.

- [ ] **Step 6: Implement the non-cancellable commit barrier**

Commit each fresh inventory reservation once. For
`InventoryEventDispatchException(true)`, add the reserved count and aggregate
the failure. Continue to world commit. For `WorldItemCommitException(true)`,
count the world extraction as applied and aggregate the failure. Never retry or
rollback applied commits.

If the cause of an applied typed failure is an `Error`, finish all guaranteed
commits, verify counts, attach other failures as suppressed, then rethrow the
original Error.

For an untyped failure, inspect the Task 2 audit without mutating it. If the
audit cannot prove committed or pending, return `INDETERMINATE` and request
fatal shutdown through an injected diagnostic sink. A fresh non-COMMITTED
result or `WorldItemCommitException(false)` is
`COMMIT_GUARANTEE_BROKEN`: request fatal shutdown, do not retry, and do not
attempt compensation that could duplicate already applied state.

- [ ] **Step 7: Verify conservation after both commits**

Read the remaining logical snapshot once. Treat absence as zero. Reject a
mismatched item identity or this equation:

```java
Math.addExact(inventoryCommittedCount, remainingWorldCount) == originalWorldCount
```

No result may derive counts from HUD or `InventoryViewModel`.

- [ ] **Step 8: Run GREEN and all inventory/interaction transaction tests**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.inventory.*" --tests "com.gaia.worlditem.WorldItemPickupTransactionTest" --tests "com.gaia.interaction.BlockBreakTransactionTest" --tests "com.gaia.interaction.BlockPlacementTransactionTest" --console=plain --no-daemon
```

Expected: all table rows, exact traces, fatal causes, idempotency, and Phase 9A
regressions pass.

- [ ] **Step 9: Inventory/game owner checkpoint**

Review two-handed locks, count arithmetic, applied-state handling, fatal Error
ordering, no blind retry, and absence of compensation after commit.

- [ ] **Step 10: Commit checkpoint after explicit authorization**

```powershell
git add game/src/main/java/com/gaia/inventory game/src/main/java/com/gaia/worlditem game/src/main/java/com/gaia/interaction/BlockBreakTransaction.java game/src/test/java/com/gaia/inventory game/src/test/java/com/gaia/worlditem game/src/test/java/com/gaia/interaction/BlockBreakTransactionTest.java
git commit -m "feat(world-items): add transactional manual pickup"
```

---

### Task 7: Shift+right routing, controller, and fixed-step composition

**Files:**
- Create: `game/src/main/java/com/gaia/worlditem/RoutedWorldInteractionInput.java`
- Create: `game/src/main/java/com/gaia/worlditem/WorldInteractionInputRouter.java`
- Create: `game/src/main/java/com/gaia/worlditem/WorldItemPickupController.java`
- Modify: `game/src/main/java/com/gaia/GameContext.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Create: `game/src/test/java/com/gaia/worlditem/WorldInteractionInputRouterTest.java`
- Create: `game/src/test/java/com/gaia/worlditem/WorldItemPickupControllerTest.java`
- Modify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`
- Create: `game/src/test/java/com/gaia/GameLoopFrameOrchestratorTest.java`

**Interfaces:**
- Consumes: Tasks 3 through 6, authoritative `GameModeManager`,
  `PlayerController.isNoclip()`, existing `InputSnapshot`, and lifecycle state.
- Produces:

```java
public record RoutedWorldInteractionInput(
        InputSnapshot blockInput,
        boolean pickupPressed) {}

public RoutedWorldInteractionInput route(
        InputSnapshot input,
        GameMode mode,
        boolean noclip,
        boolean interactionEnabled);

public Optional<WorldItemPickupResult> fixedUpdate(
        boolean pickupPressed,
        long tick);
```

- [ ] **Step 1: Write RED pure-routing tests**

Test both Shift keys, right press without Shift, Shift+right held without a new
press, F4 simultaneous with the chord, Creative, noclip, disabled interaction,
and a `heldOnly()` catch-up snapshot.

```java
@Test
void shiftRightClaimsPickupAndRemovesOnlyPlacementEdge() {
    InputSnapshot input = new InputSnapshot(
            Set.of(GLFW_KEY_LEFT_SHIFT),
            Set.of(),
            Set.of(GLFW_MOUSE_BUTTON_RIGHT),
            Set.of(GLFW_MOUSE_BUTTON_RIGHT),
            List.of());

    RoutedWorldInteractionInput routed = new WorldInteractionInputRouter()
            .route(input, GameMode.SURVIVAL, false, true);

    assertTrue(routed.pickupPressed());
    assertFalse(routed.blockInput().isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT));
    assertTrue(routed.blockInput().isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));
    assertEquals(input.downKeys(), routed.blockInput().downKeys());
}
```

The F4 case must assert `pickupPressed == false` and preserve F4 for the existing
mode owner.

- [ ] **Step 2: Write RED controller and catch-up tests**

Use recording target/transaction fakes to assert exactly one call on press, zero
calls across later held steps, release/re-press re-arm through `InputManager`,
and no transaction for failed target, delay, Creative, or noclip.

Add lifecycle tests for F1, focus loss, loading, blocking UI, and mode switch.
They must assert exact target and transaction call counts, not final visual
state.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.worlditem.WorldInteractionInputRouterTest" --tests "com.gaia.worlditem.WorldItemPickupControllerTest" --tests "com.gaia.GameLoopStructureTest" --tests "com.gaia.GameLoopFrameOrchestratorTest" --console=plain --no-daemon
```

Expected: routing/controller types are absent and GameLoop lacks the approved
projection/pickup order.

- [ ] **Step 4: Implement stateless routing**

Recognize either `GLFW_KEY_LEFT_SHIFT` or `GLFW_KEY_RIGHT_SHIFT`. A pickup chord
requires a right press edge, Survival, non-noclip, enabled interaction, and no
F4 press edge. Copy the two mouse sets only when removing the right press from
block input. Do not remove held state and do not keep any router fields.

- [ ] **Step 5: Implement one-edge pickup controller**

On `pickupPressed`, capture current authoritative feet plus eye height and
Camera forward, fetch one immutable physical snapshot list, target once, and
execute one transaction. Return a closed `WorldItemPickupResult`. Do not fall
through to placement after no target or transaction failure.

- [ ] **Step 6: Wire explicit GameContext ownership**

Add `GameModeManager`, `PhysicalWorldItemSystem`,
`WorldInteractionInputRouter`, and `WorldItemPickupController` to `GameContext`.
Construct them in `GameBootstrap` with constructor injection. Do not use
`ServiceLocator`.

- [ ] **Step 7: Apply the exact fixed-step order**

Extend the existing `runFixedSystemStep` composition without inventing a
second scheduler or bypassing its lifecycle predicate:

```java
runFixedSystemStep(
        () -> {
            context.inventoryInput().handle(
                    stepInput, inventoryTick, Optional.of(dropLocation()));
            runInventoryDebugShortcut(stepInput);
            context.playerManager().fixedUpdate(fixedDelta, stepInput);
            context.physicalWorldItems().prepareStep(inventoryTick);
            context.physicsWorld().step(fixedDelta);
            context.physicalWorldItems().finishStep();
        },
        () -> interactionEnabled(
                state == State.RUNNING,
                cursorCaptured,
                context.inputManager().isWindowFocused(),
                context.interactionBlockState().blocked()),
        fixedInteractionEnabled -> {
            RoutedWorldInteractionInput routed =
                    context.worldInteractionInput().route(
                            stepInput,
                            context.gameModes().mode(),
                            context.playerController().isNoclip(),
                            fixedInteractionEnabled);
            context.worldItemPickup().fixedUpdate(
                    routed.pickupPressed(), inventoryTick);
            context.blockInteraction().fixedUpdate(
                    routed.blockInput(), fixedDelta, inventoryTick,
                    Math.max(0L, System.nanoTime()),
                    fixedInteractionEnabled);
        },
        fixedInteractionEnabled ->
                context.interactionFeedback().fixedUpdate(
                        context.blockInteraction().viewModel(),
                        fixedInteractionEnabled,
                        inventoryTick),
        () -> {
            ModuleManager.getInstance().updateAll(fixedDelta);
            EventBus.getInstance().processAll();
        });
```

Keep the current lifecycle/mode-switch suppression in `InputManager` and
`GameLoopFrameOrchestrator`; the stateless router consumes that already-gated
snapshot. Keep debug shortcuts before physics and feedback/modules/events after
block interaction. Blocking pickup or block interaction must never skip
inventory input, player, physics, projection, modules, or event draining.

- [ ] **Step 8: Run GREEN plus all input/interaction tests**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.worlditem.*" --tests "com.gaia.interaction.*" --tests "com.gaia.GameLoop*" --console=plain --no-daemon
.\gradlew.bat :engine:test --tests "com.overlord.core.input.InputManagerTest" --tests "com.overlord.core.input.InputSnapshotTest" --console=plain --no-daemon
```

Expected: one press produces at most one pickup, ordinary placement remains one
per press, Survival held break remains fixed-step, and other systems never skip.

- [ ] **Step 9: Game-owner checkpoint**

Review simultaneous keys, mode authority, no second mouse state machine,
fixedSteps zero/catch-up behavior, and lifecycle suppression.

- [ ] **Step 10: Commit checkpoint after explicit authorization**

```powershell
git add game/src/main/java/com/gaia game/src/test/java/com/gaia
git commit -m "feat(world-items): route Shift-right manual pickup"
```

---

### Task 8: Six-face 0.50 block-item presentation

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/feedback/WorldItemFaceRegions.java`
- Modify: `engine/src/main/java/com/overlord/renderer/feedback/WorldItemVisual.java`
- Modify: `engine/src/main/java/com/overlord/renderer/feedback/OpenGlUnitCubeMesh.java`
- Modify: `engine/src/main/java/com/overlord/renderer/pass/WorldItemVisualPass.java`
- Modify: `engine/src/main/resources/assets/overlord/shaders/feedback/world_item.vert`
- Modify: `engine/src/main/resources/assets/overlord/shaders/feedback/world_item.frag`
- Create: `game/src/main/java/com/gaia/interaction/feedback/GaiaWorldItemFaceResolver.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/WorldItemVisualTracker.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/InteractionFeedbackCoordinator.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `engine/src/test/java/com/overlord/renderer/feedback/OpenGlUnitCubeMeshTest.java`
- Modify: `engine/src/test/java/com/overlord/renderer/pass/WorldItemVisualPassTest.java`
- Create: `game/src/test/java/com/gaia/interaction/feedback/GaiaWorldItemFaceResolverTest.java`
- Modify: `game/src/test/java/com/gaia/interaction/feedback/WorldItemVisualTrackerTest.java`

**Interfaces:**
- Consumes: existing `BlockRegistry.blockForItem`, `BlockRenderInfo.region`, Task
  3 presentation snapshots, and existing block atlas.
- Produces one immutable face set:

```java
public record WorldItemFaceRegions(Map<BlockFace, TextureRegion> regions) {
    public TextureRegion region(BlockFace face);
}
```

- [ ] **Step 1: Write RED resolver and tracker tests**

Assert all six grass/log face identities, stable-ID add/update/remove,
interpolated center, input-order independence, and explicit six-face missing
fallback. Assert source snapshots remain unchanged.

```java
@Test
void resolverUsesOwningBlockRenderInfoForEveryFace() {
    WorldItemFaceRegions faces = resolver.resolve(GRASS_ITEM);
    for (BlockFace face : BlockFace.values()) {
        assertEquals(blocks.resolve(GRASS_BLOCK_ID).region(face), faces.region(face));
    }
}
```

- [ ] **Step 2: Write RED mesh/shader/pass tests**

Assert `OpenGlUnitCubeMesh` uploads six floats per vertex with face indices
`0..5`, attribute location 2 is configured, the pass scales by `0.50f`, one
cube draw occurs per item, six face UV sets are uploaded, and normal/exceptional
render exits restore complete GL state.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.feedback.OpenGlUnitCubeMeshTest" --tests "com.overlord.renderer.pass.WorldItemVisualPassTest" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.GaiaWorldItemFaceResolverTest" --tests "com.gaia.interaction.feedback.WorldItemVisualTrackerTest" --console=plain --no-daemon
```

Expected: single-region visual contracts and 0.25 pass scale fail the new assertions.

- [ ] **Step 4: Add complete immutable face regions**

Copy into an `EnumMap`, require every `BlockFace`, reject nulls, and expose an
unmodifiable map. `GaiaWorldItemFaceResolver` must resolve item to owning block,
then call `BlockRegistry.resolve(block.id()).region(face)`. Unknown/non-block
items return six references to the existing missing atlas region and report one
diagnostic per item identity.

- [ ] **Step 5: Add face index without duplicating cube GPU resources**

Change `OpenGlUnitCubeMesh` from five to six floats per vertex. The sixth float
is a constant face index for each quad. Reorder `createVertices()` from its
current geometric order into this exact enum order while preserving each
face's established outward winding and UV orientation:

```text
0 NORTH, 1 SOUTH, 2 UP, 3 DOWN, 4 WEST, 5 EAST
```

Configure `layout(location=2)` as one float. The block-damage shader continues
to ignore the extra attribute and uses the same shared cube.

- [ ] **Step 6: Update GLSL 410 and pass UV upload**

The vertex shader emits a flat integer face index. The fragment shader declares
six-element `uMin`, `uMax`, `vMin`, and `vMax` arrays, selects by face index,
and preserves the existing single shader gamma path and alpha cutout.

The pass loops `BlockFace.values()` and calls scalar uniform names such as
`uMin[0]`. It uses one model matrix with `.scale(0.50f)` and one cube draw per
item. Update shader required-uniform validation to accept the array base names.

- [ ] **Step 7: Route immutable physical presentation to the tracker**

At render capture, request `presentationSnapshots()`. The projection exposes
uninterpolated immutable previous/current coordinates; the tracker resolves
faces by canonical item ID and performs the one interpolation step through the
presentation coordinate accessors. Frozen/sleeping snapshots are static.
Logical world-item count for debug remains derived from the logical service.

- [ ] **Step 8: Run GREEN, shader packaging, and feedback regression**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.feedback.*" --tests "com.overlord.renderer.pass.WorldItemVisualPassTest" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.*" --tests "com.gaia.InteractionFeedbackGameLoopTest" --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
```

Expected: six-face identity, 0.50 scale, interpolation, GLSL 410 packaging, and
state restoration all pass.

- [ ] **Step 9: Render/game owner checkpoint**

Review face order against mesh winding, no shared block-atlas UV mutation, one
cube GPU owner, stable identity, fallback diagnostics, and macOS-compatible GLSL.

- [ ] **Step 10: Commit checkpoint after explicit authorization**

```powershell
git add engine/src/main/java/com/overlord/renderer engine/src/main/resources/assets/overlord/shaders/feedback game/src/main/java/com/gaia/interaction/feedback game/src/main/java/com/gaia/GameLoop.java engine/src/test/java/com/overlord/renderer game/src/test/java/com/gaia/interaction/feedback
git commit -m "feat(rendering): show complete physical block drops"
```

---

### Task 9: Priority-safe particles and committed pickup feedback

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/particle/ParticlePriority.java`
- Create: `engine/src/main/java/com/overlord/renderer/particle/ParticleEmissionResult.java`
- Create: `engine/src/main/java/com/overlord/renderer/particle/ParticleAllocationMetrics.java`
- Modify: `engine/src/main/java/com/overlord/renderer/particle/ParticleCategory.java`
- Modify: `engine/src/main/java/com/overlord/renderer/particle/ParticleEmission.java`
- Modify: `engine/src/main/java/com/overlord/renderer/particle/ParticleSystem.java`
- Modify: `engine/src/main/java/com/overlord/renderer/feedback/ParticleVisual.java`
- Create: `game/src/main/java/com/gaia/interaction/feedback/CommittedPickupVisualAdapter.java`
- Modify: `game/src/main/java/com/gaia/worlditem/WorldItemPickupController.java`
- Modify: `engine/src/test/java/com/overlord/renderer/particle/ParticleSystemTest.java`
- Create: `game/src/test/java/com/gaia/interaction/feedback/CommittedPickupVisualAdapterTest.java`

**Interfaces:**
- Consumes: existing Phase 9B particle system and committed pickup result.
- Produces explicit priority and immutable metrics without another queue or pass.

- [ ] **Step 1: Write RED capacity and eviction tests**

Assert LOW cap 384, total cap 512, request cap 64 between fixed updates,
per-request cap 32, HIGH eviction of oldest LOW, LOW rejection when protected
capacity remains, and deterministic sequences.

```java
@Test
void lowPriorityCannotEvictCommittedParticles() {
    ParticleSystem system = new ParticleSystem();
    emitBatches(system, ParticleCategory.BREAK_COMMITTED,
            ParticlePriority.HIGH, 4, 32, 1);
    emitBatches(system, ParticleCategory.BREAK_CONTINUOUS,
            ParticlePriority.LOW, 12, 32, 5);
    List<Long> committedBefore = committedSequences(system.snapshot());

    ParticleEmissionResult rejected = system.emit(
            emission(ParticleCategory.BREAK_CONTINUOUS, ParticlePriority.LOW, 1, 3));

    assertEquals(ParticleEmissionResult.Status.REJECTED_LOW_CAP, rejected.status());
    assertEquals(committedBefore, committedSequences(system.snapshot()));
    assertEquals(512, system.snapshot().particles().size());
}
```

- [ ] **Step 2: Write RED committed-pickup tests**

Assert `PICKED_ALL`, `PICKED_PARTIAL`, and
`PICKED_WITH_NOTIFICATION_FAILURE` emit exactly one six-particle HIGH request.
Every delayed, missing, busy, full, reservation-failed, guarantee-broken,
indeterminate, duplicate-press, and cancelled result emits zero.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.particle.ParticleSystemTest" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.CommittedPickupVisualAdapterTest" --console=plain --no-daemon
```

Expected: priority/result/metrics types and pickup category are absent.

- [ ] **Step 4: Add priority-compatible emission contracts**

Add `PICKUP_COMMITTED` to `ParticleCategory`. Add LOW/HIGH to
`ParticlePriority`. Extend the canonical `ParticleEmission` with priority and
provide an overload matching the current constructor that maps
`BREAK_COMMITTED` and `PICKUP_COMMITTED` to HIGH and other current categories
to LOW. Reject counts above 32.

- [ ] **Step 5: Implement deterministic capacity policy**

Track LOW active count and requests since the last `fixedUpdate`. Return a
closed `ParticleEmissionResult`. LOW rejects at 384 or request 65. HIGH at total
capacity removes the first LOW in spawn-sequence order; only when none exists
does it remove the oldest HIGH. No code path permits LOW to remove HIGH.

Reset the per-step request counter at the start of `fixedUpdate`. Preserve
existing fixed 1/60 advancement and lifetime behavior.

- [ ] **Step 6: Add immutable allocation metrics**

Count received/admitted/rejected requests, particle states created/advanced,
low/high active counts, and evictions. `metrics()` returns a value copy; caller
mutation is impossible. `clear()` resets live particles but preserves lifetime
totals until explicit `resetMetrics()` used only by the profiling fixture.

- [ ] **Step 7: Emit committed pickup feedback once**

`CommittedPickupVisualAdapter` accepts the optional immutable
`WorldItemPickupReceipt` carried by the final result. It uses the receipt's
canonical item identity and committed position, resolves the existing UP face
or missing region, emits six HIGH particles with a seed derived from stable ID
and transaction tick, and never queries a removed world item after commit.

- [ ] **Step 8: Run GREEN and Phase 9B regression**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.particle.*" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.*" --tests "com.gaia.worlditem.WorldItemPickupControllerTest" --console=plain --no-daemon
```

Expected: committed effects are protected, pickup emits once, and current break
particle behavior remains deterministic.

- [ ] **Step 9: Render/game owner checkpoint**

Review request-window boundaries, deterministic eviction, no default pooling,
committed-only feedback, and compatibility with one future LOW ambience API.

- [ ] **Step 10: Commit checkpoint after explicit authorization**

```powershell
git add engine/src/main/java/com/overlord/renderer/particle engine/src/main/java/com/overlord/renderer/feedback/ParticleVisual.java game/src/main/java/com/gaia/interaction/feedback game/src/main/java/com/gaia/worlditem/WorldItemPickupController.java engine/src/test/java/com/overlord/renderer/particle game/src/test/java/com/gaia/interaction/feedback
git commit -m "feat(particles): protect committed interaction feedback"
```

---

### Task 10: Allocation and GC profiling fixture

**Files:**
- Create: `tools/src/main/java/com/gaia/tools/WorldItemPerformanceFixture.java`
- Create: `tools/src/test/java/com/gaia/tools/WorldItemPerformanceFixtureTest.java`
- Modify: `tools/build.gradle`
- Modify: `game/src/main/java/com/gaia/worlditem/WorldItemPhysicsMetrics.java`
- Modify: `engine/src/main/java/com/overlord/renderer/particle/ParticleAllocationMetrics.java`

**Interfaces:**
- Consumes: Task 3/4 world-item system, Task 9 particle metrics, JDK management
  beans when available.
- Produces deterministic console evidence; it does not alter gameplay defaults.

- [ ] **Step 1: Write RED short-fixture tests**

Run a 120-step fixture with a fixed seed and assert exact steps, cap-respecting
live counts, non-negative allocated bytes, supported/unsupported allocation
flag, GC deltas, and stable result formatting.

```java
@Test
void shortFixtureIsDeterministicAndReportsBoundedMetrics() {
    WorldItemPerformanceFixture.Result first =
            WorldItemPerformanceFixture.run(new Configuration(7L, 32, 64, 120, 0));
    WorldItemPerformanceFixture.Result second =
            WorldItemPerformanceFixture.run(new Configuration(7L, 32, 64, 120, 0));

    assertEquals(first.simulationHash(), second.simulationHash());
    assertEquals(120, first.sampleSteps());
    assertTrue(first.peakWorldItems() <= 32);
    assertTrue(first.peakParticles() <= 64);
    assertTrue(first.allocatedBytes() >= 0);
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :tools:test --tests "com.gaia.tools.WorldItemPerformanceFixtureTest" --console=plain --no-daemon
```

Expected: fixture and Gradle task do not exist.

- [ ] **Step 3: Implement deterministic headless fixture**

Build a flat loaded voxel world, the real logical service, real physical item
system, and real particle system. Spawn IDs in deterministic order. Advance
exactly the configured number of fixed steps without a window or OpenGL.

Use `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` only when
supported and enabled. Read `GarbageCollectorMXBean` before and after, and
register a `NotificationListener` on collector `NotificationEmitter`
implementations to extract `GarbageCollectionNotificationInfo` pause durations.
Remove each listener in `finally`. Report unsupported counters explicitly
instead of inventing zero evidence.

- [ ] **Step 4: Add the full profiling command**

Register:

```groovy
tasks.register('profileWorldItems', JavaExec) {
    group = 'verification'
    description = 'Runs the deterministic 10s warm-up and 60s Phase 11 allocation fixture.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.gaia.tools.WorldItemPerformanceFixture'
    args '12345', '1024', '512', '600', '3600'
}
```

The fixture prints world-item cap, particle cap, allocated bytes per second, GC
collection delta, GC time delta, and simulation hash.

- [ ] **Step 5: Run GREEN short fixture**

```powershell
.\gradlew.bat :tools:test --tests "com.gaia.tools.WorldItemPerformanceFixtureTest" --console=plain --no-daemon
```

Expected: deterministic short fixture passes without starting GLFW.

- [ ] **Step 6: Run and record the full fixture**

```powershell
.\gradlew.bat :tools:profileWorldItems --console=plain --no-daemon
```

Compare against the approved thresholds: sustained Phase 11 allocation above
2 MiB/s, more than two GC collections/minute, or maximum available GC pause
evidence above 5 ms. Do not add pooling in this task. Record evidence in the
Phase 11 progress handoff.

- [ ] **Step 7: Performance checkpoint**

Attribute allocations between existing `PhysicsWorld`, Phase 11 projection,
and ParticleSystem before recommending any reuse. Confirm no public value is a
pool candidate.

- [ ] **Step 8: Commit checkpoint after explicit authorization**

```powershell
git add tools/src tools/build.gradle game/src/main/java/com/gaia/worlditem/WorldItemPhysicsMetrics.java engine/src/main/java/com/overlord/renderer/particle/ParticleAllocationMetrics.java
git commit -m "test(performance): add bounded world-item profiling fixture"
```

---

### Task 11: Shutdown, exceptional cleanup, and branch-wide integration

**Files:**
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `game/src/main/java/com/gaia/GameContext.java`
- Modify: nested `GameBootstrap.ShutdownBarrier` in
  `game/src/main/java/com/gaia/GameBootstrap.java`.
- Modify: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- Create: `game/src/test/java/com/gaia/worlditem/PhysicalWorldItemShutdownTest.java`
- Create: `game/src/test/java/com/gaia/worlditem/WorldItemIntegrationTest.java`
- Modify: `game/src/test/java/com/gaia/UiGameLoopIntegrationTest.java`
- Modify: `game/src/test/java/com/gaia/InteractionFeedbackGameLoopTest.java`

**Interfaces:**
- Consumes: all prior tasks.
- Produces final composition and reverse cleanup without changing logical items.

- [ ] **Step 1: Write RED shutdown-order tests**

Use recording fakes to assert this exact order:

```text
stop-new-actions
finish-or-rollback-pickup
remove-physics-bodies
clear-world-item-presentation
clear-particles
close-renderer
close-world-executor
close-engine
```

Assert normal close, repeated close, partial construction failure, body-removal
failure, cache-clear failure, and renderer-close failure aggregate exceptions
without skipping later cleanup. Assert logical snapshots remain after projection
close.

- [ ] **Step 2: Write RED integrated behavior tests**

Cover:

- Q spawn joins projection before the current physics step;
- block-break spawn joins projection next step;
- post-physics pickup targets canonical current position;
- full pickup removes one body and one logical ID;
- partial pickup keeps the same stable ID and updates visual count/position;
- lifecycle blocking prevents pickup but not player/physics/modules;
- renderer receives immutable presentation and no mutable service;
- DebugHud reads counts without mutation;
- Phase 9A event/dirty traces remain byte-for-byte ordered.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.worlditem.PhysicalWorldItemShutdownTest" --tests "com.gaia.worlditem.WorldItemIntegrationTest" --tests "com.gaia.GameBootstrapStructureTest" --tests "com.gaia.UiGameLoopIntegrationTest" --tests "com.gaia.InteractionFeedbackGameLoopTest" --console=plain --no-daemon
```

Expected: shutdown registration and full integrated composition are incomplete.

- [ ] **Step 4: Register reverse ownership once**

Register physical-world-item cleanup after logical service construction and
before engine close so LIFO shutdown removes bodies and CPU presentation before
renderer/context destruction. Ensure the pickup controller rejects new work as
soon as GameLoop enters STOPPING. Because the commit barrier is synchronous,
finish an entered barrier before loop exit; reverse-roll back only pre-barrier
reservations.

- [ ] **Step 5: Make cleanup failure-complete and idempotent**

`PhysicalWorldItemSystem.close()` marks closed, unregisters every body exactly
once, clears projection/presentation data, and never invokes world extraction.
Attach secondary failures with `addSuppressed` and rethrow the primary after all
bodies are attempted.

- [ ] **Step 6: Run GREEN integrated suites**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.worlditem.*" --tests "com.gaia.GameBootstrapStructureTest" --tests "com.gaia.UiGameLoopIntegrationTest" --tests "com.gaia.InteractionFeedbackGameLoopTest" --console=plain --no-daemon
```

Expected: composition, counts, order, immutable presentation, and cleanup pass.

- [ ] **Step 7: Run all Phase 6 through 10 related regression suites**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.*" --tests "com.overlord.worlditem.*" --tests "com.overlord.renderer.*" --console=plain --no-daemon
.\gradlew.bat :game:test --tests "com.gaia.inventory.*" --tests "com.gaia.interaction.*" --tests "com.gaia.ui.*" --tests "com.gaia.worlditem.*" --console=plain --no-daemon
```

Expected: no regressions in physics, inventory, block interaction, feedback, or UI.

- [ ] **Step 8: Engine/game/render owner checkpoint**

Review shutdown ownership, exception aggregation, no logical deletion, GL thread
ownership, immutable render capture, and Phase 9A/9B ordering.

- [ ] **Step 9: Commit checkpoint after explicit authorization**

```powershell
git add game/src/main/java/com/gaia game/src/test/java/com/gaia
git commit -m "feat(world-items): integrate physical pickup lifecycle"
```

---

### Task 12: Documentation, complete verification, and final read-only review

**Files:**
- Modify: `docs/architecture/physical-world-items.md`
- Modify: `docs/agent-handoffs/phase-11-progress.md`
- Modify: `docs/architecture/current-baseline.md` only to describe confirmed Phase 11 state after implementation.
- Create: `docs/testing/phase-11-world-item-acceptance.md`

**Interfaces:**
- Consumes: final implementation and measured evidence from Tasks 1 through 11.
- Produces the delivery record; no production behavior originates in docs.

- [ ] **Step 1: Replace design-status wording with verified implementation facts**

Record completed and unfinished scope, final constants, authority diagram,
transaction table, exact changed-file inventory, test commands/results,
profiling output, owner verdicts, risks, and protected interfaces. Keep platform
claims evidence-based.

- [ ] **Step 2: Run focused module suites**

```powershell
.\gradlew.bat :engine:test --console=plain --no-daemon
.\gradlew.bat :game:test --console=plain --no-daemon
.\gradlew.bat :tools:test --console=plain --no-daemon
```

Expected: all tests pass; record exact Engine/Game/Tools and total counts from
the reports rather than estimating.

- [ ] **Step 3: Run clean build**

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

Expected: `BUILD SUCCESSFUL` on JDK 21 with Java 17 source/target compatibility.

- [ ] **Step 4: Run all packaged resource checks**

```powershell
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
```

Expected: all three tasks pass and GLSL resources are present in JAR/installDist.

- [ ] **Step 5: Run repository hygiene and architecture scans**

```powershell
git diff --check
git status --short --untracked-files=all
git ls-files --others --exclude-standard
git diff --stat
git diff --name-status
git ls-files | rg "(^|/)(build|bin)/|\.class$|hs_err_pid|replay_pid"
rg -n "org\.gradle\.java\.home|/Library/Java|[A-Za-z]:\\\\.*jdk" . --glob "!**/build/**"
rg -n "#version (42[0-9]|4[2-9][0-9])|GL_COMPUTE_SHADER|GL_SHADER_STORAGE_BUFFER|SSBO" engine game --glob "!**/build/**"
rg -n "com\.gaia" engine/src/main engine/build.gradle
rg -n "WorldMutationService|InventoryService|WorldItemService|PhysicsWorld" engine/src/main/java/com/overlord/renderer
rg -n "record ItemStack|class ItemStack|BlockDropEntity|BlockStack|new LogicalWorldItemService" engine/src/main game/src/main
```

Expected: no whitespace errors, generated tracked files, absolute JDK paths,
GL 4.2+ features, engine-to-game dependency, renderer gameplay dependency, or
duplicate domain model. The sole expected `new LogicalWorldItemService` is the
game bootstrap composition root.

- [ ] **Step 6: Run Windows development and installDist acceptance**

Run:

```powershell
.\gradlew.bat :game --console=plain --no-daemon
.\gradlew.bat :game:installDist --console=plain --no-daemon
.\game\build\install\game\bin\game.bat
```

Manually verify Q/block drop parity, complete and partial pickup, full inventory,
pickup delay, stable-ID remainder, 0.50 production cube, 1.00 comparison fixture,
floor/wall/Chunk collisions, sleep/wake, F1, F4, Alt+Tab, resize, UI, particles,
and Escape. Record both process exit codes.

- [ ] **Step 7: Run macOS acceptance or record NOT RUN**

On a Mac:

```bash
./gradlew clean test build --console=plain --no-daemon
./gradlew :game --console=plain --no-daemon
```

Verify native launch, Retina, resize, focus, item collision, six-face GLSL 410,
pickup, particles, and shutdown. If no Mac is available, write `macOS: NOT RUN`
without implying compatibility was manually proven.

- [ ] **Step 8: Run the full profiling fixture and record evidence**

```powershell
.\gradlew.bat :tools:profileWorldItems --console=plain --no-daemon
```

Record seed, caps, warm-up/sample durations, simulation hash, allocated bytes/s,
GC count/time, and whether any approved pooling threshold was crossed. Do not
introduce pooling during final verification; a measured optimization requires a
separate approved design.

- [ ] **Step 9: Perform independent owner reviews**

Request separate read-only reviews from:

- Engine/physics/world-item owner;
- Game/inventory/interaction owner;
- Render/particle owner;
- final branch-wide reviewer.

Each review classifies Critical, Important, and Minor findings with exact
file/line, failure scenario, violated invariant, correction, and missing
regression test. Any finding requires a fresh RED/GREEN task before re-running
the affected and full suites.

- [ ] **Step 10: Final documentation and status check**

Update `phase-11-progress.md` with final HEAD, complete diff stat, exact test
counts, profiling evidence, Windows exit codes, macOS status, owner verdicts,
known risks, and clean/dirty worktree state. Do not claim READY unless reviews
report zero Critical, Important, and Minor findings and every required command
has current evidence.

- [ ] **Step 11: Final commit checkpoint after explicit authorization**

```powershell
git add docs/architecture/physical-world-items.md docs/architecture/current-baseline.md docs/agent-handoffs/phase-11-progress.md docs/testing/phase-11-world-item-acceptance.md
git commit -m "docs(physics): record physical world-item verification"
```

Suggested final squash message if the maintainers choose one commit:

```text
feat(physics): add physical world items and manual pickup
```

Suggested PR title:

```text
feat(physics): add stable-ID physical drops and transactional pickup
```

## Final plan self-review checklist

- Every approved design section maps to at least one task.
- Task interfaces use one consistent type and method spelling throughout.
- Phase 7 public signatures remain protected.
- Stable ID maps to one canonical item, one optional body, and one rebuildable visual.
- Shift+right cannot fall through to placement or repeat in catch-up steps.
- Full, partial, failure, applied-state, fatal Error, rollback, duplicate, and shutdown transaction paths have explicit tests.
- Chunk unload, reload, bounds, depenetration, sleep, wake, and terminal removal are covered.
- LOW particles cannot evict committed HIGH effects.
- Pooling is disabled and evidence-gated.
- Future ambience work has one compatible particle API and merge order.
- Renderer and UI retain immutable read-only boundaries.
- Windows, macOS, packaged resources, hygiene scans, and owner reviews have concrete commands.
- No unresolved implementation placeholder remains in this plan.
