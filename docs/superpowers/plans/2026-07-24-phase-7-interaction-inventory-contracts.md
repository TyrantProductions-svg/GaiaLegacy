# Phase 7 Interaction and Inventory Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add stable, tested block-interaction and three-slot inventory API
contracts without implementing gameplay, inventory rules, or UI rendering.

**Architecture:** Pure contracts live in `engine` under separate interaction
and inventory API packages. A constructor-injected
`DefaultWorldMutationService` owns the synchronous
before/revalidate/write/changed/dirty transaction while a narrow
`BlockWorldAccess` SPI keeps Gaia block-ID translation out of the engine.
Reusable fakes live in Gradle test fixtures so engine and game developers can
compile against identical contracts.

**Tech Stack:** Java 17, Gradle 8.5 Wrapper, JUnit Jupiter 6.1.1, JOML 1.10.5.

**Final review amendments:** The implementation revalidates the expected
block immediately after synchronous before-change dispatch; inventory
snapshots and change-result views use `Optional<InventoryView>` with
status-dependent presence invariants; `BlockHitResult` accepts only the six
exact axis-face normal patterns; and the direct-write guard allows only
`WorldLoader.java` and `GaiaWorldGenerator.java`. These final shapes govern
where an earlier task transcript below shows the pre-review form.

## Global Constraints

- Keep Java 17 source and target compatibility; builds may run on JDK 21.
- Use the checked-in Gradle Wrapper and do not write a platform-specific JDK
  path.
- Do not modify `World`, `Renderer`, `GaiaMain`, `GameBootstrap`, or
  `GameContext` in this phase.
- Do not implement breaking, placement, stacking, pickup, dropping,
  persistence, or UI rendering.
- Do not expand `ServiceLocator`; use constructor injection.
- All world and inventory mutation must run on the main thread during fixed
  update.
- All OpenGL and GPU work remains on the OpenGL context thread; these
  contracts perform no OpenGL work.
- Keep OpenGL compatibility at macOS OpenGL 4.1 and GLSL 410.
- Do not copy third-party code or assets.
- Do not commit generated output, `.class` files, `bin/`, local caches, or
  platform absolute paths.
- Keep Phase 7 changes on `feat/interaction-api-contracts`; do not modify,
  merge, or push `main`, and do not force-push.
- Before handoff run `git diff --check` and
  `.\gradlew.bat clean test build`; record `.\gradlew.bat :game` only if the
  interactive smoke test is actually run.

---

### Task 1: Add immutable entity and body-inventory contracts

**Files:**

- Modify: `engine/build.gradle`
- Create: `engine/src/main/java/com/overlord/interaction/api/EntityRef.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/BodySlot.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/ItemStackView.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryView.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryChangeRequest.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryChangeResult.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/InventoryService.java`
- Create: `engine/src/main/java/com/overlord/inventory/api/BodyInventoryViewModel.java`
- Test: `engine/src/test/java/com/overlord/inventory/api/InventoryContractTest.java`

**Interfaces:**

- Produces: `EntityRef(int id)`.
- Produces: `BodySlot.LEFT_HAND`, `RIGHT_HAND`, and `MOUTH`.
- Produces: read-only `ItemStackView`, `InventoryView`, and
  `BodyInventoryViewModel`.
- Produces: `InventoryService.snapshot(EntityRef)` returning
  `Optional<InventoryView>` and
  `replaceSlot(InventoryChangeRequest)`.

- [ ] **Step 1: Enable engine test fixtures and write the failing inventory contract test**

Add the plugin without changing existing Java or dependency settings:

```groovy
plugins {
    id 'java-library'
    id 'java-test-fixtures'
}
```

Create this test:

```java
package com.overlord.inventory.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InventoryContractTest {
    private static final ItemStackView STONE =
            new ItemStackView() {
                @Override
                public ResourceLocation itemId() {
                    return ResourceLocation.parse("gaia:stone");
                }

                @Override
                public int count() {
                    return 2;
                }
            };

    @Test
    void bodySlotsHaveStableThreeSlotOrder() {
        assertArrayEquals(
                new BodySlot[] {
                    BodySlot.LEFT_HAND,
                    BodySlot.RIGHT_HAND,
                    BodySlot.MOUTH
                },
                BodySlot.values());
    }

    @Test
    void entityReferencesRejectNegativeIds() {
        assertEquals(7, new EntityRef(7).id());
        assertThrows(IllegalArgumentException.class, () -> new EntityRef(-1));
    }

    @Test
    void inventoryChangeRequiresValidRevisionAndReferences() {
        EntityRef owner = new EntityRef(4);
        assertEquals(
                Optional.of(STONE),
                new InventoryChangeRequest(
                                owner,
                                BodySlot.RIGHT_HAND,
                                3,
                                Optional.of(STONE))
                        .replacement());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InventoryChangeRequest(
                                owner,
                                BodySlot.RIGHT_HAND,
                                -1,
                                Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () ->
                        new InventoryChangeRequest(
                                owner,
                                BodySlot.RIGHT_HAND,
                                0,
                                null));
    }

    @Test
    void uiViewModelDoesNotExposeInventoryService() {
        for (Method method : BodyInventoryViewModel.class.getMethods()) {
            assertFalse(
                    InventoryService.class.isAssignableFrom(
                            method.getReturnType()),
                    method.toString());
        }
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails because the contracts do not exist**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.inventory.api.InventoryContractTest
```

Expected: `compileTestJava` fails with missing
`com.overlord.inventory.api` and `EntityRef` symbols.

- [ ] **Step 3: Add the minimal immutable contracts**

Create the production types:

```java
// engine/src/main/java/com/overlord/interaction/api/EntityRef.java
package com.overlord.interaction.api;

public record EntityRef(int id) {
    public EntityRef {
        if (id < 0) {
            throw new IllegalArgumentException(
                    "entity id must be non-negative");
        }
    }
}
```

```java
// engine/src/main/java/com/overlord/inventory/api/BodySlot.java
package com.overlord.inventory.api;

public enum BodySlot {
    LEFT_HAND,
    RIGHT_HAND,
    MOUTH
}
```

```java
// engine/src/main/java/com/overlord/inventory/api/ItemStackView.java
package com.overlord.inventory.api;

import com.overlord.assets.ResourceLocation;

public interface ItemStackView {
    ResourceLocation itemId();

    /** Returns a positive count for a valid stack. */
    int count();
}
```

```java
// engine/src/main/java/com/overlord/inventory/api/InventoryView.java
package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Optional;

public interface InventoryView {
    EntityRef owner();

    long revision();

    Optional<ItemStackView> stack(BodySlot slot);
}
```

```java
// engine/src/main/java/com/overlord/inventory/api/InventoryChangeRequest.java
package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Objects;
import java.util.Optional;

public record InventoryChangeRequest(
        EntityRef owner,
        BodySlot slot,
        long expectedRevision,
        Optional<ItemStackView> replacement) {
    public InventoryChangeRequest {
        owner = Objects.requireNonNull(owner, "owner");
        slot = Objects.requireNonNull(slot, "slot");
        replacement =
                Objects.requireNonNull(replacement, "replacement");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedRevision must be non-negative");
        }
    }
}
```

```java
// engine/src/main/java/com/overlord/inventory/api/InventoryChangeResult.java
package com.overlord.inventory.api;

import java.util.Objects;
import java.util.Optional;

public record InventoryChangeResult(
        Status status, Optional<InventoryView> inventory) {
    public InventoryChangeResult {
        status = Objects.requireNonNull(status, "status");
        inventory = Objects.requireNonNull(inventory, "inventory");
        if (status == Status.UNKNOWN_OWNER) {
            if (inventory.isPresent()) {
                throw new IllegalArgumentException(
                        "UNKNOWN_OWNER must not include an inventory");
            }
        } else {
            if (inventory.isEmpty()) {
                throw new IllegalArgumentException(
                        status + " requires an inventory");
            }
            InventoryView view = inventory.orElseThrow();
            if (view.revision() < 0) {
                throw new IllegalArgumentException(
                        "inventory revision must be non-negative");
            }
        }
    }

    public enum Status {
        APPLIED,
        CONFLICT,
        INVALID_STACK,
        UNKNOWN_OWNER
    }
}
```

```java
// engine/src/main/java/com/overlord/inventory/api/InventoryService.java
package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Optional;

public interface InventoryService {
    Optional<InventoryView> snapshot(EntityRef owner);

    InventoryChangeResult replaceSlot(
            InventoryChangeRequest request);
}
```

```java
// engine/src/main/java/com/overlord/inventory/api/BodyInventoryViewModel.java
package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;

public interface BodyInventoryViewModel {
    EntityRef owner();

    BodySlot activeSlot();

    InventoryView inventory();
}
```

- [ ] **Step 4: Run the focused inventory test**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.inventory.api.InventoryContractTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the inventory contract slice**

```powershell
git add engine/build.gradle engine/src/main/java/com/overlord/interaction/api/EntityRef.java engine/src/main/java/com/overlord/inventory/api engine/src/test/java/com/overlord/inventory/api/InventoryContractTest.java
git commit -m "feat(api): add body inventory contracts"
```

---

### Task 2: Add interaction context and raycast contracts

**Files:**

- Create: `engine/src/main/java/com/overlord/interaction/api/InteractionAction.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/InteractionContext.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/BlockHitResult.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/BlockRaycastService.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/ItemUseContext.java`
- Test: `engine/src/test/java/com/overlord/interaction/api/InteractionContractTest.java`

**Interfaces:**

- Consumes: `EntityRef`, `BodySlot`, and `ItemStackView` from Task 1.
- Produces: `InteractionContext` with actor, active body slot, action, fixed
  tick, and monotonic timestamp.
- Produces: read-only `BlockRaycastService.raycast(...)`.
- Produces: immutable `BlockHitResult` and `ItemUseContext`.

- [ ] **Step 1: Write the failing validation and shape tests**

Create:

```java
package com.overlord.interaction.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.BodySlot;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InteractionContractTest {
    @Test
    void blockHitRequiresMatchingAdjacentCellAndAxisNormal() {
        BlockHitResult hit =
                new BlockHitResult(
                        4,
                        5,
                        6,
                        5,
                        5,
                        6,
                        ResourceLocation.parse("gaia:stone"),
                        1,
                        0,
                        0,
                        5.0f,
                        5.5f,
                        6.5f,
                        3.0f);

        assertEquals(5, hit.adjacentX());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockHitResult(
                                4,
                                5,
                                6,
                                4,
                                5,
                                6,
                                ResourceLocation.parse("gaia:stone"),
                                1,
                                0,
                                0,
                                5.0f,
                                5.5f,
                                6.5f,
                                3.0f));
    }

    @Test
    void itemUseContextCarriesEmptyHandAndMissWithoutNulls() {
        ItemUseContext context =
                new ItemUseContext(
                        new EntityRef(9),
                        BodySlot.MOUTH,
                        Optional.empty(),
                        Optional.empty(),
                        InteractionAction.USE,
                        12,
                        900);

        assertEquals(12, context.tick());
        assertEquals(Optional.empty(), context.heldStack());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ItemUseContext(
                                new EntityRef(9),
                                BodySlot.MOUTH,
                                Optional.empty(),
                                Optional.empty(),
                                InteractionAction.USE,
                                -1,
                                900));
    }
}
```

- [ ] **Step 2: Run the focused test and verify missing symbols**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.api.InteractionContractTest
```

Expected: `compileTestJava` fails because the interaction types are absent.

- [ ] **Step 3: Implement the interaction and raycast values**

Create:

```java
// InteractionAction.java
package com.overlord.interaction.api;

public enum InteractionAction {
    PRIMARY,
    SECONDARY,
    USE
}
```

```java
// InteractionContext.java
package com.overlord.interaction.api;

import com.overlord.inventory.api.BodySlot;

public interface InteractionContext {
    EntityRef actor();

    BodySlot activeBodySlot();

    InteractionAction action();

    long tick();

    long timestampNanos();
}
```

```java
// BlockHitResult.java
package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record BlockHitResult(
        int blockX,
        int blockY,
        int blockZ,
        int adjacentX,
        int adjacentY,
        int adjacentZ,
        ResourceLocation block,
        int normalX,
        int normalY,
        int normalZ,
        float pointX,
        float pointY,
        float pointZ,
        float distance) {
    public BlockHitResult {
        block = Objects.requireNonNull(block, "block");
        boolean xFace =
                (normalX == 1 || normalX == -1)
                        && normalY == 0
                        && normalZ == 0;
        boolean yFace =
                normalX == 0
                        && (normalY == 1 || normalY == -1)
                        && normalZ == 0;
        boolean zFace =
                normalX == 0
                        && normalY == 0
                        && (normalZ == 1 || normalZ == -1);
        if (!(xFace || yFace || zFace)) {
            throw new IllegalArgumentException(
                    "normal must identify one axis-aligned face");
        }
        if ((long) adjacentX != (long) blockX + normalX
                || (long) adjacentY != (long) blockY + normalY
                || (long) adjacentZ != (long) blockZ + normalZ) {
            throw new IllegalArgumentException(
                    "adjacent coordinates must follow the hit normal");
        }
        if (!Float.isFinite(pointX)
                || !Float.isFinite(pointY)
                || !Float.isFinite(pointZ)
                || !Float.isFinite(distance)
                || distance < 0) {
            throw new IllegalArgumentException(
                    "hit point and distance must be finite");
        }
    }
}
```

```java
// BlockRaycastService.java
package com.overlord.interaction.api;

import java.util.Optional;
import org.joml.Vector3fc;

public interface BlockRaycastService {
    Optional<BlockHitResult> raycast(
            Vector3fc origin,
            Vector3fc direction,
            float maxDistance);
}
```

```java
// ItemUseContext.java
package com.overlord.interaction.api;

import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStackView;
import java.util.Objects;
import java.util.Optional;

public record ItemUseContext(
        EntityRef actor,
        BodySlot activeBodySlot,
        Optional<ItemStackView> heldStack,
        Optional<BlockHitResult> raycastResult,
        InteractionAction action,
        long tick,
        long timestampNanos)
        implements InteractionContext {
    public ItemUseContext {
        actor = Objects.requireNonNull(actor, "actor");
        activeBodySlot =
                Objects.requireNonNull(activeBodySlot, "activeBodySlot");
        heldStack = Objects.requireNonNull(heldStack, "heldStack");
        raycastResult =
                Objects.requireNonNull(raycastResult, "raycastResult");
        action = Objects.requireNonNull(action, "action");
        if (tick < 0 || timestampNanos < 0) {
            throw new IllegalArgumentException(
                    "tick and timestampNanos must be non-negative");
        }
        heldStack.ifPresent(
                stack -> {
                    Objects.requireNonNull(
                            stack.itemId(), "held stack itemId");
                    if (stack.count() <= 0) {
                        throw new IllegalArgumentException(
                                "held stack count must be positive");
                    }
                });
    }
}
```

- [ ] **Step 4: Run the interaction API test**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.api.InteractionContractTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the interaction read-contract slice**

```powershell
git add engine/src/main/java/com/overlord/interaction/api engine/src/test/java/com/overlord/interaction/api/InteractionContractTest.java
git commit -m "feat(api): add interaction and raycast contracts"
```

---

### Task 3: Add block mutation request, result, and event contracts

**Files:**

- Create: `engine/src/main/java/com/overlord/interaction/api/BlockChangeRequest.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/BlockChangeResult.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/BeforeBlockChangedEvent.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/BlockChangedEvent.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/ChunkDirtyEvent.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/BlockChangeDecision.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/BlockChangeEventPublisher.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/WorldMutationService.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/BlockChangeDispatchException.java`
- Test: `engine/src/test/java/com/overlord/interaction/api/BlockMutationContractTest.java`

**Interfaces:**

- Consumes: `InteractionContext`, `ResourceLocation`, and `ChunkKey`.
- Produces: the synchronous `WorldMutationService.changeBlock` boundary.
- Produces: immutable event payloads and explicit `ALLOW`/`CANCEL`.
- Produces: result statuses and a dispatch exception that states whether the
  world write happened.

- [ ] **Step 1: Write failing tests for immutable results and events**

Create:

```java
package com.overlord.interaction.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkKey;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlockMutationContractTest {
    private final InteractionContext context =
            new ItemUseContext(
                    new EntityRef(1),
                    BodySlot.RIGHT_HAND,
                    Optional.empty(),
                    Optional.empty(),
                    InteractionAction.PRIMARY,
                    5,
                    50);
    private final BlockChangeRequest request =
            new BlockChangeRequest(
                    context,
                    15,
                    4,
                    3,
                    ResourceLocation.parse("gaia:stone"),
                    ResourceLocation.parse("gaia:air"));

    @Test
    void resultCopiesDirtyChunkSet() {
        Set<ChunkKey> mutable =
                new java.util.HashSet<>(Set.of(new ChunkKey(0, 0)));
        BlockChangeResult result =
                new BlockChangeResult(
                        request,
                        BlockChangeResult.Status.APPLIED,
                        Optional.of(ResourceLocation.parse("gaia:stone")),
                        mutable);
        mutable.add(new ChunkKey(1, 0));

        assertEquals(Set.of(new ChunkKey(0, 0)), result.dirtyChunks());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.dirtyChunks().add(new ChunkKey(2, 0)));
    }

    @Test
    void dirtyEventRejectsEmptyAffectedSet() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkDirtyEvent(request, Set.of()));
    }

    @Test
    void dispatchExceptionReportsCommitState() {
        BlockChangeDispatchException failure =
                new BlockChangeDispatchException(
                        "post-change delivery failed",
                        new IllegalStateException("listener"),
                        true);
        assertEquals(true, failure.mutationApplied());
    }
}
```

- [ ] **Step 2: Run the focused test and verify missing mutation contracts**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.api.BlockMutationContractTest
```

Expected: `compileTestJava` fails on the missing request, result, event, and
exception symbols.

- [ ] **Step 3: Implement the mutation API values**

Create the exact public shapes:

```java
// BlockChangeRequest.java
package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record BlockChangeRequest(
        InteractionContext context,
        int x,
        int y,
        int z,
        ResourceLocation expectedBlock,
        ResourceLocation replacementBlock) {
    public BlockChangeRequest {
        context = Objects.requireNonNull(context, "context");
        expectedBlock =
                Objects.requireNonNull(expectedBlock, "expectedBlock");
        replacementBlock =
                Objects.requireNonNull(
                        replacementBlock, "replacementBlock");
    }
}
```

```java
// BlockChangeResult.java
package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record BlockChangeResult(
        BlockChangeRequest request,
        Status status,
        Optional<ResourceLocation> observedBlock,
        Set<ChunkKey> dirtyChunks) {
    public BlockChangeResult {
        request = Objects.requireNonNull(request, "request");
        status = Objects.requireNonNull(status, "status");
        observedBlock =
                Objects.requireNonNull(observedBlock, "observedBlock");
        dirtyChunks = Set.copyOf(dirtyChunks);
        if (status == Status.APPLIED && dirtyChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "applied result requires dirty chunks");
        }
        if (status != Status.APPLIED && !dirtyChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected result cannot contain dirty chunks");
        }
    }

    public enum Status {
        APPLIED,
        NO_CHANGE,
        CANCELLED,
        CONFLICT,
        OUT_OF_BOUNDS,
        UNKNOWN_BLOCK
    }
}
```

```java
// BeforeBlockChangedEvent.java
package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record BeforeBlockChangedEvent(
        BlockChangeRequest request, ResourceLocation currentBlock) {
    public BeforeBlockChangedEvent {
        request = Objects.requireNonNull(request, "request");
        currentBlock =
                Objects.requireNonNull(currentBlock, "currentBlock");
    }
}
```

```java
// BlockChangedEvent.java
package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record BlockChangedEvent(
        BlockChangeRequest request,
        ResourceLocation previousBlock,
        ResourceLocation currentBlock) {
    public BlockChangedEvent {
        request = Objects.requireNonNull(request, "request");
        previousBlock =
                Objects.requireNonNull(previousBlock, "previousBlock");
        currentBlock =
                Objects.requireNonNull(currentBlock, "currentBlock");
    }
}
```

```java
// ChunkDirtyEvent.java
package com.overlord.interaction.api;

import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Set;

public record ChunkDirtyEvent(
        BlockChangeRequest request, Set<ChunkKey> chunks) {
    public ChunkDirtyEvent {
        request = Objects.requireNonNull(request, "request");
        chunks = Set.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "dirty event requires at least one chunk");
        }
    }
}
```

```java
// BlockChangeDecision.java
package com.overlord.interaction.api;

public enum BlockChangeDecision {
    ALLOW,
    CANCEL
}
```

```java
// BlockChangeEventPublisher.java
package com.overlord.interaction.api;

public interface BlockChangeEventPublisher {
    BlockChangeDecision beforeChange(
            BeforeBlockChangedEvent event);

    void blockChanged(BlockChangedEvent event);

    void chunksDirty(ChunkDirtyEvent event);
}
```

```java
// WorldMutationService.java
package com.overlord.interaction.api;

public interface WorldMutationService {
    BlockChangeResult changeBlock(BlockChangeRequest request);
}
```

```java
// BlockChangeDispatchException.java
package com.overlord.interaction.api;

public final class BlockChangeDispatchException
        extends RuntimeException {
    private final boolean mutationApplied;

    public BlockChangeDispatchException(
            String message,
            Throwable cause,
            boolean mutationApplied) {
        super(message, cause);
        this.mutationApplied = mutationApplied;
    }

    public boolean mutationApplied() {
        return mutationApplied;
    }
}
```

- [ ] **Step 4: Run the mutation contract test**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.api.BlockMutationContractTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the transaction event contracts**

```powershell
git add engine/src/main/java/com/overlord/interaction/api engine/src/test/java/com/overlord/interaction/api/BlockMutationContractTest.java
git commit -m "feat(api): add block mutation event contracts"
```

---

### Task 4: Implement the standard synchronous mutation coordinator

**Files:**

- Create: `engine/src/main/java/com/overlord/interaction/BlockWorldAccess.java`
- Create: `engine/src/main/java/com/overlord/interaction/DefaultWorldMutationService.java`
- Test: `engine/src/test/java/com/overlord/interaction/DefaultWorldMutationServiceTest.java`

**Interfaces:**

- Consumes: mutation contracts from Task 3, `MainThreadGuard`,
  `ChunkDirtyTracker`, and `ChunkKey`.
- Produces: constructor-injected `DefaultWorldMutationService`.
- Produces: narrow Gaia-facing `BlockWorldAccess`.

- [ ] **Step 1: Write the failing happy-path ordering and edge-dirty tests**

Create a test class with an inner recording access fake:

```java
package com.overlord.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangeRequest;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.BeforeBlockChangedEvent;
import com.overlord.interaction.api.ChunkDirtyEvent;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.ItemUseContext;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultWorldMutationServiceTest {
    private static final ResourceLocation AIR =
            ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE =
            ResourceLocation.parse("gaia:stone");

    @Test
    void appliesInBeforeWriteChangedDirtyOrderAtChunkEdge() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        DefaultWorldMutationService service =
                new DefaultWorldMutationService(
                        MainThreadGuard.captureCurrentThread(),
                        access,
                        events,
                        new ChunkDirtyTracker());

        BlockChangeResult result =
                service.changeBlock(request(15, 4, 3));

        assertEquals(BlockChangeResult.Status.APPLIED, result.status());
        assertEquals(
                Set.of(new ChunkKey(0, 0), new ChunkKey(1, 0)),
                result.dirtyChunks());
        assertEquals(
                List.of("before", "write", "changed", "dirty"),
                order);
    }

    private static BlockChangeRequest request(int x, int y, int z) {
        return request(x, y, z, STONE, AIR);
    }

    private static BlockChangeRequest request(
            int x,
            int y,
            int z,
            ResourceLocation expected,
            ResourceLocation replacement) {
        return new BlockChangeRequest(
                new ItemUseContext(
                        new EntityRef(1),
                        BodySlot.RIGHT_HAND,
                        Optional.empty(),
                        Optional.empty(),
                        InteractionAction.PRIMARY,
                        2,
                        20),
                x,
                y,
                z,
                expected,
                replacement);
    }

    private static DefaultWorldMutationService service(
            RecordingAccess access,
            RecordingPublisher events) {
        return new DefaultWorldMutationService(
                MainThreadGuard.captureCurrentThread(),
                access,
                events,
                new ChunkDirtyTracker());
    }

    private static final class RecordingAccess
            implements BlockWorldAccess {
        private final List<String> order;
        private final Set<ResourceLocation> known =
                new HashSet<>(Set.of(AIR, STONE));
        private ResourceLocation block;
        private int writes;
        private boolean withinBounds = true;
        private boolean writeSucceeds = true;

        private RecordingAccess(
                List<String> order, ResourceLocation block) {
            this.order = order;
            this.block = block;
        }

        @Override
        public boolean isWithinBounds(int x, int y, int z) {
            return withinBounds && y >= 0 && y < 256;
        }

        @Override
        public boolean isKnownBlock(ResourceLocation candidate) {
            return known.contains(candidate);
        }

        @Override
        public ResourceLocation blockAt(int x, int y, int z) {
            return block;
        }

        @Override
        public boolean setBlock(
                int x,
                int y,
                int z,
                ResourceLocation replacement) {
            order.add("write");
            writes++;
            if (!writeSucceeds) {
                return false;
            }
            block = replacement;
            return true;
        }
    }

    private static class RecordingPublisher
            implements BlockChangeEventPublisher {
        private final List<String> order;
        private BlockChangeDecision decision =
                BlockChangeDecision.ALLOW;
        private RuntimeException changedFailure;
        private RuntimeException dirtyFailure;

        private RecordingPublisher(List<String> order) {
            this.order = order;
        }

        @Override
        public BlockChangeDecision beforeChange(
                BeforeBlockChangedEvent event) {
            order.add("before");
            return decision;
        }

        @Override
        public void blockChanged(BlockChangedEvent event) {
            order.add("changed");
            if (changedFailure != null) {
                throw changedFailure;
            }
        }

        @Override
        public void chunksDirty(ChunkDirtyEvent event) {
            order.add("dirty");
            if (dirtyFailure != null) {
                throw dirtyFailure;
            }
        }
    }
}
```

- [ ] **Step 2: Run the coordinator test and verify the implementation is missing**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.DefaultWorldMutationServiceTest
```

Expected: `compileTestJava` fails because `BlockWorldAccess` and
`DefaultWorldMutationService` do not exist.

- [ ] **Step 3: Implement the narrow world SPI and coordinator**

Create:

```java
// BlockWorldAccess.java
package com.overlord.interaction;

import com.overlord.assets.ResourceLocation;

public interface BlockWorldAccess {
    boolean isWithinBounds(int x, int y, int z);

    boolean isKnownBlock(ResourceLocation block);

    ResourceLocation blockAt(int x, int y, int z);

    boolean setBlock(
            int x, int y, int z, ResourceLocation block);
}
```

```java
// DefaultWorldMutationService.java
package com.overlord.interaction;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangeRequest;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.BeforeBlockChangedEvent;
import com.overlord.interaction.api.ChunkDirtyEvent;
import com.overlord.interaction.api.WorldMutationService;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DefaultWorldMutationService
        implements WorldMutationService {
    private final MainThreadGuard mainThreadGuard;
    private final BlockWorldAccess world;
    private final BlockChangeEventPublisher events;
    private final ChunkDirtyTracker dirtyTracker;

    public DefaultWorldMutationService(
            MainThreadGuard mainThreadGuard,
            BlockWorldAccess world,
            BlockChangeEventPublisher events,
            ChunkDirtyTracker dirtyTracker) {
        this.mainThreadGuard =
                Objects.requireNonNull(
                        mainThreadGuard, "mainThreadGuard");
        this.world = Objects.requireNonNull(world, "world");
        this.events = Objects.requireNonNull(events, "events");
        this.dirtyTracker =
                Objects.requireNonNull(
                        dirtyTracker, "dirtyTracker");
    }

    @Override
    public BlockChangeResult changeBlock(
            BlockChangeRequest request) {
        mainThreadGuard.assertMainThread("world mutation");
        Objects.requireNonNull(request, "request");

        if (!world.isWithinBounds(
                request.x(), request.y(), request.z())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.OUT_OF_BOUNDS,
                    Optional.empty());
        }
        if (!world.isKnownBlock(request.expectedBlock())
                || !world.isKnownBlock(
                        request.replacementBlock())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.UNKNOWN_BLOCK,
                    Optional.empty());
        }

        ResourceLocation current =
                Objects.requireNonNull(
                        world.blockAt(
                                request.x(),
                                request.y(),
                                request.z()),
                        "world block");
        if (!current.equals(request.expectedBlock())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.CONFLICT,
                    Optional.of(current));
        }
        if (current.equals(request.replacementBlock())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.NO_CHANGE,
                    Optional.of(current));
        }

        BlockChangeDecision decision;
        try {
            decision =
                    Objects.requireNonNull(
                            events.beforeChange(
                                    new BeforeBlockChangedEvent(
                                            request, current)),
                            "before-change decision");
        } catch (RuntimeException failure) {
            throw new BlockChangeDispatchException(
                    "before-change event delivery failed",
                    failure,
                    false);
        }
        if (decision == BlockChangeDecision.CANCEL) {
            return rejected(
                    request,
                    BlockChangeResult.Status.CANCELLED,
                    Optional.of(current));
        }

        ResourceLocation revalidatedCurrent =
                Objects.requireNonNull(
                        world.blockAt(
                                request.x(),
                                request.y(),
                                request.z()),
                        "world block");
        if (!revalidatedCurrent.equals(
                request.expectedBlock())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.CONFLICT,
                    Optional.of(revalidatedCurrent));
        }

        if (!world.setBlock(
                request.x(),
                request.y(),
                request.z(),
                request.replacementBlock())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.CONFLICT,
                    Optional.of(current));
        }

        ChunkKey key =
                ChunkKey.fromWorld(request.x(), request.z());
        Set<ChunkKey> dirtyChunks =
                dirtyTracker.affectedByBlock(
                        key,
                        ChunkKey.localCoordinate(request.x()),
                        ChunkKey.localCoordinate(request.z()));
        RuntimeException deliveryFailure = null;
        try {
            events.blockChanged(
                    new BlockChangedEvent(
                            request,
                            current,
                            request.replacementBlock()));
        } catch (RuntimeException failure) {
            deliveryFailure = failure;
        }
        try {
            events.chunksDirty(
                    new ChunkDirtyEvent(request, dirtyChunks));
        } catch (RuntimeException failure) {
            if (deliveryFailure == null) {
                deliveryFailure = failure;
            } else {
                deliveryFailure.addSuppressed(failure);
            }
        }
        if (deliveryFailure != null) {
            throw new BlockChangeDispatchException(
                    "post-change event delivery failed",
                    deliveryFailure,
                    true);
        }
        return new BlockChangeResult(
                request,
                BlockChangeResult.Status.APPLIED,
                Optional.of(current),
                dirtyChunks);
    }

    private static BlockChangeResult rejected(
            BlockChangeRequest request,
            BlockChangeResult.Status status,
            Optional<ResourceLocation> observed) {
        return new BlockChangeResult(
                request, status, observed, Set.of());
    }
}
```

- [ ] **Step 4: Run the happy-path test**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.DefaultWorldMutationServiceTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Add rejection, exception, and worker-thread tests**

Extend `DefaultWorldMutationServiceTest` with tests that configure the inner
fakes and assert:

```java
@Test
void cancellationDoesNotWriteOrPublishSuccessEvents() {
    List<String> order = new ArrayList<>();
    RecordingAccess access = new RecordingAccess(order, STONE);
    RecordingPublisher events = new RecordingPublisher(order);
    events.decision = BlockChangeDecision.CANCEL;
    DefaultWorldMutationService service =
            service(access, events);

    BlockChangeResult result =
            service.changeBlock(request(2, 4, 3));

    assertEquals(BlockChangeResult.Status.CANCELLED, result.status());
    assertEquals(0, access.writes);
    assertEquals(List.of("before"), order);
}

@Test
void conflictNoChangeOutOfBoundsAndUnknownBlockDoNotWrite() {
    List<String> conflictOrder = new ArrayList<>();
    RecordingAccess conflictAccess =
            new RecordingAccess(conflictOrder, AIR);
    BlockChangeResult conflict =
            service(
                            conflictAccess,
                            new RecordingPublisher(conflictOrder))
                    .changeBlock(request(2, 4, 3));
    assertEquals(BlockChangeResult.Status.CONFLICT, conflict.status());
    assertEquals(0, conflictAccess.writes);
    assertTrue(conflictOrder.isEmpty());

    List<String> noChangeOrder = new ArrayList<>();
    RecordingAccess noChangeAccess =
            new RecordingAccess(noChangeOrder, STONE);
    BlockChangeResult noChange =
            service(
                            noChangeAccess,
                            new RecordingPublisher(noChangeOrder))
                    .changeBlock(
                            request(2, 4, 3, STONE, STONE));
    assertEquals(BlockChangeResult.Status.NO_CHANGE, noChange.status());
    assertEquals(0, noChangeAccess.writes);
    assertTrue(noChangeOrder.isEmpty());

    List<String> boundsOrder = new ArrayList<>();
    RecordingAccess boundsAccess =
            new RecordingAccess(boundsOrder, STONE);
    boundsAccess.withinBounds = false;
    BlockChangeResult outOfBounds =
            service(
                            boundsAccess,
                            new RecordingPublisher(boundsOrder))
                    .changeBlock(request(2, 4, 3));
    assertEquals(
            BlockChangeResult.Status.OUT_OF_BOUNDS,
            outOfBounds.status());
    assertEquals(0, boundsAccess.writes);
    assertTrue(boundsOrder.isEmpty());

    List<String> unknownOrder = new ArrayList<>();
    RecordingAccess unknownAccess =
            new RecordingAccess(unknownOrder, STONE);
    unknownAccess.known.remove(AIR);
    BlockChangeResult unknown =
            service(
                            unknownAccess,
                            new RecordingPublisher(unknownOrder))
                    .changeBlock(request(2, 4, 3));
    assertEquals(
            BlockChangeResult.Status.UNKNOWN_BLOCK,
            unknown.status());
    assertEquals(0, unknownAccess.writes);
    assertTrue(unknownOrder.isEmpty());
}

@Test
void postChangeFailuresAttemptBothEventsAndReportCommittedMutation() {
    List<String> order = new ArrayList<>();
    RecordingAccess access = new RecordingAccess(order, STONE);
    RecordingPublisher events = new RecordingPublisher(order);
    events.changedFailure =
            new IllegalStateException("changed listener");
    events.dirtyFailure =
            new IllegalArgumentException("dirty listener");

    BlockChangeDispatchException failure =
            assertThrows(
                    BlockChangeDispatchException.class,
                    () ->
                            service(access, events)
                                    .changeBlock(request(2, 4, 3)));

    assertTrue(failure.mutationApplied());
    assertEquals(1, access.writes);
    assertEquals(
            List.of("before", "write", "changed", "dirty"),
            order);
    assertEquals(1, failure.getCause().getSuppressed().length);
}

@Test
void beforeChangeFailureDoesNotWrite() {
    List<String> order = new ArrayList<>();
    RecordingAccess access = new RecordingAccess(order, STONE);
    RecordingPublisher events =
            new RecordingPublisher(order) {
                @Override
                public BlockChangeDecision beforeChange(
                        BeforeBlockChangedEvent event) {
                    throw new IllegalStateException("before listener");
                }
            };

    BlockChangeDispatchException failure =
            assertThrows(
                    BlockChangeDispatchException.class,
                    () ->
                            service(access, events)
                                    .changeBlock(request(2, 4, 3)));

    assertEquals(false, failure.mutationApplied());
    assertEquals(0, access.writes);
}

@Test
void workerThreadCannotMutateWorld() throws Exception {
    DefaultWorldMutationService service =
            service(
                    new RecordingAccess(new ArrayList<>(), STONE),
                    new RecordingPublisher(new ArrayList<>()));
    java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
        java.util.concurrent.ExecutionException failure =
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () ->
                                worker.submit(
                                                () ->
                                                        service.changeBlock(
                                                                request(
                                                                        2,
                                                                        4,
                                                                        3)))
                                        .get());
        assertInstanceOf(
                IllegalStateException.class, failure.getCause());
    } finally {
        worker.shutdownNow();
        assertTrue(
                worker.awaitTermination(
                        5, java.util.concurrent.TimeUnit.SECONDS));
    }
}
```

- [ ] **Step 6: Run the full coordinator test and engine suite**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.DefaultWorldMutationServiceTest
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit the standard coordinator**

```powershell
git add engine/src/main/java/com/overlord/interaction engine/src/test/java/com/overlord/interaction/DefaultWorldMutationServiceTest.java
git commit -m "feat(api): enforce transactional block mutation order"
```

---

### Task 5: Add shared engine test fixtures and prove game consumption

**Files:**

- Modify: `game/build.gradle`
- Create: `engine/src/testFixtures/java/com/overlord/interaction/testing/StubBlockRaycastService.java`
- Create: `engine/src/testFixtures/java/com/overlord/interaction/testing/FakeBlockWorldAccess.java`
- Create: `engine/src/testFixtures/java/com/overlord/interaction/testing/RecordingBlockChangeEventPublisher.java`
- Create: `engine/src/testFixtures/java/com/overlord/inventory/testing/TestItemStackView.java`
- Create: `engine/src/testFixtures/java/com/overlord/inventory/testing/TestInventoryView.java`
- Create: `engine/src/testFixtures/java/com/overlord/inventory/testing/StubInventoryService.java`
- Create: `engine/src/testFixtures/java/com/overlord/inventory/testing/StubBodyInventoryViewModel.java`
- Test: `game/src/test/java/com/gaia/contracts/EngineContractFixtureSmokeTest.java`

**Interfaces:**

- Consumes: all API contracts and `BlockWorldAccess`.
- Produces: configurable test-only fixtures on Gradle's test-fixtures
  variant.
- Produces: a game test that imports the shared fixtures without runtime
  coupling.

- [ ] **Step 1: Add the game test-fixture dependency and write a failing consumption smoke test**

Add inside `game` dependencies:

```groovy
testImplementation(testFixtures(project(':engine')))
```

Create:

```java
package com.gaia.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.testing.TestInventoryView;
import com.overlord.inventory.testing.TestItemStackView;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineContractFixtureSmokeTest {
    @Test
    void gameTestsConsumeSharedInventoryFixtures() {
        TestItemStackView stack =
                new TestItemStackView(
                        ResourceLocation.parse("gaia:stone"), 2);
        TestInventoryView inventory =
                new TestInventoryView(
                        new EntityRef(3),
                        7,
                        Map.of(BodySlot.LEFT_HAND, stack));

        assertEquals(
                stack,
                inventory.stack(BodySlot.LEFT_HAND).orElseThrow());
    }
}
```

- [ ] **Step 2: Run the smoke test and verify fixtures are missing**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.contracts.EngineContractFixtureSmokeTest
```

Expected: `compileTestJava` fails because the shared fixture classes do not
exist.

- [ ] **Step 3: Implement focused immutable inventory fixtures**

Create:

```java
// TestItemStackView.java
package com.overlord.inventory.testing;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStackView;
import java.util.Objects;

public record TestItemStackView(
        ResourceLocation itemId, int count)
        implements ItemStackView {
    public TestItemStackView {
        itemId = Objects.requireNonNull(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException(
                    "count must be positive");
        }
    }
}
```

```java
// TestInventoryView.java
package com.overlord.inventory.testing;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStackView;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record TestInventoryView(
        EntityRef owner,
        long revision,
        Map<BodySlot, ItemStackView> stacks)
        implements InventoryView {
    public TestInventoryView {
        owner = Objects.requireNonNull(owner, "owner");
        if (revision < 0) {
            throw new IllegalArgumentException(
                    "revision must be non-negative");
        }
        stacks = Map.copyOf(stacks);
    }

    @Override
    public Optional<ItemStackView> stack(BodySlot slot) {
        Objects.requireNonNull(slot, "slot");
        return Optional.ofNullable(stacks.get(slot));
    }
}
```

```java
// StubInventoryService.java
package com.overlord.inventory.testing;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import java.util.Objects;
import java.util.Optional;

public final class StubInventoryService
        implements InventoryService {
    private Optional<InventoryView> snapshot;
    private InventoryChangeResult replacementResult;
    private InventoryChangeRequest lastRequest;

    public StubInventoryService(
            Optional<InventoryView> snapshot,
            InventoryChangeResult replacementResult) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.replacementResult =
                Objects.requireNonNull(
                        replacementResult, "replacementResult");
    }

    @Override
    public Optional<InventoryView> snapshot(EntityRef owner) {
        Objects.requireNonNull(owner, "owner");
        return snapshot;
    }

    @Override
    public InventoryChangeResult replaceSlot(
            InventoryChangeRequest request) {
        lastRequest = Objects.requireNonNull(request, "request");
        return replacementResult;
    }

    public InventoryChangeRequest lastRequest() {
        return lastRequest;
    }
}
```

```java
// StubBodyInventoryViewModel.java
package com.overlord.inventory.testing;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryView;
import java.util.Objects;

public record StubBodyInventoryViewModel(
        EntityRef owner,
        BodySlot activeSlot,
        InventoryView inventory)
        implements BodyInventoryViewModel {
    public StubBodyInventoryViewModel {
        owner = Objects.requireNonNull(owner, "owner");
        activeSlot = Objects.requireNonNull(activeSlot, "activeSlot");
        inventory = Objects.requireNonNull(inventory, "inventory");
    }
}
```

- [ ] **Step 4: Implement configurable interaction fixtures**

Create:

```java
// StubBlockRaycastService.java
package com.overlord.interaction.testing;

import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.BlockRaycastService;
import java.util.Objects;
import java.util.Optional;
import org.joml.Vector3fc;

public final class StubBlockRaycastService
        implements BlockRaycastService {
    private Optional<BlockHitResult> result;
    private int calls;

    public StubBlockRaycastService(
            Optional<BlockHitResult> result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public Optional<BlockHitResult> raycast(
            Vector3fc origin,
            Vector3fc direction,
            float maxDistance) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(direction, "direction");
        calls++;
        return result;
    }

    public int calls() {
        return calls;
    }
}
```

```java
// FakeBlockWorldAccess.java
package com.overlord.interaction.testing;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.BlockWorldAccess;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class FakeBlockWorldAccess
        implements BlockWorldAccess {
    private final Set<ResourceLocation> known = new HashSet<>();
    private ResourceLocation block;
    private boolean withinBounds = true;
    private int writes;

    public FakeBlockWorldAccess(
            ResourceLocation initialBlock,
            Set<ResourceLocation> knownBlocks) {
        block = Objects.requireNonNull(initialBlock, "initialBlock");
        known.addAll(Set.copyOf(knownBlocks));
    }

    @Override
    public boolean isWithinBounds(int x, int y, int z) {
        return withinBounds;
    }

    @Override
    public boolean isKnownBlock(ResourceLocation candidate) {
        return known.contains(candidate);
    }

    @Override
    public ResourceLocation blockAt(int x, int y, int z) {
        return block;
    }

    @Override
    public boolean setBlock(
            int x,
            int y,
            int z,
            ResourceLocation replacement) {
        block = Objects.requireNonNull(replacement, "replacement");
        writes++;
        return true;
    }

    public void setWithinBounds(boolean withinBounds) {
        this.withinBounds = withinBounds;
    }

    public int writes() {
        return writes;
    }
}
```

```java
// RecordingBlockChangeEventPublisher.java
package com.overlord.interaction.testing;

import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.BlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.BeforeBlockChangedEvent;
import com.overlord.interaction.api.ChunkDirtyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RecordingBlockChangeEventPublisher
        implements BlockChangeEventPublisher {
    private final List<Object> events = new ArrayList<>();
    private BlockChangeDecision decision =
            BlockChangeDecision.ALLOW;

    @Override
    public BlockChangeDecision beforeChange(
            BeforeBlockChangedEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
        return decision;
    }

    @Override
    public void blockChanged(BlockChangedEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    @Override
    public void chunksDirty(ChunkDirtyEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    public void setDecision(BlockChangeDecision decision) {
        this.decision = Objects.requireNonNull(decision, "decision");
    }

    public List<Object> events() {
        return List.copyOf(events);
    }
}
```

- [ ] **Step 5: Compile all fixtures and run the game consumption test**

Run:

```powershell
.\gradlew.bat :engine:testFixturesClasses
.\gradlew.bat :game:test --tests com.gaia.contracts.EngineContractFixtureSmokeTest
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit shared test support**

```powershell
git add engine/src/testFixtures game/build.gradle game/src/test/java/com/gaia/contracts
git commit -m "test(api): share interaction contract fixtures"
```

---

### Task 6: Enforce architecture boundaries

**Files:**

- Create: `engine/src/test/java/com/overlord/interaction/InteractionArchitectureTest.java`

**Interfaces:**

- Consumes: production source layout and reflection-visible API types.
- Produces: regression guards for engine/game dependencies, UI read-only
  access, queued-event avoidance, and direct gameplay world writes.

- [ ] **Step 1: Write the architecture regression test**

Create:

```java
package com.overlord.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.InventoryService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class InteractionArchitectureTest {
    private static final Path REPOSITORY_ROOT =
            Path.of("..").toAbsolutePath().normalize();
    private static final Path ENGINE_MAIN =
            Path.of("src/main/java").toAbsolutePath().normalize();
    private static final Path GAME_MAIN =
            REPOSITORY_ROOT.resolve("game/src/main/java");
    private static final Set<Path> DIRECT_WORLD_WRITE_ALLOWLIST =
            Set.of(
                    GAME_MAIN.resolve(
                                    "com/gaia/world/WorldLoader.java")
                            .normalize(),
                    GAME_MAIN.resolve(
                                    "com/gaia/world/GaiaWorldGenerator.java")
                            .normalize());
    private static final Pattern DIRECT_SET_BLOCK_CALL =
            Pattern.compile("\\.\\s*setBlock\\s*\\(");

    @Test
    void engineContractsDoNotDependOnGameGraphicsOrGlfw()
            throws IOException {
        List<Path> offenders;
        try (Stream<Path> sources =
                javaSources(
                        ENGINE_MAIN.resolve(
                                "com/overlord/interaction/api"),
                        ENGINE_MAIN.resolve(
                                "com/overlord/inventory/api"))) {
            offenders =
                    sources.filter(
                                    source -> {
                                        String text = read(source);
                                        return text.contains("com.gaia")
                                                || text.contains(
                                                        "com.overlord.renderer")
                                                || text.contains("org.lwjgl")
                                                || text.contains("GLFW");
                                    })
                            .toList();
        }
        assertTrue(
                offenders.isEmpty(),
                "API boundary offenders: " + offenders);
    }

    @Test
    void uiViewModelCannotReturnMutableInventoryService() {
        for (Method method : BodyInventoryViewModel.class.getMethods()) {
            assertFalse(
                    InventoryService.class.isAssignableFrom(
                            method.getReturnType()),
                    method.toString());
        }
    }

    @Test
    void gameplaySourcesDoNotCallWorldSetBlock()
            throws IOException {
        List<Path> offenders;
        try (Stream<Path> sources = javaSources(GAME_MAIN)) {
            offenders =
                    sources.filter(
                                    source ->
                                            !isDirectWorldWriteAllowlisted(
                                                    source))
                            .filter(
                                    source -> {
                                        String text = read(source);
                                        return DIRECT_SET_BLOCK_CALL
                                                .matcher(text)
                                                .find();
                                    })
                            .toList();
        }
        assertTrue(
                offenders.isEmpty(),
                "Gameplay bypasses WorldMutationService: " + offenders);
    }

    @Test
    void directWorldWriteWhitelistAllowsOnlyExactGenerationFiles() {
        assertTrue(
                isDirectWorldWriteAllowlisted(
                        GAME_MAIN.resolve(
                                "com/gaia/world/WorldLoader.java")));
        assertTrue(
                isDirectWorldWriteAllowlisted(
                        GAME_MAIN.resolve(
                                "com/gaia/world/GaiaWorldGenerator.java")));
        assertFalse(
                isDirectWorldWriteAllowlisted(
                        GAME_MAIN.resolve(
                                "com/gaia/world/FutureGameplayWriter.java")));
    }

    @Test
    void standardMutationServiceDoesNotUseQueuedEventBus()
            throws IOException {
        String source =
                Files.readString(
                        ENGINE_MAIN.resolve(
                                "com/overlord/interaction/"
                                        + "DefaultWorldMutationService.java"));
        assertFalse(source.contains("EventBus"));
        assertFalse(source.contains("ServiceLocator"));
    }

    private static Stream<Path> javaSources(Path... roots)
            throws IOException {
        Stream<Path> combined = Stream.empty();
        for (Path root : roots) {
            combined =
                    Stream.concat(
                            combined,
                            Files.walk(root)
                                    .filter(Files::isRegularFile)
                                    .filter(
                                            path ->
                                                    path.toString()
                                                            .endsWith(
                                                                    ".java")));
        }
        return combined;
    }

    private static boolean isDirectWorldWriteAllowlisted(
            Path source) {
        return DIRECT_WORLD_WRITE_ALLOWLIST.contains(
                source.toAbsolutePath().normalize());
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not read " + source, failure);
        }
    }
}
```

- [ ] **Step 2: Run the architecture test**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.InteractionArchitectureTest
```

Expected: `BUILD SUCCESSFUL`. If a rule fails, inspect the exact offender and
fix the production dependency. The only direct-write exceptions are
`game/src/main/java/com/gaia/world/WorldLoader.java` and
`game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`; do not add a
package-wide or gameplay exception. The raw-source regex can false-positive
on comments or strings and false-negative through indirection.

- [ ] **Step 3: Run all engine and game tests**

Run:

```powershell
.\gradlew.bat test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit architecture enforcement**

```powershell
git add engine/src/test/java/com/overlord/interaction/InteractionArchitectureTest.java
git commit -m "test(api): guard interaction ownership boundaries"
```

---

### Task 7: Reconcile documentation, complete handoff, and verify

**Files:**

- Modify: `docs/architecture/interaction-inventory-contract.md`
- Modify: `docs/architecture/current-baseline.md`
- Create: `docs/agent-handoffs/phase-07-handoff.md`

**Interfaces:**

- Consumes: final public signatures and verification results from Tasks 1–6.
- Produces: the required Phase 7 architecture record and handoff.

- [ ] **Step 1: Reconcile the approved architecture document with the final names**

Compare every public type and method in
`docs/architecture/interaction-inventory-contract.md` against
`engine/src/main/java/com/overlord/interaction` and
`engine/src/main/java/com/overlord/inventory`. Update only discrepancies
introduced by reviewed implementation changes. Preserve the approved event
order, thread ownership, and non-goals.

- [ ] **Step 2: Update the current baseline**

Add a Phase 7 section to `docs/architecture/current-baseline.md` stating:

```markdown
### Interaction and inventory contracts

- Gameplay block writes use the synchronous `WorldMutationService` contract;
  the standard implementation validates the main thread and emits
  before-change, revalidates the expected block, and emits changed and
  chunk-dirty events in the documented order.
- `BlockRaycastService` exposes data-driven `ResourceLocation` hits while
  preserving the Phase 6 raycast as the algorithmic implementation to adapt.
- `InventoryView`, `ItemStackView`, and `BodyInventoryViewModel` are
  read-only snapshots; `InventoryService.snapshot` and change-result views
  use `Optional<InventoryView>` so unknown owners are representable, and
  mutations remain isolated behind `InventoryService`.
- Body inventory slots are `LEFT_HAND`, `RIGHT_HAND`, and `MOUTH`.
- Phase 7 does not wire or implement gameplay, inventory rules, or UI.
```

- [ ] **Step 3: Run whitespace, generated-file, and full Windows verification**

Run:

```powershell
git diff --check
git ls-files | Select-String -Pattern '(^|/)(bin/|build/)|\.class$'
.\gradlew.bat clean test build
```

Expected:

- `git diff --check` prints nothing.
- The tracked-generated-file query prints nothing.
- Gradle reports `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the interactive Windows smoke test only with a user present**

Run:

```powershell
.\gradlew.bat :game
```

Expected: the existing world opens and movement/rendering behavior is
unchanged. Exit normally. If the smoke test is not run, record it as not run;
do not claim success.

- [ ] **Step 5: Write the Phase 7 handoff with actual evidence**

Create `docs/agent-handoffs/phase-07-handoff.md` with these exact headings:

```markdown
# Phase 07 Handoff

## Completed work

## Unfinished work

## Core architecture decisions

## Modified files

## Test commands and results

## Known risks

## Interfaces the next phase must not break

## Git diff stat

## Suggested commit message

## Suggested pull request
```

Populate every section with the actual final repository state. Record
Windows automated and interactive results separately. Record both macOS
commands as not run unless they were actually executed on macOS. Use:

```text
feat(api): define block interaction and body inventory contracts
```

as the suggested final commit message and:

```text
Phase 7: define interaction and body inventory API contracts
```

as the suggested PR title.

- [ ] **Step 6: Run final documentation and build verification**

Run:

```powershell
git diff --check
.\gradlew.bat clean test build
git status --short --branch
git diff --stat origin/main...HEAD
```

Expected: both checks succeed, the branch remains
`feat/interaction-api-contracts`, and only Phase 7 files/commits differ from
`origin/main`.

- [ ] **Step 7: Commit the final documentation**

```powershell
git add docs/architecture/interaction-inventory-contract.md docs/architecture/current-baseline.md docs/agent-handoffs/phase-07-handoff.md
git commit -m "docs: complete phase 7 interaction handoff"
```

Do not merge or push unless the user separately requests it.

## Final review reconciliation

The user-approved final review wave was implemented on 2026-07-25. The
primary production/test fixes are committed at `7415cf6`, with boundary
adjacency hardening at `64f1743`.

- Strict RED to GREEN evidence was captured for post-before precondition
  revalidation, the Optional inventory API/result invariants, and extreme
  integer face normals.
- A follow-up strict RED to GREEN regression proved that outward adjacency at
  the integer coordinate boundaries can match only after `int` wrap; all
  three adjacency comparisons now use widened arithmetic.
- The five changed engine focused suites passed individually with 15, 6, 9,
  6, and 6 tests. The game fixture consumer also passed.
- `.\gradlew.bat test` passed, followed by a clean
  `.\gradlew.bat clean test build` with 18 of 18 tasks executed.
- Final clean-build XML contains 57 suites and 503 tests: engine 46/400 and
  game 11/103, with zero failures, errors, or skipped tests.
- The final branch diff relative to `origin/main` is 43 files changed,
  5,476 insertions, and 7 deletions.
- The interactive game was not relaunched for the final review wave. The
  earlier Windows launch remains qualified because no Gradle exit code or
  independent visual confirmation was captured; macOS remains unrun.
- No push or merge was performed.
