# Phase 8 Body Inventory Design

## Scope

Phase 8 implements the physical left hand, right hand, and mouth inventory
domain. It intentionally does not render a HUD, add a backpack, create world
entities, or implement breaking and placement.

## Source of truth

`ItemStack(ResourceLocation, positive count)` remains the only item-stack
domain value. `ItemStackView` remains a read-only projection. Gaia does not
introduce an item registry: `BlockRegistry.itemForm(ResourceLocation)` is a
read-only index over the existing Phase 2 `BlockDefinition.item` values.
`ItemFormDefinition` supplies the maximum stack size, mouth eligibility, and
two-handed rule for every accepted stack.

The current content marks `gaia:oak_leaves` as `mouthHoldable`. This provides
one data-driven, non-edible mouth-capable item for development verification;
no eating behavior is implied.

## Ownership and state

`BodyInventoryService` is the only public mutation entry point. It owns one
`BodyInventory` for the injected player `EntityRef`; every service operation,
including `snapshot`, `viewModel`, and `totalCount`, must be invoked on the
captured main fixed-update thread. A caller may pass an already-created
immutable snapshot to another thread, but a worker must not read the live
service. `BodyInventory` exposes no public mutator. Snapshots return immutable
`InventoryView`/`BodyInventoryViewModel` values.

The service publishes post-mutation `InventoryChanged(owner, revision)` and
post-selection `ActiveBodySlotChanged(owner, previous, active)` through an
injected event sink. These committed notifications are deliberately
non-cancellable: `cancel()` is a no-op and `isCancelled()` is always false, so
one observer cannot suppress later observers. Events contain immutable
identity data; a UI obtains a fresh read-only view from the service rather than
receiving storage.

State is committed before notification publication. If an event sink throws,
the service throws `InventoryEventDispatchException` with
`stateChangeApplied=true` and the exact failed notification. The committed
inventory state is not rolled back or automatically retried. Callers must not
blindly retry an operation after receiving this exception.

## Slots and two-handed items

The presentation order is fixed by Phase 7:

1. `LEFT_HAND`
2. `RIGHT_HAND`
3. `MOUTH`

Hand slots accept normal items. The mouth accepts only an ItemForm with
`mouthHoldable=true`; Phase 8 has no separate edible registry or food model.
A two-handed stack is stored once at the left-hand anchor and projected through
both hand slots. It is counted once, locks both hand slots for transactions,
and is cleared or replaced atomically. The service never leaves a one-hand
half-state for a two-handed item.

`split(owner, source, destination, count)` is an exact, atomic move using the
same canonical `ItemStack`. It never creates an alternate stack type. The
destination must be the requested legal slot, must be empty or contain the same
item, and must have capacity for the complete count. Moving the entire source
is legal and clears that source atomically. A split never partially applies;
all rejected statuses preserve both contents and total item count.

## Transactions and Q drop

The Phase 7 `InventoryService` reservation signatures are unchanged. An
extract reservation locks the affected slot(s); an insert reservation locks
capacity. Ordinary changes to unrelated slots may proceed, but locked slots
cannot invalidate a successful reservation. Commit and rollback are terminal
and idempotent. A normal successful reservation therefore makes its later
commit guaranteed.

`InventoryDropController` coordinates the only world-item source of truth:

1. reserve a complete active-slot extraction;
2. ask `WorldItemService` to spawn the reserved canonical `ItemStack`;
3. commit the inventory reservation only after spawn succeeds;
4. roll back the inventory reservation when reserve is partial/rejected or
   spawn is rejected.

The complete `WorldItemSpawnRequest` is validated before inventory is locked.
If `WorldItemService.spawn` throws, its side effect is unknowable: the
controller throws `WorldItemSpawnIndeterminateException` containing the still-
live inventory reservation. It neither commits nor rolls back automatically.
Reconciliation must inspect the authoritative world-item service and then
explicitly commit or roll back that reservation; blind retry is forbidden.
If spawn explicitly returns `REJECTED`, rollback remains safe and automatic.
Closed Q-drop results preserve the canonical remainder returned by the failed
inventory reservation or rejected world-item spawn. Result constructors enforce
that success/commit-failure carries a world item, explicit rejection carries a
remainder, and empty/unavailable outcomes carry neither.

No production `WorldItemService` creates entities in Phase 8. Consequently the
running game recognizes Q but reports the closed `WORLD_ITEM_UNAVAILABLE`
result and does not mutate inventory. Phase 11 must inject the unique physical
world-item service; it must not add another drop store or ID namespace.

## Input and developer tools

`InputManager` latches key edges and scroll steps until the first fixed update.
`InputSnapshot.heldOnly()` clears both, so fixed-step catch-up cannot repeat a
1/2/3 selection, scroll cycle, Q transaction, or debug action. While the game
is not `RUNNING`, `GameLoop` discards gameplay press and wheel edges but keeps
held-key state; loading input therefore cannot replay when the world becomes
ready. Focus loss clears held keys, press edges, wheel edges, and the mouse
baseline.

- `1`, `2`, `3`: select left hand, right hand, mouth.
- mouse wheel: cycle active slot.
- `Q`: request a transactional active-slot drop.
- `F5`, `F6`, `F7`, `F8`: explicit debug `seed`, `clear`, `fill`, `print`
  when debug shortcuts are enabled.

If more than one numeric selection edge appears in one snapshot, priority is
`1`, then `2`, then `3`; numeric selection also takes precedence over wheel
steps in that same snapshot. Multiple wheel steps are applied in order and
publish the corresponding complete active-slot event sequence. `InputManager`
preserves callback order, including opposing directions in one fixed sample,
and accepts at most 64 scroll steps per sample to bound main-thread work from a
malformed driver offset.

The normal build starts empty. To execute one development command at startup:

```powershell
.\gradlew.bat :game -PgaiaInventoryDebugCommand=seed
```

To enable F5 through F8 while running:

```powershell
.\gradlew.bat :game -PgaiaInventoryDebugShortcuts=true
```

`InventoryDebugSeeder` is deliberately constructed against the public
`InventoryService` interface, and `InventorySnapshotFormatter` accepts only
read-only `BodyInventoryViewModel` data.

## Protected follow-on interfaces

- Phase 7 canonical `ItemStack`, reservation, and world-item API signatures.
- `BodySlot` ordering and read-only inventory views.
- `WorldItemService` as the only future source of Q, block-drop, and physics-
  drop identities.
- fixed-update main-thread mutation ownership; all GLFW/OpenGL work stays in
  its existing main-context path.
