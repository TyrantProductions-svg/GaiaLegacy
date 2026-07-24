# Phase 07 Handoff

Branch: `feat/interaction-api-contracts`
Base: `origin/main` at `ed707ec821b47b21232d44f79a5faf048c4f77e6`
Prompt Suite v2.1 scope-alignment supplement: 2026-07-25

## Completed work

The original Phase 7 work established game-neutral raycast, block-mutation,
body-inventory, fixture, and architecture boundaries. The dated Prompt Suite
v2.1 scope-alignment supplement completed Tasks 1-6 as follows:

- introduced canonical item commands and clarified snapshot projections;
- added inventory reservation contracts and a stateful test-fixture fake;
- added a unique world-item source-of-truth contract and fake;
- moved authoritative dirty/revision outcomes into `ChunkRepository`;
- migrated `DefaultWorldMutationService` to repository-issued outcomes and
  prohibited Before-event reentrancy;
- added the read-only interaction presentation contract and fixture;
- reconciled the normative architecture document and current baseline;
- added a focused documentation contract test using a verified RED/GREEN
  cycle.

Scope verification confirms that no Phase 8 gameplay, formal inventory,
production world entity, physics drop, renderer, controller, mesh-manager, or
UI implementation was added.

## Unfinished work

- Initial branch-wide owner review at `1583b34` reported two Important
  findings: inventory owner isolation and the subscriber `Error` protocol.
  This fix wave addresses both; controller re-review remains pending, and this
  handoff does not claim a clean or approved owner-review verdict.
- Gaia `BlockRaycastService` and resource-ID `BlockWorldAccess` adapters are
  not implemented or wired.
- Production inventory storage/rules, a production world-item adapter,
  breaking, placement, pickup, Q drop, block drops, Phase 11 physics drops,
  persistence, controller behavior, and UI remain unimplemented.
- Windows interactive `.\gradlew.bat :game` was not rerun for this v2.1
  supplement. The earlier qualified launch observation did not capture an
  independent visual verdict or Gradle exit code.
- Native macOS clean-build and interactive verification were not run.
- No push, pull request, merge, or modification of `main` was performed.

## Core architecture decisions

### Canonical stack and inventory reservations

- `ItemStack(ResourceLocation itemId, int count)` is the canonical immutable `ItemStack`.
  Its `ResourceLocation` is non-null and its count is a positive count.
- `ItemStackView` is a read-only snapshot/projection, not a second domain stack.
  It is for views; commands and results use `ItemStack`.
- There is no second item registry, and Phase 8 must not define another `ItemStack`
  or alternate item identity.
- `InventoryReservation` and `InventoryReservationId` support `reserve`,
  `commit`, and `rollback` for `INSERT` and `EXTRACT`.
- Full and partial success, remainder, and explicit failure shapes are
  validated. A successful reservation is protected from ordinary inventory state changes.
- `commit` and `rollback` are terminal and idempotent for repeat calls;
  opposite terminal calls return a conflict.
- `FakeInventoryReservationService` is test-fixture-only and does not imply
  production capacity, stacking, storage, or concurrency behavior.

### Unique world-item service

- The unique `WorldItemService` owns all instance identities and transaction
  holds. Its values include `WorldItemSpawnRequest`, `WorldItemSpawnResult`,
  `WorldItemReservation`, `WorldItemSnapshot`, stable `WorldItemId`, and
  stable reservation IDs.
- Spawn and reservation results make full/partial remainders and explicit
  failures observable. Commit removes only the reserved amount; rollback
  restores availability; repeated terminal calls are idempotent.
- Q drop, block drops, and Phase 11 physics drops share the service. Pickup
  coordinates world-item and inventory reservations; no second entity store
  or ID namespace may be introduced.
- `FakeWorldItemService` is test-fixture-only. It creates no production ECS
  entity, physics body, renderer object, or gameplay loop.

### Repository outcome and mutation events

- `ChunkRepository` owns dirty propagation and revision outcomes through
  `ChunkMutationOutcome`. `World` delegates to it, and
  `BlockWorldMutationOutcome` maps the same authoritative facts into
  resource identities.
- The applied outcome contains exact `DirtyChunkRevision` entries; missing boundary neighbors are not reported as dirtied.
- Phase 3 stale-result and mesh lifecycle remain authoritative. This
  supplement does not modify `ChunkMeshManager`.
- `DefaultWorldMutationService` has exactly `MainThreadGuard`,
  `BlockWorldAccess`, and `BlockChangeEventPublisher` constructor
  dependencies. It has no `ChunkDirtyTracker` and computes no theoretical
  invalidation candidates.
- `ChunkDirtyEvent` is post-commit observation only and carries exact
  repository-issued revisions.
- Before-event mutation reentrancy is prohibited for all targets. There is no
  supported nesting depth, the guard resets in `finally`, and post-Before
  target revalidation remains required.
- A post-write subscriber failure does not roll back and is not automatically retried.
  Both post events are attempted. `mutationApplied() == true` forbids blind caller retry.

### Read-only interaction projection

- The read-only `InteractionViewModel` exposes optional target and face,
  finite inclusive progress, mode, active item, and failure reason.
- `BlockFace` is exactly the six axis faces. `InteractionMode` is `NONE`,
  `BREAKING`, `PLACING`, or `USING`; failure reasons are non-null
  `ResourceLocation` codes without display text.
- `StubInteractionViewModel` is fixture-only. It performs no raycast,
  mutation, controller, scheduling, or UI work.

### Ownership

- Engine contracts and fixtures remain under engine-developer ownership.
  Future Gaia adapters and gameplay remain game-developer owned.
- Shared build and documentation changes require both owners' awareness.
- World/inventory/world-item mutations remain fixed-update main-thread work.
  Every OpenGL/GLFW/GPU lifecycle operation remains on the context-owning
  main thread.

## Exact modified files relative to `origin/main`

The branch changes these exact 76 tracked paths relative to `origin/main`:

```text
docs/agent-handoffs/phase-07-handoff.md
docs/architecture/current-baseline.md
docs/architecture/interaction-inventory-contract.md
docs/superpowers/plans/2026-07-24-phase-7-interaction-inventory-contracts.md
docs/superpowers/plans/2026-07-25-phase-7-prompt-suite-v2-1-alignment.md
engine/build.gradle
engine/src/main/java/com/overlord/interaction/BlockWorldAccess.java
engine/src/main/java/com/overlord/interaction/BlockWorldMutationOutcome.java
engine/src/main/java/com/overlord/interaction/DefaultWorldMutationService.java
engine/src/main/java/com/overlord/interaction/api/BeforeBlockChangedEvent.java
engine/src/main/java/com/overlord/interaction/api/BlockChangeDecision.java
engine/src/main/java/com/overlord/interaction/api/BlockChangeDispatchException.java
engine/src/main/java/com/overlord/interaction/api/BlockChangeEventPublisher.java
engine/src/main/java/com/overlord/interaction/api/BlockChangeRequest.java
engine/src/main/java/com/overlord/interaction/api/BlockChangeResult.java
engine/src/main/java/com/overlord/interaction/api/BlockChangedEvent.java
engine/src/main/java/com/overlord/interaction/api/BlockFace.java
engine/src/main/java/com/overlord/interaction/api/BlockHitResult.java
engine/src/main/java/com/overlord/interaction/api/BlockMutationReentrancyException.java
engine/src/main/java/com/overlord/interaction/api/BlockRaycastService.java
engine/src/main/java/com/overlord/interaction/api/ChunkDirtyEvent.java
engine/src/main/java/com/overlord/interaction/api/EntityRef.java
engine/src/main/java/com/overlord/interaction/api/InteractionAction.java
engine/src/main/java/com/overlord/interaction/api/InteractionContext.java
engine/src/main/java/com/overlord/interaction/api/InteractionFailureReason.java
engine/src/main/java/com/overlord/interaction/api/InteractionMode.java
engine/src/main/java/com/overlord/interaction/api/InteractionViewModel.java
engine/src/main/java/com/overlord/interaction/api/ItemUseContext.java
engine/src/main/java/com/overlord/interaction/api/WorldMutationService.java
engine/src/main/java/com/overlord/inventory/api/BodyInventoryViewModel.java
engine/src/main/java/com/overlord/inventory/api/BodySlot.java
engine/src/main/java/com/overlord/inventory/api/InventoryChangeRequest.java
engine/src/main/java/com/overlord/inventory/api/InventoryChangeResult.java
engine/src/main/java/com/overlord/inventory/api/InventoryReservation.java
engine/src/main/java/com/overlord/inventory/api/InventoryReservationId.java
engine/src/main/java/com/overlord/inventory/api/InventoryReservationOperation.java
engine/src/main/java/com/overlord/inventory/api/InventoryReservationRequest.java
engine/src/main/java/com/overlord/inventory/api/InventoryReservationResult.java
engine/src/main/java/com/overlord/inventory/api/InventoryReserveResult.java
engine/src/main/java/com/overlord/inventory/api/InventoryService.java
engine/src/main/java/com/overlord/inventory/api/InventoryView.java
engine/src/main/java/com/overlord/inventory/api/ItemStack.java
engine/src/main/java/com/overlord/inventory/api/ItemStackView.java
engine/src/main/java/com/overlord/voxel/ChunkMutationOutcome.java
engine/src/main/java/com/overlord/voxel/ChunkRepository.java
engine/src/main/java/com/overlord/voxel/DirtyChunkRevision.java
engine/src/main/java/com/overlord/voxel/World.java
engine/src/main/java/com/overlord/worlditem/api/WorldItemId.java
engine/src/main/java/com/overlord/worlditem/api/WorldItemReservation.java
engine/src/main/java/com/overlord/worlditem/api/WorldItemReservationId.java
engine/src/main/java/com/overlord/worlditem/api/WorldItemReservationResult.java
engine/src/main/java/com/overlord/worlditem/api/WorldItemService.java
engine/src/main/java/com/overlord/worlditem/api/WorldItemSnapshot.java
engine/src/main/java/com/overlord/worlditem/api/WorldItemSpawnRequest.java
engine/src/main/java/com/overlord/worlditem/api/WorldItemSpawnResult.java
engine/src/test/java/com/overlord/interaction/DefaultWorldMutationServiceTest.java
engine/src/test/java/com/overlord/interaction/InteractionArchitectureTest.java
engine/src/test/java/com/overlord/interaction/api/BlockMutationContractTest.java
engine/src/test/java/com/overlord/interaction/api/InteractionContractTest.java
engine/src/test/java/com/overlord/inventory/api/InventoryContractTest.java
engine/src/test/java/com/overlord/inventory/api/InventoryReservationContractTest.java
engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java
engine/src/test/java/com/overlord/voxel/WorldTest.java
engine/src/test/java/com/overlord/worlditem/api/WorldItemContractTest.java
engine/src/testFixtures/java/com/overlord/interaction/testing/FakeBlockWorldAccess.java
engine/src/testFixtures/java/com/overlord/interaction/testing/RecordingBlockChangeEventPublisher.java
engine/src/testFixtures/java/com/overlord/interaction/testing/StubBlockRaycastService.java
engine/src/testFixtures/java/com/overlord/interaction/testing/StubInteractionViewModel.java
engine/src/testFixtures/java/com/overlord/inventory/testing/FakeInventoryReservationService.java
engine/src/testFixtures/java/com/overlord/inventory/testing/StubBodyInventoryViewModel.java
engine/src/testFixtures/java/com/overlord/inventory/testing/StubInventoryService.java
engine/src/testFixtures/java/com/overlord/inventory/testing/TestInventoryView.java
engine/src/testFixtures/java/com/overlord/inventory/testing/TestItemStackView.java
engine/src/testFixtures/java/com/overlord/worlditem/testing/FakeWorldItemService.java
game/build.gradle
game/src/test/java/com/gaia/contracts/EngineContractFixtureSmokeTest.java
```

Ignored `.superpowers/sdd` coordination briefs and reports are not tracked
branch changes.

## TDD and verification evidence

Documentation-contract RED on 2026-07-25:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.InteractionArchitectureTest
```

- Expected failure: 9 tests completed, 1 failed.
- `phaseSevenDocumentsProtectPromptSuiteV21Decisions` reported that the old
  normative document lacked the canonical-stack and command/view decisions.
- The first restricted invocation could not download Gradle 8.5 because
  network access was denied; the approved identical rerun produced the
  expected product-test failure above.

Documentation-contract GREEN:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.interaction.InteractionArchitectureTest
```

- Passed: `BUILD SUCCESSFUL in 1s`; 6 actionable tasks, 1 executed and 5
  up-to-date; all 9 architecture tests passed.

Full Windows build:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

- Passed: `BUILD SUCCESSFUL in 18s`; 18 actionable tasks, all 18 executed.
- Gradle selected `natives-windows`.
- Engine JUnit XML: 48 suites, 479 tests, 0 failures, 0 errors, 0 skipped.
- Game JUnit XML: 11 suites, 107 tests, 0 failures, 0 errors, 0 skipped.
- Exact total: 59 suites, 586 tests, 0 failures, 0 errors, 0 skipped.

Packaged-resource verification:

```powershell
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
```

- Passed: `BUILD SUCCESSFUL in 9s`; 5 actionable tasks, all 5 executed.

Hygiene and scope checks:

- `git diff --check`: passed after removing Task 7 trailing spaces.
- Final post-Task-7 `git diff --check origin/main..HEAD` at
  `3a5f0c0e386c54e2b1a8b189ac5eb96096913c61`: exit `0`, no output.
- Tracked `build/`, `bin/`, `.class`, crash-dump, and replay-dump scan:
  no matches.
- Prohibited renderer/player/controller/mesh-manager production path changes
  in `0569ffc..HEAD`: no matches.
- `DefaultWorldMutationService` scan for `ChunkDirtyTracker`,
  `affectedByBlock`, `ChunkKey.fromWorld`, and `localCoordinate`: no matches.
- Production leakage of `FakeWorldItemService` or
  `FakeInventoryReservationService` outside test fixtures: no matches.
- Direct game production `.setBlock` calls outside the exact loader/generator
  allowlist: no matches.
- Task 7 changes exactly four documentation files and one engine test; it
  changes no production Java.

Owner-review input and final reconciliation HEAD:

```text
Initial owner-review input: 1583b34
Final fix/reconciliation HEAD: reported in the final external task report
after the required commit because a commit cannot contain its own
content-derived hash.
```

The initial review's two Important findings—inventory owner isolation and the
subscriber `Error` protocol—are addressed by this fix wave pending controller
re-review. No clean or approved owner-review verdict is claimed here.

Interactive/platform truth:

- Windows interactive game smoke for v2.1: **NOT RUN**.
- Native macOS clean build: **NOT RUN**.
- Native macOS interactive smoke: **NOT RUN**.

## Known risks

- Controller re-review of the two addressed Important findings is pending;
  owner review is not yet recorded as clean or approved.
- Production adapters do not exist, so cross-service transaction sketches
  are contract obligations, not exercised gameplay paths.
- Reservation fakes intentionally omit production capacity, persistence,
  concurrency, ECS, and physics behavior. Implementers must not infer those
  policies from fixture internals.
- The raw-source direct-world-write guard may match comments/strings and
  cannot detect an indirect write hidden behind a new abstraction.
- Post-write event failure leaves committed world state and exact revisions;
  recovery needs explicit reconciliation, not blind retry.
- Windows interactive rendering/input and native macOS behavior remain
  unverified by this supplement.

## Protected interfaces Phase 8 and Phase 11 must not break

- Canonical `ItemStack`, positive-count and `ResourceLocation` identity
  invariants, plus `ItemStackView` read-only projection semantics.
- `InventoryService.snapshot`, `replaceSlot`, `reserve`, `commit`, and
  `rollback`; request/result status shapes, remainder accounting,
  reservation protection, and terminal idempotency.
- The unique `WorldItemService`, stable `WorldItemId` and reservation IDs,
  `WorldItemSpawnRequest`, `WorldItemSpawnResult`, `WorldItemReservation`,
  `WorldItemSnapshot`, explicit failures, partial counts, and transaction
  semantics shared by pickup and every drop source.
- `ChunkRepository.compareAndSetBlock`, `ChunkMutationOutcome`,
  `DirtyChunkRevision`, missing-neighbor behavior, and Phase 3 stale mesh
  rejection.
- `BlockWorldAccess.compareAndSetBlock` and `BlockWorldMutationOutcome`
  mapping without a second block store.
- `DefaultWorldMutationService(MainThreadGuard, BlockWorldAccess,
  BlockChangeEventPublisher)`, synchronous order, Before-event reentrancy
  prohibition, post-Before revalidation, both post-write event attempts, and
  `mutationApplied()` retry meaning.
- `BlockChangeResult` and `ChunkDirtyEvent` exact dirty-revision payloads;
  dirty events remain observers, not commands.
- `EntityRef`, `InteractionAction`, `InteractionContext`, canonical held stack
  in `ItemUseContext`, `BlockHitResult`, and read-only
  `BlockRaycastService`.
- `BlockFace`, `InteractionMode`, `InteractionFailureReason`, and the exact
  read-only `InteractionViewModel` method set and invariants.
- Exactly three `BodySlot` values in presentation order and read-only
  `InventoryView`/`BodyInventoryViewModel`.
- The `java-test-fixtures` production boundary, direct-write allowlist,
  engine/game module separation, Java 17 compatibility, checked-in Gradle
  Wrapper, OpenGL 4.1/GLSL 410 limit, and main/context-thread GPU ownership.

## Git diff stat

Final `git diff --stat origin/main..HEAD`:

```text
76 files changed, 9617 insertions(+), 20 deletions(-)
```

## Suggested overall commit and pull request

Suggested overall commit message:

```text
feat(api): align interaction, inventory, and world-item contracts
```

Task 7 documentation commit:

```text
docs: align phase 7 contracts with prompt suite v2.1
```

Suggested pull request title:

```text
Phase 7: align interaction contracts with Prompt Suite v2.1
```

Suggested pull request description:

```markdown
## Summary

- establish one canonical item stack plus inventory reservation boundaries
- establish one stable world-item source of truth for pickup and all drops
- publish repository-owned dirty revisions through the mutation service
- prohibit Before-event mutation reentrancy and preserve post-write failure
  semantics
- expose a read-only interaction presentation contract
- reconcile normative architecture, baseline, tests, and Phase 7 handoff

## Verification

- targeted documentation architecture test: RED against old docs, GREEN after
  reconciliation
- Windows `.\gradlew.bat clean test build --console=plain --no-daemon`
- explicit `:game:verifyPackagedResources --rerun-tasks`
- JUnit XML totals and hygiene scans recorded in the Phase 7 handoff

## Remaining

- controller re-review of the two addressed Important findings remains
  pending; no clean or approved verdict is claimed
- production gameplay/adapters, formal inventory, world entities, physics
  drops, renderer/controller/mesh-manager work, and UI remain out of scope
- Windows interactive and native macOS verification were not run for v2.1
```

No push, pull request, merge, or change to `main` was performed.
