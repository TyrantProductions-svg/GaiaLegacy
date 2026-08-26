# Phase 15 Task 8 report

## Result

Task 8 implements the approved minimal bounded load/generate/save execution
boundary. It adds an exact cancelable resident Chunk unload reservation, one
combined Chunk/WorldItem durable publication adapter, two fixed-capacity worker
lanes, owner-thread publication/rollback, bounded current diagnostics, and a
detached deterministic generation seam. Task 9 mesh/GPU work and Task 10
origin/rebase work were not started.

## Contract delivered

- `ChunkRepository.prepareStreamingUnload` keeps the exact Chunk resident and
  does not advance revision. Its opaque ticket is issuer-, repository-owner-
  thread-, key-, entry-incarnation-, revision-, state-, and failure-bound.
- Mutation or replacement before final validation makes the ticket stale.
  Final validation seals block mutation/replacement, so exact commit has no
  environmental failure branch. Cancel and commit are single-consume; foreign,
  stale, replayed, and wrong-thread use fails closed.
- Workers receive detached immutable generation/save values, never repository
  or unload-ticket authority.
- `LogicalWorldItemService.activeRevisionsInChunk` is an exact sorted,
  defensive immutable observation only.
- `StreamedWorldItemPageBackend.persistUnload` merges an exact Chunk capture
  with an optional prepared WorldItem plan and required bounded globals into
  the existing Task4 staged candidate. Chunk bytes, WorldItem page/checkpoint,
  and required globals become visible through one final root publication.
  The external global list is capped at one required
  `streamed-session-checkpoint` upsert; remove, arbitrary-section, optional,
  and multi-entry input fails closed before staging.
- When WorldItems participate, the plan must supply exactly one canonical,
  identity/tick-matching session checkpoint input. The backend consumes its
  own `AtomicCheckpointBinding` to rebuild the published session checkpoint's
  WorldItem revision, digest, and source index sequence. Caller placeholder
  binding fields are never published. Chunk-only unload cannot mutate session
  authority.
- A prepared WorldItem plan exposes only detached atomic epoch/tick/freshness
  state to the persistence worker. Worker code never reads the service's
  owner-thread maps. Owner cancellation, lifecycle mutation, or close makes
  the detached plan stale before final publication; owner commit still performs
  the full exact canonical-state validation.
- Load/generate accepts at most 32 total with at most 4 active. Save accepts at
  most 8 total with at most 1 active. Accepted tokens include queued, active,
  and completed-but-owner-undrained states. A token is released only by queued
  cancellation or owner drain/discard.
- Current diagnostics are per-key and capped at 256. Work/result/context state
  is bounded by the two lane capacities; no historical result/failure log was
  added.
- Persistence failure, stale durability, late cancellation, corrupt load, or
  physical hibernation failure cannot evict resident authority. The successful
  owner order is durable result, exact ticket validation, frozen Task6D
  hibernation commit, then exact Chunk removal.

## Persistence and save-format effect

No save-format version, codec version, root identity, migration protocol, slot
count, 64-blob batch bound, or 64-MiB batch bound changed. Task 8 adds a narrow
adapter contract that supplies one detached Chunk mutation to the already
accepted bounded prepublication candidate and final root publication. Task4
remains opaque byte/index storage and does not own Chunk or WorldItem lifecycle.
The combined adapter now requires the existing canonical session-checkpoint
codec/input and rebinds it to the same atomic WorldItem checkpoint/root; this is
a validation/composition contract change, not a wire-format change.

## Tests and verification

- Tests-only RED: engine had 37 and game had 89 missing-contract compile
  errors, all for approved Task 8 symbols/seams.
- Final Task 8 focused: 88/88 GREEN:
  - repository unload transaction 11;
  - logical dormant/revision observation 21;
  - combined real-store unload publication 5;
  - lane/scheduler accounting 7;
  - pipeline fault/order/shutdown 9;
  - `WorldLoader` generation/lifecycle regression 35.
- Affected engine proportional: 286/286 GREEN. This includes repository,
  snapshot, Task7 `GlobalPosition`, and frozen logical WorldItem suites.
- Task6D/6E + Task7 + Task8 short game matrix: 91/91 GREEN.
- Task4/Task6 codec/TTL/paging/corruption short matrix: 66/66 GREEN.
- Frozen Task6C restart/save subset: 16/16 GREEN, explicitly excluding the
  known long 1,024-owner method.
- Total distinct successful proportional cases: 494.
- `git diff --check`: PASS with line-ending warnings only.

Four over-broad or slow supplemental commands were intentionally canceled and
are not counted as passes: one included the Task6E 500-transition soak, one
included the long 1,024-candidate staging pressure path, one combined too many
store regressions, and the isolated `StreamedChunkStoreFaultTest` exceeded the
five-minute proportional budget. None produced failure output before
cancellation. The frozen closure evidence for long cases was not reopened.

`git diff --stat` for the cumulative tracked Phase 15 worktree reports 63 files,
7,209 insertions, and 698 deletions. This aggregate includes accepted Tasks1-7
and omits untracked Phase 15 files from Git's stat output; it is not presented
as a Task 8-only delta.

## Scope and risks

- `ChunkMeshManager`, renderer/GPU ownership, Task 9, Task 10 origin/rebase,
  region format, WAL/database, catalog/refcount/GC, and background compaction
  are untouched by Task 8.
- The pipeline is an execution component consuming immutable Task7 decisions;
  product-loop composition remains a later integration boundary and is not
  hidden inside the pure Task7 controller.
- Task11 composition must bind the real unload lifecycle to the existing
  `StreamedWorldItemPageBackend`, `LogicalWorldItemService.commitPersistence`,
  and rollback-safe `PhysicalWorldItemSystem.commitHibernate` path. A fake or
  split Chunk/WorldItem publication is not an acceptable production binding.
- macOS/Apple Silicon smoke and interactive `:game` were not run and are not
  claimed.

## Review

The initial independent review found 1 Critical, 1 Important, and 1 Minor:
worker access to owner-thread WorldItem state, an unbounded external-global
list, and incorrect save-worker exception diagnostics. All three received
focused REDs and production fixes. A subsequent sweep found one additional
Important: arbitrary session bytes were not rebound to the new atomic
WorldItem checkpoint. A binding-aware RED failed in two places, then passed
after the backend began constructing the canonical session extension from its
`AtomicCheckpointBinding`. Final independent result: **0 Critical / 0 Important
/ 0 Minor — READY**.
