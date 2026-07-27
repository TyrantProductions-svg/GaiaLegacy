# Block Interaction Core

Phase 9A implements the gameplay core for mode-aware block breaking and
placement. It deliberately exposes only logical feedback; crack rendering,
particles, world-item rendering, and HUD presentation remain later work.

## Runtime ownership

`GameLoop` invokes `BlockInteractionController.fixedUpdate` after player movement
and physics on each existing 1/60 fixed step. The controller, inventory,
`WorldMutationService`, and logical world-item backend are main-thread state.
No interaction operation submits worker work or performs OpenGL calls.

The ray origin is copied from the authoritative `PhysicsBody` feet position plus
eye height. Direction is copied from `Camera.getForward`. The Gaia adapter then
delegates to the unique Phase 6 shape-aware `BlockRaycast`; it does not implement a
second voxel traversal.

## Inputs and modes

| Input | Fixed-step meaning |
| --- | --- |
| Left mouse held (Survival) | advance or start a timed break session |
| Left mouse press edge (Creative) | perform at most one instant break |
| Right mouse press edge | attempt one placement |
| `F4` press edge | toggle Survival/Creative, immediately cancel, and suppress held destructive buttons until release |
| `1` / `2` / `3`, wheel | select real body slot through Phase 8 |
| `Q` press edge | drop through the same logical `WorldItemService` backend |
| `F1` | release/recapture cursor; the frame boundary immediately cancels and suppresses mouse interaction until release |

Mouse button holds survive fixed-step catch-up while press edges are presented only
to the first step. Survival reads the left-button held state so one physical press
can accumulate a timed break. Creative reads the left-button press edge, so the
same physical press cannot immediately break a newly raycast block behind the first
target during catch-up. Both placement modes already read the right-button press
edge. `InputManager`, which owns the GLFW button callbacks, is the sole authority
for physical-release re-arming across F1 and focus boundaries. It records which
destructive buttons were held before clearing their visible state, masks them from
fixed snapshots, and clears each mask only on that button's GLFW release callback.
`BlockInteractionController` owns only the already-consumed F4 batch mask, so a
mode switch cannot hand an old held button to later catch-up steps. The
`MouseInteractionLifecycle` invokes session cancellation at the render-frame
boundary; it does not wait for a future fixed step. Focus loss also clears key,
scroll, and cursor state, and `InputManager` emits one invalidation signal for the
game boundary to consume.
Creative selection is independent from the real three-slot inventory, so returning
to Survival restores the unchanged real active slot. Game mode never toggles noclip.

## Break state machine

```text
IDLE
  | primary held + loaded breakable target
  v
BREAKING -- fixed 1/60 accumulation --> COMPLETE
  |                                      |
  | release / block, face, revision,     | reserve item destinations
  | load, mode, range, or UI change      | then WorldMutationService
  v                                      v
CANCELLED ----------------------------> APPLIED / REJECTED
```

A session identity is block position, hit face, expected block identity, and Chunk
revision. Hit point and distance may vary while looking at the same face and do not
reset progress. Survival duration is `hardness / baseBreakSpeed`; hardness zero is
instant, while air is explicitly unbreakable. Creative breaking is instant, creates
no drop, and consumes no inventory. It is nevertheless one mutation per physical
left-button press; release and a new press are required before another Creative
break.

Progress and crack stage exist only in `BlockBreakSession` and immutable view-model
snapshots. They do not write a block, mark a Chunk dirty, or rebuild a mesh.

## Break item transaction

The block's existing `ItemFormDefinition` produces one canonical `ItemStack` in the
current content rules. Before changing the block:

1. `InventoryService.reserve(INSERT)` protects every insertable part, trying the
   active slot first and then the remaining physical slots.
2. The unique logical world-item object reserves future spawn capacity for the exact
   remainder through its `WorldItemSpawnReservations` capability.
3. Any missing required capacity rolls back all acquired reservations; no mutation
   occurs.
4. `WorldMutationService` compares the expected block and writes air.
5. Only an applied mutation commits the inventory and future-spawn reservations.

Every applied result enforces:

```text
produced = inventoryCommitted + worldItemCommitted
```

The future-spawn capability is not a second service or store. One
`LogicalWorldItemService` instance owns normal Q spawns, block-drop future spawns,
stable `WorldItemId` values, immutable snapshots, pickup delay, and existing-item
extraction reservations.

Pickup availability uses monotonic saturated tick arithmetic. Even a reservation
created at the end of the `long` tick range can commit as promised; its availability
is clamped to `Long.MAX_VALUE` instead of overflowing after reservation success.

## Placement transaction

The candidate is the hit-adjacent cell. Validation occurs before reservation:

- selected identity resolves through `BlockRegistry.blockForItem`;
- the destination Chunk is loaded and the cell is air (the current replaceable set);
- the unit block AABB does not intersect the authoritative player-body AABB.

Survival then reserves one extraction from the active anchored body slot. The
mutation expects air and writes the registry-backed block identity. A rejected or
cancelled mutation rolls back; an applied mutation commits exactly one extraction.
Creative placement reads only `CreativeSelection` and consumes no Survival item.

## Mutation and event order

All gameplay writes enter `DefaultWorldMutationService` through
`GaiaBlockWorldAccess`. `ChunkRepository.compareAndSetBlock` remains the only owner of
revisions, dirty propagation, and boundary-neighbor invalidation.

The synchronous order is:

1. `BeforeBlockChangedEvent`;
2. repository compare-and-set plus revision/dirty update;
3. `BlockChangedEvent`;
4. `ChunkDirtyEvent` containing repository-issued revisions;
5. inventory/world-item reservation commits.

Before cancellation has no write, dirty, post event, or item commit. A post-write
subscriber exception carries `mutationApplied=true`; the item reservations still
commit, the failure is exposed in the view model, and callers never retry the world
mutation. Inventory post-commit notification failure follows the same no-rollback,
no-retry rule.

## Read-only presentation

`BlockInteractionViewModel` extends, without changing, the protected Phase 7
`InteractionViewModel`. Its immutable snapshots expose target, hit face, progress,
interaction mode, active item, recent failure, crack stage, and Survival/Creative
mode. Phase 9B and Phase 10 may observe it but must not mutate interaction,
inventory, or world state through the view.

## Deferred work

- crack overlay, particles, hand animation, and audio (Phase 9B);
- three-slot HUD, icons, quantities, and mode feedback (Phase 10);
- PhysicsBody-backed dropped items, pickup, merging, and persistence (Phase 11);
- complex replaceable blocks, oriented/non-cube placement, tools, and loot tables.
