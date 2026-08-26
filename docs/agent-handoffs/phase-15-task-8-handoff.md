# Phase 15 Task 8 handoff

## Completed work

Task 8 supplies the bounded detached load/generate/save execution layer below
the frozen Task7 policy controller. Exact resident Chunk unload is prepared and
pinned on the owner thread, persisted together with optional WorldItem state
through one Task4 root, revalidated, then committed only after the frozen Task6D
hibernation transaction succeeds.

The two implemented lanes are load/generate `32 accepted / 4 active` and save
`8 accepted / 1 active`. Completed-but-undrained results retain their capacity
tokens. Diagnostics and all retained work metadata are hard bounded.

## Unfinished work

Task 8 is complete and independently reviewed. Task 9 is not authorized by
this handoff and must not be started automatically.

## Core architecture decisions

- `ChunkRepository` remains the sole resident Chunk authority. Its opaque
  unload ticket never crosses to a worker.
- `LogicalWorldItemService` remains the sole WorldItem semantic, stable-ID,
  allocator, TTL, and lifecycle authority.
- Task4 remains generic opaque byte/index durability. Chunk payload,
  WorldItem page/checkpoint, and required globals share one semantic root
  publication; no intermediate staged bytes are reader-visible.
- Worker freshness for WorldItem persistence is a detached atomic signal plus
  atomic epoch/tick mirrors; no worker reads owner-thread service maps. The
  combined adapter requires one canonical session input for a WorldItem commit
  and rebuilds its revision/digest/index-sequence binding from the backend's
  atomic candidate. Chunk-only unload cannot mutate session authority.
- Owner publication validates work ID, key, desired epoch, source, revision,
  exact unload ticket, and durable status before mutation.
- Persistence or hibernation failure cancels preparations and retains resident
  authority. Final-validated pinned Chunk mutation/replacement is rejected so
  exact commit is deterministic.
- Task7 immutable controller types and desired-epoch behavior are unchanged.
- No mesh/GPU scheduling, origin/rebase, database/WAL, second repository,
  background GC, or general scheduler framework was added.

## Modified Task 8 files

- engine unload types and `ChunkRepository` transaction seam;
- `LogicalWorldItemService.activeRevisionsInChunk`;
- game combined unload plan/result and `StreamedWorldItemPageBackend` adapter;
- game `ChunkWorkScheduler`, work result/diagnostic, and
  `ChunkStreamingPipeline`;
- `WorldLoader.generateDetached`;
- focused repository, WorldItem, store, pipeline, fault, and loader tests;
- Phase 15 design, active plan, Task 8 report, and this handoff.

The worktree also contains accepted Tasks1-7 and `dist/` from before Task 8.
No Git mutation was performed and `dist/` was not touched.

## Test commands and results

- Task 8 focused: 88/88 GREEN.
- Affected engine proportional: 286/286 GREEN.
- Task6D/6E + Task7 + Task8 short game matrix: 91/91 GREEN.
- Task4/Task6 codec/TTL/corruption short matrix: 66/66 GREEN.
- Frozen Task6C restart/save subset: 16/16 GREEN.
- Distinct successful proportional total: 494.
- `git diff --check`: PASS with existing line-ending warnings only.
- Four long/over-broad supplemental commands were canceled after exceeding the
  proportional budget and are not counted; see the Task 8 report.
- Cumulative tracked Phase 15 `git diff --stat`: 63 files, 7,209 insertions,
  698 deletions; untracked Phase 15 files are necessarily absent from that stat.

## Independent review

Initial review: 1 Critical / 1 Important / 1 Minor. A later sweep found one
additional Important in session-checkpoint binding. Every finding has a focused
regression and production fix. Final independent result:
**0 Critical / 0 Important / 0 Minor — READY**.

## Known risks and interfaces the next task must not break

- Preserve the exact unload issuer/owner/entry/revision/state/failure binding
  and final-validation seal.
- Preserve one visible root for Chunk + WorldItem + required global state.
- Preserve capacity-token ownership through completed-but-undrained state and
  bounded diagnostics/results.
- Keep workers detached from repository, ticket, logical/physical authority,
  and GPU state.
- Task 9 may consume immutable Task8 results but must not move GL/GPU work off
  the context-owning main thread.
- Do not infer Task 10 origin/rebase authority from `GlobalPosition`.
- Task11 production composition must use the real combined backend and bind
  `LogicalWorldItemService.commitPersistence` plus rollback-safe
  `PhysicalWorldItemSystem.commitHibernate`; it must not replace this with a
  fake lifecycle or split visible publication.

## Suggested integration text

- Suggested commit: `feat: add bounded chunk streaming pipeline`
- Suggested PR title: `Phase 15: add transactional bounded Chunk streaming IO`
- Suggested PR summary: adds exact cancelable Chunk unload tickets, one-root
  Chunk/WorldItem durability, fixed 32/4 and 8/1 detached worker lanes,
  owner-thread stale validation/publication, rollback-safe unload ordering,
  and bounded diagnostics without starting mesh/GPU or origin work.
