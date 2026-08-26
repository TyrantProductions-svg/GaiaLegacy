# Phase 15 handoff — bounded infinite-world streaming

## Status

Phase 15 implementation is complete through Task 12's deterministic Gate 15F
soak. Repository-wide verification and Windows development/installDist runtime
acceptance are complete for this exact unstaged candidate. Both final
independent owner reviews are complete, all findings are resolved, and Task 12
is **CLOSED / READY**. Apple Silicon macOS evidence was not supplied and remains
**NOT RUN / PENDING** rather than inferred from Windows.

No Git mutation has been performed. The candidate remains on
`feat/infinite-world-streaming` as one cumulative unstaged Tasks 1-12 working
tree, and Phase 16 has not started.

## Completed work

- checked 64-bit `ChunkKey` addressing, canonical `GlobalPosition`, explicit
  safe-coordinate rejection, and negative-coordinate codecs;
- deterministic staged terrain generation with generator/base identity;
- one immutable Task4 streamed root for modified Chunk payloads, opaque
  WorldItem pages/checkpoint state, and required session/global checkpoint
  data, including bounded invisible prepublication staging;
- canonical five-minute (`18_000` fixed ticks) WorldItem expiry, stable IDs,
  allocator high-water persistence, bounded paging, rollback-safe activation,
  and durable-before-evict linked commits;
- deterministic radius `2/4/5` simulation/render/preload selection with
  radius-`7` unload hysteresis and stable desired epochs;
- bounded load/generate (`32/4`) and save (`8/1`) lanes whose completed but
  undrained results retain capacity;
- owner-thread mesh/GPU lifecycle budgets, UNKNOWN-safe query semantics, and
  atomic simulation-origin rebasing;
- production session composition, immutable HUD metrics, quiescent save
  capture, owner-ordered shutdown, and deterministic clean-Chunk no-write
  unload;
- a typed production-session Gate 15F probe covering more than 500 desired-set
  transitions, all cardinal directions, negative coordinates, rapid travel,
  cancellation/staleness, multiple rebases, modification persistence,
  WorldItem hibernation/expiry, and process-style restart.
- fresh streamed worlds bootstrap a zero-Chunk v1 compatibility floor rather
  than durably rewriting 289 reproducible base Chunks; sparse v2 restart
  regenerates only the bounded radius-2 simulation neighborhood before
  collision-safe player publication;
- catalog observation treats a safe dual INITIALIZING quorum as unpublished
  old-authority state without deep-reading invisible staged payloads.
- restore validation walks all historical durable Chunk descriptors and bytes
  through one bounded immutable read view, but publishes only the radius-2
  simulation neighborhood into the resident repository;
- Save capture pins exact dirty resident Chunk tickets and includes their
  detached payloads in the same staged semantic root as the session and
  WorldItem checkpoints; durable acknowledgement keeps those Chunks resident;
- scheduler cancellation cannot rewrite an already-returned durable save
  success during the post-operation worker handoff race; a one-way fence is
  marked only after the backend returns durable SUCCESS.

## Core architecture decisions

1. `ChunkRepository` is the sole resident Chunk authority. Streaming policy,
   worker scheduling, storage, and meshes observe or transact through narrow
   tickets/captures; none becomes a second repository.
2. `LogicalWorldItemService` is the sole WorldItem semantic, stable-ID,
   allocator, item-stack, and lifecycle authority. Task4 stores opaque bytes
   only. Expired IDs remain retired while expired DTO/page history is not kept.
3. Save v2 publishes Chunk payloads, WorldItem pages/checkpoint, allocator
   high-water, and authoritative world tick through one semantic root. Staged
   batches are bounded and invisible until one final root publication.
4. Worker results are detached and immutable. Canonical repository publication,
   OpenGL/GPU work, and ticket consumption remain owner-thread operations.
5. Global coordinates remain canonical. `SimulationOrigin` converts canonical
   positions into the bounded resident-local precision envelope; rebasing is
   an owner-thread atomic relocation, not a change to save identity.
6. Missing resident data is `UNKNOWN`, never air. Untouched Chunks regenerate
   from deterministic identity; modified Chunks require exact durable payloads.
7. Durable streamed authority is not the same as the resident snapshot. Restore
   validates every referenced historical payload without materializing all of
   them, publishes only the bounded simulation neighborhood, and retains the
   durable index/revision high-water for later exact loads.

## Protected interfaces the next phase must not break

- `ChunkKey`/`GlobalPosition` canonical checked addressing and deterministic
  ordering/codec semantics;
- `ChunkRepository` owner-thread publication plus single-use generation and
  unload tickets;
- `LogicalWorldItemService` sole authority, `expiresAtWorldTick` sole lifetime
  field, `WORLD_ITEM_TTL_TICKS = 18_000L`, strict allocator non-reuse, and
  linked durable-hibernate rollback guarantees;
- Task4 old-root/staged-candidate/final-publication visibility boundary and
  physical batch ceilings of 64 blobs / 64 MiB;
- controller desired epoch changes only when desired-set identity changes;
- scheduler accepted counts include queued, active, and completed-undrained
  work;
- main-thread OpenGL/GPU ownership and mesh owner budgets;
- save/restart ordering: validate identity, restore tick, restore allocator,
  validate one immutable root and all pages, reject duplicates, publish once,
  then reconstruct projections;
- pause and process downtime do not advance authoritative simulation time or
  WorldItem TTL.

## Known limits and risks

- Phase 15 is bounded currently-live streaming, not a region-file database or
  permanent history service. Orphan cleanup is bounded and does not participate
  in authority correctness.
- Safe-coordinate checks intentionally reject inputs outside the proven
  Chunk/global conversion envelope.
- The Task4 wire contract intentionally caps one encoded Chunk payload at
  18 MiB and one current streamed index at 65,536 entries. Multi-batch staging
  does not weaken either decode bound.
- The formal production soak is intentionally security/serialization heavy and
  is an acceptance test, not a per-frame performance promise; it uses no FPS or
  wall-clock pass threshold.
- Native Apple Silicon macOS Phase 15 runtime, Retina/focus behavior, and
  installDist remain **NOT RUN / PENDING** until supplied on real arm64
  hardware.

## Verification

Fresh accepted evidence:

- Gate 15F formal same-JVM matrix: **3/3 GREEN**, 9m14s focused; the production
  performance case recorded 530.65 seconds;
- affected game streaming/save/session/WorldItem matrix: **148/148 GREEN**;
- affected engine Chunk/WorldItem matrix: **55/55 GREEN**;
- exact legacy-v1 regression rerun: **10/10 GREEN**;
- post-acceptance Task4/Phase14 migration matrix: **129/129 GREEN**;
- post-acceptance production session/origin/shutdown/save matrix:
  **57/57 GREEN**;
- fresh bootstrap composition: **4/4 GREEN**;
- durability-fence focused RED-to-GREEN set: **2/2 GREEN**; complete scheduler/
  pipeline fault matrix: **29/29 GREEN**; affected production session,
  shutdown, and GPU-owner boundary: **64/64 GREEN**;
- final `clean test build`: **3,351 total, 3,350 passed, one skipped, zero
  failures/errors**, BUILD SUCCESSFUL in 1h58m01s. Module totals were engine
  1,309; game 2,015; tools 27. The sole skip was the platform-conditional
  `UiAssetGeneratorTest.rejectsSymlinkedParentThatEscapesTheRootWhenSupported`;
- the clean build executed `:game:installDist`; packaged shader/resource/audio
  audits passed and the distribution contains LWJGL/OpenAL 3.3.3 Windows
  natives;
- Windows development runtime: **PASS**. Main Menu and Settings rendered and
  accepted visible input, return navigation worked, and normal Alt+F4 shutdown
  ended the Gradle process with exit 0. The reported 1h25m29s includes a
  desktop-control authorization wait and is not a performance result;
- Windows installDist runtime: **PASS**. The generated `game.bat` launched
  independently; `New Worlx826` entered gameplay, a visible dirt block was
  placed in a resident Chunk, Save & Quit completed, and the process exited 0.
  A fresh process loaded the same sparse streamed-v2 world with the same view,
  Creative mode, and dirt block visible; the second Save & Quit and final
  shutdown also exited 0. No standalone duration was instrumented. One
  sandbox-restricted relaunch attempt was rejected before application
  bootstrap by Windows AppData `AccessDenied`; it was excluded, and the
  normal-permission relaunch above passed;
- Apple Silicon macOS: **NOT RUN / PENDING**.

Final independent review:

- engine owner: **0 Critical / 0 Important / 0 Minor — READY**;
- game/save owner: **0 Critical / 0 Important / 1 Minor** initially; the sole
  documentation-only stale-stat finding was corrected below;
- final unresolved findings: **0 Critical / 0 Important / 0 Minor — READY**.

No pending platform row is counted as PASS.

## Modified-file inventory

The cumulative candidate changes engine voxel, renderer/physics, and WorldItem
infrastructure; game generation, streaming, save/session, UI metrics, and
physical WorldItem composition; focused tests; Phase 15 architecture/testing
documents; and the root README/changelog/known-issues baseline. The final
read-only tracked `git diff --stat` reports **101 files changed, 11,930
insertions, 1,075 deletions**. New untracked Phase 15 source/test/document files
are listed separately by `git status`; the pre-existing untracked `dist/`
archive was not touched.

## Unfinished work

- Apple Silicon macOS native arm64 Gate 15F: **NOT RUN / PENDING**.
- Phase 16 and later extensions are intentionally not started.

## Suggested Git handoff

- Suggested commit: `feat(world): add deterministic infinite chunk streaming`
- Suggested pull request title:
  `feat(world): implement bounded infinite-world chunk streaming`
- Suggested pull request description:

  > Implements Phase 15's deterministic bounded infinite-world streaming on
  > the existing single Chunk, WorldItem, and save-root authorities. Adds
  > checked global addressing, pure deterministic generation, modified-only
  > durable payloads, TTL-bounded WorldItem paging, fixed worker/GPU budgets,
  > UNKNOWN-safe queries, atomic origin rebasing, production composition, and a
  > typed 500-plus-transition Gate 15F soak. Includes fresh automated and
  > Windows runtime evidence; Apple Silicon macOS remains explicitly pending.
