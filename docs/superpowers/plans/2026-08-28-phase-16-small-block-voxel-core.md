# Phase 16 Small-Block Voxel Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do not begin until the controller approves this spec and plan and resolves the implementation execution mode.

**Goal:** Add a sparse production `4 x 4 x 4` DETAIL parent representation that shares the existing Chunk authority, revision, streaming persistence, raycast, collision, mesh, and GPU lifecycle.

**Architecture:** DETAIL table membership inside the owning `Chunk` is the physical discriminator; a DETAIL parent always has backing FULL byte `0` and nonzero occupancy. `ChunkSnapshot` carries immutable FULL and DETAIL state through existing worker, save, restore, and meshing boundaries, while game/save code translates runtime byte IDs through the existing `BlockRegistry` to a required-if-present `detail-blocks` extension.

**Tech Stack:** Java 17, JUnit Jupiter 6.1.1, Gradle Wrapper, JOML, existing LWJGL/OpenGL 4.1 renderer, existing streamed Chunk store/codecs.

**Spec:** `docs/superpowers/specs/2026-08-28-phase-16-small-block-voxel-core-design.md`

## Global Constraints

- Branch/base must remain `feat/small-voxel-core` at the controller-approved descendant of `fa852fbef2d2292a5778e385b8775b8c81f70ad1`.
- Never work on, commit to, push, or merge `main`.
- No staging, commit, push, PR, merge, tag, or release is authorized by this plan.
- Preserve engine-to-game dependency direction; engine DETAIL storage contains runtime bytes, never game objects.
- Production scale is exactly `DETAIL_4`; no 8, 16, or dynamic scale mode.
- A parent is exactly FULL or DETAIL. DETAIL membership requires backing FULL byte `0` and nonzero occupancy.
- `MAX_DETAIL_PARENTS_PER_CHUNK` is exactly 1,024 for Phase 16 v1.
- `ChunkRepository` remains the sole Chunk revision, dirty, stale, publication, and unload authority.
- `ChunkMeshInput` remains exactly nine `ChunkSnapshot` record components.
- `ChunkMeshingClaim` remains the separate claim ID/key/revision/input capability.
- Workers receive immutable detached data only; they never read mutable World, publish repositories, or call OpenGL.
- Keep Phase 15 queue and owner-frame limits unchanged.
- `UNKNOWN` and `FAILED` never become AIR or miss.
- Preserve the existing save root, bounded staging, and PR #27 durable acknowledgement rule.
- Use the Gradle Wrapper. Keep Java 17, OpenGL 4.1, and GLSL 410 compatibility.
- Run focused tests per Task and Gate; run the full repository matrix only for the final candidate.

## Planned file structure

### Engine canonical data and authority

- `engine/src/main/java/com/overlord/voxel/VoxelScale.java` — production DETAIL scale.
- `engine/src/main/java/com/overlord/voxel/LocalSubVoxelPosition.java` — checked local coordinates and deterministic index.
- `engine/src/main/java/com/overlord/voxel/ParentCellState.java` — sealed FULL/DETAIL discriminator.
- `engine/src/main/java/com/overlord/voxel/FullCellState.java` — immutable FULL runtime ID.
- `engine/src/main/java/com/overlord/voxel/DetailCellState.java` — immutable occupancy and 64 runtime IDs.
- `engine/src/main/java/com/overlord/voxel/DetailChunkSnapshot.java` — compact ordered immutable sparse table.
- `engine/src/main/java/com/overlord/voxel/DetailStorage.java` — package-private bounded mutable Chunk storage.
- `engine/src/main/java/com/overlord/voxel/ParentCellObservation.java` — immutable state plus exact Chunk revision.
- `engine/src/main/java/com/overlord/voxel/ParentCellObservationResult.java` — AVAILABLE/UNKNOWN/FAILED observation result.
- `engine/src/main/java/com/overlord/voxel/ChunkDetailMutation.java` — sealed repository mutation commands.
- `engine/src/main/java/com/overlord/voxel/ChunkDetailMutationOutcome.java` — exact applied/rejection result.
- Existing `Chunk`, `ChunkSnapshot`, `ChunkGenerationData`, `ChunkRepository`, `World`, `ChunkMeshInput`, `ChunkMeshBuilder`, and AO types are modified only for typed canonical state.

### Engine interaction, raycast, and collision APIs

- `engine/src/main/java/com/overlord/interaction/api/DetailMutationService.java`.
- `engine/src/main/java/com/overlord/interaction/api/FullToDetailRequest.java`.
- `engine/src/main/java/com/overlord/interaction/api/DetailMutationRequest.java`.
- `engine/src/main/java/com/overlord/interaction/api/DetailToFullRequest.java`.
- `engine/src/main/java/com/overlord/interaction/api/DetailMutationResult.java`.
- `engine/src/main/java/com/overlord/physics/RaycastCellTarget.java` — sealed FULL/DETAIL hit provenance.
- `engine/src/main/java/com/overlord/physics/FullRaycastTarget.java`.
- `engine/src/main/java/com/overlord/physics/DetailRaycastTarget.java`.
- `engine/src/main/java/com/overlord/physics/DetailCollisionBoxMerger.java`.
- Existing raycast/collision hit, resolver, and kernel files are extended rather than replaced.

### Game/save integration

- `game/src/main/java/com/gaia/interaction/GaiaDetailMutationService.java` — registry translation and main-thread service.
- `game/src/main/java/com/gaia/save/streaming/DetailBlocksCodec.java` — exact v1 extension codec.
- `game/src/main/java/com/gaia/debug/DetailDebugTools.java` — development-only commands.
- `game/src/main/java/com/gaia/debug/DetailFixturePattern.java` — deterministic fixture definitions.
- Existing BlockRegistry adapters, streamed save/load composition, and `GameSessionFactory` are extended.

### Tools and documentation

- `tools/src/main/java/com/gaia/tools/DetailVoxelPerformanceFixture.java`.
- `tools/src/test/java/com/gaia/tools/DetailVoxelPerformanceFixtureTest.java`.
- `docs/architecture/small-block-voxel-core.md`.
- `docs/testing/phase-16-small-block-voxel-acceptance.md`.
- `docs/agent-handoffs/phase-16-handoff.md`.

---

## Pre-implementation safety checkpoint

### Task 0: Quarantine pre-existing worktree changes

**Gate:** Entry

**Boundary:** Repository safety; no source change.

**Files:**

- Quarantine without editing: `game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetricsRecorder.java`
- Quarantine without editing: `game/src/test/java/com/gaia/session/ChunkStreamingSessionIntegrationTest.java`
- Never touch: `dist/`

**Interfaces:**

- Consumes: current working tree and approved base.
- Produces: an implementation-session record of excluded paths and hashes.

- [ ] **Step 1: Reverify branch and remote base**

Run:

```powershell
git fetch origin --prune
git branch --show-current
git rev-parse HEAD
git rev-parse origin/main
git rev-list --left-right --count HEAD...origin/main
```

Expected: branch `feat/small-voxel-core`; `origin/main` is the approved Phase 15 commit or controller-approved descendant. Stop on unapproved drift.

- [ ] **Step 2: Record the quarantine without modifying it**

Run:

```powershell
git status --short
git diff -- game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetricsRecorder.java
git diff -- game/src/test/java/com/gaia/session/ChunkStreamingSessionIntegrationTest.java
Get-FileHash game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetricsRecorder.java
Get-FileHash game/src/test/java/com/gaia/session/ChunkStreamingSessionIntegrationTest.java
```

Expected: only the known tracked paths plus `dist/` are pre-existing. Compare the recomputed hashes with the design-spec quarantine baseline and record the result in the implementation task log.

- [ ] **Step 3: Enforce path exclusion**

Use path-scoped diffs after every Gate:

```powershell
git diff --name-only
git status --short
```

Expected: the two quarantined paths remain byte-identical to their recorded hashes and are absent from Phase 16 patches. Create new Phase 16 integration tests instead of editing the quarantined test. Stop if either file becomes necessary.

---

## Gate 16A: Canonical data model

### Task 1: DETAIL scale, coordinate, and immutable cell invariants

**Boundary:** Engine API.

**Files:**

- Create: `engine/src/main/java/com/overlord/voxel/VoxelScale.java`
- Create: `engine/src/main/java/com/overlord/voxel/LocalSubVoxelPosition.java`
- Create: `engine/src/main/java/com/overlord/voxel/ParentCellState.java`
- Create: `engine/src/main/java/com/overlord/voxel/FullCellState.java`
- Create: `engine/src/main/java/com/overlord/voxel/DetailCellState.java`
- Create: `engine/src/test/java/com/overlord/voxel/DetailCellStateTest.java`

**Interfaces:**

- Produces: `VoxelScale.DETAIL_4`, `LocalSubVoxelPosition#index()`, sealed `ParentCellState`, `FullCellState(byte)`, and immutable `DetailCellState(long, byte[])`.
- Consumes: existing runtime byte block identity where `0` is AIR.

- [ ] **Step 1: Write RED invariant tests**

Add tests including:

```java
@Test
void indexIsXFastestAndRoundTripsAll64Coordinates() {
    assertEquals(0, new LocalSubVoxelPosition(0, 0, 0).index());
    assertEquals(1, new LocalSubVoxelPosition(1, 0, 0).index());
    assertEquals(4, new LocalSubVoxelPosition(0, 1, 0).index());
    assertEquals(16, new LocalSubVoxelPosition(0, 0, 1).index());
    assertEquals(63, new LocalSubVoxelPosition(3, 3, 3).index());
}

@Test
void detailRequiresNonzeroMaskAndExactOccupancyIdAgreement() {
    assertThrows(IllegalArgumentException.class,
            () -> new DetailCellState(0L, new byte[64]));
    byte[] ids = new byte[64];
    ids[0] = 7;
    DetailCellState state = new DetailCellState(1L, ids);
    ids[0] = 0;
    assertEquals(7, Byte.toUnsignedInt(
            state.blockId(new LocalSubVoxelPosition(0, 0, 0))));
}
```

Also cover coordinates outside `[0,3]`, set-bit/AIR mismatch, clear-bit/nonzero mismatch, defensive copies, equality, and all 64 index values.

- [ ] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.DetailCellStateTest"
```

Expected: compilation fails because the new types do not exist.

- [ ] **Step 3: Implement minimal immutable values**

Implement `DETAIL_4(4)`, checked coordinates, X-fastest indexing, the sealed interface, and defensive-copy detail state. Do not add dynamic scale registration.

- [ ] **Step 4: Run GREEN and adjacent block identity tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.DetailCellStateTest"
.\gradlew.bat :game:test --tests "com.gaia.blocks.BlockRegistryTest"
```

Expected: PASS.

- [ ] **Step 5: Review checkpoint without staging**

```powershell
git diff --check -- engine/src/main/java/com/overlord/voxel engine/src/test/java/com/overlord/voxel/DetailCellStateTest.java
```

### Task 2: Bounded sparse Chunk-owned DETAIL storage

**Boundary:** Engine internal storage.

**Files:**

- Create: `engine/src/main/java/com/overlord/voxel/DetailStorage.java`
- Modify: `engine/src/main/java/com/overlord/voxel/Chunk.java`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkDetailStorageTest.java`

**Interfaces:**

- Consumes: Task 1 state values.
- Produces: `Chunk.cellState(int,int,int)`, package-private canonical replacement primitives, and `Chunk.MAX_DETAIL_PARENTS_PER_CHUNK == 1024`.

- [ ] **Step 1: Write RED physical-discriminator tests**

Test exact behavior:

```java
@Test
void detailMembershipForcesBackingFullByteToAir() {
    Chunk chunk = new Chunk(32);
    chunk.setBlock(2, 3, 4, (byte) 9);
    chunk.replaceCanonicalCell(2, 3, 4,
            DetailCellState.uniform((byte) 9));

    assertInstanceOf(DetailCellState.class, chunk.cellState(2, 3, 4));
    assertEquals(0, chunk.rawFullBlockForInvariant(2, 3, 4));
    assertThrows(IllegalStateException.class,
            () -> chunk.getBlock(2, 3, 4));
}
```

Cover sorted parent indices, zero allocation for an ordinary Chunk, first DETAIL insertion, final removal to FULL AIR, explicit uniform compaction, cap 1,024, entry 1,025 rejection, negative/out-of-range local coordinates, and no dual-authority intermediate exposed through `cellState`.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkDetailStorageTest"
```

Expected: FAIL because sparse storage and typed access are absent.

- [ ] **Step 3: Implement sorted compact storage**

Use no stored empty DETAIL sentinel. A FULL-only `Chunk` has no physical DETAIL storage allocation; every stored table has at least one nonzero-mask entry and exact-length sorted `short[]`, `long[]`, and flattened `byte[]`. A shared non-null empty object, if exposed, is an API/view only and is never retained or interpreted as DETAIL. Implement binary search by unsigned parent index and prepare replacement arrays before swapping them into `Chunk`. Keep mutable typed/raw invariant access package-private and under the repository entry lock; public consumers use repository observations or snapshots.

- [ ] **Step 4: Run GREEN and existing Chunk tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkDetailStorageTest"
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkRepositoryTest"
```

Expected: PASS; existing FULL behavior remains unchanged.

- [ ] **Step 5: Review checkpoint without staging**

```powershell
git diff --check -- engine/src/main/java/com/overlord/voxel/Chunk.java engine/src/main/java/com/overlord/voxel/DetailStorage.java engine/src/test/java/com/overlord/voxel/ChunkDetailStorageTest.java
```

### Task 3: Immutable DETAIL-aware ChunkSnapshot and detached generation data

**Boundary:** Engine API.

**Files:**

- Create: `engine/src/main/java/com/overlord/voxel/DetailChunkSnapshot.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkSnapshot.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkGenerationData.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepositorySnapshot.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkSnapshotTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/DetailChunkSnapshotTest.java`

**Interfaces:**

- Consumes: Task 2 compact representation.
- Produces: `ChunkSnapshot.details()`, `cellState`, `canonicalContentEquals`, `canonicalContentHash`, and `ChunkGenerationData.details()` with an empty default overload.

- [ ] **Step 1: Write RED snapshot tests**

Add:

```java
@Test
void equalityAndContentHashIncludeCanonicalDetail() {
    ChunkSnapshot first = snapshotWithDetail((byte) 7, 9L);
    ChunkSnapshot same = snapshotWithDetail((byte) 7, 9L);
    ChunkSnapshot changed = snapshotWithDetail((byte) 8, 9L);

    assertEquals(first, same);
    assertEquals(first.canonicalContentHash(), same.canonicalContentHash());
    assertNotEquals(first, changed);
    assertNotEquals(first.canonicalContentHash(), changed.canonicalContentHash());
}
```

Cover defensive copies, canonical order, duplicate/out-of-range rejection, backing byte conflict, 1,024 cap, typed reads, byte-read throw on DETAIL, and empty-detail compatibility constructors.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkSnapshotTest" --tests "com.overlord.voxel.DetailChunkSnapshotTest"
```

Expected: FAIL because snapshots are byte-only.

- [ ] **Step 3: Implement canonical snapshot payload**

Add the compact detail snapshot and include it in all factories, equality, hash, canonical content equality, and deterministic SHA-256 content hashing. Keep lifecycle metadata out.

- [ ] **Step 4: Run GREEN and restore snapshot regressions**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkSnapshotTest" --tests "com.overlord.voxel.DetailChunkSnapshotTest" --tests "com.overlord.voxel.ChunkRepositoryTest" --tests "com.overlord.voxel.ChunkRepositoryPersistenceTest"
```

Expected: PASS.

- [ ] **Step 5: Review checkpoint without staging**

```powershell
git diff --check -- engine/src/main/java/com/overlord/voxel engine/src/test/java/com/overlord/voxel
```

### Task 4: Atomic typed parent observation and safe legacy byte seams

**Boundary:** Engine API.

**Files:**

- Create: `engine/src/main/java/com/overlord/voxel/ParentCellObservation.java`
- Create: `engine/src/main/java/com/overlord/voxel/ParentCellObservationResult.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `engine/src/main/java/com/overlord/voxel/World.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/TypedParentObservationTest.java`

**Interfaces:**

- Produces:

```java
ParentCellObservationResult ChunkRepository.observeCell(
        int worldX, int y, int worldZ);

ParentCellObservationResult World.observeCell(
        int worldX, int y, int worldZ);
```

- `ParentCellObservation` contains canonical `ChunkKey`, local parent coordinates, exact owning revision, and immutable `ParentCellState`.

- [ ] **Step 1: Write RED observation tests**

Test AVAILABLE FULL, AVAILABLE DETAIL, exact revision, UNKNOWN missing Chunk, FAILED resident Chunk, negative canonical coordinates, and large checked coordinates. Verify `World.getBlock` and repository byte reads throw on DETAIL rather than return zero.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.TypedParentObservationTest"
```

Expected: FAIL because atomic typed observation is absent.

- [ ] **Step 3: Implement observation under the existing entry lock**

Sample availability, entry incarnation, revision, and state in one repository operation. Do not allocate or publish a Chunk for an UNKNOWN read.

- [ ] **Step 4: Run GREEN and coordinate regressions**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.TypedParentObservationTest" --tests "com.overlord.voxel.ChunkCoordinatePolicyTest"
```

Expected: PASS.

- [ ] **Step 5: Review checkpoint without staging**

```powershell
git diff --check -- engine/src/main/java/com/overlord/voxel engine/src/test/java/com/overlord/voxel
```

### Task 5: Snapshot propagation and architecture guards

**Boundary:** Engine API and architecture enforcement.

**Files:**

- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshInput.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshInputTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshLifecycleStructureTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/DetailArchitectureContractTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/DetailSnapshotBoundTest.java`

**Interfaces:**

- Consumes: DETAIL-aware snapshots and typed state.
- Produces: `ChunkMeshInput.cellState(int,int,int)` across the existing halo; unchanged nine-component record shape.

- [ ] **Step 1: Write RED structural and bound tests**

Assert:

```java
assertEquals(9, ChunkMeshInput.class.getRecordComponents().length);
assertTrue(Arrays.stream(ChunkMeshInput.class.getRecordComponents())
        .allMatch(component -> component.getType() == ChunkSnapshot.class));
```

Also assert a claimed center and neighbor DETAIL state survives immutable capture, `ChunkMeshingClaim` remains separate, the snapshot backing formula is `74 * count + 256 <= 76_032` at 1,024, and forbidden production sources do not call byte APIs from raycast/collision/mesh/save packages.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.DetailArchitectureContractTest" --tests "com.overlord.voxel.DetailSnapshotBoundTest" --tests "com.overlord.voxel.ChunkMeshInputTest"
```

Expected: FAIL until snapshot propagation and typed halo access exist.

- [ ] **Step 3: Implement snapshot propagation only**

Update repository snapshot/unload/canonical restore/meshing capture and `ChunkMeshInput.cellState`. Do not change record components or add claims to snapshots.

- [ ] **Step 4: Run Gate 16A focused matrix**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.Detail*" --tests "com.overlord.voxel.ChunkSnapshotTest" --tests "com.overlord.voxel.ChunkRepositoryTest" --tests "com.overlord.voxel.ChunkMeshInputTest" --tests "com.overlord.voxel.ChunkMeshLifecycleStructureTest"
```

Expected: PASS.

- [ ] **Step 5: Gate 16A quarantine and diff check**

Re-run Task 0 hashes, then:

```powershell
git diff --check
git diff --name-only
```

Expected: quarantined files unchanged; `dist/` untouched.

---

## Gate 16B: Mutation, revision, dirty, and streamed save

### Task 6: Atomic repository DETAIL conversions and edits

**Boundary:** Engine API.

**Files:**

- Create: `engine/src/main/java/com/overlord/voxel/ChunkDetailMutation.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkDetailMutationOutcome.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMutationOutcome.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkDetailMutationTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkDetailMutationConcurrencyTest.java`

**Interfaces:**

- Produces sealed commands:

```java
ChunkDetailMutation.ConvertFullToDetail(
        int x, int y, int z, long expectedRevision, byte expectedFullId)

ChunkDetailMutation.SetSubVoxel(
        int x, int y, int z, long expectedRevision,
        ParentCellState expectedState,
        LocalSubVoxelPosition position, byte replacementId)

ChunkDetailMutation.CompactDetailToFull(
        int x, int y, int z, long expectedRevision,
        DetailCellState expectedState, byte replacementFullId)
```

- Produces `ChunkRepository.mutateDetail(ChunkDetailMutation)` and the exact status set from the spec.

- [ ] **Step 1: Write RED transition tests**

Cover:

```java
@Test
void fullToDetailPublishesOneCanonicalRevision() {
    long before = repository.revision(key);
    ChunkDetailMutationOutcome result = repository.mutateDetail(
            new ChunkDetailMutation.ConvertFullToDetail(
                    x, y, z, before, (byte) 7));

    assertEquals(APPLIED, result.status());
    assertEquals(before + 1, result.resultingChunkRevision());
    DetailCellState detail = assertInstanceOf(
            DetailCellState.class,
            repository.observeCell(x, y, z).observation().orElseThrow().state());
    assertEquals(-1L, detail.occupancyMask());
}
```

Also test FULL AIR first placement, final clear to FULL AIR, explicit uniform compaction, nonuniform compaction rejection, stale Chunk revision, expected representation/state conflict, UNKNOWN/FAILED, finalized unload, neighbor/diagonal dirty revisions, cap exhaustion, and concurrent observation never seeing dual authority.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkDetailMutationTest" --tests "com.overlord.voxel.ChunkDetailMutationConcurrencyTest"
```

Expected: FAIL because repository commands are absent.

- [ ] **Step 3: Implement repository transaction**

Prepare replacement arrays before publication, revalidate under the entry lock, reuse `prepareEntryMutation`, `reserveRevisions`, `ChunkDirtyTracker.affectedByBlock`, and repository dirty-outcome helpers. Do not add a revision counter or dirty collection.

- [ ] **Step 4: Run GREEN and FULL mutation regressions**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkDetailMutation*" --tests "com.overlord.voxel.ChunkRepositoryTest" --tests "com.overlord.interaction.DefaultWorldMutationServiceTest"
```

Expected: PASS; FULL mutation against DETAIL rejects instead of reading AIR.

- [ ] **Step 5: Review checkpoint without staging**

```powershell
git diff --check -- engine/src/main/java/com/overlord/voxel engine/src/test/java/com/overlord/voxel
```

### Task 7: Narrow game-facing DetailMutationService

**Boundary:** Engine interaction API plus game integration.

**Files:**

- Create: `engine/src/main/java/com/overlord/interaction/api/DetailMutationService.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/FullToDetailRequest.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/DetailMutationRequest.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/DetailToFullRequest.java`
- Create: `engine/src/main/java/com/overlord/interaction/api/DetailMutationResult.java`
- Create: `game/src/main/java/com/gaia/interaction/GaiaDetailMutationService.java`
- Create: `game/src/test/java/com/gaia/interaction/GaiaDetailMutationServiceTest.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`

**Interfaces:**

- `FullToDetailRequest` carries context, canonical parent coordinate, expected Chunk revision, and expected FULL `ResourceLocation`.
- `DetailMutationRequest` carries context, coordinate, expected revision, exact `ParentCellState`, local position, and `Optional<ResourceLocation>` replacement where empty means clear.
- `DetailToFullRequest` carries context, coordinate, expected revision, exact expected detail state, and requested FULL `ResourceLocation`.

- [ ] **Step 1: Write RED service tests**

Test owner-thread enforcement, actor/tick preservation, registry translation, unknown material rejection, stale mapping, exact old/new state, and that debug-style calls cannot obtain or mutate `DetailStorage` directly.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.interaction.GaiaDetailMutationServiceTest"
```

Expected: FAIL because the service does not exist.

- [ ] **Step 3: Implement registry adapter and composition**

Resolve all game identities through the existing `BlockRegistry`, call only `ChunkRepository.mutateDetail`, map exact statuses, and inject the service through session composition. Do not register a second material map.

- [ ] **Step 4: Run GREEN and existing interaction tests**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.interaction.GaiaDetailMutationServiceTest" --tests "com.gaia.interaction.*"
```

Expected: PASS.

- [ ] **Step 5: Review checkpoint without staging**

```powershell
git diff --check -- engine/src/main/java/com/overlord/interaction/api game/src/main/java/com/gaia/interaction game/src/test/java/com/gaia/interaction
```

### Task 8: Exact required-if-present DetailBlocksCodec v1

**Boundary:** Game/save integration.

**Files:**

- Create: `game/src/main/java/com/gaia/save/streaming/DetailBlocksCodec.java`
- Create: `game/src/test/java/com/gaia/save/streaming/DetailBlocksCodecTest.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedExtensionSupportRegistry.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedChunkCodec.java`
- Modify: `game/src/test/java/com/gaia/save/streaming/StreamedChunkCodecTest.java`

**Interfaces:**

- Produces `DetailBlocksCodec.encode(ChunkSnapshot, BlockRegistry)` and `decode(byte[], int, byte[], BlockRegistry)` returning `DetailChunkSnapshot` or bounded diagnostics.
- Enforces magic `GLD1`, scale `4`, flags `0`, 128-byte sorted palette names, 74-byte entries, count 1..1,024, and no trailing bytes.

- [ ] **Step 1: Write RED literal and failure tests**

Create one exact hex literal for a one-parent, one-material extension and assert byte-for-byte stability. Add failures for unsupported descriptor version, `required == false`, magic, scale, flags, palette order/duplicate/length, unknown material, parent count 1,025, duplicate/out-of-range parent, zero mask, occupancy/code mismatch, nonzero FULL backing, and trailing bytes.

Core round-trip assertion:

```java
byte[] encoded = codec.encode(snapshot, registry);
DetailChunkSnapshot decoded = codec.decode(
        encoded, snapshot.worldHeight(), snapshot.copyFullBlocks(), registry)
        .details().orElseThrow();
assertEquals(snapshot.details(), decoded);
assertEquals(108_938L, DetailBlocksCodec.maximumEncodedBytes());
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.save.streaming.DetailBlocksCodecTest" --tests "com.gaia.save.streaming.StreamedChunkCodecTest"
```

Expected: FAIL because semantic detail codec and required handling are absent.

- [ ] **Step 3: Implement exact codec and support policy**

Use big-endian `DataInputStream`/`DataOutputStream`, one-based palette material codes, canonical registry mapping, exact bound calculations, and stable diagnostics. Reject an optional `detail-blocks` descriptor even though unknown unrelated optional extensions retain existing behavior.

- [ ] **Step 4: Run GREEN and codec regression matrix**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.save.streaming.DetailBlocksCodecTest" --tests "com.gaia.save.streaming.StreamedChunkCodecTest" --tests "com.gaia.save.streaming.StreamedChunkStoreFaultTest"
```

Expected: PASS.

- [ ] **Step 5: Review checkpoint without staging**

```powershell
git diff --check -- game/src/main/java/com/gaia/save/streaming game/src/test/java/com/gaia/save/streaming
```

### Task 9: Streamed capture, no-op equality, and durable acknowledgement

**Boundary:** Game/save integration over existing engine snapshots.

**Files:**

- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedSessionSaveTarget.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedChunkPayload.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/StreamedChunkUnloadPlan.java`
- Modify: `game/src/main/java/com/gaia/world/streaming/ChunkStreamingPipeline.java`
- Create: `game/src/test/java/com/gaia/save/streaming/DetailStreamedPersistenceTest.java`
- Create: `game/src/test/java/com/gaia/save/streaming/DetailNoOpRevisionAckTest.java`

**Interfaces:**

- Consumes: exact `ChunkSnapshot` and `DetailBlocksCodec`.
- Produces: one streamed payload containing canonical FULL bytes plus required DETAIL extension, with equality based on both.

- [ ] **Step 1: Write RED persistence and PR #27 tests**

Test DETAIL-only mutation forces a write even when FULL bytes remain identical. Test exact content/revision no-op does not write and returns no new acknowledgement. Test a higher revision with byte-identical FULL+DETAIL content is written before acknowledgement. Test detail removal/root mutation is not reported as no-op.

Representative assertion:

```java
assertTrue(durability.persistedChunkRevision().isEmpty(),
        "an actual no-op cannot claim a newly persisted revision");
assertEquals(previousPersistedRevision, repository.persistedRevision(key));
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.save.streaming.DetailStreamedPersistenceTest" --tests "com.gaia.save.streaming.DetailNoOpRevisionAckTest"
```

Expected: FAIL because current equality uses flat bytes and unload starts with no DETAIL extension.

- [ ] **Step 3: Implement paired FULL+DETAIL capture**

Encode DETAIL from the exact snapshot in unload and explicit save paths, retain unrelated required extensions, include detail presence in `persistenceRequired`, and expose persisted revision only after an actual durable transaction of that revision.

- [ ] **Step 4: Run GREEN and adjacent save/unload tests**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.save.streaming.Detail*" --tests "com.gaia.save.streaming.StreamedChunkUnloadTransactionTest" --tests "com.gaia.save.session.SaveCoordinatorTest"
```

Expected: PASS.

- [ ] **Step 5: Review checkpoint without staging**

Do not edit the quarantined session integration test. Run:

```powershell
git diff --check -- game/src/main/java/com/gaia/save game/src/main/java/com/gaia/session/GameSessionFactory.java game/src/main/java/com/gaia/world/streaming/ChunkStreamingPipeline.java game/src/test/java/com/gaia/save
```

### Task 10: Streamed load, restore, and Phase 14 compatibility

**Boundary:** Engine detached load API plus game/save integration.

**Files:**

- Modify: `engine/src/main/java/com/overlord/voxel/ChunkGenerationData.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Modify: `game/src/main/java/com/gaia/save/streaming/Phase14SaveMigrator.java`
- Create: `game/src/test/java/com/gaia/save/streaming/DetailStreamedReloadTest.java`
- Modify: `game/src/test/java/com/gaia/save/streaming/Phase14SaveMigrationTest.java`

**Interfaces:**

- Consumes: decoded empty or nonempty `DetailChunkSnapshot` in `ChunkGenerationData`.
- Produces: owner-thread publication of the exact combined Chunk state.

- [ ] **Step 1: Write RED restore tests**

Test absent extension loads FULL-only, supported extension reconstructs exact occupancy/material/revision, unsupported/malformed extension publishes no Chunk, unknown palette identity fails closed, unload/reload retains exact content hash, and Phase 14 migration yields empty details.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.save.streaming.DetailStreamedReloadTest" --tests "com.gaia.save.streaming.Phase14SaveMigrationTest"
```

Expected: FAIL because load discards extensions.

- [ ] **Step 3: Implement detached decode and owner publication**

Decode DETAIL on the load worker from immutable payload bytes and registry identity, carry it in `ChunkGenerationData`, and publish it through the existing ticket/revision path. Keep old migration payloads extension-free.

- [ ] **Step 4: Run Gate 16B focused matrix**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkDetailMutation*" --tests "com.overlord.voxel.ChunkRepositoryTest"
.\gradlew.bat :game:test --tests "com.gaia.interaction.GaiaDetailMutationServiceTest" --tests "com.gaia.save.streaming.Detail*" --tests "com.gaia.save.streaming.StreamedChunk*" --tests "com.gaia.save.streaming.Phase14SaveMigrationTest"
```

Expected: PASS.

- [ ] **Step 5: Gate 16B quarantine and diff check**

Re-run Task 0 hashes, then `git diff --check` and confirm `dist/` is untouched.

---

## Gate 16C1: Detail-aware raycast

### Task 11: Discriminated hit provenance and parent refinement

**Boundary:** Engine API.

**Files:**

- Create: `engine/src/main/java/com/overlord/physics/RaycastCellTarget.java`
- Create: `engine/src/main/java/com/overlord/physics/FullRaycastTarget.java`
- Create: `engine/src/main/java/com/overlord/physics/DetailRaycastTarget.java`
- Modify: `engine/src/main/java/com/overlord/physics/BlockRaycastHit.java`
- Modify: `engine/src/main/java/com/overlord/physics/BlockRaycast.java`
- Modify: `engine/src/test/java/com/overlord/physics/BlockRaycastTest.java`
- Create: `engine/src/test/java/com/overlord/physics/DetailBlockRaycastTest.java`

**Interfaces:**

- `DetailRaycastTarget` carries `VoxelScale.DETAIL_4` and exact `LocalSubVoxelPosition`.
- `BlockRaycastHit` carries target provenance and owning Chunk revision while retaining a compatibility constructor for FULL-only tests.

- [ ] **Step 1: Write RED refinement tests**

Cover six faces, nearest occupied subvoxel, empty gap, continuation into a later parent, edge and corner ties, inside-origin hit, parent boundary, Chunk boundary, negative and large canonical coordinates.

Key continuation assertion:

```java
BlockRaycastHit hit = raycast.cast(originThroughDetailHole, direction, 8)
        .orElseThrow();
assertEquals(laterParentX, hit.blockX());
assertInstanceOf(FullRaycastTarget.class, hit.target());
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.DetailBlockRaycastTest"
```

Expected: FAIL because raycast reads bytes and has no detail provenance.

- [ ] **Step 3: Implement refinement inside existing DDA**

Replace only the parent candidate refinement seam. Iterate occupied subindices ascending, use exact quarter AABBs and existing slab/tie functions, return no candidate for a DETAIL gap, and leave the coarse DDA loop intact.

- [ ] **Step 4: Run GREEN and existing raycast suite**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.BlockRaycastTest" --tests "com.overlord.physics.DetailBlockRaycastTest"
```

Expected: PASS.

- [ ] **Step 5: Review checkpoint without staging**

```powershell
git diff --check -- engine/src/main/java/com/overlord/physics engine/src/test/java/com/overlord/physics
```

### Task 12: Origin-aware availability and game hit mapping

**Boundary:** Engine spatial API plus game integration.

**Files:**

- Modify: `engine/src/main/java/com/overlord/interaction/api/BlockHitResult.java`
- Modify: `game/src/main/java/com/gaia/interaction/GaiaBlockRaycastService.java`
- Modify: `game/src/test/java/com/gaia/interaction/GaiaBlockRaycastServiceTest.java`
- Create: `game/src/test/java/com/gaia/interaction/DetailTargetingIntegrationTest.java`

**Interfaces:**

- Produces game-facing block identity, discriminated FULL/DETAIL target, and exact Chunk revision without creating a second raycast.

- [ ] **Step 1: Write RED integration tests**

Test ResourceLocation mapping for the exact subvoxel material, UNKNOWN and FAILED propagation, stale hit rejection through `GaiaDetailMutationService`, simulation-origin rebasing, and identical fixed-state result at 10/60/144/240 render rates.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.interaction.GaiaBlockRaycastServiceTest" --tests "com.gaia.interaction.DetailTargetingIntegrationTest"
```

Expected: FAIL until mapping carries provenance and revision.

- [ ] **Step 3: Implement mapping only**

Map runtime ID through the existing registry, preserve `SpatialQueryResult` exactly, and pass the engine hit target/revision through the game API.

- [ ] **Step 4: Run Gate 16C1 matrix**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.*Raycast*"
.\gradlew.bat :game:test --tests "com.gaia.interaction.*Raycast*" --tests "com.gaia.interaction.DetailTargetingIntegrationTest"
```

Expected: PASS.

- [ ] **Step 5: Gate 16C1 diff check**

Run `git diff --check` and Task 0 quarantine hashes.

---

## Gate 16C2: Detail collision

### Task 13: Deterministic greedy DETAIL collision boxes

**Boundary:** Engine CPU value algorithm.

**Files:**

- Create: `engine/src/main/java/com/overlord/physics/DetailCollisionBoxMerger.java`
- Create: `engine/src/test/java/com/overlord/physics/DetailCollisionBoxMergerTest.java`

**Interfaces:**

- Produces `BlockCollisionShape merge(DetailCellState)` using X, then Y, then Z growth and ascending seed order.

- [ ] **Step 1: Write RED merge tests**

Cover one cell, full 64-cell cube, stair, thin wall, asymmetric pattern, two materials sharing occupancy, deterministic repeat, the 32-box face-isolated checkerboard, and adversarial patterns proving `boxCount <= occupiedCellCount <= 64`.

```java
assertEquals(List.of(new Aabb(0, 0, 0, 1, 1, 1)),
        merger.merge(DetailCellState.uniform((byte) 7)).boxes());
assertEquals(32, merger.merge(faceIsolatedCheckerboard()).boxes().size());
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.DetailCollisionBoxMergerTest"
```

Expected: FAIL because the merger is absent.

- [ ] **Step 3: Implement bounded mask clearing algorithm**

Use only the 64-bit local mask, exact `0.25f` bounds, deterministic seed/growth order, and no material-based split.

- [ ] **Step 4: Run GREEN**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.DetailCollisionBoxMergerTest"
```

Expected: PASS with box count in `[1,64]` for every nonempty state.

- [ ] **Step 5: Review checkpoint without staging**

Run path-scoped `git diff --check`.

### Task 14: Typed CollisionWorld integration

**Boundary:** Engine API and game composition.

**Files:**

- Modify: `engine/src/main/java/com/overlord/physics/BlockCollisionShapeResolver.java`
- Modify: `engine/src/main/java/com/overlord/physics/CollisionWorld.java`
- Modify: `engine/src/test/java/com/overlord/physics/CollisionWorldMotionTest.java`
- Modify: `engine/src/test/java/com/overlord/physics/CollisionWorldSweepTest.java`
- Modify: `engine/src/test/java/com/overlord/physics/PlayerControllerCollisionTest.java`
- Modify: `engine/src/test/java/com/overlord/physics/PlayerControllerTraversalTest.java`
- Create: `engine/src/test/java/com/overlord/physics/DetailCollisionWorldTest.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Create: `game/src/test/java/com/gaia/PhysicsDetailCompositionTest.java`

**Interfaces:**

- Resolver consumes typed `ParentCellState`; FULL uses existing AIR/full-cube mapping and DETAIL delegates to Task 13.
- CollisionWorld broad phase and sweep algorithms remain unchanged.

- [ ] **Step 1: Write RED collision behavior tests**

Test standing on quarter geometry, quarter-width wall, hole passthrough, coherent step-up/down, supported-speed tunneling protection, parent/Chunk boundaries, negative Chunk, simulation-origin rebase, 64-box preservation, and UNKNOWN/FAILED fail-closed.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.DetailCollisionWorldTest"
.\gradlew.bat :game:test --tests "com.gaia.PhysicsDetailCompositionTest"
```

Expected: FAIL because CollisionWorld resolves byte IDs.

- [ ] **Step 3: Implement typed resolution without replacing CollisionWorld**

Use `World.observeCell` in the existing parent loops, map availability to the existing unavailable result, and pass every merged AABB to existing sweep/overlap/depenetration code.

- [ ] **Step 4: Run Gate 16C2 matrix**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.physics.CollisionWorldMotionTest" --tests "com.overlord.physics.CollisionWorldSweepTest" --tests "com.overlord.physics.DetailCollision*" --tests "com.overlord.physics.PlayerControllerCollisionTest" --tests "com.overlord.physics.PlayerControllerTraversalTest"
.\gradlew.bat :game:test --tests "com.gaia.PhysicsDetailCompositionTest" --tests "com.gaia.worlditem.PhysicalWorldItemCollisionTest"
```

Expected: PASS.

- [ ] **Step 5: Gate 16C2 diff check**

Run `git diff --check` and Task 0 quarantine hashes.

---

## Gate 16D: Detail mesh and render

### Task 15: Immutable quarter-grid sampler

**Boundary:** Engine worker input API.

**Files:**

- Create: `engine/src/main/java/com/overlord/voxel/QuarterVoxelSample.java`
- Create: `engine/src/main/java/com/overlord/voxel/QuarterVoxelSampler.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshInput.java`
- Create: `engine/src/test/java/com/overlord/voxel/QuarterVoxelSamplerTest.java`

**Interfaces:**

- Produces immutable `sample(parentX,parentY,parentZ,subX,subY,subZ)` with occupied/runtime ID and parent representation provenance.
- Consumes only the nine snapshots.

- [ ] **Step 1: Write RED sampler tests**

Cover FULL expansion to 64 occupied samples, FULL AIR, DETAIL gaps/materials, subcoordinate wrapping, all eight horizontal neighbor snapshots, vertical bounds, negative Chunk keys, and no World/repository fields.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.QuarterVoxelSamplerTest"
```

Expected: FAIL because the sampler is absent.

- [ ] **Step 3: Implement pure snapshot sampler**

Normalize subcoordinates with floor division/modulo and resolve wrapped parent state through `ChunkMeshInput.cellState`. Do not allocate a 4x-expanded Chunk grid.

- [ ] **Step 4: Run GREEN and mesh input regressions**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.QuarterVoxelSamplerTest" --tests "com.overlord.voxel.ChunkMeshInputTest"
```

Expected: PASS.

- [ ] **Step 5: Review checkpoint without staging**

Run path-scoped `git diff --check`.

### Task 16: DETAIL faces, FULL/DETAIL clipping, UV, and quarter AO

**Boundary:** Engine meshing API; game material resolver remains existing.

**Files:**

- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java`
- Modify: `engine/src/main/java/com/overlord/voxel/VoxelAmbientOcclusion.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshData.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/VoxelAmbientOcclusionTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/DetailChunkMeshBuilderTest.java`

**Interfaces:**

- Produces hidden-face-eliminated DETAIL geometry in the owning Chunk mesh and deterministic `ChunkMeshData.canonicalHash()`.
- Uses Task 15 sampler and existing `BlockRenderResolver`.

- [ ] **Step 1: Write RED geometry tests**

Cover:

- one subvoxel gives six faces;
- two adjacent subvoxels remove their internal face;
- full 64-cell DETAIL cube emits only the 96 quarter facelets on the outer shell;
- mixed material occupied boundary has no internal face;
- FULL/DETAIL face is split only at empty DETAIL boundary samples;
- DETAIL/DETAIL and DETAIL/AIR masks;
- parent and Chunk edges;
- exact quarter coordinates;
- no duplicate coplanar vertices;
- no cracks at shared boundaries;
- FULL cropped UV and DETAIL full-tile UV ownership;
- quarter AO side/corner cases;
- identical input produces identical canonical mesh hash.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.DetailChunkMeshBuilderTest" --tests "com.overlord.voxel.VoxelAmbientOcclusionTest"
```

Expected: FAIL because the existing mesher sees DETAIL backing AIR.

- [ ] **Step 3: Implement hybrid FULL and quarter-grid meshing**

Keep the existing FULL-only fast path byte-for-byte where no DETAIL participates. For DETAIL interfaces, apply single-sided occupied-to-empty ownership, cropped FULL UVs, full-tile DETAIL UVs, deterministic row-major FULL mask merging, and quarter-grid AO.

- [ ] **Step 4: Run GREEN and existing mesh regressions**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkMeshBuilderTest" --tests "com.overlord.voxel.DetailChunkMeshBuilderTest" --tests "com.overlord.voxel.VoxelAmbientOcclusionTest"
```

Expected: PASS; existing FULL mesh hashes/vertex expectations remain unchanged.

- [ ] **Step 5: Review checkpoint without staging**

Run path-scoped `git diff --check`.

### Task 17: Existing mesh claim, stale-result, and GPU lifecycle preservation

**Boundary:** Engine lifecycle regression plus game composition.

**Files:**

- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshLifecycleStructureTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/DetailChunkMeshLifecycleTest.java`
- Modify: `game/src/test/java/com/gaia/world/streaming/ChunkGpuOwnershipTest.java`
- Create: `game/src/test/java/com/gaia/DetailRenderCompositionTest.java`

**Interfaces:**

- Consumes: unchanged claim and manager production APIs.
- Produces: proof that detail revisions flow through the one existing queue/upload authority.

- [ ] **Step 1: Write RED lifecycle tests**

Test detail edit invalidates an active old claim, stale detail mesh is rejected, current detail mesh uploads through the normal queue, unload schedules the same render-object destruction, and queue/upload/destruction limits remain 32/2/2/4 with aggregate publication 2.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.DetailChunkMeshLifecycleTest"
.\gradlew.bat :game:test --tests "com.gaia.DetailRenderCompositionTest" --tests "com.gaia.world.streaming.ChunkGpuOwnershipTest"
```

Expected: FAIL until detail revisions reach claims and composition.

- [ ] **Step 3: Make only lifecycle wiring corrections exposed by tests**

Do not add queues, uploads, handles, or render objects. Reuse current state/revision checks.

- [ ] **Step 4: Run Gate 16D matrix**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.*Mesh*" --tests "com.overlord.voxel.VoxelAmbientOcclusionTest" --tests "com.overlord.voxel.QuarterVoxelSamplerTest"
.\gradlew.bat :game:test --tests "com.gaia.DetailRenderCompositionTest" --tests "com.gaia.world.streaming.ChunkGpuOwnershipTest"
```

Expected: PASS.

- [ ] **Step 5: Gate 16D diff check**

Run `git diff --check`, architecture scans, and Task 0 quarantine hashes.

---

## Gate 16E: Debug, fixtures, measurements, documentation, and acceptance

### Task 18: Development-only commands and deterministic fixtures

**Boundary:** Game integration only.

**Files:**

- Create: `game/src/main/java/com/gaia/debug/DetailFixturePattern.java`
- Create: `game/src/main/java/com/gaia/debug/DetailDebugTools.java`
- Create: `game/src/test/java/com/gaia/debug/DetailFixturePatternTest.java`
- Create: `game/src/test/java/com/gaia/debug/DetailDebugToolsTest.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`

**Interfaces:**

- Produces commands to convert, fill, clear, print snapshot/hash/revision, print collision boxes, and spawn `STAIR`, `HOLLOW_OPENING`, `THIN_WALL`, `CHECKERBOARD`, and `ASYMMETRIC` patterns.
- Every mutating command consumes `DetailMutationService` only.

- [ ] **Step 1: Write RED fixture tests**

Assert exact 64-bit occupancy masks and material arrays for all five patterns, deterministic repeat hashes, and that clear/fill operations issue service requests rather than access Chunk storage.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.debug.Detail*"
```

Expected: FAIL because tooling is absent.

- [ ] **Step 3: Implement development-only tooling**

Gate composition behind an explicit debug property. Do not add survival inventory/material consumption or direct map access.

- [ ] **Step 4: Run GREEN and composition tests**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.debug.Detail*" --tests "com.gaia.GameBootstrap*"
```

Expected: PASS.

- [ ] **Step 5: Review checkpoint without staging**

Run path-scoped `git diff --check`.

### Task 19: Deterministic performance and bound fixture

**Boundary:** Tools measurement; no production authority.

**Files:**

- Create: `tools/src/main/java/com/gaia/tools/DetailVoxelPerformanceFixture.java`
- Create: `tools/src/test/java/com/gaia/tools/DetailVoxelPerformanceFixtureTest.java`
- Modify: `tools/build.gradle`

**Interfaces:**

- Produces a `profileDetailVoxels` Gradle task and immutable report for 1, 64, 256, and 1,024 DETAIL parents.

- [ ] **Step 1: Write RED deterministic report test**

The report contains canonical backing bytes, snapshot backing bytes, mesh nanoseconds, vertex/index count, collision boxes, raycast nanoseconds, extension bytes, and unload/reload nanoseconds. Assert codec and snapshot bounds and deterministic hashes; use generous time ceilings only to detect hangs, not machine ranking.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :tools:test --tests "com.gaia.tools.DetailVoxelPerformanceFixtureTest"
```

Expected: FAIL because the fixture is absent.

- [ ] **Step 3: Implement measurement fixture**

Use deterministic seed/patterns, warm-up iterations, monotonic `System.nanoTime`, and no pooling. Print exact counts and hashes separately from timings.

- [ ] **Step 4: Run GREEN and capture measurements**

```powershell
.\gradlew.bat :tools:test --tests "com.gaia.tools.DetailVoxelPerformanceFixtureTest"
.\gradlew.bat :tools:profileDetailVoxels
```

Expected: PASS and bounded reports for all four sizes without raising scheduler or persistence limits.

- [ ] **Step 5: Review checkpoint without staging**

Run path-scoped `git diff --check`; do not commit generated build output.

### Task 20: Architecture docs, acceptance evidence, and final handoff

**Boundary:** Shared documentation and final verification.

**Files:**

- Create: `docs/architecture/small-block-voxel-core.md`
- Modify: `docs/architecture/infinite-world-streaming.md`
- Modify: `docs/architecture/save-load-v1.md`
- Create: `docs/testing/phase-16-small-block-voxel-acceptance.md`
- Create: `docs/agent-handoffs/phase-16-handoff.md`
- Create: `game/src/test/java/com/gaia/DetailAuthorityArchitectureTest.java`

**Interfaces:**

- Consumes: completed Gates 16A–16E and captured measurements/acceptance evidence.
- Produces: final architecture, codec documentation, authority scan, runtime checklist, and handoff.

- [ ] **Step 1: Write RED final architecture scan**

Scan engine/game production sources and reject:

```text
DetailWorld
DetailChunkMap
detailRevision
detailDirty
detailUploadQueue
new BlockRaycast outside approved composition
new CollisionWorld outside approved composition
World.getBlock in DETAIL-aware paths
ChunkSnapshot.getBlock in DETAIL-aware paths
OpenGL calls in DETAIL worker code
```

Also reflectively assert the nine-component `ChunkMeshInput` contract and one production `BlockRegistry` identity source.

- [ ] **Step 2: Run RED or confirm existing implementation already satisfies it**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.DetailAuthorityArchitectureTest"
```

Expected before final cleanup: any remaining forbidden bypass fails with exact file names.

- [ ] **Step 3: Remove only detected architectural bypasses and write docs**

Document exact runtime representation, codec table/size proof, no-op semantics, fixture measurements, modified files, unfinished work, risks, and interfaces the next phase must not break. Do not change queue limits or add unrelated refactors.

- [ ] **Step 4: Run final automated candidate matrix**

```powershell
.\gradlew.bat :engine:test
.\gradlew.bat :game:test
.\gradlew.bat :tools:test
.\gradlew.bat clean test build
.\gradlew.bat :engine:verifyPackagedShaderResources
.\gradlew.bat :game:verifyPackagedResources
.\gradlew.bat :game:verifyInstalledShaderResources
git diff --check
```

Expected: every command exits zero. Record command, duration, and result in the handoff.

- [ ] **Step 5: Run duplicate-authority scan**

```powershell
rg -n "DetailWorld|DetailChunkMap|detailRevision|detailDirty|detailUploadQueue|new BlockRaycast|new CollisionWorld|World\.getBlock|ChunkSnapshot\.getBlock|\bgl[A-Z]" engine/src/main game/src/main
```

Expected: only approved composition/compatibility occurrences documented by the architecture test; no duplicate authority.

- [ ] **Step 6: Perform Windows runtime acceptance**

Run the spec's full runtime checklist: ordinary FULL world, conversion, asymmetric pattern, hole ray, exact selection, top/wall collision, parent/Chunk boundary, unload/return, save, Save & Quit, relaunch, FULL regressions, resize, high-DPI, Alt+Tab, and clean exit. Record observed build identity and results.

- [ ] **Step 7: Perform Apple Silicon macOS runtime acceptance**

Repeat the same checklist using `./gradlew :game`, including Retina and focus recovery. Record hardware/OS/JDK and observed results. Do not mark complete from Windows evidence alone.

- [ ] **Step 8: Verify two consecutive PR matrices after controller authorizes PR work**

Require Windows, Ubuntu, and macOS 3/3 GREEN twice consecutively. Any failure triggers root-cause analysis and a fresh two-consecutive-run requirement. Merge and post-merge main verification remain controller-only.

- [ ] **Step 9: Final quarantine and handoff check**

Recompute Task 0 hashes. Confirm the two user-owned tracked files are unchanged by Phase 16 and `dist/` was untouched. Add `git diff --stat`, test results, suggested commit message, and suggested PR title/description to the handoff without staging or committing.

## Execution stop

This plan authorizes no production implementation. After controller review, implementation must start at Task 0 and Gate 16A, use RED/GREEN per Task, and stop at every Gate review checkpoint requested by the controller.
