# Streaming Correctness, UNKNOWN Safety, and Progress UX Closure Plan

> **Execution rule:** This plan is documentation only until the controller separately approves implementation. Once approved, execute every gate tests-first: tests-only RED, inspect the expected failure, minimal GREEN, focused verification, and then the next gate. Do not stage, commit, push, create a PR, or run the full repository suite.

**Goal:** Eliminate the real UNKNOWN-space crash, make retained load work and current priority coherent without stale-work resurrection, prioritize near visible terrain within frozen budgets, classify streaming holes with bounded current observations, and make load/save progress monotonic and truthful.

**Design:** docs/superpowers/specs/2026-08-26-streaming-correctness-unknown-progress-design.md

**Branch/base:** fix/runtime-polish-loading at committed base 703d1555bee083adcbbcc5aeda6a8691eb7774c2, preserving all existing dirty work and dist/.

## Frozen contracts for every task

- Do not reopen or redesign the accepted WorldItem pickup repair.
- Do not change camera-local first-person presentation.
- Do not change SimulationOriginRebasePolicy thresholds.
- Do not remove or widen the bounded mesh completion drain.
- Do not change save wire format, Task4, Phase 14, or streamed-v2 root authority.
- Do not create another Chunk or WorldItem authority.
- Do not increase load 32/4, save 8/1, mesh 32/2, or GPU 2/4 limits.
- Do not turn UNKNOWN or FAILED into AIR.
- Do not move repository publication or OpenGL/GPU work to workers.
- Do not add an unbounded queue, log, priority history, diagnostic history, or completed-result drain.
- Do not mutate Git state or touch dist/.
- Do not run clean test build or another repository-wide suite without controller approval.

## Phase 0: baseline and evidence lock

**Read-only files**

- AGENTS.md
- docs/agent-handoffs/phase-15-handoff.md
- docs/architecture/infinite-world-streaming.md
- docs/testing/phase-15-infinite-world-streaming-acceptance.md
- docs/agent-handoffs/runtime-polish-loading-report.md
- every production and test file named by the design

**Steps**

1. Record git status --short and git diff --stat without modifying or staging anything.
2. Confirm the real crash path still terminates at GaiaBlockRaycastService.availableResult.
3. Record the current retained-work epoch comparison and scheduler comparator.
4. Record current meshingCandidates and FIFO scheduling behavior.
5. Record the progress snapshot sequence for one deterministic load fixture.
6. Run only the currently affected focused baseline groups if needed to distinguish pre-existing failures from the new REDs.
7. If an existing focused test is already failing before new RED changes, STOP and classify it before implementation.

No production or test change belongs to Phase 0.

## Phase 1: Gate E tests-only RED

### Task 1.1: typed query contract RED

**Test files**

- engine/src/test/java/com/overlord/physics/BlockRaycastTest.java
- game/src/test/java/com/gaia/interaction/GaiaBlockRaycastServiceTest.java
- game/src/test/java/com/gaia/interaction/PlayerBlockTargetingTest.java
- game/src/test/java/com/gaia/interaction/BlockInteractionControllerTest.java
- game/src/test/java/com/gaia/worlditem/WorldItemTargetingServiceTest.java
- game/src/test/java/com/gaia/worlditem/WorldItemPickupControllerTest.java
- game/src/test/java/com/gaia/world/streaming/UnknownSpaceBarrierTest.java

**Add REDs**

1. AVAILABLE hit maps the exact identity and coordinates.
2. AVAILABLE empty remains a real no-hit.
3. UNKNOWN at conceptual ChunkKey(17, 2) reaches the interaction owner without throwing.
4. FAILED reaches the interaction owner and is not converted to AVAILABLE empty.
5. UNKNOWN suppresses block break and clears an in-progress break session.
6. UNKNOWN suppresses placement.
7. UNKNOWN and FAILED suppress WorldItem pickup through unavailable occlusion space.
8. One thousand UNKNOWN fixed steps remain nonfatal and bounded.
9. Parallel movement/look along an UNKNOWN boundary remains stable.
10. A deterministic UNKNOWN -> AVAILABLE transition resumes block targeting and item occlusion.
11. The unavailable canonical key survives every adapter.
12. A deliberate invariant RuntimeException from the block registry or invalid input still propagates.
13. Source/structure assertions reject broad catch(Exception) or catch(Throwable) around gameplay queries.

**Focused RED command**

    .\gradlew.bat :engine:test --tests "com.overlord.physics.BlockRaycastTest" :game:test --tests "com.gaia.interaction.GaiaBlockRaycastServiceTest" --tests "com.gaia.interaction.PlayerBlockTargetingTest" --tests "com.gaia.interaction.BlockInteractionControllerTest" --tests "com.gaia.worlditem.WorldItemTargetingServiceTest" --tests "com.gaia.worlditem.WorldItemPickupControllerTest" --tests "com.gaia.world.streaming.UnknownSpaceBarrierTest" --console=plain --no-daemon

**Expected RED**

- The current Gaia adapter throws for UNKNOWN/FAILED.
- Optional-only interaction providers cannot express the assertions.
- WorldItem occlusion cannot distinguish unavailable space from an ordinary no-hit.

Record the exact failing tests. Do not modify production until these failures are observed.

### Task 1.2: minimal Gate E GREEN

**Production files**

- add engine/src/main/java/com/overlord/interaction/api/SpatialBlockRaycastService.java
- game/src/main/java/com/gaia/interaction/GaiaBlockRaycastService.java
- game/src/main/java/com/gaia/interaction/PlayerBlockTargeting.java
- game/src/main/java/com/gaia/interaction/BlockTargetProvider.java
- game/src/main/java/com/gaia/interaction/BlockInteractionController.java
- game/src/main/java/com/gaia/worlditem/WorldItemTargetingService.java
- game/src/main/java/com/gaia/worlditem/WorldItemPickupController.java
- production composition in GameSessionFactory only as needed for typed injection

**Implementation order**

1. Add the typed functional interface using existing SpatialQueryResult.
2. Change GaiaBlockRaycastService to map the existing typed BlockRaycast result without status erasure.
3. Move PlayerBlockTargeting and BlockTargetProvider to the typed result.
4. Make BlockInteractionController the block-interaction decision owner.
5. Propagate typed block occlusion through WorldItemTargetingService to WorldItemPickupController.
6. Store only the current bounded unavailable observation; add no history.
7. Keep legacy Optional APIs out of the production origin-aware path.
8. Make the smallest fixture/constructor compatibility adjustments; do not add a second raycast.

**Focused GREEN**

Run the exact Task 1.1 command. Then run the relevant existing pickup transaction, stable-ID, inventory, and UNKNOWN barrier subsets. Gate E closes only when all new and frozen pickup regressions are GREEN.

## Phase 2: Gates F and F2 tests-only RED

### Task 2.1: retained epoch and resurrection RED

**Test files**

- game/src/test/java/com/gaia/world/streaming/ChunkStreamingControllerTest.java
- game/src/test/java/com/gaia/world/streaming/ChunkStreamingPipelineTest.java
- game/src/test/java/com/gaia/world/streaming/ChunkStreamingFaultTest.java
- engine/src/test/java/com/overlord/voxel/ChunkRepositoryStreamingTest.java

**Add deterministic REDs**

1. Center A admits a blocked request.
2. Center B advances desired epoch while retaining the key in current preload.
3. The retained result remains capacity-accounted.
4. The retained old-epoch result publishes when its exact work/context/ticket/revision/source remain current.
5. A key outside current preload cannot publish.
6. Explicit leave -> cancel -> re-enter -> late old completion is rejected.
7. The re-entered admission receives a new workId and repository ticket.
8. Late old completion cannot match or consume the new context.
9. Cancel replay remains rejected.
10. Source, revision, issuer, and ticket mismatch remain fail-closed.
11. Stale-result counters distinguish legitimate retained completion from rejected canceled completion.

The leave/cancel/re-enter RED is mandatory and must fail if processLoad relies only on current key membership.

### Task 2.2: dynamic queued priority RED

**Test files**

- game/src/test/java/com/gaia/world/streaming/ChunkStreamingControllerTest.java
- game/src/test/java/com/gaia/world/streaming/ChunkStreamingPipelineTest.java

**Add REDs**

1. QUEUED requested phases are defensive immutable and bounded to 32.
2. Desired priority order contains exactly the current 121 preload keys.
3. Current simulation beats render and preload.
4. Current distance and canonical key provide deterministic tie-break.
5. Center shifts east, west, north, south, diagonal, across negative zero, and rapidly reverses.
6. A newly critical simulation key displaces a lower-priority QUEUED preload key at capacity.
7. Still-desired ACTIVE and COMPLETED work are not displaced.
8. Completed-undrained continues to consume capacity.
9. Reprioritization preserves workId, repository ticket, task identity, and one accepted token.
10. Reprioritization creates no duplicate work and loses no cancellation.
11. Accepted remains <=32 and active remains <=4 through every update.
12. Invalid/missing/duplicate ranks reject before heap mutation.
13. Repeated equivalent updates do not create retained priority history.

**Focused RED command**

    .\gradlew.bat :game:test --tests "com.gaia.world.streaming.ChunkStreamingControllerTest" --tests "com.gaia.world.streaming.ChunkStreamingPipelineTest" --tests "com.gaia.world.streaming.ChunkStreamingFaultTest" :engine:test --tests "com.overlord.voxel.ChunkRepositoryStreamingTest" --console=plain --no-daemon

**Expected RED**

- Still-desired old-epoch completion is discarded by the global epoch check.
- A canceled old result can only be proven safe after an explicit exact-context test is added.
- Observation cannot describe queued/active/completed state.
- Retained queued priority remains admission-time priority.
- At capacity, the new simulation key remains rejected behind queued preload work.

Record the exact RED evidence before production changes.

### Task 2.3: minimal F/F2 GREEN

**Production files**

- game/src/main/java/com/gaia/world/streaming/ChunkStreamingObservation.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingDecision.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingController.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingPipeline.java
- game/src/main/java/com/gaia/world/streaming/ChunkWorkScheduler.java
- production observation composition in GameSessionFactory

**Implementation order**

1. Add the immutable requested phase observation, enforcing the existing bound.
2. Add the exact 121-key desired priority order to the decision.
3. Update controller capacity selection: outside-desired cancellation, active/completed retention, queued selection/preemption, then admissions.
4. Expose exact scheduler phase snapshots without exposing scheduler/repository objects.
5. Add bounded queued heap rebuild under the existing scheduler lock.
6. Validate all ranks before mutation.
7. Rebuild queued Work values while preserving workId, ticket, expected revision, task, cancellation state, and accepted token.
8. In pipeline apply, perform cancellation, heap rebuild, then admission.
9. In processLoad, replace global-epoch-only rejection with current desired membership plus exact current context and all existing capability checks.
10. Preserve the canceled-context removal that makes leave/cancel/re-enter safe.
11. Do not alter active/completed work or any capacity constant.

**Focused GREEN**

Run the exact Task 2 RED command. Also run existing queue saturation, cancellation, shutdown, stale save, corrupt load, and repository ticket suites.

Gate F/F2 closes only if the explicit resurrection RED and all 32/4 accounting tests are GREEN.

## Phase 3: Gates G and G2 tests-only RED

### Task 3.1: bounded classification observation RED

**Test files**

- game/src/test/java/com/gaia/session/ChunkStreamingSessionIntegrationTest.java
- game/src/test/java/com/gaia/world/streaming/ChunkStreamingPerformanceMeasurementTest.java
- engine/src/test/java/com/overlord/voxel/ChunkMeshStreamingBudgetTest.java

**Add REDs**

1. Observation distinguishes non-resident, queued, active, completed-undrained, resident-unmeshed, mesh queued/active/completed, awaiting upload, installed, and FAILED.
2. Only the 16 highest-priority gaps are retained.
3. The source enumeration is bounded to the 121 desired keys.
4. Snapshot is defensive immutable and contains no repository, executor, callback, or mutable collection.
5. Raw frame/player/origin/epoch/rebase/GPU facts can classify movement-blocked-by-UNKNOWN separately from an owner-frame stall.
6. No observation method mutates repository, pipeline, or mesh state.

### Task 3.2: ordered mesh priority RED

**Test files**

- engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java
- engine/src/test/java/com/overlord/voxel/ChunkMeshStreamingBudgetTest.java
- game/src/test/java/com/gaia/session/ChunkStreamingSessionIntegrationTest.java

**Add REDs**

1. Nearest resident simulation-visible Chunk is claimed first.
2. Simulation keys precede remaining render keys.
3. Preload-only keys are not scheduled by the production ordered path.
4. Canonical key resolves equal-distance ties.
5. Center shift reprioritizes not-yet-started queued mesh inputs.
6. Running and completed mesh work is not canceled merely for reprioritization.
7. Accepted <=32, active <=2, upload <=2/frame, destruction <=4/frame.
8. Completed-undrained remains capacity-accounted.
9. Stale revision remains rejected before GPU upload.
10. Engine interfaces import no game streaming type.
11. Existing no-argument scheduling remains compatible for non-streaming fixtures.

**Focused RED command**

    .\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkMeshManagerTest" --tests "com.overlord.voxel.ChunkMeshStreamingBudgetTest" :game:test --tests "com.gaia.session.ChunkStreamingSessionIntegrationTest" --tests "com.gaia.world.streaming.ChunkStreamingPerformanceMeasurementTest" --console=plain --no-daemon

**Expected RED**

- Current HashSet/Set-copy candidate order cannot satisfy deterministic near-first assertions.
- Existing FIFO queued mesh inputs do not move when center changes.
- Current metrics cannot classify the complete gap lifecycle.

### Task 3.3: ordered mesh GREEN without claim release

**Production files**

- engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java
- game/src/main/java/com/gaia/session/GameSessionFactory.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetrics.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetricsRecorder.java
- narrow immutable observation types adjacent to metrics as needed

**Implementation order**

1. Add orderedEligibleKeys overload and validate a bounded duplicate-free detached list.
2. Rebuild only the manager's not-yet-started queued deque under its existing lifecycle lock.
3. Claim new candidates in supplied order while preserving all fixed budgets.
4. Production filters the controller-authored desiredPriorityOrder to current simulation and render keys; it does not recalculate policy priority and omits preload-only keys.
5. Add read-only current mesh/load status observations.
6. Assemble and cap the 16-entry gap observation.
7. Preserve the existing bounded completed CPU drain exactly.

Run Task 3.2 tests. Do not add a repository queued-claim release yet.

### Task 3.4: conditional full-capacity starvation RED

After Task 3.3 is GREEN, add one focused adversarial test:

    32 accepted mesh inputs
    -> 2 active plus lower-priority queued work
    -> a new near simulation/render Chunk becomes resident
    -> center update supplies it at highest priority
    -> verify whether it can enter before lower-priority queued far work

If ordered reheap alone allows the near input to enter within the existing lifecycle, record that evidence and do not add a release seam.

If it remains blocked solely because all accepted tokens are held by lower-priority queued inputs, record that focused RED. Only then may production add the conditional exact release below.

### Task 3.5: conditional queued mesh-claim release GREEN

**Production file allowed only after Task 3.4 RED**

- engine/src/main/java/com/overlord/voxel/ChunkRepository.java
- corresponding package-narrow support in ChunkMeshManager.java

**Required release REDs before GREEN**

1. Exact manager-owned queued input releases MESHING -> DIRTY without revision/failure change.
2. Active, completed, awaiting-upload, installed, unloaded, replaced, or newer-revision input rejects.
3. Foreign, stale, and replay release reject.
4. Revision/incarnation mismatch rejects.
5. Accepted token releases exactly once after repository release succeeds.
6. Failed release retains the input/token safely.
7. No GPU or worker call occurs.
8. Full-capacity near-starvation scenario now admits the near key within 32/2.

Implement only a package-narrow queued-only release. If globally unique revision cannot prove exact incarnation in the focused tests, STOP and return to the controller instead of adding a broader ticket framework.

Re-run the Task 3 focused command after any conditional implementation.

## Phase 4: Gate H tests-only RED

### Task 4.1: one progress authority RED

**Test files**

- game/src/test/java/com/gaia/shell/OperationProgressSnapshotTest.java
- game/src/test/java/com/gaia/shell/ProductOperationRunnerTest.java
- game/src/test/java/com/gaia/shell/ProductLoopResponsiveLoadTest.java
- game/src/test/java/com/gaia/shell/ProductLoopSaveOrderingTest.java
- game/src/test/java/com/gaia/shell/ProductLoopTest.java
- game/src/test/java/com/gaia/shell/ui/ProductScreenPresenterTest.java
- game/src/test/java/com/gaia/session/GameSessionLauncherTest.java
- game/src/test/java/com/gaia/save/session/SaveCoordinatorTest.java

**Add REDs**

1. New operation receives a new positive operationId.
2. Only ProductOperationRunner assigns operationId and sequence.
3. Worker update objects contain neither field.
4. Sequence strictly increases within an operation.
5. Stale worker context cannot publish into a new generation.
6. Phase ordinal cannot move backward.
7. Completed cannot move backward within one exact phase/total.
8. Completed cannot exceed total.
9. Terminal success/failure/cancel publishes once and rejects later updates.
10. Unknown overall total remains indeterminate through all phase changes.
11. Exact phase units display 0/25, 1/25, 24/25, and 25/25 as text without driving the main bar backward.
12. Exact overall progress is determinate only when explicitly declared exact.
13. 100% appears only for actual exact-overall success.
14. Same-operation phase change does not reset animation elapsed time.
15. Resize, focus, and worker jitter do not reset animation.
16. Repeated operation resets animation only because operationId changes.
17. Save failure and canceled load retain truthful terminal state.
18. Presenter does not allocate or publish progress authority.

**Focused RED command**

    .\gradlew.bat :game:test --tests "com.gaia.shell.OperationProgressSnapshotTest" --tests "com.gaia.shell.ProductOperationRunnerTest" --tests "com.gaia.shell.ProductLoopResponsiveLoadTest" --tests "com.gaia.shell.ProductLoopSaveOrderingTest" --tests "com.gaia.shell.ProductLoopTest" --tests "com.gaia.shell.ui.ProductScreenPresenterTest" --tests "com.gaia.session.GameSessionLauncherTest" --tests "com.gaia.save.session.SaveCoordinatorTest" --console=plain --no-daemon

**Expected RED**

- Snapshot lacks published operationId/sequence.
- Phase snapshots reset pulseStep.
- Exact phase units currently control the apparent overall fraction.
- Worker and owner update paths cannot prove one sequence authority.

Record the bounded snapshot sequence that reproduces the visual reset before production changes.

### Task 4.2: minimal Gate H GREEN

**Production files**

- game/src/main/java/com/gaia/shell/OperationProgressSnapshot.java
- game/src/main/java/com/gaia/shell/ProductOperationRunner.java
- game/src/main/java/com/gaia/shell/ProductLoop.java
- game/src/main/java/com/gaia/shell/ProductShellSnapshot.java
- game/src/main/java/com/gaia/shell/ProductShellController.java
- game/src/main/java/com/gaia/shell/ui/ProductScreenPresenter.java
- game/src/main/java/com/gaia/session/GameSessionLauncher.java
- game/src/main/java/com/gaia/save/session/SaveCoordinator.java
- other existing operation call sites only if needed to report phase facts

**Implementation order**

1. Separate worker-reported OperationProgressUpdate from published OperationProgressSnapshot.
2. Give ProductOperationRunner sole generation, sequence, and terminal publication ownership.
3. Validate stale generation, phase monotonicity, exact-unit monotonicity, bounds, and terminal once before publication.
4. Mark multi-phase load/save overall progress indeterminate; retain exact phase units as text.
5. Move marquee time to ProductLoop frame-time state keyed only by operationId.
6. Remove worker/snapshot pulse advancement as a publication path.
7. Keep window polling/render/swap on the main thread and existing worker work detached.
8. Preserve existing load/save publication, cancellation, failure, and shutdown ordering.
9. Do not alter save bytes or wire format.

Run the exact Gate H command and the existing Phase14/Task4/Task6C persistence subset affected by launcher/save orchestration.

## Phase 5: initial radius-2 readiness characterization

### Task 5.1: tests-only characterization

**Test files**

- game/src/test/java/com/gaia/session/ChunkStreamingSessionIntegrationTest.java
- game/src/test/java/com/gaia/session/GameSessionLauncherTest.java
- game/src/test/java/com/gaia/save/session/SessionRestoreCoordinatorTest.java

**Test**

Artificially delay one required radius-2 simulation Chunk in the production restore fixture.

Assert:

1. READY/PLAYING is not published while it is absent.
2. Player-controlled fixed steps do not begin.
3. Radius-4/radius-5 completion is not required.
4. Once the exact safety Chunk is resident and satisfies current initial mesh readiness, READY publishes once.

**Focused command**

    .\gradlew.bat :game:test --tests "com.gaia.session.ChunkStreamingSessionIntegrationTest" --tests "com.gaia.session.GameSessionLauncherTest" --tests "com.gaia.save.session.SessionRestoreCoordinatorTest" --console=plain --no-daemon

If this characterization is GREEN, make no readiness production change. If it is RED, STOP and report the exact safety defect before changing production architecture.

## Phase 6: proportional verification and acceptance

### Task 6.1: focused combined rerun

Run the exact focused Gate E, F/G, and H commands. Record exact test totals, elapsed time, and failures.

### Task 6.2: proportional affected matrix

Run only affected packages/suites:

**Engine**

- spatial queries;
- ChunkRepository streaming;
- mesh manager/budgets/lifecycle;
- renderer boundary regressions needed to freeze camera-local behavior.

**Game**

- interaction;
- WorldItem targeting/pickup and reservation/conservation;
- streaming controller/pipeline/fault/shutdown;
- session/origin/rebase;
- shell/progress presenter;
- launcher/save/load and affected persistence boundaries.

Do not run clean test build or the full repository suite.

### Task 6.3: frozen-boundary regressions

Explicitly keep GREEN:

- repeated pickup at zero/non-zero/post-rebase origins;
- partial pickup same-ID and no reservation leak;
- camera yaw/pitch local-basis tests;
- SimulationOriginRebasePolicy threshold/teleport tests;
- bounded mesh completion drain and completed-undrained accounting;
- Task4/save wire compatibility and WorldItem authority tests;
- load 32/4, save 8/1, mesh 32/2, GPU 2/4 assertions.

### Task 6.4: deterministic before/after observations

Capture the same bounded route/probe before and after:

- maximum owner frame and p95/p99 if the existing harness exposes them cheaply;
- load accepted/queued/active/completed;
- canceled and stale-result counts;
- missing simulation count;
- mesh accepted/queued/active/completed/awaiting-upload;
- rebase count;
- current gap classifications.

Required conclusion: no doomed retained-work churn, no current simulation starvation behind queued preload work, no unbounded owner drain, and no fabricated progress.

### Task 6.5: real Windows acceptance

Launch only after automated focused/proportional GREEN:

    .\gradlew.bat :game:run --console=plain --no-daemon

Perform:

1. UNKNOWN boundary look/break/place until terrain becomes available.
2. Near-first travel toward missing terrain.
3. Rapid east/west boundary reversal.
4. Load a real existing streamed-v2 world.
5. Resize, drag, Alt+Tab, and restore focus during load.

Record only observed facts. If the game requires user interaction that cannot be reliably automated, leave it open and report the exact manual steps still required rather than claiming success.

After the run, verify no residual Java/Gradle process remains.

### Task 6.6: independent review

Request a fresh independent review of the completed diff, specifically for:

- typed UNKNOWN/FAILED propagation;
- FAILED not becoming AIR;
- leave/cancel/re-enter resurrection;
- exact context/ticket/revision/source checks;
- heap rebuild under the existing lock;
- preservation of workId/ticket/token;
- all fixed capacity limits;
- conditional mesh release proof and scope, if present;
- engine/game dependency direction;
- one progress sequence/generation authority;
- stale async update races;
- save/pickup/camera/rebase/mesh-drain frozen contracts;
- shutdown and zero leaks.

Resolve every Critical and Important finding tests-first. Re-run only the proportional affected groups. Target 0 Critical / 0 Important, preferably 0 Minor.

### Task 6.7: report and STOP

Update docs/agent-handoffs/runtime-polish-loading-report.md by appending a second closure section. Preserve the first-pass evidence.

Include:

- real crash reproduction;
- E/F/F2/G/H root causes;
- exact production changes;
- whether conditional mesh claim release was required;
- test commands and exact totals;
- before/after observations;
- initial readiness characterization result;
- real manual result;
- independent review findings;
- remaining risks;
- whether a full suite is recommended.

Run:

    git diff --check
    git status --short
    git diff --stat

Confirm dist/ remains untouched and no Git mutation occurred. Then STOP. Do not launch the full suite, stage, commit, push, PR, merge, tag, release, or begin another phase.

## Documentation self-review gate

Before implementation approval, inspect this plan and its design against these questions:

1. Does any new type become a second Chunk, WorldItem, raycast, mesh, or progress authority?
2. Can membership-only validation resurrect a canceled request after re-entry?
3. Is every queued priority change followed by heap/deque rebuild under the existing lock?
4. Can reprioritization alter workId, capability, token count, active work, or completed work?
5. Can any legal path exceed 32/4, 8/1, 32/2, or 2/4?
6. Can a worker assign operationId, sequence, terminal state, or animation time?
7. Can phase-local counts masquerade as exact overall percentage?
8. Is queued mesh release conditional on an observed focused starvation RED?
9. If release exists, is it exact queued-only and revision/incarnation-bound?
10. Is radius-2 readiness still characterization-only?
11. Are pickup, camera-local presentation, rebase threshold, bounded mesh drain, persistence, and authorities frozen?
12. Is every final claim backed by focused/proportional/manual evidence rather than assumption?

Any failed answer is a STOP condition before production implementation.

**Self-review completed for this revision:** PASS on all twelve questions. The plan assigns no second authority, includes the mandatory leave/cancel/re-enter RED, rebuilds queues under existing locks, preserves every fixed bound, gives progress publication order only to ProductOperationRunner, makes mesh release conditional, and keeps radius-2 readiness characterization-only.

## Execution status (2026-08-26)

- [x] Gate E tests-only RED and minimal typed `AVAILABLE`/`UNKNOWN`/`FAILED` GREEN.
- [x] Gate F tests-only RED and current-work identity/capability GREEN.
- [x] Gate F2 leave -> cancel -> re-enter -> late-completion RED and GREEN.
- [x] Queued reprioritization validates first and rebuilds the existing heap under
  its lock without changing workId, ticket, token, active, or completed work.
- [x] Gate G ordered mesh scheduling RED and GREEN.
- [x] Full 32-token starvation RED proved reordering insufficient; one exact
  queued-only key/revision-bound release was added and verified single-use.
- [x] Gate G2 immutable current gap observations capped at 16, including
  resident simulation mesh gaps.
- [x] Gate H runner/snapshot RED for phase ordinal, monotonic exact units,
  owner-only terminal publication, and worker identity isolation.
- [x] Gate H owner-side load/save publication GREEN; animation is separate
  presentation state keyed by operationId.
- [x] New-world cancel RED prevents progress resurrection after leaving LOADING.
- [x] Initial radius-2 readiness characterization GREEN; no production change.
- [x] Gate H final focused: 99/99 GREEN.
- [x] Engine proportional: 86/86 GREEN.
- [x] Game proportional: 114/114 GREEN.
- [x] Workspace-local TEMP/TMP used and cleaned after every Gradle invocation.
- [x] Full repository suite deliberately not run without controller approval.
- [x] Final independent re-review: READY, 0 Critical / 0 Important / 0 Minor.
- [ ] Controller/manual Windows gameplay acceptance for UNKNOWN boundary UX and
  the remaining first-pass visual checklist.
