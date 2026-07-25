# Deterministic World Generation Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the monolithic Gaia terrain generator with a deterministic,
composable, CPU-only staged pipeline and revision-safe repository generation
transactions for a finite 81-Chunk world.

**Architecture:** Providers sample immutable configuration and absolute world
coordinates into a single-Chunk `GenerationRegion`. The Pipeline freezes a
complete `ChunkGenerationData` value, and `ChunkRepository` atomically
publishes it through an initial-generation or rebuild ticket while retaining
exclusive ownership of revisions, neighbor invalidation, stale mesh rejection,
and lifecycle state.

**Tech Stack:** Java 17, Gradle 8.5 Wrapper, JUnit Jupiter, existing Phase 2
`ResourceLocation`/`BlockRegistry`, Phase 3 `ChunkRepository` and mesh
lifecycle, Phase 6 spawn recovery, Phase 7 gameplay mutation boundary, Java
standard-library SHA-256.

## Global Constraints

- Work only on `feat/worldgen-pipeline`, based on `origin/main` at
  `f1ca80beb47616025bea21615d7e3fccaa5b31c6`; never modify, commit to, or push
  `main`.
- Keep Java 17 source/target compatibility. JDK 21 may run the build.
- Use the checked-in Gradle Wrapper. Do not write a platform-specific JDK path.
- `engine` must remain independent of `game`.
- Generation and Stage code is CPU-only. It must not import or call Renderer,
  Mesh, `ChunkMeshManager`, LWJGL, GLFW, OpenGL, Shader, Texture, or any GPU
  lifecycle API.
- Every OpenGL/GPU action remains on the main context-owning thread and remains
  compatible with OpenGL 4.1 / GLSL 410.
- Do not modify `Renderer`, `PlayerController`, `ChunkMeshManager`, or Phase 5
  interfaces.
- Initial loading and rebuild use the typed `ChunkRepository` generation
  transaction. They must not call `World.setBlock`, `WorldMutationService`,
  `BeforeBlockChangedEvent`, gameplay `BlockChangedEvent`, inventory
  transactions, or world-item services.
- `ChunkRepository` remains the only owner of revisions, dirty propagation,
  neighbor invalidation, snapshots, stale-result rejection, and unload
  conflicts.
- Resolve air/grass/dirt/stone through `ResourceLocation` and the Phase 2
  `BlockRegistry`; do not add a second registry, new resource, copied code, or
  third-party asset.
- The production default is seed `12345L`, algorithm version `1`, and inclusive
  Chunk radius `4`, producing 81 initial Chunks.
- Use a project-owned fixed 64-bit coordinate mix and `StrictMath`; do not use
  shared/global `Random`, `SplittableRandom`, or mutable permutation state.
- Each task follows RED -> GREEN -> REFACTOR, focused verification,
  `git diff --check`, independent review, and a local commit.
- Do not push, create a pull request, or merge.

---

## Planned File Structure

### Engine generation transaction

- Create `engine/src/main/java/com/overlord/voxel/ChunkGenerationMode.java`
  - distinguishes initial publication from revision-guarded rebuild.
- Create `engine/src/main/java/com/overlord/voxel/ChunkGenerationStatus.java`
  - explicit per-key attempt state.
- Create `engine/src/main/java/com/overlord/voxel/ChunkGenerationTicket.java`
  - opaque attempt identity, key, mode, and base revision.
- Create `engine/src/main/java/com/overlord/voxel/ChunkGenerationData.java`
  - immutable canonical Chunk block bytes.
- Create `engine/src/main/java/com/overlord/voxel/ChunkGenerationResult.java`
  - committed, conflict, and failed terminal outcomes.
- Modify `engine/src/main/java/com/overlord/voxel/Chunk.java`
  - reconstruct a complete sparse Chunk from canonical bytes.
- Modify `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
  - ticket ownership, atomic commit/failure, rebuild revision checks, and
    repository-owned neighbor invalidation.
- Modify `engine/src/main/java/com/overlord/voxel/World.java`
  - retain the old compatibility generator but expose no gameplay shortcut for
    Phase 4.

### Game generation primitives

- Create `game/src/main/java/com/gaia/world/generation/BiomeType.java`
  - stable three-biome identity.
- Create `game/src/main/java/com/gaia/world/generation/BiomeSample.java`
  - normalized per-column biome weights.
- Create
  `game/src/main/java/com/gaia/world/generation/WorldGenerationConfig.java`
  - seed, algorithm version, radius, and complete immutable tuning.
- Create
  `game/src/main/java/com/gaia/world/generation/DeterministicCoordinateSampler.java`
  - fixed integer mixing and continuous value-noise sampling.
- Create
  `game/src/main/java/com/gaia/world/generation/GenerationBlockPalette.java`
  - resolved existing block IDs.
- Create
  `game/src/main/java/com/gaia/world/generation/GenerationContext.java`
  - immutable configuration, palette, and sampler.
- Create
  `game/src/main/java/com/gaia/world/generation/GenerationRegion.java`
  - one bounded mutable work buffer plus per-column biome/height data.
- Create
  `game/src/main/java/com/gaia/world/generation/GenerationStageResult.java`
  - ordered success/failure Stage outcome.
- Create
  `game/src/main/java/com/gaia/world/generation/WorldGenerationResult.java`
  - complete Chunk data or failed Stage.
- Create
  `game/src/main/java/com/gaia/world/generation/WorldGenerationStage.java`
  - one stable-ID Stage boundary.
- Create
  `game/src/main/java/com/gaia/world/generation/WorldGenerator.java`
  - generator interface.
- Create
  `game/src/main/java/com/gaia/world/generation/StagedWorldGenerator.java`
  - ordered Stage executor.

### Provider and Stage implementations

- Create `BiomeType.java`, `BiomeSample.java`, `BiomeProvider.java`, and
  `ContinuousBiomeProvider.java`.
- Create `HeightProvider.java` and `BlendedHeightProvider.java`.
- Create `StrataDensityProvider.java` and
  `DefaultStrataDensityProvider.java`.
- Create `CaveProvider.java` and `NoiseCaveProvider.java`.
- Create `SurfaceProvider.java` and `DefaultSurfaceProvider.java`.
- Create `DecorationProvider.java` and
  `StoneOutcropDecorationProvider.java`.
- Modify `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`
  - change the old monolith into a default Pipeline composition factory.

Provider interfaces extend `WorldGenerationStage`, so each Provider is both
independently sampleable/testable and directly composable in the ordered
Pipeline. No duplicate wrapper Stage classes are added.

### Loading, composition, snapshots, and docs

- Create `game/src/main/java/com/gaia/world/WorldLoadState.java`.
- Create `game/src/main/java/com/gaia/world/WorldLoadFailure.java`.
- Create `game/src/main/java/com/gaia/world/WorldLoadException.java`.
- Create `game/src/main/java/com/gaia/world/WorldRebuildResult.java`.
- Create `game/src/main/java/com/gaia/world/SafeSpawnSelector.java`.
- Create
  `game/src/main/java/com/gaia/world/generation/WorldGenerationHasher.java`.
- Modify `game/src/main/java/com/gaia/world/WorldLoader.java`.
- Modify `game/src/main/java/com/gaia/world/WorldLoadResult.java`.
- Modify `game/src/main/java/com/gaia/GameBootstrap.java`.
- Modify `game/src/main/java/com/gaia/GameContext.java`.
- Modify `game/src/main/java/com/gaia/GameLoop.java`.
- Modify `engine/src/main/java/com/overlord/config/GameConfig.java`
  - remove obsolete Gaia terrain constants after migration.
- Create `docs/architecture/phase-04-deterministic-snapshots.md`.
- Create `docs/agent-handoffs/phase-04-handoff.md`.
- Update `docs/architecture/current-baseline.md`.

### Focused tests

- Create
  `engine/src/test/java/com/overlord/voxel/ChunkGenerationDataTest.java`.
- Create
  `engine/src/test/java/com/overlord/voxel/ChunkRepositoryGenerationTransactionTest.java`.
- Extend `ChunkRepositoryTest`, `ChunkMeshManagerTest`, and `WorldTest` only
  where compatibility or stale-result behavior needs direct regression.
- Create tests under
  `game/src/test/java/com/gaia/world/generation/` matching every core value,
  Provider, Pipeline, determinism, and snapshot responsibility.
- Replace the monolithic expectations in
  `game/src/test/java/com/gaia/world/GaiaWorldGeneratorTest.java`.
- Extend `WorldLoaderTest`, `GameBootstrapTest`, `GameLoopStructureTest`, and
  architecture tests for load state, spawn, rebuild, and prohibited
  dependencies.

---

### Task 1: Atomic Engine Generation Transactions

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/ChunkGenerationMode.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkGenerationStatus.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkGenerationTicket.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkGenerationData.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkGenerationResult.java`
- Modify: `engine/src/main/java/com/overlord/voxel/Chunk.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Create:
  `engine/src/test/java/com/overlord/voxel/ChunkGenerationDataTest.java`
- Create:
  `engine/src/test/java/com/overlord/voxel/ChunkRepositoryGenerationTransactionTest.java`

**Interfaces:**
- Produces:

```java
public enum ChunkGenerationMode {
    INITIAL,
    REBUILD
}

public enum ChunkGenerationStatus {
    IDLE,
    GENERATING,
    COMMITTED,
    FAILED
}

public record ChunkGenerationTicket(
        ChunkKey key,
        ChunkGenerationMode mode,
        long attemptId,
        long baseRevision) {}

public final class ChunkGenerationData {
    public ChunkGenerationData(
            ChunkKey key, int worldHeight, byte[] blocks);
    public ChunkKey key();
    public int worldHeight();
    public byte getBlock(int localX, int y, int localZ);
    public byte[] copyBlocks();
}

public record ChunkGenerationResult(
        Status status,
        ChunkKey key,
        long revision,
        Optional<Throwable> failure) {
    public enum Status {
        COMMITTED,
        CONFLICT,
        FAILED
    }
}

public ChunkGenerationTicket beginGeneration(
        ChunkKey key, ChunkGenerationMode mode);
public ChunkGenerationResult commitGeneration(
        ChunkGenerationTicket ticket,
        ChunkGenerationData data);
public ChunkGenerationResult failGeneration(
        ChunkGenerationTicket ticket,
        Throwable failure);
public ChunkGenerationStatus generationStatus(ChunkKey key);
public Optional<Throwable> generationFailure(ChunkKey key);
```

- Preserves: existing `generate`, `setBlock`, snapshots, mesh claims, unload,
  and non-allocating reads.

- [ ] **Step 1: Write immutable payload and initial-transaction tests**

```java
@Test
void generationDataDefensivelyCopiesCanonicalBytes() {
    byte[] bytes = new byte[16 * 32 * 16];
    bytes[canonicalIndex(2, 7, 3, 32)] = 5;
    ChunkGenerationData data =
            new ChunkGenerationData(new ChunkKey(0, 0), 32, bytes);
    bytes[canonicalIndex(2, 7, 3, 32)] = 9;
    assertEquals(5, data.getBlock(2, 7, 3));
    byte[] returned = data.copyBlocks();
    returned[canonicalIndex(2, 7, 3, 32)] = 1;
    assertEquals(5, data.getBlock(2, 7, 3));
}

@Test
void initialCommitPublishesWholeChunkOnce() {
    ChunkRepository repository = new ChunkRepository(32, new ChunkDirtyTracker());
    ChunkKey key = new ChunkKey(-1, 2);
    ChunkGenerationTicket ticket =
            repository.beginGeneration(key, ChunkGenerationMode.INITIAL);
    assertEquals(ChunkGenerationStatus.GENERATING,
            repository.generationStatus(key));
    assertEquals(0, repository.getBlock(-14, 7, 35));

    ChunkGenerationResult result =
            repository.commitGeneration(ticket, data(key, 2, 7, 3, (byte) 5));

    assertEquals(ChunkGenerationResult.Status.COMMITTED, result.status());
    assertEquals(ChunkGenerationStatus.COMMITTED,
            repository.generationStatus(key));
    assertEquals(ChunkState.GENERATED, repository.state(key));
    assertEquals(5, repository.getBlock(-14, 7, 35));
    assertTrue(result.revision() > 0);
}

@Test
void failedInitialAttemptPublishesNoPartialChunk() {
    ChunkRepository repository = new ChunkRepository(32, new ChunkDirtyTracker());
    ChunkKey key = new ChunkKey(0, 0);
    ChunkGenerationTicket ticket =
            repository.beginGeneration(key, ChunkGenerationMode.INITIAL);
    IllegalStateException cause = new IllegalStateException("stage failed");

    ChunkGenerationResult result =
            repository.failGeneration(ticket, cause);

    assertEquals(ChunkGenerationResult.Status.FAILED, result.status());
    assertEquals(ChunkGenerationStatus.FAILED,
            repository.generationStatus(key));
    assertSame(cause, repository.generationFailure(key).orElseThrow());
    assertEquals(ChunkState.EMPTY, repository.state(key));
    assertFalse(repository.contains(key));
}

@Test
void laterInitialCommitInvalidatesAlreadyLoadedHorizontalNeighbor() {
    ChunkRepository repository = new ChunkRepository(32, new ChunkDirtyTracker());
    commitInitial(repository, new ChunkKey(0, 0), filled((byte) 1));
    long before = repository.revision(new ChunkKey(0, 0));
    commitInitial(repository, new ChunkKey(1, 0), filled((byte) 1));
    assertEquals(ChunkState.DIRTY,
            repository.state(new ChunkKey(0, 0)));
    assertTrue(repository.revision(new ChunkKey(0, 0)) > before);
}
```

Also cover invalid world height/byte length, key mismatch, duplicate begin,
reused terminal ticket, invalid attempt ID, nulls, and `RuntimeException` plus
`Error` failure preservation.

- [ ] **Step 2: Run the new tests and confirm RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.ChunkGenerationDataTest `
  --tests com.overlord.voxel.ChunkRepositoryGenerationTransactionTest `
  --console=plain --no-daemon
```

Expected: test compilation fails because the five transaction values and
repository methods do not exist.

- [ ] **Step 3: Implement immutable data and initial ticket ownership**

Use the existing canonical index:

```java
private int index(int localX, int y, int localZ) {
    return localX
            + y * GameConfig.Chunk.SIZE
            + localZ * GameConfig.Chunk.SIZE * worldHeight;
}
```

`ChunkGenerationData` validates positive height, exact byte length, local
bounds, and defensive copying. Add a package-private `Chunk.fromCanonicalBytes`
factory that calls `setBlock` only on the detached new `Chunk`.

In `ChunkRepository`, use a repository `AtomicLong` attempt sequence plus a
per-key attempt map. `beginGeneration` inserts exactly one active attempt.
Initial generation requires no loaded entry. `failGeneration` terminally
records the exact failure and leaves no published entry. `commitGeneration`
creates the entry and complete `Chunk` under repository ownership, allocates
one revision, sets `GENERATED`, removes the live attempt, and invalidates every
already loaded horizontal neighbor through the existing repository dirty
tracker. A later valid begin replaces the prior terminal status/failure for
that key with a new `GENERATING` attempt.

- [ ] **Step 4: Run focused tests and existing repository compatibility**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.ChunkGenerationDataTest `
  --tests com.overlord.voxel.ChunkRepositoryGenerationTransactionTest `
  --tests com.overlord.voxel.ChunkRepositoryTest `
  --tests com.overlord.voxel.WorldTest `
  --console=plain --no-daemon
```

Expected: all selected tests pass.

- [ ] **Step 5: Review and commit**

Verify the transaction classes import no `game`, renderer, event, interaction,
inventory, or world-item type. Run `git diff --check`, then:

```powershell
git add engine/src/main/java/com/overlord/voxel `
  engine/src/test/java/com/overlord/voxel
git commit -m "feat(voxel): add atomic generation transactions"
```

---

### Task 2: Revision-Safe Rebuild and Repository-Owned Invalidation

**Files:**
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify:
  `engine/src/test/java/com/overlord/voxel/ChunkRepositoryGenerationTransactionTest.java`
- Modify:
  `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`

**Interfaces:**
- Consumes: Task 1 tickets, payloads, outcomes, repository revisions.
- Produces: `REBUILD` commit/failure/conflict semantics without changing any
  `ChunkMeshManager` production API.

- [ ] **Step 1: Write rebuild, edge, stale, and unload tests**

```java
@Test
void rebuildFailurePreservesCommittedChunkAndLifecycle() {
    Fixture fixture = renderableFixture();
    long revision = fixture.repository.revision(KEY);
    byte oldBlock = fixture.repository.getBlock(1, 4, 1);
    ChunkGenerationTicket ticket =
            fixture.repository.beginGeneration(KEY, ChunkGenerationMode.REBUILD);

    fixture.repository.failGeneration(
            ticket, new IllegalStateException("provider failure"));

    assertEquals(revision, fixture.repository.revision(KEY));
    assertEquals(oldBlock, fixture.repository.getBlock(1, 4, 1));
    assertEquals(ChunkState.RENDERABLE, fixture.repository.state(KEY));
    assertEquals(ChunkGenerationStatus.FAILED,
            fixture.repository.generationStatus(KEY));
}

@Test
void rebuildRejectsChangedBaseRevision() {
    ChunkRepository repository = generatedRepository(KEY, EAST);
    ChunkGenerationTicket ticket =
            repository.beginGeneration(KEY, ChunkGenerationMode.REBUILD);
    repository.setBlock(1, 4, 1, (byte) 7);

    ChunkGenerationResult result =
            repository.commitGeneration(ticket, filled(KEY, (byte) 3));

    assertEquals(ChunkGenerationResult.Status.CONFLICT, result.status());
    assertEquals(7, repository.getBlock(1, 4, 1));
}

@Test
void changedEastEdgeDirtiesOnlyTargetAndLoadedEastNeighbor() {
    ChunkRepository repository = generatedRepository(KEY, EAST, NORTH);
    long northRevision = repository.revision(NORTH);
    ChunkGenerationTicket ticket =
            repository.beginGeneration(KEY, ChunkGenerationMode.REBUILD);

    repository.commitGeneration(ticket, withEastEdgeChanged(KEY));

    assertEquals(ChunkState.DIRTY, repository.state(KEY));
    assertEquals(ChunkState.DIRTY, repository.state(EAST));
    assertEquals(northRevision, repository.revision(NORTH));
}
```

Extend the mesh test so a claimed old revision completes after rebuild and is
rejected before upload. Cover unchanged edges, all four changed edges, missing
neighbors, rebuild while `UNLOADING`, and duplicate terminal calls.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.ChunkRepositoryGenerationTransactionTest `
  --tests com.overlord.voxel.ChunkMeshManagerTest `
  --console=plain --no-daemon
```

Expected: rebuild tests fail because Task 1 only supports initial commit or
does not yet preserve/compare the stable base.

- [ ] **Step 3: Implement rebuild commit under repository ownership**

For `REBUILD`, capture the current stable revision in the ticket without
changing `ChunkState`. On commit:

```java
if (entry == null
        || entry.state == ChunkState.UNLOADING
        || entry.revision != ticket.baseRevision()) {
    return conflict(ticket.key());
}
Chunk oldChunk = entry.chunk;
Chunk replacement =
        Chunk.fromCanonicalBytes(
                data.worldHeight(), data.copyBlocks());
Set<ChunkKey> changedEdges =
        changedHorizontalEdges(ticket.key(), oldChunk, replacement);
entry.chunk = replacement;
entry.revision = nextRevision();
entry.failure = null;
entry.state = ChunkState.DIRTY;
dirtyChangedLoadedNeighbors(changedEdges);
```

Make `Entry.chunk` replaceable only inside the synchronized repository commit.
Edge comparison must inspect all Y values on the relevant X/Z plane. Reuse the
existing repository revision sequence and `dirtyIfPresent`; do not introduce a
second dirty tracker or emit an event.

- [ ] **Step 4: Run focused and Phase 3 lifecycle tests**

```powershell
.\gradlew.bat :engine:test `
  --tests com.overlord.voxel.ChunkRepositoryGenerationTransactionTest `
  --tests com.overlord.voxel.ChunkRepositoryTest `
  --tests com.overlord.voxel.ChunkMeshManagerTest `
  --tests com.overlord.voxel.ChunkMeshLifecycleStructureTest `
  --console=plain --no-daemon
```

Expected: all selected tests pass; stale results make no backend upload.

- [ ] **Step 5: Review and commit**

Review synchronization, ticket terminality, revision uniqueness, and old-state
preservation. Run `git diff --check`, then:

```powershell
git add engine/src/main/java/com/overlord/voxel/ChunkRepository.java `
  engine/src/test/java/com/overlord/voxel
git commit -m "feat(voxel): add revision-safe chunk rebuild"
```

---

### Task 3: Deterministic Configuration, Sampler, Region, and Results

**Files:**
- Create:
  `game/src/main/java/com/gaia/world/generation/WorldGenerationConfig.java`
- Create: `game/src/main/java/com/gaia/world/generation/BiomeType.java`
- Create: `game/src/main/java/com/gaia/world/generation/BiomeSample.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/DeterministicCoordinateSampler.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/GenerationBlockPalette.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/GenerationContext.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/GenerationRegion.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/GenerationStageResult.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/WorldGenerationResult.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/WorldGenerationStage.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/WorldGenerator.java`
- Create:
  `game/src/test/java/com/gaia/world/generation/WorldGenerationConfigTest.java`
- Create:
  `game/src/test/java/com/gaia/world/generation/DeterministicCoordinateSamplerTest.java`
- Create:
  `game/src/test/java/com/gaia/world/generation/GenerationRegionTest.java`
- Create:
  `game/src/test/java/com/gaia/world/generation/GenerationContractTest.java`

**Interfaces:**
- Produces:

```java
public record WorldGenerationConfig(
        long seed,
        int algorithmVersion,
        int chunkRadius,
        BiomeSettings biome,
        HeightSettings height,
        CaveSettings cave,
        SurfaceSettings surface,
        DecorationSettings decoration,
        SpawnSettings spawn) {
    public static WorldGenerationConfig defaults();
    public String canonicalFingerprintInput();

    public record BiomeSettings(
            double scale,
            double transitionSharpness) {}

    public record HeightSettings(
            double detailScale,
            int minimumSurfaceHeight,
            int maximumSurfaceHeight,
            int plainsBase,
            int plainsVariation,
            int hillsBase,
            int hillsVariation,
            int highlandsBase,
            int highlandsVariation) {}

    public record CaveSettings(
            double scale,
            double threshold,
            int bedrockDepth,
            int surfaceBuffer) {}

    public record SurfaceSettings(
            int dirtDepth,
            double rockyWeightThreshold,
            double rockySlopeThreshold) {}

    public record DecorationSettings(
            int chanceDenominator,
            int maximumOutcropHeight) {}

    public record SpawnSettings(
            int maximumSearchRadiusBlocks,
            int requiredEmptyBlocks) {}
}

public enum BiomeType {
    PLAINS,
    ROLLING_HILLS,
    ROCKY_HIGHLANDS
}

public record BiomeSample(
        double plains,
        double rollingHills,
        double rockyHighlands) {
    public BiomeType dominant();
}

public final class DeterministicCoordinateSampler {
    public double unit(
            ResourceLocation stageId,
            int worldX, int worldY, int worldZ,
            long salt);
    public double valueNoise2D(
            ResourceLocation stageId,
            double worldX, double worldZ,
            double scale, long salt);
    public double valueNoise3D(
            ResourceLocation stageId,
            double worldX, double worldY, double worldZ,
            double scale, long salt);
}

public record GenerationBlockPalette(
        byte air, byte grass, byte dirt, byte stone) {
    public static GenerationBlockPalette from(BlockRegistry blocks);
}

public record GenerationContext(
        WorldGenerationConfig config,
        GenerationBlockPalette palette,
        DeterministicCoordinateSampler sampler) {}

public interface WorldGenerationStage {
    ResourceLocation id();
    GenerationStageResult generate(
            GenerationContext context, GenerationRegion region);
}

public interface WorldGenerator {
    WorldGenerationResult generate(
            GenerationContext context, ChunkKey key);
}

public record GenerationStageResult(
        ResourceLocation stageId,
        Status status,
        int samples,
        int writes,
        Optional<Throwable> failure) {
    public enum Status {
        SUCCEEDED,
        FAILED
    }
}

public record WorldGenerationResult(
        Optional<ChunkGenerationData> chunkData,
        List<GenerationStageResult> stageResults) {
    public boolean succeeded();
    public Optional<GenerationStageResult> failedStage();
}
```

`GenerationRegion` exposes bounded `getBlock`/`setBlock`, `BiomeSample` and
height column storage, world/local coordinate conversion, write count, and
`freeze()` returning Task 1 `ChunkGenerationData`.

- [ ] **Step 1: Write config, sampler, bounds, and result tests**

```java
@Test
void defaultsDefineApprovedFiniteWorld() {
    WorldGenerationConfig config = WorldGenerationConfig.defaults();
    assertEquals(12345L, config.seed());
    assertEquals(1, config.algorithmVersion());
    assertEquals(4, config.chunkRadius());
    assertEquals(81, squareChunkCount(config));
}

@Test
void coordinateSamplesIgnoreCallOrder() {
    DeterministicCoordinateSampler first = sampler(12345L, 1);
    double expected = first.unit(CAVE, -17, 42, 31, 9);
    first.unit(DECORATION, 999, 2, -400, 3);
    assertEquals(expected, first.unit(CAVE, -17, 42, 31, 9));
    assertNotEquals(expected, sampler(54321L, 1)
            .unit(CAVE, -17, 42, 31, 9));
}

@Test
void regionRejectsEveryOutOfBoundsWrite() {
    GenerationRegion region = region(new ChunkKey(-1, 2), 64);
    assertThrows(IndexOutOfBoundsException.class,
            () -> region.setBlock(-1, 2, 1, (byte) 3));
    assertThrows(IndexOutOfBoundsException.class,
            () -> region.setBlock(16, 2, 1, (byte) 3));
    assertThrows(IndexOutOfBoundsException.class,
            () -> region.setBlock(1, 64, 1, (byte) 3));
}
```

Cover non-positive algorithm version, negative radius, invalid nested tuning,
canonical fingerprint stability, stage-ID/salt/coordinate separation, negative
coordinates, `StrictMath` interpolation, palette ResourceLocation resolution,
defensive result lists, success/failure invariants, and freeze immutability.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :game:test `
  --tests "com.gaia.world.generation.*" `
  --console=plain --no-daemon
```

Expected: compilation fails because the generation package does not exist.

- [ ] **Step 3: Implement the deterministic core**

Use a fixed, documented SplitMix-style finalizer implemented locally:

```java
private static long mix64(long value) {
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
}

private static double unitDouble(long bits) {
    return (bits >>> 11) * 0x1.0p-53;
}
```

Hash the UTF-8 bytes of the canonical Stage `ResourceLocation` with a fixed
project-local integer fold before coordinate mixing. Value noise samples the
integer lattice through `unit`, applies a fixed quintic fade, and interpolates
with `StrictMath.floor`. It owns no mutable state after construction.

`GenerationRegion` stores bytes in the engine canonical layout and rejects
out-of-bounds writes. It may read out of bounds as air only through an
explicit `sampleLocalOrAir` method; normal writes always throw.

- [ ] **Step 4: Run the complete Task 3 tests**

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.generation.WorldGenerationConfigTest `
  --tests com.gaia.world.generation.DeterministicCoordinateSamplerTest `
  --tests com.gaia.world.generation.GenerationRegionTest `
  --tests com.gaia.world.generation.GenerationContractTest `
  --console=plain --no-daemon
```

Expected: all selected tests pass.

- [ ] **Step 5: Review and commit**

Confirm every result/value is immutable, all configuration inputs are in the
canonical fingerprint, and no static mutable state or graphics dependency
exists. Run `git diff --check`, then:

```powershell
git add game/src/main/java/com/gaia/world/generation `
  game/src/test/java/com/gaia/world/generation
git commit -m "feat(worldgen): add deterministic generation primitives"
```

---

### Task 4: Continuous Biome and Height Providers

**Files:**
- Create: `game/src/main/java/com/gaia/world/generation/BiomeProvider.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/ContinuousBiomeProvider.java`
- Create: `game/src/main/java/com/gaia/world/generation/HeightProvider.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/BlendedHeightProvider.java`
- Create:
  `game/src/test/java/com/gaia/world/generation/ContinuousBiomeProviderTest.java`
- Create:
  `game/src/test/java/com/gaia/world/generation/BlendedHeightProviderTest.java`

**Interfaces:**
- Consumes: Task 3 context, sampler, Region, Stage results.
- Produces:

```java
public enum BiomeType {
    PLAINS,
    ROLLING_HILLS,
    ROCKY_HIGHLANDS
}

public record BiomeSample(
        double plains,
        double rollingHills,
        double rockyHighlands) {
    public BiomeType dominant();
}

public interface BiomeProvider extends WorldGenerationStage {
    BiomeSample sample(
            GenerationContext context, int worldX, int worldZ);
}

public interface HeightProvider extends WorldGenerationStage {
    int sampleHeight(
            GenerationContext context,
            int worldX,
            int worldZ,
            BiomeSample biome);
}
```

- [ ] **Step 1: Write biome normalization, continuity, and height tests**

```java
@Test
void biomeWeightsAreNormalizedAtChunkBoundary() {
    BiomeSample west = provider.sample(context, 15, -4);
    BiomeSample east = provider.sample(context, 16, -4);
    assertEquals(1.0, west.plains()
            + west.rollingHills()
            + west.rockyHighlands(), 1.0e-12);
    assertEquals(1.0, east.plains()
            + east.rollingHills()
            + east.rockyHighlands(), 1.0e-12);
    assertTrue(distance(west, east) < 0.15);
}

@Test
void adjacentChunksUseOneWorldSpaceHeightFunction() {
    int left = height.sampleHeight(context, 15, 7,
            biome.sample(context, 15, 7));
    int right = height.sampleHeight(context, 16, 7,
            biome.sample(context, 16, 7));
    assertTrue(Math.abs(left - right) <= 3);
}
```

Cover weights in `[0,1]`, deterministic dominant tie order, negative
coordinates, absence of per-Chunk min/max normalization, configured height
bounds, seed difference, and fixed default-seed samples containing all three
dominant biome types within the 144-by-144 block finite world.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.generation.ContinuousBiomeProviderTest `
  --tests com.gaia.world.generation.BlendedHeightProviderTest `
  --console=plain --no-daemon
```

Expected: compilation fails because biome and height Provider types do not
exist.

- [ ] **Step 3: Implement continuous world-space biome and height fields**

Use two low-frequency sampler fields to derive smooth logits, apply a stable
positive weighting function, then normalize once per absolute coordinate.
Blend three configured base/amplitude/ruggedness height functions by those
weights. Clamp the final integer height to configured safe limits.

`generate` for the biome Provider writes every column's `BiomeSample`;
`generate` for the height Provider requires those samples and writes every
column height. Both return deterministic sample counts and no block writes.

- [ ] **Step 4: Run focused and primitive tests**

```powershell
.\gradlew.bat :game:test `
  --tests "com.gaia.world.generation.*" `
  --console=plain --no-daemon
```

Expected: all Task 3 and Task 4 tests pass.

- [ ] **Step 5: Review and commit**

Inspect border sampling and ensure neither Provider computes a Chunk-local
range or normalization. Run `git diff --check`, then:

```powershell
git add game/src/main/java/com/gaia/world/generation `
  game/src/test/java/com/gaia/world/generation
git commit -m "feat(worldgen): add continuous biome height providers"
```

---

### Task 5: Strata, Cave, Surface, and Decoration Providers

**Files:**
- Create:
  `game/src/main/java/com/gaia/world/generation/StrataDensityProvider.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/DefaultStrataDensityProvider.java`
- Create: `game/src/main/java/com/gaia/world/generation/CaveProvider.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/NoiseCaveProvider.java`
- Create: `game/src/main/java/com/gaia/world/generation/SurfaceProvider.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/DefaultSurfaceProvider.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/DecorationProvider.java`
- Create:
  `game/src/main/java/com/gaia/world/generation/StoneOutcropDecorationProvider.java`
- Create focused matching tests for all four default Providers.

**Interfaces:**
- Consumes: Task 4 biome/height columns and Task 3 Region/palette/sampler.
- Produces:

```java
public interface StrataDensityProvider extends WorldGenerationStage {}
public interface CaveProvider extends WorldGenerationStage {}
public interface SurfaceProvider extends WorldGenerationStage {}
public interface DecorationProvider extends WorldGenerationStage {}
```

Each default Provider has one stable ID:
`gaia:strata_density`, `gaia:cave`, `gaia:surface`, or `gaia:decoration`.

- [ ] **Step 1: Write block layering, cave, and decoration bounds tests**

```java
@Test
void surfaceProducesGrassDirtStoneLayers() {
    GenerationRegion region = generatedColumnRegion(24, BiomeType.PLAINS);
    run(strata, caveDisabledContext(), region);
    run(surface, caveDisabledContext(), region);
    assertEquals(GRASS, region.getBlock(3, 23, 5));
    assertEquals(DIRT, region.getBlock(3, 22, 5));
    assertEquals(DIRT, region.getBlock(3, 21, 5));
    assertEquals(STONE, region.getBlock(3, 20, 5));
}

@Test
void cavesPreserveConfiguredSurfaceBufferAndBedrock() {
    GenerationRegion region = solidRegionWithHeight(40);
    cave.generate(caveHeavyContext(), region);
    for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
            assertNotEquals(AIR, region.getBlock(x, 0, z));
            assertNotEquals(AIR, region.getBlock(x, 39, z));
            assertNotEquals(AIR, region.getBlock(x, 38, z));
        }
    }
}

@Test
void decorationCannotWriteAnotherChunk() {
    GenerationRegion region = trackingRegion(new ChunkKey(0, 0));
    decoration.generate(decorationHeavyContext(), region);
    assertTrue(region.recordedWrites().stream()
            .allMatch(write -> write.localX() >= 0
                    && write.localX() < 16
                    && write.localZ() >= 0
                    && write.localZ() < 16));
}
```

Cover empty/low columns, maximum height, steep rocky surfaces, cave
determinism, at least one fixed-seed cave sample, decoration independence from
iteration order, and exclusive use of palette IDs.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.generation.DefaultStrataDensityProviderTest `
  --tests com.gaia.world.generation.NoiseCaveProviderTest `
  --tests com.gaia.world.generation.DefaultSurfaceProviderTest `
  --tests com.gaia.world.generation.StoneOutcropDecorationProviderTest `
  --console=plain --no-daemon
```

Expected: compilation fails because the Provider interfaces and implementations
do not exist.

- [ ] **Step 3: Implement bounded Providers**

Strata fills stone to the sampled height, then marks the configured shallow
soil band. Cave sampling considers only cells between the configured bedrock
and surface buffers and replaces selected cells with palette air. Surface
re-evaluates exposed top cells after caves and writes grass/dirt or rocky stone
according to biome weight and local world-space slope. Decoration evaluates a
coordinate-derived chance per surface column and places only bounded
grass/dirt/stone variations or small single-Chunk stone outcrops.

All loops use the Region's exact bounds. No Provider catches and hides a bounds
exception.

- [ ] **Step 4: Run all generation Provider tests**

```powershell
.\gradlew.bat :game:test `
  --tests "com.gaia.world.generation.*ProviderTest" `
  --tests com.gaia.world.generation.GenerationRegionTest `
  --console=plain --no-daemon
```

Expected: all selected tests pass.

- [ ] **Step 5: Review and commit**

Verify no Provider imports the repository, world, renderer, interaction,
inventory, or world-item packages. Run `git diff --check`, then:

```powershell
git add game/src/main/java/com/gaia/world/generation `
  game/src/test/java/com/gaia/world/generation
git commit -m "feat(worldgen): add terrain cave surface decoration stages"
```

---

### Task 6: Compose the Pipeline and Migrate GaiaWorldGenerator

**Files:**
- Create:
  `game/src/main/java/com/gaia/world/generation/StagedWorldGenerator.java`
- Modify: `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`
- Create:
  `game/src/test/java/com/gaia/world/generation/StagedWorldGeneratorTest.java`
- Modify: `game/src/test/java/com/gaia/world/GaiaWorldGeneratorTest.java`

**Interfaces:**
- Consumes: Tasks 3-5 `WorldGenerator`, ordered Providers, Region, context.
- Produces:

```java
public final class StagedWorldGenerator implements WorldGenerator {
    public StagedWorldGenerator(List<WorldGenerationStage> stages);
    @Override
    public WorldGenerationResult generate(
            GenerationContext context, ChunkKey key);
}

public final class GaiaWorldGenerator {
    public static WorldGenerator createDefault();
}
```

The default order is exactly biome, height, strata/density, cave, surface,
decoration.

- [ ] **Step 1: Write Pipeline stop/order and production-content tests**

```java
@Test
void executesStagesInDeclaredOrderAndFreezesOnce() {
    List<ResourceLocation> calls = new ArrayList<>();
    WorldGenerator generator =
            new StagedWorldGenerator(List.of(
                    successStage("gaia:first", calls),
                    successStage("gaia:second", calls)));
    WorldGenerationResult result = generator.generate(context(), KEY);
    assertTrue(result.succeeded());
    assertEquals(List.of(parse("gaia:first"), parse("gaia:second")), calls);
    assertEquals(2, result.stageResults().size());
}

@Test
void firstFailureStopsPipelineAndReturnsNoChunkData() {
    WorldGenerationResult result =
            new StagedWorldGenerator(List.of(
                    successStage("gaia:first"),
                    failedStage("gaia:failed"),
                    forbiddenStage()))
                    .generate(context(), KEY);
    assertFalse(result.succeeded());
    assertTrue(result.chunkData().isEmpty());
    assertEquals(parse("gaia:failed"),
            result.failedStage().orElseThrow().stageId());
}

@Test
void defaultPipelineUsesProductionResourceIds() {
    WorldGenerationResult result =
            GaiaWorldGenerator.createDefault()
                    .generate(productionContext(), new ChunkKey(0, 0));
    ChunkGenerationData data = result.chunkData().orElseThrow();
    assertContainsOnly(data, Set.of(AIR, GRASS, DIRT, STONE));
    assertHasLayeredSurface(data);
}
```

Also test null/empty/duplicate Stage IDs, deterministic result-list ordering,
Stage-thrown `RuntimeException` and `Error` conversion to failed result, and
the absence of old static Perlin state.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.generation.StagedWorldGeneratorTest `
  --tests com.gaia.world.GaiaWorldGeneratorTest `
  --console=plain --no-daemon
```

Expected: compilation or assertions fail because the Pipeline/default factory
has not replaced the monolith.

- [ ] **Step 3: Implement ordered execution and default composition**

`StagedWorldGenerator` constructs a fresh Region, executes immutable Stage
instances in order, converts a thrown failure into the current Stage's failed
result, stops immediately, and freezes exactly once after all successes.

Replace `GaiaWorldGenerator`'s static `PerlinNoise`, constants, and direct
`World.generate` callback with the factory above. Do not retain an overload
that accepts `World` or `ChunkRepository`.

- [ ] **Step 4: Run Pipeline, Provider, and resource tests**

```powershell
.\gradlew.bat :game:test `
  --tests "com.gaia.world.generation.*" `
  --tests com.gaia.world.GaiaWorldGeneratorTest `
  --tests com.gaia.assets.GaiaProductionAssetsTest `
  --console=plain --no-daemon
```

Expected: all selected tests pass and production IDs remain valid.

- [ ] **Step 5: Review and commit**

Scan the production generator for `static PerlinNoise`, `Random`, `World`,
`ChunkRepository`, `setBlock`, Mesh, and Renderer. Run `git diff --check`,
then:

```powershell
git add game/src/main/java/com/gaia/world `
  game/src/test/java/com/gaia/world
git commit -m "feat(worldgen): compose staged Gaia terrain pipeline"
```

---

### Task 7: Finite Loading, Explicit Failure, Safe Spawn, and Debug Rebuild

**Files:**
- Create: `game/src/main/java/com/gaia/world/WorldLoadState.java`
- Create: `game/src/main/java/com/gaia/world/WorldLoadFailure.java`
- Create: `game/src/main/java/com/gaia/world/WorldLoadException.java`
- Create: `game/src/main/java/com/gaia/world/WorldRebuildResult.java`
- Create: `game/src/main/java/com/gaia/world/SafeSpawnSelector.java`
- Modify: `game/src/main/java/com/gaia/world/WorldLoader.java`
- Modify: `game/src/main/java/com/gaia/world/WorldLoadResult.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/GameContext.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `engine/src/main/java/com/overlord/config/GameConfig.java`
- Modify: `game/src/test/java/com/gaia/world/WorldLoaderTest.java`
- Create: `game/src/test/java/com/gaia/world/SafeSpawnSelectorTest.java`
- Modify: `game/src/test/java/com/gaia/GameBootstrapTest.java`
- Modify: `game/src/test/java/com/gaia/GameLoopStructureTest.java`

**Interfaces:**
- Consumes: Task 1/2 repository transaction and Task 6 generator.
- Produces:

```java
public enum WorldLoadState {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

public record WorldLoadFailure(
        Set<ChunkKey> completedChunks,
        Optional<ChunkKey> failedChunk,
        Optional<ResourceLocation> failedStage,
        ResourceLocation code,
        Throwable cause) {}

public record WorldRebuildResult(
        Set<ChunkKey> committedChunks,
        Map<ChunkKey, ChunkGenerationResult> outcomes) {}

public final class WorldLoadException extends RuntimeException {
    public WorldLoadException(WorldLoadFailure failure);
    public WorldLoadFailure failure();
}

public final class SafeSpawnSelector {
    public Optional<Vector3f> find(
            World world,
            Set<ChunkKey> committedChunks,
            WorldGenerationConfig config);
}

public record WorldLoadResult(
        Set<ChunkKey> initialChunks,
        Vector3f playerFeetPosition,
        String configFingerprint,
        String generationHash) {}

public final class WorldLoader {
    public WorldLoader(
            WorldGenerator generator,
            BlockRegistry blocks,
            WorldGenerationConfig config,
            SafeSpawnSelector spawnSelector);
public WorldLoadResult load(World world);
public WorldRebuildResult rebuildRegion(
        World world,
        Set<ChunkKey> keys,
        WorldGenerationConfig config);
public WorldLoadState state();
public Optional<WorldLoadFailure> failure();
}
```

`WorldLoadResult` adds the config fingerprint and aggregate generation hash
while preserving defensive initial keys and feet-position copies.

- [ ] **Step 1: Write load range, failure, spawn, cancellation, and rebuild tests**

```java
@Test
void defaultLoadCommitsInclusiveRadiusFourThroughTransactions() {
    World world = new World();
    WorldLoader loader = loader(defaults());
    WorldLoadResult result = loader.load(world);
    assertEquals(81, result.initialChunks().size());
    assertTrue(result.initialChunks().contains(new ChunkKey(-4, -4)));
    assertTrue(result.initialChunks().contains(new ChunkKey(4, 4)));
    assertEquals(WorldLoadState.SUCCEEDED, loader.state());
}

@Test
void failedStageLeavesBatchFailedAndNeverReturnsSuccess() {
    WorldLoader loader = loader(generatorFailingAt(new ChunkKey(0, 1)));
    WorldLoadException failure =
            assertThrows(WorldLoadException.class,
                    () -> loader.load(new World()));
    assertEquals(WorldLoadState.FAILED, loader.state());
    assertEquals(new ChunkKey(0, 1),
            failure.failure().failedChunk().orElseThrow());
    assertFalse(failure.failure().completedChunks().isEmpty());
}

@Test
void safeSpawnRequiresSupportAndTwoEmptyHeadCells() {
    World world = generatedWorldWithColumns();
    Optional<Vector3f> spawn =
            new SafeSpawnSelector().find(world, world.chunks().keys(), config);
    Vector3f feet = spawn.orElseThrow();
    int x = (int) StrictMath.floor(feet.x);
    int y = (int) StrictMath.floor(feet.y);
    int z = (int) StrictMath.floor(feet.z);
    assertNotEquals(0, world.getBlock(x, y - 1, z));
    assertEquals(0, world.getBlock(x, y, z));
    assertEquals(0, world.getBlock(x, y + 1, z));
}

@Test
void rebuildUsesRevisionGuardAndNeverCallsGameplayMutation() {
    World world = initiallyLoadedWorld();
    long before = world.chunks().revision(KEY);
    WorldRebuildResult result =
            loader.rebuildRegion(world, Set.of(KEY), alternateConfig());
    assertEquals(ChunkGenerationResult.Status.COMMITTED,
            result.outcomes().get(KEY).status());
    assertTrue(world.chunks().revision(KEY) > before);
    assertEquals(ChunkState.DIRTY, world.chunks().state(KEY));
    assertEquals(0, gameplayPublisher.events().size());
}
```

Cover deterministic key order, invalid radius overflow, no safe spawn, worker
thread ownership, already-interrupted cancellation, failure cause preservation,
completed-key immutability, stale rebuild conflict, mixed rebuild outcomes,
and `GameLoop` refusing `RUNNING` on failed load.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.WorldLoaderTest `
  --tests com.gaia.world.SafeSpawnSelectorTest `
  --tests com.gaia.GameBootstrapTest `
  --tests com.gaia.GameLoopStructureTest `
  --console=plain --no-daemon
```

Expected: tests fail because loading still uses the old generator/direct
fallback writes and has no explicit load/rebuild status.

- [ ] **Step 3: Implement batch orchestration and spawn selection**

Construct keys in ascending X then Z order over the inclusive radius. For each
key:

```java
ChunkGenerationTicket ticket =
        world.chunks().beginGeneration(key, mode);
WorldGenerationResult generated =
        generator.generate(contextFor(config), key);
if (!generated.succeeded()) {
    Throwable cause =
            generated.failedStage()
                    .orElseThrow()
                    .failure()
                    .orElseThrow();
    world.chunks().failGeneration(ticket, cause);
    WorldLoadFailure failure =
            new WorldLoadFailure(
                    Set.copyOf(completed),
                    Optional.of(key),
                    generated.failedStage()
                            .map(GenerationStageResult::stageId),
                    ResourceLocation.parse("gaia:generation_failed"),
                    cause);
    throw new WorldLoadException(failure);
}
ChunkGenerationResult committed =
        world.chunks().commitGeneration(
                ticket, generated.chunkData().orElseThrow());
if (committed.status()
        != ChunkGenerationResult.Status.COMMITTED) {
    IllegalStateException cause =
            new IllegalStateException(
                    "Generation commit "
                            + committed.status()
                            + " for "
                            + key);
    WorldLoadFailure failure =
            new WorldLoadFailure(
                    Set.copyOf(completed),
                    Optional.of(key),
                    Optional.empty(),
                    ResourceLocation.parse("gaia:generation_commit_failed"),
                    cause);
    throw new WorldLoadException(failure);
}
```

Update loader state in a `try/catch/finally` path that distinguishes
cancellation from failure and never overwrites a terminal failure. Remove the
fallback ground ID and every direct `World.setBlock`.

`SafeSpawnSelector` scans only committed keys and sorts candidates by squared
distance, X, Z, then feet Y. It requires solid support plus empty feet/head
blocks. No candidate produces `WorldLoadException`.

`GameBootstrap` creates default config, palette/context, the default Pipeline,
and the loader explicitly. Keep the existing dedicated world executor and
shutdown ordering. `GameLoop` handles the loader's failed future through an
explicit `FAILED` state before stopping/rethrowing.

Remove obsolete `GameConfig.WorldGeneration` constants after all consumers
use `WorldGenerationConfig`.

- [ ] **Step 4: Run loader, composition, lifecycle, and physics-spawn tests**

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.WorldLoaderTest `
  --tests com.gaia.world.SafeSpawnSelectorTest `
  --tests com.gaia.GameBootstrapTest `
  --tests com.gaia.GameLoopStructureTest `
  --tests com.gaia.PhysicsCompositionStructureTest `
  --tests com.overlord.physics.PlayerControllerTraversalTest `
  --console=plain --no-daemon
```

Expected: all selected tests pass; the initial set contains 81 keys.

- [ ] **Step 5: Review and commit**

Inspect executor ownership, failure transitions, defensive copies, and absence
of gameplay/GPU calls. Run `git diff --check`, then:

```powershell
git add engine/src/main/java/com/overlord/config/GameConfig.java `
  game/src/main/java/com/gaia `
  game/src/test/java/com/gaia
git commit -m "feat(worldgen): add finite load spawn rebuild workflow"
```

---

### Task 8: Scheduling Determinism, Snapshot Contract, and Architecture Guards

**Files:**
- Create:
  `game/src/main/java/com/gaia/world/generation/WorldGenerationHasher.java`
- Create:
  `game/src/test/java/com/gaia/world/generation/WorldGenerationDeterminismTest.java`
- Create:
  `game/src/test/java/com/gaia/world/generation/WorldGenerationSnapshotTest.java`
- Create:
  `game/src/test/java/com/gaia/world/generation/WorldGenerationBoundaryTest.java`
- Create:
  `game/src/test/java/com/gaia/world/WorldGenerationArchitectureTest.java`
- Modify:
  `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`
- Create: `docs/architecture/phase-04-deterministic-snapshots.md`

**Interfaces:**
- Consumes: all production behavior from Tasks 1-7.
- Produces:

```java
public final class WorldGenerationHasher {
    public static String hashChunk(
            WorldGenerationConfig config,
            ChunkGenerationData data);
    public static String hashRegion(
            WorldGenerationConfig config,
            Collection<ChunkGenerationData> chunks);
}
```

- [ ] **Step 1: Write determinism, boundary, hash, and structure tests**

```java
@Test
void schedulingOrderDoesNotChangeAggregateHash() throws Exception {
    List<ChunkKey> keys = defaultKeys();
    String forward = generateAndHash(keys);
    List<ChunkKey> reverse = new ArrayList<>(keys);
    Collections.reverse(reverse);
    String reversed = generateAndHash(reverse);
    String shuffledConcurrent = generateConcurrentlyAndHash(
            shuffled(keys, 99173L), 4);
    assertEquals(forward, reversed);
    assertEquals(forward, shuffledConcurrent);
}

@Test
void adjacentBoundaryColumnsMatchWorldSpaceProviders() {
    GeneratedPair pair = generate(new ChunkKey(0, 0), new ChunkKey(1, 0));
    for (int z = 0; z < 16; z++) {
        assertTrue(Math.abs(
                pair.heightAtLeft(15, z)
                - pair.heightAtRight(0, z)) <= 3);
        assertSurfaceIsValid(pair.left(), 15, z);
        assertSurfaceIsValid(pair.right(), 0, z);
    }
}

@Test
void fixedDefaultWorldMatchesApprovedSnapshot() {
    assertEquals(VERSION_ONE_REGION_HASH,
            generateAndHash(defaultKeys()));
}
```

The architecture test reads production sources and fails if world generation
references Renderer, Mesh, `ChunkMeshManager`, LWJGL, GLFW, OpenGL,
`WorldMutationService`, block-change events, inventory, world-item services,
`World.setBlock`, global `Random`, or static `PerlinNoise`. A final Git
name-only scan, rather than a runtime test, verifies that `Renderer`,
`PlayerController`, and `ChunkMeshManager` are absent from the Phase 4 diff.

- [ ] **Step 2: Run behavior tests before recording snapshots**

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.generation.WorldGenerationDeterminismTest `
  --tests com.gaia.world.generation.WorldGenerationBoundaryTest `
  --tests com.gaia.world.WorldGenerationArchitectureTest `
  --console=plain --no-daemon
```

Expected: behavior and structure tests pass. If any scheduling-order hash
differs, stop and fix deterministic ownership before creating snapshot
constants.

- [ ] **Step 3: Generate and independently verify fixed hashes/check coordinates**

Add a test helper that prints candidate hashes while asserting only the
64-character lowercase hexadecimal shape and two-run equality. Run it twice
in separate Gradle processes and confirm byte-for-byte equality:

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.generation.WorldGenerationSnapshotTest `
  --rerun-tasks --console=plain --no-daemon
.\gradlew.bat clean :game:test `
  --tests com.gaia.world.generation.WorldGenerationSnapshotTest `
  --console=plain --no-daemon
```

Use provider queries to select the nearest deterministic coordinate for each
dominant biome and the first valid cave exposure, plus origin, negative, and
boundary coordinates. Record exact coordinates and hashes in
`phase-04-deterministic-snapshots.md`.

- [ ] **Step 4: Lock snapshot constants and verify GREEN**

Replace the candidate-print assertion with exact non-empty SHA-256 constants
for representative Chunks and the 81-Chunk aggregate. The document records
seed `12345L`, algorithm version `1`, canonical configuration input, each
coordinate, and the rule that intentional hash changes require a documented
version/config update.

Run:

```powershell
.\gradlew.bat :engine:test :game:test `
  --rerun-tasks --console=plain --no-daemon
```

Expected: every engine/game test passes; forward, reverse, and concurrent
generation share the exact aggregate hash.

- [ ] **Step 5: Review and commit**

Review snapshot byte ordering, SHA-256 inputs, platform-neutral encoding,
representative coordinate validity, and structure-test allowlists. Run
`git diff --check`, then:

```powershell
git add game/src/main/java/com/gaia/world/generation/WorldGenerationHasher.java `
  game/src/test/java/com/gaia/world `
  engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java `
  docs/architecture/phase-04-deterministic-snapshots.md
git commit -m "test(worldgen): lock deterministic generation snapshots"
```

---

### Task 9: Final Documentation, Owner Review, and Verification

**Files:**
- Modify: `docs/architecture/current-baseline.md`
- Create: `docs/agent-handoffs/phase-04-handoff.md`
- Modify:
  `docs/superpowers/plans/2026-07-25-deterministic-worldgen-pipeline.md`

**Interfaces:**
- Documents and protects every approved Phase 4 boundary without changing
  production behavior.

**2026-07-25 execution note:** the documentation delegate completed Steps 3
and 6 without changing production or test code. The root orchestrator retains
Steps 1-2, the final branch-wide owner reviews in Step 4, and the post-doc
clean/package verification in Step 5. Step 7 is complete only after the
documentation commit and ignored coordination report exist.

- [ ] **Step 1: Add documentation/source assertions before docs**

Extend `WorldGenerationArchitectureTest` to require the final baseline,
snapshot document, and handoff to name:

- repository generation transaction and per-Chunk atomic publication;
- explicit failure state;
- deterministic seed/version/config contract;
- six ordered Providers;
- Phase 3 revision/dirty/stale authority;
- Phase 7 gameplay mutation exclusion;
- default 81-Chunk finite range;
- debug rebuild lifecycle;
- fixed test seed and manual coordinates.

- [ ] **Step 2: Run the documentation test and confirm RED**

```powershell
.\gradlew.bat :game:test `
  --tests com.gaia.world.WorldGenerationArchitectureTest `
  --console=plain --no-daemon
```

Expected: the documentation assertion fails because the handoff and updated
baseline do not yet contain the required final statements.

- [x] **Step 3: Write final baseline and handoff**

`phase-04-handoff.md` records:

- completed and unfinished work;
- core architecture decisions;
- exact modified files;
- focused, full, packaged-resource, and manual test results;
- fixed seed, aggregate hash, and manual coordinates;
- owner-review findings and resolutions;
- known risks;
- interfaces Phase 5/8/9 and later world work must not break;
- final diff stat, suggested commit message, and suggested PR title/description.

The baseline describes the new repository generation transaction, CPU Pipeline,
finite loader, failure state, safe spawn, and debug rebuild without claiming
unperformed Windows interactive or native macOS verification.

- [ ] **Step 4: Run branch-wide engine and game owner reviews**

Review `origin/main..HEAD` across engine, game, shared docs, resources, tests,
and build files. Classify Critical, Important, and Minor findings. Resolve
every Critical and Important finding through a focused RED/GREEN regression
before final verification. Re-run both owners after fixes.

- [ ] **Step 5: Run final automated verification**

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources `
  --rerun-tasks --console=plain --no-daemon
git diff --check
git diff --check origin/main..HEAD
git status --short --branch
git diff --stat origin/main..HEAD
```

Count JUnit XML suites/tests/failures/errors/skips. Verify:

```powershell
git ls-files |
  Select-String -Pattern '(^|/)(build|bin)/|\.class$|hs_err_pid|replay_pid'
rg -n "org\.gradle\.java\.home|/Library/Java|[A-Za-z]:\\\\" gradle.properties
rg -n "Renderer|Mesh|ChunkMeshManager|org\.lwjgl|GLFW|OpenGL|gl[A-Z]" `
  game/src/main/java/com/gaia/world
rg -n "WorldMutationService|BeforeBlockChangedEvent|BlockChangedEvent|Inventory|WorldItem|world\.setBlock" `
  game/src/main/java/com/gaia/world
git diff --name-only origin/main..HEAD |
  Select-String -Pattern 'Renderer|PlayerController|ChunkMeshManager'
```

Expected: build and packaged verification pass; JUnit reports zero
failure/error/skip; policy scans produce no prohibited match; the worktree is
clean after the final commit.

- [x] **Step 6: Record manual/platform truth**

Run Windows `.\gradlew.bat :game` only as an interactive developer smoke test.
Record whether plains, hills, highlands, cave, boundary continuity, spawn,
rebuild, resize/input, and Escape shutdown were actually observed. On native
macOS, run `./gradlew clean test build` and `./gradlew :game`, then compare the
recorded aggregate hash. If either platform is unavailable, mark it **NOT
RUN**, not passed.

- [ ] **Step 7: Commit documentation and report without publishing**

```powershell
git add docs/architecture/current-baseline.md `
  docs/architecture/phase-04-deterministic-snapshots.md `
  docs/agent-handoffs/phase-04-handoff.md `
  docs/superpowers/plans/2026-07-25-deterministic-worldgen-pipeline.md `
  game/src/test/java/com/gaia/world/WorldGenerationArchitectureTest.java
git commit -m "docs(worldgen): record deterministic pipeline handoff"
```

Report final HEAD, exact test count, hashes, owner verdict, `git diff --stat`,
known risks, unfinished work, suggested overall commit message, and suggested
PR title/description. Do not push, create a pull request, or merge.
