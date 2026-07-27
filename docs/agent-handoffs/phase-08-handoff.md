# Phase 08 Handoff

Branch: `feat/body-inventory`
Base: `origin/main` at `d13d8fe4d0ac59e2a1a94b84cc0ed698fa6aca33`

## Completed work

- Implemented the transactional three-slot inventory domain and its fixed-
  update input composition.
- Preserved Phase 7 `ItemStack`, `InventoryService`, reservations, and
  `WorldItemService` signatures.
- Added ItemForm lookup through the existing `BlockRegistry`; no item registry
  or alternate stack type was introduced.
- Added immutable inventory/active-slot events, body inventory snapshots,
  stack merge/remainder behavior, mouth eligibility, atomic two-handed anchors,
  reserve/commit/rollback, Q-drop coordination, and explicit debug commands.
- Resolved the final branch-review findings: two-handed insert reservations
  commit as one anchored unit; mouth reservations lock only the mouth; exact
  split is atomic; live reads enforce main-thread ownership; committed events
  cannot be cancelled; non-running input edges are discarded; and event tests
  assert complete order and failure exclusion.
- Defined explicit exceptional outcomes: indeterminate world-item spawn keeps
  the inventory reservation live for reconciliation, while post-commit event
  publication failure reports `stateChangeApplied=true` without rollback or
  automatic retry.
- Added focused behavior tests for all Phase 8 contract paths.
- Confirmed a clean Windows build with 649 engine tests plus 289 game tests
  (938 total), all passing.

## Unfinished work

- No production world-item entity, physics body, pickup, or visual Q drop.
- No player-facing block breaking, placement, crafting, backpack, HUD, icons,
  text, or slot rendering.
- Native macOS build and interactive verification remain pending.

## Core architecture decisions

- `BodyInventoryService` is the only mutable slot boundary. `BodyInventory`
  has no public mutation API.
- Two-handed stacks have one internal left-hand anchor, read through both hand
  slots and counted once. Both hands are locked/reserved/released atomically.
- Exact split moves either the complete requested count or nothing; it obeys
  slot eligibility, locks, stack limits, and two-handed ownership.
- The running game uses an absent world-item adapter to reject Q without
  deleting inventory. `InventoryDropController` is ready for Phase 11's unique
  `WorldItemService` implementation.
- Committed inventory notifications are non-cancellable observations. A
  throwing event sink exposes an applied-state exception and never rolls the
  mutation back.
- Debug tools are opt-in only through explicit Gradle properties or F5 through F8;
  they use public inventory APIs and never auto-seed a normal build.

## Modified files

```text
docs/agent-handoffs/phase-08-handoff.md
docs/architecture/body-inventory.md
engine/src/main/java/com/overlord/config/GameConfig.java
engine/src/main/java/com/overlord/core/input/InputManager.java
engine/src/main/java/com/overlord/core/input/InputSnapshot.java
engine/src/main/java/com/overlord/inventory/api/ActiveBodySlotChanged.java
engine/src/main/java/com/overlord/inventory/api/CommittedInventoryEvent.java
engine/src/main/java/com/overlord/inventory/api/InventoryChanged.java
engine/src/main/java/com/overlord/inventory/api/InventoryEventDispatchException.java
engine/src/test/java/com/overlord/inventory/api/InventoryContractTest.java
engine/src/test/java/com/overlord/core/input/InputManagerTest.java
engine/src/test/java/com/overlord/core/input/InputSnapshotTest.java
game/build.gradle
game/src/main/java/com/gaia/GameBootstrap.java
game/src/main/java/com/gaia/GameContext.java
game/src/main/java/com/gaia/GameLoop.java
game/src/main/java/com/gaia/blocks/BlockRegistry.java
game/src/main/java/com/gaia/inventory/ActiveSlotChangeResult.java
game/src/main/java/com/gaia/inventory/BodyInventory.java
game/src/main/java/com/gaia/inventory/BodyInventoryInputController.java
game/src/main/java/com/gaia/inventory/BodyInventoryService.java
game/src/main/java/com/gaia/inventory/DebugCommandResult.java
game/src/main/java/com/gaia/inventory/DebugInventoryProfile.java
game/src/main/java/com/gaia/inventory/InventoryDebugCommands.java
game/src/main/java/com/gaia/inventory/InventoryDebugSeeder.java
game/src/main/java/com/gaia/inventory/InventoryDropController.java
game/src/main/java/com/gaia/inventory/InventoryDropLocation.java
game/src/main/java/com/gaia/inventory/InventoryDropResult.java
game/src/main/java/com/gaia/inventory/InventoryExtractResult.java
game/src/main/java/com/gaia/inventory/InventoryInputResult.java
game/src/main/java/com/gaia/inventory/InventoryInsertResult.java
game/src/main/java/com/gaia/inventory/InventoryOperationResult.java
game/src/main/java/com/gaia/inventory/InventorySnapshotFormatter.java
game/src/main/java/com/gaia/inventory/InventorySplitResult.java
game/src/main/java/com/gaia/inventory/ItemFormLookup.java
game/src/main/java/com/gaia/inventory/WorldItemSpawnIndeterminateException.java
game/src/main/resources/assets/gaia/blocks/oak_leaves.json
game/src/test/java/com/gaia/GameBootstrapTest.java
game/src/test/java/com/gaia/GameLoopStructureTest.java
game/src/test/java/com/gaia/blocks/BlockRegistryTest.java
game/src/test/java/com/gaia/inventory/BodyInventoryInputControllerTest.java
game/src/test/java/com/gaia/inventory/BodyInventoryServiceTest.java
game/src/test/java/com/gaia/inventory/InventoryDebugToolsTest.java
game/src/test/java/com/gaia/inventory/InventoryDropControllerTest.java
game/src/test/java/com/gaia/inventory/InventoryResultContractTest.java
```

## Tests and results

Focused red/green cycles:

```powershell
.\gradlew.bat :game:test --tests com.gaia.inventory.BodyInventoryServiceTest --console=plain --no-daemon
```

- Initial RED: compile failed because the Phase 8 service/events/results did
  not yet exist.
- GREEN: all five initial body-inventory tests passed after the service was
  implemented.

```powershell
.\gradlew.bat :game:test --tests com.gaia.inventory.InventoryDropControllerTest --tests com.gaia.inventory.BodyInventoryInputControllerTest --tests com.gaia.inventory.InventoryDebugToolsTest --console=plain --no-daemon
```

- Initial RED: compile failed because Q controller, scroll input, and debug
  types did not yet exist.
- GREEN: Q commit/rollback, input edges, and debug fixtures passed after the
  implementation was added.

Final branch-review remediation used focused RED/GREEN cycles:

1. **Important - two-handed insert reservation.** Invariant: commit must
   publish one stack through both hands and count it once. RED failed with a
   missing right-hand projection; GREEN uses the stored both-hands ownership.
2. **Important - mouth reservation ownership.** Invariant: a mouth hold locks
   only the mouth even while a two-handed item occupies both hands. RED allowed
   a conflicting mouth replacement; GREEN derives both-hand ownership only
   from a hand anchor.
3. **Important - exact split.** Invariant: a split moves the exact requested
   count atomically or changes nothing. RED did not compile because the API was
   absent; GREEN covers success and every closed split failure status.
4. **Important - indeterminate world spawn.** Invariant: invalid spawn data
   cannot lock inventory, and a thrown spawn cannot be guessed or retried.
   RED left an invalid request reserved and lacked a typed indeterminate
   outcome; GREEN validates first and exposes the live reservation.
5. **Important - post-commit publication failure.** Invariant: event-sink
   failure cannot roll back committed inventory. RED lacked an applied-state
   outcome; GREEN throws `InventoryEventDispatchException` and verifies Q
   creates exactly one world item.
6. **Important - non-cancellable committed events.** Invariant: one observer
   cannot cancel a committed fact for later observers. RED suppressed the
   second EventBus subscriber; GREEN uses `CommittedInventoryEvent`.
7. **Important - loading input edges.** Invariant: gameplay edges accumulated
   outside `RUNNING` cannot replay later. RED lacked an edge-discard API; GREEN
   clears press/wheel edges while preserving held keys.
8. **Minor - read ownership.** Invariant: live inventory reads are captured on
   the service owner thread; immutable captured snapshots may then cross
   threads. RED allowed worker reads; GREEN guards all three read entry points.
9. **Minor - test evidence.** Existing `anyMatch`, double-negative, and source-
   only assertions did not prove order or exclusion. This was a test-quality
   gap rather than a production behavior suitable for an artificial RED.
   Replacement tests assert complete ordered events, no-op/failure exclusion,
   simultaneous numeric priority, multi-step wheel order, focus loss, and
   catch-up edge clearing.

The first independent Sol High follow-up review then found four additional
Important issues and two Minor documentation/result-shape issues. These were
also resolved before final verification:

1. A full-count split now clears the source and moves/merges the complete stack
   with one revision and one event. The focused RED returned
   `SOURCE_TOO_SMALL`; the focused GREEN covers empty and compatible targets.
2. Scroll callbacks now remain an ordered immutable delta sequence, so
   `+1, -1, +1` produces three ordered transitions rather than a net `+1`.
   Input is bounded to 64 steps per fixed sample. The RED did not compile
   against the missing sequence API; the related engine/game suites are GREEN.
3. Q-drop results now expose the exact canonical reservation or spawn
   remainder. Partial reservation and explicit spawn rejection RED tests lacked
   that accessor; both paths and rollback are GREEN.
4. All public Phase 8 closed-result records now enforce status/payload
   coherence. Constructor-contract tests cover every status category.
5. Documentation uses ASCII wording for the debug key range so UTF-8 readers
   cannot display a damaged dash.
6. This handoff now contains current complete diff accounting plus the required
   commit and pull-request suggestions.

Final Windows verification:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
.\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon
```

- `clean test build`: **passed** in 42s; all 22 actionable tasks executed.
- Engine JUnit XML: **649 tests**, 0 failures/errors.
- Game JUnit XML: **289 tests**, 0 failures/errors.
- Total: **938 tests**, 0 failures/errors.
- The final `build` ran packaged resources, packaged engine shaders, and
  installed-distribution shader verification. The same three tasks were also
  run explicitly with `--rerun-tasks` earlier in this phase and passed.
- `git diff --check`: passed.
- Complete unstaged worktree accounting relative to `origin/main`: **45 files changed, 3,807 insertions, 6 deletions**
  (15 tracked modifications and 30 intended untracked additions). No generated files are included.

Windows interactive verification:

```powershell
.\gradlew.bat :game --console=plain --no-daemon
.\gradlew.bat :game -PgaiaInventoryDebugCommand=seed -PgaiaInventoryDebugShortcuts=true --console=plain --no-daemon
```

- Both runs exited through the game with Gradle exit code **0**.
- The debug run printed the seeded left/right/mouth stacks, active slot changed
  to `MOUTH`, `clear` produced three empty slots, and `seed` restored all three
  standard stacks. This confirms the exercised numeric/wheel selection and
  F8/F6/F5 debug paths without enabling debug injection by default.

Focused tests cover merge/remainder, mouth and two-handed restrictions,
reservations, Q success/rejection, fixed-step input edges, snapshots/events,
debug injection, and the production composition boundary. The review-fix suite
also covers two-handed insert reservation commit, mouth-only reservation
ownership, every split failure, indeterminate spawn reconciliation, applied
event-publication failures, non-cancellable committed notifications, loading-
edge discard, live-read thread rejection, complete event ordering, no-op and
failure-event exclusion, simultaneous numeric priority, and multi-step wheel
ordering.

## Known risks

- The current player has no production world-item adapter, so Q intentionally
  fails closed until Phase 11.
- A thrown `WorldItemService.spawn` has an indeterminate side effect and
  requires explicit reconciliation of the exposed live reservation. A thrown
  post-commit inventory event means the mutation already applied; callers must
  not blindly retry either condition.
- `oak_leaves` is mouth-holdable only to exercise the data-driven physical
  mouth slot; this is not an eating system.
- Inventory state is single-owner/player scope for this phase. Multi-entity
  inventory management and persistence need a deliberate later contract.
- Native macOS build and interactive verification are **NOT RUN**.

## Interfaces the next phase must not break

- Phase 7 inventory/world-item reservations and idempotent terminal outcomes.
- `BodyInventoryService` as the only mutation path and immutable
  `BodyInventoryViewModel`/`InventoryView` projections.
- `BodySlot` order, `InventoryChanged`, `ActiveBodySlotChanged`, and
  fixed-step input edge semantics.
- Phase 11 must use `InventoryDropController` with the canonical
  `WorldItemService`, not introduce an independent drop store.

## Independent final review

- First Sol High follow-up review findings were resolved with focused tests.
- Second Sol High review returned 0 Critical, 0 Important, and one Minor test-
  evidence gap; exhaustive `InventorySplitResult` payload tests closed it.
- Final targeted read-only confirmation: **Engine READY; Game/shared READY; 0 Critical, 0 Important, 0 Minor**.

## Suggested commit and pull request

Suggested commit message:

```text
feat(inventory): add transactional three-slot body inventory
```

Suggested PR title:

```text
feat(inventory): implement physical left right and mouth inventory
```

Suggested PR description:

- implement canonical three-slot stacking, exact split, two-handed ownership,
  reservations, immutable views, and committed notifications;
- integrate fixed-step numeric, ordered bounded wheel, Q-drop, and opt-in debug
  controls without adding a HUD or production world-item entity;
- preserve Phase 7 inventory/world-item contracts and Phase 2 ItemForm rules;
- verify 938 automated tests, packaged resources/shaders, and Windows
  interactive debug input.

No commit, push, PR creation, or merge was performed by Codex.
