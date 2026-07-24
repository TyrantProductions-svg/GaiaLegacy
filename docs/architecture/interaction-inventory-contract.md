# Interaction and Body Inventory Contract

Status: approved design for Phase 7  
Date: 2026-07-24  
Branch: `feat/interaction-api-contracts`

## Purpose and scope

Phase 7 establishes stable contracts for block interaction and the
three-slot body inventory before gameplay and UI implementations begin. The
contracts let interaction and inventory work proceed without modifying
`World`, `Renderer`, or `GaiaMain`.

This phase provides:

- read-only block raycast results and a raycast service contract;
- a single transactional gameplay entry point for block changes;
- synchronous block-change event contracts and dirty propagation;
- read-only inventory and UI views plus a separate mutation service;
- shared Gradle test fixtures for engine and game development.

This phase does not implement breaking, placement, item stacking, pickup,
dropping, inventory persistence, or UI rendering. It also does not wire the
new contracts into `GameBootstrap` or `GameContext`.

## Module and package boundaries

Stable, game-neutral APIs live in the engine module:

- `com.overlord.interaction.api` owns interaction contexts, raycast and
  mutation contracts, block-change events, and entity references.
- `com.overlord.inventory.api` owns body slots, item stack views, inventory
  snapshots, mutation requests/results, and the read-only UI ViewModel.
- `com.overlord.interaction` owns the standard transaction coordinator and
  its narrow world-access SPI.
- `com.overlord.interaction.testing` and
  `com.overlord.inventory.testing` under `engine/src/testFixtures/java` own
  shared configurable fakes and recording stubs.

The engine contracts may depend on existing engine value types such as
`ResourceLocation`, `ChunkKey`, `MainThreadGuard`, and JOML read-only vector
interfaces. They must not depend on `game`.

Future Gaia adapters belong under `game/src/main/java/com/gaia/interaction`
and `game/src/main/java/com/gaia/inventory`. A game adapter will translate
between data-driven `ResourceLocation` block identities and the byte IDs
stored by `World`, using `BlockRegistry`.

## Interaction API

### Entity identity

`EntityRef` is an immutable value containing a non-negative integer ID. It
is used at API boundaries instead of exposing the existing mutable
`com.overlord.ecs.Entity`.

```java
public record EntityRef(int id) {}
```

Its constructor rejects negative IDs.

### Actions and common context

`InteractionAction` represents gameplay intent rather than a physical input
binding:

```java
public enum InteractionAction {
    PRIMARY,
    SECONDARY,
    USE
}
```

`InteractionContext` is the common read-only context:

```java
public interface InteractionContext {
    EntityRef actor();
    BodySlot activeBodySlot();
    InteractionAction action();
    long tick();
    long timestampNanos();
}
```

`tick` is a non-negative fixed-update tick. `timestampNanos` is a
non-negative monotonic timestamp, not wall-clock time.

`ItemUseContext` implements `InteractionContext` and adds the item and
raycast snapshots required by item behavior:

```java
public record ItemUseContext(
        EntityRef actor,
        BodySlot activeBodySlot,
        Optional<ItemStackView> heldStack,
        Optional<BlockHitResult> raycastResult,
        InteractionAction action,
        long tick,
        long timestampNanos)
        implements InteractionContext {}
```

An empty hand or raycast miss is represented with `Optional.empty()`.
Context records defensively validate all references and numeric fields.

### Raycast contract

`BlockHitResult` is immutable and contains:

- hit block coordinates;
- adjacent placement coordinates;
- the hit block's `ResourceLocation`;
- an axis-aligned face normal;
- the world-space hit point;
- non-negative hit distance.

The face normal has exactly one component equal to `-1` or `1`; the other
components are zero. All floating-point fields must be finite.

```java
public interface BlockRaycastService {
    Optional<BlockHitResult> raycast(
            Vector3fc origin,
            Vector3fc direction,
            float maxDistance);
}
```

The service is read-only. A later Gaia adapter delegates to the Phase 6
`BlockRaycast` implementation and maps its stored byte ID through
`BlockRegistry`; Phase 7 does not duplicate the DDA or collision-shape
algorithm.

## Block mutation contract

### Request and result

`BlockChangeRequest` contains:

- the initiating `InteractionContext`;
- target world coordinates;
- an expected current `ResourceLocation`;
- a replacement `ResourceLocation`.

The expected block is mandatory. It provides optimistic conflict detection
for stale raycast results.

`WorldMutationService` is synchronous:

```java
public interface WorldMutationService {
    BlockChangeResult changeBlock(BlockChangeRequest request);
}
```

`BlockChangeResult` contains the original request, a status, the block
observed at the target when one was available, and an immutable set of dirty
chunk keys. Its statuses are:

- `APPLIED`: the block changed and all post-change events were delivered;
- `NO_CHANGE`: expected and replacement blocks are the same;
- `CANCELLED`: a before-change subscriber vetoed the request;
- `CONFLICT`: the observed block did not match the expected block;
- `OUT_OF_BOUNDS`: the target is outside the mutable world bounds;
- `UNKNOWN_BLOCK`: the expected or replacement resource is not registered.

Expected domain rejection is returned as a status. Null values, invalid
contexts, and wrong-thread calls are programming errors and throw explicit
exceptions.

### World access SPI

The standard coordinator depends on a narrow `BlockWorldAccess` SPI:

```java
public interface BlockWorldAccess {
    boolean isWithinBounds(int x, int y, int z);
    boolean isKnownBlock(ResourceLocation block);
    ResourceLocation blockAt(int x, int y, int z);
    boolean setBlock(int x, int y, int z, ResourceLocation block);
}
```

The future Gaia implementation must make `setBlock` delegate to the existing
`World.setBlock`, preserving Phase 3 dirty tracking and mesh lifecycle. It
must not introduce a second block store.

`World.setBlock` remains a low-level engine method for world generation and
storage. Gameplay code must use `WorldMutationService`. An architecture test
allows the existing `game/world` generation paths and rejects new direct
gameplay calls to `World.setBlock`.

## Events and transaction order

The mutation transaction uses a constructor-injected synchronous
`BlockChangeEventPublisher`. It does not use the queued singleton
`EventBus`, because a queued before-change event cannot veto the current
transaction.

The event values are immutable:

- `BeforeBlockChangedEvent` contains the request and current block;
- `BlockChangedEvent` contains the request, previous block, and new block;
- `ChunkDirtyEvent` contains the request and the complete immutable set of
  affected `ChunkKey` values.

The publisher contract is:

```java
public interface BlockChangeEventPublisher {
    BlockChangeDecision beforeChange(BeforeBlockChangedEvent event);
    void blockChanged(BlockChangedEvent event);
    void chunksDirty(ChunkDirtyEvent event);
}
```

`BlockChangeDecision` is `ALLOW` or `CANCEL`. Cancellation is returned by
the publisher rather than stored as mutable state in the event.

`DefaultWorldMutationService` receives `MainThreadGuard`,
`BlockWorldAccess`, `BlockChangeEventPublisher`, and `ChunkDirtyTracker`
through its constructor. A successful call has this exact order:

1. Assert main-thread ownership.
2. Validate coordinates and block resource identities.
3. Read the current block and compare it with the expected block.
4. Publish `BeforeBlockChangedEvent`.
5. Stop with `CANCELLED` if the publisher vetoes the request.
6. Perform one world write. The underlying repository marks actual loaded
   chunks dirty.
7. Compute the invalidation set with `ChunkDirtyTracker`; it contains the
   target chunk and horizontal neighbors when the block lies on an edge.
8. Publish `BlockChangedEvent`.
9. Publish one `ChunkDirtyEvent` containing the complete invalidation set.
10. Return `APPLIED`.

Rejected requests do not write the world and do not publish success events.

If before-change dispatch throws, the write is not attempted. After a
successful write, the coordinator attempts both post-change publications
even if the first one fails. It then throws
`BlockChangeDispatchException` with `mutationApplied() == true`, preserving
the first cause and suppressing any additional failure. Callers must not
blindly retry such a request.

## Thread rules

- `WorldMutationService.changeBlock` may run only on the main thread during
  the fixed-update stage.
- UI, worker tasks, render callbacks, and GLFW callbacks do not write the
  world directly. They produce gameplay intent for the fixed update.
- `BlockChangeEventPublisher` runs synchronously on the mutation caller's
  thread. Subscribers must not offload or reorder transaction callbacks.
- `BlockRaycastService` is read-only. An implementation may use a world
  snapshot on a worker, but an implementation backed directly by the live
  `World` follows that world's read-concurrency rules.
- Inventory mutations use the same main/fixed-update ownership rule.
- Immutable `InventoryView`, `ItemStackView`, and event snapshots may be
  passed to read-only UI consumers.
- These APIs perform no OpenGL calls and create no GPU resources.

## Body inventory API

`BodySlot` has exactly three values in stable presentation order:

```java
public enum BodySlot {
    LEFT_HAND,
    RIGHT_HAND,
    MOUTH
}
```

`ItemStackView` is a read-only snapshot:

```java
public interface ItemStackView {
    ResourceLocation itemId();
    int count();
}
```

The count must be positive. Stack limits and item-specific rules are outside
Phase 7.

`InventoryView` is an immutable revisioned snapshot:

```java
public interface InventoryView {
    EntityRef owner();
    long revision();
    Optional<ItemStackView> stack(BodySlot slot);
}
```

Calling `stack` for any value in `BodySlot.values()` is supported. Empty
slots return `Optional.empty()`; implementations do not use null or an
artificial empty item.

`InventoryService` is the mutable boundary:

```java
public interface InventoryService {
    InventoryView snapshot(EntityRef owner);

    InventoryChangeResult replaceSlot(
            InventoryChangeRequest request);
}
```

`InventoryChangeRequest` contains owner, slot, expected revision, and an
optional replacement `ItemStackView`. `InventoryChangeResult` contains a
status and the resulting or currently observed `InventoryView`. Its statuses
are `APPLIED`, `CONFLICT`, `INVALID_STACK`, and `UNKNOWN_OWNER`.

`replaceSlot` is a minimal atomic primitive, not a stacking or transfer
algorithm. A revision mismatch returns `CONFLICT` and does not overwrite
newer state.

## UI read-only ViewModel

`BodyInventoryViewModel` exposes only:

```java
public interface BodyInventoryViewModel {
    EntityRef owner();
    BodySlot activeSlot();
    InventoryView inventory();
}
```

It has no setter and exposes no `InventoryService`. UI code can render the
left hand, right hand, and mouth by iterating `BodySlot.values()`, but it can
only emit intents. A controller or gameplay system performs mutations.

## Shared test fixtures

The engine applies Gradle's `java-test-fixtures` plugin. Game tests consume
the fixtures with:

```groovy
testImplementation(testFixtures(project(':engine')))
```

The shared fixtures are configurable contract aids, not gameplay
implementations:

- `StubBlockRaycastService`;
- `TestItemStackView`;
- `TestInventoryView`;
- `StubInventoryService`, which records configured calls without
  implementing stacking;
- `FakeBlockWorldAccess`;
- `RecordingBlockChangeEventPublisher`;
- `StubBodyInventoryViewModel`.

No fixture is placed on the production runtime classpath.

## Verification requirements

Focused tests cover:

- value validation for entity IDs, ticks, timestamps, vectors, distances,
  item counts, optionals, and resource identities;
- exactly three stable body slots;
- raycast hits and misses through the service stub;
- immutable inventory snapshots and revision conflicts;
- absence of mutation methods from the UI ViewModel;
- successful before/write/changed/dirty ordering;
- cancellation, conflict, no-change, out-of-bounds, and unknown-block
  rejection without a write;
- current-chunk and edge-neighbor dirty propagation;
- worker-thread rejection through `MainThreadGuard`;
- post-write dispatch failure reporting that the mutation was applied;
- game tests compiling against the shared engine fixtures;
- an architecture guard against new gameplay calls to `World.setBlock`.

Before handoff, run:

```powershell
.\gradlew.bat clean test build
```

Run `.\gradlew.bat :game` only as an interactive local smoke test; this phase
does not intentionally change game behavior. Record macOS verification as
not run unless it is actually executed on macOS.

## Directory ownership and future integration

- The engine developer owns `engine/**`, including the API packages,
  standard transaction coordinator, and shared test fixtures.
- The game developer owns future Gaia adapters in
  `game/**/interaction` and `game/**/inventory`.
- Within future game work, the interaction implementer owns
  `game/**/interaction`; the inventory/UI implementer owns
  `game/**/inventory` and the inventory ViewModel adapter.
- Root Gradle files and `docs/**` are shared and require both developers'
  awareness.
- Future `GameBootstrap` or `GameContext` composition changes are shared
  boundaries and require review from both owners.

## Interfaces later phases must preserve

Later phases must not:

- bypass `WorldMutationService` for gameplay block writes;
- make the UI depend on `InventoryService`;
- replace `ResourceLocation` with Gaia-specific constants at the API
  boundary;
- expose mutable ECS `Entity` or mutable item stacks through these contracts;
- reorder the before/write/changed/dirty transaction;
- move world or inventory mutation to worker threads;
- route the cancellable before event through the queued `EventBus`;
- make test fixtures part of the production runtime.
