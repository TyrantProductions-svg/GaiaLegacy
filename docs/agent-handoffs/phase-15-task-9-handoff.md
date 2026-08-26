# Phase 15 Task 9 handoff

## Completed work

Task 9 adds the immutable `ChunkMeshBudget` and upgrades `ChunkMeshManager` to
bounded `32 accepted / 2 active` CPU mesh scheduling plus owner-thread
`2 uploads / 4 destructions` per outer frame pump. Capacity includes every
nonterminal CPU/GPU handoff state, and callback reentrancy shares rather than
resets the current frame allowance.

## Unfinished work

Task 9 is complete and independently reviewed. Task 10 is not authorized by
this handoff and must not be started automatically.

## Core architecture decisions

- `ChunkRepository` remains the sole resident Chunk authority.
- Workers receive detached `ChunkMeshInput`, produce detached
  `ChunkMeshData`, and enqueue bounded completion/failure values only.
- Repository state/failure publication and every render-backend operation are
  owner-thread-only behind `MainThreadGuard`.
- An accepted token is retained through queue, execution, completed-undrained,
  upload-ready, and failed-upload retry state. Only terminal stale/discard,
  successful publication, or shutdown releases it.
- Normal frame allowances belong to the outermost pump invocation. Nested
  upload/release callbacks consume the same counters, which are decremented
  before the callback.
- Shutdown drains current GPU ownership outside normal frame limits and
  aggregates cleanup failures.
- Existing Renderer/ChunkRenderObject coordinate and GL ownership semantics
  remain unchanged; Task 10 and Task 11 were not started.

## Modified Task 9 files

- `engine/.../voxel/ChunkMeshBudget.java`;
- `engine/.../voxel/ChunkMeshManager.java`;
- `engine/.../voxel/ChunkMeshStreamingBudgetTest.java`;
- the existing mesh lifecycle structure test sentinel;
- `game/.../world/streaming/ChunkGpuOwnershipTest.java`;
- Phase 15 active design/plan Task 9 sections;
- Task 9 report and this handoff.

## Test commands and results

- Task 9 focused: 80/80 GREEN.
- Engine voxel + renderer proportional: 669/669 GREEN.
- Affected game streaming + metrics proportional: 30/30 GREEN.
- Distinct proportional total: 699 GREEN.
- `git diff --check`: PASS with line-ending warnings only.
- Cumulative tracked Phase 15 `git diff --stat`: 65 files, 7,495 insertions,
  815 deletions; accepted Tasks 1-8 are included and untracked files are not.
- Supplemental broad game architecture run: 115/116 passed; the single
  failure is the pre-existing test mismatch that classifies Task4
  `org.lwjgl.system` durable-file calls as OpenGL. Task 9 production introduces
  no such import or call.
- Interactive `:game` and macOS smoke were not run.

## Independent review

Initial result: 0 Critical / 1 Important / 1 Minor. Both findings were closed
with adversarial REDs, shared reentrant pump budgets, and plan updates.

Final result: **0 Critical / 0 Important / 0 Minor — READY**.

## Known risks and interfaces the next task must not break

- Preserve exact `32/2/2/4` defaults and accepted-token ownership through all
  completed-undrained and retained-retry states.
- Do not let nested callbacks, multiple internal drains, or worker completion
  create new per-frame upload/destruction budgets.
- Keep all repository publication and GPU/OpenGL work on the context-owning
  owner thread.
- Preserve stale revision validation before backend upload.
- Task 10 may introduce independent origin types/coordinator but must not
  change `ChunkRenderObject` coordinate semantics inside this completed task.
- Task 11 must compose the existing bounded Task 8 and Task 9 components; it
  must not create unbounded futures, result histories, or a second authority.

## Suggested integration text

- Suggested commit: `feat: bound chunk mesh and gpu frame work`
- Suggested PR title: `Phase 15: add bounded CPU mesh and GPU frame budgets`
- Suggested PR description: introduces immutable 32/2/2/4 mesh/GPU budgets,
  bounded end-to-end capacity tokens, owner-only stale-safe publication,
  reentrancy-safe outer frame allowances, bounded metrics, and full shutdown
  cleanup without starting origin/rebase work.
