# Phase 07 Handoff

## Completed work

- Built Phase 7 on `feat/interaction-api-contracts` from `origin/main` at
  `ed707ec`; the reviewed implementation and architecture-guard commits end
  at `efd743f` before this handoff documentation commit.
- Added game-neutral interaction values and contracts for entity references,
  gameplay actions and contexts, immutable `ResourceLocation` block hits,
  read-only block raycasts, and synchronous gameplay world mutation.
- Added `DefaultWorldMutationService` with explicit constructor dependencies,
  main-thread validation, optimistic conflict detection, synchronous
  cancellation, one world write, dirty-chunk calculation, and ordered
  post-change publication.
- Added immutable before-change, changed, and chunk-dirty events. A failure
  before the write reports `mutationApplied() == false`; post-write dispatch
  attempts both events and reports `mutationApplied() == true`, preserving
  the first cause and suppressing a distinct second failure.
- Added read-only body inventory contracts with the stable `LEFT_HAND`,
  `RIGHT_HAND`, and `MOUTH` slot order. Snapshot reads are separated from the
  `InventoryService` mutation boundary, and the UI ViewModel cannot expose
  that service.
- Enabled Gradle `java-test-fixtures` in `engine`, made the fixtures available
  to game tests, and added configurable interaction/inventory fakes and stubs.
  The fixtures are absent from the production runtime classpath.
- Added contract, transaction, dirty-propagation, fixture-consumption, and
  architecture tests. The architecture guard rejects direct `setBlock` call
  patterns in game production sources outside
  `game/src/main/java/com/gaia/world`.
- Reconciled the approved architecture document with the final public names
  and behavior and updated the current architecture baseline for Phase 7.

## Unfinished work

- No Gaia `BlockWorldAccess` or `BlockRaycastService` adapter is implemented
  or wired into `GameBootstrap` or `GameContext`.
- Breaking, placement, item use, pickup, dropping, inventory storage and
  rules, persistence, controller behavior, and inventory UI remain
  unimplemented.
- Windows visual and behavioral smoke-test results remain unverified. A game
  session was launched and its process exited after manual window closure,
  but the controller captured neither the Gradle console exit code nor an
  independent visual check.
- Native macOS `./gradlew clean test build` and interactive
  `./gradlew :game` were not run because no macOS environment was used.

## Core architecture decisions

- `WorldMutationService.changeBlock` is the only gameplay block-write
  boundary. `World.setBlock` remains available for low-level storage and world
  generation, and a future Gaia `BlockWorldAccess` adapter must delegate to
  it rather than introduce another block store.
- A successful mutation preserves this synchronous order on the main
  fixed-update thread: assert ownership, validate the request, read and
  compare the current block, publish before-change, write once, calculate
  dirty chunks, publish changed, publish one complete chunk-dirty event, then
  return `APPLIED`.
- The cancellable before-change event uses an injected synchronous
  `BlockChangeEventPublisher`, not the queued singleton `EventBus`. Rejected
  requests publish no success events and perform no write.
- `BlockChangeRequest.expectedBlock` provides optimistic conflict detection.
  Expected domain rejections are returned as statuses; invalid references,
  wrong-thread calls, and dispatch failures remain explicit programming or
  delivery errors.
- `BlockRaycastService` is a read-only, data-driven boundary whose hits expose
  `ResourceLocation`. A future Gaia adapter must reuse the Phase 6
  shape-aware `BlockRaycast` algorithm and translate stored byte IDs with
  `BlockRegistry`.
- `InventoryView`, `ItemStackView`, and `BodyInventoryViewModel` expose
  read-only snapshots. Inventory mutation remains behind `InventoryService`,
  and UI code emits intent rather than receiving a mutable service.
- Interaction and inventory APIs remain engine-owned and game-neutral.
  Future adapters are game-owned. Shared Gradle and documentation changes
  remain shared boundaries requiring both owners' awareness.
- Phase 7 is contract infrastructure only. It deliberately does not add
  gameplay behavior, inventory rules, UI rendering, OpenGL calls, GPU
  resources, or new `ServiceLocator` use.

## Modified files

The final Phase 7 branch changes these exact 43 tracked paths relative to
`origin/main`:

**Architecture, plan, and handoff**

- `docs/agent-handoffs/phase-07-handoff.md`
- `docs/architecture/current-baseline.md`
- `docs/architecture/interaction-inventory-contract.md`
- `docs/superpowers/plans/2026-07-24-phase-7-interaction-inventory-contracts.md`

**Build configuration**

- `engine/build.gradle`
- `game/build.gradle`

**Engine production interaction API and implementation**

- `engine/src/main/java/com/overlord/interaction/BlockWorldAccess.java`
- `engine/src/main/java/com/overlord/interaction/DefaultWorldMutationService.java`
- `engine/src/main/java/com/overlord/interaction/api/BeforeBlockChangedEvent.java`
- `engine/src/main/java/com/overlord/interaction/api/BlockChangeDecision.java`
- `engine/src/main/java/com/overlord/interaction/api/BlockChangeDispatchException.java`
- `engine/src/main/java/com/overlord/interaction/api/BlockChangeEventPublisher.java`
- `engine/src/main/java/com/overlord/interaction/api/BlockChangeRequest.java`
- `engine/src/main/java/com/overlord/interaction/api/BlockChangeResult.java`
- `engine/src/main/java/com/overlord/interaction/api/BlockChangedEvent.java`
- `engine/src/main/java/com/overlord/interaction/api/BlockHitResult.java`
- `engine/src/main/java/com/overlord/interaction/api/BlockRaycastService.java`
- `engine/src/main/java/com/overlord/interaction/api/ChunkDirtyEvent.java`
- `engine/src/main/java/com/overlord/interaction/api/EntityRef.java`
- `engine/src/main/java/com/overlord/interaction/api/InteractionAction.java`
- `engine/src/main/java/com/overlord/interaction/api/InteractionContext.java`
- `engine/src/main/java/com/overlord/interaction/api/ItemUseContext.java`
- `engine/src/main/java/com/overlord/interaction/api/WorldMutationService.java`

**Engine production inventory API**

- `engine/src/main/java/com/overlord/inventory/api/BodyInventoryViewModel.java`
- `engine/src/main/java/com/overlord/inventory/api/BodySlot.java`
- `engine/src/main/java/com/overlord/inventory/api/InventoryChangeRequest.java`
- `engine/src/main/java/com/overlord/inventory/api/InventoryChangeResult.java`
- `engine/src/main/java/com/overlord/inventory/api/InventoryService.java`
- `engine/src/main/java/com/overlord/inventory/api/InventoryView.java`
- `engine/src/main/java/com/overlord/inventory/api/ItemStackView.java`

**Engine tests**

- `engine/src/test/java/com/overlord/interaction/DefaultWorldMutationServiceTest.java`
- `engine/src/test/java/com/overlord/interaction/InteractionArchitectureTest.java`
- `engine/src/test/java/com/overlord/interaction/api/BlockMutationContractTest.java`
- `engine/src/test/java/com/overlord/interaction/api/InteractionContractTest.java`
- `engine/src/test/java/com/overlord/inventory/api/InventoryContractTest.java`

**Shared engine test fixtures**

- `engine/src/testFixtures/java/com/overlord/interaction/testing/FakeBlockWorldAccess.java`
- `engine/src/testFixtures/java/com/overlord/interaction/testing/RecordingBlockChangeEventPublisher.java`
- `engine/src/testFixtures/java/com/overlord/interaction/testing/StubBlockRaycastService.java`
- `engine/src/testFixtures/java/com/overlord/inventory/testing/StubBodyInventoryViewModel.java`
- `engine/src/testFixtures/java/com/overlord/inventory/testing/StubInventoryService.java`
- `engine/src/testFixtures/java/com/overlord/inventory/testing/TestInventoryView.java`
- `engine/src/testFixtures/java/com/overlord/inventory/testing/TestItemStackView.java`

**Game fixture-consumption test**

- `game/src/test/java/com/gaia/contracts/EngineContractFixtureSmokeTest.java`

Ignored `.superpowers/sdd` briefs and reports are local coordination records
and are not part of the tracked branch diff.

## Test commands and results

Earlier Phase 7 evidence:

- Pre-change Windows `.\gradlew.bat clean test build`: passed with
  16 actionable tasks.
- Task 4 focused transaction verification: 14/14 tests passed.
- Task 4 full engine verification: 382/382 tests passed.
- Task 5 Windows `.\gradlew.bat clean test build`: passed with
  18 actionable tasks; the production runtime classpath excluded test
  fixtures.
- Task 6 focused architecture and full test commands passed.

Fresh Task 7 Windows automated verification on 2026-07-24:

```powershell
git diff --check
git ls-files | Select-String -Pattern '(^|/)(bin/|build/)|\.class$'
.\gradlew.bat clean test build
```

- `git diff --check`: exit `0`, no whitespace errors. Git emitted only
  line-ending conversion warnings for the two edited Markdown files.
- The tracked generated-file query returned no matches.
- The initial restricted Gradle invocation could not download the Gradle 8.5
  distribution and failed with
  `java.net.SocketException: Permission denied: getsockopt`; this was an
  environment access failure, not a product test result.
- The identical approved rerun exited `0`: `BUILD SUCCESSFUL in 9s`;
  `18 actionable tasks: 18 executed`.
- Gradle selected `natives-windows`.
- Engine JUnit XML: 46 suites, 387 tests, 0 failures, 0 errors, 0 skipped.
- Game JUnit XML: 11 suites, 103 tests, 0 failures, 0 errors, 0 skipped.
- Total JUnit XML: 57 suites, 490 tests, 0 failures, 0 errors, 0 skipped.
- The build compiled, tested, packaged, produced distributions, and ran
  `:game:verifyPackagedResources`.

Windows interactive verification:

```powershell
.\gradlew.bat :game
```

- A visible game session was launched before Task 7, and its process exited
  after manual window closure.
- The controller did not capture the Gradle console exit code and did not
  independently verify visuals or behavior. This is a qualified launch/exit
  observation, not an unconditional pass.
- Task 7 did not launch another interactive game window.

Platform status:

- macOS `./gradlew clean test build`: **NOT RUN**.
- macOS `./gradlew :game`: **NOT RUN**.

Final Task 7 documentation and clean-build gate:

```powershell
git diff --check
git ls-files | Select-String -Pattern '(^|/)(bin/|build/)|\.class$'
.\gradlew.bat clean test build
git status --short --branch
```

- `git diff --check`: exit `0`, no whitespace errors.
- The tracked generated-file query and unfinished-marker scan returned
  no matches.
- The final clean build exited `0` and reported `BUILD SUCCESSFUL`;
  `18 actionable tasks: 18 executed`.
- The branch remained `feat/interaction-api-contracts`; before staging, its
  only worktree entries were the three Task 7 documentation paths.
- Final post-commit status and branch diff evidence are recorded in the local
  Task 7 report.

## Known risks

- The architecture guard scans raw Java source with a `.setBlock` regular
  expression. It can match comments and string literals, and it cannot detect
  indirect writes through another method or abstraction.
- No Gaia `BlockWorldAccess` or `BlockRaycastService` adapter wiring exists,
  so the new contracts are not exercised by actual gameplay.
- No production inventory implementation, inventory rules, gameplay,
  controller, persistence, or UI exists. The Phase 7 inventory types and
  fixtures establish boundaries only.
- The qualified Windows game launch did not independently establish correct
  rendering, movement, input, resize/focus behavior, or a clean Gradle exit
  code. Native macOS behavior remains untested.
- `BlockWorldAccess.setBlock` can report a failed write after the optimistic
  read; the standard service maps that race or rejection to `CONFLICT`.
  Future adapters must retain that outcome and must not publish success
  events for it.
- A post-write subscriber failure means the mutation is already applied.
  Callers must inspect `BlockChangeDispatchException.mutationApplied()` and
  must not blindly retry.

## Interfaces the next phase must not break

- Preserve `EntityRef` as a non-negative immutable ID rather than exposing
  mutable ECS `Entity`.
- Preserve `InteractionAction`, `InteractionContext`, `ItemUseContext`,
  `BlockHitResult`, and `BlockRaycastService` as game-neutral, immutable or
  read-only boundaries using `ResourceLocation` block and item identities.
- Preserve `WorldMutationService.changeBlock(BlockChangeRequest)` as the
  synchronous gameplay write boundary and preserve the complete
  `BlockChangeResult.Status` set:
  `APPLIED`, `NO_CHANGE`, `CANCELLED`, `CONFLICT`, `OUT_OF_BOUNDS`, and
  `UNKNOWN_BLOCK`.
- Preserve the exact successful transaction order:
  main-thread assertion, validation, current-block comparison,
  `BeforeBlockChangedEvent`, one write, dirty-set calculation,
  `BlockChangedEvent`, one complete `ChunkDirtyEvent`, then `APPLIED`.
- Preserve before-write cancellation and dispatch-failure behavior, both
  post-write publication attempts, and
  `BlockChangeDispatchException.mutationApplied()` retry semantics.
- Preserve explicit constructor injection of `MainThreadGuard`,
  `BlockWorldAccess`, `BlockChangeEventPublisher`, and `ChunkDirtyTracker`.
  Do not route cancellation through queued `EventBus` or expand
  `ServiceLocator`.
- Preserve `BlockWorldAccess` as the narrow future Gaia storage adapter.
  Delegate to the existing `World.setBlock`; do not create a second block
  store or bypass Phase 3 dirty/revision/mesh lifecycle behavior.
- Preserve the architecture guard outside
  `game/src/main/java/com/gaia/world`; do not add gameplay exceptions to the
  generation whitelist.
- Preserve exactly three body slots in presentation order:
  `LEFT_HAND`, `RIGHT_HAND`, `MOUTH`.
- Preserve `ItemStackView`, `InventoryView`, and
  `BodyInventoryViewModel` as read-only snapshots, and keep mutation behind
  `InventoryService.replaceSlot(InventoryChangeRequest)`. UI code must not
  receive or expose `InventoryService`.
- Preserve the `java-test-fixtures` boundary and keep fixtures off production
  runtime classpaths.
- Preserve main/fixed-update ownership for world and inventory mutations,
  Java 17 compatibility, the checked-in Gradle Wrapper, OpenGL 4.1 /
  GLSL 410 compatibility, and main/context-thread ownership of every OpenGL
  and GPU-resource operation.

## Git diff stat

The final Phase 7 branch diff relative to `origin/main` is:

```text
43 files changed, 4733 insertions(+), 7 deletions(-)
```

## Suggested commit message

Suggested overall Phase 7 commit:

```text
feat(api): define block interaction and body inventory contracts
```

Suggested Task 7 documentation commit:

```text
docs: complete phase 7 interaction handoff
```

## Suggested pull request

Title:

```text
Phase 7: define interaction and body inventory API contracts
```

Description:

```markdown
**Summary**

- define game-neutral interaction, raycast, synchronous block-mutation, and
  three-slot body-inventory contracts
- implement ordered main-thread world mutation with optimistic conflict
  detection, cancellation, dirty propagation, and explicit dispatch-failure
  semantics
- share configurable engine test fixtures with game tests while keeping them
  off production runtime classpaths
- enforce engine/game ownership, read-only UI, synchronous event, and direct
  gameplay-write boundaries with automated tests

**Verification**

- `.\gradlew.bat clean test build`
  - BUILD SUCCESSFUL; 18/18 actionable tasks executed
  - 490 tests in 57 suites; 0 failures, 0 errors, 0 skipped
  - LWJGL selected `natives-windows`
- `git diff --check`
- tracked generated-file query returned no matches

**Interactive and platform status**

- Windows `.\gradlew.bat :game` launched visibly and the process exited after
  manual closure, but no Gradle console exit code or independent visual check
  was captured; this is not an unconditional pass
- macOS clean build and interactive smoke were not run

**Scope**

- no Gaia world/raycast adapter wiring, gameplay, inventory rules,
  persistence, controller, or UI
- no OpenGL/GPU changes, copied third-party code or assets, push, or merge
```
