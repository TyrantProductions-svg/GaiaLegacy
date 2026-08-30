package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.CollisionWorld;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class DetailChunkMeshLifecycleTest {
    private static final ChunkKey KEY = new ChunkKey(2, -3);

    @Test
    void detailMutationMakesAlreadyCompletedOldClaimStaleBeforeGpuUpload() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        RecordingBackend backend = new RecordingBackend();
        ChunkMeshManager manager = manager(repository, executor, backend);
        long claimedRevision = repository.revision(KEY);

        assertEquals(1, manager.scheduleEligible());
        ChunkDetailMutationOutcome mutation = repository.mutateDetail(
                new ChunkDetailMutation.ConvertFullToDetail(
                        KEY.worldOriginX() + 1,
                        1,
                        KEY.worldOriginZ() + 1,
                        claimedRevision,
                        (byte) 1));
        executor.runAll();

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, mutation.status());
        assertTrue(mutation.resultingChunkRevision() > claimedRevision);
        assertEquals(1, manager.drainCompletedCpuWork());
        assertEquals(0, manager.processMainThreadWork());
        assertEquals(0, backend.uploads);
        assertEquals(ChunkState.DIRTY, repository.state(KEY));
        assertTrue(manager.renderObjects().isEmpty());
    }

    @Test
    void currentDetailClaimUsesTheOrdinaryChunkUploadLifecycle() {
        ChunkRepository repository = generatedRepository();
        long revision = repository.revision(KEY);
        ChunkDetailMutationOutcome mutation = repository.mutateDetail(
                new ChunkDetailMutation.ConvertFullToDetail(
                        KEY.worldOriginX() + 1,
                        1,
                        KEY.worldOriginZ() + 1,
                        revision,
                        (byte) 1));
        ManualExecutor executor = new ManualExecutor();
        RecordingBackend backend = new RecordingBackend();
        ChunkMeshManager manager = manager(repository, executor, backend);

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, mutation.status());
        assertEquals(1, manager.scheduleEligible());
        executor.runAll();
        assertEquals(1, manager.drainCompletedCpuWork());
        assertEquals(1, manager.processMainThreadWork());

        assertEquals(1, backend.uploads);
        assertEquals(1, manager.renderObjects().size());
        ChunkRenderObject installed = manager.renderObjects().iterator().next();
        assertEquals(mutation.resultingChunkRevision(), installed.revision());
        assertEquals(ChunkState.RENDERABLE, repository.state(KEY));
    }

    @Test
    void outputLimitFailureLatchesWithoutRetryingOrHidingCanonicalState() {
        ChunkRepository repository = fragmentedRepository(256);
        ChunkRepositorySnapshot canonicalBefore = repository.canonicalSnapshot();
        ManualExecutor executor = new ManualExecutor();
        RecordingBackend backend = new RecordingBackend();
        ChunkMeshManager manager = manager(repository, executor, backend);

        assertEquals(1, manager.scheduleEligible());
        executor.runAll();
        assertEquals(1, manager.drainCompletedCpuWork());

        assertTrue(manager.pollFailure().isEmpty(),
                "bounded complexity diagnostics are not fatal worker failures");
        assertTrue(manager.outputLimitDiagnostic(KEY).isPresent());
        assertEquals(ChunkMeshManager.MeshPhase.FAILED, manager.meshPhase(KEY));
        assertEquals(ChunkState.DIRTY, repository.state(KEY));
        assertEquals(
                ChunkAvailability.AVAILABLE,
                repository.observeCell(
                        KEY.worldOriginX(), 0, KEY.worldOriginZ()).status());
        assertEquals(canonicalBefore, repository.canonicalSnapshot());
        World world = new World(repository);
        assertTrue(new BlockRaycast(
                        world,
                        BlockCollisionShapeResolver.fullCubesForNonAir())
                .cast(
                        new Vector3f(
                                KEY.worldOriginX() + 0.30f,
                                0.125f,
                                KEY.worldOriginZ() + 0.125f),
                        new Vector3f(-1, 0, 0),
                        1.0f)
                .isPresent());
        assertTrue(new CollisionWorld(
                        world,
                        BlockCollisionShapeResolver.fullCubesForNonAir())
                .overlapsSolid(new Aabb(
                        KEY.worldOriginX() + 0.05f,
                        0.05f,
                        KEY.worldOriginZ() + 0.05f,
                        KEY.worldOriginX() + 0.20f,
                        0.20f,
                        KEY.worldOriginZ() + 0.20f)));
        assertFalse(manager.hasInstalledRenderObject(KEY));
        for (int attempt = 0; attempt < 10; attempt++) {
            assertEquals(0, manager.scheduleEligible());
        }
        assertEquals(0, executor.size());
        assertEquals(0, manager.metrics().accepted());
    }

    @Test
    void staleOutputLimitFailureCannotPoisonAChangedRevision() {
        ChunkRepository repository = fragmentedRepository(256);
        ManualExecutor executor = new ManualExecutor();
        ChunkMeshManager manager = manager(
                repository, executor, new RecordingBackend());
        long claimedRevision = repository.revision(KEY);

        assertEquals(1, manager.scheduleEligible());
        DetailCellState expected = (DetailCellState) repository.observeCell(
                        KEY.worldOriginX(), 0, KEY.worldOriginZ())
                .observation().orElseThrow().state();
        ChunkDetailMutationOutcome changed = repository.mutateDetail(
                new ChunkDetailMutation.SetSubVoxel(
                        KEY.worldOriginX(),
                        0,
                        KEY.worldOriginZ(),
                        claimedRevision,
                        expected,
                        new LocalSubVoxelPosition(0, 0, 0),
                        (byte) 2));
        executor.runAll();

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, changed.status());
        assertEquals(1, manager.drainCompletedCpuWork());
        assertTrue(manager.pollFailure().isEmpty());
        assertTrue(manager.outputLimitDiagnostic(KEY).isEmpty());
        assertEquals(ChunkAvailability.AVAILABLE, repository.observeCell(
                KEY.worldOriginX(), 0, KEY.worldOriginZ()).status());
        assertEquals(ChunkState.DIRTY, repository.state(KEY));
    }

    @Test
    void reducingCanonicalComplexityAfterFailurePermitsASuccessfulBuild() {
        ChunkRepository repository = fragmentedRepository(256);
        ManualExecutor executor = new ManualExecutor();
        RecordingBackend backend = new RecordingBackend();
        ChunkMeshManager manager = manager(repository, executor, backend);
        assertEquals(1, manager.scheduleEligible());
        executor.runAll();
        assertEquals(1, manager.drainCompletedCpuWork());
        assertTrue(manager.outputLimitDiagnostic(KEY).isPresent());

        clearCheckerboardParents(repository, 80);

        assertTrue(manager.outputLimitDiagnostic(KEY).isEmpty());
        assertEquals(1, manager.scheduleEligible());
        executor.runAll();
        assertEquals(1, manager.drainCompletedCpuWork());
        assertEquals(1, manager.processMainThreadWork());
        assertEquals(1, backend.uploads);
        assertEquals(ChunkState.RENDERABLE, repository.state(KEY));
    }

    @Test
    void outputLimitFailurePreservesButDoesNotMislabelLastKnownGoodMesh() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        RecordingBackend backend = new RecordingBackend();
        AtomicBoolean reject = new AtomicBoolean();
        ChunkMeshBuilder delegate = new ChunkMeshBuilder(ignored -> renderInfo());
        ChunkMesher mesher = input -> {
            if (reject.get()) {
                throw new ChunkMeshOutputLimitExceededException(
                        input.center().key(),
                        input.center().revision(),
                        ChunkMeshBuilder.MAX_HYBRID_MESH_BYTES,
                        8_388_480L,
                        8_388_720L,
                        34_953L,
                        209_718L,
                        8_388_608L);
            }
            return delegate.build(input);
        };
        ChunkMeshManager manager = new ChunkMeshManager(
                repository,
                mesher,
                executor,
                backend,
                MainThreadGuard.captureCurrentThread(),
                2);
        assertEquals(1, manager.scheduleEligible());
        executor.runAll();
        assertEquals(1, manager.drainCompletedCpuWork());
        assertEquals(1, manager.processMainThreadWork());
        long installedRevision = manager.renderObjects().iterator().next().revision();

        reject.set(true);
        assertTrue(repository.setBlock(
                KEY.worldOriginX() + 1,
                1,
                KEY.worldOriginZ() + 1,
                (byte) 2));
        long rejectedRevision = repository.revision(KEY);
        assertEquals(1, manager.scheduleEligible());
        executor.runAll();
        assertEquals(1, manager.drainCompletedCpuWork());

        assertTrue(manager.hasInstalledRenderObject(KEY));
        assertEquals(installedRevision,
                manager.renderObjects().iterator().next().revision());
        assertTrue(installedRevision < rejectedRevision);
        assertEquals(
                rejectedRevision,
                manager.outputLimitDiagnostic(KEY).orElseThrow().revision());
        assertEquals(ChunkMeshManager.MeshPhase.FAILED, manager.meshPhase(KEY));
        assertEquals(1, backend.uploads);
        assertEquals(ChunkState.DIRTY, repository.state(KEY));
    }

    private static ChunkMeshManager manager(
            ChunkRepository repository,
            Executor executor,
            RecordingBackend backend) {
        return new ChunkMeshManager(
                repository,
                new ChunkMeshBuilder(ignored -> renderInfo()),
                executor,
                backend,
                MainThreadGuard.captureCurrentThread(),
                2);
    }

    private static ChunkRepository generatedRepository() {
        ChunkRepository repository = new ChunkRepository();
        repository.generate(KEY, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        return repository;
    }

    private static ChunkRepository fragmentedRepository(int parents) {
        int height = 4;
        long mask = checkerboardMask();
        int[] indices = new int[parents];
        long[] masks = new long[parents];
        byte[] ids = new byte[parents * DetailCellState.CELL_COUNT];
        for (int parent = 0; parent < parents; parent++) {
            indices[parent] = parent;
            masks[parent] = mask;
            for (int cell = 0; cell < DetailCellState.CELL_COUNT; cell++) {
                if ((mask & (1L << cell)) != 0L) {
                    ids[parent * DetailCellState.CELL_COUNT + cell] = 1;
                }
            }
        }
        ChunkSnapshot snapshot = ChunkSnapshot.of(
                KEY,
                12L,
                height,
                new byte[16 * height * 16],
                DetailChunkSnapshot.of(indices, masks, ids));
        ChunkRepository repository = new ChunkRepository(
                height, new ChunkDirtyTracker());
        assertEquals(
                ChunkRepositoryRestoreResult.Status.RESTORED,
                repository.restoreCanonical(new ChunkRepositorySnapshot(
                        height, 12L, List.of(snapshot))).status());
        return repository;
    }

    private static void clearCheckerboardParents(
            ChunkRepository repository, int parentCount) {
        for (int parent = 0; parent < parentCount; parent++) {
            int localX = parent % 16;
            int remaining = parent / 16;
            int y = remaining % 4;
            int localZ = remaining / 4;
            int worldX = KEY.worldOriginX() + localX;
            int worldZ = KEY.worldOriginZ() + localZ;
            for (int cell = 0; cell < DetailCellState.CELL_COUNT; cell++) {
                if ((checkerboardMask() & (1L << cell)) == 0L) {
                    continue;
                }
                ParentCellObservation observation = repository.observeCell(
                                worldX, y, worldZ)
                        .observation().orElseThrow();
                ChunkDetailMutationOutcome outcome = repository.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                worldX,
                                y,
                                worldZ,
                                observation.chunkRevision(),
                                observation.state(),
                                LocalSubVoxelPosition.fromIndex(cell),
                                (byte) 0));
                assertEquals(
                        ChunkDetailMutationOutcome.Status.APPLIED,
                        outcome.status());
            }
        }
    }

    private static long checkerboardMask() {
        long mask = 0L;
        for (int index = 0; index < DetailCellState.CELL_COUNT; index++) {
            int x = index & 3;
            int y = (index >>> 2) & 3;
            int z = index >>> 4;
            if (((x + y + z) & 1) == 0) {
                mask |= 1L << index;
            }
        }
        return mask;
    }

    private static BlockRenderInfo renderInfo() {
        ResourceLocation atlas = ResourceLocation.parse("test:blocks");
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("test:solid"),
                atlas,
                RenderType.OPAQUE,
                0.5f,
                ResourceLocation.parse("test:missing"));
        TextureRegion region = new TextureRegion(
                ResourceLocation.parse("test:solid"),
                0,
                0,
                16,
                16,
                16,
                16);
        Map<BlockFace, TextureRegion> regions = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, region);
        }
        return new BlockRenderInfo(material, regions, true);
    }

    private static final class RecordingBackend implements ChunkRenderBackend {
        private int uploads;

        @Override
        public ChunkRenderObject upload(ChunkMeshData data) {
            uploads++;
            return new ChunkRenderObject(
                    data.key(),
                    data.revision(),
                    new FakeGpuMesh(data.vertexCount()),
                    data.localBounds().orElseThrow());
        }

        @Override
        public void release(ChunkRenderObject object) {}
    }

    private record FakeGpuMesh(int vertexCount) implements ChunkGpuMesh {
        @Override
        public void draw() {}

        @Override
        public void cleanup() {}
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }

        private int size() {
            return tasks.size();
        }
    }
}
