package com.gaia.tools;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.BlockRenderResolver;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshBudget;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.ChunkMeshInput;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.ChunkMeshMemoryPlan;
import com.overlord.voxel.ChunkMesher;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositoryRestoreResult;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DetailChunkSnapshot;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryUtil;

/** Mixed 512 MiB stress for the production CPU-mesh byte lifecycle. */
public final class HybridMeshRetentionStressFixture {
    private static final int WORLD_HEIGHT = 128;
    private static final int CHUNK_COUNT = 32;
    private static final long TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(3);

    private HybridMeshRetentionStressFixture() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--attribution".equals(args[0])) {
            runAttribution(true);
            return;
        }
        if (args.length == 1
                && "--attribution-artifact-resolver".equals(args[0])) {
            runAttribution(false);
            return;
        }
        boolean artifactResolver = args.length == 1
                && "--artifact-resolver".equals(args[0]);
        boolean cachedProductionResolver = !artifactResolver;
        boolean diagnosticWarmupGc = args.length == 1
                && "--production-resolver-warmed".equals(args[0]);
        Result result = run(
                cachedProductionResolver, diagnosticWarmupGc);
        System.out.printf(
                Locale.ROOT,
                "mode=%s resolver=%s heapLimit=%d warmedBaseline=%d "
                        + "memoryBudget=%d memoryUsedPeak=%d "
                        + "activeReservedPeak=%d completedBytesPeak=%d "
                        + "uploadScratchPeak=%d directBytesPeak=%d "
                        + "acceptedPeak=%d queuedPeak=%d activePeak=%d "
                        + "completedPeak=%d heapPeak=%d heapPercent=%.2f "
                        + "gcCount=%d gcTimeMs=%d uploads=%d drainFrames=%d "
                        + "elapsedMs=%.3f forwardProgress=%s starvation=%s%n",
                diagnosticWarmupGc
                        ? "diagnostic-explicit-gc"
                        : "acceptance-no-explicit-gc",
                cachedProductionResolver
                        ? "production-cached"
                        : "artifact-reconstructing",
                result.heapLimitBytes(), result.warmedBaselineBytes(),
                result.memoryBudgetBytes(),
                result.memoryUsedPeakBytes(), result.activeReservedPeakBytes(),
                result.completedBytesPeak(), result.uploadScratchPeakBytes(),
                result.directBytesPeak(), result.acceptedPeak(),
                result.queuedPeak(), result.activePeak(),
                result.completedPeak(), result.heapPeakBytes(),
                result.heapPercent(), result.gcCount(), result.gcTimeMillis(),
                result.uploads(), result.drainFrames(),
                result.elapsedNanos() / 1_000_000.0,
                result.forwardProgress(), result.starvationObserved());
    }

    public static Result run() throws Exception {
        return run(true, false);
    }

    private static Result run(
            boolean cachedProductionResolver,
            boolean diagnosticWarmupGc)
            throws Exception {
        AtomicLong heapPeak = new AtomicLong();
        ChunkRepository repository = repository();
        ExecutorService workers = Executors.newFixedThreadPool(
                ChunkMeshBudget.productionDefaults().maxActive(),
                runnable -> {
                    Thread thread = new Thread(runnable, "mixed-mesh-stress");
                    thread.setDaemon(true);
                    return thread;
                });
        MeasuringBackend backend = new MeasuringBackend(heapPeak);
        ChunkMeshBuilder production = new ChunkMeshBuilder(
                fixtureResolver(cachedProductionResolver));
        ChunkMesher measured = new ChunkMesher() {
            @Override
            public ChunkMeshMemoryPlan preflight(ChunkMeshInput input) {
                return production.preflight(input);
            }

            @Override
            public ChunkMeshData build(ChunkMeshInput input) {
                return production.build(input);
            }

            @Override
            public ChunkMeshData build(
                    ChunkMeshInput input, ChunkMeshMemoryPlan plan) {
                sampleHeap(heapPeak);
                ChunkMeshData data = production.build(input, plan);
                sampleHeap(heapPeak);
                return data;
            }
        };
        ChunkMeshManager manager = new ChunkMeshManager(
                repository, measured, workers, backend,
                MainThreadGuard.captureCurrentThread(),
                ChunkMeshBudget.productionDefaults());

        warmUp(production);
        if (diagnosticWarmupGc) {
            fullGc();
        }
        long warmedBaseline = usedHeap();
        heapPeak.set(warmedBaseline);
        long started = System.nanoTime();
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTimeMillis();

        int acceptedPeak = 0;
        int queuedPeak = 0;
        int activePeak = 0;
        int completedPeak = 0;
        long activeReservedPeak = 0L;
        long completedBytesPeak = 0L;
        long uploadScratchPeak = 0L;
        int frames = 0;
        try {
            int scheduled = manager.scheduleEligible();
            if (scheduled != CHUNK_COUNT) {
                throw new IllegalStateException(
                        "expected 32 accepted meshes, got " + scheduled);
            }
            ChunkMeshManager.Metrics initialCounts = manager.metrics();
            ChunkMeshManager.MemoryMetrics initialMemory =
                    manager.memoryMetrics();
            acceptedPeak = initialCounts.accepted();
            queuedPeak = initialCounts.queued();
            activePeak = initialCounts.active();
            completedPeak = initialCounts.completed();
            activeReservedPeak = initialMemory.activeReservedBytes();
            completedBytesPeak = initialMemory.completedRetainedBytes();
            uploadScratchPeak = initialMemory.uploadScratchBytes();

            ChunkKey staleKey = new ChunkKey(0, 0);
            if (!repository.setBlock(
                    staleKey.worldOriginX(), 0,
                    staleKey.worldOriginZ(), (byte) 2)) {
                throw new IllegalStateException(
                        "stress could not create the stale FULL claim");
            }

            long deadline = Math.addExact(System.nanoTime(), TIMEOUT_NANOS);
            while (manager.metrics().accepted() != 0) {
                manager.processMainThreadWork();
                frames++;
                ChunkMeshManager.Metrics counts = manager.metrics();
                ChunkMeshManager.MemoryMetrics memory =
                        manager.memoryMetrics();
                acceptedPeak = Math.max(acceptedPeak, counts.accepted());
                queuedPeak = Math.max(queuedPeak, counts.queued());
                activePeak = Math.max(activePeak, counts.active());
                completedPeak = Math.max(completedPeak, counts.completed());
                activeReservedPeak = Math.max(
                        activeReservedPeak, memory.activeReservedBytes());
                completedBytesPeak = Math.max(
                        completedBytesPeak, memory.completedRetainedBytes());
                uploadScratchPeak = Math.max(
                        uploadScratchPeak, memory.uploadScratchBytes());
                sampleHeap(heapPeak);
                if (memory.usedBytes() > memory.budgetBytes()) {
                    throw new IllegalStateException(
                            "CPU mesh byte policy was exceeded");
                }
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "mixed mesh lifecycle did not drain");
                }
                Thread.sleep(1L);
            }
            Throwable failure = manager.pollFailure().orElse(null);
            if (failure != null) {
                throw new IllegalStateException(
                        "mixed lifecycle unexpectedly failed", failure);
            }
            ChunkMeshManager.MemoryMetrics finalMemory =
                    manager.memoryMetrics();
            boolean forward = finalMemory.usedBytes() == 0L
                    && backend.uploads == CHUNK_COUNT - 1;
            double heapPercent = heapPeak.get() * 100.0
                    / Runtime.getRuntime().maxMemory();
            return new Result(
                    Runtime.getRuntime().maxMemory(),
                    warmedBaseline,
                    finalMemory.budgetBytes(), finalMemory.peakUsedBytes(),
                    activeReservedPeak, completedBytesPeak,
                    uploadScratchPeak, finalMemory.peakDirectUploadBytes(),
                    acceptedPeak, queuedPeak, activePeak, completedPeak,
                    heapPeak.get(), heapPercent,
                    Math.max(0L, gcCount() - gcCountBefore),
                    Math.max(0L, gcTimeMillis() - gcTimeBefore),
                    backend.uploads, frames, System.nanoTime() - started,
                    forward, false);
        } finally {
            workers.shutdownNow();
            if (!workers.awaitTermination(10L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("mesh workers did not stop");
            }
            manager.close();
        }
    }

    private static ChunkRepository repository() {
        List<ChunkSnapshot> snapshots = new ArrayList<>(CHUNK_COUNT);
        for (int index = 0; index < CHUNK_COUNT; index++) {
            snapshots.add(snapshot(index, new ChunkKey(index * 2, 0),
                    index + 1L));
        }
        ChunkRepository repository = new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
        ChunkRepositoryRestoreResult restored = repository.restoreCanonical(
                new ChunkRepositorySnapshot(
                        WORLD_HEIGHT, CHUNK_COUNT, snapshots));
        if (restored.status()
                != ChunkRepositoryRestoreResult.Status.RESTORED) {
            throw new IllegalStateException(
                    "stress restore failed: " + restored.status());
        }
        return repository;
    }

    private static void warmUp(ChunkMeshBuilder builder) {
        ChunkSnapshot center = smallFull(new ChunkKey(-10_000, -10_000), 1L);
        ChunkMeshData mesh = builder.build(new ChunkMeshInput(
                center, null, null, null, null, null, null, null, null));
        if (mesh.isEmpty()) {
            throw new IllegalStateException("mesh warm-up unexpectedly empty");
        }
    }

    private static ChunkSnapshot snapshot(
            int ordinal, ChunkKey key, long revision) {
        return switch (ordinal & 3) {
            case 0 -> heavyFull(key, revision);
            case 1 -> nearCapHybrid(key, revision);
            case 2 -> smallFull(key, revision);
            default -> smallHybrid(key, revision);
        };
    }

    private static ChunkSnapshot heavyFull(ChunkKey key, long revision) {
        byte[] blocks = new byte[
                GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];
        for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
            for (int y = 0; y < WORLD_HEIGHT; y++) {
                for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
                    if (((x + y + z) & 1) == 0) {
                        blocks[x + y * GameConfig.Chunk.SIZE
                                + z * GameConfig.Chunk.SIZE * WORLD_HEIGHT] = 1;
                    }
                }
            }
        }
        return ChunkSnapshot.of(key, revision, WORLD_HEIGHT, blocks);
    }

    private static ChunkSnapshot smallFull(ChunkKey key, long revision) {
        byte[] blocks = new byte[
                GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];
        blocks[0] = 1;
        return ChunkSnapshot.of(key, revision, WORLD_HEIGHT, blocks);
    }

    private static ChunkSnapshot nearCapHybrid(
            ChunkKey key, long revision) {
        int parents = Chunk.MAX_DETAIL_PARENTS_PER_CHUNK;
        int[] indices = new int[parents];
        long[] masks = new long[parents];
        byte[] ids = new byte[parents * DetailCellState.CELL_COUNT];
        long slab = 0L;
        for (int cell = 0; cell < DetailCellState.CELL_COUNT; cell++) {
            if (((cell >>> 2) & 3) == 0) {
                slab |= 1L << cell;
            }
        }
        for (int parent = 0; parent < parents; parent++) {
            int parentX = parent % GameConfig.Chunk.SIZE;
            int parentY = (parent / GameConfig.Chunk.SIZE) % 4;
            int parentZ = parent / (GameConfig.Chunk.SIZE * 4);
            indices[parent] = parentX
                    + parentY * GameConfig.Chunk.SIZE
                    + parentZ * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
            masks[parent] = slab;
            for (int cell = 0; cell < DetailCellState.CELL_COUNT; cell++) {
                if ((slab & (1L << cell)) != 0L) {
                    int x = cell & 3;
                    int z = cell >>> 4;
                    ids[parent * DetailCellState.CELL_COUNT + cell] =
                            (byte) (((x + z) & 1) == 0 ? 1 : 2);
                }
            }
        }
        return ChunkSnapshot.of(
                key, revision, WORLD_HEIGHT,
                new byte[GameConfig.Chunk.SIZE * WORLD_HEIGHT
                        * GameConfig.Chunk.SIZE],
                DetailChunkSnapshot.of(indices, masks, ids));
    }

    private static ChunkSnapshot smallHybrid(
            ChunkKey key, long revision) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        ids[0] = 1;
        return ChunkSnapshot.of(
                key, revision, WORLD_HEIGHT,
                new byte[GameConfig.Chunk.SIZE * WORLD_HEIGHT
                        * GameConfig.Chunk.SIZE],
                DetailChunkSnapshot.of(
                        new int[] {0}, new long[] {1L}, ids));
    }

    private static BlockRenderInfo renderInfo() {
        ResourceLocation atlas = ResourceLocation.parse("test:blocks");
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("test:solid"), atlas,
                RenderType.OPAQUE, 0.5f,
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

    static BlockRenderResolver fixtureResolver(
            boolean cachedProductionResolver) {
        if (!cachedProductionResolver) {
            return ignored -> renderInfo();
        }
        BlockRenderInfo cached = renderInfo();
        return ignored -> cached;
    }

    private static void sampleHeap(AtomicLong peak) {
        peak.accumulateAndGet(usedHeap(), Math::max);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long gcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean bean
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() >= 0L) {
                total = Math.addExact(total, bean.getCollectionCount());
            }
        }
        return total;
    }

    private static long gcTimeMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean bean
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionTime() >= 0L) {
                total = Math.addExact(total, bean.getCollectionTime());
            }
        }
        return total;
    }

    private static void runAttribution(boolean cachedProductionResolver)
            throws Exception {
        Path outputDirectory = Path.of(
                "tools", "build", "diagnostics", "memory-attribution",
                cachedProductionResolver
                        ? "production-resolver"
                        : "artifact-resolver");
        Files.createDirectories(outputDirectory);

        AtomicLong heapPeak = new AtomicLong(usedHeap());
        ChunkRepository repository = repository();
        ExecutorService workers = Executors.newFixedThreadPool(
                ChunkMeshBudget.productionDefaults().maxActive(),
                runnable -> {
                    Thread thread = new Thread(
                            runnable, "mesh-attribution-worker");
                    thread.setDaemon(true);
                    return thread;
                });
        CountDownLatch permitWorkerStart = new CountDownLatch(1);
        Executor gatedExecutor = command -> workers.execute(() -> {
            await(permitWorkerStart, "worker-start gate");
            command.run();
        });
        CountDownLatch firstTwoBuilt = new CountDownLatch(2);
        CountDownLatch releaseFirstTwoBuilds = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger();
        ChunkMeshBuilder production = new ChunkMeshBuilder(
                fixtureResolver(cachedProductionResolver));
        ChunkMesher heldMesher = new ChunkMesher() {
            @Override
            public ChunkMeshMemoryPlan preflight(ChunkMeshInput input) {
                return production.preflight(input);
            }

            @Override
            public ChunkMeshData build(ChunkMeshInput input) {
                return production.build(input);
            }

            @Override
            public ChunkMeshData build(
                    ChunkMeshInput input, ChunkMeshMemoryPlan plan) {
                ChunkMeshData data = production.build(input, plan);
                if (builds.getAndIncrement() < 2) {
                    firstTwoBuilt.countDown();
                    await(releaseFirstTwoBuilds,
                            "first-two-build attribution gate");
                }
                sampleHeap(heapPeak);
                return data;
            }
        };
        MeasuringBackend backend = new MeasuringBackend(heapPeak);
        ChunkMeshManager manager = new ChunkMeshManager(
                repository, heldMesher, gatedExecutor, backend,
                MainThreadGuard.captureCurrentThread(),
                ChunkMeshBudget.productionDefaults());
        try {
            fullGc();
            capture("P0", manager, outputDirectory, false);

            int scheduled = manager.scheduleEligible();
            if (scheduled != CHUNK_COUNT) {
                throw new IllegalStateException(
                        "expected 32 accepted meshes, got " + scheduled);
            }
            capture("P1", manager, outputDirectory, true);

            permitWorkerStart.countDown();
            if (!firstTwoBuilt.await(2L, TimeUnit.MINUTES)) {
                throw new IllegalStateException(
                        "two attribution builds did not reach hold point");
            }
            capture("P2", manager, outputDirectory, true);

            releaseFirstTwoBuilds.countDown();
            awaitCompletedPressure(manager);
            capture("P3", manager, outputDirectory, true);

            manager.processMainThreadWork();
            capture("P4", manager, outputDirectory, true);

            drainAll(manager);
            capture("P5", manager, outputDirectory, false);
            fullGc();
            capture("P6", manager, outputDirectory, false);

            ChunkMeshManager.MemoryMetrics finalMemory =
                    manager.memoryMetrics();
            System.out.printf(
                    Locale.ROOT,
                    "ATTRIBUTION_DONE resolver=%s uploads=%d "
                            + "managerUsed=%d installed=%d heap=%d "
                            + "heapPeak=%d%n",
                    cachedProductionResolver ? "production" : "artifact",
                    backend.uploads,
                    finalMemory.usedBytes(),
                    manager.renderObjects().size(),
                    usedHeap(),
                    heapPeak.get());
        } finally {
            permitWorkerStart.countDown();
            releaseFirstTwoBuilds.countDown();
            workers.shutdownNow();
            if (!workers.awaitTermination(10L, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "attribution workers did not stop");
            }
            manager.close();
        }
    }

    private static void awaitCompletedPressure(ChunkMeshManager manager)
            throws InterruptedException {
        long deadline = Math.addExact(
                System.nanoTime(), TimeUnit.MINUTES.toNanos(2));
        while (true) {
            ChunkMeshManager.Metrics metrics = manager.metrics();
            if (metrics.active() == 0
                    && metrics.queued() > 0
                    && metrics.completed() > 0) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "completed-pressure checkpoint was not reached: "
                                + metrics);
            }
            Thread.sleep(1L);
        }
    }

    private static void drainAll(ChunkMeshManager manager)
            throws InterruptedException {
        long deadline = Math.addExact(
                System.nanoTime(), TimeUnit.MINUTES.toNanos(3));
        while (manager.metrics().accepted() != 0) {
            manager.processMainThreadWork();
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "attribution lifecycle did not drain");
            }
            Thread.sleep(1L);
        }
    }

    private static void capture(
            String label,
            ChunkMeshManager manager,
            Path outputDirectory,
            boolean collectAfterSnapshot) throws Exception {
        writeJcmd(outputDirectory.resolve(label + "-heap.txt"),
                "GC.heap_info");
        writeJcmd(outputDirectory.resolve(label + "-histogram-all.txt"),
                "GC.class_histogram", "-all");
        writeJcmd(outputDirectory.resolve(label + "-native.txt"),
                "VM.native_memory", "summary");
        printCheckpoint(label + "_BEFORE_GC", manager);
        if (collectAfterSnapshot) {
            fullGc();
            writeJcmd(
                    outputDirectory.resolve(label + "-histogram-live.txt"),
                    "GC.class_histogram", "-all");
            printCheckpoint(label + "_AFTER_GC", manager);
        }
    }

    private static void printCheckpoint(
            String label, ChunkMeshManager manager) {
        ChunkMeshManager.Metrics counts = manager.metrics();
        ChunkMeshManager.MemoryMetrics memory = manager.memoryMetrics();
        System.out.printf(
                Locale.ROOT,
                "CHECKPOINT %s heap=%d manager=%d activeReserved=%d "
                        + "completedBytes=%d direct=%d accepted=%d queued=%d "
                        + "active=%d completed=%d awaiting=%d installed=%d%n",
                label, usedHeap(), memory.usedBytes(),
                memory.activeReservedBytes(),
                memory.completedRetainedBytes(),
                memory.directUploadBytes(),
                counts.accepted(), counts.queued(), counts.active(),
                counts.completed(), counts.awaitingUpload(),
                manager.renderObjects().size());
    }

    private static void fullGc() throws InterruptedException {
        long before = gcCount();
        System.gc();
        System.runFinalization();
        long deadline = Math.addExact(
                System.nanoTime(), TimeUnit.SECONDS.toNanos(10));
        while (gcCount() <= before && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        Thread.sleep(100L);
    }

    private static void writeJcmd(Path output, String... diagnosticCommand)
            throws Exception {
        String javaHome = System.getProperty("java.home");
        Path executable = Path.of(
                javaHome, "bin",
                System.getProperty("os.name").startsWith("Windows")
                        ? "jcmd.exe"
                        : "jcmd");
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add(Long.toString(ProcessHandle.current().pid()));
        command.addAll(List.of(diagnosticCommand));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        byte[] outputBytes = process.getInputStream().readAllBytes();
        if (!process.waitFor(30L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("jcmd timed out: " + command);
        }
        Files.writeString(
                output,
                new String(outputBytes, StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "jcmd failed: " + command + " -> "
                            + process.exitValue());
        }
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(3L, TimeUnit.MINUTES)) {
                throw new IllegalStateException(description + " timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    description + " interrupted", interrupted);
        }
    }

    private static final class MeasuringBackend implements ChunkRenderBackend {
        private final AtomicLong heapPeak;
        private int uploads;

        private MeasuringBackend(AtomicLong heapPeak) {
            this.heapPeak = heapPeak;
        }

        @Override
        public UploadMemoryRequirement uploadMemoryRequirement(
                ChunkMeshData data) {
            return new UploadMemoryRequirement(0L, data.outputByteSize());
        }

        @Override
        public ChunkRenderObject upload(ChunkMeshData data) {
            ByteBuffer direct = MemoryUtil.memAlloc(
                    Math.toIntExact(data.outputByteSize()));
            try {
                data.copyVerticesTo(direct.asFloatBuffer());
                sampleHeap(heapPeak);
            } finally {
                MemoryUtil.memFree(direct);
            }
            uploads++;
            return new ChunkRenderObject(
                    data.key(), data.revision(),
                    new FakeGpuMesh(data.vertexCount()),
                    data.localBounds().orElseThrow());
        }

        @Override
        public void release(ChunkRenderObject object) {
            object.mesh().cleanup();
        }
    }

    private record FakeGpuMesh(int vertexCount) implements ChunkGpuMesh {
        @Override
        public void draw() {}

        @Override
        public void cleanup() {}
    }

    public record Result(
            long heapLimitBytes,
            long warmedBaselineBytes,
            long memoryBudgetBytes,
            long memoryUsedPeakBytes,
            long activeReservedPeakBytes,
            long completedBytesPeak,
            long uploadScratchPeakBytes,
            long directBytesPeak,
            int acceptedPeak,
            int queuedPeak,
            int activePeak,
            int completedPeak,
            long heapPeakBytes,
            double heapPercent,
            long gcCount,
            long gcTimeMillis,
            int uploads,
            int drainFrames,
            long elapsedNanos,
            boolean forwardProgress,
            boolean starvationObserved) {}
}
