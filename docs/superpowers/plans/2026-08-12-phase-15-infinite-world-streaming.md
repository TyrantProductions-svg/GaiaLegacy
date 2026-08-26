# Phase 15 Infinite World Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the finite 81-Chunk runtime with deterministic, persistence-aware, player-centered horizontal Chunk streaming whose resident state, work queues, GPU publication, and WorldItems remain bounded and failure-safe.

**Architecture:** Extend the existing `ChunkRepository` state/ticket authority and Phase 14 save root; do not create a second World store. A game-owned `ChunkStreamingController` computes immutable desired sets, bounded workers load/generate/mesh/save immutable results, and the owner thread publishes canonical and GPU state under fixed budgets. Canonical global positions use `ChunkKey + double local offset`; resident physics/rendering use an explicitly rebased float simulation origin.

**Tech Stack:** Java 17, Gradle 8.5 Wrapper, JUnit 5, JOML, LWJGL OpenGL 4.1/GLSL 410, JDK executors/files/ZIP/checksums, existing Phase 14 save codecs and atomic file seams.

## Global Constraints

- Branch is `feat/infinite-world-streaming`, based on merged Phase 14 `origin/main@ddd6a961826ebb593ce8d45458f48e7f86e9559b`.
- Do not stage, commit, push, create a PR, or merge without separate explicit authorization. The per-task commit examples in the generic skill are intentionally omitted.
- Preserve the untracked Milestone 1 `dist/` artifact; never add `dist/`, `build/`, `.gradle/`, logs, saves, screenshots, crash dumps, IDE state, or temporary corruption fixtures.
- `ChunkRepository`, `WorldMutationService`, `LogicalWorldItemService`, `SaveRootProvider`/`SaveGameManifest`, and owner-thread OpenGL ownership remain unique authorities.
- Keep Java 17 source/target, macOS OpenGL 4.1, GLSL 410, and existing engine-to-game dependency direction.
- `ChunkKey(int,int)` remains the only Chunk address. The safe axis envelope is `[-134217727,+134217727]`; all origins, neighbors, distances, and priorities use checked `long` intermediates.
- Defaults are simulation/render/preload/unload `2/4/5/7`; load-generation queue/active `32/4`, mesh `32/2`, save `8/1`; owner-thread publish/upload/destroy budgets `2/2/4` per frame.
- Unloaded/unready space is `UNKNOWN/UNAVAILABLE`, never implicit AIR.
- Phase 15 persists modified Chunks only, conservatively imports all Phase 14 v1 Chunks, and preserves a readable v1 recovery archive until full v2 validation.
- Do not implement infinite Y, detail voxels, large POI/biome content, region-container optimization, LOD, multiplayer, moving assemblies, or structural physics.
- Each task follows strict tests-first RED, controller acceptance, minimal GREEN, focused regression, independent review, and report update. Stop immediately at each RED gate when the execution brief requires controller authorization.

---

## File and package structure

New engine-facing address/lifecycle types stay under `com.overlord.voxel`; no game-specific policy enters engine. WorldItem paging contracts stay under `com.overlord.worlditem.api` and are implemented by the existing service. Game streaming policy/controller/pipeline live under `com.gaia.world.streaming`. Streamed persistence lives under `com.gaia.save.streaming` and composes Phase 14 file/format types. Simulation-origin orchestration lives under `com.gaia.session.streaming`, while reusable origin values and renderer transforms live in engine packages.

The following planned production files each own one boundary:

- `engine/.../voxel/ChunkCoordinatePolicy.java`: checked safe envelope, origins, neighbors, distance, and ordering.
- `engine/.../voxel/ChunkAvailability.java`: closed AVAILABLE/UNKNOWN/FAILED query result.
- `engine/.../voxel/ChunkStreamingTicket.java`: key + epoch + expected revision source identity.
- `engine/.../voxel/ChunkRepository.java`: extend existing request/publication/unload authority only.
- `engine/.../worlditem/api/WorldItemPaging*.java`: expiry-bearing page,
  checkpoint, plan, opaque ticket, durable proof, policy, and metrics contracts.
- `engine/.../worlditem/LogicalWorldItemService.java`: one bounded live
  metadata aggregate, global allocator authority, exact-tick expiry index,
  bounded cleanup intents, and ticket commits. It receives but never advances
  `SessionPersistenceClock` values.
- `engine/.../physics/SimulationOrigin.java`: immutable origin and checked global/local conversion.
- `engine/.../renderer/RenderOrigin.java`: owner-thread render-origin value derived from simulation origin.
- `game/.../world/streaming/ChunkStreamingPolicy.java`: validated radii and budget defaults.
- `game/.../world/streaming/ChunkDesiredSets.java`: immutable desired set snapshot.
- `game/.../world/streaming/ChunkStreamingController.java`: player-centered deterministic policy only.
- `game/.../world/streaming/ChunkStreamingPipeline.java`: bounded admission, worker handoff, cancellation, diagnostics.
- `game/.../world/streaming/ChunkStreamingMetrics.java`: immutable observation snapshot.
- `game/.../world/streaming/UnknownSpaceBarrier.java`: physics/raycast availability boundary.
- `game/.../save/streaming/StreamedChunkCodec.java`: v2 per-Chunk payload and optional extensions.
- `game/.../save/streaming/StreamedChunkIndex*.java`: immutable manifest index + versioned codec.
- `game/.../save/streaming/StreamedChunkStore.java`: atomic file/index transaction on the Phase 14 root.
- `game/.../save/streaming/Phase14SaveMigrator.java`: conservative v1 full import and recovery.
- `game/.../session/streaming/SimulationOriginCoordinator.java`: atomic owner-thread rebase transaction.
- `docs/architecture/infinite-world-streaming.md`: final authoritative architecture/limits.
- `docs/agent-handoffs/phase-15-handoff.md`: final evidence and protected interfaces.

## Task 1: Gate 15A checked global addressing

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/ChunkCoordinatePolicy.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkKey.java`
- Modify: `game/src/main/java/com/gaia/save/codec/ChunkSectionCodec.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkCoordinatePolicyTest.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkKeyTest.java`
- Test: `game/src/test/java/com/gaia/save/codec/ChunkSectionCodecTest.java`

**Interfaces:**
- Produces: `ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE`, `requireSafe(ChunkKey)`, `worldOriginX/Z(ChunkKey): long`, `neighbor(ChunkKey,int,int): ChunkKey`, `squaredDistance(ChunkKey,ChunkKey): long`, and canonical comparator.
- Preserves: `ChunkKey.fromWorld(int,int)` and `localCoordinate(int)` floor semantics and the `int x/z` save wire format.

- [ ] **Step 1: Write the addressing RED**

Add parameterized cases for world coordinates `-33,-32,-17,-16,-1,0,1,15,16,17,31,32,33`, exact local round trips, both safe-envelope endpoints, checked rejection outside the envelope, neighbor overflow, and distance/priority near opposite endpoints. Assert the comparator returns the same order for shuffled `HashSet`, reverse insertion, and sorted input.

- [ ] **Step 2: Run the focused RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.voxel.ChunkCoordinatePolicyTest' --tests 'com.overlord.voxel.ChunkKeyTest' :game:test --tests 'com.gaia.save.codec.ChunkSectionCodecTest' --console=plain --no-daemon
```

Expected: compile/semantic RED only for missing checked policy and unsafe origin/neighbor behavior.

- [ ] **Step 3: Implement checked addressing**

Implement the policy with `Math.multiplyExact((long) key.x(), 16L)`, checked axis validation, checked neighbor addition in `long`, and saturating-free squared distance that rejects values outside the approved envelope. Make `ChunkKey.worldOriginX/Z()` delegate to a checked result or replace unsafe callers with the policy without changing the key width. Validate decoded keys before allocating Chunk payload arrays.

- [ ] **Step 4: Verify Gate 15A address GREEN**

Run the focused command plus:

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.voxel.*' :game:test --tests 'com.gaia.save.codec.*' --console=plain --no-daemon
```

Expected: PASS; no key-width migration and no `x * 16`/`z * 16` unchecked origin remains in production.

## Task 2: Gate 15A repository request lifecycle

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/ChunkAvailability.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkStreamingTicket.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkStreamingPublication.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkState.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryStreamingTest.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryGenerationTransactionTest.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryPersistenceTest.java`

**Interfaces:**
- Produces: `request(ChunkKey,long epoch,SourcePreference)`, `publish(ChunkStreamingTicket,ChunkGenerationData,BaseIdentity)`, `cancel(ChunkStreamingTicket)`, `availability(ChunkKey)`, and exact ticket-aware unload completion.
- Consumes: checked keys from Task 1 and existing generation/mesh/unload revisions.

- [ ] **Step 1: Write coalescing/stale REDs**

Cover duplicate same-key requests, newer epoch replacement, load-vs-generate source selection, cancel before/after worker result, unload while work is running, publication after replacement, revision exhaustion before side effects, and UNKNOWN queries that never return AIR.

- [ ] **Step 2: Run focused repository RED**

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.voxel.ChunkRepositoryStreamingTest' --tests 'com.overlord.voxel.ChunkRepositoryGenerationTransactionTest' --tests 'com.overlord.voxel.ChunkRepositoryPersistenceTest' --console=plain --no-daemon
```

- [ ] **Step 3: Extend the existing repository minimally**

Keep the existing `Entry` and generation-attempt authority. Add request metadata keyed by the same `ChunkKey`; do not introduce a separate Chunk map. Prevalidate tickets/revisions/results before canonical mutation, reuse existing publication probes, and ensure unload invalidates outstanding tickets before entry removal.

- [ ] **Step 4: Verify repository GREEN and architecture**

Run all engine voxel tests and scan production for a second Chunk map/store class. Expected: all existing states/generation/mesh tests pass; stale results cannot publish.

## Task 3: Gate 15B pure deterministic generation

**Files:**
- Modify: `game/src/main/java/com/gaia/world/generation/DeterministicCoordinateSampler.java`
- Modify: `game/src/main/java/com/gaia/world/generation/GenerationRegion.java`
- Modify: `game/src/main/java/com/gaia/world/generation/WorldGenerationStage.java`
- Modify: `game/src/main/java/com/gaia/world/generation/StagedWorldGenerator.java`
- Modify: provider files under `game/src/main/java/com/gaia/world/generation/`
- Create: `game/src/main/java/com/gaia/world/generation/GenerationStageContract.java`
- Create: `game/src/main/java/com/gaia/world/generation/StableRegionAnchor.java`
- Test: `game/src/test/java/com/gaia/world/generation/OnDemandGenerationDeterminismTest.java`
- Test: `game/src/test/java/com/gaia/world/generation/WorldGenerationSeamTest.java`
- Test: `game/src/test/java/com/gaia/world/WorldGenerationArchitectureTest.java`

**Interfaces:**
- Produces: stage `id/version/haloRadius`, long-lattice deterministic sampling, stable region anchor ownership, and canonical generated hash.
- Consumes: checked `ChunkKey`/origins and existing immutable `GenerationContext`.

- [ ] **Step 1: Write schedule/order/seam REDs**

Generate the same keys first, after 100 keys, reversed, shuffled on 1/2/4 worker schedules, and after unload. Compare exact block bytes and hashes. Compare cardinal/diagonal border columns for height/surface/cave/strata. Place decoration anchors straddling four Chunks and assert one global decision with clipped unique writes.

- [ ] **Step 2: Write forbidden-input structural REDs**

Assert generator/providers do not depend on `ChunkRepository`, `World`, loaded-neighbor snapshots, mutable `Random`, wall time, thread ID, or cross-border `sampleLocalOrAir`.

- [ ] **Step 3: Run Gate 15B RED**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.world.generation.OnDemandGenerationDeterminismTest' --tests 'com.gaia.world.generation.WorldGenerationSeamTest' --tests 'com.gaia.world.WorldGenerationArchitectureTest' --console=plain --no-daemon
```

- [ ] **Step 4: Implement world-coordinate stage contracts**

Use `long` lattice coordinates/mixing, include generator and stage versions, compute decoration anchors from stable signed region coordinates, and restrict local sampling. Preserve current terrain content aside from deterministic seam corrections; do not add biomes/POIs.

- [ ] **Step 5: Verify generation GREEN**

Run all `com.gaia.world.*` tests plus engine repository tests. Record deterministic hashes in a fixture file under test resources only if the existing test convention uses checked fixtures.

## Task 4: Gate 15C v2 streamed Chunk format and atomic store

**Files:**
- Create: `game/src/main/java/com/gaia/save/streaming/StreamedChunkPayload.java`
- Create: `game/src/main/java/com/gaia/save/streaming/StreamedChunkCodec.java`
- Create: `game/src/main/java/com/gaia/save/streaming/StreamedChunkIndex.java`
- Create: `game/src/main/java/com/gaia/save/streaming/StreamedChunkIndexCodec.java`
- Create: `game/src/main/java/com/gaia/save/streaming/StreamedChunkStore.java`
- Modify: `game/src/main/java/com/gaia/save/format/SaveFormatVersion.java`
- Modify: `game/src/main/java/com/gaia/save/format/SaveSectionId.java`
- Modify: `game/src/main/java/com/gaia/save/format/SaveCodecRegistry.java`
- Modify: `game/src/main/java/com/gaia/save/store/SaveFileOperations.java`
- Modify: `game/src/main/java/com/gaia/save/store/JdkSaveFileOperations.java`
- Test: `game/src/test/java/com/gaia/save/streaming/StreamedChunkCodecTest.java`
- Test: `game/src/test/java/com/gaia/save/streaming/StreamedChunkStoreFaultTest.java`

**Interfaces:**
- Produces: `read(SaveGameId,ChunkKey,ExpectedBase)`, atomic
  `StreamedPersistenceTransaction` over exact Chunk captures plus generic
  global-extension upsert/remove mutations, immutable v2/v3 index entries, and
  optional extension descriptors including reserved `detail-blocks`.
- Reuses: Phase 14 root/world identity, guarded file operations, bounded diagnostics, temp-force-reread-move patterns.

- [ ] **Step 1: Write codec/layout REDs**

Lock canonical key/identity/base/revision/hash/modified fields, deterministic bytes, negative/large keys, unknown optional extension validation/skip, required extension rejection, duplicate/trailing/oversize/corrupt payloads, and exact round trip.

- [ ] **Step 2: Write atomic fault REDs**

Inject temp create/write/force/reread, Chunk move, index write/force/move, directory force, post-move mismatch, path replacement/junction, stale revision, and cleanup failure. Assert exact last-known-good file/index and no successful unload authorization on failure.

- [ ] **Step 3: Run streamed-store RED**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.streaming.*' --console=plain --no-daemon
```

- [ ] **Step 4: Implement the minimal v2 format/store**

Use sharded directories derived from canonical signed key encodings, direct-child confinement, per-file bounds, exact expected world/key/base/revision validation, and index publication after payload validation. Do not add a region container or new JSON dependency.

- [ ] **Step 5: Verify store GREEN and Phase 14 regressions**

Run all `com.gaia.save.*`, settings atomic-file tests, security/path tests, and `git diff --check`.

## Task 5: Gate 15C conservative Phase 14 migration

**Files:**
- Create: `game/src/main/java/com/gaia/save/streaming/Phase14SaveMigrator.java`
- Create: `game/src/main/java/com/gaia/save/streaming/Phase14MigrationResult.java`
- Modify: `game/src/main/java/com/gaia/save/store/SaveRepository.java`
- Modify: `game/src/main/java/com/gaia/save/archive/SaveArchiveReader.java`
- Test: `game/src/test/java/com/gaia/save/streaming/Phase14SaveMigrationTest.java`
- Test: `game/src/test/java/com/gaia/save/SaveFailureRecoveryIntegrationTest.java`

**Interfaces:**
- Produces: closed `NOT_REQUIRED/MIGRATED/FAILED/BLOCKING_FAILURE` result with validated v2 manifest/index and retained readable v1 backup.
- Consumes: Phase 14 reader/snapshot and Task 4 store; imports all v1 Chunks as authoritative persisted data.

- [ ] **Step 1: Write full-import/migration fault REDs**

Assert every v1 Chunk is persisted regardless of current generated hash. Inject failure before/after each Chunk, index, v2 manifest, backup preservation, and final reread. Assert launch still reads v1 until complete v2 validation and repeated migration is idempotent.

- [ ] **Step 2: Run migration RED**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.save.streaming.Phase14SaveMigrationTest' --tests 'com.gaia.save.SaveFailureRecoveryIntegrationTest' --console=plain --no-daemon
```

- [ ] **Step 3: Implement the conservative transaction**

Never infer unmodified v1 state. Stage all imported payloads/index/manifest, validate the complete v2 world, then publish; retain v1 as recovery. Return bounded diagnostics without exposing paths.

- [ ] **Step 4: Verify migration GREEN**

Run full save tests and actual fixture reopen through `SaveRepository`; confirm
no silent format downgrade or half-migrated index entry.

## Task 6: Gate 15C single-authority WorldItem paging

> **Project-owner reset (2026-08-14):** Do not execute the former Task 6 or
> Option A owner-directory/blob maintenance plan. The canonical detailed plan
> is now
> `docs/superpowers/plans/2026-08-13-phase-15-worlditem-paging-backend.md`.
> The earlier no-automatic-expiry rule is explicitly superseded.

**Locked contract:** `WORLD_ITEM_TTL_TICKS = 18_000L` and
`expiresAtWorldTick` is the sole lifetime field. `worldTick` maps to existing
`fixedTick`; pause and process downtime do not advance it. Spawn uses
saturating addition and expiry is `worldTick >= expiresAtWorldTick`. Restore
uses `SessionPersistenceClock` as the only advancing authority and validates one
immutable index-sequence/checkpoint-digest view before publication. The cap is
`active DTO + decoded dormant DTO + evicted-unexpired metadata + unique pending
<= 1,024`; metadata separates intended owner/revision from optional durable
proof. Clean dormant/evicted requires proof; unproved active/dirty/pending stays
resident/pinned. Pickup/expiry deletes metadata.

Task 4 remains byte-only authority. It gains generic inline global-extension
`Upsert`/`Remove` mutations with omission-retains semantics and a 1 MiB
per-extension and retained-total bound. `GLWP` version 1 is the first streamed
page format and is independent of whole-world save v1/v2. The checkpoint has
the complete <=1,024 physical descriptors with raw encoded and checkpoint-tick
survivor counts; it declares a matching generic physical-page dependency count.
Explicit expected-revision/hash Chunk/page
upsert/remove distinguishes item-only Chunk removal from extension-only
removal. No owner
directory/trie, opaque blob graph, catalog/refcount/GC, overlay, maintenance
protocol, database, global scan, or permanent dormant DTO map is allowed.

### Task 6A: TTL page seam (1.5-3 engineer-days)

Add exact expiry to engine snapshots; add `WorldItemPagingCheckpoint`; encode
literal bounded `GLWP` v1 pages/checkpoint; add generic Task 4 extension
upsert/remove, descriptor/dependency counts, immutable read views, and atomic
page+checkpoint storage. Write codec/index/store/fault, stale remove,
item-only/extension-only removal, crash, read-view, 1 MiB, dependency, and
legacy-reader REDs. Delete the detailed plan's complete obsolete
owner/blob/maintenance API list after consumer audit.

### Task 6B: Dormant activation and eviction (1.5-2.5 engineer-days)

Extend only `LogicalWorldItemService` and its engine paging DTOs/tests with one
bounded complete metadata, identical-ID deterministic expiry heap, 64-intent/
64-KiB lossy cleanup queue, private-issuer-bound opaque tickets, backend-private
marker proof, injected trusted verifier, and exact single-consume
`commitPersistence(ticket,proof)`. Durable checkpoint survivor sum and runtime
metadata/expiry equality are separate; dirty delta stays resident/pinned under
its 1,024-entry/16-MiB bound. All due IDs die semantically on the
exact tick; page cleanup may lag/fail without resurrection. Test metadata cap,
historical churn, cleanup saturation/rediscovery, persistence failure,
ticket/proof mismatch/replay, metadata activation match, collision, and partial
pickup.

### Task 6C: v2 and restart (1.5-2 engineer-days)

Integrate the checkpoint/page adapter with the existing Phase 14 save root,
manifest, fresh-target restore, and v2 lifecycle. Legacy v1 read derives
saturating `spawnTick + 18_000`; v1 write additionally requires every stored
expiry to equal that derivation and rejects paging. Test clock-only advancement,
one-view raw-count/survivor/global-ID validation before one publication, no
load-order selection, no offline expiry, migration crash old-or-complete-new,
negative keys, and complete v2 round-trip.

### Task 6D: Projection rollback (0.5-1 engineer-day)

Modify the existing physical projection adapter only after engine paging is
stable. Guard callbacks against same-service reentrant mutation before any
canonical change; prebuild projections and guarantee exact logical/physical
rollback for callback and activation failure. Test every callback boundary.

### Task 6E: Acceptance (1-1.5 engineer-days plus platform smoke)

Run WorldItem, Task 4/5, Phase 14, session, inventory, interaction, physics, and
streaming regressions. Add process restart, arbitrary-order, cross-page
duplicate/cap/hash/count/dependency corruption, expiry/cleanup failure,
500-transition bounded-memory, ticket/proof/read-view, persistence-fault,
shutdown-leak, metrics, and v1/v2 acceptance. Record Windows and Apple Silicon
macOS smoke only when actually observed. Total implementation estimate is 6-10
engineer-days plus platform smoke.

Each subtask follows tests-only RED -> controller approval -> minimal GREEN ->
focused/proportional verification -> independent review. Do not advance while
a Critical or Important finding remains.

## Task 7: Gate 15D streaming policy and controller

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/GlobalPosition.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkStreamingPolicy.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkDesiredSets.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkPriority.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkStreamingObservation.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkStreamingDecision.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkStreamingController.java`
- Test: `engine/src/test/java/com/overlord/voxel/GlobalPositionTest.java`
- Test: `game/src/test/java/com/gaia/world/streaming/ChunkStreamingControllerTest.java`
- Test: `game/src/test/java/com/gaia/world/streaming/ChunkStreamingPolicyTest.java`

**Interfaces:**
- Produces: engine-owned immutable
  `GlobalPosition(ChunkKey,double localX,double y,double localZ)`, immutable
  `ChunkStreamingObservation(resident,requested)`, and
  `update(GlobalPosition,ChunkStreamingObservation): ChunkStreamingDecision`
  with immutable desired sets, admissions, cancellations, rejections, unload
  candidates, and monotonic desired epoch.
- Consumes: Task 1 policy and repository observations only; performs no IO/generation/GPU work.

- [x] **Step 1: Write desired-set/hysteresis REDs**

Test checked canonical `GlobalPosition`, exact 2/4/5 sets, 7-radius unload
eligibility, one-Chunk movement in four directions, negative traversal,
repeated boundary oscillation, stable nearest priority, teleport replacement,
queue-full farthest rejection, cancellations, and deterministic outputs from
shuffled resident/request sets. Assert stationary/equivalent observations keep
the same desired epoch, while only a desired-set identity change advances it.
Assert overlapping resident/request completion does not consume capacity and
custom materialized desired radii above 7 fail before enumeration.

- [x] **Step 2: Run controller RED**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.world.streaming.ChunkStreaming*Test' --console=plain --no-daemon
```

- [x] **Step 3: Implement pure controller policy**

Use authoritative global player position, checked square enumeration, immutable
sorted sets, explicit identity-driven epoch, and no side effects. Validate
`simulation <= render <= preload < unload` and all queue/frame budgets in one
policy record. Bound eager desired-set materialization to radius 7/225 keys and
derive outstanding work as `requested - resident`. `GlobalPosition` remains a
coordinate value only; Task 10 owns all origin/rebase/float-conversion behavior.

- [x] **Step 4: Verify controller GREEN**

Run focused tests plus GameSession/ProductLoop tests to prove no camera/interpolated position is used as streaming authority.

## Task 8: Gate 15E bounded load/generate/save pipeline

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/ChunkUnloadTicket.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkUnloadPreparation.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkUnloadResult.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkStreamingPipeline.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkWorkScheduler.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkWorkResult.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkStreamingDiagnostic.java`
- Create: `game/src/main/java/com/gaia/save/streaming/StreamedChunkUnloadPlan.java`
- Create: `game/src/main/java/com/gaia/save/streaming/StreamedChunkUnloadResult.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedWorldItemPageBackend.java`
- Modify: `game/src/main/java/com/gaia/world/WorldLoader.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryUnloadTransactionTest.java`
- Test: `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemDormantLifecycleTest.java`
- Test: `game/src/test/java/com/gaia/save/streaming/StreamedChunkUnloadTransactionTest.java`
- Test: `game/src/test/java/com/gaia/world/streaming/ChunkStreamingPipelineTest.java`
- Test: `game/src/test/java/com/gaia/world/streaming/ChunkStreamingFaultTest.java`

**Interfaces:**
- Produces:
  `prepareStreamingUnload(ChunkKey): ChunkUnloadPreparation`,
  `validateStreamingUnload(ChunkUnloadTicket): ChunkUnloadResult`,
  `cancelStreamingUnload(ChunkUnloadTicket): ChunkUnloadResult`, and
  `commitStreamingUnload(ChunkUnloadTicket): ChunkUnloadResult`; bounded
  admission for load/generate `32/4` and save `8/1`; one combined durable
  Chunk/WorldItem publication; cooperative cancellation; owner-thread result
  drain; bounded exact per-key diagnostics.
- Consumes: Task 7 decisions, exact repository tickets/captures, detached
  deterministic generation, Task4 store/staging, prepared WorldItem plans, and
  existing Task 6D logical/physical hibernation transaction.
- Excludes: `ChunkMeshManager`, mesh/GPU budgets, Task 9, Task 10, WAL,
  database, general transaction scheduler, and background GC/compaction.

- [x] **Step 1: Write exact unload transaction REDs**

Add real repository tests proving prepare retains resident bytes and exact
revision/state/failure, cancel restores them, final validation precedes commit,
commit is single-consume and deterministic, and foreign/stale/replayed/wrong-
thread tickets fail closed. Mutate or replace after prepare and prove the old
capture cannot commit.

- [x] **Step 2: Write combined-publication and WorldItem observation REDs**

Add the immutable `activeRevisionsInChunk` observation test. With a real
Task4 store and real WorldItem backend, stage an exact Chunk payload plus an
optional prepared WorldItem plan. Attack failure/crash checkpoints before the
final root and prove readers see the complete old root; after publication prove
the Chunk, page, WorldItem checkpoint, and required global dependency all come
from one new root. Prove persistence failure and physical hibernation failure
leave resident Chunk/WorldItems safe and stale save cannot publish.

- [x] **Step 3: Write bounded scheduler/pipeline REDs**

Simulate rapid travel while workers are blocked, queue saturation, generation throw, corrupt decode, running generation becoming unload-eligible, stale save, canceled work completing late, retry policy, and shutdown with queued work. Assert queue/active bounds after every operation.
Assert accepted equals queued plus active plus completed-undrained, with hard
`32/4` load-generation and `8/1` save limits. Drain/discard or queued
cancellation is the only token release. Assert diagnostics and retained work
metadata remain bounded.

- [x] **Step 4: Run the complete tests-only RED gate**

```powershell
.\gradlew.bat :engine:compileTestJava :game:compileTestJava --continue --console=plain --no-daemon
```

Expected: compilation fails only for the approved missing Task 8 contracts;
production hashes for frozen Tasks 6A-7 remain unchanged.

- [x] **Step 5: Implement opaque unload reservation**

Keep the exact immutable snapshot detached. Bind each private ticket to issuer,
owner thread, entry incarnation, key, revision, state, and failure identity.
Preparation adds a bounded per-key reservation without advancing revision.
Legacy unload cannot bypass a live pin. Mutation/replacement makes the
reservation stale. Cancel restores exact observable state; commit removes the
exact revalidated entry with no IO and no environmental failure branch.

- [x] **Step 6: Implement combined durable adapter**

Translate the exact Chunk capture and optional WorldItem plan into the existing
bounded candidate/staging mechanism. Merge mutations for the same Chunk before
staging so a transaction never repeats a key. Include required bounded globals
and publish one final root. Return an issuer-bound WorldItem durable proof only
after success. Failure returns no eviction authority.

- [x] **Step 7: Implement bounded stage handoff**

Use two fixed worker sets and capacity tokens spanning queued, active, and
completed-undrained work. Workers receive detached immutable inputs and never
repository/ticket/authority references. Owner processing performs exact ticket,
epoch, source, and revision validation before canonical publication. Failure
latches one bounded diagnostic and does not retry each frame.

- [x] **Step 8: Verify focused and proportional GREEN**

Run Task 8 focused tests, Task6A-E frozen subsets, Task7 controller tests,
repository/generator/store/session shutdown regressions, `git diff --check`,
bounded-state/static forbidden scans, and a zero-process audit. Inspect worker
lifecycle through tracked thread factories/latches rather than wall-clock
sleeps. Perform independent review; close every Critical/Important finding and
STOP before Task 9.

## Task 9: Gate 15E CPU mesh and GPU frame budgets

**Files:**
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java`
- Inspect, no change required: `engine/src/main/java/com/overlord/renderer/ChunkRenderObject.java`
- Inspect, no change required: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkMeshBudget.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkMeshStreamingBudgetTest.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`
- Test: `game/src/test/java/com/gaia/world/streaming/ChunkGpuOwnershipTest.java`

**Interfaces:**
- Produces: bounded mesh queue/active `32/2`, `pumpUploads(2)`, `pumpDestructions(4)`, stale upload rejection, and owner-thread metrics.
- Consumes: repository meshing tickets and Task 8 immutable results.
- `accepted` includes queued, active, completed-undrained, awaiting-upload, and
  retained failed-upload work. State transfer does not release capacity;
  terminal stale/discard/success or shutdown does.
- Normal frame work attempts at most two uploads/publications and four GPU
  destructions. Reentrant owner callbacks share the outermost pump's remaining
  allowances. Shutdown drains all owned GPU resources outside the frame budget
  while preserving aggregate failure reporting.

- [x] **Step 1: Write budget/ownership REDs**

Queue more than 32 meshes, complete results out of order, unload/revise before upload, cross one border with many ready meshes, and invoke every upload/destroy path from a worker. Assert at most 2 uploads and 4 destroys per owner frame and zero GL backend calls from workers.

- [x] **Step 2: Run mesh RED**

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.voxel.ChunkMeshStreamingBudgetTest' --tests 'com.overlord.voxel.ChunkMeshManagerTest' :game:test --tests 'com.gaia.world.streaming.ChunkGpuOwnershipTest' --console=plain --no-daemon
```

- [x] **Step 3: Add explicit budgets without changing GL ownership**

Bound pending CPU work and main-thread drains, validate ticket/revision before upload, and delay excess work to later frames. Keep all render-backend calls behind `MainThreadGuard`.

- [x] **Step 4: Verify mesh GREEN**

Run all engine voxel/renderer tests and game render architecture tests; assert macOS OpenGL 4.1 code paths remain unchanged.

## Task 10: UNKNOWN barrier and simulation-origin rebasing

**Files:**
- Create: `engine/src/main/java/com/overlord/physics/SimulationOrigin.java`
- Create: `engine/src/main/java/com/overlord/renderer/RenderOrigin.java`
- Create: `engine/src/main/java/com/overlord/physics/SpatialQueryResult.java`
- Create: `game/src/main/java/com/gaia/world/streaming/UnknownSpaceBarrier.java`
- Create: `game/src/main/java/com/gaia/session/streaming/SimulationOriginCoordinator.java`
- Modify: `engine/src/main/java/com/overlord/physics/CollisionWorld.java`
- Modify: `engine/src/main/java/com/overlord/physics/BlockRaycast.java`
- Modify: `engine/src/main/java/com/overlord/physics/PhysicsBody.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Camera.java`
- Modify: `engine/src/main/java/com/overlord/renderer/ChunkRenderObject.java`
- Modify: `game/src/main/java/com/gaia/worlditem/PhysicalWorldItemSystem.java`
- Modify: bounded transient/particle local-position owners as required by RED
- Test: `game/src/test/java/com/gaia/world/streaming/UnknownSpaceBarrierTest.java`
- Test: `game/src/test/java/com/gaia/session/streaming/SimulationOriginCoordinatorTest.java`
- Test: `engine/src/test/java/com/overlord/physics/LargeCoordinatePhysicsTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/RenderOriginTest.java`

**Interfaces:**
- Produces: checked `GlobalPosition <-> local Vector3f`, atomic `rebase(old,new,participants)`, and unavailable collision/raycast results.
- Consumes: Task 1 coordinate policy and Task 2 availability; preserves canonical block authority.
- Task 10 provides composable participant seams and isolated atomicity tests;
  Task 11 alone installs the coordinator into the production session fixed-step
  and save/render composition.
- Origin-aware collision/raycast returns explicit `AVAILABLE`, `UNKNOWN`, or
  `FAILED` with the canonical blocked key, and checks availability before voxel
  access. Legacy zero-origin APIs remain source-compatible.
- Rebase participant preparation is side-effect-free. Prepared commit is
  allocation-free, callback-free, and non-throwing. The coordinator publishes
  the new simulation/render origin only after every preparation succeeds.
- Physics previous/current positions and presentation interpolation rebase
  together. Canonical DTOs, IDs, keys, revisions, velocities, and worker
  results are bit-for-bit unchanged.

- [x] **Step 1: Write precision/barrier REDs**

Cover positive/negative large keys, checked round-trip and too-distant rejection,
boundary movement, local transforms remaining small/finite, exact global block
raycast, collision at a loaded edge, availability-before-sample, player blocked
at UNKNOWN, no global pause, noclip/teleport wait, WorldItem freeze, and failed
Chunk diagnostic.

- [x] **Step 2: Write atomic rebase REDs**

Inject failure while preparing each participant and assert zero commit/origin
publication. Reject same-coordinator reentrant mutation before commit. On
success assert player, camera, physics bodies, active WorldItems,
transients/particles, Chunk render replacements, previous/interpolated
positions, and both origins change together once. Assert GPU meshes and worker
results remain canonical and unaffected.

- [x] **Step 3: Run precision RED**

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.physics.LargeCoordinatePhysicsTest' --tests 'com.overlord.renderer.RenderOriginTest' :game:test --tests 'com.gaia.world.streaming.UnknownSpaceBarrierTest' --tests 'com.gaia.session.streaming.SimulationOriginCoordinatorTest' --console=plain --no-daemon
```

- [x] **Step 4: Implement one explicit origin transaction**

Prebuild all conversions, validate finite/range, commit only at the owner fixed-step/frame boundary, and forbid per-system ad-hoc offsets. Availability checks precede collision/raycast voxel sampling.

- [x] **Step 5: Verify large-coordinate GREEN**

Run all physics, interaction, renderer, feedback, WorldItem, session restore, and save round-trip tests. Stop for design review if a participant cannot join the atomic transaction.

## Task 11: Session composition, metrics, HUD, save/shutdown

**Files:**
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetrics.java`
- Create: `game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetricsRecorder.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFrame.java`
- Modify: `game/src/main/java/com/gaia/save/session/SaveCoordinator.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: HUD/debug frame types and presenter paths that render F3 metrics
- Test: `game/src/test/java/com/gaia/session/ChunkStreamingSessionIntegrationTest.java`
- Test: `game/src/test/java/com/gaia/session/ChunkStreamingShutdownTest.java`
- Test: `game/src/test/java/com/gaia/GameBootstrapStreamingCompositionTest.java`

**Interfaces:**
- Produces: one real production composition, immutable per-frame metrics, truthful `Streaming terrain...`, explicit retry, save checkpoint after streamed commits, and deterministic shutdown.
- Consumes: Tasks 4–10; F3 is read-only.

- [x] **Step 1: Write composition/order REDs**

Assert the controller observes authoritative player global position after fixed-step mutation, desired decisions precede pipeline admission, owner publications/uploads respect frame budgets, metrics capture afterward, and F3/presenter performs no IO or policy mutation.

- [x] **Step 2: Write save/shutdown REDs**

Cover Pause Save, Save & Quit, dirty Return, modified save failure, pending
WorldItem hibernate/checkpoint publication, cancellation of untouched work,
primary/suppressed close ordering, queued workers, repeated sessions, and no
checkpoint/session close before streamed commits validate.

- [x] **Step 3: Run integration RED**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.session.ChunkStreaming*Test' --tests 'com.gaia.GameBootstrapStreamingCompositionTest' --console=plain --no-daemon
```

- [x] **Step 4: Wire the real runtime minimally**

Compose one controller/pipeline/store/origin coordinator per session using constructor injection. Register shutdown in dependency order. Extend frame/debug snapshots with immutable metrics; keep UI disabled from direct filesystem or executor access.

- [x] **Step 5: Verify session GREEN**

Run all game session, shell, save, settings, audio, physics, WorldItem, bootstrap structure, and ProductLoop suites.

## Task 12: Gate 15F 500-transition soak, docs, and platform acceptance

**Files:**
- Create: `game/src/test/java/com/gaia/world/streaming/ChunkStreamingSoakTest.java`
- Create: `game/src/test/java/com/gaia/world/streaming/ChunkStreamingPerformanceMeasurementTest.java`
- Create: `docs/architecture/infinite-world-streaming.md`
- Create: `docs/testing/phase-15-infinite-world-streaming-acceptance.md`
- Create: `docs/agent-handoffs/phase-15-handoff.md`
- Modify: `README.md`
- Modify: `KNOWN_ISSUES.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/architecture/current-baseline.md`

**Interfaces:**
- Produces: deterministic structural soak evidence, local observations, exact Windows/macOS status, final protected interfaces and known coordinate/storage limits.

- [x] **Step 1: Write the structural soak**

Drive at least 500 Chunk transitions with east/west/north/south, negative keys,
reversals, rapid travel, cancellation, at least two origin rebases, untouched
regenerate checks, distant block modification, WorldItem same-ID
hibernate/activate and expiry, Save & Quit style restart, and return to earlier
coordinates. Assert after each epoch that resident/queue counts stay within
policy bounds; active DTO + decoded dormant DTO + evicted-unexpired metadata +
unique pending WorldItems stay at most 1,024; survivor sum/metadata/expiry-index
agree while physical descriptor/dependency counts agree separately; cleanup
intents/bytes/tombstones stay bounded; due items remain dead
through cleanup failure; revisited expired pages converge without a global
scan; and retained state does not grow with total distance.

- [x] **Step 2: Run forced focused soak and fault matrices**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.world.streaming.ChunkStreamingSoakTest' --tests 'com.gaia.world.streaming.ChunkStreamingPerformanceMeasurementTest' --rerun-tasks --console=plain --no-daemon
```

Record archive/file counts, resident peaks, queue peaks, canceled/stale totals, rebase count, and latency observations. Do not add FPS/time assertions.

- [x] **Step 3: Run repository-wide verification**

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:installDist --console=plain --no-daemon
git diff --check
git status --short --untracked-files=all
```

Audit packaged shaders/resources/OpenAL natives, no absolute paths, no generated output, no parallel Chunk/WorldItem authority, and no OpenGL call reachable from streaming workers.

- [x] **Step 4: Perform Windows interactive Gate 15F**

From a representative world: travel beyond the old radius in positive and negative directions; inspect seams/void barriers; return across unload; modify a distant Chunk; force unload/reload; drop/pick up WorldItems near a boundary; Save & Quit/relaunch; verify exact modifications and stable IDs; inspect bounded metrics; resize/Alt+Tab; close without workers/files alive. Record exact duration and environment only when observed.

- [ ] **Step 5: Perform Apple Silicon macOS Gate 15F — NOT RUN / PENDING**

Repeat representative traversal/save/reload on native arm64, including case/path behavior, Retina/resize, focus recovery, OpenGL 4.1 streaming stability, installDist, and clean shutdown. Mark every unavailable numeric/environment datum as not supplied; never infer it from Windows.

- [x] **Step 6: Write architecture, acceptance, and handoff docs**

Document the lifecycle diagram, checked addressing, simulation-origin policy, modified-only format and v1 migration, WorldItem paging, budgets/metrics, UNKNOWN semantics, deterministic hashes, soak results, platform status, Phase 16/19 extensions, known limits, full file inventory, test commands/results, `git diff --stat`, suggested commit `feat(world): add deterministic infinite chunk streaming`, and PR title `feat(world): implement bounded infinite-world chunk streaming`.

- [x] **Step 7: Final independent reviews and stop — CLOSED / READY**

Obtain engine-owner and game/save-owner reviews. Resolve all Critical/Important/Minor findings with tests-first correction rounds. Stop with the complete unstaged candidate. Do not stage, commit, push, open a PR, merge, tag, release, or begin Phase 16 without explicit authorization.

## Plan self-review checklist

- Gate 15A: Tasks 1–2 cover key width, negative/large mapping, ordering, lifecycle, coalescing, and stale publication.
- Gate 15B: Task 3 covers pure world-coordinate generation, stages, seams, and stable decoration ownership.
- Gate 15C: Tasks 4–6 cover modified-only storage, v1 migration, atomicity, and same-authority WorldItem paging.
- Gate 15D: Task 7 covers exact 2/4/5/7 policy, hysteresis, priority, and fast travel.
- Gate 15E: Tasks 8–9 and 11 cover bounded workers, stale/cancel behavior, frame budgets, GPU ownership, composition, and shutdown.
- Gate 15F: Tasks 10 and 12 cover coordinate precision, atomic rebasing, UNKNOWN safety, 500 transitions, performance observations, Windows/macOS, docs, and handoff.
- Phase 16/19 and M3 extension/deferral boundaries are explicit in Tasks 4, 12, and global constraints.
- No task creates a second `ChunkRepository`, World store, save root, WorldItem service, key type, or GPU worker path.
