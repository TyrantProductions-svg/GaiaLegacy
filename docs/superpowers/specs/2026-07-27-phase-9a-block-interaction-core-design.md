# Phase 9A Block Interaction Core Design

## Scope and ownership

Phase 9A adds deterministic block breaking and placement without renderer feedback,
HUD, particles, or physical dropped-item bodies. Gaia-specific policies and runtime
composition live in `game`; reusable world-item storage and reservation primitives
live in `engine`. Renderer, physics collision, world generation, and Chunk mesh
lifecycle implementations are not changed.

The main thread owns target selection, interaction state, inventory reservations,
world-item reservations, world mutation, and event publication. Interaction advances
only from the existing 1/60 fixed-update path. It never submits work to a worker.

## Existing contracts retained

- `ResourceLocation` and the Phase 7 `ItemStack` remain the only item identity and
  stack value.
- `BlockRegistry` remains the data-driven source for block definitions and
  `ItemFormDefinition`; no item registry is introduced.
- `InventoryService`, `InventoryReservation`, and their method signatures remain
  unchanged. `BodyInventoryService` remains the only inventory mutation boundary.
- `WorldItemService` remains the only world-item source and store. Its Phase 7
  methods and meanings remain unchanged.
- `DefaultWorldMutationService` remains the only gameplay block-write path and the
  `ChunkRepository` remains the authority for revisions, dirty propagation, and
  boundary-neighbor invalidation.
- The Phase 6 shape-aware `BlockRaycast` remains the only voxel raycast algorithm.
  A Gaia adapter only translates its hit into the Phase 7 API result.

## Minimal Phase 7 migration: future-spawn reservation

Breaking requires all item capacity to be protected before the block becomes air.
`WorldItemService.reserve` protects an already-existing world item, so calling
`spawn` before mutation would create a duplicate if mutation later failed, while
calling it after mutation would offer no commit guarantee.

Phase 9A therefore adds a narrow `WorldItemSpawnReservations` capability (not a
second service).
The single production `LogicalWorldItemService` implements both it and the existing
`WorldItemService`, backed by one item map, one reservation map, and one stable-ID
sequence. Runtime composition supplies the same object for both interfaces. The
extension provides:

1. `reserveSpawn(request)` — protects capacity but creates no visible item;
2. `commitSpawn(id)` — idempotently materializes the exact reserved snapshot;
3. `rollbackSpawn(id)` — idempotently releases capacity without creating an item.

An accepted reservation has a commit guarantee. Explicit rejection happens before
world mutation. A thrown commit after an applied mutation is an indeterminate
post-commit infrastructure failure: the controller exposes the failure and must not
blindly retry the block mutation.

## Target authority

Each fixed step constructs the ray origin from the authoritative
`PhysicsBody.position + eyeHeight`; it never uses interpolated Camera position.
Direction is copied from Camera forward. The adapter delegates to the Phase 6
`BlockRaycast` and maps the stored block ID through `BlockRegistry`.

Target validity requires a loaded, non-unloading Chunk and a hit within configured
reach. A break session records position, face, expected block identity, owning Chunk
revision, elapsed fixed time, required time, progress, and crack stage.

## Game mode and input

`GameMode` has `SURVIVAL` and `CREATIVE`. `GameModeManager` publishes one committed,
non-cancellable `GameModeChanged` notification per actual transition. F4 is consumed
as a press edge on the first fixed step only. A transition cancels the active session,
updates the view model, and returns immediately so no break/place action occurs in
that fixed step. It never changes noclip.

Primary mouse hold breaks; secondary mouse press places. Mouse button state joins
`InputSnapshot` as immutable down/pressed sets. `heldOnly()` retains held buttons and
removes button edges, so fixed-step catch-up advances breaking but cannot duplicate a
placement. Focus loss clears keys, buttons, scroll, and cursor deltas. Cursor release
blocks interaction and cancels a live break session.

`CreativeSelection` is independent of `BodyInventory`. Returning to survival reveals
the unchanged real active body slot.

## Breaking state machine

Survival break time is `hardness / baseBreakSpeed`, accumulated only in 1/60 steps.
Air is never targetable. Hardness zero is immediately breakable; non-finite or
otherwise invalid definitions are rejected. Creative breaks on its action step.

A session cancels on release, target/face/expected-block change, range failure,
Chunk revision change, unload, mode transition, cursor/UI blocking, or mutation
conflict. Cancellation clears progress and crack stage without touching Chunk data.

At completion, the block's canonical item form yields one item. Before mutation the
coordinator reserves as much insertion capacity as possible through
`InventoryService`, then reserves spawn capacity for the exact remainder. If either
required reservation fails, all acquired reservations roll back and the block stays.
After an applied air mutation, both reservations commit exactly once. The invariant
is `produced = inventoryCommitted + worldItemCommitted`.

Creative breaking skips all drop reservations and mutates directly to air.

## Placement transaction

Placement targets the hit-adjacent cell. Validation requires a loaded Chunk, an air
(replaceable in this first content set) target, a registry-backed placeable block
item, sufficient survival quantity, and no intersection with the authoritative
player AABB.

Survival first reserves one extraction from the active anchored slot. It then calls
`WorldMutationService` with expected air and the selected block. Applied mutation is
followed by the guaranteed, idempotent reservation commit; every pre-write or
mutation failure rolls back. Creative reads `CreativeSelection` and consumes no
inventory.

## Events and post-write failures

World mutation event order is inherited unchanged:

1. `BeforeBlockChangedEvent`;
2. repository compare-and-set and revision/dirty propagation;
3. `BlockChangedEvent`;
4. `ChunkDirtyEvent`.

Before cancellation produces no write, no dirty notification, and no inventory or
world-item commit. A `BlockChangeDispatchException` with `mutationApplied=true` is
treated as an applied mutation: item reservations commit and the caller must not
retry the mutation. Post-write subscriber failures never roll back the world and are
surfaced as an interaction failure for diagnostics.

`GameModeChanged` is post-commit and non-cancellable. Interaction snapshots and
events contain only immutable records/interfaces; mutable vectors and inventories
do not escape.

## Read-only presentation

The Phase 7 `InteractionViewModel` method set is unchanged. A game-layer
`BlockInteractionViewModel` extends it with `crackStage()` and `gameMode()`. Snapshots
contain target, face, progress, interaction mode, active item snapshot, last failure,
crack stage, and game mode. Phase 9B/10 may read these values but cannot mutate the
controller.

## Failure policy

All expected validation and reservation failures are closed result values and leave
counts unchanged. Unexpected reservation commit failures after an applied world
mutation are invariant violations: they are surfaced, never compensated by another
world write, and never automatically retried. Tests cover no-op, cancellation,
conflict, explicit rejection, post-write notification failure, and idempotent
terminal calls.
