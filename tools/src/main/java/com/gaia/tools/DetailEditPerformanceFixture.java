package com.gaia.tools;

import com.gaia.assets.GaiaResourceLoader;
import com.gaia.interaction.GaiaDetailMutationService;
import com.gaia.interaction.GaiaInteractionContext;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.inventory.api.BodySlot;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellState;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;

/** Bounded repeatable Phase 17 pressed-edge edit measurement. */
public final class DetailEditPerformanceFixture {
    private static final ChunkKey KEY = new ChunkKey(0, 0);
    private static final int PARENT_X = 1;
    private static final int PARENT_Y = 2;
    private static final int PARENT_Z = 1;
    private static final LocalSubVoxelPosition LOCAL = new LocalSubVoxelPosition(1, 1, 1);
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");

    private DetailEditPerformanceFixture() {}

    public static Result run(int pressedEdges, int staleEvery) {
        if (pressedEdges < 1 || pressedEdges > 10_000) {
            throw new IllegalArgumentException("pressedEdges must be within 1..10000");
        }
        if (staleEvery < 0) {
            throw new IllegalArgumentException("staleEvery must be nonnegative");
        }
        ChunkRepository repository = new ChunkRepository(8, new ChunkDirtyTracker());
        repository.generate(KEY, ignored -> {});
        GaiaDetailMutationService mutations = new GaiaDetailMutationService(
                MainThreadGuard.captureCurrentThread(),
                new GaiaResourceLoader(new AssetManager(
                        DetailEditPerformanceFixture.class.getClassLoader()))
                        .load().blockRegistry(),
                repository);
        ManualExecutor executor = new ManualExecutor();
        RecordingBackend backend = new RecordingBackend();
        ChunkMeshManager meshes = new ChunkMeshManager(
                repository,
                new ChunkMeshBuilder(ignored -> renderInfo()),
                executor,
                backend,
                MainThreadGuard.captureCurrentThread(),
                2);
        LinkedHashSet<ChunkKey> affected = new LinkedHashSet<>();
        int applied = 0;
        int stale = 0;
        int rejected = 0;
        long totalLatency = 0;
        long maximumLatency = 0;
        int meshAcceptedPeak = 0;
        int meshActivePeak = 0;
        int meshCompletedPeak = 0;
        long meshOutputBytesPeak = 0L;
        long meshCpuBudgetBytesPeak = 0L;

        for (int edge = 1; edge <= pressedEdges; edge++) {
            long currentRevision = repository.revision(KEY);
            ParentCellState expected = repository.snapshot(KEY).orElseThrow()
                    .cellState(PARENT_X, PARENT_Y, PARENT_Z);
            Optional<ResourceLocation> replacement = expected instanceof FullCellState
                    ? Optional.of(STONE)
                    : Optional.empty();
            long requestedRevision = staleEvery > 0 && edge % staleEvery == 0
                    ? currentRevision - 1
                    : currentRevision;
            long started = System.nanoTime();
            DetailMutationResult result = mutations.sculptParentSubVoxel(
                    new SculptParentSubVoxelRequest(
                            new GaiaInteractionContext(
                                    new EntityRef(1), BodySlot.RIGHT_HAND,
                                    replacement.isPresent()
                                            ? InteractionAction.SECONDARY
                                            : InteractionAction.PRIMARY,
                                    edge, edge),
                            PARENT_X, PARENT_Y, PARENT_Z, requestedRevision,
                            expected, LOCAL, replacement));
            long latency = System.nanoTime() - started;
            totalLatency = Math.addExact(totalLatency, latency);
            maximumLatency = Math.max(maximumLatency, latency);
            if (result.status() == DetailMutationResult.Status.APPLIED) {
                applied++;
                affected.addAll(result.dirtyChunks());
                if (applied == 1) {
                    meshes.scheduleEligible(Set.copyOf(affected).stream().sorted().toList());
                    meshAcceptedPeak = Math.max(
                            meshAcceptedPeak, meshes.metrics().accepted());
                    meshActivePeak = Math.max(
                            meshActivePeak, meshes.metrics().active());
                    meshCpuBudgetBytesPeak = Math.max(
                            meshCpuBudgetBytesPeak,
                            meshes.memoryMetrics().usedBytes());
                }
            } else if (result.status()
                    == DetailMutationResult.Status.STALE_CHUNK_REVISION) {
                stale++;
            } else {
                rejected++;
            }
        }

        executor.runAll();
        meshCompletedPeak = Math.max(meshCompletedPeak, meshes.metrics().completed());
        meshOutputBytesPeak = Math.max(
                meshOutputBytesPeak,
                meshes.memoryMetrics().completedRetainedBytes());
        meshCpuBudgetBytesPeak = Math.max(
                meshCpuBudgetBytesPeak,
                meshes.memoryMetrics().usedBytes());
        int awaitingUploadBeforeDrain = meshes.metrics().awaitingUpload();
        int drainedMeshResults = meshes.drainCompletedCpuWork();
        int currentMeshResults = Math.subtractExact(
                meshes.metrics().awaitingUpload(), awaitingUploadBeforeDrain);
        int staleMeshResults = Math.subtractExact(drainedMeshResults, currentMeshResults);

        meshes.scheduleEligible(Set.copyOf(affected).stream().sorted().toList());
        meshAcceptedPeak = Math.max(meshAcceptedPeak, meshes.metrics().accepted());
        meshActivePeak = Math.max(meshActivePeak, meshes.metrics().active());
        meshCpuBudgetBytesPeak = Math.max(
                meshCpuBudgetBytesPeak,
                meshes.memoryMetrics().usedBytes());
        executor.runAll();
        meshCompletedPeak = Math.max(meshCompletedPeak, meshes.metrics().completed());
        meshOutputBytesPeak = Math.max(
                meshOutputBytesPeak,
                meshes.memoryMetrics().completedRetainedBytes());
        meshCpuBudgetBytesPeak = Math.max(
                meshCpuBudgetBytesPeak,
                meshes.memoryMetrics().usedBytes());
        meshes.processMainThreadWork();
        long meshBuildLatencyNanos = meshes.lifecycleMetrics().lastMeshLatencyNanos();
        long meshCpuBudgetBytesLimit = meshes.memoryMetrics().budgetBytes();
        int meshAffectedChunks = affected.size();
        meshes.close();

        ParentCellState finalState = repository.snapshot(KEY).orElseThrow()
                .cellState(PARENT_X, PARENT_Y, PARENT_Z);
        int occupied = finalState instanceof DetailCellState detail
                ? Long.bitCount(detail.occupancyMask())
                : 0;
        return new Result(
                pressedEdges, applied, stale, rejected,
                totalLatency, maximumLatency, Set.copyOf(affected),
                repository.revision(KEY), occupied,
                meshAcceptedPeak, meshActivePeak, meshCompletedPeak,
                staleMeshResults, meshOutputBytesPeak,
                meshCpuBudgetBytesPeak, meshCpuBudgetBytesLimit,
                meshAffectedChunks, meshBuildLatencyNanos);
    }

    public static void main(String[] args) {
        int edges = args.length == 0 ? 120 : Integer.parseInt(args[0]);
        int staleEvery = args.length < 2 ? 0 : Integer.parseInt(args[1]);
        System.out.println(run(edges, staleEvery));
    }

    public record Result(
            int attempts,
            int applied,
            int stale,
            int rejected,
            long totalLatencyNanos,
            long maximumLatencyNanos,
            Set<ChunkKey> affectedChunks,
            long finalRevision,
            int finalOccupiedCount,
            int meshAcceptedPeak,
            int meshActivePeak,
            int meshCompletedPeak,
            int staleMeshResults,
            long meshOutputBytesPeak,
            long meshCpuBudgetBytesPeak,
            long meshCpuBudgetBytesLimit,
            int meshAffectedChunks,
            long meshBuildLatencyNanos) {
        public Result {
            affectedChunks = Set.copyOf(affectedChunks);
        }
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
                0, 0, 16, 16, 16, 16);
        Map<BlockFace, TextureRegion> regions = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, region);
        }
        return new BlockRenderInfo(material, regions, true);
    }

    private static final class RecordingBackend implements ChunkRenderBackend {
        @Override
        public ChunkRenderObject upload(ChunkMeshData data) {
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
        @Override public void draw() {}
        @Override public void cleanup() {}
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
    }
}
