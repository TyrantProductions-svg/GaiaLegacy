# Runtime Polish, Streaming Responsiveness, and Loading UX Plan

> **Execution rule:** Work Gate-by-Gate with systematic debugging and strict tests-first RED -> minimal GREEN. Do not stage, commit, push, create a PR, or run the full clean suite without separate controller authorization.

**Goal:** Repair repeated WorldItem pickup across rebases, bound the identified Chunk-boundary owner-thread work, make first-person presentation camera-local, and keep the existing GLFW window responsive through truthful load/save progress.

**Base:** `fix/runtime-polish-loading` from `703d1555bee083adcbbcc5aeda6a8691eb7774c2`.

**Protected architecture:** All Phase 15 authorities, stable-ID/TTL rules, one-root durable publication, Task4 physical batch limits, streaming/mesh/GPU capacity limits, owner-thread repository/GPU publication, UNKNOWN terrain, and atomic origin publication remain unchanged. This patch introduces no save-wire change, second authority, database/WAL/MVCC, background GC, second window/context, or unbounded work/history.

## Evidence and design decisions

| Gate | Root-cause evidence | Minimal design |
|---|---|---|
| A | `WorldItemPickupController` builds the eye ray from the resident-local player body while `LogicalWorldItemService.physicalSnapshots()` exposes canonical-global item positions. `WorldItemTargetingService` compares both values directly. Zero origin masks the defect. | Convert the resident-local eye to canonical-global exactly once using the current immutable `SimulationOrigin`; keep WorldItems canonical-global and keep direction vectors translation-free. |
| B | Every player Chunk change currently initiates a full atomic origin rebase. `ChunkMeshManager` prepares/copies every installed render object, with other participants also scanning resident physical state. `ChunkMeshManager` also drains the complete CPU-result backlog in one owner frame. JFR rules out GC as the principal long stall (max pause 4.37 ms) and shows generation/mesh/map work as the active load. | Add a bounded rebase threshold so normal adjacent Chunk crossings remain representable without rebasing, and bound CPU completion dequeue/validation per owner frame while retaining completed-undrained capacity accounting. Add scoped bounded phase metrics to prove the old work is removed/bounded. Do not raise any Phase 15 budget. |
| C | `Renderer` post-multiplies presentation transforms onto the canonical view (`V * P`). JOML documents `translateLocal`/`rotateLocal*` as pre-multiplication. | Build a camera-local perturbation before the canonical view (`P * V`) using explicit local/pre-multiply operations. Canonical camera/frustum/raycast state remains untouched. |
| D | `ProductLoop` invokes `session.save()` synchronously; `GameSessionLauncher.loadWorld()` reads/validates/restores before a pollable loading session exists; initial-world save is also synchronous inside loading polling. The static LOADING screen and empty SAVING screen have no operation model. | Add one bounded shell operation runner and one immutable progress snapshot. Owner frames perform owner-only prepare/publish steps; one bounded worker performs only detached read/validation/encode/file work; owner drains one retained completion and publishes exactly once. Presenter is read-only and uses determinate units only for exact totals, otherwise an indeterminate pulse. |

## External references applied

- GLFW Input Guide / Introduction / Context Guide: keep `glfwPollEvents`, window handling, and the current OpenGL context on the main thread; long post-window work must yield back to the frame loop; `glfwPostEmptyEvent` is only a wake-up mechanism.
- JOML `Matrix4f` API: ordinary `translate`/`rotateZ` post-multiply; `translateLocal`/`rotateLocalZ` pre-multiply. Camera-local visual perturbation therefore precedes the canonical view.
- Luanti `EmergeManager`: coalesce and bound requested work and use a small measured worker pool; do not respond to latency by widening every queue.
- Terasology `StateLoading`, `LoadProcess`, and `ChunkProcessingPipeline`: expose real staged progress, return regularly to rendering, and retain explicit completion/cancellation boundaries. No source code is copied.

## Gate A - canonical-global WorldItem targeting

**Expected production files**

- `game/src/main/java/com/gaia/worlditem/WorldItemPickupController.java`
- `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- optionally one small checked conversion helper adjacent to existing origin types

**Tests**

- `game/src/test/java/com/gaia/worlditem/WorldItemPickupControllerTest.java`
- `game/src/test/java/com/gaia/worlditem/WorldItemTargetingServiceTest.java`
- existing router/transaction/inventory/logical/physical regressions

- [x] Add REDs for origin `(0,0)`, positive/negative origins, post-rebase and boundary positions, yaw `0/90/180/270`, non-zero pitch, large checked coordinates, and opaque-block occlusion.
- [x] Add sequential two/three-item REDs with independent RMB edges, distinct block types, partial-stack merge, failed-then-successful retry, partial pickup same-ID remainder, delay and FROZEN_UNLOADED rejection (focused additions plus the existing transaction/router regression suite).
- [x] Assert inventory and extraction reservations clear, conservation holds, no duplicate insertion occurs, and allocator/TTL snapshots are unchanged through the focused WorldItem/inventory matrix.
- [x] Run focused RED and record the failing canonical-vs-resident coordinate assertions.
- [x] Inject a read-only current-origin supplier and convert eye position before targeting. Do not store a second position authority.
- [x] Run focused GREEN plus origin, inventory, paging, and Task 6 rollback subsets.

## Gate B - bounded owner work at Chunk crossings

**Expected production files**

- `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- `game/src/main/java/com/gaia/session/streaming/SimulationOriginCoordinator.java`
- `engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java`
- existing immutable streaming/render metrics types, only if bounded phase observations are needed

**Tests**

- `game/src/test/java/com/gaia/session/streaming/SimulationOriginCoordinatorTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`
- `engine/src/test/java/com/overlord/voxel/ChunkMeshStreamingBudgetTest.java`
- `game/src/test/java/com/gaia/world/streaming/ChunkStreamingPipelineTest.java`

- [x] Add a deterministic policy probe proving adjacent boundary crossings no longer request a full origin participant transaction.
- [x] Add east/west and north/south oscillation REDs, including negative coordinates, rapid reversal, teleport, and exact threshold crossings.
- [x] Add completion-drain REDs proving a full completed backlog is not dequeued/validated in one owner frame and that completed-undrained results still consume lane capacity.
- [x] Preserve stale/cancel/saturation/shutdown, modification persistence, boundary WorldItem, unload/reload, upload `<=2`, destruction `<=4`, mesh accepted/active `<=32/2`, load `<=32/4`, save `<=8/1`.
- [x] Implement the smallest checked rebase threshold compatible with the existing resident-local float envelope. Rebase remains one atomic prepare-all/commit-all publication when the threshold is reached.
- [x] Add a fixed completion-dequeue budget; do not change worker or GPU limits and do not drop completions.
- [ ] Record before/after deterministic work-unit counts and JFR observations on the same route/probe.

## Gate C - view-local presentation matrices

**Expected production file**

- `engine/src/main/java/com/overlord/renderer/Renderer.java`

**Tests**

- existing renderer visual camera impulse/presentation tests plus a focused matrix-basis regression
- existing movement/action presentation tests

- [x] Add deterministic vector/basis REDs for yaw `0/90/180/270`, pitch `+30/-30`, X-only, Y-only, roll-only, bob+roll, bob+action, action pitch and action yaw.
- [x] Assert the same positive lateral perturbation maps to the same view-space screen axis for every yaw and never mutates canonical camera or frustum input.
- [x] Implement explicit `P * V` composition using JOML local/pre-multiply semantics with a documented order between movement and action perturbations.
- [x] Run renderer/camera/frustum/movement/action focused GREEN.

## Gate D - responsive truthful load/save operations

**New narrow contracts**

- `OperationProgressSnapshot`: immutable operation kind, real phase/status, optional exact units/total, terminal state, cancelability, and optional bounded failure detail.
- `ProductOperationRunner` (name may be refined): one owner-created bounded operation, one worker task, one retained completion, explicit owner drain/cancel/shutdown. It is not a general scheduler.
- detached load/save preparation/result types that contain no repository, renderer, OpenGL, or mutable UI references.

**Likely production files**

- `game/src/main/java/com/gaia/shell/ProductLoop.java`
- `game/src/main/java/com/gaia/shell/ProductShellSnapshot.java`
- `game/src/main/java/com/gaia/shell/ui/ProductScreenPresenter.java`
- `game/src/main/java/com/gaia/session/GameSessionLauncher.java`
- `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- `game/src/main/java/com/gaia/save/SaveCoordinator.java`
- existing save/streaming adapters only through narrow detached prepare/publish seams

**Sequencing**

Load: owner enters LOADING and renders it -> worker reads/decodes/validates a detached candidate -> owner validates freshness and constructs/publishes canonical state once -> runtime projections/GPU preparation remain owner-owned -> READY.

Save: owner enters SAVING and renders it -> owner quiesces/admission-barriers and captures exact detached state/tickets -> worker encodes/stages/validates/publishes the existing one semantic root -> owner consumes proof/tickets and finalizes -> SAVED. Save & Quit closes only after this success.

- [x] Add RED: LOADING and SAVING each render/swap before a blocked worker completes; frame polling/render/swap continue while blocked.
- [x] Add RED: immutable progress is bounded; exact `0/total` and `total/total` are determinate; unknown total is indeterminate; no overflow/fake percentage/history.
- [x] Add RED: canceled/stale load never publishes, successful load publishes once, failed load returns safe failure state.
- [x] Add RED: failed save leaves the active session resident; Save & Quit closes only after durable success; initial New World save exposes loading progress.
- [x] Add ownership REDs: worker receives detached archive/save state, while restore, durable proof consumption, dirty acknowledgement, and session publication remain owner-only.
- [x] Add lifecycle REDs for bounded one-slot capacity, cancellation, retained completion, replay rejection, and shutdown.
- [x] Split only the necessary detached work from load/save. Reuse the existing validate-complete/publish-once restore and bounded Task4 staging seams; do not change the wire format.
- [x] Present title, real phase, status, exact units when available, otherwise deterministic indeterminate pulse. Show Cancel only while the operation reports a proven cancelable phase.
- [x] Run focused shell/session/save/Task4/Phase14/Task6C regressions.

## Verification and handoff

- [x] Run the combined proportional engine matrix for renderer presentation, ChunkRepository, and mesh streaming (203 tests).
- [x] Run the combined proportional game matrix for worlditem, inventory, interaction, streaming, origin, and shutdown (321 tests), plus the post-review expanded shell/session/save/Task4/Phase14 matrix (957 tests).
- [x] Run `git diff --check`; inspect status and ensure pre-existing `dist/` remains untouched/untracked and remove workspace-local verification caches.
- [ ] Run Windows manual acceptance for ten pickups, cardinal/rebased boundary pickup, same-route boundary travel, 360-degree camera behavior, responsive load/focus/resize, and save/restart exactness.
- [x] Request a fresh independent review for coordinates/conservation, bounded owner work, matrix semantics, async lifecycle, shutdown, authorities, truthful progress, worker ownership, and Phase 15 persistence.
- [x] Resolve Critical/Important findings tests-first and record Minor findings (final review: 0 Critical / 0 Important / 0 Minor).
- [x] Create `docs/agent-handoffs/runtime-polish-loading-report.md` with branch/base, root causes, references, files, exact test totals, JFR/work-unit observations, manual evidence, review findings, and remaining issues.
- [x] STOP and ask before running `clean test build`; do not enter finishing/commit/PR work without separate authorization.
