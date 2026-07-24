# Phase 7 Prompt Suite v2.1 Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the approved Phase 7 interaction and inventory contracts with Prompt Suite v2.1 before push or pull-request creation, without implementing Phase 8 gameplay, production inventory, world-item entities, physics drops, rendering, or UI.

**Architecture:** Introduce one immutable `ItemStack` domain value and keep `ItemStackView` as its read-only presentation projection. Add reservation-oriented inventory and world-item API contracts with stateful test fixtures only. Move dirty/revision outcome authority into `ChunkRepository`, surface the committed result through `World` and `BlockWorldAccess`, and make `DefaultWorldMutationService` publish post-commit observations from that outcome instead of independently calculating dirty chunks.

**Tech Stack:** Java 17 source compatibility, Gradle 8.5 Wrapper, JUnit Jupiter, existing `ResourceLocation`, Phase 3 `ChunkRepository`, and Phase 7 `java-test-fixtures`.

## Global Constraints

- Work only on `feat/interaction-api-contracts`; never modify, push, or merge `main`.
- Do not push, create a pull request, merge, force-push, or force-merge.
- Keep Java 17 source and target compatibility and use the checked-in Gradle Wrapper.
- Preserve macOS OpenGL 4.1 / GLSL 410 compatibility and main/context-thread ownership of every OpenGL/GPU operation.
- Do not modify `Renderer`, `PlayerController`, `ChunkMeshManager`, or production gameplay.
- Do not implement production inventory rules, production world-item entities, block breaking/placement, Q-drop gameplay, Phase 11 physics drops, or UI.
- Do not add a second item registry; item identity remains `ResourceLocation`.
- `ChunkRepository` remains the sole dirty/revision/stale-result authority.
- Follow strict TDD: add a focused failing test, observe the expected failure, implement the minimum production or test-fixture code, then rerun focused and affected tests.
- Preserve the existing 503 passing tests and add focused contract tests.
- Test fixtures must remain absent from production runtime classpaths.
- Do not copy third-party code or assets.

---

### Task 1: Canonical immutable ItemStack

**Files:**
- Create: `engine/src/main/java/com/overlord/inventory/api/ItemStack.java`
- Modify: `engine/src/main/java/com/overlord/inventory/api/ItemStackView.java`
- Modify: `engine/src/main/java/com/overlord/inventory/api/InventoryChangeRequest.java`
- Modify: `engine/src/main/java/com/overlord/interaction/api/ItemUseContext.java`
- Modify: `engine/src/testFixtures/java/com/overlord/inventory/testing/TestItemStackView.java`
- Modify: `engine/src/test/java/com/overlord/inventory/api/InventoryContractTest.java`
- Modify: `engine/src/test/java/com/overlord/interaction/api/InteractionContractTest.java`
- Modify: `game/src/test/java/com/gaia/contracts/EngineContractFixtureSmokeTest.java`

**Interfaces:**
- Produces: `record ItemStack(ResourceLocation itemId, int count) implements ItemStackView`
- Changes command-side stack fields from `ItemStackView` to `ItemStack`
- Preserves read-only `ItemStackView.itemId()` and `ItemStackView.count()`

- [x] **Step 1: Write failing canonical value tests**

Add tests equivalent to:

```java
@Test
void itemStackIsTheCanonicalValidatedImmutableValue() {
    ResourceLocation stone = ResourceLocation.parse("gaia:stone");
    assertEquals(stone, new ItemStack(stone, 2).itemId());
    assertThrows(NullPointerException.class, () -> new ItemStack(null, 1));
    assertThrows(IllegalArgumentException.class, () -> new ItemStack(stone, 0));
    assertThrows(IllegalArgumentException.class, () -> new ItemStack(stone, -1));
}
```

Add reflection assertions that `InventoryChangeRequest.replacement()` and
`ItemUseContext.heldStack()` are parameterized with `ItemStack`, while
`InventoryView.stack()` and `InteractionViewModel.activeItem()` remain view
surfaces.

- [x] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.inventory.api.InventoryContractTest --tests com.overlord.interaction.api.InteractionContractTest
```

Expected: compilation fails because `ItemStack` does not exist or the command
types still use `ItemStackView`.

- [x] **Step 3: Implement the minimal canonical value**

Implement:

```java
public record ItemStack(ResourceLocation itemId, int count)
        implements ItemStackView {
    public ItemStack {
        itemId = Objects.requireNonNull(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}
```

Use `Optional<ItemStack>` in command values. Keep `ItemStackView` documented
as a snapshot/projection and do not introduce any item registry.

- [x] **Step 4: Verify GREEN**

Run the focused command from Step 2 and then:

```powershell
.\gradlew.bat :engine:test :game:test
```

Expected: all affected tests pass.

- [x] **Step 5: Commit**

```powershell
git add engine/src/main engine/src/test engine/src/testFixtures game/src/test docs/superpowers/plans/2026-07-25-phase-7-prompt-suite-v2-1-alignment.md
git commit -m "feat(api): add canonical immutable item stack"
```

### Task 2: Inventory reservation contracts and stateful fake

**Files:**
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryReservationId.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryReservationOperation.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryReservationRequest.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryReservation.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryReserveResult.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryReservationResult.java`
- Modify: `engine/src/main/java/com/overlord/inventory/api/InventoryService.java`
- Create: `engine/src/testFixtures/java/com/overlord/inventory/testing/FakeInventoryReservationService.java`
- Modify: `engine/src/testFixtures/java/com/overlord/inventory/testing/StubInventoryService.java`
- Create: `engine/src/test/java/com/overlord/inventory/api/InventoryReservationContractTest.java`
- Modify: `game/src/test/java/com/gaia/contracts/EngineContractFixtureSmokeTest.java`

**Interfaces:**
- Consumes: canonical `ItemStack`
- Produces:

```java
InventoryReserveResult reserve(InventoryReservationRequest request);
InventoryReservationResult commit(InventoryReservationId reservationId);
InventoryReservationResult rollback(InventoryReservationId reservationId);
```

- [x] **Step 1: Write failing reservation value and service tests**

Cover:

```java
@Test
void successfulReservationSeparatesReservedStackAndRemainder() {
    FakeInventoryReservationService service =
            new FakeInventoryReservationService(Optional.of(inventory(1)));
    service.setNextReservationLimit(3);
    InventoryReserveResult result =
            service.reserve(
                    new InventoryReservationRequest(
                            OWNER,
                            BodySlot.RIGHT_HAND,
                            InventoryReservationOperation.INSERT,
                            new ItemStack(STONE_ID, 5)));

    assertEquals(InventoryReserveResult.Status.PARTIALLY_RESERVED, result.status());
    assertEquals(3, result.reservation().orElseThrow().reserved().count());
    assertEquals(new ItemStack(STONE_ID, 2), result.remainder().orElseThrow());
}

@Test
void repeatedCommitIsIdempotentAcrossOrdinaryStateChanges() {
    FakeInventoryReservationService service =
            new FakeInventoryReservationService(Optional.of(inventory(1)));
    InventoryReservationId id =
            service.reserve(extraction(new ItemStack(STONE_ID, 1)))
                    .reservation().orElseThrow().id();
    service.simulateOrdinaryStateChange(inventory(2));

    assertEquals(
            InventoryReservationResult.Status.COMMITTED,
            service.commit(id).status());
    assertEquals(
            InventoryReservationResult.Status.ALREADY_COMMITTED,
            service.commit(id).status());
    assertEquals(1, service.commitSideEffectCount());
    assertEquals(
            InventoryReservationResult.Status.TERMINAL_CONFLICT,
            service.rollback(id).status());
}

@Test
void repeatedRollbackIsIdempotentAndBlocksLaterCommit() {
    FakeInventoryReservationService service =
            new FakeInventoryReservationService(Optional.of(inventory(1)));
    InventoryReservationId id =
            service.reserve(extraction(new ItemStack(STONE_ID, 1)))
                    .reservation().orElseThrow().id();

    assertEquals(
            InventoryReservationResult.Status.ROLLED_BACK,
            service.rollback(id).status());
    assertEquals(
            InventoryReservationResult.Status.ALREADY_ROLLED_BACK,
            service.rollback(id).status());
    assertEquals(1, service.rollbackSideEffectCount());
    assertEquals(
            InventoryReservationResult.Status.TERMINAL_CONFLICT,
            service.commit(id).status());
}
```

Require `INSERT` and `EXTRACT`, positive stable IDs, a positive reserved stack,
an optional positive same-item remainder, explicit failure statuses, and
unknown-reservation results.

- [x] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.inventory.api.InventoryReservationContractTest
```

Expected: compilation fails because reservation contracts do not exist.

- [x] **Step 3: Implement minimal contracts and fake**

Use these status families:

```java
InventoryReserveResult.Status:
    RESERVED, PARTIALLY_RESERVED, REJECTED,
    UNKNOWN_OWNER, INVALID_STACK

InventoryReservationResult.Status:
    COMMITTED, ROLLED_BACK, ALREADY_COMMITTED,
    ALREADY_ROLLED_BACK, TERMINAL_CONFLICT,
    UNKNOWN_RESERVATION
```

The stateful fake records `RESERVED`, `COMMITTED`, or `ROLLED_BACK` per
reservation ID. Repeating the same terminal operation produces no second side
effect. Opposite terminal operations return `TERMINAL_CONFLICT`. Its commit
decision depends only on the reservation ledger, not the current inventory
revision. It does not implement stacking, capacity, or production storage
rules.

- [x] **Step 4: Verify GREEN**

Run the focused command and:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.inventory.api.InventoryContractTest
.\gradlew.bat :game:test --tests com.gaia.contracts.EngineContractFixtureSmokeTest
```

Expected: all focused tests pass.

- [x] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/inventory engine/src/test/java/com/overlord/inventory engine/src/testFixtures/java/com/overlord/inventory game/src/test
git commit -m "feat(api): define inventory reservation contracts"
```

### Task 3: World item source-of-truth contracts and fake

**Files:**
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemId.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemReservationId.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemSpawnRequest.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemSpawnResult.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemReservation.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemReservationResult.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemSnapshot.java`
- Create: `engine/src/main/java/com/overlord/worlditem/api/WorldItemService.java`
- Create: `engine/src/testFixtures/java/com/overlord/worlditem/testing/FakeWorldItemService.java`
- Create: `engine/src/test/java/com/overlord/worlditem/api/WorldItemContractTest.java`
- Modify: `game/src/test/java/com/gaia/contracts/EngineContractFixtureSmokeTest.java`

**Interfaces:**
- Consumes: canonical `ItemStack`
- Produces a single stable `WorldItemId` namespace and:

```java
WorldItemSpawnResult spawn(WorldItemSpawnRequest request);
Optional<WorldItemSnapshot> snapshot(WorldItemId itemId);
WorldItemReservationResult reserve(WorldItemId itemId, int count);
WorldItemReservationResult commit(WorldItemReservationId reservationId);
WorldItemReservationResult rollback(WorldItemReservationId reservationId);
```

- [x] **Step 1: Write failing world-item contract tests**

Cover:

```java
@Test
void spawnedSnapshotKeepsStableWorldIdAndCanonicalStack() {
    FakeWorldItemService service = new FakeWorldItemService();
    ItemStack stack = new ItemStack(STONE_ID, 5);
    WorldItemSpawnResult result =
            service.spawn(spawnRequest(stack));
    WorldItemSnapshot spawned = result.item().orElseThrow();

    assertEquals(WorldItemSpawnResult.Status.SPAWNED, result.status());
    assertEquals(stack, spawned.stack());
    assertEquals(
            spawned,
            service.snapshot(spawned.id()).orElseThrow());
}

@Test
void partialReservationCommitRemovesOnlyReservedCount() {
    FakeWorldItemService service = new FakeWorldItemService();
    WorldItemId id =
            service.spawn(spawnRequest(new ItemStack(STONE_ID, 5)))
                    .item().orElseThrow().id();
    WorldItemReservationResult reserved = service.reserve(id, 3);
    WorldItemReservation reservation = reserved.reservation().orElseThrow();

    assertEquals(
            WorldItemReservationResult.Status.PARTIALLY_RESERVED,
            reserved.status());
    assertEquals(new ItemStack(STONE_ID, 2), reserved.remainder().orElseThrow());
    assertEquals(
            WorldItemReservationResult.Status.COMMITTED,
            service.commit(reservation.id()).status());
    assertEquals(
            new ItemStack(STONE_ID, 2),
            service.snapshot(id).orElseThrow().stack());
    assertEquals(
            WorldItemReservationResult.Status.ALREADY_COMMITTED,
            service.commit(reservation.id()).status());
}

@Test
void rollbackRestoresAvailabilityAndIsIdempotent() {
    FakeWorldItemService service = new FakeWorldItemService();
    WorldItemId id =
            service.spawn(spawnRequest(new ItemStack(STONE_ID, 5)))
                    .item().orElseThrow().id();
    WorldItemReservationId reservationId =
            service.reserve(id, 5).reservation().orElseThrow().id();

    assertEquals(
            WorldItemReservationResult.Status.ROLLED_BACK,
            service.rollback(reservationId).status());
    assertEquals(
            WorldItemReservationResult.Status.ALREADY_ROLLED_BACK,
            service.rollback(reservationId).status());
    assertEquals(
            new ItemStack(STONE_ID, 5),
            service.snapshot(id).orElseThrow().stack());
    assertEquals(
            WorldItemReservationResult.Status.TERMINAL_CONFLICT,
            service.commit(reservationId).status());
}
```

Also use reflection/source architecture assertions to ensure the API has no
dependency on ECS, physics, rendering, LWJGL, OpenGL, or `game`.

- [x] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.worlditem.api.WorldItemContractTest
```

Expected: compilation fails because the world-item API does not exist.

- [x] **Step 3: Implement minimal contracts and stateful fake**

`WorldItemSpawnRequest` and `WorldItemSnapshot` contain primitive finite
position and velocity components, a canonical `ItemStack`, a non-negative
tick, and an optional source `EntityRef`. `WorldItemSnapshot` adds a
non-negative revision. The fake allocates monotonically increasing stable
item/reservation IDs and models only spawn, reservation availability,
commit, rollback, partial remainder, and terminal idempotency. It creates no
production entity or `PhysicsBody`.

- [x] **Step 4: Verify GREEN**

Run the focused command and:

```powershell
.\gradlew.bat :game:test --tests com.gaia.contracts.EngineContractFixtureSmokeTest
```

Expected: all focused tests pass.

- [x] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/worlditem engine/src/test/java/com/overlord/worlditem engine/src/testFixtures/java/com/overlord/worlditem game/src/test
git commit -m "feat(api): define world item service contracts"
```

### Task 4: Repository-owned block mutation outcome

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/DirtyChunkRevision.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkMutationOutcome.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `engine/src/main/java/com/overlord/voxel/World.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/WorldTest.java`

**Interfaces:**
- Produces:

```java
ChunkMutationOutcome compareAndSetBlock(
        int worldX, int y, int worldZ,
        byte expectedBlockId, byte replacementBlockId);
```

`ChunkMutationOutcome` reports `APPLIED`, `NO_CHANGE`, `CONFLICT`, or
`OUT_OF_BOUNDS`, the observed byte block, and the exact immutable ordered
list of `DirtyChunkRevision` values advanced by that committed mutation.

- [x] **Step 1: Write failing authoritative-outcome tests**

Cover:

```java
@Test
void boundaryMutationReportsOnlyActuallyLoadedDirtyRevisions() {
    ChunkRepository repository = new ChunkRepository();
    ChunkKey center = new ChunkKey(0, 0);
    ChunkKey east = center.east();
    repository.generate(center, chunk -> {});
    long oldCenterRevision = repository.revision(center);

    ChunkMutationOutcome missingNeighborOutcome =
            repository.compareAndSetBlock(15, 4, 2, (byte) 0, (byte) 1);

    assertEquals(ChunkMutationOutcome.Status.APPLIED, missingNeighborOutcome.status());
    assertEquals(Set.of(center), missingNeighborOutcome.dirtyRevisions().keySet());
    assertTrue(
            missingNeighborOutcome.dirtyRevisions().get(center)
                    > oldCenterRevision);
    assertEquals(0, repository.revision(east));

    repository.generate(east, chunk -> {});
    ChunkMutationOutcome loadedNeighborOutcome =
            repository.compareAndSetBlock(15, 4, 2, (byte) 1, (byte) 2);
    assertEquals(
            Set.of(center, east),
            loadedNeighborOutcome.dirtyRevisions().keySet());
}

@Test
void staleExpectedBlockDoesNotAdvanceRevisionOrAcceptStaleMesh() {
    ChunkRepository repository = new ChunkRepository();
    ChunkKey center = new ChunkKey(0, 0);
    repository.generate(center, chunk -> {});
    long claimed =
            repository.claimMeshing(center).orElseThrow().center().revision();

    ChunkMutationOutcome conflict =
            repository.compareAndSetBlock(1, 4, 2, (byte) 9, (byte) 1);
    assertEquals(ChunkMutationOutcome.Status.CONFLICT, conflict.status());
    assertTrue(conflict.dirtyRevisions().isEmpty());
    assertTrue(repository.markReadyForUpload(center, claimed));

    repository.compareAndSetBlock(1, 4, 2, (byte) 0, (byte) 1);
    assertFalse(repository.isReadyForUpload(center, claimed));
}
```

- [x] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.voxel.ChunkRepositoryTest --tests com.overlord.voxel.WorldTest
```

Expected: compilation fails because `compareAndSetBlock` and outcome values do
not exist.

- [x] **Step 3: Implement atomic outcome collection**

Perform expected-value comparison and target mutation under the target entry
monitor. Record the target's newly issued repository revision, then call a
revised `dirtyIfPresent` that returns the neighbor revision it actually
advanced. Never create missing neighbors. Keep `setBlock` behavior by
delegating to the same internal mutation path without an expected-value
precondition.

- [x] **Step 4: Verify GREEN and stale lifecycle**

Run the focused command and:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.voxel.ChunkMeshManagerTest --tests com.overlord.voxel.ChunkMeshLifecycleStructureTest
```

Expected: repository, world, stale-result, and mesh lifecycle tests pass.

- [x] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/voxel engine/src/test/java/com/overlord/voxel
git commit -m "refactor(voxel): expose committed mutation outcome"
```

### Task 5: WorldMutationService outcome migration and reentrancy policy

**Files:**
- Create: `engine/src/main/java/com/overlord/interaction/BlockWorldMutationOutcome.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/BlockMutationReentrancyException.java`
- Modify: `engine/src/main/java/com/overlord/interaction/BlockWorldAccess.java`
- Modify: `engine/src/main/java/com/overlord/interaction/DefaultWorldMutationService.java`
- Modify: `engine/src/main/java/com/overlord/interaction/api/BlockChangeResult.java`
- Modify: `engine/src/main/java/com/overlord/interaction/api/ChunkDirtyEvent.java`
- Modify: `engine/src/testFixtures/java/com/overlord/interaction/testing/FakeBlockWorldAccess.java`
- Modify: `engine/src/test/java/com/overlord/interaction/DefaultWorldMutationServiceTest.java`
- Modify: `engine/src/test/java/com/overlord/interaction/api/BlockMutationContractTest.java`
- Modify: `engine/src/test/java/com/overlord/interaction/InteractionArchitectureTest.java`

**Interfaces:**
- Consumes: repository-owned dirty revisions from Task 4
- Removes: `ChunkDirtyTracker` constructor dependency from `DefaultWorldMutationService`
- Produces exact post-commit dirty revision observations

- [x] **Step 1: Write failing service migration tests**

Cover:

```java
@Test
void publishesOnlyDirtyRevisionsReturnedByCommittedWorldOutcome() {
    RecordingAccess access = new RecordingAccess(order, STONE);
    access.nextMutationOutcome =
            BlockWorldMutationOutcome.applied(
                    STONE,
                    Map.of(new ChunkKey(0, 0), 41L));
    BlockChangeResult result =
            service(access, events).changeBlock(request(15, 4, 3));

    assertEquals(Map.of(new ChunkKey(0, 0), 41L), result.dirtyRevisions());
    assertEquals(
            result.dirtyRevisions(),
            events.dirtyEvent.dirtyRevisions());
    assertFalse(result.dirtyChunks().contains(new ChunkKey(1, 0)));
}

@Test
void conflictOutcomePublishesNoPostCommitEvents() {
    RecordingAccess access = new RecordingAccess(order, STONE);
    access.nextMutationOutcome =
            BlockWorldMutationOutcome.conflict(DIRT);

    BlockChangeResult result =
            service(access, events).changeBlock(request(2, 4, 3));

    assertEquals(BlockChangeResult.Status.CONFLICT, result.status());
    assertTrue(result.dirtyRevisions().isEmpty());
    assertNull(events.changedEvent);
    assertNull(events.dirtyEvent);
}

@Test
void beforeSubscriberCannotReenterWorldMutationService() {
    RecordingAccess access = new RecordingAccess(order, STONE);
    RecordingPublisher events = new RecordingPublisher(order);
    DefaultWorldMutationService service = service(access, events);
    events.beforeAction = () -> service.changeBlock(request(3, 4, 3));

    BlockChangeDispatchException failure =
            assertThrows(
                    BlockChangeDispatchException.class,
                    () -> service.changeBlock(request(2, 4, 3)));

    assertFalse(failure.mutationApplied());
    assertInstanceOf(
            BlockMutationReentrancyException.class,
            failure.getCause());
}
```

Add architecture assertions that `DefaultWorldMutationService` neither imports
nor constructs `ChunkDirtyTracker`, and that `ChunkDirtyEvent` is published
only after an `APPLIED` outcome.

- [x] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.DefaultWorldMutationServiceTest --tests com.overlord.interaction.api.BlockMutationContractTest --tests com.overlord.interaction.InteractionArchitectureTest
```

Expected: compilation or behavioral failures because the service still
recalculates dirty chunks and allows Before-event reentrancy.

- [x] **Step 3: Implement minimal migration**

Replace `BlockWorldAccess.setBlock` with a compare-and-set outcome boundary.
Use the outcome's actual dirty revisions in both `BlockChangeResult` and
`ChunkDirtyEvent`; retain `dirtyChunks()` convenience views derived from those
revisions. Guard only the synchronous Before dispatch against nested
`changeBlock` calls, reset the guard in `finally`, and retain the existing
post-Before target revalidation. Preserve post-write failure aggregation and
`mutationApplied=true`.

- [x] **Step 4: Verify GREEN**

Run the focused command and:

```powershell
.\gradlew.bat :engine:test
```

Expected: all engine tests pass.

- [x] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/interaction engine/src/test/java/com/overlord/interaction engine/src/testFixtures/java/com/overlord/interaction
git commit -m "fix(api): publish authoritative chunk mutation outcomes"
```

### Task 6: Read-only InteractionViewModel

**Files:**
- Create: `engine/src/main/java/com/overlord/interaction/api/BlockFace.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/InteractionMode.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/InteractionFailureReason.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/InteractionViewModel.java`
- Create: `engine/src/testFixtures/java/com/overlord/interaction/testing/StubInteractionViewModel.java`
- Modify: `engine/src/test/java/com/overlord/interaction/api/InteractionContractTest.java`
- Modify: `engine/src/test/java/com/overlord/interaction/InteractionArchitectureTest.java`
- Modify: `game/src/test/java/com/gaia/contracts/EngineContractFixtureSmokeTest.java`

**Interfaces:**
- Produces:

```java
Optional<BlockHitResult> target();
Optional<BlockFace> hitFace();
double progress();
InteractionMode mode();
Optional<ItemStackView> activeItem();
Optional<InteractionFailureReason> failureReason();
```

- [x] **Step 1: Write failing view-model tests**

Test the exact method set, six `BlockFace` values and normal conversion,
`NONE/BREAKING/PLACING/USING` modes, finite inclusive `[0, 1]` progress,
target/face presence consistency in the fixture, and the absence of mutable
service return types.

- [x] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.api.InteractionContractTest --tests com.overlord.interaction.InteractionArchitectureTest
```

Expected: compilation fails because the view-model contracts do not exist.

- [x] **Step 3: Implement minimal read-only contracts and fixture**

`InteractionFailureReason` wraps a non-null `ResourceLocation` code and no
display text. `StubInteractionViewModel` defensively validates progress and
target/face presence but performs no raycast, gameplay, inventory mutation,
or UI work.

- [x] **Step 4: Verify GREEN**

Run the focused command and the game fixture smoke test.

- [x] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/interaction/api engine/src/test/java/com/overlord/interaction engine/src/testFixtures/java/com/overlord/interaction game/src/test
git commit -m "feat(api): add read-only interaction view model"
```

### Task 7: Documentation, branch-wide review, and final verification

**Files:**
- Modify: `docs/architecture/interaction-inventory-contract.md`
- Modify: `docs/architecture/current-baseline.md`
- Modify: `docs/agent-handoffs/phase-07-handoff.md`
- Modify: `docs/superpowers/plans/2026-07-25-phase-7-prompt-suite-v2-1-alignment.md`

**Interfaces:**
- Documents every public contract from Tasks 1-6
- Records Before reentrancy prohibition, post-write no-rollback/no-retry
  semantics, and repository-owned dirty/revision outcomes

- [x] **Step 1: Add documentation architecture tests or source assertions first**

Extend architecture tests to require documentation terms for canonical
`ItemStack`, both reservation services, world-item source-of-truth use,
repository-owned dirty revisions, Before-event reentrancy prohibition,
post-write no rollback/no automatic retry, and `mutationApplied=true`.

- [x] **Step 2: Verify RED**

Run the affected architecture tests and confirm failure on missing v2.1
documentation.

- [x] **Step 3: Update architecture and handoff documents**

Record completed and unfinished work, architecture decisions, modified files,
test evidence, known risks, and next-phase protected interfaces. Explicitly
state that no Phase 8 gameplay, production inventory, world entity, physics
drop, renderer, controller, mesh-manager, or UI implementation was added.

- [ ] **Step 4: Run branch-wide owner review (pending controller dispatch)**

Review all `origin/main..HEAD` engine, game, shared build, fixture, and
documentation changes for Critical, Important, and Minor findings. Resolve
all Critical and Important findings with focused failing tests before fixes.

- [x] **Step 5: Run final verification**

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
git diff --check
git diff --check origin/main..HEAD
git status --short --branch
git diff --stat origin/main..HEAD
```

Count JUnit XML tests, failures, errors, and skipped tests. Verify no tracked
generated files and no prohibited production dependencies or direct gameplay
world writes.

- [x] **Step 6: Commit documentation**

```powershell
git add docs engine/src/test game/src/test
git commit -m "docs: align phase 7 contracts with prompt suite v2.1"
```

- [x] **Step 7: Report without publishing**

Report final HEAD, exact test count, build and packaged-resource results,
`git diff --stat`, owner-review verdict, known risks, and unfinished items.
Do not push, create a pull request, or merge.
