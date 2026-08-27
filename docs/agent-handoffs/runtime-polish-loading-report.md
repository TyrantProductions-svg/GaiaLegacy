# Runtime Polish, Streaming Responsiveness, and Loading UX Report

## Scope and repository state

- Branch: `fix/runtime-polish-loading`
- Base and current committed HEAD: `703d1555bee083adcbbcc5aeda6a8691eb7774c2`
- Worktree changes are intentionally unstaged and uncommitted.
- Pre-existing untracked `dist/` was not read, changed, staged, or removed.
- No save wire-format, WorldItem TTL/stable-ID, Task4 batch, scheduler capacity,
  repository authority, or OpenGL ownership contract changed.

## Root causes and fixes

### A. Repeated WorldItem pickup

The player eye supplied by `WorldItemPickupController` was resident-local, while
the `LogicalWorldItemService` physical snapshots consumed by
`WorldItemTargetingService` remain canonical-global. Zero simulation origin
masked the mismatch. The controller now samples one immutable
`SimulationOrigin`, converts the eye to checked `GlobalPosition`, and performs
WorldItem AABB targeting in canonical doubles. Block occlusion continues through
the existing origin-aware resident raycast. WorldItem authority, stable IDs,
allocator, TTL, and inventory/extraction transactions are unchanged.

### B. Chunk-boundary frame spikes

Two owner paths amplified ordinary boundary crossings:

1. every player Chunk-key change initiated a full atomic origin rebase, including
   preparation of all installed mesh render objects and other participants;
2. `ChunkMeshManager.processMainThreadWork()` dequeued and validated the entire
   completed CPU backlog in one owner frame before applying the existing two-upload
   GPU budget.

The new checked `SimulationOriginRebasePolicy` retains an origin for up to 64
Chunks (1,024 blocks) from its anchor, within the established resident-local float
envelope, while teleport/threshold crossings still use the unchanged atomic
prepare-all/commit-all coordinator. The production mesh pump now dequeues at most
the same two-result top-level owner-frame budget as uploads. Completed-undrained
work continues to retain capacity; accepted/active/upload/destruction bounds were
not raised.

JFR before the fix recorded 322 GC pauses over 161 seconds, max 4.37 ms and p99
3.95 ms, ruling out GC as the principal severe hitch. Hot work instead included
deterministic generation, hash-map operations, mesh construction, and frustum
work. Deterministically, the pathological work changes from one full rebase at
every adjacent Chunk crossing to no rebase until the checked 64-Chunk threshold,
and from up to the retained mesh backlog dequeued in one pump to at most two.

### C. Camera-local movement presentation

Visual movement/action transforms were post-multiplied onto the canonical view
(`V * P`), so yaw could rotate presentation motion into world-axis behavior.
`Renderer` now uses JOML local/pre-multiply transforms (`translateLocal`,
`rotateLocalX/Y/Z`) to apply a presentation perturbation before the canonical view
(`P * V`). Canonical camera yaw/pitch/position, targeting, physics, and frustum
inputs remain unchanged.

### D. Responsive truthful loading and saving

Archive reading happened before a pollable loading session existed; regular save
called `session.save()` synchronously from an owner frame; and the initial new-world
save ran synchronously inside `pollLoad()`. LOADING was static and SAVING lacked a
real operation model.

The patch adds one immutable `OperationProgressSnapshot` and one single-slot,
single-worker `ProductOperationRunner`. It retains only one active operation and
one completed-undrained result. The split is:

- owner: enter and render LOADING/SAVING, freeze admissions, capture immutable
  save/load state, and prepare existing tickets;
- worker: detached archive read/validation or existing bounded atomic save target;
- owner: fresh restore construction, durable proof consumption, dirty Chunk
  acknowledgement, save revision publication, and lifecycle transition exactly
  once.

Blocked load/save tests prove that event polling, frame begin, progress rendering,
buffer swap, focus observation, and resized layout continue while the worker is
blocked. Canceled load generations cannot publish a late session. Save & Quit
closes only after durable success; failure returns to the live paused session.
Unknown totals use an indeterminate pulse; determinate bars are available only
when both exact completed and total units exist. No progress history or backend
handle is retained by the UI.

Independent review initially found two Important shutdown/staging defects. The
final implementation now stops and confirms the detached operation worker before
canceling an exact prepared save or closing its session, routes initial-save
cancellation through one owner path, and closes the wrapped session exactly once.
Save preparation also no longer captures a `ChunkSnapshot` for every WorldItem
page owner: it reuses only the immutable save snapshot and already-prepared dirty
captures, leaving absent owners to the existing durable Task4 rows/fail-closed
backend validation. A legal 1,024-owner regression proves zero per-owner capture
calls.

The pre-window bootstrap path was not redesigned: doing so would require a
separate approved bootstrap task if measurement shows a material pre-drawable
stall. No second GLFW window/context was introduced.

## External design references

- GLFW event and context guides: regular visible-window event processing remains
  on the main thread and OpenGL context ownership stays explicit.
  <https://www.glfw.org/docs/latest/input_guide.html#events_processing>
  <https://www.glfw.org/docs/latest/context_guide.html>
- JOML `Matrix4f` API: ordinary transforms post-multiply while `*Local` methods
  pre-multiply, which determines the view-local composition.
  <https://joml-ci.github.io/JOML/apidocs/org/joml/Matrix4f.html>
- Luanti `EmergeManager`: bounded/coalesced requests and a small worker pool,
  applied only as an architectural lesson.
  <https://github.com/luanti-org/luanti/blob/master/src/emerge.h>
- Terasology loading and Chunk processing code: expose real staged progress and
  return regularly to rendering with bounded completion work. No code was copied.
  <https://github.com/MovingBlocks/Terasology/tree/develop/engine/src/main/java/org/terasology/engine>

## Changed production surfaces

- Engine renderer and mesh owner pump:
  `Renderer`, `ChunkMeshManager`.
- Pickup/origin composition:
  `WorldItemPickupController`, `WorldItemTargetingService`,
  `GameSessionFactory`, `SimulationOriginRebasePolicy`.
- Product operation model and presentation:
  `OperationProgressSnapshot`, `ProductOperationRunner`, `ProductLoop`,
  `ProductShellSnapshot`, `ProductShellController`, `ProductScreenPresenter`.
- Detached persistence lifecycle:
  `GameSession`, `GameSessionLauncher`, `SaveCoordinator`.
- Focused tests in the corresponding engine/game test packages, including blocked
  load/save, cancellation, owner/worker separation, camera basis, origin policy,
  canonical pickup, and bounded mesh completion drain.
- New source/test files:
  `SimulationOriginRebasePolicy`, `OperationProgressSnapshot`,
  `ProductOperationRunner`, and their focused tests plus
  `ProductLoopResponsiveLoadTest`.

Tracked `git diff --stat` at handoff (untracked new files and this report are not
included by Git in this statistic):

```text
20 files changed, 1656 insertions(+), 205 deletions(-)
```

## Verification

Final proportional commands used the checked-in wrapper, JDK 21 runtime with Java
17 project compatibility, a workspace-local Gradle home, and a workspace-local
temporary directory:

- Engine renderer/mesh/ChunkRepository matrix: **203/203 GREEN**.
- Game WorldItem/inventory/streaming/origin/shutdown matrix: **321/321 GREEN**.
- Focused post-repair load/launcher/coordinator matrix: **30/30 GREEN**.
- Expanded game shell/session/save/Task4/Phase14 matrix: **957/957 GREEN**
  (0 failures, 0 errors, 0 skipped; 42m35s).
- `git diff --check`: PASS (line-ending conversion warnings only).

The 203-test engine matrix and 321-test game matrix remained frozen GREEN from
the final pre-review verification. The post-review 957-test affected matrix is
broader than the earlier 426-test save subset and includes the real Phase14
migration/fault boundary. The repository-wide `clean test build` was deliberately
not run because the controller requires separate approval before that roughly
two-hour full suite.

Final independent review: **0 Critical / 0 Important / 0 Minor**. The reviewer
confirmed both initial Important findings are closed and found no new authority,
ticket, worker-ownership, memory-bound, or persistence-root regression.

## Runtime and manual acceptance

`./gradlew.bat :game:run --console=plain --no-daemon` launched the current branch
into the real GLFW application window and remained stable for approximately one
minute without terminal exceptions. The process was then stopped and no new game
window process remained.

This environment did not expose a callable desktop mouse/keyboard automation
binding during the final run. Therefore the following controller-visible checks
remain explicitly **NOT VERIFIED**, rather than being inferred from automated
tests:

- ten independent Shift + RMB pickups across directions/boundaries/rebase;
- before/after visible hitch comparison on the same traversal route;
- north/east/south/west and continuous-rotation bob observation;
- moving/resizing/Alt+Tab during a real large streamed-world load and save;
- real modify -> Save -> Save & Quit -> relaunch exact-state round trip.

Recommended manual command:

```powershell
.\gradlew.bat :game:run --console=plain --no-daemon
```

## Remaining risks and scope decisions

- The principal remaining acceptance gap is the manual gameplay list above.
- Load/save phase reporting is truthful but deliberately coarse when the existing
  backend exposes no exact denominator; it uses indeterminate presentation rather
  than fabricated percentages.
- Pre-window cold-start work is unchanged and should only be revisited with fresh
  measurements and separate approval if a single-window early-bootstrap design is
  justified.
- Workspace-local `.gradle-user-home/` and `.test-tmp/` verification artifacts
  were removed after testing. `dist/` remains untouched.

## Suggested integration text (not executed)

- Commit: `fix(runtime): improve pickup, streaming responsiveness, and loading UX`
- Pull request: `fix(runtime): polish interaction, chunk streaming, and loading UX`
- No staging, commit, push, PR, merge, tag, or release action was performed.

## Streaming correctness and progress-authority closure (second pass)

### Approved scope and root causes

This pass implemented only the approved Gates E through H plus the radius-2
characterization. It did not change save bytes, Task4/WorldItem/Chunk authority,
pickup semantics, camera-local presentation, the 64-Chunk rebase threshold, or
the fixed load 32/4, save 8/1, mesh 32/2, and GPU upload/destruction 2/4 limits.

- Gate E: interaction call sites collapsed not-yet-resident space into ordinary
  empty results. The new narrow engine spatial query preserves `AVAILABLE`,
  `UNKNOWN`, and `FAILED` through the game targeting layer. A hit whose resident
  authority disappears between query and observation remains `UNKNOWN`; it is
  never published as AIR.
- Gates F/F2: desired-set membership alone could retain an old-epoch request, so
  leave -> cancel -> re-enter could let late work resurrect. Current request
  capability/work identity is now distinct from desired membership. Still-desired
  old-epoch work may survive, while canceled/re-entered work must match the new
  current identity. Queued priority changes are validated completely, then the
  existing heap is rebuilt under its existing lock without replacing workId,
  ticket, or capacity token.
- Gates G/G2: mesh candidates previously followed repository order rather than
  current controller priority. Ordered scheduling now consumes the immutable
  desired priority list. A full 32-token starvation RED proved reordering alone
  was insufficient because a newly near resident key could not enter a queue
  already filled by far work. The added release is therefore narrowly limited to
  one not-yet-started queued claim, exact key/revision/incarnation bound, and
  single-use; active work and all fixed capacities are unchanged. Current gap
  diagnostics are immutable, capped at 16, and include resident simulation
  chunks that still lack renderable mesh state.
- Gate H: worker completion, phase changes, and animation pulses competed as
  progress publishers. `ProductOperationRunner` is now the sole operationId,
  sequence, phase-order, and terminal-state authority. Workers report only
  identity-free phase facts. Detached completion remains RUNNING until owner-side
  load/save publication finishes; only the owner then publishes SUCCESS/FAILED
  once and releases the completed-undrained capacity token. Exact phase units are
  display text, while the main bar remains indeterminate unless exact overall
  progress is explicitly supplied. Animation time is separate shell presentation
  state keyed by operationId and does not consume progress sequence numbers.

The first real RED matrix reproduced the UNKNOWN/FAILED collapse, old-work
resurrection after leave/cancel/re-enter, stale heap order, mesh starvation, and
duplicate progress-publication authority before production changes. A later
focused RED also proved that canceling a new-world operation could republish its
old progress into MAIN_MENU; the create generation is now terminalized and
released before leaving LOADING.

### Characterization and verification

- Initial radius-2 production restore characterization: GREEN. READY remains
  withheld until the exact safety neighborhood is resident/currently mesh-ready;
  radius 4/5 completion is not required. No readiness production change was made.
- Gate H final focused matrix: **99/99 GREEN**.
- Engine proportional repository/mesh/renderer matrix: **86/86 GREEN**.
- Game proportional interaction/WorldItem/streaming/session matrix:
  **114/114 GREEN**.
- Exact mesh queued-release and simulation-gap strengthening subset: GREEN.
- `git diff --check`: PASS; only existing LF-to-CRLF conversion warnings were
  reported.
- Every Gradle command used a unique writable workspace-local `TEMP`/`TMP`,
  restored the parent environment, and removed that temporary directory.
- The repository-wide suite was not run, per controller scope. The real GLFW
  acceptance list in the first-pass report was not rerun for this closure and
  remains **NOT VERIFIED** here.

### Scope and handoff state

- No full-directory history, second authority, database, WAL, or generic
  transaction framework was introduced.
- `dist/` remains pre-existing and untouched.
- No Git mutation was performed: no stage, commit, push, reset, stash, rebase,
  clean, merge, tag, or release.
- The final independent re-review is **READY: 0 Critical / 0 Important / 0
  Minor**. It verified that owner-only restore/create failures terminalize and
  release the runner token, terminal progress rejects further owner updates,
  and queued mesh release is owner-thread-only, claim-identity-bound,
  queued-only, replay-safe, and rejected after dispatch becomes active.

### Final review-repair verification

The first independent review found two Important issues and one Minor issue:
owner-only restore failure could retain the sole operation token; a queued mesh
release was not claim-unique across same-revision reclaim; and `ownerUpdate`
reported success after terminal publication. Focused REDs reproduced all three.
The minimal repairs now install an owner failure completion before terminal
publication, release/clear the exact active load generation, return the actual
progress publication result, and bind each repository-produced mesh claim to a
monotonic single-use claim ID plus explicit queued/active state.

Final review-repair focused matrix: **69/69 GREEN**:

- `ChunkRepositoryTest.queuedMeshingClaimReleaseIsExactRevisionBoundAndSingleUse`:
  1/1 GREEN.
- `ChunkMeshStreamingBudgetTest`: 11/11 GREEN.
- `ProductOperationRunnerTest`: 8/8 GREEN.
- `ProductLoopResponsiveLoadTest`: 3/3 GREEN.
- `ProductLoopTest`: 46/46 GREEN.

The final `git diff --check` passed with line-ending conversion warnings only.
No workspace-local Gradle TEMP directory remained. The repository-wide suite
and the manual GLFW acceptance checklist were not run in this closure.

## Overnight full-suite closure

### Initial full-suite failures and classification

The first known repository-wide run reached `:engine:test`,
`:game:compileJava`, and `:game:compileTestJava`, then exposed two failures in
`:game:test`.

1. `GameBootstrapSaveCompositionTest`
   `.realCompositionWritesAndReopensStreamedV2WithoutLossyLegacyRewrite()`
   expected `SUCCESS` but observed `WRITE_FAILED`. This was a test fixture
   incompatibility with the already-approved detached-save architecture, not a
   production persistence defect. Production captures the complete immutable
   canonical resident Chunk snapshot before worker execution; the fixture had
   created a WorldItem in a distant owner Chunk absent from its save snapshot
   and depended on the obsolete live `captureWorldItemChunk` callback. The
   fixture now includes that owner in its immutable `ChunkRepositorySnapshot`.
   All exact streamed-v2, legacy-byte preservation, world-tick, stable-ID,
   expiry, page-completeness, corruption, and fail-closed assertions remain
   unchanged.
2. `GameLoopStructureTest`
   `.productLoopDelegatesSessionLifecycleWithoutOwningWorldLoadOrFixedTime()`
   expected the obsolete source spelling `session.pollLoad()`. The approved
   responsive lifecycle delegates through `session.pollLoadResponsive()`.
   Production still owns product-level orchestration only. The stale spelling
   assertion was updated and the test was strengthened to reject direct
   `ChunkRepository`, `SessionPersistenceClock`, `StreamedChunkStore`, and
   `StreamedWorldItemPageBackend` ownership in `ProductLoop`.

No production file changed for either initial failure. No test was removed,
disabled, relaxed to `assertDoesNotThrow`, or changed to accept lossy/corrupt
save output.

### Later full-suite failures

The second additional full-suite attempt found six test failures:

- Five `InstalledAudioRuntimeAuditTest` methods failed because Windows `cmd /c`
  parsed an absolute wrapper path containing the repository's space as
  `'D:\Game'`. The test harness now invokes `gradlew.bat` relative to its exact
  `ProcessBuilder` working directory. The installed-runtime audit commands and
  assertions are unchanged; Unix wrapper behavior is unchanged.
- `ChunkStreamingSessionIntegrationTest`
  `.productionMetricsPublishNonzeroFrameDeltasResidentModificationAndScalarLatencies()`
  observed zero later-frame publications after discarding the first legitimate
  admission frame. Fast work may legally complete during that frame. The test
  now accumulates the immutable metrics returned by the initial
  `advancePlaying()` call before its existing bounded loop. It cannot fabricate
  a delta, and no scheduler, capacity, publication, or production timing
  behavior changed.

### RED/GREEN evidence

- Initial focused REDs: 2/2 reproduced independently. Save composition expected
  `SUCCESS` and observed `WRITE_FAILED`; the structure test failed on the stale
  `pollLoad()` source assertion.
- Pre-full-suite focused matrix: **638/638 GREEN**, 0 skipped
  (engine mesh/repository 165; game bootstrap/ProductLoop/session/save/
  streamed-v2/Phase14/WorldItem 473).
- Proportional affected matrix: **1,544/1,544 GREEN**, 0 skipped
  (engine 501; game 1,043).
- Later focused RED: `InstalledAudioRuntimeAuditTest` **5/5 RED** with the exact
  Windows path parsing error. The streaming metrics failure was supplied by the
  repository-wide run and its exact focused rerun was GREEN, confirming the
  observation-frame race rather than a deterministic production failure.
- Later adjacent focused matrix: **53/53 GREEN**, 0 skipped, covering the audio
  audit, streaming-session integration, streaming pipeline/controller, and
  session factory.
- Two fresh independent reviews both reported **0 Critical / 0 Important / 0
  Minor — READY**. They found no weakened persistence assertion, ProductLoop
  authority expansion, claim-ID regression, thread-ownership change, hidden
  owner-thread IO, token-release regression, or progress-lifecycle regression.

### Repository-wide attempts

Three additional attempts were consumed, including one environment/tool abort:

1. Attempt 1 was externally aborted after **1,330/1,330 engine tests GREEN** and
   game compilation, before any game test result existed. No Gradle/test failure
   was reported. The interrupted command's exact leftover `.test-tmp-*`
   directory was removed and no Java process remained.
2. Attempt 2 completed **3,404 tests: 3,398 passed, 6 failed, 0 skipped** and
   supplied the audio-path and first-frame-metrics failures described above.
3. Attempt 3 completed **3,431 tests: 3,430 passed, 0 failed, 1 skipped** across
   335 suites. Module totals were engine 1,330/1,330, game 2,074/2,074, and
   tools 26 passed plus 1 skipped. The one skip was the platform-capability
   characterization
   `UiAssetGeneratorTest.rejectsSymlinkedParentThatEscapesTheRootWhenSupported`.
   Gradle reported `BUILD SUCCESSFUL` in **43m47s** with 31 actionable tasks (30
   executed, 1 up-to-date). The installed OpenAL audit also reported
   `AUDIO_INSTALL_AUDIT_OK` for LWJGL OpenAL 3.3.3 and its Windows natives.

Compiler notes, recorded separately from test results:

- deprecated API use in `Phase14SaveMigrator.java`;
- deprecated API use in `Phase14SaveMigrationTest.java`;
- unchecked/unsafe operations in
  `ChunkStreamingPerformanceMeasurementTest.java`;
- deprecated API use in `WorldItemPerformanceFixture.java`.

### Final safety state

- `git diff --check`: PASS; only existing LF-to-CRLF conversion warnings.
- Tracked diff at closure: **49 files changed, 3,735 insertions, 379
  deletions**. Untracked approved files are not included in this Git statistic.
- No stage, commit, push, reset, restore, stash, rebase, clean, PR, merge, tag,
  or release action was performed.
- Every Gradle command used a unique workspace-local `TEMP`/`TMP`; the parent
  environment was restored and final `.test-tmp-*` count was zero.
- Final Java/Gradle process count was zero.
- The pre-existing untracked
  `dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip` remained untouched (size
  4,401,406 bytes; last-write time 2026-08-08 01:08:21).
- No save wire-format, WorldItem stable-ID/TTL, Task4 authority, Chunk/WorldItem
  authority, fixed capacity/GPU limit, UNKNOWN/FAILED semantics, pickup,
  camera-local presentation, or OpenGL ownership contract changed during this
  full-suite closure.
