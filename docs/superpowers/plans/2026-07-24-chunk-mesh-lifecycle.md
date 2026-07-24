# Independent Chunk Mesh Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the combined terrain mesh with revision-safe independent
chunk CPU meshes and main-thread GPU render objects.

**Architecture:** `ChunkRepository` owns voxel entries, lifecycle state, short
snapshot locks, and mesh revisions. `ChunkMeshManager` claims immutable
neighborhood snapshots for worker meshing, rejects stale results, and applies
a two-upload-per-frame main-thread budget through a renderer backend.

**Tech Stack:** Java 17, Gradle 8.5 Wrapper, JUnit Jupiter 6.1.1, LWJGL 3.3.3,
OpenGL 4.1, GLSL 410, JOML 1.10.5.

## Global Constraints

- Work only on `refactor/chunk-mesh-lifecycle`, based on refreshed
  `origin/main`; never commit to or push `main`.
- Keep `engine` independent of `game`; `game` may use public `engine` APIs.
- All OpenGL calls and GPU create/upload/draw/release operations run on the
  context-owning main thread and assert `MainThreadGuard`.
- CPU generation, snapshot copying, and CPU meshing may use workers; workers
  must not reference or invoke GPU resources.
- Keep Java 17 source/target compatibility and OpenGL 4.1 / GLSL 410.
- Do not add OpenGL 4.3+, compute shaders, platform-exclusive APIs, absolute
  JDK paths, generated artifacts, or new `ServiceLocator` uses.
- Preserve terrain seed, noise, height rules, block IDs, materials, atlas
  pixels, UV behavior, player behavior, and Phase 1 fixed-update ordering.
- Do not implement automatic distance streaming, frustum culling, LOD,
  transparent sorting, block interaction UI, new terrain, or visual effects.
- Use the Gradle Wrapper. Every task follows RED, GREEN, refactor, focused
  verification, independent review, and a local commit.
- A cross-boundary task modifying both `engine/**` and `game/**` requires
  engine-owner and game-owner review.

---

## Planned File Structure

### Engine voxel lifecycle

- `engine/src/main/java/com/overlord/voxel/ChunkKey.java`
  - value key and world/local coordinate conversion.
- `engine/src/main/java/com/overlord/voxel/ChunkState.java`
  - lifecycle enum.
- `engine/src/main/java/com/overlord/voxel/ChunkDirtyTracker.java`
  - target and horizontal-neighbor dirty calculation.
- `engine/src/main/java/com/overlord/voxel/ChunkSnapshot.java`
  - immutable owned voxel copy.
- `engine/src/main/java/com/overlord/voxel/ChunkMeshInput.java`
  - center plus four immutable neighbor snapshots.
- `engine/src/main/java/com/overlord/voxel/ChunkMeshData.java`
  - CPU result, revision, vertices, and bounds.
- `engine/src/main/java/com/overlord/voxel/ChunkMesher.java`
  - functional CPU meshing seam.
- `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
  - entries, states, revisions, mutation, snapshot claims, and unload state.
- `engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java`
  - worker scheduling, completion, upload budget, replacement, unload, close.

### Engine rendering

- `engine/src/main/java/com/overlord/renderer/AxisAlignedBounds.java`
  - immutable local/world bounds.
- `engine/src/main/java/com/overlord/renderer/ChunkGpuMesh.java`
  - draw/release test seam implemented by `Mesh`.
- `engine/src/main/java/com/overlord/renderer/ChunkRenderObject.java`
  - chunk key, revision, GPU mesh, model transform, world bounds.
- `engine/src/main/java/com/overlord/renderer/ChunkRenderBackend.java`
  - upload/release boundary implemented by `Renderer`.

### Modified engine files

- `engine/src/main/java/com/overlord/voxel/Chunk.java`
- `engine/src/main/java/com/overlord/voxel/SubChunk.java`
- `engine/src/main/java/com/overlord/voxel/World.java`
- `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java`
- `engine/src/main/java/com/overlord/renderer/Mesh.java`
- `engine/src/main/java/com/overlord/renderer/Renderer.java`

### Modified game files

- `game/src/main/java/com/gaia/GameBootstrap.java`
- `game/src/main/java/com/gaia/GameContext.java`
- `game/src/main/java/com/gaia/GameLoop.java`
- `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`
- `game/src/main/java/com/gaia/world/WorldLoader.java`
- `game/src/main/java/com/gaia/world/WorldLoadResult.java`

### Tests and handoff

- Focused tests mirror each production type under `engine/src/test` and
  `game/src/test`.
- `docs/agent-handoffs/phase-03-handoff.md` records the completed phase.

---

### Task 1: Chunk Coordinates, Bounds, States, and Dirty Mapping

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/ChunkKey.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkState.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkDirtyTracker.java`
- Create: `engine/src/main/java/com/overlord/renderer/AxisAlignedBounds.java`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkKeyTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkDirtyTrackerTest.java`
- Create: `engine/src/test/java/com/overlord/renderer/AxisAlignedBoundsTest.java`

**Interfaces:**
- Produces:

```java
public record ChunkKey(int x, int z) {
    public static ChunkKey fromWorld(int worldX, int worldZ);
    public static int localCoordinate(int worldCoordinate);
    public int worldOriginX();
    public int worldOriginZ();
    public ChunkKey north();
    public ChunkKey south();
    public ChunkKey west();
    public ChunkKey east();
}

public enum ChunkState {
    EMPTY,
    GENERATING,
    GENERATED,
    MESHING,
    READY_FOR_UPLOAD,
    RENDERABLE,
    DIRTY,
    UNLOADING
}

public final class ChunkDirtyTracker {
    public Set<ChunkKey> affectedByBlock(
            ChunkKey target, int localX, int localZ);
    public Set<ChunkKey> horizontalNeighbors(ChunkKey target);
}

public record AxisAlignedBounds(
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ) {
    public AxisAlignedBounds translate(float x, float y, float z);
}
```

- [ ] **Step 1: Write coordinate, dirty-set, and bounds tests**

```java
@Test
void convertsNegativeWorldCoordinatesWithFloorRules() {
    assertEquals(new ChunkKey(-1, -1), ChunkKey.fromWorld(-1, -1));
    assertEquals(15, ChunkKey.localCoordinate(-1));
    assertEquals(-16, new ChunkKey(-1, 0).worldOriginX());
}

@Test
void cornerChangeDirtiesOnlyTwoOrthogonalNeighbors() {
    ChunkKey center = new ChunkKey(0, 0);
    assertEquals(
            Set.of(center, center.west(), center.north()),
            new ChunkDirtyTracker().affectedByBlock(center, 0, 0));
}

@Test
void translatesBoundsWithoutChangingExtent() {
    AxisAlignedBounds local =
            new AxisAlignedBounds(0, 2, 1, 16, 9, 16);
    assertEquals(
            new AxisAlignedBounds(32, 2, -15, 48, 9, 0),
            local.translate(32, 0, -16));
}
```

Cover interior, all four edges, all four corners, invalid local coordinates,
positive coordinates, negative exact multiples of 16, and neighbor methods.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkKeyTest" --tests "com.overlord.voxel.ChunkDirtyTrackerTest" --tests "com.overlord.renderer.AxisAlignedBoundsTest"
```

Expected: `compileTestJava` fails because the four value types do not exist.

- [ ] **Step 3: Implement the minimal value types**

Use the configured chunk size, not a duplicate literal:

```java
public static ChunkKey fromWorld(int worldX, int worldZ) {
    return new ChunkKey(
            Math.floorDiv(worldX, GameConfig.Chunk.SIZE),
            Math.floorDiv(worldZ, GameConfig.Chunk.SIZE));
}

public static int localCoordinate(int worldCoordinate) {
    return Math.floorMod(
            worldCoordinate, GameConfig.Chunk.SIZE);
}
```

`affectedByBlock` starts with a `LinkedHashSet` containing the target and adds
only the matching cardinal neighbors. Validate local coordinates against
`0..GameConfig.Chunk.SIZE - 1`.

Validate every bounds component is finite and each maximum is greater than or
equal to its corresponding minimum.

- [ ] **Step 4: Run focused and full engine tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkKeyTest" --tests "com.overlord.voxel.ChunkDirtyTrackerTest" --tests "com.overlord.renderer.AxisAlignedBoundsTest"
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/voxel/ChunkKey.java engine/src/main/java/com/overlord/voxel/ChunkState.java engine/src/main/java/com/overlord/voxel/ChunkDirtyTracker.java engine/src/main/java/com/overlord/renderer/AxisAlignedBounds.java engine/src/test/java/com/overlord/voxel/ChunkKeyTest.java engine/src/test/java/com/overlord/voxel/ChunkDirtyTrackerTest.java engine/src/test/java/com/overlord/renderer/AxisAlignedBoundsTest.java
git commit -m "feat(voxel): add chunk lifecycle value types"
```

---

### Task 2: Immutable Chunk Snapshots and Repository State

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/ChunkSnapshot.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `engine/src/main/java/com/overlord/voxel/Chunk.java`
- Modify: `engine/src/main/java/com/overlord/voxel/SubChunk.java`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkSnapshotTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java`

**Interfaces:**
- Consumes: `ChunkKey`, `ChunkState`, `ChunkDirtyTracker`.
- Produces:

```java
public final class ChunkSnapshot {
    public ChunkKey key();
    public long revision();
    public int worldHeight();
    public byte getBlock(int localX, int y, int localZ);
    public static ChunkSnapshot of(
            ChunkKey key,
            long revision,
            int worldHeight,
            byte[] blocks);
    public static ChunkSnapshot empty(
            ChunkKey key, long revision, int worldHeight);
}

public final class ChunkRepository {
    public ChunkRepository();
    public ChunkRepository(
            int worldHeight, ChunkDirtyTracker dirtyTracker);
    public boolean contains(ChunkKey key);
    public Set<ChunkKey> keys();
    public ChunkState state(ChunkKey key);
    public long revision(ChunkKey key);
    public byte getBlock(int worldX, int y, int worldZ);
    public void generate(
            ChunkKey key, Consumer<Chunk> generator);
    public boolean setBlock(
            int worldX, int y, int worldZ, byte blockId);
    public Optional<ChunkSnapshot> snapshot(ChunkKey key);
    public boolean isRenderable(ChunkKey key);
}
```

- [ ] **Step 1: Write repository state and snapshot tests**

```java
@Test
void generationTransitionsOnceAndSnapshotOwnsBytes() {
    ChunkRepository repository = new ChunkRepository();
    ChunkKey key = new ChunkKey(0, 0);

    repository.generate(
            key, chunk -> chunk.setBlock(1, 2, 3, (byte) 7));

    assertEquals(ChunkState.GENERATED, repository.state(key));
    assertEquals(1L, repository.revision(key));
    ChunkSnapshot snapshot = repository.snapshot(key).orElseThrow();

    repository.setBlock(1, 2, 3, (byte) 9);

    assertEquals(7, Byte.toUnsignedInt(snapshot.getBlock(1, 2, 3)));
    assertEquals(9, Byte.toUnsignedInt(repository.getBlock(1, 2, 3)));
}

@Test
void missingReadsAndAirWritesDoNotCreateEntries() {
    ChunkRepository repository = new ChunkRepository();
    assertEquals(0, repository.getBlock(20, 5, -2));
    assertFalse(repository.setBlock(20, 5, -2, (byte) 0));
    assertTrue(repository.keys().isEmpty());
}

@Test
void nonAirMutationExplicitlyCreatesDirtyEntry() {
    ChunkRepository repository = new ChunkRepository();
    assertTrue(repository.setBlock(20, 5, -2, (byte) 3));
    ChunkKey key = ChunkKey.fromWorld(20, -2);
    assertEquals(ChunkState.DIRTY, repository.state(key));
    assertEquals(1L, repository.revision(key));
}
```

Also test defensive snapshot bytes, out-of-range Y, duplicate generation,
generator failure rollback/removal, and immutable `keys()`.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkSnapshotTest" --tests "com.overlord.voxel.ChunkRepositoryTest"
```

Expected: `compileTestJava` fails because snapshot and repository are absent.

- [ ] **Step 3: Add owned block copying to Chunk and SubChunk**

Add package-private copy methods:

```java
void copyBlocksTo(byte[] target) {
    for (Map.Entry<Integer, SubChunk> entry : subChunks.entrySet()) {
        entry.getValue().copyBlocksTo(
                target,
                entry.getKey()
                        * GameConfig.Chunk.SUBCHUNK_HEIGHT,
                worldHeight);
    }
}
```

`SubChunk.copyBlocksTo(byte[] target, int baseY, int worldHeight)` copies each
block into the full-chunk layout:

```java
int targetIndex =
        x + yWorld * GameConfig.Chunk.SIZE
                + z * GameConfig.Chunk.SIZE * worldHeight;
```

Use one documented layout consistently in snapshot `getBlock` and copying.

- [ ] **Step 4: Implement snapshot defensive ownership**

The constructor copies its input and access validates coordinates:

```java
private ChunkSnapshot(
        ChunkKey key,
        long revision,
        int worldHeight,
        byte[] blocks) {
    this.key = Objects.requireNonNull(key, "key");
    this.revision = revision;
    this.worldHeight = worldHeight;
    this.blocks = Arrays.copyOf(blocks, blocks.length);
}
```

Only `ChunkRepository` captures a mutable `Chunk`.

- [ ] **Step 5: Implement repository entries and legal transitions**

Use a `ConcurrentHashMap<ChunkKey, Entry>`, but synchronize on each private
entry while reading or mutating its `Chunk`, state, revision, and failure.

```java
private static final class Entry {
    private final Chunk chunk;
    private ChunkState state = ChunkState.EMPTY;
    private long revision;
    private Throwable failure;

    private Entry(int worldHeight) {
        chunk = new Chunk(worldHeight);
    }
}
```

Generation:

```java
synchronized (entry) {
    transition(entry, key, ChunkState.EMPTY, ChunkState.GENERATING);
    try {
        generator.accept(entry.chunk);
        entry.revision++;
        entry.state = ChunkState.GENERATED;
    } catch (RuntimeException | Error failure) {
        entries.remove(key, entry);
        throw failure;
    }
}
```

Missing `state` returns `EMPTY`; missing `revision` returns `0`.

- [ ] **Step 6: Run focused and full engine tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkSnapshotTest" --tests "com.overlord.voxel.ChunkRepositoryTest"
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```powershell
git add engine/src/main/java/com/overlord/voxel/ChunkSnapshot.java engine/src/main/java/com/overlord/voxel/ChunkRepository.java engine/src/main/java/com/overlord/voxel/Chunk.java engine/src/main/java/com/overlord/voxel/SubChunk.java engine/src/test/java/com/overlord/voxel/ChunkSnapshotTest.java engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java
git commit -m "feat(voxel): add revisioned chunk repository"
```

---

### Task 3: World Delegation and Boundary Dirty Propagation

**Files:**
- Modify: `engine/src/main/java/com/overlord/voxel/World.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/WorldTest.java`

**Interfaces:**
- Consumes: Task 2 repository.
- Produces:

```java
public final class World {
    public World();
    public World(ChunkRepository chunks);
    public ChunkRepository chunks();
    public byte getBlock(int x, int y, int z);
    public boolean setBlock(int x, int y, int z, byte blockId);
    public void generate(
            ChunkKey key, Consumer<Chunk> generator);
}
```

The existing `getChunk(int, int)` remains temporarily deprecated so current
game code compiles. Task 10 removes it after all callers migrate.

- [ ] **Step 1: Write real dirty-propagation tests**

```java
@Test
void eastEdgeChangeDirtiesOnlyTargetAndEastNeighbor() {
    ChunkRepository repository = generatedPairEastWest();
    ChunkKey center = new ChunkKey(0, 0);
    ChunkKey east = center.east();

    repository.setBlock(
            GameConfig.Chunk.SIZE - 1, 4, 2, (byte) 1);

    assertEquals(ChunkState.DIRTY, repository.state(center));
    assertEquals(ChunkState.DIRTY, repository.state(east));
}

@Test
void interiorChangeDoesNotDirtyNeighbor() {
    ChunkRepository repository = generatedPairEastWest();
    long eastRevision = repository.revision(new ChunkKey(1, 0));
    repository.setBlock(2, 4, 2, (byte) 1);
    assertEquals(eastRevision, repository.revision(new ChunkKey(1, 0)));
}
```

Add tests for all edges, corners, generation of an adjacent chunk, missing
neighbors, unchanged block writes, and negative world coordinates.

Build the pair without test-only production hooks:

```java
private static ChunkRepository generatedPairEastWest() {
    ChunkRepository repository = new ChunkRepository();
    repository.generate(
            new ChunkKey(0, 0),
            chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
    repository.generate(
            new ChunkKey(1, 0),
            chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
    return repository;
}
```

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkRepositoryTest" --tests "com.overlord.voxel.WorldTest"
```

Expected: assertions fail because repository mutations do not yet dirty
neighbors and `World` still owns a string-keyed map.

- [ ] **Step 3: Centralize dirty state updates**

Add a private repository method:

```java
private void dirtyIfPresent(ChunkKey key) {
    Entry entry = entries.get(key);
    if (entry == null) {
        return;
    }
    synchronized (entry) {
        if (entry.state == ChunkState.UNLOADING
                || entry.state == ChunkState.EMPTY) {
            return;
        }
        entry.revision++;
        entry.failure = null;
        if (entry.state != ChunkState.GENERATING) {
            entry.state = ChunkState.DIRTY;
        }
    }
}
```

For a changed block, dirty the target and each present key returned by
`affectedByBlock`. Do not increment the target twice.

After successful generation, dirty existing cardinal neighbors once.

- [ ] **Step 4: Refactor World to delegate to ChunkRepository**

```java
public World(ChunkRepository chunks) {
    this.chunks = Objects.requireNonNull(chunks, "chunks");
}

public byte getBlock(int x, int y, int z) {
    return chunks.getBlock(x, y, z);
}

public boolean setBlock(int x, int y, int z, byte blockId) {
    return chunks.setBlock(x, y, z, blockId);
}
```

The deprecated `getChunk` must use repository-owned storage and must not create
an independent map.

Add one package-private bridge used only by deprecated `World.getChunk`:

```java
Chunk mutableChunkForCompatibility(ChunkKey key) {
    return entries.computeIfAbsent(
                    key, ignored -> new Entry(worldHeight))
            .chunk;
}
```

It intentionally leaves the entry `EMPTY`; Phase 3 lifecycle code must never
call it. Task 9 migrates the Gaia generator and Task 10 deletes this bridge.

- [ ] **Step 5: Run focused and complete tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkRepositoryTest" --tests "com.overlord.voxel.WorldTest"
.\gradlew.bat :engine:test
.\gradlew.bat :game:test
```

Expected: every command reports `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add engine/src/main/java/com/overlord/voxel/World.java engine/src/main/java/com/overlord/voxel/ChunkRepository.java engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java engine/src/test/java/com/overlord/voxel/WorldTest.java
git commit -m "refactor(voxel): route world access through chunk repository"
```

---

### Task 4: Snapshot-Only Chunk Meshing and CPU Mesh Data

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/ChunkMeshInput.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkMeshData.java`
- Create: `engine/src/main/java/com/overlord/voxel/ChunkMesher.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkMeshDataTest.java`

**Interfaces:**
- Consumes: `ChunkSnapshot`, `AxisAlignedBounds`, Phase 2
  `BlockRenderResolver`.
- Produces:

```java
@FunctionalInterface
public interface ChunkMesher {
    ChunkMeshData build(ChunkMeshInput input);
}

public record ChunkMeshInput(
        ChunkSnapshot center,
        ChunkSnapshot north,
        ChunkSnapshot south,
        ChunkSnapshot west,
        ChunkSnapshot east) {
    public byte getBlock(int localX, int y, int localZ);
}

public final class ChunkMeshData {
    public ChunkMeshData(
            ChunkKey key, long revision, float[] vertices);
    public ChunkKey key();
    public long revision();
    public float[] vertices();
    public int vertexCount();
    public Optional<AxisAlignedBounds> localBounds();
    public boolean isEmpty();
}

public final class ChunkMeshBuilder implements ChunkMesher {
    @Override
    public ChunkMeshData build(ChunkMeshInput input);
}
```

The old mutable-world builder overload remains deprecated until Task 10.

- [ ] **Step 1: Rewrite meshing tests around immutable input**

```java
@Test
void usesNeighborSnapshotToHideEastBoundaryFace() {
    ChunkSnapshot center =
            snapshotWithBlock(new ChunkKey(0, 0), 1, 15, 1, 2, (byte) 1);
    ChunkSnapshot east =
            snapshotWithBlock(new ChunkKey(1, 0), 1, 0, 1, 2, (byte) 1);

    ChunkMeshData data =
            builder().build(
                    input(center, emptyNorth(), emptySouth(), emptyWest(), east));

    assertEquals(150, data.vertices().length);
}

@Test
void emittedVerticesAreChunkLocalAndBoundsAreLocal() {
    ChunkMeshData data = builder().build(singleBlockInput(15, 4, 3));
    assertEquals(
            new AxisAlignedBounds(15, 4, 3, 16, 5, 4),
            data.localBounds().orElseThrow());
    assertTrue(maxPositionX(data.vertices()) <= 16.0f);
}
```

Retain six-face UV, non-renderable block, and non-renderable-neighbor tests.
Add missing-neighbor-as-air, empty snapshot, defensive vertex copying, revision
propagation, and all four horizontal boundary tests.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkMeshBuilderTest" --tests "com.overlord.voxel.ChunkMeshDataTest"
```

Expected: `compileTestJava` fails because the snapshot meshing types and
`build(ChunkMeshInput)` do not exist.

- [ ] **Step 3: Implement neighborhood reads**

```java
public byte getBlock(int x, int y, int z) {
    if (y < 0 || y >= center.worldHeight()) {
        return 0;
    }
    if (x < 0) return west.getBlock(GameConfig.Chunk.SIZE - 1, y, z);
    if (x >= GameConfig.Chunk.SIZE) return east.getBlock(0, y, z);
    if (z < 0) return north.getBlock(x, y, GameConfig.Chunk.SIZE - 1);
    if (z >= GameConfig.Chunk.SIZE) return south.getBlock(x, y, 0);
    return center.getBlock(x, y, z);
}
```

Validate center and neighbor keys are cardinally adjacent and all snapshots
share world height.

- [ ] **Step 4: Implement owned mesh data and bounds calculation**

The constructor copies vertices. For non-empty data, scan every five-float
vertex and compute min/max XYZ. Empty data uses `Optional.empty()`.

```java
public float[] vertices() {
    return Arrays.copyOf(vertices, vertices.length);
}
```

Reject vertex arrays whose length is not divisible by five.

- [ ] **Step 5: Refactor ChunkMeshBuilder to local coordinates**

Iterate the center snapshot rather than subchunk maps:

```java
for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
    for (int y = 0; y < input.center().worldHeight(); y++) {
        for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
            byte block = input.center().getBlock(x, y, z);
            // resolve render info and emit existing face winding/UVs
        }
    }
}
```

Pass local `x/y/z` to existing face construction. Determine neighbor solidity
only through `input.getBlock`.

- [ ] **Step 6: Run focused and complete tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkMeshBuilderTest" --tests "com.overlord.voxel.ChunkMeshDataTest"
.\gradlew.bat :engine:test
.\gradlew.bat :game:test
```

Expected: every command reports `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```powershell
git add engine/src/main/java/com/overlord/voxel/ChunkMeshInput.java engine/src/main/java/com/overlord/voxel/ChunkMeshData.java engine/src/main/java/com/overlord/voxel/ChunkMesher.java engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java engine/src/test/java/com/overlord/voxel/ChunkMeshDataTest.java
git commit -m "refactor(voxel): mesh immutable chunk snapshots"
```

---

### Task 5: Chunk Render Resource Abstractions

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/ChunkGpuMesh.java`
- Create: `engine/src/main/java/com/overlord/renderer/ChunkRenderObject.java`
- Create: `engine/src/main/java/com/overlord/renderer/ChunkRenderBackend.java`
- Create: `engine/src/test/java/com/overlord/renderer/ChunkRenderObjectTest.java`

**Interfaces:**
- Consumes: `ChunkKey`, `ChunkMeshData`, `AxisAlignedBounds`.
- Produces:

```java
public interface ChunkGpuMesh {
    int vertexCount();
    void draw();
    void cleanup();
}

public interface ChunkRenderBackend {
    ChunkRenderObject upload(ChunkMeshData data);
    void release(ChunkRenderObject object);
}

public final class ChunkRenderObject {
    public ChunkRenderObject(
            ChunkKey key,
            long revision,
            ChunkGpuMesh mesh,
            AxisAlignedBounds localBounds);
    public ChunkKey key();
    public long revision();
    public ChunkGpuMesh mesh();
    public Matrix4f modelMatrix();
    public AxisAlignedBounds worldBounds();
}
```

- [ ] **Step 1: Write render-object ownership tests**

```java
@Test
void computesDefensiveWorldTransformAndBounds() {
    FakeGpuMesh mesh = new FakeGpuMesh(36);
    ChunkRenderObject object =
            new ChunkRenderObject(
                    new ChunkKey(2, -1),
                    8,
                    mesh,
                    new AxisAlignedBounds(0, 1, 0, 16, 5, 16));

    assertEquals(
            new AxisAlignedBounds(32, 1, -16, 48, 5, 0),
            object.worldBounds());
    assertEquals(32.0f, object.modelMatrix().m30());
    Matrix4f changed = object.modelMatrix().translate(100, 0, 0);
    assertEquals(32.0f, object.modelMatrix().m30());
}
```

Also reject an empty GPU mesh, null values, negative revisions, and mismatched
zero vertex counts.

- [ ] **Step 2: Run the test and confirm RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.ChunkRenderObjectTest"
```

Expected: `compileTestJava` fails because the render types do not exist.

- [ ] **Step 3: Implement the interfaces and immutable render object**

Compute translation from `ChunkKey.worldOriginX/Z` and return a new
`Matrix4f` on every accessor:

```java
public Matrix4f modelMatrix() {
    return new Matrix4f(modelMatrix);
}
```

The render object does not create, draw, or release GPU state in its
constructor.

- [ ] **Step 4: Run focused and full engine tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.ChunkRenderObjectTest"
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/renderer/ChunkGpuMesh.java engine/src/main/java/com/overlord/renderer/ChunkRenderObject.java engine/src/main/java/com/overlord/renderer/ChunkRenderBackend.java engine/src/test/java/com/overlord/renderer/ChunkRenderObjectTest.java
git commit -m "feat(rendering): define independent chunk render objects"
```

---

### Task 6: Revision-Aware CPU Mesh Scheduling

**Files:**
- Create: `engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`

**Interfaces:**
- Consumes: `ChunkMesher`, `ChunkMeshInput`, `ChunkMeshData`,
  `ChunkRenderBackend`, `MainThreadGuard`, `Executor`.
- Produces:

```java
public final class ChunkMeshManager implements AutoCloseable {
    public ChunkMeshManager(
            ChunkRepository repository,
            ChunkMesher mesher,
            Executor meshExecutor,
            ChunkRenderBackend renderBackend,
            MainThreadGuard mainThreadGuard,
            int maxUploadsPerFrame);
    public int scheduleEligible();
    public int drainCompletedCpuWork();
    public Optional<Throwable> pollFailure();
    public boolean allRenderable(Set<ChunkKey> keys);
}
```

Repository lifecycle methods:

```java
public Set<ChunkKey> meshingCandidates();
public Optional<ChunkMeshInput> claimMeshing(ChunkKey key);
public boolean markReadyForUpload(ChunkKey key, long revision);
public void markMeshingFailure(
        ChunkKey key, long revision, Throwable failure);
public void retry(ChunkKey key);
```

- [ ] **Step 1: Write manual-executor scheduling tests**

Use a nested deterministic executor:

```java
final class ManualExecutor implements Executor {
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    public void execute(Runnable command) { tasks.add(command); }
    void runNext() { tasks.remove().run(); }
    void runAll() {
        while (!tasks.isEmpty()) {
            runNext();
        }
    }
    int size() { return tasks.size(); }
}
```

Use an explicit fixture whose backend fails the test if worker code crosses
the GPU boundary:

```java
private record Fixture(
        ChunkRepository repository,
        ManualExecutor executor,
        AtomicInteger backendCalls,
        ChunkMeshManager manager) {}

private static Fixture generatedFixture() {
    ChunkRepository repository = new ChunkRepository();
    repository.generate(
            KEY,
            chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
    ManualExecutor executor = new ManualExecutor();
    AtomicInteger backendCalls = new AtomicInteger();
    ChunkRenderBackend backend =
            new ChunkRenderBackend() {
                public ChunkRenderObject upload(ChunkMeshData data) {
                    backendCalls.incrementAndGet();
                    throw new AssertionError("CPU scheduling called upload");
                }
                public void release(ChunkRenderObject object) {
                    backendCalls.incrementAndGet();
                    throw new AssertionError("CPU scheduling called release");
                }
            };
    ChunkMeshManager manager =
            new ChunkMeshManager(
                    repository,
                    input ->
                            new ChunkMeshData(
                                    input.center().key(),
                                    input.center().revision(),
                                    oneBlockVertices()),
                    executor,
                    backend,
                    MainThreadGuard.captureCurrentThread(),
                    2);
    return new Fixture(repository, executor, backendCalls, manager);
}

private static float[] oneBlockVertices() {
    return new float[] {
        0, 0, 0, 0, 0,
        1, 0, 0, 1, 0,
        0, 1, 0, 0, 1
    };
}
```

Tests:

```java
@Test
void coalescesRepeatedDirtyStateIntoOneClaimedTask() {
    Fixture fixture = generatedFixture();
    assertEquals(1, fixture.manager.scheduleEligible());
    assertEquals(0, fixture.manager.scheduleEligible());
    assertEquals(1, fixture.executor.size());
    assertEquals(ChunkState.MESHING, fixture.repository.state(KEY));
}

@Test
void discardsResultWhenRevisionChangesDuringMeshing() {
    Fixture fixture = generatedFixture();
    fixture.manager.scheduleEligible();
    fixture.repository.setBlock(1, 1, 1, (byte) 2);
    fixture.executor.runNext();
    fixture.manager.drainCompletedCpuWork();
    assertEquals(ChunkState.DIRTY, fixture.repository.state(KEY));
}
```

Also test snapshot capture after claim, neighbor-edge mutation invalidation,
worker failure transfer, explicit retry, and that the worker never calls fake
backend methods.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkMeshManagerTest" --tests "com.overlord.voxel.ChunkRepositoryTest"
```

Expected: `compileTestJava` fails because manager and repository claim methods
do not exist.

- [ ] **Step 3: Implement atomic meshing claims**

Claim only `GENERATED` or failure-free `DIRTY` entries:

```java
synchronized (entry) {
    if (!isMeshingCandidate(entry)) {
        return Optional.empty();
    }
    long claimedRevision = entry.revision;
    entry.state = ChunkState.MESHING;
}
```

Capture center and cardinal snapshots without retaining locks, then recheck:

```java
synchronized (entry) {
    if (entry.state != ChunkState.MESHING
            || entry.revision != claimedRevision) {
        if (entry.state != ChunkState.UNLOADING) {
            entry.state = ChunkState.DIRTY;
        }
        return Optional.empty();
    }
}
```

Missing neighbors become `ChunkSnapshot.empty(...)`.

- [ ] **Step 4: Implement CPU scheduling and completion queues**

```java
meshExecutor.execute(
        () -> {
            try {
                completed.add(mesher.build(input));
            } catch (RuntimeException | Error failure) {
                failed.add(
                        new MeshingFailure(
                                input.center().key(),
                                input.center().revision(),
                                failure));
            }
        });
```

`drainCompletedCpuWork` performs no GPU work. It moves current results to an
internal upload queue after `markReadyForUpload`, discards stale results, and
records failures.

- [ ] **Step 5: Run focused and full engine tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkMeshManagerTest" --tests "com.overlord.voxel.ChunkRepositoryTest"
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java engine/src/main/java/com/overlord/voxel/ChunkRepository.java engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java
git commit -m "feat(voxel): schedule revision-safe chunk meshing"
```

---

### Task 7: Main-Thread Upload Budget, Replacement, and Unload

**Files:**
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkRepository.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java`

**Interfaces:**
- Extends manager:

```java
public int processMainThreadWork();
public Collection<ChunkRenderObject> renderObjects();
public void retry(ChunkKey key);
public void unload(ChunkKey key);
@Override public void close();
```

- Extends repository:

```java
public boolean markRenderable(ChunkKey key, long revision);
public boolean beginUnload(ChunkKey key);
public boolean completeUnload(ChunkKey key);
```

- [ ] **Step 1: Add fake-backend upload and unload tests**

```java
@Test
void uploadsAtMostConfiguredBudgetPerFrame() {
    Fixture fixture = readyFixture(3, 2);
    assertEquals(2, fixture.manager.processMainThreadWork());
    assertEquals(2, fixture.backend.uploaded.size());
    assertEquals(1, fixture.manager.processMainThreadWork());
    assertEquals(3, fixture.backend.uploaded.size());
}

@Test
void emptyMeshBecomesRenderableWithoutGpuAllocation() {
    Fixture fixture = readyEmptyFixture();
    fixture.manager.processMainThreadWork();
    assertEquals(0, fixture.backend.uploadCalls);
    assertEquals(ChunkState.RENDERABLE, fixture.repository.state(KEY));
    assertTrue(fixture.manager.renderObjects().isEmpty());
}

@Test
void rebuildReleasesExactlyOnePreviousObject() {
    Fixture fixture = uploadedFixture();
    ChunkRenderObject previous = fixture.manager.renderObjects().iterator().next();
    dirtyBuildAndUpload(fixture);
    assertEquals(List.of(previous), fixture.backend.released);
}

@Test
void unloadInvalidatesLateResultAndReleasesOnce() {
    Fixture fixture = uploadedFixture();
    fixture.manager.unload(KEY);
    fixture.manager.processMainThreadWork();
    fixture.executor.runAll();
    fixture.manager.drainCompletedCpuWork();
    assertFalse(fixture.repository.contains(KEY));
    assertEquals(1, fixture.backend.released.size());
}
```

Also test upload failure preserving the previous object, explicit retry, stale
upload, idempotent unload, neighbor dirtying on unload, close releasing every
object, close idempotence, and worker-thread rejection by
`processMainThreadWork`.

`readyFixture(count, budget)` must create `count` distinct generated entries,
call `scheduleEligible`, execute all manual worker tasks, and call
`drainCompletedCpuWork`; it must not directly change repository state.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkMeshManagerTest" --tests "com.overlord.voxel.ChunkRepositoryTest"
```

Expected: compile or assertion failures because upload, render-object, unload,
and close behavior are not implemented.

- [ ] **Step 3: Implement main-thread work and replacement**

Start with:

```java
mainThreadGuard.assertMainThread("chunk mesh upload");
drainUnloads();
drainCompletedCpuWork();
```

Process at most `maxUploadsPerFrame`. For non-empty data:

```java
ChunkRenderObject replacement = renderBackend.upload(data);
if (!repository.markRenderable(data.key(), data.revision())) {
    renderBackend.release(replacement);
    continue;
}
ChunkRenderObject previous =
        renderObjects.put(data.key(), replacement);
if (previous != null) {
    renderBackend.release(previous);
}
```

Create the replacement before changing the map. If the revision becomes stale
during upload, release the uninstalled replacement and retain the old object.
On upload failure, retain the old object, remove the item from automatic
retry, store the failed data, and surface the failure.

- [ ] **Step 4: Implement empty, unload, retry, and close paths**

Empty data removes/releases the old object and marks the revision renderable.

`unload`:

```java
if (repository.beginUnload(key)) {
    pendingUnloads.add(key);
}
```

Main-thread unload removes and releases the render object before
`repository.completeUnload(key)`.

`close` asserts the main thread, releases every installed object exactly once,
clears queues, completes pending unloads, and is idempotent.

- [ ] **Step 5: Run focused and full tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkMeshManagerTest" --tests "com.overlord.voxel.ChunkRepositoryTest"
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add engine/src/main/java/com/overlord/voxel/ChunkMeshManager.java engine/src/main/java/com/overlord/voxel/ChunkRepository.java engine/src/test/java/com/overlord/voxel/ChunkMeshManagerTest.java engine/src/test/java/com/overlord/voxel/ChunkRepositoryTest.java
git commit -m "feat(voxel): upload and unload independent chunk meshes"
```

---

### Task 8: Renderer as the Main-Thread Chunk Backend

**Files:**
- Modify: `engine/src/main/java/com/overlord/renderer/Mesh.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`
- Create: `engine/src/test/java/com/overlord/renderer/ChunkRenderBackendTest.java`

**Interfaces:**
- `Mesh implements ChunkGpuMesh`.
- `Renderer implements ChunkRenderBackend`.
- Adds:

```java
@Override
public ChunkRenderObject upload(ChunkMeshData data);
@Override
public void release(ChunkRenderObject object);
public void renderChunks(Collection<ChunkRenderObject> chunks);
```

The old `replaceMesh(float[])` and zero-argument terrain `render()` remain
temporarily until Task 10 so game compilation stays green.

- [ ] **Step 1: Write constructor/reflection and worker rejection tests**

```java
@Test
void rendererImplementsChunkRenderBackend() {
    assertTrue(
            ChunkRenderBackend.class.isAssignableFrom(Renderer.class));
}

@Test
void uploadRejectsWorkerBeforeOpenGl() throws Exception {
    Renderer renderer =
            new Renderer(
                    MainThreadGuard.captureCurrentThread(),
                    RenderAssets.missing());
    ChunkMeshData data = nonEmptyData();
    Future<?> call =
            Executors.newSingleThreadExecutor()
                    .submit(() -> renderer.upload(data));
    assertInstanceOf(
            IllegalStateException.class,
            assertThrows(ExecutionException.class, call::get).getCause());
}
```

Add equivalent worker rejection for release and `renderChunks`. Reflection
must verify `Mesh` implements `ChunkGpuMesh`.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.ChunkRenderBackendTest" --tests "com.overlord.core.thread.MainThreadGuardTest"
```

Expected: assertions or compilation fail because Renderer is not the backend.

- [ ] **Step 3: Implement Mesh interface and Renderer upload/release**

```java
public ChunkRenderObject upload(ChunkMeshData data) {
    mainThreadGuard.assertMainThread("chunk mesh GPU upload");
    if (data.isEmpty()) {
        throw new IllegalArgumentException(
                "Empty chunk data does not allocate a GPU mesh");
    }
    Mesh gpuMesh = new Mesh(mainThreadGuard, data.vertices());
    return new ChunkRenderObject(
            data.key(),
            data.revision(),
            gpuMesh,
            data.localBounds().orElseThrow());
}
```

`release` asserts the main thread and calls `object.mesh().cleanup()`.

- [ ] **Step 4: Add collection rendering**

Bind shader/atlas and projection/view once, then:

```java
for (ChunkRenderObject chunk : chunks) {
    shader.setUniformMat4f("model", chunk.modelMatrix());
    chunk.mesh().draw();
}
```

Do not add culling. Preserve GLSL 410 and current texture/material behavior.

- [ ] **Step 5: Run focused, engine, and game tests**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.ChunkRenderBackendTest" --tests "com.overlord.core.thread.MainThreadGuardTest"
.\gradlew.bat :engine:test
.\gradlew.bat :game:test
```

Expected: all commands report `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add engine/src/main/java/com/overlord/renderer/Mesh.java engine/src/main/java/com/overlord/renderer/Renderer.java engine/src/test/java/com/overlord/renderer/ChunkRenderBackendTest.java engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java
git commit -m "refactor(rendering): render independent chunk objects"
```

---

### Task 9: Initial World Load and Game Lifecycle Integration

**Files:**
- Modify: `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`
- Modify: `game/src/main/java/com/gaia/world/WorldLoader.java`
- Modify: `game/src/main/java/com/gaia/world/WorldLoadResult.java`
- Modify: `game/src/main/java/com/gaia/GameContext.java`
- Modify: `game/src/main/java/com/gaia/GameLoop.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/test/java/com/gaia/world/GaiaWorldGeneratorTest.java`
- Modify: `game/src/test/java/com/gaia/world/WorldLoaderTest.java`
- Modify: `game/src/test/java/com/gaia/GameBootstrapTest.java`
- Modify: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- Create: `game/src/test/java/com/gaia/GameLoopStructureTest.java`

**Interfaces:**
- `WorldLoadResult` becomes:

```java
public record WorldLoadResult(
        Set<ChunkKey> initialChunks,
        Vector3f spawnPosition)
```

- `WorldLoader` constructor becomes:

```java
public WorldLoader(
        GaiaWorldGenerator worldGenerator,
        byte fallbackGroundId)
```

- `GameContext` adds:

```java
ChunkMeshManager chunkMeshes
```

- [ ] **Step 1: Write generation-only loader tests**

```java
@Test
void generatesIndependentChunksWithoutCpuMeshCombination()
        throws Exception {
    World world = new World();
    WorldLoadResult result = workerLoad(world);

    assertEquals(16, result.initialChunks().size());
    assertTrue(
            result.initialChunks().stream()
                    .allMatch(world.chunks()::contains));
    assertEquals(
            Set.class,
            WorldLoadResult.class.getRecordComponents()[0].getType());
}
```

Assert each key state is `GENERATED` or `DIRTY`, terrain layers remain IDs
1/2/3, cancellation still propagates, no worker references renderer types, and
the record defensively copies the key set and spawn vector.

- [ ] **Step 2: Write Bootstrap and GameLoop structure tests**

Require source to contain:

```text
new ChunkMeshManager(
chunkMeshes.scheduleEligible()
chunkMeshes.processMainThreadWork()
renderChunks(chunkMeshes.renderObjects())
initialChunks()
```

Require source not to contain:

```text
result.meshData()
combineMeshData
new Mesh(
replaceMesh(
```

Also assert Bootstrap creates a named mesh executor, registers manager cleanup
after Engine creation, and registers executor shutdown after manager
registration so reverse cleanup stops workers before GPU release.

- [ ] **Step 3: Run game tests and confirm RED**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.world.*" --tests "com.gaia.GameBootstrap*" --tests "com.gaia.GameLoopStructureTest"
```

Expected: compile/assertion failures because load results and game composition
still use one `float[]`.

- [ ] **Step 4: Convert Gaia generation to repository batching**

```java
public void generateChunk(
        World world, ChunkKey key) {
    world.generate(
            key,
            chunk -> fillChunk(chunk, key.x(), key.z()));
}
```

Move only the existing loops into `fillChunk`. Keep seed, octaves, persistence,
scale, base height, variation, loop bounds, and grass/dirt/stone selection
unchanged.

- [ ] **Step 5: Remove CPU meshing from WorldLoader**

Generate and collect the same `-2..1` X/Z key range. Preserve cancellation
checks, spawn scan, and fallback column.

```java
Set<ChunkKey> generated = new LinkedHashSet<>();
for (...) {
    ChunkKey key = new ChunkKey(chunkX, chunkZ);
    worldGenerator.generateChunk(world, key);
    generated.add(key);
}
return new WorldLoadResult(generated, spawnPosition);
```

- [ ] **Step 6: Compose manager and executors in Bootstrap**

Use two named mesh workers:

```java
ExecutorService meshExecutor =
        Executors.newFixedThreadPool(
                2,
                namedThreadFactory("Gaia-Chunk-Mesher"));
ChunkMeshManager chunkMeshes =
        new ChunkMeshManager(
                engine.getWorld().chunks(),
                new ChunkMeshBuilder(blocks),
                meshExecutor,
                engine.getRenderer(),
                mainThreadGuard,
                2);
```

Register cleanup in this construction order:

```text
engine
chunkMeshes
meshExecutor
worldExecutor
worldLoad
```

Reverse shutdown is therefore world load, world executor, mesh executor,
manager GPU cleanup, then Engine.

- [ ] **Step 7: Pump independent lifecycle from GameLoop**

Store the completed load result once. Every frame after generation:

```java
context.chunkMeshes().scheduleEligible();
context.chunkMeshes().processMainThreadWork();
Throwable meshFailure =
        context.chunkMeshes().pollFailure().orElse(null);
if (meshFailure != null) {
    rethrowMeshFailure(meshFailure);
}
```

Use an explicit helper so errors are not wrapped or swallowed:

```java
private static void rethrowMeshFailure(Throwable failure) {
    if (failure instanceof RuntimeException runtimeException) {
        throw runtimeException;
    }
    throw (Error) failure;
}
```

Transition to `RUNNING` only when:

```java
context.chunkMeshes()
        .allRenderable(loadResult.initialChunks())
```

Render only in `RUNNING`:

```java
context.engine()
        .getRenderer()
        .renderChunks(context.chunkMeshes().renderObjects());
```

Preserve mouse, input, fixed-step, physics, event, resize, and clear/swap
ordering.

- [ ] **Step 8: Run focused and complete tests**

```powershell
.\gradlew.bat :game:test --tests "com.gaia.world.*" --tests "com.gaia.GameBootstrap*" --tests "com.gaia.GameLoopStructureTest"
.\gradlew.bat :engine:test
.\gradlew.bat :game:test
```

Expected: all commands report `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```powershell
git add game/src/main/java/com/gaia game/src/test/java/com/gaia
git commit -m "refactor(game): compose independent chunk mesh loading"
```

---

### Task 10: Remove Combined-Mesh Compatibility Paths

**Files:**
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java`
- Modify: `engine/src/main/java/com/overlord/voxel/Chunk.java`
- Modify: `engine/src/main/java/com/overlord/voxel/World.java`
- Modify: `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`
- Create: `engine/src/test/java/com/overlord/voxel/ChunkMeshLifecycleStructureTest.java`
- Modify: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`

**Interfaces:**
- Final Renderer terrain API:

```java
public ChunkRenderObject upload(ChunkMeshData data);
public void release(ChunkRenderObject object);
public void renderChunks(Collection<ChunkRenderObject> chunks);
```

- Final meshing API:

```java
public ChunkMeshData build(ChunkMeshInput input);
```

- [ ] **Step 1: Write final structure tests**

Read production sources and assert:

```java
assertFalse(renderer.contains("replaceMesh("));
assertFalse(renderer.contains("private Mesh mesh"));
assertFalse(renderer.contains("fallbackMesh"));
assertFalse(builder.contains("World world"));
assertFalse(builder.contains("Chunk chunk"));
assertFalse(world.contains("Map<String, Chunk>"));
assertFalse(world.contains("computeIfAbsent"));
assertFalse(worldLoader.contains("combineMeshData"));
assertFalse(worldLoadResult.contains("float[]"));
```

Also use reflection to assert the old public methods are absent.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.voxel.ChunkMeshLifecycleStructureTest"
.\gradlew.bat :game:test --tests "com.gaia.GameBootstrapStructureTest"
```

Expected: assertions fail because temporary compatibility paths remain.

- [ ] **Step 3: Delete old single-mesh ownership**

Remove from Renderer:

- global terrain `mesh`;
- `fallbackMesh`;
- fallback cube construction;
- `replaceMesh(float[])`;
- zero-argument terrain `render()`;
- their cleanup branches.

Keep shader, atlas, camera, projection, clear, resize, chunk backend, and
`renderChunks`.

- [ ] **Step 4: Delete mutable-world meshing access**

Remove:

- deprecated `ChunkMeshBuilder.buildChunkMeshData(Chunk, int, int, World)`;
- any `ChunkMeshBuilder` access to `World`;
- deprecated `World.getChunk`;
- public mutable `Chunk.getSubChunks`.

Retain only repository-controlled package-private mutation/copy access.

- [ ] **Step 5: Run all tests and architecture scans**

```powershell
.\gradlew.bat clean test build
rg -n "replaceMesh|combineMeshData|meshData\\(\\)|buildChunkMeshData|Map<String, Chunk>|getChunk\\(" engine/src/main game/src/main
rg -n "new Mesh|glGen|glBind|glBuffer|glDelete|glDraw" game/src/main engine/src/main/java/com/overlord/voxel
rg -n "#version 4(2|3|4|5|6)|glDispatchCompute" engine/src game/src
git diff --check
```

Expected:

- Gradle reports `BUILD SUCCESSFUL`;
- old combined-mesh scan has no matches;
- game and voxel worker packages contain no GPU calls;
- no GLSL above 410 or compute dispatch;
- `git diff --check` is silent.

- [ ] **Step 6: Commit**

```powershell
git add engine/src/main/java/com/overlord/renderer/Renderer.java engine/src/main/java/com/overlord/voxel engine/src/test/java/com/overlord/voxel game/src/test/java/com/gaia/GameBootstrapStructureTest.java
git commit -m "refactor(voxel): remove combined terrain mesh path"
```

---

### Task 11: Phase Handoff and Final Verification

**Files:**
- Create: `docs/agent-handoffs/phase-03-handoff.md`
- Modify only if required by actual final state:
  `docs/architecture/current-baseline.md`

**Interfaces:**
- Consumes: final branch implementation and verification evidence.
- Produces: Phase 3 handoff and final report.

- [ ] **Step 1: Run final Windows automated verification**

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
```

Record actionable task counts, test suite/test counts from XML, failures,
errors, skips, selected natives, and packaged-resource result.

- [ ] **Step 2: Run repository and architecture checks**

```powershell
git diff --check
git status --short --branch
git diff --stat origin/main..HEAD
git ls-files | Select-String -Pattern '(^|/)bin(/|$)|\\.class($|[^/]*$)|hs_err_pid|replay_pid'
Select-String -Path gradle.properties -Pattern 'org\\.gradle\\.java\\.home|/Library/Java|[A-Za-z]:\\\\'
rg -n "replaceMesh|combineMeshData|meshData\\(\\)|buildChunkMeshData|Map<String, Chunk>" engine/src/main game/src/main
rg -n "glGen|glBind|glBuffer|glDelete|glDraw|new Mesh" game/src/main engine/src/main/java/com/overlord/voxel
rg -n "#version 4(2|3|4|5|6)|glDispatchCompute" engine/src game/src
```

Expected: all policy scans are empty except valid GPU calls inside renderer
classes; branch and diff contain only Phase 3 work.

- [ ] **Step 3: Run or record interactive/platform verification**

Windows interactive:

```powershell
.\gradlew.bat :game
```

Verify unchanged terrain/materials, no permanent seams in the initial chunk
set, movement, cursor release/resize, and clean Escape shutdown.

If native macOS is unavailable, record these as not run rather than claiming
success:

```bash
./gradlew clean test build
./gradlew :game
```

- [ ] **Step 4: Write the handoff**

Use these exact top-level sections:

```markdown
## Completed work
## Incomplete work
## Core architecture decisions
## Modified files
## Test commands and results
## Known risks
## Interfaces the next phase must not break
## Final phase report
```

The architecture section must state:

- repository-owned state/revision;
- immutable snapshot meshing;
- target revision invalidation for edge changes;
- two uploads per frame;
- main-thread GPU backend;
- explicit unload with no automatic streaming;
- empty `RENDERABLE` chunks have no GPU object;
- renderer no longer owns one combined terrain mesh.

Include exact `git diff --stat`, suggested commit
`refactor(voxel): add independent chunk mesh lifecycle`, and suggested PR
title/description.

- [ ] **Step 5: Request two-perspective review**

Engine review checks:

- state transitions and synchronization;
- no mutable chunk reads during meshing;
- stale result rejection;
- GPU main-thread enforcement;
- replacement/unload cleanup and leak prevention.

Game review checks:

- unchanged generation and player behavior;
- loading transition and initial-key readiness;
- executor and shutdown ordering;
- no GPU use from worker paths;
- no unrelated gameplay changes.

Fix every Critical and Important finding, rerun affected tests, and request
re-review.

- [ ] **Step 6: Commit documentation**

```powershell
git add docs/agent-handoffs/phase-03-handoff.md docs/architecture/current-baseline.md
git commit -m "docs: record Phase 3 chunk mesh lifecycle handoff"
```

- [ ] **Step 7: Final branch review**

Review `origin/main..HEAD`, rerun full verification after any fix, and confirm:

- no Critical or Important findings;
- worktree clean;
- no push or merge occurred;
- remaining Windows interactive or macOS checks are reported accurately.

---

## Expected Commit Sequence

```text
docs: design independent chunk mesh lifecycle
docs: plan independent chunk mesh lifecycle
feat(voxel): add chunk lifecycle value types
feat(voxel): add revisioned chunk repository
refactor(voxel): route world access through chunk repository
refactor(voxel): mesh immutable chunk snapshots
feat(rendering): define independent chunk render objects
feat(voxel): schedule revision-safe chunk meshing
feat(voxel): upload and unload independent chunk meshes
refactor(rendering): render independent chunk objects
refactor(game): compose independent chunk mesh loading
refactor(voxel): remove combined terrain mesh path
docs: record Phase 3 chunk mesh lifecycle handoff
```

Fix/review commits may be added when a focused review finds a concrete defect.
