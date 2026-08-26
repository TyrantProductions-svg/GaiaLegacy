# Phase 15 Task 6C Bounded Staging Implementation Plan

> **For Codex:** Execute this plan with tests-only RED, minimal GREEN,
> proportional verification, independent review, and a final STOP. Do not
> enter Task 6D.

**Goal:** Publish a legal 1,024-owner WorldItem checkpoint through bounded
Task4 physical batches while readers observe one old or complete-new root.

**Architecture:** Reuse each Chunk's inactive fixed payload slot as invisible
staging. Retain only bounded descriptors plus one <=64-payload/<=64-MiB batch,
validate the complete detached candidate, and publish one index generation.

**Tech stack:** Java 17, JUnit 5, checked-in Gradle wrapper.

---

## Task 1: Tests-only RED and controller gate

**Files:**

- Modify: `game/src/test/java/com/gaia/save/streaming/StreamedChunkTtlStoreTest.java`
- Modify: `game/src/test/java/com/gaia/save/streaming/StreamedChunkStoreFaultTest.java`
- Modify: `game/src/test/java/com/gaia/save/streaming/WorldItemPagingRestartTest.java`
- Modify: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemServicePagingTest.java`

- [x] Add a 65-upsert test that requires two physical batches and observes no
  new index until final publication.
- [x] Add a 1,024-owner WorldItem plan test with instrumentation asserting one
  resident batch, <=64 payloads, and <=64 MiB.
- [x] Make a candidate exceed 64 MiB in total and require success.
- [x] Inject failures in first, middle, and final staging batches and assert the
  old root reopens.
- [x] Inject crash immediately before and after final publication and assert
  old-or-complete-new only.
- [x] Add stale, cancel, late revision/hash mismatch, orphan restart, reader
  invisibility, and single-batch compatibility REDs.
- [x] Run only the new tests and record that they fail because the staging seam
  and the 1,024 semantic-page allowance do not yet exist.

## Task 2: Minimal generic Task4 staging seam

**Files:**

- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedChunkStore.java`

- [x] Add a package-private nested, single-owner staging session bound to one observed
  base sequence/index and one next sequence.
- [x] Validate each batch at <=64 mutations and <=64 MiB before its first
  payload write.
- [x] Write only slots unreferenced by the captured base authority; force,
  reread, hash, and retain only lightweight descriptors afterward.
- [x] Reject duplicate keys, stale base revision/hash, false freshness,
  cancellation, and terminal-session reuse before publication.
- [x] Finalize removals and global mutations into one detached candidate,
  revalidate all staged slots and dependency counts, then publish recovery and
  main index once.
- [x] Keep existing `StreamedPersistenceTransaction` and
  `commitTransaction` limits and behavior unchanged.
- [x] Keep cleanup as lazy exact inactive-slot overwrite in <=64-path batches;
  perform no automatic cleanup retry, allow at most one fresh semantic retry
  per failed save request, and prove restart remains safe when the unreachable
  remnant is retained.

## Task 3: WorldItem incremental staging integration

**Files:**

- Modify: `engine/src/main/java/com/overlord/worlditem/api/WorldItemPersistencePlan.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedWorldItemPageBackend.java`

- [x] Restore the semantic page-mutation bound to 1,024 while leaving the
  Task4 physical batch bound at 64.
- [x] Replace the all-payload `chunkMutations` accumulation with incremental
  batches. Encode and stage at most 64 payloads/64 MiB, release each completed
  batch, and retain semantic descriptors only.
- [x] Complete page/checkpoint identity, descriptor, count, duplicate-ID,
  allocator, and worldTick validation before finalization.
- [x] Bind WorldItem checkpoint and streamed session checkpoint to the same
  intended index sequence and final root publication.
- [x] Preserve the caller freshness predicate so dirty/unproved state remains
  pinned when staging or publication fails.

## Task 4: GREEN and focused regressions

**Files:** Tests above plus affected existing Task4/Phase14/Task6C suites.

- [x] Run the new staging RED set to GREEN.
- [x] Run focused Task4 store/TTL/fault tests.
- [x] Run `WorldItemPagingRestartTest` and current Task 6C v1/v2/restart tests.
- [x] Run Phase14 persistence/migration regressions.
- [x] Run frozen Task 6A and 6B regression subsets only.
- [x] Run `git diff --check` and record exact totals. Stop Gradle daemons after
  the final independent review.
- [x] Convert the final-review findings to three REDs: constructor repair must
  share the writer gate, transient payload residency needs an executable
  conservative bound, and physical I/O must be counted independently at the
  file-operations seam.
- [x] Run the correction GREEN set: 16/16 staging, 24/24 Task4 TTL/store,
  16/16 Task6C restart, and 26/26 expanded Phase14 persistence cases.

## Task 5: Documentation, independent review, STOP

**Files:**

- Modify: `docs/superpowers/specs/2026-08-13-phase-15-worlditem-paging-backend-design.md`
- Modify: `docs/superpowers/plans/2026-08-13-phase-15-worlditem-paging-backend.md`
- Create/modify: `.superpowers/sdd/2026-08-12-phase-15-infinite-world-streaming/task-6c-report.md`
- Create: `docs/agent-handoffs/phase-15-task-6c-handoff.md`

- [x] Record the new Task4 staging contract, batch/memory/cleanup bounds, and
  old-or-complete-new failure semantics.
- [x] Mark Task 6C complete only after all focused and proportional regressions
  pass.
- [x] Request independent review against the approved constraints and report
  Critical/Important/Minor findings.
- [x] STOP without starting Task 6D.
