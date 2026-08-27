# Streaming Correctness, UNKNOWN Safety, and Progress UX Closure Design

**Status:** Controller-approved architecture; documentation complete pending implementation approval.

**Branch and base:** Continue only on fix/runtime-polish-loading, whose committed base is 703d1555bee083adcbbcc5aeda6a8691eb7774c2. Preserve the existing unstaged/uncommitted runtime-polish work and the untracked dist/ artifact.

**Scope:** Medium stabilization closure for typed spatial-query safety, retained streaming work, dynamic near-first load and mesh scheduling, bounded gap diagnosis, truthful operation progress, and initial-load safety characterization.

## 1. Frozen boundaries

This closure must not change any of the following accepted contracts:

- WorldItem pickup coordinates, conservation, stable IDs, TTL, allocator, and LogicalWorldItemService authority.
- First-person camera-local presentation composition.
- SimulationOriginRebasePolicy safe-envelope threshold behavior.
- The bounded top-level mesh completion drain introduced by the first runtime-polish pass.
- Save wire format, Task4 persistence/root publication, Phase 14 restore ordering, or streamed-v2 authority.
- ChunkRepository as the sole resident Chunk authority.
- LogicalWorldItemService as the sole WorldItem authority.
- UNKNOWN terrain is not AIR and movement into UNKNOWN remains blocked.
- Load/generate accepted/active <=32/4.
- Save accepted/active <=8/1.
- Mesh accepted/active <=32/2.
- GPU upload/destruction <=2/4 per owner frame.
- Completed-but-owner-undrained work continues to consume its accepted-capacity token.
- Repository publication and all OpenGL/GPU work remain on their existing owner threads.

No save-format change, second authority, second scheduler, database, WAL, MVCC, background compactor, unbounded history, worker repository mutation, or worker OpenGL is permitted.

## 2. Confirmed root causes

### 2.1 UNKNOWN crash

BlockRaycast.cast(SimulationOrigin, ...) already returns a typed SpatialQueryResult<BlockRaycastHit>. The current GaiaBlockRaycastService origin-aware adapter converts that value into the legacy Optional path and throws from availableResult() for UNKNOWN or FAILED. The exception escapes the fixed-step interaction path and terminates the product loop.

The defect is not in the Phase 6 ray traversal. It is semantic erasure in the game adapter.

### 2.2 Retained work that is guaranteed stale

ChunkStreamingController advances desiredEpoch when desired-set identity changes. Outstanding work that remains within the next preload set is retained. ChunkStreamingPipeline.processLoad() nevertheless rejects every result whose admission epoch differs from the newest global desired epoch.

Therefore a retained request can remain current by key, consume a bounded token, finish successfully, and still be discarded solely because the player moved one Chunk.

### 2.3 Admission-time priority becomes stale

The controller sorts new admissions by desired class, distance, and canonical key. ChunkWorkScheduler stores that rank in each admitted Work, and its PriorityQueue is never rebuilt when the player center changes. Retained queued work therefore keeps its old conceptual priority.

At capacity, reprioritizing only existing work is insufficient if a newly simulation-critical key has not been admitted. The policy must be able to replace lower-priority queued desired work without canceling still-desired active or completed work.

### 2.4 Mesh visibility order is unrelated to current player priority

ChunkRepository.meshingCandidates() returns a detached set without player-distance order. ChunkMeshManager.scheduleEligible() iterates that set and appends claimed inputs to a FIFO queue. A center shift does not reorder queued CPU mesh inputs. Farther resident data can therefore become visible before nearer resident data.

### 2.5 Progress restarts between phases

OperationProgressSnapshot.indeterminate() creates pulseStep=0. Each new load/save phase constructs another snapshot, so the same apparent operation bar restarts. Exact phase-local counts also drive the same bar as though they were exact overall progress. There is no published operation identity or sequence with which the UI can reject an older update.

## 3. External architectural lessons

- GLFW requires regular event processing for a visible window and restricts event processing and the current context to their owner thread. This closure must keep polling, rendering, and GPU lifecycle on the current main thread while bounding owner work. Sources: https://www.glfw.org/docs/latest/quick_guide.html and https://www.glfw.org/docs/latest/intro.
- Java PriorityQueue maintains heap order using the comparator values observed by heap operations. When conceptual priority changes, callers must explicitly remove/reinsert or rebuild the heap. Source: https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/PriorityQueue.html.
- Luanti's current emerge path uses finite workers, bounded queues, and duplicate request coalescing. The lesson applied here is to preserve useful current work and bounded capacity instead of widening all limits. Source: https://github.com/luanti-org/luanti/blob/master/src/emerge.cpp.
- Terasology's current Chunk pipeline uses a fixed worker pool and bounded priority queues, while recent releases call out deadline-throttled Chunk processing. The lesson applied here is to bound owner and worker bursts and make priority explicit. Sources: https://raw.githubusercontent.com/MovingBlocks/Terasology/develop/engine/src/main/java/org/terasology/engine/world/chunks/pipeline/ChunkProcessingPipeline.java and https://github.com/MovingBlocks/Terasology/releases.
- Microsoft progress guidance distinguishes indeterminate unknown-total work from exact determinate work and warns against apparent backward movement. Sources: https://learn.microsoft.com/en-us/windows/apps/develop/ui/controls/progress-controls and https://learn.microsoft.com/en-us/visualstudio/extensibility/ux-guidelines/notifications-and-progress-for-visual-studio.

No external source code will be copied.

## 4. Gate E: typed spatial interaction

### 4.1 Narrow engine contract

Add an engine-owned functional interface adjacent to the existing interaction API:

    public interface SpatialBlockRaycastService {
        SpatialQueryResult<BlockHitResult> query(
                Vector3fc origin,
                Vector3fc direction,
                float maximumDistance);
    }

SpatialQueryResult<BlockHitResult> retains four distinct meanings:

| Status | Result | Gameplay meaning |
|---|---|---|
| AVAILABLE | hit present | Normal target. |
| AVAILABLE | empty | The available ray contains no solid hit. |
| UNKNOWN | empty plus canonical key | No interaction this tick; unavailable space is not AIR. |
| FAILED | empty plus canonical key | No interaction; surface a bounded diagnostic. |

The existing Phase 6 BlockRaycast remains the unique shape-aware traversal implementation. GaiaBlockRaycastService maps block identity while preserving status and unavailable key. The production origin-aware path must use the typed interface; it must not call a legacy Optional adapter.

### 4.2 Typed propagation

Typed status remains intact until each gameplay owner makes its decision:

    BlockRaycast
      -> GaiaBlockRaycastService
      -> PlayerBlockTargeting
      -> BlockInteractionController

    BlockRaycast
      -> GaiaBlockRaycastService
      -> WorldItemTargetingService
      -> WorldItemPickupController

BlockTargetProvider returns SpatialQueryResult<BlockHitResult>. WorldItemTargetingService returns SpatialQueryResult<WorldItemTarget> so an unavailable block ray cannot be collapsed into an ordinary item miss.

The owners behave as follows:

- BlockInteractionController clears/cancels the current break session, performs no break or placement transaction, and retains a bounded current unavailable observation.
- WorldItemPickupController performs no extraction reservation or inventory mutation while block occlusion is UNKNOWN or FAILED.
- When the same ray becomes AVAILABLE, ordinary targeting resumes without requiring a restart or explicit retry.
- No broad exception handler is added. Invalid vectors, corrupt identity mapping, illegal repository state, and other genuine invariants continue to fail normally.

### 4.3 Diagnostics

The current unavailable observation contains only status and canonical ChunkKey. It is overwritten by the next observation and creates no history. Existing repository/pipeline diagnostics remain the source for underlying FAILED details.

## 5. Gates F and F2: coherent retained work and dynamic priority

### 5.1 Two independent publication predicates

A load result may publish only when both predicates are true:

1. **Policy-current:** its key belongs to the current desired preload set.
2. **Admission-current:** the exact current context still matches its workId, scheduler result identity, repository ticket/issuer, expected revision/source, and non-canceled state.

The publication rule is:

    current desired membership
    AND exact current admission context
    AND exact repository ticket/revision/source validation
    AND not canceled
    -> eligible for owner publication

The newest global desired epoch is an observation of desired-set identity, not by itself the lifetime of a still-desired admission. An old-epoch result may publish only when all exact-current checks above pass.

### 5.2 Leave, cancel, and re-enter cannot resurrect old work

Membership alone is intentionally insufficient. The following state sequence is mandatory:

    admission H1/P1 for key K
    -> K leaves current preload
    -> exact H1/P1 context canceled and detached from current context map
    -> K re-enters preload
    -> new admission H2/P2 issued
    -> late H1/P1 completion arrives
    -> reject because result does not match current H2/P2 context

Re-entry always receives a new workId and repository capability. The pipeline must never search for a current context by key and then accept an old result merely because the key is desired again. Cancellation, removed-context identity, and repository ticket replay checks remain authoritative even across desired-epoch changes.

### 5.3 Immutable observation and decision extensions

Extend the game-owned immutable observation with exact bounded load phases:

    enum RequestedLoadPhase { QUEUED, ACTIVE, COMPLETED }

    Map<ChunkKey, RequestedLoadPhase> requestedLoadPhases

The map is a defensive immutable copy and is bounded by the existing load accepted limit of 32. requested() remains the derived key set for compatibility. Production captures the phases from the scheduler; the controller never receives the scheduler itself.

Extend the immutable decision with:

    List<ChunkKey> desiredPriorityOrder

The list contains every current preload key exactly once, at most 121 entries, ordered by:

1. simulation before render before preload;
2. current squared distance from player Chunk;
3. canonical ChunkKey tie-break.

It is a value, not a retained priority map or second policy authority.

### 5.4 Capacity selection

On each controller update:

1. Cancel every requested key outside current preload, regardless of phase.
2. Retain still-desired ACTIVE and COMPLETED work. They remain capacity-accounted and are not killed for reprioritization.
3. Rank still-desired QUEUED work and missing unrequested candidates together using the new current order.
4. Fill the remaining accepted capacity with the highest-ranked members.
5. Cancel lower-ranked QUEUED work displaced by a newly critical candidate.
6. Admit the newly selected candidates after cancellation frees their tokens.

Completed-undrained work cannot be preempted; it remains bounded by 32 and must be owner-drained through the existing path. Active work remains bounded by 4. The policy never creates a 33rd accepted token.

### 5.5 Scheduler heap rebuild

The scheduler adds one bounded operation for queued load/generate work. Under the scheduler's existing synchronization lock it:

1. validates the bounded current rank input;
2. drains/copies at most 32 queued states;
3. recreates or rekeys their comparator value;
4. clears and rebuilds the PriorityQueue;
5. leaves workId, repository ticket, expected revision, task, cancellation state, and accepted token unchanged.

It does not reprioritize by mutating comparator-relevant state in place. It does not touch ACTIVE or COMPLETED work. It retains no priority history after the call.

### 5.6 Pipeline ordering

One owner application of a decision uses this order:

    publish current desired set/epoch observation
    -> cancel exact no-longer-selected contexts
    -> rebuild queued heap under scheduler lock
    -> admit newly selected work

Results racing with the update still pass through exact context and ticket validation. A canceled result is stale even if its key re-enters before completion is drained.

## 6. Gates G and G2: visibility priority and bounded diagnosis

### 6.1 Ordered engine-neutral mesh input

Add a narrow overload:

    int ChunkMeshManager.scheduleEligible(List<ChunkKey> orderedEligibleKeys)

The game composition filters the controller-authored desiredPriorityOrder to the current simulation and render sets. It does not independently recalculate priority, so ChunkStreamingController remains the one game policy authority. The filtered order is:

1. simulation keys by current distance and canonical tie-break;
2. remaining render keys by current distance and canonical tie-break.

Preload-only keys are not submitted merely to keep mesh workers busy. Engine code imports no game streaming type.

The manager validates the list as a bounded, duplicate-free detached value. It reorders only not-yet-started queued CPU mesh work; ACTIVE, COMPLETED, awaiting-upload, installed, failed-upload, and pending-destruction states preserve their existing lifecycle.

### 6.2 Conditional queued mesh-claim release

Reordering is implemented and tested first. No repository release seam is added merely because it may be useful.

Only if the focused full-capacity starvation RED proves that a newly resident near key cannot enter because all accepted tokens are held by lower-priority queued inputs may the implementation add a package-narrow release operation.

If required, that operation must satisfy all of these constraints:

- The manager first removes the exact input from its own queued deque under the existing lifecycle lock.
- The repository transition is allowed only from MESHING for the exact key and globally unique current revision/incarnation captured in that input.
- It changes only MESHING -> DIRTY; it does not change revision, blocks, failure, persistence state, or authority identity.
- ACTIVE, completed, awaiting-upload, installed, unloaded, replaced, or newer-revision entries reject the release.
- Replay and foreign/stale inputs fail closed.
- The manager releases exactly one accepted token only after exact release succeeds.
- The operation remains owner-thread only and performs no worker or GPU work.

The existing globally monotonic repository revision identifies the exact resident incarnation. If focused tests demonstrate that this is not sufficient, implementation must STOP rather than introduce a broader ticket framework without controller approval.

### 6.3 Bounded current-state observation

Add a current-only immutable ChunkGapObservation, capped at the 16 highest-priority gaps. Each entry contains:

- desired class and canonical key;
- availability and repository state;
- resident flag;
- load phase: none/queued/active/completed;
- mesh phase: none/queued/active/completed/awaiting-upload/installed/failed;
- whether the render object is installed.

The aggregate metrics additionally expose raw current facts needed for classification: frame delta, player canonical position/Chunk, SimulationOrigin, desired epoch, missing simulation count, work-lane counts, rebase event, and per-frame upload/destruction counts.

The observation is assembled on the owner thread by enumerating at most the 121 desired keys, sorting by the already-produced priority order, and retaining at most 16 gaps. It is not a timeline, log, repository, or authority.

No arbitrary stall threshold is encoded into canonical behavior. Tests and manual acceptance classify the evidence as process crash, owner-frame stall, movement blocked by UNKNOWN, resident data hole, mesh visibility hole, or event-loop stall.

## 7. Gate H: one progress publication authority

### 7.1 Authority model

ProductOperationRunner is the sole publication authority for load/save operation progress.

- The owner begins an operation and receives a new positive operationId/generation.
- Workers report bounded OperationProgressUpdate values that contain phase/status and optional exact phase units, but no operationId and no sequence.
- The runner validates the reporting context against the current generation and assigns the next monotonic publication sequence.
- Owner completion/cancellation/failure also passes through the runner's single terminal publication path.
- ProductLoop, ProductShellController, and the presenter may copy/read the published snapshot but cannot independently assign sequence, generation, or terminal state.

This prevents workers from becoming competing sequence authorities.

### 7.2 Published snapshot

The published immutable snapshot contains:

    operationId
    sequence
    operationKind
    phaseOrdinal
    phase label
    status label
    overall progress: exact or indeterminate
    optional exact phase units
    terminal state
    cancelable
    optional bounded detail

The runner enforces:

- operationId changes for every new operation;
- sequence strictly increases within that operation;
- phaseOrdinal never decreases;
- exact completed units never decrease within the same phase/total;
- exact values remain within [0,total];
- terminal state publishes once;
- no update follows terminal publication;
- stale contexts cannot overwrite a newer operation.

Explicit retry is not part of this closure. A future retry requiring phase regression must use a new operation generation or receive a separately reviewed model.

### 7.3 Truthful presentation

Current multi-phase load/save operations do not know an exact overall work graph at operation start. Their main bar therefore remains operation-level indeterminate from begin through terminal publication. Exact current-phase work, such as 13 / 25 CHUNKS, appears as labeled text only.

Determinate overall mode is supported only when a producer proves an exact overall total. No arbitrary phase weights, elapsed-time percentage, or estimated total is allowed. 100% appears only with actual exact-overall success.

The main-loop presentation clock is separate from worker progress:

- ProductLoop advances bounded animation elapsed time from supplied frame delta.
- It resets only when operationId changes or the operation disappears.
- Phase/status updates do not reset it.
- Resize, focus, or worker completion jitter do not reset it.
- Workers never advance animation.

The old per-snapshot pulseStep publication loop is removed or made presentation-only so it cannot create a second progress authority or allocate a new worker snapshot every frame.

## 8. Initial radius-2 readiness

This gate is characterization-only unless the RED proves a real defect.

The test delays one required radius-2 Chunk and observes the actual production restore path. Expected current behavior is that READY/PLAYING remains unpublished until the restored safety set is resident and satisfies the existing initial mesh-readiness contract.

If the test passes, production code is unchanged. If it fails, implementation stops and reports the exact contract conflict before changing readiness architecture. This closure must not wait for the entire radius-4 render or radius-5 preload set.

## 9. Failure and shutdown semantics

- UNKNOWN/FAILED spatial queries perform no gameplay transaction and do not consume reservations.
- Canceled or displaced load work cannot publish even if it later completes successfully.
- Re-entered keys receive new admission identities; old completion is stale.
- Heap rebuild validation occurs before mutation; a rejected rank input leaves the prior heap and tokens coherent.
- Mesh reprioritization never invokes GPU work and does not release active claims.
- Progress stale-generation updates are ignored/rejected before publication; terminal publication remains exactly once.
- Existing pipeline, mesh, operation-runner, session, and renderer shutdown order remains frozen and must retain zero leaked queued/active/completed work.

## 10. Expected file surface

### Engine production candidates

- engine/src/main/java/com/overlord/interaction/api/SpatialBlockRaycastService.java (new)
- engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java
- engine/src/main/java/com/overlord/voxel/ChunkRepository.java only if the conditional starvation RED requires exact queued-claim release

BlockRaycast.java should require tests but no production change unless a RED demonstrates a typed-query defect.

### Game production candidates

- game/src/main/java/com/gaia/interaction/GaiaBlockRaycastService.java
- game/src/main/java/com/gaia/interaction/PlayerBlockTargeting.java
- game/src/main/java/com/gaia/interaction/BlockTargetProvider.java
- game/src/main/java/com/gaia/interaction/BlockInteractionController.java
- game/src/main/java/com/gaia/worlditem/WorldItemTargetingService.java
- game/src/main/java/com/gaia/worlditem/WorldItemPickupController.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingObservation.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingDecision.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingController.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingPipeline.java
- game/src/main/java/com/gaia/world/streaming/ChunkWorkScheduler.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetrics.java
- game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetricsRecorder.java
- game/src/main/java/com/gaia/session/GameSessionFactory.java
- game/src/main/java/com/gaia/shell/OperationProgressSnapshot.java
- game/src/main/java/com/gaia/shell/ProductOperationRunner.java
- game/src/main/java/com/gaia/shell/ProductLoop.java
- game/src/main/java/com/gaia/shell/ProductShellSnapshot.java
- game/src/main/java/com/gaia/shell/ProductShellController.java
- game/src/main/java/com/gaia/shell/ui/ProductScreenPresenter.java
- existing launcher/save call sites only as necessary to report phase updates through the runner

No listed file is authorization for unrelated refactoring.

## 11. Acceptance invariants

The closure is correct only if all of the following hold together:

1. Typed AVAILABLE/UNKNOWN/FAILED semantics reach each interaction owner.
2. UNKNOWN and FAILED remain non-interactable and never become AIR.
3. Transient UNKNOWN cannot terminate the product loop.
4. Still-desired old-epoch work may finish, but exact canceled work cannot resurrect after leave/re-enter.
5. Queued load priority reflects the current center and the heap is explicitly rebuilt.
6. Reprioritization preserves workId, ticket, task, and accepted token.
7. Simulation-critical missing keys displace only lower-priority queued work, never active/completed work.
8. Load/save/mesh/GPU bounds remain exactly 32/4, 8/1, 32/2, and 2/4.
9. Mesh ordering is current, deterministic, engine-neutral, and excludes preload-only work.
10. Any queued mesh release is introduced only after its dedicated RED and is exact queued-only plus revision/incarnation-bound.
11. Current gaps can be classified without unbounded logs or a second authority.
12. One runner assigns progress operationId, sequence, and terminal publication.
13. Workers report facts but never assign publication identity or order.
14. The main bar neither fabricates an overall percentage nor resets on phase changes.
15. Initial radius-2 readiness remains characterization-only unless a defect is proved.
16. Pickup, camera-local presentation, rebase threshold, bounded mesh drain, persistence, and all Phase 15 authorities remain frozen.

## 12. STOP conditions

Stop and return to the controller before implementation expands into any save wire change, second repository/authority/scheduler, new persistent format, database/WAL/MVCC, unbounded priority/history, increased queue/GPU bounds, worker repository/GPU publication, UNKNOWN-as-AIR behavior, generalized transaction framework, or Phase 16 work.

## 13. Documentation self-review result

| Review concern | Result | Evidence in this design |
|---|---|---|
| Authority duplication | PASS | BlockRaycast remains the unique traversal; ChunkStreamingController authors desired priority; ChunkRepository and LogicalWorldItemService remain sole semantic authorities; ProductOperationRunner alone publishes operation progress identity/order. |
| Stale-work resurrection | PASS | Publication requires current desired membership and exact admission identity; leave/cancel/re-enter produces a new workId/capability and rejects the late old completion. |
| Heap invariants | PASS | Queued load work is validated and rebuilt under the existing scheduler lock; comparator-relevant priority is never mutated in place. |
| Bounded capacity | PASS | Priority values are bounded by 121 desired keys; scheduler work stays <=32/4; mesh/save/GPU limits remain 32/2, 8/1, and 2/4; diagnostics retain at most 16 gaps. |
| Progress-generation races | PASS | Workers report identity-free updates; the runner assigns operationId, sequence, phase validation, and terminal publication; animation time is presentation-only. |
| Conditional mesh release | PASS | No release seam is allowed until the dedicated full-capacity RED proves reordering alone insufficient; any release is queued-only and exact revision/incarnation-bound. |
| Initial readiness scope | PASS | Radius-2 is characterization-only and a RED triggers STOP rather than an assumed production change. |
| Frozen repairs and persistence | PASS | Pickup, camera-local presentation, rebase policy, bounded completion drain, save wire, authorities, and all fixed limits are explicit frozen boundaries. |
