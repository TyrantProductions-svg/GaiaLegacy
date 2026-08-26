# Phase 15 Task 9 report

## Result

Task 9 implements the approved bounded CPU-mesh and owner-thread GPU frame
budgets. `ChunkMeshManager` now admits at most 32 mesh jobs, exposes at most two
jobs to the executor, uploads at most two current meshes per outer frame pump,
and attempts at most four GPU destructions per outer frame pump. Task 10
origin/rebase work and Task 11 runtime composition were not started.

## Contract delivered

- `ChunkMeshBudget.productionDefaults()` is exactly `32 accepted / 2 active /
  2 uploads / 4 destructions`.
- Accepted tokens cover manager-queued, executor-active,
  completed-but-owner-undrained, awaiting-upload, and retained failed-upload
  work. Moving between those states does not free capacity.
- Repository candidates above 32 remain unclaimed, and only two accepted jobs
  can be submitted to the external executor at once.
- Workers build detached immutable CPU mesh data only. Repository publication,
  failure publication, render-backend upload, and GPU destruction stay on the
  context-owning owner thread.
- Worker-side executor refill rejection enters the bounded completion handoff;
  it cannot mutate repository state until owner drain.
- Stale results are rejected before any render-backend call and release their
  exact accepted token. Upload failure retains its token and exact retry data.
- Nested owner-thread pump calls share the outermost pump's remaining upload
  and destruction allowances. Each allowance is consumed before invoking a
  backend callback, so callback reentrancy cannot reset a frame budget.
- Normal destruction backlog is resident/installed-authority-derived. With at
  most two uploads adding replacement cleanup while four destructions drain per
  pump, it cannot grow from historical churn. Shutdown is outside the frame
  budget and releases all currently owned GPU objects with aggregate failure
  reporting.
- `ChunkMeshManager.Metrics` is an immutable owner-thread snapshot. Failure
  diagnostics are capped at the accepted-work bound.
- Existing `Renderer` and `ChunkRenderObject` main-thread ownership already
  satisfied the contract and therefore required no production change.

## Tests and verification

- Initial tests-only RED: 14 engine and 2 game missing-contract compile errors,
  all limited to the approved Task 9 budget/metrics seams.
- Independent-review RED: both reentrant upload and destruction budget tests
  failed before the fix and passed afterward.
- Final focused: 80/80 GREEN:
  - mesh streaming budgets and adversarial reentrancy: 7;
  - existing `ChunkMeshManager`: 55;
  - mesh lifecycle structure: 5;
  - render backend/object: 11;
  - game GPU ownership boundary: 2.
- Engine voxel + renderer proportional regression: 669/669 GREEN.
- Affected game streaming + metrics regression: 30/30 GREEN.
- Distinct proportional total: 699 GREEN; the focused cases are a subset and
  are not added again.
- `git diff --check`: PASS with line-ending warnings only.
- Cumulative tracked Phase 15 `git diff --stat`: 65 files, 7,495 insertions,
  815 deletions. This includes accepted Tasks 1-8 and necessarily omits
  untracked Phase 15 files, so it is not presented as a Task 9-only delta.

A supplemental broader game architecture command executed 116 cases and found
one previously existing scope mismatch:
`RenderArchitectureTest.gameSourcesDoNotCallOpenGlDirectly` treats every
`org.lwjgl` import as OpenGL, while accepted Task 4
`JdkSaveFileOperations` uses `org.lwjgl.system` for native durable-file calls.
Task 9 added no game production OpenGL/LWJGL import and did not modify that
file. The unrelated test/Task4 boundary was recorded rather than changed in
this mesh/GPU-budget task.

The interactive `:game` smoke and macOS/Apple Silicon smoke were not run and
are not claimed.

## Review

Initial independent review: **0 Critical / 1 Important / 1 Minor**. The
Important finding showed that reentrant render-backend callbacks could reset
the frame budgets; two focused adversarial REDs reproduced the issue and the
shared outer-pump budget closed it. The Minor was stale plan checkboxes.

Final independent review: **0 Critical / 0 Important / 0 Minor — READY**.

## Scope and risks

- Task 8 immutable controller/pipeline boundaries and all frozen Task 6
  WorldItem contracts are unchanged.
- No OpenGL call, GPU resource operation, or repository publication moved to a
  worker.
- No origin type, rebase coordinator, render precision policy, second Chunk
  authority, general scheduler, database, WAL, GC, or compactor was added.
- Task 11 composition must invoke the owner pump once per intended frame;
  nested callbacks are safe but do not create extra capacity.
- The working tree still contains cumulative accepted Phase 15 Tasks 1-8 and
  pre-existing `dist/`. No Git mutation was performed and `dist/` was not
  touched.

## Suggested integration text

- Suggested commit: `feat: bound chunk mesh and gpu frame work`
- Suggested PR title: `Phase 15: add bounded CPU mesh and GPU frame budgets`
- Suggested PR summary: caps mesh acceptance/active work at 32/2, preserves
  tokens through undrained and retry states, limits outer owner pumps to 2
  uploads and 4 destructions even under callback reentrancy, rejects stale work
  before GPU calls, and exposes bounded immutable metrics.
