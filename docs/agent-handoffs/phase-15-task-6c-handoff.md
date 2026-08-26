# Phase 15 Task 6C handoff

## Completed work

Task 6C integrates WorldItem v2 paging/TTL with the real Phase14 save root and
process restart. Legal 1,024-owner semantic checkpoints publish through bounded
invisible Task4 fixed-slot batches, complete validation, and one final
recovery/main index generation. Restore validates tick, allocator, pages,
dependencies, hashes/counts, and duplicate IDs before one fresh-target
publication. Legacy v1 read and lossy-write rejection remain intact.

## Unfinished work

Task 6D callback reentrancy/rollback and Task 6E broad acceptance are not
authorized by this handoff and remain unstarted. Final Task6C independent
review is complete: 0 Critical / 0 Important / 0 Minor — READY.

## Core architecture decisions

- `WORLD_ITEM_TTL_TICKS=18_000L`; `expiresAtWorldTick` is the sole lifetime
  field; pause and process downtime do not advance it.
- `LogicalWorldItemService` remains the sole WorldItem semantic, stable-ID,
  allocator/high-water, ItemStack, and lifecycle authority.
- Task4 remains generic opaque bytes/index authority. Staging reuses only A/B
  fixed slots and publishes no new authority before the final index root.
- Every physical staging step is `<=64` distinct payload blobs and `<=64 MiB`.
  New owners cost two blobs because A and B must both exist pre-publication.
- Payload byte-array residency has an enforced conservative `<=256 MiB`
  ceiling; metrics derive the upper bound from the observed batch plus bounded
  codec/adapter/reread buffers. Metadata remains bounded by 64 batch entries
  and 1,024 candidate descriptors.
- One reference-queued weak-value save-root writer capability excludes concurrent writers.
  Constructor creation/repair also acquires it and reinspects before mutation.
  Bounded generation readers pin exact slots and lazily decode one payload,
  preserving old-view validity without a whole-authority payload map.
- Crash/failure before final publication leaves old authority; interruption
  between dual index slots resolves old or complete new; cleanup has no
  authority role and requires no scan.
- Allocator high-water never regresses. Session snapshot tick/allocator must
  exactly equal the WorldItem checkpoint before publication.
- No database, WAL/MVCC, catalog/refcount/GC, maintenance overlay, second
  repository, wall-clock expiry, or permanent expired DTO history exists.

## Modified files

Task6C/staging closure directly changes:

- `engine/src/main/java/com/overlord/worlditem/api/WorldItemPersistencePlan.java`
- `game/src/main/java/com/gaia/save/streaming/StreamedChunkStore.java`
- `game/src/main/java/com/gaia/save/streaming/StreamedWorldItemPageBackend.java`
- `game/src/main/java/com/gaia/save/streaming/StreamedSessionSaveTarget.java`
- `game/src/test/java/com/gaia/save/streaming/BoundedPrepublicationStagingTest.java`
- `game/src/test/java/com/gaia/save/streaming/StreamedChunkTtlStoreTest.java`
- `game/src/test/java/com/gaia/save/streaming/WorldItemPagingRestartTest.java`
- `game/src/test/java/com/gaia/save/streaming/Phase14SaveMigrationTest.java`
- Task6C design, implementation plan, report, and this handoff

The shared dirty worktree also contains Tasks1-6B and earlier Task6C files.
This list is a scope statement, not ownership of every dirty file.

## Test commands and results

- staging adversarial: 16/16 GREEN;
- Task4 TTL/page focused: 29/29 GREEN;
- Task6C restart/save focused: 16/16 GREEN;
- real 1,024-owner WorldItem adapter: 1/1 GREEN;
- v1 codec/session restore/clock/GameSession: 57/57 GREEN;
- Phase14 migration/fault matrix: 112/112 GREEN;
- frozen Task6A/6B boundary: 93/93 GREEN;
- post-review correction round: 82/82 GREEN;
- total distinct final cases: 324/324 GREEN;
- `git diff --check`: PASS with existing line-ending warnings only.

The exact Gradle commands and timing are recorded in the Task6C report and
bounded staging plan. No interactive `:game` smoke was run.

## Known risks and interfaces not to break

- Do not bypass the shared writer capability in a new Task4 write path.
- Do not recycle a slot pinned by an open bounded generation.
- Do not replace bounded proof/restore with `openPinnedReadView` or another
  whole-authority payload map.
- Do not publish page/session globals in separate visible roots.
- Do not recompute allocator high-water from page load order.
- Do not weaken frozen Task6A/6B TTL, durable-before-evict, or ticket/proof
  contracts.

## Diff and suggested integration text

The repository-wide tracked `git diff --stat` currently reports 61 files,
6,383 insertions, and 662 deletions. It includes the shared Phase15 worktree and
excludes untracked files, so it is not Task6C-only.

- Suggested commit: `feat: integrate bounded WorldItem v2 restart`
- Suggested PR title: `Phase 15: integrate bounded WorldItem v2 persistence`
- Suggested PR summary: integrates exact-tick WorldItem TTL paging with v2
  Save/Quit/restart through bounded invisible Task4 staging, one validated root
  publication, stable allocator/IDs, v1 fail-closed compatibility, and
  crash-safe bounded restore; Task6D remains unstarted.
