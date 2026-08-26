# Phase 15 Task 12 report — Gate 15F and platform acceptance

## Status

Task 12 is **CLOSED / READY**. Phase 16 has not started. No Git mutation was
performed.
Native Apple Silicon macOS Phase 15 evidence remains **NOT RUN / PENDING**.

## Completed acceptance

- Gate 15F typed production probe: **3/3 GREEN** across more than 500 Chunk
  transitions, cardinal and negative travel, rapid travel, stale/cancel work,
  multiple rebases, modified/untouched Chunk restart, WorldItem paging/expiry,
  bounded retained state, and latency observations.
- Windows installDist: created `New Worlx826`, entered gameplay, switched to
  Creative, placed a visible dirt block in the resident Chunk, Save & Quit ->
  exit 0 -> fresh process -> loaded the same streamed-v2 world -> observed the
  same player view/mode and exact dirt block -> Save & Quit -> exit 0.
- Final clean repository verification: **3,351 total, 3,350 passed, one
  platform-conditional tools skip, zero failures/errors**; BUILD SUCCESSFUL in
  1h58m01s. Module totals: engine 1,309; game 2,015; tools 27.
- Package verification: installDist, shader/resources, UI assets, and Windows
  LWJGL/OpenAL 3.3.3 native audit all passed.

## Windows-acceptance repairs

The visible installDist workflow and final durability-race regression found
and tests-first corrected these real
composition defects:

1. Fresh streamed bootstrap had migrated all 289 reproducible Phase14 base
   Chunks, causing minutes of startup validation. The v1 compatibility floor
   now contains zero base Chunks while preserving world identity, player,
   inventory, WorldItem state, and Chunk revision high-water.
2. A safe dual INITIALIZING quorum with no published migration proof now
   observes the old authority as UNPUBLISHED without deep-reading invisible
   staged payloads. Corrupt published authority and proof-free Task4 shapes
   without the quorum retain their existing validation.
3. The production streaming factory no longer applies finite-radius complete
   snapshot validation to sparse v2 checkpoints. Before player/collision
   publication, deterministic generation fills only the bounded radius-2
   simulation neighborhood; persisted modified Chunk snapshots take priority.
   The complete candidate still publishes through the existing fresh-target
   restore transaction.
4. Phase14-to-v2 validation now walks every durable Chunk through one bounded
   read view without materializing historical payload bodies into the runtime
   session snapshot. Restart publishes only the radius-2 simulation
   neighborhood while retaining the global durable revision high-water and
   exact distant authority for later streaming loads.
5. Save capture now prepares exact tickets for dirty resident Chunks and
   publishes those detached captures with the session checkpoint, WorldItem
   pages, and WorldItem checkpoint through one semantic root. Tickets keep the
   resident Chunks pinned until durable success, are canceled on failure, and
   are acknowledged without unloading after publication.
6. Scheduler cancellation now distinguishes an ordinary successful worker
   return from the explicit one-way durable-publication fence. Cancellation
   before publication suppresses handoff and keeps the resident Chunk safe;
   publication before scheduler handoff preserves the durable success for
   owner reconciliation. The fence is marked only after the backend returns a
   successful `StreamedChunkUnloadResult`.

## Focused evidence after repairs

- Task4/Phase14 migration and fault boundaries: **129/129 GREEN**.
- Production session/origin/shutdown/save regression set: **57/57 GREEN**.
- Fresh streamed bootstrap composition: **4/4 GREEN**.
- Durability-fence RED-to-GREEN set: **2/2 GREEN**.
- Scheduler/pipeline fault matrix after the fence repair: **29/29 GREEN**.
- Affected production session/shutdown/GPU-owner boundary: **64/64 GREEN**.
- Gate 15F final rerun: **3/3 GREEN**, 9m14s; performance case 530.65 seconds.
- Legacy bounded-resident assertion corrections: **10/10 GREEN**. The v1
  archive still decodes all 81 fixture Chunks; runtime recapture now correctly
  asserts an exact 25-Chunk resident subset plus unchanged global high-water
  and non-Chunk save sections.
- Final clean build: **3,351 total / 3,350 passed / 1 conditional skip / 0
  failures / 0 errors**, BUILD SUCCESSFUL in 1h58m01s.
- `git diff --check`: recorded after final review corrections.

## Persistence contract impact

- No Task4 root or payload wire-format change was introduced by Task 12.
- Fresh deterministic base Chunks are runtime-regenerated and are not forced
  into the durable modified-Chunk index.
- One encoded Chunk payload remains capped at 18 MiB; one streamed index
  remains capped at 65,536 entries; physical staging batches remain at most 64
  blobs and 64 MiB.
- Safe unpublished staging remains invisible and old authority remains readable
  until the single publication point.

## Remaining work

- Apple Silicon macOS native arm64 Phase 15 acceptance remains pending.
- STOP after Task 12; do not enter Phase 16 without explicit approval.

## Final independent review

- Engine owner: **0 Critical / 0 Important / 0 Minor — READY**.
- Game/save owner initially reported **0 Critical / 0 Important / 1 Minor**:
  the handoff still contained a pre-cleanup `git diff --stat` placeholder.
  The final read-only stat and review totals are now recorded, resolving that
  documentation-only Minor.
- Final unresolved findings: **0 Critical / 0 Important / 0 Minor — READY**.
