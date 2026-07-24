# Interaction, Inventory, and World-Item Contract

Status: approved normative design for Phase 7 Prompt Suite v2.1
Original approval: 2026-07-24
v2.1 scope alignment: 2026-07-25
Branch: `feat/interaction-api-contracts`

## Purpose and scope

Phase 7 establishes game-neutral contracts for raycast results, transactional
block mutation, body inventory, world-item ownership, and read-only
interaction presentation. It does not wire gameplay. The contracts let Phase
8 and Phase 11 build against one set of identities and transaction boundaries
without modifying renderer or controller ownership.

Stable APIs live in `engine`. Gaia adapters and gameplay composition belong in
`game` and may depend only on public engine APIs. New code uses explicit
constructor dependencies; it must not expand `ServiceLocator`.

## Canonical item identity and command/view split

`com.overlord.inventory.api.ItemStack` is the canonical immutable `ItemStack`
for commands, requests, results, reservations, inventory fakes, and world-item
fakes:

```java
public record ItemStack(ResourceLocation itemId, int count)
        implements ItemStackView {}
```

Every stack has a non-null `ResourceLocation` item identity and a positive count.
`ItemStackView` is a read-only snapshot/projection, not a second domain stack.
Read APIs such as `InventoryView.stack` and
`InteractionViewModel.activeItem` may expose `ItemStackView`; mutation values
such as `ItemUseContext.heldStack`, `InventoryChangeRequest.replacement`,
reservation requests, spawn requests, and remainders use `ItemStack`.

There is no second item registry in Phase 7. `ResourceLocation` is the item
identity boundary until an explicitly reviewed item-definition registry is
needed. Phase 8 must not define another `ItemStack`, alternate item identity,
or competing stack hierarchy.

## Interaction contexts and raycast

`EntityRef` is an immutable non-negative entity ID used at API boundaries
instead of exposing mutable ECS entities. `InteractionAction` expresses
`PRIMARY`, `SECONDARY`, or `USE` intent. Every `InteractionContext` exposes
actor, active `BodySlot`, action, non-negative fixed-update tick, and
non-negative monotonic timestamp.

`ItemUseContext` adds an optional canonical held `ItemStack` and an optional
immutable `BlockHitResult`. Empty hands and misses use `Optional.empty()`.

`BlockHitResult` contains target and adjacent block coordinates, the target
block `ResourceLocation`, one of six exact axis normals, a finite world-space
hit point, and a finite non-negative hit distance.
`BlockRaycastService.raycast` is read-only. A future Gaia adapter delegates to
the existing Phase 6 DDA and block registry; Phase 7 does not duplicate that
algorithm.

## Inventory contract

### Read and replace operations

`BodySlot` remains exactly `LEFT_HAND`, `RIGHT_HAND`, and `MOUTH` in stable
presentation order. `InventoryView` is an immutable owner/revision snapshot
whose slots expose `Optional<ItemStackView>`. `BodyInventoryViewModel` exposes
only owner, active slot, and the read-only inventory snapshot.

`InventoryService` has these operations:

```java
Optional<InventoryView> snapshot(EntityRef owner);
InventoryChangeResult replaceSlot(InventoryChangeRequest request);
InventoryReserveResult reserve(InventoryReservationRequest request);
InventoryReservationResult commit(InventoryReservationId reservationId);
InventoryReservationResult rollback(InventoryReservationId reservationId);
```

`replaceSlot` retains optimistic expected-revision behavior for direct slot
replacement. It is not sufficient for multi-service pickup and drop
transactions, which use reservations.

### Reservation shapes and invariants

`InventoryReservationRequest` contains owner, slot, operation, and requested
canonical stack. `InventoryReservationOperation` is exactly `INSERT` or
`EXTRACT`. `InventoryReservation` contains a stable
`InventoryReservationId`, the original request, and the protected canonical
stack.

`InventoryReserveResult` statuses are `RESERVED`, `PARTIALLY_RESERVED`,
`REJECTED`, `UNKNOWN_OWNER`, and `INVALID_STACK`.

- `RESERVED` protects the complete request and has no remainder.
- `PARTIALLY_RESERVED` has a reservation and positive remainder whose counts
  sum exactly to the request.
- Every explicit failure has no reservation and returns the full request as
  its remainder; `UNKNOWN_OWNER` has no inventory snapshot.
- Successful and known-owner failures include a non-negative-revision
  inventory snapshot.

`InventoryReservationResult` statuses are `COMMITTED`, `ROLLED_BACK`,
`ALREADY_COMMITTED`, `ALREADY_ROLLED_BACK`, `TERMINAL_CONFLICT`, and
`UNKNOWN_RESERVATION`.

The first `commit` or `rollback` makes the reservation terminal. Repeating the
same terminal operation is idempotent and reports the matching `ALREADY_*`
status without a second side effect. Attempting the opposite terminal action
reports `TERMINAL_CONFLICT`.

Once `reserve` succeeds, the exact reserved operation is protected from
ordinary inventory state changes. In particular, `commit` cannot fail merely
because an unrelated slot or revision changed after reservation. Production
implementations must enforce that protected commit guarantee with a ledger or
equivalent ownership; callers must not simulate it with a later unprotected
replace.

Phase 7 supplies only `FakeInventoryReservationService` under
`engine/src/testFixtures`. It models reservation protection, remainders,
terminal status, and idempotency. It deliberately does not implement
production capacity, item limits, stacking, persistence, or concurrency.

## Unique world-item source of truth

There is one unique `WorldItemService` contract:

```java
WorldItemSpawnResult spawn(WorldItemSpawnRequest request);
Optional<WorldItemSnapshot> snapshot(WorldItemId itemId);
WorldItemReservationResult reserve(WorldItemId itemId, int count);
WorldItemReservationResult commit(WorldItemReservationId reservationId);
WorldItemReservationResult rollback(WorldItemReservationId reservationId);
```

`WorldItemId` is the stable instance identity and is distinct from the
canonical stack's `ResourceLocation` item identity. A
`WorldItemReservationId` identifies one transaction hold.

`WorldItemSpawnRequest` contains a canonical stack, finite position and
velocity components, optional source `EntityRef`, and non-negative tick.
`WorldItemSpawnResult` is `SPAWNED` with a matching snapshot and no remainder,
or `REJECTED` with no item and the complete stack remainder.
`WorldItemSnapshot` contains stable `WorldItemId`, canonical stack, finite
position and velocity, and non-negative revision.

`WorldItemReservation` protects a canonical count from one stable instance.
Reservation statuses cover full and partial reservation, commit, rollback,
same-operation idempotency, terminal conflict, unavailable or unknown items,
unknown reservations, and invalid counts. Partial reservation returns a
positive remainder. Commit removes only the reserved count; rollback restores
availability without changing the instance identity.

### Transaction sketches

Drop:

1. `InventoryService.reserve(EXTRACT)` protects the canonical stack.
2. `WorldItemService.spawn` receives that exact stack.
3. On `SPAWNED`, commit the inventory reservation.
4. On explicit spawn failure, roll back the inventory reservation and keep the
   returned remainder visible to the caller.

Pickup:

1. `WorldItemService.reserve` protects a full or partial count.
2. `InventoryService.reserve(INSERT)` protects the accepted inventory change.
3. Commit both reservations only after both services report compatible
   amounts; otherwise roll back every acquired reservation.
4. Treat terminal results explicitly. Do not blindly retry an unknown outcome.

Player Q drop, block drops, and Phase 11 physics drops share the service.
They must not allocate parallel IDs, bypass reservations, or create an
alternate production world-item store.

Phase 7 supplies only `FakeWorldItemService` in test fixtures. It creates no
production ECS entity, renderer object, collision shape, or `PhysicsBody`.

## Repository-owned block mutation outcomes

`ChunkRepository` owns dirty propagation and revision outcomes. Its atomic
boundary is:

```java
ChunkMutationOutcome compareAndSetBlock(
        int worldX, int y, int worldZ,
        byte expectedBlockId, byte replacementBlockId);
```

`ChunkMutationOutcome` reports `APPLIED`, `NO_CHANGE`, `CONFLICT`, or
`OUT_OF_BOUNDS`, the observed byte block, and an immutable ordered list of
`DirtyChunkRevision`. Only `APPLIED` has dirty revisions. Each entry records
the exact positive revision issued by the committed repository mutation.

`World.compareAndSetBlock` delegates to that repository boundary. A Gaia
`BlockWorldAccess` adapter maps resource IDs through `BlockRegistry` and
returns the corresponding `BlockWorldMutationOutcome` with the same statuses,
observed block identity, order, chunk keys, and exact revisions.

The repository dirties the target and only loaded horizontal boundary
neighbors. It does not create absent chunks: missing boundary neighbors are not reported as dirtied.
The service does not independently compute
invalidation candidates. `dirtyChunks()` is only a convenience view derived
from the authoritative revision entries.

Phase 3 stale-result and mesh lifecycle remain authoritative. A real mutation
invalidates older mesh claims using repository revisions; conflict,
no-change, and out-of-bounds outcomes advance no revision and publish no
post-commit event. This supplement does not change `ChunkMeshManager`.

## WorldMutationService transaction and events

`DefaultWorldMutationService` is constructed with exactly:

```java
DefaultWorldMutationService(
        MainThreadGuard mainThreadGuard,
        BlockWorldAccess world,
        BlockChangeEventPublisher events)
```

There is no `ChunkDirtyTracker` dependency. Successful transaction order is:

1. Assert main-thread ownership and validate the request.
2. Check bounds and known resource identities.
3. Read and compare the expected block; return `CONFLICT` or `NO_CHANGE` as
   appropriate.
4. Publish synchronous `BeforeBlockChangedEvent`.
5. Return `CANCELLED` on veto.
6. Re-read the target after Before dispatch; return `CONFLICT` if it changed.
7. Call the world compare-and-set once.
8. Map a rejected `BlockWorldMutationOutcome` without success events.
9. For `APPLIED`, attempt `BlockChangedEvent`.
10. Attempt `ChunkDirtyEvent` with the exact repository-issued revisions.
11. Return `APPLIED` only when both post-write attempts complete.

`ChunkDirtyEvent` is post-commit observation only. It contains exact committed
dirty revisions; it is neither an invalidation command nor a list of
theoretical neighbors.

Before-event mutation reentrancy is prohibited for all targets. There is no
supported nesting depth and changing coordinates does not evade the guard. A
nested call throws `BlockMutationReentrancyException`; the outer Before
dispatch wraps it in `BlockChangeDispatchException` with
`mutationApplied() == false`, performs no outer write, and resets the guard in
`finally`. Post-Before target revalidation remains mandatory even though
reentrant calls through the same service are prohibited.

If Before dispatch otherwise fails, no mutation occurs. After an applied
write, post-write subscriber failure does not roll back and is not automatically retried.
The service attempts both `BlockChangedEvent` and
`ChunkDirtyEvent`, preserves the first failure, suppresses a distinct second
failure, and throws `BlockChangeDispatchException` with
`mutationApplied() == true`. `mutationApplied() == true` forbids blind caller retry
because the repository mutation and its revisions already committed.
Recovery must inspect durable state or reconcile observers explicitly.

Gameplay world writes must use `WorldMutationService`. Direct
`World.setBlock` remains allowed only in the exact world-loading/generation
files guarded by `InteractionArchitectureTest`.

## Read-only InteractionViewModel

The read-only `InteractionViewModel` exposes target, face, progress, mode,
active item, and failure reason:

```java
Optional<BlockHitResult> target();
Optional<BlockFace> hitFace();
double progress();
InteractionMode mode();
Optional<ItemStackView> activeItem();
Optional<InteractionFailureReason> failureReason();
```

`BlockFace` has exactly `EAST`, `WEST`, `UP`, `DOWN`, `SOUTH`, and `NORTH`
with exact axis normals and conversion from a hit. `InteractionMode` is
`NONE`, `BREAKING`, `PLACING`, or `USING`. Progress is finite and inclusive
in `[0, 1]`. Target and face presence agree in the fixture. Active item is a
snapshot projection. `InteractionFailureReason` contains a non-null
`ResourceLocation` code and no display text. The interface returns no mutable
inventory, mutation, or world-item service.

Phase 7 provides `StubInteractionViewModel` only as a test fixture. It does
not raycast, mutate gameplay, control UI, or own progress scheduling.

## Thread ownership and error handling

- Inventory, world-item, and block mutations are main-thread fixed-update
  operations unless a future implementation publishes a stricter documented
  boundary.
- OpenGL, GLFW polling, GPU uploads, and resource destruction remain on the
  main OpenGL context thread. These contracts perform none of those actions.
- Read-only immutable snapshots may cross into UI or worker consumers when
  their backing implementation permits it.
- Expected domain rejection uses explicit result statuses and remainders.
  Nulls, invalid value shapes, non-finite numbers, negative IDs/revisions, and
  wrong-thread calls are programming errors.
- Synchronous event subscribers must not offload, reorder, or recursively
  invoke the guarded mutation transaction.

## Directory ownership and test fixtures

Engine contracts and fixtures are engine-developer owned under `engine/**`.
Gaia adapters and gameplay composition are game-developer owned under
`game/**`. Cross-boundary adapter changes require both owners. Shared
architecture, handoff, Gradle, and CI files require awareness from both.

All stateful implementations introduced for inventory reservations,
world-item reservations, and interaction presentation are under
`engine/src/testFixtures`. Game tests may consume them through Gradle test
fixtures. They are not production adapters.

## Explicit non-goals

The v2.1 supplement added contracts, tests, fixtures, repository outcome
reporting, and documentation only: no Phase 8 gameplay, formal inventory,
production world entity, physics drop, renderer, controller, mesh-manager, or
UI implementation was added. It also added no inventory persistence, item
definition registry, production pickup/drop adapter, block-breaking loop,
placement loop, HUD, new GPU work, or physics simulation.

## Protected interfaces for later phases

Phase 8 and Phase 11 must preserve:

- canonical `ItemStack` commands and `ItemStackView` read projections;
- `InventoryService` snapshot, replace, `reserve`, `commit`, and `rollback`
  semantics, including protected commits and terminal idempotency;
- the unique `WorldItemService`, stable identities, snapshots, spawn
  remainders, and reservation transaction semantics;
- `ChunkRepository.compareAndSetBlock`, exact `DirtyChunkRevision` reporting,
  and Phase 3 mesh/revision authority;
- `BlockWorldAccess.compareAndSetBlock`,
  `DefaultWorldMutationService` constructor, reentrancy guard, revalidation,
  event order, and post-write failure semantics;
- `BlockChangeResult` and `ChunkDirtyEvent` authoritative dirty revisions;
- the six-face `BlockHitResult`/`BlockFace` mapping and read-only
  `InteractionViewModel`;
- main-thread mutation and OpenGL/GLFW ownership rules;
- the engine/game module boundary and direct-world-write architecture guard.

Any incompatible change requires an explicit cross-owner architecture review,
updated contract tests, and a new handoff decision.
